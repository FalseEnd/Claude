'use strict';
// 資料庫連線、版本遷移，以及「以某位使用者身分」執行查詢的交易包裝。
const fs = require('fs');
const path = require('path');
const { Pool, types } = require('pg');

// numeric / bigint 轉成 JS number，date 保持 'YYYY-MM-DD' 字串
types.setTypeParser(1700, (v) => (v === null ? null : Number(v)));
types.setTypeParser(20, (v) => (v === null ? null : Number(v)));
types.setTypeParser(1082, (v) => v);

const MIGRATIONS = path.join(__dirname, '..', 'db', 'migrations');

function createDb(databaseUrl) {
  const pool = new Pool({ connectionString: databaseUrl, max: 10 });

  async function migrate(log = console.log) {
    const c = await pool.connect();
    try {
      await c.query('create schema if not exists meta');
      await c.query('create table if not exists meta.migrations (name text primary key, applied_at timestamptz not null default now())');
      const done = new Set((await c.query('select name from meta.migrations')).rows.map((r) => r.name));
      const files = fs.readdirSync(MIGRATIONS).filter((f) => f.endsWith('.sql')).sort();
      for (const f of files) {
        if (done.has(f)) continue;
        await c.query('begin');
        try {
          await c.query(fs.readFileSync(path.join(MIGRATIONS, f), 'utf8'));
          await c.query('insert into meta.migrations (name) values ($1)', [f]);
          await c.query('commit');
          log(`[db] 已套用 ${f}`);
        } catch (e) {
          await c.query('rollback');
          throw new Error(`資料庫遷移 ${f} 失敗：${e.message}`);
        }
      }
    } finally {
      c.release();
    }
  }

  async function tx(uid, fn) {
    const c = await pool.connect();
    try {
      await c.query('begin');
      if (uid) {
        // 之後的查詢都以這位使用者的身分執行，受 Row Level Security 保護
        await c.query("select set_config('request.jwt.claim.sub', $1, true)", [uid]);
        await c.query('set local role authenticated');
      }
      const out = await fn(c);
      await c.query('commit');
      return out;
    } catch (e) {
      try { await c.query('rollback'); } catch (_) { /* 連線已斷 */ }
      throw e;
    } finally {
      c.release();
    }
  }

  return {
    pool,
    migrate,
    asUser: (uid, fn) => tx(uid, fn),
    asOwner: (fn) => tx(null, fn),
    close: () => pool.end()
  };
}

module.exports = { createDb };
