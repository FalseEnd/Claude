begin;
create or replace function public.enqueue_notification(
  p_recipient uuid, p_kind text, p_title text, p_body text, p_data jsonb default '{}'
) returns void
language sql security definer set search_path = public as $$
  insert into notification_outbox (recipient_id, kind, title, body, data)
  select p_recipient, p_kind, p_title, p_body, p_data
  where p_recipient is not null
    and not exists (select 1 from notification_prefs
                    where user_id = p_recipient and kind = p_kind and not enabled)
$$;
create or replace function public.log_activity(p_couple uuid, p_actor uuid, p_kind text, p_payload jsonb default '{}')
returns void
language sql security definer set search_path = public as $$
  insert into activity_log (couple_id, actor_id, kind, payload)
  select p_couple, p_actor, p_kind, p_payload where p_couple is not null
$$;
create or replace function public.gen_pair_code() returns text
language plpgsql volatile as $$
declare
  alphabet constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  bytes bytea := decode(replace(gen_random_uuid()::text, '-', ''), 'hex');
  code text := '';
begin
  for i in 0..5 loop
    code := code || substr(alphabet, (get_byte(bytes, i) % 32) + 1, 1);
  end loop;
  return code;
end $$;
create or replace function public.mood_label(p_mood text) returns text
language sql immutable as $$
  select case p_mood
    when 'happy' then '開心' when 'sweet' then '甜蜜' when 'calm' then '平靜'
    when 'missyou' then '想你' when 'tired' then '好累' when 'anxious' then '焦慮'
    when 'sad' then '難過' when 'angry' then '生氣' when 'sick' then '不舒服' else p_mood end
$$;
create or replace function public.comfort_text(p_kind text) returns text
language sql immutable as $$
  select case p_kind
    when 'hug' then '給了你一個大大的抱抱' when 'pat' then '摸摸你的頭，說辛苦了'
    when 'kiss' then '親了你一下' when 'here' then '說：我在，想聊隨時找我'
    when 'cheer' then '幫你加油打氣' when 'treat' then '說等等請你喝一杯'
    else '傳了悄悄話給你' end
$$;
create or replace function public.next_yearly(p_date date, p_today date) returns date
language plpgsql immutable as $$
declare
  m int := extract(month from p_date);
  d int := extract(day from p_date);
  y int := greatest(extract(year from p_today)::int, extract(year from p_date)::int);
  candidate date;
begin
  loop
    candidate := case
      when m = 2 and d = 29 and not ((y % 4 = 0 and y % 100 <> 0) or y % 400 = 0) then make_date(y, 2, 28)
      else make_date(y, m, d) end;
    if candidate >= p_today then return candidate; end if;
    y := y + 1;
  end loop;
end $$;
create or replace function public.create_invite() returns text
language plpgsql security definer set search_path = public as $$
declare v_code text;
begin
  if auth.uid() is null then raise exception 'not_authenticated'; end if;
  if couple_of(auth.uid()) is not null then raise exception 'already_paired'; end if;
  delete from pair_invites where inviter_id = auth.uid() and accepted_at is null;
  loop
    v_code := gen_pair_code();
    begin
      insert into pair_invites (code, inviter_id) values (v_code, auth.uid());
      return v_code;
    exception when unique_violation then
    end;
  end loop;
end $$;
create or replace function public.accept_invite(p_code text) returns uuid
language plpgsql security definer set search_path = public as $$
declare
  v_me     uuid := auth.uid();
  v_inv    pair_invites;
  v_couple uuid;
begin
  if v_me is null then raise exception 'not_authenticated'; end if;
  select * into v_inv from pair_invites where code = upper(trim(p_code)) for update;
  if not found or v_inv.accepted_at is not null or v_inv.expires_at < now() then
    raise exception 'invite_invalid';
  end if;
  if v_inv.inviter_id = v_me then raise exception 'cannot_pair_with_self'; end if;
  if exists (select 1 from couple_members where user_id in (v_me, v_inv.inviter_id) and left_at is null) then
    raise exception 'already_paired';
  end if;
  insert into couples default values returning id into v_couple;
  insert into couple_members (couple_id, user_id) values (v_couple, v_inv.inviter_id), (v_couple, v_me);
  update pair_invites set accepted_by = v_me, accepted_at = now() where code = v_inv.code;
  insert into sharing_settings (user_id) values (v_inv.inviter_id), (v_me) on conflict (user_id) do nothing;
  perform log_activity(v_couple, v_me, 'paired');
  perform enqueue_notification(v_inv.inviter_id, 'pairing', '配對成功',
    display_name_of(v_me) || ' 接受了你的邀請，一起決定要分享哪些資訊吧');
  return v_couple;
end $$;
create or replace function public.unpair() returns void
language plpgsql security definer set search_path = public as $$
declare
  v_couple  uuid := couple_of(auth.uid());
  v_partner uuid := partner_of(auth.uid());
begin
  if auth.uid() is null then raise exception 'not_authenticated'; end if;
  if v_couple is null then raise exception 'not_paired'; end if;
  update couple_members set left_at = now() where couple_id = v_couple and left_at is null;
  update couples set ended_at = now() where id = v_couple;
  perform log_activity(v_couple, auth.uid(), 'unpaired');
  perform enqueue_notification(v_partner, 'pairing', '已解除配對', '所有即時分享已經停止');
end $$;
create or replace function public.tg_couple_member_deleted() returns trigger
language plpgsql security definer set search_path = public as $$
declare v_left uuid;
begin
  if old.left_at is not null then return old; end if;
  update couples set ended_at = coalesce(ended_at, now()) where id = old.couple_id;
  for v_left in
    update couple_members set left_at = now() where couple_id = old.couple_id and left_at is null returning user_id
  loop
    perform enqueue_notification(v_left, 'pairing', '已解除配對', '對方的帳號已經刪除，所有即時分享已經停止');
  end loop;
  return old;
end $$;
create trigger couple_member_deleted after delete on public.couple_members
  for each row execute function public.tg_couple_member_deleted();
create or replace function public.pause_sharing(p_minutes integer) returns timestamptz
language plpgsql security definer set search_path = public as $$
declare v_until timestamptz;
begin
  if auth.uid() is null then raise exception 'not_authenticated'; end if;
  if p_minutes < 0 or p_minutes > 24 * 60 then raise exception 'invalid_duration'; end if;
  v_until := case when p_minutes = 0 then null else now() + make_interval(mins => p_minutes) end;
  insert into sharing_settings (user_id, paused_until) values (auth.uid(), v_until)
  on conflict (user_id) do update set paused_until = excluded.paused_until, updated_at = now();
  return v_until;
end $$;
create or replace function public.expense_shown(p_e public.expenses) returns boolean
language sql stable security definer set search_path = public as $$
  select (p_e.visibility = 'shared' or (p_e.visibility = 'surprise' and p_e.reveal_on <= current_date))
         and not exists (select 1 from sharing_settings s
                         where s.user_id = p_e.user_id and p_e.category = any (s.hidden_categories))
$$;
create or replace function public.partner_expenses(p_from timestamptz, p_to timestamptz)
returns table (
  id uuid, amount numeric, merchant text, category text, source text,
  occurred_at timestamptz, guessable boolean, my_guess numeric, my_points smallint
)
language sql stable security definer set search_path = public as $$
  select e.id,
         case when e.guessable and g.expense_id is null then null else e.amount end,
         e.merchant, e.category, e.source, e.occurred_at, e.guessable, g.guess_amount, g.points
  from expenses e
  left join expense_guesses g on g.expense_id = e.id and g.guesser_id = auth.uid()
  where e.user_id = partner_of(auth.uid())
    and shares(e.user_id, 'expenses')
    and e.occurred_at >= p_from and e.occurred_at < p_to
    and expense_shown(e)
  order by e.occurred_at desc
$$;
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
select '第 2 份完成 ✅ 接著貼第 3 份' as "小倆口";
commit;
