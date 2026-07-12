# Brief v13 — GPU DEVICE_LOST persists on Mesa 25.2.4: the zink-bug hypothesis is DEAD. Confirmed real device loss from work submitted in Unity's splash-unload frame. How do we name the faulting submission?

## Recap (one paragraph)
RimWorld 1.6 (Unity 2022.3.35f1 Linux x86_64) on Android via box64 + in-process X server. Render pivot WORKS:
Unity's GLCore renderer runs over ZFA (Mesa/Zink → Turnip → ANativeWindow via kopper), the loading screen
renders and animates (~40-60 presents). Then, deterministically at the frame where Unity unloads its splash
scene ("Unloading 5 Unused Serialized files / 89 unused Assets", a ~270 ms MarkObjects stall), the GPU device
is LOST. With `MESA_VK_ABORT_ON_DEVICE_LOSS=1` we get a clean SIGABRT (native pc = libc `abort()+0xa0`)
while WAITING in a glFinish we inserted before the frontbuffer flush (our marker `swap_phase=1`).

## What v12's consensus produced (all executed) — and the verdict
- **Rebuilt libzfa on Mesa 25.2.4** (ZFA turns out to be a single ~23KB patch on vanilla Mesa from the
  zomdroid-dependencies repo; we rebased it from 25.0.2 → 25.2.4, restored public gl* exports via
  `libglapi_bridge` — the old static glapi was removed upstream — and added `zfaReleaseCurrent`; built via
  GitHub Actions CI, ~6 min turnaround). Verified on device: `Version: 4.3 (Core Profile) Mesa 25.2.4
  (git-ab462ae6b7)`. **Crash is byte-for-byte identical.** The 25.2.4 zink batch/kopper lifecycle fixes did
  NOT help → the v12 "known Mesa 25.0.2 bug" bet is dead. This is a REAL device loss caused by the workload.
- **glFinish discriminator**: abort fires at `swap_phase=1` (inside the pre-flush glFinish, NOT in
  zfaFlushFront). The poison is GPU work submitted during that frame; the wait merely surfaces it.
  The present path itself is exonerated.
- Also eliminated earlier: threading (fully serialized Zink run — unchanged), redundant zfaMakeCurrent
  rebinds (now skipped), same-size ConfigureNotify (now suppressed), missing GL entry points (new libzfa
  exports 1300 gl* incl. the full sync family), out-params corruption in our bridges (E-signature fix,
  verified args), the Vulkan driver itself (identical moment on Qualcomm proprietary — there it crashed
  inside `vkCmdPipelineBarrier2` — and on Turnip 25.1 and 25.2.4).
- **Why we still don't have the device-lost message**: `MESA_LOG_FILE` is stdio-buffered and `abort()`
  doesn't flush — the file contains only early gralloc lines. Next run switches to default Android logcat
  logging (unbuffered) to capture Turnip's fault report. (A prior "Failed to link shaders / Pipeline create
  failed" logcat lead turned out to belong to ANOTHER app's pid — false lead, disregard.)

## Fresh evidence: full guest backtrace of the aborting present
```
box64(glXSwapBuffers)                      ← our bridge (abort surfaces here, swap_phase=1)
UnityPlayer.so+0x1946ca9                   ← SDL (static) X11_GL_SwapWindow
UnityPlayer.so+0xeeb352
UnityPlayer.so+0xef8976
UnityPlayer.so+0xc07833
UnityPlayer.so+0xc012a9
UnityPlayer.so+0xbf2187 / +0xbf2141 / +0xbf2448
UnityPlayer.so(PlayerMain+0x1731)
```
(Load base 0x3f00000000; we have a fully-analyzed Ghidra project for UnityPlayer.so and can decompile any of
these offsets on request.)

One more oddity, right before the crash in EVERY run, box64 logs:
```
FillBlock triggered a segfault at 0x32367000 from <native pc>
FillBlock at 0x32366ff0 triggered a segfault (state=2, size=2), canceling
```
0x32xxxxxx = box64's BRIDGE arena. Something (likely Mono JIT enumeration or a guest wild-jump) makes box64's
dynarec try to compile code AT a bridge address. It recovers ("canceling"), but it hints the guest may be
executing/patching in odd places around this moment.

## Environment facts
- Device: Adreno 830 (Snapdragon 8 Elite), NOT rooted. Each on-device run costs a manual unlock.
- box64 emulates the game (x86_64→arm64); we control box64 fully (all GL/GLX bridges are ours).
- Zink advertises GL 4.3 core; Unity uses GLCore path (compute shaders available; RimWorld's own shaders +
  Unity builtins load exactly around the crash frame — the splash scene unload is followed by the first
  RimWorld-specific rendering).
- MESA_EXTENSION_OVERRIDE (inherited from 1.5): disables DSA family, internalformat_query(2), timer_query,
  sparse_texture, blend_equation_advanced, OES_EGL_image; forces s3tc/rgtc/bptc on.
- Interesting: in-process mesa probed Android properties `vendor.mesa.spirv.dump.path` /
  `vendor.mesa.vk.enable.pipeline.cache` (so SPIR-V dump plumbing exists in this build).

## Questions
1. **Naming the faulting submission on Turnip/kgsl without root and without the Android Vulkan loader**
   (we dlopen the Turnip ICD directly via a linker-namespace bypass — the system loader and its layer
   machinery are OUT of the loop): what's the sharpest tool?
   - Which `TU_DEBUG` values in Mesa 25.2 actually help localize a GPU fault (`syncdraw`? `flushall`?
     `startup`? `log_skip_gmem_ops`? `rd_output` for cmdstream dumps?) and which are usable on a non-rooted
     kgsl device?
   - Does Turnip print detailed fault info (fault address, IB, opcode) to logcat on VK_ERROR_DEVICE_LOST,
     and does `MESA_VK_ABORT_ON_DEVICE_LOSS` abort BEFORE that report is emitted?
   - Can VK_LAYER_KHRONOS_validation be interposed MANUALLY in a custom-loader setup (chain its
     vkGetInstanceProcAddr between zink and the ICD)? Any known minimal recipe?
2. **What Unity-2022-GLCore work at a scene-unload could plausibly GPU-fault under zink?** Candidates we see:
   compute-shader dispatches (Unity async upload / mip streaming), glInvalidateFramebuffer patterns,
   multi_draw_indirect, drawing with just-deleted resources (zink refcounts should hold them), massive
   glDeleteTextures bursts, Unity's render-target recreation at resolution apply. Which is the usual suspect
   with zink-on-Turnip, and is there a cheap GL-level toggle (extension to hide via MESA_EXTENSION_OVERRIDE,
   e.g. hide compute? hide multi_draw_indirect? hide buffer_storage/persistent mapping?) to bisect by
   subtraction? Persistent-mapped buffers (ARB_buffer_storage) under an EMULATOR (box64) are a special worry:
   guest writes to persistently-mapped GPU memory with relaxed ordering — could stale/garbage vertex+index
   data reach the GPU and fault it? (STRONGMEM=4 barriers are on.) Would hiding GL_ARB_buffer_storage force
   Unity onto safer glBufferSubData paths — and is that a known stability lever for GL-over-emulation stacks?
3. **The box64 angle**: given the FillBlock-in-bridge-arena oddity and that ALL GL args flow through emulated
   code, how would you cheaply trap "garbage args into GL" (e.g. a parameter-sanity shim on glDrawElements /
   glDrawElementsInstanced / glBufferSubData / glDispatchCompute logging when counts/sizes exceed sane
   bounds)? We can wrap ANY GL entry point in box64 — which 5 entry points give the best fault coverage per
   effort?
4. **Timeout vs fault**: can a ~270 ms main-thread stall (no presents, queue idle) followed by a heavy frame
   cause kgsl to kill the context (long-running submission / preemption timeout) rather than a true memory
   fault? How to distinguish on a non-rooted device (logcat kgsl lines? dumpsys? /sys/class/kgsl/kgsl-3d0/
   readable nodes?), and does Turnip surface the kgsl fault reason in its device-lost report?
5. If you recognize the Unity offsets pattern (0xbf2xxx cluster → 0xc0xxxx → 0xeexxxx → SDL swap): is this
   Unity's `GfxDeviceGL::PresentFrame` / end-of-frame path or a RESOLUTION-APPLY path (SetResolution →
   recreate default FBO → present)? RimWorld applies its saved resolution right around this moment. If it's
   resolution-apply: Unity recreating its default framebuffer/backbuffer on GLCore while zink's kopper
   swapchain stays 2340×1080 — a known device-lost trigger?

## Assets
- Full box64 source control (bridges, wrappers, logging, SEGV/ABRT handler with native-pc dladdr).
- Reproducible ~6-min CI rebuild of libzfa (any Mesa version/patch — cherry-picks cheap now).
- Ghidra project with UnityPlayer.so analyzed (can decompile the backtrace offsets).
- One manual unlock per on-device run → we batch diagnostics; highest-signal-per-run advice valued.

## Current plan (pending your input)
Next run captures mesa/Turnip logcat output at the abort (MESA_LOG_FILE removed — it was buffering the
death message). Then, per your answers: TU_DEBUG fault localization vs GL-level extension-subtraction bisect
vs box64 arg-sanity shim.
