// 執行：npm test（或 node --test prototype/）
const test = require('node:test');
const assert = require('node:assert/strict');
const C = require('./core.js');

test('date helpers', () => {
  assert.equal(C.addDays('2026-02-27', 2), '2026-03-01');
  assert.equal(C.addDays('2024-02-28', 1), '2024-02-29');
  assert.equal(C.diffDays('2026-03-07', '2026-03-09'), 2); // 跨美國日光節約時間仍正確
  assert.equal(C.daysTogether('2026-10-15', '2026-10-15'), 1);
  assert.equal(C.daysTogether('2025-05-20', '2026-10-15'), 514);
});

test('nextOccurrence: yearly anniversaries roll to next year once passed', () => {
  assert.deepEqual(C.nextOccurrence('2023-05-20', '2026-10-15', 'yearly'), { date: '2027-05-20', daysLeft: 217, years: 4 });
  assert.deepEqual(C.nextOccurrence('1999-10-15', '2026-10-15', 'yearly'), { date: '2026-10-15', daysLeft: 0, years: 27 });
  assert.equal(C.nextOccurrence('2026-12-24', '2026-10-15', 'none').daysLeft, 70);
});

test('nextOccurrence: Feb 29 is celebrated on Feb 28 in common years', () => {
  assert.equal(C.nextOccurrence('2024-02-29', '2026-01-10', 'yearly').date, '2026-02-28');
  assert.equal(C.nextOccurrence('2024-02-29', '2027-03-01', 'yearly').date, '2028-02-29');
});

test('upcomingMilestones mixes hundreds with 520 / 1314', () => {
  const m = C.upcomingMilestones('2025-05-20', '2026-10-15', 3); // today is day 514
  assert.deepEqual(m.map((x) => x.day), [520, 600, 700]);
  assert.equal(m[0].date, '2026-10-21');
  assert.equal(m[0].daysLeft, 6);
  assert.deepEqual(C.upcomingMilestones('2026-10-15', '2026-10-15', 2).map((x) => x.day), [100, 200]);
});

const periods = [
  { start: '2026-06-01', end: '2026-06-05' },
  { start: '2026-06-30', end: '2026-07-05' },
  { start: '2026-07-28', end: '2026-08-02' },
  { start: '2026-08-25', end: '2026-08-29' },
  { start: '2026-09-22', end: '2026-09-27' }
];

test('cycleStats averages recent cycles and period lengths', () => {
  const s = C.cycleStats(periods);
  assert.equal(s.cycleLength, 28); // 29, 28, 28, 28 -> 28.25
  assert.equal(s.periodLength, 6); // 5,6,6,5,6 -> 5.6 -> 6
  assert.equal(s.samples, 4);
  assert.equal(s.irregular, false);
});

test('cycleStats ignores gaps from missed logs and honours manual settings', () => {
  const s = C.cycleStats([{ start: '2026-01-01' }, { start: '2026-04-01' }, { start: '2026-04-29' }]);
  assert.equal(s.cycleLength, 28); // 90 天的間隔被排除
  assert.equal(s.samples, 1);
  assert.equal(C.cycleStats(periods, { cycleLength: 32 }).cycleLength, 32);
  assert.equal(C.cycleStats([]).cycleLength, 28);
});

test('predictCycle: next period, ovulation and fertile window', () => {
  const p = C.predictCycle(periods, {}, '2026-10-15');
  assert.equal(p.cycleStart, '2026-09-22');
  assert.equal(p.cycleDay, 24);
  assert.equal(p.nextStart, '2026-10-20');
  assert.equal(p.daysUntilNext, 5);
  assert.equal(p.ovulation, '2026-10-06');
  assert.equal(p.fertileStart, '2026-10-01');
  assert.equal(p.fertileEnd, '2026-10-07');
  assert.equal(p.lateDays, 0);
  assert.deepEqual(p.today, { phase: 'safe', sub: 'luteal', predicted: true, cycleDay: 24 });
});

test('predictCycle: reports a late period instead of skipping ahead', () => {
  const p = C.predictCycle(periods, {}, '2026-10-25');
  assert.equal(p.lateDays, 5);
  assert.equal(p.nextStart, '2026-10-20');
  assert.equal(p.cycleDay, 34);
  assert.deepEqual(p.today, { phase: 'late' });
});

test('predictCycle: rolls forward when logs were missed for a whole cycle', () => {
  const p = C.predictCycle([{ start: '2026-08-01', end: '2026-08-05' }], {}, '2026-10-15');
  assert.equal(p.lateDays, 0);
  assert.equal(p.cycleStart, '2026-09-26');
  assert.equal(p.nextStart, '2026-10-24');
});

test('dayPhase classifies each day of a cycle', () => {
  const at = (d) => C.dayPhase(d, periods, {}, '2026-10-15');
  assert.equal(at('2026-05-30').phase, null);          // 第一筆紀錄之前
  assert.equal(at('2026-09-25').phase, 'period');
  assert.equal(at('2026-09-25').predicted, false);
  assert.equal(at('2026-09-30').sub, 'follicular');
  assert.equal(at('2026-10-02').phase, 'fertile');
  assert.equal(at('2026-10-06').phase, 'ovulation');
  assert.equal(at('2026-10-07').phase, 'fertile');
  assert.equal(at('2026-10-09').sub, 'luteal');
  assert.equal(at('2026-10-20').phase, 'period');
  assert.equal(at('2026-10-20').predicted, true);
  assert.equal(at('2026-08-11').phase, 'ovulation');   // 過去週期由下次開始日回推
});

test('geofenceStep fires arrive/leave once and ignores jitter at the edge', () => {
  const places = [{ id: 'home', x: 0, y: 0, radius: 100 }];
  let s = C.geofenceStep(places, [], { x: 300, y: 0 });
  assert.deepEqual(s.events, []);
  s = C.geofenceStep(places, s.inside, { x: 90, y: 0 });
  assert.deepEqual(s.events, [{ placeId: 'home', type: 'arrive' }]);
  s = C.geofenceStep(places, s.inside, { x: 115, y: 0 }); // 在緩衝區內飄移，不算離開
  assert.deepEqual(s.events, []);
  assert.deepEqual(s.inside, ['home']);
  s = C.geofenceStep(places, s.inside, { x: 130, y: 0 });
  assert.deepEqual(s.events, [{ placeId: 'home', type: 'leave' }]);
});

test('haversine matches a known distance (Taipei 101 to Taipei Main Station ~ 5 km)', () => {
  const d = C.haversine({ lat: 25.0340, lng: 121.5645 }, { lat: 25.0478, lng: 121.5170 });
  assert.ok(d > 4900 && d < 5200, String(d));
});

test('scoreGuess tiers', () => {
  assert.equal(C.scoreGuess(135, 135).points, 100);
  assert.equal(C.scoreGuess(130, 135).points, 80);
  assert.equal(C.scoreGuess(150, 135).direction, 'high');
  assert.equal(C.scoreGuess(100, 135).points, 20);
  assert.equal(C.scoreGuess(1000, 135).points, 5);
  assert.throws(() => C.scoreGuess(10, 0));
});

test('parseNotification extracts amount, merchant, source and category', () => {
  assert.deepEqual(C.parseNotification('LINE Pay｜付款完成 NT$135 商店：星巴克 信義新天地門市'), {
    amount: 135, merchant: '星巴克 信義新天地門市', source: 'linepay', sourceLabel: 'LINE Pay', category: 'drink'
  });
  const card = C.parseNotification('【國泰世華】您的卡片末四碼1234於10/15 12:03消費新台幣1,280元，商店：UNIQLO 微風南山店');
  assert.equal(card.amount, 1280);
  assert.equal(card.merchant, 'UNIQLO 微風南山店');
  assert.equal(card.source, 'card');
  assert.equal(card.category, 'shopping');
  const jko = C.parseNotification('街口支付：您已付款 65 元給 全家便利商店 松仁店');
  assert.equal(jko.amount, 65);
  assert.equal(jko.merchant, '全家便利商店 松仁店');
  assert.equal(jko.category, 'daily');
  const apple = C.parseNotification('Apple Pay 交易：台灣高鐵 NT$1,490');
  assert.equal(apple.merchant, '台灣高鐵');
  assert.equal(apple.category, 'transport');
  assert.equal(C.parseNotification('您的包裹已送達'), null);
});

test('summarizeExpenses groups by category for the month', () => {
  const ts = (d) => new Date(d + 'T12:00:00').getTime();
  const list = [
    { ts: ts('2026-10-01'), amount: 100, category: 'food' },
    { ts: ts('2026-10-02'), amount: 300, category: 'shopping' },
    { ts: ts('2026-10-03'), amount: 50, category: 'food' },
    { ts: ts('2026-09-30'), amount: 999, category: 'food' }
  ];
  const s = C.summarizeExpenses(list, '2026-10');
  assert.equal(s.total, 450);
  assert.equal(s.count, 3);
  assert.deepEqual(s.byCategory.map((c) => [c.key, c.amount]), [['shopping', 300], ['food', 150]]);
});

test('formatting', () => {
  assert.equal(C.money(1280), 'NT$1,280');
  assert.equal(C.money(1234567), 'NT$1,234,567');
  const now = new Date('2026-10-15T15:00:00').getTime();
  assert.equal(C.relTime(now - 20e3, now), '剛剛');
  assert.equal(C.relTime(now - 5 * 60e3, now), '5 分鐘前');
  assert.equal(C.relTime(now - 3 * 3600e3, now), '3 小時前');
  assert.equal(C.relTime(new Date('2026-10-14T21:30:00').getTime(), now), '昨天 21:30');
  assert.equal(C.relTime(new Date('2026-10-12T09:05:00').getTime(), now), '10/12 09:05');
});

test('mood helpers', () => {
  assert.equal(C.needsComfort('sad'), true);
  assert.equal(C.needsComfort('happy'), false);
  assert.equal(C.needsComfort('nope'), false);
  assert.equal(C.comfort('hug').label, '抱抱');
});
