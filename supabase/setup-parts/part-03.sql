-- 小倆口 Supabase 設定：第 3 份，共 8 份（請照順序執行，每份只要執行一次）
-- 這個檔案由 supabase/scripts/split-setup.js 從 supabase/migrations/ 產生，請勿手動修改。

begin;
-- 計分規則與 prototype/core.js 的 GUESS_TIERS 相同；在資料庫算，猜之前拿不到答案
create or replace function public.submit_guess(p_expense uuid, p_guess numeric)
returns table (actual_amount numeric, score_points smallint, error_ratio numeric)
language plpgsql security definer set search_path = public as $$
declare
  v_e      expenses;
  v_ratio  numeric;
  v_points smallint;
begin
  select * into v_e from expenses where expenses.id = p_expense;
  if not found or v_e.user_id is distinct from partner_of(auth.uid()) or not v_e.guessable
     or not shares(v_e.user_id, 'expenses') or v_e.visibility <> 'shared' then
    raise exception 'expense_not_guessable';
  end if;
  if p_guess is null or p_guess < 0 then raise exception 'invalid_guess'; end if;

  v_ratio := abs(p_guess - v_e.amount) / v_e.amount;
  v_points := case when v_ratio <= 0.03 then 100 when v_ratio <= 0.10 then 80
                   when v_ratio <= 0.25 then 50 when v_ratio <= 0.50 then 20 else 5 end;
  insert into expense_guesses (expense_id, guesser_id, guess_amount, points)
  values (p_expense, auth.uid(), p_guess, v_points);

  perform log_activity(couple_of(auth.uid()), auth.uid(), 'guess', jsonb_build_object(
    'expense_id', p_expense, 'merchant', v_e.merchant, 'guess', p_guess, 'actual', v_e.amount, 'points', v_points));
  perform enqueue_notification(v_e.user_id, 'guess', display_name_of(auth.uid()) || ' 猜了你的花費',
    v_e.merchant || '：猜 NT$' || round(p_guess) || '，實際 NT$' || round(v_e.amount) || '（' || v_points || ' 分）');
  return query select v_e.amount, v_points, round(v_ratio, 4);
end $$;

-- =============================================================================
-- Triggers：寫入歷史紀錄並通知對方
-- =============================================================================

create or replace function public.tg_mood_inserted() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if not shares(new.user_id, 'mood') then return new; end if;
  perform log_activity(couple_of(new.user_id), new.user_id, 'mood',
    jsonb_build_object('mood', new.mood, 'note', new.note));
  perform enqueue_notification(partner_of(new.user_id), 'mood',
    display_name_of(new.user_id) || ' 現在覺得' || mood_label(new.mood),
    case when new.mood in ('tired', 'anxious', 'sad', 'angry', 'sick')
         then coalesce(new.note || '。', '') || '要不要安撫一下？'
         else coalesce(new.note, '') end,
    jsonb_build_object('mood', new.mood));
  return new;
end $$;
create trigger mood_inserted after insert on public.moods
  for each row execute function public.tg_mood_inserted();

create or replace function public.tg_comfort_before() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  -- 防洗版：10 分鐘內最多 20 次
  if (select count(*) from comforts where sender_id = new.sender_id
      and created_at > now() - interval '10 minutes') >= 20 then
    raise exception 'rate_limited';
  end if;
  return new;
end $$;
create trigger comfort_before before insert on public.comforts
  for each row execute function public.tg_comfort_before();

create or replace function public.tg_comfort_inserted() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  perform log_activity(new.couple_id, new.sender_id, 'comfort',
    jsonb_build_object('kind', new.kind, 'message', new.message));
  perform enqueue_notification(new.receiver_id, 'comfort',
    display_name_of(new.sender_id) || ' ' || comfort_text(new.kind),
    coalesce(new.message, '點開回應一下吧'), jsonb_build_object('comfort_id', new.id));
  return new;
end $$;
create trigger comfort_inserted after insert on public.comforts
  for each row execute function public.tg_comfort_inserted();

create or replace function public.tg_place_event_inserted() returns trigger
language plpgsql security definer set search_path = public as $$
declare v_place places;
begin
  select * into v_place from places where id = new.place_id;
  if v_place.watch_user is not null and v_place.watch_user <> new.user_id then return new; end if;
  perform log_activity(v_place.couple_id, new.user_id, 'place_' || new.kind,
    jsonb_build_object('place_id', v_place.id, 'place', v_place.name));
  if (new.kind = 'arrive' and v_place.notify_arrive) or (new.kind = 'leave' and v_place.notify_leave) then
    perform enqueue_notification(partner_of(new.user_id), 'place',
      display_name_of(new.user_id) || case new.kind when 'arrive' then ' 已抵達 ' else ' 已離開 ' end || v_place.name,
      to_char(new.occurred_at at time zone 'Asia/Taipei', 'HH24:MI'),
      jsonb_build_object('place_id', v_place.id));
  end if;
  return new;
end $$;
create trigger place_event_inserted after insert on public.place_events
  for each row execute function public.tg_place_event_inserted();

-- 對方新增會偵測「我」的地點，或把地點改成偵測我、搬了位置、改了範圍時，一定通知我（透明度，不能關閉）
create or replace function public.tg_place_saved() returns trigger
language plpgsql security definer set search_path = public as $$
declare
  v_actor   uuid := coalesce(auth.uid(), new.created_by);
  v_partner uuid := partner_of(v_actor);
begin
  if v_partner is not null and (new.watch_user is null or new.watch_user = v_partner)
     and (tg_op = 'INSERT' or new.watch_user is distinct from old.watch_user
          or new.lat <> old.lat or new.lng <> old.lng or new.radius_m <> old.radius_m) then
    insert into notification_outbox (recipient_id, kind, title, body, data)
    values (v_partner, 'sharing_changed',
            display_name_of(v_actor) || case when tg_op = 'INSERT' then ' 新增了地點「' else ' 修改了地點「' end || new.name || '」',
            '你抵達或離開這裡時，對方會收到通知。可以在地圖頁查看或關閉位置分享。',
            jsonb_build_object('place_id', new.id));
  end if;
  return new;
end $$;
create trigger place_saved after insert or update of watch_user, lat, lng, radius_m on public.places
  for each row execute function public.tg_place_saved();

create or replace function public.tg_event_changed() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if tg_op = 'INSERT' then
    perform log_activity(new.couple_id, new.created_by, 'event_created',
      jsonb_build_object('event_id', new.id, 'title', new.title, 'starts_at', new.starts_at));
    perform enqueue_notification(partner_of(new.created_by), 'event',
      display_name_of(new.created_by) || ' 約你：' || new.title,
      to_char(new.starts_at at time zone 'Asia/Taipei', 'MM/DD HH24:MI'), jsonb_build_object('event_id', new.id));
  elsif new.status is distinct from old.status and new.status in ('confirmed', 'declined') then
    perform log_activity(new.couple_id, auth.uid(), 'event_' || new.status,
      jsonb_build_object('event_id', new.id, 'title', new.title));
    perform enqueue_notification(new.created_by, 'event',
      display_name_of(auth.uid()) || case new.status when 'confirmed' then ' 答應了：' else ' 婉拒了：' end || new.title,
      to_char(new.starts_at at time zone 'Asia/Taipei', 'MM/DD HH24:MI'), jsonb_build_object('event_id', new.id));
  end if;
  return new;
end $$;
create trigger event_changed after insert or update on public.events
  for each row execute function public.tg_event_changed();

create or replace function public.tg_device_status_changed() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if shares(new.user_id, 'device')
     and coalesce(old.battery_level, 100) > 20 and new.battery_level <= 20 and not coalesce(new.is_charging, false) then
    perform log_activity(couple_of(new.user_id), new.user_id, 'battery_low', jsonb_build_object('level', new.battery_level));
    perform enqueue_notification(partner_of(new.user_id), 'battery_low',
      display_name_of(new.user_id) || ' 的手機電量剩 ' || new.battery_level || '%',
      '可能快聯絡不到了，提醒對方充電吧');
  end if;
  return new;
end $$;
create trigger device_status_changed after update on public.device_status
  for each row execute function public.tg_device_status_changed();

-- 分享設定一改就讓對方知道（關閉、暫停都看得到，不能偷偷改）
create or replace function public.tg_sharing_changed() returns trigger
language plpgsql security definer set search_path = public as $$
declare v_changes jsonb := '{}';
begin
  if new.share_location is distinct from old.share_location then v_changes := v_changes || jsonb_build_object('location', new.share_location); end if;
  if new.share_device   is distinct from old.share_device   then v_changes := v_changes || jsonb_build_object('device', new.share_device); end if;
  if new.share_expenses is distinct from old.share_expenses then v_changes := v_changes || jsonb_build_object('expenses', new.share_expenses); end if;
  if new.share_mood     is distinct from old.share_mood     then v_changes := v_changes || jsonb_build_object('mood', new.share_mood); end if;
  if new.share_cycle    is distinct from old.share_cycle    then v_changes := v_changes || jsonb_build_object('cycle', new.share_cycle); end if;
  if new.paused_until   is distinct from old.paused_until   then v_changes := v_changes || jsonb_build_object('paused_until', new.paused_until); end if;
  if v_changes = '{}' then return new; end if;
  perform log_activity(couple_of(new.user_id), new.user_id, 'sharing_changed', v_changes);
  perform enqueue_notification(partner_of(new.user_id), 'sharing_changed',
    display_name_of(new.user_id) || ' 更新了分享設定', '到「我的 › 分享設定」看看有哪些改變');
  return new;
end $$;
create trigger sharing_changed after update on public.sharing_settings
  for each row execute function public.tg_sharing_changed();

-- =============================================================================
-- 每日排程：紀念日、交往天數里程碑；解除配對 30 天後清除共享資料
-- pg_cron 每天 09:00 後呼叫一次（app_job_daily，見 20261015000004_jobs.sql），
-- 經期關懷提醒也在那裡，用 prototype/core.js 同一套週期預測。
-- =============================================================================

create or replace function public.daily_reminders(p_today date default (now() at time zone 'Asia/Taipei')::date)
returns integer
language plpgsql security definer set search_path = public as $$
declare
  r       record;
  v_next  date;
  v_left  integer;
  v_day   integer;
  v_sent  integer := 0;
begin
  for r in
    select a.*, m.user_id as member_id
    from anniversaries a
    join couples c on c.id = a.couple_id and c.ended_at is null
    join couple_members m on m.couple_id = a.couple_id and m.left_at is null
    where a.calendar = 'solar'
  loop
    v_next := case when r.repeat = 'yearly' then next_yearly(r.date, p_today) else r.date end;
    v_left := v_next - p_today;
    if v_left = any (r.remind_days) then
      perform enqueue_notification(r.member_id, 'anniversary', r.title,
        case when v_left = 0 then '就是今天！' else '還有 ' || v_left || ' 天' end,
        jsonb_build_object('anniversary_id', r.id, 'date', v_next));
      v_sent := v_sent + 1;
    end if;
  end loop;

  for r in
    select c.id, c.together_since, m.user_id as member_id
    from couples c join couple_members m on m.couple_id = c.id and m.left_at is null
    where c.ended_at is null and c.together_since is not null
  loop
    v_day := p_today - r.together_since + 1;
    if v_day > 0 and (v_day % 100 = 0 or v_day in (520, 1314)) then
      perform enqueue_notification(r.member_id, 'anniversary', '在一起第 ' || v_day || ' 天', '今天也要好好相處喔');
      v_sent := v_sent + 1;
    end if;
  end loop;

  delete from couples where ended_at < now() - interval '30 days';
  return v_sent;
end $$;

-- =============================================================================
-- Row Level Security
-- =============================================================================

alter table public.profiles            enable row level security;
alter table public.couples             enable row level security;
alter table public.couple_members      enable row level security;
alter table public.pair_invites        enable row level security;
alter table public.sharing_settings    enable row level security;
alter table public.device_status       enable row level security;
alter table public.locations           enable row level security;
alter table public.places              enable row level security;
alter table public.place_events        enable row level security;
alter table public.expenses            enable row level security;
alter table public.expense_guesses     enable row level security;
alter table public.moods               enable row level security;
alter table public.comforts            enable row level security;
alter table public.anniversaries       enable row level security;
alter table public.events              enable row level security;
alter table public.cycle_settings      enable row level security;
alter table public.cycle_periods       enable row level security;
alter table public.cycle_logs          enable row level security;
alter table public.activity_log        enable row level security;
alter table public.notification_prefs  enable row level security;
alter table public.push_tokens         enable row level security;
alter table public.notification_outbox enable row level security;  -- 只能讀自己的（見 realtime 遷移）
alter table public.job_runs            enable row level security;  -- 沒有 policy = 用戶端完全不能碰
alter table public.app_config          enable row level security;

-- 帳號
create policy "profiles: self and partner read" on public.profiles for select using (id = auth.uid() or public.is_partner(id));
create policy "profiles: self insert" on public.profiles for insert with check (id = auth.uid());
create policy "profiles: self update" on public.profiles for update using (id = auth.uid()) with check (id = auth.uid());

create policy "couples: members read" on public.couples for select using (id = public.my_couple_id());
create policy "couples: members update" on public.couples for update using (id = public.my_couple_id()) with check (id = public.my_couple_id());
create policy "couple_members: members read" on public.couple_members for select using (couple_id = public.my_couple_id());
create policy "pair_invites: own read" on public.pair_invites for select using (inviter_id = auth.uid());

-- 分享設定：自己可改；對方可以看（知道自己被分享了什麼）
create policy "sharing: self and partner read" on public.sharing_settings for select using (user_id = auth.uid() or public.is_partner(user_id));

select '第 3 份完成 ✅ 接著貼第 4 份' as "小倆口";

commit;
