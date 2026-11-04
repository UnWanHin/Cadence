# Cadence

Cadence is a local pose-fitness prototype with a C++ movement-evaluation core.

## C++ core

The native code lives under `src/` and is split into deep modules:

- `geometry`: joint-angle and distance calculations.
- `pose_features`: converts MediaPipe-style 33-landmark frames into exercise features.
- `exercise_rules`: evaluates squat, push-up, lunge, plank, and jumping-jack form.
- `repetition_counter`: hysteresis state machine for complete repetitions.
- `workout_session`: aggregates repetitions, points, calories, quality, and elapsed time.
- `pose_source`: pluggable frame sources, including text replay.
- `pose_backend`: pluggable native inference backend seam.

The browser prototype under `prototype/` remains the working MediaPipe WASM demo. The native C++ build deliberately has no hidden SDK dependency: a MediaPipe Tasks or ONNX backend is added as an adapter implementing `PoseBackend`.

## Build and test

```bash
cmake -S src -B build
cmake --build build
ctest --test-dir build --output-on-failure
./build/cadence_demo
```

## Replay a pose frame

`TextPoseSource` reads one frame per line. Each line contains 33 landmarks, each encoded as `x y z visibility`, followed by `timestamp_ms`.

```text
x0 y0 z0 v0 x1 y1 z1 v1 ... x32 y32 z32 v32 timestamp_ms
```

## Native inference integration

Implement the backend seam:

```cpp
class MediaPipePoseBackend final : public cadence::PoseBackend {
 public:
  cadence::BackendStatus load(const std::string& model_path) override;
  bool detect(const std::string& frame_source, cadence::PoseFrame& frame) override;
};
```

The adapter owns model loading and landmark conversion. It must populate all 33 `PoseFrame::landmarks` values with normalized coordinates and visibility scores. The movement core then remains unchanged.

The current environment does not ship MediaPipe C++ or OpenCV development headers, so the default build reports the native backend as unavailable rather than pretending that camera inference is wired.
