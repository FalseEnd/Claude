'use strict';
// 從 supabase/migrations/*.sql 產生給使用者貼到 SQL Editor 的檔案：
//   supabase/setup.sql          全部一次貼上
//   supabase/setup-parts/*.sql  切成幾個小份（每份約 15 KB），給一次複製不了太多文字的人
// 產生的檔案拿掉所有 -- 註解：複製貼上時如果換行被吃掉，註解會把後面的程式碼一起變成註解。
// 只在最外層的分號切開（會跳過字串與 $$ 函式內容），每份自己包在 begin; … commit; 裡。
//   node supabase/scripts/split-setup.js           產生
//   node supabase/scripts/split-setup.js --check   只檢查是否和遷移檔一致
const fs = require('fs');
const path = require('path');

const dir = path.join(__dirname, '..');
const outDir = path.join(dir, 'setup-parts');
const LIMIT = 15000;

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
  const parts = [];
  let cur = '';
  for (const s of stmts) {
    if (cur && Buffer.byteLength(cur + s) > LIMIT) { parts.push(cur); cur = ''; }
    cur += s;
  }
  if (cur.trim()) parts.push(cur);
  const wrap = (body, done) => `begin;\n${body.replace(/^\n+/, '').replace(/\n+$/, '')}\nselect '${done}' as "小倆口";\ncommit;\n`;
  return {
    whole: wrap(stmts.join(''), '設定完成 🎉 回到說明文件做第 3 步'),
    parts: parts.map((body, n) => wrap(body, n === parts.length - 1
      ? '設定完成 🎉 回到說明文件做第 3 步' : `第 ${n + 1} 份完成 ✅ 接著貼第 ${n + 2} 份`))
  };
}

const { whole, parts } = build();
const names = parts.map((_, n) => `part-${String(n + 1).padStart(2, '0')}.sql`);
const wholeFile = path.join(dir, 'setup.sql');
if (process.argv.includes('--check')) {
  const existing = fs.existsSync(outDir) ? fs.readdirSync(outDir).filter((f) => f.endsWith('.sql')).sort() : [];
  const ok = fs.existsSync(wholeFile) && fs.readFileSync(wholeFile, 'utf8') === whole
    && existing.join() === names.join() && names.every((f, n) => fs.readFileSync(path.join(outDir, f), 'utf8') === parts[n]);
  if (!ok) { console.error('supabase/setup.sql 或 setup-parts 和 migrations 不一致，請執行 bash supabase/scripts/build-setup.sh'); process.exit(1); }
  console.log(`setup.sql 與 setup-parts（${names.length} 份）和 migrations 一致`);
} else {
  fs.writeFileSync(wholeFile, whole);
  fs.mkdirSync(outDir, { recursive: true });
  for (const f of fs.readdirSync(outDir)) if (f.endsWith('.sql')) fs.unlinkSync(path.join(outDir, f));
  parts.forEach((p, n) => fs.writeFileSync(path.join(outDir, names[n]), p));
  console.log(`已產生 setup.sql 與 ${names.length} 份 setup-parts：${names.join(', ')}`);
}
