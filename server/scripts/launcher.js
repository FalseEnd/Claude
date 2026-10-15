#!/usr/bin/env node
'use strict';
// 不用 Docker 的啟動程式：start-windows.bat、start-mac.command、start-linux.sh 都是呼叫這支。
//   第一次執行：自動下載套件、建立小倆口專用的 PostgreSQL 帳號與資料庫、寫入 server/.env
//   之後執行：檢查資料庫、啟動伺服器、每天自動備份
// 其他功能：--backup、--restore 檔案、--reset-password 帳號、--install-autostart、--remove-autostart（完整說明：--help）
// 這個檔案刻意不用 ?. 與 ??，太舊的 Node 也能跑到版本檢查、看到中文提示。

const fs = require('fs');
const os = require('os');
const net = require('net');
const path = require('path');
const crypto = require('crypto');
const { spawn, spawnSync } = require('child_process');

const SERVER_DIR = path.join(__dirname, '..');
const ROOT = path.join(SERVER_DIR, '..');
const ENV_FILE = process.env.XLK_ENV_FILE || path.join(SERVER_DIR, '.env');
const DB_NAME = 'xiaoliangkou';
const DB_USER = 'xlk';
const MIN_NODE = 20;
const MIN_PG = 130000;
const PG_PORTS = [5432, 5433, 5434];
const IS_WIN = process.platform === 'win32';
const IS_MAC = process.platform === 'darwin';
const START_FILE = IS_WIN ? 'start-windows.bat' : IS_MAC ? 'start-mac.command' : 'start-linux.sh';

/** 要顯示給使用者看的錯誤：只印訊息，不印程式堆疊 */
class Fatal extends Error {}

const say = (s) => console.log(s === undefined ? '' : s);

// ---------------------------------------------------------------- 參數

const FLAGS = {
  '--setup': 'setup', '--no-browser': 'noBrowser', '--autostart': 'autostart', '--yes': 'yes',
  '--backup': 'backup', '--install-autostart': 'installAutostart', '--remove-autostart': 'removeAutostart',
  '--help': 'help', '-h': 'help'
};
const OPTIONS = {
  '--port': 'port', '--pg-host': 'pgHost', '--pg-port': 'pgPort', '--pg-user': 'pgUser',
  '--pg-password': 'pgPassword', '--restore': 'restore', '--reset-password': 'resetPassword'
};
const MAY_BE_EMPTY = ['--pg-password', '--restore', '--reset-password'];

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    let a = argv[i];
    let v;
    const eq = a.indexOf('=');
    if (a.startsWith('--') && eq > 0) { v = a.slice(eq + 1); a = a.slice(0, eq); }
    if (FLAGS[a] && v === undefined) out[FLAGS[a]] = true;
    else if (OPTIONS[a]) {
      if (v === undefined) v = argv[++i];
      if (v === undefined || (v === '' && MAY_BE_EMPTY.indexOf(a) < 0)) throw new Fatal(`${a} 後面要接一個值`);
      out[OPTIONS[a]] = v;
    } else throw new Fatal(`看不懂的參數：${argv[i]}（用 --help 看說明）`);
  }
  for (const k of ['port', 'pgPort']) {
    if (out[k] === undefined) continue;
    const n = Number(out[k]);
    if (!Number.isInteger(n) || n < 1 || n > 65535) throw new Fatal(`埠號要是 1 到 65535 的數字：${out[k]}`);
    out[k] = n;
  }
  if (out.autostart) out.noBrowser = true;
  return out;
}

const HELP = `小倆口伺服器啟動程式

直接執行（或雙擊 ${START_FILE}）：第一次會設定資料庫，之後直接啟動伺服器。

  --setup               重新設定資料庫帳號（重灌 PostgreSQL 或改密碼之後用）
  --port 埠號           伺服器用的埠號（預設 8080；搭配 --setup 會記住）
  --no-browser          第一次設定完不要自動打開瀏覽器
  --backup              備份資料庫到 backups 資料夾
  --restore 檔案        用備份檔取代目前的資料（伺服器要先關掉）
  --reset-password 帳號 忘記 App 密碼時，產生一組臨時密碼
  --install-autostart   登入電腦時自動啟動伺服器（再執行一次可以取消）
  --remove-autostart    取消自動啟動
  --pg-host / --pg-port / --pg-user / --pg-password
                        PostgreSQL 管理員連線資訊（預設 localhost、5432、postgres）
  --yes                 還原時不再確認`;

// ---------------------------------------------------------------- 小工具（有單元測試）

/** 更新 .env 裡的幾個值，保留其他設定與註解 */
function upsertEnv(text, values) {
  const lines = text ? text.replace(/\r?\n$/, '').split(/\r?\n/) : [];
  const seen = new Set();
  const out = lines.map((line) => {
    const m = line.match(/^\s*([A-Z0-9_]+)\s*=/);
    if (m && Object.prototype.hasOwnProperty.call(values, m[1])) {
      if (seen.has(m[1])) return null;
      seen.add(m[1]);
      return `${m[1]}=${values[m[1]]}`;
    }
    return line;
  }).filter((l) => l !== null);
  for (const k of Object.keys(values)) if (!seen.has(k)) out.push(`${k}=${values[k]}`);
  return out.join('\n') + '\n';
}

function buildDatabaseUrl({ user, password, host, port, database }) {
  const h = host.includes(':') && !host.startsWith('[') ? `[${host}]` : host;
  return `postgresql://${encodeURIComponent(user)}:${encodeURIComponent(password)}@${h}:${port}/${encodeURIComponent(database)}`;
}

/** DATABASE_URL 轉成 pg_dump / psql 看得懂的環境變數（密碼不會出現在指令列） */
function pgEnv(url) {
  const u = new URL(url);
  const host = u.searchParams.get('host') || u.hostname.replace(/^\[(.*)\]$/, '$1') || 'localhost';
  return {
    PGHOST: host,
    PGPORT: u.port || '5432',
    PGUSER: decodeURIComponent(u.username),
    PGPASSWORD: decodeURIComponent(u.password),
    PGDATABASE: decodeURIComponent(u.pathname.slice(1))
  };
}

const pad = (n) => String(n).padStart(2, '0');
function backupFileName(d, auto = false) {
  const stamp = `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}-${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`;
  return `xiaoliangkou-${auto ? 'auto-' : ''}${stamp}.sql`;
}

/** 依檔名（時間）排序，回傳超過保留份數、該刪掉的自動備份 */
function autoBackupsToPrune(names, keep) {
  const auto = names.filter((n) => /^xiaoliangkou-auto-\d{8}-\d{6}\.sql$/.test(n)).sort();
  return auto.slice(0, Math.max(0, auto.length - keep));
}

function pgVersionText(num) {
  const n = Number(num);
  return n >= 100000 ? `${Math.floor(n / 10000)}.${n % 10000}` : `${Math.floor(n / 10000)}.${Math.floor(n / 100) % 100}`;
}

/** 連線錯誤分類：down 沒在執行、password 要密碼或密碼錯、nodb 資料庫不存在 */
function errKind(e) {
  if (!e) return null;
  const codes = [e.code].concat((e.errors || []).map((x) => x.code));
  if (codes.some((c) => ['ECONNREFUSED', 'ENOENT', 'EHOSTUNREACH', 'ETIMEDOUT', 'ECONNRESET', 'EADDRNOTAVAIL'].includes(c))) return 'down';
  if (/timeout/i.test(e.message)) return 'down';
  if (e.code === '28P01' || /password/i.test(e.message)) return 'password';
  if (e.code === '28000') return 'denied';
  if (e.code === '3D000') return 'nodb';
  return 'other';
}

const escapeXml = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
const systemdQuote = (s) => `"${String(s).replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/%/g, '%%').replace(/\$/g, '$$$$')}"`;
const psQuote = (s) => `'${String(s).replace(/['\u2018\u2019\u201a\u201b]/g, '$&$&')}'`;

// ---------------------------------------------------------------- 輸入

/** 問問題：一般終端機逐字讀（密碼顯示成 *），被導向的輸入（測試、腳本）逐行讀 */
function createPrompter(input = process.stdin, output = process.stdout) {
  const lines = [];
  const waiters = [];
  let ended = false;
  let attached = false;
  let partial = '';

  function attachPipe() {
    if (attached) return;
    attached = true;
    input.setEncoding('utf8');
    input.on('data', (chunk) => {
      const parts = (partial + chunk).split(/\r?\n/);
      partial = parts.pop();
      for (const p of parts) lines.push(p);
      flush();
    });
    input.on('end', () => {
      if (partial) lines.push(partial);
      partial = '';
      ended = true;
      flush();
    });
    input.resume();
  }
  function flush() {
    while (waiters.length && (lines.length || ended)) waiters.shift()(lines.length ? lines.shift() : null);
  }

  function askTty(hidden) {
    return new Promise((resolve) => {
      let value = '';
      input.setEncoding('utf8');
      input.setRawMode(true);
      input.resume();
      const onData = (s) => {
        if (s.startsWith('\u001b')) return; // 方向鍵等控制序列
        for (const ch of s) {
          if (ch === '\r' || ch === '\n') return done();
          if (ch === '\u0003') { input.setRawMode(false); output.write('\n'); process.exit(130); }
          if (ch === '\u007f' || ch === '\b') {
            if (value) { value = Array.from(value).slice(0, -1).join(''); output.write('\b \b'); }
            continue;
          }
          if (ch < ' ') continue;
          value += ch;
          output.write(hidden ? '*' : ch);
        }
      };
      const done = () => {
        input.off('data', onData);
        input.setRawMode(false);
        input.pause();
        output.write('\n');
        resolve(value);
      };
      input.on('data', onData);
    });
  }

  return {
    interactive: !!input.isTTY,
    /** 回傳輸入的文字；沒有輸入可讀（例如開機自動啟動）時回傳 null */
    async ask(question, { hidden = false } = {}) {
      output.write(question);
      if (input.isTTY) return askTty(hidden);
      attachPipe();
      const line = await new Promise((resolve) => { waiters.push(resolve); flush(); });
      output.write('\n');
      return line;
    },
    close() {
      if (attached) { input.removeAllListeners('data'); input.removeAllListeners('end'); }
      input.pause();
    }
  };
}

let prompter;
const getPrompter = () => prompter || (prompter = createPrompter());

async function confirm(question) {
  const a = await getPrompter().ask(`${question}（y/N）：`);
  return /^\s*(y|yes|是|好)\s*$/i.test(a || '');
}

// ---------------------------------------------------------------- 環境

function checkNode() {
  const major = Number(process.versions.node.split('.')[0]);
  if (major < MIN_NODE) {
    openUrl(path.join(__dirname, 'need-node.html'));
    throw new Fatal(`你的 Node.js 是 ${process.versions.node}，小倆口需要 ${MIN_NODE} 以上。請到 https://nodejs.org 安裝 LTS 版本後再試一次。`);
  }
}

/** 用預設瀏覽器打開網址或檔案；失敗就算了（例如沒有桌面環境） */
function openUrl(target) {
  try {
    const [cmd, args] = IS_WIN ? ['explorer.exe', [target]] : [IS_MAC ? 'open' : 'xdg-open', [target]];
    const p = spawn(cmd, args, { stdio: 'ignore', detached: true, windowsHide: true });
    p.on('error', () => {});
    p.unref();
  } catch (_) { /* 沒有瀏覽器 */ }
}

function npmCommand() {
  const dir = path.dirname(process.execPath);
  for (const p of [path.join(dir, 'node_modules', 'npm', 'bin', 'npm-cli.js'), path.join(dir, '..', 'lib', 'node_modules', 'npm', 'bin', 'npm-cli.js')]) {
    if (fs.existsSync(p)) return { cmd: process.execPath, pre: [p], shell: false };
  }
  return { cmd: IS_WIN ? 'npm.cmd' : 'npm', pre: [], shell: IS_WIN };
}

/** 第一次執行或更新版本後，自動安裝伺服器需要的套件（pg、ws） */
function ensureDeps() {
  const lock = path.join(SERVER_DIR, 'package-lock.json');
  const stamp = path.join(SERVER_DIR, 'node_modules', '.xlk-installed');
  const hash = crypto.createHash('sha1').update(fs.readFileSync(lock)).digest('hex');
  let resolvable = true;
  try { for (const m of ['pg', 'ws']) require.resolve(m, { paths: [SERVER_DIR] }); } catch (_) { resolvable = false; }
  let stamped = null;
  try { stamped = fs.readFileSync(stamp, 'utf8').trim(); } catch (_) { /* 沒有紀錄 */ }
  if (resolvable && (stamped === hash || stamped === null)) {
    if (stamped === null) try { fs.writeFileSync(stamp, hash); } catch (_) { /* 唯讀也沒關係 */ }
    return;
  }
  say(resolvable ? '小倆口更新了，正在更新伺服器套件……' : '第一次啟動，正在下載伺服器需要的套件（大約 1 分鐘）……');
  const npm = npmCommand();
  const r = spawnSync(npm.cmd, npm.pre.concat(['ci', '--omit=dev', '--no-audit', '--no-fund', '--loglevel=error']),
    { cwd: SERVER_DIR, stdio: 'inherit', shell: npm.shell });
  if (r.status !== 0) throw new Fatal('下載套件失敗。請確認電腦有連上網路，再試一次。');
  fs.writeFileSync(stamp, hash);
}

function readEnvFile() {
  const { parseEnv } = require('../src/config');
  try { return parseEnv(fs.readFileSync(ENV_FILE, 'utf8')); } catch (_) { return {}; }
}

/** 備份資料夾：.env 的 BACKUP_DIR（可以設成雲端硬碟資料夾），預設是小倆口資料夾裡的 backups */
function backupDir() {
  const dir = process.env.XLK_BACKUP_DIR || readEnvFile().BACKUP_DIR;
  return dir ? path.resolve(ROOT, dir) : path.join(ROOT, 'backups');
}

function writeEnvFile(values) {
  let text = '';
  try { text = fs.readFileSync(ENV_FILE, 'utf8'); } catch (_) {
    text = '# 小倆口伺服器設定（啟動程式自動產生）\n'
      + `# 重新設定資料庫：執行 ${START_FILE} --setup\n`
      + '# PORT 是伺服器埠號，MAX_USERS 是最多幾個帳號\n';
  }
  fs.writeFileSync(ENV_FILE, upsertEnv(text, values), { mode: 0o600 });
}

// ---------------------------------------------------------------- PostgreSQL

function subdirs(dir) {
  try { return fs.readdirSync(dir, { withFileTypes: true }).filter((d) => d.isDirectory()).map((d) => path.join(dir, d.name)); } catch (_) { return []; }
}

function pgBinDirs() {
  const dirs = (process.env.PATH || '').split(path.delimiter).filter(Boolean);
  if (IS_WIN) {
    for (const pf of [process.env.ProgramFiles, process.env.ProgramW6432, 'C:\\Program Files']) {
      if (pf) for (const v of subdirs(path.join(pf, 'PostgreSQL'))) dirs.push(path.join(v, 'bin'));
    }
  } else if (IS_MAC) {
    for (const v of subdirs('/Applications/Postgres.app/Contents/Versions')) dirs.push(path.join(v, 'bin'));
    for (const base of ['/opt/homebrew/opt', '/usr/local/opt']) {
      for (const v of subdirs(base)) if (/^postgresql/.test(path.basename(v))) dirs.push(path.join(v, 'bin'));
    }
    dirs.push('/opt/homebrew/bin', '/usr/local/bin');
  } else {
    for (const v of subdirs('/usr/lib/postgresql')) dirs.push(path.join(v, 'bin'));
    for (const v of subdirs('/usr')) if (/^pgsql-\d+/.test(path.basename(v))) dirs.push(path.join(v, 'bin'));
  }
  return Array.from(new Set(dirs));
}

/** 找 PostgreSQL 的工具（pg_dump、psql），有好幾個版本時用最新的 */
function findPgTool(name) {
  let best = null;
  for (const dir of pgBinDirs()) {
    const file = path.join(dir, IS_WIN ? `${name}.exe` : name);
    if (!fs.existsSync(file)) continue;
    const r = spawnSync(file, ['--version'], { encoding: 'utf8', windowsHide: true });
    const m = /(\d+)(?:\.\d+)?/.exec(r.stdout || '');
    if (!m) continue;
    const major = Number(m[1]);
    if (!best || major > best.major) best = { path: file, major };
  }
  return best;
}

function portOpen(host, port, ms = 1000) {
  return new Promise((resolve) => {
    const s = net.connect({ host, port });
    const done = (ok) => { s.destroy(); resolve(ok); };
    s.setTimeout(ms, () => done(false));
    s.once('connect', () => done(true));
    s.once('error', () => done(false));
  });
}

async function tryConnect(config) {
  const { Client } = require('pg');
  const c = new Client(Object.assign({ connectionTimeoutMillis: 5000 }, config));
  c.on('error', () => {});
  try {
    await c.connect();
    return { client: c };
  } catch (e) {
    try { await c.end(); } catch (_) { /* 本來就沒連上 */ }
    return { error: e };
  }
}

function pgDownMessage(host, port) {
  if (!findPgTool('psql') && !findPgTool('pg_dump')) {
    openUrl(path.join(__dirname, 'need-postgres.html'));
    return '這台電腦好像還沒安裝 PostgreSQL 資料庫。已經幫你打開安裝說明，裝好之後再啟動一次。';
  }
  const how = IS_WIN
    ? '請按 Win 鍵搜尋「服務」並打開，找到 postgresql-x64-（版本），按右鍵 ›「啟動」，再把「啟動類型」設成「自動」。'
    : IS_MAC
      ? 'Postgres.app：打開它並按 Start（勾選開機自動啟動）。Homebrew：執行 brew services start postgresql@17（換成你的版本）。'
      : '請執行 sudo systemctl enable --now postgresql';
  return `PostgreSQL 已經安裝了，但是沒有在執行（連不上 ${host}:${port}）。\n${how}\n`
    + '如果你的 PostgreSQL 用的不是 5432 埠，可以加上 --setup --pg-port 埠號 重新設定。';
}

/** 用 PostgreSQL 管理員帳號連線：先試不用密碼（Mac 的 Postgres.app、Homebrew），不行再問密碼 */
async function connectAdmin(args) {
  const host = args.pgHost || 'localhost';
  let port = args.pgPort;
  if (!port) {
    for (const p of PG_PORTS) if (await portOpen(host, p)) { port = p; break; }
    port = port || PG_PORTS[0];
  }
  const base = { host, port, database: 'postgres' };

  if (args.pgPassword !== undefined) {
    const user = args.pgUser || 'postgres';
    const r = await tryConnect(Object.assign({ user, password: args.pgPassword }, base));
    if (r.client) return { client: r.client, host, port };
    if (errKind(r.error) === 'down') throw new Fatal(pgDownMessage(host, port));
    throw new Fatal(`無法用 ${user} 登入 PostgreSQL：${r.error.message}`);
  }

  let osUser = null;
  try { osUser = os.userInfo().username; } catch (_) { /* 某些環境拿不到 */ }
  const users = args.pgUser ? [args.pgUser] : Array.from(new Set(['postgres', osUser].filter(Boolean)));
  const hosts = [host];
  if (!IS_WIN && host === 'localhost') {
    for (const dir of ['/var/run/postgresql', '/tmp']) if (fs.existsSync(path.join(dir, `.s.PGSQL.${port}`))) hosts.push(dir);
  }
  let reachable = false;
  for (const user of users) {
    for (const h of hosts) {
      const r = await tryConnect(Object.assign({}, base, { host: h, user }));
      if (r.client) return { client: r.client, host, port };
      if (errKind(r.error) !== 'down') reachable = true;
    }
  }
  if (!reachable) throw new Fatal(pgDownMessage(host, port));

  const p = getPrompter();
  say('需要 PostgreSQL 管理員的密碼，就是安裝 PostgreSQL 時自己設定的那一組。');
  say('只會用這一次來建立小倆口專用的資料庫帳號，不會存起來。');
  if (process.platform === 'linux') say('（Linux 預設沒有密碼，可以先執行：sudo -u postgres psql -c "\\password postgres" 設定一組）');
  for (let attempt = 1; attempt <= 3; attempt++) {
    const user = args.pgUser || (await p.ask('管理員帳號（直接按 Enter 就是 postgres）：'));
    if (user === null) throw new Fatal(`需要設定資料庫：請直接雙擊 ${START_FILE}，照著畫面輸入密碼。`);
    const password = await p.ask('密碼（輸入時不會顯示）：', { hidden: true });
    if (password === null) throw new Fatal(`需要設定資料庫：請直接雙擊 ${START_FILE}，照著畫面輸入密碼。`);
    const name = user.trim() || 'postgres';
    const r = await tryConnect(Object.assign({}, base, { user: name, password }));
    if (r.client) return { client: r.client, host, port };
    const left = 3 - attempt;
    if (errKind(r.error) === 'password' || errKind(r.error) === 'denied') say(left ? `😣 帳號或密碼不對，再試一次（還有 ${left} 次）` : '😣 帳號或密碼不對。');
    else say(`😣 登入失敗：${r.error.message}`);
  }
  throw new Fatal('三次都登入失敗。忘記密碼的話，可以參考說明文件的「忘記 PostgreSQL 密碼」。');
}

/** 建立（或重設）小倆口專用帳號與資料庫，回傳 DATABASE_URL。可以重複執行。 */
async function setupDatabase(admin, { host, port }) {
  const ver = Number((await admin.query('show server_version_num')).rows[0].server_version_num);
  if (ver < MIN_PG) throw new Fatal(`你的 PostgreSQL 是 ${pgVersionText(ver)} 版，太舊了，請安裝 13 以上的版本（建議最新版）。`);
  const me = (await admin.query('select rolsuper, rolcreaterole, rolcreatedb from pg_roles where rolname = current_user')).rows[0];
  if (!me.rolsuper && !(me.rolcreaterole && me.rolcreatedb)) throw new Fatal('這個帳號沒有建立資料庫的權限，請改用 postgres 管理員帳號。');

  const password = crypto.randomBytes(18).toString('base64url');
  const user = admin.escapeIdentifier(DB_USER);
  const roleExists = (await admin.query('select 1 from pg_roles where rolname = $1', [DB_USER])).rowCount > 0;
  await admin.query(`${roleExists ? 'alter' : 'create'} role ${user} login password ${admin.escapeLiteral(password)}`);
  for (const r of ['anon', 'authenticated']) {
    if (!(await admin.query('select 1 from pg_roles where rolname = $1', [r])).rowCount) await admin.query(`create role ${r} nologin`);
  }
  // 伺服器每個請求都會 set role authenticated，好讓 Row Level Security 生效
  await admin.query(`grant authenticated to ${user}`);
  if (!me.rolsuper) await admin.query(`grant ${user} to current_user`).catch(() => {});

  const db = (await admin.query('select pg_get_userbyid(datdba) as owner from pg_database where datname = $1', [DB_NAME])).rows[0];
  const name = admin.escapeIdentifier(DB_NAME);
  if (!db) await admin.query(`create database ${name} owner ${user} template template0 encoding 'UTF8' lc_collate 'C' lc_ctype 'C'`);
  else if (db.owner !== DB_USER) await admin.query(`alter database ${name} owner to ${user}`);
  return buildDatabaseUrl({ user: DB_USER, password, host, port, database: DB_NAME });
}

async function runSetup(args) {
  say('🔧 正在準備資料庫……');
  const admin = await connectAdmin(args);
  let url;
  try {
    url = await setupDatabase(admin.client, admin);
  } finally {
    await admin.client.end().catch(() => {});
  }
  const check = await tryConnect({ connectionString: url });
  if (!check.client) {
    throw new Fatal(`資料庫建立好了，但小倆口的帳號登入失敗：${check.error.message}\n`
      + '請檢查 PostgreSQL 的 pg_hba.conf 是否允許本機用密碼登入（scram-sha-256 或 md5）。');
  }
  await check.client.end();
  const values = { DATABASE_URL: url };
  const old = readEnvFile();
  if (args.port) values.PORT = args.port;
  else if (!old.PORT) values.PORT = 8080;
  if (!old.MAX_USERS) values.MAX_USERS = 2;
  writeEnvFile(values);
  say(`✅ 資料庫準備好了（PostgreSQL ${admin.host}:${admin.port}，資料庫 ${DB_NAME}，設定存在 server/.env）`);
}

// ---------------------------------------------------------------- 啟動

async function isOurServer(port) {
  try {
    const res = await fetch(`http://127.0.0.1:${port}/api/health`, { signal: AbortSignal.timeout(1500) });
    return res.ok && (await res.json()).ok === true;
  } catch (_) {
    return false;
  }
}

async function start(args) {
  let fresh = false;
  let env = readEnvFile();
  if (args.setup || !env.DATABASE_URL) {
    if (!env.DATABASE_URL) say('💞 歡迎使用小倆口！第一次啟動，先幫你準備好資料庫。\n');
    await runSetup(args);
    fresh = true;
  } else {
    let r = await tryConnect({ connectionString: env.DATABASE_URL });
    // 開機自動啟動時 PostgreSQL 可能還沒準備好，最多等 2 分鐘
    for (let waited = 0; args.autostart && r.error && errKind(r.error) === 'down' && waited < 120; waited += 3) {
      await new Promise((res) => setTimeout(res, 3000));
      r = await tryConnect({ connectionString: env.DATABASE_URL });
    }
    if (r.client) await r.client.end();
    else {
      const kind = errKind(r.error);
      const u = new URL(env.DATABASE_URL);
      if (kind === 'down') throw new Fatal(pgDownMessage(u.hostname, u.port || 5432));
      if (kind === 'other') throw new Fatal(`連不到資料庫：${r.error.message}`);
      say('資料庫的帳號跟上次不一樣（可能重灌過 PostgreSQL），重新設定一次。');
      await runSetup(Object.assign({ pgHost: u.hostname.replace(/^\[(.*)\]$/, '$1'), pgPort: Number(u.port || 5432) }, args));
      fresh = true;
    }
  }
  if (prompter) prompter.close();

  env = readEnvFile();
  Object.assign(process.env, env); // .env 是啟動程式的設定來源
  if (args.port) process.env.PORT = String(args.port);
  const cfg = require('../src/config')();

  if (await isOurServer(cfg.port)) {
    say(`💞 小倆口伺服器已經在執行了：http://localhost:${cfg.port}`);
    say('不用再開一次。要停止的話，關掉原本那個伺服器視窗。');
    if (!args.noBrowser) openUrl(`http://localhost:${cfg.port}`);
    return false;
  }
  if (IS_WIN && fresh) {
    say('\n🛡️ 等一下 Windows 可能會跳出「Windows 安全性警訊」：請勾選「私人網路」後按「允許存取」，手機才連得到。');
  }
  process.title = '小倆口伺服器';
  const { port } = await require('../src/index').main(cfg);
  if (IS_WIN) say('  小提醒：在這個視窗裡點一下會讓伺服器暫停（標題出現「選取」），按 Esc 就會繼續。\n');
  if (fresh && !args.noBrowser) openUrl(`http://localhost:${port}`);
  scheduleAutoBackup(env.DATABASE_URL);
  return true;
}

// ---------------------------------------------------------------- 備份與還原

function requirePgTool(name) {
  const tool = findPgTool(name);
  if (!tool) throw new Fatal(`找不到 PostgreSQL 的 ${name} 工具。請確認 PostgreSQL 有安裝在這台電腦上。`);
  return tool;
}

function dumpArgs(file) {
  return ['--no-owner', '--format=plain', '--encoding=UTF8', `--file=${file}`];
}

function backup({ openFolder = true } = {}) {
  const env = readEnvFile();
  const BACKUP_DIR = backupDir();
  if (!env.DATABASE_URL) throw new Fatal(`還沒設定過資料庫，請先雙擊 ${START_FILE}。`);
  const pgDump = requirePgTool('pg_dump');
  fs.mkdirSync(BACKUP_DIR, { recursive: true });
  const file = path.join(BACKUP_DIR, backupFileName(new Date()));
  const r = spawnSync(pgDump.path, dumpArgs(file), {
    env: Object.assign({}, process.env, pgEnv(env.DATABASE_URL)), stdio: ['ignore', 'inherit', 'inherit'], windowsHide: true
  });
  if (r.status !== 0) {
    try { fs.unlinkSync(file); } catch (_) { /* 沒產生檔案 */ }
    throw new Fatal('備份失敗（上面有 PostgreSQL 的錯誤訊息）。請確認 PostgreSQL 有在執行。');
  }
  say(`✅ 已備份到 ${file}`);
  say('   建議再複製一份到雲端硬碟或隨身碟，電腦壞掉時才救得回來。');
  if (openFolder && process.stdout.isTTY && IS_WIN) openUrl(BACKUP_DIR);
  return file;
}

/** 距離上次備份超過一天就在背景備份一次，並只保留最近 keep 份自動備份 */
function autoBackup(databaseUrl, keep, pgDump) {
  const DAY = 24 * 3600 * 1000;
  const BACKUP_DIR = backupDir();
  let latest = 0;
  try {
    for (const n of fs.readdirSync(BACKUP_DIR)) {
      if (/^xiaoliangkou-.*\.sql$/.test(n)) latest = Math.max(latest, fs.statSync(path.join(BACKUP_DIR, n)).mtimeMs);
    }
  } catch (_) { /* 還沒有備份資料夾 */ }
  if (Date.now() - latest < DAY - 60000) return Promise.resolve(null);
  fs.mkdirSync(BACKUP_DIR, { recursive: true });
  const file = path.join(BACKUP_DIR, backupFileName(new Date(), true));
  return new Promise((resolve) => {
    const p = spawn(pgDump.path, dumpArgs(file), {
      env: Object.assign({}, process.env, pgEnv(databaseUrl)), stdio: 'ignore', windowsHide: true
    });
    p.on('error', () => resolve(null));
    p.on('exit', (code) => {
      if (code !== 0) {
        try { fs.unlinkSync(file); } catch (_) { /* 沒產生 */ }
        console.error('[備份] 自動備份失敗，請確認 PostgreSQL 有在執行');
        return resolve(null);
      }
      console.log(`[備份] 已自動備份到 ${file}`);
      for (const n of autoBackupsToPrune(fs.readdirSync(BACKUP_DIR), keep)) fs.unlinkSync(path.join(BACKUP_DIR, n));
      resolve(file);
    });
  });
}

/** 伺服器執行時每天自動備份，保留最近 AUTO_BACKUP_KEEP 份（預設 14，設 0 關閉） */
function scheduleAutoBackup(databaseUrl) {
  const keep = Number(process.env.AUTO_BACKUP_KEEP || 14);
  if (!keep) return;
  const pgDump = findPgTool('pg_dump');
  if (!pgDump) return say('（找不到 pg_dump，不會自動備份；可以之後用 --backup 手動備份）');
  const run = () => autoBackup(databaseUrl, keep, pgDump);
  setTimeout(run, 60 * 1000).unref();
  setInterval(run, 3600 * 1000).unref();
}

async function restore(args) {
  if (!args.restore) throw new Fatal(IS_WIN ? '請把備份檔（.sql）拖曳到 restore-windows.bat 上面。' : `用法：./${START_FILE} --restore 備份檔.sql`);
  const file = path.resolve(args.restore);
  if (!fs.existsSync(file)) throw new Fatal(`找不到備份檔：${file}`);
  const env = readEnvFile();
  if (!env.DATABASE_URL) {
    throw new Fatal(`還沒設定過資料庫：請先雙擊 ${START_FILE} 完成第一次設定，關掉伺服器視窗後再還原。`);
  }
  const port = Number(env.PORT || 8080);
  if (await portOpen('127.0.0.1', port)) throw new Fatal('伺服器正在執行，請先關掉伺服器視窗再還原。');
  const psql = requirePgTool('psql');
  const conn = pgEnv(env.DATABASE_URL);

  say(`要用這個備份檔取代目前所有的資料：\n  ${file}`);
  if (!args.yes && !(await confirm('確定嗎？'))) { say('已取消，沒有改動任何資料。'); return; }

  // 先把現在的資料備份起來，以防選錯檔案
  const reachable = await tryConnect({ connectionString: env.DATABASE_URL });
  if (reachable.client) {
    await reachable.client.end();
    say('先備份目前的資料……');
    backup({ openFolder: false });
  }

  const admin = await connectAdmin(Object.assign({ pgHost: conn.PGHOST, pgPort: Number(conn.PGPORT) }, args));
  try {
    const db = admin.client.escapeIdentifier(conn.PGDATABASE);
    await admin.client.query(`drop database if exists ${db} with (force)`);
    await admin.client.query(`create database ${db} owner ${admin.client.escapeIdentifier(conn.PGUSER)} template template0 encoding 'UTF8' lc_collate 'C' lc_ctype 'C'`);
  } finally {
    await admin.client.end().catch(() => {});
  }
  say('正在還原……');
  const r = spawnSync(psql.path, ['-X', '-q', '-v', 'ON_ERROR_STOP=1', '--single-transaction', '-f', file], {
    env: Object.assign({}, process.env, conn), stdio: ['ignore', 'ignore', 'inherit'], windowsHide: true
  });
  if (r.status !== 0) throw new Fatal('還原失敗（上面有 PostgreSQL 的錯誤訊息），資料庫目前是空的。確認備份檔正確後可以再還原一次。');
  say(`✅ 還原完成！雙擊 ${START_FILE} 就能繼續使用。`);
}

// ---------------------------------------------------------------- 忘記 App 密碼

/** 好唸、不會看錯的臨時密碼（沒有 0/o、1/l/i） */
function tempPassword(length = 8) {
  const chars = 'abcdefghjkmnpqrstuvwxyz23456789';
  let out = '';
  for (let i = 0; i < length; i++) out += chars[crypto.randomInt(chars.length)];
  return out;
}

async function resetPassword(args) {
  const env = readEnvFile();
  if (!env.DATABASE_URL) throw new Fatal(`還沒設定過資料庫，請先雙擊 ${START_FILE}。`);
  const r = await tryConnect({ connectionString: env.DATABASE_URL });
  if (!r.client) {
    const u = new URL(env.DATABASE_URL);
    throw new Fatal(errKind(r.error) === 'down' ? pgDownMessage(u.hostname, u.port || 5432) : `連不到資料庫：${r.error.message}`);
  }
  const db = r.client;
  try {
    const names = (await db.query('select username from auth.users order by created_at')).rows.map((x) => x.username);
    if (!names.length) throw new Fatal('還沒有人註冊帳號。');
    let username = String(args.resetPassword || '').trim().toLowerCase();
    if (!username) {
      say(`目前的帳號：${names.join('、')}`);
      const answer = await getPrompter().ask('要重設哪個帳號的密碼？');
      username = String(answer || '').trim().toLowerCase();
      if (!username) { say('已取消。'); return; }
    }
    if (names.indexOf(username) < 0) throw new Fatal(`找不到帳號「${username}」。目前的帳號：${names.join('、')}`);
    const password = tempPassword();
    const { hashPassword } = require('../src/auth');
    // 跟 App 裡改密碼一樣：舊的登入全部失效
    await db.query("update auth.users set password_hash = $2, tokens_valid_after = now() - interval '1 second' where username = $1",
      [username, await hashPassword(password)]);
    say(`✅ 已重設「${username}」的密碼。臨時密碼：${password}`);
    say('   用它登入 App 之後，記得到「我的 › 伺服器與帳號 › 改密碼」換成自己的密碼。');
  } finally {
    await db.end().catch(() => {});
  }
}

// ---------------------------------------------------------------- 開機自動啟動

function autostartFile() {
  if (IS_WIN) return path.join(process.env.APPDATA || path.join(os.homedir(), 'AppData', 'Roaming'), 'Microsoft', 'Windows', 'Start Menu', 'Programs', 'Startup', 'XiaoLiangKou.lnk');
  if (IS_MAC) return path.join(os.homedir(), 'Library', 'LaunchAgents', 'tw.xiaoliangkou.server.plist');
  return path.join(process.env.XDG_CONFIG_HOME || path.join(os.homedir(), '.config'), 'systemd', 'user', 'xiaoliangkou.service');
}

function macPlist() {
  const log = path.join(os.homedir(), 'Library', 'Logs', 'XiaoLiangKou.log');
  const args = ['/bin/bash', path.join(ROOT, START_FILE), '--autostart'].map((a) => `    <string>${escapeXml(a)}</string>`).join('\n');
  return `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>tw.xiaoliangkou.server</string>
  <key>ProgramArguments</key>
  <array>
${args}
  </array>
  <key>WorkingDirectory</key><string>${escapeXml(ROOT)}</string>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>
  <key>ThrottleInterval</key><integer>30</integer>
  <key>StandardOutPath</key><string>${escapeXml(log)}</string>
  <key>StandardErrorPath</key><string>${escapeXml(log)}</string>
</dict>
</plist>
`;
}

function systemdUnit() {
  return `[Unit]
Description=XiaoLiangKou server
Wants=network-online.target
After=network-online.target postgresql.service

[Service]
ExecStart=/bin/bash ${systemdQuote(path.join(ROOT, START_FILE))} --autostart
WorkingDirectory=${ROOT.replace(/%/g, '%%')}
Restart=on-failure
RestartSec=30

[Install]
WantedBy=default.target
`;
}

function removeAutostart({ quiet = false } = {}) {
  const file = autostartFile();
  if (IS_MAC) spawnSync('launchctl', ['unload', file], { stdio: 'ignore' });
  if (!IS_WIN && !IS_MAC) spawnSync('systemctl', ['--user', 'disable', 'xiaoliangkou.service'], { stdio: 'ignore' });
  const existed = fs.existsSync(file);
  if (existed) fs.unlinkSync(file);
  if (!quiet) say(existed ? '✅ 已取消開機自動啟動。' : '本來就沒有設定開機自動啟動。');
}

async function installAutostart() {
  if (!readEnvFile().DATABASE_URL) throw new Fatal(`請先雙擊 ${START_FILE} 完成第一次設定，再設定自動啟動。`);
  const file = autostartFile();
  if (fs.existsSync(file) && getPrompter().interactive) {
    if (await confirm('已經設定過開機自動啟動了。要取消嗎？')) return removeAutostart();
  }
  fs.mkdirSync(path.dirname(file), { recursive: true });
  if (IS_WIN) {
    const script = [
      `$s = (New-Object -ComObject WScript.Shell).CreateShortcut(${psQuote(file)})`,
      `$s.TargetPath = ${psQuote(path.join(ROOT, START_FILE))}`,
      "$s.Arguments = '--autostart'",
      `$s.WorkingDirectory = ${psQuote(ROOT)}`,
      '$s.WindowStyle = 7',
      "$s.Description = 'XiaoLiangKou server'",
      '$s.Save()'
    ].join('; ');
    const r = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-EncodedCommand',
      Buffer.from(script, 'utf16le').toString('base64')], { stdio: 'inherit', windowsHide: true });
    if (r.status !== 0 || !fs.existsSync(file)) throw new Fatal('建立啟動捷徑失敗。也可以手動把 start-windows.bat 的捷徑放進「啟動」資料夾（Win+R 輸入 shell:startup）。');
    say('✅ 設定好了！之後登入 Windows 時，伺服器會自動在背景啟動（工作列上會有一個縮小的視窗，別關掉它）。');
  } else if (IS_MAC) {
    fs.writeFileSync(file, macPlist());
    say('✅ 設定好了！之後登入 Mac 時，伺服器會自動在背景啟動。');
    say(`   紀錄檔在 ~/Library/Logs/XiaoLiangKou.log。想現在就改成背景執行：先關掉伺服器視窗，再執行 launchctl load "${file}"`);
  } else {
    fs.writeFileSync(file, systemdUnit());
    const reload = spawnSync('systemctl', ['--user', 'daemon-reload'], { stdio: 'inherit' });
    const enable = reload.status === 0 && spawnSync('systemctl', ['--user', 'enable', 'xiaoliangkou.service'], { stdio: 'inherit' });
    if (!enable || enable.status !== 0) throw new Fatal(`無法啟用 systemd 使用者服務。設定檔已寫在 ${file}，可以手動處理。`);
    say('✅ 設定好了！之後登入時會自動啟動。想在還沒登入時就啟動，執行：sudo loginctl enable-linger $USER');
    say('   現在就改成背景執行：關掉目前的伺服器後，執行 systemctl --user start xiaoliangkou');
  }
}

// ---------------------------------------------------------------- 入口

async function run(argv) {
  checkNode();
  const args = parseArgs(argv);
  if (args.help) { say(HELP); return false; }
  if (args.removeAutostart) { removeAutostart(); return false; }
  if (args.installAutostart) { await installAutostart(); return false; }
  ensureDeps();
  if (args.backup) { backup(); return false; }
  if (args.restore !== undefined) { await restore(args); return false; }
  if (args.resetPassword !== undefined) { await resetPassword(args); return false; }
  return start(args);
}

if (require.main === module) {
  run(process.argv.slice(2)).then((keepRunning) => {
    if (!keepRunning) process.exit(0);
  }, (e) => {
    if (e instanceof Fatal) console.error(`\n😿 ${e.message}`);
    else console.error('\n😿 發生未預期的錯誤：', e);
    process.exit(1);
  });
}

module.exports = {
  parseArgs, upsertEnv, buildDatabaseUrl, pgEnv, backupFileName, autoBackupsToPrune, pgVersionText, errKind,
  escapeXml, systemdQuote, psQuote, createPrompter, macPlist, systemdUnit, autoBackup, findPgTool, tempPassword, Fatal
};
