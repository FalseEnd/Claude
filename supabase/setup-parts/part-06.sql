begin;
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
select '第 6 份完成 ✅ 接著貼第 7 份' as "小倆口";
commit;
