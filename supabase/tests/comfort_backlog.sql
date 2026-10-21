-- 以前收到的安撫：0.2.1 以前的 App 從來沒有標成看過，第一次執行新的 upgrade-v2.sql 時一天以前的標成看過；
-- 之後再執行時，還沒看過的安撫一則都不能動。
-- test.sh 在 0.2.1 的資料庫上依序執行（以 postgres 身分，資料留到下一步的 supabase db reset）：
--   seed → upgrade-v2.sql → first → upgrade-v2.sql → again
--   psql "$DB_URL" -v step=seed -f supabase/tests/comfort_backlog.sql
\set ON_ERROR_STOP on
\set QUIET on

select :'step' = 'seed' as seed, :'step' = 'first' as first \gset

create function pg_temp.ok(cond boolean, msg text) returns void language plpgsql as $$
begin
  if cond is distinct from true then raise exception 'FAILED: %', msg; end if;
  raise notice 'ok - %', msg;
end $$;

-- 跟 PostgREST 一樣，用 JWT claims 告訴資料庫目前是誰（阿哲 b1、小雨 b2）
create function pg_temp.login(p_user text) returns void language sql as $$
  select set_config('request.jwt.claims', json_build_object('sub', '00000000-0000-0000-0000-0000000000' || p_user, 'role', 'authenticated')::text, false);
$$;

create function pg_temp.read_at(p_message text) returns timestamptz language sql as $$
  select read_at from public.comforts where message = p_message
$$;

\if :seed
insert into auth.users (instance_id, id, aud, role, email, raw_user_meta_data) values
  ('00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-0000000000b1', 'authenticated', 'authenticated',
   'backlog-zhe@example.com', '{"display_name": "阿哲"}'),
  ('00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-0000000000b2', 'authenticated', 'authenticated',
   'backlog-yu@example.com', '{"display_name": "小雨"}');
select pg_temp.login('b1');
select public.create_invite() as code \gset
select pg_temp.login('b2');
select public.accept_invite(:'code');
insert into public.comforts (couple_id, receiver_id, kind, message)
values (public.my_couple_id(), '00000000-0000-0000-0000-0000000000b1', 'hug', 'to zhe, long ago');
select pg_temp.login('b1');
insert into public.comforts (couple_id, receiver_id, kind, message, read_at) values
  (public.my_couple_id(), '00000000-0000-0000-0000-0000000000b2', 'hug', 'long ago', null),
  (public.my_couple_id(), '00000000-0000-0000-0000-0000000000b2', 'pat', 'read long ago', '2026-01-01 08:00+00'),
  (public.my_couple_id(), '00000000-0000-0000-0000-0000000000b2', 'kiss', 'this morning', null);
update public.comforts set created_at = now() - interval '5 days' where message <> 'this morning';
update public.comforts set created_at = now() - interval '2 hours' where message = 'this morning';
select pg_temp.ok((select count(*) from public.comforts where read_at is null) = 3, 'three comforts are still unread before the upgrade');

\elif :first
select pg_temp.ok(pg_temp.read_at('long ago') is not null and pg_temp.read_at('to zhe, long ago') is not null,
                  'the first upgrade marks the comforts both of them received before 0.2.2 as read');
select pg_temp.ok(pg_temp.read_at('this morning') is null, 'comforts from the last day stay unread (Home still shows them)');
select pg_temp.ok(pg_temp.read_at('read long ago') = '2026-01-01 08:00+00', 'comforts read earlier keep their time');
-- 更新之後收到的：之後再執行 upgrade-v2.sql 也不能被標掉，就算已經是好幾天前的
select pg_temp.login('b1');
insert into public.comforts (couple_id, receiver_id, kind, message)
values (public.my_couple_id(), '00000000-0000-0000-0000-0000000000b2', 'hug', 'after the upgrade');
update public.comforts set created_at = now() - interval '5 days' where message = 'after the upgrade';

\else
select pg_temp.ok(pg_temp.read_at('after the upgrade') is null and pg_temp.read_at('this morning') is null,
                  'running upgrade-v2.sql again leaves unread comforts alone');
\endif
