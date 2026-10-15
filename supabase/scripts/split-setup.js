'use strict';
// 把 supabase/migrations/*.sql 切成幾個小份（每份約 15 KB），給一次複製不了太多文字的人分批貼到 SQL Editor。
// 只在最外層的分號切開（會跳過字串、$$ 函式內容與註解），每份自己包在 begin; … commit; 裡。
//   node supabase/scripts/split-setup.js           產生 supabase/setup-parts/
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

function build() {
  const files = fs.readdirSync(path.join(dir, 'migrations')).filter((f) => f.endsWith('.sql')).sort();
  const stmts = [];
  for (const f of files) stmts.push(...statements(fs.readFileSync(path.join(dir, 'migrations', f), 'utf8')));
  const parts = [];
  let cur = '';
  for (const s of stmts) {
    if (cur && Buffer.byteLength(cur + s) > LIMIT) { parts.push(cur); cur = ''; }
    cur += s;
  }
  if (cur.trim()) parts.push(cur);
  return parts.map((body, n) => {
    const last = n === parts.length - 1;
    const done = last ? '設定完成 🎉 回到說明文件做第 3 步' : `第 ${n + 1} 份完成 ✅ 接著貼第 ${n + 2} 份`;
    return `-- 小倆口 Supabase 設定：第 ${n + 1} 份，共 ${parts.length} 份（請照順序執行，每份只要執行一次）\n`
      + '-- 這個檔案由 supabase/scripts/split-setup.js 從 supabase/migrations/ 產生，請勿手動修改。\n\n'
      + `begin;\n${body.replace(/^\n+/, '')}\n\nselect '${done}' as "小倆口";\n\ncommit;\n`;
  });
}

const parts = build();
const names = parts.map((_, n) => `part-${String(n + 1).padStart(2, '0')}.sql`);
if (process.argv.includes('--check')) {
  const existing = fs.existsSync(outDir) ? fs.readdirSync(outDir).filter((f) => f.endsWith('.sql')).sort() : [];
  const ok = existing.join() === names.join() && names.every((f, n) => fs.readFileSync(path.join(outDir, f), 'utf8') === parts[n]);
  if (!ok) { console.error('supabase/setup-parts 和 migrations 不一致，請執行 node supabase/scripts/split-setup.js'); process.exit(1); }
  console.log(`setup-parts 和 migrations 一致（${names.length} 份）`);
} else {
  fs.mkdirSync(outDir, { recursive: true });
  for (const f of fs.readdirSync(outDir)) if (f.endsWith('.sql')) fs.unlinkSync(path.join(outDir, f));
  parts.forEach((p, n) => fs.writeFileSync(path.join(outDir, names[n]), p));
  console.log(`已產生 ${names.length} 份：${names.join(', ')}`);
}
