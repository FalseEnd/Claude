-- =============================================================================
-- v2（2026-10）：個人資料、只有自己看得到的備註、日曆的生活紀錄、備忘錄、照片與語音（Storage）
--
-- 已經跑過舊版 setup.sql 的專案：到 SQL Editor 執行 supabase/upgrade-v2.sql 一次（內容就是這個檔案）。
-- 不小心多跑一次也沒關係：每一段都可以重複執行（if not exists、create or replace、先 drop 再建 policy）。
-- 隱私：
--   1. partner_notes 只有寫的人看得到：不掛 tg_changed、不寫通知、不寫歷史紀錄，
--      對方連「有沒有寫、什麼時候寫」都不會知道。
--   2. 私人備忘錄（shared = false）一樣不通知對方，照片和語音放在 u/<自己的 id>/，對方讀不到。
--   3. 照片和語音的路徑由資料庫檢查：只能指到這對情侶（c/<couple_id>/）或自己（u/<user_id>/）的資料夾。
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 個人資料：身高、體重、血型…（key 由 App 定義，資料庫只限制大小）。對方本來就讀得到 profiles。
-- -----------------------------------------------------------------------------

alter table public.profiles add column if not exists info jsonb not null default '{}'
  check (jsonb_typeof(info) = 'object' and octet_length(info::text) <= 4000);

create or replace function public.api_fields(p_kind text) returns text[]
language sql immutable parallel safe set search_path = public as $$
  select case p_kind
    when 'profile' then array['displayName:display_name:text', 'birthday:birthday:date', 'avatarUrl:avatar_url:text',
                              'info:info:jsonb']
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
    'user', coalesce(camel((select to_jsonb(p) from (select id, display_name, avatar_url, birthday, info
                                                     from profiles where id = v_uid) p)), '{}'::jsonb)
            || jsonb_build_object('email', (select u.email from auth.users u where u.id = v_uid)),
    'couple', camel((select to_jsonb(c) from (select id, together_since, created_at from couples where id = v_couple) c)),
    'partner', camel((select to_jsonb(p) from (select id, display_name, avatar_url, birthday, info
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
                    ''id'', id, ''display_name'', display_name, ''avatar_url'', avatar_url, ''birthday'', birthday,
                    ''info'', info)', v_set)
    into v_row using $1;
  return camel(v_row);
end $$;


-- -----------------------------------------------------------------------------
-- 只有自己看得到的備註（寫給自己看的「關於對方」）
-- -----------------------------------------------------------------------------

create table if not exists public.partner_notes (
  id          uuid primary key default gen_random_uuid(),
  owner_id    uuid not null default auth.uid() references auth.users(id) on delete cascade,
  about_user  uuid,   -- 寫的時候的對方（沒配對時寫的是 null）
  body        text not null check (char_length(body) between 1 and 2000),
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);
-- about_user 不設 foreign key：對方的帳號刪掉後（換人時要到後台刪帳號）還是記得是寫給誰的，
-- 不會變成 null 被當成「沒配對時寫的」，跑到下一位對方的頁面上。
-- 執行過舊版這個檔案的資料庫（有 on delete set null）也一起拿掉
alter table public.partner_notes drop constraint if exists partner_notes_about_user_fkey;
create index if not exists partner_notes_owner on public.partner_notes (owner_id, created_at desc);


-- -----------------------------------------------------------------------------
-- 日曆：記錄那天的生活（兩人共用，各自寫各自的）
-- -----------------------------------------------------------------------------

create table if not exists public.day_entries (
  id          uuid primary key default gen_random_uuid(),
  couple_id   uuid not null references public.couples(id) on delete cascade,
  user_id     uuid not null default auth.uid() references auth.users(id) on delete cascade,
  day         date not null check (day between '1900-01-01' and '2200-12-31'),
  body        text not null default '' check (char_length(body) <= 5000),
  media       jsonb not null default '[]' check (jsonb_typeof(media) = 'array' and jsonb_array_length(media) <= 12),
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  check (body <> '' or jsonb_array_length(media) > 0)
);
create index if not exists day_entries_couple_day on public.day_entries (couple_id, day);


-- -----------------------------------------------------------------------------
-- 備忘錄：共用的兩個人都能看、都能改；私人的只有自己
-- -----------------------------------------------------------------------------

create table if not exists public.memos (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null default auth.uid() references auth.users(id) on delete cascade,
  couple_id   uuid references public.couples(id) on delete set null,   -- 建立時的配對；沒配對是 null
  title       text not null default '' check (char_length(title) <= 60),
  body        text not null default '' check (char_length(body) <= 5000),
  media       jsonb not null default '[]' check (jsonb_typeof(media) = 'array' and jsonb_array_length(media) <= 12),
  shared      boolean not null default true,    -- true：兩個人都看得到、都能編輯；false：只有自己
  pinned      boolean not null default false,   -- 放在桌面小工具
  color       text not null default 'pink' check (color in ('pink', 'yellow', 'mint', 'sky', 'lavender', 'peach')),
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);
create index if not exists memos_user on public.memos (user_id);
create index if not exists memos_couple on public.memos (couple_id) where shared;


-- -----------------------------------------------------------------------------
-- 照片、語音（media 陣列）：每一項是 {path, kind, mime?, width?, height?, durationMs?}
-- path 只能是指定資料夾底下的一個檔名（不能有子資料夾或 ..），不然就能指到別人的檔案
-- -----------------------------------------------------------------------------

create or replace function public.media_valid(p_media jsonb, p_folder text) returns boolean
language sql immutable parallel safe set search_path = public as $$
  select case
    when p_media is null or jsonb_typeof(p_media) <> 'array' then false
    else not exists (
      select 1 from jsonb_array_elements(p_media) e
      where not coalesce(jsonb_typeof(e) = 'object'
                         and jsonb_typeof(e->'path') = 'string'
                         and starts_with(e->>'path', p_folder)
                         and substr(e->>'path', length(p_folder) + 1) ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$'
                         and e->>'kind' in ('photo', 'voice')
                         and octet_length(e::text) <= 1000, false))
  end
$$;

-- 通知用：沒有文字時說新增了什麼
create or replace function public.media_summary(p_media jsonb) returns text
language sql immutable parallel safe set search_path = public as $$
  select case
    when exists (select 1 from jsonb_array_elements(p_media) e where e->>'kind' = 'photo') then '📷 新增了照片'
    when exists (select 1 from jsonb_array_elements(p_media) e where e->>'kind' = 'voice') then '🎙️ 錄了一段語音'
    else '' end
$$;

-- 日曆紀錄、共用備忘錄放 c/<couple_id>/；私人備忘錄放 u/<user_id>/。
-- 用觸發程序檢查，直接打 API 寫資料表也一樣擋得住。
create or replace function public.tg_media_valid() returns trigger
language plpgsql security definer set search_path = public as $$
declare
  v_row    jsonb := to_jsonb(new);
  v_folder text := case when tg_table_name = 'memos' and not (v_row->>'shared')::boolean
                        then 'u/' || (v_row->>'user_id') || '/'
                        else 'c/' || (v_row->>'couple_id') || '/' end;
begin
  if not media_valid(new.media, v_folder) then raise exception 'invalid_media'; end if;
  return new;
end $$;
-- 只在 media、shared、couple_id 有寫到時檢查（重新配對後把備忘錄搬到新的配對也一樣要檢查）；
-- 解除配對 30 天後清除配對時，共用備忘錄的 couple_id 被設成 null，不檢查才不會被擋住
create or replace trigger day_entry_media before insert or update of media on public.day_entries
  for each row execute function public.tg_media_valid();
create or replace trigger memo_media before insert or update of media, shared, couple_id on public.memos
  for each row when (new.couple_id is not null or not new.shared) execute function public.tg_media_valid();


-- -----------------------------------------------------------------------------
-- 通知與即時更新
-- -----------------------------------------------------------------------------

create or replace function public.tg_day_entry_inserted() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  perform enqueue_notification(partner_of(new.user_id), 'diary',
    display_name_of(new.user_id) || ' 記錄了 ' || to_char(new.day, 'FMMM/FMDD') || ' 的生活',
    coalesce(nullif(left(btrim(new.body), 40), ''), nullif(media_summary(new.media), ''), '打開看看吧'),
    jsonb_build_object('day', new.day));
  return new;
end $$;
create or replace trigger day_entry_inserted after insert on public.day_entries
  for each row execute function public.tg_day_entry_inserted();

-- 兩人共用的資料，跟 20261015000002_realtime.sql 的其他表一樣
create or replace trigger realtime_day_entries after insert or update or delete on public.day_entries
  for each row execute function public.tg_changed();

-- 新增共用的備忘錄，或把備忘錄改成和現在的對方共用（原本只有自己、或是和以前的配對共用）時通知對方
create or replace function public.tg_memo_inserted() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if new.shared and new.couple_id is not null then
    perform enqueue_notification(partner_of(new.user_id), 'memo',
      display_name_of(new.user_id) || case when tg_op = 'INSERT' then ' 新增了共同備忘錄' else ' 分享了一則備忘錄' end,
      coalesce(nullif(new.title, ''), nullif(left(btrim(new.body), 40), ''), nullif(media_summary(new.media), ''), '打開看看吧'),
      jsonb_build_object('id', new.id));
  end if;
  return new;
end $$;
create or replace trigger memo_inserted after insert on public.memos
  for each row execute function public.tg_memo_inserted();
create or replace trigger memo_shared after update of shared, couple_id on public.memos
  for each row when (new.shared and new.couple_id is not null and not (old.shared and old.couple_id is not distinct from new.couple_id))
  execute function public.tg_memo_inserted();

-- 備忘錄不用 tg_changed：建立的人一定收到；對方只有在改之前或改之後是共用的才收到，
-- 私人備忘錄什麼時候寫、什麼時候改，對方都不會知道
create or replace function public.tg_changed_memo() returns trigger
language plpgsql security definer set search_path = public as $$
declare
  v_row        jsonb := to_jsonb(case when tg_op = 'DELETE' then old else new end);
  v_old        jsonb := case when tg_op = 'UPDATE' then to_jsonb(old) end;
  v_user       uuid := (v_row->>'user_id')::uuid;
  v_couple     uuid := case when (v_row->>'shared')::boolean then (v_row->>'couple_id')::uuid end;
  v_old_couple uuid := case when (v_old->>'shared')::boolean then (v_old->>'couple_id')::uuid end;
begin
  insert into realtime_events (user_id, kind, table_name, op, by_user)
  select t.uid, 'changed', tg_table_name, lower(tg_op), coalesce(auth.uid(), v_user)
  from (
    select v_user as uid
    union
    select m.user_id from couple_members m
    where m.left_at is null and m.couple_id in (v_couple, v_old_couple)
  ) t
  where exists (select 1 from auth.users u where u.id = t.uid);
  return null;
end $$;
create or replace trigger realtime_memos after insert or update or delete on public.memos
  for each row execute function public.tg_changed_memo();


-- -----------------------------------------------------------------------------
-- Row Level Security
-- -----------------------------------------------------------------------------

alter table public.partner_notes enable row level security;
alter table public.day_entries   enable row level security;
alter table public.memos         enable row level security;

drop policy if exists "partner_notes: owner only" on public.partner_notes;
create policy "partner_notes: owner only" on public.partner_notes for all
  using (owner_id = auth.uid()) with check (owner_id = auth.uid());

drop policy if exists "day_entries: couple read" on public.day_entries;
drop policy if exists "day_entries: own insert" on public.day_entries;
drop policy if exists "day_entries: own update" on public.day_entries;
drop policy if exists "day_entries: own delete" on public.day_entries;
create policy "day_entries: couple read" on public.day_entries for select using (couple_id = public.my_couple_id());
create policy "day_entries: own insert" on public.day_entries for insert with check (
  user_id = auth.uid() and couple_id = public.my_couple_id());
create policy "day_entries: own update" on public.day_entries for update
  using (user_id = auth.uid() and couple_id = public.my_couple_id())
  with check (user_id = auth.uid() and couple_id = public.my_couple_id());
create policy "day_entries: own delete" on public.day_entries for delete using (
  user_id = auth.uid() and couple_id = public.my_couple_id());

-- 共用的要掛在目前的配對；對方可以改共用的內容，但改完一定還是共用（不能改成私人、搬走）
drop policy if exists "memos: own or shared read" on public.memos;
drop policy if exists "memos: own insert" on public.memos;
drop policy if exists "memos: own or shared update" on public.memos;
drop policy if exists "memos: creator delete" on public.memos;
create policy "memos: own or shared read" on public.memos for select using (
  user_id = auth.uid() or (shared and couple_id is not null and couple_id = public.my_couple_id()));
create policy "memos: own insert" on public.memos for insert with check (
  user_id = auth.uid() and (not shared or (couple_id is not null and couple_id = public.my_couple_id())));
create policy "memos: own or shared update" on public.memos for update
  using (user_id = auth.uid() or (shared and couple_id is not null and couple_id = public.my_couple_id()))
  with check ((user_id = auth.uid() and not shared)
              or (shared and couple_id is not null and couple_id = public.my_couple_id()));
create policy "memos: creator delete" on public.memos for delete using (user_id = auth.uid());

-- 資料表權限明確給（同 20261015000003_api.sql）；建立者、配對、建立時間寫入後就不能改
revoke all on public.partner_notes, public.day_entries, public.memos from anon, authenticated;
grant select, insert, delete on public.partner_notes, public.day_entries, public.memos to authenticated;
grant update (body, updated_at) on public.partner_notes to authenticated;
grant update (day, body, media, updated_at) on public.day_entries to authenticated;
grant update (couple_id, title, body, media, shared, pinned, color, updated_at) on public.memos to authenticated;


-- -----------------------------------------------------------------------------
-- 照片、語音：Supabase Storage 的 media bucket（不公開，每個檔案都要登入才讀得到）
--   c/<couple_id>/<uuid>.<ext>  兩人共用（日曆、共用備忘錄）：這對情侶目前的成員可以讀、上傳
--   u/<user_id>/<uuid>.<ext>    只有自己（私人備忘錄）
-- 只有上傳的人能刪；不開放覆寫（檔名都是新的 uuid）
-- 已經有叫 media 的空間（例如自己建過公開的）也改成不公開，不然任何人都能用網址讀到照片
-- -----------------------------------------------------------------------------

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('media', 'media', false, 10485760,
        array['image/jpeg', 'image/png', 'image/webp', 'audio/mp4', 'audio/m4a', 'audio/x-m4a', 'audio/aac', 'audio/mpeg'])
on conflict (id) do update set public = false, file_size_limit = excluded.file_size_limit,
                               allowed_mime_types = excluded.allowed_mime_types;

drop policy if exists "xlk media: couple or own folder read" on storage.objects;
drop policy if exists "xlk media: couple or own folder upload" on storage.objects;
drop policy if exists "xlk media: uploader delete" on storage.objects;
create policy "xlk media: couple or own folder read" on storage.objects for select to authenticated using (
  bucket_id = 'media'
  and ((storage.foldername(name))[1] = 'c' and (storage.foldername(name))[2] = public.my_couple_id()::text
       or (storage.foldername(name))[1] = 'u' and (storage.foldername(name))[2] = auth.uid()::text));
-- 上傳的檔名和 media 的檢查一樣：資料夾底下直接一個檔名，不能有子資料夾或 ..
create policy "xlk media: couple or own folder upload" on storage.objects for insert to authenticated with check (
  bucket_id = 'media'
  and name ~ '^[cu]/[0-9a-f-]{36}/[A-Za-z0-9][A-Za-z0-9._-]{0,99}$'
  and ((storage.foldername(name))[1] = 'c' and (storage.foldername(name))[2] = public.my_couple_id()::text
       or (storage.foldername(name))[1] = 'u' and (storage.foldername(name))[2] = auth.uid()::text));
create policy "xlk media: uploader delete" on storage.objects for delete to authenticated using (
  bucket_id = 'media' and owner_id = auth.uid()::text);


-- -----------------------------------------------------------------------------
-- 通知種類：日曆紀錄（diary）、共同備忘錄（memo）
-- -----------------------------------------------------------------------------

alter table public.notification_prefs drop constraint if exists notification_prefs_kind_check,
  add constraint notification_prefs_kind_check check (kind in ('pairing', 'place', 'mood', 'comfort', 'battery_low', 'guess',
    'event', 'anniversary', 'cycle_care', 'sharing_changed', 'diary', 'memo'));

create or replace function public.notif_kinds() returns text[]
language sql immutable parallel safe set search_path = public as $$
  select array['pairing', 'place', 'mood', 'comfort', 'battery_low', 'guess', 'event', 'anniversary', 'cycle_care', 'sharing_changed',
               'diary', 'memo']
$$;

-- schema：App 用來判斷資料庫有沒有更新（沒有或小於 2 → 請使用者執行 upgrade-v2.sql）
create or replace function public.app_health(jsonb default '{}') returns jsonb
language sql stable set search_path = public set timezone to 'UTC' as $$
  select jsonb_build_object('ok', true, 'time', now(), 'schema', 2)
$$;


-- -----------------------------------------------------------------------------
-- RPC：私人備註
-- -----------------------------------------------------------------------------

-- 自己寫的、關於目前這位對方（或沒配對時寫的）備註，新的在前
create or replace function public.app_notes(jsonb default '{}') returns jsonb
language sql stable set search_path = public set timezone to 'UTC' as $$
  select camel_rows((select jsonb_agg(to_jsonb(n) - 'owner_id' order by n.created_at desc) from partner_notes n
                     where n.owner_id = auth.uid() and (n.about_user is null or n.about_user = partner_id())))
$$;

create or replace function public.app_save_note(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
declare v_row jsonb;
begin
  if nullif($1->>'id', '') is null then
    insert into partner_notes as t (about_user, body) values (partner_id(), $1->>'body') returning to_jsonb(t) into v_row;
  else
    update partner_notes t set body = $1->>'body', updated_at = now()
    where t.id = ($1->>'id')::uuid and t.owner_id = auth.uid() returning to_jsonb(t) into v_row;
    if v_row is null then raise exception 'not_found'; end if;
  end if;
  return camel(v_row - 'owner_id');
end $$;

create or replace function public.app_delete_note(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
begin
  delete from partner_notes where id = ($1->>'id')::uuid and owner_id = auth.uid();
  if not found then raise exception 'not_found'; end if;
  return jsonb_build_object('ok', true);
end $$;


-- -----------------------------------------------------------------------------
-- RPC：日曆紀錄
-- -----------------------------------------------------------------------------

-- from / to：YYYY-MM-DD（含當天）；沒給是今天往前 62 天到往後 31 天
create or replace function public.app_day_entries(jsonb default '{}') returns jsonb
language plpgsql stable set search_path = public set timezone to 'UTC' as $$
declare
  v_from date;
  v_to   date;
begin
  begin
    v_from := coalesce(nullif($1->>'from', '')::date, current_date - 62);
    v_to := coalesce(nullif($1->>'to', '')::date, current_date + 31);
  exception when data_exception then
    raise exception 'invalid_range';
  end;
  return camel_rows((select jsonb_agg(to_jsonb(d) - 'couple_id' order by d.day, d.created_at) from day_entries d
                     where d.couple_id = my_couple_id() and d.day between v_from and v_to));
end $$;

create or replace function public.app_save_day_entry(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
declare
  v_couple uuid := my_couple_id();
  v_media  jsonb := nullif($1->'media', 'null');
  v_row    jsonb;
begin
  if v_couple is null then raise exception 'not_paired'; end if;
  if nullif($1->>'id', '') is null then
    insert into day_entries as t (couple_id, day, body, media)
    values (v_couple, ($1->>'day')::date, coalesce($1->>'body', ''), coalesce(v_media, '[]'))
    returning to_jsonb(t) into v_row;
  else
    update day_entries t set day = coalesce(($1->>'day')::date, t.day), body = coalesce($1->>'body', t.body),
                             media = coalesce(v_media, t.media), updated_at = now()
    where t.id = ($1->>'id')::uuid and t.user_id = auth.uid() and t.couple_id = v_couple
    returning to_jsonb(t) into v_row;
    if v_row is null then raise exception 'not_found'; end if;
  end if;
  return camel(v_row - 'couple_id');
end $$;

create or replace function public.app_delete_day_entry(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
begin
  delete from day_entries where id = ($1->>'id')::uuid and user_id = auth.uid();
  if not found then raise exception 'not_found'; end if;
  return jsonb_build_object('ok', true);
end $$;


-- -----------------------------------------------------------------------------
-- RPC：備忘錄
-- -----------------------------------------------------------------------------

-- 自己的全部＋對方共用的（目前的配對）；放在桌面的在前，再依更新時間新的在前。
-- 和以前的配對共用的備忘錄現在只有自己看得到，shared 回傳 false
create or replace function public.app_memos(jsonb default '{}') returns jsonb
language sql stable set search_path = public set timezone to 'UTC' as $$
  select camel_rows((select jsonb_agg((to_jsonb(m) - 'couple_id')
                                      || jsonb_build_object('shared', coalesce(m.shared and m.couple_id = my_couple_id(), false))
                                      order by m.pinned desc, m.updated_at desc) from memos m
                     where m.user_id = auth.uid() or (m.shared and m.couple_id = my_couple_id())))
$$;

-- 新增：掛在目前的配對，沒配對只能私人。修改：自己的全部都能改（改成共用時掛到目前的配對）；
-- 對方共用的只能改標題、內容、照片語音、桌面、顏色。
-- 和以前的配對共用的備忘錄當成私人的：不會因為改了內容就分享給現在的對方；
-- 留在以前那個資料夾的照片語音已經沒有人讀得到，存檔時拿掉
create or replace function public.app_save_memo(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
declare
  v_couple uuid := my_couple_id();
  v_media  jsonb := nullif($1->'media', 'null');
  v_memo   memos;
  v_was    boolean;
  v_shared boolean;
  v_row    jsonb;
begin
  if nullif($1->>'id', '') is null then
    insert into memos as t (couple_id, title, body, media, shared, pinned, color)
    values (v_couple, coalesce($1->>'title', ''), coalesce($1->>'body', ''), coalesce(v_media, '[]'),
            coalesce(($1->>'shared')::boolean, true) and v_couple is not null,
            coalesce(($1->>'pinned')::boolean, false), coalesce(nullif($1->>'color', ''), 'pink'))
    returning to_jsonb(t) into v_row;
    return camel(v_row - 'couple_id');
  end if;

  select * into v_memo from memos where id = ($1->>'id')::uuid;
  if not found then raise exception 'not_found'; end if;
  if v_memo.user_id = auth.uid() then
    v_was := coalesce(v_memo.shared and v_memo.couple_id = v_couple, false);
    v_shared := coalesce(($1->>'shared')::boolean, v_was) and v_couple is not null;
    v_media := coalesce(v_media, v_memo.media);
    if v_memo.shared and not v_was then
      v_media := coalesce((select jsonb_agg(e.value order by e.i) from jsonb_array_elements(v_media) with ordinality as e(value, i)
                           where not coalesce(e.value->>'path' like 'c/%'
                                              and not starts_with(e.value->>'path', 'c/' || coalesce(v_couple::text, '') || '/'), false)),
                          '[]');
    end if;
    update memos t set title = coalesce($1->>'title', t.title), body = coalesce($1->>'body', t.body),
                       media = v_media, shared = v_shared,
                       couple_id = case when v_shared then v_couple else t.couple_id end,
                       pinned = coalesce(($1->>'pinned')::boolean, t.pinned),
                       color = coalesce(nullif($1->>'color', ''), t.color), updated_at = now()
    where t.id = v_memo.id
    returning to_jsonb(t) into v_row;
  else
    update memos t set title = coalesce($1->>'title', t.title), body = coalesce($1->>'body', t.body),
                       media = coalesce(v_media, t.media), pinned = coalesce(($1->>'pinned')::boolean, t.pinned),
                       color = coalesce(nullif($1->>'color', ''), t.color), updated_at = now()
    where t.id = v_memo.id
    returning to_jsonb(t) into v_row;
  end if;
  if v_row is null then raise exception 'not_found'; end if;
  return camel(v_row - 'couple_id');
end $$;

create or replace function public.app_delete_memo(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
begin
  delete from memos where id = ($1->>'id')::uuid and user_id = auth.uid();
  if not found then raise exception 'not_found' using detail = '只有建立備忘錄的人可以刪除'; end if;
  return jsonb_build_object('ok', true);
end $$;


-- -----------------------------------------------------------------------------
-- 權限：新的 RPC 只給登入後的人；內部函式（檢查、觸發程序）用戶端不能呼叫
-- -----------------------------------------------------------------------------

revoke all on function public.media_valid(jsonb, text), public.media_summary(jsonb), public.tg_media_valid(),
  public.tg_day_entry_inserted(), public.tg_memo_inserted(), public.tg_changed_memo()
  from public, anon, authenticated;
revoke all on function public.app_notes(jsonb), public.app_save_note(jsonb), public.app_delete_note(jsonb),
  public.app_day_entries(jsonb), public.app_save_day_entry(jsonb), public.app_delete_day_entry(jsonb),
  public.app_memos(jsonb), public.app_save_memo(jsonb), public.app_delete_memo(jsonb)
  from public, anon;
grant execute on function public.app_notes(jsonb), public.app_save_note(jsonb), public.app_delete_note(jsonb),
  public.app_day_entries(jsonb), public.app_save_day_entry(jsonb), public.app_delete_day_entry(jsonb),
  public.app_memos(jsonb), public.app_save_memo(jsonb), public.app_delete_memo(jsonb)
  to authenticated;

-- 請 Data API（PostgREST）重新讀取函式清單
notify pgrst, 'reload schema';
