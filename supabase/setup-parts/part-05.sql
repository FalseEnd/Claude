-- 小倆口 Supabase 設定：第 5 份，共 8 份（請照順序執行，每份只要執行一次）
-- 這個檔案由 supabase/scripts/split-setup.js 從 supabase/migrations/ 產生，請勿手動修改。

begin;
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

select '第 5 份完成 ✅ 接著貼第 6 份' as "小倆口";

commit;
