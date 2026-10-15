-- 小倆口 Supabase 設定：第 7 份，共 8 份（請照順序執行，每份只要執行一次）
-- 這個檔案由 supabase/scripts/split-setup.js 從 supabase/migrations/ 產生，請勿手動修改。

begin;
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
to authenticated;-- =============================================================================
-- 排程（pg_cron，在資料庫裡執行，不需要另外的伺服器）
--   xlk-minutely  每分鐘    約會提醒
--   xlk-daily     每 10 分鐘 當地時間 09:00 之後執行一次：紀念日、交往天數、經期關懷提醒
--   xlk-cleanup   每小時    清掉過期的即時事件與舊通知
-- 排程函式以資料表擁有者（postgres）身分執行，不受 Row Level Security 限制；App 不能呼叫。
-- =============================================================================

create extension if not exists pg_cron;

select '第 7 份完成 ✅ 接著貼第 8 份' as "小倆口";

commit;
