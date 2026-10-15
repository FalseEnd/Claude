'use strict';
// 排程：約會提醒（每分鐘）、每日 09:00 的紀念日與經期關懷提醒
const core = require('./core');

function localParts(date, timeZone) {
  const f = new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', hour12: false });
  const p = Object.fromEntries(f.formatToParts(date).map((x) => [x.type, x.value]));
  return { date: `${p.year}-${p.month}-${p.day}`, hour: Number(p.hour) % 24 };
}

function createJobs({ db, cfg, log }) {
  async function eventReminders() {
    return db.asOwner(async (c) => {
      const due = (await c.query(`
        update events e set reminded_at = now()
        from couples c
        where c.id = e.couple_id and c.ended_at is null
          and e.status = 'confirmed' and e.reminded_at is null and e.starts_at > now()
          and e.starts_at - make_interval(mins => coalesce(e.remind_minutes[1], 60)) <= now()
        returning e.id, e.couple_id, e.title, e.starts_at`)).rows;
      for (const e of due) {
        const when = new Intl.DateTimeFormat('zh-TW', { timeZone: cfg.timezone, month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false }).format(e.starts_at);
        await c.query(`select enqueue_notification(m.user_id, 'event', $2, $3, jsonb_build_object('event_id', $4::uuid))
                       from couple_members m where m.couple_id = $1 and m.left_at is null`,
        [e.couple_id, `約會提醒：${e.title}`, `${when} 開始，準備出門囉`, e.id]);
      }
      return due.length;
    });
  }

  async function cycleCare(today) {
    return db.asOwner(async (c) => {
      const owners = (await c.query(`
        select s.*, partner_of(s.user_id) as partner, display_name_of(s.user_id) as name
        from cycle_settings s
        where s.care_reminders and shares(s.user_id, 'cycle') and partner_of(s.user_id) is not null`)).rows;
      let sent = 0;
      for (const o of owners) {
        const periods = (await c.query('select start_date as start, end_date as "end" from cycle_periods where user_id = $1', [o.user_id])).rows;
        const pr = core.predictCycle(periods, {
          cycleLength: o.cycle_length, periodLength: o.period_length, lutealLength: o.luteal_length
        }, today);
        if (!pr || pr.lateDays || pr.daysUntilNext < 0 || pr.daysUntilNext > 2 || o.care_sent_for === pr.nextStart) continue;
        const when = pr.daysUntilNext === 0 ? '今天' : ` ${pr.daysUntilNext} 天後`;
        await c.query("select enqueue_notification($1, 'cycle_care', $2, $3, jsonb_build_object('next_start', $4::text))",
          [o.partner, `${o.name} 的經期預計${when}開始`, '準備熱飲和暖暖包，多一點耐心和抱抱', pr.nextStart]);
        await c.query('update cycle_settings set care_sent_for = $2 where user_id = $1', [o.user_id, pr.nextStart]);
        sent++;
      }
      return sent;
    });
  }

  async function daily(now, force) {
    const { date, hour } = localParts(now, cfg.timezone);
    if (!force && hour < 9) return null;
    const first = await db.asOwner(async (c) => (await c.query(
      "insert into job_runs (name, run_on) values ('daily', $1) on conflict do nothing returning 1", [date])).rowCount);
    if (!first && !force) return null;
    const reminders = await db.asOwner(async (c) => (await c.query('select daily_reminders($1::date) as n', [date])).rows[0].n);
    const care = await cycleCare(date);
    log(`[jobs] ${date} 每日提醒：紀念日 ${reminders}、經期關懷 ${care}`);
    return { date, reminders, care };
  }

  async function runAll(now = new Date(), opts = {}) {
    const events = await eventReminders();
    const dailyResult = await daily(now, !!opts.force);
    return { events, daily: dailyResult };
  }

  let timer = null;
  return {
    runAll,
    eventReminders,
    cycleCare,
    start(intervalMs = 60000) {
      const tick = () => runAll().catch((e) => log('[jobs] 執行失敗', e.message));
      timer = setInterval(tick, intervalMs);
      setTimeout(tick, 2000);
    },
    stop() { clearInterval(timer); }
  };
}

module.exports = { createJobs, localParts };
