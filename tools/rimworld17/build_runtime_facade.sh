#!/bin/sh
# Build the System.Runtime.dll of RimWorld 1.7 on .NET: the stock facade plus forwarders to the
# stand-ins in clrhooks/WaylithClrStubs.cs. Kept in the repository next to RimDroidClrHooks.dll, so
# ordinary builds need no C# compiler; rebuild it when the .NET version in app/build.gradle changes.
# usage: CSC_DLL=.../csc.dll DOTNET=.../dotnet build_runtime_facade.sh [framework-dir]
# (Unity's Editor has both: Data/DotNetSdkRoslyn/csc.dll and Data/NetCoreRuntime/dotnet)
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
FW=${1:-$ROOT/app/build/dotnet/assets/dotnet/framework}
OUT=$ROOT/app/src/dotnet/assets/dotnet/hooks
test -f "$FW/System.Runtime.dll" || { echo "no .NET framework in $FW (build once with dotnetEngine=true)" >&2; exit 1; }
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
native() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
HOST=$(ls -d "$(dirname "$DOTNET")"/shared/Microsoft.NETCore.App/* | tail -1)
for f in "$HOST"/System*.dll "$HOST"/netstandard.dll; do
    case "$f" in *.Native.dll) ;; *) printf '%s\n' "-r:\"$(native "$f")\"" >> "$WORK/refs.rsp" ;; esac
done
"$DOTNET" "$CSC_DLL" -nologo -noconfig -nostdlib+ -optimize+ "-out:$(native "$WORK/AddForwards.dll")" \
    "@$(native "$WORK/refs.rsp")" "$(native "$HERE/runtimefacade/AddForwards.cs")"
printf '%s\n' '{"runtimeOptions":{"tfm":"net6.0","rollForward":"Major","framework":{"name":"Microsoft.NETCore.App","version":"6.0.0"}}}' \
    > "$WORK/AddForwards.runtimeconfig.json"
"$DOTNET" exec "$(native "$WORK/AddForwards.dll")" "$(native "$FW/System.Runtime.dll")" "$(native "$OUT/System.Runtime.dll")" \
    RimDroidClrHooks WaylithClr.FieldInjection WaylithClr.FieldInjectionApplyResult WaylithClr.FieldInjectionBatch \
    WaylithClr.FieldInjectionBatchStatus WaylithClr.FieldInjectionRefusal WaylithClr.ILOffsetMapEntry \
    WaylithClr.InjectedField WaylithClr.MethodBodyUpdater
echo "built $OUT/System.Runtime.dll"
