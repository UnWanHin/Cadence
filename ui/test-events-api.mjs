// Room service rules for workout events: dedupe, demo rejection, bounds.
//
// Run: node ui/test-events-api.mjs
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(__dirname, '..');
const PORT = 8796;
const BASE = `http://127.0.0.1:${PORT}/api/v1`;

let failures = 0;
function check(condition, message) {
  if (condition) console.log(`ok - ${message}`);
  else { console.error(`FAIL: ${message}`); failures += 1; }
}

function event(overrides) {
  return {
    eventId: 'e1', profileId: 'p1', deviceId: 'd1', username: 'YG',
    exercise: 'squat', repetitions: 10, durationSeconds: 60,
    points: 100, occurredAt: Date.now(), ruleVersion: 'model-v1-wasm', ...overrides,
  };
}

async function post(body) {
  const response = await fetch(`${BASE}/events`, {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body),
  });
  return { status: response.status, body: await response.json() };
}
async function list() {
  const response = await fetch(`${BASE}/events`);
  return (await response.json()).events;
}
async function waitForServer(timeoutMs = 15000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try { if ((await fetch(`${BASE}/health`)).ok) return; } catch (_) { /* not up */ }
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  throw new Error('room service did not start');
}

const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'cadence-events-'));
const server = spawn(process.execPath, [path.join(root, 'database/local-server/server.mjs')], {
  env: { ...process.env, PORT: String(PORT), HOST: '127.0.0.1', DATA_DIR: dataDir },
  stdio: 'ignore',
});

try {
  await waitForServer();
  check((await list()).length === 0, 'the room starts empty');

  await post({ events: [event({ eventId: 'a' })] });
  check((await list()).length === 1, 'an event is stored');

  await post({ events: [event({ eventId: 'a' })] });
  check((await list()).length === 1, 'a repeated eventId is ignored');

  await post({ events: [event({ eventId: 'demo', demo: true })] });
  const afterDemo = await list();
  check(afterDemo.length === 1, 'a demo event is rejected');
  check(!afterDemo.some((item) => item.eventId === 'demo'), 'the demo event is not stored');

  await post({ events: [event({ eventId: 'negative', points: -5 })] });
  check((await list()).length === 1, 'a negative score is rejected');

  await post({ events: [event({ eventId: 'huge', points: 999999 })] });
  check((await list()).length === 1, 'an out-of-range score is rejected');

  await post({ events: [event({ eventId: '' })] });
  check((await list()).length === 1, 'an event without an id is rejected');

  await post({ events: [event({ eventId: 'a' }), event({ eventId: 'a' })] });
  check((await list()).length === 1, 'a batch containing a duplicate is deduped');

  await post({ events: [
    event({ eventId: 'p1-a', profileId: 'p1', points: 40 }),
    event({ eventId: 'p2-a', profileId: 'p2', points: 60 }),
  ] });
  check((await list()).length === 3, 'events from different profiles are kept');
// A browser POSTing JSON cross-origin preflights with OPTIONS. Answering 405
// there makes the real request never happen, which breaks HTML <-> phone.
{
  const preflight = await fetch(`${BASE}/events`, {
    method: 'OPTIONS',
    headers: {
      Origin: 'http://127.0.0.1:9999',
      'Access-Control-Request-Method': 'POST',
      'Access-Control-Request-Headers': 'content-type',
    },
  });
  check(preflight.ok, 'OPTIONS preflight on /events is answered');
  check(
    preflight.headers.get('access-control-allow-origin') === '*',
    'preflight allows any origin',
  );
  check(
    (preflight.headers.get('access-control-allow-headers') || '').toLowerCase().includes('content-type'),
    'preflight allows the content-type header',
  );
}

} finally {
  server.kill();
  fs.rmSync(dataDir, { recursive: true, force: true });
}

if (failures) {
  console.error(`EVENTS API TEST FAILED (${failures})`);
  process.exitCode = 1;
} else {
  console.log('PASS');
}
