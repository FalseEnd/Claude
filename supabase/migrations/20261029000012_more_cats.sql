-- =============================================================================
-- 小倆口 2026-10：更多貓咪
-- 品種從 6 種變成 15 種（虎斑、橘白、乳牛、玳瑁、美國短毛、英國短毛、摺耳、暹羅、布偶），
-- 已經養了的也可以換品種（app_update_pet，照顧紀錄寫「誰把牠換成什麼」）。
-- 包在 upgrade-v2.sql 裡：已經跑過 upgrade-v2.sql 的人再跑一次就好，可以重複執行。
-- =============================================================================

-- 品種的清單，和 App 的 core/Pet.kt 一樣（Android 的 PetTest 會比對）
create or replace function public.pet_coats() returns text[]
language sql immutable parallel safe as $$
  select array['orange', 'gray', 'white', 'black', 'calico', 'tuxedo',
               'tabby', 'gingerwhite', 'cow', 'tortie', 'silver', 'british', 'fold', 'siamese', 'ragdoll']
$$;

-- not valid：已經存的資料不再檢查一次（upgrade-v2.sql 每次都從頭跑，前面的步驟換成比較短的清單時才不會失敗）
alter table public.pets drop constraint if exists pets_coat_check,
  add constraint pets_coat_check check (coat = any (public.pet_coats())) not valid;
alter table public.pet_events drop constraint if exists pet_events_action_check,
  add constraint pet_events_action_check check (action in ('adopt', 'feed', 'play', 'pet', 'bath', 'brush', 'bonus', 'streak', 'grow',
    'rename', 'coat')) not valid;

-- 領養：{name, coat}。一對情侶一隻，已經有了就是 pet_exists
create or replace function public.app_adopt_pet(jsonb default '{}') returns jsonb
language plpgsql security definer set search_path = public set timezone to 'UTC' as $$
declare
  v_me     uuid := auth.uid();
  v_couple uuid := couple_of(auth.uid());
  v_name   text := btrim(coalesce($1->>'name', ''));
  v_coat   text := coalesce(nullif($1->>'coat', ''), 'orange');
begin
  if v_me is null then raise exception 'not_authenticated'; end if;
  if v_couple is null then raise exception 'not_paired'; end if;
  if v_name = '' then v_name := '咪咪'; end if;
  if char_length(v_name) > 12 or not (v_coat = any (pet_coats())) then
    raise exception 'invalid_input';
  end if;
  insert into pets (couple_id, name, coat, adopted_by) values (v_couple, v_name, v_coat, v_me)
  on conflict (couple_id) do nothing;
  if not found then raise exception 'pet_exists' using detail = '你們已經有一隻貓咪了'; end if;
  insert into pet_events (couple_id, user_id, action, item) values (v_couple, v_me, 'adopt', v_coat);
  perform enqueue_notification(partner_of(v_me), 'pet', display_name_of(v_me) || ' 領養了一隻小貓 🐱',
    '牠叫「' || v_name || '」，快來跟牠打招呼', jsonb_build_object('pet', true));
  return pet_payload(v_couple);
end $$;

-- 改名字、換品種：{name?, coat?}，沒給的不改；有改到的才寫紀錄（rename、coat）
create or replace function public.app_update_pet(jsonb default '{}') returns jsonb
language plpgsql security definer set search_path = public set timezone to 'UTC' as $$
declare
  v_me     uuid := auth.uid();
  v_couple uuid := couple_of(auth.uid());
  v_name   text := btrim($1->>'name');
  v_coat   text := nullif($1->>'coat', '');
  p        pets;
begin
  if v_me is null then raise exception 'not_authenticated'; end if;
  if v_couple is null then raise exception 'not_paired'; end if;
  if (v_name is not null and (v_name = '' or char_length(v_name) > 12)) or (v_coat is not null and not (v_coat = any (pet_coats()))) then
    raise exception 'invalid_input';
  end if;
  select * into p from pets where couple_id = v_couple for update;
  if not found then raise exception 'not_found' using detail = '還沒有領養貓咪'; end if;
  if v_name is not null and v_name <> p.name then
    update pets set name = v_name where couple_id = v_couple;
    insert into pet_events (couple_id, user_id, action, item) values (v_couple, v_me, 'rename', left(v_name, 20));
  end if;
  if v_coat is not null and v_coat <> p.coat then
    update pets set coat = v_coat where couple_id = v_couple;
    insert into pet_events (couple_id, user_id, action, item) values (v_couple, v_me, 'coat', v_coat);
  end if;
  return pet_payload(v_couple);
end $$;

revoke all on function public.pet_coats() from public, anon, authenticated;
revoke all on function public.app_adopt_pet(jsonb), public.app_update_pet(jsonb) from public, anon;
grant execute on function public.app_adopt_pet(jsonb), public.app_update_pet(jsonb) to authenticated;

-- App 用 schema 判斷資料庫有沒有更新：…8 手機溫度；9 再加更多貓咪
create or replace function public.app_health(jsonb default '{}') returns jsonb
language sql stable set search_path = public set timezone to 'UTC' as $$
  select jsonb_build_object('ok', true, 'time', now(), 'schema', 9)
$$;

notify pgrst, 'reload schema';
