// Hold and quality rules, exercised through the real WASM core in Node.
//
// Field report: a plank clip showed a quality score but recorded almost no
// time, and a plank-like clip in push-up mode showed a high quality score with
// zero reps. Both came from the core, so they are pinned here without a browser.
import { readFile } from 'node:fs/promises';
import { initCadenceWasm, CadenceWasmBridge } from './cadence-wasm-bridge.js';

let failures = 0;
function check(ok, label) {
  if (ok) {
    console.log('ok - ' + label);
  } else {
    failures += 1;
    console.log('FAIL - ' + label);
  }
}

// The Emscripten glue only fetches, and Node cannot fetch file:// URLs, so the
// binary is compiled here and handed over through the instantiateWasm hook.
const wasmBinary = await readFile(new URL('./wasm/cadence-core.wasm', import.meta.url));
const module = await initCadenceWasm({
  instantiateWasm(imports, receiveInstance) {
    WebAssembly.instantiate(wasmBinary, imports)
      .then(({ instance }) => receiveInstance(instance));
    return {};
  },
});

/** 33 landmarks, all visible unless a test hides one. */
function blank(visibility = 1) {
  return Array.from({ length: 33 }, () => ({ x: 0.5, y: 0.5, z: 0, visibility }));
}

/** A plank: shoulder, hip and ankle on one straight line. */
function plank({ hideKnee }) {
  const points = blank();
  points[11] = { x: 0, y: 0, z: 0, visibility: 1 };
  points[23] = { x: 1, y: 0, z: 0, visibility: 1 };
  points[27] = { x: 2, y: 0, z: 0, visibility: 1 };
  points[12] = { x: 0, y: 0.3, z: 0, visibility: 1 };
  points[24] = { x: 1, y: 0.3, z: 0, visibility: 1 };
  points[28] = { x: 2, y: 0.3, z: 0, visibility: 1 };
  points[25] = { x: 1.5, y: 0, z: 0, visibility: hideKnee ? 0.1 : 1 };
  points[26] = { x: 1.5, y: 0.3, z: 0, visibility: hideKnee ? 0.1 : 1 };
  return points;
}

/** A push-up pose whose elbow bends by `dip` degrees. */
function pushup(dip) {
  const points = blank();
  const radians = (180 - dip) * Math.PI / 180;
  points[11] = { x: 0, y: 0, z: 0, visibility: 1 };
  points[13] = { x: 1, y: 0, z: 0, visibility: 1 };
  points[15] = { x: 1 + Math.cos(radians), y: -Math.sin(radians), z: 0, visibility: 1 };
  points[12] = { x: 0, y: 0.3, z: 0, visibility: 1 };
  points[14] = { x: 1, y: 0.3, z: 0, visibility: 1 };
  points[16] = { x: 2, y: 0.3, z: 0, visibility: 1 };
  points[23] = { x: 0, y: 0.6, z: 0, visibility: 1 };
  points[25] = { x: 1, y: 0.6, z: 0, visibility: 1 };
  points[27] = { x: 2, y: 0.6, z: 0, visibility: 1 };
  return points;
}

// ---- a plank whose knee is hidden still counts as a hold ----
const hiddenKnee = new CadenceWasmBridge(module, 'plank');
const plankFrame = plank({ hideKnee: true });
const first = hiddenKnee.process(plankFrame, 0);
check(first && first.poseDetected === true, '膝盖被挡住时平板仍然被识别');
check(first && first.hold === true, '识别出来的平板是保持类动作');

let elapsed = 0;
for (let frame = 0; frame < 144; frame += 1) {
  hiddenKnee.process(plankFrame, elapsed);   // 6 秒 @24fps
  elapsed += 1 / 24;
}
const plankSummary = hiddenKnee.summary();
check(plankSummary.elapsedSeconds > 5.9, `6 秒平板记到了 ${plankSummary.elapsedSeconds.toFixed(2)} 秒`);
check(plankSummary.points > 5, `6 秒平板记到了 ${plankSummary.points} 分`);
check(plankSummary.averageQuality > 0.9, '一条直线的平板质量高');
check(plankSummary.repetitions === 0, '保持类动作不产生次数');

// ---- a clip that never performs the movement must not report quality ----
const shallow = new CadenceWasmBridge(module, 'pushup');
let shallowTime = 0;
for (let frame = 0; frame < 120; frame += 1) {
  shallow.process(pushup(frame % 2 ? 15 : 0), shallowTime);
  shallowTime += 1 / 24;
}
const shallowSummary = shallow.summary();
check(shallowSummary.repetitions === 0, '手臂几乎不动时不记次数');
check(shallowSummary.averageQuality === 0,
      `没做动作时不报质量分（当前 ${shallowSummary.averageQuality}）`);
check(shallowSummary.points === 0, '没做动作时不给分');

// ---- and a real push-up still counts ----
const deep = new CadenceWasmBridge(module, 'pushup');
let deepTime = 0;
for (let frame = 0; frame < 96; frame += 1) {
  deep.process(pushup(frame % 24 < 12 ? 0 : 95), deepTime);
  deepTime += 1 / 24;
}
const deepSummary = deep.summary();
check(deepSummary.repetitions === 3, `真的下到底记到 ${deepSummary.repetitions} 次`);
check(deepSummary.averageQuality > 0.5, '下到底的俯卧撑有质量分');
check(deepSummary.points > 0, '下到底的俯卧撑有分');

console.log(failures === 0 ? 'PASS' : `FAIL (${failures})`);
process.exit(failures === 0 ? 0 : 1);
