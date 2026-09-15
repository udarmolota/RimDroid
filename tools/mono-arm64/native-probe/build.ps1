[CmdletBinding()]
param(
    [string] $NdkRoot,
    [string] $CompilerMonoRoot = "C:/Program Files/Unity/Hub/Editor/2019.4.30f1/Editor/Data/MonoBleedingEdge",
    [string] $OutDir = "$PSScriptRoot/out"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if (-not $NdkRoot) {
    $sdkRoot = if ($env:ANDROID_SDK_ROOT) {
        $env:ANDROID_SDK_ROOT
    } elseif ($env:ANDROID_HOME) {
        $env:ANDROID_HOME
    } else {
        Join-Path $env:LOCALAPPDATA "Android/Sdk"
    }
    $NdkRoot = Join-Path $sdkRoot "ndk/27.0.12077973"
}

$hostRoot = Get-ChildItem -LiteralPath (Join-Path $NdkRoot "toolchains/llvm/prebuilt") -Directory |
    Select-Object -First 1
if (-not $hostRoot) { throw "NDK LLVM host tools not found under $NdkRoot" }

$clang = Join-Path $hostRoot.FullName "bin/aarch64-linux-android30-clang.cmd"
$mono = Join-Path $CompilerMonoRoot "bin/mono.exe"
$mcs = Join-Path $CompilerMonoRoot "lib/mono/4.5/mcs.exe"
foreach ($tool in @($clang, $mono, $mcs)) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) { throw "Tool not found: $tool" }
}

New-Item -ItemType Directory -Path $OutDir -Force | Out-Null

& $clang -std=c11 -Wall -Wextra -Werror -fPIE -pie `
    "-Wl,-z,max-page-size=16384" `
    "$PSScriptRoot/mono_arm64_probe.c" -ldl `
    -o "$OutDir/mono_arm64_probe"
if ($LASTEXITCODE -ne 0) { throw "ARM64 native probe compilation failed" }

& $mono $mcs -nologo -target:library -optimize+ `
    "-out:$OutDir/RimDroid.MonoArm64Probe.dll" `
    "$PSScriptRoot/Probe.cs"
if ($LASTEXITCODE -ne 0) { throw "Managed probe compilation failed" }

Write-Output "Built: $OutDir/mono_arm64_probe"
Write-Output "Built: $OutDir/RimDroid.MonoArm64Probe.dll"
