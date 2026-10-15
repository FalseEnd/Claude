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
