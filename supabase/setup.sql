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
  alphabet constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
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
alter table public.notification_outbox enable row level security;
alter table public.job_runs            enable row level security;
alter table public.app_config          enable row level security;
create policy "profiles: self and partner read" on public.profiles for select using (id = auth.uid() or public.is_partner(id));
create policy "profiles: self insert" on public.profiles for insert with check (id = auth.uid());
create policy "profiles: self update" on public.profiles for update using (id = auth.uid()) with check (id = auth.uid());
create policy "couples: members read" on public.couples for select using (id = public.my_couple_id());
create policy "couples: members update" on public.couples for update using (id = public.my_couple_id()) with check (id = public.my_couple_id());
create policy "couple_members: members read" on public.couple_members for select using (couple_id = public.my_couple_id());
create policy "pair_invites: own read" on public.pair_invites for select using (inviter_id = auth.uid());
create policy "sharing: self and partner read" on public.sharing_settings for select using (user_id = auth.uid() or public.is_partner(user_id));
create policy "sharing: self insert" on public.sharing_settings for insert with check (user_id = auth.uid());
create policy "sharing: self update" on public.sharing_settings for update using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "device: owner" on public.device_status for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "device: partner read" on public.device_status for select using (public.partner_shares(user_id, 'device'));
create policy "locations: owner" on public.locations for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "locations: partner read" on public.locations for select using (public.partner_shares(user_id, 'location'));
create policy "moods: owner" on public.moods for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "moods: partner read" on public.moods for select using (public.partner_shares(user_id, 'mood'));
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
revoke execute on function public.enqueue_notification(uuid, text, text, text, jsonb) from public, anon, authenticated;
revoke execute on function public.log_activity(uuid, uuid, text, jsonb) from public, anon, authenticated;
revoke execute on function public.gen_pair_code() from public, anon, authenticated;
revoke execute on function public.daily_reminders(date) from public, anon, authenticated;
revoke execute on function public.couple_of(uuid) from public, anon, authenticated;
revoke execute on function public.partner_of(uuid) from public, anon, authenticated;
revoke execute on function public.shares(uuid, text) from public, anon, authenticated;
revoke execute on function public.display_name_of(uuid) from public, anon, authenticated;
create or replace function public.handle_new_user() returns trigger
language plpgsql security definer set search_path = public as $$
declare
  v_max  integer := coalesce((select max_users from app_config), 2);
  v_name text;
begin
  perform pg_advisory_xact_lock(7311);
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
revoke execute on function public.handle_new_user() from public, anon, authenticated;create table public.realtime_events (
  id          bigserial primary key,
  user_id     uuid not null references auth.users(id) on delete cascade,
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
create or replace function public.tg_changed() returns trigger
language plpgsql security definer set search_path = public as $$
declare
  v_row    jsonb := to_jsonb(case when tg_op = 'DELETE' then old else new end);
  v_old    jsonb := case when tg_op = 'UPDATE' then to_jsonb(old) end;
  v_user   uuid;
  v_couple uuid;
  v_kind   text;
  v_shown  boolean;
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
    v_shown := true;
  elsif tg_table_name = 'expenses' then
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
    select v_user where v_user is not null
  ) t
  where exists (select 1 from auth.users u where u.id = t.uid);
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
end $$;create or replace function public.camel_key(p_key text) returns text
language sql immutable parallel safe set search_path = public as $$
  select coalesce(string_agg(case when t.i = 1 then t.part
                                  when t.part ~ '^[a-z]' then upper(left(t.part, 1)) || substr(t.part, 2)
                                  else '_' || t.part end, '' order by t.i), '')
  from unnest(string_to_array(p_key, '_')) with ordinality as t(part, i)
$$;
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
create or replace function public.camel_rows(p jsonb) returns jsonb
language sql immutable parallel safe set search_path = public as $$
  select coalesce(camel(p), '[]'::jsonb)
$$;
create or replace function public.j_arr(p jsonb) returns text[]
language sql immutable parallel safe set search_path = public as $$
  select case when p is null or jsonb_typeof(p) = 'null' then null
              else array(select e.value from jsonb_array_elements_text(p) with ordinality as e(value, i) order by e.i) end
$$;
create or replace function public.jnum(p jsonb) returns numeric
language sql immutable parallel safe set search_path = public as $$
  select case jsonb_typeof(p)
    when 'number' then (p #>> '{}')::numeric
    when 'string' then case when (p #>> '{}') ~ '^\s*[-+]?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?\s*$'
                            then trim(p #>> '{}')::numeric end
  end
$$;
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
create or replace function public.body_range(p_body jsonb, p_days integer, out p_from timestamptz, out p_to timestamptz)
language plpgsql stable set search_path = public set timezone to 'UTC' as $$
begin
  p_to := coalesce(nullif(p_body->>'to', '')::timestamptz, now() + interval '1 day');
  p_from := coalesce(nullif(p_body->>'from', '')::timestamptz, p_to - make_interval(days => p_days));
exception when data_exception then
  raise exception 'invalid_range';
end $$;
create or replace function public.watch_target(p_watch text) returns uuid
language sql stable set search_path = public as $$
  select case p_watch when 'me' then auth.uid() when 'partner' then partner_id() end
$$;
create or replace function public.notif_kinds() returns text[]
language sql immutable parallel safe set search_path = public as $$
  select array['pairing', 'place', 'mood', 'comfort', 'battery_low', 'guess', 'event', 'anniversary', 'cycle_care', 'sharing_changed']
$$;
create or replace function public.app_health(jsonb default '{}') returns jsonb
language sql stable set search_path = public set timezone to 'UTC' as $$
  select jsonb_build_object('ok', true, 'time', now())
$$;
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
do $$
declare f regprocedure;
begin
  for f in
    select p.oid::regprocedure from pg_proc p
    where p.pronamespace = 'public'::regnamespace
      and not exists (select 1 from pg_depend d where d.objid = p.oid and d.deptype = 'e')
  loop
    execute format('revoke all on function %s from public, anon, authenticated', f);
  end loop;
end $$;
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
revoke all on all tables in schema public from anon;
revoke all on all sequences in schema public from anon;
grant select, insert, update, delete on all tables in schema public to authenticated;
grant usage, select on all sequences in schema public to authenticated;
revoke insert, update on public.comforts from authenticated;
revoke update on public.couples, public.sharing_settings, public.places, public.anniversaries,
                 public.events, public.cycle_periods from authenticated;
grant update (together_since) on public.couples to authenticated;
grant update (share_location, share_device, share_expenses, share_mood, share_cycle, hidden_categories, updated_at)
  on public.sharing_settings to authenticated;
grant insert (couple_id, receiver_id, kind, message) on public.comforts to authenticated;
grant update (read_at) on public.comforts to authenticated;
grant update (name, icon, lat, lng, radius_m, notify_arrive, notify_leave, watch_user) on public.places to authenticated;
grant update (title, date, kind, repeat, calendar, remind_days) on public.anniversaries to authenticated;
grant update (title, starts_at, ends_at, all_day, place_id, location_text, note, status, remind_minutes, updated_at, reminded_at)
  on public.events to authenticated;
grant update (start_date, end_date) on public.cycle_periods to authenticated;
grant execute on function
  public.my_couple_id(), public.partner_id(), public.is_partner(uuid), public.partner_shares(uuid, text), public.i_share(text),
  public.create_invite(), public.accept_invite(text), public.unpair(), public.pause_sharing(integer),
  public.partner_expenses(timestamptz, timestamptz), public.submit_guess(uuid, numeric),
  public.camel_key(text), public.camel(jsonb), public.camel_rows(jsonb), public.j_arr(jsonb), public.jnum(jsonb),
  public.truthy(jsonb), public.api_fields(text), public.body_fields(jsonb, text[]), public.body_range(jsonb, integer),
  public.watch_target(text), public.notif_kinds()
to authenticated;create extension if not exists pg_cron;
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
  c_window   constant integer := 6;
  c_project  constant integer := 6;
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
  select coalesce(array_agg(u.s order by u.s), '{}'), coalesce(array_agg(u.e order by u.s), '{}')
    into v_starts, v_ends
  from unnest(p_starts, p_ends) as u(s, e)
  where u.s is not null;
  n := cardinality(v_starts);
  if n = 0 then return; end if;
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
  cycle_length := coalesce(nullif(p_cycle_length, 0),
    case when cardinality(v_cycles) > 0 then floor((select avg(x) from unnest(v_cycles) x) + 0.5)::integer else 28 end);
  period_length := coalesce(nullif(p_period_length, 0),
    case when cardinality(v_lengths) > 0 then floor((select avg(x) from unnest(v_lengths) x) + 0.5)::integer else 5 end);
  luteal_length := coalesce(nullif(p_luteal_length, 0), 14);
  samples := cardinality(v_cycles);
  irregular := samples >= 3
    and (select max(x) - min(x) from unnest(v_cycles) x) > 7;
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
  foreach d in array v_all loop
    if d <= p_today then v_cur := d; else v_nx := d; exit; end if;
  end loop;
  v_expected := v_last + cycle_length;
  late_days := p_today - v_expected;
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
    perform enqueue_notification(m.user_id, 'event', '約會提醒：' || e.title,
              to_char(e.starts_at at time zone v_tz, 'FMMM/FMDD HH24:MI') || ' 開始，準備出門囉',
              jsonb_build_object('event_id', e.id))
    from couple_members m
    where m.couple_id = e.couple_id and m.left_at is null;
    n := n + 1;
  end loop;
  return n;
end $$;
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
revoke all on function public.predict_cycle(date[], date[], integer, integer, integer, date) from public, anon, authenticated;
revoke all on function public.app_job_event_reminders() from public, anon, authenticated;
revoke all on function public.app_job_cycle_care(date) from public, anon, authenticated;
revoke all on function public.app_job_daily(date, boolean) from public, anon, authenticated;
revoke all on function public.app_job_cleanup() from public, anon, authenticated;
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
select cron.schedule('xlk-daily', '*/10 * * * *', $cron$
  select public.app_job_daily((now() at time zone c.timezone)::date)
  from public.app_config c
  where extract(hour from now() at time zone c.timezone) >= 9
$cron$);
select cron.schedule('xlk-cleanup', '23 * * * *', 'select public.app_job_cleanup()');
notify pgrst, 'reload schema';alter table public.profiles add column if not exists info jsonb not null default '{}'
  check (jsonb_typeof(info) = 'object' and octet_length(info::text) <= 4000);
create or replace function public.api_fields(p_kind text) returns text[]
language sql immutable parallel safe set search_path = public as $$
  select case p_kind
    when 'profile' then array['displayName:display_name:text', 'birthday:birthday:date', 'avatarUrl:avatar_url:text',
                              'info:info:jsonb']
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
    'user', coalesce(camel((select to_jsonb(p) from (select id, display_name, avatar_url, birthday, info
                                                     from profiles where id = v_uid) p)), '{}'::jsonb)
            || jsonb_build_object('email', (select u.email from auth.users u where u.id = v_uid)),
    'couple', camel((select to_jsonb(c) from (select id, together_since, created_at from couples where id = v_couple) c)),
    'partner', camel((select to_jsonb(p) from (select id, display_name, avatar_url, birthday, info
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
                    ''id'', id, ''display_name'', display_name, ''avatar_url'', avatar_url, ''birthday'', birthday,
                    ''info'', info)', v_set)
    into v_row using $1;
  return camel(v_row);
end $$;
create table if not exists public.partner_notes (
  id          uuid primary key default gen_random_uuid(),
  owner_id    uuid not null default auth.uid() references auth.users(id) on delete cascade,
  about_user  uuid,
  body        text not null check (char_length(body) between 1 and 2000),
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);
alter table public.partner_notes drop constraint if exists partner_notes_about_user_fkey;
create index if not exists partner_notes_owner on public.partner_notes (owner_id, created_at desc);
create table if not exists public.day_entries (
  id          uuid primary key default gen_random_uuid(),
  couple_id   uuid not null references public.couples(id) on delete cascade,
  user_id     uuid not null default auth.uid() references auth.users(id) on delete cascade,
  day         date not null check (day between '1900-01-01' and '2200-12-31'),
  body        text not null default '' check (char_length(body) <= 5000),
  media       jsonb not null default '[]' check (jsonb_typeof(media) = 'array' and jsonb_array_length(media) <= 12),
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  check (body <> '' or jsonb_array_length(media) > 0)
);
create index if not exists day_entries_couple_day on public.day_entries (couple_id, day);
create table if not exists public.memos (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null default auth.uid() references auth.users(id) on delete cascade,
  couple_id   uuid references public.couples(id) on delete set null,
  title       text not null default '' check (char_length(title) <= 60),
  body        text not null default '' check (char_length(body) <= 5000),
  media       jsonb not null default '[]' check (jsonb_typeof(media) = 'array' and jsonb_array_length(media) <= 12),
  shared      boolean not null default true,
  pinned      boolean not null default false,
  color       text not null default 'pink' check (color in ('pink', 'yellow', 'mint', 'sky', 'lavender', 'peach')),
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);
create index if not exists memos_user on public.memos (user_id);
create index if not exists memos_couple on public.memos (couple_id) where shared;
create or replace function public.media_valid(p_media jsonb, p_folder text) returns boolean
language sql immutable parallel safe set search_path = public as $$
  select case
    when p_media is null or jsonb_typeof(p_media) <> 'array' then false
    else not exists (
      select 1 from jsonb_array_elements(p_media) e
      where not coalesce(jsonb_typeof(e) = 'object'
                         and jsonb_typeof(e->'path') = 'string'
                         and starts_with(e->>'path', p_folder)
                         and substr(e->>'path', length(p_folder) + 1) ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$'
                         and e->>'kind' in ('photo', 'voice')
                         and octet_length(e::text) <= 1000, false))
  end
$$;
create or replace function public.media_summary(p_media jsonb) returns text
language sql immutable parallel safe set search_path = public as $$
  select case
    when exists (select 1 from jsonb_array_elements(p_media) e where e->>'kind' = 'photo') then '📷 新增了照片'
    when exists (select 1 from jsonb_array_elements(p_media) e where e->>'kind' = 'voice') then '🎙️ 錄了一段語音'
    else '' end
$$;
create or replace function public.tg_media_valid() returns trigger
language plpgsql security definer set search_path = public as $$
declare
  v_row    jsonb := to_jsonb(new);
  v_folder text := case when tg_table_name = 'memos' and not (v_row->>'shared')::boolean
                        then 'u/' || (v_row->>'user_id') || '/'
                        else 'c/' || (v_row->>'couple_id') || '/' end;
begin
  if not media_valid(new.media, v_folder) then raise exception 'invalid_media'; end if;
  return new;
end $$;
create or replace trigger day_entry_media before insert or update of media on public.day_entries
  for each row execute function public.tg_media_valid();
create or replace trigger memo_media before insert or update of media, shared, couple_id on public.memos
  for each row when (new.couple_id is not null or not new.shared) execute function public.tg_media_valid();
create or replace function public.tg_day_entry_inserted() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  perform enqueue_notification(partner_of(new.user_id), 'diary',
    display_name_of(new.user_id) || ' 記錄了 ' || to_char(new.day, 'FMMM/FMDD') || ' 的生活',
    coalesce(nullif(left(btrim(new.body), 40), ''), nullif(media_summary(new.media), ''), '打開看看吧'),
    jsonb_build_object('day', new.day));
  return new;
end $$;
create or replace trigger day_entry_inserted after insert on public.day_entries
  for each row execute function public.tg_day_entry_inserted();
create or replace trigger realtime_day_entries after insert or update or delete on public.day_entries
  for each row execute function public.tg_changed();
create or replace function public.tg_memo_inserted() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if new.shared and new.couple_id is not null then
    perform enqueue_notification(partner_of(new.user_id), 'memo',
      display_name_of(new.user_id) || case when tg_op = 'INSERT' then ' 新增了共同備忘錄' else ' 分享了一則備忘錄' end,
      coalesce(nullif(new.title, ''), nullif(left(btrim(new.body), 40), ''), nullif(media_summary(new.media), ''), '打開看看吧'),
      jsonb_build_object('id', new.id));
  end if;
  return new;
end $$;
create or replace trigger memo_inserted after insert on public.memos
  for each row execute function public.tg_memo_inserted();
create or replace trigger memo_shared after update of shared, couple_id on public.memos
  for each row when (new.shared and new.couple_id is not null and not (old.shared and old.couple_id is not distinct from new.couple_id))
  execute function public.tg_memo_inserted();
create or replace function public.tg_changed_memo() returns trigger
language plpgsql security definer set search_path = public as $$
declare
  v_row        jsonb := to_jsonb(case when tg_op = 'DELETE' then old else new end);
  v_old        jsonb := case when tg_op = 'UPDATE' then to_jsonb(old) end;
  v_user       uuid := (v_row->>'user_id')::uuid;
  v_couple     uuid := case when (v_row->>'shared')::boolean then (v_row->>'couple_id')::uuid end;
  v_old_couple uuid := case when (v_old->>'shared')::boolean then (v_old->>'couple_id')::uuid end;
begin
  insert into realtime_events (user_id, kind, table_name, op, by_user)
  select t.uid, 'changed', tg_table_name, lower(tg_op), coalesce(auth.uid(), v_user)
  from (
    select v_user as uid
    union
    select m.user_id from couple_members m
    where m.left_at is null and m.couple_id in (v_couple, v_old_couple)
  ) t
  where exists (select 1 from auth.users u where u.id = t.uid);
  return null;
end $$;
create or replace trigger realtime_memos after insert or update or delete on public.memos
  for each row execute function public.tg_changed_memo();
alter table public.partner_notes enable row level security;
alter table public.day_entries   enable row level security;
alter table public.memos         enable row level security;
drop policy if exists "partner_notes: owner only" on public.partner_notes;
create policy "partner_notes: owner only" on public.partner_notes for all
  using (owner_id = auth.uid()) with check (owner_id = auth.uid());
drop policy if exists "day_entries: couple read" on public.day_entries;
drop policy if exists "day_entries: own insert" on public.day_entries;
drop policy if exists "day_entries: own update" on public.day_entries;
drop policy if exists "day_entries: own delete" on public.day_entries;
create policy "day_entries: couple read" on public.day_entries for select using (couple_id = public.my_couple_id());
create policy "day_entries: own insert" on public.day_entries for insert with check (
  user_id = auth.uid() and couple_id = public.my_couple_id());
create policy "day_entries: own update" on public.day_entries for update
  using (user_id = auth.uid() and couple_id = public.my_couple_id())
  with check (user_id = auth.uid() and couple_id = public.my_couple_id());
create policy "day_entries: own delete" on public.day_entries for delete using (
  user_id = auth.uid() and couple_id = public.my_couple_id());
drop policy if exists "memos: own or shared read" on public.memos;
drop policy if exists "memos: own insert" on public.memos;
drop policy if exists "memos: own or shared update" on public.memos;
drop policy if exists "memos: creator delete" on public.memos;
create policy "memos: own or shared read" on public.memos for select using (
  user_id = auth.uid() or (shared and couple_id is not null and couple_id = public.my_couple_id()));
create policy "memos: own insert" on public.memos for insert with check (
  user_id = auth.uid() and (not shared or (couple_id is not null and couple_id = public.my_couple_id())));
create policy "memos: own or shared update" on public.memos for update
  using (user_id = auth.uid() or (shared and couple_id is not null and couple_id = public.my_couple_id()))
  with check ((user_id = auth.uid() and not shared)
              or (shared and couple_id is not null and couple_id = public.my_couple_id()));
create policy "memos: creator delete" on public.memos for delete using (user_id = auth.uid());
revoke all on public.partner_notes, public.day_entries, public.memos from anon, authenticated;
grant select, insert, delete on public.partner_notes, public.day_entries, public.memos to authenticated;
grant update (body, updated_at) on public.partner_notes to authenticated;
grant update (day, body, media, updated_at) on public.day_entries to authenticated;
grant update (couple_id, title, body, media, shared, pinned, color, updated_at) on public.memos to authenticated;
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('media', 'media', false, 10485760,
        array['image/jpeg', 'image/png', 'image/webp', 'audio/mp4', 'audio/m4a', 'audio/x-m4a', 'audio/aac', 'audio/mpeg'])
on conflict (id) do update set public = false, file_size_limit = excluded.file_size_limit,
                               allowed_mime_types = excluded.allowed_mime_types;
drop policy if exists "xlk media: couple or own folder read" on storage.objects;
drop policy if exists "xlk media: couple or own folder upload" on storage.objects;
drop policy if exists "xlk media: uploader delete" on storage.objects;
create policy "xlk media: couple or own folder read" on storage.objects for select to authenticated using (
  bucket_id = 'media'
  and ((storage.foldername(name))[1] = 'c' and (storage.foldername(name))[2] = public.my_couple_id()::text
       or (storage.foldername(name))[1] = 'u' and (storage.foldername(name))[2] = auth.uid()::text));
create policy "xlk media: couple or own folder upload" on storage.objects for insert to authenticated with check (
  bucket_id = 'media'
  and name ~ '^[cu]/[0-9a-f-]{36}/[A-Za-z0-9][A-Za-z0-9._-]{0,99}$'
  and ((storage.foldername(name))[1] = 'c' and (storage.foldername(name))[2] = public.my_couple_id()::text
       or (storage.foldername(name))[1] = 'u' and (storage.foldername(name))[2] = auth.uid()::text));
create policy "xlk media: uploader delete" on storage.objects for delete to authenticated using (
  bucket_id = 'media' and owner_id = auth.uid()::text);
alter table public.notification_prefs drop constraint if exists notification_prefs_kind_check,
  add constraint notification_prefs_kind_check check (kind in ('pairing', 'place', 'mood', 'comfort', 'battery_low', 'guess',
    'event', 'anniversary', 'cycle_care', 'sharing_changed', 'diary', 'memo')) not valid;
create or replace function public.notif_kinds() returns text[]
language sql immutable parallel safe set search_path = public as $$
  select array['pairing', 'place', 'mood', 'comfort', 'battery_low', 'guess', 'event', 'anniversary', 'cycle_care', 'sharing_changed',
               'diary', 'memo']
$$;
create or replace function public.app_health(jsonb default '{}') returns jsonb
language sql stable set search_path = public set timezone to 'UTC' as $$
  select jsonb_build_object('ok', true, 'time', now(), 'schema', 2)
$$;
create or replace function public.app_notes(jsonb default '{}') returns jsonb
language sql stable set search_path = public set timezone to 'UTC' as $$
  select camel_rows((select jsonb_agg(to_jsonb(n) - 'owner_id' order by n.created_at desc) from partner_notes n
                     where n.owner_id = auth.uid() and (n.about_user is null or n.about_user = partner_id())))
$$;
create or replace function public.app_save_note(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
declare v_row jsonb;
begin
  if nullif($1->>'id', '') is null then
    insert into partner_notes as t (about_user, body) values (partner_id(), $1->>'body') returning to_jsonb(t) into v_row;
  else
    update partner_notes t set body = $1->>'body', updated_at = now()
    where t.id = ($1->>'id')::uuid and t.owner_id = auth.uid() returning to_jsonb(t) into v_row;
    if v_row is null then raise exception 'not_found'; end if;
  end if;
  return camel(v_row - 'owner_id');
end $$;
create or replace function public.app_delete_note(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
begin
  delete from partner_notes where id = ($1->>'id')::uuid and owner_id = auth.uid();
  if not found then raise exception 'not_found'; end if;
  return jsonb_build_object('ok', true);
end $$;
create or replace function public.app_day_entries(jsonb default '{}') returns jsonb
language plpgsql stable set search_path = public set timezone to 'UTC' as $$
declare
  v_from date;
  v_to   date;
begin
  begin
    v_from := coalesce(nullif($1->>'from', '')::date, current_date - 62);
    v_to := coalesce(nullif($1->>'to', '')::date, current_date + 31);
  exception when data_exception then
    raise exception 'invalid_range';
  end;
  return camel_rows((select jsonb_agg(to_jsonb(d) - 'couple_id' order by d.day, d.created_at) from day_entries d
                     where d.couple_id = my_couple_id() and d.day between v_from and v_to));
end $$;
create or replace function public.app_save_day_entry(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
declare
  v_couple uuid := my_couple_id();
  v_media  jsonb := nullif($1->'media', 'null');
  v_row    jsonb;
begin
  if v_couple is null then raise exception 'not_paired'; end if;
  if nullif($1->>'id', '') is null then
    insert into day_entries as t (couple_id, day, body, media)
    values (v_couple, ($1->>'day')::date, coalesce($1->>'body', ''), coalesce(v_media, '[]'))
    returning to_jsonb(t) into v_row;
  else
    update day_entries t set day = coalesce(($1->>'day')::date, t.day), body = coalesce($1->>'body', t.body),
                             media = coalesce(v_media, t.media), updated_at = now()
    where t.id = ($1->>'id')::uuid and t.user_id = auth.uid() and t.couple_id = v_couple
    returning to_jsonb(t) into v_row;
    if v_row is null then raise exception 'not_found'; end if;
  end if;
  return camel(v_row - 'couple_id');
end $$;
create or replace function public.app_delete_day_entry(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
begin
  delete from day_entries where id = ($1->>'id')::uuid and user_id = auth.uid();
  if not found then raise exception 'not_found'; end if;
  return jsonb_build_object('ok', true);
end $$;
create or replace function public.app_memos(jsonb default '{}') returns jsonb
language sql stable set search_path = public set timezone to 'UTC' as $$
  select camel_rows((select jsonb_agg((to_jsonb(m) - 'couple_id')
                                      || jsonb_build_object('shared', coalesce(m.shared and m.couple_id = my_couple_id(), false))
                                      order by m.pinned desc, m.updated_at desc) from memos m
                     where m.user_id = auth.uid() or (m.shared and m.couple_id = my_couple_id())))
$$;
create or replace function public.app_save_memo(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
declare
  v_couple uuid := my_couple_id();
  v_media  jsonb := nullif($1->'media', 'null');
  v_memo   memos;
  v_was    boolean;
  v_shared boolean;
  v_row    jsonb;
begin
  if nullif($1->>'id', '') is null then
    insert into memos as t (couple_id, title, body, media, shared, pinned, color)
    values (v_couple, coalesce($1->>'title', ''), coalesce($1->>'body', ''), coalesce(v_media, '[]'),
            coalesce(($1->>'shared')::boolean, true) and v_couple is not null,
            coalesce(($1->>'pinned')::boolean, false), coalesce(nullif($1->>'color', ''), 'pink'))
    returning to_jsonb(t) into v_row;
    return camel(v_row - 'couple_id');
  end if;
  select * into v_memo from memos where id = ($1->>'id')::uuid;
  if not found then raise exception 'not_found'; end if;
  if v_memo.user_id = auth.uid() then
    v_was := coalesce(v_memo.shared and v_memo.couple_id = v_couple, false);
    v_shared := coalesce(($1->>'shared')::boolean, v_was) and v_couple is not null;
    v_media := coalesce(v_media, v_memo.media);
    if v_memo.shared and not v_was then
      v_media := coalesce((select jsonb_agg(e.value order by e.i) from jsonb_array_elements(v_media) with ordinality as e(value, i)
                           where not coalesce(e.value->>'path' like 'c/%'
                                              and not starts_with(e.value->>'path', 'c/' || coalesce(v_couple::text, '') || '/'), false)),
                          '[]');
    end if;
    update memos t set title = coalesce($1->>'title', t.title), body = coalesce($1->>'body', t.body),
                       media = v_media, shared = v_shared,
                       couple_id = case when v_shared then v_couple else t.couple_id end,
                       pinned = coalesce(($1->>'pinned')::boolean, t.pinned),
                       color = coalesce(nullif($1->>'color', ''), t.color), updated_at = now()
    where t.id = v_memo.id
    returning to_jsonb(t) into v_row;
  else
    update memos t set title = coalesce($1->>'title', t.title), body = coalesce($1->>'body', t.body),
                       media = coalesce(v_media, t.media), pinned = coalesce(($1->>'pinned')::boolean, t.pinned),
                       color = coalesce(nullif($1->>'color', ''), t.color), updated_at = now()
    where t.id = v_memo.id
    returning to_jsonb(t) into v_row;
  end if;
  if v_row is null then raise exception 'not_found'; end if;
  return camel(v_row - 'couple_id');
end $$;
create or replace function public.app_delete_memo(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
begin
  delete from memos where id = ($1->>'id')::uuid and user_id = auth.uid();
  if not found then raise exception 'not_found' using detail = '只有建立備忘錄的人可以刪除'; end if;
  return jsonb_build_object('ok', true);
end $$;
revoke all on function public.media_valid(jsonb, text), public.media_summary(jsonb), public.tg_media_valid(),
  public.tg_day_entry_inserted(), public.tg_memo_inserted(), public.tg_changed_memo()
  from public, anon, authenticated;
revoke all on function public.app_notes(jsonb), public.app_save_note(jsonb), public.app_delete_note(jsonb),
  public.app_day_entries(jsonb), public.app_save_day_entry(jsonb), public.app_delete_day_entry(jsonb),
  public.app_memos(jsonb), public.app_save_memo(jsonb), public.app_delete_memo(jsonb)
  from public, anon;
grant execute on function public.app_notes(jsonb), public.app_save_note(jsonb), public.app_delete_note(jsonb),
  public.app_day_entries(jsonb), public.app_save_day_entry(jsonb), public.app_delete_day_entry(jsonb),
  public.app_memos(jsonb), public.app_save_memo(jsonb), public.app_delete_memo(jsonb)
  to authenticated;
notify pgrst, 'reload schema';alter table public.device_status add column if not exists device_name text check (char_length(device_name) <= 60);
alter table public.device_status add column if not exists carrier text check (char_length(carrier) <= 60);
create or replace function public.app_report_device(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
begin
  insert into device_status (user_id, battery_level, is_charging, network, network_label, notifications_silenced,
                             steps_today, last_active_at, device_model, device_name, carrier, updated_at)
  values (auth.uid(), ($1->>'batteryLevel')::numeric::smallint, ($1->>'isCharging')::boolean, $1->>'network',
          $1->>'networkLabel', ($1->>'notificationsSilenced')::boolean, ($1->>'stepsToday')::numeric::integer,
          nullif($1->>'lastActiveAt', '')::timestamptz, $1->>'deviceModel',
          left(nullif(btrim($1->>'deviceName'), ''), 60), left(nullif(btrim($1->>'carrier'), ''), 60), now())
  on conflict (user_id) do update set
    battery_level = excluded.battery_level, is_charging = excluded.is_charging, network = excluded.network,
    network_label = excluded.network_label, notifications_silenced = excluded.notifications_silenced,
    steps_today = excluded.steps_today, last_active_at = coalesce(excluded.last_active_at, device_status.last_active_at),
    device_model = excluded.device_model, device_name = excluded.device_name, carrier = excluded.carrier, updated_at = now();
  return jsonb_build_object('ok', true);
end $$;
create or replace function public.app_health(jsonb default '{}') returns jsonb
language sql stable set search_path = public set timezone to 'UTC' as $$
  select jsonb_build_object('ok', true, 'time', now(), 'schema', 3)
$$;do $$
begin
  if not exists (select 1 from information_schema.columns
                 where table_schema = 'public' and table_name = 'comforts' and column_name = 'sticker') then
    update public.comforts set read_at = now() where read_at is null and created_at < now() - interval '1 day';
  end if;
end $$;
alter table public.moods add column if not exists sticker text check (sticker ~ '^[A-Za-z0-9]{1,64}$');
alter table public.comforts add column if not exists sticker text check (sticker ~ '^[A-Za-z0-9]{1,64}$');
alter table public.couples add column if not exists giphy_key text check (giphy_key ~ '^[A-Za-z0-9_-]{16,128}$');
grant insert (sticker) on public.comforts to authenticated;
grant update (giphy_key) on public.couples to authenticated;
create or replace function public.app_post_mood(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
declare v_row jsonb;
begin
  insert into moods as t (mood, note, sticker) values ($1->>'mood', nullif($1->>'note', ''), nullif(btrim($1->>'sticker'), ''))
  returning to_jsonb(t) into v_row;
  return camel(v_row);
end $$;
create or replace function public.app_send_comfort(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
declare v_row jsonb;
begin
  insert into comforts as t (couple_id, receiver_id, kind, message, sticker)
  values (my_couple_id(), partner_id(), $1->>'kind', nullif($1->>'message', ''), nullif(btrim($1->>'sticker'), ''))
  returning to_jsonb(t) into v_row;
  return camel(v_row);
end $$;
create or replace function public.app_read_comfort(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
begin
  if truthy($1->'all') then
    update comforts set read_at = now() where receiver_id = auth.uid() and read_at is null;
  else
    update comforts set read_at = now() where id = ($1->>'id')::bigint and read_at is null;
  end if;
  return jsonb_build_object('ok', true);
end $$;
create or replace function public.tg_mood_inserted() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if not shares(new.user_id, 'mood') then return new; end if;
  perform log_activity(couple_of(new.user_id), new.user_id, 'mood',
    jsonb_build_object('mood', new.mood, 'note', new.note, 'sticker', new.sticker));
  perform enqueue_notification(partner_of(new.user_id), 'mood',
    display_name_of(new.user_id) || ' 現在覺得' || mood_label(new.mood),
    case when new.mood in ('tired', 'anxious', 'sad', 'angry', 'sick')
         then coalesce(new.note || '。', '') || '要不要安撫一下？'
         else coalesce(nullif(new.note, ''), case when new.sticker is not null then '附上一張貼圖 ✨' end, '') end,
    jsonb_build_object('mood', new.mood, 'sticker', new.sticker));
  return new;
end $$;
create or replace function public.tg_comfort_inserted() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  perform log_activity(new.couple_id, new.sender_id, 'comfort',
    jsonb_build_object('kind', new.kind, 'message', new.message, 'sticker', new.sticker));
  perform enqueue_notification(new.receiver_id, 'comfort',
    display_name_of(new.sender_id) || ' ' || comfort_text(new.kind),
    coalesce(new.message, case when new.sticker is not null then '傳了一張貼圖給你，點開看看 👀' else '點開回應一下吧' end),
    jsonb_build_object('comfort_id', new.id, 'sticker', new.sticker));
  return new;
end $$;
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
    'user', coalesce(camel((select to_jsonb(p) from (select id, display_name, avatar_url, birthday, info
                                                     from profiles where id = v_uid) p)), '{}'::jsonb)
            || jsonb_build_object('email', (select u.email from auth.users u where u.id = v_uid)),
    'couple', camel((select to_jsonb(c) from (select id, together_since, created_at, giphy_key from couples where id = v_couple) c)),
    'partner', camel((select to_jsonb(p) from (select id, display_name, avatar_url, birthday, info
                                               from profiles where id = v_partner) p)),
    'mySharing', camel((select to_jsonb(s) from sharing_settings s where s.user_id = v_uid)),
    'partnerSharing', camel((select to_jsonb(s) from sharing_settings s where s.user_id = v_partner)),
    'partnerOnline', coalesce((select d.updated_at > now() - interval '10 minutes'
                               from device_status d where d.user_id = v_partner), false));
end $$;
create or replace function public.app_set_giphy_key(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
declare v_row jsonb;
begin
  update couples set giphy_key = nullif(btrim($1->>'key'), '') where id = my_couple_id()
  returning jsonb_build_object('id', id, 'together_since', together_since, 'created_at', created_at, 'giphy_key', giphy_key)
  into v_row;
  if v_row is null then raise exception 'not_paired'; end if;
  return camel(v_row);
end $$;
create or replace function public.app_health(jsonb default '{}') returns jsonb
language sql stable set search_path = public set timezone to 'UTC' as $$
  select jsonb_build_object('ok', true, 'time', now(), 'schema', 4)
$$;
revoke all on function public.app_set_giphy_key(jsonb) from public, anon;
grant execute on function public.app_set_giphy_key(jsonb) to authenticated;
notify pgrst, 'reload schema';alter table public.moods drop constraint if exists moods_mood_check, add constraint moods_mood_check check (mood in (
  'happy', 'sweet', 'excited', 'laugh', 'calm', 'relaxed',
  'missyou', 'shy', 'sleepy', 'hungry', 'bored', 'busy',
  'tired', 'anxious', 'stressed', 'wronged', 'sad', 'angry', 'jealous', 'lonely', 'sick')) not valid;
alter table public.comforts drop constraint if exists comforts_kind_check, add constraint comforts_kind_check check (kind in (
  'hug', 'pat', 'here', 'cheer', 'support', 'treat',
  'kiss', 'love', 'missyou', 'flower', 'morning', 'night',
  'water', 'meal', 'rest', 'custom')) not valid;
create or replace function public.mood_label(p_mood text) returns text
language sql immutable as $$
  select case p_mood
    when 'happy' then '開心' when 'sweet' then '甜蜜' when 'excited' then '興奮' when 'laugh' then '好好笑'
    when 'calm' then '平靜' when 'relaxed' then '放鬆'
    when 'missyou' then '想你' when 'shy' then '害羞' when 'sleepy' then '想睡' when 'hungry' then '肚子餓'
    when 'bored' then '無聊' when 'busy' then '忙翻了'
    when 'tired' then '好累' when 'anxious' then '焦慮' when 'stressed' then '壓力大' when 'wronged' then '委屈'
    when 'sad' then '難過' when 'angry' then '生氣' when 'jealous' then '吃醋' when 'lonely' then '寂寞'
    when 'sick' then '不舒服' else p_mood end
$$;
create or replace function public.comfort_text(p_kind text) returns text
language sql immutable as $$
  select case p_kind
    when 'hug' then '給了你一個大大的抱抱' when 'pat' then '摸摸你的頭，說辛苦了'
    when 'here' then '說：我在，想聊隨時找我' when 'cheer' then '幫你加油打氣'
    when 'support' then '說永遠站在你這邊' when 'treat' then '說等等請你喝一杯'
    when 'kiss' then '親了你一下' when 'love' then '說愛你'
    when 'missyou' then '說好想你' when 'flower' then '送你一束花'
    when 'morning' then '跟你說早安' when 'night' then '跟你說晚安，好夢'
    when 'water' then '提醒你記得多喝水' when 'meal' then '問你吃飯了沒'
    when 'rest' then '叫你早點休息'
    else '傳了悄悄話給你' end
$$;
create or replace function public.tg_mood_inserted() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if not shares(new.user_id, 'mood') then return new; end if;
  perform log_activity(couple_of(new.user_id), new.user_id, 'mood',
    jsonb_build_object('mood', new.mood, 'note', new.note, 'sticker', new.sticker));
  perform enqueue_notification(partner_of(new.user_id), 'mood',
    display_name_of(new.user_id) || ' 現在覺得' || mood_label(new.mood),
    case when new.mood in ('tired', 'anxious', 'stressed', 'wronged', 'sad', 'angry', 'jealous', 'lonely', 'sick')
         then coalesce(new.note || '。', '') || '要不要安撫一下？'
         else coalesce(nullif(new.note, ''), case when new.sticker is not null then '附上一張貼圖 ✨' end, '') end,
    jsonb_build_object('mood', new.mood, 'sticker', new.sticker));
  return new;
end $$;
create or replace function public.app_health(jsonb default '{}') returns jsonb
language sql stable set search_path = public set timezone to 'UTC' as $$
  select jsonb_build_object('ok', true, 'time', now(), 'schema', 5)
$$;
notify pgrst, 'reload schema';alter table public.comforts drop constraint if exists comforts_kind_check, add constraint comforts_kind_check check (kind in (
  'hug', 'pat', 'here', 'cheer', 'support', 'treat', 'listen', 'holdhand', 'proud',
  'kiss', 'love', 'missyou', 'flower', 'morning', 'night', 'heart', 'cuddle', 'date',
  'water', 'meal', 'rest', 'warm', 'gethome', 'safe',
  'joke', 'silly', 'kitty', 'dessert', 'gift', 'custom')) not valid;
create or replace function public.comfort_text(p_kind text) returns text
language sql immutable as $$
  select case p_kind
    when 'hug' then '給了你一個大大的抱抱' when 'pat' then '摸摸你的頭，說辛苦了'
    when 'here' then '說：我在，想聊隨時找我' when 'cheer' then '幫你加油打氣'
    when 'support' then '說永遠站在你這邊' when 'treat' then '說等等請你喝一杯'
    when 'listen' then '說想聽你說說今天' when 'holdhand' then '牽起你的手'
    when 'proud' then '說你是最棒的'
    when 'kiss' then '親了你一下' when 'love' then '說愛你'
    when 'missyou' then '說好想你' when 'flower' then '送你一束花'
    when 'morning' then '跟你說早安' when 'night' then '跟你說晚安，好夢'
    when 'heart' then '對你比了一個愛心' when 'cuddle' then '蹭蹭你撒嬌'
    when 'date' then '說想跟你約會'
    when 'water' then '提醒你記得多喝水' when 'meal' then '問你吃飯了沒'
    when 'rest' then '叫你早點休息' when 'warm' then '提醒你穿暖一點'
    when 'gethome' then '問你到家了沒' when 'safe' then '叫你路上小心'
    when 'joke' then '講了一個冷笑話給你' when 'silly' then '對你扮了一個鬼臉'
    when 'kitty' then '送你一隻可愛的小貓' when 'dessert' then '說要請你吃甜點'
    when 'gift' then '說準備了小禮物給你'
    else '傳了悄悄話給你' end
$$;
create or replace function public.app_health(jsonb default '{}') returns jsonb
language sql stable set search_path = public set timezone to 'UTC' as $$
  select jsonb_build_object('ok', true, 'time', now(), 'schema', 6)
$$;
notify pgrst, 'reload schema';create table if not exists public.pets (
  couple_id    uuid primary key references public.couples(id) on delete cascade,
  name         text not null default '咪咪' check (char_length(name) between 1 and 12),
  coat         text not null default 'orange' check (coat in ('orange', 'gray', 'white', 'black', 'calico', 'tuxedo')),
  fullness     double precision not null default 80 check (fullness between 0 and 100),
  happiness    double precision not null default 80 check (happiness between 0 and 100),
  cleanliness  double precision not null default 100 check (cleanliness between 0 and 100),
  xp           integer not null default 0 check (xp >= 0),
  streak       integer not null default 0 check (streak >= 0),
  last_fed_on  date,
  bonus_on     date,
  reminded_on  date,
  adopted_by   uuid references auth.users(id) on delete set null,
  adopted_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now()
);
create table if not exists public.pet_events (
  id          bigint generated always as identity primary key,
  couple_id   uuid not null references public.couples(id) on delete cascade,
  user_id     uuid references auth.users(id) on delete set null,
  action      text not null check (action in ('adopt', 'feed', 'play', 'pet', 'bath', 'brush', 'bonus', 'streak', 'grow', 'rename')),
  item        text check (char_length(item) <= 20),
  xp          integer not null default 0,
  created_at  timestamptz not null default now()
);
create index if not exists pet_events_couple_time on public.pet_events (couple_id, created_at desc);
alter table public.pets enable row level security;
alter table public.pet_events enable row level security;
drop policy if exists "pets: couple read" on public.pets;
create policy "pets: couple read" on public.pets for select using (couple_id = public.my_couple_id());
drop policy if exists "pet_events: couple read" on public.pet_events;
create policy "pet_events: couple read" on public.pet_events for select using (couple_id = public.my_couple_id());
revoke all on public.pets, public.pet_events from anon, authenticated;
grant select on public.pets, public.pet_events to authenticated;
drop trigger if exists realtime_pets on public.pets;
create trigger realtime_pets after insert or update or delete on public.pets
  for each row execute function public.tg_changed();
alter table public.notification_prefs drop constraint if exists notification_prefs_kind_check,
  add constraint notification_prefs_kind_check check (kind in ('pairing', 'place', 'mood', 'comfort', 'battery_low', 'guess',
    'event', 'anniversary', 'cycle_care', 'sharing_changed', 'diary', 'memo', 'pet')) not valid;
create or replace function public.notif_kinds() returns text[]
language sql immutable parallel safe set search_path = public as $$
  select array['pairing', 'place', 'mood', 'comfort', 'battery_low', 'guess', 'event', 'anniversary', 'cycle_care', 'sharing_changed',
               'diary', 'memo', 'pet']
$$;
create or replace function public.pet_at(p public.pets, p_at timestamptz) returns public.pets
language plpgsql immutable set search_path = public as $$
declare
  h      double precision := greatest(0, extract(epoch from (p_at - p.updated_at)) / 3600.0);
  hungry double precision;
begin
  if h = 0 then return p; end if;
  hungry := greatest(0, h - greatest(0, (p.fullness - 20) / 3.0));
  p.fullness := greatest(0, p.fullness - 3.0 * h);
  p.happiness := greatest(0, p.happiness - 2.0 * h - 1.0 * hungry);
  p.cleanliness := greatest(0, p.cleanliness - 1.2 * h);
  p.updated_at := p_at;
  return p;
end $$;
create or replace function public.pet_stage(p_xp integer) returns text
language sql immutable parallel safe as $$
  select case when p_xp >= 2000 then '大胖貓' when p_xp >= 1000 then '成貓' when p_xp >= 400 then '少年貓'
              when p_xp >= 100 then '幼貓' else '小奶貓' end
$$;
create or replace function public.pet_zone() returns text
language sql stable security definer set search_path = public as $$
  select coalesce((select timezone from app_config), 'Asia/Taipei')
$$;
create or replace function public.pet_today() returns date
language sql stable security definer set search_path = public as $$
  select (now() at time zone pet_zone())::date
$$;
create or replace function public.pet_today_counts(p_couple uuid) returns jsonb
language sql stable security definer set search_path = public as $$
  select jsonb_build_object(
    'feeds',  count(*) filter (where e.action = 'feed'),
    'plays',  count(*) filter (where e.action = 'play'),
    'pets',   count(*) filter (where e.action = 'pet'),
    'treats', count(*) filter (where e.action = 'feed' and e.item = 'fish'))
  from pet_events e
  where e.couple_id = p_couple
    and (e.created_at at time zone pet_zone())::date = pet_today()
$$;
create or replace function public.pet_payload(p_couple uuid) returns jsonb
language plpgsql stable security definer set search_path = public set timezone to 'UTC' as $$
declare
  p pets;
begin
  select * into p from pets where couple_id = p_couple;
  if not found then
    return jsonb_build_object('pet', null, 'today', pet_today_counts(p_couple), 'events', '[]'::jsonb);
  end if;
  p := pet_at(p, now());
  return jsonb_build_object(
    'pet', camel(to_jsonb(p) - 'couple_id' - 'reminded_on'),
    'today', pet_today_counts(p_couple),
    'events', camel_rows((select jsonb_agg(to_jsonb(e) - 'couple_id' order by e.created_at desc, e.id desc)
                          from (select * from pet_events where couple_id = p_couple order by created_at desc, id desc limit 30) e)));
end $$;
create or replace function public.app_pet(jsonb default '{}') returns jsonb
language plpgsql stable security definer set search_path = public set timezone to 'UTC' as $$
declare
  v_couple uuid := couple_of(auth.uid());
begin
  if v_couple is null then raise exception 'not_paired'; end if;
  return pet_payload(v_couple);
end $$;
create or replace function public.app_adopt_pet(jsonb default '{}') returns jsonb
language plpgsql security definer set search_path = public set timezone to 'UTC' as $$
declare
  v_me     uuid := auth.uid();
  v_couple uuid := couple_of(auth.uid());
  v_name   text := btrim(coalesce($1->>'name', ''));
  v_coat   text := coalesce(nullif($1->>'coat', ''), 'orange');
begin
  if v_me is null then raise exception 'not_authenticated'; end if;
  if v_couple is null then raise exception 'not_paired'; end if;
  if v_name = '' then v_name := '咪咪'; end if;
  if char_length(v_name) > 12 or v_coat not in ('orange', 'gray', 'white', 'black', 'calico', 'tuxedo') then
    raise exception 'invalid_input';
  end if;
  insert into pets (couple_id, name, coat, adopted_by) values (v_couple, v_name, v_coat, v_me)
  on conflict (couple_id) do nothing;
  if not found then raise exception 'pet_exists' using detail = '你們已經有一隻貓咪了'; end if;
  insert into pet_events (couple_id, user_id, action, item) values (v_couple, v_me, 'adopt', v_coat);
  perform enqueue_notification(partner_of(v_me), 'pet', display_name_of(v_me) || ' 領養了一隻小貓 🐱',
    '牠叫「' || v_name || '」，快來跟牠打招呼', jsonb_build_object('pet', true));
  return pet_payload(v_couple);
end $$;
create or replace function public.app_rename_pet(jsonb default '{}') returns jsonb
language plpgsql security definer set search_path = public set timezone to 'UTC' as $$
declare
  v_couple uuid := couple_of(auth.uid());
  v_name   text := btrim(coalesce($1->>'name', ''));
begin
  if v_couple is null then raise exception 'not_paired'; end if;
  if v_name = '' or char_length(v_name) > 12 then raise exception 'invalid_input'; end if;
  update pets set name = v_name where couple_id = v_couple;
  if not found then raise exception 'not_found'; end if;
  insert into pet_events (couple_id, user_id, action, item) values (v_couple, auth.uid(), 'rename', left(v_name, 20));
  return pet_payload(v_couple);
end $$;
create or replace function public.app_pet_action(jsonb default '{}') returns jsonb
language plpgsql security definer set search_path = public set timezone to 'UTC' as $$
declare
  v_me     uuid := auth.uid();
  v_couple uuid := couple_of(auth.uid());
  v_action text := $1->>'action';
  v_item   text := nullif($1->>'item', '');
  v_today  date;
  t        jsonb;
  p        pets;
  v_before text;
  v_xp     integer;
  v_df double precision := 0; v_dh double precision := 0; v_dc double precision := 0;
  v_bonus  boolean := false;
  v_sbonus boolean := false;
  v_feeds integer; v_plays integer; v_pets integer; v_treats integer;
  v_done_before boolean;
  v_food   text;
begin
  if v_me is null then raise exception 'not_authenticated'; end if;
  if v_couple is null then raise exception 'not_paired'; end if;
  select * into p from pets where couple_id = v_couple for update;
  if not found then raise exception 'not_found' using detail = '還沒有領養貓咪'; end if;
  p := pet_at(p, now());
  v_today := pet_today();
  t := pet_today_counts(v_couple);
  v_feeds := (t->>'feeds')::integer; v_plays := (t->>'plays')::integer;
  v_pets := (t->>'pets')::integer; v_treats := (t->>'treats')::integer;
  v_done_before := v_feeds > 0 and v_plays > 0 and v_pets >= 3;
  v_before := pet_stage(p.xp);
  if v_action = 'feed' then
    if v_item not in ('kibble', 'can', 'fish') or v_item is null then raise exception 'invalid_input'; end if;
    if p.fullness >= 95 then raise exception 'pet_full' using detail = '牠吃不下了，晚點再餵'; end if;
    if v_item = 'fish' and v_treats >= 3 then raise exception 'pet_treats' using detail = '小魚乾今天給太多了，明天再給'; end if;
    select x.df, x.dh, x.xp into v_df, v_dh, v_xp
    from (values ('kibble', 30, 2, 8), ('can', 45, 8, 12), ('fish', 10, 15, 6)) as x(k, df, dh, xp) where x.k = v_item;
    if p.fullness >= 70 then v_xp := 0; end if;
    v_feeds := v_feeds + 1;
    if v_item = 'fish' then v_treats := v_treats + 1; end if;
    if p.last_fed_on is distinct from v_today then
      p.streak := case when p.last_fed_on = v_today - 1 then p.streak + 1 else 1 end;
      v_sbonus := p.streak % 7 = 0;
      p.last_fed_on := v_today;
    end if;
  elsif v_action = 'play' then
    if v_item not in ('wand', 'laser', 'yarn') or v_item is null then raise exception 'invalid_input'; end if;
    if p.fullness < 15 then raise exception 'pet_hungry' using detail = '牠肚子太餓了，先餵牠吃點東西'; end if;
    select x.df, x.dh, x.dc, x.xp into v_df, v_dh, v_dc, v_xp
    from (values ('wand', -6, 20, -4, 10), ('laser', -8, 25, -2, 10), ('yarn', -4, 15, -6, 8)) as x(k, df, dh, dc, xp) where x.k = v_item;
    if p.happiness >= 85 then v_xp := 0; end if;
    v_plays := v_plays + 1;
  elsif v_action = 'pet' then
    v_item := null;
    v_xp := case when v_pets < 15 then 2 else 0 end;
    v_dh := 4;
    v_pets := v_pets + 1;
  elsif v_action = 'bath' then
    v_item := null;
    if p.cleanliness >= 90 then raise exception 'pet_clean' using detail = '牠已經很乾淨了'; end if;
    v_xp := case when p.cleanliness < 60 then 15 else 0 end;
    v_dc := 100 - p.cleanliness;
    v_dh := -10;
  elsif v_action = 'brush' then
    v_item := null;
    if p.cleanliness >= 98 then raise exception 'pet_clean' using detail = '牠已經很乾淨了'; end if;
    v_xp := case when p.cleanliness < 85 then 6 else 0 end;
    v_dc := 25;
    v_dh := 6;
  else
    raise exception 'invalid_input';
  end if;
  p.fullness := least(100, greatest(0, p.fullness + v_df));
  p.happiness := least(100, greatest(0, p.happiness + v_dh));
  p.cleanliness := least(100, greatest(0, p.cleanliness + v_dc));
  insert into pet_events (couple_id, user_id, action, item, xp) values (v_couple, v_me, v_action, v_item, v_xp);
  if not v_done_before and v_feeds > 0 and v_plays > 0 and v_pets >= 3 and p.bonus_on is distinct from v_today then
    v_bonus := true;
    p.bonus_on := v_today;
    insert into pet_events (couple_id, user_id, action, xp) values (v_couple, v_me, 'bonus', 20);
  end if;
  if v_sbonus then
    insert into pet_events (couple_id, user_id, action, item, xp) values (v_couple, v_me, 'streak', p.streak::text, 30);
  end if;
  p.xp := p.xp + v_xp + case when v_bonus then 20 else 0 end + case when v_sbonus then 30 else 0 end;
  update pets set fullness = p.fullness, happiness = p.happiness, cleanliness = p.cleanliness, xp = p.xp,
    streak = p.streak, last_fed_on = p.last_fed_on, bonus_on = p.bonus_on, updated_at = p.updated_at
  where couple_id = v_couple;
  if v_action = 'feed' and v_item <> 'fish' then
    v_food := case v_item when 'kibble' then '乾乾 🥣' else '罐罐 🥫' end;
    perform enqueue_notification(partner_of(v_me), 'pet', display_name_of(v_me) || ' 餵 ' || p.name || ' 吃了' || v_food,
      p.name || ' 吃得飽飽的 😋', jsonb_build_object('pet', true));
  elsif v_action = 'bath' then
    perform enqueue_notification(partner_of(v_me), 'pet', display_name_of(v_me) || ' 幫 ' || p.name || ' 洗了澡 🛁',
      p.name || ' 香噴噴的，雖然有點不高興', jsonb_build_object('pet', true));
  end if;
  if pet_stage(p.xp) <> v_before then
    insert into pet_events (couple_id, user_id, action, item) values (v_couple, v_me, 'grow', pet_stage(p.xp));
    perform enqueue_notification(m.user_id, 'pet', p.name || ' 長大了！🎉', '現在是' || pet_stage(p.xp) || '了，謝謝你們的照顧',
      jsonb_build_object('pet', true))
    from couple_members m where m.couple_id = v_couple and m.left_at is null;
  end if;
  return pet_payload(v_couple) || jsonb_build_object('result', jsonb_build_object(
    'xp', v_xp + case when v_bonus then 20 else 0 end + case when v_sbonus then 30 else 0 end,
    'bonus', v_bonus, 'streakBonus', v_sbonus,
    'grewTo', case when pet_stage(p.xp) <> v_before then pet_stage(p.xp) end));
end $$;
create or replace function public.app_job_pet_reminders(p_today date) returns integer
language plpgsql security definer set search_path = public set timezone to 'UTC' as $$
declare
  v_sent integer := 0;
  r      record;
begin
  for r in
    select x.couple_id, x.name from (
      select p.couple_id, p.name, (pet_at(p, now())).fullness as fullness, p.last_fed_on, p.reminded_on
      from pets p
    ) x
    where x.last_fed_on is distinct from p_today and x.fullness < 35 and x.reminded_on is distinct from p_today
  loop
    update pets set reminded_on = p_today where couple_id = r.couple_id;
    perform enqueue_notification(m.user_id, 'pet', r.name || ' 肚子餓了 🥺', '今天還沒有人餵牠，快來餵牠吃飯吧',
      jsonb_build_object('pet', true))
    from couple_members m where m.couple_id = r.couple_id and m.left_at is null;
    v_sent := v_sent + 1;
  end loop;
  return v_sent;
end $$;
create or replace function public.app_job_pet(p_today date, p_force boolean default false) returns integer
language plpgsql set search_path = public as $$
begin
  if coalesce(p_force, false) then
    update pets set reminded_on = null where reminded_on = p_today;
  end if;
  return app_job_pet_reminders(p_today);
end $$;
do $$
begin
  if exists (select 1 from pg_extension where extname = 'pg_cron') then
    if exists (select 1 from cron.job where jobname = 'xlk-pet') then
      perform cron.unschedule('xlk-pet');
    end if;
    perform cron.schedule('xlk-pet', '*/10 * * * *', $cron$
      select public.app_job_pet((now() at time zone c.timezone)::date)
      from public.app_config c
      where extract(hour from now() at time zone c.timezone) >= 20
    $cron$);
  end if;
end $$;
revoke all on function public.pet_at(public.pets, timestamptz), public.pet_stage(integer), public.pet_zone(), public.pet_today(),
  public.pet_today_counts(uuid), public.pet_payload(uuid), public.app_job_pet_reminders(date), public.app_job_pet(date, boolean)
  from public, anon, authenticated;
revoke all on function public.app_pet(jsonb), public.app_adopt_pet(jsonb), public.app_rename_pet(jsonb), public.app_pet_action(jsonb)
  from public, anon;
grant execute on function public.app_pet(jsonb), public.app_adopt_pet(jsonb), public.app_rename_pet(jsonb), public.app_pet_action(jsonb)
  to authenticated;
create or replace function public.app_health(jsonb default '{}') returns jsonb
language sql stable set search_path = public set timezone to 'UTC' as $$
  select jsonb_build_object('ok', true, 'time', now(), 'schema', 7)
$$;
notify pgrst, 'reload schema';alter table public.device_status add column if not exists battery_temp numeric(4, 1) check (battery_temp between -40 and 100);
alter table public.device_status add column if not exists thermal smallint check (thermal between 0 and 6);
create or replace function public.app_report_device(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
declare
  v_temp    numeric := case when jsonb_typeof($1->'batteryTemp') = 'number' then ($1->>'batteryTemp')::numeric end;
  v_thermal numeric := case when jsonb_typeof($1->'thermal') = 'number' then ($1->>'thermal')::numeric end;
begin
  if v_temp not between -40 and 100 then v_temp := null; end if;
  if v_thermal not between 0 and 6 then v_thermal := null; end if;
  insert into device_status (user_id, battery_level, is_charging, network, network_label, notifications_silenced,
                             steps_today, last_active_at, device_model, device_name, carrier, battery_temp, thermal, updated_at)
  values (auth.uid(), ($1->>'batteryLevel')::numeric::smallint, ($1->>'isCharging')::boolean, $1->>'network',
          $1->>'networkLabel', ($1->>'notificationsSilenced')::boolean, ($1->>'stepsToday')::numeric::integer,
          nullif($1->>'lastActiveAt', '')::timestamptz, $1->>'deviceModel',
          left(nullif(btrim($1->>'deviceName'), ''), 60), left(nullif(btrim($1->>'carrier'), ''), 60),
          round(v_temp, 1), v_thermal::smallint, now())
  on conflict (user_id) do update set
    battery_level = excluded.battery_level, is_charging = excluded.is_charging, network = excluded.network,
    network_label = excluded.network_label, notifications_silenced = excluded.notifications_silenced,
    steps_today = excluded.steps_today, last_active_at = coalesce(excluded.last_active_at, device_status.last_active_at),
    device_model = excluded.device_model, device_name = excluded.device_name, carrier = excluded.carrier,
    battery_temp = excluded.battery_temp, thermal = excluded.thermal, updated_at = now();
  return jsonb_build_object('ok', true);
end $$;
create or replace function public.app_health(jsonb default '{}') returns jsonb
language sql stable set search_path = public set timezone to 'UTC' as $$
  select jsonb_build_object('ok', true, 'time', now(), 'schema', 8)
$$;
notify pgrst, 'reload schema';
select '設定完成 🎉 回到說明文件做第 3 步' as "小倆口";
commit;
