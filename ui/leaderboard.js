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
export function createProfile(username) {
  const deviceId = localStorage.getItem('cadence-device-id') || crypto.randomUUID();
  localStorage.setItem('cadence-device-id', deviceId);
  return { deviceId, profileId: deviceId, username: username || '同学A' };
}
