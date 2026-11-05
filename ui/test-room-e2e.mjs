// Browser-level regression for the LAN room.
//
// The bug this locks down: each page kept its own copy of the chat list, so
// polling updated the store while the view kept rendering the stale copy —
// messages only appeared when the local user pressed send.
//
// This test drives two independent browser contexts. Neither one sends after
// the other's message; both must still see it (and the leaderboard entry).
//
// Requires playwright-core plus a Chromium binary:
//   npm i playwright-core
//   npx playwright install chromium
// Optional overrides: CHROME_PATH, PORT, BASE_URL.
//
// Run: node ui/test-room-e2e.mjs
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(__dirname, '..');
const PORT = Number(process.env.PORT || 8797);
const BASE = process.env.BASE_URL || `http://127.0.0.1:${PORT}/`;

async function loadChromium() {
  // Try the usual resolution first, then PLAYWRIGHT_PATH, then a local install.
  const candidates = [
    'playwright-core',
    process.env.PLAYWRIGHT_PATH,
    path.join(root, 'node_modules', 'playwright-core', 'index.mjs'),
  ].filter(Boolean);
  for (const candidate of candidates) {
    try {
      const module = await import(candidate);
      if (module.chromium) return module.chromium;
    } catch (_) { /* try the next one */ }
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

const chromium = await loadChromium();
const executablePath = findChrome();
if (!chromium || !executablePath) {
  console.log('SKIP: playwright-core or a Chromium binary is not available');
  process.exit(0);
}

const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'cadence-room-'));
// EXTERNAL=1 points the test at an already-running room service (for example
// the JVM one the APK ships) instead of starting the Node one.
const external = process.env.EXTERNAL === '1';
const server = external ? null : spawn(process.execPath, [path.join(root, 'database/local-server/server.mjs')], {
  env: { ...process.env, PORT: String(PORT), HOST: '127.0.0.1', DATA_DIR: dataDir },
  stdio: 'ignore',
});

const failures = [];
function check(condition, message) {
  if (condition) console.log(`ok - ${message}`);
  else { console.error(`FAIL: ${message}`); failures.push(message); }
}

async function waitForServer(timeoutMs = 15000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(`http://127.0.0.1:${PORT}/api/v1/health`);
      if (response.ok) return;
    } catch (_) { /* not up yet */ }
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  throw new Error('room service did not start');
}

async function until(page, predicate, timeoutMs = 15000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await predicate()) return true;
    await page.waitForTimeout(500);
  }
  return false;
}

const browser = await chromium.launch({ executablePath, args: ['--no-sandbox'] });

try {
  if (!external) await waitForServer();
  const alice = await browser.newContext();
  const bob = await browser.newContext();
  const a = await alice.newPage();
  const b = await bob.newPage();
  for (const [label, page] of [['A', a], ['B', b]]) {
    page.on('pageerror', (error) => {
      console.error(`FAIL: page ${label} threw ${error.message}`);
      failures.push(`${label} pageerror`);
    });
  }

  await a.goto(BASE, { waitUntil: 'domcontentloaded' });
  await b.goto(BASE, { waitUntil: 'domcontentloaded' });
  await a.fill('#who', 'YG-A');
  await a.dispatchEvent('#who', 'input');
  await b.fill('#who', 'YG-B');
  await b.dispatchEvent('#who', 'input');

  // A sends; B never sends. B must still render A's message.
  await a.fill('#chatInput', '来自A的消息');
  await a.click('#chatForm button');
  const bSawA = await until(b, async () => (await b.textContent('#chatLog')).includes('来自A的消息'));
  check(bSawA, 'B sees A without sending');

  await b.fill('#chatInput', '来自B的消息');
  await b.click('#chatForm button');
  const aSawB = await until(a, async () => (await a.textContent('#chatLog')).includes('来自B的消息'));
  check(aSawB, 'A sees B without sending');

  const once = (await b.textContent('#chatLog')).split('来自A的消息').length - 1;
  check(once === 1, 'message is rendered exactly once on the receiving page');

  // Leaderboard: inject a training event as a third client, A must update.
  await fetch(`http://127.0.0.1:${PORT}/api/v1/events`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      events: [{
        eventId: 'e2e-event-1', profileId: 'profile-carol', deviceId: 'device-carol',
        username: '同学C', exercise: 'squat', repetitions: 30, durationSeconds: 300,
        quality: 0.9, points: 300, occurredAt: Date.now(), ruleVersion: 'model-v1-wasm',
      }],
    }),
  });
  const aSawEvent = await until(a, async () => (await a.textContent('#board')).includes('同学C'));
  check(aSawEvent, 'A sees a leaderboard entry pushed by another client');
} finally {
  await browser.close();
  if (server) server.kill();
  fs.rmSync(dataDir, { recursive: true, force: true });
}

if (failures.length) {
  console.error(`ROOM E2E FAILED (${failures.length})`);
  process.exitCode = 1;
} else {
  console.log('PASS');
}
