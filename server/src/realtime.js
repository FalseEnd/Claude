'use strict';
// WebSocket 連線管理 + PostgreSQL LISTEN：
//   xlk_outbox  → 把通知送給收件人的 App，App 回 ack 後標記已送達；離線時等下次連線補送
//   xlk_changed → 告訴這對情侶的 App「哪張表變了」，App 重新抓對應的資料
const { Client } = require('pg');
const { WebSocketServer } = require('ws');
const { verifyToken } = require('./auth');
const { camel } = require('./http');

function createHub() {
  const sockets = new Map(); // userId -> Set<ws>
  return {
    add(uid, ws) {
      if (!sockets.has(uid)) sockets.set(uid, new Set());
      sockets.get(uid).add(ws);
    },
    remove(uid, ws) {
      const set = sockets.get(uid);
      if (!set) return;
      set.delete(ws);
      if (!set.size) sockets.delete(uid);
    },
    isOnline: (uid) => sockets.has(uid),
    sendToUser(uid, msg) {
      const set = sockets.get(uid);
      if (!set) return 0;
      const data = JSON.stringify(msg);
      let n = 0;
      for (const ws of set) if (ws.readyState === 1) { ws.send(data); n++; }
      return n;
    },
    closeAll() { for (const set of sockets.values()) for (const ws of set) ws.terminate(); sockets.clear(); }
  };
}

function outboxMessage(row) {
  const n = camel(row);
  return { type: 'notification', id: n.id, kind: n.kind, title: n.title, body: n.body, data: n.data, createdAt: n.createdAt };
}

function attachWebSocket(server, { db, cfg, hub, log }) {
  const wss = new WebSocketServer({ noServer: true });

  async function authenticate(req) {
    const url = new URL(req.url, 'http://x');
    const token = url.searchParams.get('token') || (req.headers.authorization || '').replace(/^Bearer /, '');
    const payload = verifyToken(cfg.secret, token);
    if (!payload) return null;
    const ok = await db.asOwner(async (c) => (await c.query(
      'select 1 from auth.users where id = $1 and tokens_valid_after < to_timestamp($2)', [payload.sub, payload.iat])).rowCount);
    return ok ? payload.sub : null;
  }

  async function sendPending(uid, ws) {
    const pending = await db.asOwner(async (c) => (await c.query(`
      select * from notification_outbox
      where recipient_id = $1 and sent_at is null and created_at > now() - interval '3 days'
      order by id`, [uid])).rows);
    for (const row of pending) ws.send(JSON.stringify(outboxMessage(row)));
  }

  server.on('upgrade', async (req, socket, head) => {
    if (!req.url.startsWith('/ws')) { socket.destroy(); return; }
    let uid = null;
    try { uid = await authenticate(req); } catch (e) { log('[ws] 驗證失敗', e.message); }
    if (!uid) { socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n'); socket.destroy(); return; }
    wss.handleUpgrade(req, socket, head, (ws) => {
      ws.isAlive = true;
      hub.add(uid, ws);
      ws.on('pong', () => { ws.isAlive = true; });
      ws.on('close', () => hub.remove(uid, ws));
      ws.on('error', () => hub.remove(uid, ws));
      ws.on('message', async (raw) => {
        let msg;
        try { msg = JSON.parse(raw.toString()); } catch (_) { return; }
        if (msg.type === 'ack' && Number.isInteger(msg.id)) {
          await db.asOwner((c) => c.query('update notification_outbox set sent_at = now() where id = $1 and recipient_id = $2', [msg.id, uid]))
            .catch((e) => log('[ws] ack 失敗', e.message));
        } else if (msg.type === 'ping') {
          ws.send(JSON.stringify({ type: 'pong', time: new Date() }));
        }
      });
      ws.send(JSON.stringify({ type: 'hello', userId: uid }));
      sendPending(uid, ws).catch((e) => log('[ws] 補送通知失敗', e.message));
    });
  });

  // 每 30 秒確認連線還活著，斷線的清掉
  const heartbeat = setInterval(() => {
    for (const ws of wss.clients) {
      if (!ws.isAlive) { ws.terminate(); continue; }
      ws.isAlive = false;
      try { ws.ping(); } catch (_) { /* ignore */ }
    }
  }, 30000);

  return { wss, close: () => { clearInterval(heartbeat); wss.close(); } };
}

/** 監聽資料庫通知；連線中斷會自動重連 */
function startListener({ db, cfg, hub, log }) {
  let client = null;
  let stopped = false;
  let retry = null;

  async function onOutbox(id) {
    const row = await db.asOwner(async (c) => (await c.query('select * from notification_outbox where id = $1', [id])).rows[0]);
    if (row) hub.sendToUser(row.recipient_id, outboxMessage(row));
  }
  async function onChanged(payload) {
    const p = JSON.parse(payload);
    let users = p.user ? [p.user] : [];
    if (p.couple) {
      users = await db.asOwner(async (c) => (await c.query('select user_id from couple_members where couple_id = $1', [p.couple])).rows.map((r) => r.user_id));
      if (p.user && !users.includes(p.user)) users.push(p.user);
    }
    for (const u of users) hub.sendToUser(u, { type: 'changed', table: p.table, op: p.op, by: p.user });
  }

  async function connect() {
    client = new Client({ connectionString: cfg.databaseUrl });
    client.on('error', (e) => { log('[listen] 連線錯誤', e.message); reconnect(); });
    client.on('end', () => reconnect());
    client.on('notification', (n) => {
      const work = n.channel === 'xlk_outbox' ? onOutbox(Number(n.payload)) : onChanged(n.payload);
      work.catch((e) => log('[listen] 處理失敗', e.message));
    });
    await client.connect();
    await client.query('listen xlk_outbox');
    await client.query('listen xlk_changed');
  }
  function reconnect() {
    if (stopped || retry) return;
    try { client && client.removeAllListeners(); client && client.end().catch(() => {}); } catch (_) { /* ignore */ }
    retry = setTimeout(() => { retry = null; connect().catch((e) => { log('[listen] 重連失敗', e.message); reconnect(); }); }, 3000);
  }

  return {
    ready: connect(),
    async close() {
      stopped = true;
      clearTimeout(retry);
      if (client) { client.removeAllListeners(); await client.end().catch(() => {}); }
    }
  };
}

module.exports = { createHub, attachWebSocket, startListener };
