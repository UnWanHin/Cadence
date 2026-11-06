// Regression test for the LAN chat: two clients must see each other's
// messages. Reproduces the reported bug ("B can't see A, A can't see B").
//
// Run: node ui/test-chat.cjs
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { mergeMessages, signature, createChatStore, withDisplayNames } from './chat.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(__dirname, '..');
const PORT = 8799;
const BASE = `http://127.0.0.1:${PORT}/api/v1`;

function assert(condition, message) {
  if (!condition) {
    console.error(`FAIL: ${message}`);
    process.exitCode = 1;
    throw new Error(message);
  }
  console.log(`ok - ${message}`);
}

async function waitForServer(timeoutMs = 15000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(`${BASE}/health`);
      if (response.ok) return;
    } catch (_) { /* not up yet */ }
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  throw new Error('server did not start');
}

function request(pathname, options) {
  return fetch(`${BASE}${pathname}`, options).then(async (response) => {
    if (!response.ok) throw new Error(`${pathname} -> ${response.status}`);
    return response.json();
  });
}

// --- unit: merge keeps one copy per logical message -----------------------
{
  const sentAt = 1_700_000_000_000;
  const local = { profileId: 'a', text: 'hi', sentAt };
  const fromServer = { messageId: 'server-1', profileId: 'a', text: 'hi', sentAt };
  const merged = mergeMessages([local], [fromServer]);
  assert(merged.length === 1, 'optimistic echo and server copy collapse to one message');
  assert(merged[0].messageId === 'server-1', 'server-assigned messageId wins');

  const empty = mergeMessages([], [{ messageId: 'x', profileId: 'b', text: 'yo', sentAt: sentAt + 1 }]);
  assert(empty.length === 1, 'merging into an empty store adds the message');
  assert(signature(empty[0]) === 'b|' + (sentAt + 1) + '|yo', 'signature is stable');
}

// --- unit: chat history follows a renamed profile ------------------------
{
  const oldMessage = { messageId: 'm1', profileId: 'p1', username: '旧名字', text: '之前发的', sentAt: 1 };
  const newMessage = { messageId: 'm2', profileId: 'p1', username: '新名字', text: '刚刚发的', sentAt: 2 };
  const renamed = withDisplayNames([oldMessage, newMessage], [], { profileId: 'me', username: '我' });
  assert(renamed[0].username === '新名字', 'old chat messages show the newest name from that profile');

  const local = withDisplayNames([oldMessage], [], { profileId: 'p1', username: '本机新名字' });
  assert(local[0].username === '本机新名字', 'the local profile name applies immediately');
}

// --- integration: A sends, B reads ---------------------------------------
// Hermetic: each run gets its own data dir so leftovers can't leak in.
const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'cadence-chat-'));
const server = spawn(process.execPath, [path.join(root, 'database/local-server/server.mjs')], {
  env: { ...process.env, PORT: String(PORT), HOST: '127.0.0.1', DATA_DIR: dataDir },
  stdio: 'ignore',
});

try {
  await waitForServer();

  const alice = createChatStore(request);
  const bob = createChatStore(request);

  await alice.send({ username: 'YG', profileId: 'profile-yg' }, 'A 的消息');
  await bob.pull();
  const seenByBob = bob.messages.find((message) => message.text === 'A 的消息');
  assert(Boolean(seenByBob), 'B sees the message A sent');
  assert(seenByBob.username === 'YG', 'B sees the sender name');

  await bob.send({ username: '同学B', profileId: 'profile-b' }, 'B 的消息');
  await alice.pull();
  const seenByAlice = alice.messages.find((message) => message.text === 'B 的消息');
  assert(Boolean(seenByAlice), 'A sees the message B sent');

  assert(alice.messages.length === 2, 'A holds both messages exactly once');
  assert(bob.messages.length === 2, 'B holds both messages exactly once');
} finally {
  server.kill();
  fs.rmSync(dataDir, { recursive: true, force: true });
}

if (process.exitCode) {
  console.error('CHAT TEST FAILED');
} else {
  console.log('PASS');
}
