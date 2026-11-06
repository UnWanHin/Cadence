#!/usr/bin/env bash
# Builds and runs the native tests for the C++ movement core.
#
# This is the fast half of the movement suite: no browser, no emscripten. The
# same sources are compiled into the APK (NDK) and into the browser core
# (emscripten), so a rule fixed here is fixed everywhere.
#
# Run: bash tools/run-core-tests.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="${BUILD_DIR:-$(mktemp -d /tmp/cadence-core-XXXXXX)}"

cmake -S "$ROOT/src" -B "$BUILD_DIR" -DCMAKE_BUILD_TYPE=Release >/dev/null
cmake --build "$BUILD_DIR" -j"$(nproc)" >/dev/null
ctest --test-dir "$BUILD_DIR" --output-on-failure
