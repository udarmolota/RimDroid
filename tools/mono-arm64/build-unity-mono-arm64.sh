#!/usr/bin/env bash

set -euo pipefail

usage() {
    cat <<'EOF'
Usage: build-unity-mono-arm64.sh MONO_SOURCE NDK_ROOT OUT_DIR

Builds the Unity 2022.3 Mono fork as an Android ARM64 Boehm runtime. The
source checkout must contain the external/bdwgc submodule. The output is a
standalone research artifact; this script does not modify the RimDroid APK.

Optional environment:
  ANDROID_API=21       Minimum Android API used by clang.
  JOBS=<cpu count>     Parallel make jobs.
EOF
}

if [[ $# -ne 3 ]]; then
    usage >&2
    exit 2
fi

source_root="$(cd "$1" && pwd)"
ndk_root="$(cd "$2" && pwd)"
out_dir="$3"
android_api="${ANDROID_API:-21}"
jobs="${JOBS:-}"

if [[ -z "$jobs" ]]; then
    if command -v nproc >/dev/null 2>&1; then
        jobs="$(nproc)"
    else
        jobs=4
    fi
fi

case "$(uname -s)" in
    Linux*)  host_tag="linux-x86_64" ;;
    Darwin*) host_tag="darwin-x86_64" ;;
    *)
        echo "ERROR: run this build under Linux or macOS, not Git Bash." >&2
        exit 3
        ;;
esac

toolchain="$ndk_root/toolchains/llvm/prebuilt/$host_tag"
target="aarch64-linux-android"
cc="$toolchain/bin/${target}${android_api}-clang"
cxx="$toolchain/bin/${target}${android_api}-clang++"

pick_tool() {
    local preferred="$1"
    local fallback="$2"
    if [[ -x "$preferred" ]]; then
        printf '%s\n' "$preferred"
    elif [[ -x "$fallback" ]]; then
        printf '%s\n' "$fallback"
    else
        echo "ERROR: neither tool exists: $preferred or $fallback" >&2
        exit 4
    fi
}

for required in \
    "$source_root/autogen.sh" \
    "$source_root/external/bdwgc/configure.ac" \
    "$cc" \
    "$cxx"; do
    if [[ ! -e "$required" ]]; then
        echo "ERROR: required input not found: $required" >&2
        exit 4
    fi
done

for command_name in autoconf automake libtoolize make mono; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
        echo "ERROR: required host command not found: $command_name" >&2
        exit 4
    fi
done

if [[ -e "$source_root/config.status" ]]; then
    echo "ERROR: source checkout is already configured; use a fresh checkout." >&2
    exit 5
fi

if [[ -d "$out_dir" ]] && [[ -n "$(find "$out_dir" -mindepth 1 -print -quit)" ]]; then
    echo "ERROR: output directory is not empty: $out_dir" >&2
    exit 5
fi
mkdir -p "$out_dir"
out_dir="$(cd "$out_dir" && pwd)"

export CC="$cc"
export CXX="$cxx"
export CPP="$cc -E"
export CXXCPP="$cxx -E"
export AR="$(pick_tool "$toolchain/bin/${target}-ar" "$toolchain/bin/llvm-ar")"
export RANLIB="$(pick_tool "$toolchain/bin/${target}-ranlib" "$toolchain/bin/llvm-ranlib")"
export STRIP="$(pick_tool "$toolchain/bin/${target}-strip" "$toolchain/bin/llvm-strip")"
export LD="$(pick_tool "$toolchain/bin/${target}-ld" "$toolchain/bin/ld.lld")"
export AS="$(pick_tool "$toolchain/bin/${target}-as" "$cc")"
export CPATH="$toolchain/sysroot/usr/include/$target:$toolchain/sysroot/usr/include"
export CFLAGS="-O2 -g -fPIC -ffunction-sections -fdata-sections -DANDROID -DPLATFORM_ANDROID -DLINUX -D__linux__ -DHAVE_USR_INCLUDE_MALLOC_H -D_POSIX_PATH_MAX=256 -DS_IWRITE=S_IWUSR -DHAVE_PTHREAD_MUTEX_TIMEDLOCK"
export CXXFLAGS="$CFLAGS"
export CPPFLAGS="$CFLAGS"
export LDFLAGS="-Wl,--no-undefined -Wl,--gc-sections -Wl,-z,max-page-size=16384 -ldl -lm -llog -lz -lc"

source_commit="unknown"
if git -C "$source_root" rev-parse HEAD >/dev/null 2>&1; then
    source_commit="$(git -C "$source_root" rev-parse HEAD)"
fi

echo "MONO_ARM64 phase=configure source=$source_commit api=$android_api"
cd "$source_root"
./autogen.sh \
    --host="$target" \
    --disable-mcs-build \
    --with-glib=embedded \
    --disable-nls \
    --with-mcs-docs=no \
    --enable-no-threads-discovery=yes \
    --enable-ignore-dynamic-loading=yes \
    --enable-dont-register-main-static-data=yes \
    --enable-thread-local-alloc=no \
    --enable-unity-define=yes \
    --disable-parallel-mark \
    --disable-shared-handles \
    --with-sigaltstack=no \
    --with-tls=pthread \
    --disable-visibility-hidden \
    --disable-btls \
    --with-sgen=no \
    mono_cv_uscore=yes

make -C mono/arch/arm

echo "MONO_ARM64 phase=make jobs=$jobs"
make -j"$jobs"

runtime="$source_root/mono/mini/.libs/libmonoboehm-2.0.so"
native="$source_root/mono/native/.libs/libmono-native.so"
posix="$source_root/support/.libs/libMonoPosixHelper.so"
for built_file in "$runtime" "$native" "$posix"; do
    if [[ ! -f "$built_file" ]]; then
        echo "ERROR: expected build output missing: $built_file" >&2
        exit 6
    fi
done

cp "$runtime" "$out_dir/libmonobdwgc-2.0.so"
ln -s libmonobdwgc-2.0.so "$out_dir/libmonoboehm-2.0.so"
cp "$native" "$out_dir/libmono-native.so"
cp "$posix" "$out_dir/libMonoPosixHelper.so"

{
    echo "unity_mono_commit=$source_commit"
    echo "android_ndk=$(basename "$ndk_root")"
    echo "android_api=$android_api"
    echo "target=$target"
    echo "cflags=$CFLAGS"
    echo "ldflags=$LDFLAGS"
} > "$out_dir/build-manifest.txt"

(cd "$out_dir" && sha256sum \
    libmonobdwgc-2.0.so \
    libmono-native.so \
    libMonoPosixHelper.so > SHA256SUMS)

echo "MONO_ARM64 verdict=BUILT output=$out_dir"
