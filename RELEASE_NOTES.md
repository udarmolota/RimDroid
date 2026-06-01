# RimDroid v0.1.3

RimWorld, running on your Android phone. **Playable, and now with working mods** — plus an
on-screen controls editor and a one-tap mod importer.

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

**Mods work now** — tested with **Harmony**, **RimHUD**, and **Pick Up And Haul**.

⚠️ **Use Harmony 2.2.2, not the latest.** The current Harmony (2.3.x) can't patch under
emulation — its patch engine fails on an `Android x86_64` environment. Harmony **2.2.2**
works. Install the recommended **Harmony 2.2.2 (RimDroid)** build instead of the Steam
Workshop Harmony, and don't install both at once (duplicate package id).

**Adding mods is easy:** menu → **Import Mods (ZIP)**. Pick any mod zip — it automatically
finds the mod's root folder, unwraps any extra/double folders, and installs it into your
instance's `Mods`. (You can still drop mod folders into `Mods` manually via Manage Storage.)

## Graphics

- Renderer: **ZINK_ZFA** (OpenGL → Vulkan on your phone's GPU).
- **Vulkan driver picker** in Settings — if the game doesn't start or looks wrong with the
  default driver, pick another from the list. Includes a **System (phone driver)** option
  (experimental) that may work on non-Adreno GPUs.
- **Render-scale slider** in Settings — lower % makes the in-game UI bigger and easier to
  read (and is lighter on the GPU).

## Controls (on-screen)

- **Left-click** — tap the game, or tap the mouse-stick.
- **Right-click** — hold the **RBC** button.
- **Left-drag / select / paint zones** — hold the **LBC** button and move the cursor.
- **Move cursor** — the **mouse-stick** (right side).
- **Move camera** — the **WASD-stick** (left side).
- **Zoom** — pinch.

**Customize the layout** in **Settings → Edit on-screen controls**: move and resize elements,
change opacity, add buttons, and bind any button to a mouse action or a key.

## Known limitations

- No sound yet.
- No on-screen keyboard yet (typing into name/search fields is limited).
- Occasional black screen on launch — close and relaunch.
- Tested on a Snapdragon 8 Elite (Adreno 830); other GPUs may need a different driver
  from the picker.

---

Thanks for trying RimDroid 🎮 — feedback welcome.
