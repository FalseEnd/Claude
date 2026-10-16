begin;
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
notify pgrst, 'reload schema';
select '設定完成 🎉 回到說明文件做第 3 步' as "小倆口";
commit;
