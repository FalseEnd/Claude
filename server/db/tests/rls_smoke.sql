-- 配對、分享權限與通知的冒煙測試。任何一個 test.ok() 失敗都會中止並顯示訊息。
\set ON_ERROR_STOP on
\set QUIET on

create schema test;
grant usage on schema test to authenticated;

create function test.ok(cond boolean, msg text) returns void language plpgsql as $$
begin
  if cond is distinct from true then raise exception 'FAILED: %', msg; end if;
  raise notice 'ok - %', msg;
end $$;

create function test.login(p_user uuid) returns void language sql as $$
  select set_config('request.jwt.claim.sub', p_user::text, false);
$$;

create function test.id(p_name text) returns uuid language sql immutable as $$
  select case p_name
    when 'zhe'  then '00000000-0000-0000-0000-00000000000a'
    when 'yu'   then '00000000-0000-0000-0000-00000000000b'
    when 'mia'  then '00000000-0000-0000-0000-00000000000c' end::uuid
$$;

-- 測試結束時用 superuser 看 outbox
create function test.outbox(p_user uuid, p_kind text) returns bigint
language sql security definer as $$
  select count(*) from public.notification_outbox where recipient_id = p_user and kind = p_kind
$$;
grant execute on all functions in schema test to authenticated;

insert into auth.users (id, username, password_hash) values
  (test.id('zhe'), 'azhe', 'x'), (test.id('yu'), 'xiaoyu', 'x'), (test.id('mia'), 'miamia', 'x');

set role authenticated;

-- ---------------------------------------------------------------- 配對
select test.login(test.id('zhe'));
insert into public.profiles (id, display_name) values (test.id('zhe'), '阿哲');
select public.create_invite() as code \gset
select test.ok(:'code' ~ '^[A-HJ-NP-Z2-9]{6}$', 'invite code uses the unambiguous alphabet');

select test.login(test.id('yu'));
insert into public.profiles (id, display_name) values (test.id('yu'), '小雨');
select test.ok(public.accept_invite(lower(:'code')) is not null, 'partner accepts the invite (case-insensitive)');
select test.ok(public.partner_id() = test.id('zhe'), 'partner_id resolves after pairing');

select test.login(test.id('mia'));
insert into public.profiles (id, display_name) values (test.id('mia'), 'Mia');
do $$ begin
  perform public.accept_invite((select code from public.pair_invites limit 1));
  raise exception 'FAILED: used invite should be rejected';
exception when others then
  if sqlerrm like 'FAILED%' then raise; end if;
  raise notice 'ok - a used invite cannot be accepted again (%)', sqlerrm;
end $$;
select test.ok((select count(*) from public.profiles) = 1, 'outsider only sees own profile');

-- ---------------------------------------------------------------- 分享與暫停
select test.login(test.id('zhe'));
update public.sharing_settings set share_location = true, share_device = true, share_expenses = true
 where user_id = test.id('zhe');
insert into public.device_status (user_id, battery_level, is_charging, network) values (test.id('zhe'), 60, false, 'wifi');
insert into public.locations (user_id, lat, lng, recorded_at) values (test.id('zhe'), 25.033, 121.565, now());

select test.login(test.id('yu'));
select test.ok((select count(*) from public.device_status) = 1, 'partner sees shared device status');
select test.ok((select count(*) from public.locations) = 1, 'partner sees shared location');
select test.ok((select count(*) from public.profiles) = 2, 'partner sees both profiles');

select test.login(test.id('mia'));
select test.ok((select count(*) from public.device_status) = 0, 'outsider sees no device status');

select test.login(test.id('zhe'));
select public.pause_sharing(60);
select test.login(test.id('yu'));
select test.ok((select count(*) from public.locations) = 0, 'paused location is hidden');
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

select test.login(test.id('yu'));
select test.ok((select count(*) from public.expenses) = 0, 'partner cannot read the expenses table directly');
select test.ok((select count(*) from public.partner_expenses(now() - interval '1 day', now() + interval '1 day')) = 1,
               'private and not-yet-revealed surprise expenses are hidden');
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

-- ---------------------------------------------------------------- 經期分享
select test.login(test.id('yu'));
insert into public.cycle_periods (user_id, start_date, end_date) values (test.id('yu'), '2026-09-22', '2026-09-27');
select test.login(test.id('zhe'));
select test.ok((select count(*) from public.cycle_periods) = 0, 'cycle data is private until shared');
select test.login(test.id('yu'));
update public.sharing_settings set share_cycle = true where user_id = test.id('yu');
insert into public.cycle_logs (user_id, log_date, symptoms) values (test.id('yu'), '2026-09-22', '{cramps}');
select test.login(test.id('zhe'));
select test.ok((select count(*) from public.cycle_periods) = 1, 'shared cycle periods are visible');
select test.ok((select count(*) from public.cycle_logs) = 0, 'symptom logs stay private');
do $$ begin
  insert into public.cycle_periods (user_id, start_date) values (test.id('yu'), '2026-10-20');
  raise exception 'FAILED: partner should not edit periods without permission';
exception when insufficient_privilege then raise notice 'ok - partner cannot log periods unless allowed';
end $$;

-- ---------------------------------------------------------------- 地點與地理圍欄
select public.my_couple_id() as couple_id \gset
insert into public.places (couple_id, name, lat, lng, radius_m, watch_user)
values (:'couple_id', '公司', 25.04, 121.56, 150, test.id('yu')) returning id as place_id \gset
select test.ok(test.outbox(test.id('yu'), 'sharing_changed') = 4, 'watched partner is told about the new place');
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

-- ---------------------------------------------------------------- 解除配對
select public.unpair();
select test.ok(public.partner_id() is null, 'no partner after unpairing');
select test.login(test.id('zhe'));
select test.ok((select count(*) from public.moods) = 0, 'former partner mood is no longer visible');
select test.ok((select count(*) from public.places) = 0, 'shared places are no longer visible');
select test.ok((select count(*) from public.profiles) = 1, 'former partner profile is no longer visible');

reset role;

-- ---------------------------------------------------------------- 每日提醒（以 superuser 執行）
insert into public.couples (id, together_since) values ('11111111-1111-1111-1111-111111111111', '2025-05-20');
insert into public.couple_members (couple_id, user_id) values
  ('11111111-1111-1111-1111-111111111111', test.id('zhe')), ('11111111-1111-1111-1111-111111111111', test.id('mia'));
insert into public.anniversaries (couple_id, created_by, title, date, kind) values
  ('11111111-1111-1111-1111-111111111111', test.id('zhe'), 'Mia 生日', '1999-10-22', 'birthday'),
  ('11111111-1111-1111-1111-111111111111', test.id('zhe'), '閏日紀念', '2024-02-29', 'custom');
select test.ok(public.next_yearly('2024-02-29', '2026-01-01') = '2026-02-28', 'Feb 29 falls back to Feb 28');
select test.ok(public.daily_reminders('2026-10-15') = 2, 'a birthday 7 days away reminds both members');
select test.ok(public.daily_reminders('2026-10-21') = 4, 'day 520 together plus 1-day birthday reminder');
