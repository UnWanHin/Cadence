// Leaderboard rules: periods, scoring, ordering, dedupe.
//
// Run: node ui/test-leaderboard.mjs
import { rankEvents, periodStart, eventFromRecord, RANGES } from './leaderboard.js';

let failures = 0;
function check(condition, message) {
  if (condition) console.log(`ok - ${message}`);
  else { console.error(`FAIL: ${message}`); failures += 1; }
}

const HOUR = 3600 * 1000;
const DAY = 24 * HOUR;
const now = new Date(2026, 10, 4, 15, 0, 0).getTime(); // Wed 2026-11-04 15:00 local

function event(overrides) {
  return {
    eventId: `id-${Math.random().toString(16).slice(2)}`,
    profileId: 'p1', username: 'YG', points: 10,
    durationSeconds: 60, repetitions: 5,
    occurredAt: now, ...overrides,
  };
}

// --- ranges --------------------------------------------------------------
check(RANGES.length === 4 && RANGES.includes('all'), 'four ranges are offered');

const dayStart = periodStart('day', now);
check(new Date(dayStart).getHours() === 0 && new Date(dayStart).getMinutes() === 0, 'day range starts at local midnight');
check(dayStart <= now && now < dayStart + DAY, 'day range contains now');

const weekStart = periodStart('week', now);
check(new Date(weekStart).getDay() === 1, 'week range starts on Monday');
check(weekStart <= now && now < weekStart + 7 * DAY, 'week range contains now');

const monthStart = periodStart('month', now);
check(new Date(monthStart).getDate() === 1, 'month range starts on the 1st');
check(new Date(monthStart).getMonth() === 10, 'month range uses the current month');

check(periodStart('all', now) === 0, 'all range has no lower bound');

// --- period filtering ----------------------------------------------------
const yesterday = rankEvents([event({ occurredAt: now - DAY })], 'day', now);
check(yesterday.length === 0, 'yesterday does not score on the day board');
const today = rankEvents([event({ occurredAt: now })], 'day', now);
check(today.length === 1, 'today scores on the day board');

const lastMonth = rankEvents([event({ occurredAt: new Date(2026, 9, 20).getTime() })], 'month', now);
check(lastMonth.length === 0, 'last month does not score on the month board');

const oldButCounted = rankEvents([event({ occurredAt: new Date(2020, 0, 1).getTime() })], 'all', now);
check(oldButCounted.length === 1, 'the all-time board keeps old events');

const beforeWeek = rankEvents([event({ occurredAt: weekStart - 1 })], 'week', now);
check(beforeWeek.length === 0, 'an event just before the week start is excluded');
const atWeek = rankEvents([event({ occurredAt: weekStart })], 'week', now);
check(atWeek.length === 1, 'an event exactly at the week start is included');

// --- demo records --------------------------------------------------------
check(rankEvents([event({ demo: true })], 'all', now).length === 0, 'demo events never score');
check(rankEvents([event({ demo: false })], 'all', now).length === 1, 'real events score');

// --- aggregation ---------------------------------------------------------
const summed = rankEvents([
  event({ points: 10, durationSeconds: 60, repetitions: 5 }),
  event({ points: 15, durationSeconds: 40, repetitions: 3 }),
], 'day', now);
check(summed.length === 1, 'one profile produces one row');
check(summed[0].points === 25, 'points are summed');
check(summed[0].durationSeconds === 100, 'duration is summed');
check(summed[0].repetitions === 8, 'repetitions are summed');

// --- ordering ------------------------------------------------------------
const ordered = rankEvents([
  event({ profileId: 'low', points: 50 }),
  event({ profileId: 'high', points: 90 }),
], 'day', now);
check(ordered[0].profileId === 'high', 'higher points rank first');

const tieDuration = rankEvents([
  event({ profileId: 'short', points: 50, durationSeconds: 60 }),
  event({ profileId: 'long', points: 50, durationSeconds: 600 }),
], 'day', now);
check(tieDuration[0].profileId === 'long', 'equal points break on longer duration');

const tieReps = rankEvents([
  event({ profileId: 'few', points: 50, durationSeconds: 60, repetitions: 5 }),
  event({ profileId: 'many', points: 50, durationSeconds: 60, repetitions: 50 }),
], 'day', now);
check(tieReps[0].profileId === 'many', 'equal points and duration break on more repetitions');

const tieAll = rankEvents([
  event({ profileId: 'bbb', points: 50, durationSeconds: 60, repetitions: 5 }),
  event({ profileId: 'aaa', points: 50, durationSeconds: 60, repetitions: 5 }),
], 'day', now);
check(tieAll[0].profileId === 'aaa', 'full tie breaks deterministically on profileId');

// --- dedupe --------------------------------------------------------------
const duplicated = rankEvents([
  event({ eventId: 'same', points: 40 }),
  event({ eventId: 'same', points: 40 }),
], 'day', now);
check(duplicated[0].points === 40, 'a duplicated eventId is counted once');

// --- identity ------------------------------------------------------------
const sameName = rankEvents([
  event({ profileId: 'p1', username: '同学A', points: 10 }),
  event({ profileId: 'p2', username: '同学A', points: 20 }),
], 'day', now);
check(sameName.length === 2, 'two devices sharing a username stay separate rows');

// --- rename --------------------------------------------------------------
const renamed = rankEvents([
  event({ profileId: 'p1', username: '旧名字', points: 10, occurredAt: now - HOUR }),
  event({ profileId: 'p1', username: '新名字', points: 10, occurredAt: now }),
], 'day', now);
check(renamed.length === 1, 'a renamed profile is still one row');
check(renamed[0].username === '新名字', 'the row shows the newest name');
check(renamed[0].points === 20, 'a rename does not lose points');

// --- record mapping ------------------------------------------------------
const record = { id: 'r1', t: now, exercise: 'squat', reps: 12, activeMs: 90000, quality: 88, pts: 40, version: 'model-v1-wasm' };
const mapped = eventFromRecord(record, { profileId: 'p1', deviceId: 'd1', username: 'YG' });
check(mapped.eventId === 'r1', 'record id becomes eventId');
check(mapped.points === 40, 'record points carry over');
check(mapped.durationSeconds === 90, 'activeMs converts to seconds');
check(mapped.quality === 0.88, 'quality converts to a 0-1 ratio');
check(mapped.profileId === 'p1' && mapped.username === 'YG', 'profile identity is attached');

const demoRecord = eventFromRecord({ ...record, demo: true }, { profileId: 'p1', deviceId: 'd1', username: 'YG' });
check(demoRecord.demo === true, 'a demo record keeps its demo flag');
check(rankEvents([demoRecord], 'all', now).length === 0, 'a mapped demo record does not score');

if (failures) {
  console.error(`LEADERBOARD TEST FAILED (${failures})`);
  process.exitCode = 1;
} else {
  console.log('PASS');
}
