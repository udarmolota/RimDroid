# Brief: blurry UI text on RimWorld 1.5 under MobileGlues (2026-08-16) — **RESOLVED**

## Resolution

**MobileGlues silently discards Unity 2019's `GL_RED` `glTexSubImage2D` transfers into the dynamic
font atlas.** The backend texture is never updated, so the glyphs never reach the GPU and every
letter renders as a filled block. Fix: replay exactly those transfers straight to GLES, bypassing
the translator and preserving the real binding — `RIMDROID_GLT_FONTFIX=1`, which the launcher sets
only when the instance is not the 1.6/X11 one (that path is unaffected).

Two things about this are worth carrying forward:

- **The symptom was named wrong for hours.** "Blurry" drove the search toward resolution, scaling
  and filtering; the screenshot showed *solid filled blocks*, which is a different failure entirely
  — saturated coverage, not lost sharpness. Ask for a picture before naming a symptom.
- **Everything in §2 below still holds** and was worth doing: it is what left the upload path as the
  only place a defect could hide, which is where it was.

The investigation that got there follows, unchanged.

---


Long-standing cosmetic bug, now the top open item after the threaded-textures hunt closed. Every
claim below is from a device run on the reporter's S25 (Adreno 830, Android 16) and was verified in
the logs before being believed — the same discipline that saved the previous investigation.

---

## 1. The symptom, and the two controls that bound it

RimWorld **1.5** on **MobileGlues**: UI text is soft — glyph edges look smeared rather than
aliased. Everything else looks correct.

| configuration | text |
|---|---|
| 1.5 + MobileGlues | **blurry** |
| 1.5 + Zink/ZFA | sharp |
| 1.6 + MobileGlues | sharp |

So it is neither "MobileGlues cannot draw text" nor "1.5 draws text badly": it is the combination.
The two versions differ in Unity (1.5 = 2019.4.30, 1.6 = 2022.3.35) and in our own path — 1.5 goes
through SDL2, 1.6 through the X11/GLX bridge.

## 2. Refuted, each with the run that killed it

1. **Mip sampling of the glyph atlas.** The threaded-textures work left `RIMDROID_GLT_NOMIP=tex`
   on by default for MobileGlues, so 1.5 now clamps minification to level 0 — log confirms
   `NOMIP on (texture objects only)`. Text unchanged. (This also killed the neat hypothesis that
   the same defect was behind both bugs, which the 1.6 mip-filter sweep had suggested.)
2. **Fullscreen vs windowed.** We pin `fullscreen=True` on the SDL route and `fullscreen=False` on
   the X11 route. Added `RIMDROID_WINDOWED=1` so 1.5 can run windowed exactly like 1.6. Text
   unchanged, and no letterboxing appeared.
3. **Render scale.** 1.5 was rendering at 72% (1685x778 of a 2340x1080 panel). Forced to 100%.
   Text unchanged.
4. **Resolution as such.** 1.6 renders at **1568x724** — *lower* than 1.5's 1685x778 — and its text
   is sharp. Whatever this is, it is not lost pixels.
5. **The translator's own diagnostics.** A MobileGlues build with `LOG_W`/`LOG_E` compiled in (the
   shipped build has them compiled out, and its `glGetError` returns `GL_NO_ERROR` unconditionally)
   reports exactly one thing across a full 1.5 session: `Stub function: glPolygonMode`. No format
   complaint, no translation failure.
6. **Shader precision.** MobileGlues injects `precision highp float;` / `highp int;` into every
   translated shader (`gl/glsl/glsl_for_es.cpp`), so the classic mediump-varyings blur is not it.
7. **Legacy format mapping.** Unity 2019 can hand GL formats that GLES core dropped;
   MobileGlues maps them to R8 plus a texture swizzle (`gl/texture.cpp`). Lossless on paper, and
   §5 shows it never complains.

**A trap worth recording:** the box64 SDL shim logged `GetDesktopDisplayMode => 1024x768@60` while
the code beside it actually reports the native size — a leftover string from a May experiment. It
sent this investigation down a wrong path for an hour. Fixed to print the real values.

## 2a. What the instrument found (and how close it came)

The glyph atlas was dumped from a run on each renderer and the two are **byte-identical**, so the
data leaving the game is the same either way. Asked directly of the driver (via `dlsym` on
`libGLESv2.so`, not through the translator), the atlas is `R8` with swizzle `RGB=ZERO, A=RED` —
textbook-correct Alpha8 emulation, on the 256px atlases and the 1024px font atlas alike. Every
swizzle call the game makes arrives intact: 64 traced, zero mismatches.

That cleared the whole texture *state* side and left the *transfer* side — which is where the
defect was: the full-texture uploads were going through and the sub-uploads were not.

**A probe trap worth remembering:** the first swizzle trace queried the driver *before* forwarding
the call, so it reported the pre-call state — identity, always — and came within one screenshot of
"proving" a swizzle drop that had not happened. A probe that reads state must read it after the
call it is measuring.

## 3. The instrument

Since geometry explanations are exhausted, the question was narrowed to: **is the glyph atlas
already soft in memory, or is it crisp and softened on the way to the screen?** Those are different
bugs and only the data can separate them.

`RIMDROID_GLT_DUMPTEX=1` writes texture uploads out as raw 8-bit rows (dimensions and format in the
file name) so they can be viewed off-device. First attempt filtered to single-channel uploads
between 64 and 256 px and caught two utility textures — one entirely black, one flat grey — while
the font atlas was created later and missed the window entirely.

Now widened: any 8-bit upload that is single-channel and ≥256, or ≥512 of any channel count, plus a
capped inventory line (`TEXLIST … tex=N lvl=L WxH at X,Y fmt=0x….`) for **every** 8-bit upload, so
the atlas identifies itself instead of being guessed at. That run is pending.

## 4. Open questions for the reviewer

1. What in Unity 2019.4's UI-text path differs from 2022.3 in a way a GL→GLES translator would
   handle differently? Dynamic font atlas format (A8 vs R8), atlas rebuild policy, or the material
   and shader used for text?
2. If the dumped atlas turns out crisp, what is the sharpest next probe on the sampling side, given
   that filters (mip and otherwise), precision, and format mapping are already excluded?
3. Is there a cheap way to compare the *same* glyph as MobileGlues and Zink deliver it — short of
   the full external-oracle GL test parked after the threaded-textures investigation?
4. RimWorld draws its UI text through Unity IMGUI. Is there anything in that path (font material,
   point-filtered atlas, pixel-perfect offset) that a translation layer could silently change?

## 5. Reference

| what | where |
|---|---|
| glyph dump + texture inventory | `box64/src/wrapped/wrappedsdl2.c`, `rd_dump_texture` |
| mip-filter clamp (`NOMIP`) | same file, `rd_nomip_mode` / `rd_nomip_filter_mode` |
| fullscreen vs windowed pin | `app/src/main/java/com/rimdroid/GameActivity.java`, `pinGamePrefs` |
| render scale | `LauncherPreferences.getEffectiveRenderScale` |
| the threaded-textures investigation this follows | `docs/BRIEF_mg_threaded_red_textures_round2.md` |
