'use strict';
// 端對端測試：兩個帳號透過 Supabase Auth、RPC、Realtime 與 Storage 走完主要流程（跟 App 的呼叫方式相同）。
// 需要一個剛 reset 過的 Supabase（supabase/scripts/test.sh 會用 Supabase CLI 啟動本機的）：
//   SUPABASE_URL          http://127.0.0.1:54321
//   SUPABASE_KEY          anon 或 publishable key（App 用的那把）
//   SUPABASE_SERVICE_KEY  service_role key：手動觸發排程、測試結束時刪掉測試帳號
//   DATABASE_URL          （選填）測試說明文件裡「忘記密碼」的 SQL
// 只用 Node 22 內建的 fetch 與 WebSocket，不需要安裝套件。
const { test, after } = require('node:test');
const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');
const crypto = require('node:crypto');
const path = require('node:path');
const core = require(path.join(__dirname, '..', '..', 'prototype', 'core.js'));

const BASE = (process.env.SUPABASE_URL || '').replace(/\/+$/, '');
const KEY = process.env.SUPABASE_KEY;
const SERVICE = process.env.SUPABASE_SERVICE_KEY;
const DB = process.env.DATABASE_URL;
const skip = !(BASE && KEY && SERVICE) && '需要 SUPABASE_URL、SUPABASE_KEY、SUPABASE_SERVICE_KEY';
const TZ = 'Asia/Taipei'; // app_config.timezone 的預設值，排程的「今天」以這個時區計算

const tag = Math.random().toString(36).slice(2, 8);
const A = { email: `azhe+${tag}@example.com`, password: 'secret1' }; // 阿哲
const B = { email: `xiaoyu+${tag}@example.com`, password: 'secret2' }; // 小雨

const files = {}; // 測試之間共用的檔案路徑
const uploaded = []; // 測試結束時用 service_role 刪掉

after(async () => {
  for (const u of [A, B]) if (u.sock) u.sock.close();
  if (uploaded.length) await http('DELETE', '/storage/v1/object/media', { key: SERVICE, token: SERVICE, body: { prefixes: uploaded } });
  // 刪掉測試帳號，讓資料庫回到空的狀態（App 的整合測試還要註冊兩個人）
  for (const u of [A, B]) {
    if (u.id) await http('DELETE', `/auth/v1/admin/users/${u.id}`, { key: SERVICE, token: SERVICE });
  }
});

// ------------------------------------------------------------------ HTTP

async function http(method, p, { key = KEY, token, body } = {}) {
  const res = await fetch(BASE + p, {
    method,
    headers: { apikey: key, 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  const text = await res.text();
  let data = text;
  try { data = text ? JSON.parse(text) : null; } catch (_) { /* 不是 JSON */ }
  return { status: res.status, data };
}

const rpc = (user, name, body = {}) => http('POST', `/rest/v1/rpc/${name}`, { token: user && user.token, body });
// 不經過 RPC、直接讀寫資料表（App 不會這樣做，但拿到自己 token 的人都可以），用來確認權限擋得住
const rest = (user, method, p, body) => http(method, `/rest/v1/${p}`, { token: user.token, body });
const denied = (r) => assert.equal(r.data && r.data.code, '42501', `應該被拒絕：${r.status} ${JSON.stringify(r.data)}`);
async function ok(user, name, body) {
  const r = await rpc(user, name, body);
  assert.equal(r.status, 200, `${name} → ${r.status} ${JSON.stringify(r.data)}`);
  return r.data;
}
// 排程函式 App 不能呼叫，用 service_role 手動觸發（pg_cron 也可能剛好先跑過）
async function job(name, body = {}) {
  const r = await http('POST', `/rest/v1/rpc/${name}`, { key: SERVICE, token: SERVICE, body });
  assert.equal(r.status, 200, `${name} → ${r.status} ${JSON.stringify(r.data)}`);
  return r.data;
}

const signUp = (u, displayName) =>
  http('POST', '/auth/v1/signup', { body: { email: u.email, password: u.password, data: { display_name: displayName } } });
const signIn = (email, password) => http('POST', '/auth/v1/token?grant_type=password', { body: { email, password } });

function session(u, data) {
  assert.ok(data.access_token, JSON.stringify(data));
  u.token = data.access_token;
  u.refresh = data.refresh_token;
  u.id = data.user.id;
}

// ------------------------------------------------------------------ Storage（照片、語音；和 App 一樣用登入者的 token）

const jpeg = () => Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xe0]), crypto.randomBytes(2048)]);

async function upload(user, p, bytes, type = 'image/jpeg', { upsert = false } = {}) {
  const res = await fetch(`${BASE}/storage/v1/object/media/${p}`, {
    method: 'POST',
    headers: { apikey: KEY, authorization: `Bearer ${user.token}`, 'content-type': type, ...(upsert ? { 'x-upsert': 'true' } : {}) },
    body: bytes
  });
  const text = await res.text();
  if (res.ok) uploaded.push(p);
  return { status: res.status, text };
}

async function download(user, p) {
  const res = await fetch(`${BASE}/storage/v1/object/authenticated/media/${p}`, {
    headers: { apikey: KEY, authorization: `Bearer ${user.token}` }
  });
  return { status: res.status, bytes: Buffer.from(await res.arrayBuffer()) };
}

async function removeFile(user, p) {
  const res = await fetch(`${BASE}/storage/v1/object/media/${p}`, {
    method: 'DELETE',
    headers: { apikey: KEY, authorization: `Bearer ${user.token}` }
  });
  return { status: res.status, text: await res.text() };
}

// Storage 的錯誤是 HTTP 400，真正的狀態寫在內容裡；讀不到和不存在的回應一樣，猜不出檔案在不在
const uploadDenied = (r) => assert.match(r.text, /row-level security/, `應該被 Storage 的權限擋下：${r.status} ${r.text}`);
const notFound = (r) => {
  assert.equal(r.status, 400);
  assert.match(r.bytes.toString(), /Object not found/);
};

function localToday() {
  const p = Object.fromEntries(new Intl.DateTimeFormat('en-CA', { timeZone: TZ, year: 'numeric', month: '2-digit', day: '2-digit' })
    .formatToParts(new Date()).map((x) => [x.type, x.value]));
  return `${p.year}-${p.month}-${p.day}`;
}

// ------------------------------------------------------------------ Realtime（Phoenix 協定，和 App 的 RealtimeClient 相同）

// 剛 db reset 完 Realtime 會重新啟動，連不上就像 App 一樣過一下再重連
async function connect(user, tries = 20) {
  for (let i = 1; ; i++) {
    try {
      return await connectOnce(user);
    } catch (e) {
      if (i >= tries) throw e;
      await new Promise((r) => setTimeout(r, 2000));
    }
  }
}

function connectOnce(user) {
  return new Promise((resolve, reject) => {
    const url = `${BASE.replace(/^http/, 'ws')}/realtime/v1/websocket?apikey=${encodeURIComponent(KEY)}&vsn=1.0.0`;
    const ws = new WebSocket(url);
    const topic = `realtime:xlk-${user.id}`;
    const msgs = [];
    const waiters = [];
    const seen = new Set();
    let ref = 1;
    let heartbeat = null;
    const send = (m) => ws.send(JSON.stringify(m));

    function push(m) {
      const w = waiters.find((x) => x.pred(m));
      if (w) { waiters.splice(waiters.indexOf(w), 1); w.resolve(m); } else msgs.push(m);
    }
    // 跟 App 一樣：收到通知就 ack，下次連線才不會重送
    function notification(n) {
      if (seen.has(n.id)) return;
      seen.add(n.id);
      push({ type: 'notification', id: n.id, kind: n.kind, title: n.title, body: n.body, data: n.data });
      rpc(user, 'app_ack_notifications', { ids: [n.id] }).catch(() => {});
    }
    const next = (pred, ms = 8000) => new Promise((res, rej) => {
      const found = msgs.find(pred);
      if (found) { msgs.splice(msgs.indexOf(found), 1); return res(found); }
      const w = { pred, resolve: res };
      waiters.push(w);
      setTimeout(() => {
        if (waiters.includes(w)) { waiters.splice(waiters.indexOf(w), 1); rej(new Error('等不到 Realtime 訊息')); }
      }, ms);
    });
    const timer = setTimeout(() => { ws.close(); reject(new Error('Realtime 連線逾時')); }, 15000);

    ws.onopen = () => {
      send({
        topic, event: 'phx_join', ref: String(ref), join_ref: String(ref),
        payload: {
          config: {
            broadcast: { self: false }, presence: { key: '' }, private: false,
            postgres_changes: [
              { event: 'INSERT', schema: 'public', table: 'notification_outbox', filter: `recipient_id=eq.${user.id}` },
              { event: 'INSERT', schema: 'public', table: 'realtime_events', filter: `user_id=eq.${user.id}` }
            ]
          },
          access_token: user.token
        }
      });
      heartbeat = setInterval(() => send({ topic: 'phoenix', event: 'heartbeat', payload: {}, ref: String(++ref) }), 25000);
    };
    ws.onerror = (e) => { clearTimeout(timer); reject(new Error(`Realtime 錯誤 ${e.message || ''}`)); };
    ws.onclose = () => clearInterval(heartbeat);
    ws.onmessage = async (ev) => {
      const m = JSON.parse(ev.data);
      if (m.event === 'phx_reply' && m.ref === '1' && m.payload.status !== 'ok') {
        clearTimeout(timer);
        reject(new Error(`加入頻道失敗 ${JSON.stringify(m.payload)}`));
      } else if (m.event === 'system' && m.payload.extension === 'postgres_changes') {
        clearTimeout(timer);
        if (m.payload.status !== 'ok') { reject(new Error(`訂閱失敗 ${JSON.stringify(m.payload)}`)); return; }
        // 已經在監聽了：補送離線時累積的通知
        try {
          for (const n of await ok(user, 'app_pending_notifications')) notification(n);
        } catch (e) { reject(e); return; }
        resolve({
          ws, next, notified: (kind) => next((x) => x.type === 'notification' && x.kind === kind),
          queued: () => [...msgs], close: () => ws.close()
        });
      } else if (m.event === 'postgres_changes') {
        const { table, record } = m.payload.data;
        if (table === 'notification_outbox') notification(record);
        else if (table === 'realtime_events') {
          push(record.kind === 'locate' ? { type: 'locate', by: record.by_user } : { type: 'changed', table: record.table_name, by: record.by_user });
        }
      }
    };
  });
}

// ------------------------------------------------------------------ 測試

test('健康檢查：還沒登入也能確認網址和金鑰', { skip }, async () => {
  const r = await ok(null, 'app_health');
  assert.equal(r.ok, true);
  assert.match(r.time, /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?\+00:00$/);
  assert.equal(r.schema, 7, 'App 用 schema 判斷有沒有執行 upgrade-v2.sql（7 = 也有一起養的小貓）');
  // 金鑰錯誤的 401 由雲端的 API gateway 檢查；本機的 Supabase CLI 不檢查 apikey，這裡測不到
});

test('註冊人數上限、重複 Email、登入與換發 token', { skip }, async () => {
  const a = await signUp(A, '阿哲');
  assert.equal(a.status, 200, `註冊失敗（資料庫要是空的，請先 supabase db reset）：${JSON.stringify(a.data)}`);
  session(A, a.data);
  const dup = await signUp(A, 'x');
  assert.equal(dup.status, 422);
  assert.equal(dup.data.error_code || dup.data.code, 'user_already_exists');
  const b = await signUp(B, '小雨');
  assert.equal(b.status, 200, JSON.stringify(b.data));
  session(B, b.data);
  // 第三個人：handle_new_user() 擋下來，Supabase Auth 回 500。
  // 新版 Auth 直接轉出資料庫錯誤 {"code":"P0001","message":"registration_closed"}，舊版是「Database error saving new user」
  const third = await signUp({ email: `mia+${tag}@example.com`, password: 'secret3' }, 'Mia');
  assert.equal(third.status, 500);
  assert.match(third.data.message || third.data.msg || '', /^registration_closed$|Database error saving new user/);

  const wrong = await signIn(B.email, 'wrong!');
  assert.equal(wrong.status, 400);
  assert.equal(wrong.data.error_code || wrong.data.error, 'invalid_credentials');
  const login = await signIn(B.email.toUpperCase(), B.password);
  assert.equal(login.status, 200);
  assert.equal(login.data.user.id, B.id);

  const refreshed = await http('POST', '/auth/v1/token?grant_type=refresh_token', { body: { refresh_token: B.refresh } });
  assert.equal(refreshed.status, 200);
  session(B, refreshed.data);

  assert.equal((await rpc(null, 'app_me')).status, 401, '沒登入不能呼叫');
  assert.equal((await rpc({ token: 'not-a-jwt' }, 'app_me')).status, 401);
});

test('配對與 Realtime 連線', { skip }, async () => {
  const invite = await ok(A, 'app_create_invite');
  assert.match(invite.code, /^[A-HJ-NP-Z2-9]{6}$/);
  assert.ok(Date.parse(invite.expiresAt) > Date.now());
  const accepted = await ok(B, 'app_accept_invite', { code: invite.code.toLowerCase(), togetherSince: '2025-05-20' });
  assert.match(accepted.coupleId, /^[0-9a-f-]{36}$/);
  const me = await ok(A, 'app_me');
  assert.deepEqual(Object.keys(me).sort(), ['couple', 'mySharing', 'partner', 'partnerOnline', 'partnerSharing', 'user']);
  assert.equal(me.user.id, A.id);
  assert.equal(me.user.displayName, '阿哲');
  assert.equal(me.user.email, A.email);
  assert.equal(me.partner.displayName, '小雨');
  assert.equal(me.couple.togetherSince, '2025-05-20');
  assert.equal(me.mySharing.shareMood, true);
  assert.equal(me.partnerOnline, false);
  // 解除配對只能用 app_unpair；直接改 ended_at 會讓排程把所有共享資料刪掉
  denied(await rest(A, 'PATCH', `couples?id=eq.${me.couple.id}`, { ended_at: '2000-01-01T00:00:00Z' }));
  assert.equal((await ok(A, 'app_update_couple', { togetherSince: '2025-05-21' })).togetherSince, '2025-05-21');
  A.sock = await connect(A);
  B.sock = await connect(B);
  // 配對成功的通知在 A 連線前就產生了：連線時會補送
  const paired = await A.sock.notified('pairing');
  assert.match(paired.title, /配對成功/);
});

test('分享設定變更會通知對方；手機狀態依分享與暫停顯示', { skip }, async () => {
  const sharing = await ok(A, 'app_update_sharing', { location: true, device: true, expenses: true });
  assert.equal(sharing.shareLocation, true);
  assert.equal(sharing.userId, A.id);
  const n = await B.sock.notified('sharing_changed');
  assert.match(n.title, /阿哲 更新了分享設定/);
  const changed = await B.sock.next((m) => m.type === 'changed' && m.table === 'sharing_settings');
  assert.equal(changed.by, A.id);

  await ok(A, 'app_report_device', {
    batteryLevel: 60, isCharging: false, network: 'wifi', networkLabel: '公司', stepsToday: 3200, deviceModel: 'Google Pixel 8',
    deviceName: '阿哲的 Pixel', carrier: 'x'.repeat(80),
  });
  await ok(A, 'app_report_location', { lat: 25.033, lng: 121.565, accuracyM: 12 });
  let st = await ok(B, 'app_partner_status');
  assert.equal(st.device.batteryLevel, 60);
  assert.equal(st.device.deviceModel, 'Google Pixel 8');
  assert.equal(st.device.deviceName, '阿哲的 Pixel');
  assert.equal(st.device.carrier, 'x'.repeat(60), '電信名稱最多存 60 個字');
  assert.equal(st.location.lat, 25.033);
  assert.equal((await ok(B, 'app_me')).partnerOnline, true, '剛回報過手機狀態就算在線上');

  await ok(A, 'app_report_device', { batteryLevel: 18, isCharging: false, network: 'cellular' });
  assert.match((await B.sock.notified('battery_low')).title, /電量剩 18%/);

  const paused = await ok(A, 'app_pause_sharing', { minutes: 60 });
  assert.ok(Date.parse(paused.pausedUntil) > Date.now());
  st = await ok(B, 'app_partner_status');
  assert.equal(st.location, null);
  assert.equal(st.device, null);
  assert.deepEqual(await ok(A, 'app_pause_sharing', { minutes: 0 }), { pausedUntil: null });
  st = await ok(B, 'app_partner_status');
  assert.equal(st.device.batteryLevel, 18);
});

test('地點標記：被偵測的人會知道，抵達時通知對方', { skip }, async () => {
  const place = await ok(B, 'app_create_place', { name: '阿哲公司', icon: 'work', lat: 25.04, lng: 121.56, radiusM: 200, watch: 'partner' });
  assert.equal(place.watchUser, A.id);
  assert.equal(place.notifyArrive, true);
  assert.match((await A.sock.notified('sharing_changed')).title, /新增了地點「阿哲公司」/);
  const ev = await ok(A, 'app_place_event', { id: place.id, kind: 'arrive' });
  assert.equal(ev.placeId, place.id);
  assert.match((await B.sock.notified('place')).title, /阿哲 已抵達 阿哲公司/);
  const updated = await ok(A, 'app_update_place', { id: place.id, radiusM: 300, watch: 'both' });
  assert.equal(updated.radiusM, 300);
  assert.equal(updated.watchUser, null);
  assert.equal((await ok(B, 'app_places')).length, 1);
  // 先建一個只偵測自己的地點（不通知），再改成偵測對方：被偵測的人一定會知道
  const gym = await ok(A, 'app_create_place', { name: '健身房', lat: 25.05, lng: 121.55, watch: 'me' });
  assert.equal(gym.watchUser, A.id);
  assert.equal((await ok(A, 'app_update_place', { id: gym.id, watch: 'partner' })).watchUser, B.id);
  await B.sock.next((m) => m.type === 'notification' && m.title === '阿哲 修改了地點「健身房」');
  await ok(A, 'app_delete_place', { id: gym.id });
  const bad = await rpc(A, 'app_place_event', { id: place.id, kind: 'stay' });
  assert.equal(bad.status, 400);
  assert.equal(bad.data.message, 'invalid_kind');
  const missing = await rpc(A, 'app_delete_place', { id: '00000000-0000-0000-0000-000000000000' });
  assert.equal(missing.data.message, 'not_found');
});

test('自動記帳去重與猜花費', { skip }, async () => {
  const body = { amount: 135, merchant: '星巴克 信義新天地', category: 'drink', source: 'notification', guessable: true, dedupeKey: 'k1' };
  const e = await ok(A, 'app_create_expense', body);
  assert.equal(e.amount, 135);
  assert.equal(e.dedupeKey, 'k1');
  assert.deepEqual(await ok(A, 'app_create_expense', body), { duplicate: true });
  await ok(A, 'app_create_expense', { amount: 520, merchant: '花店', category: 'gift', visibility: 'private' });
  // 私人的開銷不會讓對方收到「開銷變了」（不然光看時間就猜得到）
  const events = await rest(B, 'GET', 'realtime_events?select=id&table_name=eq.expenses&op=eq.insert');
  assert.equal(events.status, 200);
  assert.equal(events.data.length, 1, JSON.stringify(events.data));

  let list = await ok(B, 'app_partner_expenses');
  assert.equal(list.length, 1);
  assert.equal(list[0].amount, null);
  assert.equal(list[0].myGuess, null);
  const g = await ok(B, 'app_guess', { id: e.id, amount: 130 });
  assert.deepEqual(Object.keys(g).sort(), ['actualAmount', 'errorRatio', 'scorePoints']);
  assert.equal(g.scorePoints, 80);
  assert.equal(g.scorePoints, core.scoreGuess(130, 135).points);
  const again = await rpc(B, 'app_guess', { id: e.id, amount: 140 });
  assert.equal(again.status, 409);
  assert.equal(again.data.code, '23505');
  assert.match((await A.sock.notified('guess')).body, /猜 NT\$130/);
  list = await ok(B, 'app_partner_expenses');
  assert.equal(list[0].amount, 135);
  assert.equal(list[0].myPoints, 80);
  const mine = await ok(A, 'app_expenses');
  assert.equal(mine.find((x) => x.id === e.id).theirGuess, 130);
  assert.equal((await rpc(A, 'app_create_expense', { amount: -5, merchant: 'x' })).data.code, '23514');
});

test('心情、安撫、離線補送', { skip }, async () => {
  await ok(B, 'app_post_mood', { mood: 'sad', note: '被主管念了' });
  assert.match((await A.sock.notified('mood')).body, /要不要安撫一下/);
  assert.ok((await ok(A, 'app_moods', { days: 7 })).some((m) => m.mood === 'sad'));
  const sent = await ok(A, 'app_send_comfort', { kind: 'hug', message: '晚上請你吃好料' });
  assert.equal(sent.receiverId, B.id);
  const c = await B.sock.notified('comfort');
  assert.match(c.title, /阿哲 給了你一個大大的抱抱/);
  assert.equal(c.body, '晚上請你吃好料');
  assert.equal(c.data.comfort_id, sent.id, '通知的 data 保持原本的 key');
  assert.deepEqual(await ok(B, 'app_read_comfort', { id: sent.id }), { ok: true });
  assert.ok((await ok(B, 'app_comforts', { limit: 10 })).find((x) => x.id === sent.id).readAt);
  // 收到的人不能改寫對方說的話；寄的人不能把時間往前改來躲過防洗版
  denied(await rest(B, 'PATCH', `comforts?id=eq.${sent.id}`, { message: '其實是我先道歉的' }));
  denied(await rest(A, 'POST', 'comforts', { couple_id: sent.coupleId, receiver_id: B.id, kind: 'hug', created_at: '2000-01-01T00:00:00Z' }));

  B.sock.close();
  await ok(A, 'app_send_comfort', { kind: 'pat' });
  B.sock = await connect(B);
  await B.sock.next((m) => m.type === 'notification' && /摸摸你的頭/.test(m.title));
  // 打開歷史紀錄：收到的、還沒看過的一次全部標成看過
  assert.ok((await ok(B, 'app_comforts', { limit: 50 })).some((x) => x.receiverId === B.id && !x.readAt));
  assert.deepEqual(await ok(B, 'app_read_comfort', { all: true }), { ok: true });
  assert.ok((await ok(B, 'app_comforts', { limit: 50 })).filter((x) => x.receiverId === B.id).every((x) => x.readAt));
});

test('0.2.3 的心情和安撫：通知用新的文字，「需要抱抱」的心情一樣問要不要安撫', { skip }, async () => {
  await ok(B, 'app_post_mood', { mood: 'lonely', note: '你出差好久' });
  const m = await A.sock.notified('mood');
  assert.equal(m.title, '小雨 現在覺得寂寞');
  assert.equal(m.body, '你出差好久。要不要安撫一下？');
  await ok(A, 'app_send_comfort', { kind: 'meal' });
  assert.equal((await B.sock.notified('comfort')).title, '阿哲 問你吃飯了沒');
  assert.equal((await rpc(B, 'app_post_mood', { mood: 'meh' })).data.code, '23514', '不認得的心情還是不能存');
});

test('0.2.6 再多的安撫：存得進去，通知用新的文字', { skip }, async () => {
  await ok(A, 'app_send_comfort', { kind: 'kitty' });
  assert.equal((await B.sock.notified('comfort')).title, '阿哲 送你一隻可愛的小貓');
  await ok(B, 'app_send_comfort', { kind: 'gethome' });
  assert.equal((await A.sock.notified('comfort')).title, '小雨 問你到家了沒');
  assert.equal((await rpc(A, 'app_send_comfort', { kind: 'slap' })).data.code, '23514', '不認得的安撫還是不能存');
});

test('一起養的小貓：領養、照顧、對方即時看到、規則在資料庫裡', { skip }, async () => {
  assert.equal((await ok(A, 'app_pet')).pet, null);
  const adopted = await ok(A, 'app_adopt_pet', { name: '小橘', coat: 'calico' });
  assert.equal(adopted.pet.name, '小橘');
  assert.equal(adopted.pet.coat, 'calico');
  assert.equal(adopted.pet.xp, 0);
  assert.equal((await B.sock.notified('pet')).title, '阿哲 領養了一隻小貓 🐱');
  await B.sock.next((m) => m.type === 'changed' && m.table === 'pets');
  const again = await rpc(B, 'app_adopt_pet', { name: '小黑', coat: 'black' });
  assert.equal(again.data.message, 'pet_exists');
  assert.equal(again.data.details, '你們已經有一隻貓咪了', 'App 顯示 detail 的中文');

  // 小雨餵罐罐：阿哲收到通知、即時看到；吃飽了再餵就被擋
  const fed = await ok(B, 'app_pet_action', { action: 'feed', item: 'can' });
  assert.equal(fed.pet.fullness, 100);
  assert.equal(fed.pet.streak, 1);
  assert.deepEqual(fed.result, { xp: 0, bonus: false, streakBonus: false, grewTo: null });
  assert.equal(fed.events[0].action, 'feed');
  assert.equal(fed.events[0].userId, B.id);
  assert.equal((await A.sock.notified('pet')).title, '小雨 餵 小橘 吃了罐罐 🥫');
  const full = await rpc(A, 'app_pet_action', { action: 'feed', item: 'kibble' });
  assert.equal(full.data.code, 'P0001');
  assert.equal(full.data.message, 'pet_full');
  // 摸摸三次、陪玩：成長值累積，兩個人看到的一樣
  for (let i = 0; i < 3; i++) await ok(A, 'app_pet_action', { action: 'pet' });
  const played = await ok(B, 'app_pet_action', { action: 'play', item: 'laser' });
  assert.deepEqual(played.today, { feeds: 1, plays: 1, pets: 3, treats: 0 });
  assert.equal(played.result.bonus, true, '今天的任務都做完了');
  assert.equal(played.pet.xp, 6 + 20, '摸摸 3 次 + 任務獎勵（88 分開心陪玩不加成長值）');
  assert.equal((await ok(A, 'app_pet')).pet.xp, 26);

  // 規則在資料庫裡：直接改資料表、或冒充別人的照顧紀錄都不行
  denied(await rest(A, 'PATCH', 'pets?xp=gte.0', { xp: 99999 }));
  denied(await rest(A, 'POST', 'pet_events', { couple_id: '00000000-0000-0000-0000-000000000000', action: 'bonus', xp: 999 }));
  assert.equal((await rest(B, 'GET', 'pets?select=xp')).data[0].xp, 26);
  assert.ok(typeof (await job('app_job_pet', { p_today: localToday(), p_force: true })) === 'number');
});

test('GIPHY 貼圖：心情和安撫附上貼圖 id，通知也帶著；API key 兩個人都讀得到，但不會出現在通知或歷史紀錄', { skip }, async () => {
  // key 一對情侶一把：阿哲設定，小雨收到 couples 的 changed，從 app_me 讀得到
  const key = `xlkApiTest_${tag}_0123456789`;
  assert.equal((await ok(A, 'app_set_giphy_key', { key: ` ${key} ` })).giphyKey, key);
  await A.sock.next((m) => m.type === 'changed' && m.table === 'couples');
  await B.sock.next((m) => m.type === 'changed' && m.table === 'couples');
  const me = await ok(B, 'app_me');
  assert.deepEqual(Object.keys(me).sort(), ['couple', 'mySharing', 'partner', 'partnerOnline', 'partnerSharing', 'user'], 'app_me 最外層的 key 不變');
  assert.equal(me.couple.giphyKey, key);
  assert.equal(me.couple.togetherSince, '2025-05-21', '設定 key 不會動到在一起的日子');
  assert.equal((await rpc(A, 'app_set_giphy_key', { key: 'not a key' })).data.code, '23514');
  assert.equal((await rest(B, 'PATCH', `couples?id=eq.${me.couple.id}`, { giphy_key: 'x' })).data.code, '23514', '直接改欄位也一樣檢查');
  assert.equal((await rpc(null, 'app_set_giphy_key', { key })).status, 401, '沒登入不能呼叫');

  const sticker = 'L4c8BNDK4STL7B5LdV';
  const mood = await ok(B, 'app_post_mood', { mood: 'happy', sticker });
  assert.equal(mood.sticker, sticker);
  const n = await A.sock.next((m) => m.type === 'notification' && m.title === '小雨 現在覺得開心');
  assert.equal(n.body, '附上一張貼圖 ✨');
  assert.deepEqual(n.data, { mood: 'happy', sticker });
  assert.equal((await ok(A, 'app_moods', { days: 1 })).find((m) => m.id === mood.id).sticker, sticker, '對方讀得到貼圖');
  assert.equal((await rpc(B, 'app_post_mood', { mood: 'happy', sticker: '../x' })).data.code, '23514', '貼圖 id 只能是英文字母和數字');

  const sent = await ok(A, 'app_send_comfort', { kind: 'hug', sticker: 'xT4uQulxzV39haRFjG' });
  assert.equal(sent.sticker, 'xT4uQulxzV39haRFjG');
  const c = await B.sock.next((m) => m.type === 'notification' && m.kind === 'comfort' && m.data.comfort_id === sent.id);
  assert.equal(c.body, '傳了一張貼圖給你，點開看看 👀');
  assert.deepEqual(c.data, { comfort_id: sent.id, sticker: sent.sticker }, '通知的 data 保持 comfort_id，再加上貼圖 id');
  assert.equal((await ok(B, 'app_comforts', { limit: 10 })).find((x) => x.id === sent.id).sticker, sent.sticker);
  denied(await rest(B, 'PATCH', `comforts?id=eq.${sent.id}`, { sticker: 'abc' }));

  // key 不會出現在任何通知、歷史紀錄或 Realtime 訊息裡
  const seen = [...A.sock.queued(), ...B.sock.queued(), n, c, ...(await ok(A, 'app_history', { limit: 200 })),
    ...(await rest(A, 'GET', 'notification_outbox?select=title,body,data')).data,
    ...(await rest(B, 'GET', 'notification_outbox?select=title,body,data')).data];
  assert.deepEqual(seen.filter((x) => JSON.stringify(x).includes(key)), []);

  // 空白就清掉，對方也收到 changed
  assert.equal((await ok(B, 'app_set_giphy_key', { key: '  ' })).giphyKey, null);
  await A.sock.next((m) => m.type === 'changed' && m.table === 'couples');
  assert.equal((await ok(A, 'app_me')).couple.giphyKey, null);
});

test('「現在在哪」請對方回報位置', { skip }, async () => {
  assert.deepEqual(await ok(A, 'app_locate_partner'), { sent: true });
  const m = await B.sock.next((x) => x.type === 'locate');
  assert.equal(m.by, A.id);
});

test('約會邀請、回覆與提醒排程', { skip }, async () => {
  const startsAt = new Date(Date.now() + 30 * 60000).toISOString();
  const ev = await ok(A, 'app_create_event', { title: '吃火鍋', startsAt, remindMinutes: [60], locationText: '老四川' });
  assert.deepEqual(ev.remindMinutes, [60]);
  assert.equal(Date.parse(ev.startsAt), Date.parse(startsAt));
  assert.match((await B.sock.notified('event')).title, /阿哲 約你：吃火鍋/);
  const answered = await ok(B, 'app_answer_event', { id: ev.id, status: 'confirmed' });
  assert.equal(answered.status, 'confirmed');
  assert.match((await A.sock.notified('event')).title, /小雨 答應了：吃火鍋/);
  assert.ok((await job('app_job_event_reminders')) <= 1); // 每分鐘的 pg_cron 也可能剛好先送了
  for (const u of [A, B]) {
    const n = await u.sock.next((m) => m.type === 'notification' && m.title === '約會提醒：吃火鍋');
    assert.match(n.body, /^\d{1,2}\/\d{1,2} \d\d:\d\d 開始，準備出門囉$/);
  }
  assert.equal(await job('app_job_event_reminders'), 0);
  assert.equal((await ok(A, 'app_events')).length, 1);
});

test('經期分享與經期前關懷提醒', { skip }, async () => {
  const today = localToday();
  const settings = await ok(B, 'app_save_cycle_settings', { careReminders: true, partnerCanEdit: true });
  assert.equal(settings.partnerCanEdit, true);
  assert.equal(settings.lutealLength, 14);
  await ok(B, 'app_add_period', { startDate: core.addDays(today, -26), endDate: core.addDays(today, -22) });
  let cy = await ok(A, 'app_cycle');
  assert.equal(cy.partner.periods.length, 0, '還沒分享時看不到');
  const quiet = await rest(A, 'GET', 'realtime_events?select=id&table_name=eq.cycle_periods');
  assert.deepEqual(quiet.data, [], '沒分享經期時，對方也不知道什麼時候記了經期');
  await ok(B, 'app_update_sharing', { cycle: true });
  await A.sock.next((m) => m.type === 'changed' && m.table === 'cycle_periods'); // 開啟分享後請對方重新抓
  cy = await ok(A, 'app_cycle');
  assert.equal(cy.partner.periods.length, 1);
  assert.equal(cy.partner.periods[0].startDate, core.addDays(today, -26));
  const log = await ok(B, 'app_save_cycle_log', { date: today, flow: 'light', symptoms: ['經痛'] });
  assert.deepEqual(log.symptoms, ['經痛']);
  assert.equal(log.logDate, today);
  assert.equal((await ok(A, 'app_cycle')).partner.logs, undefined);
  assert.equal((await ok(B, 'app_cycle')).mine.logs.length, 1);

  assert.ok((await job('app_job_cycle_care', { p_today: today })) <= 1);
  assert.match((await A.sock.notified('cycle_care')).title, /小雨 的經期預計 2 天後開始/);
  assert.equal(await job('app_job_cycle_care', { p_today: today }), 0, '同一次經期只提醒一次');
  // 對方允許時可以幫忙記錄
  const p = await ok(A, 'app_add_period', { owner: 'partner', startDate: core.addDays(today, 2) });
  assert.equal(p.userId, B.id);
  assert.equal(p.recordedBy, A.id);
  assert.equal((await ok(A, 'app_update_period', { id: p.id, endDate: core.addDays(today, 6) })).endDate, core.addDays(today, 6));
  // 幫忙記錄的人不能把紀錄「搬」到自己名下（之後就能刪掉）
  denied(await rest(A, 'PATCH', `cycle_periods?id=eq.${p.id}`, { user_id: A.id }));
  const bad = await rpc(B, 'app_add_period', { startDate: 'infinity' });
  assert.equal(bad.data.code, '23514', '日期不能是 infinity，排程才不會壞掉');
});

test('紀念日與每日提醒', { skip }, async () => {
  const today = localToday();
  const ann = await ok(A, 'app_create_anniversary', { title: '小雨生日', date: '1999-' + core.addDays(today, 7).slice(5), kind: 'birthday', remindDays: [0, 7] });
  assert.deepEqual(ann.remindDays, [0, 7]);
  assert.equal((await ok(B, 'app_anniversaries')).length, 1);
  assert.equal((await ok(B, 'app_update_anniversary', { id: ann.id, title: '小雨的生日' })).title, '小雨的生日');
  assert.equal((await rpc(A, 'app_create_anniversary', { title: '永遠', date: 'infinity' })).data.code, '23514');
  const r = await job('app_job_daily', { p_today: today, p_force: true });
  assert.equal(r.date, today);
  assert.ok(r.reminders >= 2);
  assert.match((await B.sock.notified('anniversary')).title, /小雨的生日/);
});

test('個人資料：身高、血型這些資料對方看得到', { skip }, async () => {
  const info = { heightCm: 160, weightKg: 48.5, bloodType: 'O', mbti: 'INFP', likes: '珍奶半糖去冰' };
  const p = await ok(B, 'app_update_profile', { birthday: '1999-03-20', info });
  assert.deepEqual(Object.keys(p).sort(), ['avatarUrl', 'birthday', 'displayName', 'id', 'info']);
  assert.deepEqual(p.info, info, 'info 裡的 key 原封不動');
  await A.sock.next((m) => m.type === 'changed' && m.table === 'profiles');
  const me = await ok(A, 'app_me');
  assert.deepEqual(me.partner.info, info);
  assert.deepEqual(me.user.info, {});
  assert.equal((await rpc(B, 'app_update_profile', { info: ['O'] })).data.code, '23514');
});

test('只有自己看得到的備註：對方從 RPC、資料表、通知、Realtime 都看不到', { skip }, async () => {
  const outbox = async () => (await rest(B, 'GET', 'notification_outbox?select=id')).data.length;
  const before = await outbox();
  const n1 = await ok(A, 'app_save_note', { body: '她喜歡半糖少冰' });
  assert.deepEqual(Object.keys(n1).sort(), ['aboutUser', 'body', 'createdAt', 'id', 'updatedAt']);
  assert.equal(n1.aboutUser, B.id);
  assert.equal((await ok(A, 'app_save_note', { id: n1.id, body: '她喜歡半糖去冰' })).body, '她喜歡半糖去冰');
  const n2 = await ok(A, 'app_save_note', { body: '生日想要的耳環款式' });
  assert.deepEqual((await ok(A, 'app_notes')).map((n) => n.id).sort(), [n1.id, n2.id].sort());

  assert.deepEqual(await ok(B, 'app_notes'), []);
  const direct = await rest(B, 'GET', 'partner_notes?select=id');
  assert.equal(direct.status, 200);
  assert.deepEqual(direct.data, []);
  assert.equal((await rpc(B, 'app_save_note', { id: n1.id, body: '偷改' })).data.message, 'not_found');
  assert.equal((await rpc(B, 'app_delete_note', { id: n1.id })).data.message, 'not_found');
  denied(await rest(B, 'POST', 'partner_notes', { owner_id: A.id, body: '假的' }));
  assert.deepEqual((await rest(B, 'GET', 'realtime_events?select=id&table_name=eq.partner_notes')).data, []);
  assert.equal(await outbox(), before, '寫備註不會產生任何通知');
  // Realtime 依序送達：等「現在在哪」送到時，之前寫的備註如果有通知或 changed 也早就到了
  await ok(A, 'app_locate_partner');
  await B.sock.next((m) => m.type === 'locate');
  const leaked = B.sock.queued().filter((m) => m.table === 'partner_notes' || /半糖|耳環/.test(`${m.title} ${m.body}`));
  assert.deepEqual(leaked, []);
  assert.deepEqual(await ok(A, 'app_delete_note', { id: n2.id }), { ok: true });
});

test('日曆紀錄：兩個人都看得到，照片和語音放在 Storage 的兩人資料夾', { skip }, async () => {
  const couple = (await ok(A, 'app_me')).couple.id;
  const day = localToday();
  const photo = `c/${couple}/${crypto.randomUUID()}.jpg`;
  const bytes = jpeg();
  const up = await upload(A, photo, bytes);
  assert.equal(up.status, 200, up.text);
  files.diaryPhoto = photo;
  const media = [{ path: photo, kind: 'photo', mime: 'image/jpeg', width: 1600, height: 1200 }];
  const e = await ok(A, 'app_save_day_entry', { day, body: '一起去淡水看夕陽', media });
  assert.deepEqual(Object.keys(e).sort(), ['body', 'createdAt', 'day', 'id', 'media', 'updatedAt', 'userId']);
  assert.deepEqual(e.media, media, 'media 裡的 key 原封不動');
  const n = await B.sock.notified('diary');
  assert.equal(n.title, `阿哲 記錄了 ${Number(day.slice(5, 7))}/${Number(day.slice(8))} 的生活`);
  assert.equal(n.body, '一起去淡水看夕陽');
  assert.equal(n.data.day, day);
  await B.sock.next((m) => m.type === 'changed' && m.table === 'day_entries');

  const list = await ok(B, 'app_day_entries', { from: day, to: day });
  assert.deepEqual(list.map((x) => x.id), [e.id]);
  assert.ok((await ok(B, 'app_day_entries')).some((x) => x.id === e.id), '預設區間包含今天');
  const got = await download(B, list[0].media[0].path);
  assert.equal(got.status, 200);
  assert.ok(got.bytes.equals(bytes), '對方下載到同一張照片');

  assert.equal((await rpc(B, 'app_save_day_entry', { id: e.id, day, body: '偷改' })).data.message, 'not_found');
  assert.equal((await rpc(B, 'app_delete_day_entry', { id: e.id })).data.message, 'not_found');
  // 路徑不能指到別的情侶、私人資料夾，也不能用 .. 跳出去
  for (const path of [`c/${crypto.randomUUID()}/x.jpg`, `u/${B.id}/x.jpg`, `u/${A.id}/x.jpg`, `c/${couple}/../../u/${A.id}/x.jpg`]) {
    const bad = await rpc(B, 'app_save_day_entry', { day, body: '看看', media: [{ path, kind: 'photo' }] });
    assert.equal(bad.status, 400, path);
    assert.equal(bad.data.message, 'invalid_media', path);
  }
  const video = await rpc(B, 'app_save_day_entry', { day, body: 'x', media: [{ path: photo, kind: 'video' }] });
  assert.equal(video.data.message, 'invalid_media', '只收照片和語音');

  const voice = `c/${couple}/${crypto.randomUUID()}.m4a`;
  assert.equal((await upload(B, voice, crypto.randomBytes(1024), 'audio/mp4')).status, 200);
  const v = await ok(B, 'app_save_day_entry', { day, media: [{ path: voice, kind: 'voice', durationMs: 12000 }] });
  assert.equal((await A.sock.notified('diary')).body, '🎙️ 錄了一段語音');
  assert.equal((await ok(A, 'app_day_entries', { from: day, to: day })).length, 2);
  assert.equal((await download(A, voice)).status, 200);
  assert.deepEqual(await ok(B, 'app_delete_day_entry', { id: v.id }), { ok: true });
});

test('備忘錄：共用的兩個人都能編輯，私人的連照片對方都看不到', { skip }, async () => {
  const couple = (await ok(A, 'app_me')).couple.id;
  const bMemoEvents = async () => (await rest(B, 'GET', 'realtime_events?select=id&table_name=eq.memos')).data.length;
  const shared = await ok(A, 'app_save_memo', { title: '週末採買清單', body: '牛奶、雞蛋', pinned: true, color: 'yellow' });
  assert.deepEqual(Object.keys(shared).sort(),
    ['body', 'color', 'createdAt', 'id', 'media', 'pinned', 'shared', 'title', 'updatedAt', 'userId']);
  assert.equal(shared.shared, true);
  const n = await B.sock.notified('memo');
  assert.equal(n.title, '阿哲 新增了共同備忘錄');
  assert.equal(n.body, '週末採買清單');
  assert.equal(n.data.id, shared.id);
  await B.sock.next((m) => m.type === 'changed' && m.table === 'memos');

  // 私人備忘錄：照片放在 u/<自己>/，對方收不到通知、changed，也讀不到檔案
  const events = await bMemoEvents();
  const secretPhoto = `u/${A.id}/${crypto.randomUUID()}.jpg`;
  assert.equal((await upload(A, secretPhoto, jpeg())).status, 200);
  const secret = await ok(A, 'app_save_memo', { title: '生日驚喜計畫', body: '偷偷訂餐廳', shared: false, color: 'lavender',
    media: [{ path: secretPhoto, kind: 'photo' }] });
  assert.equal(secret.shared, false);
  await ok(A, 'app_save_memo', { id: secret.id, body: '偷偷訂餐廳，記得蛋糕' });
  assert.equal(await bMemoEvents(), events, '私人備忘錄的變動不會讓對方收到 changed');
  assert.deepEqual((await ok(B, 'app_memos')).map((m) => m.id), [shared.id]);
  assert.deepEqual((await rest(B, 'GET', 'memos?select=id')).data, [{ id: shared.id }]);
  assert.equal((await rpc(B, 'app_save_memo', { id: secret.id, title: '偷看' })).data.message, 'not_found');
  notFound(await download(B, secretPhoto)); // 對方讀不到私人照片
  assert.equal((await download(A, secretPhoto)).status, 200);
  assert.ok(!B.sock.queued().some((m) => /生日驚喜/.test(`${m.title} ${m.body}`)));
  // 對方不能上傳到我的私人資料夾或別的情侶的資料夾；bucket 只收照片和語音
  uploadDenied(await upload(B, `u/${A.id}/${crypto.randomUUID()}.jpg`, jpeg()));
  uploadDenied(await upload(B, `c/${crypto.randomUUID()}/${crypto.randomUUID()}.jpg`, jpeg()));
  uploadDenied(await upload(B, `c/${couple}/sub/${crypto.randomUUID()}.jpg`, jpeg()));
  assert.match((await upload(A, `c/${couple}/${crypto.randomUUID()}.txt`, Buffer.from('hi'), 'text/plain')).text, /mime type/);
  // 照片路徑由資料庫檢查
  assert.equal((await rpc(A, 'app_save_memo', { title: 'x', shared: false, media: [{ path: `c/${couple}/x.jpg`, kind: 'photo' }] })).data.message,
    'invalid_media', '私人備忘錄的照片要在自己的資料夾');
  assert.equal((await rpc(B, 'app_save_memo', { id: shared.id, media: [{ path: secretPhoto, kind: 'photo' }] })).data.message,
    'invalid_media', '共用備忘錄不能指到私人照片');

  // 對方可以改共用的內容，但不能改成私人、不能刪
  const edited = await ok(B, 'app_save_memo', { id: shared.id, body: '牛奶、雞蛋、吐司', shared: false, pinned: false });
  assert.equal(edited.body, '牛奶、雞蛋、吐司');
  assert.equal(edited.shared, true);
  assert.equal(edited.pinned, false);
  assert.equal(edited.userId, A.id);
  await A.sock.next((m) => m.type === 'changed' && m.table === 'memos' && m.by === B.id);
  denied(await rest(B, 'PATCH', `memos?id=eq.${shared.id}`, { shared: false }));
  assert.equal((await rpc(B, 'app_delete_memo', { id: shared.id })).data.message, 'not_found');
  await rest(B, 'DELETE', `memos?id=eq.${shared.id}`);
  const mine = await ok(A, 'app_memos');
  assert.deepEqual(mine.map((m) => m.id).sort(), [shared.id, secret.id].sort());

  // 只有上傳的人能刪檔案；不能覆寫（檔名都是新的 uuid）
  const before = await download(A, files.diaryPhoto);
  assert.notEqual((await removeFile(B, files.diaryPhoto)).status, 200);
  assert.notEqual((await upload(B, files.diaryPhoto, jpeg(), 'image/jpeg', { upsert: true })).status, 200);
  assert.notEqual((await upload(A, files.diaryPhoto, jpeg())).status, 200);
  const after = await download(A, files.diaryPhoto);
  assert.equal(after.status, 200, '對方刪不掉我上傳的照片');
  assert.ok(after.bytes.equals(before.bytes), '也不能覆寫');
  assert.equal((await removeFile(A, secretPhoto)).status, 200);
  notFound(await download(A, secretPhoto));
  assert.deepEqual(await ok(A, 'app_delete_memo', { id: secret.id }), { ok: true });
});

test('歷史紀錄與通知設定', { skip }, async () => {
  const h = await ok(A, 'app_history', { limit: 100 });
  const kinds = new Set(h.map((x) => x.kind));
  for (const k of ['paired', 'mood', 'comfort', 'guess', 'place_arrive', 'event_created', 'event_confirmed', 'battery_low', 'sharing_changed']) {
    assert.ok(kinds.has(k), `history 應包含 ${k}`);
  }
  assert.ok(h.every((x, i) => i === 0 || x.id < h[i - 1].id), '新的在前');
  assert.ok(h.find((x) => x.kind === 'guess').payload.expense_id, 'payload 保持原本的 key');
  const moods = await ok(A, 'app_history', { kinds: ['mood'] });
  assert.ok(moods.length && moods.every((x) => x.kind === 'mood'));
  assert.equal((await ok(A, 'app_history', { limit: 2 })).length, 2);
  assert.deepEqual(await ok(A, 'app_set_notification_prefs', { comfort: false, bogus: true }), { ok: true });
  const prefs = await ok(A, 'app_notification_prefs');
  assert.equal(prefs.comfort, false);
  assert.equal(prefs.mood, true);
  assert.equal(Object.keys(prefs).length, 13);
  assert.equal(prefs.diary, true);
  assert.equal(prefs.memo, true);
  assert.equal(prefs.pet, true);
  await ok(B, 'app_send_comfort', { kind: 'kiss' });
  await assert.rejects(A.sock.notified('comfort'), /等不到/);
});

test('解除配對後立即看不到對方', { skip }, async () => {
  assert.deepEqual(await ok(B, 'app_unpair'), { ok: true });
  const me = await ok(A, 'app_me');
  assert.equal(me.partner, null);
  assert.equal(me.couple, null);
  assert.equal(me.partnerSharing, null);
  const st = await ok(A, 'app_partner_status');
  assert.equal(st.device, null);
  assert.equal((await ok(A, 'app_places')).length, 0);
  const r = await rpc(A, 'app_locate_partner');
  assert.equal(r.status, 400);
  assert.equal(r.data.message, 'not_paired');
  // 日曆、共用備忘錄、兩人資料夾裡的照片都看不到了（沒配對的人讀不到任何一對情侶的檔案）
  assert.deepEqual(await ok(A, 'app_day_entries', { from: '2000-01-01', to: '2200-01-01' }), []);
  assert.ok((await ok(B, 'app_memos')).every((m) => m.userId === B.id), '看不到對方的共用備忘錄');
  notFound(await download(A, files.diaryPhoto));
  notFound(await download(B, files.diaryPhoto));
  uploadDenied(await upload(A, files.diaryPhoto.replace(/[^/]+$/, `${crypto.randomUUID()}.jpg`), jpeg())); // 原本的共用資料夾
  assert.equal((await rpc(A, 'app_save_day_entry', { day: localToday(), body: '一個人' })).data.message, 'not_paired');
  // 自己的私人資料夾照常可以用
  const mine = `u/${A.id}/${crypto.randomUUID()}.jpg`;
  assert.equal((await upload(A, mine, jpeg())).status, 200);
  assert.equal((await download(A, mine)).status, 200);
  assert.equal((await ok(A, 'app_save_memo', { title: '一個人的清單', shared: true, media: [{ path: mine, kind: 'photo' }] })).shared, false,
    '沒配對時備忘錄只能是私人的');
});

test('改密碼：先用舊密碼登入確認，再更新', { skip }, async () => {
  assert.equal((await signIn(A.email, A.password)).status, 200);
  const r = await http('PUT', '/auth/v1/user', { token: A.token, body: { password: 'secret9' } });
  assert.equal(r.status, 200, JSON.stringify(r.data));
  assert.equal((await signIn(A.email, A.password)).status, 400);
  assert.equal((await signIn(A.email, 'secret9')).status, 200);
});

test('說明文件的「忘記密碼」SQL 可以重設密碼', { skip: skip || (!DB && '沒有 DATABASE_URL') }, async () => {
  const sql = `update auth.users set encrypted_password = extensions.crypt('newpass123', extensions.gen_salt('bf')) where email = '${B.email}';`;
  assert.match(execFileSync('psql', [DB, '-X', '-v', 'ON_ERROR_STOP=1', '-c', sql], { encoding: 'utf8' }), /UPDATE 1/);
  assert.equal((await signIn(B.email, B.password)).status, 400);
  const r = await signIn(B.email, 'newpass123');
  assert.equal(r.status, 200);
  assert.equal(r.data.user.id, B.id);
});

test('刪除對方的帳號後，留下來的人自動解除配對，可以重新配對', { skip }, async () => {
  const invite = await ok(A, 'app_create_invite');
  await ok(B, 'app_accept_invite', { code: invite.code });
  assert.ok((await ok(A, 'app_me')).partner);
  // 和在 Supabase 後台 › Authentication › Users 刪除帳號相同
  const del = await http('DELETE', `/auth/v1/admin/users/${B.id}`, { key: SERVICE, token: SERVICE });
  assert.equal(del.status, 200, JSON.stringify(del.data));
  B.id = null; // 測試結束時不用再刪
  const me = await ok(A, 'app_me');
  assert.equal(me.couple, null);
  assert.equal(me.partner, null);
  await A.sock.next((m) => m.type === 'notification' && m.title === '已解除配對' && /帳號已經刪除/.test(m.body));
  assert.match((await ok(A, 'app_create_invite')).code, /^[A-HJ-NP-Z2-9]{6}$/);
});
