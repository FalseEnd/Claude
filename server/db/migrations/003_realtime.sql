-- 自架伺服器需要的補充：即時通知、提醒紀錄、裝置型號。

alter table public.device_status add column if not exists device_model text;
alter table public.events add column if not exists reminded_at timestamptz;
alter table public.cycle_settings add column if not exists care_sent_for date;

-- 伺服器排程（每日提醒）執行紀錄，重開機也不會重複執行
create table if not exists public.job_runs (
  name    text not null,
  run_on  date not null,
  primary key (name, run_on)
);
alter table public.job_runs enable row level security;  -- 沒有 policy：用戶端不能碰

-- 新通知 → 伺服器 LISTEN xlk_outbox，經由 WebSocket 送到 App
create or replace function public.tg_outbox_notify() returns trigger
language plpgsql as $$
begin
  perform pg_notify('xlk_outbox', new.id::text);
  return new;
end $$;
create trigger outbox_notify after insert on public.notification_outbox
  for each row execute function public.tg_outbox_notify();

-- 資料變動 → 伺服器 LISTEN xlk_changed，通知這對情侶的 App 重新整理對應的畫面
create or replace function public.tg_changed() returns trigger
language plpgsql security definer set search_path = public as $$
declare
  v_row    jsonb := to_jsonb(case when tg_op = 'DELETE' then old else new end);
  v_user   uuid;
  v_couple uuid;
begin
  v_user := coalesce(v_row->>'user_id', v_row->>'guesser_id', v_row->>'sender_id',
                     case when tg_table_name = 'profiles' then v_row->>'id' end)::uuid;
  v_couple := coalesce(
    case when tg_table_name = 'couples' then (v_row->>'id')::uuid end,
    (v_row->>'couple_id')::uuid,
    couple_of(v_user));
  if v_couple is not null or v_user is not null then
    perform pg_notify('xlk_changed', json_build_object(
      'couple', v_couple, 'user', v_user, 'table', tg_table_name, 'op', lower(tg_op))::text);
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
