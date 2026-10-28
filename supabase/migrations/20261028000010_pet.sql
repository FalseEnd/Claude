-- =============================================================================
-- 小倆口 2026-10：一起養的小貓
-- 一對情侶一隻（pets，以 couple_id 為主鍵），兩個人都能餵、陪玩、摸摸、洗澡、梳毛；照顧的紀錄在 pet_events。
-- 規則和 App 的 core/Pet.kt 一樣（Android 的 PetTest 會比對數字）：
--   - 飽足、開心、乾淨 0～100，每小時飽足 -3、開心 -2（餓肚子時再 -1）、乾淨 -1.2；只存上次更新的值，看的時候才算到現在
--   - 照顧得到成長值（真的有幫到才有），長大：小奶貓 0 → 幼貓 100 → 少年貓 400 → 成貓 1000 → 大胖貓 2000
--   - 每天的任務（餵飯、陪玩、摸摸 3 次）都做完 +20；每天都有人餵就累積連續照顧，每滿 7 天 +30
-- 通知（kind = pet）：領養、對方餵正餐和洗澡、長大、晚上 8 點還沒人餵而且肚子餓
-- 包在 upgrade-v2.sql 裡：已經跑過 upgrade-v2.sql 的人再跑一次就好，可以重複執行。
-- =============================================================================

create table if not exists public.pets (
  couple_id    uuid primary key references public.couples(id) on delete cascade,
  name         text not null default '咪咪' check (char_length(name) between 1 and 12),
  coat         text not null default 'orange' check (coat in ('orange', 'gray', 'white', 'black', 'calico', 'tuxedo')),
  fullness     double precision not null default 80 check (fullness between 0 and 100),
  happiness    double precision not null default 80 check (happiness between 0 and 100),
  cleanliness  double precision not null default 100 check (cleanliness between 0 and 100),
  xp           integer not null default 0 check (xp >= 0),
  streak       integer not null default 0 check (streak >= 0),
  last_fed_on  date,
  bonus_on     date,            -- 這天的任務獎勵已經給過了
  reminded_on  date,            -- 這天已經提醒過「肚子餓了」
  adopted_by   uuid references auth.users(id) on delete set null,
  adopted_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now()   -- 上面的狀態是這個時間的
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
-- 只能透過下面的 app_* 函式改（規則在資料庫裡，App 繞不過去）
revoke all on public.pets, public.pet_events from anon, authenticated;
grant select on public.pets, public.pet_events to authenticated;

-- 兩個人都要即時看到牠的變化
drop trigger if exists realtime_pets on public.pets;
create trigger realtime_pets after insert or update or delete on public.pets
  for each row execute function public.tg_changed();

-- 通知種類多一個 pet（可以在通知設定關掉）
alter table public.notification_prefs drop constraint if exists notification_prefs_kind_check,
  add constraint notification_prefs_kind_check check (kind in ('pairing', 'place', 'mood', 'comfort', 'battery_low', 'guess',
    'event', 'anniversary', 'cycle_care', 'sharing_changed', 'diary', 'memo', 'pet'));

create or replace function public.notif_kinds() returns text[]
language sql immutable parallel safe set search_path = public as $$
  select array['pairing', 'place', 'mood', 'comfort', 'battery_low', 'guess', 'event', 'anniversary', 'cycle_care', 'sharing_changed',
               'diary', 'memo', 'pet']
$$;


-- -----------------------------------------------------------------------------
-- 規則
-- -----------------------------------------------------------------------------

-- 從 p.updated_at 過到 p_at：狀態慢慢往下掉
create or replace function public.pet_at(p public.pets, p_at timestamptz) returns public.pets
language plpgsql immutable set search_path = public as $$
declare
  h      double precision := greatest(0, extract(epoch from (p_at - p.updated_at)) / 3600.0);
  hungry double precision;
begin
  if h = 0 then return p; end if;
  -- 飽足掉到 20 以下之後的那幾個小時，開心掉得比較快
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

-- 「今天」：app_config.timezone（預設台北時間）的日期，和排程一樣
create or replace function public.pet_zone() returns text
language sql stable security definer set search_path = public as $$
  select coalesce((select timezone from app_config), 'Asia/Taipei')
$$;
create or replace function public.pet_today() returns date
language sql stable security definer set search_path = public as $$
  select (now() at time zone pet_zone())::date
$$;

-- 今天做過幾次：餵、玩、摸摸、小魚乾
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

-- App 要的樣子：狀態算到現在、今天做過的、最近 30 筆照顧紀錄
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


-- -----------------------------------------------------------------------------
-- RPC
-- -----------------------------------------------------------------------------

create or replace function public.app_pet(jsonb default '{}') returns jsonb
language plpgsql stable security definer set search_path = public set timezone to 'UTC' as $$
declare
  v_couple uuid := couple_of(auth.uid());
begin
  if v_couple is null then raise exception 'not_paired'; end if;
  return pet_payload(v_couple);
end $$;

-- 領養：{name, coat}。一對情侶一隻，已經有了就是 pet_exists
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

-- 照顧一次：{action: feed|play|pet|bath|brush, item}。回傳 pet_payload 加上 result {xp, bonus, streakBonus, grewTo}
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

  -- 今天的任務第一次全部做完
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

  -- 對方：餵正餐（乾乾、罐罐；小魚乾是零食不通知）、洗澡時通知；長大了兩個人都通知
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


-- -----------------------------------------------------------------------------
-- 晚上的提醒：當地時間 8 點以後每 10 分鐘看一次，今天還沒有人餵、肚子又餓（飽足 < 35）就通知兩個人；
-- 每隻貓一天最多提醒一次（reminded_on），8 點還不餓、10 點才餓的也提醒得到
-- -----------------------------------------------------------------------------

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

-- 排程呼叫這個；p_force 給測試用：今天提醒過的也再提醒一次
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


-- -----------------------------------------------------------------------------
-- 權限
-- -----------------------------------------------------------------------------

revoke all on function public.pet_at(public.pets, timestamptz), public.pet_stage(integer), public.pet_zone(), public.pet_today(),
  public.pet_today_counts(uuid), public.pet_payload(uuid), public.app_job_pet_reminders(date), public.app_job_pet(date, boolean)
  from public, anon, authenticated;
revoke all on function public.app_pet(jsonb), public.app_adopt_pet(jsonb), public.app_rename_pet(jsonb), public.app_pet_action(jsonb)
  from public, anon;
grant execute on function public.app_pet(jsonb), public.app_adopt_pet(jsonb), public.app_rename_pet(jsonb), public.app_pet_action(jsonb)
  to authenticated;

-- App 用 schema 判斷資料庫有沒有更新：…6 再加更多安撫；7 再加一起養的小貓
create or replace function public.app_health(jsonb default '{}') returns jsonb
language sql stable set search_path = public set timezone to 'UTC' as $$
  select jsonb_build_object('ok', true, 'time', now(), 'schema', 7)
$$;

notify pgrst, 'reload schema';
