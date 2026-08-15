# Brief: MobileGlues threaded red textures — round 2 (2026-08-13, evening update)

Round 1 (`BRIEF_mobileglues_threaded_texture_race.md`) ended on "some race inside MobileGlues".
Round 2 killed that and eight more hypotheses. **Everything below is now verified from logs**, not
from recollection: the box64 shim's output turns out to land in Unity's own `Player.log` (Unity
captures the process's stdout), and we now pull both that and `rimdroid.log` off the device
directly over wireless adb, so every claimed knob state is checked before the result is believed.

That check mattered: two earlier "refutations" were invalid — one run had `MUTLICTX` misspelled,
another `MULTCTX`, and a third never received the variable at all. Those three experiments have
been re-run correctly and are reported below with their real outcomes.

---

## 1. The symptom (unchanged)

RimWorld 1.6 on MobileGlues, Unity's **threaded** renderer (`RIMDROID_GLT_THREADED=1`, which does
exactly one thing: it stops passing `-force-gfx-direct`):

- Saturated **red patches** over world sprites — plants, grass, rocks, items.
- **Zoom-dependent**: zoom out → red everywhere; zoom in → the same area is clean. Now proven
  mechanically, not just correlated: forcing non-mipmapped minification removes the red entirely
  (§4.1), so the corruption lives in mip levels ≥1 while level 0 is intact.
- **Thread-dependent**: single-threaded is always clean, same save, same build, same session.
- Threading is worth 2-3x fps (11-23 single vs 33-55 threaded @ speed 2), so disabling it is the
  status quo we are trying to escape.
- On the Zink/ZFA renderer the same game, same box64, same GLX bridge, same threaded renderer has
  **never** shown this. MobileGlues is the only differentiator.

## 2. The decisive new datum: clean vs red trace diff

Same save, same build, same session, one variable changed (`THREADED`). Both `Player.log`s pulled
from the device and compared:

| | clean (single) | red (threaded) |
|---|---|---|
| DXT→ETC2 transcodes | **16896** | **16896** |
| S3TC decode samples | 75 | 75 |
| big `glTexImage2D` (4096x2560/4096x2048 RGBA8) | 36 | 38 |
| upload levels / sizes / formats | identical | identical |
| `glXMakeCurrent` calls | 11 | 47 |
| threads issuing GL | **1** (tid 2747) | **2** (tid 29585 / 29685) |
| real contexts in use | 3, one per logical, each on the one thread | **1 real context taken in turns by both threads** |

Both runs had `RIMDROID_GLT_MULTICTX=1`: the bridge created a real shared EGL context per logical
GLX context in both. The differing context count is Unity's own behaviour, not a configuration
difference — single-threaded it spreads its work over the three logical contexts on one thread,
threaded it settles on one of them and hands it back and forth between two threads.

**Unity does not change what or how it uploads when threading is on.** Same textures, same mip
levels, same source pointers, same volume. The only difference in the entire trace is that one real
GL context is made current alternately on two threads. So the corruption is not in *what* we upload
but in *where it lands* — or in how it is sampled.

## 3. Refuted, each with its evidence

1. **Race on our scratch buffers** — made every staging buffer `_Thread_local`. Still red.
2. **Two threads inside our upload wrappers at once** — collision detector wraps the whole body of
   all four upload shims, logs any overlap. **Zero collisions** in a corrupted session.
3. **Driver-level context aliasing** (N logical contexts on 1 real context) — "Level 3" gives a real
   shared EGL context per logical context. multictx + single-thread = **clean**; multictx +
   threaded = red.
4. **A race inside MobileGlues' globals** — forked MG with a global `std::recursive_mutex` taken at
   every GL entry point. Disassembly confirms the unlock is at function exit, i.e. the whole entry
   body was serialized. Still red, fps unchanged.
5. **Cross-context write visibility** — `glFinish` after every texture write (2D, 3D, compressed,
   copies, mipgen). Still red.
6. **MobileGlues never saw our EGL contexts** (its own `gl/texture.cpp` documents that untracked
   contexts share one fallback state record) — routed context create/make-current/destroy/present
   through MG's own `egl*` exports, including the make-current in the forked child where the game
   actually renders. Log confirms the routing and both worker contexts. Still red.
7. **Our mip-shrink bookkeeping picking the wrong texture** — `RIMDROID_TEX_SHRINK=0`, verified
   applied in the log. Still red.
8. **Our DXT→ETC2 conversion / the PBO hole in it** (Codex's round-2 candidate: the transcode path
   treats `data` as a client pointer with no `PIXEL_UNPACK_BUFFER` check, unlike the bounce helper
   which does check) — the trace diff in §2 settles it: the conversion runs identically in the clean
   run, and Unity never switches to a PBO upload path when threading is on. **The PBO hole is a real
   bug** (it will corrupt any device that needs the conversion the moment Unity does use a PBO) and
   is queued for a fix, but it is not this.
9. **Process-wide GLX current-context state** (Codex's other find, independently confirmed: zero
   thread-local storage in `wrappedlibgl.c`) — current context, "have current", and the context slot
   are now `__thread`; display/drawable deliberately stay shared, since the bridge fakes exactly one
   display and one drawable. Also added a never-rate-limited log line for a refused make-current and
   for a skipped present. Result: **no refused binds occur at all** (so the "thread left with no
   context, its GL calls discarded" mechanism does not fire), and still red.
10. **MobileGlues skipping a bind its shadow believes redundant** — `RIMDROID_GLT_REBIND=1` forces
    `glBindTexture(0)` + `glBindTexture(tex)` before every 2D upload, a pair no shadow can skip.
    Verified engaged in the log. Still red. (Incidentally fps peaked at 55.)

## 4. Open, in the order we rank them

1. ✅ **ANSWERED — the damage is in the mip levels, and only there.** `RIMDROID_GLT_NOMIP=1` forces
   every minification filter to a non-mipmapped one, so the GPU samples level 0 at any zoom.
   Threaded, same save: **no red at all**, image otherwise correct, fps between single-threaded and
   the red threaded run. So level 0 arrives intact and levels ≥1 are what is broken.
   This is the sharpest fact we have, and it makes the question concrete:

   > Every mip level is uploaded by the game as its own `glCompressedTexImage2D`/`glTexImage2D`
   > call, and §2 shows the clean and red runs issue **exactly the same 16896 level uploads** with
   > the same sizes and formats. The same bytes are submitted either way. What corrupts only the
   > non-zero levels when the context alternates between two threads?

   Candidate mechanisms we have not yet separated: the level uploads are silently dropped (the data
   never lands, leaving undefined storage); they land on the wrong level or wrong texture; or they
   land correctly and something later invalidates or redefines the chain. A read-back probe
   (attach level N to an FBO, `glReadPixels` a texel, compare with the source) would separate these
   in one run and is the next instrument we would build.
2. **The bundled MobileGlues may predate its own per-context refactor.** Reviewer note (2026-08-13)
   proposed that MG holds `TextureUnits` / `CurrentTextureUnitIndex` process-wide, which would fit
   every symptom. In MG main as of 2026-08-09 that is no longer true — the globals are gone,
   replaced by a per-context record selected at `eglMakeCurrent` (`mg_texture_bind_context`), which
   is exactly the fix that reviewer prescribes. But per-context state only engages for contexts MG
   *knows about*, and our matrix has an untested cell: the bundled 2.0.0 lib was tested with and
   without EGLTRACK (red both times), and a build from current main was tested only WITHOUT it (the
   global-lock experiment, before EGLTRACK existed — so its per-context state fell back to the one
   shared default record and it behaved like the old design). **Current main + tracked contexts +
   threaded has never been run.** That test is in flight.

3. **MG state that is `thread_local` but not re-pointed on make-current.** An audit of MG's
   `thread_local`s shows most are bound per context at make-current (`g_tc`/`g_tg`, `gl_state`, FBO,
   FSR) or explicitly guarded by an owner-context id (`g_basevertex_ibo`). The DSA wrapper's binding
   stacks and `g_resolved_program` are per-thread caches; they look correctly scoped on paper, but a
   context that migrates between threads is precisely the case none of them were designed for.
3. **MG's redundant-call elimination against a migrating context.** With tracked contexts MG *does*
   skip driver calls its shadow considers unnecessary (untracked contexts are handled
   conservatively). Its shadow follows the context; the driver state follows the context too — but
   the two are updated from different threads in turn. #10 tested this for texture bindings only.

## 5. Questions

1. Given §2 — identical upload work, one context alternating between two threads — and §3, what
   mechanism is still standing?
2. In MobileGlues, what state is consulted per *draw* (not per upload) that could be stale for a
   context that changed threads since it was written?
3. Is there anything in Unity 2022.3's threaded GLCore that changes *sampling* rather than upload:
   different sampler objects per thread, LOD bias, or texture parameters applied from the render
   thread while the loader thread owns the context?
4. Zink/ZFA survives the identical bridge and the identical migration. What does a desktop-class GL
   driver do at make-current that a GLES translation layer would have to emulate by hand — and could
   the missing piece be exactly what corrupts minified sampling?

## 6. Reference: code pointers

| what | where |
|---|---|
| GL entry shims, upload wrappers, collision probe, SYNCUP/REBIND/NOMIP probes | `box64/src/wrapped/wrappedsdl2.c` |
| DXT decode + ETC2 transcode call sites (and the missing PBO check) | same file, `rd_glCompressedTex(Sub)Image2D` |
| ETC2/EAC encoder (own TU, -O2) | `box64/src/wrapped/rd_etc2.c` |
| GLX→EGL bridge, multictx, per-thread current-context state | `box64/src/wrapped/wrappedlibgl.c` |
| EGL context factory, EGLTRACK routing, child rebind after fork | `app/src/main/cpp/rimdroid.c` |
| env knob plumbing, ETC2/shrink defaults | `app/src/main/java/com/rimdroid/GameLauncher.java` |
| threaded vs `-force-gfx-direct` | `app/src/main/java/com/rimdroid/game/GameInstance.java` |

## 7. How to read our logs (for whoever gets this next)

- `rimdroid.log` (app side) — the launch header echoes the extra-env string **and resolves each
  variable through `getenv`**, so a typo shows up as a variable that "resolved" but does nothing.
  Context creation/destruction and the EGL routing line live here.
- `Player.log` (`<instance>/unity3d/Ludeon Studios/RimWorld by Ludeon Studios/`) — everything box64
  prints, because Unity captures stdout. Texture uploads, transcode counters, GLX bridge trace.
- Every probe prints a one-line "on" banner when it engages. If the banner is absent, the
  experiment did not run — that check is now mandatory before any result is trusted.
