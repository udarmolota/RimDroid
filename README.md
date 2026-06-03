# RimDroid

Run **RimWorld** (the native Linux x86_64 build, Unity 2019) on an Android phone via
x86_64→ARM64 emulation, with **real GPU rendering** and **on-screen touch controls**.

> **Status (v0.1.4): playable on Snapdragon/Adreno, with working mods.** RimWorld 1.5 boots,
> renders at native resolution, takes touch input (move/select, drag, orders, camera, zoom),
> runs mods (Harmony, RimHUD, Pick Up And Haul), and has an on-screen controls editor, a mod
> importer, save/settings backup, and one-tap log export. Mali/MediaTek is **experimental** —
> it launches and new colonies are playable, but loading a save currently loses colonists.

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
- ✅ **render-scale slider** in Settings — lower = bigger, more readable UI; the minimum
  adapts to the screen so the UI never drops below RimWorld's usable size;
- ✅ **selectable Vulkan/Turnip driver** in Settings — **System (phone driver) by default**
  (works across GPUs), with Turnip variants for Adreno;
- ✅ **input:** left-click (tap / mouse-stick), **right-click** (RBC button),
  **left-drag** (LBC button — selection box / zones / Architect), camera pan
  (WASD-stick → arrow keys), **pinch-to-zoom**;
- ✅ **editable on-screen controls** — move / resize / opacity (with "Opacity → all"), add
  buttons bound to any key or mouse action (incl. **F1–F12**), circular or rectangular, and
  **export/import the layout** (Settings → Edit on-screen controls; menu → Export/Import
  controls layout);
- ✅ **mods work** — Harmony patching is functional (tested: Harmony, RimHUD, Pick Up And
  Haul). Requires **Harmony 2.2.2** (see [Mods](#mods));
- ✅ **smart mod importer** (menu → Import Mods (ZIP)) — finds each mod's root and strips
  wrapper folders;
- ✅ **save/settings backup** (menu → Export/Import saves + settings) and **one-tap log
  export** (menu → Export logs);
- ✅ **Community / new-versions links** in the menu (Reddit, GitHub releases);
- ✅ saving/loading on Adreno (on Mali/MediaTek a save currently loads without colonists —
  see [Remaining](#remaining--todo)).

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
- **Mods didn't patch (Harmony `NotImplementedException`)** — Harmony 2.3's MonoMod.Core read
  `/proc/self/auxv`, saw `aarch64`, and picked an unimplemented ARM64 code-detour, so every
  patch failed. Fixed two ways: box64 now reports `x86_64` in `/proc/self/auxv` (so arch
  detection is correct), and RimDroid uses **Harmony 2.2.2** (older MonoMod) whose x86_64
  detour works under emulation.

## Controls

- **Mouse-stick** (right): drag to move the cursor (white arrow); tap the stick = left-click.
- **Direct tap** on the game = left-click.
- **WASD-stick** (left): pan the camera (arrow keys).
- **RBC** button (top-right): hold to right-click at the cursor (orders, context menus).
- **LBC** button: hold to left-drag at the cursor (selection box, zone painting, Architect drag).
- **Pinch**: zoom.

The whole layout is editable in **Settings → Edit on-screen controls**: drag to move, **hold
an element briefly to open its settings**, sliders for size and opacity (with **"Opacity →
all"** to copy one element's opacity to every element), add/delete elements, and bind any
button to a mouse action or a key (Space, Tab, Esc, digits, letters, arrows, **F1–F12**, …).
Buttons are circular by default and can be switched to rectangular. **Export/Import controls
layout** from the menu to back up or share your setup.

## Mods

Mods that patch the game use **Harmony**, and Harmony must be version **2.2.2** — **not** the
latest 2.3.x. Under emulation the 2.3.x patch engine (MonoMod.Core) detects an
`Android x86_64` environment and has no working code-detour, so every patch fails; the older
2.2.2 engine works. Install the recommended **Harmony 2.2.2** build (`Harmony-2.2.2-RimDroid.zip`,
packageId `brrainz.harmony`) instead of the Steam Workshop Harmony — don't install both
(duplicate package id).

Add mods (and Harmony itself) with **menu → Import Mods (ZIP)**: pick any mod zip and it
finds the mod root, unwraps any extra/double folder, and drops it into the instance's `Mods`.
Dragging a mod folder into `Mods` via **Manage Storage** still works too.

## Build

- Android Studio, **JDK 21** (JDK 25 breaks Kotlin DSL compilation — see `gradle.properties`);
- native part: `gradlew :app:externalNativeBuildDebug`;
- `box64/` is a fork (`udarmolota/rimdroid-box64`);
- `libzfa.so` (Mesa+Zink+ZFA target) is built via GitHub Actions in the
  `udarmolota/zomdroid-dependencies` fork;
- an instance holds the extracted Linux build of RimWorld (`RimWorldLinux` + `RimWorldLinux_Data`).

## Remaining / TODO

- **Mali/MediaTek: colonists missing after loading a save** — a low-level box64 emulation bug
  on those CPUs (Snapdragon/Adreno unaffected);
- **software (CPU) renderer** for GPUs where Zink/Vulkan won't run (in progress);
- on-screen keyboard (text fields: colony/pawn names, search);
- occasional black screen / stuck loading on launch — kill + relaunch (usually works on the
  second try);
- audio (FMOD);
- make the latest Harmony 2.3 work, so users aren't pinned to 2.2.2;
- physical mouse/keyboard polish.

---

*Core logic: `app/src/main/cpp/rimdroid.c` (launch, ZFA/GPU, input ring),
`box64/src/wrapped/wrappedsdl2.c` (SDL/GL/event intercepts + dynapi remap),
`box64/src/wrapped/wrappedlibc.c` (`/proc/self/auxv` → x86_64),
`app/src/main/java/com/rimdroid/input/` (`InputControlsView`, `ControlElement`,
`ButtonElement` + sticks), `ControlsEditorActivity`, `ModImporter`, `GameDataTransfer`
(save/settings backup), `LogExporter` (log zip), `GameActivity`.*

## Credits & Third-Party Sources

* [box64](https://github.com/ptitSeb/box64) — x86_64→ARM64 emulation backend
* [Mesa / Zink / ZFA](https://gitlab.freedesktop.org/mesa/mesa) — GPU rendering (OpenGL→Vulkan)
* [Turnip / libvulkan_freedreno](https://gitlab.freedesktop.org/mesa/mesa) — Adreno Vulkan driver
* [liblinkernsbypass](https://github.com/bylaws/liblinkernsbypass) — Android linker namespace access
* [Zomdroid](https://github.com/udarmolota/zomdroid) — architecture reference and inspiration
* [Harmony](https://github.com/pardeike/Harmony) — required by mods (MIT)

> RimDroid is not affiliated with Ludeon Studios.
> RimWorld must be purchased separately.
