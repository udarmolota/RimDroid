# Brief v12 — RimWorld 1.6 on Android now RENDERS (GL via Zink); deterministic crash: zink batch-state = NULL at the present right after Unity unloads its splash scene. What silently kills the batch, and what's the cleanest fix/diagnostic?

## Context (one paragraph)
RimWorld 1.6 (Unity 2022.3.35f1 Linux player, x86_64) runs on Android under box64 with an in-process Java
X11 server. After weeks stuck on Unity's Vulkan present-gate we pivoted to Unity's **OpenGL Core renderer**:
box64 intercepts the player's glX calls (SDL2 is statically linked inside UnityPlayer; its `X11_GL_LoadLibrary`
resolves glX via `glXGetProcAddressARB`) and routes them to **ZFA** — a prebuilt Mesa 25.0.2
(git-06631a8876) library ("libzfa.so", stripped, ~15 MB) exposing desktop GL 4.3 core via **Zink**, presenting
to the Android `ANativeWindow`. Exports: `zfaCreateContext(depth,stencil,compat,major,minor)`,
`zfaMakeCurrent(ctx, ANativeWindow*, w, h)`, `zfaFlushFront()`, `zfaDestroyContext(ctx)`. Present =
`glXSwapBuffers -> zfaFlushFront()`. **This works**: Unity accepts the device ("Renderer: zink Vulkan 1.4
(Adreno 830 (v25.1) (MESA_TURNIP)); Version: 4.3 (Core Profile) Mesa 25.0.2"), and the game shows its loading
screen with animated "Initializing..." — the first visible RimWorld 1.6 frames on Android ever. ~40+
successful presents happen.

## The crash (deterministic, same place every run)
Right after the game prints its banner and Unity unloads the splash scene, the NEXT `glXSwapBuffers` →
`zfaFlushFront()` SIGSEGVs **inside libzfa (Zink)**:

```
Player.log tail:
  RimWorld 1.6.4518 rev93
  [~40 successful swaps earlier; several more right here]
  Unloading 5 Unused Serialized files (Serialized files now loaded: 0)
  Unloading 89 unused Assets to reduce memory usage. Loaded Objects now: 15741.
  Total: 270.78 ms (... MarkObjects: 260.75 ms ...)
  SIGSEGV si_addr=0x90, native pc = libzfa.so+0xcded30
```

Disassembly at the fault (llvm-objdump; nearest export label `trace_screen_create+0x4e1xx`, real fn unknown —
.so is stripped):
```asm
cded10: ldr  x21, [x0]            ; x19 = arg0 (zink context?)
cded18: bl   0xcdea18
cded1c: ldr  x20, [x19, #0x5d8]   ; x20 = ctx->batch state  <-- LOADS NULL
cded20: mov  w8, #0x1
cded28: mov  w10, #0x2a           ; 42 = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO
cded30: strb w8, [x20, #0x90]     ; <== SIGSEGV: write flag into NULL+0x90
cded34: ldr  x0, [x20, #0xa8]     ; would be the VkCommandBuffer
cded40: ldr  x9, [x9, #0x3b78]    ; screen's vkBeginCommandBuffer
cded44: str  w10, [sp]            ; begin_info.sType = 42
cded48: str  w8,  [sp, #0x10]     ; flags = ONE_TIME_SUBMIT
cded4c: blr  x9                   ; vkBeginCommandBuffer(...)
```
So Zink is (re)starting command-buffer recording during the frontbuffer flush and its **batch state pointer
(ctx+0x5d8) is NULL** — it was destroyed/never recreated some time earlier, silently.

**Driver-independent:** with the phone's proprietary driver (Adreno 830, QUALCOMM_PROPRIETARY) the same moment
crashes inside `vulkan.adreno.so qglinternal::vkCmdPipelineBarrier2+0x13c` reading NULL+0xbc (garbage cmdbuf);
with **Turnip** (Mesa 25.1 freedreno, now forced) it crashes as above inside libzfa. Same trigger, same frame.

## What we've ruled out (all verified on device)
- **Our glX bridge state**: full per-call logging shows NO anomaly before the crash — no `glXMakeCurrent(NULL)`
  unbind, no thread change (every glX call + every swap on the same tid), no drawable/display change
  (`glXSwapBuffers(dpy=0x7b18394d50, drawable=0x400004)` constant), ~40 good identical swaps first. The last
  glX ops before the crash are ordinary `glXMakeCurrent` binds alternating between two of Unity's contexts
  (all alias ONE real ZFA context; MakeCurrent → `zfaMakeCurrent(ctx, win, 2340, 1080)` each time, succeeds).
- **Bridge argument marshalling**: earlier we had a real bug (missing emu slot in the wrapper signatures →
  all args shifted by one → our stub wrote 4 zero bytes through garbage pointers → memory corruption). FIXED
  and verified (args now exact: real Display*, real gl_data out-params). The crash below survives that fix.
- **Dynarec**: full-interpreter runs move the crash but that was the (now-fixed) arg-shift garbage varying;
  with correct args the crash is identical under dynarec.
- **Driver**: crashes on both stock Adreno and Turnip (different fault sites, same trigger) — it's Zink state,
  not a driver bug per se.
- MESA_DEBUG=1, MESA_GLSL=errors, ZINK_DEBUG=compact are set: **nothing is printed** before the crash — the
  batch state dies silently.
- MESA_EXTENSION_OVERRIDE already disables (1.5-era list): whole DSA family, internalformat_query(2),
  timer_query, sparse_texture(+2/clamp), blend_equation_advanced(+coherent), OES_EGL_image; forces on
  s3tc/rgtc/bptc. Unknown GL entry points resolved via glXGetProcAddressARB return a shared no-op stub
  (returns 0, writes nothing) or, for 4 known getters, zero/lie-filling stubs.

## Simultaneous events at the trigger moment (X server / app logs)
- Unity destroys its helper X window: `UNMAPWIN win=0x400005` + `DestroyNotify`.
- `ConfigureNotify win=0x400004 2340x1080+0+0` twice (main window, SAME size as always).
- Unity's asset unload pauses the main thread ~270 ms (MarkObjects) — no presents during it.
- A box64 oddity right before (both runs): `FillBlock triggered a segfault at 0x32367000 ... canceling`
  (box64's dynarec block-builder touching its own bridge arena; recovered, possibly noise).
- RimWorld reads its own prefs around this time (its ManagerWindow code may call SetResolution — the
  ConfigureNotify pair suggests a same-size SDL_SetWindowSize / fullscreen dance).

## Questions
1. **What paths in Mesa 25 Zink set/leave `ctx->batch.state = NULL` silently?** Candidates we suspect:
   VK_ERROR_DEVICE_LOST or swapchain/kopper failure during a flush → batch torn down; a failed
   `zink_batch_reference`/reset; context "unrecoverable" flag. Which of these can happen WITHOUT any
   MESA_DEBUG/ZINK_DEBUG output, and which is consistent with the very next flush_frontbuffer crashing at
   `strb 1,[bs+0x90]` (start_batch/begin cmdbuf on a NULL bs)?
2. **Can a GL call made during Unity's mass resource destruction (glDeleteTextures/Framebuffers/... of 89
   assets, possibly a stubbed/no-op entry point among them) put Zink into that state?** Unity 2022 resolves
   more modern entries than Unity 2019 did (we saw glTextureView, glTexStorage2DMultisample resolve —
   present in libzfa or stubbed, unverified). What single missing/no-op GL function would most plausibly
   corrupt zink's batch lifecycle?
3. **Is per-swap `st_manager flush_frontbuffer` (our zfaFlushFront) even the right present for Zink+kopper on
   a native window?** The sibling project's GLFW consumer has `//zfaFlushFront();` COMMENTED OUT in its swap
   path — implying presents happen some other way there (kopper present on internal flush?). Could
   DOUBLE-presenting or flush_frontbuffer-during-teardown be our own fault? What's the canonical swap for a
   kopper-backed winsys: glFlush? st_context_flush(ST_FLUSH_FRONT)? explicit kopper_present?
4. **Sharpest one-run diagnostic:** which env/config would NAME the killer — e.g. ZINK_DEBUG=validation /
   VK_LAYER_KHRONOS_validation (can we even load the validation layer under this embedding?),
   MESA_LOG_FILE + which MESA_DEBUG flags, ZINK_DEBUG=(rp|synchronization|...)? We get ONE cheap log-only
   run per device unlock, so the highest-signal switch matters.
5. **Known issue?** Mesa 25.0.2 zink: any known bug/fix upstream matching "batch state NULL deref at
   begin_command_buffer after device-lost/present failure" (kopper), fixed in later 25.x? (Upgrading libzfa
   is possible but expensive — we'd need to rebuild the custom ZFA winsys against newer Mesa.)
6. Given the ~270 ms main-thread stall right before: can an Android BufferQueue/ANativeWindow acquire fail
   (timeout/abandoned) inside kopper during the FIRST present after such a stall, and does zink handle that
   failure by nulling the batch without logging?

## Assets available
- Full box64 source (we patch freely: all glX bridges are ours; we can add logging anywhere, incl. inside the
  SEGV handler — we already log guest RIP + native PC + dladdr).
- libzfa.so binary only (stripped; llvm-objdump disasm available; no sources on this machine — ZFA winsys
  sources exist in the sibling Zomdroid project's history if rebuild becomes necessary).
- In-process Java X server (full control), GameActivity/ANativeWindow lifecycle (can log surface recreates).
- Ghidra project for UnityPlayer.so (SDL's statically-linked GL code already mapped).
- One tester device on hand (Adreno 830); each run costs a manual unlock, so batched diagnostics preferred.

## Current bet
Something in the splash-scene teardown frame (a stubbed GL entry point, a kopper present failure after the
270 ms stall, or our flush_frontbuffer-as-present pattern) silently kills Zink's batch; the fix is either a
proper stub/extension-disable, handling the surface/present failure, or switching to the canonical
kopper present call. We need the sharpest way to see WHICH.
