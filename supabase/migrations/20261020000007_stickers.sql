-- =============================================================================
-- 小倆口 2026-10：GIPHY 貼圖
-- 記心情、送安撫時可以附一張 GIPHY 貼圖（或 GIF），只存 GIPHY 的 id。心情和安撫的種類還是必填，
-- 通知、桌面小工具、狀態列和舊版 App（0.2.1）都照常運作。
-- GIPHY 的 API key 一對情侶一把（couples.giphy_key），兩個人都讀得到（app_me 的 couple.giphyKey），
-- App 用 app_set_giphy_key 設定；key 不寫進歷史紀錄、通知或即時事件。
-- 收到的安撫可以一次全部標成看過（app_read_comfort 的 all）。
-- 包在 upgrade-v2.sql 裡：已經跑過 upgrade-v2.sql 的人再跑一次就好，可以重複執行。
-- =============================================================================

-- 0.2.1 以前的 App 從來沒有把收到的安撫標成看過（read_at 一直是空的）：第一次更新到這一版時，
-- 一天以前收到的都標成看過，首頁「收到的安撫」和鈴鐺的紅點才不會把以前的安撫一則一則翻出來。
-- 只做這一次：comforts 還沒有 sticker 欄位就是第一次（所以放在下面加欄位之前），
-- 之後再執行 upgrade-v2.sql 時，新收到、還沒看過的安撫不會被標掉
do $$
begin
  if not exists (select 1 from information_schema.columns
                 where table_schema = 'public' and table_name = 'comforts' and column_name = 'sticker') then
    update public.comforts set read_at = now() where read_at is null and created_at < now() - interval '1 day';
  end if;
end $$;

-- id 只能是英文字母和數字：App 用 id 組出 GIPHY 的網址，對方傳來奇怪的 id 也不會讓手機去載別的東西
alter table public.moods add column if not exists sticker text check (sticker ~ '^[A-Za-z0-9]{1,64}$');
alter table public.comforts add column if not exists sticker text check (sticker ~ '^[A-Za-z0-9]{1,64}$');
alter table public.couples add column if not exists giphy_key text check (giphy_key ~ '^[A-Za-z0-9_-]{16,128}$');

-- 這兩張表只開放指定的欄位（20261015000003_api.sql）；收到安撫的人一樣只能改 read_at
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

-- {"id": n} 標一則看過了；{"all": true} 把收到的、還沒看過的全部標成看過（打開歷史紀錄時，一次就好）。
-- 用登入者的身分執行：Row Level Security 只讓收到的人改 read_at，別人的安撫動不到
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


-- -----------------------------------------------------------------------------
-- 通知與歷史紀錄帶著貼圖 id（App 用它在通知和歷史紀錄裡顯示貼圖）
-- -----------------------------------------------------------------------------

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


-- -----------------------------------------------------------------------------
-- GIPHY 的 API key：一對情侶一把，誰設定都可以，兩個人都讀得到
-- -----------------------------------------------------------------------------

-- couple 多了 giphyKey；最外層的 key 不變（舊版 App 照常讀）
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

-- 空白或沒給 key 就清掉。不用 app_update_couple：它會一起改掉 together_since。
-- 格式由 couples 的 check 檢查，直接打 API 改這個欄位也一樣擋得住
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

-- App 用 schema 判斷資料庫有沒有更新：2 有個人資料、日曆紀錄、備忘錄；3 再加手機名稱與電信；4 再加 GIPHY 貼圖
create or replace function public.app_health(jsonb default '{}') returns jsonb
language sql stable set search_path = public set timezone to 'UTC' as $$
  select jsonb_build_object('ok', true, 'time', now(), 'schema', 4)
$$;

-- 新的 RPC 只給登入後的人
revoke all on function public.app_set_giphy_key(jsonb) from public, anon;
grant execute on function public.app_set_giphy_key(jsonb) to authenticated;

-- 請 Data API（PostgREST）重新讀取函式清單
notify pgrst, 'reload schema';
