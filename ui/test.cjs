// Tests the JavaScript reference mirror in core.js.
// The browser UI itself evaluates poses through cadence-wasm-bridge.js and the C++ WASM core.
const assert = require('node:assert/strict');
const C = require('./core');

function blank() {
  return Array.from({ length: 33 }, () => ({ x: 0, y: 0, z: 0, visibility: 1 }));
}
function put(lm, i, x, y, visibility = 1) {
  lm[i] = { x, y, z: 0, visibility };
}
function rad(deg) { return (deg * Math.PI) / 180; }

// Build a left/right leg chain with a chosen knee angle and hip angle.
function squatPose(kneeDeg, hipDeg = 180) {
  const lm = blank();
  for (const side of [-1, 1]) {
    const y = side < 0 ? 1 : 2;
    const hip = side < 0 ? 23 : 24;
    const knee = side < 0 ? 25 : 26;
    const ankle = side < 0 ? 27 : 28;
    const shoulder = side < 0 ? 11 : 12;
    put(lm, hip, 1, y);
    put(lm, knee, 0, y);
    put(lm, ankle, Math.cos(rad(kneeDeg)), y + Math.sin(rad(kneeDeg)));
    put(lm, shoulder, 1 + Math.cos(Math.PI - rad(hipDeg)), y + Math.sin(Math.PI - rad(hipDeg)));
  }
  return lm;
}

// Build a push-up chain with a chosen elbow angle and hip angle.
function pushupPose(elbowDeg, hipDeg = 175) {
  const lm = blank();
  for (const side of [-1, 1]) {
    const y = side < 0 ? 1 : 2;
    const shoulder = side < 0 ? 11 : 12;
    const elbow = side < 0 ? 13 : 14;
    const wrist = side < 0 ? 15 : 16;
    const hip = side < 0 ? 23 : 24;
    const knee = side < 0 ? 25 : 26;
    put(lm, hip, 1, 2 + side);
    put(lm, knee, 0, 2 + side);
    const sx = 1 + Math.cos(Math.PI - rad(hipDeg));
    const sy = 2 + side + Math.sin(Math.PI - rad(hipDeg));
    put(lm, shoulder, sx, sy);
    put(lm, elbow, sx + 1, sy);
    put(lm, wrist, sx + 1 + Math.cos(Math.PI - rad(elbowDeg)), sy + Math.sin(Math.PI - rad(elbowDeg)));
  }
  return lm;
}

function plankPose(hipDeg) {
  const lm = blank();
  put(lm, 23, 1, 2);
  put(lm, 25, 0, 2);
  put(lm, 11, 1 + Math.cos(Math.PI - rad(hipDeg)), 2 + Math.sin(Math.PI - rad(hipDeg)));
  return lm;
}

// 1. geometry
const a = { x: 0, y: 0 }, b = { x: 0, y: 1 }, c = { x: 1, y: 1 };
assert.ok(Math.abs(C.angleAt(a, b, c) - 90) < 1e-6);

// 2. visibility gate
const hidden = squatPose(80);
hidden[27].visibility = 0.1;
hidden[28].visibility = 0.1;
assert.equal(C.extractFeatures(hidden).knee, null);

// 3. squat target
const squatFeatures = C.extractFeatures(squatPose(80));
assert.ok(Math.abs(squatFeatures.knee - 80) < 1e-6);
const squatResult = C.evaluateFeatures('squat', squatFeatures);
assert.equal(squatResult.poseDetected, true);
assert.equal(squatResult.inTarget, true);
assert.equal(squatResult.quality, 1);

// 4. counter requires a full cycle
const counter = new C.RepCounter();
assert.equal(counter.update(0.8).repetitionCompleted, false);
assert.equal(counter.update(0.4).repetitionCompleted, true);
assert.equal(counter.count, 1);

// 5. session counts one squat rep and pays 10 points
const session = new C.WorkoutSession('squat');
session.process(squatPose(80), 0);
session.process(squatPose(170), 1);
let summary = session.summary();
assert.equal(summary.repetitions, 1);
assert.equal(summary.points, 10);
assert.ok(summary.averageQuality > 0);

// 6. plank pays by seconds and has no reps
const plank = new C.WorkoutSession('plank');
plank.process(plankPose(178), 100);
summary = plank.summary();
assert.equal(summary.repetitions, 0);
assert.equal(summary.points, 150);
assert.ok(Math.abs(summary.calories - 15) < 1e-6);
assert.ok(summary.averageQuality > 0.9);

// 7. push-up elbow target
const pushup = C.extractFeatures(pushupPose(80));
const pushupResult = C.evaluateFeatures('pushup', pushup);
assert.equal(pushupResult.poseDetected, true);
assert.ok(Math.abs(pushupResult.mainValue - 80) < 1e-6);
assert.equal(pushupResult.inTarget, true);

// 8. jumping jack wrist distance
const jack = blank();
put(jack, 15, 0.1, 0.5);
put(jack, 16, 0.9, 0.5);
const jackResult = C.evaluateFeatures('jumpingjack', C.extractFeatures(jack));
assert.equal(jackResult.poseDetected, true);
assert.equal(jackResult.inTarget, true);
assert.equal(jackResult.quality, 1);

// 9. date/week boundaries (Asia/Shanghai)
assert.equal(C.day(Date.parse('2026-11-01T16:00:00Z')), '2026-11-02');
assert.equal(C.week(Date.parse('2026-11-01T16:00:00Z')), '2026-11-02');
assert.equal(C.formatDuration(125000), '2:05');

console.log('PASS');
