begin;
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
create table if not exists public.partner_notes (
  id          uuid primary key default gen_random_uuid(),
  owner_id    uuid not null default auth.uid() references auth.users(id) on delete cascade,
  about_user  uuid,
  body        text not null check (char_length(body) between 1 and 2000),
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);
alter table public.partner_notes drop constraint if exists partner_notes_about_user_fkey;
create index if not exists partner_notes_owner on public.partner_notes (owner_id, created_at desc);
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
create table if not exists public.memos (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null default auth.uid() references auth.users(id) on delete cascade,
  couple_id   uuid references public.couples(id) on delete set null,
  title       text not null default '' check (char_length(title) <= 60),
  body        text not null default '' check (char_length(body) <= 5000),
  media       jsonb not null default '[]' check (jsonb_typeof(media) = 'array' and jsonb_array_length(media) <= 12),
  shared      boolean not null default true,
  pinned      boolean not null default false,
  color       text not null default 'pink' check (color in ('pink', 'yellow', 'mint', 'sky', 'lavender', 'peach')),
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);
create index if not exists memos_user on public.memos (user_id);
create index if not exists memos_couple on public.memos (couple_id) where shared;
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
create or replace function public.media_summary(p_media jsonb) returns text
language sql immutable parallel safe set search_path = public as $$
  select case
    when exists (select 1 from jsonb_array_elements(p_media) e where e->>'kind' = 'photo') then '📷 新增了照片'
    when exists (select 1 from jsonb_array_elements(p_media) e where e->>'kind' = 'voice') then '🎙️ 錄了一段語音'
    else '' end
$$;
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
create or replace trigger day_entry_media before insert or update of media on public.day_entries
  for each row execute function public.tg_media_valid();
create or replace trigger memo_media before insert or update of media, shared, couple_id on public.memos
  for each row when (new.couple_id is not null or not new.shared) execute function public.tg_media_valid();
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
create or replace trigger realtime_day_entries after insert or update or delete on public.day_entries
  for each row execute function public.tg_changed();
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
revoke all on public.partner_notes, public.day_entries, public.memos from anon, authenticated;
grant select, insert, delete on public.partner_notes, public.day_entries, public.memos to authenticated;
grant update (body, updated_at) on public.partner_notes to authenticated;
grant update (day, body, media, updated_at) on public.day_entries to authenticated;
grant update (couple_id, title, body, media, shared, pinned, color, updated_at) on public.memos to authenticated;
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
create policy "xlk media: couple or own folder upload" on storage.objects for insert to authenticated with check (
  bucket_id = 'media'
  and name ~ '^[cu]/[0-9a-f-]{36}/[A-Za-z0-9][A-Za-z0-9._-]{0,99}$'
  and ((storage.foldername(name))[1] = 'c' and (storage.foldername(name))[2] = public.my_couple_id()::text
       or (storage.foldername(name))[1] = 'u' and (storage.foldername(name))[2] = auth.uid()::text));
create policy "xlk media: uploader delete" on storage.objects for delete to authenticated using (
  bucket_id = 'media' and owner_id = auth.uid()::text);
alter table public.notification_prefs drop constraint if exists notification_prefs_kind_check,
  add constraint notification_prefs_kind_check check (kind in ('pairing', 'place', 'mood', 'comfort', 'battery_low', 'guess',
    'event', 'anniversary', 'cycle_care', 'sharing_changed', 'diary', 'memo'));
create or replace function public.notif_kinds() returns text[]
language sql immutable parallel safe set search_path = public as $$
  select array['pairing', 'place', 'mood', 'comfort', 'battery_low', 'guess', 'event', 'anniversary', 'cycle_care', 'sharing_changed',
               'diary', 'memo']
$$;
create or replace function public.app_health(jsonb default '{}') returns jsonb
language sql stable set search_path = public set timezone to 'UTC' as $$
  select jsonb_build_object('ok', true, 'time', now(), 'schema', 2)
$$;
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
create or replace function public.app_memos(jsonb default '{}') returns jsonb
language sql stable set search_path = public set timezone to 'UTC' as $$
  select camel_rows((select jsonb_agg((to_jsonb(m) - 'couple_id')
                                      || jsonb_build_object('shared', coalesce(m.shared and m.couple_id = my_couple_id(), false))
                                      order by m.pinned desc, m.updated_at desc) from memos m
                     where m.user_id = auth.uid() or (m.shared and m.couple_id = my_couple_id())))
$$;
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
notify pgrst, 'reload schema';alter table public.device_status add column if not exists device_name text check (char_length(device_name) <= 60);
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
create or replace function public.app_health(jsonb default '{}') returns jsonb
language sql stable set search_path = public set timezone to 'UTC' as $$
  select jsonb_build_object('ok', true, 'time', now(), 'schema', 3)
$$;do $$
begin
  if not exists (select 1 from information_schema.columns
                 where table_schema = 'public' and table_name = 'comforts' and column_name = 'sticker') then
    update public.comforts set read_at = now() where read_at is null and created_at < now() - interval '1 day';
  end if;
end $$;
alter table public.moods add column if not exists sticker text check (sticker ~ '^[A-Za-z0-9]{1,64}$');
alter table public.comforts add column if not exists sticker text check (sticker ~ '^[A-Za-z0-9]{1,64}$');
alter table public.couples add column if not exists giphy_key text check (giphy_key ~ '^[A-Za-z0-9_-]{16,128}$');
grant insert (sticker) on public.comforts to authenticated;
grant update (giphy_key) on public.couples to authenticated;
create or replace function public.app_post_mood(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
declare v_row jsonb;
begin
  insert into moods as t (mood, note, sticker) values ($1->>'mood', nullif($1->>'note', ''), nullif(btrim($1->>'sticker'), ''))
  returning to_jsonb(t) into v_row;
  return camel(v_row);
end $$;
create or replace function public.app_send_comfort(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
declare v_row jsonb;
begin
  insert into comforts as t (couple_id, receiver_id, kind, message, sticker)
  values (my_couple_id(), partner_id(), $1->>'kind', nullif($1->>'message', ''), nullif(btrim($1->>'sticker'), ''))
  returning to_jsonb(t) into v_row;
  return camel(v_row);
end $$;
create or replace function public.app_read_comfort(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
begin
  if truthy($1->'all') then
    update comforts set read_at = now() where receiver_id = auth.uid() and read_at is null;
  else
    update comforts set read_at = now() where id = ($1->>'id')::bigint and read_at is null;
  end if;
  return jsonb_build_object('ok', true);
end $$;
create or replace function public.tg_mood_inserted() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if not shares(new.user_id, 'mood') then return new; end if;
  perform log_activity(couple_of(new.user_id), new.user_id, 'mood',
    jsonb_build_object('mood', new.mood, 'note', new.note, 'sticker', new.sticker));
  perform enqueue_notification(partner_of(new.user_id), 'mood',
    display_name_of(new.user_id) || ' 現在覺得' || mood_label(new.mood),
    case when new.mood in ('tired', 'anxious', 'sad', 'angry', 'sick')
         then coalesce(new.note || '。', '') || '要不要安撫一下？'
         else coalesce(nullif(new.note, ''), case when new.sticker is not null then '附上一張貼圖 ✨' end, '') end,
    jsonb_build_object('mood', new.mood, 'sticker', new.sticker));
  return new;
end $$;
create or replace function public.tg_comfort_inserted() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  perform log_activity(new.couple_id, new.sender_id, 'comfort',
    jsonb_build_object('kind', new.kind, 'message', new.message, 'sticker', new.sticker));
  perform enqueue_notification(new.receiver_id, 'comfort',
    display_name_of(new.sender_id) || ' ' || comfort_text(new.kind),
    coalesce(new.message, case when new.sticker is not null then '傳了一張貼圖給你，點開看看 👀' else '點開回應一下吧' end),
    jsonb_build_object('comfort_id', new.id, 'sticker', new.sticker));
  return new;
end $$;
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
    'couple', camel((select to_jsonb(c) from (select id, together_since, created_at, giphy_key from couples where id = v_couple) c)),
    'partner', camel((select to_jsonb(p) from (select id, display_name, avatar_url, birthday, info
                                               from profiles where id = v_partner) p)),
    'mySharing', camel((select to_jsonb(s) from sharing_settings s where s.user_id = v_uid)),
    'partnerSharing', camel((select to_jsonb(s) from sharing_settings s where s.user_id = v_partner)),
    'partnerOnline', coalesce((select d.updated_at > now() - interval '10 minutes'
                               from device_status d where d.user_id = v_partner), false));
end $$;
create or replace function public.app_set_giphy_key(jsonb default '{}') returns jsonb
language plpgsql set search_path = public set timezone to 'UTC' as $$
declare v_row jsonb;
begin
  update couples set giphy_key = nullif(btrim($1->>'key'), '') where id = my_couple_id()
  returning jsonb_build_object('id', id, 'together_since', together_since, 'created_at', created_at, 'giphy_key', giphy_key)
  into v_row;
  if v_row is null then raise exception 'not_paired'; end if;
  return camel(v_row);
end $$;
create or replace function public.app_health(jsonb default '{}') returns jsonb
language sql stable set search_path = public set timezone to 'UTC' as $$
  select jsonb_build_object('ok', true, 'time', now(), 'schema', 4)
$$;
revoke all on function public.app_set_giphy_key(jsonb) from public, anon;
grant execute on function public.app_set_giphy_key(jsonb) to authenticated;
notify pgrst, 'reload schema';alter table public.moods drop constraint if exists moods_mood_check, add constraint moods_mood_check check (mood in (
  'happy', 'sweet', 'excited', 'laugh', 'calm', 'relaxed',
  'missyou', 'shy', 'sleepy', 'hungry', 'bored', 'busy',
  'tired', 'anxious', 'stressed', 'wronged', 'sad', 'angry', 'jealous', 'lonely', 'sick'));
alter table public.comforts drop constraint if exists comforts_kind_check, add constraint comforts_kind_check check (kind in (
  'hug', 'pat', 'here', 'cheer', 'support', 'treat',
  'kiss', 'love', 'missyou', 'flower', 'morning', 'night',
  'water', 'meal', 'rest', 'custom'));
create or replace function public.mood_label(p_mood text) returns text
language sql immutable as $$
  select case p_mood
    when 'happy' then '開心' when 'sweet' then '甜蜜' when 'excited' then '興奮' when 'laugh' then '好好笑'
    when 'calm' then '平靜' when 'relaxed' then '放鬆'
    when 'missyou' then '想你' when 'shy' then '害羞' when 'sleepy' then '想睡' when 'hungry' then '肚子餓'
    when 'bored' then '無聊' when 'busy' then '忙翻了'
    when 'tired' then '好累' when 'anxious' then '焦慮' when 'stressed' then '壓力大' when 'wronged' then '委屈'
    when 'sad' then '難過' when 'angry' then '生氣' when 'jealous' then '吃醋' when 'lonely' then '寂寞'
    when 'sick' then '不舒服' else p_mood end
$$;
create or replace function public.comfort_text(p_kind text) returns text
language sql immutable as $$
  select case p_kind
    when 'hug' then '給了你一個大大的抱抱' when 'pat' then '摸摸你的頭，說辛苦了'
    when 'here' then '說：我在，想聊隨時找我' when 'cheer' then '幫你加油打氣'
    when 'support' then '說永遠站在你這邊' when 'treat' then '說等等請你喝一杯'
    when 'kiss' then '親了你一下' when 'love' then '說愛你'
    when 'missyou' then '說好想你' when 'flower' then '送你一束花'
    when 'morning' then '跟你說早安' when 'night' then '跟你說晚安，好夢'
    when 'water' then '提醒你記得多喝水' when 'meal' then '問你吃飯了沒'
    when 'rest' then '叫你早點休息'
    else '傳了悄悄話給你' end
$$;
create or replace function public.tg_mood_inserted() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if not shares(new.user_id, 'mood') then return new; end if;
  perform log_activity(couple_of(new.user_id), new.user_id, 'mood',
    jsonb_build_object('mood', new.mood, 'note', new.note, 'sticker', new.sticker));
  perform enqueue_notification(partner_of(new.user_id), 'mood',
    display_name_of(new.user_id) || ' 現在覺得' || mood_label(new.mood),
    case when new.mood in ('tired', 'anxious', 'stressed', 'wronged', 'sad', 'angry', 'jealous', 'lonely', 'sick')
         then coalesce(new.note || '。', '') || '要不要安撫一下？'
         else coalesce(nullif(new.note, ''), case when new.sticker is not null then '附上一張貼圖 ✨' end, '') end,
    jsonb_build_object('mood', new.mood, 'sticker', new.sticker));
  return new;
end $$;
create or replace function public.app_health(jsonb default '{}') returns jsonb
language sql stable set search_path = public set timezone to 'UTC' as $$
  select jsonb_build_object('ok', true, 'time', now(), 'schema', 5)
$$;
notify pgrst, 'reload schema';alter table public.comforts drop constraint if exists comforts_kind_check, add constraint comforts_kind_check check (kind in (
  'hug', 'pat', 'here', 'cheer', 'support', 'treat', 'listen', 'holdhand', 'proud',
  'kiss', 'love', 'missyou', 'flower', 'morning', 'night', 'heart', 'cuddle', 'date',
  'water', 'meal', 'rest', 'warm', 'gethome', 'safe',
  'joke', 'silly', 'kitty', 'dessert', 'gift', 'custom'));
create or replace function public.comfort_text(p_kind text) returns text
language sql immutable as $$
  select case p_kind
    when 'hug' then '給了你一個大大的抱抱' when 'pat' then '摸摸你的頭，說辛苦了'
    when 'here' then '說：我在，想聊隨時找我' when 'cheer' then '幫你加油打氣'
    when 'support' then '說永遠站在你這邊' when 'treat' then '說等等請你喝一杯'
    when 'listen' then '說想聽你說說今天' when 'holdhand' then '牽起你的手'
    when 'proud' then '說你是最棒的'
    when 'kiss' then '親了你一下' when 'love' then '說愛你'
    when 'missyou' then '說好想你' when 'flower' then '送你一束花'
    when 'morning' then '跟你說早安' when 'night' then '跟你說晚安，好夢'
    when 'heart' then '對你比了一個愛心' when 'cuddle' then '蹭蹭你撒嬌'
    when 'date' then '說想跟你約會'
    when 'water' then '提醒你記得多喝水' when 'meal' then '問你吃飯了沒'
    when 'rest' then '叫你早點休息' when 'warm' then '提醒你穿暖一點'
    when 'gethome' then '問你到家了沒' when 'safe' then '叫你路上小心'
    when 'joke' then '講了一個冷笑話給你' when 'silly' then '對你扮了一個鬼臉'
    when 'kitty' then '送你一隻可愛的小貓' when 'dessert' then '說要請你吃甜點'
    when 'gift' then '說準備了小禮物給你'
    else '傳了悄悄話給你' end
$$;
create or replace function public.app_health(jsonb default '{}') returns jsonb
language sql stable set search_path = public set timezone to 'UTC' as $$
  select jsonb_build_object('ok', true, 'time', now(), 'schema', 6)
$$;
notify pgrst, 'reload schema';
select '更新完成 🎉' as "小倆口";
commit;
