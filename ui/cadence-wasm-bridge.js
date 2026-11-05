// Browser adapter for the C++ movement core compiled to WebAssembly.
// The C++ side owns evaluation, repetition counting, quality, points and calories.
// See src/cadence/wasm_api.cpp and docs/canonical-rules.md.
import CadenceWasmFactory from './wasm/cadence-core.js';

export const EXERCISE_IDS = {
  squat: 0,
  pushup: 1,
  lunge: 2,
  plank: 3,
  jumpingjack: 4,
};

let modulePromise = null;

export function initCadenceWasm() {
  if (!modulePromise) {
    modulePromise = CadenceWasmFactory();
  }
  return modulePromise;
}

export class CadenceWasmBridge {
  constructor(module, exerciseCode) {
    if (!(exerciseCode in EXERCISE_IDS)) {
      throw new Error('未知动作: ' + exerciseCode);
    }
    this.module = module;
    this.exerciseCode = exerciseCode;
    this.session = module._cadence_create_session(EXERCISE_IDS[exerciseCode]);
    if (!this.session) {
      throw new Error('C++ 模型创建会话失败');
    }
    this.landmarksPtr = module._malloc(33 * 4 * 4);
    if (!this.landmarksPtr) {
      module._cadence_destroy_session(this.session);
      this.session = 0;
      throw new Error('C++ 模型分配关键点内存失败');
    }
    this.view = new Float32Array(module.HEAPF32.buffer, this.landmarksPtr, 33 * 4);
  }

  process(landmarks, elapsedSeconds) {
    if (!this.session || !landmarks || landmarks.length !== 33) return null;
    if (this.view.buffer !== this.module.HEAPF32.buffer) {
      this.view = new Float32Array(this.module.HEAPF32.buffer, this.landmarksPtr, 33 * 4);
    }
    for (let i = 0; i < 33; i += 1) {
      const point = landmarks[i];
      const offset = i * 4;
      this.view[offset] = Number(point.x) || 0;
      this.view[offset + 1] = Number(point.y) || 0;
      this.view[offset + 2] = Number(point.z) || 0;
      this.view[offset + 3] = point.visibility == null ? 1 : Number(point.visibility) || 0;
    }
    const pointer = this.module._cadence_process_landmarks(
      this.session, this.landmarksPtr, 33, Math.max(0, Number(elapsedSeconds) || 0),
    );
    return JSON.parse(this.module.UTF8ToString(pointer));
  }

  summary() {
    if (!this.session) {
      return { repetitions: 0, points: 0, calories: 0, averageQuality: 0, elapsedSeconds: 0 };
    }
    const pointer = this.module._cadence_session_summary(this.session);
    return JSON.parse(this.module.UTF8ToString(pointer));
  }

  reset() {
    if (this.session) this.module._cadence_reset_session(this.session);
  }

  dispose() {
    if (!this.session) return;
    this.module._cadence_destroy_session(this.session);
    if (this.landmarksPtr) this.module._free(this.landmarksPtr);
    this.session = 0;
    this.landmarksPtr = 0;
    this.view = null;
  }
}

export async function createCadenceSession(exerciseCode) {
  const module = await initCadenceWasm();
  return new CadenceWasmBridge(module, exerciseCode);
}
