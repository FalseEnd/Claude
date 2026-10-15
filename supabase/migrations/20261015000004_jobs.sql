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
