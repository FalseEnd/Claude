begin;
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
create unique index couple_members_one_active on public.couple_members (user_id) where left_at is null;
create table public.pair_invites (
  code         text primary key check (code ~ '^[A-HJ-NP-Z2-9]{6}$'),
  inviter_id   uuid not null references auth.users(id) on delete cascade,
  created_at   timestamptz not null default now(),
  expires_at   timestamptz not null default now() + interval '24 hours',
  accepted_by  uuid references auth.users(id) on delete set null,
  accepted_at  timestamptz
);
create table public.sharing_settings (
  user_id            uuid primary key references auth.users(id) on delete cascade,
  share_location     boolean not null default false,
  share_device       boolean not null default false,
  share_expenses     boolean not null default false,
  share_mood         boolean not null default true,
  share_cycle        boolean not null default false,
  hidden_categories  text[] not null default '{}',
  paused_until       timestamptz,
  updated_at         timestamptz not null default now()
);
create table public.device_status (
  user_id                 uuid primary key references auth.users(id) on delete cascade,
  battery_level           smallint check (battery_level between 0 and 100),
  is_charging             boolean,
  network                 text check (network in ('wifi', 'cellular', 'offline')),
  network_label           text,
  notifications_silenced  boolean,
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
  watch_user     uuid references auth.users(id) on delete cascade,
  created_at     timestamptz not null default now()
);
create index places_couple on public.places (couple_id);
create table public.place_events (
  id           bigint generated always as identity primary key,
  place_id     uuid not null references public.places(id) on delete cascade,
  user_id      uuid not null default auth.uid() references auth.users(id) on delete cascade,
  kind         text not null check (kind in ('arrive', 'leave')),
  occurred_at  timestamptz not null default now()
);
create index place_events_place on public.place_events (place_id, occurred_at desc);
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
  reveal_on    date,
  guessable    boolean not null default false,
  dedupe_key   text,
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
  primary key (expense_id, guesser_id)
);
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
create table public.anniversaries (
  id           uuid primary key default gen_random_uuid(),
  couple_id    uuid not null references public.couples(id) on delete cascade,
  created_by   uuid not null default auth.uid() references auth.users(id) on delete cascade,
  title        text not null check (char_length(title) between 1 and 30),
  date         date not null check (date between '1900-01-01' and '2200-12-31'),
  kind         text not null default 'custom' check (kind in ('together', 'birthday', 'first_date', 'custom')),
  repeat       text not null default 'yearly' check (repeat in ('none', 'yearly')),
  calendar     text not null default 'solar' check (calendar in ('solar', 'lunar')),
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
  reminded_at     timestamptz,
  check (ends_at is null or ends_at >= starts_at),
  check (starts_at between '1900-01-01 00:00+00' and '2200-12-31 23:59:59+00'
         and (ends_at is null or ends_at <= '2200-12-31 23:59:59+00'))
);
create index events_couple_time on public.events (couple_id, starts_at);
create table public.cycle_settings (
  user_id           uuid primary key references auth.users(id) on delete cascade,
  cycle_length      smallint check (cycle_length between 15 and 60),
  period_length     smallint check (period_length between 1 and 10),
  luteal_length     smallint not null default 14 check (luteal_length between 10 and 16),
  partner_can_edit  boolean not null default false,
  care_reminders    boolean not null default true,
  updated_at        timestamptz not null default now(),
  care_sent_for     date
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
create table public.cycle_logs (
  user_id   uuid not null references auth.users(id) on delete cascade,
  log_date  date not null,
  flow      text check (flow in ('spotting', 'light', 'medium', 'heavy')),
  symptoms  text[] not null default '{}',
  note      text check (char_length(note) <= 300),
  primary key (user_id, log_date)
);
create table public.activity_log (
  id          bigint generated always as identity primary key,
  couple_id   uuid not null references public.couples(id) on delete cascade,
  actor_id    uuid references auth.users(id) on delete set null,
  kind        text not null,
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
create table public.job_runs (
  name    text not null,
  run_on  date not null,
  primary key (name, run_on)
);
create table public.app_config (
  id         boolean primary key default true check (id),
  max_users  integer not null default 2,
  timezone   text not null default 'Asia/Taipei'
);
insert into public.app_config default values;
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
create or replace function public.my_couple_id() returns uuid
language sql stable security definer set search_path = public as $$ select couple_of(auth.uid()) $$;
create or replace function public.partner_id() returns uuid
language sql stable security definer set search_path = public as $$ select partner_of(auth.uid()) $$;
create or replace function public.is_partner(p_user uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select p_user is not null and p_user = partner_of(auth.uid())
$$;
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
select '第 1 份完成 ✅ 接著貼第 2 份' as "小倆口";
commit;
