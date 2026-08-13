# Brief: MobileGlues texture corruption in two-threaded mode

## Symptom

RimWorld 1.6 runs through MobileGlues on Android/GLES. With the experimental
`RIMDROID_GLT_THREADED=1`, some runs show a random broken texture: one object or
atlas region becomes a solid blue/red patch. The victim is not stable between
runs. The rest of the scene remains usable.

This is not yet a proven MobileGlues shader bug. RimDroid currently violates
several assumptions that its own GL shim explicitly makes about single-threaded
rendering.

Reference report:
`rimdroid_report_12082026_1416.zip` (TECNO LJ7, Mali-G615 MC2, Android 15).

## What is confirmed

### 1. The flag really enables Unity's second rendering thread

`GameInstance.java` removes `-force-gfx-direct` when
`RIMDROID_GLT_THREADED=1` is present. The default translator path is deliberately
single-threaded.

Relevant code:

- `app/src/main/java/com/rimdroid/game/GameInstance.java:131-160`

### 2. Unity creates multiple logical GLX contexts, but RimDroid aliases all of them to one EGL context

The report contains three logical context creations. They all name the same real
translator context:

```text
glXCreateContext #2 -> handle 0x707249d3a0 (EGL-translator 0x707247d3a0)
glXCreateContext #3 -> handle 0x70724ad3a0 (EGL-translator 0x707247d3a0)
glXMakeCurrent: alias switch, rebind skipped
```

`wrappedlibgl.c` returns distinct fake handles but maps every one to the single
global `g_egl_context`. It also skips a real rebind for an alias switch.

Relevant code:

- `box64/src/wrapped/wrappedlibgl.c:57-80`
- `box64/src/wrapped/wrappedlibgl.c:124-142`
- `box64/src/wrapped/wrappedlibgl.c:151-185`

This does not reproduce GLX shared-context semantics. Unity expects separate GL
state per context with shared objects. We currently give it one state machine
under several names. A single EGL context also cannot legally be current on two
threads simultaneously.

### 3. Texture upload scratch buffers are process-global and documented as single-thread-only

The MobileGlues compatibility shim has three reusable static buffers:

- upload bounce buffer: `wrappedsdl2.c:762-823`;
- DXT/S3TC -> RGBA decode buffer: `wrappedsdl2.c:901-918`;
- optional ETC2 encoder buffer: `wrappedsdl2.c:1062`.

The source comments explicitly say reuse is safe because the translator path is
single-threaded. That premise becomes false under `RIMDROID_GLT_THREADED=1`.

If two upload wrappers overlap, thread B can overwrite/reallocate the buffer
after thread A fills it but before/during A's real GLES upload. The resulting
victim depends on scheduling, which matches the random texture identity.

### 4. Texture binding bookkeeping is also global

`rd_active_unit` and `rd_tex2d_bound[256]` are process-global:

- `box64/src/wrapped/wrappedsdl2.c:415-424`;
- updated by `rd_glActiveTexture` / `rd_glBindTexture` around lines 626-639.

They are neither per-thread nor per-logical-context. An interleaving can make an
upload, shrink decision, FBO classification, or decode land on the wrong texture
ID even if the pixel buffer itself remains intact.

### 5. The Mali path heavily exercises the unsafe decoder

The report reaches at least 17,152 software S3TC decode/upload operations. Mali
does not expose the desktop DXT path, so this shim is hot during content loading:

```text
RIMDROID GLT S3TC-DECODE enabled
...
RIMDROID GLT S3TC-DECODE ... (n=17152)
```

That gives a large opportunity window for an occasional overlap.

### 6. HOLEY SOURCE exists, but is not proof of the two-thread race

The same report logs mip uploads whose guest source range is mostly unreadable;
the bounce shim zero-fills those pages:

```text
RIMDROID GLT-BOUNCE HOLEY SOURCE ... bytes unreadable and zero-filled
```

This can independently create empty/flat mip content. However, lazy or genuinely
untouched guest pages can also be semantically zero, so this message alone does
not prove corruption. It becomes the leading suspect only if the artifact also
reproduces with `RIMDROID_GLT_THREADED` disabled.

## Current confidence

The code contains real thread-safety defects. It is not yet proven which one
caused the reported red/blue texture:

1. scratch-buffer overwrite/realloc race - high probability;
2. one real EGL context masquerading as multiple GLX contexts - high structural risk;
3. global texture-unit/binding state crossing contexts - high structural risk;
4. HOLEY SOURCE zero-fill independent of threading - secondary candidate;
5. a MobileGlues shader/format bug - still possible, but currently less specific.

The color of the patch cannot identify the asset. A random solid color is
consistent with wrong or partially overwritten upload data, undefined content,
or an atlas upload reaching the wrong texture.

## Cheapest decisive tests

### Test A: single-thread oracle

Run the same save and content without `RIMDROID_GLT_THREADED=1` several times.

- Artifact disappears: investigate RimDroid context/scratch races first.
- Artifact remains: investigate HOLEY SOURCE, format conversion, and MobileGlues.

This is a correctness oracle, not the final performance configuration.

### Test B: collision detector around texture uploads

Add an atomic `in_upload` guard around the complete body, including the final
native call, of:

- `rd_glTexImage2D`;
- `rd_glTexSubImage2D`;
- `rd_glCompressedTexImage2D`;
- `rd_glCompressedTexSubImage2D`.

Record owner TID, contender TID, texture ID, level, dimensions, and a collision
counter. Do not log every normal upload. One collision is enough to prove that
the static buffers are unsafe in the active workload.

For a stronger A/B, serialize only those four complete wrappers with one mutex.
If artifacts disappear, the upload race is causally implicated. This is a probe,
not necessarily the production architecture.

### Test C: thread-local scratch A/B

Convert only `buf/cap`, `dbuf/dcap`, and `ebuf/ecap` to `_Thread_local`. No global
GL mutex, no context rewrite. This is a small patch and should have negligible
steady-state overhead.

- Fixes artifact: scratch reuse was the immediate cause.
- Does not fix it: proceed to context and binding-state repair.

Adding canaries or a cheap sampled hash before and immediately before the real GL
upload can provide extra evidence, but the overlap counter is cheaper and clearer.

## Repair options

### Level 1: safe production workaround

Keep MobileGlues on `-force-gfx-direct` and do not expose threaded mode to users.
This preserves the architecture assumed by the current shim. It costs the FPS
that the second rendering thread might have provided.

### Level 2: harden upload machinery

Make every reusable conversion/bounce buffer thread-local. Move upload counters
to atomics or thread-local counters. Replace the global texture bookkeeping with
state keyed by the current logical GL context.

This is relatively small and likely fixes random upload corruption, but it does
not make the one-EGL-context model correct.

### Level 3: proper threaded GLX/EGL bridge

Represent each Unity GLX context with a real EGL context:

- first context creates the share group;
- later contexts honor the GLX `share` argument using `eglCreateContext(...,
  share_context, ...)`;
- keep per-context active texture, bindings, pixel-store and other shadow state;
- bind the exact real context in `glXMakeCurrent`;
- ensure one EGL context is current on at most one thread;
- use the window surface only for the presenting context; worker contexts may
  need a pbuffer or surfaceless binding;
- destroy the corresponding EGL context instead of treating every destroy as a
  no-op.

This is the real solution if threaded rendering is expected to ship. It is a
medium-sized bridge rewrite and must be validated against both MobileGlues and
NG_GL4ES behavior.

## Separate performance result: state-cache experiment

The attempted deduplication of `glActiveTexture` and `glBindTexture` was a dead
end and has been rolled back. Once connected to the real direct-symbol path, the
telemetry showed:

- most 300-frame windows: 0% removable calls;
- best short startup window: 254 / 11,887 calls, about 2%;
- steady gameplay: typically zero, occasionally 90 duplicates among roughly
  50,000 calls.

Unity already avoids redundant texture state well. The wrapper overhead could
therefore reduce FPS. This result should not be used as evidence for or against
the two-thread texture race.

## Recommended next move

Do Test B and Test C before touching MobileGlues shaders or attempting a complete
EGL bridge rewrite. They can prove or clear the most concrete race in one short
load. Meanwhile keep threaded mode opt-in/off by default. If TLS buffers remove
the random texture but later context-dependent corruption remains, implement
per-context state and then real shared EGL contexts in that order.
