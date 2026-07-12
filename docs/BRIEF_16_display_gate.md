# Brief v11 — GATE LOCALIZED: Display.main is half-init (system/rendering = 0×0) from an early SDL display-probe failure; Unity never composites/presents. We CAN byte-patch UnityPlayer.so / game files — what's the cleanest patch?

## The precise finding (this is the endgame)
RimWorld 1.6 boots and runs under our box64/X11/Vulkan stack. Our WSI is proven working (a synthetic
clear+present we injected turned the Android surface magenta). The main render display is correct:
`Screen.width/height = 2340×1080`, swapchain created at 2340×1080, Unity renders every frame (draws climb,
`Time.frameCount` → 40k+, `Update` runs, async load completes).

**But Unity never composites a full-screen frame and never calls `vkQueuePresentKHR`.** We confirmed via
Mono reflection the smoking gun:
```
Screen                    = 2340×1080        (correct — from window/surface)
Display.main.active        = 1
Display.main.systemWidth   = 0   ← BROKEN
Display.main.renderingWidth = 0  ← BROKEN
```
So the **multi-display API object (`Display.main`) is half-initialized**: active, but its resolution
fields are 0. This is the classic split (Screen works, Display broken).

### Root of the 0×0
Player.log, very early (before engine init):
```
Error getting num native displays: Video subsystem has not been initialized   (×3)
Desktop is 0 x 0 @ 120 Hz
```
Later (line 773), the display enumerates correctly:
```
Desktop is 2340 x 1080 @ 60 Hz     (this is OUR RandR — 60Hz = our mode)
InitializeOrResetSwapChain 2340x1080
```
So Unity's EARLY display probe fails ("SDL video subsystem not initialized" — it runs before SDL_Init(VIDEO)
completes), caching Display.main.system/rendering = 0. The LATE, correct enumeration populates the render
display (Screen/swapchain) but does NOT refresh Display.main. Our **install-time byte-patch "C"** (a
UnityPlayer getter forced to return 1, added long ago to stop a null-display-array crash) appears to FREEZE
this half-init state.

Our X server is fully correct: it comes up at 2340×1080, advertises + fully answers RandR 1.3
(QueryVersion/GetScreenResourcesCurrent/GetOutputInfo/GetCrtcInfo/GetOutputPrimary/GetCrtcTransform, all
returning 2340×1080), and the connection-setup screen size is 2340. So the X side is not the problem — the
issue is Unity's early SDL probe order + the stale Display.main + patch C.

### Confirmed NOT the cause (each tested on-device, screen-ON, window verified valid)
WSI/window/driver (magenta), threading (`-force-gfx-direct` vs threaded identical), surface transform
(spoofed IDENTITY, no change; real surface is ROTATE_90 but that's not the gate), X-event flood (we send
~0), load/worker thread (completes), `vulkanEnableLateAcquireNextImage` (already False in PlayerSettings),
fullscreen (forced Windowed via UnityPy, no change), window map-state (0x400004 ends mapped/visible/focused).
Also: there is NO 2340×1080 offscreen COLOR render target at all (Unity's color images are all power-of-two
atlas/RT textures) — so Unity does not even ALLOCATE a backbuffer composite target. The decision to not
composite happens upstream, tied to the display state.

## Disasm so far (Ghidra 11.3.2, UnityPlayer.so analyzed)
- Display resolution lives at ScreenManager-object `r14+0xcc/0xd0` (logged as "Desktop is W×H").
- Enumeration fn (≈0xe77280) calls an SDL bounds function (≈0x192a449); on the early probe it returns an
  error → the width/height stores are skipped → 0.
- Per-frame present-setup reads a w/h pair with a **zero-fallback** (`cmovel`), so a 0 resolution isn't a
  hard gate there — the real gate is elsewhere.
- Candidate present/backbuffer gate in a swapchain-mgmt function (FUN_016618e0): 
  `(*(obj+0xd0) & 0x4400) == 0x4400 && *(obj+0x497) && *(obj+0x499) && *(obj+0x4c0)` on an object from a
  singleton getter FUN_00d30270. If one of these booleans reflects "display has a valid presentable
  output" and is 0 because Display.main is half-init, that's the gate.
- Patch "C" is inside FUN_00e656c0, which reads env-var-like strings and returns 0/1/2 — it is NOT a plain
  "display count" getter as we'd assumed; **patch C's real effect is now uncertain and may be harmful.**

## KEY LEVER: we can patch game files
We can **byte-patch UnityPlayer.so at install time** (patch C proves this works — deployed via bsdiff-style
install patch) and **edit globalgamemanagers via UnityPy** (verified round-trip). So the fix does NOT have
to be a runtime box64 hack — we can neutralize the gate directly in the binary. We also fully control the
in-process X server and can wrap any vk/libX11/libc call.

## Questions
1. In Unity 2022.3 Linux Vulkan, what exact predicate gates the whole backbuffer-composite+present path?
   Is it a `ScreenManager`/`GfxDeviceVK` "display target valid / has active output" boolean derived from
   the (multi-display) enumeration — separate from Screen/window/surface validity? Is the FUN_016618e0
   `obj+0x497/0x499/0x4c0` / `obj+0xd0 & 0x4400` combination a known "can present to backbuffer" gate?
2. The root is the EARLY "get num native displays" probe running before SDL_Init(VIDEO) succeeds. Why does
   Unity 2022.3 Linux probe displays that early, and does it re-populate Display.main after the late
   enumeration on real Linux? Is our environment (box64 + statically-linked SDL) changing the init order?
3. Best byte-patch target, given we can patch UnityPlayer.so and globalgamemanagers:
   (a) patch the enumeration to inject 2340×1080 into r14+0xcc/0xd0 even when the early SDL call fails;
   (b) patch the present/composite gate predicate (FUN_016618e0 bools) to always-true;
   (c) remove patch C and instead fix the early probe so the late enumeration populates Display.main;
   (d) force the early SDL probe to succeed. Which is cleanest/safest?
4. Is patch C (env-enum getter → return 1) plausibly the thing that freezes the half-init Display, i.e.
   would removing it + a targeted enumeration fix be better than layering another patch on top?
5. Any Unity env/launch flag that forces the display resolution or the backbuffer-composite path
   regardless of the multi-display enumeration (e.g. a "single window, always present" mode)?

## Assets
- Ghidra 11.3.2 with UnityPlayer.so fully analyzed (reusable project; can decompile any function fast via
  -process -noanalysis; CreateFunctionCmd for jump-table targets).
- box64 wraps any vk/libX11/libc call; Mono reflection reads any managed field/static (Screen/Display/Time/
  Event, method names); guest backtrace with Mono-JIT naming; a proven synthetic clear+present harness.
- Install-time UnityPlayer.so byte-patching (patch C + a display-count patch already shipped) and UnityPy
  edits to globalgamemanagers (verified). Full control of the in-process Java X server (RandR already
  implemented and answering 2340×1080).

## Current bet
The present/composite path is gated on a "display has a valid presentable output" boolean that is false
because Display.main is half-init (0×0) from the early SDL-probe failure that patch C froze. Cleanest fix
is likely a targeted UnityPlayer.so byte-patch: either force the present-gate predicate true, or make the
display enumeration store 2340×1080 into the Display object even on the early-probe failure — and re-examine
whether patch C should be removed rather than reinforced.
