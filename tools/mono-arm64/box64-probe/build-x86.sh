#!/usr/bin/env sh
set -eu

out_dir="${1:-out}"
mkdir -p "$out_dir"

cc -O2 -Wall -Wextra -Werror -o "$out_dir/mono_box64_probe" mono_box64_probe.c -ldl
file "$out_dir/mono_box64_probe"
