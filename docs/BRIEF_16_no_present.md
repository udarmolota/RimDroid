# Brief v6 — RimWorld 1.6 on Android: load completes, but ZERO present → OOM death. SDL fully exonerated.

## TL;DR of the shift
Previous briefs asked *"why no picture / why is the window offscreen"*. **That framing is dead.**
Verified on-device this session:
- The Vulkan surface is created on the **correct** X11 window (`0x400004`, the one we heal).
- SDL_Window flags are **perfect** the whole time (`SHOWN | INPUT_FOCUS | MOUSE_FOCUS`, size 2340x1080,
  a single window). Force-adding the `VULKAN` flag every 20k frames changes nothing.
- Scene load **completes** (`Loaded Objects now: 15741`, "Unloading 89 unused Assets…").

So the gate is **not** window visibility. The real question is now:

> **Why does Unity's Vulkan backend record 1.4M+ commands into ONE command buffer during first-scene
> load and NEVER submit/present it — until per-frame driver memory hits the ~2.8 GB kgsl ceiling and
> the Adreno driver aborts (`kgsl_sharedmem_alloc(32KB) failed` → SIGABRT inside
> `qglinternal::vkCmdBindDescriptorSets`)?**

## Environment
- RimWorld **1.6.4518**, Unity **2022.3.35f1**, Mono (MonoBleedingEdge, not IL2CPP).
- x86_64 Linux build under **box64** (dynarec) on Android arm64.
- Graphics route **A2**: in-process Java X server → box64 wrapped `libvulkan` → **native Turnip/System
  Adreno driver**; WSI swapped X11→`VK_KHR_android_surface` onto our `ANativeWindow`. No GL/Zink.
- Device: Adreno 8-gen, 11.4 GB UMA RAM (kgsl GPU memory **is** system RAM).
- `-force-gfx-direct` **removed** for 1.6 → Unity uses **threaded** render (`GfxDevice threaded=1`).

## Hard evidence (all logged on device)
1. **Load advances to end of first scene**, then dies right at the threshold of RimWorld managed code
   (no RimWorld `[…]` log lines yet).
2. Per full run, the Vulkan call census is:
   - `vkCmdBindDescriptorSets`: **1.4M+** and climbing until death
   - `vkBeginCommandBuffer`=9, `vkEndCommandBuffer`=8, `vkResetCommandBuffer`=**0**,
     `vkCmdBeginRenderPass`=8
   - `vkQueueSubmit`=**5** (all during init; none during the bind storm)
   - `vkAcquireNextImageKHR`=**0 or 1** (stochastic across runs), `vkQueuePresentKHR`=**0**
3. The bind storm is a **repeating pattern** (period ≈ 2000 binds: 3× {layoutA, dyn=2} + 1× {layoutB,
   dyn=1}) — i.e. the main thread keeps posting the **same frame's** commands over and over.
4. **Guest backtrace** of every bind (box64 `my_backtrace_ip`), UnityPlayer.so offsets:
   ```
   vkCmdBindDescriptorSets
     ← +0xf3d219 ← +0xf7c4d4 ← +0xf87cc0 ← +0xf87d16 ← +0xa79930 ← +0xf7c1ff
     ← +0x166cb80  (a command handler)
     ← +0x1663a3f  (render-thread command DISPATCHER)
   ```
   The dispatcher at `+0x1663a3f` is a big `switch` on `(code - 0x2711)` with ~230 handlers
   (jump table `int32[]` at `+0x2d5e38`, table-relative; dispatcher fn body at `+0x1663ad0`).
   So this is Unity's **client→render command-stream replay** loop, draining a ring the main
   thread fills. It drains binds but the "submit/present" command never arrives (or is gated off).
5. Memory at death: `MemAvailable ~1.3 GB` of 11.4 GB; process PSS ~9 GB. Unity's own "Vulkan - Out of
   memory" pre-check was silenced earlier by inflating `memoryHeaps[].size` to 8 GB (heap[1] really
   reports 4095 MB) — that fix is correct and stays; the death is now the **real kernel kgsl limit**,
   not Unity's self-check.

## What we ruled out this session
- Window identity / surface-on-wrong-window (Opus's bet). Surface XID == healed window.
- SDL flags SHOWN/HIDDEN/MINIMIZED/VULKAN (ChatGPT's bet). Flags perfect; forcing them does nothing.
- `_NET_WM_STATE_HIDDEN` latch — we never set it; window is `NormalState`.

## Dead-end noted (so nobody repeats it)
String-anchored disassembly does **not** work in this UnityPlayer.so: xrefs to
`"Gfx.WaitForPresentOnGfxThread"` (@file 0x26a7eb) and `"vulkanEnableLateAcquireNextImage"`
(@0x1b98a3) exist as **neither** rip-relative disp32, **nor** absolute pointers, **nor** `.rela.dyn`
addends. Unity references its shader/keyword strings via some indirected table we haven't cracked.

## Current in-flight probe (not yet landed)
Trying to locate Unity's function-pointer slot holding the `vkQueuePresentKHR` bridge, by:
- saving the guest bridge address returned from `my_vkGetDeviceProcAddr("vkQueuePresentKHR")`, then
- scanning UnityPlayer data+BSS for a slot equal to it → its rip-relative xrefs = the present
  call-sites → disassemble the "should we present this frame?" branch.

**It didn't fire**: no `guest bridge vkQueuePresentKHR=` line appeared, meaning Unity resolved present
**not** via `vkGetDeviceProcAddr`. Next step is to also hook `vkGetInstanceProcAddr` (and check whether
Unity caches the pointer from the swapchain-extension dispatch instead).

## Questions for you
1. **Is a multi-hundred-thousand / million-command single command buffer during Unity 2022.3 first-scene
   load NORMAL** (just survived on desktop by large VRAM + eventual submit at scene end), or is it a
   **stall/retry loop** — Unity's render thread spinning the same frame because it's waiting on an event
   (a fence, a semaphore, a `WaitForPresentOnGfxThread`, a GfxJob completion) that never completes under
   our box64/threaded setup? The repeating identical-frame pattern strongly suggests a **retry/spin**,
   not honest accumulation.
2. If it's a **wait that never completes**: what does Unity 2022.3's threaded GfxDeviceVK wait on at the
   end of the *loading* frame before it will `Submit`+`Present`? Candidates we can probe: a
   client-thread → render-thread semaphore, `Gfx.WaitForPresentOnGfxThread`, a GfxJobs fence, the
   `LongEventHandler` main-thread pump. Which single Vulkan/pthread primitive, if it silently returns
   the "wrong" value under emulation, would make the render thread **re-enqueue the same frame instead
   of submitting**?
3. Would forcing **`-force-gfx-direct` back ON** (single-threaded render) plausibly avoid this — i.e. is
   the bug specifically in the **threaded** client/render hand-off? (We turned it OFF for 1.6 believing
   threaded rendering was required so frames present during the blocking load; but if no present happens
   anyway, single-thread may be strictly safer and would collapse the two-thread race.)
4. Any known Unity env var / launch flag to **cap or flush** the render command ring, disable late-
   acquire (`vulkanEnableLateAcquireNextImage`), or force a present per N frames during
   `LongEventHandler` loads?
5. Cheapest **oracle** to distinguish "honest accumulation" vs "spin": we can, from box64, at each Nth
   bind read the guest render-thread's wait state. What specific thing should we log to prove it's
   blocked-waiting vs freely-looping? (e.g. correlate bind-rate with a `pthread_cond_wait` /
   `vkWaitForFences` / `sem_wait` that returns immediately every iteration.)

## Assets we have to act on any answer
- box64 wrapped-vulkan interception of **every** vk call (add logging/patching at will).
- Working guest backtrace from any wrapper (`my_backtrace_ip` + `my_backtrace_symbols`).
- Guest-memory read/patch from any wrapped call (note: `getProtection` false-negatives on guest ELF
  BSS — use `msync(page,4096,MS_ASYNC)==0` to test mapped-ness).
- Install-time UnityPlayer.so byte-patching (2 patches already shipped) for a last-resort branch patch.
- Full control of the X server, the Android Surface, and box64 dynarec source.
