# Brief v14 — All crashes fixed; the wall is now GPU memory: zink retains gigabytes of un-reclaimed staging during RimWorld 1.6's atlas bake and Android LMK kills the app. How do we make zink/Turnip reclaim staging aggressively (or shrink the peak)?

## Where we are (all verified on device, S25 Ultra 12GB / Adreno 830)
RimWorld 1.6 under box64 renders via Unity GLCore → our glX bridge → ZFA (Mesa 25.2.4 zink + custom
ANativeWindow kopper winsys) → Turnip 25.1 on kgsl. The loading screen renders; loading proceeds through the
splash-unload into RimWorld's TEXTURE ATLAS BAKE, then Android's low-memory killer terminates the process
(signal 9, `am_low_memory` storm; NO crash — every crash bug found earlier is fixed, incl. a
zink_kopper_present_queue NULL-dt race we patched with a guard that now fires and works:
"RDZFA present_queue: no dt/present, skipping").

## The measured memory picture at death (~90-110 s in)
- Process RSS climbs linearly to **~5.2-5.7 GB → LMK kill**. smaps: **5.11 GB of 5.20 GB = kgsl-3d0 GPU
  BOs (98%)**; guest heap (Mono/Unity CPU) is trivial.
- GL-level demand (we shim & sum EVERY allocation entry point in box64):
  **textures = 1282 MB in 3571 creations** (mostly 4096×4096/4096×2048 RGBA8 + DXT5 atlases and their
  sources; sizes are all sane), buffers = 4 MB / 118 calls, renderbuffers = 0, TexStorage3D ≈ 0.
- zink-level (instrumented `zink_bo.c` around `vkAllocateMemory`/`bo_destroy`):
  last print before death **gross=2833 MB, net=2539 MB, calls=464; freed only 294 MB** — and the print
  cadence (every 256 MB) means net kept climbing after; kgsl's 5.1 GB is consistent with zink net at death.
  Repeating pattern: pairs of ~64 MB allocations (a 4096×4096 image + its staging copy), plus ONE
  **250 MB single allocation (heap=3, type=0 — host-visible!)**.
- Upload pacing we added (forced `glFlush` every 192 MB of uploads, via our GL shims) fixed an earlier layer
  (kgsl's ~3 GB cap caused mmap-ENOMEM device-lost → gone, 82 fails → 0), but flush alone only SUBMITS —
  staging is reclaimed lazily on batch-state recycle, and the numbers show frees lag allocations by ~2 GB+.
- A periodic `glFinish` in the pacing crashed zink earlier — BUT that crash symbolized to the SAME
  `zink_kopper_present_queue` NULL-dt bug we have since guarded, so glFinish-pacing may be safe NOW.
- zink's dead-BO reuse cache (pb_cache, default total_mem/8) is already hard-capped at 256 MB by us — no
  effect on the curve, confirming the retained gigabytes are LIVE/in-flight, not cache garbage.
- Content is Core-only (no DLC), RimWorld `textureCompression=True`. Unity `globalTextureMipmapLimit=1`
  breaks RimWorld's full-res→atlas `Graphics.CopyTexture` (region mismatch spam) — dead end.

## Calibration point (important)
**RimWorld 1.6 loads and plays fine on the SAME phone via Winlator/GameHub** (Windows build under
Wine + DXVK → the same Turnip/kgsl driver, same 12 GB, same LMK). So the game's inherent memory demand
FITS this device — DXVK's memory manager (chunked suballocation, eager recycling of staging) keeps the
peak survivable where our zink path does not. The gigabytes of retained staging below are OUR stack's
abnormal overhead, not the game's requirement. Target: peak comparable to DXVK's (~3-3.5 GB).

## Interpretation
Live textures (~1.3-1.6 GB: sources + atlases coexist during the bake) are irreducible without content
changes, but the EXTRA ~2-3 GB is **staging buffers waiting for reclaim**: RimWorld uploads the whole
1.3 GB+ inside one long frame; zink allocates a staging BO per upload, frees it only when the batch state
recycles; batch states recycle lazily (Mesa 25.3 has "defer batch state resets more competently",
"reset batch states on destroy" — we're on 25.2.4); the GPU falls behind the CPU-side upload storm, so
staging piles up ~1:1 with live data, doubling-tripling the peak. Peak > what LMK tolerates (~5.5 GB on a
12 GB phone with One UI).

## Questions
1. **The cleanest way to bound staging in zink 25.2.4?** Candidates we see — which is right?
   a) Re-enable our pacing `glFinish` every N hundred MB now that the kopper NULL-dt guard exists (full
      pipeline drain → batch states recycle → staging freed). Risks?
   b) A zink knob/patch to cap in-flight batch states or force `zink_batch_reset_all` when
      outstanding-staging exceeds a threshold (we rebuild libzfa in ~6 min via CI, happy to carry a patch).
   c) Cherry-pick the 25.3 batch-state lifecycle patches onto 25.2.4 — do they actually make reclaim
      EAGER, or only fix crashes?
   d) `ZINK_DEBUG=flushsync` or glthread/tc tuning — anything that ties staging release to our forced
      flushes rather than batch recycling?
2. **What is the single 250 MB host-visible allocation (heap=3/type=0)?** Suspects: Unity's
   AsyncUploadManager ring buffer (QualitySettings.asyncUploadBufferSize — but Unity default is 4-16 MB);
   a zink internal (sparse? copy-box staging coalescing?); something RimWorld sets. If it's Unity's async
   upload ring, can we shrink it via a serialized QualitySettings field (globalgamemanagers is editable)?
3. **Can we make Unity upload LESS during the bake?** RimWorld creates full Texture2Ds for every source
   sprite, bakes atlases via Graphics.CopyTexture, then (presumably) frees sources. Any Unity 2022 GLCore
   flag to keep sources CPU-side / delay GPU upload until first use (uploadhandler tweaks,
   `-force-gles-texturestorage-off`-style)? Or a Mono-side hook (we have working Mono reflection) to force
   `Resources.UnloadUnusedAssets`/GC mid-bake so sources drop earlier?
4. **kgsl/LMK angle**: GPU BOs are unswappable and count fully in RSS. Any way to make kgsl allocations
   count less against the app (ION/dmabuf heap flags visible in tu_knl_kgsl.cc — `bo_init_new_dmaheap`
   path with KGSL_ION_SYSTEM_HEAP_MASK), or is that fixed by the kernel? (Non-rooted device.)
5. Sanity-check the plan: our next intended step is (a) re-enable glFinish pacing at 512 MB intervals
   behind the kopper guard, and if that's not enough (b) patch zink to eagerly destroy staging on our
   forced flushes. Better ideas welcome — especially ones that also help the ~1.3 GB live floor (e.g. a
   CopyTexture-safe way to halve texture resolution, since globalTextureMipmapLimit is not it).

## Assets
- box64 fully ours: every GL entry point shimmed/instrumented (allocation accounting incl. per-call sizes);
  upload pacing hooks in place; Mono reflection harness available.
- libzfa CI rebuild pipeline (~6 min, unstripped symbols, custom patches — pb_cache cap, kopper guards,
  zink_bo accounting all live in the current build).
- UnityPy edits of globalgamemanagers verified (QualitySettings round-trip).
- One manual unlock per device run; batch diagnostics preferred.
