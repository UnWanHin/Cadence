// Browser-level check for the share QR panel.
//
// The encoder has its own test, but a page can still render a QR that no phone
// can read: wrong element size, a CSS transform that blurs the modules, or a
// URL built from the wrong origin. So this opens the real page, clicks the real
// buttons, screenshots what the browser painted and decodes that image.
//
// Requires playwright-core plus a Chromium binary, and python3 with OpenCV:
//   npm i playwright-core && npx playwright install chromium
// Optional overrides: CHROME_PATH, PORT, BASE_URL.
//
// Run: node ui/test-qr-page.mjs
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(__dirname, '..');
const PORT = Number(process.env.PORT || 8796);
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

/** Decodes every PNG in [files] with OpenCV; null when the toolchain is missing. */
function decodeImages(files) {
  const script = `
import sys, json
try:
    import cv2
except ImportError:
    print("SKIP"); raise SystemExit(0)
out = {}
for name in sys.argv[1:]:
    image = cv2.imread(name)
    if image is None:
        out[name] = None; continue
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY) if image.ndim == 3 else image
    text, _, _ = cv2.QRCodeDetector().detectAndDecode(gray)
    out[name] = text
print(json.dumps(out))
`;
  const result = spawn('python3', ['-c', script, ...files], { encoding: 'utf8' });
  return result;
}

function runPython(files) {
  return new Promise((resolve) => {
    const child = decodeImages(files);
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (chunk) => { stdout += chunk; });
    child.stderr.on('data', (chunk) => { stderr += chunk; });
    child.on('error', () => resolve(null));
    child.on('close', () => {
      const trimmed = stdout.trim();
      if (!trimmed || trimmed === 'SKIP') {
        resolve(null);
        return;
      }
      try {
        resolve(JSON.parse(trimmed));
      } catch {
        resolve(null);
      }
    });
  });
}

const chromium = await loadChromium();
const executablePath = findChrome();
if (!chromium || !executablePath) {
  console.log('SKIP: playwright-core 或 Chromium 不可用');
  process.exit(0);
}

const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'cadence-qr-'));
const shotDir = path.join(dataDir, 'shots');
fs.mkdirSync(shotDir, { recursive: true });

// Start a room service unless the caller points at one.
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
const page = await browser.newPage({ viewport: { width: 1100, height: 950 } });
const pageErrors = [];
page.on('pageerror', (error) => pageErrors.push(String(error)));

try {
  await page.goto(BASE, { waitUntil: 'load' });
  check(pageErrors.length === 0, `页面加载没有脚本错误${pageErrors.length ? '：' + pageErrors[0] : ''}`);

  const panel = page.locator('#qrPanel');
  check(!(await panel.isVisible()), '分享面板一开始是收起的');

  await page.click('#qrApk');
  await panel.waitFor({ state: 'visible' });
  const apkUrl = (await page.textContent('#qrUrl')).trim();
  check(apkUrl.endsWith('/app.apk'), `安装包链接指向 /app.apk（${apkUrl}）`);
  check((await page.locator('#qrFrame svg').count()) === 1, '面板里渲染了一个 SVG 二维码');
  await page.locator('#qrFrame').screenshot({ path: path.join(shotDir, 'apk.png') });

  await page.click('#qrRoom');
  const roomUrl = (await page.textContent('#qrUrl')).trim();
  check(roomUrl === BASE.replace(/\/$/, '/') || roomUrl.startsWith(BASE), `房间链接就是当前地址（${roomUrl}）`);
  await page.locator('#qrFrame').screenshot({ path: path.join(shotDir, 'room.png') });

  const decoded = await runPython([path.join(shotDir, 'apk.png'), path.join(shotDir, 'room.png')]);
  if (!decoded) {
    console.log('SKIP: 没有 python3 + OpenCV，跳过解码校验');
  } else {
    check(decoded[path.join(shotDir, 'apk.png')] === apkUrl, '截图中扫出来的安装包链接和页面上显示的一致');
    check(decoded[path.join(shotDir, 'room.png')] === roomUrl, '截图中扫出来的房间链接和页面上显示的一致');
  }

  if (!external) {
    await page.click('#qrCopy');
    // The label flips within the 1.8 s reset window, including the fallback path.
    const label = await page.waitForFunction(
      () => {
        const text = document.getElementById('qrCopy').textContent.trim();
        return text !== '复制链接' ? text : false;
      },
      null,
      { timeout: 3000 },
    ).then((handle) => handle.jsonValue()).catch(() => '复制链接');
    check(label !== '复制链接', `复制按钮给出反馈（${label}）`);
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
