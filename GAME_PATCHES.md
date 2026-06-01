# RimDroid — Game-File Patches

Modifications applied to the **RimWorld game files** (inside an instance) to make it
run on Android via box64. Tracked here so they can be reproduced after a reinstall /
new version, and **later migrated to universal renderer/box64-side fixes** so the game
can stay unmodified.

**Strategy:** Phase 1 — get it *running & playable* (game-side patches are OK as quick
wins). Phase 2 — move each patch into our own components (libzfa / box64) for
flexibility & universality, then remove the game-side patch.

Instance root on device:
`/data/user/0/com.rimdroid/files/instances/RimWorld_1.5/`
Unity config dir (RimWorld persistentDataPath):
`.../unity3d/Ludeon Studios/RimWorld by Ludeon Studios/`

---

> **CURRENT STATE (2026-06-01): NO game-file patches are needed or applied.**
> A pristine, completely unpatched RimWorld instance boots and is playable. The app
> does NOT modify any game file (verified: nothing touches `globalgamemanagers`,
> `Prefs.xml`, or `boot.config`). All fixes live in OUR components and ship inside the
> APK (box64 / libzfa / app). **Nothing to distribute to users besides a stock
> RimWorld Linux build** — which is also legally cleaner (we don't redistribute
> Ludeon's modified files).
>
> **Why the patches below are obsolete:** they came from the (later DISPROVEN)
> theory that the infinite `SDL_GL_DeleteContext` loop was caused by multithreaded
> rendering handing one GL context between two threads. The REAL root cause was an
> off-by-one in box64's SDL dynapi remap (`wrappedsdl2.c`): the game's
> `SDL_GL_SwapWindow` (slot 522) was being routed to box64's `SDL_GL_DeleteContext`
> no-op (slot 523). Fixing the slot indices made the per-frame present work, so the
> `m_MTRendering=0` asset edit is no longer required. The recipes below are kept for
> history only.

## Patches (recipes — OBSOLETE, NOT applied, kept for history)

### 1. `Config/Prefs.xml` — windowed, 1024×768
- **Path:** `unity3d/Ludeon Studios/RimWorld by Ludeon Studios/Config/Prefs.xml`
- **Change:** created the file with `<fullscreen>False</fullscreen>`, `<screenWidth>1024</screenWidth>`, `<screenHeight>768</screenHeight>`, `<uiScale>1</uiScale>`.
- **Why:** RimWorld's C# applies resolution/fullscreen from this file at startup (overriding the Unity `-screen-fullscreen 0` arg). Default (no file) = fullscreen, which caused a bogus `fullscreen 1024×768 @ 0 Hz` mode-set. Windowed keeps the pipeline clean.
- **Status:** OBSOLETE / NOT applied. Display is now handled our-side (native surface + render-scale, landscape + identity buffer transform); the game runs from a stock instance with no Prefs.xml. Kept only as a fallback recipe if a user ever needs a forced resolution.

### 2. `RimWorldLinux_Data/globalgamemanagers` — disable Multithreaded Rendering  ⟵ in progress
- **Path:** `RimWorldLinux_Data/globalgamemanagers`
- **Change:** PlayerSettings `m_MTRendering` → 0 (force single-threaded GL).
- **Why (theory — DISPROVEN):** we thought Unity's render worker thread handed the single ZFA/Vulkan GL context between two threads → device-lost → infinite `SDL_GL_DeleteContext` teardown. WRONG. The loop was box64's SDL dynapi remap off-by-one routing `SDL_GL_SwapWindow` into `SDL_GL_DeleteContext` (fixed in `wrappedsdl2.c`). With that fixed, MT rendering can stay ON and the game runs unpatched.
- **Status:** OBSOLETE / NOT applied. (Was briefly applied 2026-05-31 as a test; a pristine unpatched instance now boots fine, so the edit was reverted/abandoned.)
- **How applied:** pull via `adb exec-out run-as ... cat` (cmd `>` for raw bytes) → edit with **UnityPy** (`obj.read_typetree(); tt["m_MTRendering"]=False; obj.save_typetree(tt); env.file.save()`) → push back via `adb push <local> /data/local/tmp/x; adb shell chmod 644 /data/local/tmp/x; run-as com.rimdroid cp /data/local/tmp/x <dst>`. (NOTE: `run-as` can read /data/local/tmp with chmod 644; it CANNOT read /sdcard; piping base64 through `adb shell` stdin LOSES bytes — don't.)
- **Phase-2 migration:** the universal fix is to add a context-release/unbind to **libzfa** (rebuild Mesa with `zfaReleaseCurrent()` → `st_api->make_current(NULL)`), then honour `MakeCurrent(NULL)` in box64 so the context can migrate between threads. That keeps multithreaded rendering and lets the game stay unmodified → this game patch can then be removed.

---

## Tried & reverted (not active)

### `RimWorldLinux_Data/boot.config` — gfx-jobs disable
- Appended `gfx-enable-gfx-jobs=0`, `gfx-enable-native-gfx-jobs=0`, `gfx-disable-mt-rendering=1`.
- **Result:** ineffective (Unity stayed `threaded=1`); added make-current churn. **REVERTED** to the original 4 lines (`wait-for-native-debugger=0`, `vr-enabled=0`, `hdr-display-enabled=0`, `gc-max-time-slice=3`).

---

## Related (NOT game patches — our side, for reference; these ship in the APK)
- box64 launch args appended in `rimdroid.c`: `-screen-fullscreen 0 -screen-width <native> -screen-height <native>` (native surface dims, e.g. 2340×1080).
- `rimdroid.c` renders into the native surface (no `setBuffersGeometry`); UI size via the render-scale slider (`setFixedSize(view*scale)`), orientation Zomdroid-style (landscape + identity buffer transform).
- box64 `wrappedsdl2.c`: **the real fix** — SDL dynapi remap (correct slots: SwapWindow 522, DeleteContext 523), native display-mode hooks, GL texture-param proxies, distinct context handles, plus the touch→SDL event injection (PollEvent/GetMouseState).
