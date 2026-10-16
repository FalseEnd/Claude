'use strict';
// 從 supabase/migrations/*.sql 產生 supabase/setup.sql：使用者貼到 Supabase SQL Editor 執行一次，整個包在同一個交易裡。
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

function build() {
  const files = fs.readdirSync(path.join(dir, 'migrations')).filter((f) => f.endsWith('.sql')).sort();
  const stmts = [];
  for (const f of files) stmts.push(...statements(stripComments(fs.readFileSync(path.join(dir, 'migrations', f), 'utf8'))));
  const wrap = (body, done) => `begin;\n${body.replace(/^\n+/, '').replace(/\n+$/, '')}\nselect '${done}' as "小倆口";\ncommit;\n`;
  return { whole: wrap(stmts.join(''), '設定完成 🎉 回到說明文件做第 3 步') };
}

const { whole } = build();
const wholeFile = path.join(dir, 'setup.sql');
if (process.argv.includes('--check')) {
  if (!fs.existsSync(wholeFile) || fs.readFileSync(wholeFile, 'utf8') !== whole) {
    console.error('supabase/setup.sql 和 migrations 不一致，請執行 bash supabase/scripts/build-setup.sh');
    process.exit(1);
  }
  console.log('setup.sql 和 migrations 一致');
} else {
  fs.writeFileSync(wholeFile, whole);
  console.log('已產生 setup.sql');
}
