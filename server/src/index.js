'use strict';
// 小倆口自架伺服器入口：HTTP API + WebSocket + 排程
const http = require('http');
const os = require('os');
const { createDb } = require('./db');
const { verifyToken } = require('./auth');
const { Router, HttpError, fromDbError, readJson, send } = require('./http');
const { createHub, attachWebSocket, startListener } = require('./realtime');
const { createJobs } = require('./jobs');
const registerRoutes = require('./routes');
const loadConfig = require('./config');

/** 這台電腦的區網 / Tailscale 位址，讓使用者知道 App 要填哪個網址 */
function addresses(port) {
  const out = [];
  for (const [name, list] of Object.entries(os.networkInterfaces())) {
    for (const a of list || []) {
      if (a.family !== 'IPv4' || a.internal) continue;
      const tailscale = a.address.startsWith('100.') && Number(a.address.split('.')[1]) >= 64 && Number(a.address.split('.')[1]) < 128;
      out.push({ name, url: `http://${a.address}:${port}`, tailscale });
    }
  }
  return out;
}

const DOCKER_HINT = '伺服器在 Docker 裡，看不到這台電腦的網路位址。Windows 請在命令提示字元輸入 ipconfig，找「IPv4 位址」（例如 192.168.1.23）；'
  + '有裝 Tailscale 的話，用 Tailscale 顯示的 100.x.x.x 位址。App 填 http://那個位址:埠號';

function landingPage(cfg, users) {
  const list = process.env.IN_DOCKER
    ? `<li>${DOCKER_HINT.replace('埠號', String(cfg.port === 8080 ? 8080 : cfg.port))}</li>`
    : addresses(cfg.port).map((a) => `<li><code>${a.url}</code> ${a.tailscale ? '<b>Tailscale：在外面也能連</b>' : '（同一個 Wi-Fi 才能連）'}</li>`).join('');
  return `<!doctype html><html lang="zh-Hant"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>小倆口伺服器</title>
<style>body{font-family:system-ui,"Noto Sans TC",sans-serif;background:#FFF5F7;color:#4A2B39;max-width:640px;margin:40px auto;padding:0 20px;line-height:1.7}
h1{color:#F0607F}code{background:#fff;padding:2px 8px;border-radius:8px;border:1px solid #F8D0DA}.card{background:#fff;border-radius:20px;padding:16px 20px;box-shadow:0 4px 20px #f0607f22}</style>
<h1>💞 小倆口伺服器運作中</h1>
<div class="card"><p>在 App 的「伺服器網址」填入下面其中一個：</p><ul>${list || '<li>找不到網路介面</li>'}</ul>
<p>已註冊帳號：${users} / ${cfg.maxUsers}</p></div>
<p>資料庫就在這台電腦上。記得定期備份，詳見 README 的「備份」一節。</p></html>`;
}

async function createApp(cfg = loadConfig(), { log = console.log, startJobs = true } = {}) {
  const db = createDb(cfg.databaseUrl);
  await db.migrate(log);
  const hub = createHub();
  const jobs = createJobs({ db, cfg, log });
  const router = new Router();
  registerRoutes(router, { db, cfg, hub, jobs });

  async function authenticate(req) {
    const token = (req.headers.authorization || '').replace(/^Bearer\s+/i, '');
    const payload = verifyToken(cfg.secret, token);
    if (!payload) return null;
    const ok = await db.asOwner(async (c) => (await c.query(
      'select 1 from auth.users where id = $1 and tokens_valid_after < to_timestamp($2)', [payload.sub, payload.iat])).rowCount);
    return ok ? payload.sub : null;
  }

  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://localhost');
    try {
      if (req.method === 'GET' && url.pathname === '/') {
        const users = await db.asOwner(async (c) => (await c.query('select count(*)::int as n from auth.users')).rows[0].n);
        return send(res, 200, landingPage(cfg, users), 'text/html; charset=utf-8');
      }
      const m = router.match(req.method, url.pathname);
      if (!m) throw new HttpError(404, 'not_found', '沒有這個 API');
      if (m.methodNotAllowed) throw new HttpError(405, 'method_not_allowed', '不支援這個方法');
      let uid = null;
      if (m.route.auth) {
        uid = await authenticate(req);
        if (!uid) throw new HttpError(401, 'unauthorized', '請重新登入');
      }
      const body = ['POST', 'PUT', 'PATCH'].includes(req.method) ? await readJson(req) : {};
      const result = await m.route.handler({ req, uid, body, params: m.params, query: url.searchParams });
      send(res, 200, result === undefined ? { ok: true } : result);
    } catch (e) {
      const err = fromDbError(e);
      if (err) return send(res, err.status, { error: err.code, message: err.message });
      log('[http] 未預期的錯誤', req.method, url.pathname, e);
      send(res, 500, { error: 'server_error', message: '伺服器出錯了，請稍後再試' });
    }
  });

  const ws = attachWebSocket(server, { db, cfg, hub, log });
  const listener = startListener({ db, cfg, hub, log });
  await listener.ready;
  if (startJobs) jobs.start();

  return {
    server,
    db,
    jobs,
    hub,
    listen(port = cfg.port) {
      return new Promise((resolve, reject) => {
        server.once('error', reject);
        server.listen(port, '0.0.0.0', () => { server.off('error', reject); resolve(server.address().port); });
      });
    },
    async close() {
      jobs.stop();
      ws.close();
      hub.closeAll();
      await listener.close();
      await new Promise((resolve) => server.close(resolve));
      await db.close();
    }
  };
}

/** 啟動伺服器並印出連線說明；npm start 與不用 Docker 的啟動程式都走這裡 */
async function main(cfg = loadConfig()) {
  let app;
  try {
    app = await createApp(cfg);
  } catch (e) {
    console.error(`啟動失敗：${e.message}`);
    if (/ECONNREFUSED|connect/i.test(e.message)) console.error('連不到資料庫：請確認 PostgreSQL 有在執行。');
    process.exit(1);
  }
  let port;
  try {
    port = await app.listen(cfg.port);
  } catch (e) {
    await app.close().catch(() => {});
    if (e.code === 'EADDRINUSE') console.error(`埠號 ${cfg.port} 已經被使用，可能小倆口已經在執行了，或在 .env 改用其他 PORT。`);
    else console.error(`啟動失敗：${e.message}`);
    process.exit(1);
  }
  console.log(`\n💞 小倆口伺服器已啟動：http://localhost:${port}`);
  if (process.env.IN_DOCKER) console.log(`  ${DOCKER_HINT}`);
  else {
    const list = addresses(port);
    if (list.length) console.log('  在 App 的「伺服器網址」填入：');
    for (const a of list) console.log(`    ${a.url}${a.tailscale ? '　← Tailscale（在外面也能連）' : '　← 同一個 Wi-Fi'}`);
  }
  console.log('  關掉這個視窗，伺服器就會停止。\n');
  const stop = () => app.close().then(() => process.exit(0));
  process.on('SIGINT', stop);
  process.on('SIGTERM', stop);
  return { app, port };
}

if (require.main === module) main();

module.exports = { createApp, addresses, main };
