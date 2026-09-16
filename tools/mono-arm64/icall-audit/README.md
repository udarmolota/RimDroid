# Unity internal-call ABI audit

This tool reads managed assembly metadata without loading the assemblies. It
lists every `InternalCall` method and reduces its managed signature to the ABI
alphabet used by Box64 wrappers.

```powershell
dotnet run --project tools/mono-arm64/icall-audit/IcallAudit.csproj -- `
  "C:\path\to\RimWorldLinux_Data\Managed" `
  "$env:TEMP\rimworld-icalls.tsv"
```

The TSV contains the assembly, Mono registration name, instance-method flag,
managed return and parameter types, and normalized ABI signature. The command
returns exit code 10 if a type could not be classified.

For the current RimWorld 1.6 managed assemblies the audit finds 9,558 internal
calls and 517 normalized ABI shapes. All 8,847 UnityEngine methods classify
cleanly and have unique registration names. The only unresolved method is a
`mscorlib` constructor taking `ReadOnlySpan<char>` by value; it is a Mono
runtime internal call rather than a Unity native callback.
