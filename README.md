# RimDroid

**RimDroid** is a launcher that runs [RimWorld](https://rimworldgame.com) — the native Linux build —
on Android phones, with real GPU rendering, touch controls, gamepad support, and mods.

> [!NOTE]
> This application is **not developed by or affiliated with Ludeon Studios** in any way.
> You must own RimWorld — RimDroid does **not** include or distribute any game files.

> [!WARNING]
> **The app is still in beta.** All tested devices launch the game, but some need **Compatibility
> mode** and behaviour is still per-device, not per-GPU brand. See [Device compatibility](#device-compatibility).

## Features

- ✔️ Runs **RimWorld 1.5** and **RimWorld 1.6** (the native Linux x86_64 build) on ARM64 phones
- ✔️ **Editable on-screen touch controls** — move / resize / opacity, add buttons bound to any key or mouse action
- ✔️ **In-app downloads** — get the game, DLC, and Workshop mods straight from Steam
- ✔️ **Mods** — Harmony patching works (tested: RimHUD, Pick Up And Haul, Camera+); needs Harmony 2.2.2
- ✔️ **Multiple instances** — each install is a card with its own settings (renderer, driver, controls)
- ✔️ **Gamepad support** — version 1.0 with a button-remapping wizard for controllers with swapped buttons
- ✔️ **Compatibility mode** — helps stubborn devices launch and lets mods load (Settings → Advanced)
- ✔️ **On-screen FPS counter** (Settings → Video)
- ✔️ **Save / Settings / layout import & export**
- ✔️ Haptics, night mode, and a Russian translation
- ⭕ **On-screen keyboard** for text fields not yet available

## Device compatibility

All tested devices now launch the game. Some run great out of the box; others may need
**Compatibility mode** (Settings → Advanced). This is a living list; if your device isn't here, try
it and send us a log.

**Works:**
- Samsung Galaxy S25 Ultra (Adreno 830)
- Lenovo Legion Y700 (Adreno 730)
- Realme P4x (Mali)
- Poco X7 (Mali)

**Loads, but may need Compatibility mode (Settings → Advanced):**
- Poco F5 / Snapdragon 7+ Gen 2 (Adreno 725)
- Infinix Note 50s 5G (Mali-G615) and similar
- Tecno Pova 7 / Helio G100 (Mali-G57)
- Tecno Pova 4 Pro

## System requirements

- Android 11+
- A 64-bit (ARM64) device; **8 GB+ RAM** recommended
- ~5–10 GB free storage for the game, DLC, and mods
- A copy of RimWorld you own (Steam/GOG)

## Roadmap

- [x] Expand GPU/device compatibility
- [x] Fixed the "colonists missing after load" bug (built-in save fix)
- [x] In-game audio
- [ ] On-screen keyboard for text fields
- [x] Resolution / render-scale options for more FPS on weaker GPUs

## How it works

RimWorld officially ships only for x86_64. RimDroid runs the **native Linux build** directly on
ARM64: [box64](https://github.com/ptitSeb/box64) emulates the x86_64 engine + Mono in-process,
graphics go through your phone's real GPU via Zink/Vulkan, and Android touch and gamepad input is
injected straight into the game.

## Mods

Mods that patch the game use **Harmony**, and it must be version **2.2.2** — not the latest 2.3.x
(its patch engine can't run under emulation). Install the provided ([Harmony-2.2.2-RimDroid.zip](https://www.mediafire.com/file/hqykm2zrl0b2rus/Harmony-2.2.2-RimDroid.zip/file))
instead of the Steam Workshop Harmony, then add mods with **menu → Import Mods (ZIP)**, or download
them in-app (Steam Downloads → Mods).

## Build

- Android Studio, **JDK 21** (see `gradle.properties`)
- `box64/` is a fork ([udarmolota/rimdroid-box64](https://github.com/udarmolota/rimdroid-box64));
  `libzfa.so` (Mesa + Zink) is built via GitHub Actions
- An instance holds the extracted Linux build of RimWorld (`RimWorldLinux` + `RimWorldLinux_Data`)

## Supporting development

This is an independent project. To help keep it going, contributions are welcome via
[Ko-Fi](https://ko-fi.com/udarmolota).

## Feedback

Please report issues or request features via
[GitHub Issues](https://github.com/udarmolota/RimDroid/issues). There's a one-tap **Export logs** in
the in-app menu — attach the zip so we can see what your device is doing.

## Credits & Third-Party Sources

- [box64](https://github.com/ptitSeb/box64) — x86_64→ARM64 emulation backend
- [Mesa / Zink](https://gitlab.freedesktop.org/mesa/mesa) — GPU rendering (OpenGL→Vulkan)
- [Turnip / libvulkan_freedreno](https://gitlab.freedesktop.org/mesa/mesa) — Adreno Vulkan driver
- [liblinkernsbypass](https://github.com/bylaws/liblinkernsbypass) — Android linker namespace access
- [Harmony](https://github.com/pardeike/Harmony) — required by mods
- [Zomdroid](https://github.com/udarmolota/zomdroid) — architecture reference and inspiration

> RimDroid is not affiliated with Ludeon Studios. RimWorld must be purchased separately.
