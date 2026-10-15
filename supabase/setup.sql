-- =============================================================================
-- 小倆口 Supabase 設定檔
-- 用法：Supabase 後台 › SQL Editor › 貼上這個檔案的全部內容 › Run（只要執行一次）。
-- 全部包在同一個交易裡：中途出錯會整個取消，不會留下設定到一半的資料庫。
-- 這個檔案由 supabase/scripts/build-setup.sh 從 supabase/migrations/ 產生，請勿手動修改。
-- =============================================================================

begin;

-- >>> migrations/20261015000001_schema.sql

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

-- >>> migrations/20261015000002_realtime.sql

-- =============================================================================
-- 即時更新：App 用 Supabase Realtime 訂閱兩張表的 INSERT
--   notification_outbox → 通知（App 收到後呼叫 app_ack_notifications）
--   realtime_events     → 「哪張表變了」（changed）與「現在在哪？」（locate）
-- 兩張表都有 Row Level Security，每個人只收得到自己的。
-- =============================================================================

create table public.realtime_events (
  id          bigserial primary key,
  user_id     uuid not null references auth.users(id) on delete cascade,  -- 收件人
  kind        text not null check (kind in ('changed', 'locate')),
  table_name  text,
  op          text,
  by_user     uuid,
  created_at  timestamptz not null default now()
);
create index realtime_events_user on public.realtime_events (user_id, id);

alter table public.realtime_events enable row level security;
create policy "realtime_events: own read" on public.realtime_events for select using (user_id = auth.uid());
create policy "outbox: own read" on public.notification_outbox for select using (recipient_id = auth.uid());

-- 資料變動 → 這對情侶的每個人各一筆 changed，App 重新抓對應的畫面。
-- 擁有者的個人資料（經期、心情、開銷、位置、手機狀態）只在擁有者有分享那一類時才通知對方，
-- 不然對方光看「什麼時候變了」就能猜出沒分享的事（例如什麼時候記了經期、買了禮物）。
create or replace function public.tg_changed() returns trigger
language plpgsql security definer set search_path = public as $$
declare
  v_row    jsonb := to_jsonb(case when tg_op = 'DELETE' then old else new end);
  v_old    jsonb := case when tg_op = 'UPDATE' then to_jsonb(old) end;
  v_user   uuid;
  v_couple uuid;
  v_kind   text;      -- 這張表屬於哪一類分享；null = 兩人共用的資料
  v_shown  boolean;   -- 對方能不能收到這筆的 changed
begin
  v_user := coalesce(v_row->>'user_id', v_row->>'guesser_id', v_row->>'sender_id',
                     case when tg_table_name = 'profiles' then v_row->>'id' end)::uuid;
  v_couple := coalesce(
    case when tg_table_name = 'couples' then (v_row->>'id')::uuid end,
    (v_row->>'couple_id')::uuid,
    couple_of(v_user));
  v_kind := case tg_table_name
    when 'cycle_periods' then 'cycle' when 'cycle_settings' then 'cycle' when 'moods' then 'mood'
    when 'expenses' then 'expenses' when 'locations' then 'location' when 'device_status' then 'device' end;
  if v_kind is null then
    -- 兩人共用的資料；猜花費（expense_guesses）只會發生在對方看得到的開銷上，兩個人本來就都看得到
    v_shown := true;
  elsif tg_table_name = 'expenses' then
    -- 開銷：改之前或改之後對方看得到才通知（例如從「公開」改成「私人」，對方的清單也要拿掉）
    v_shown := shares(v_user, v_kind)
      and (coalesce(expense_shown(jsonb_populate_record(null::expenses, v_row)), false)
           or coalesce(expense_shown(jsonb_populate_record(null::expenses, v_old)), false));
  else
    v_shown := shares(v_user, v_kind);
  end if;

  insert into realtime_events (user_id, kind, table_name, op, by_user)
  select t.uid, 'changed', tg_table_name, lower(tg_op), v_user
  from (
    select m.user_id as uid from couple_members m
    where m.couple_id = v_couple and m.left_at is null and (v_shown or m.user_id = v_user)
    union
    select v_user where v_user is not null   -- 沒有配對（或剛解除配對）時，至少通知自己
  ) t
  -- 刪除帳號時會連帶刪除資料，這時不用再通知已經不存在的人
  where exists (select 1 from auth.users u where u.id = t.uid);

  -- 分享設定改了：請對方重新抓受影響的資料（開啟後才看得到之前記的、關閉或暫停後要藏起來）
  if tg_table_name = 'sharing_settings' and tg_op = 'UPDATE' then
    insert into realtime_events (user_id, kind, table_name, op, by_user)
    select p.uid, 'changed', x.tbl, 'update', v_user
    from (select partner_of(v_user) as uid) p,
         (values ('moods', array['share_mood']),
                 ('expenses', array['share_expenses', 'hidden_categories', 'paused_until']),
                 ('cycle_periods', array['share_cycle'])) as x(tbl, cols)
    where p.uid is not null
      and exists (select 1 from unnest(x.cols) c where v_row->c is distinct from v_old->c);
  end if;
  return null;
end $$;

do $$
declare t text;
begin
  foreach t in array array['profiles', 'couples', 'couple_members', 'sharing_settings', 'device_status', 'locations',
                           'places', 'place_events', 'expenses', 'expense_guesses', 'moods', 'comforts',
                           'anniversaries', 'events', 'cycle_settings', 'cycle_periods', 'activity_log']
  loop
    execute format('create trigger %I after insert or update or delete on public.%I
                    for each row execute function public.tg_changed()', 'realtime_' || t, t);
  end loop;
end $$;
revoke execute on function public.tg_changed() from public, anon, authenticated;

-- 加進 Supabase Realtime 的 publication（Supabase 專案本來就有 supabase_realtime）
do $$
begin
  if not exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
    create publication supabase_realtime;
  end if;
  if not exists (select 1 from pg_publication_tables
                 where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'notification_outbox') then
    alter publication supabase_realtime add table public.notification_outbox;
  end if;
  if not exists (select 1 from pg_publication_tables
                 where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'realtime_events') then
    alter publication supabase_realtime add table public.realtime_events;
  end if;
end $$;

-- >>> migrations/20261015000003_api.sql

-- =============================================================================
-- App 用的 API：PostgREST RPC 函式
--
-- App 呼叫 POST {url}/rest/v1/rpc/app_<名稱>，JSON body 原封不動傳進唯一的 jsonb 參數（$1）。
-- 回傳格式和舊版自架伺服器的 REST API 完全相同（欄位 camelCase），App 的資料模型不用改。
-- 除了少數標明 security definer 的函式，一律以登入者身分執行，權限由 Row Level Security 決定。
-- 錯誤：raise exception '<代碼>'（P0001），App 依代碼顯示中文訊息；資料格式錯誤保留原本的 SQLSTATE。
-- 時間：每個函式都 set timezone to 'UTC'，時間戳記輸出成 "2026-10-15T02:16:21.531234+00:00"。
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 共用小工具（純函式，不碰資料）
-- -----------------------------------------------------------------------------

-- snake_case → camelCase（與舊伺服器 camel() 相同：只轉「底線 + 小寫字母」）
create or replace function public.camel_key(p_key text) returns text
language sql immutable parallel safe set search_path = public as $$
  select coalesce(string_agg(case when t.i = 1 then t.part
                                  when t.part ~ '^[a-z]' then upper(left(t.part, 1)) || substr(t.part, 2)
                                  else '_' || t.part end, '' order by t.i), '')
  from unnest(string_to_array(p_key, '_')) with ordinality as t(part, i)
$$;

-- 物件的第一層欄位轉 camelCase；裡面的 jsonb（通知 data、歷史 payload…）保持原樣。陣列逐筆轉換。
create or replace function public.camel(p jsonb) returns jsonb
language plpgsql immutable parallel safe set search_path = public as $$
begin
  case jsonb_typeof(p)
    when 'object' then
      return coalesce((select jsonb_object_agg(camel_key(e.key), e.value) from jsonb_each(p) e), '{}'::jsonb);
    when 'array' then
      return coalesce((select jsonb_agg(camel(e.value) order by e.i)
                       from jsonb_array_elements(p) with ordinality as e(value, i)), '[]'::jsonb);
    else
      return p;
  end case;
end $$;

-- 查詢結果（jsonb_agg 的陣列）轉 camelCase；沒有資料（null）時回傳 []
create or replace function public.camel_rows(p jsonb) returns jsonb
language sql immutable parallel safe set search_path = public as $$
  select coalesce(camel(p), '[]'::jsonb)
$$;

-- JSON 陣列 → text[]（null 或沒給 → null）
create or replace function public.j_arr(p jsonb) returns text[]
language sql immutable parallel safe set search_path = public as $$
  select case when p is null or jsonb_typeof(p) = 'null' then null
              else array(select e.value from jsonb_array_elements_text(p) with ordinality as e(value, i) order by e.i) end
$$;

-- JSON 數字或數字字串 → numeric；其他 → null（對應舊版 Number(x) || 預設值）
create or replace function public.jnum(p jsonb) returns numeric
language sql immutable parallel safe set search_path = public as $$
  select case jsonb_typeof(p)
    when 'number' then (p #>> '{}')::numeric
    when 'string' then case when (p #>> '{}') ~ '^\s*[-+]?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?\s*$'
                            then trim(p #>> '{}')::numeric end
  end
$$;

-- JavaScript 的 !!value
create or replace function public.truthy(p jsonb) returns boolean
language sql immutable parallel safe set search_path = public as $$
  select case jsonb_typeof(p)
    when 'boolean' then (p #>> '{}')::boolean
    when 'number' then (p #>> '{}')::numeric <> 0
    when 'string' then (p #>> '{}') <> ''
    when 'object' then true
    when 'array' then true
    else false end
$$;

-- 各種資料可以由 App 設定的欄位：'camelKey:欄位:型別'
create or replace function public.api_fields(p_kind text) returns text[]
language sql immutable parallel safe set search_path = public as $$
  select case p_kind
    when 'profile' then array['displayName:display_name:text', 'birthday:birthday:date', 'avatarUrl:avatar_url:text']
    when 'sharing' then array['location:share_location:boolean', 'device:share_device:boolean',
                              'expenses:share_expenses:boolean', 'mood:share_mood:boolean',
                              'cycle:share_cycle:boolean', 'hiddenCategories:hidden_categories:text[]']
    when 'place' then array['name:name:text', 'icon:icon:text', 'lat:lat:double precision', 'lng:lng:double precision',
                            'radiusM:radius_m:integer', 'notifyArrive:notify_arrive:boolean', 'notifyLeave:notify_leave:boolean']
    when 'expense' then array['amount:amount:numeric', 'merchant:merchant:text', 'category:category:text',
                              'source:source:text', 'occurredAt:occurred_at:timestamptz', 'visibility:visibility:text',
                              'revealOn:reveal_on:date', 'guessable:guessable:boolean', 'dedupeKey:dedupe_key:text']
    when 'anniversary' then array['title:title:text', 'date:date:date', 'kind:kind:text', 'repeat:repeat:text',
                                  'calendar:calendar:text', 'remindDays:remind_days:smallint[]']
    when 'event' then array['title:title:text', 'startsAt:starts_at:timestamptz', 'endsAt:ends_at:timestamptz',
                            'allDay:all_day:boolean', 'placeId:place_id:uuid', 'locationText:location_text:text',
                            'note:note:text', 'remindMinutes:remind_minutes:smallint[]']
    when 'cycle_settings' then array['cycleLength:cycle_length:smallint', 'periodLength:period_length:smallint',
                                     'lutealLength:luteal_length:smallint', 'partnerCanEdit:partner_can_edit:boolean',
                                     'careReminders:care_reminders:boolean']
    when 'period' then array['startDate:start_date:date', 'endDate:end_date:date']
  end
$$;

-- 依 body 裡「有出現」的欄位組 SQL 片段（同舊版 buildSet / insertParts）。
-- 值一律在 SQL 裡從 $1（整個 body）取出再轉型，不會把使用者輸入拼進 SQL。
create or replace function public.body_fields(p_body jsonb, p_specs text[], out cols text, out vals text, out sets text)
language sql immutable parallel safe set search_path = public as $$
  with f as (
    select s.i, split_part(s.spec, ':', 1) as k, split_part(s.spec, ':', 2) as col, split_part(s.spec, ':', 3) as typ
    from unnest(p_specs) with ordinality as s(spec, i)
  ), e as (
    select f.i, quote_ident(f.col) as col,
           case
             when f.typ in ('smallint[]', 'integer[]') then format('public.j_arr($1->%L)::numeric[]::%s', f.k, f.typ)
             when f.typ like '%[]' then format('public.j_arr($1->%L)::%s', f.k, f.typ)
             when f.typ in ('smallint', 'integer') then format('($1->>%L)::numeric::%s', f.k, f.typ)
             else format('($1->>%L)::%s', f.k, f.typ)
           end as expr
    from f
    where jsonb_typeof(p_body) = 'object' and p_body ? f.k
  )
  select string_agg(e.col, ', ' order by e.i), string_agg(e.expr, ', ' order by e.i),
         string_agg(e.col || ' = ' || e.expr, ', ' order by e.i)
  from e
$$;

-- 查詢區間（同舊版 range()）：沒給 to 是明天此刻，沒給 from 是 to 往前 p_days 天
create or replace function public.body_range(p_body jsonb, p_days integer, out p_from timestamptz, out p_to timestamptz)
language plpgsql stable set search_path = public set timezone to 'UTC' as $$
begin
  p_to := coalesce(nullif(p_body->>'to', '')::timestamptz, now() + interval '1 day');
  p_from := coalesce(nullif(p_body->>'from', '')::timestamptz, p_to - make_interval(days => p_days));
exception when data_exception then
  raise exception 'invalid_range';
end $$;

-- 地點「偵測誰」：App 傳 me / partner / both，資料庫存使用者 id（null = 兩人）
create or replace function public.watch_target(p_watch text) returns uuid
language sql stable set search_path = public as $$
  select case p_watch when 'me' then auth.uid() when 'partner' then partner_id() end
$$;

create or replace function public.notif_kinds() returns text[]
language sql immutable parallel safe set search_path = public as $$
  select array['pairing', 'place', 'mood', 'comfort', 'battery_low', 'guess', 'event', 'anniversary', 'cycle_care', 'sharing_changed']
$$;


-- -----------------------------------------------------------------------------
-- 健康檢查（還沒登入也能呼叫，App 用來測試網址和金鑰）
-- -----------------------------------------------------------------------------

create or replace function public.app_health(jsonb default '{}') returns jsonb
language sql stable set search_path = public set timezone to 'UTC' as $$
  select jsonb_build_object('ok', true, 'time', now())
$$;


-- -----------------------------------------------------------------------------
-- 我、對方、配對
-- -----------------------------------------------------------------------------

-- security definer：要讀 auth.users 的 Email，以及對方的手機最後回報時間（判斷是否在線上）
create or replace function public.app_me(jsonb default '{}') returns jsonb
language plpgsql stable security definer set search_path = public set timezone to 'UTC' as $$
declare
  v_uid     uuid := auth.uid();
  v_couple  uuid;
  v_partner uuid;
begin
  if v_uid is null then raise exception 'not_authenticated'; end if;
  v_couple := couple_of(v_uid);
  if v_couple is not null then v_partner := partner_of(v_uid); end if;
  return jsonb_build_object(
    'user', coalesce(camel((select to_jsonb(p) from (select id, display_name, avatar_url, birthday
                                                     from profiles where id = v_uid) p)), '{}'::jsonb)
            || jsonb_build_object('email', (select u.email from auth.users u where u.id = v_uid)),
    'couple', camel((select to_jsonb(c) from (select id, together_since, created_at from couples where id = v_couple) c)),
    'partner', camel((select to_jsonb(p) from (select id, display_name, avatar_url, birthday
                                               from profiles where id = v_partner) p)),
    'mySharing', camel((select to_jsonb(s) from sharing_settings s where s.user_id = v_uid)),
    'partnerSharing', camel((select to_jsonb(s) from sharing_settings s where s.user_id = v_partner)),
    'partnerOnline', coalesce((select d.updated_at > now() - interval '10 minutes'
                               from device_status d where d.user_id = v_partner), false));
end $$;

create or replace function public.app_update_profile(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
declare
  v_set text := (select sets from body_fields($1, api_fields('profile')));
  v_row jsonb;
begin
  if v_set is null then raise exception 'nothing_to_update'; end if;
  execute format('update profiles set %s where id = auth.uid() returning jsonb_build_object(
                    ''id'', id, ''display_name'', display_name, ''avatar_url'', avatar_url, ''birthday'', birthday)', v_set)
    into v_row using $1;
  return camel(v_row);
end $$;

create or replace function public.app_update_couple(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
declare v_row jsonb;
begin
  update couples set together_since = ($1->>'togetherSince')::date where id = my_couple_id()
  returning jsonb_build_object('id', id, 'together_since', together_since, 'created_at', created_at) into v_row;
  if v_row is null then raise exception 'not_paired'; end if;
  return camel(v_row);
end $$;

create or replace function public.app_create_invite(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
declare v_code text := create_invite();
begin
  return (select camel(jsonb_build_object('code', code, 'expires_at', expires_at)) from pair_invites where code = v_code);
end $$;

create or replace function public.app_accept_invite(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
declare v_couple uuid := accept_invite(coalesce($1->>'code', ''));
begin
  if nullif($1->>'togetherSince', '') is not null then
    update couples set together_since = ($1->>'togetherSince')::date where id = v_couple;
  end if;
  return jsonb_build_object('coupleId', v_couple);
end $$;

create or replace function public.app_unpair(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
begin
  perform unpair();
  return jsonb_build_object('ok', true);
end $$;


-- -----------------------------------------------------------------------------
-- 分享設定
-- -----------------------------------------------------------------------------

create or replace function public.app_update_sharing(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
declare
  v_set text := (select sets from body_fields($1, api_fields('sharing')));
  v_row jsonb;
begin
  if v_set is null then raise exception 'nothing_to_update'; end if;
  execute format('update sharing_settings t set %s, updated_at = now() where t.user_id = auth.uid() returning to_jsonb(t)', v_set)
    into v_row using $1;
  return camel(v_row);
end $$;

create or replace function public.app_pause_sharing(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
begin
  return jsonb_build_object('pausedUntil', pause_sharing(coalesce(jnum($1->'minutes'), 0)::integer));
end $$;


-- -----------------------------------------------------------------------------
-- 手機狀態與位置
-- -----------------------------------------------------------------------------

create or replace function public.app_report_device(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
begin
  insert into device_status (user_id, battery_level, is_charging, network, network_label, notifications_silenced,
                             steps_today, last_active_at, device_model, updated_at)
  values (auth.uid(), ($1->>'batteryLevel')::numeric::smallint, ($1->>'isCharging')::boolean, $1->>'network',
          $1->>'networkLabel', ($1->>'notificationsSilenced')::boolean, ($1->>'stepsToday')::numeric::integer,
          nullif($1->>'lastActiveAt', '')::timestamptz, $1->>'deviceModel', now())
  on conflict (user_id) do update set
    battery_level = excluded.battery_level, is_charging = excluded.is_charging, network = excluded.network,
    network_label = excluded.network_label, notifications_silenced = excluded.notifications_silenced,
    steps_today = excluded.steps_today, last_active_at = coalesce(excluded.last_active_at, device_status.last_active_at),
    device_model = excluded.device_model, updated_at = now();
  return jsonb_build_object('ok', true);
end $$;

create or replace function public.app_report_location(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
begin
  insert into locations (user_id, lat, lng, accuracy_m, speed_mps, activity, recorded_at, updated_at)
  values (auth.uid(), ($1->>'lat')::double precision, ($1->>'lng')::double precision, ($1->>'accuracyM')::real,
          ($1->>'speedMps')::real, nullif($1->>'activity', ''),
          coalesce(nullif($1->>'recordedAt', '')::timestamptz, now()), now())
  on conflict (user_id) do update set lat = excluded.lat, lng = excluded.lng, accuracy_m = excluded.accuracy_m,
    speed_mps = excluded.speed_mps, activity = excluded.activity, recorded_at = excluded.recorded_at, updated_at = now();
  return jsonb_build_object('ok', true);
end $$;

create or replace function public.app_partner_status(jsonb default '{}') returns jsonb
language sql stable set search_path = public set timezone to 'UTC' as $$
  select jsonb_build_object(
    'device', camel((select to_jsonb(d) from device_status d where d.user_id = partner_id())),
    'location', camel((select to_jsonb(l) from locations l where l.user_id = partner_id())))
$$;

-- 「現在在哪？」：請對方的手機立刻回報一次位置（對方 App 經由 Realtime 收到 locate）
create or replace function public.app_locate_partner(jsonb default '{}') returns jsonb
language plpgsql security definer set search_path = public set timezone to 'UTC' as $$
declare
  v_uid     uuid := auth.uid();
  v_partner uuid;
begin
  if v_uid is null then raise exception 'not_authenticated'; end if;
  v_partner := partner_of(v_uid);
  if v_partner is null then raise exception 'not_paired'; end if;
  insert into realtime_events (user_id, kind, by_user) values (v_partner, 'locate', v_uid);
  return jsonb_build_object('sent', true);
end $$;


-- -----------------------------------------------------------------------------
-- 地點標記
-- -----------------------------------------------------------------------------

create or replace function public.app_places(jsonb default '{}') returns jsonb
language sql stable set search_path = public set timezone to 'UTC' as $$
  select camel_rows((select jsonb_agg(to_jsonb(p) order by p.created_at) from places p))
$$;

create or replace function public.app_create_place(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
declare
  f     record;
  v_row jsonb;
begin
  select * into f from body_fields($1, api_fields('place'));
  execute format('insert into places as t (couple_id, watch_user%s)
                  values (my_couple_id(), watch_target(coalesce(nullif($1->>''watch'', ''''), ''partner''))%s)
                  returning to_jsonb(t)', coalesce(', ' || f.cols, ''), coalesce(', ' || f.vals, ''))
    into v_row using $1;
  return camel(v_row);
end $$;

create or replace function public.app_update_place(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
declare
  v_set text := concat_ws(', ', (select sets from body_fields($1, api_fields('place'))),
                          case when jsonb_typeof($1) = 'object' and $1 ? 'watch' then 'watch_user = watch_target($1->>''watch'')' end);
  v_row jsonb;
begin
  if v_set = '' then raise exception 'nothing_to_update'; end if;
  execute format('update places t set %s where t.id = ($1->>''id'')::uuid returning to_jsonb(t)', v_set) into v_row using $1;
  if v_row is null then raise exception 'not_found'; end if;
  return camel(v_row);
end $$;

create or replace function public.app_delete_place(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
begin
  delete from places where id = ($1->>'id')::uuid;
  if not found then raise exception 'not_found'; end if;
  return jsonb_build_object('ok', true);
end $$;

create or replace function public.app_place_event(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
declare v_row jsonb;
begin
  if coalesce($1->>'kind', '') not in ('arrive', 'leave') then raise exception 'invalid_kind'; end if;
  insert into place_events as t (place_id, kind, occurred_at)
  values (($1->>'id')::uuid, $1->>'kind', coalesce(nullif($1->>'occurredAt', '')::timestamptz, now()))
  returning to_jsonb(t) into v_row;
  return camel(v_row);
end $$;


-- -----------------------------------------------------------------------------
-- 開銷與猜花費
-- -----------------------------------------------------------------------------

create or replace function public.app_expenses(jsonb default '{}') returns jsonb
language plpgsql stable set search_path = public set timezone to 'UTC' as $$
declare r record := body_range($1, 31);
begin
  return camel_rows((
    select jsonb_agg(to_jsonb(e) || jsonb_build_object('their_guess', g.guess_amount, 'their_points', g.points)
                     order by e.occurred_at desc)
    from expenses e
    left join expense_guesses g on g.expense_id = e.id and g.guesser_id = partner_id()
    where e.user_id = auth.uid() and e.occurred_at >= r.p_from and e.occurred_at < r.p_to));
end $$;

-- 自動記帳同一筆交易可能進來兩次（通知、發票）：dedupeKey 重複時回傳 {duplicate: true}
create or replace function public.app_create_expense(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
declare
  v_body jsonb := jsonb_build_object('occurredAt', now())
                  || case when jsonb_typeof($1) = 'object' then $1 else '{}'::jsonb end;
  f      record;
  v_row  jsonb;
begin
  select * into f from body_fields(v_body, api_fields('expense'));
  execute format('insert into expenses as t (user_id, %s) values (auth.uid(), %s)
                  on conflict (user_id, dedupe_key) do nothing returning to_jsonb(t)', f.cols, f.vals)
    into v_row using v_body;
  return coalesce(camel(v_row), jsonb_build_object('duplicate', true));
end $$;

create or replace function public.app_update_expense(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
declare
  v_set text := (select sets from body_fields($1, api_fields('expense')));
  v_row jsonb;
begin
  if v_set is null then raise exception 'nothing_to_update'; end if;
  execute format('update expenses t set %s where t.id = ($1->>''id'')::uuid returning to_jsonb(t)', v_set) into v_row using $1;
  if v_row is null then raise exception 'not_found'; end if;
  return camel(v_row);
end $$;

create or replace function public.app_delete_expense(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
begin
  delete from expenses where id = ($1->>'id')::uuid;
  if not found then raise exception 'not_found'; end if;
  return jsonb_build_object('ok', true);
end $$;

create or replace function public.app_partner_expenses(jsonb default '{}') returns jsonb
language plpgsql stable set search_path = public set timezone to 'UTC' as $$
declare r record := body_range($1, 31);
begin
  return camel_rows((select jsonb_agg(to_jsonb(x) order by x.occurred_at desc) from partner_expenses(r.p_from, r.p_to) x));
end $$;

create or replace function public.app_guess(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
begin
  return (select camel(to_jsonb(g)) from submit_guess(($1->>'id')::uuid, ($1->>'amount')::numeric) g);
end $$;


-- -----------------------------------------------------------------------------
-- 心情與安撫
-- -----------------------------------------------------------------------------

create or replace function public.app_moods(jsonb default '{}') returns jsonb
language plpgsql stable set search_path = public set timezone to 'UTC' as $$
declare v_days integer := least(366, greatest(1, coalesce(nullif(jnum($1->'days'), 0), 7)))::integer;
begin
  return camel_rows((select jsonb_agg(to_jsonb(m) order by m.created_at desc) from moods m
                     where m.created_at > now() - make_interval(days => v_days)));
end $$;

create or replace function public.app_post_mood(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
declare v_row jsonb;
begin
  insert into moods as t (mood, note) values ($1->>'mood', nullif($1->>'note', '')) returning to_jsonb(t) into v_row;
  return camel(v_row);
end $$;

create or replace function public.app_comforts(jsonb default '{}') returns jsonb
language plpgsql stable set search_path = public set timezone to 'UTC' as $$
declare v_limit integer := least(200, greatest(1, coalesce(nullif(jnum($1->'limit'), 0), 50)))::integer;
begin
  return camel_rows((select jsonb_agg(to_jsonb(c) order by c.created_at desc)
                     from (select * from comforts order by created_at desc limit v_limit) c));
end $$;

create or replace function public.app_send_comfort(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
declare v_row jsonb;
begin
  insert into comforts as t (couple_id, receiver_id, kind, message)
  values (my_couple_id(), partner_id(), $1->>'kind', nullif($1->>'message', ''))
  returning to_jsonb(t) into v_row;
  return camel(v_row);
end $$;

create or replace function public.app_read_comfort(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
begin
  update comforts set read_at = now() where id = ($1->>'id')::bigint and read_at is null;
  return jsonb_build_object('ok', true);
end $$;


-- -----------------------------------------------------------------------------
-- 紀念日與約會
-- -----------------------------------------------------------------------------

create or replace function public.app_anniversaries(jsonb default '{}') returns jsonb
language sql stable set search_path = public set timezone to 'UTC' as $$
  select camel_rows((select jsonb_agg(to_jsonb(a) order by a.date) from anniversaries a))
$$;

create or replace function public.app_create_anniversary(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
declare
  f     record;
  v_row jsonb;
begin
  select * into f from body_fields($1, api_fields('anniversary'));
  execute format('insert into anniversaries as t (couple_id%s) values (my_couple_id()%s) returning to_jsonb(t)',
                 coalesce(', ' || f.cols, ''), coalesce(', ' || f.vals, ''))
    into v_row using $1;
  return camel(v_row);
end $$;

create or replace function public.app_update_anniversary(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
declare
  v_set text := (select sets from body_fields($1, api_fields('anniversary')));
  v_row jsonb;
begin
  if v_set is null then raise exception 'nothing_to_update'; end if;
  execute format('update anniversaries t set %s where t.id = ($1->>''id'')::uuid returning to_jsonb(t)', v_set)
    into v_row using $1;
  if v_row is null then raise exception 'not_found'; end if;
  return camel(v_row);
end $$;

create or replace function public.app_delete_anniversary(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
begin
  delete from anniversaries where id = ($1->>'id')::uuid;
  if not found then raise exception 'not_found'; end if;
  return jsonb_build_object('ok', true);
end $$;

create or replace function public.app_events(jsonb default '{}') returns jsonb
language plpgsql stable set search_path = public set timezone to 'UTC' as $$
declare
  v_from timestamptz := coalesce(nullif($1->>'from', '')::timestamptz, now() - interval '62 days');
  v_to   timestamptz := coalesce(nullif($1->>'to', '')::timestamptz, now() + interval '366 days');
begin
  return camel_rows((select jsonb_agg(to_jsonb(e) order by e.starts_at) from events e
                     where e.starts_at >= v_from and e.starts_at < v_to));
end $$;

create or replace function public.app_create_event(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
declare
  f     record;
  v_row jsonb;
begin
  select * into f from body_fields($1, api_fields('event'));
  execute format('insert into events as t (couple_id%s) values (my_couple_id()%s) returning to_jsonb(t)',
                 coalesce(', ' || f.cols, ''), coalesce(', ' || f.vals, ''))
    into v_row using $1;
  return camel(v_row);
end $$;

-- 改了時間就重新提醒
create or replace function public.app_update_event(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
declare
  v_set text := (select sets from body_fields($1, api_fields('event')));
  v_row jsonb;
begin
  if v_set is null then raise exception 'nothing_to_update'; end if;
  execute format('update events t set %s, updated_at = now(), reminded_at = null
                  where t.id = ($1->>''id'')::uuid returning to_jsonb(t)', v_set)
    into v_row using $1;
  if v_row is null then raise exception 'not_found'; end if;
  return camel(v_row);
end $$;

create or replace function public.app_answer_event(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
declare v_row jsonb;
begin
  if coalesce($1->>'status', '') not in ('confirmed', 'declined', 'cancelled') then raise exception 'invalid_status'; end if;
  update events t set status = $1->>'status', updated_at = now() where t.id = ($1->>'id')::uuid
  returning to_jsonb(t) into v_row;
  if v_row is null then raise exception 'not_found'; end if;
  return camel(v_row);
end $$;

create or replace function public.app_delete_event(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
begin
  delete from events where id = ($1->>'id')::uuid;
  if not found then raise exception 'not_found' using detail = '只有建立約會的人可以刪除'; end if;
  return jsonb_build_object('ok', true);
end $$;


-- -----------------------------------------------------------------------------
-- 經期
-- -----------------------------------------------------------------------------

create or replace function public.app_cycle(jsonb default '{}') returns jsonb
language sql stable set search_path = public set timezone to 'UTC' as $$
  select jsonb_build_object(
    'mine', jsonb_build_object(
      'settings', camel((select to_jsonb(s) from cycle_settings s where s.user_id = auth.uid())),
      'periods', camel_rows((select jsonb_agg(to_jsonb(p) order by p.start_date) from cycle_periods p
                             where p.user_id = auth.uid())),
      'logs', camel_rows((select jsonb_agg(to_jsonb(l) order by l.log_date) from cycle_logs l
                          where l.user_id = auth.uid() and l.log_date > current_date - 120))),
    'partner', jsonb_build_object(
      'settings', camel((select to_jsonb(s) from cycle_settings s where s.user_id = partner_id())),
      'periods', camel_rows((select jsonb_agg(to_jsonb(p) order by p.start_date) from cycle_periods p
                             where p.user_id = partner_id()))))
$$;

create or replace function public.app_save_cycle_settings(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
declare v_set text := (select sets from body_fields($1, api_fields('cycle_settings')));
begin
  insert into cycle_settings (user_id) values (auth.uid()) on conflict (user_id) do nothing;
  if v_set is not null then
    execute format('update cycle_settings set %s, updated_at = now() where user_id = auth.uid()', v_set) using $1;
  end if;
  return camel((select to_jsonb(s) from cycle_settings s where s.user_id = auth.uid()));
end $$;

create or replace function public.app_stop_cycle(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
begin
  delete from cycle_settings where user_id = auth.uid();
  return jsonb_build_object('ok', true);
end $$;

-- owner = 'partner'：對方允許時幫忙記錄
create or replace function public.app_add_period(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
declare v_row jsonb;
begin
  insert into cycle_periods as t (user_id, start_date, end_date)
  values (case when coalesce(nullif($1->>'owner', ''), 'me') = 'partner' then partner_id() else auth.uid() end,
          ($1->>'startDate')::date, nullif($1->>'endDate', '')::date)
  on conflict (user_id, start_date) do update set end_date = excluded.end_date
  returning to_jsonb(t) into v_row;
  return camel(v_row);
end $$;

create or replace function public.app_update_period(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
declare
  v_set text := (select sets from body_fields($1, api_fields('period')));
  v_row jsonb;
begin
  if v_set is null then raise exception 'nothing_to_update'; end if;
  execute format('update cycle_periods t set %s where t.id = ($1->>''id'')::uuid returning to_jsonb(t)', v_set)
    into v_row using $1;
  if v_row is null then raise exception 'not_found'; end if;
  return camel(v_row);
end $$;

create or replace function public.app_delete_period(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
begin
  delete from cycle_periods where id = ($1->>'id')::uuid;
  if not found then raise exception 'not_found'; end if;
  return jsonb_build_object('ok', true);
end $$;

create or replace function public.app_save_cycle_log(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
declare v_row jsonb;
begin
  insert into cycle_logs as t (user_id, log_date, flow, symptoms, note)
  values (auth.uid(), ($1->>'date')::date, nullif($1->>'flow', ''), coalesce(j_arr($1->'symptoms'), '{}'),
          nullif($1->>'note', ''))
  on conflict (user_id, log_date) do update set flow = excluded.flow, symptoms = excluded.symptoms, note = excluded.note
  returning to_jsonb(t) into v_row;
  return camel(v_row);
end $$;


-- -----------------------------------------------------------------------------
-- 歷史紀錄、通知設定
-- -----------------------------------------------------------------------------

-- kinds：["mood", "comfort"]（也接受舊版的逗號字串）
create or replace function public.app_history(jsonb default '{}') returns jsonb
language plpgsql stable set search_path = public set timezone to 'UTC' as $$
declare
  v_before bigint := nullif($1->>'before', '')::bigint;
  v_limit  integer := least(200, greatest(1, coalesce(nullif(jnum($1->'limit'), 0), 50)))::integer;
  v_kinds  text[] := case jsonb_typeof($1->'kinds')
                       when 'array' then nullif(j_arr($1->'kinds'), '{}')
                       when 'string' then array(select k from unnest(string_to_array(nullif($1->>'kinds', ''), ',')) k
                                                where k <> '')
                     end;
begin
  if jsonb_typeof($1->'kinds') = 'string' and $1->>'kinds' = '' then v_kinds := null; end if;
  return camel_rows((select jsonb_agg(to_jsonb(a) order by a.id desc)
                     from (select * from activity_log
                           where (v_before is null or id < v_before) and (v_kinds is null or kind = any (v_kinds))
                           order by id desc limit v_limit) a));
end $$;

create or replace function public.app_notification_prefs(jsonb default '{}') returns jsonb
language sql stable set search_path = public set timezone to 'UTC' as $$
  select jsonb_object_agg(k.kind, coalesce(p.enabled, true))
  from unnest(notif_kinds()) as k(kind)
  left join notification_prefs p on p.user_id = auth.uid() and p.kind = k.kind
$$;

create or replace function public.app_set_notification_prefs(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
begin
  insert into notification_prefs (user_id, kind, enabled)
  select auth.uid(), e.key, truthy(e.value)
  from jsonb_each(case when jsonb_typeof($1) = 'object' then $1 else '{}'::jsonb end) e
  where e.key = any (notif_kinds())
  on conflict (user_id, kind) do update set enabled = excluded.enabled;
  return jsonb_build_object('ok', true);
end $$;


-- -----------------------------------------------------------------------------
-- 通知補送：App 連上 Realtime 時先拿這三天還沒收到的，收到後回報 ack
-- -----------------------------------------------------------------------------

create or replace function public.app_pending_notifications(jsonb default '{}') returns jsonb
language plpgsql stable security definer set search_path = public set timezone to 'UTC' as $$
declare v_uid uuid := auth.uid();
begin
  if v_uid is null then raise exception 'not_authenticated'; end if;
  return coalesce((
    select jsonb_agg(jsonb_build_object('id', n.id, 'kind', n.kind, 'title', n.title, 'body', n.body,
                                        'data', n.data, 'createdAt', n.created_at) order by n.id)
    from notification_outbox n
    where n.recipient_id = v_uid and n.sent_at is null and n.created_at > now() - interval '3 days'), '[]'::jsonb);
end $$;

create or replace function public.app_ack_notifications(jsonb default '{}') returns jsonb
language plpgsql security definer set search_path = public set timezone to 'UTC' as $$
declare v_uid uuid := auth.uid();
begin
  if v_uid is null then raise exception 'not_authenticated'; end if;
  update notification_outbox set sent_at = now()
  where recipient_id = v_uid and sent_at is null
    and id = any (coalesce(j_arr(case when jsonb_typeof($1->'ids') = 'array' then $1->'ids' end)::numeric[]::bigint[], '{}'));
  return jsonb_build_object('ok', true);
end $$;


-- -----------------------------------------------------------------------------
-- 權限：Supabase 預設讓每個人都能執行 public 的函式，這裡全部收回再逐一開放
-- -----------------------------------------------------------------------------

do $$
declare f regprocedure;
begin
  for f in
    select p.oid::regprocedure from pg_proc p
    where p.pronamespace = 'public'::regnamespace
      and not exists (select 1 from pg_depend d where d.objid = p.oid and d.deptype = 'e')  -- 不動擴充套件的函式
  loop
    execute format('revoke all on function %s from public, anon, authenticated', f);
  end loop;
end $$;

-- App 的 API（登入後）
do $$
declare f regprocedure;
begin
  for f in select p.oid::regprocedure from pg_proc p
           where p.pronamespace = 'public'::regnamespace and p.proname like 'app\_%' and p.proname not like 'app\_job\_%'
  loop
    execute format('grant execute on function %s to authenticated', f);
  end loop;
end $$;
grant execute on function public.app_health(jsonb) to anon;

-- 沒登入（anon）不能直接讀寫任何資料表；登入後也要通過 Row Level Security
revoke all on all tables in schema public from anon;
revoke all on all sequences in schema public from anon;
-- 登入後的資料表權限明確給，不依賴 Supabase 的預設權限（專案關掉「自動公開新資料表」也能用）；
-- 每一列能不能讀寫仍由 Row Level Security 決定
grant select, insert, update, delete on all tables in schema public to authenticated;
grant usage, select on all sequences in schema public to authenticated;

-- 欄位層級的權限：Row Level Security 只管「哪幾列」，管不到「哪幾欄」。
-- 下面這些表只開放 App 真的會改的欄位（和以登入者身分執行的 app_* 函式用到的一樣），
-- 擁有者、建立者、建立時間、配對結束時間這些欄位寫入後就不能再改，也不能用直接打 API 的方式偽造。
-- （先收回整張表的權限再給欄位；security definer 的函式與排程以擁有者身分執行，不受影響）
revoke insert, update on public.comforts from authenticated;
revoke update on public.couples, public.sharing_settings, public.places, public.anniversaries,
                 public.events, public.cycle_periods from authenticated;
-- 配對：只能改交往紀念日（ended_at 只有 unpair() 能設定，排程會依它清除解除配對 30 天後的資料）
grant update (together_since) on public.couples to authenticated;
-- 分享設定：暫停只能經由 pause_sharing()（最多 24 小時）
grant update (share_location, share_device, share_expenses, share_mood, share_cycle, hidden_categories, updated_at)
  on public.sharing_settings to authenticated;
-- 安撫：寄件人與時間由資料庫填（防洗版的次數限制才算得準）；收件人只能標記已讀，不能改內容
grant insert (couple_id, receiver_id, kind, message) on public.comforts to authenticated;
grant update (read_at) on public.comforts to authenticated;
grant update (name, icon, lat, lng, radius_m, notify_arrive, notify_leave, watch_user) on public.places to authenticated;
grant update (title, date, kind, repeat, calendar, remind_days) on public.anniversaries to authenticated;
grant update (title, starts_at, ends_at, all_day, place_id, location_text, note, status, remind_minutes, updated_at, reminded_at)
  on public.events to authenticated;
-- 經期：對方幫忙記錄時只能改日期，不能把紀錄「搬」到自己名下再刪掉
grant update (start_date, end_date) on public.cycle_periods to authenticated;

-- Row Level Security policy 與上面以登入者身分執行的函式會用到的
grant execute on function
  public.my_couple_id(), public.partner_id(), public.is_partner(uuid), public.partner_shares(uuid, text), public.i_share(text),
  public.create_invite(), public.accept_invite(text), public.unpair(), public.pause_sharing(integer),
  public.partner_expenses(timestamptz, timestamptz), public.submit_guess(uuid, numeric),
  public.camel_key(text), public.camel(jsonb), public.camel_rows(jsonb), public.j_arr(jsonb), public.jnum(jsonb),
  public.truthy(jsonb), public.api_fields(text), public.body_fields(jsonb, text[]), public.body_range(jsonb, integer),
  public.watch_target(text), public.notif_kinds()
to authenticated;

-- >>> migrations/20261015000004_jobs.sql

-- =============================================================================
-- 排程（pg_cron，在資料庫裡執行，不需要另外的伺服器）
--   xlk-minutely  每分鐘    約會提醒
--   xlk-daily     每 10 分鐘 當地時間 09:00 之後執行一次：紀念日、交往天數、經期關懷提醒
--   xlk-cleanup   每小時    清掉過期的即時事件與舊通知
-- 排程函式以資料表擁有者（postgres）身分執行，不受 Row Level Security 限制；App 不能呼叫。
-- =============================================================================

create extension if not exists pg_cron;


-- -----------------------------------------------------------------------------
-- 經期預測：prototype/core.js 的 cycleStats + cycleStarts + predictCycle 逐行移植
-- （不含 today 的階段判斷 dayPhase；排程只需要下次經期與延遲天數）
-- p_starts / p_ends 是同樣長度的陣列（每次經期的開始日、結束日，結束日可為 null），不必先排序。
-- 沒有任何紀錄時全部回傳 null（對應 predictCycle 回傳 null）。
-- -----------------------------------------------------------------------------

create or replace function public.predict_cycle(
  p_starts date[], p_ends date[],
  p_cycle_length integer, p_period_length integer, p_luteal_length integer,
  p_today date,
  out cycle_length integer, out period_length integer, out luteal_length integer,
  out samples integer, out irregular boolean,
  out cycle_start date, out cycle_day integer,
  out next_start date, out days_until_next integer,
  out ovulation date, out fertile_start date, out fertile_end date,
  out late_days integer)
language plpgsql immutable set search_path = public as $$
declare
  c_window   constant integer := 6;   -- CYCLE_WINDOW：用最近 6 個週期計算平均
  c_project  constant integer := 6;   -- PROJECT_CYCLES：往後推算 6 個週期
  v_starts   date[];
  v_ends     date[];
  v_cycles   integer[] := '{}';
  v_lengths  integer[] := '{}';
  v_all      date[];
  v_last     date;
  v_next     date;
  v_expected date;
  v_cur      date;
  v_nx       date;
  d          date;
  n          integer;
  len        integer;
begin
  -- sortPeriods
  select coalesce(array_agg(u.s order by u.s), '{}'), coalesce(array_agg(u.e order by u.s), '{}')
    into v_starts, v_ends
  from unnest(p_starts, p_ends) as u(s, e)
  where u.s is not null;
  n := cardinality(v_starts);
  if n = 0 then return; end if;

  -- cycleStats：超出 15–60 天的週期、1–10 天以外的經期視為漏記，不納入平均
  for i in 2..n loop
    len := v_starts[i] - v_starts[i - 1];
    if len between 15 and 60 then v_cycles := v_cycles || len; end if;
  end loop;
  v_cycles := v_cycles[greatest(1, cardinality(v_cycles) - c_window + 1):];
  for i in 1..n loop
    if v_ends[i] is not null then
      len := v_ends[i] - v_starts[i] + 1;
      if len between 1 and 10 then v_lengths := v_lengths || len; end if;
    end if;
  end loop;
  v_lengths := v_lengths[greatest(1, cardinality(v_lengths) - c_window + 1):];

  -- 手動指定的長度優先；Math.round = floor(x + 0.5)
  cycle_length := coalesce(nullif(p_cycle_length, 0),
    case when cardinality(v_cycles) > 0 then floor((select avg(x) from unnest(v_cycles) x) + 0.5)::integer else 28 end);
  period_length := coalesce(nullif(p_period_length, 0),
    case when cardinality(v_lengths) > 0 then floor((select avg(x) from unnest(v_lengths) x) + 0.5)::integer else 5 end);
  luteal_length := coalesce(nullif(p_luteal_length, 0), 14);
  samples := cardinality(v_cycles);
  irregular := samples >= 3
    and (select max(x) - min(x) from unnest(v_cycles) x) > 7;

  -- cycleStarts：已記錄的開始日 + 推算的開始日；超過一整個週期沒記錄，把預測推到今天之後
  v_last := v_starts[n];
  v_next := v_last + cycle_length;
  while p_today - v_next >= cycle_length loop
    v_next := v_next + cycle_length;
  end loop;
  v_all := v_starts;
  for k in 1..c_project loop
    v_all := v_all || v_next;
    v_next := v_next + cycle_length;
  end loop;

  -- predictCycle
  foreach d in array v_all loop
    if d <= p_today then v_cur := d; else v_nx := d; exit; end if;
  end loop;
  v_expected := v_last + cycle_length;
  late_days := p_today - v_expected;
  -- 預測日已過、但還在一個週期內：視為延遲，而不是直接跳到下一個週期
  if late_days > 0 and late_days < cycle_length then
    v_cur := v_last;
    v_nx := v_expected;
  else
    late_days := 0;
  end if;

  cycle_start := v_cur;
  cycle_day := p_today - v_cur + 1;
  next_start := v_nx;
  days_until_next := v_nx - p_today;
  ovulation := v_nx - luteal_length;
  fertile_start := ovulation - 5;
  fertile_end := ovulation + 1;
end $$;


-- -----------------------------------------------------------------------------
-- 約會提醒（每分鐘）：已確認、還沒開始、進入提醒時間的約會，通知兩個人
-- -----------------------------------------------------------------------------

create or replace function public.app_job_event_reminders() returns integer
language plpgsql set search_path = public as $$
declare
  v_tz text := coalesce((select timezone from app_config), 'Asia/Taipei');
  e    record;
  n    integer := 0;
begin
  for e in
    update events ev set reminded_at = now()
    from couples c
    where c.id = ev.couple_id and c.ended_at is null
      and ev.status = 'confirmed' and ev.reminded_at is null and ev.starts_at > now()
      and ev.starts_at - make_interval(mins => coalesce(ev.remind_minutes[1], 60)) <= now()
    returning ev.id, ev.couple_id, ev.title, ev.starts_at
  loop
    -- 和舊版 Intl.DateTimeFormat('zh-TW') 相同的格式，例如「10/15 14:00」
    perform enqueue_notification(m.user_id, 'event', '約會提醒：' || e.title,
              to_char(e.starts_at at time zone v_tz, 'FMMM/FMDD HH24:MI') || ' 開始，準備出門囉',
              jsonb_build_object('event_id', e.id))
    from couple_members m
    where m.couple_id = e.couple_id and m.left_at is null;
    n := n + 1;
  end loop;
  return n;
end $$;


-- -----------------------------------------------------------------------------
-- 經期關懷：預計 0–2 天內開始、而且沒有延遲時，提醒對方；同一次經期只提醒一次
-- -----------------------------------------------------------------------------

create or replace function public.app_job_cycle_care(p_today date) returns integer
language plpgsql set search_path = public as $$
declare
  o    record;
  pr   record;
  sent integer := 0;
begin
  for o in
    select s.*, partner_of(s.user_id) as partner, display_name_of(s.user_id) as name
    from cycle_settings s
    where s.care_reminders and shares(s.user_id, 'cycle') and partner_of(s.user_id) is not null
  loop
    select * into pr from predict_cycle(
      array(select p.start_date from cycle_periods p where p.user_id = o.user_id order by p.start_date),
      array(select p.end_date from cycle_periods p where p.user_id = o.user_id order by p.start_date),
      o.cycle_length, o.period_length, o.luteal_length, p_today);
    if pr.next_start is null or pr.late_days <> 0 or pr.days_until_next < 0 or pr.days_until_next > 2
       or o.care_sent_for is not distinct from pr.next_start then
      continue;
    end if;
    perform enqueue_notification(o.partner, 'cycle_care',
      o.name || ' 的經期預計' || case when pr.days_until_next = 0 then '今天' else ' ' || pr.days_until_next || ' 天後' end || '開始',
      '準備熱飲和暖暖包，多一點耐心和抱抱',
      jsonb_build_object('next_start', pr.next_start::text));
    update cycle_settings set care_sent_for = pr.next_start where user_id = o.user_id;
    sent := sent + 1;
  end loop;
  return sent;
end $$;


-- -----------------------------------------------------------------------------
-- 每日提醒：同一天只跑一次（job_runs）；p_force 給測試與手動補跑用
-- 回傳 {date, reminders, care}，今天已經跑過則回傳 null
-- -----------------------------------------------------------------------------

create or replace function public.app_job_daily(p_today date, p_force boolean default false) returns jsonb
language plpgsql set search_path = public as $$
declare
  v_first     integer;
  v_reminders integer;
  v_care      integer;
begin
  insert into job_runs (name, run_on) values ('daily', p_today) on conflict do nothing;
  get diagnostics v_first = row_count;
  if v_first = 0 and not coalesce(p_force, false) then return null; end if;
  -- 每一項各自處理錯誤：一筆壞掉的資料只影響那一項，其他提醒照送，今天也不會每 10 分鐘重跑失敗
  begin
    v_reminders := daily_reminders(p_today);
  exception when others then
    raise warning '[xlk] % 紀念日提醒失敗：% (%)', p_today, sqlerrm, sqlstate;
  end;
  begin
    v_care := app_job_cycle_care(p_today);
  exception when others then
    raise warning '[xlk] % 經期關懷提醒失敗：% (%)', p_today, sqlerrm, sqlstate;
  end;
  raise log '[xlk] % 每日提醒：紀念日 %、經期關懷 %', p_today, v_reminders, v_care;
  return jsonb_build_object('date', p_today, 'reminders', v_reminders, 'care', v_care);
end $$;


-- -----------------------------------------------------------------------------
-- 清理（每小時）：即時事件留一天；通知留 30 天
-- （沒送達的通知 App 只補送 3 天內的，超過 30 天的一起清掉，避免沒開 App 時越積越多）
-- -----------------------------------------------------------------------------

create or replace function public.app_job_cleanup() returns jsonb
language plpgsql set search_path = public as $$
declare
  v_events integer;
  v_outbox integer;
begin
  delete from realtime_events where created_at < now() - interval '1 day';
  get diagnostics v_events = row_count;
  delete from notification_outbox where created_at < now() - interval '30 days';
  get diagnostics v_outbox = row_count;
  return jsonb_build_object('realtimeEvents', v_events, 'notifications', v_outbox);
end $$;


-- 排程函式只給資料庫自己（pg_cron）執行
revoke all on function public.predict_cycle(date[], date[], integer, integer, integer, date) from public, anon, authenticated;
revoke all on function public.app_job_event_reminders() from public, anon, authenticated;
revoke all on function public.app_job_cycle_care(date) from public, anon, authenticated;
revoke all on function public.app_job_daily(date, boolean) from public, anon, authenticated;
revoke all on function public.app_job_cleanup() from public, anon, authenticated;


-- -----------------------------------------------------------------------------
-- 排程（可重複執行：同名的排程先移除再建立）
-- -----------------------------------------------------------------------------

do $$
declare j text;
begin
  foreach j in array array['xlk-minutely', 'xlk-daily', 'xlk-cleanup'] loop
    if exists (select 1 from cron.job where jobname = j) then
      perform cron.unschedule(j);
    end if;
  end loop;
end $$;

select cron.schedule('xlk-minutely', '* * * * *', 'select public.app_job_event_reminders()');
-- 每 10 分鐘看一次：當地時間 9 點以後才執行，job_runs 確保一天只跑一次
select cron.schedule('xlk-daily', '*/10 * * * *', $cron$
  select public.app_job_daily((now() at time zone c.timezone)::date)
  from public.app_config c
  where extract(hour from now() at time zone c.timezone) >= 9
$cron$);
select cron.schedule('xlk-cleanup', '23 * * * *', 'select public.app_job_cleanup()');

-- 請 Data API（PostgREST）重新讀取函式清單，App 馬上就能呼叫新的 app_* 函式
notify pgrst, 'reload schema';

-- SQL Editor 會顯示最後一個查詢的結果：看到這一行就代表設定完成
select '設定完成 🎉 回到說明文件做第 3 步' as "小倆口";

commit;
