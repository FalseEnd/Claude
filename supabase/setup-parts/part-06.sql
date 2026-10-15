-- 小倆口 Supabase 設定：第 6 份，共 8 份（請照順序執行，每份只要執行一次）
-- 這個檔案由 supabase/scripts/split-setup.js 從 supabase/migrations/ 產生，請勿手動修改。

begin;
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

select '第 6 份完成 ✅ 接著貼第 7 份' as "小倆口";

commit;
