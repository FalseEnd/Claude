'use strict';
// 不用 Docker 的啟動程式：純函式的單元測試（不需要資料庫）
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { PassThrough } = require('node:stream');
const L = require('../scripts/launcher');
const { parseEnv } = require('../src/config');

test('parseArgs：旗標、帶值參數、--key=value 與錯誤', () => {
  assert.deepEqual(L.parseArgs([]), {});
  assert.deepEqual(L.parseArgs(['--setup', '--port', '9000', '--pg-port=5433']), { setup: true, port: 9000, pgPort: 5433 });
  assert.deepEqual(L.parseArgs(['--autostart']), { autostart: true, noBrowser: true });
  assert.deepEqual(L.parseArgs(['--pg-password', '']), { pgPassword: '' });
  assert.deepEqual(L.parseArgs(['--restore', '']), { restore: '' });
  assert.deepEqual(L.parseArgs(['--reset-password', '']), { resetPassword: '' });
  assert.deepEqual(L.parseArgs(['--reset-password', 'xiaoyu']), { resetPassword: 'xiaoyu' });
  assert.equal(L.parseArgs(['--restore', 'C:\\備份\\a b.sql']).restore, 'C:\\備份\\a b.sql');
  assert.throws(() => L.parseArgs(['--bogus']), L.Fatal);
  assert.throws(() => L.parseArgs(['--port']), /後面要接一個值/);
  assert.throws(() => L.parseArgs(['--port', '70000']), /埠號/);
  assert.throws(() => L.parseArgs(['--setup=1']), L.Fatal);
});

test('upsertEnv：更新指定的值，保留註解與其他設定', () => {
  const before = '# 註解\nDATABASE_URL=old\r\nPORT=9000\nDATABASE_URL=dup\n';
  const after = L.upsertEnv(before, { DATABASE_URL: 'new', MAX_USERS: 2 });
  assert.equal(after, '# 註解\nDATABASE_URL=new\nPORT=9000\nMAX_USERS=2\n');
  assert.deepEqual(parseEnv(after), { DATABASE_URL: 'new', PORT: '9000', MAX_USERS: '2' });
  assert.equal(L.upsertEnv('', { A: 1 }), 'A=1\n');
});

test('DATABASE_URL 與 pg_dump 環境變數：特殊字元來回不變', () => {
  const url = L.buildDatabaseUrl({ user: 'xlk', password: 'p@ss:/w#rd', host: 'localhost', port: 5433, database: 'xiaoliangkou' });
  assert.equal(url, 'postgresql://xlk:p%40ss%3A%2Fw%23rd@localhost:5433/xiaoliangkou');
  assert.deepEqual(L.pgEnv(url), { PGHOST: 'localhost', PGPORT: '5433', PGUSER: 'xlk', PGPASSWORD: 'p@ss:/w#rd', PGDATABASE: 'xiaoliangkou' });
  assert.equal(L.pgEnv(L.buildDatabaseUrl({ user: 'a', password: 'b', host: '::1', port: 5432, database: 'd' })).PGHOST, '::1');
  assert.equal(L.pgEnv('postgresql://postgres@localhost/xlk_api?host=/tmp/pg').PGHOST, '/tmp/pg');
  assert.equal(L.pgEnv('postgresql://postgres@localhost/xlk_api').PGPORT, '5432');
});

test('備份檔名與自動備份的保留份數', () => {
  const d = new Date(2026, 0, 2, 3, 4, 5);
  assert.equal(L.backupFileName(d), 'xiaoliangkou-20260102-030405.sql');
  assert.equal(L.backupFileName(d, true), 'xiaoliangkou-auto-20260102-030405.sql');
  const names = ['xiaoliangkou-auto-20260103-000000.sql', 'xiaoliangkou-20250101-000000.sql', 'notes.txt',
    'xiaoliangkou-auto-20260101-000000.sql', 'xiaoliangkou-auto-20260102-000000.sql'];
  assert.deepEqual(L.autoBackupsToPrune(names, 2), ['xiaoliangkou-auto-20260101-000000.sql']);
  assert.deepEqual(L.autoBackupsToPrune(names, 5), []);
});

test('臨時密碼：夠長、不含容易看錯的字', () => {
  for (let i = 0; i < 50; i++) {
    const p = L.tempPassword();
    assert.match(p, /^[a-hjkmnp-z2-9]{8}$/);
  }
  assert.notEqual(L.tempPassword(), L.tempPassword());
});

test('PostgreSQL 版本與連線錯誤分類', () => {
  assert.equal(L.pgVersionText(160004), '16.4');
  assert.equal(L.pgVersionText(90624), '9.6');
  assert.equal(L.errKind(Object.assign(new Error('connect ECONNREFUSED 127.0.0.1:5432'), { code: 'ECONNREFUSED' })), 'down');
  assert.equal(L.errKind(Object.assign(new AggregateError([Object.assign(new Error(), { code: 'ECONNREFUSED' })], 'x'))), 'down');
  assert.equal(L.errKind(new Error('Connection terminated due to connection timeout')), 'down');
  assert.equal(L.errKind(new Error('SASL: SCRAM-SERVER-FIRST-MESSAGE: client password must be a string')), 'password');
  assert.equal(L.errKind(Object.assign(new Error('password authentication failed'), { code: '28P01' })), 'password');
  assert.equal(L.errKind(Object.assign(new Error('Peer authentication failed'), { code: '28000' })), 'denied');
  assert.equal(L.errKind(Object.assign(new Error('database "x" does not exist'), { code: '3D000' })), 'nodb');
  assert.equal(L.errKind(new Error('something else')), 'other');
});

test('自動啟動設定檔：路徑有空白、中文與特殊字元時引號正確', () => {
  assert.equal(L.systemdQuote('/home/小雨/my "app" 100%/$x'), '"/home/小雨/my \\"app\\" 100%%/$$x"');
  assert.equal(L.psQuote("C:\\Users\\O'Neil\\小倆口"), "'C:\\Users\\O''Neil\\小倆口'");
  assert.equal(L.escapeXml('a & <b> "c"'), 'a &amp; &lt;b&gt; &quot;c&quot;');
  assert.match(L.macPlist(), /<string>--autostart<\/string>/);
  assert.match(L.systemdUnit(), /^ExecStart=\/bin\/bash ".*" --autostart$/m);
});

test('問問題：被導向的輸入逐行讀，讀完回傳 null', async () => {
  const input = new PassThrough();
  const output = new PassThrough();
  let shown = '';
  output.on('data', (c) => { shown += c; });
  const p = L.createPrompter(input, output);
  input.write('postgres\r\nsec');
  assert.equal(await p.ask('帳號：'), 'postgres');
  const pw = p.ask('密碼：', { hidden: true });
  input.end('ret\n');
  assert.equal(await pw, 'secret');
  assert.equal(await p.ask('還有嗎：'), null);
  assert.equal(shown, '帳號：\n密碼：\n還有嗎：\n');
  p.close();
});
