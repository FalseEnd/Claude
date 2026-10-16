begin;
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
select '第 4 份完成 ✅ 接著貼第 5 份' as "小倆口";
commit;
