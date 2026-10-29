-- 用過最新功能的資料庫再執行一次 upgrade-v2.sql：
-- upgrade-v2.sql 會從頭再跑一次以前的步驟（例如 0.2.3 把安撫的種類換成 15 種、0.2 把通知的種類換成 12 種），
-- 資料表裡已經有後來才多的種類（0.2.6 的安撫、0.3.0 小貓的通知設定）時，那些步驟不能失敗。
-- test.sh 依序執行（以 postgres 身分）：
--   套用全部 migrations 的資料庫：seed → upgrade-v2.sql → check
--   0.2.6 的資料庫（已經送過 0.2.6 才有的安撫）：seed026 → upgrade-v2.sql → check026
--   psql "$DB_URL" -v step=seed -f supabase/tests/newest_rows.sql
\set ON_ERROR_STOP on
\set QUIET on

select :'step' in ('seed', 'seed026') as seed, :'step' in ('seed', 'check') as latest \gset

create function pg_temp.ok(cond boolean, msg text) returns void language plpgsql as $$
begin
  if cond is distinct from true then raise exception 'FAILED: %', msg; end if;
  raise notice 'ok - %', msg;
end $$;

-- 跟 PostgREST 一樣，用 JWT claims 告訴資料庫目前是誰（阿哲 c1、小雨 c2）
create function pg_temp.login(p_user text) returns void language sql as $$
  select set_config('request.jwt.claims', json_build_object('sub', '00000000-0000-0000-0000-0000000000' || p_user, 'role', 'authenticated')::text, false);
$$;

-- 預期會失敗的語句：回傳 SQLSTATE，沒失敗則回傳 null
create function pg_temp.error_of(p_sql text) returns text language plpgsql as $$
begin
  execute p_sql;
  return null;
exception when others then
  return sqlstate;
end $$;

\if :seed
insert into auth.users (instance_id, id, aud, role, email, raw_user_meta_data) values
  ('00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-0000000000c1', 'authenticated', 'authenticated',
   'newest-zhe@example.com', '{"display_name": "阿哲"}'),
  ('00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-0000000000c2', 'authenticated', 'authenticated',
   'newest-yu@example.com', '{"display_name": "小雨"}');
select pg_temp.login('c1');
select public.create_invite() as code \gset
select pg_temp.login('c2');
select public.accept_invite(:'code');
-- 每一種安撫、每一種心情都存一筆（0.2.6 的「送小貓」「到家沒」…、0.2.3 的「寂寞」…）
-- 時間放在昨天：不會碰到防洗版（10 分鐘內最多 20 次）
insert into public.comforts (couple_id, receiver_id, kind, message, created_at)
select public.my_couple_id(), '00000000-0000-0000-0000-0000000000c1', k, 'newest ' || k, now() - interval '1 day'
from unnest(array['hug', 'pat', 'here', 'cheer', 'support', 'treat', 'listen', 'holdhand', 'proud',
                  'kiss', 'love', 'missyou', 'flower', 'morning', 'night', 'heart', 'cuddle', 'date',
                  'water', 'meal', 'rest', 'warm', 'gethome', 'safe',
                  'joke', 'silly', 'kitty', 'dessert', 'gift', 'custom']) k;
insert into public.moods (user_id, mood)
select '00000000-0000-0000-0000-0000000000c2', m
from unnest(array['happy', 'sweet', 'excited', 'laugh', 'calm', 'relaxed', 'missyou', 'shy', 'sleepy', 'hungry', 'bored', 'busy',
                  'tired', 'anxious', 'stressed', 'wronged', 'sad', 'angry', 'jealous', 'lonely', 'sick']) m;
\if :latest
-- 0.3.0：關掉小貓的通知、養了一隻貓、回報了手機溫度
select public.app_set_notification_prefs('{"pet": false, "memo": false}');
select public.app_adopt_pet('{"name": "麻糬", "coat": "ragdoll"}');
select public.app_pet_action('{"action": "pet"}');
select public.app_report_device('{"batteryLevel": 50, "network": "wifi", "batteryTemp": 36.5, "thermal": 1}');
\else
select public.app_set_notification_prefs('{"memo": false}');
\endif
select pg_temp.ok((select count(*) from public.comforts) = 30 and (select count(*) from public.moods) = 21,
                  'every comfort and mood kind is stored before the upgrade');

\else
select pg_temp.ok((select count(*) from public.comforts) = 30 and (select count(*) from public.moods) = 21,
                  'upgrade-v2.sql runs again on a database that already has every comfort and mood kind');
select pg_temp.login('c2');
\if :latest
select pg_temp.ok(public.app_notification_prefs()->'pet' = 'false' and public.app_notification_prefs()->'memo' = 'false',
                  'notification settings (including the cat) survive the upgrade');
select pg_temp.ok(public.app_pet()->'pet'->>'name' = '麻糬' and public.app_pet()->'pet'->>'coat' = 'ragdoll'
                  and (public.app_pet()->'pet'->>'xp')::int = 2, 'the cat (a newer breed) survives the upgrade');
select pg_temp.ok((select battery_temp = 36.5 and thermal = 1 from public.device_status where user_id = '00000000-0000-0000-0000-0000000000c2'),
                  'the phone temperature survives the upgrade');
\else
select pg_temp.ok(public.app_notification_prefs()->'memo' = 'false' and public.app_notification_prefs()->'pet' = 'true',
                  'notification settings survive the upgrade and the cat is on by default');
select pg_temp.ok(public.app_pet()->'pet' = 'null', 'the cat can be adopted after the upgrade');
\endif
-- 清單還是有在擋：不認得的種類存不進去
select pg_temp.ok(pg_temp.error_of($$insert into public.comforts (couple_id, receiver_id, kind) values
                    (public.my_couple_id(), '00000000-0000-0000-0000-0000000000c1', 'slap')$$) = '23514',
                  'unknown comfort kinds are still rejected');
select pg_temp.ok(pg_temp.error_of($$insert into public.moods (user_id, mood) values ('00000000-0000-0000-0000-0000000000c2', 'meh')$$) = '23514',
                  'unknown moods are still rejected');
select pg_temp.ok(pg_temp.error_of($$insert into public.notification_prefs (user_id, kind) values ('00000000-0000-0000-0000-0000000000c2', 'spam')$$) = '23514',
                  'unknown notification kinds are still rejected');
\endif
