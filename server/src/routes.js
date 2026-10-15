'use strict';
// App 用的 REST API。除了註冊、登入與健康檢查，每個請求都以登入者身分在交易中執行，
// 權限由資料庫的 Row Level Security 決定（見 db/migrations/002_schema.sql）。
const { HttpError, bad, notFound, camel, buildSet } = require('./http');
const { hashPassword, verifyPassword, signToken } = require('./auth');

const SHARE_FIELDS = {
  location: 'share_location', device: 'share_device', expenses: 'share_expenses',
  mood: 'share_mood', cycle: 'share_cycle', hiddenCategories: ['hidden_categories', 'text[]']
};
const PLACE_FIELDS = {
  name: 'name', icon: 'icon', lat: 'lat', lng: 'lng', radiusM: 'radius_m',
  notifyArrive: 'notify_arrive', notifyLeave: 'notify_leave'
};
const EXPENSE_FIELDS = {
  amount: 'amount', merchant: 'merchant', category: 'category', source: 'source', occurredAt: ['occurred_at', 'timestamptz'],
  visibility: 'visibility', revealOn: ['reveal_on', 'date'], guessable: 'guessable', dedupeKey: 'dedupe_key'
};
const ANNIVERSARY_FIELDS = {
  title: 'title', date: ['date', 'date'], kind: 'kind', repeat: 'repeat', calendar: 'calendar', remindDays: ['remind_days', 'smallint[]']
};
const EVENT_FIELDS = {
  title: 'title', startsAt: ['starts_at', 'timestamptz'], endsAt: ['ends_at', 'timestamptz'], allDay: 'all_day',
  placeId: ['place_id', 'uuid'], locationText: 'location_text', note: 'note', remindMinutes: ['remind_minutes', 'smallint[]']
};
const CYCLE_SETTING_FIELDS = {
  cycleLength: 'cycle_length', periodLength: 'period_length', lutealLength: 'luteal_length',
  partnerCanEdit: 'partner_can_edit', careReminders: 'care_reminders'
};
const NOTIF_KINDS = ['pairing', 'place', 'mood', 'comfort', 'battery_low', 'guess', 'event', 'anniversary', 'cycle_care', 'sharing_changed'];

const rows = async (c, sql, params = []) => camel((await c.query(sql, params)).rows);
const one = async (c, sql, params = []) => camel((await c.query(sql, params)).rows[0] || null);

function range(query, defaultDays = 31) {
  const to = query.get('to') ? new Date(query.get('to')) : new Date(Date.now() + 86400000);
  const from = query.get('from') ? new Date(query.get('from')) : new Date(to.getTime() - defaultDays * 86400000);
  if (isNaN(from) || isNaN(to)) throw bad('invalid_range', '日期格式不正確');
  return [from, to];
}

/** 新增時把 body 轉成欄位與值（只取 map 裡有的欄位） */
function insertParts(body, map, fixed = {}) {
  const cols = [];
  const vals = [];
  const params = [];
  for (const [col, expr] of Object.entries(fixed)) { cols.push(col); vals.push(expr); }
  for (const [key, spec] of Object.entries(map)) {
    if (!(key in body)) continue;
    const [col, cast] = Array.isArray(spec) ? spec : [spec, null];
    params.push(body[key]);
    cols.push(col);
    vals.push(`$${params.length}${cast ? '::' + cast : ''}`);
  }
  return { cols: cols.join(', '), vals: vals.join(', '), params };
}

// 地點「偵測誰」：App 傳 me / partner / both，資料庫存使用者 id（null = 兩人）
const WATCH_SQL = (i) => `case $${i}::text when 'me' then auth.uid() when 'partner' then partner_id() else null end`;

module.exports = function registerRoutes(r, { db, cfg, hub, jobs }) {
  const asUser = db.asUser;

  // ------------------------------------------------------------ 帳號
  r.add('GET', '/api/health', async () => ({ ok: true, time: new Date() }), { auth: false });

  r.add('POST', '/api/auth/register', async ({ body }) => {
    const username = String(body.username || '').trim().toLowerCase();
    const password = String(body.password || '');
    const displayName = String(body.displayName || '').trim();
    if (!/^[a-z0-9_.]{3,32}$/.test(username)) throw bad('invalid_username', '帳號要 3–32 個字，只能用英文小寫、數字、底線或點');
    if (password.length < 6) throw bad('weak_password', '密碼至少要 6 個字');
    if (!displayName || displayName.length > 30) throw bad('invalid_name', '請輸入 1–30 個字的暱稱');
    const hash = await hashPassword(password);
    const id = await db.asOwner(async (c) => {
      await c.query('select pg_advisory_xact_lock(7311)');
      const { n } = (await c.query('select count(*)::int as n from auth.users')).rows[0];
      if (n >= cfg.maxUsers) throw new HttpError(403, 'registration_closed', `這台伺服器最多 ${cfg.maxUsers} 個帳號，已經滿了`);
      if ((await c.query('select 1 from auth.users where username = $1', [username])).rowCount) {
        throw new HttpError(409, 'username_taken', '這個帳號已經有人用了');
      }
      return (await c.query('insert into auth.users (username, password_hash) values ($1, $2) returning id', [username, hash])).rows[0].id;
    });
    await asUser(id, async (c) => {
      await c.query('insert into profiles (id, display_name) values ($1, $2)', [id, displayName]);
      await c.query('insert into sharing_settings (user_id) values ($1) on conflict (user_id) do nothing', [id]);
    });
    return { token: signToken(cfg.secret, id), userId: id };
  }, { auth: false });

  r.add('POST', '/api/auth/login', async ({ body }) => {
    const username = String(body.username || '').trim().toLowerCase();
    const user = await db.asOwner(async (c) => (await c.query('select id, password_hash from auth.users where username = $1', [username])).rows[0]);
    if (!user || !(await verifyPassword(String(body.password || ''), user.password_hash))) {
      throw new HttpError(401, 'invalid_login', '帳號或密碼不正確');
    }
    return { token: signToken(cfg.secret, user.id), userId: user.id };
  }, { auth: false });

  r.add('POST', '/api/auth/password', async ({ uid, body }) => {
    const user = await db.asOwner(async (c) => (await c.query('select password_hash from auth.users where id = $1', [uid])).rows[0]);
    if (!(await verifyPassword(String(body.oldPassword || ''), user.password_hash))) throw new HttpError(401, 'invalid_login', '目前的密碼不正確');
    if (String(body.newPassword || '').length < 6) throw bad('weak_password', '密碼至少要 6 個字');
    const hash = await hashPassword(String(body.newPassword));
    await db.asOwner((c) => c.query("update auth.users set password_hash = $2, tokens_valid_after = now() - interval '1 second' where id = $1", [uid, hash]));
    return { token: signToken(cfg.secret, uid) };
  });

  // ------------------------------------------------------------ 我、對方、配對
  r.add('GET', '/api/me', async ({ uid }) => {
    const username = await db.asOwner(async (c) => (await c.query('select username from auth.users where id = $1', [uid])).rows[0].username);
    return asUser(uid, async (c) => {
      const user = await one(c, 'select id, display_name, avatar_url, birthday from profiles where id = auth.uid()');
      const couple = await one(c, 'select id, together_since, created_at from couples where id = my_couple_id()');
      const partner = couple ? await one(c, 'select id, display_name, avatar_url, birthday from profiles where id = partner_id()') : null;
      const mySharing = await one(c, 'select * from sharing_settings where user_id = auth.uid()');
      const partnerSharing = partner ? await one(c, 'select * from sharing_settings where user_id = partner_id()') : null;
      return { user: { ...user, username }, couple, partner, mySharing, partnerSharing, partnerOnline: partner ? hub.isOnline(partner.id) : false };
    });
  });

  r.add('PUT', '/api/profile', ({ uid, body }) => asUser(uid, async (c) => {
    const set = buildSet(body, { displayName: 'display_name', birthday: ['birthday', 'date'], avatarUrl: 'avatar_url' }, 1);
    if (!set.sql) throw bad('nothing_to_update', '沒有要更新的欄位');
    return one(c, `update profiles set ${set.sql} where id = auth.uid() returning id, display_name, avatar_url, birthday`, set.values);
  }));

  r.add('PUT', '/api/couple', ({ uid, body }) => asUser(uid, async (c) => {
    const row = await one(c, 'update couples set together_since = $1::date where id = my_couple_id() returning id, together_since, created_at', [body.togetherSince]);
    if (!row) throw bad('not_paired', '還沒有配對');
    return row;
  }));

  r.add('POST', '/api/pair/invite', ({ uid }) => asUser(uid, async (c) => {
    const code = (await c.query('select create_invite() as code')).rows[0].code;
    return one(c, 'select code, expires_at from pair_invites where code = $1', [code]);
  }));
  r.add('POST', '/api/pair/accept', ({ uid, body }) => asUser(uid, async (c) => {
    const id = (await c.query('select accept_invite($1) as id', [String(body.code || '')])).rows[0].id;
    if (body.togetherSince) await c.query('update couples set together_since = $2::date where id = $1', [id, body.togetherSince]);
    return { coupleId: id };
  }));
  r.add('POST', '/api/pair/unpair', ({ uid }) => asUser(uid, async (c) => { await c.query('select unpair()'); return { ok: true }; }));

  // ------------------------------------------------------------ 分享設定
  r.add('PUT', '/api/sharing', ({ uid, body }) => asUser(uid, async (c) => {
    const set = buildSet(body, SHARE_FIELDS, 1);
    if (!set.sql) throw bad('nothing_to_update', '沒有要更新的欄位');
    return one(c, `update sharing_settings set ${set.sql}, updated_at = now() where user_id = auth.uid() returning *`, set.values);
  }));
  r.add('POST', '/api/sharing/pause', ({ uid, body }) => asUser(uid, async (c) => {
    const until = (await c.query('select pause_sharing($1) as until', [Number(body.minutes) || 0])).rows[0].until;
    return { pausedUntil: until };
  }));

  // ------------------------------------------------------------ 手機狀態與位置
  r.add('PUT', '/api/status/device', ({ uid, body }) => asUser(uid, async (c) => {
    await c.query(`
      insert into device_status (user_id, battery_level, is_charging, network, network_label, notifications_silenced,
                                 steps_today, last_active_at, device_model, updated_at)
      values (auth.uid(), $1, $2, $3, $4, $5, $6, $7, $8, now())
      on conflict (user_id) do update set
        battery_level = excluded.battery_level, is_charging = excluded.is_charging, network = excluded.network,
        network_label = excluded.network_label, notifications_silenced = excluded.notifications_silenced,
        steps_today = excluded.steps_today, last_active_at = coalesce(excluded.last_active_at, device_status.last_active_at),
        device_model = excluded.device_model, updated_at = now()`,
    [body.batteryLevel, body.isCharging, body.network, body.networkLabel, body.notificationsSilenced,
      body.stepsToday, body.lastActiveAt || null, body.deviceModel]);
    return { ok: true };
  }));
  r.add('PUT', '/api/status/location', ({ uid, body }) => asUser(uid, async (c) => {
    await c.query(`
      insert into locations (user_id, lat, lng, accuracy_m, speed_mps, activity, recorded_at, updated_at)
      values (auth.uid(), $1, $2, $3, $4, $5, coalesce($6::timestamptz, now()), now())
      on conflict (user_id) do update set lat = excluded.lat, lng = excluded.lng, accuracy_m = excluded.accuracy_m,
        speed_mps = excluded.speed_mps, activity = excluded.activity, recorded_at = excluded.recorded_at, updated_at = now()`,
    [body.lat, body.lng, body.accuracyM, body.speedMps, body.activity || null, body.recordedAt || null]);
    return { ok: true };
  }));
  r.add('GET', '/api/partner/status', ({ uid }) => asUser(uid, async (c) => ({
    device: await one(c, 'select * from device_status where user_id = partner_id()'),
    location: await one(c, 'select * from locations where user_id = partner_id()')
  })));
  // 「現在在哪？」：請對方的手機立刻回報一次位置
  r.add('POST', '/api/partner/locate', async ({ uid }) => {
    const partnerId = await asUser(uid, async (c) => (await c.query('select partner_id() as id')).rows[0].id);
    if (!partnerId) throw bad('not_paired', '還沒有配對');
    return { sent: hub.sendToUser(partnerId, { type: 'locate' }) > 0 };
  });

  // ------------------------------------------------------------ 地點標記
  r.add('GET', '/api/places', ({ uid }) => asUser(uid, (c) => rows(c, 'select * from places order by created_at')));
  r.add('POST', '/api/places', ({ uid, body }) => asUser(uid, async (c) => {
    const p = insertParts(body, PLACE_FIELDS);
    p.params.push(body.watch || 'partner');
    return one(c, `insert into places (couple_id, watch_user, ${p.cols}) values (my_couple_id(), ${WATCH_SQL(p.params.length)}, ${p.vals}) returning *`, p.params);
  }));
  r.add('PUT', '/api/places/:id', ({ uid, body, params }) => asUser(uid, async (c) => {
    const set = buildSet(body, PLACE_FIELDS, 2);
    const parts = [set.sql];
    const values = [params.id, ...set.values];
    if ('watch' in body) { values.push(body.watch); parts.push(`watch_user = ${WATCH_SQL(values.length)}`); }
    const sql = parts.filter(Boolean).join(', ');
    if (!sql) throw bad('nothing_to_update', '沒有要更新的欄位');
    const row = await one(c, `update places set ${sql} where id = $1 returning *`, values);
    if (!row) throw notFound();
    return row;
  }));
  r.add('DELETE', '/api/places/:id', ({ uid, params }) => asUser(uid, async (c) => {
    if (!(await c.query('delete from places where id = $1', [params.id])).rowCount) throw notFound();
    return { ok: true };
  }));
  r.add('POST', '/api/places/:id/events', ({ uid, body, params }) => asUser(uid, async (c) => {
    if (!['arrive', 'leave'].includes(body.kind)) throw bad('invalid_kind', 'kind 必須是 arrive 或 leave');
    return one(c, 'insert into place_events (place_id, kind, occurred_at) values ($1, $2, coalesce($3::timestamptz, now())) returning *',
      [params.id, body.kind, body.occurredAt || null]);
  }));

  // ------------------------------------------------------------ 開銷與猜花費
  r.add('GET', '/api/expenses', ({ uid, query }) => asUser(uid, (c) => rows(c, `
    select e.*, g.guess_amount as their_guess, g.points as their_points
    from expenses e left join expense_guesses g on g.expense_id = e.id and g.guesser_id = partner_id()
    where e.user_id = auth.uid() and e.occurred_at >= $1 and e.occurred_at < $2
    order by e.occurred_at desc`, range(query))));
  r.add('POST', '/api/expenses', ({ uid, body }) => asUser(uid, async (c) => {
    const p = insertParts({ occurredAt: new Date().toISOString(), ...body }, EXPENSE_FIELDS, { user_id: 'auth.uid()' });
    const row = await one(c, `insert into expenses (${p.cols}) values (${p.vals}) on conflict (user_id, dedupe_key) do nothing returning *`, p.params);
    return row || { duplicate: true };
  }));
  r.add('PUT', '/api/expenses/:id', ({ uid, body, params }) => asUser(uid, async (c) => {
    const set = buildSet(body, EXPENSE_FIELDS, 2);
    if (!set.sql) throw bad('nothing_to_update', '沒有要更新的欄位');
    const row = await one(c, `update expenses set ${set.sql} where id = $1 returning *`, [params.id, ...set.values]);
    if (!row) throw notFound();
    return row;
  }));
  r.add('DELETE', '/api/expenses/:id', ({ uid, params }) => asUser(uid, async (c) => {
    if (!(await c.query('delete from expenses where id = $1', [params.id])).rowCount) throw notFound();
    return { ok: true };
  }));
  r.add('GET', '/api/partner/expenses', ({ uid, query }) => asUser(uid, (c) => rows(c, 'select * from partner_expenses($1, $2)', range(query))));
  r.add('POST', '/api/partner/expenses/:id/guess', ({ uid, body, params }) => asUser(uid, (c) =>
    one(c, 'select * from submit_guess($1, $2)', [params.id, body.amount])));

  // ------------------------------------------------------------ 心情與安撫
  r.add('GET', '/api/moods', ({ uid, query }) => asUser(uid, (c) => rows(c,
    'select * from moods where created_at > now() - make_interval(days => $1) order by created_at desc',
    [Math.min(366, Math.max(1, Number(query.get('days')) || 7))])));
  r.add('POST', '/api/moods', ({ uid, body }) => asUser(uid, (c) =>
    one(c, 'insert into moods (mood, note) values ($1, nullif($2, \'\')) returning *', [body.mood, body.note || null])));
  r.add('GET', '/api/comforts', ({ uid, query }) => asUser(uid, (c) => rows(c,
    'select * from comforts order by created_at desc limit $1', [Math.min(200, Number(query.get('limit')) || 50)])));
  r.add('POST', '/api/comforts', ({ uid, body }) => asUser(uid, (c) => one(c,
    'insert into comforts (couple_id, receiver_id, kind, message) values (my_couple_id(), partner_id(), $1, nullif($2, \'\')) returning *',
    [body.kind, body.message || null])));
  r.add('POST', '/api/comforts/:id/read', ({ uid, params }) => asUser(uid, async (c) => {
    await c.query('update comforts set read_at = now() where id = $1 and read_at is null', [params.id]);
    return { ok: true };
  }));

  // ------------------------------------------------------------ 紀念日與約會
  r.add('GET', '/api/anniversaries', ({ uid }) => asUser(uid, (c) => rows(c, 'select * from anniversaries order by date')));
  r.add('POST', '/api/anniversaries', ({ uid, body }) => asUser(uid, (c) => {
    const p = insertParts(body, ANNIVERSARY_FIELDS, { couple_id: 'my_couple_id()' });
    return one(c, `insert into anniversaries (${p.cols}) values (${p.vals}) returning *`, p.params);
  }));
  r.add('PUT', '/api/anniversaries/:id', ({ uid, body, params }) => asUser(uid, async (c) => {
    const set = buildSet(body, ANNIVERSARY_FIELDS, 2);
    if (!set.sql) throw bad('nothing_to_update', '沒有要更新的欄位');
    const row = await one(c, `update anniversaries set ${set.sql} where id = $1 returning *`, [params.id, ...set.values]);
    if (!row) throw notFound();
    return row;
  }));
  r.add('DELETE', '/api/anniversaries/:id', ({ uid, params }) => asUser(uid, async (c) => {
    if (!(await c.query('delete from anniversaries where id = $1', [params.id])).rowCount) throw notFound();
    return { ok: true };
  }));

  r.add('GET', '/api/events', ({ uid, query }) => asUser(uid, (c) => {
    const from = query.get('from') ? new Date(query.get('from')) : new Date(Date.now() - 62 * 86400000);
    const to = query.get('to') ? new Date(query.get('to')) : new Date(Date.now() + 366 * 86400000);
    return rows(c, 'select * from events where starts_at >= $1 and starts_at < $2 order by starts_at', [from, to]);
  }));
  r.add('POST', '/api/events', ({ uid, body }) => asUser(uid, (c) => {
    const p = insertParts(body, EVENT_FIELDS, { couple_id: 'my_couple_id()' });
    return one(c, `insert into events (${p.cols}) values (${p.vals}) returning *`, p.params);
  }));
  r.add('PUT', '/api/events/:id', ({ uid, body, params }) => asUser(uid, async (c) => {
    const set = buildSet(body, EVENT_FIELDS, 2);
    if (!set.sql) throw bad('nothing_to_update', '沒有要更新的欄位');
    const row = await one(c, `update events set ${set.sql}, updated_at = now(), reminded_at = null where id = $1 returning *`, [params.id, ...set.values]);
    if (!row) throw notFound();
    return row;
  }));
  r.add('POST', '/api/events/:id/answer', ({ uid, body, params }) => asUser(uid, async (c) => {
    if (!['confirmed', 'declined', 'cancelled'].includes(body.status)) throw bad('invalid_status', '狀態不正確');
    const row = await one(c, 'update events set status = $2, updated_at = now() where id = $1 returning *', [params.id, body.status]);
    if (!row) throw notFound();
    return row;
  }));
  r.add('DELETE', '/api/events/:id', ({ uid, params }) => asUser(uid, async (c) => {
    if (!(await c.query('delete from events where id = $1', [params.id])).rowCount) throw notFound('只有建立約會的人可以刪除');
    return { ok: true };
  }));

  // ------------------------------------------------------------ 經期
  r.add('GET', '/api/cycle', ({ uid }) => asUser(uid, async (c) => ({
    mine: {
      settings: await one(c, 'select * from cycle_settings where user_id = auth.uid()'),
      periods: await rows(c, 'select * from cycle_periods where user_id = auth.uid() order by start_date'),
      logs: await rows(c, "select * from cycle_logs where user_id = auth.uid() and log_date > current_date - 120 order by log_date")
    },
    partner: {
      settings: await one(c, 'select * from cycle_settings where user_id = partner_id()'),
      periods: await rows(c, 'select * from cycle_periods where user_id = partner_id() order by start_date')
    }
  })));
  r.add('PUT', '/api/cycle/settings', ({ uid, body }) => asUser(uid, async (c) => {
    await c.query('insert into cycle_settings (user_id) values (auth.uid()) on conflict (user_id) do nothing');
    const set = buildSet(body, CYCLE_SETTING_FIELDS, 1);
    if (set.sql) await c.query(`update cycle_settings set ${set.sql}, updated_at = now() where user_id = auth.uid()`, set.values);
    return one(c, 'select * from cycle_settings where user_id = auth.uid()');
  }));
  r.add('DELETE', '/api/cycle/settings', ({ uid }) => asUser(uid, async (c) => {
    await c.query('delete from cycle_settings where user_id = auth.uid()');
    return { ok: true };
  }));
  r.add('POST', '/api/cycle/periods', ({ uid, body }) => asUser(uid, (c) => one(c, `
    insert into cycle_periods (user_id, start_date, end_date)
    values (case when $1::text = 'partner' then partner_id() else auth.uid() end, $2::date, $3::date)
    on conflict (user_id, start_date) do update set end_date = excluded.end_date
    returning *`, [body.owner || 'me', body.startDate, body.endDate || null])));
  r.add('PUT', '/api/cycle/periods/:id', ({ uid, body, params }) => asUser(uid, async (c) => {
    const set = buildSet(body, { startDate: ['start_date', 'date'], endDate: ['end_date', 'date'] }, 2);
    if (!set.sql) throw bad('nothing_to_update', '沒有要更新的欄位');
    const row = await one(c, `update cycle_periods set ${set.sql} where id = $1 returning *`, [params.id, ...set.values]);
    if (!row) throw notFound();
    return row;
  }));
  r.add('DELETE', '/api/cycle/periods/:id', ({ uid, params }) => asUser(uid, async (c) => {
    if (!(await c.query('delete from cycle_periods where id = $1', [params.id])).rowCount) throw notFound();
    return { ok: true };
  }));
  r.add('PUT', '/api/cycle/logs/:date', ({ uid, body, params }) => asUser(uid, (c) => one(c, `
    insert into cycle_logs (user_id, log_date, flow, symptoms, note) values (auth.uid(), $1::date, $2, coalesce($3::text[], '{}'), $4)
    on conflict (user_id, log_date) do update set flow = excluded.flow, symptoms = excluded.symptoms, note = excluded.note
    returning *`, [params.date, body.flow || null, body.symptoms || null, body.note || null])));

  // ------------------------------------------------------------ 歷史紀錄、通知設定
  r.add('GET', '/api/history', ({ uid, query }) => asUser(uid, (c) => {
    const kinds = query.get('kinds') ? query.get('kinds').split(',').filter(Boolean) : null;
    return rows(c, `
      select * from activity_log
      where ($1::bigint is null or id < $1) and ($3::text[] is null or kind = any ($3))
      order by id desc limit $2`,
    [query.get('before') || null, Math.min(200, Number(query.get('limit')) || 50), kinds]);
  }));
  r.add('GET', '/api/notification-prefs', ({ uid }) => asUser(uid, async (c) => {
    const saved = await rows(c, 'select kind, enabled from notification_prefs where user_id = auth.uid()');
    const out = Object.fromEntries(NOTIF_KINDS.map((k) => [k, true]));
    saved.forEach((p) => { out[p.kind] = p.enabled; });
    return out;
  }));
  r.add('PUT', '/api/notification-prefs', ({ uid, body }) => asUser(uid, async (c) => {
    for (const [kind, enabled] of Object.entries(body)) {
      if (!NOTIF_KINDS.includes(kind)) continue;
      await c.query(`insert into notification_prefs (user_id, kind, enabled) values (auth.uid(), $1, $2)
                     on conflict (user_id, kind) do update set enabled = excluded.enabled`, [kind, !!enabled]);
    }
    return { ok: true };
  }));

  // 手動觸發排程（測試與除錯用，只能由本機呼叫）
  r.add('POST', '/api/admin/run-jobs', async ({ req, body }) => {
    const ip = req.socket.remoteAddress || '';
    if (!/^(::1|127\.|::ffff:127\.)/.test(ip)) throw new HttpError(403, 'forbidden', '只能從伺服器本機執行');
    return jobs.runAll(body.date ? new Date(body.date) : new Date(), { force: true });
  }, { auth: false });
};
