-- =============================================================================
-- 小倆口 資料庫結構（Supabase）
--
-- auth.users、auth.uid()、anon / authenticated 角色都由 Supabase 提供。
-- App 直接用 Supabase Auth 登入，再呼叫 app_* RPC（見 20261015000003_api.sql）。
-- 原則：
--   1. 每一筆個人資料（位置、電量、開銷、心情、經期）都屬於「擁有者」，
--      對方能不能看，由擁有者的 sharing_settings 決定，並且可以隨時暫停。
--   2. 兩人共用的資料（地點、紀念日、約會、歷史紀錄）掛在 couple 底下。
--   3. 解除配對後，所有共享立即失效（helper 只認 left_at is null 的配對）。
--   4. 通知一律先寫入 notification_outbox，App 經由 Supabase Realtime 收到（見 20261015000002_realtime.sql）。
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 帳號與配對
-- -----------------------------------------------------------------------------

create table public.profiles (
  id            uuid primary key references auth.users(id) on delete cascade,
  display_name  text not null check (char_length(display_name) between 1 and 30),
  avatar_url    text,
  birthday      date check (birthday between '1900-01-01' and '2200-12-31'),
  timezone      text not null default 'Asia/Taipei',
  created_at    timestamptz not null default now()
);

create table public.couples (
  id             uuid primary key default gen_random_uuid(),
  together_since date check (together_since between '1900-01-01' and '2200-12-31'),
  created_at     timestamptz not null default now(),
  ended_at       timestamptz
);

create table public.couple_members (
  couple_id  uuid not null references public.couples(id) on delete cascade,
  user_id    uuid not null references auth.users(id) on delete cascade,
  joined_at  timestamptz not null default now(),
  left_at    timestamptz,
  primary key (couple_id, user_id)
);
-- 一個人同時只能有一段有效配對（也擋掉兩人同時接受邀請的競態）
create unique index couple_members_one_active on public.couple_members (user_id) where left_at is null;

create table public.pair_invites (
  code         text primary key check (code ~ '^[A-HJ-NP-Z2-9]{6}$'),  -- 不含易混淆的 I O 0 1
  inviter_id   uuid not null references auth.users(id) on delete cascade,
  created_at   timestamptz not null default now(),
  expires_at   timestamptz not null default now() + interval '24 hours',
  accepted_by  uuid references auth.users(id) on delete set null,
  accepted_at  timestamptz
);

-- -----------------------------------------------------------------------------
-- 分享設定（每個人各一筆；預設不分享，必須在引導流程中逐項開啟）
-- -----------------------------------------------------------------------------

create table public.sharing_settings (
  user_id            uuid primary key references auth.users(id) on delete cascade,
  share_location     boolean not null default false,
  share_device       boolean not null default false,
  share_expenses     boolean not null default false,
  share_mood         boolean not null default true,
  share_cycle        boolean not null default false,
  hidden_categories  text[] not null default '{}',   -- 這些類別的開銷不給對方看
  paused_until       timestamptz,                    -- 暫停位置 / 手機狀態 / 開銷分享
  updated_at         timestamptz not null default now()
);

-- -----------------------------------------------------------------------------
-- 即時狀態：手機資訊與位置（只保留最新一筆，不留軌跡）
-- -----------------------------------------------------------------------------

create table public.device_status (
  user_id                 uuid primary key references auth.users(id) on delete cascade,
  battery_level           smallint check (battery_level between 0 and 100),
  is_charging             boolean,
  network                 text check (network in ('wifi', 'cellular', 'offline')),
  network_label           text,      -- 使用者自己命名的網路（「家裡」），不存 SSID
  notifications_silenced  boolean,   -- iOS 專注模式 / Android 勿擾：只知道是否靜音
  steps_today             integer check (steps_today >= 0),
  last_active_at          timestamptz,
  device_model            text,
  updated_at              timestamptz not null default now()
);

create table public.locations (
  user_id      uuid primary key references auth.users(id) on delete cascade,
  lat          double precision not null check (lat between -90 and 90),
  lng          double precision not null check (lng between -180 and 180),
  accuracy_m   real,
  speed_mps    real,
  activity     text check (activity in ('still', 'walking', 'running', 'cycling', 'driving', 'transit')),
  recorded_at  timestamptz not null,
  updated_at   timestamptz not null default now()
);

-- -----------------------------------------------------------------------------
-- 地點標記（地理圍欄）
-- -----------------------------------------------------------------------------

create table public.places (
  id             uuid primary key default gen_random_uuid(),
  couple_id      uuid not null references public.couples(id) on delete cascade,
  created_by     uuid not null default auth.uid() references auth.users(id) on delete cascade,
  name           text not null check (char_length(name) between 1 and 20),
  icon           text not null default 'pin',
  lat            double precision not null check (lat between -90 and 90),
  lng            double precision not null check (lng between -180 and 180),
  radius_m       integer not null default 150 check (radius_m between 50 and 2000),
  notify_arrive  boolean not null default true,
  notify_leave   boolean not null default true,
  watch_user     uuid references auth.users(id) on delete cascade,  -- null = 兩個人都偵測
  created_at     timestamptz not null default now()
);
create index places_couple on public.places (couple_id);

-- 由「移動的那個人」的手機判斷進出，再回報到這裡
create table public.place_events (
  id           bigint generated always as identity primary key,
  place_id     uuid not null references public.places(id) on delete cascade,
  user_id      uuid not null default auth.uid() references auth.users(id) on delete cascade,
  kind         text not null check (kind in ('arrive', 'leave')),
  occurred_at  timestamptz not null default now()
);
create index place_events_place on public.place_events (place_id, occurred_at desc);

-- -----------------------------------------------------------------------------
-- 自動記帳與猜花費
-- -----------------------------------------------------------------------------

create table public.expenses (
  id           uuid primary key default gen_random_uuid(),
  user_id      uuid not null default auth.uid() references auth.users(id) on delete cascade,
  amount       numeric(12, 2) not null check (amount > 0),
  currency     char(3) not null default 'TWD',
  merchant     text not null check (char_length(merchant) between 1 and 60),
  category     text not null default 'other'
               check (category in ('drink', 'food', 'transport', 'daily', 'shopping', 'fun', 'gift', 'other')),
  source       text not null default 'manual'
               check (source in ('manual', 'notification', 'shortcut', 'einvoice', 'ocr')),
  occurred_at  timestamptz not null,
  visibility   text not null default 'shared' check (visibility in ('shared', 'private', 'surprise')),
  reveal_on    date,                                  -- 禮物模式：這天起對方才看得到
  guessable    boolean not null default false,        -- 開放對方猜金額
  dedupe_key   text,                                  -- 同一筆交易從通知與發票各進來一次時去重
  created_at   timestamptz not null default now(),
  unique (user_id, dedupe_key),
  check (visibility <> 'surprise' or reveal_on is not null)
);
create index expenses_user_time on public.expenses (user_id, occurred_at desc);

create table public.expense_guesses (
  expense_id    uuid not null references public.expenses(id) on delete cascade,
  guesser_id    uuid not null references auth.users(id) on delete cascade,
  guess_amount  numeric(12, 2) not null check (guess_amount >= 0),
  points        smallint not null,
  created_at    timestamptz not null default now(),
  primary key (expense_id, guesser_id)              -- 每筆只能猜一次
);

-- -----------------------------------------------------------------------------
-- 心情與安撫
-- -----------------------------------------------------------------------------

create table public.moods (
  id          bigint generated always as identity primary key,
  user_id     uuid not null default auth.uid() references auth.users(id) on delete cascade,
  mood        text not null check (mood in ('happy', 'sweet', 'calm', 'missyou', 'tired', 'anxious', 'sad', 'angry', 'sick')),
  note        text check (char_length(note) <= 100),
  created_at  timestamptz not null default now()
);
create index moods_user_time on public.moods (user_id, created_at desc);

create table public.comforts (
  id           bigint generated always as identity primary key,
  couple_id    uuid not null references public.couples(id) on delete cascade,
  sender_id    uuid not null default auth.uid() references auth.users(id) on delete cascade,
  receiver_id  uuid not null references auth.users(id) on delete cascade,
  kind         text not null check (kind in ('hug', 'pat', 'kiss', 'here', 'cheer', 'treat', 'custom')),
  message      text check (char_length(message) <= 200),
  created_at   timestamptz not null default now(),
  read_at      timestamptz
);
create index comforts_couple_time on public.comforts (couple_id, created_at desc);

-- -----------------------------------------------------------------------------
-- 紀念日與約會日曆
-- -----------------------------------------------------------------------------

create table public.anniversaries (
  id           uuid primary key default gen_random_uuid(),
  couple_id    uuid not null references public.couples(id) on delete cascade,
  created_by   uuid not null default auth.uid() references auth.users(id) on delete cascade,
  title        text not null check (char_length(title) between 1 and 30),
  date         date not null check (date between '1900-01-01' and '2200-12-31'),  -- 擋掉 'infinity'，每日排程才不會壞掉
  kind         text not null default 'custom' check (kind in ('together', 'birthday', 'first_date', 'custom')),
  repeat       text not null default 'yearly' check (repeat in ('none', 'yearly')),
  calendar     text not null default 'solar' check (calendar in ('solar', 'lunar')),  -- 農曆由 App 換算後提醒
  remind_days  smallint[] not null default '{0,1,7}',
  created_at   timestamptz not null default now()
);

create table public.events (
  id              uuid primary key default gen_random_uuid(),
  couple_id       uuid not null references public.couples(id) on delete cascade,
  created_by      uuid not null default auth.uid() references auth.users(id) on delete cascade,
  title           text not null check (char_length(title) between 1 and 40),
  starts_at       timestamptz not null,
  ends_at         timestamptz,
  all_day         boolean not null default false,
  place_id        uuid references public.places(id) on delete set null,
  location_text   text,
  note            text check (char_length(note) <= 500),
  status          text not null default 'proposed' check (status in ('proposed', 'confirmed', 'declined', 'cancelled')),
  remind_minutes  smallint[] not null default '{60}',
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  reminded_at     timestamptz,   -- 約會提醒已送出（改時間時清掉，重新提醒）
  check (ends_at is null or ends_at >= starts_at),
  check (starts_at between '1900-01-01 00:00+00' and '2200-12-31 23:59:59+00'
         and (ends_at is null or ends_at <= '2200-12-31 23:59:59+00'))
);
create index events_couple_time on public.events (couple_id, starts_at);

-- -----------------------------------------------------------------------------
-- 經期（屬於記錄週期的那個人；預測在 App 端計算，見 prototype/core.js）
-- -----------------------------------------------------------------------------

create table public.cycle_settings (
  user_id           uuid primary key references auth.users(id) on delete cascade,
  cycle_length      smallint check (cycle_length between 15 and 60),   -- null = 依紀錄自動計算
  period_length     smallint check (period_length between 1 and 10),
  luteal_length     smallint not null default 14 check (luteal_length between 10 and 16),
  partner_can_edit  boolean not null default false,  -- 允許對方幫忙記錄經期開始 / 結束
  care_reminders    boolean not null default true,   -- 經期前兩天提醒對方多關心
  updated_at        timestamptz not null default now(),
  care_sent_for     date                             -- 這次預測的經期已經提醒過了
);

create table public.cycle_periods (
  id           uuid primary key default gen_random_uuid(),
  user_id      uuid not null references auth.users(id) on delete cascade,
  start_date   date not null,
  end_date     date,
  recorded_by  uuid not null default auth.uid() references auth.users(id) on delete cascade,
  created_at   timestamptz not null default now(),
  unique (user_id, start_date),
  check (end_date is null or end_date >= start_date),
  check (start_date between '1900-01-01' and '2200-12-31' and (end_date is null or end_date <= '2200-12-31'))
);

-- 症狀與筆記只有本人看得到
create table public.cycle_logs (
  user_id   uuid not null references auth.users(id) on delete cascade,
  log_date  date not null,
  flow      text check (flow in ('spotting', 'light', 'medium', 'heavy')),
  symptoms  text[] not null default '{}',
  note      text check (char_length(note) <= 300),
  primary key (user_id, log_date)
);

-- -----------------------------------------------------------------------------
-- 歷史紀錄、通知
-- -----------------------------------------------------------------------------

create table public.activity_log (
  id          bigint generated always as identity primary key,
  couple_id   uuid not null references public.couples(id) on delete cascade,
  actor_id    uuid references auth.users(id) on delete set null,
  kind        text not null,   -- paired / place_arrive / place_leave / mood / comfort / guess / event_* / battery_low / sharing_changed …
  payload     jsonb not null default '{}',
  created_at  timestamptz not null default now()
);
create index activity_log_couple_time on public.activity_log (couple_id, created_at desc);

create table public.notification_prefs (
  user_id  uuid not null references auth.users(id) on delete cascade,
  kind     text not null check (kind in ('pairing', 'place', 'mood', 'comfort', 'battery_low', 'guess',
                                         'event', 'anniversary', 'cycle_care', 'sharing_changed')),
  enabled  boolean not null default true,
  primary key (user_id, kind)
);

create table public.push_tokens (
  token       text primary key,
  user_id     uuid not null default auth.uid() references auth.users(id) on delete cascade,
  platform    text not null check (platform in ('ios', 'android')),
  updated_at  timestamptz not null default now()
);

-- 由資料庫的觸發程序與排程寫入；App 經由 Realtime 收到後呼叫 app_ack_notifications 填 sent_at
create table public.notification_outbox (
  id            bigint generated always as identity primary key,
  recipient_id  uuid not null references auth.users(id) on delete cascade,
  kind          text not null,
  title         text not null,
  body          text not null,
  data          jsonb not null default '{}',
  created_at    timestamptz not null default now(),
  sent_at       timestamptz,
  error         text
);
create index notification_outbox_pending on public.notification_outbox (recipient_id, id) where sent_at is null;

-- 排程（每日提醒）執行紀錄，同一天只跑一次
create table public.job_runs (
  name    text not null,
  run_on  date not null,
  primary key (name, run_on)
);

-- 整個專案的設定（只有一筆）：人數上限與排程用的時區
create table public.app_config (
  id         boolean primary key default true check (id),
  max_users  integer not null default 2,
  timezone   text not null default 'Asia/Taipei'
);
insert into public.app_config default values;

-- =============================================================================
-- Helper functions（security definer，避免 RLS 互相遞迴）
-- =============================================================================

create or replace function public.couple_of(p_user uuid) returns uuid
language sql stable security definer set search_path = public as $$
  select couple_id from couple_members where user_id = p_user and left_at is null
$$;

create or replace function public.partner_of(p_user uuid) returns uuid
language sql stable security definer set search_path = public as $$
  select m2.user_id
  from couple_members m1
  join couple_members m2 on m2.couple_id = m1.couple_id and m2.user_id <> m1.user_id and m2.left_at is null
  where m1.user_id = p_user and m1.left_at is null
$$;

-- 以下四個給 RLS policy 使用：只回答「關於目前登入者」的問題
create or replace function public.my_couple_id() returns uuid
language sql stable security definer set search_path = public as $$ select couple_of(auth.uid()) $$;

create or replace function public.partner_id() returns uuid
language sql stable security definer set search_path = public as $$ select partner_of(auth.uid()) $$;

create or replace function public.is_partner(p_user uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select p_user is not null and p_user = partner_of(auth.uid())
$$;

-- 擁有者目前是否分享某類資料。暫停只影響被動收集的資料（位置、手機狀態、開銷）。
create or replace function public.shares(p_owner uuid, p_kind text) returns boolean
language sql stable security definer set search_path = public as $$
  select coalesce((
    select case p_kind
             when 'location' then s.share_location
             when 'device'   then s.share_device
             when 'expenses' then s.share_expenses
             when 'mood'     then s.share_mood
             when 'cycle'    then s.share_cycle
             else false
           end
           and (p_kind not in ('location', 'device', 'expenses') or s.paused_until is null or s.paused_until <= now())
    from sharing_settings s where s.user_id = p_owner
  ), false)
$$;

create or replace function public.partner_shares(p_owner uuid, p_kind text) returns boolean
language sql stable security definer set search_path = public as $$
  select is_partner(p_owner) and shares(p_owner, p_kind)
$$;

create or replace function public.i_share(p_kind text) returns boolean
language sql stable security definer set search_path = public as $$ select shares(auth.uid(), p_kind) $$;

create or replace function public.display_name_of(p_user uuid) returns text
language sql stable security definer set search_path = public as $$
  select coalesce((select display_name from profiles where id = p_user), '對方')
$$;

create or replace function public.enqueue_notification(
  p_recipient uuid, p_kind text, p_title text, p_body text, p_data jsonb default '{}'
) returns void
language sql security definer set search_path = public as $$
  insert into notification_outbox (recipient_id, kind, title, body, data)
  select p_recipient, p_kind, p_title, p_body, p_data
  where p_recipient is not null
    and not exists (select 1 from notification_prefs
                    where user_id = p_recipient and kind = p_kind and not enabled)
$$;

create or replace function public.log_activity(p_couple uuid, p_actor uuid, p_kind text, p_payload jsonb default '{}')
returns void
language sql security definer set search_path = public as $$
  insert into activity_log (couple_id, actor_id, kind, payload)
  select p_couple, p_actor, p_kind, p_payload where p_couple is not null
$$;

create or replace function public.gen_pair_code() returns text
language plpgsql volatile as $$
declare
  alphabet constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';  -- 32 個字元
  -- gen_random_uuid() 是 PostgreSQL 13 起內建的強隨機數，不需要安裝 pgcrypto
  bytes bytea := decode(replace(gen_random_uuid()::text, '-', ''), 'hex');
  code text := '';
begin
  for i in 0..5 loop
    code := code || substr(alphabet, (get_byte(bytes, i) % 32) + 1, 1);
  end loop;
  return code;
end $$;

create or replace function public.mood_label(p_mood text) returns text
language sql immutable as $$
  select case p_mood
    when 'happy' then '開心' when 'sweet' then '甜蜜' when 'calm' then '平靜'
    when 'missyou' then '想你' when 'tired' then '好累' when 'anxious' then '焦慮'
    when 'sad' then '難過' when 'angry' then '生氣' when 'sick' then '不舒服' else p_mood end
$$;

create or replace function public.comfort_text(p_kind text) returns text
language sql immutable as $$
  select case p_kind
    when 'hug' then '給了你一個大大的抱抱' when 'pat' then '摸摸你的頭，說辛苦了'
    when 'kiss' then '親了你一下' when 'here' then '說：我在，想聊隨時找我'
    when 'cheer' then '幫你加油打氣' when 'treat' then '說等等請你喝一杯'
    else '傳了悄悄話給你' end
$$;

-- 下一次的週年日期；2/29 在平年以 2/28 計
create or replace function public.next_yearly(p_date date, p_today date) returns date
language plpgsql immutable as $$
declare
  m int := extract(month from p_date);
  d int := extract(day from p_date);
  y int := greatest(extract(year from p_today)::int, extract(year from p_date)::int);
  candidate date;
begin
  loop
    candidate := case
      when m = 2 and d = 29 and not ((y % 4 = 0 and y % 100 <> 0) or y % 400 = 0) then make_date(y, 2, 28)
      else make_date(y, m, d) end;
    if candidate >= p_today then return candidate; end if;
    y := y + 1;
  end loop;
end $$;

-- =============================================================================
-- RPC：配對
-- =============================================================================

create or replace function public.create_invite() returns text
language plpgsql security definer set search_path = public as $$
declare v_code text;
begin
  if auth.uid() is null then raise exception 'not_authenticated'; end if;
  if couple_of(auth.uid()) is not null then raise exception 'already_paired'; end if;
  delete from pair_invites where inviter_id = auth.uid() and accepted_at is null;
  loop
    v_code := gen_pair_code();
    begin
      insert into pair_invites (code, inviter_id) values (v_code, auth.uid());
      return v_code;
    exception when unique_violation then
      -- 碰撞機率極低，重新產生即可
    end;
  end loop;
end $$;

create or replace function public.accept_invite(p_code text) returns uuid
language plpgsql security definer set search_path = public as $$
declare
  v_me     uuid := auth.uid();
  v_inv    pair_invites;
  v_couple uuid;
begin
  if v_me is null then raise exception 'not_authenticated'; end if;
  select * into v_inv from pair_invites where code = upper(trim(p_code)) for update;
  if not found or v_inv.accepted_at is not null or v_inv.expires_at < now() then
    raise exception 'invite_invalid';
  end if;
  if v_inv.inviter_id = v_me then raise exception 'cannot_pair_with_self'; end if;
  if exists (select 1 from couple_members where user_id in (v_me, v_inv.inviter_id) and left_at is null) then
    raise exception 'already_paired';
  end if;

  insert into couples default values returning id into v_couple;
  insert into couple_members (couple_id, user_id) values (v_couple, v_inv.inviter_id), (v_couple, v_me);
  update pair_invites set accepted_by = v_me, accepted_at = now() where code = v_inv.code;
  insert into sharing_settings (user_id) values (v_inv.inviter_id), (v_me) on conflict (user_id) do nothing;

  perform log_activity(v_couple, v_me, 'paired');
  perform enqueue_notification(v_inv.inviter_id, 'pairing', '配對成功',
    display_name_of(v_me) || ' 接受了你的邀請，一起決定要分享哪些資訊吧');
  return v_couple;
end $$;

create or replace function public.unpair() returns void
language plpgsql security definer set search_path = public as $$
declare
  v_couple  uuid := couple_of(auth.uid());
  v_partner uuid := partner_of(auth.uid());
begin
  if auth.uid() is null then raise exception 'not_authenticated'; end if;
  if v_couple is null then raise exception 'not_paired'; end if;
  update couple_members set left_at = now() where couple_id = v_couple and left_at is null;
  update couples set ended_at = now() where id = v_couple;
  perform log_activity(v_couple, auth.uid(), 'unpaired');
  perform enqueue_notification(v_partner, 'pairing', '已解除配對', '所有即時分享已經停止');
end $$;

-- 帳號被刪除（Supabase 後台 › Authentication › Users）時，配對紀錄會跟著被刪掉。
-- 留下來的那個人自動解除配對，才能重新產生配對碼、和新的帳號配對，不會卡在「只剩一個人」的配對裡。
create or replace function public.tg_couple_member_deleted() returns trigger
language plpgsql security definer set search_path = public as $$
declare v_left uuid;
begin
  if old.left_at is not null then return old; end if;
  update couples set ended_at = coalesce(ended_at, now()) where id = old.couple_id;
  for v_left in
    update couple_members set left_at = now() where couple_id = old.couple_id and left_at is null returning user_id
  loop
    perform enqueue_notification(v_left, 'pairing', '已解除配對', '對方的帳號已經刪除，所有即時分享已經停止');
  end loop;
  return old;
end $$;
create trigger couple_member_deleted after delete on public.couple_members
  for each row execute function public.tg_couple_member_deleted();

-- 暫停分享（0 = 立即恢復）。對方會收到通知，避免「偷偷消失」造成誤會。
create or replace function public.pause_sharing(p_minutes integer) returns timestamptz
language plpgsql security definer set search_path = public as $$
declare v_until timestamptz;
begin
  if auth.uid() is null then raise exception 'not_authenticated'; end if;
  if p_minutes < 0 or p_minutes > 24 * 60 then raise exception 'invalid_duration'; end if;
  v_until := case when p_minutes = 0 then null else now() + make_interval(mins => p_minutes) end;
  insert into sharing_settings (user_id, paused_until) values (auth.uid(), v_until)
  on conflict (user_id) do update set paused_until = excluded.paused_until, updated_at = now();
  return v_until;
end $$;

-- =============================================================================
-- RPC：對方的開銷（可猜的那筆在猜之前不回傳金額）與猜花費
-- =============================================================================

-- 這筆開銷對方看不看得到（另外還要擁有者有分享開銷：shares(user_id, 'expenses')）
create or replace function public.expense_shown(p_e public.expenses) returns boolean
language sql stable security definer set search_path = public as $$
  select (p_e.visibility = 'shared' or (p_e.visibility = 'surprise' and p_e.reveal_on <= current_date))
         and not exists (select 1 from sharing_settings s
                         where s.user_id = p_e.user_id and p_e.category = any (s.hidden_categories))
$$;

create or replace function public.partner_expenses(p_from timestamptz, p_to timestamptz)
returns table (
  id uuid, amount numeric, merchant text, category text, source text,
  occurred_at timestamptz, guessable boolean, my_guess numeric, my_points smallint
)
language sql stable security definer set search_path = public as $$
  select e.id,
         case when e.guessable and g.expense_id is null then null else e.amount end,
         e.merchant, e.category, e.source, e.occurred_at, e.guessable, g.guess_amount, g.points
  from expenses e
  left join expense_guesses g on g.expense_id = e.id and g.guesser_id = auth.uid()
  where e.user_id = partner_of(auth.uid())
    and shares(e.user_id, 'expenses')
    and e.occurred_at >= p_from and e.occurred_at < p_to
    and expense_shown(e)
  order by e.occurred_at desc
$$;

-- 計分規則與 prototype/core.js 的 GUESS_TIERS 相同；在資料庫算，猜之前拿不到答案
create or replace function public.submit_guess(p_expense uuid, p_guess numeric)
returns table (actual_amount numeric, score_points smallint, error_ratio numeric)
language plpgsql security definer set search_path = public as $$
declare
  v_e      expenses;
  v_ratio  numeric;
  v_points smallint;
begin
  select * into v_e from expenses where expenses.id = p_expense;
  if not found or v_e.user_id is distinct from partner_of(auth.uid()) or not v_e.guessable
     or not shares(v_e.user_id, 'expenses') or v_e.visibility <> 'shared' then
    raise exception 'expense_not_guessable';
  end if;
  if p_guess is null or p_guess < 0 then raise exception 'invalid_guess'; end if;

  v_ratio := abs(p_guess - v_e.amount) / v_e.amount;
  v_points := case when v_ratio <= 0.03 then 100 when v_ratio <= 0.10 then 80
                   when v_ratio <= 0.25 then 50 when v_ratio <= 0.50 then 20 else 5 end;
  insert into expense_guesses (expense_id, guesser_id, guess_amount, points)
  values (p_expense, auth.uid(), p_guess, v_points);

  perform log_activity(couple_of(auth.uid()), auth.uid(), 'guess', jsonb_build_object(
    'expense_id', p_expense, 'merchant', v_e.merchant, 'guess', p_guess, 'actual', v_e.amount, 'points', v_points));
  perform enqueue_notification(v_e.user_id, 'guess', display_name_of(auth.uid()) || ' 猜了你的花費',
    v_e.merchant || '：猜 NT$' || round(p_guess) || '，實際 NT$' || round(v_e.amount) || '（' || v_points || ' 分）');
  return query select v_e.amount, v_points, round(v_ratio, 4);
end $$;

-- =============================================================================
-- Triggers：寫入歷史紀錄並通知對方
-- =============================================================================

create or replace function public.tg_mood_inserted() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if not shares(new.user_id, 'mood') then return new; end if;
  perform log_activity(couple_of(new.user_id), new.user_id, 'mood',
    jsonb_build_object('mood', new.mood, 'note', new.note));
  perform enqueue_notification(partner_of(new.user_id), 'mood',
    display_name_of(new.user_id) || ' 現在覺得' || mood_label(new.mood),
    case when new.mood in ('tired', 'anxious', 'sad', 'angry', 'sick')
         then coalesce(new.note || '。', '') || '要不要安撫一下？'
         else coalesce(new.note, '') end,
    jsonb_build_object('mood', new.mood));
  return new;
end $$;
create trigger mood_inserted after insert on public.moods
  for each row execute function public.tg_mood_inserted();

create or replace function public.tg_comfort_before() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  -- 防洗版：10 分鐘內最多 20 次
  if (select count(*) from comforts where sender_id = new.sender_id
      and created_at > now() - interval '10 minutes') >= 20 then
    raise exception 'rate_limited';
  end if;
  return new;
end $$;
create trigger comfort_before before insert on public.comforts
  for each row execute function public.tg_comfort_before();

create or replace function public.tg_comfort_inserted() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  perform log_activity(new.couple_id, new.sender_id, 'comfort',
    jsonb_build_object('kind', new.kind, 'message', new.message));
  perform enqueue_notification(new.receiver_id, 'comfort',
    display_name_of(new.sender_id) || ' ' || comfort_text(new.kind),
    coalesce(new.message, '點開回應一下吧'), jsonb_build_object('comfort_id', new.id));
  return new;
end $$;
create trigger comfort_inserted after insert on public.comforts
  for each row execute function public.tg_comfort_inserted();

create or replace function public.tg_place_event_inserted() returns trigger
language plpgsql security definer set search_path = public as $$
declare v_place places;
begin
  select * into v_place from places where id = new.place_id;
  if v_place.watch_user is not null and v_place.watch_user <> new.user_id then return new; end if;
  perform log_activity(v_place.couple_id, new.user_id, 'place_' || new.kind,
    jsonb_build_object('place_id', v_place.id, 'place', v_place.name));
  if (new.kind = 'arrive' and v_place.notify_arrive) or (new.kind = 'leave' and v_place.notify_leave) then
    perform enqueue_notification(partner_of(new.user_id), 'place',
      display_name_of(new.user_id) || case new.kind when 'arrive' then ' 已抵達 ' else ' 已離開 ' end || v_place.name,
      to_char(new.occurred_at at time zone 'Asia/Taipei', 'HH24:MI'),
      jsonb_build_object('place_id', v_place.id));
  end if;
  return new;
end $$;
create trigger place_event_inserted after insert on public.place_events
  for each row execute function public.tg_place_event_inserted();

-- 對方新增會偵測「我」的地點，或把地點改成偵測我、搬了位置、改了範圍時，一定通知我（透明度，不能關閉）
create or replace function public.tg_place_saved() returns trigger
language plpgsql security definer set search_path = public as $$
declare
  v_actor   uuid := coalesce(auth.uid(), new.created_by);
  v_partner uuid := partner_of(v_actor);
begin
  if v_partner is not null and (new.watch_user is null or new.watch_user = v_partner)
     and (tg_op = 'INSERT' or new.watch_user is distinct from old.watch_user
          or new.lat <> old.lat or new.lng <> old.lng or new.radius_m <> old.radius_m) then
    insert into notification_outbox (recipient_id, kind, title, body, data)
    values (v_partner, 'sharing_changed',
            display_name_of(v_actor) || case when tg_op = 'INSERT' then ' 新增了地點「' else ' 修改了地點「' end || new.name || '」',
            '你抵達或離開這裡時，對方會收到通知。可以在地圖頁查看或關閉位置分享。',
            jsonb_build_object('place_id', new.id));
  end if;
  return new;
end $$;
create trigger place_saved after insert or update of watch_user, lat, lng, radius_m on public.places
  for each row execute function public.tg_place_saved();

create or replace function public.tg_event_changed() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if tg_op = 'INSERT' then
    perform log_activity(new.couple_id, new.created_by, 'event_created',
      jsonb_build_object('event_id', new.id, 'title', new.title, 'starts_at', new.starts_at));
    perform enqueue_notification(partner_of(new.created_by), 'event',
      display_name_of(new.created_by) || ' 約你：' || new.title,
      to_char(new.starts_at at time zone 'Asia/Taipei', 'MM/DD HH24:MI'), jsonb_build_object('event_id', new.id));
  elsif new.status is distinct from old.status and new.status in ('confirmed', 'declined') then
    perform log_activity(new.couple_id, auth.uid(), 'event_' || new.status,
      jsonb_build_object('event_id', new.id, 'title', new.title));
    perform enqueue_notification(new.created_by, 'event',
      display_name_of(auth.uid()) || case new.status when 'confirmed' then ' 答應了：' else ' 婉拒了：' end || new.title,
      to_char(new.starts_at at time zone 'Asia/Taipei', 'MM/DD HH24:MI'), jsonb_build_object('event_id', new.id));
  end if;
  return new;
end $$;
create trigger event_changed after insert or update on public.events
  for each row execute function public.tg_event_changed();

create or replace function public.tg_device_status_changed() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if shares(new.user_id, 'device')
     and coalesce(old.battery_level, 100) > 20 and new.battery_level <= 20 and not coalesce(new.is_charging, false) then
    perform log_activity(couple_of(new.user_id), new.user_id, 'battery_low', jsonb_build_object('level', new.battery_level));
    perform enqueue_notification(partner_of(new.user_id), 'battery_low',
      display_name_of(new.user_id) || ' 的手機電量剩 ' || new.battery_level || '%',
      '可能快聯絡不到了，提醒對方充電吧');
  end if;
  return new;
end $$;
create trigger device_status_changed after update on public.device_status
  for each row execute function public.tg_device_status_changed();

-- 分享設定一改就讓對方知道（關閉、暫停都看得到，不能偷偷改）
create or replace function public.tg_sharing_changed() returns trigger
language plpgsql security definer set search_path = public as $$
declare v_changes jsonb := '{}';
begin
  if new.share_location is distinct from old.share_location then v_changes := v_changes || jsonb_build_object('location', new.share_location); end if;
  if new.share_device   is distinct from old.share_device   then v_changes := v_changes || jsonb_build_object('device', new.share_device); end if;
  if new.share_expenses is distinct from old.share_expenses then v_changes := v_changes || jsonb_build_object('expenses', new.share_expenses); end if;
  if new.share_mood     is distinct from old.share_mood     then v_changes := v_changes || jsonb_build_object('mood', new.share_mood); end if;
  if new.share_cycle    is distinct from old.share_cycle    then v_changes := v_changes || jsonb_build_object('cycle', new.share_cycle); end if;
  if new.paused_until   is distinct from old.paused_until   then v_changes := v_changes || jsonb_build_object('paused_until', new.paused_until); end if;
  if v_changes = '{}' then return new; end if;
  perform log_activity(couple_of(new.user_id), new.user_id, 'sharing_changed', v_changes);
  perform enqueue_notification(partner_of(new.user_id), 'sharing_changed',
    display_name_of(new.user_id) || ' 更新了分享設定', '到「我的 › 分享設定」看看有哪些改變');
  return new;
end $$;
create trigger sharing_changed after update on public.sharing_settings
  for each row execute function public.tg_sharing_changed();

-- =============================================================================
-- 每日排程：紀念日、交往天數里程碑；解除配對 30 天後清除共享資料
-- pg_cron 每天 09:00 後呼叫一次（app_job_daily，見 20261015000004_jobs.sql），
-- 經期關懷提醒也在那裡，用 prototype/core.js 同一套週期預測。
-- =============================================================================

create or replace function public.daily_reminders(p_today date default (now() at time zone 'Asia/Taipei')::date)
returns integer
language plpgsql security definer set search_path = public as $$
declare
  r       record;
  v_next  date;
  v_left  integer;
  v_day   integer;
  v_sent  integer := 0;
begin
  for r in
    select a.*, m.user_id as member_id
    from anniversaries a
    join couples c on c.id = a.couple_id and c.ended_at is null
    join couple_members m on m.couple_id = a.couple_id and m.left_at is null
    where a.calendar = 'solar'
  loop
    v_next := case when r.repeat = 'yearly' then next_yearly(r.date, p_today) else r.date end;
    v_left := v_next - p_today;
    if v_left = any (r.remind_days) then
      perform enqueue_notification(r.member_id, 'anniversary', r.title,
        case when v_left = 0 then '就是今天！' else '還有 ' || v_left || ' 天' end,
        jsonb_build_object('anniversary_id', r.id, 'date', v_next));
      v_sent := v_sent + 1;
    end if;
  end loop;

  for r in
    select c.id, c.together_since, m.user_id as member_id
    from couples c join couple_members m on m.couple_id = c.id and m.left_at is null
    where c.ended_at is null and c.together_since is not null
  loop
    v_day := p_today - r.together_since + 1;
    if v_day > 0 and (v_day % 100 = 0 or v_day in (520, 1314)) then
      perform enqueue_notification(r.member_id, 'anniversary', '在一起第 ' || v_day || ' 天', '今天也要好好相處喔');
      v_sent := v_sent + 1;
    end if;
  end loop;

  delete from couples where ended_at < now() - interval '30 days';
  return v_sent;
end $$;

-- =============================================================================
-- Row Level Security
-- =============================================================================

alter table public.profiles            enable row level security;
alter table public.couples             enable row level security;
alter table public.couple_members      enable row level security;
alter table public.pair_invites        enable row level security;
alter table public.sharing_settings    enable row level security;
alter table public.device_status       enable row level security;
alter table public.locations           enable row level security;
alter table public.places              enable row level security;
alter table public.place_events        enable row level security;
alter table public.expenses            enable row level security;
alter table public.expense_guesses     enable row level security;
alter table public.moods               enable row level security;
alter table public.comforts            enable row level security;
alter table public.anniversaries       enable row level security;
alter table public.events              enable row level security;
alter table public.cycle_settings      enable row level security;
alter table public.cycle_periods       enable row level security;
alter table public.cycle_logs          enable row level security;
alter table public.activity_log        enable row level security;
alter table public.notification_prefs  enable row level security;
alter table public.push_tokens         enable row level security;
alter table public.notification_outbox enable row level security;  -- 只能讀自己的（見 realtime 遷移）
alter table public.job_runs            enable row level security;  -- 沒有 policy = 用戶端完全不能碰
alter table public.app_config          enable row level security;

-- 帳號
create policy "profiles: self and partner read" on public.profiles for select using (id = auth.uid() or public.is_partner(id));
create policy "profiles: self insert" on public.profiles for insert with check (id = auth.uid());
create policy "profiles: self update" on public.profiles for update using (id = auth.uid()) with check (id = auth.uid());

create policy "couples: members read" on public.couples for select using (id = public.my_couple_id());
create policy "couples: members update" on public.couples for update using (id = public.my_couple_id()) with check (id = public.my_couple_id());
create policy "couple_members: members read" on public.couple_members for select using (couple_id = public.my_couple_id());
create policy "pair_invites: own read" on public.pair_invites for select using (inviter_id = auth.uid());

-- 分享設定：自己可改；對方可以看（知道自己被分享了什麼）
create policy "sharing: self and partner read" on public.sharing_settings for select using (user_id = auth.uid() or public.is_partner(user_id));
create policy "sharing: self insert" on public.sharing_settings for insert with check (user_id = auth.uid());
create policy "sharing: self update" on public.sharing_settings for update using (user_id = auth.uid()) with check (user_id = auth.uid());

-- 擁有者資料：本人全權；對方只在擁有者分享時可讀
create policy "device: owner" on public.device_status for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "device: partner read" on public.device_status for select using (public.partner_shares(user_id, 'device'));
create policy "locations: owner" on public.locations for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "locations: partner read" on public.locations for select using (public.partner_shares(user_id, 'location'));
create policy "moods: owner" on public.moods for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "moods: partner read" on public.moods for select using (public.partner_shares(user_id, 'mood'));
-- 開銷只開放給本人；對方一律經由 partner_expenses()，才能遮住待猜金額
create policy "expenses: owner" on public.expenses for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "guesses: guesser or owner read" on public.expense_guesses for select using (
  guesser_id = auth.uid() or exists (select 1 from public.expenses e where e.id = expense_id and e.user_id = auth.uid()));

create policy "cycle_settings: owner" on public.cycle_settings for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "cycle_settings: partner read" on public.cycle_settings for select using (public.partner_shares(user_id, 'cycle'));
create policy "cycle_periods: owner" on public.cycle_periods for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "cycle_periods: partner read" on public.cycle_periods for select using (public.partner_shares(user_id, 'cycle'));
create policy "cycle_periods: partner write" on public.cycle_periods for insert with check (
  public.partner_shares(user_id, 'cycle')
  and exists (select 1 from public.cycle_settings s where s.user_id = cycle_periods.user_id and s.partner_can_edit));
create policy "cycle_periods: partner update" on public.cycle_periods for update using (
  public.partner_shares(user_id, 'cycle')
  and exists (select 1 from public.cycle_settings s where s.user_id = cycle_periods.user_id and s.partner_can_edit));
create policy "cycle_logs: owner only" on public.cycle_logs for all using (user_id = auth.uid()) with check (user_id = auth.uid());

-- 兩人共用的資料
-- 被偵測的人也能修改或刪除對方建立的地點
create policy "places: couple read" on public.places for select using (couple_id = public.my_couple_id());
create policy "places: couple insert" on public.places for insert with check (
  couple_id = public.my_couple_id() and created_by = auth.uid()
  and (watch_user is null or watch_user = auth.uid() or public.is_partner(watch_user)));
create policy "places: couple update" on public.places for update using (couple_id = public.my_couple_id()) with check (
  couple_id = public.my_couple_id() and (watch_user is null or watch_user = auth.uid() or public.is_partner(watch_user)));
create policy "places: couple delete" on public.places for delete using (couple_id = public.my_couple_id());
create policy "place_events: couple read" on public.place_events for select using (
  exists (select 1 from public.places p where p.id = place_id and p.couple_id = public.my_couple_id()));
create policy "place_events: self report" on public.place_events for insert with check (
  user_id = auth.uid() and public.i_share('location')
  and exists (select 1 from public.places p where p.id = place_id and p.couple_id = public.my_couple_id()));

create policy "comforts: couple read" on public.comforts for select using (
  couple_id = public.my_couple_id() and auth.uid() in (sender_id, receiver_id));
create policy "comforts: send to partner" on public.comforts for insert with check (
  sender_id = auth.uid() and couple_id = public.my_couple_id() and public.is_partner(receiver_id));
create policy "comforts: receiver marks read" on public.comforts for update using (receiver_id = auth.uid()) with check (receiver_id = auth.uid());

create policy "anniversaries: couple" on public.anniversaries for all
  using (couple_id = public.my_couple_id()) with check (couple_id = public.my_couple_id());
create policy "events: couple read" on public.events for select using (couple_id = public.my_couple_id());
create policy "events: couple insert" on public.events for insert with check (couple_id = public.my_couple_id() and created_by = auth.uid());
create policy "events: couple update" on public.events for update using (couple_id = public.my_couple_id()) with check (couple_id = public.my_couple_id());
create policy "events: creator delete" on public.events for delete using (couple_id = public.my_couple_id() and created_by = auth.uid());

create policy "activity: couple read" on public.activity_log for select using (couple_id = public.my_couple_id());

create policy "notification_prefs: owner" on public.notification_prefs for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "push_tokens: owner" on public.push_tokens for all using (user_id = auth.uid()) with check (user_id = auth.uid());

-- 內部函式不給用戶端直接呼叫
revoke execute on function public.enqueue_notification(uuid, text, text, text, jsonb) from public, anon, authenticated;
revoke execute on function public.log_activity(uuid, uuid, text, jsonb) from public, anon, authenticated;
revoke execute on function public.gen_pair_code() from public, anon, authenticated;
revoke execute on function public.daily_reminders(date) from public, anon, authenticated;
revoke execute on function public.couple_of(uuid) from public, anon, authenticated;
revoke execute on function public.partner_of(uuid) from public, anon, authenticated;
revoke execute on function public.shares(uuid, text) from public, anon, authenticated;
revoke execute on function public.display_name_of(uuid) from public, anon, authenticated;

-- =============================================================================
-- 新帳號：Supabase Auth 建立使用者後，自動建立個人資料與分享設定
-- 一個專案最多 app_config.max_users 個人（預設 2）；滿了就拒絕註冊，
-- Supabase Auth 會回 500「Database error saving new user」，App 顯示友善的說明。
-- =============================================================================

create or replace function public.handle_new_user() returns trigger
language plpgsql security definer set search_path = public as $$
declare
  v_max  integer := coalesce((select max_users from app_config), 2);
  v_name text;
begin
  perform pg_advisory_xact_lock(7311);  -- 兩個人同時註冊也不會超過上限
  if (select count(*) from profiles) >= v_max then
    raise exception 'registration_closed';
  end if;
  v_name := coalesce(
    nullif(left(trim(new.raw_user_meta_data->>'display_name'), 30), ''),
    nullif(left(split_part(coalesce(new.email, ''), '@', 1), 30), ''),
    '親愛的');
  insert into profiles (id, display_name) values (new.id, v_name);
  insert into sharing_settings (user_id) values (new.id) on conflict (user_id) do nothing;
  return new;
end $$;
create trigger on_auth_user_created after insert on auth.users
  for each row execute function public.handle_new_user();
revoke execute on function public.handle_new_user() from public, anon, authenticated;
