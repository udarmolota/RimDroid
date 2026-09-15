#!/usr/bin/env bash

set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "Usage: build.sh NDK_ROOT OUT_DIR" >&2
    exit 2
fi

ndk_root="$(cd "$1" && pwd)"
out_dir="$2"
android_api="${ANDROID_API:-21}"
host_tag="linux-x86_64"
if [[ "$(uname -s)" == Darwin* ]]; then
    host_tag="darwin-x86_64"
fi

clang="$ndk_root/toolchains/llvm/prebuilt/$host_tag/bin/aarch64-linux-android${android_api}-clang"
for tool in "$clang" mcs; do
    if [[ "$tool" == */* ]]; then
        [[ -x "$tool" ]] || { echo "ERROR: tool not found: $tool" >&2; exit 3; }
    else
        command -v "$tool" >/dev/null 2>&1 || { echo "ERROR: tool not found: $tool" >&2; exit 3; }
    fi
done

mkdir -p "$out_dir"
"$clang" -std=c11 -Wall -Wextra -Werror -fPIE -pie \
    -Wl,-z,max-page-size=16384 \
    "$(dirname "$0")/mono_arm64_probe.c" -ldl \
    -o "$out_dir/mono_arm64_probe"
mcs -nologo -target:library -optimize+ \
    -out:"$out_dir/RimDroid.MonoArm64Probe.dll" \
    "$(dirname "$0")/Probe.cs"

echo "MONO_ARM64_PROBE verdict=BUILT output=$out_dir"
