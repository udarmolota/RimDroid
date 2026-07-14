# Brief — Unity's runtime BC-compression shader hangs the GPU on zink→Turnip→Adreno 830

## Goal
Make RimWorld 1.6's **runtime texture compression** work on Android so DLC (Biotech, etc.) fits in RAM
on 8 GB devices. Uncompressed atlases cost ~2× the memory and LMK-kill the process on 8 GB phones
(survives only on the 12 GB S25 Ultra, and barely). This is a **GPU/Mesa problem, separate from our
box64 CPU "deep bug."**

## Stack
RimWorld 1.6 (Unity 2022.3, GLCore renderer) → box64 (x86_64→arm64) → our glX→ZFA bridge →
**Mesa zink 25.2.4** (GL-on-Vulkan) → **Turnip 25.1** → **Adreno 830** (Qualcomm SD 8 Elite, kgsl),
Android. `GL_RENDERER = "zink Vulkan 1.4 (Adreno (TM) 830 (v25.1) (MESA_TURNIP))"`.

## The bug
With `textureCompression=True`, RimWorld's atlas bake compresses atlases to **BC7 (BPTC, GL 0x8e8c)** and
**DXT5 (S3TC, GL 0x83f3)** using Unity's built-in **`Hidden/CompressBC`** shader. Running that shader on
this path **hangs the GPU** → `VK_ERROR_DEVICE_LOST` at `vkQueueSubmit`, and kgsl records a **hang-class
GPU fault** (`/sys/class/kgsl/kgsl-3d0/gpufaults` increments; **zero pagefaults** → not a bad memory
access, a genuine hang / watchdog `ft_long_ib_detect`). The process then can't render (context lost).

With `textureCompression=False` (our shipped default) the shader never compiles and 1.6 loads fine — but
uncompressed → the memory blocker for DLC.

## The shader (full text attached: `CompressBC_shader.glsl`, ~100 KB GLSL)
It is the **only** shader in the app that writes a `writeonly uniform uimage2D _Target` (via `imageStore`).
A fragment shader acting as a compute-style BC encoder. Characteristics:
- Reads `sampler2D _Source`; uniforms `_Quality`, `_mipLevel`; a 17-element `vec4 ImmCB_5[17]` constant
  array initialised inline at the top of `main()`.
- **16 `while(true){ if(cond){break;} … }` loops** (HLSLcc's lowering of `for`), several NESTED.
- Heavy bit manipulation: `bitfieldExtract` ×25, `bitfieldInsert` ×13, `floatBitsToUint`/`uintBitsToFloat`.
- **Crucially, several loops use a FLOAT as the loop counter and test the exit via
  `floatBitsToInt(counter) >= N`** (a float reinterpreted as int for the comparison). e.g.:
  `while(true){ u_xlatb71 = floatBitsToInt(u_xlat17.x) >= int(u_xlatu32); if(u_xlatb71){break;} … }`.

## What we've tried (box64-side, since we intercept `glShaderSource`)
A shim (`rd_bc_bound_loops` in box64 `wrappedsdl2.c`) detects the compressor (by `uimage2D`) and rewrites
every `while(true){` into a hard-capped `for(int _g=0;_g<CAP;++_g){` (the real `if(break)` stays; the cap
is a safety net). Results on device:
- **CAP=256 → DEVICE_LOST** (still hangs).
- **CAP=16 → PHASIC**: some runs SURVIVE the compressor (no device-lost) and **RSS drops to ~814 MB**
  (proving the memory win — compression actually works when it survives), then continue loading; other
  runs still **DEVICE_LOST**. So loop-bounding helps sometimes but is **not reliable**.
The phasic behaviour (a hard finite cap sometimes still DEVICE_LOSTs) is puzzling: if the hang were a
pure infinite loop from a miscompiled float↔int exit test, ANY finite cap should stop it.

## Calibration (important)
The **same game on the same phone runs fine via Winlator/GameHub** (Windows build under Wine + DXVK →
the SAME Turnip/kgsl/Adreno 830 driver). So the GPU **can** do BC compression; the problem is specific to
our **GL(zink)→Turnip** path and/or Turnip's handling of THIS shader, not the hardware.

## Questions
1. Why would Turnip/Adreno 830 hang on this shader via zink when DXVK's Vulkan path doesn't? Is it most
   likely (a) a **Turnip ir3 compiler** problem (e.g. trying to fully unroll a data-dependent `while` →
   compile-time blowup or bad code), (b) a **runtime GPU hang** (the shader genuinely loops forever due to
   a miscompiled `floatBitsToInt`-based loop exit, or is simply too heavy per 4096² atlas and trips the
   watchdog), or (c) a **zink→Turnip translation** issue (imageStore in a fragment shader, image
   layout/barrier, SPIR-V the ir3 backend mishandles)?
2. Does the **phasic** result (CAP=16 sometimes survives, sometimes DEVICE_LOSTs) point to a race /
   timing-dependent codegen rather than a deterministic infinite loop? What would make a hard-capped loop
   *still* hang?
3. Best fix, given we can (i) transform the GLSL before zink sees it, (ii) rebuild libzfa (Mesa+Turnip) in
   ~6 min CI, (iii) not touch the app's Unity: 
   - rewrite the **float-counter loops to true integer counters** in the shim (avoid the
     `floatBitsToInt`-in-loop-condition pattern)?
   - split the compression into smaller dispatches (pace it) so no single submit trips the watchdog?
   - a **Turnip/zink knob** (TU_DEBUG=..., disable loop unrolling, force a compile path) that tames it?
   - make Unity use a **CPU** BC path instead (any GLCore/QualitySettings lever)?
4. Is there a known Turnip/ir3 bug with **`while(true)`+`floatBitsToInt` loop exits**, **`imageStore` in
   fragment shaders**, or **BC7/BPTC compression compute shaders** on a6xx/a8xx?

## Assets
- Full shader: `docs/CompressBC_shader.glsl`.
- box64 fully ours (intercepts + can transform GLSL at `glShaderSource`); env `RIMDROID_BC_CAP` sets the
  loop cap, `RIMDROID_BC_NOCAP=1` disables the transform; `rd_texcompress` marker flips Unity's
  `textureCompression=True` for testing.
- libzfa = Mesa 25.2.4 + our patches (zink kopper/ANativeWindow winsys, reaper), rebuilt via CI; Turnip is
  the freedreno driver bundled per-GPU. Deterministic repro on device (adb-driven, one manual unlock).
