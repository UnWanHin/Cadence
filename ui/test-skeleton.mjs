// Browser check for the pose skeleton overlay and the prototype mount.
//
// The overlay is easy to get wrong in ways no unit test sees: a canvas sized
// before the video has a box, a letterbox offset that pushes the figure off
// screen, or an overlay that never gets hooked into the frame loop. So this
// runs the demo session, which needs no camera, and counts the pixels that were
// actually painted.
//
// It also covers the playback controls, because the overlay used to go stale
// there: seeking while paused left the old skeleton over a new frame, and a
// dead frame loop left a frozen skeleton with nothing to restart it.
//
// Requires playwright-core plus a Chromium binary.
// Optional overrides: CHROME_PATH, PORT, BASE_URL.
//
// Run: node ui/test-skeleton.mjs
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(__dirname, '..');
const PORT = Number(process.env.PORT || 8795);
const VIDEO = process.env.VIDEO
  || path.join(root, 'videos', 'jumpingjack-h264.mp4');
const BASE = process.env.BASE_URL || `http://127.0.0.1:${PORT}/`;

let failures = 0;
function check(condition, message) {
  if (condition) {
    console.log('ok - ' + message);
  } else {
    console.error('FAIL: ' + message);
    failures += 1;
  }
}

async function loadChromium() {
  const candidates = [
    'playwright-core',
    process.env.PLAYWRIGHT_PATH,
    path.join(root, 'node_modules', 'playwright-core', 'index.mjs'),
  ].filter(Boolean);
  for (const candidate of candidates) {
    try {
      const module = await import(candidate);
      if (module.chromium) return module.chromium;
    } catch {
      // try the next candidate
    }
  }
  return null;
}

function findChrome() {
  if (process.env.CHROME_PATH) return process.env.CHROME_PATH;
  const base = path.join(os.homedir(), '.cache', 'ms-playwright');
  if (!fs.existsSync(base)) return null;
  for (const entry of fs.readdirSync(base)) {
    if (!entry.startsWith('chromium')) continue;
    for (const candidate of [
      path.join(base, entry, 'chrome-linux', 'chrome'),
      path.join(base, entry, 'chrome-headless-shell-linux64', 'chrome-headless-shell'),
    ]) {
      if (fs.existsSync(candidate)) return candidate;
    }
  }
  return null;
}

/** Pixels the overlay actually painted, read straight from the canvas. */
const PAINTED = `(() => {
  const canvas = document.getElementById('poseCanvas');
  if (!canvas || !canvas.width) return null;
  const data = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
  let painted = 0;
  let minX = Infinity, maxX = -1, minY = Infinity, maxY = -1;
  for (let i = 3; i < data.length; i += 4) {
    if (data[i] <= 10) continue;
    painted += 1;
    const pixel = (i - 3) / 4;
    const x = pixel % canvas.width;
    const y = Math.floor(pixel / canvas.width);
    if (x < minX) minX = x;
    if (x > maxX) maxX = x;
    if (y < minY) minY = y;
    if (y > maxY) maxY = y;
  }
  return { painted, width: canvas.width, height: canvas.height, minX, maxX, minY, maxY };
})()`;

const chromium = await loadChromium();
const executablePath = findChrome();
if (!chromium || !executablePath) {
  console.log('SKIP: playwright-core 或 Chromium 不可用');
  process.exit(0);
}

const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'cadence-skeleton-'));
let server = null;
const external = process.env.EXTERNAL === '1';
if (!external) {
  server = spawn('node', [path.join(root, 'database', 'local-server', 'server.mjs')], {
    env: { ...process.env, PORT: String(PORT), DATA_DIR: path.join(dataDir, 'data') },
    stdio: 'ignore',
  });
  const deadline = Date.now() + 15_000;
  let up = false;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(`${BASE}api/v1/health`);
      if (response.ok) { up = true; break; }
    } catch {
      // still starting
    }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  if (!up) {
    console.log('SKIP: 房间服务没有起来');
    server.kill();
    process.exit(0);
  }
}

const browser = await chromium.launch({ executablePath, args: ['--no-sandbox'] });
try {
  // The prototype the page borrows its model bundle from must be reachable.
  const prototype = await fetch(`${BASE}prototype/pose-prototype.html`);
  check(prototype.ok, `/prototype/pose-prototype.html 可以访问（${prototype.status}）`);
  const bundle = await fetch(`${BASE}prototype/vision_bundle.mjs`);
  check(bundle.ok, `/prototype/vision_bundle.mjs 可以访问（${bundle.status}）`);
  const escaped = await fetch(`${BASE}prototype/%2e%2e/package.json`);
  check(!escaped.ok, '原型目录不能穿越到仓库上层');

  const page = await browser.newPage({ viewport: { width: 1200, height: 1000 } });
  const pageErrors = [];
  page.on('pageerror', (error) => pageErrors.push(String(error)));
  await page.goto(BASE, { waitUntil: 'load' });

  const initial = await page.evaluate(`(() => {
    const canvas = document.getElementById('poseCanvas');
    return {
      exists: !!canvas,
      label: document.getElementById('skeleton')?.textContent?.trim(),
    };
  })()`);
  check(initial.exists, '页面里有骨架画布');
  check(initial.label === '显示骨架：开', `骨架默认打开（${initial.label}）`);

  // Demo mode needs no camera and still produces landmarks every frame.
  await page.click('#demo');
  await page.waitForTimeout(4200);

  const drawn = await page.evaluate(PAINTED);
  check(drawn !== null && drawn.painted > 500, `叠加层画出了骨架（${drawn?.painted ?? 0} 个像素）`);
  check(
    drawn !== null && drawn.maxX - drawn.minX > drawn.width * 0.2,
    '骨架横向铺开，不是一个点',
  );
  check(
    drawn !== null && drawn.minX >= 0 && drawn.maxX < drawn.width,
    '骨架没有画到画布外面',
  );

  await page.locator('.video-wrap').screenshot({ path: path.join(dataDir, 'skeleton.png') });
  const shot = fs.statSync(path.join(dataDir, 'skeleton.png'));
  check(shot.size > 1000, `截图有内容（${shot.size} 字节）`);

  await page.click('#skeleton');
  await page.waitForTimeout(150);
  const off = await page.evaluate(PAINTED);
  const label = await page.evaluate(() => document.getElementById('skeleton').textContent.trim());
  check(off !== null && off.painted === 0, '关掉开关后画布被清空');
  check(label === '显示骨架：关', `开关文案跟着变（${label}）`);

  await page.click('#skeleton');
  await page.waitForTimeout(300);
  const back = await page.evaluate(PAINTED);
  check(back !== null && back.painted > 500, '再打开又能画出来');

  check(pageErrors.length === 0, `没有脚本错误${pageErrors.length ? '：' + pageErrors[0] : ''}`);

  // ---- both controls drive the same state ----
  await page.click('#skeletonChip');
  const chipOff = await page.evaluate(() => ({
    chip: document.getElementById('skeletonChip').textContent.trim(),
    button: document.getElementById('skeleton').textContent.trim(),
    pressed: document.getElementById('skeletonChip').getAttribute('aria-pressed'),
  }));
  check(chipOff.button === '显示骨架：关' && chipOff.pressed === 'false',
    `视频角标也能关骨架，并同步到工具按钮（${chipOff.chip} / ${chipOff.button}）`);
  await page.click('#skeleton');
  const syncedBack = await page.evaluate(() => document.getElementById('skeletonChip').getAttribute('aria-pressed'));
  check(syncedBack === 'true', '工具按钮打开时角标跟着变');
  await page.close();

  // ---- playback: pause, seek, replay ----
  if (!fs.existsSync(VIDEO)) {
    console.log('SKIP: 没有 ' + path.basename(VIDEO) + '，跳过播放控制检查');
  } else {
    const video = await browser.newPage({ viewport: { width: 1200, height: 1000 } });
    const videoErrors = [];
    video.on('pageerror', (error) => videoErrors.push(String(error)));
    await video.goto(BASE, { waitUntil: 'load' });
    await video.setInputFiles('#videoFile', VIDEO);
    await video.waitForTimeout(6000);

    const readState = () => video.evaluate(`(() => {
      const debug = window.__cadenceDebug ? window.__cadenceDebug() : {};
      const canvas = document.getElementById('poseCanvas');
      const data = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
      let painted = 0;
      for (let i = 3; i < data.length; i += 4) if (data[i] > 10) painted += 1;
      const element = document.getElementById('vid');
      return {
        time: element.currentTime,
        paused: element.paused,
        painted,
        detections: Object.values(debug.poses || {}).reduce((a, b) => a + Number(b), 0),
        frames: debug.frames || 0,
        resets: debug.trackerResets || 0,
        resetFailures: debug.trackerResetFailures || 0,
        loopErrors: debug.loopErrors || 0,
        loopRestarts: debug.loopRestarts || 0,
      };
    })()`);

    const playing = await readState();
    check(playing.painted > 0, `播放时骨架在画（${playing.painted} 像素）`);

    // Pause, then seek: the overlay must show the new frame, not stay empty or stale.
    await video.click('#pause');
    await video.waitForTimeout(600);
    const paused = await readState();
    check(paused.paused && paused.painted === 0, '暂停时清掉骨架，不留上一帧');
    await video.evaluate(() => { document.getElementById('vid').currentTime = 1.0; });
    await video.waitForTimeout(1200);
    const seekedPaused = await readState();
    check(seekedPaused.painted > 0, `暂停中跳播会在新帧上重画（${seekedPaused.painted} 像素）`);
    check(seekedPaused.resets > paused.resets, '跳播会重置追踪器，丢掉旧的位置状态');

    // Resume and seek while playing: the loop has to keep following.
    await video.click('#pause');
    await video.waitForTimeout(800);
    const beforeJump = await readState();
    await video.evaluate(() => { document.getElementById('vid').currentTime = 6.0; });
    await video.waitForTimeout(2200);
    const afterJump = await readState();
    check(afterJump.detections > beforeJump.detections, '播放中跳播之后仍在检测（没有卡死）');
    check(afterJump.painted > 0, '跳播后骨架重新出现');

    // Let it run to the end, then replay from the button.
    await video.evaluate(() => { document.getElementById('vid').currentTime = 99; });
    await video.waitForTimeout(2500);
    const ended = await readState();
    check(ended.painted === 0, '视频结束后清掉骨架，不把最后一帧留在画面上');
    await video.click('#replay');
    await video.waitForTimeout(5000);
    const replayed = await readState();
    check(replayed.detections > ended.detections, '重播后恢复检测');
    check(replayed.painted > 0, '重播后骨架重新画出来');

    check(replayed.loopErrors === 0, `循环没有因异常中断（${replayed.loopErrors} 次）`);
    check(replayed.loopRestarts === 0, `循环不需要看门狗重启（${replayed.loopRestarts} 次）`);
    check(replayed.resetFailures === 0, '追踪器重置没有失败（本地 bundle 支持 setOptions）');
    check(videoErrors.length === 0, `播放过程中没有脚本错误${videoErrors.length ? '：' + videoErrors[0] : ''}`);
    await video.close();
  }
} finally {
  await browser.close();
  if (server) server.kill();
  fs.rmSync(dataDir, { recursive: true, force: true });
}

if (failures === 0) {
  console.log('PASS');
} else {
  console.error(failures + ' failure(s)');
  process.exit(1);
}
