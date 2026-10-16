'use strict';
// 從 supabase/migrations/*.sql 產生使用者貼到 Supabase SQL Editor 執行一次的檔案，各自包在同一個交易裡：
//   supabase/setup.sql        新專案：全部的 migrations
//   supabase/upgrade-v2.sql   已經跑過舊版 setup.sql 的專案：只有 20261016000005 這一個
// 產生的檔案拿掉所有 -- 註解：複製貼上時如果換行被吃掉，註解會把後面的程式碼一起變成註解。
//   node supabase/scripts/build-setup.js           產生
//   node supabase/scripts/build-setup.js --check   只檢查是否和遷移檔一致
const fs = require('fs');
const path = require('path');

const dir = path.join(__dirname, '..');

function statements(sql) {
  const out = [];
  let start = 0;
  let i = 0;
  while (i < sql.length) {
    const c = sql[i];
    if (c === '-' && sql[i + 1] === '-') { const e = sql.indexOf('\n', i); i = e < 0 ? sql.length : e + 1; continue; }
    if (c === '/' && sql[i + 1] === '*') { const e = sql.indexOf('*/', i + 2); i = e < 0 ? sql.length : e + 2; continue; }
    if (c === "'") { i++; while (i < sql.length) { if (sql[i] === "'") { if (sql[i + 1] === "'") { i += 2; continue; } break; } i++; } i++; continue; }
    if (c === '"') { const e = sql.indexOf('"', i + 1); i = e < 0 ? sql.length : e + 1; continue; }
    if (c === '$') {
      const m = /^\$[A-Za-z_]*\$/.exec(sql.slice(i));
      if (m) { const e = sql.indexOf(m[0], i + m[0].length); i = e < 0 ? sql.length : e + m[0].length; continue; }
    }
    if (c === ';') { out.push(sql.slice(start, i + 1)); start = i + 1; }
    i++;
  }
  if (sql.slice(start).trim()) out.push(sql.slice(start));
  return out;
}

// 拿掉 -- 與區塊註解（字串、雙引號名稱裡的不動），並刪掉多餘的空行與行尾空白
function stripComments(sql) {
  let out = '';
  let i = 0;
  while (i < sql.length) {
    const c = sql[i];
    if (c === '-' && sql[i + 1] === '-') { const e = sql.indexOf('\n', i); i = e < 0 ? sql.length : e; continue; }
    if (c === '/' && sql[i + 1] === '*') { const e = sql.indexOf('*/', i + 2); i = e < 0 ? sql.length : e + 2; continue; }
    if (c === "'" || c === '"') {
      let j = i + 1;
      while (j < sql.length) { if (sql[j] === c) { if (sql[j + 1] === c) { j += 2; continue; } break; } j++; }
      out += sql.slice(i, j + 1);
      i = j + 1;
      continue;
    }
    out += c;
    i++;
  }
  return out.split('\n').map((l) => l.replace(/\s+$/, '')).filter((l) => l !== '').join('\n') + '\n';
}

const OUTPUTS = [
  { file: 'setup.sql', only: () => true, done: '設定完成 🎉 回到說明文件做第 3 步' },
  { file: 'upgrade-v2.sql', only: (f) => f.startsWith('20261016000005_'), done: '更新完成 🎉' }
];

function build(out) {
  const files = fs.readdirSync(path.join(dir, 'migrations')).filter((f) => f.endsWith('.sql') && out.only(f)).sort();
  const stmts = [];
  for (const f of files) stmts.push(...statements(stripComments(fs.readFileSync(path.join(dir, 'migrations', f), 'utf8'))));
  const body = stmts.join('').replace(/^\n+/, '').replace(/\n+$/, '');
  return `begin;\n${body}\nselect '${out.done}' as "小倆口";\ncommit;\n`;
}

const check = process.argv.includes('--check');
let stale = false;
for (const out of OUTPUTS) {
  const sql = build(out);
  const file = path.join(dir, out.file);
  if (check) {
    if (!fs.existsSync(file) || fs.readFileSync(file, 'utf8') !== sql) {
      console.error(`supabase/${out.file} 和 migrations 不一致，請執行 bash supabase/scripts/build-setup.sh`);
      stale = true;
    } else {
      console.log(`${out.file} 和 migrations 一致`);
    }
  } else {
    fs.writeFileSync(file, sql);
    console.log(`已產生 ${out.file}`);
  }
}
if (stale) process.exit(1);
