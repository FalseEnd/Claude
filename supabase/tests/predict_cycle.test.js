'use strict';
// 確認資料庫的 predict_cycle()（經期關懷排程用）和 App / 原型的 core.predictCycle 算出一樣的結果。
// 需要 DATABASE_URL 與 psql（supabase/scripts/test.sh 會設定好）；沒有就略過。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');
const path = require('node:path');
const core = require(path.join(__dirname, '..', '..', 'prototype', 'core.js'));

const DB = process.env.DATABASE_URL;

// 固定種子的亂數，失敗時可以重現
function rng(seed) {
  return () => {
    seed = (seed * 1664525 + 1013904223) >>> 0;
    return seed / 2 ** 32;
  };
}

function randomCases(n) {
  const rand = rng(20261015);
  const int = (a, b) => a + Math.floor(rand() * (b - a + 1));
  const out = [];
  for (let i = 0; i < n; i++) {
    const count = int(0, 8);
    let day = '2025-01-01';
    day = core.addDays(day, int(0, 300));
    const periods = [];
    for (let k = 0; k < count; k++) {
      // 大多是正常週期，偶爾漏記（很長）或記錯（很短）
      day = core.addDays(day, k === 0 ? 0 : (rand() < 0.8 ? int(21, 38) : int(5, 90)));
      const end = rand() < 0.75 ? core.addDays(day, int(0, 11)) : null;
      periods.push({ start: day, end });
    }
    // 打亂順序：兩邊都要自己排序
    periods.sort(() => rand() - 0.5);
    const settings = {
      cycleLength: rand() < 0.3 ? int(15, 60) : null,
      periodLength: rand() < 0.3 ? int(1, 10) : null,
      lutealLength: rand() < 0.5 ? int(10, 16) : null
    };
    const last = periods.length ? periods.map((p) => p.start).sort().pop() : '2026-01-01';
    const today = core.addDays(last, int(-10, 150));
    out.push({ i, periods, settings, today });
  }
  return out;
}

test('predict_cycle() 與 core.predictCycle 結果相同', { skip: !DB && '沒有 DATABASE_URL' }, () => {
  const cases = randomCases(600);
  const rows = cases.map((c) => ({
    i: c.i,
    starts: c.periods.map((p) => p.start),
    ends: c.periods.map((p) => p.end),
    cl: c.settings.cycleLength, pl: c.settings.periodLength, ll: c.settings.lutealLength,
    today: c.today
  }));
  const sql = `
    select coalesce(json_agg(to_jsonb(p) || jsonb_build_object('i', c.i) order by c.i), '[]')
    from json_to_recordset($json$${JSON.stringify(rows)}$json$)
           as c(i int, starts date[], ends date[], cl int, pl int, ll int, today date)
    cross join lateral public.predict_cycle(c.starts, c.ends, c.cl, c.pl, c.ll, c.today) p`;
  const out = JSON.parse(execFileSync('psql', [DB, '-AtqX', '-v', 'ON_ERROR_STOP=1'], { input: sql, encoding: 'utf8' }));
  assert.equal(out.length, cases.length);

  for (const c of cases) {
    const db = out[c.i];
    const js = core.predictCycle(c.periods, c.settings, c.today);
    const label = `case ${c.i}: ${JSON.stringify(c)}`;
    if (!js) {
      assert.equal(db.next_start, null, label);
      continue;
    }
    assert.deepEqual({
      cycleLength: db.cycle_length, periodLength: db.period_length, lutealLength: db.luteal_length,
      samples: db.samples, irregular: db.irregular,
      cycleStart: db.cycle_start, cycleDay: db.cycle_day, nextStart: db.next_start, daysUntilNext: db.days_until_next,
      ovulation: db.ovulation, fertileStart: db.fertile_start, fertileEnd: db.fertile_end, lateDays: db.late_days
    }, {
      cycleLength: js.stats.cycleLength, periodLength: js.stats.periodLength, lutealLength: js.stats.lutealLength,
      samples: js.stats.samples, irregular: js.stats.irregular,
      cycleStart: js.cycleStart, cycleDay: js.cycleDay, nextStart: js.nextStart, daysUntilNext: js.daysUntilNext,
      ovulation: js.ovulation, fertileStart: js.fertileStart, fertileEnd: js.fertileEnd, lateDays: js.lateDays
    }, label);
  }
});
