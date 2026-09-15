[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $UnityPlayer,

    [Parameter(Mandatory = $true)]
    [string] $ReferenceMono,

    [string] $CandidateMono,
    [string] $ReadElf,
    [string] $OutFile,
    [switch] $Json
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-ExistingFile([string] $Path, [string] $Label) {
    if (-not $Path -or -not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Label not found: $Path"
    }
    return (Resolve-Path -LiteralPath $Path).Path
}

function Resolve-ReadElf([string] $ExplicitPath) {
    if ($ExplicitPath) {
        return Resolve-ExistingFile $ExplicitPath "llvm-readelf"
    }

    $command = Get-Command llvm-readelf, llvm-readelf.exe -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($command) {
        return $command.Source
    }

    $repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
    $gradlePath = Join-Path $repoRoot "app/build.gradle"
    $ndkVersion = $null
    if (Test-Path -LiteralPath $gradlePath) {
        $gradle = Get-Content -LiteralPath $gradlePath -Raw
        if ($gradle -match 'ndkVersion\s+["'']([^"'']+)["'']') {
            $ndkVersion = $Matches[1]
        }
    }

    $sdkRoots = @(
        $env:ANDROID_SDK_ROOT,
        $env:ANDROID_HOME,
        $(if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA "Android/Sdk" })
    ) | Where-Object { $_ }

    foreach ($sdkRoot in $sdkRoots) {
        $ndkRoots = if ($ndkVersion) {
            @(Join-Path $sdkRoot "ndk/$ndkVersion")
        } else {
            @(Get-ChildItem -LiteralPath (Join-Path $sdkRoot "ndk") -Directory -ErrorAction SilentlyContinue |
                Sort-Object Name -Descending |
                ForEach-Object FullName)
        }

        foreach ($ndkRoot in $ndkRoots) {
            $prebuiltRoot = Join-Path ([string] $ndkRoot) "toolchains/llvm/prebuilt"
            $hosts = Get-ChildItem -LiteralPath $prebuiltRoot -Directory -ErrorAction SilentlyContinue
            foreach ($hostDir in $hosts) {
                foreach ($toolName in @("llvm-readelf.exe", "llvm-readelf")) {
                    $toolPath = Join-Path $hostDir.FullName "bin/$toolName"
                    if (Test-Path -LiteralPath $toolPath -PathType Leaf) {
                        return (Resolve-Path -LiteralPath $toolPath).Path
                    }
                }
            }
        }
    }

    throw "llvm-readelf was not found. Pass -ReadElf or install the project's Android NDK."
}

function Get-ElfInfo([string] $Path, [string] $ReadElfPath) {
    $raw = (& $ReadElfPath --elf-output-style=JSON --file-header --dyn-syms $Path) -join "`n"
    if ($LASTEXITCODE -ne 0) {
        throw "llvm-readelf failed for $Path (exit $LASTEXITCODE)"
    }

    $documents = $raw | ConvertFrom-Json -Depth 24
    $document = @($documents)[0]
    $exports = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal
    )

    foreach ($entry in $document.DynamicSymbols) {
        $symbol = $entry.Symbol
        if ($symbol.Section.Name -eq "Undefined") { continue }
        if ($symbol.Binding.Name -notin @("Global", "Weak")) { continue }

        $name = [string] $symbol.Name.Name
        if (-not $name) { continue }
        $name = $name.Split('@')[0]
        [void] $exports.Add($name)
    }

    return [PSCustomObject]@{
        Path    = $Path
        Format  = [string] $document.FileSummary.Format
        Arch    = [string] $document.FileSummary.Arch
        Machine = [string] $document.ElfHeader.Machine.Name
        Exports = $exports
    }
}

function Get-MonoNamesFromBinary([string] $Path) {
    # Unity resolves Mono dynamically, so these names live in its string table rather
    # than as normal ELF imports. This is deliberately an over-approximation; intersecting
    # it with the known-good x86 Mono exports removes diagnostic-only names.
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    $ascii = [System.Text.Encoding]::ASCII.GetString($bytes)
    $names = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal
    )
    foreach ($match in [regex]::Matches($ascii, '(?<![A-Za-z0-9_])mono_[A-Za-z0-9_]+')) {
        [void] $names.Add($match.Value)
    }
    return $names
}

function Get-Intersection($Left, $Right) {
    return @($Left | Where-Object { $Right.Contains($_) } | Sort-Object)
}

function Get-Difference($Left, $Right) {
    return @($Left | Where-Object { -not $Right.Contains($_) } | Sort-Object)
}

function Get-Sha256([string] $Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

$UnityPlayer = Resolve-ExistingFile $UnityPlayer "UnityPlayer"
$ReferenceMono = Resolve-ExistingFile $ReferenceMono "Reference Mono"
if ($CandidateMono) {
    $CandidateMono = Resolve-ExistingFile $CandidateMono "Candidate Mono"
}
$ReadElf = Resolve-ReadElf $ReadElf

$unity = Get-ElfInfo $UnityPlayer $ReadElf
$reference = Get-ElfInfo $ReferenceMono $ReadElf
$unityNames = Get-MonoNamesFromBinary $UnityPlayer
$required = @(Get-Intersection $unityNames $reference.Exports)
$notInReference = @(Get-Difference $unityNames $reference.Exports)

$callbackPattern = 'callback|handler|hook|_func$|_install|fallback_register|add_internal_call'
$callbackSurface = @($required | Where-Object { $_ -match $callbackPattern } | Sort-Object)
$unitySpecific = @($required | Where-Object { $_ -like 'mono_unity_*' } | Sort-Object)

$result = [ordered]@{
    unityPlayer = [ordered]@{
        path = $UnityPlayer
        sha256 = Get-Sha256 $UnityPlayer
        arch = $unity.Arch
        machine = $unity.Machine
        monoNamesFound = $unityNames.Count
    }
    referenceMono = [ordered]@{
        path = $ReferenceMono
        sha256 = Get-Sha256 $ReferenceMono
        arch = $reference.Arch
        machine = $reference.Machine
        exportedSymbols = $reference.Exports.Count
    }
    baseline = [ordered]@{
        requiredExportCount = $required.Count
        unitySpecificCount = $unitySpecific.Count
        callbackSurfaceCount = $callbackSurface.Count
        requiredExports = $required
        unitySpecificExports = $unitySpecific
        callbackSurface = $callbackSurface
        namesAbsentFromReference = $notInReference
    }
}

$exitCode = 0
if ($CandidateMono) {
    $candidate = Get-ElfInfo $CandidateMono $ReadElf
    $missing = @(Get-Difference $required $candidate.Exports)
    $candidateIsArm64 = $candidate.Machine -eq "EM_AARCH64" -or $candidate.Arch -match "aarch64|arm64"
    $result.candidateMono = [ordered]@{
        path = $CandidateMono
        sha256 = Get-Sha256 $CandidateMono
        arch = $candidate.Arch
        machine = $candidate.Machine
        exportedSymbols = $candidate.Exports.Count
        isArm64 = $candidateIsArm64
        missingRequiredCount = $missing.Count
        missingRequiredExports = $missing
    }

    if (-not $candidateIsArm64) { $exitCode = 2 }
    elseif ($missing.Count -gt 0) { $exitCode = 3 }
}

if ($Json) {
    $report = $result | ConvertTo-Json -Depth 8
} else {
    $lines = [System.Collections.Generic.List[string]]::new()
    $lines.Add("Mono ARM64 ABI audit")
    $lines.Add("UnityPlayer: $($unity.Arch) / $($unity.Machine) / $($unityNames.Count) mono_* names")
    $lines.Add("Reference:   $($reference.Arch) / $($reference.Machine) / $($reference.Exports.Count) exports")
    $lines.Add("Baseline:    $($required.Count) Unity names exported by known-good Mono")
    $lines.Add("Unity API:   $($unitySpecific.Count) mono_unity_* exports")
    $lines.Add("Callbacks:   $($callbackSurface.Count) callback-bearing/suspicious exports")
    $lines.Add("Reference gaps: $($notInReference.Count) names (usually optional or diagnostic-only)")

    if ($CandidateMono) {
        $c = $result.candidateMono
        $lines.Add("")
        $lines.Add("Candidate:    $($c.arch) / $($c.machine) / ARM64=$($c.isArm64)")
        $lines.Add("Missing required exports: $($c.missingRequiredCount)")
        foreach ($name in $c.missingRequiredExports) { $lines.Add("  MISSING $name") }
        if ($exitCode -eq 0) {
            $lines.Add("Static verdict: GO for the native harness (ABI presence only).")
        } elseif ($exitCode -eq 2) {
            $lines.Add("Static verdict: NO-GO; candidate is not ARM64.")
        } else {
            $lines.Add("Static verdict: NO-GO until required exports are accounted for.")
        }
    } else {
        $lines.Add("")
        $lines.Add("No ARM64 candidate supplied; baseline inventory completed.")
    }
    $report = $lines -join "`n"
}

if ($OutFile) {
    $parent = Split-Path -Parent $OutFile
    if ($parent -and -not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    Set-Content -LiteralPath $OutFile -Value $report -Encoding utf8NoBOM
} else {
    Write-Output $report
}

exit $exitCode
