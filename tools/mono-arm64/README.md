# Mono ARM64 feasibility tools

This directory is an isolated research spike. It does not replace the game's
Mono runtime and is not part of the APK build.

**Status:** the feasibility phase is complete. The ARM64 candidate builds, passes
the ABI inventory and the native probe, and all five bridge gates of the
[Box64 probe](box64-probe/README.md) (forward ABI, reverse internal calls, GC
roots, exceptions, signals) pass on a Samsung S25. Results and the plan for the
Unity integration phase: [`docs/MONO_ARM64_SPIKE.md`](../../docs/MONO_ARM64_SPIKE.md).

| Directory | Purpose |
|---|---|
| `native-probe` | Managed probe assembly and an Android ARM64 embedding executable |
| `box64-probe` | x86_64 embedding executable run through Box64, one gate per method |
| `icall-audit` | Catalog of Unity internal calls reduced to Box64 ABI signatures |
| `icall-thunk-gen` | Generator of typed ARM64 internal call thunks for the integration phase |

## Build a source candidate

Unity 2022.3.35f1 does not ship an Android ARM64 Mono binary, but its public
`unity-2022.3-mbe` source contains an AArch64 Android target. On a Linux or
macOS build host:

```bash
git clone --filter=blob:none --no-checkout \
  https://github.com/Unity-Technologies/mono.git unity-mono
git -C unity-mono checkout --detach \
  c11bc9adba5d9dcad976d64b295bca8424fa41cd
git -C unity-mono submodule update --init --depth 1 external/bdwgc

./tools/mono-arm64/build-unity-mono-arm64.sh \
  unity-mono "$ANDROID_NDK_ROOT" out/mono-arm64
```

The manual `Build Unity Mono ARM64 spike` GitHub Actions workflow performs the
same build with NDK r19c and uploads only a research tarball. It does not build
or modify an APK. The source build intentionally keeps debug symbols for the
first harness runs.

## ABI inventory

`audit-mono-abi.ps1` extracts the Mono names embedded in `UnityPlayer.so`,
intersects them with the exports of the known-good x86_64 Unity Mono, and can
compare that baseline with an ARM64 candidate.
`baseline-required-exports.txt` freezes that 286-symbol intersection for CI;
regenerate it only when either frozen runtime hash changes.

Baseline-only run:

```powershell
./tools/mono-arm64/audit-mono-abi.ps1 `
  -UnityPlayer C:/path/to/RimWorld/UnityPlayer.so `
  -ReferenceMono C:/path/to/RimWorld/RimWorldLinux_Data/MonoBleedingEdge/x86_64/libmonobdwgc-2.0.so
```

Candidate gate:

```powershell
./tools/mono-arm64/audit-mono-abi.ps1 `
  -UnityPlayer C:/path/to/RimWorld/UnityPlayer.so `
  -ReferenceMono C:/path/to/RimWorld/RimWorldLinux_Data/MonoBleedingEdge/x86_64/libmonobdwgc-2.0.so `
  -CandidateMono C:/path/to/arm64/libmonobdwgc-2.0.so `
  -Json
```

Any nonzero exit means the candidate failed the gate: internally, `2` means it
is not ARM64 and `3` means it lacks symbols exported by the reference runtime
and named by UnityPlayer. Passing the
gate proves symbol presence only; callback ABI and Unity object-layout
compatibility still require dedicated harnesses.

## Native ARM64 probe

`native-probe` is the next gate and remains completely outside the APK. Its
build script creates an Android ARM64 executable and a small managed assembly.
The executable loads a candidate Mono with `dlopen`, initializes its JIT, and
runs either a minimal method or a thread/allocation/GC stress method.

```powershell
./tools/mono-arm64/native-probe/build.ps1
```

On an Android test environment, invoke it as:

```text
mono_arm64_probe LIBMONO MANAGED_DIR CONFIG_DIR \
  RimDroid.MonoArm64Probe.dll RunBasic
```

Run `RunStress` only after `RunBasic` prints `PROBE verdict=PASS`.
