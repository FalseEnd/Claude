-- =============================================================================
-- 小倆口 2026-10：手機溫度
-- 對方的手機卡片多了電池溫度（°C）和系統的過熱等級（Android 10 以上的 thermal status，0～6）。
-- 和其他手機狀態一樣，只有打開「手機狀態」分享、沒有暫停時對方才看得到（device_status 的 RLS 不變）。
-- 包在 upgrade-v2.sql 裡：已經跑過 upgrade-v2.sql 的人再跑一次就好，可以重複執行。
-- =============================================================================

alter table public.device_status add column if not exists battery_temp numeric(4, 1) check (battery_temp between -40 and 100);
alter table public.device_status add column if not exists thermal smallint check (thermal between 0 and 6);

-- 不合理的溫度、等級（手機回報錯了）當作沒有，不要讓整筆手機狀態存不進去
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

-- App 用 schema 判斷資料庫有沒有更新：…7 一起養的小貓；8 再加手機溫度
create or replace function public.app_health(jsonb default '{}') returns jsonb
language sql stable set search_path = public set timezone to 'UTC' as $$
  select jsonb_build_object('ok', true, 'time', now(), 'schema', 8)
$$;

notify pgrst, 'reload schema';
