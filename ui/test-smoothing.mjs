// Unit checks for landmark smoothing and body selection.
//
// Both behaviours exist because of a field report: the page counted no reps
// while the prototype counted, and the page's skeleton twitched where the
// prototype's glided. Two causes were in this logic, so it gets tested here
// rather than only through a browser.
//
// Run: node ui/test-smoothing.mjs
import { createPoseSmoother, DEFAULT_ALPHA } from './pose-smoothing.js';

let failures = 0;
function check(condition, message) {
  if (condition) {
    console.log('ok - ' + message);
  } else {
    console.error('FAIL: ' + message);
    failures += 1;
  }
}

/** A pose of [count] landmarks all sitting at (x, y). */
function poseAt(x, y, count = 33, visibility = 1) {
  return Array.from({ length: count }, () => ({ x, y, z: 0, visibility }));
}

// ---- smoothing -------------------------------------------------------------
{
  const smoother = createPoseSmoother(0.5);
  const first = smoother.smooth(poseAt(0, 0));
  check(first[0].x === 0, '第一帧直接采用原始坐标');

  smoother.smooth(poseAt(1, 0));
  const after = smoother.current[0].x;
  check(Math.abs(after - 0.5) < 1e-6, `第二帧走一半（实际 ${after}）`);

  // A constant input must settle exactly, not creep.
  for (let frame = 0; frame < 40; frame += 1) smoother.smooth(poseAt(1, 0));
  check(Math.abs(smoother.current[0].x - 1) < 1e-6, '持续同一输入最终收敛到该值');
}

{
  const smoother = createPoseSmoother(0.5);
  smoother.smooth(poseAt(0, 0));
  // 抖动 ±0.1 应被压到远小于原始振幅
  let last = 0;
  const wobble = [];
  for (let frame = 0; frame < 20; frame += 1) {
    const raw = frame % 2 === 0 ? 0.1 : -0.1;
    wobble.push(smoother.smooth(poseAt(raw, 0))[0].x);
    last = raw;
  }
  const peak = Math.max(...wobble.map(Math.abs));
  check(peak < 0.1, `抖动被衰减（峰值 ${peak.toFixed(3)} < 0.1）`);
  check(last !== wobble[wobble.length - 1], '输出不等于最后一帧原始值');
}

{
  const smoother = createPoseSmoother();
  smoother.smooth(poseAt(0, 0));
  smoother.reset();
  check(smoother.current === null, 'reset 后不再保留上一帧');
  const fresh = smoother.smooth(poseAt(5, 5));
  check(fresh[0].x === 5, 'reset 后第一帧直接采用原始坐标');
}

// ---- body selection --------------------------------------------------------
{
  const smoother = createPoseSmoother();
  // No history: the most visible body wins.
  const left = poseAt(0.2, 0.5, 33, 0.4);
  const right = poseAt(0.8, 0.5, 33, 0.95);
  const picked = smoother.pick([left, right]);
  check(picked.pose === right, '没有历史时选可见度最高的那个身体');
  check(picked.others === 1, '报告画面里还有几个身体');
}

{
  const smoother = createPoseSmoother();
  smoother.smooth(poseAt(0.2, 0.5));
  // Two bodies arrive, one of them close to where tracking already is.
  const near = poseAt(0.21, 0.5);
  const far = poseAt(0.9, 0.5, 33, 1);
  const picked = smoother.pick([far, near]);
  check(picked.pose === near, '两个人时选离上一帧最近的那个');
}

{
  const smoother = createPoseSmoother();
  const single = poseAt(0.5, 0.5);
  const picked = smoother.pick([single]);
  check(picked.pose === single && picked.others === 0, '单人帧不标记为多人');
  check(smoother.pick([]) === null, '空帧返回 null');
}

{
  // A second body must not drag the smoothed skeleton towards it.
  const smoother = createPoseSmoother(0.5);
  smoother.smooth(poseAt(0.2, 0.5));
  for (let frame = 0; frame < 10; frame += 1) {
    const picked = smoother.pick([poseAt(0.2, 0.5), poseAt(0.9, 0.9)]);
    smoother.smooth(picked.pose);
  }
  const x = smoother.current[0].x;
  check(Math.abs(x - 0.2) < 0.02, `跟踪的身体没有被第二个人带走（x=${x.toFixed(3)}）`);
}

{
  const smoother = createPoseSmoother();
  let threw = false;
  try {
    createPoseSmoother(0);
  } catch {
    threw = true;
  }
  check(threw, '非法的平滑系数会被拒绝');
  check(DEFAULT_ALPHA > 0 && DEFAULT_ALPHA <= 1, '默认平滑系数合法');
}

if (failures === 0) {
  console.log('PASS');
} else {
  console.error(failures + ' failure(s)');
  process.exit(1);
}
