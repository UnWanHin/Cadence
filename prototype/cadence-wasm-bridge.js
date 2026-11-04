// Thin browser adapter: camera and MediaPipe stay in JavaScript; movement rules run in C++ WASM.
export class CadenceWasmBridge {
  constructor(moduleFactory, exercise = 0) {
    this.module = moduleFactory;
    this.session = moduleFactory._cadence_create_session(exercise);
    this.landmarks = moduleFactory._malloc(33 * 4 * 4);
    this.view = new Float32Array(moduleFactory.HEAPF32.buffer, this.landmarks, 33 * 4);
  }

  process(landmarks, elapsedSeconds) {
    if (!landmarks || landmarks.length !== 33) return null;
    for (let i = 0; i < 33; i += 1) {
      const point = landmarks[i];
      this.view[i * 4] = point.x;
      this.view[i * 4 + 1] = point.y;
      this.view[i * 4 + 2] = point.z ?? 0;
      this.view[i * 4 + 3] = point.visibility ?? point.presence ?? 0;
    }
    const ptr = this.module._cadence_process_landmarks(
      this.session, this.landmarks, 33, elapsedSeconds,
    );
    return JSON.parse(this.module.UTF8ToString(ptr));
  }

  summary() {
    const ptr = this.module._cadence_session_summary(this.session);
    return JSON.parse(this.module.UTF8ToString(ptr));
  }

  reset() {
    this.module._cadence_reset_session(this.session);
  }

  dispose() {
    this.module._cadence_destroy_session(this.session);
    this.module._free(this.landmarks);
  }
}

export async function loadCadenceWasm(moduleFactory, exercise = 0) {
  const module = await moduleFactory();
  return new CadenceWasmBridge(module, exercise);
}
