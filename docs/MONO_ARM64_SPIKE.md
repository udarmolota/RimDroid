# Native Mono ARM64 spike

## Objective

Determine whether RimDroid can run the x86_64 Unity 2022 player under Box64
while executing RimWorld managed code in a native Android ARM64 Unity Mono.
The existing x86_64 Mono path must remain the default and fallback.

## Frozen baseline

- RimWorld `1.6.4518 rev91`
- Unity `2022.3.35f1`
- `UnityPlayer.so` SHA-256:
  `4c64f497b3f67fdb59c2e8bdc03d45a9dfbde51dd9300d288640f80c3737ad04`
- `libmonobdwgc-2.0.so` SHA-256:
  `32eb66eb4992296f44d3fed053fbfcfc87f0dd85e3eb4d91d27576048e2c7280`

RimWorld `1.6.4566 rev575` uses the same UnityPlayer and Mono binaries. Game
revisions are therefore secondary until either runtime hash changes.

The official Unity 2022.3.35f1 Android Support payload contains Mono only for
`armeabi-v7a`; it does not ship `mono/.../arm64-v8a`. This is a packaging and
support-policy limitation, not a missing ARM64 backend in the public source:

- `external/buildscripts/build.pl` in `unity-2022.3-mbe` accepts
  `--androidarch=aarch64` and selects `aarch64-linux-android`, API 21.
- The public source has an Android ARM64 target in `sdks/builds/android.mk` and
  ARM64 JIT support throughout `mono/mini`.
- The stock wrapper intentionally requests only ARMv7 and x86, both with
  `--arch32=1`.

The first reproducible source candidate is commit
`c11bc9adba5d9dcad976d64b295bca8424fa41cd` from 2024-06-25, immediately before
the 2022.3.35f1 Android payload dated 2024-06-26. This is a strong provenance
match, not proof that Unity used that exact commit.

The extracted official ARMv7 runtime is a useful platform control:

- SHA-256: `48b09c12322a5ff02366ce7c5dbccdf7eef6a171ccc71355bddf4b76c68c48c3`
- It exports all `286/286` Mono entry points used by RimWorld's Linux
  `UnityPlayer.so`.
- Therefore the immediate source-build gate is realistic; it does not prove
  callback or object-layout compatibility.

## Architecture

Dropping an ARM64 library into `MonoBleedingEdge/x86_64` cannot work. The spike
requires both directions of an ABI bridge:

```text
x86_64 UnityPlayer -> Box64 wrappedlibmono -> ARM64 Unity Mono
ARM64 Mono/JIT -> generated callback thunk -> x86_64 UnityPlayer
```

## Gates

1. **Candidate provenance:** obtain ARM64 Android Mono from Unity 2022.3.35f1.
   Stock upstream Mono is not an equivalent candidate. Build the Unity fork
   with `tools/mono-arm64/build-unity-mono-arm64.sh`; the manual
   `mono-arm64-spike.yml` workflow packages the runtime and native probe only.
2. **Static ABI:** compare candidate exports with the known-good runtime using
   `tools/mono-arm64/audit-mono-abi.ps1`.
3. **Native harness:** initialize ARM64 Mono directly, load a tiny managed
   assembly, invoke a method, collect garbage, run a worker, and clean up.
4. **Box64 harness:** repeat from a small x86_64 embedding executable through a
   minimal wrapped library.
5. **Reverse callbacks:** prove logging, resolver, profiler, and representative
   internal calls before attempting Unity.
6. **Headless Unity:** run `-batchmode -nographics` and reach the first managed
   RimWorld method.
7. **Game matrix:** menu, map, save/load, DLC, Harmony, then long-run testing.

## Primary risks

- `mono_add_internal_call` stores x86_64 Unity function pointers that native
  ARM64 JIT code cannot call without signature-aware reverse thunks.
- Unity may consume JIT or trampoline pointers returned by Mono and attempt to
  execute ARM64 code as x86_64 guest code.
- P/Invoke from native Mono cannot directly load RimWorld's Linux x86_64 native
  plugins and may need another bridge into Box64.

No app integration should begin until the static gate and both harnesses pass.

## Parallel workstream for Claude

While the runtime candidate is being built, independently audit Box64's
reverse-call boundary. Deliver a report before changing code:

1. For the nine callback-bearing Mono exports reported by
   `audit-mono-abi.ps1`, recover the exact callback prototypes from the pinned
   Unity Mono headers and map each prototype to Box64 `RunFunctionFmt` types.
2. Identify every exported Mono API in the 286-function baseline that accepts
   a function pointer, returns executable/JIT code, or stores a native pointer
   for later managed execution. Do not rely only on names containing
   `callback`.
3. Trace `mono_add_internal_call`: determine how to recover the managed method
   signature at registration or resolution time and where a signature-aware
   ARM64-to-x86_64 thunk can be installed.
4. Find the smallest existing Box64 wrapped library that demonstrates both a
   native-to-guest callback and a returned native function pointer exposed to
   the guest. Cite files and wrapper-generator declarations.
5. End with a staged wrapper plan and explicit no-go conditions. Do not edit
   RimDroid integration or build an APK.
