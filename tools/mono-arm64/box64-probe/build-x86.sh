#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
out_dir="${1:-$script_dir/out}"
mkdir -p "$out_dir"

cc -O2 -Wall -Wextra -Werror -o "$out_dir/mono_box64_probe" "$script_dir/mono_box64_probe.c" -ldl
file "$out_dir/mono_box64_probe"
