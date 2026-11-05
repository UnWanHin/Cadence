// Local-first leaderboard and LAN chat client.
export const RANGES = ['day', 'week', 'month', 'all'];
export function periodStart(range, timestamp = Date.now()) {
  const date = new Date(timestamp);
  if (range === 'all') return 0;
  if (range === 'day') return new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime();
  if (range === 'month') return new Date(date.getFullYear(), date.getMonth(), 1).getTime();
  const day = date.getDay() || 7;
  return new Date(date.getFullYear(), date.getMonth(), date.getDate() - day + 1).getTime();
}
export function rankEvents(events, range = 'week', now = Date.now()) {
  const start = periodStart(range, now);
  const grouped = new Map();
  const seen = new Set();
  for (const event of events) {
    if (event.eventId && seen.has(event.eventId)) continue;
    if (event.eventId) seen.add(event.eventId);
    if (event.demo || Number(event.occurredAt || event.t) < start) continue;
    const id = event.profileId || event.deviceId || event.n || 'unknown';
    const at = Number(event.occurredAt || event.t) || 0;
    const row = grouped.get(id) || {
      profileId: id,
      username: event.username || event.n || '同学',
      points: 0,
      durationSeconds: 0,
      repetitions: 0,
      latestAt: 0,
    };
    // A player may rename themselves; show the name from their newest event.
    if (at >= row.latestAt) {
      row.latestAt = at;
      row.username = event.username || event.n || row.username;
    }
    row.points += Number(event.points ?? event.pts) || 0;
    row.durationSeconds += Number(event.durationSeconds ?? (event.activeMs || 0) / 1000) || 0;
    row.repetitions += Number(event.repetitions ?? event.reps) || 0;
    grouped.set(id, row);
  }
  return [...grouped.values()].sort((a, b) => b.points - a.points || b.durationSeconds - a.durationSeconds || b.repetitions - a.repetitions || a.profileId.localeCompare(b.profileId));
}
export function eventFromRecord(record, profile) {
  return {
    eventId: record.eventId || record.id,
    profileId: profile.profileId,
    deviceId: profile.deviceId,
    username: profile.username,
    exercise: record.exercise,
    repetitions: record.reps || 0,
    durationSeconds: Math.round((record.activeMs || 0) / 1000),
    quality: (record.quality || 0) / 100,
    points: record.pts || 0,
    occurredAt: record.t || Date.now(),
    ruleVersion: record.version || 'model-v1-wasm',
    // Demo runs must never score; rankEvents drops events carrying this flag.
    demo: record.demo === true,
  };
}
/**
 * crypto.randomUUID() only exists in a secure context (HTTPS or localhost).
 * A phone opening this page over http://192.168.x.x is NOT secure, so calling
 * it directly throws and takes the whole module down. Fall back to random bytes.
 */
export function randomId() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    try { return crypto.randomUUID(); } catch (_) { /* fall through */ }
  }
  const bytes = new Uint8Array(16);
  if (typeof crypto !== 'undefined' && typeof crypto.getRandomValues === 'function') {
    crypto.getRandomValues(bytes);
  } else {
    for (let i = 0; i < bytes.length; i += 1) bytes[i] = Math.floor(Math.random() * 256);
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = [...bytes].map((b) => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export function createProfile(username, storage = safeStorage()) {
  let deviceId = '';
  try { deviceId = storage.getItem('cadence-device-id') || ''; } catch (_) { deviceId = ''; }
  if (!deviceId) {
    deviceId = randomId();
    try { storage.setItem('cadence-device-id', deviceId); } catch (_) { /* private mode */ }
  }
  return { deviceId, profileId: deviceId, username: username || '同学A' };
}

/** Some browsers throw on localStorage access (private mode, blocked storage). */
export function safeStorage() {
  try {
    if (typeof localStorage !== 'undefined') return localStorage;
  } catch (_) { /* blocked */ }
  const map = new Map();
  return {
    getItem: (key) => (map.has(key) ? map.get(key) : null),
    setItem: (key, value) => { map.set(key, String(value)); },
    removeItem: (key) => { map.delete(key); },
  };
}
