# RimDroid

A launcher that runs **RimWorld** (the native Linux x86_64 build, Unity) on an Android phone via x86_64→ARM64 emulation — with real GPU rendering.

> Status: **work in progress.** RimWorld 1.5 launches, initializes Mono and the GPU pipeline, and draws the main menu — but hits one wall (see below).

---

## What & why

RimWorld officially ships only for x86_64 (Windows/Linux/macOS). RimDroid takes the **native Linux build of the game** and runs it directly on an ARM64 phone:

- **box64** emulates the x86_64 code (the Unity engine + Mono) on ARM64;
- it runs **in-process, without fork** — possible for 1.5 because `UnityPlayer.so` is relocatable (PIE) and loads below the ART heap (unlike the monolithic 1.2, which needed a fork that broke the GPU);
- graphics go through the phone's real GPU, not a software renderer.

## Stack

| Layer | What is used |
|-------|--------------|
| Emulation | **box64** (x86_64 → ARM64), in-process |
| GPU | **Zink** (OpenGL→Vulkan, Mesa) over **Turnip** (`libvulkan_freedreno`, Adreno) → real **OpenGL 4.3 Core** via `libzfa.so` |
| Window/input | **SDL2** with `SDL_VIDEODRIVER=dummy` (no X11/Wayland); rendering into the Android Activity's `ANativeWindow` |
| SDL dynapi | **remap** of the jump_table to the proc order of the SDL statically linked into `UnityPlayer.so` (which differs from box64's) |
| Game runtime | the game's own **Mono / Boehm GC** |

Development device: **Snapdragon 8 Elite, Adreno 830** (rendering is currently 1024×768, scaled to the screen).

## What already works

- ✅ RimWorld 1.5 launches in-process (no fork);
- ✅ box64 + SDL dynapi seeding/remap, GL bridges (CreateContext/MakeCurrent/SwapWindow/DeleteContext, etc.);
- ✅ full GPU pipeline: Zink/Vulkan/Turnip provides GL 4.3 Core, the default framebuffer is valid, textures/shaders load without errors;
- ✅ Mono starts, assemblies load, the `RimWorld 1.5.x` banner prints;
- ✅ the **main menu** renders (background + UI), scaled to fill the screen.

## Where we stopped (current wall)

At startup Unity 1.5 switches **windowed → fullscreen**, and its `GfxDevice` goes into an **infinite teardown** (a recursive walk that calls `SDL_GL_DeleteContext` endlessly) — it never reaches the first `SwapWindow`, so the picture "freezes" on the first frame.

Tested and **ruled out** as the cause:

- window size / FBO mismatch (everything was unified to 1024×768 — same loop);
- the dummy driver's 0 Hz refresh (forced 60 Hz — same loop);
- broken context/window/format, shader compile failure, GC, advapi32 — all disproven by data.

**Conclusion:** the trigger is the fullscreen transition itself in Unity (a GfxDevice reset/recreate) which, combined with the dummy SDL driver + a fixed ZFA context, never completes.

## Next steps

1. **RimWorld 1.6** (newer Unity, 2025) — it may not have this teardown at all (would require re-deriving the dynapi remap);
2. on 1.5 — find out why `-screen-fullscreen 0` is ignored and force it to stay windowed; or neutralize the context recreation in the teardown;
3. input (touch → SDL events) and audio (FMOD) — once the menu is live.

## Build

- Android Studio, **JDK 21** (JDK 25 breaks Kotlin DSL compilation — see `gradle.properties`);
- native part: `gradlew :app:externalNativeBuildDebug`;
- `box64/` is a submodule (fork `udarmolota/rimdroid-box64`);
- an instance holds the extracted Linux build of RimWorld (`RimWorldLinux` + `RimWorldLinux_Data`).

---

*Experimental project. Core logic lives in `app/src/main/cpp/rimdroid.c` (launch, ZFA/GPU) and `box64/src/wrapped/wrappedsdl2.c` (SDL/GL intercepts).*
