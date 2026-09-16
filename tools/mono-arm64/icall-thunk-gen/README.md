# Unity internal-call thunk generator

This tool consumes the Unity-only TSV produced by `icall-audit` and emits
typed ARM64 C callbacks plus a binary-search registration table.

```powershell
dotnet run --project tools/mono-arm64/icall-thunk-gen/IcallThunkGen.csproj -- `
  "$env:TEMP\rimworld-unity-icalls.tsv" `
  "$env:TEMP\rimworld-unity-icalls.generated.c"
```

An optional third argument limits the number of generated methods for quick
compile checks. The generated file expects Box64's `callback.h` and provides
`rd_mono_icall_bind(name, guest)`, which returns the native thunk for a guest
x86-64 function address.

`testdata/representative.tsv` covers every supported scalar type and the
largest 23-argument shape found in RimWorld. The spike workflow generates and
cross-compiles this fixture for Android ARM64 before building Box64.
