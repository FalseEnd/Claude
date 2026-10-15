'use strict';
// 端對端測試：兩個帳號透過 HTTP API 與 WebSocket 走完主要流程。
// 需要 DATABASE_URL 指向一個空的資料庫（scripts/test.sh 會自動建立臨時的 PostgreSQL）。
const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const WebSocket = require('ws');
const core = require('../src/core');
const { createApp } = require('../src/index');

let app;
let base;
const A = {}; // 阿哲
const B = {}; // 小雨

before(async () => {
  // 時區跟測試程式一致，core.localDate() 算出的「今天」才會和排程相同
  const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  app = await createApp({ port: 0, databaseUrl: process.env.DATABASE_URL, secret: 'test-secret', maxUsers: 2, timezone },
    { log: () => {}, startJobs: false });
  base = `http://127.0.0.1:${await app.listen(0)}`;
});
after(async () => {
  for (const u of [A, B]) if (u.sock) u.sock.ws.close();
  await app.close();
});

async function api(user, method, path, body) {
  const res = await fetch(base + path, {
    method,
    headers: { 'content-type': 'application/json', ...(user && user.token ? { authorization: `Bearer ${user.token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  return { status: res.status, data: await res.json() };
}
const ok = async (user, method, path, body) => {
  const r = await api(user, method, path, body);
  assert.equal(r.status, 200, `${method} ${path} → ${r.status} ${JSON.stringify(r.data)}`);
  return r.data;
};

function connect(user) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`${base.replace('http', 'ws')}/ws?token=${user.token}`);
    const msgs = [];
    const waiters = [];
    ws.on('message', (raw) => {
      const m = JSON.parse(raw.toString());
      // 跟 App 一樣：收到通知就回 ack，伺服器才不會在下次連線時重送
      if (m.type === 'notification') ws.send(JSON.stringify({ type: 'ack', id: m.id }));
      msgs.push(m);
      for (const w of waiters.slice()) if (w.pred(m)) { waiters.splice(waiters.indexOf(w), 1); w.resolve(m); }
    });
    const next = (pred, ms = 3000) => new Promise((res, rej) => {
      const found = msgs.find(pred);
      if (found) { msgs.splice(msgs.indexOf(found), 1); return res(found); }
      const w = { pred: (m) => { if (pred(m)) { msgs.splice(msgs.indexOf(m), 1); return true; } return false; }, resolve: res };
      waiters.push(w);
      setTimeout(() => { if (waiters.includes(w)) { waiters.splice(waiters.indexOf(w), 1); rej(new Error('等不到 WebSocket 訊息')); } }, ms);
    });
    const notified = (kind) => next((m) => m.type === 'notification' && m.kind === kind);
    ws.on('open', () => resolve({ ws, msgs, next, notified }));
    ws.on('error', reject);
  });
}

test('註冊人數上限、重複帳號、登入', async () => {
  Object.assign(A, await ok(null, 'POST', '/api/auth/register', { username: 'zhe', password: 'secret1', displayName: '阿哲' }));
  assert.equal((await api(null, 'POST', '/api/auth/register', { username: 'zhe', password: 'secret1', displayName: 'x' })).status, 409);
  Object.assign(B, await ok(null, 'POST', '/api/auth/register', { username: 'XiaoYu', password: 'secret2', displayName: '小雨' }));
  const third = await api(null, 'POST', '/api/auth/register', { username: 'mia', password: 'secret3', displayName: 'Mia' });
  assert.equal(third.status, 403);
  assert.equal(third.data.error, 'registration_closed');
  assert.equal((await api(null, 'POST', '/api/auth/login', { username: 'xiaoyu', password: 'wrong!' })).status, 401);
  assert.ok((await ok(null, 'POST', '/api/auth/login', { username: 'xiaoyu', password: 'secret2' })).token);
  assert.equal((await api(null, 'GET', '/api/me')).status, 401);
});

test('配對與 WebSocket 連線', async () => {
  const invite = await ok(A, 'POST', '/api/pair/invite');
  assert.match(invite.code, /^[A-HJ-NP-Z2-9]{6}$/);
  await ok(B, 'POST', '/api/pair/accept', { code: invite.code.toLowerCase(), togetherSince: '2025-05-20' });
  const me = await ok(A, 'GET', '/api/me');
  assert.equal(me.user.username, 'zhe');
  assert.equal(me.partner.displayName, '小雨');
  assert.equal(me.couple.togetherSince, '2025-05-20');
  A.sock = await connect(A);
  B.sock = await connect(B);
  // 配對成功的通知在 A 連線前就產生了：連線時會補送
  const paired = await A.sock.notified('pairing');
  assert.match(paired.title, /配對成功/);
});

test('分享設定變更會通知對方；手機狀態依分享與暫停顯示', async () => {
  await ok(A, 'PUT', '/api/sharing', { location: true, device: true, expenses: true });
  const n = await B.sock.notified('sharing_changed');
  assert.match(n.title, /阿哲 更新了分享設定/);
  await B.sock.next((m) => m.type === 'changed' && m.table === 'sharing_settings');

  await ok(A, 'PUT', '/api/status/device', { batteryLevel: 60, isCharging: false, network: 'wifi', networkLabel: '公司', stepsToday: 3200, deviceModel: 'Pixel 8' });
  await ok(A, 'PUT', '/api/status/location', { lat: 25.033, lng: 121.565, accuracyM: 12 });
  let st = await ok(B, 'GET', '/api/partner/status');
  assert.equal(st.device.batteryLevel, 60);
  assert.equal(st.device.deviceModel, 'Pixel 8');
  assert.equal(st.location.lat, 25.033);

  await ok(A, 'PUT', '/api/status/device', { batteryLevel: 18, isCharging: false, network: 'cellular' });
  assert.match((await B.sock.notified('battery_low')).title, /電量剩 18%/);

  await ok(A, 'POST', '/api/sharing/pause', { minutes: 60 });
  st = await ok(B, 'GET', '/api/partner/status');
  assert.equal(st.location, null);
  assert.equal(st.device, null);
  await ok(A, 'POST', '/api/sharing/pause', { minutes: 0 });
  st = await ok(B, 'GET', '/api/partner/status');
  assert.equal(st.device.batteryLevel, 18);
});

test('地點標記：被偵測的人會知道，抵達時通知對方', async () => {
  const place = await ok(B, 'POST', '/api/places', { name: '阿哲公司', icon: 'work', lat: 25.04, lng: 121.56, radiusM: 200, watch: 'partner' });
  assert.equal(place.watchUser, A.userId);
  assert.match((await A.sock.notified('sharing_changed')).title, /新增了地點「阿哲公司」/);
  await ok(A, 'POST', `/api/places/${place.id}/events`, { kind: 'arrive' });
  assert.match((await B.sock.notified('place')).title, /阿哲 已抵達 阿哲公司/);
  const updated = await ok(A, 'PUT', `/api/places/${place.id}`, { radiusM: 300, watch: 'both' });
  assert.equal(updated.radiusM, 300);
  assert.equal(updated.watchUser, null);
  assert.equal((await ok(B, 'GET', '/api/places')).length, 1);
});

test('自動記帳去重與猜花費', async () => {
  const body = { amount: 135, merchant: '星巴克 信義新天地', category: 'drink', source: 'notification', guessable: true, dedupeKey: 'k1' };
  const e = await ok(A, 'POST', '/api/expenses', body);
  assert.equal(e.amount, 135);
  assert.deepEqual(await ok(A, 'POST', '/api/expenses', body), { duplicate: true });
  await ok(A, 'POST', '/api/expenses', { amount: 520, merchant: '花店', category: 'gift', visibility: 'private' });

  let list = await ok(B, 'GET', '/api/partner/expenses');
  assert.equal(list.length, 1);
  assert.equal(list[0].amount, null);
  const g = await ok(B, 'POST', `/api/partner/expenses/${e.id}/guess`, { amount: 130 });
  assert.equal(g.scorePoints, 80);
  assert.equal(g.scorePoints, core.scoreGuess(130, 135).points);
  assert.equal((await api(B, 'POST', `/api/partner/expenses/${e.id}/guess`, { amount: 140 })).status, 409);
  assert.match((await A.sock.notified('guess')).body, /猜 NT\$130/);
  list = await ok(B, 'GET', '/api/partner/expenses');
  assert.equal(list[0].amount, 135);
  const mine = await ok(A, 'GET', '/api/expenses');
  assert.equal(mine.find((x) => x.id === e.id).theirGuess, 130);
});

test('心情、安撫、離線補送', async () => {
  await ok(B, 'POST', '/api/moods', { mood: 'sad', note: '被主管念了' });
  assert.match((await A.sock.notified('mood')).body, /要不要安撫一下/);
  assert.ok((await ok(A, 'GET', '/api/moods?days=7')).some((m) => m.mood === 'sad'));
  await ok(A, 'POST', '/api/comforts', { kind: 'hug', message: '晚上請你吃好料' });
  const c = await B.sock.notified('comfort');
  assert.match(c.title, /阿哲 給了你一個大大的抱抱/);
  assert.equal(c.body, '晚上請你吃好料');

  B.sock.ws.close();
  await ok(A, 'POST', '/api/comforts', { kind: 'pat' });
  B.sock = await connect(B);
  await B.sock.next((m) => m.type === 'notification' && /摸摸你的頭/.test(m.title));
});

test('「現在在哪」請對方回報位置', async () => {
  const r = await ok(A, 'POST', '/api/partner/locate');
  assert.equal(r.sent, true);
  await B.sock.next((m) => m.type === 'locate');
});

test('約會邀請、回覆與提醒排程', async () => {
  const startsAt = new Date(Date.now() + 30 * 60000).toISOString();
  const ev = await ok(A, 'POST', '/api/events', { title: '吃火鍋', startsAt, remindMinutes: [60], locationText: '老四川' });
  assert.match((await B.sock.notified('event')).title, /阿哲 約你：吃火鍋/);
  await ok(B, 'POST', `/api/events/${ev.id}/answer`, { status: 'confirmed' });
  assert.match((await A.sock.notified('event')).title, /小雨 答應了：吃火鍋/);
  assert.equal(await app.jobs.eventReminders(), 1);
  assert.match((await A.sock.notified('event')).title, /約會提醒：吃火鍋/);
  assert.match((await B.sock.notified('event')).title, /約會提醒：吃火鍋/);
  assert.equal(await app.jobs.eventReminders(), 0);
});

test('經期分享與經期前關懷提醒', async () => {
  const today = core.localDate();
  await ok(B, 'PUT', '/api/cycle/settings', { careReminders: true, partnerCanEdit: true });
  await ok(B, 'POST', '/api/cycle/periods', { startDate: core.addDays(today, -26), endDate: core.addDays(today, -22) });
  let cy = await ok(A, 'GET', '/api/cycle');
  assert.equal(cy.partner.periods.length, 0, '還沒分享時看不到');
  await ok(B, 'PUT', '/api/sharing', { cycle: true });
  cy = await ok(A, 'GET', '/api/cycle');
  assert.equal(cy.partner.periods.length, 1);
  assert.equal(cy.partner.periods[0].startDate, core.addDays(today, -26));
  await ok(B, 'PUT', `/api/cycle/logs/${today}`, { flow: 'light', symptoms: ['經痛'] });
  assert.equal((await ok(A, 'GET', '/api/cycle')).partner.logs, undefined);

  assert.equal(await app.jobs.cycleCare(today), 1);
  assert.match((await A.sock.notified('cycle_care')).title, /小雨 的經期預計 2 天後開始/);
  assert.equal(await app.jobs.cycleCare(today), 0, '同一次經期只提醒一次');
  // 對方允許時可以幫忙記錄
  const p = await ok(A, 'POST', '/api/cycle/periods', { owner: 'partner', startDate: core.addDays(today, 2) });
  assert.equal(p.userId, B.userId);
});

test('紀念日與每日提醒', async () => {
  const today = core.localDate();
  await ok(A, 'POST', '/api/anniversaries', { title: '小雨生日', date: '1999-' + core.addDays(today, 7).slice(5), kind: 'birthday', remindDays: [0, 7] });
  assert.equal((await ok(B, 'GET', '/api/anniversaries')).length, 1);
  const r = await app.jobs.runAll(new Date(), { force: true });
  assert.ok(r.daily.reminders >= 2);
  assert.match((await B.sock.notified('anniversary')).title, /小雨生日/);
});

test('歷史紀錄與通知設定', async () => {
  const h = await ok(A, 'GET', '/api/history?limit=100');
  const kinds = new Set(h.map((x) => x.kind));
  for (const k of ['paired', 'mood', 'comfort', 'guess', 'place_arrive', 'event_created', 'event_confirmed', 'battery_low', 'sharing_changed']) {
    assert.ok(kinds.has(k), `history 應包含 ${k}`);
  }
  const moods = await ok(A, 'GET', '/api/history?kinds=mood');
  assert.ok(moods.every((x) => x.kind === 'mood'));
  await ok(A, 'PUT', '/api/notification-prefs', { comfort: false });
  assert.equal((await ok(A, 'GET', '/api/notification-prefs')).comfort, false);
  await ok(B, 'POST', '/api/comforts', { kind: 'kiss' });
  await assert.rejects(A.sock.notified('comfort'), /等不到/);
});

test('解除配對後立即看不到對方', async () => {
  await ok(B, 'POST', '/api/pair/unpair');
  const me = await ok(A, 'GET', '/api/me');
  assert.equal(me.partner, null);
  const st = await ok(A, 'GET', '/api/partner/status');
  assert.equal(st.device, null);
  assert.equal((await ok(A, 'GET', '/api/places')).length, 0);
});

test('改密碼後舊的登入失效', async () => {
  await new Promise((r) => setTimeout(r, 1100));
  const { token } = await ok(A, 'POST', '/api/auth/password', { oldPassword: 'secret1', newPassword: 'secret9' });
  assert.equal((await api(A, 'GET', '/api/me')).status, 401);
  assert.equal((await api({ token }, 'GET', '/api/me')).status, 200);
});
