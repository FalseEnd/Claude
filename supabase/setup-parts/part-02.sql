-- 小倆口 Supabase 設定：第 2 份，共 8 份（請照順序執行，每份只要執行一次）
-- 這個檔案由 supabase/scripts/split-setup.js 從 supabase/migrations/ 產生，請勿手動修改。

begin;
create index activity_log_couple_time on public.activity_log (couple_id, created_at desc);

create table public.notification_prefs (
  user_id  uuid not null references auth.users(id) on delete cascade,
  kind     text not null check (kind in ('pairing', 'place', 'mood', 'comfort', 'battery_low', 'guess',
                                         'event', 'anniversary', 'cycle_care', 'sharing_changed')),
  enabled  boolean not null default true,
  primary key (user_id, kind)
);

create table public.push_tokens (
  token       text primary key,
  user_id     uuid not null default auth.uid() references auth.users(id) on delete cascade,
  platform    text not null check (platform in ('ios', 'android')),
  updated_at  timestamptz not null default now()
);

-- 由資料庫的觸發程序與排程寫入；App 經由 Realtime 收到後呼叫 app_ack_notifications 填 sent_at
create table public.notification_outbox (
  id            bigint generated always as identity primary key,
  recipient_id  uuid not null references auth.users(id) on delete cascade,
  kind          text not null,
  title         text not null,
  body          text not null,
  data          jsonb not null default '{}',
  created_at    timestamptz not null default now(),
  sent_at       timestamptz,
  error         text
);
create index notification_outbox_pending on public.notification_outbox (recipient_id, id) where sent_at is null;

-- 排程（每日提醒）執行紀錄，同一天只跑一次
create table public.job_runs (
  name    text not null,
  run_on  date not null,
  primary key (name, run_on)
);

-- 整個專案的設定（只有一筆）：人數上限與排程用的時區
create table public.app_config (
  id         boolean primary key default true check (id),
  max_users  integer not null default 2,
  timezone   text not null default 'Asia/Taipei'
);
insert into public.app_config default values;

-- =============================================================================
-- Helper functions（security definer，避免 RLS 互相遞迴）
-- =============================================================================

create or replace function public.couple_of(p_user uuid) returns uuid
language sql stable security definer set search_path = public as $$
  select couple_id from couple_members where user_id = p_user and left_at is null
$$;

create or replace function public.partner_of(p_user uuid) returns uuid
language sql stable security definer set search_path = public as $$
  select m2.user_id
  from couple_members m1
  join couple_members m2 on m2.couple_id = m1.couple_id and m2.user_id <> m1.user_id and m2.left_at is null
  where m1.user_id = p_user and m1.left_at is null
$$;

-- 以下四個給 RLS policy 使用：只回答「關於目前登入者」的問題
create or replace function public.my_couple_id() returns uuid
language sql stable security definer set search_path = public as $$ select couple_of(auth.uid()) $$;

create or replace function public.partner_id() returns uuid
language sql stable security definer set search_path = public as $$ select partner_of(auth.uid()) $$;

create or replace function public.is_partner(p_user uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select p_user is not null and p_user = partner_of(auth.uid())
$$;

-- 擁有者目前是否分享某類資料。暫停只影響被動收集的資料（位置、手機狀態、開銷）。
create or replace function public.shares(p_owner uuid, p_kind text) returns boolean
language sql stable security definer set search_path = public as $$
  select coalesce((
    select case p_kind
             when 'location' then s.share_location
             when 'device'   then s.share_device
             when 'expenses' then s.share_expenses
             when 'mood'     then s.share_mood
             when 'cycle'    then s.share_cycle
             else false
           end
           and (p_kind not in ('location', 'device', 'expenses') or s.paused_until is null or s.paused_until <= now())
    from sharing_settings s where s.user_id = p_owner
  ), false)
$$;

create or replace function public.partner_shares(p_owner uuid, p_kind text) returns boolean
language sql stable security definer set search_path = public as $$
  select is_partner(p_owner) and shares(p_owner, p_kind)
$$;

create or replace function public.i_share(p_kind text) returns boolean
language sql stable security definer set search_path = public as $$ select shares(auth.uid(), p_kind) $$;

create or replace function public.display_name_of(p_user uuid) returns text
language sql stable security definer set search_path = public as $$
  select coalesce((select display_name from profiles where id = p_user), '對方')
$$;

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
  alphabet constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';  -- 32 個字元
  -- gen_random_uuid() 是 PostgreSQL 13 起內建的強隨機數，不需要安裝 pgcrypto
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

-- 下一次的週年日期；2/29 在平年以 2/28 計
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

-- =============================================================================
-- RPC：配對
-- =============================================================================

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
      -- 碰撞機率極低，重新產生即可
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

-- 帳號被刪除（Supabase 後台 › Authentication › Users）時，配對紀錄會跟著被刪掉。
-- 留下來的那個人自動解除配對，才能重新產生配對碼、和新的帳號配對，不會卡在「只剩一個人」的配對裡。
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

-- 暫停分享（0 = 立即恢復）。對方會收到通知，避免「偷偷消失」造成誤會。
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

-- =============================================================================
-- RPC：對方的開銷（可猜的那筆在猜之前不回傳金額）與猜花費
-- =============================================================================

-- 這筆開銷對方看不看得到（另外還要擁有者有分享開銷：shares(user_id, 'expenses')）
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

select '第 2 份完成 ✅ 接著貼第 3 份' as "小倆口";

commit;
