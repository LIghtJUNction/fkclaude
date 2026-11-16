#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
jdk_dir="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
build_dir="$(mktemp -d /tmp/fkclaude-network-audit.XXXXXX)"
trap 'rm -rf -- "$build_dir"' EXIT
"${CXX:-c++}" -std=c++17 -Wall -Wextra -Werror -g -O1 \
  -fsanitize=address,undefined -fno-omit-frame-pointer -no-pie \
  -DFKCLAUDE_HOST_TEST -pthread \
  -I"$repo_dir/app/src/main/cpp" -I"$jdk_dir/include" -I"$jdk_dir/include/linux" \
  "$repo_dir/app/src/main/cpp/network_audit.cpp" "$repo_dir/app/src/main/cpp/ip_guard.cpp" \
  "$repo_dir/tests/network_audit_test.cpp" -ldl -o "$build_dir/network_audit_test"
ASAN_OPTIONS=detect_leaks=1 UBSAN_OPTIONS=halt_on_error=1 "$build_dir/network_audit_test"
