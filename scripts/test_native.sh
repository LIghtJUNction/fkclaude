#!/usr/bin/env bash
set -euo pipefail
repo_dir=$(cd -- "$(dirname -- "$0")/.." && pwd)
test_dir="$repo_dir/artifacts/native-tests"
mkdir -p "$test_dir"
jdk_dir=$(dirname -- "$(dirname -- "$(readlink -f -- "$(command -v javac)")")")
clang++ -std=c++17 -DFKCLAUDE_HOST_TEST -Wall -Wextra -Werror -O1 -g \
  -fsanitize=address,undefined -fno-omit-frame-pointer -pthread \
  -I"$repo_dir/app/src/main/cpp" -I"$jdk_dir/include" -I"$jdk_dir/include/linux" \
  "$repo_dir/tests/native_test.cpp" "$repo_dir/app/src/main/cpp/module.cpp" \
  "$repo_dir/app/src/main/cpp/native_config.cpp" -ldl -o "$test_dir/native_test"
ASAN_OPTIONS=detect_leaks=1 "$test_dir/native_test" "$repo_dir/app/src/test/resources/native-golden.hex"
