# Generated WebAssembly artifacts

These files are generated from `src/cadence/*` by Emscripten:

- `cadence-core.js` — ES module loader
- `cadence-core.wasm` — C++ movement core

Do not edit them by hand. Rebuild with:

```bash
emcmake cmake -S src -B build-wasm -DCADENCE_BUILD_WASM=ON
cmake --build build-wasm
cp build-wasm/cadence-core.js build-wasm/cadence-core.wasm ui/wasm/
```

The UI loads them through `ui/cadence-wasm-bridge.js`; `ui/core.js` is only a
JavaScript reference mirror for tests and metadata.

On Windows, if the repository path contains non-ASCII characters, build from an
ASCII-only copy (for example `E:\cadence-build`) because `mingw32-make` may
crash on such paths.
