# RimDroid

**RimDroid** is a launcher that runs [RimWorld](https://rimworldgame.com) — the native Linux build —
on Android phones, with real GPU rendering, touch controls, gamepad support, and mods.

> [!NOTE]
> This application is **not developed by or affiliated with Ludeon Studios** in any way.
> You must own RimWorld — RimDroid does **not** include or distribute any game files.

> [!WARNING]
> **The app is still in beta.** All tested devices launch the game, but some need **Compatibility
> mode** and behaviour is still per-device, not per-GPU brand. 

## Features

- ✔️ Runs **RimWorld 1.5** and **RimWorld 1.6** (the native Linux x86_64 build) on ARM64 phones
- ✔️ **Editable on-screen touch controls** — move / resize / opacity, add buttons bound to any key or mouse action
- ✔️ **In-app downloads** — get the game, DLC, and Workshop mods straight from Steam
- ✔️ **Mods** — Harmony patching works (tested: RimHUD, Pick Up And Haul, Camera+)
- ✔️ **Multiple instances** — each install is a card with its own settings (renderer, driver, controls)
- ✔️ **Gamepad support** — version 1.0 with a button-remapping wizard for controllers with swapped buttons
- ✔️ **Compatibility mode** — helps stubborn devices launch and lets mods load (Settings → Advanced)
- ✔️ **On-screen FPS counter** (Settings → Video)
- ✔️ **Save / Settings / layout import & export**
- ✔️ Haptics, night mode, and a Russian translation
- ⭕ **On-screen keyboard** for text fields not yet available

## Project status & what to expect

RimDroid is young — about **two months old**, built by **one person**, and still in active
development. That context matters if you're comparing it to more established Android launchers like
GameHub or GameNative: those comparisons are fair and interesting, but please keep the scale in mind.
This is an early solo project, and it will keep getting better.

A few honest notes so expectations land right:

- **RimWorld is extremely CPU-heavy**, and emulation adds cost on top of that. RimDroid *launches* on
  a wide range of phones — but launching is not the same as playing *comfortably*. A fresh 3-colonist
  start runs far lighter than a mature colony with dozens of pawns, animals, and running systems. The
  bigger and older your colony gets, the more CPU it demands — and the more even a strong phone will
  feel it.
- Future updates will bring real improvements, but the bottleneck is the simulation itself. Don't
  expect miracles that turn a budget phone into a desktop.
- **The closer your device is to a current flagship, the better your experience** — especially frame
  rate, and how well things hold up as the colony grows.

**If you're used to long PC sessions:** RimDroid has **not been tested with more than ~50 mods**. How
it behaves on large, long-running, heavily-modded saves is genuinely unknown right now. If that's your
style, a near-flagship device gives you the best odds — but treat big modlists as experimental for now.

We'd rather set expectations honestly than overpromise.

## Device compatibility

All tested devices now launch the game. Some run great out of the box; others may need
**Compatibility mode** (Settings → Advanced).

## System requirements

RimDroid runs on a wide range of phones, but **how well it plays depends heavily on your hardware and
on how big your colony and mod list are.** RimWorld is CPU-bound under emulation, so a phone can
launch the game and still slow down as a colony matures.

- **To launch and play (small / early colonies):** Android 11+, ARM64, **6 GB RAM**, ~5–10 GB free
  storage, a copy of RimWorld you own (Steam/GOG). Mid-range phones fall here. Expect **single-digit
  to low-teens FPS on large, mature saves** — one real report saw a ~300-day colony run around **8–11
  FPS** on mid-range hardware (playable if you're patient, not smooth).
- **Recommended (smoother play, room to grow):** **8 GB+ RAM** and a recent flagship-class chip.
  **Adreno GPUs** currently have the most mature driver path (Turnip/Zink); other GPUs work but may
  need extra tweaks.
- **Large / long / heavily-modded PC-style saves (experimental):** the newest flagship you can get,
  **12 GB+ RAM**. This is untested territory — the closer to a current flagship, the better your odds.

Because the game is CPU-bound, on weaker phones the **in-game speed setting may not change your FPS** —
all speeds can look the same. That's expected: the simulation, not rendering, is the limit.

## Getting the best performance

- **Turn on your phone's game booster and set it to its highest-performance profile.** Most phones
  have one — Samsung *Game Booster*, Xiaomi/POCO *Game Turbo*, Realme/OPPO/OnePlus *Game Space*,
  vivo/iQOO *Ultra Game Mode*, Infinix/Tecno *Game Mode*. RimDroid registers itself as a game, so
  these tools should detect it automatically — just make sure the profile isn't "balanced" or a
  battery-saver.
- **Install a performance mod** such as RocketMan. The bottleneck is the CPU, not the GPU, and these
  mods cut the simulation cost directly — the single biggest thing you can do.
- **Keep the colony and mod list modest on weaker devices.** RimWorld's cost grows with the colony:
  a fresh small map is light; a large, old, event-heavy colony is far heavier.
- **Close background apps** so the game gets the RAM and CPU to itself.

## Roadmap

- [x] Expand GPU/device compatibility
- [x] Fixed the "colonists missing after load" bug (built-in save fix)
- [x] In-game audio
- [ ] On-screen keyboard for text fields
- [x] Resolution / render-scale options for more FPS on weaker GPUs

## How it works

RimWorld officially ships only for x86_64. RimDroid runs the **native Linux build** directly on
ARM64: [box64](https://github.com/ptitSeb/box64) emulates the x86_64 engine + Mono in-process,
graphics go through your phone's real GPU, and Android touch and gamepad input is injected straight
into the game.

There are two renderers. **Zink** (Mesa) turns the game's OpenGL into Vulkan and is the default.
**[MobileGlues](https://github.com/MobileGL-Dev/MobileGlues)** turns it into OpenGL ES instead and
never touches Vulkan at all — which is why it works on phones whose Vulkan driver cannot carry Zink.
On Samsung Exynos (Xclipse) that is the difference between distorted text and models and a game that
simply looks right; it is switchable per instance in Settings → Video.

## Build

- Android Studio, **JDK 21** (see `gradle.properties`)
- `box64/` is a fork ([udarmolota/rimdroid-box64](https://github.com/udarmolota/rimdroid-box64));
  `libzfa.so` (Mesa + Zink) is built via GitHub Actions
- `libmobileglues.so` ships unmodified from [MobileGlues](https://github.com/MobileGL-Dev/MobileGlues)
  in the bundled libraries; it is LGPL-2.1, so it stays a separate shared library that can be replaced
- An instance holds the extracted Linux build of RimWorld (`RimWorldLinux` + `RimWorldLinux_Data`)

## Supporting development

This is an independent project. To help keep it going, contributions are welcome via
[Ko-Fi](https://ko-fi.com/udarmolota).

## Feedback

Please report issues or request features via
[GitHub Issues](https://github.com/udarmolota/RimDroid/issues). There's a one-tap **Export logs** in
the in-app menu — attach the zip so we can see what your device is doing.

Running a big or long-lived colony? **Tell us how it holds up** — long-save reports are exactly the
data we're missing, and they're how the device recommendations here will get more precise.

## Credits & Third-Party Sources

- [box64](https://github.com/ptitSeb/box64) — x86_64→ARM64 emulation backend
- [MobileGlues](https://github.com/MobileGL-Dev/MobileGlues) by [MobileGL-Dev](https://github.com/MobileGL-Dev)
  — the second renderer (OpenGL→OpenGL ES), LGPL-2.1. It is what makes RimWorld run on phones whose
  Vulkan driver cannot carry Zink — Samsung Exynos in particular
- [Mesa / Zink](https://gitlab.freedesktop.org/mesa/mesa) — GPU rendering (OpenGL→Vulkan)
- [Turnip / libvulkan_freedreno](https://gitlab.freedesktop.org/mesa/mesa) — Adreno Vulkan driver
- [liblinkernsbypass](https://github.com/bylaws/liblinkernsbypass) — Android linker namespace access
- [Harmony](https://github.com/pardeike/Harmony) — required by mods
- [Zomdroid](https://github.com/udarmolota/zomdroid) — architecture reference and inspiration

> RimDroid is not affiliated with Ludeon Studios. RimWorld must be purchased separately.
