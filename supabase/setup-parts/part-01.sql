-- 小倆口 Supabase 設定：第 1 份，共 8 份（請照順序執行，每份只要執行一次）
-- 這個檔案由 supabase/scripts/split-setup.js 從 supabase/migrations/ 產生，請勿手動修改。

begin;
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

select '第 1 份完成 ✅ 接著貼第 2 份' as "小倆口";

commit;
