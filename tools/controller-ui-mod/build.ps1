param(
    [Parameter(Mandatory = $true)]
    [string]$ManagedDir,

    [string]$Output = "$PSScriptRoot\..\..\app\src\main\assets\builtinmods\RimDroidControllerUI\Assemblies\RimDroid.ControllerUI.dll"
)

$ErrorActionPreference = "Stop"

$assemblyCSharp = Join-Path $ManagedDir "Assembly-CSharp.dll"
$mscorlib = Join-Path $ManagedDir "mscorlib.dll"
if (!(Test-Path -LiteralPath $assemblyCSharp) -or !(Test-Path -LiteralPath $mscorlib)) {
    throw "ManagedDir must contain Assembly-CSharp.dll and mscorlib.dll from the developer's own RimWorld copy."
}

$sdk = (& dotnet --list-sdks | Select-Object -Last 1).Split(' ')[0]
$csc = Join-Path $env:ProgramFiles "dotnet\sdk\$sdk\Roslyn\bincore\csc.dll"
if (!(Test-Path -LiteralPath $csc)) {
    throw "Roslyn compiler not found: $csc"
}

$outDir = Split-Path -Parent $Output
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

& dotnet $csc /nologo /noconfig /nostdlib+ /target:library /optimize+ /deterministic+ `
    /langversion:7.3 "/reference:$mscorlib" "/reference:$assemblyCSharp" `
    "/out:$Output" "$PSScriptRoot\ControllerUiMod.cs"
if ($LASTEXITCODE -ne 0) {
    throw "Controller UI mod compilation failed ($LASTEXITCODE)."
}

Write-Host "Built $Output"
