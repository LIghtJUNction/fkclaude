#!/usr/bin/env bash
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
java_home="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
build_dir="$(mktemp -d /tmp/fkclaude-ip-guard.XXXXXX)"
trap 'rm -rf -- "$build_dir"' EXIT
"${CXX:-c++}" -std=c++17 -Wall -Wextra -Werror -g -O1 \
  -fsanitize=address,undefined -fno-omit-frame-pointer -no-pie \
  -DFKCLAUDE_HOST_TEST -pthread \
  -I"$root/app/src/main/cpp" -I"$java_home/include" -I"$java_home/include/linux" \
  "$root/app/src/main/cpp/ip_guard.cpp" "$root/app/src/main/cpp/network_audit.cpp" "$root/tests/ip_guard_test.cpp" \
  -ldl -o "$build_dir/ip_guard_test"
ASAN_OPTIONS=detect_leaks=1 UBSAN_OPTIONS=halt_on_error=1 "$build_dir/ip_guard_test"
