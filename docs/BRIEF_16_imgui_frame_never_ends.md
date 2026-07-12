# Brief v7 — RimWorld 1.6 on Android: the load succeeds, but the MAIN thread is stuck in ONE Unity IMGUI frame that never completes → never presents → OOM

## The one-line diagnosis (this is now well-supported, not a guess)
RimWorld 1.6 boots fully under our box64/X11/Vulkan stack. Its async data-load runs and **finishes
cleanly**. The bug is entirely on the **main thread**: it enters the GUI/OnGUI phase of a single Unity
frame and **never leaves it** — `Verse.Root.OnGUI()` is invoked millions of times within one frame, the
frame never reaches EndFrame, so `vkQueuePresentKHR` is never called, `Update()` never runs again, and
the game never advances to the menu. Without a mitigation the un-recycled per-frame GPU memory grows
until the Adreno driver aborts (kgsl OOM). **We need to know why Unity's per-frame IMGUI event loop
never terminates under emulation.**

## Environment
- RimWorld **1.6.4518 rev93**, Unity **2022.3.35f1**, Mono (MonoBleedingEdge).
- x86_64 Linux build under **box64** (dynarec) on Android arm64 (Adreno 8-gen, 11.4 GB UMA).
- Route: in-process Java **X server** → box64 wrapped **libvulkan** → native **Qualcomm/System Vulkan**
  driver; WSI swapped X11→`VK_KHR_android_surface` onto our `ANativeWindow`.
- `-force-gfx-direct` **ON** (single-threaded render). `BOX64_MAXCPU=1` set (ProcessorCount→1).
- Instrumentation: full vk-call interception; a working **Mono-reflection toolkit** (call guest
  `mono_*` via box64 RunFunction — resolve method names, read class static/instance fields, read
  managed strings/delegates); guest backtrace with Mono-JIT frame naming via `mono_pmip`.

## The decisive experiment (last night) — "survival test"
We made `my_vkCmdBindDescriptorSets` **return before the host driver call** once bind count > 15000, so
per-frame GPU memory stops growing and the process can't OOM. Then we sampled `Verse.LongEventHandler`
static state and OS thread states every 200k binds. Results:

| bind #   | `currentEvent`          | R-state threads | RimWorld log advance |
|----------|-------------------------|-----------------|----------------------|
| 200,000  | async event `b__10_1`   | 2 (main+worker) | none past load       |
| **400,000** | **NULL (finished)**  | **1 (worker exited)** | none            |
| 400k–1,800,000+ | NULL forever     | 1               | none — process alive |

Interpretation:
1. The process **survives to 1.8M+ binds** with the skip → the crash was purely un-recycled render
   accumulation, not a hard fault.
2. The async load event (`Verse.Root/<>c:<Start>b__10_1`, RimWorld's first async LongEvent) **completed
   between #200k and #400k and its worker thread exited** (`currentEvent`→null, R-count 2→1). So the
   worker/child thread is **NOT stuck** — it was merely slow under box64 and finished correctly.
3. **After** the load finished, the main thread **keeps spinning `Root.OnGUI` forever** (to #1.8M+) and
   RimWorld never advances. So the OnGUI spin is **independent of the load** — it is the main thread
   stuck in one Unity frame's GUI phase.

## The stuck call stack (main thread, single-threaded render, named via mono_pmip)
```
[0]  vkCmdBindDescriptorSets
[1-10]  UnityPlayer native render  (f3d219 ← f7c4d4 ← f87cc0 ← f87d16 ← a79930 ← f7c1ff ← b2ea64 ←
                                    a3024d ← ac006d ← 75082f  [managed→native ICall])
[11-17] UnityEngine.GUI:DrawTexture  (7 overload frames) → UnityEngine.Graphics:Internal_DrawTexture
[18]    Verse.Root:OnGUI ()
[19]    object:runtime_invoke_void__this__   (Mono invoke wrapper)
[20-21] libmonobdwgc-2.0.so  (mono_runtime_invoke internals)
[22]    <Unity native GUI-event loop caller — NOT yet unwound past the mono boundary>
```
So Unity's native GUI-event dispatcher calls `mono_runtime_invoke(Root.OnGUI)` over and over within one
frame. `Root.OnGUI` just draws the loading/menu backdrop via `GUI.DrawTexture` (normal). The bug is the
**loop that re-invokes OnGUI without ending the frame.**

## Vulkan call census during the storm (per run)
- `vkCmdBindDescriptorSets`: millions (1.44M+ until OOM, 1.8M+ with survival-skip)
- `vkBeginCommandBuffer`/`vkEndCommandBuffer`: 8/8 (balanced), `vkResetCommandBuffer`: 0
- `vkQueueSubmit`: 5 (init only); `vkAcquireNextImageKHR`: 0–1; **`vkQueuePresentKHR`: 0**
- The per-frame dynamic-UBO offset was observed climbing monotonically (never reset) over the sampled
  window → consistent with ONE frame, but we have not yet confirmed it never resets across the whole
  survival run (that's our next cheap probe).

## What is RULED OUT (each tested on-device)
- Window/surface/SDL: surface XID == the healed X window; `SDL_Window.flags` perfect (SHOWN|FOCUS, not
  hidden/minimized); forcing flags changes nothing. Screen-off (win=0x0) confounder is controlled for.
- Threaded render handoff: reproduces identically with `-force-gfx-direct` (single thread).
- Our X server flooding events: an event counter on `XClient.sendEvent` shows **~0 events sent** during
  the storm. So the events Unity processes are NOT coming from us.
- Parallel def-load (the 1.5 self-replicating-`Parallel.ForEach` bug): `BOX64_MAXCPU=1` (verified
  ProcessorCount→1) does NOT help; and we hang BEFORE def-load even starts.
- Child/worker thread handoff: the worker finishes cleanly (see survival test) — not the bug.

## The core question
**Why does Unity 2022.3's per-frame IMGUI event loop never terminate on the main thread under box64?**
Normal Unity flow per frame: Layout event → (input events) → Repaint event → 2–3 `OnGUI` calls → frame
ends → `Update` → present. Here `OnGUI` is invoked millions of times in a single frame. We send ~0 X
events, so either:
- (a) Unity's GUI event source keeps producing events internally (e.g. a continuously re-queued Repaint,
  or `GUI.changed`/`Event.current` handling that loops), or
- (b) Unity's "are there more events?" check is mis-evaluated under emulation and always says "yes" —
  e.g. `Event.PopEvent`, or the X poll it feeds from (`XPending`/`XEventsQueued`/`XNextEvent`) returning
  a wrong nonzero count, or an internal event-queue head/tail index that box64's atomics/memory-ordering
  leaves stale.

## Specific questions for you
1. In Unity 2022.3 **standalone Linux**, what exactly drives the number of `OnGUI` invocations per
   frame? Is the IMGUI event pump fed from a Unity-internal `Event` queue, or does it poll the X
   connection (`XPending`/`XEventsQueued`) each iteration? Which function's return value is the loop's
   termination condition?
2. Is there a known Unity/IMGUI pattern where **one frame** re-enters `OnGUI` unboundedly — e.g. an
   exception thrown in OnGUI that Unity catches and retries, `GUI.changed` forcing repaint, or a
   `EventType.Used`/`ExecuteCommand` re-queue loop? Anything RimWorld's `Root.OnGUI` +
   `LongEventHandler.LongEventsOnGUI` could trigger during the loading screen?
3. If it's (b) — a mis-emulated event-count check — which **libX11 call** would Unity's Linux input pump
   most plausibly use per-iteration, so we can wrap and verify it in box64 returns the correct
   "no more events" (0)? We already emulate the X protocol in our own server; if Unity calls e.g.
   `XPending` and gets nonzero when our server has sent nothing, that's the smoking gun.
4. Cheapest way to distinguish (a) vs (b): we can, from box64, read `UnityEngine.Event.current.type` via
   Mono reflection at each OnGUI, and/or wrap `XPending`/`XEventsQueued`. Which would you instrument
   first, and what result would confirm each branch?
5. Any Unity launch flag / env / player setting that changes the IMGUI/event-pump behavior or forces a
   frame to complete (e.g. disabling continuous repaint, `-disable-gpu-skinning`, event-batching)?

## Assets to act on any answer
- box64 wrapped-Vulkan and wrapped-libX11 — can log/patch any call.
- Mono-reflection from any wrapper: resolve managed method names, read any class field/static, read
  `Event.current` etc.
- Guest backtrace with Mono-JIT naming (mono_pmip); process-survival skip to keep the app alive for
  long live probes.
- Full control of our in-process X server (we see exactly what Unity requests and what we reply).

## Current bet
Leaning (b): Unity's Linux IMGUI/event pump polls the X connection each iteration and box64 (or our X
server's reply framing) makes the "events pending" check never reach zero, so the GUI phase of one frame
loops forever. Confirming with `XPending`/`XEventsQueued` wrapping + reading `Event.current.type` is the
next step. If it's (a), the loop is Unity-internal (repaint re-queue) and we chase the exit condition in
the native GUI dispatcher above frame [22].
