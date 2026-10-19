-- =============================================================================
-- 小倆口 2026-10：手機名稱與電信
-- 對方的手機卡片除了型號（OPPO CPH2689），也顯示手機名稱（設定 › 關於手機 › 裝置名稱，例如「Find X8 Pro」）
-- 和電信（中華電信、台灣大哥大、遠傳電信…）。
-- 和其他手機狀態一樣，只有打開「手機狀態」分享、沒有暫停時對方才看得到（device_status 的 RLS 不變）。
-- 包在 upgrade-v2.sql 裡：已經跑過 upgrade-v2.sql 的人再跑一次就好，可以重複執行。
-- =============================================================================

alter table public.device_status add column if not exists device_name text check (char_length(device_name) <= 60);
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

-- App 用 schema 判斷資料庫有沒有更新：2 有個人資料、日曆紀錄、備忘錄；3 再加手機名稱與電信
create or replace function public.app_health(jsonb default '{}') returns jsonb
language sql stable set search_path = public set timezone to 'UTC' as $$
  select jsonb_build_object('ok', true, 'time', now(), 'schema', 3)
$$;
