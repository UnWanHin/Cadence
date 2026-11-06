#!/usr/bin/env bash
# Rebuilds ui/wasm/cadence-core.{js,wasm} from src/cadence with emscripten.
#
# The artifact is committed because the page must work without a toolchain and
# without the network, so it has to be rebuilt whenever the movement core
# changes. Install emscripten first (e.g. https://github.com/emscripten-core/emsdk):
#
#   git clone https://github.com/emscripten-core/emsdk ~/emsdk
#   ~/emsdk/emsdk install latest && ~/emsdk/emsdk activate latest
#   source ~/emsdk/emsdk_env.sh
#
# Run: bash tools/build-wasm.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="${BUILD_DIR:-$(mktemp -d /tmp/cadence-wasm-XXXXXX)}"

if ! command -v emcmake >/dev/null 2>&1; then
  echo "emcmake not found: install and activate emscripten first (see the header)." >&2
  exit 1
fi

emcmake cmake -S "$ROOT/src" -B "$BUILD_DIR" -DCADENCE_BUILD_WASM=ON -DCMAKE_BUILD_TYPE=Release >/dev/null
cmake --build "$BUILD_DIR" -j"$(nproc)" >/dev/null

cp "$BUILD_DIR/cadence-core.js" "$BUILD_DIR/cadence-core.wasm" "$ROOT/ui/wasm/"
echo "updated ui/wasm/cadence-core.js and ui/wasm/cadence-core.wasm"
