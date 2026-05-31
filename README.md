# RimDroid

Run **RimWorld** (the native Linux x86_64 build, Unity 2019) on an Android phone via
x86_64→ARM64 emulation, with **real GPU rendering** and **on-screen touch controls**.

> **Status (v0.1.1): playable.** RimWorld 1.5 boots, renders at native resolution,
> takes touch input (move/select, orders, camera, zoom), and runs mods (incl. Harmony).

---

## What & why

RimWorld officially ships only for x86_64 (Windows/Linux/macOS). RimDroid takes the
**native Linux build of the game** and runs it directly on an ARM64 phone:

- **box64** emulates the x86_64 code (Unity engine + Mono) on ARM64;
- it runs **in-process, without fork** — possible for 1.5 because `UnityPlayer.so` is
  relocatable (PIE) and loads below the ART heap;
- graphics go through the phone's real GPU (not a software renderer);
- touch input is injected into the game's SDL event queue.

## Stack

| Layer | What is used |
|-------|--------------|
| Emulation | **box64** (x86_64 → ARM64), in-process, no fork |
| GPU | **Zink** (OpenGL→Vulkan, Mesa 25) over **Turnip** (`libvulkan_freedreno`, Adreno) → real **OpenGL 4.3 Core** via `libzfa.so` |
| Window | **SDL2** (`SDL_VIDEODRIVER=dummy`), rendering into the Activity's `ANativeWindow`; orientation handled Zomdroid-style (landscape + identity buffer transform) |
| SDL dynapi | **remap** of the jump_table to the proc order of the SDL statically linked into `UnityPlayer.so` (differs from box64's) |
| Input | Android touch → injected **SDL events** (`my2_SDL_PollEvent` / `SDL_GetMouseState`); on-screen sticks + buttons |
| Runtime | the game's own **Mono / Boehm GC** |

Reference device: **Snapdragon 8 Elite, Adreno 830**.

## What works

- ✅ RimWorld **1.5 launches** in-process and **renders at native resolution** (landscape);
- ✅ full GPU pipeline (Zink/Vulkan/Turnip, GL 4.3 Core);
- ✅ **render-scale slider** in Settings (67–100%) — lower = bigger, more readable UI;
- ✅ **input:** left-click (tap / mouse-stick), **right-click** (RMB button), camera pan
  (WASD-stick → arrow keys), **pinch-to-zoom**;
- ✅ **mods** load and apply (tested with **Harmony** + a large mod list);
- ✅ saving/loading.

## Key problems that were solved

- **The "infinite `SDL_GL_DeleteContext` loop"** that froze the first frame for days was a
  **red herring**: the SDL dynapi remap was off-by-one (it assumed the game lacks
  `SDL_GL_GetDrawableSize`), so the game's **`SDL_GL_SwapWindow` was routed to
  `SDL_GL_DeleteContext`**. Unity's normal present loop was spinning into our no-op delete.
  Fixed by correcting the slot indices (SwapWindow 522, DeleteContext 523).
- **Orientation** (rotated / quarter-screen) — fixed by mirroring Zomdroid: `SENSOR_LANDSCAPE`
  + `holder.setFixedSize()` + an IDENTITY `ANativeWindow` buffer transform, no native
  `setBuffersGeometry`.
- **Clicks "selected everything of a type" / right-click misbehaved** — injected SDL events
  had `timestamp == 0`, so RimWorld read every click as a double/triple click. Fixed by
  stamping a real monotonic-ms timestamp on each injected event.

## Controls

- **Mouse-stick** (right): drag to move the cursor (white arrow); tap the stick = left-click.
- **Direct tap** on the game = left-click.
- **WASD-stick** (left): pan the camera (arrow keys).
- **RMB** button (top-right): hold to right-click at the cursor (orders, context menus).
- **Pinch**: zoom.

## Build

- Android Studio, **JDK 21** (JDK 25 breaks Kotlin DSL compilation — see `gradle.properties`);
- native part: `gradlew :app:externalNativeBuildDebug`;
- `box64/` is a fork (`udarmolota/rimdroid-box64`);
- `libzfa.so` (Mesa+Zink+ZFA target) is built via GitHub Actions in the
  `udarmolota/zomdroid-dependencies` fork;
- an instance holds the extracted Linux build of RimWorld (`RimWorldLinux` + `RimWorldLinux_Data`).

## Remaining / TODO

- left-drag (selection box / zone painting / Architect drag);
- on-screen keyboard (text fields: colony/pawn names, search);
- occasional black screen on launch (kill + relaunch);
- physical mouse/keyboard polish; audio (FMOD).

---

*Core logic: `app/src/main/cpp/rimdroid.c` (launch, ZFA/GPU, input ring),
`box64/src/wrapped/wrappedsdl2.c` (SDL/GL/event intercepts + dynapi remap),
`app/src/main/java/com/rimdroid/` (`GameActivity`, `InputOverlayView`).*
