# Brief v8 — RimWorld 1.6 on Android: the GAME RUNS; the sole defect is Unity never calls vkQueuePresentKHR per frame (renders offscreen → black screen)

## The corrected one-line diagnosis
RimWorld 1.6 boots, loads, and **runs** under our box64/X11/Vulkan stack — `Time.frameCount` climbs by
tens of thousands, `Update()` ticks every frame, the async load finishes. The **only** defect: Unity
renders every frame but **never calls `vkQueuePresentKHR`**, so nothing reaches the Android surface →
black screen. Unity acquired a swapchain image exactly **once, during PlayerMain init**, and the
**per-frame "end frame → present" path never executes**. We need to know why Unity's per-frame present
is skipped/never reached.

(This supersedes brief v7's "one infinite frame" conclusion — that was wrong. `Time.frameCount` proves
Unity advances many frames.)

## Environment
- RimWorld **1.6.4518 rev93**, Unity **2022.3.35f1**, Mono. **No SDL** (Unity 2022 Linux uses native
  X11 directly — the SDL layer that existed in 1.5 is gone).
- x86_64 Linux under **box64** (dynarec) on Android arm64 (Adreno 8-gen, 11.4 GB UMA).
- In-process Java **X server**; box64 wrapped **libvulkan** → native **Qualcomm/System Vulkan** driver;
  WSI swapped X11→`VK_KHR_android_surface` on our `ANativeWindow` (window handle is valid, non-NULL).
- `-force-gfx-direct` **ON** (single-threaded render). `BOX64_MAXCPU=1`.
- We already install-time byte-patched Unity's **display-count getter → `mov eax,1`** (patch "C") so
  Unity sees exactly one display; without it Unity crashed reading a null display array.

## Hard evidence (frame watchdog, per survival run; sampled via Mono reflection + vk counters)
```
FRAME[bind#1200]   Time.frameCount=75    CB begin=7 end=5  RP begin=0 end=0  submit=1 acquire=1 present=0
FRAME[bind#2500]   Time.frameCount=157   CB begin=7 end=5  RP begin=0 end=0  submit=1 acquire=1 present=0
FRAME[bind#200000] Time.frameCount=12296 CB begin=8 end=6  RP begin=0 end=0  submit=1 acquire=1 present=0
FRAME[bind#1400000]Time.frameCount=46362 ...                                  submit=5 acquire=1 present=0
```
- **`Time.frameCount` climbs fast** (75 → 46362) → Unity's PlayerLoop runs, many frames, `Update` each.
- **`present=0` always; `acquire=1` exactly once; swapchain created OK** (extent 2340x1080, fmt 37,
  minImages 4, present mode 2 = FIFO, clipped).
- `Event.current.type` at every OnGUI = **Repaint** (=7) — Unity re-renders the GUI unthrottled because
  present never paces it.

## The single acquire's backtrace (it is INIT-only, not per-frame)
```
vkAcquireNextImageKHR (libvulkan+0x21e98)
 ← UnityPlayer.so +f693cf ← f8d519 ← f8ff5f ← f8fe03 ← f703aa
 ← 1661b12 ← b0a76a ← 1662f06 ← c02664
 ← PlayerMain +1896 (e65146)
```
So the one acquire happens inside **PlayerMain's one-time graphics/swapchain setup**, NOT in a per-frame
render. Per frame there is **no** acquire, **no** frame-final submit, **no** present. The
1661b12 / 1662f06 frames sit in the **ScreenManager/GfxDeviceVK** region we've partly mapped (near the
render command dispatcher at 1663a3f and command handler 166cb80). Unity **has** the `vkQueuePresentKHR`
function pointer (we located its slot in Unity's vk dispatch table) but never invokes it.

## What is RULED OUT (each tested on-device)
- **X layer / event pump**: during the render storm the X socket is IDLE — poll/read/recv counters are
  frozen, `XNextEvent`/`XPending` not called. A wrapped `XClient.sendEvent` shows our server sends ~0
  events. So it's not X-event-driven and not our server flooding.
- **POLLOUT false-readiness**: early poll showed POLLOUT-ready but it stops before the storm; not the
  cause.
- **The load / worker thread**: the async LongEvent (`Verse.Root/<>c:<Start>b__10_1`) completes cleanly
  (`currentEvent`→null, worker thread exits) — it was slow, not stuck. Not the bug.
- **"One infinite frame"**: disproven by climbing `Time.frameCount`.
- **Window/surface validity**: `ANativeWindow` non-NULL; swapchain creates fine at 2340x1080. Screen-off
  (which makes the window NULL) is controlled for — all recent runs are screen-ON.

## The core question
**Why does Unity 2022.3 (Vulkan, Linux, single-threaded `-force-gfx-direct`) render every frame but never
execute the per-frame present (`vkQueuePresentKHR`)?** The game logic runs; only the swapchain
present/flip is missing. Candidate mechanisms:
1. Unity decided at init (or checks per-frame) that the window/display is **not presentable** and put the
   GfxDevice into a **render-offscreen / no-present** mode. (We patched the display *count*; is there a
   *second* presentability/display-enabled flag?)
2. **`vulkanEnableLateAcquireNextImage`**: Unity renders to a staging image and acquires+presents only at
   frame end; if that frame-end present-trigger never fires under our stack, it renders forever without
   presenting. The single init acquire fits a late-acquire "probe."
3. The per-frame present lives on a **render thread** that our `-force-gfx-direct` (single-thread)
   suppresses, so present is never scheduled. (We forced single-thread for GL-context reasons in 1.5;
   1.6 is native Vulkan and may need threaded rendering for present. NOTE: threaded was only ever tested
   with the screen OFF = invalid window; never retested screen-ON.)
4. A box64 dynarec miscompile of the specific branch in ScreenManager/GfxDeviceVK that reaches present.

## Specific questions for you
1. In Unity 2022.3 **standalone Linux Vulkan**, what gates the per-frame present? Is there a
   `ScreenManager`/`GfxDeviceVK` "display active / window visible / can present" boolean that, if false,
   makes Unity render the frame but skip `vkQueuePresentKHR`? What sets it — an init probe, an X11
   visibility/map query, `vkGetPhysicalDeviceSurfaceCapabilitiesKHR.currentExtent`, or
   `vkGetPhysicalDeviceXlibPresentationSupportKHR`?
2. Does `-force-gfx-direct` (single-threaded render) change WHERE/WHETHER present is issued in 2022.3?
   Could present be skipped entirely in direct mode if the device expects a render thread? Is it worth
   retesting **threaded** (drop `-force-gfx-direct`) with a valid on-screen window?
3. `vulkanEnableLateAcquireNextImage`: how is it read at runtime, and is there any launch flag / env /
   boot.config key (not just the serialized PlayerSettings bool) to disable it? If we can only flip the
   serialized bool, where in `globalgamemanagers` (PlayerSettings block) does it live?
4. The one acquire comes from PlayerMain init (chain c02664 ← PlayerMain). Where in that init does Unity
   decide the present strategy, and is there a known symbol/string to anchor disasm (e.g. "GfxDeviceVK",
   "PresentFrame", "AcquireNextImage", "swapchain", "ScreenManagerLinux", "no display")?
5. Cheapest decisive probe to tell #1 vs #3 vs #2 apart? We can: wrap any vk/libX11 call; read any Mono
   field/static; byte-patch UnityPlayer; find/patch Unity's vk dispatch-table present slot; take the
   guest backtrace at any wrapped call.

## Assets to act on any answer
- box64 wrapped Vulkan + libX11 + libc (poll/read/recv) — log or patch any call.
- Mono reflection from any wrapper (read `Time.frameCount`, `Event.current`, any class field/static).
- Guest backtrace with Mono-JIT frame naming (mono_pmip); a survival-skip that keeps the app alive for
  long live probes; frame-watchdog summary (frameCount + all vk counters).
- Install-time UnityPlayer byte-patching (2 patches already shipped); located Unity's vk dispatch-table
  present slot; a mapped ScreenManager/render-dispatcher region (1661b12/1662f06/1663a3f/166cb80).

## Current bet
Either (1) Unity is in a persistent render-offscreen/no-present mode from an init-time presentability
decision (a second display/visibility flag beyond the one we patched), or (3) `-force-gfx-direct`
suppresses the present path and threaded rendering (screen-ON, never yet tested) would restore it. (3)
is the cheapest test — one flag, no disasm. If (3) fails, disasm the ScreenManager present-decision to
find the gate (1), reusing the patch-C precedent.
