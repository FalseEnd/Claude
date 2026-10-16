begin;
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
alter table public.notification_outbox enable row level security;
alter table public.job_runs            enable row level security;
alter table public.app_config          enable row level security;
create policy "profiles: self and partner read" on public.profiles for select using (id = auth.uid() or public.is_partner(id));
create policy "profiles: self insert" on public.profiles for insert with check (id = auth.uid());
create policy "profiles: self update" on public.profiles for update using (id = auth.uid()) with check (id = auth.uid());
create policy "couples: members read" on public.couples for select using (id = public.my_couple_id());
create policy "couples: members update" on public.couples for update using (id = public.my_couple_id()) with check (id = public.my_couple_id());
create policy "couple_members: members read" on public.couple_members for select using (couple_id = public.my_couple_id());
create policy "pair_invites: own read" on public.pair_invites for select using (inviter_id = auth.uid());
create policy "sharing: self and partner read" on public.sharing_settings for select using (user_id = auth.uid() or public.is_partner(user_id));
create policy "sharing: self insert" on public.sharing_settings for insert with check (user_id = auth.uid());
create policy "sharing: self update" on public.sharing_settings for update using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "device: owner" on public.device_status for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "device: partner read" on public.device_status for select using (public.partner_shares(user_id, 'device'));
create policy "locations: owner" on public.locations for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "locations: partner read" on public.locations for select using (public.partner_shares(user_id, 'location'));
create policy "moods: owner" on public.moods for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "moods: partner read" on public.moods for select using (public.partner_shares(user_id, 'mood'));
create policy "expenses: owner" on public.expenses for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "guesses: guesser or owner read" on public.expense_guesses for select using (
  guesser_id = auth.uid() or exists (select 1 from public.expenses e where e.id = expense_id and e.user_id = auth.uid()));
create policy "cycle_settings: owner" on public.cycle_settings for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "cycle_settings: partner read" on public.cycle_settings for select using (public.partner_shares(user_id, 'cycle'));
create policy "cycle_periods: owner" on public.cycle_periods for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "cycle_periods: partner read" on public.cycle_periods for select using (public.partner_shares(user_id, 'cycle'));
create policy "cycle_periods: partner write" on public.cycle_periods for insert with check (
  public.partner_shares(user_id, 'cycle')
  and exists (select 1 from public.cycle_settings s where s.user_id = cycle_periods.user_id and s.partner_can_edit));
create policy "cycle_periods: partner update" on public.cycle_periods for update using (
  public.partner_shares(user_id, 'cycle')
  and exists (select 1 from public.cycle_settings s where s.user_id = cycle_periods.user_id and s.partner_can_edit));
create policy "cycle_logs: owner only" on public.cycle_logs for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "places: couple read" on public.places for select using (couple_id = public.my_couple_id());
create policy "places: couple insert" on public.places for insert with check (
  couple_id = public.my_couple_id() and created_by = auth.uid()
  and (watch_user is null or watch_user = auth.uid() or public.is_partner(watch_user)));
create policy "places: couple update" on public.places for update using (couple_id = public.my_couple_id()) with check (
  couple_id = public.my_couple_id() and (watch_user is null or watch_user = auth.uid() or public.is_partner(watch_user)));
create policy "places: couple delete" on public.places for delete using (couple_id = public.my_couple_id());
create policy "place_events: couple read" on public.place_events for select using (
  exists (select 1 from public.places p where p.id = place_id and p.couple_id = public.my_couple_id()));
create policy "place_events: self report" on public.place_events for insert with check (
  user_id = auth.uid() and public.i_share('location')
  and exists (select 1 from public.places p where p.id = place_id and p.couple_id = public.my_couple_id()));
create policy "comforts: couple read" on public.comforts for select using (
  couple_id = public.my_couple_id() and auth.uid() in (sender_id, receiver_id));
create policy "comforts: send to partner" on public.comforts for insert with check (
  sender_id = auth.uid() and couple_id = public.my_couple_id() and public.is_partner(receiver_id));
create policy "comforts: receiver marks read" on public.comforts for update using (receiver_id = auth.uid()) with check (receiver_id = auth.uid());
create policy "anniversaries: couple" on public.anniversaries for all
  using (couple_id = public.my_couple_id()) with check (couple_id = public.my_couple_id());
create policy "events: couple read" on public.events for select using (couple_id = public.my_couple_id());
create policy "events: couple insert" on public.events for insert with check (couple_id = public.my_couple_id() and created_by = auth.uid());
create policy "events: couple update" on public.events for update using (couple_id = public.my_couple_id()) with check (couple_id = public.my_couple_id());
create policy "events: creator delete" on public.events for delete using (couple_id = public.my_couple_id() and created_by = auth.uid());
create policy "activity: couple read" on public.activity_log for select using (couple_id = public.my_couple_id());
create policy "notification_prefs: owner" on public.notification_prefs for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "push_tokens: owner" on public.push_tokens for all using (user_id = auth.uid()) with check (user_id = auth.uid());
revoke execute on function public.enqueue_notification(uuid, text, text, text, jsonb) from public, anon, authenticated;
revoke execute on function public.log_activity(uuid, uuid, text, jsonb) from public, anon, authenticated;
revoke execute on function public.gen_pair_code() from public, anon, authenticated;
revoke execute on function public.daily_reminders(date) from public, anon, authenticated;
revoke execute on function public.couple_of(uuid) from public, anon, authenticated;
revoke execute on function public.partner_of(uuid) from public, anon, authenticated;
revoke execute on function public.shares(uuid, text) from public, anon, authenticated;
revoke execute on function public.display_name_of(uuid) from public, anon, authenticated;
create or replace function public.handle_new_user() returns trigger
language plpgsql security definer set search_path = public as $$
declare
  v_max  integer := coalesce((select max_users from app_config), 2);
  v_name text;
begin
  perform pg_advisory_xact_lock(7311);
  if (select count(*) from profiles) >= v_max then
    raise exception 'registration_closed';
  end if;
  v_name := coalesce(
    nullif(left(trim(new.raw_user_meta_data->>'display_name'), 30), ''),
    nullif(left(split_part(coalesce(new.email, ''), '@', 1), 30), ''),
    '親愛的');
  insert into profiles (id, display_name) values (new.id, v_name);
  insert into sharing_settings (user_id) values (new.id) on conflict (user_id) do nothing;
  return new;
end $$;
create trigger on_auth_user_created after insert on auth.users
  for each row execute function public.handle_new_user();
revoke execute on function public.handle_new_user() from public, anon, authenticated;create table public.realtime_events (
  id          bigserial primary key,
  user_id     uuid not null references auth.users(id) on delete cascade,
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
select '第 3 份完成 ✅ 接著貼第 4 份' as "小倆口";
commit;
