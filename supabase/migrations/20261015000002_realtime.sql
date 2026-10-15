-- =============================================================================
-- 即時更新：App 用 Supabase Realtime 訂閱兩張表的 INSERT
--   notification_outbox → 通知（App 收到後呼叫 app_ack_notifications）
--   realtime_events     → 「哪張表變了」（changed）與「現在在哪？」（locate）
-- 兩張表都有 Row Level Security，每個人只收得到自己的。
-- =============================================================================

create table public.realtime_events (
  id          bigserial primary key,
  user_id     uuid not null references auth.users(id) on delete cascade,  -- 收件人
  kind        text not null check (kind in ('changed', 'locate')),
  table_name  text,
  op          text,
  by_user     uuid,
  created_at  timestamptz not null default now()
);
create index realtime_events_user on public.realtime_events (user_id, id);

alter table public.realtime_events enable row level security;
create policy "realtime_events: own read" on public.realtime_events for select using (user_id = auth.uid());
create policy "outbox: own read" on public.notification_outbox for select using (recipient_id = auth.uid());

-- 資料變動 → 這對情侶的每個人各一筆 changed，App 重新抓對應的畫面。
-- 擁有者的個人資料（經期、心情、開銷、位置、手機狀態）只在擁有者有分享那一類時才通知對方，
-- 不然對方光看「什麼時候變了」就能猜出沒分享的事（例如什麼時候記了經期、買了禮物）。
create or replace function public.tg_changed() returns trigger
language plpgsql security definer set search_path = public as $$
declare
  v_row    jsonb := to_jsonb(case when tg_op = 'DELETE' then old else new end);
  v_old    jsonb := case when tg_op = 'UPDATE' then to_jsonb(old) end;
  v_user   uuid;
  v_couple uuid;
  v_kind   text;      -- 這張表屬於哪一類分享；null = 兩人共用的資料
  v_shown  boolean;   -- 對方能不能收到這筆的 changed
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
    -- 兩人共用的資料；猜花費（expense_guesses）只會發生在對方看得到的開銷上，兩個人本來就都看得到
    v_shown := true;
  elsif tg_table_name = 'expenses' then
    -- 開銷：改之前或改之後對方看得到才通知（例如從「公開」改成「私人」，對方的清單也要拿掉）
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
    select v_user where v_user is not null   -- 沒有配對（或剛解除配對）時，至少通知自己
  ) t
  -- 刪除帳號時會連帶刪除資料，這時不用再通知已經不存在的人
  where exists (select 1 from auth.users u where u.id = t.uid);

  -- 分享設定改了：請對方重新抓受影響的資料（開啟後才看得到之前記的、關閉或暫停後要藏起來）
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

-- 加進 Supabase Realtime 的 publication（Supabase 專案本來就有 supabase_realtime）
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
end $$;
