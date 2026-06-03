# RimDroid v0.1.4

RimWorld, running on your Android phone. Playable on Snapdragon/Adreno, with working mods,
an on-screen controls editor, save/settings backup, and one-tap log sharing.

---

## What's new in v0.1.4

- **System GPU driver is now the default** — works across different GPUs out of the box
  (Adreno, and experimentally Mali/MediaTek). No more picking a driver just to launch.
- **Launch button fixed** — after installing an instance it activates right away, with a clear
  "installed" notification (no need to leave and re-enter the screen).
- **Status bar hidden** for a cleaner full-screen game.
- **Back up your colony:** export and import **saves + settings** as a single file (menu).
- **One-tap log export** for support — grab everything we need to help, in one zip.
- **Share your control layouts** — export/import the on-screen controls layout, plus a nicer
  default and editor improvements (see Controls).
- **Community & updates links** in the menu — find where to ask questions and get new versions.
- **Mali/MediaTek devices now launch** (experimental — see Known limitations).

---

## Getting the game

RimDroid does **not** include RimWorld. You need to own it on Steam and download the
**Linux build of RimWorld 1.5 (stable)** yourself.

The easiest way on-device is **Termux + DepotDownloader**:

```
depotdownloader -app 294100 -depot 294103 -manifest 5353311791356367188 -dir rimdroid -username YOURUSERNAME -password YOURPASSWORD
```

This downloads the exact 1.5 stable build that RimDroid was tested against into a
`rimdroid` folder. Then add it as an instance in the RimDroid launcher.

> Use your own Steam login. RimDroid never ships or redistributes any game files.

## Mods

**Mods work** — tested with **Harmony**, **RimHUD**, and **Pick Up And Haul**.

⚠️ **Use Harmony 2.2.2, not the latest.** The current Harmony (2.3.x) can't patch under
emulation — its patch engine fails on an `Android x86_64` environment. Harmony **2.2.2**
works. Install the recommended **Harmony 2.2.2 (RimDroid)** build instead of the Steam
Workshop Harmony, and don't install both at once (duplicate package id).

**Adding mods is easy:** menu → **Import Mods (ZIP)**. Pick any mod zip — it automatically
finds the mod's root folder, unwraps any extra/double folders, and installs it into your
instance's `Mods`. (You can still drop mod folders into `Mods` manually via Manage Storage.)

## Graphics

- Renderer: **ZINK_ZFA** (OpenGL → Vulkan on your phone's GPU).
- **System (phone) driver by default.** If the game doesn't start or looks wrong, open
  **Settings → Vulkan driver** and try another option (Turnip variants for Adreno are there).
- **Render-scale slider** in Settings — lower % makes the in-game UI bigger and easier to
  read (and is lighter on the GPU). The minimum adapts to your screen so it never drops below
  RimWorld's usable UI size.

## Controls (on-screen)

- **Left-click** — tap the game, or tap the mouse-stick.
- **Right-click** — hold the **RBC** button.
- **Left-drag / select / paint zones** — hold the **LBC** button and move the cursor.
- **Move cursor** — the **mouse-stick** (right side).
- **Move camera** — the **WASD-stick** (left side).
- **Zoom** — pinch.
- Default layout now includes handy **Space, Tab, 1–3 and F1–F3** buttons (and the binding list
  covers F1–F12).

**Customize the layout** in **Settings → Edit on-screen controls**: drag to move, hold an
element briefly to open its settings, resize, set opacity (with **"Opacity → all"** to match
every element at once), add buttons, and bind any button to a mouse action or a key.
**Export/Import controls layout** from the menu to back up or share your setup.

## Backup & diagnostics (menu)

- **Export / Import saves + settings** — one file; importing accepts a full or partial backup
  (saves only, settings only, or both — detected automatically).
- **Export logs (ZIP)** — bundles the logs we need for troubleshooting in one tap.
- **Export / Import controls layout** — back up or share your on-screen layout.

## Known limitations

- No sound yet.
- No on-screen keyboard yet (typing into name/search fields is limited).
- **Occasional black screen / stuck loading on first launch — close and relaunch** (it usually
  works on the second try).
- **Mali / MediaTek GPUs are experimental:** the game launches and **new colonies are
  playable**, but **loading a saved game currently loses colonists** — a low-level emulation
  bug we're still chasing. Snapdragon/Adreno devices are not affected.
- Primary testing is on Snapdragon 8 Elite (Adreno 830); other GPUs may need a different
  driver from the picker.

---

Thanks for trying RimDroid 🎮 — feedback welcome on Reddit (menu → Community), and grab new
versions from GitHub (menu → New versions).
