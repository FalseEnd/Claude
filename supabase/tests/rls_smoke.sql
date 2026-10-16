-- 資料庫冒煙測試：配對、分享權限、通知、RPC 輸出格式、函式權限、排程與即時事件，
-- 以及 v2 的個人資料、私人備註、日曆紀錄、備忘錄與 Storage 權限。
-- 以 postgres 身分執行：psql "$DB_URL" -f supabase/tests/rls_smoke.sql
-- 全部在一個交易裡，最後 rollback，不會在資料庫留下任何資料。
-- 任何一個 test.ok() 失敗都會中止並顯示訊息。
\set ON_ERROR_STOP on
\set QUIET on

begin;

create schema test;
grant usage on schema test to authenticated, anon;

create function test.ok(cond boolean, msg text) returns void language plpgsql as $$
begin
  if cond is distinct from true then raise exception 'FAILED: %', msg; end if;
  raise notice 'ok - %', msg;
end $$;

-- 跟 PostgREST 一樣，用 JWT claims 告訴資料庫目前是誰
create function test.login(p_user uuid) returns void language sql as $$
  select set_config('request.jwt.claims', json_build_object('sub', p_user, 'role', 'authenticated')::text, true);
$$;

create function test.id(p_name text) returns uuid language sql immutable as $$
  select case p_name
    when 'zhe'  then '00000000-0000-0000-0000-00000000000a'
    when 'yu'   then '00000000-0000-0000-0000-00000000000b'
    when 'mia'  then '00000000-0000-0000-0000-00000000000c'
    when 'leo'  then '00000000-0000-0000-0000-00000000000d' end::uuid
$$;

-- 用 postgres（資料表擁有者）的權限看 outbox
create function test.outbox(p_user uuid, p_kind text) returns bigint
language sql security definer set search_path = public as $$
  select count(*) from public.notification_outbox where recipient_id = p_user and kind = p_kind
$$;
create function test.outbox_titled(p_user uuid, p_title text) returns bigint
language sql security definer set search_path = public as $$
  select count(*) from public.notification_outbox where recipient_id = p_user and title = p_title
$$;

create function test.outbox_body(p_user uuid, p_title text) returns text
language sql security definer set search_path = public as $$
  select body from public.notification_outbox where recipient_id = p_user and title = p_title order by id desc limit 1
$$;
-- 某人收到某張表的 changed 幾次（不受 Row Level Security 限制，才能確認「對方真的沒收到」）
create function test.changed(p_user uuid, p_table text) returns bigint
language sql security definer set search_path = public as $$
  select count(*) from public.realtime_events where user_id = p_user and table_name = p_table
$$;
-- 所有人的即時事件、通知、歷史紀錄筆數：寫私人備註前後要一模一樣
create function test.trace() returns text
language sql security definer set search_path = public as $$
  select format('%s/%s/%s', (select count(*) from public.realtime_events), (select count(*) from public.notification_outbox),
                (select count(*) from public.activity_log))
$$;

-- 預期會失敗的語句：回傳錯誤訊息（SQLSTATE: message），沒失敗則回傳 null
create function test.error_of(p_sql text) returns text language plpgsql as $$
begin
  execute p_sql;
  return null;
exception when others then
  return sqlstate || ': ' || sqlerrm;
end $$;
grant execute on all functions in schema test to authenticated, anon;

-- 跟 Supabase Auth 一樣建立帳號（觸發 handle_new_user）
create function test.signup(p_id uuid, p_email text, p_name text) returns void language sql as $$
  insert into auth.users (instance_id, id, aud, role, email, raw_user_meta_data)
  values ('00000000-0000-0000-0000-000000000000', p_id, 'authenticated', 'authenticated', p_email,
          case when p_name is null then '{}'::jsonb else jsonb_build_object('display_name', p_name) end)
$$;

-- ---------------------------------------------------------------- 註冊與人數上限
update public.app_config set max_users = 3;   -- 這個測試需要一個「外人」
select test.signup(test.id('zhe'), 'azhe@example.com', '阿哲');
select test.signup(test.id('yu'), 'xiaoyu@example.com', '  小雨  ');
select test.signup(test.id('mia'), 'miamia@example.com', null);
select test.ok((select display_name from public.profiles where id = test.id('yu')) = '小雨', 'new user gets a profile with the trimmed display name');
select test.ok((select display_name from public.profiles where id = test.id('mia')) = 'miamia', 'display name falls back to the email local part');
update public.profiles set display_name = 'Mia' where id = test.id('mia');
select test.ok((select count(*) from public.sharing_settings) = 3, 'new users get default sharing settings');
select test.ok(test.error_of($$select test.signup(test.id('leo'), 'leo@example.com', 'Leo')$$) like 'P0001: registration_closed%',
               'registration is closed once max_users is reached');
select test.ok(not exists (select 1 from auth.users where id = test.id('leo')), 'rejected sign-up leaves no auth user');

set local role authenticated;

-- ---------------------------------------------------------------- 配對
select test.login(test.id('zhe'));
select public.create_invite() as code \gset
select test.ok(:'code' ~ '^[A-HJ-NP-Z2-9]{6}$', 'invite code uses the unambiguous alphabet');

select test.login(test.id('yu'));
select test.ok(public.accept_invite(lower(:'code')) is not null, 'partner accepts the invite (case-insensitive)');
select test.ok(public.partner_id() = test.id('zhe'), 'partner_id resolves after pairing');

select test.login(test.id('mia'));
do $$ begin
  perform public.accept_invite((select code from public.pair_invites limit 1));
  raise exception 'FAILED: used invite should be rejected';
exception when others then
  if sqlerrm like 'FAILED%' then raise; end if;
  raise notice 'ok - a used invite cannot be accepted again (%)', sqlerrm;
end $$;
select test.ok((select count(*) from public.profiles) = 1, 'outsider only sees own profile');

select test.login(test.id('yu'));
select test.ok(test.error_of($$update public.couples set ended_at = '2000-01-01'$$) like '42501:%',
               'members cannot end the couple directly (the daily job would delete everything)');
select test.ok(public.app_update_couple('{"togetherSince": "2024-02-14"}')->>'togetherSince' = '2024-02-14',
               'app_update_couple still sets together_since');
select test.ok(test.error_of($$select public.app_update_couple('{"togetherSince": "infinity"}')$$) like '23514:%',
               'together_since cannot be infinity');

-- ---------------------------------------------------------------- 分享與暫停
select test.login(test.id('zhe'));
update public.sharing_settings set share_location = true, share_device = true, share_expenses = true, hidden_categories = '{fun}'
 where user_id = test.id('zhe');
insert into public.device_status (user_id, battery_level, is_charging, network) values (test.id('zhe'), 60, false, 'wifi');
insert into public.locations (user_id, lat, lng, recorded_at) values (test.id('zhe'), 25.033, 121.565, now());

select test.login(test.id('yu'));
select test.ok((select count(*) from public.device_status) = 1, 'partner sees shared device status');
select test.ok((select count(*) from public.locations) = 1, 'partner sees shared location');
select test.ok((select count(*) from public.profiles) = 2, 'partner sees both profiles');
select test.ok((select count(*) from public.realtime_events where table_name = 'locations') = 1,
               'partner gets changed events for a shared location');

select test.login(test.id('mia'));
select test.ok((select count(*) from public.device_status) = 0, 'outsider sees no device status');

select test.login(test.id('zhe'));
select public.pause_sharing(60);
select test.ok(test.error_of($$update public.sharing_settings set paused_until = 'infinity'$$) like '42501:%',
               'pausing only goes through pause_sharing (at most 24 hours)');
update public.locations set lat = 25.034 where user_id = test.id('zhe');
select test.login(test.id('yu'));
select test.ok((select count(*) from public.locations) = 0, 'paused location is hidden');
select test.ok((select count(*) from public.realtime_events where table_name = 'locations') = 1,
               'location updates while paused do not tell the partner');
select test.ok(test.outbox(test.id('yu'), 'sharing_changed') = 2, 'partner is told about sharing changes and the pause');
select test.login(test.id('zhe'));
select public.pause_sharing(0);
select test.login(test.id('yu'));
select test.ok((select count(*) from public.locations) = 1, 'location is visible again after resuming');

-- ---------------------------------------------------------------- 電量通知
select test.login(test.id('zhe'));
update public.device_status set battery_level = 18 where user_id = test.id('zhe');
update public.device_status set battery_level = 15 where user_id = test.id('zhe');
select test.ok(test.outbox(test.id('yu'), 'battery_low') = 1, 'low battery notifies partner once when crossing 20%');

-- ---------------------------------------------------------------- 開銷與猜花費
insert into public.expenses (amount, merchant, category, source, occurred_at, guessable)
values (135, '星巴克 信義新天地', 'drink', 'notification', now() - interval '1 hour', true);
insert into public.expenses (amount, merchant, category, occurred_at, visibility)
values (520, '花店', 'gift', now(), 'private');
insert into public.expenses (amount, merchant, category, occurred_at, visibility, reveal_on)
values (2400, '香水', 'gift', now(), 'surprise', current_date + 7);
insert into public.expenses (amount, merchant, category, occurred_at) values (680, 'KTV', 'fun', now());

select test.login(test.id('yu'));
select test.ok((select count(*) from public.expenses) = 0, 'partner cannot read the expenses table directly');
select test.ok((select count(*) from public.partner_expenses(now() - interval '1 day', now() + interval '1 day')) = 1,
               'private, hidden-category and not-yet-revealed surprise expenses are hidden');
select test.ok((select count(*) from public.realtime_events where table_name = 'expenses' and op = 'insert') = 1,
               'only expenses the partner can see create changed events for them');
select test.ok((select amount from public.partner_expenses(now() - interval '1 day', now() + interval '1 day')) is null,
               'guessable amount is masked before guessing');
select id as expense_id from public.partner_expenses(now() - interval '1 day', now() + interval '1 day') \gset
select test.ok((select score_points from public.submit_guess(:'expense_id', 130)) = 80, 'guess within 10% scores 80');
select test.ok((select amount from public.partner_expenses(now() - interval '1 day', now() + interval '1 day')) = 135,
               'amount is revealed after guessing');
do $$ begin
  perform public.submit_guess((select id from public.partner_expenses(now() - interval '1 day', now() + interval '1 day')), 140);
  raise exception 'FAILED: second guess should be rejected';
exception when unique_violation then raise notice 'ok - each expense can be guessed once';
end $$;
select test.ok(test.outbox(test.id('zhe'), 'guess') = 1, 'owner is notified of the guess');
select test.login(test.id('zhe'));
update public.expenses set merchant = '花店（玫瑰）' where merchant = '花店';
update public.expenses set visibility = 'private' where merchant = '星巴克 信義新天地';
update public.expenses set visibility = 'shared' where merchant = '星巴克 信義新天地';
select test.login(test.id('yu'));
-- 3 筆是上面開啟分享、暫停、恢復時的「請重新抓開銷」，另外 2 筆是公開 → 私人 → 公開；改私人的那筆不通知
select test.ok((select count(*) from public.realtime_events where table_name = 'expenses' and op = 'update') = 5,
               'editing a private expense is silent; hiding or showing a shared one tells the partner');

-- ---------------------------------------------------------------- 心情與安撫
insert into public.moods (mood, note) values ('sad', '被主管念了');
select test.login(test.id('zhe'));
select test.ok((select mood from public.moods where user_id = test.id('yu')) = 'sad', 'partner mood is visible');
select test.ok(test.outbox(test.id('zhe'), 'mood') = 1, 'mood change notifies partner');
insert into public.comforts (couple_id, receiver_id, kind) values (public.my_couple_id(), test.id('yu'), 'hug');
select test.ok(test.outbox(test.id('yu'), 'comfort') = 1, 'comfort notifies the receiver');
do $$ begin
  insert into public.comforts (couple_id, receiver_id, kind) values (public.my_couple_id(), test.id('mia'), 'hug');
  raise exception 'FAILED: comfort to a non-partner should be rejected';
exception when insufficient_privilege then raise notice 'ok - cannot send comfort outside the couple';
end $$;
select test.ok(test.error_of($$insert into public.comforts (couple_id, receiver_id, kind, created_at)
                               values (public.my_couple_id(), test.id('yu'), 'hug', '2000-01-01')$$) like '42501:%',
               'comforts cannot be backdated to dodge the rate limit');
select test.ok(test.error_of($$insert into public.comforts (couple_id, sender_id, receiver_id, kind)
                               values (public.my_couple_id(), test.id('yu'), test.id('zhe'), 'hug')$$) like '42501:%',
               'the sender is always the signed-in user');
select test.ok(public.app_send_comfort('{"kind": "pat", "message": "辛苦了"}')->>'senderId' = test.id('zhe')::text,
               'app_send_comfort still works');
select test.login(test.id('yu'));
select test.ok(test.error_of($$update public.comforts set message = '我原諒你了'$$) like '42501:%',
               'the receiver cannot rewrite what the sender said');
select public.app_read_comfort(jsonb_build_object('id', (select min(id) from public.comforts)));
select test.ok((select read_at is not null from public.comforts order by id limit 1), 'the receiver can still mark comforts read');

-- 沒分享心情時，對方連「什麼時候記了心情」都不知道
update public.sharing_settings set share_mood = false where user_id = test.id('yu');
insert into public.moods (mood) values ('tired');
select test.login(test.id('zhe'));
select test.ok((select count(*) from public.realtime_events where table_name = 'moods' and op = 'insert') = 1,
               'unshared moods create no changed events for the partner');
select test.ok((select count(*) from public.realtime_events where table_name = 'moods' and op = 'update') = 1,
               'turning mood sharing off tells the partner to reload moods');
select test.login(test.id('yu'));
update public.sharing_settings set share_mood = true where user_id = test.id('yu');

-- ---------------------------------------------------------------- 經期分享
select test.login(test.id('yu'));
insert into public.cycle_periods (user_id, start_date, end_date) values (test.id('yu'), '2026-09-22', '2026-09-27');
select test.login(test.id('zhe'));
select test.ok((select count(*) from public.cycle_periods) = 0, 'cycle data is private until shared');
select test.ok((select count(*) from public.realtime_events where table_name = 'cycle_periods') = 0,
               'unshared period records create no changed events for the partner');
select test.login(test.id('yu'));
update public.sharing_settings set share_cycle = true where user_id = test.id('yu');
select test.login(test.id('zhe'));
select test.ok((select count(*) from public.realtime_events where table_name = 'cycle_periods') = 1,
               'turning cycle sharing on tells the partner to reload the cycle');
select test.login(test.id('yu'));
insert into public.cycle_logs (user_id, log_date, symptoms) values (test.id('yu'), '2026-09-22', '{cramps}');
select test.login(test.id('zhe'));
select test.ok((select count(*) from public.cycle_periods) = 1, 'shared cycle periods are visible');
select test.ok((select count(*) from public.cycle_logs) = 0, 'symptom logs stay private');
do $$ begin
  insert into public.cycle_periods (user_id, start_date) values (test.id('yu'), '2026-10-20');
  raise exception 'FAILED: partner should not edit periods without permission';
exception when insufficient_privilege then raise notice 'ok - partner cannot log periods unless allowed';
end $$;
select test.login(test.id('yu'));
select public.app_save_cycle_settings('{"partnerCanEdit": true, "careReminders": false}');
select test.ok(test.error_of($$select public.app_add_period('{"startDate": "infinity"}')$$) like '23514:%',
               'period dates cannot be infinity');
select test.login(test.id('zhe'));
select public.app_add_period('{"owner": "partner", "startDate": "2026-10-20"}') as helped \gset
select test.ok((:'helped'::jsonb)->>'userId' = test.id('yu')::text and (:'helped'::jsonb)->>'recordedBy' = test.id('zhe')::text,
               'partner can log a period when allowed');
select test.ok(public.app_add_period('{"owner": "partner", "startDate": "2026-10-20", "endDate": "2026-10-24"}')->>'endDate' = '2026-10-24',
               'partner can finish the period (upsert)');
select test.ok(public.app_update_period(jsonb_build_object('id', (:'helped'::jsonb)->>'id', 'endDate', '2026-10-25'))->>'endDate' = '2026-10-25',
               'partner can fix the dates with app_update_period');
select test.ok(test.error_of(format($$update public.cycle_periods set user_id = auth.uid() where id = %L$$, (:'helped'::jsonb)->>'id')) like '42501:%',
               'partner cannot move a period record to themselves');
select test.ok(test.error_of(format($$update public.cycle_periods set recorded_by = test.id('yu') where id = %L$$, (:'helped'::jsonb)->>'id')) like '42501:%',
               'recorded_by cannot be changed');
delete from public.cycle_periods where id = ((:'helped'::jsonb)->>'id')::uuid;
select test.login(test.id('yu'));
select test.ok((select count(*) from public.cycle_periods where user_id = test.id('yu')) = 2, 'partner cannot delete the owner''s periods');
select test.login(test.id('zhe'));

-- ---------------------------------------------------------------- 地點與地理圍欄
select public.my_couple_id() as couple_id \gset
insert into public.places (couple_id, name, lat, lng, radius_m, watch_user)
values (:'couple_id', '公司', 25.04, 121.56, 150, test.id('yu')) returning id as place_id \gset
select test.ok(test.outbox(test.id('yu'), 'sharing_changed') = 4, 'watched partner is told about the new place');
select public.app_create_place('{"name": "健身房", "lat": 25.05, "lng": 121.55, "watch": "me"}')->>'id' as gym \gset
select test.ok(test.outbox(test.id('yu'), 'sharing_changed') = 4, 'a place that only watches its creator is silent');
select public.app_update_place(jsonb_build_object('id', :'gym', 'watch', 'partner'));
select test.ok(test.outbox(test.id('yu'), 'sharing_changed') = 5 and test.outbox_titled(test.id('yu'), '阿哲 修改了地點「健身房」') = 1,
               'retargeting a place to watch the partner tells them');
select public.app_update_place(jsonb_build_object('id', :'gym', 'lat', 25.06));
select test.ok(test.outbox(test.id('yu'), 'sharing_changed') = 6, 'moving a watching place tells the watched partner');
select public.app_update_place(jsonb_build_object('id', :'gym', 'name', '健身房2', 'radiusM', 150, 'watch', 'partner'));
select test.ok(test.outbox(test.id('yu'), 'sharing_changed') = 6, 'renaming (no watch / position change) is silent');
select test.ok(test.error_of(format($$update public.places set created_by = test.id('yu') where id = %L$$, :'gym')) like '42501:%',
               'place creator cannot be changed');
select public.app_delete_place(jsonb_build_object('id', :'gym'));
select test.login(test.id('yu'));
do $$ begin
  insert into public.place_events (place_id, kind) values ((select id from public.places limit 1), 'arrive');
  raise exception 'FAILED: place events require location sharing';
exception when insufficient_privilege then raise notice 'ok - no geofence reports while location sharing is off';
end $$;
update public.sharing_settings set share_location = true where user_id = test.id('yu');
insert into public.place_events (place_id, kind) values (:'place_id', 'arrive');
select test.ok(test.outbox(test.id('zhe'), 'place') = 1, 'arrival notifies partner');
select test.ok((select count(*) from public.activity_log where kind = 'place_arrive') = 1, 'arrival is in the history');

-- ---------------------------------------------------------------- 約會
select test.login(test.id('zhe'));
insert into public.events (couple_id, title, starts_at) values (:'couple_id', '週六晚餐', '2026-10-17 18:30+08') returning id as event_id \gset
select test.login(test.id('yu'));
update public.events set status = 'confirmed' where id = :'event_id';
select test.ok(test.outbox(test.id('zhe'), 'event') = 1, 'creator is told the date was accepted');
select test.ok(test.error_of(format($$update public.events set created_by = auth.uid() where id = %L$$, :'event_id')) like '42501:%',
               'partner cannot take over (and then delete) an event');
select test.ok(test.error_of($$select public.app_create_event('{"title": "永遠", "startsAt": "infinity"}')$$) like '23514:%',
               'event times cannot be infinity');
select test.ok(test.error_of($$select public.app_create_anniversary('{"title": "永遠", "date": "infinity"}')$$) like '23514:%',
               'anniversary dates cannot be infinity');
select test.ok(public.app_update_event(jsonb_build_object('id', :'event_id', 'note', '記得訂位'))->>'note' = '記得訂位',
               'app_update_event still works for the partner');

-- ---------------------------------------------------------------- RPC：camelCase 輸出、錯誤代碼
select test.login(test.id('zhe'));
select public.app_me() as me \gset
select test.ok((:'me'::jsonb)->'user' ?& array['id', 'displayName', 'avatarUrl', 'birthday', 'info', 'email'], 'app_me user has camelCase keys and email');
select test.ok((:'me'::jsonb)#>>'{user,email}' = 'azhe@example.com', 'app_me returns the login email');
select test.ok((:'me'::jsonb)#>>'{partner,displayName}' = '小雨', 'app_me returns the partner');
select test.ok((:'me'::jsonb)->'mySharing' ?& array['userId', 'shareLocation', 'hiddenCategories', 'pausedUntil', 'updatedAt'],
               'app_me sharing rows are camelCased');
select test.ok((:'me'::jsonb)#>>'{couple,createdAt}' ~ '^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?\+00:00$', 'timestamps are ISO 8601 in UTC');
select test.ok((:'me'::jsonb)->'partnerOnline' = 'false', 'partner is offline without a recent device report');
select test.ok((public.app_update_profile('{"birthday": "1998-03-14"}'))->>'birthday' = '1998-03-14', 'dates are YYYY-MM-DD');
select test.ok(public.app_places()->0->>'watchUser' = test.id('yu')::text and public.app_places()->0 ? 'radiusM', 'app_places rows are camelCased');
select test.ok((select bool_and(e ? 'theirGuess' and e ? 'theirPoints' and e ? 'occurredAt') from jsonb_array_elements(public.app_expenses()) e)
               and jsonb_array_length(public.app_expenses()) = 4, 'app_expenses includes theirGuess / theirPoints');
select test.ok((select (e->>'theirGuess')::numeric = 130 and jsonb_typeof(e->'amount') = 'number'
                from jsonb_array_elements(public.app_expenses()) e where e->>'guessable' = 'true'), 'numbers stay JSON numbers');
select test.ok((select count(*) from jsonb_object_keys(public.app_notification_prefs())) = 12
               and public.app_notification_prefs() ?& array['diary', 'memo'], 'notification prefs list every kind');
select public.app_set_notification_prefs('{"diary": false}');
select test.ok(public.app_notification_prefs()->'diary' = 'false', 'diary notifications can be turned off');
select public.app_set_notification_prefs('{"diary": true}');
select test.ok((public.app_history('{"kinds": ["mood"]}')->0->'payload') ? 'note', 'nested jsonb (activity payload) keeps its keys');
select test.ok(test.error_of($$select public.app_update_profile('{}')$$) like 'P0001: nothing_to_update%', 'empty update raises nothing_to_update');
select test.ok(test.error_of($$select public.app_delete_place('{"id": "00000000-0000-0000-0000-000000000000"}')$$) like 'P0001: not_found%',
               'missing row raises not_found');
select test.ok(test.error_of($$select public.app_place_event('{"id": "00000000-0000-0000-0000-000000000000", "kind": "stay"}')$$) like 'P0001: invalid_kind%',
               'bad place event kind raises invalid_kind');
select test.ok(test.error_of($$select public.app_expenses('{"from": "yesterday-ish"}')$$) like 'P0001: invalid_range%', 'bad range raises invalid_range');
select test.ok(test.error_of($$select public.app_create_expense('{"amount": -1, "merchant": "x"}')$$) like '23514:%', 'check violations keep their SQLSTATE');
select test.ok((public.app_create_expense('{"amount": 50, "merchant": "7-11", "dedupeKey": "d1"}')) ? 'id'
               and public.app_create_expense('{"amount": 50, "merchant": "7-11", "dedupeKey": "d1"}') = '{"duplicate": true}',
               'duplicate auto-recorded expense returns {duplicate: true}');

-- ---------------------------------------------------------------- v2：個人資料（info）
select test.login(test.id('zhe'));
select test.ok(public.app_update_profile('{"info": {"heightCm": 175, "bloodType": "O", "likes": "珍奶半糖"}}')->'info'
               = '{"heightCm": 175, "bloodType": "O", "likes": "珍奶半糖"}', 'app_update_profile saves and returns info');
select test.ok((public.app_me())#>>'{user,info,likes}' = '珍奶半糖', 'app_me returns my info');
select test.ok(test.error_of($$select public.app_update_profile('{"info": ["O"]}')$$) like '23514:%', 'info must be a JSON object');
select test.ok(test.error_of(format('select public.app_update_profile(%L)', jsonb_build_object('info', jsonb_build_object('intro', repeat('好', 1500)))))
               like '23514:%', 'info is limited to 4000 bytes');
select test.login(test.id('yu'));
select test.ok((public.app_me())#>>'{partner,info,heightCm}' = '175', 'the partner sees my info with its keys untouched');
select test.ok((public.app_me())#>'{user,info}' = '{}', 'info defaults to an empty object');

-- ---------------------------------------------------------------- v2：只有自己看得到的備註
select test.login(test.id('zhe'));
select test.trace() as trace_before \gset
insert into public.partner_notes (about_user, body, created_at) values (test.id('yu'), '她喜歡半糖少冰', now() - interval '1 day')
returning id as note_id \gset
select test.ok(public.app_save_note(jsonb_build_object('id', :'note_id', 'body', '她喜歡半糖去冰'))->>'body' = '她喜歡半糖去冰', 'notes can be edited');
select public.app_save_note('{"body": "生日想要的耳環款式"}') as note \gset
select test.ok((:'note'::jsonb) ?& array['id', 'aboutUser', 'body', 'createdAt', 'updatedAt'] and not (:'note'::jsonb) ? 'ownerId'
               and (:'note'::jsonb)->>'aboutUser' = test.id('yu')::text, 'a new note is about the current partner');
select test.ok(jsonb_array_length(public.app_notes()) = 2 and public.app_notes()->0->>'body' = '生日想要的耳環款式', 'app_notes lists my notes, newest first');
select test.ok(test.error_of($$select public.app_save_note('{"body": ""}')$$) like '23514:%', 'empty notes are rejected');
select test.ok(not exists (select 1 from pg_trigger where tgrelid = 'public.partner_notes'::regclass and not tgisinternal)
               and not exists (select 1 from pg_publication_tables where schemaname = 'public' and tablename = 'partner_notes'),
               'partner_notes has no triggers and is not published to Realtime');
select test.login(test.id('yu'));
select test.ok((select count(*) from public.partner_notes) = 0, 'the partner cannot read or even count my notes');
select test.ok(public.app_notes() = '[]', 'app_notes shows the partner nothing');
select test.ok(test.error_of(format($$select public.app_save_note(%L)$$, jsonb_build_object('id', :'note_id', 'body', '偷看'))) like 'P0001: not_found%',
               'the partner cannot edit my note');
select test.ok(test.error_of(format($$select public.app_delete_note(%L)$$, jsonb_build_object('id', :'note_id'))) like 'P0001: not_found%',
               'the partner cannot delete my note');
update public.partner_notes set body = '偷改';
delete from public.partner_notes;
select test.ok(test.error_of(format($$insert into public.partner_notes (owner_id, body) values (%L, '假的')$$, test.id('zhe'))) like '42501:%',
               'the partner cannot write a note in my name');
select test.login(test.id('zhe'));
select test.ok((select string_agg(body, ',' order by created_at) from public.partner_notes) = '她喜歡半糖去冰,生日想要的耳環款式', 'my notes are untouched');
select test.ok(public.app_delete_note(jsonb_build_object('id', :'note_id')) = '{"ok": true}', 'app_delete_note deletes my note');
select test.ok(jsonb_array_length(public.app_notes()) = 1, 'and it is gone');
select test.ok(test.trace() = :'trace_before', 'notes create no realtime event, notification or history for anyone');

-- ---------------------------------------------------------------- v2：日曆紀錄
select public.app_save_day_entry(jsonb_build_object('day', '2026-10-10', 'body', '一起去淡水看夕陽', 'media', jsonb_build_array(
  jsonb_build_object('path', 'c/' || :'couple_id' || '/a1.jpg', 'kind', 'photo', 'width', 1600, 'height', 1200)))) as entry \gset
select test.ok((:'entry'::jsonb) ?& array['id', 'userId', 'day', 'body', 'media', 'createdAt', 'updatedAt'] and not (:'entry'::jsonb) ? 'coupleId'
               and (:'entry'::jsonb)->>'day' = '2026-10-10' and (:'entry'::jsonb)#>>'{media,0,width}' = '1600',
               'app_save_day_entry returns the entry with media as given');
select test.ok(test.outbox_body(test.id('yu'), '阿哲 記錄了 10/10 的生活') = '一起去淡水看夕陽', 'the partner is told about a new diary entry');
select public.app_save_day_entry(jsonb_build_object('day', '2026-10-11', 'media', jsonb_build_array(
  jsonb_build_object('path', 'c/' || :'couple_id' || '/v1.m4a', 'kind', 'voice', 'durationMs', 12000))));
select test.ok(test.outbox_body(test.id('yu'), '阿哲 記錄了 10/11 的生活') = '🎙️ 錄了一段語音', 'a voice-only entry says so in the notification');
select test.ok(test.error_of($$select public.app_save_day_entry('{"day": "2026-10-12", "body": ""}')$$) like '23514:%', 'empty entries are rejected');
select test.ok(test.changed(test.id('yu'), 'day_entries') = 2 and test.changed(test.id('zhe'), 'day_entries') = 2,
               'diary entries tell both of us to reload');
select test.login(test.id('yu'));
select public.app_save_day_entry(jsonb_build_object('day', '2026-10-10', 'body', '風好大但很開心')) as yu_entry \gset
select test.ok(test.outbox_body(test.id('zhe'), '小雨 記錄了 10/10 的生活') = '風好大但很開心', 'the other way around too');
select test.ok(jsonb_array_length(public.app_day_entries('{"from": "2026-10-01", "to": "2026-10-31"}')) = 3, 'both of us see every entry of the couple');
select test.ok(public.app_day_entries('{"from": "2026-10-11", "to": "2026-10-11"}')->0->>'day' = '2026-10-11'
               and jsonb_array_length(public.app_day_entries('{"from": "2026-10-11", "to": "2026-10-11"}')) = 1, 'from and to are inclusive days');
select test.ok(jsonb_typeof(public.app_day_entries()) = 'array', 'the default range works');
select test.ok(test.error_of($$select public.app_day_entries('{"from": "someday"}')$$) like 'P0001: invalid_range%', 'bad dates raise invalid_range');
select test.ok(test.error_of(format($$select public.app_save_day_entry(%L)$$, jsonb_build_object('id', (:'entry'::jsonb)->>'id', 'body', '偷改')))
               like 'P0001: not_found%', 'the partner cannot edit my entry');
select test.ok(test.error_of(format($$select public.app_delete_day_entry(%L)$$, jsonb_build_object('id', (:'entry'::jsonb)->>'id')))
               like 'P0001: not_found%', 'the partner cannot delete my entry');
update public.day_entries set body = '偷改' where user_id = test.id('zhe');
delete from public.day_entries where user_id = test.id('zhe');
select test.ok(test.error_of(format($$insert into public.day_entries (couple_id, user_id, day, body) values (%L, %L, '2026-10-12', '假的')$$,
                                    :'couple_id', test.id('zhe'))) like '42501:%', 'the partner cannot write an entry in my name');
select test.ok((select count(*) from public.day_entries where user_id = test.id('zhe') and body <> '偷改') = 2, 'my entries are untouched');
select test.ok(public.app_save_day_entry(jsonb_build_object('id', (:'yu_entry'::jsonb)->>'id', 'day', '2026-10-10', 'body', '風好大但很開心！',
                 'media', jsonb_build_array(jsonb_build_object('path', 'c/' || :'couple_id' || '/y1.jpg', 'kind', 'photo'))))#>>'{media,0,path}'
               = 'c/' || :'couple_id' || '/y1.jpg', 'I can edit my own entry');
-- 照片語音的路徑只能指到這對情侶的資料夾；直接寫資料表也一樣會檢查
select test.ok(test.error_of($$select public.app_save_day_entry('{"day": "2026-10-12", "media": [{"path": "c/11111111-1111-1111-1111-111111111111/x.jpg", "kind": "photo"}]}')$$)
               like 'P0001: invalid_media%', 'diary media cannot point at another couple''s folder');
select test.ok(test.error_of(format($$select public.app_save_day_entry(%L)$$, jsonb_build_object('day', '2026-10-12', 'media',
                 jsonb_build_array(jsonb_build_object('path', 'u/' || test.id('yu') || '/x.jpg', 'kind', 'photo'))))) like 'P0001: invalid_media%',
               'diary media cannot point at a private folder');
select test.ok(test.error_of(format($$select public.app_save_day_entry(%L)$$, jsonb_build_object('day', '2026-10-12', 'media',
                 jsonb_build_array(jsonb_build_object('path', 'c/' || :'couple_id' || '/../../u/' || test.id('zhe') || '/x.jpg', 'kind', 'photo')))))
               like 'P0001: invalid_media%', 'media paths cannot climb out of the folder');
select test.ok(test.error_of(format($$select public.app_save_day_entry(%L)$$, jsonb_build_object('day', '2026-10-12', 'media',
                 jsonb_build_array(jsonb_build_object('path', 'c/' || :'couple_id' || '/x.mp4', 'kind', 'video'))))) like 'P0001: invalid_media%',
               'media kind is photo or voice');
select test.ok(test.error_of(format($$select public.app_save_day_entry(%L)$$, jsonb_build_object('day', '2026-10-12', 'media',
                 jsonb_build_array('c/' || :'couple_id' || '/x.jpg')))) like 'P0001: invalid_media%', 'media items are objects');
select test.ok(test.error_of(format($$insert into public.day_entries (couple_id, day, media) values (%L, '2026-10-12', %L)$$, :'couple_id',
                 '[{"path": "c/11111111-1111-1111-1111-111111111111/x.jpg", "kind": "photo"}]')) like 'P0001: invalid_media%',
               'writing the table directly is checked too');
select test.ok(test.error_of(format($$update public.day_entries set media = %L where id = %L$$,
                 jsonb_build_array(jsonb_build_object('path', 'u/' || test.id('zhe') || '/x.jpg', 'kind', 'photo')), (:'yu_entry'::jsonb)->>'id'))
               like 'P0001: invalid_media%', 'updating the table directly is checked too');
select test.login(test.id('mia'));
select test.ok((select count(*) from public.day_entries) = 0 and public.app_day_entries('{"from": "2026-10-01", "to": "2026-10-31"}') = '[]',
               'outsiders see no diary entries');
select test.ok(test.error_of($$select public.app_save_day_entry('{"day": "2026-10-12", "body": "我也要"}')$$) like 'P0001: not_paired%',
               'diary entries need a partner');

-- ---------------------------------------------------------------- v2：備忘錄
select test.login(test.id('zhe'));
select public.app_save_memo(jsonb_build_object('title', '週末採買清單', 'body', '牛奶、雞蛋', 'pinned', true, 'color', 'yellow', 'media',
  jsonb_build_array(jsonb_build_object('path', 'c/' || :'couple_id' || '/m1.jpg', 'kind', 'photo')))) as shared_memo \gset
select test.ok((:'shared_memo'::jsonb) ?& array['id', 'userId', 'title', 'body', 'media', 'shared', 'pinned', 'color', 'createdAt', 'updatedAt']
               and not (:'shared_memo'::jsonb) ? 'coupleId' and (:'shared_memo'::jsonb)->>'shared' = 'true', 'memos are shared by default');
select test.ok(test.outbox_body(test.id('yu'), '阿哲 新增了共同備忘錄') = '週末採買清單', 'the partner is told about a new shared memo');
select test.ok(test.changed(test.id('yu'), 'memos') = 1 and test.changed(test.id('zhe'), 'memos') = 1, 'a shared memo tells both of us to reload');
select public.app_save_memo(jsonb_build_object('title', '生日驚喜計畫', 'body', '偷偷訂餐廳', 'shared', false, 'color', 'lavender', 'media',
  jsonb_build_array(jsonb_build_object('path', 'u/' || test.id('zhe') || '/p1.jpg', 'kind', 'photo')))) as private_memo \gset
select public.app_save_memo(jsonb_build_object('id', (:'private_memo'::jsonb)->>'id', 'body', '偷偷訂餐廳，記得蛋糕'));
select test.ok((:'private_memo'::jsonb)->>'shared' = 'false' and test.changed(test.id('zhe'), 'memos') = 3, 'my private memo changes tell me');
select test.ok(test.changed(test.id('yu'), 'memos') = 1 and test.outbox(test.id('yu'), 'memo') = 1, 'but tell the partner nothing');
select test.ok(test.error_of(format($$select public.app_save_memo(%L)$$, jsonb_build_object('title', 'x', 'shared', false, 'media',
                 jsonb_build_array(jsonb_build_object('path', 'c/' || :'couple_id' || '/x.jpg', 'kind', 'photo'))))) like 'P0001: invalid_media%',
               'a private memo cannot use the shared folder');
select test.ok(test.error_of(format($$select public.app_save_memo(%L)$$, jsonb_build_object('title', 'x', 'shared', false, 'media',
                 jsonb_build_array(jsonb_build_object('path', 'u/' || test.id('yu') || '/x.jpg', 'kind', 'photo'))))) like 'P0001: invalid_media%',
               'a memo cannot point at the partner''s private files');
select test.ok(test.error_of(format($$select public.app_save_memo(%L)$$, jsonb_build_object('title', 'x', 'media',
                 jsonb_build_array(jsonb_build_object('path', 'u/' || test.id('zhe') || '/x.jpg', 'kind', 'photo'))))) like 'P0001: invalid_media%',
               'a shared memo cannot point at my private files');
select test.ok(test.error_of($$select public.app_save_memo('{"title": "x", "media": [{"path": "c/11111111-1111-1111-1111-111111111111/x.jpg", "kind": "photo"}]}')$$)
               like 'P0001: invalid_media%', 'a shared memo cannot point at another couple''s folder');
select test.ok(test.error_of($$select public.app_save_memo('{"title": "x", "color": "black"}')$$) like '23514:%', 'unknown colors are rejected');
-- 改成私人時照片語音要搬到自己的資料夾（App 會重新上傳）
select test.ok(test.error_of(format($$select public.app_save_memo(%L)$$, jsonb_build_object('id', (:'shared_memo'::jsonb)->>'id', 'shared', false)))
               like 'P0001: invalid_media%', 'a memo cannot become private while its media sit in the shared folder');
select public.app_save_memo('{"title": "要一起看的電影"}') as movie_memo \gset
select test.ok(test.changed(test.id('yu'), 'memos') = 2 and test.outbox(test.id('yu'), 'memo') = 2, 'another shared memo');
select public.app_save_memo(jsonb_build_object('id', (:'movie_memo'::jsonb)->>'id', 'shared', false));
select test.ok(test.changed(test.id('yu'), 'memos') = 3, 'making a shared memo private tells the partner to drop it');
select public.app_save_memo(jsonb_build_object('id', (:'movie_memo'::jsonb)->>'id', 'body', '其實想自己先看'));
select test.ok(test.changed(test.id('yu'), 'memos') = 3 and test.changed(test.id('zhe'), 'memos') = 6, 'later edits of the now-private memo are silent');
select test.login(test.id('yu'));
select test.ok((select count(*) from public.memos) = 1 and jsonb_array_length(public.app_memos()) = 1 and public.app_memos()->0->>'title' = '週末採買清單',
               'the partner sees my shared memo but none of my private ones');
select test.ok(test.error_of(format($$select public.app_save_memo(%L)$$, jsonb_build_object('id', (:'private_memo'::jsonb)->>'id', 'title', '偷看')))
               like 'P0001: not_found%', 'the partner cannot edit my private memo');
select public.app_save_memo(jsonb_build_object('id', (:'shared_memo'::jsonb)->>'id', 'body', '牛奶、雞蛋、吐司', 'shared', false, 'pinned', false)) as edited \gset
select test.ok((:'edited'::jsonb)->>'body' = '牛奶、雞蛋、吐司' and (:'edited'::jsonb)->>'shared' = 'true' and (:'edited'::jsonb)->>'pinned' = 'false'
               and (:'edited'::jsonb)->>'userId' = test.id('zhe')::text, 'the partner can edit a shared memo but not make it private');
select test.ok(test.changed(test.id('zhe'), 'memos') = 7, 'the creator is told when the partner edits a shared memo');
select test.ok(test.error_of(format($$select public.app_save_memo(%L)$$, jsonb_build_object('id', (:'shared_memo'::jsonb)->>'id', 'media',
                 jsonb_build_array(jsonb_build_object('path', 'u/' || test.id('yu') || '/x.jpg', 'kind', 'photo'))))) like 'P0001: invalid_media%',
               'the partner cannot attach private files to a shared memo');
-- 被 Row Level Security 擋下，或照片還在共用資料夾先被路徑檢查擋下
select test.ok(test.error_of(format($$update public.memos set shared = false where id = %L$$, (:'shared_memo'::jsonb)->>'id'))
               ~ '^(42501: new row violates row-level security|P0001: invalid_media)', 'the partner cannot make my memo private directly');
select test.ok(test.error_of(format($$update public.memos set shared = false, media = '[]' where id = %L$$, (:'shared_memo'::jsonb)->>'id'))
               like '42501: new row violates row-level security%', 'not even after removing its media');
select test.ok(test.error_of(format($$update public.memos set couple_id = null where id = %L$$, (:'shared_memo'::jsonb)->>'id')) like '42501:%',
               'the partner cannot move my memo out of the couple');
select test.ok(test.error_of(format($$update public.memos set user_id = auth.uid() where id = %L$$, (:'shared_memo'::jsonb)->>'id')) like '42501:%',
               'the partner cannot take over my memo');
select test.ok(test.error_of(format($$select public.app_delete_memo(%L)$$, jsonb_build_object('id', (:'shared_memo'::jsonb)->>'id')))
               like 'P0001: not_found%', 'the partner cannot delete my shared memo');
delete from public.memos;
select test.ok(test.error_of(format($$insert into public.memos (user_id, title) values (%L, '假的')$$, test.id('zhe'))) like '42501:%',
               'the partner cannot write a memo in my name');
select test.ok(test.error_of($$insert into public.memos (title, couple_id) values ('別人的', '11111111-1111-1111-1111-111111111111')$$) like '42501:%',
               'a shared memo must belong to my couple');
select test.login(test.id('zhe'));
select test.ok((select count(*) from public.memos where user_id = test.id('zhe')) = 3, 'my memos are all still there');
select public.app_save_memo(jsonb_build_object('id', (:'private_memo'::jsonb)->>'id', 'pinned', true));
select test.ok(public.app_memos()->0->>'id' = (:'private_memo'::jsonb)->>'id', 'pinned memos come first');
select test.ok(public.app_delete_memo(jsonb_build_object('id', (:'movie_memo'::jsonb)->>'id')) = '{"ok": true}', 'the creator can delete a memo');

-- ---------------------------------------------------------------- v2：照片與語音（Storage）
-- storage-api 以登入者的身分寫入 storage.objects，這裡直接寫來測 policy
insert into storage.objects (bucket_id, name, owner_id) values
  ('media', 'c/' || :'couple_id' || '/s1.jpg', auth.uid()::text), ('media', 'u/' || test.id('zhe') || '/s2.jpg', auth.uid()::text);
select test.ok(test.error_of(format($$insert into storage.objects (bucket_id, name) values ('media', %L)$$, 'u/' || test.id('yu') || '/s3.jpg'))
               like '42501:%', 'cannot upload into the partner''s private folder');
select test.ok(test.error_of($$insert into storage.objects (bucket_id, name) values ('media', 'c/11111111-1111-1111-1111-111111111111/s4.jpg')$$)
               like '42501:%', 'cannot upload into another couple''s folder');
select test.ok(test.error_of(format($$insert into storage.objects (bucket_id, name) values ('media', %L)$$,
                 'c/' || :'couple_id' || '/../../u/' || test.id('yu') || '/s5.jpg')) like '42501:%', 'upload paths cannot climb out of the folder');
select test.ok(test.error_of(format($$insert into storage.objects (bucket_id, name) values ('media', %L)$$, 'c/' || :'couple_id' || '/a/s6.jpg'))
               like '42501:%', 'uploads go straight into the folder');
select test.login(test.id('yu'));
insert into storage.objects (bucket_id, name, owner_id) values ('media', 'c/' || :'couple_id' || '/y1.jpg', auth.uid()::text);
select test.ok((select string_agg(name, ',' order by name) from storage.objects where bucket_id = 'media')
               = 'c/' || :'couple_id' || '/s1.jpg,c/' || :'couple_id' || '/y1.jpg', 'the partner reads the couple folder but not my private folder');
select set_config('storage.allow_delete_query', 'true', true);
delete from storage.objects where bucket_id = 'media' and owner_id = test.id('zhe')::text;
select test.login(test.id('mia'));
select test.ok((select count(*) from storage.objects where bucket_id = 'media') = 0, 'outsiders read no media');
select test.login(test.id('zhe'));
delete from storage.objects where name = 'c/' || :'couple_id' || '/y1.jpg';
select test.ok((select count(*) from storage.objects where bucket_id = 'media') = 3, 'only the uploader can delete a file');
select test.login(test.id('yu'));
delete from storage.objects where name = 'c/' || :'couple_id' || '/y1.jpg';
select test.ok((select count(*) from storage.objects where bucket_id = 'media') = 1, 'the uploader can delete a file');
select test.login(test.id('zhe'));

-- ---------------------------------------------------------------- 即時事件與通知補送
select test.ok((select count(*) from public.realtime_events where kind = 'changed' and table_name = 'expenses' and by_user = test.id('zhe')) > 0,
               'a data change creates changed events for me');
select test.login(test.id('yu'));
select test.ok((select count(*) from public.realtime_events where kind = 'changed' and table_name = 'expenses' and by_user = test.id('zhe')) > 0,
               'the partner gets the same changed event');
select test.ok((select bool_and(user_id = test.id('yu')) from public.realtime_events), 'realtime events are only visible to their recipient');
select test.ok((select bool_and(recipient_id = test.id('yu')) from public.notification_outbox), 'notifications are only visible to their recipient');
select test.ok(public.app_locate_partner() = '{"sent": true}', 'locate request is sent');
select test.login(test.id('zhe'));
select test.ok((select count(*) from public.realtime_events where kind = 'locate' and by_user = test.id('yu')) = 1, 'locate reaches the partner');
select test.login(test.id('mia'));
select test.ok((select count(*) from public.realtime_events where table_name = 'expenses') = 0, 'outsiders get no changed events');
select test.ok(test.error_of($$select public.app_locate_partner()$$) like 'P0001: not_paired%', 'locate without a partner raises not_paired');
select test.login(test.id('yu'));
select jsonb_array_length(public.app_pending_notifications()) as pending_before \gset
select test.ok(:pending_before >= 5 and public.app_pending_notifications()->0 ?& array['id', 'kind', 'title', 'body', 'data', 'createdAt'],
               'pending notifications have the WebSocket message shape');
select public.app_ack_notifications(jsonb_build_object('ids',
  (select jsonb_agg(id) from (select id from public.notification_outbox where sent_at is null order by id limit 2) x)));
select test.ok(jsonb_array_length(public.app_pending_notifications()) = :pending_before - 2, 'acked notifications are not sent again');
select test.login(test.id('zhe'));
select (select count(*) from public.notification_outbox where sent_at is null) as zhe_pending \gset
select test.login(test.id('yu'));
select public.app_ack_notifications(jsonb_build_object('ids', jsonb_build_array(
  (select max(id) from public.notification_outbox) + 1, (select max(id) from public.notification_outbox) + 2)));
select test.login(test.id('zhe'));
select test.ok((select count(*) from public.notification_outbox where sent_at is null) = :zhe_pending, 'cannot ack someone else''s notifications');
select test.ok(test.error_of($$insert into public.realtime_events (user_id, kind) values (auth.uid(), 'locate')$$) like '42501:%',
               'clients cannot write realtime events directly');
select test.ok(test.error_of($$update public.notification_outbox set sent_at = now()$$) is null
               and (select count(*) from public.notification_outbox where sent_at is not null) = 0,
               'clients cannot mark notifications sent directly');

-- ---------------------------------------------------------------- 解除配對
select test.login(test.id('zhe'));
select public.app_save_memo('{"title": "一起存錢去日本"}') as japan_memo \gset
select test.login(test.id('yu'));
select public.unpair();
select test.ok(public.partner_id() is null, 'no partner after unpairing');
select test.login(test.id('zhe'));
select test.ok((select count(*) from public.moods) = 0, 'former partner mood is no longer visible');
select test.ok((select count(*) from public.places) = 0, 'shared places are no longer visible');
select test.ok((select count(*) from public.profiles) = 1, 'former partner profile is no longer visible');
select test.ok((public.app_me())->'partner' = 'null' and (public.app_me())->'couple' = 'null', 'app_me has null couple and partner after unpairing');
select test.ok(public.app_day_entries('{"from": "2026-10-01", "to": "2026-10-31"}') = '[]', 'diary entries are no longer visible after unpairing');
select test.ok((select count(*) from storage.objects where bucket_id = 'media') = 1, 'the old couple folder is no longer readable (only my own folder is)');
select test.ok(public.app_notes() = '[]', 'notes about the former partner are hidden');
select test.ok(test.error_of($$select public.app_save_day_entry('{"day": "2026-10-20", "body": "一個人"}')$$) like 'P0001: not_paired%',
               'diary entries need a partner');
select test.ok((public.app_save_memo('{"title": "一個人的清單", "shared": true}'))->>'shared' = 'false', 'memos are private while unpaired');
select test.login(test.id('yu'));
select test.ok(not exists (select 1 from jsonb_array_elements(public.app_memos()) m where m->>'userId' = test.id('zhe')::text)
               and (select count(*) from public.memos where user_id = test.id('zhe')) = 0, 'shared memos of the former partner are no longer visible');

-- ---------------------------------------------------------------- 匿名（沒登入）
reset role;
select set_config('request.jwt.claims', '', true);
set local role anon;
select test.ok(public.app_health()->>'ok' = 'true', 'anon can call app_health');
select test.ok(test.error_of($$select public.app_me()$$) like '42501:%', 'anon cannot call app_me');
select test.ok(test.error_of($$select count(*) from public.profiles$$) like '42501:%', 'anon cannot read tables');
reset role;

-- ---------------------------------------------------------------- 函式權限
select test.ok((select array_agg(p.proname::text) from pg_proc p
                where p.pronamespace = 'public'::regnamespace and has_function_privilege('anon', p.oid, 'execute')) = '{app_health}',
               'anon can execute only app_health');
select test.ok((select bool_and(has_function_privilege('authenticated', p.oid, 'execute')) from pg_proc p
                where p.pronamespace = 'public'::regnamespace and p.proname like 'app\_%' and p.proname not like 'app\_job\_%'),
               'authenticated can execute every app_* RPC');
select test.ok(not exists (select 1 from pg_proc p where p.pronamespace = 'public'::regnamespace
                           and p.proname in ('app_job_event_reminders', 'app_job_daily', 'app_job_cycle_care', 'app_job_cleanup',
                                             'predict_cycle', 'enqueue_notification', 'log_activity', 'daily_reminders',
                                             'couple_of', 'partner_of', 'shares', 'display_name_of', 'handle_new_user', 'tg_changed',
                                             'expense_shown', 'tg_couple_member_deleted', 'tg_place_saved',
                                             'media_valid', 'media_summary', 'tg_media_valid', 'tg_day_entry_inserted',
                                             'tg_memo_inserted', 'tg_changed_memo')
                           and has_function_privilege('authenticated', p.oid, 'execute')),
               'jobs and internal helpers are not callable by clients');
select test.ok(not exists (select 1 from pg_proc p where p.pronamespace = 'public'::regnamespace and p.prosecdef
                           and not exists (select 1 from unnest(p.proconfig) c where c = 'search_path=public')),
               'every security definer function pins search_path = public');
select test.ok(not exists (select 1 from pg_proc p where p.pronamespace = 'public'::regnamespace and p.proname like 'app\_%'
                           and p.proname not like 'app\_job\_%'
                           and (pg_get_function_identity_arguments(p.oid) <> 'jsonb'
                                or not exists (select 1 from unnest(p.proconfig) c where c = 'TimeZone=UTC'))),
               'every app_* RPC takes one jsonb argument and runs in UTC');
select test.ok((select not public and file_size_limit = 10485760 and allowed_mime_types @> array['image/jpeg', 'audio/mp4']
                from storage.buckets where id = 'media'), 'the media bucket is private, 10 MB, photos and voice only');
select test.ok((select array_agg(cmd order by cmd) from pg_policies where schemaname = 'storage' and policyname like 'xlk media:%')
               = '{DELETE,INSERT,SELECT}', 'media files can be read, uploaded and deleted but never overwritten');
select test.ok(not exists (select 1 from pg_trigger where tgrelid = 'public.memos'::regclass and tgfoid = 'public.tg_changed'::regproc),
               'memos do not use tg_changed (it would tell the partner about private memos)');

-- ---------------------------------------------------------------- 解除配對 30 天後清除（daily_reminders 的 delete from couples）
delete from public.couples where id = :'couple_id';
select test.ok(not exists (select 1 from public.day_entries where couple_id = :'couple_id'), 'diary entries are removed with the couple');
select test.ok((select count(*) from public.memos where user_id = test.id('zhe') and couple_id is null and shared) = 2,
               'shared memos stay with their creator (the media check does not block the cleanup)');

-- ---------------------------------------------------------------- 每日提醒（以 postgres 執行）
insert into public.couples (id, together_since) values ('11111111-1111-1111-1111-111111111111', '2025-05-20');
insert into public.couple_members (couple_id, user_id) values
  ('11111111-1111-1111-1111-111111111111', test.id('zhe')), ('11111111-1111-1111-1111-111111111111', test.id('mia'));
insert into public.anniversaries (couple_id, created_by, title, date, kind) values
  ('11111111-1111-1111-1111-111111111111', test.id('zhe'), 'Mia 生日', '1999-10-22', 'birthday'),
  ('11111111-1111-1111-1111-111111111111', test.id('zhe'), '閏日紀念', '2024-02-29', 'custom');
select test.ok(public.next_yearly('2024-02-29', '2026-01-01') = '2026-02-28', 'Feb 29 falls back to Feb 28');
select test.ok(public.daily_reminders('2026-10-15') = 2, 'a birthday 7 days away reminds both members');
select test.ok(public.daily_reminders('2026-10-21') = 4, 'day 520 together plus 1-day birthday reminder');

select test.ok((public.app_job_daily('2030-10-22'))->>'reminders' = '2', 'the daily job sends the birthday reminders');
select test.ok(public.app_job_daily('2030-10-22') is null, 'the daily job runs only once per day');
select test.ok(public.app_job_daily('2030-10-22', true) is not null, 'the daily job can be forced');

-- ---------------------------------------------------------------- 約會提醒排程
insert into public.events (couple_id, created_by, title, starts_at, status, remind_minutes)
values ('11111111-1111-1111-1111-111111111111', test.id('zhe'), '吃火鍋', now() + interval '30 minutes', 'confirmed', '{60}'),
       ('11111111-1111-1111-1111-111111111111', test.id('zhe'), '看電影', now() + interval '3 hours', 'confirmed', '{60}'),
       ('11111111-1111-1111-1111-111111111111', test.id('zhe'), '還沒答應', now() + interval '10 minutes', 'proposed', '{60}');
select test.ok(public.app_job_event_reminders() = 1, 'only confirmed events inside the reminder window are reminded');
select test.ok(public.app_job_event_reminders() = 0, 'each event is reminded once');
select test.ok((select count(*) from public.notification_outbox where kind = 'event' and title = '約會提醒：吃火鍋'
                and recipient_id in (test.id('zhe'), test.id('mia'))) = 2, 'both members get the date reminder');
select test.ok((select body from public.notification_outbox where title = '約會提醒：吃火鍋' limit 1)
               = to_char((now() + interval '30 minutes') at time zone 'Asia/Taipei', 'FMMM/FMDD HH24:MI') || ' 開始，準備出門囉',
               'reminder body shows the local start time');
select test.ok(to_char(timestamptz '2026-01-05 01:07+00' at time zone 'Asia/Taipei', 'FMMM/FMDD HH24:MI') = '1/5 09:07'
               and to_char(timestamptz '2026-10-15 16:05+00' at time zone 'Asia/Taipei', 'FMMM/FMDD HH24:MI') = '10/16 00:05',
               'time format matches Intl.DateTimeFormat(zh-TW)');

-- ---------------------------------------------------------------- 經期關懷提醒
update public.sharing_settings set share_cycle = true where user_id = test.id('mia');
insert into public.cycle_settings (user_id) values (test.id('mia'));
insert into public.cycle_periods (user_id, start_date, end_date, recorded_by) values
  (test.id('mia'), '2026-08-20', '2026-08-24', test.id('mia')),
  (test.id('mia'), '2026-09-17', '2026-09-21', test.id('mia'));
select test.ok(public.app_job_cycle_care('2026-10-12') = 0, 'no care reminder 3 days before');
select test.ok(public.app_job_cycle_care('2026-10-13') = 1, 'care reminder 2 days before the predicted period');
select test.ok((select title from public.notification_outbox where kind = 'cycle_care' and recipient_id = test.id('zhe'))
               = 'Mia 的經期預計 2 天後開始', 'care reminder goes to the partner with the expected text');
select test.ok((select data->>'next_start' from public.notification_outbox where kind = 'cycle_care') = '2026-10-15', 'care reminder carries next_start');
select test.ok(public.app_job_cycle_care('2026-10-14') = 0, 'the same period is reminded only once');
update public.cycle_settings set care_sent_for = null where user_id = test.id('mia');
select test.ok(public.app_job_cycle_care('2026-10-14') = 1, 'reminded again after care_sent_for is cleared');
select test.ok((select count(*) from public.notification_outbox where title = 'Mia 的經期預計 1 天後開始') = 1, 'one day before says 1 天後');
update public.cycle_settings set care_sent_for = null where user_id = test.id('mia');
-- 和 core.predictCycle 相同：預測日當天已算進這個週期，下次經期變成下一個週期
select test.ok(public.app_job_cycle_care('2026-10-15') = 0, 'on the predicted day the next period moves to the next cycle');
select test.ok(public.app_job_cycle_care('2026-10-18') = 0, 'no reminder when the period is late');
update public.sharing_settings set share_cycle = false where user_id = test.id('mia');
select test.ok(public.app_job_cycle_care('2026-10-13') = 0, 'no reminder when cycle sharing is off');
select test.ok((select row(next_start, days_until_next, late_days, cycle_day, ovulation)::text
                from public.predict_cycle('{2026-08-20,2026-09-17}', '{2026-08-24,2026-09-21}', null, null, null, '2026-10-18'))
               = '(2026-10-15,-3,3,32,2026-10-01)', 'predict_cycle reports late periods like core.predictCycle');
select test.ok((select next_start from public.predict_cycle('{}', '{}', null, null, null, '2026-10-18')) is null, 'no history, no prediction');

-- ---------------------------------------------------------------- 清理
insert into public.realtime_events (user_id, kind, created_at) values (test.id('zhe'), 'locate', now() - interval '2 days');
insert into public.notification_outbox (recipient_id, kind, title, body, created_at, sent_at)
values (test.id('zhe'), 'mood', 'old', 'old', now() - interval '40 days', now() - interval '40 days');
select test.ok((public.app_job_cleanup())->>'realtimeEvents' = '1', 'old realtime events are removed');
select test.ok(not exists (select 1 from public.notification_outbox where title = 'old'), 'old notifications are removed');
select test.ok((select count(*) from cron.job where jobname in ('xlk-minutely', 'xlk-daily', 'xlk-cleanup')) = 3, 'pg_cron jobs are scheduled');

-- ---------------------------------------------------------------- v2：和以前的配對共用的備忘錄
-- zhe 現在和 mia 是一對（每日提醒那一段建立的）；和小雨共用過的備忘錄只剩自己看得到
set local role authenticated;
select test.login(test.id('zhe'));
select test.ok((select count(*) filter (where m->>'shared' = 'false') from jsonb_array_elements(public.app_memos()) m
                where m->>'id' in ((:'shared_memo'::jsonb)->>'id', (:'japan_memo'::jsonb)->>'id')) = 2,
               'memos shared with a former partner show as private');
-- 直接打 API 把它搬到新的配對：照片還在以前那對的資料夾，一樣擋下來
select test.ok(test.error_of(format($$update public.memos set couple_id = %L where id = %L$$,
                 '11111111-1111-1111-1111-111111111111', (:'shared_memo'::jsonb)->>'id')) like 'P0001: invalid_media%',
               'moving a memo to the new couple directly checks its media too');
select public.app_save_memo(jsonb_build_object('id', (:'japan_memo'::jsonb)->>'id', 'body', '一個人也要去')) as japan \gset
select test.ok((:'japan'::jsonb)->>'shared' = 'false', 'editing one does not share it with the new partner');
select public.app_save_memo(jsonb_build_object('id', (:'shared_memo'::jsonb)->>'id', 'body', '牛奶')) as old_list \gset
select test.ok((:'old_list'::jsonb)->>'shared' = 'false' and (:'old_list'::jsonb)->'media' = '[]',
               'media left in the old couple folder (nobody can read it anymore) is dropped');
select test.login(test.id('mia'));
select test.ok(not exists (select 1 from public.memos where user_id = test.id('zhe'))
               and public.app_memos() = '[]', 'the new partner sees none of them');
select test.login(test.id('zhe'));
select public.app_save_memo(jsonb_build_object('id', (:'japan_memo'::jsonb)->>'id', 'shared', true));
select test.login(test.id('mia'));
select test.ok(public.app_memos()->0->>'title' = '一起存錢去日本' and jsonb_array_length(public.app_memos()) = 1,
               'until it is shared again on purpose');
-- 什麼都沒寫的備忘錄、只有空白的日曆紀錄，通知也要有內容
select test.login(test.id('zhe'));
select public.app_save_memo('{}');
select test.ok(test.outbox_body(test.id('mia'), '阿哲 新增了共同備忘錄') = '打開看看吧', 'an empty memo still has a notification body');
select public.app_save_day_entry('{"day": "2026-10-20", "body": "  "}');
select test.ok(test.outbox_body(test.id('mia'), '阿哲 記錄了 10/20 的生活') = '打開看看吧', 'so does a diary entry of only spaces');
reset role;

-- ---------------------------------------------------------------- 刪除帳號（Supabase 後台 › Authentication › Users）
-- zhe 和 mia 在上面的每日提醒測試裡是一對；刪掉 mia 後 zhe 不能卡在「只剩一個人」的配對裡
delete from auth.users where id = test.id('mia');
select test.ok((select left_at is not null from public.couple_members
                where couple_id = '11111111-1111-1111-1111-111111111111' and user_id = test.id('zhe')),
               'the remaining member is unpaired when the partner account is deleted');
select test.ok((select ended_at is not null from public.couples where id = '11111111-1111-1111-1111-111111111111'),
               'the couple is ended');
select test.ok((select count(*) from public.notification_outbox where recipient_id = test.id('zhe') and kind = 'pairing'
                and body like '對方的帳號已經刪除%') = 1, 'the remaining member is told');
set local role authenticated;
select test.login(test.id('zhe'));
select test.ok((public.app_me())->'couple' = 'null', 'app_me shows no couple');
select test.ok(public.create_invite() ~ '^[A-HJ-NP-Z2-9]{6}$', 'the remaining member can pair again');
reset role;

rollback;
