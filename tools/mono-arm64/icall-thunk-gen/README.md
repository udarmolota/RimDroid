# Unity internal-call thunk generator

This tool consumes the Unity-only TSV produced by `icall-audit` and emits
typed ARM64 C callbacks plus a binary-search registration table.

The generated prototypes preserve signed and unsigned 8-, 16-, 32-, and
64-bit integer types. This is required for correct extension at both ABI
boundaries; treating every small managed integer as `int32_t` is unsafe.

```powershell
dotnet run --project tools/mono-arm64/icall-thunk-gen/IcallThunkGen.csproj -- `
  "$env:TEMP\rimworld-unity-icalls.tsv" `
  "$env:TEMP\rimworld-unity-icalls.generated.c"
```

An optional third argument limits the number of generated methods for quick
compile checks. The generated file expects Box64's `callback.h` and provides
`rd_mono_icall_bind(name, guest)`, which returns the native thunk for a guest
x86-64 function address.

Every thunk runs its guest call inside the exception bridge proven by the P4
probe. The includer defines `RD_MONO_ICALL_BRIDGE_DEFINED` and supplies
`rd_icall_frame_t`, `rd_icall_enter()`, `rd_icall_leave()` and
`rd_icall_float_result()`; otherwise the generated file includes
`rd_mono_icall_bridge.h` (the fixture has a declaration-only copy).
`rd_icall_leave()` returns nonzero when the x86 side raised a managed exception,
and the thunk then discards the result so Mono throws the pending exception in
managed code.

Two ABI details are handled in the generated code:

- A `float` result is read from the low 32 bits of the guest `xmm0`.
  `RunFunctionFmtD` reads a `double` and would return garbage for the 671
  RimWorld internal calls that return `float`.
- 8- and 16-bit arguments are passed to the guest as full 32-bit values
  (`i`/`u`), already sign- or zero-extended by C promotion.

The RimWorld 1.6 output is checked in as
`box64/src/wrapped/wrappedlibmonobdwgc_icalls.h` because CI has no game
assemblies. Regenerate it with the unity-only TSV from `icall-audit` whenever
the game's Unity version changes.

`testdata/representative.tsv` covers every supported scalar type and the
largest 23-argument shape found in RimWorld. The spike workflow generates and
cross-compiles this fixture for Android ARM64 before building Box64.
