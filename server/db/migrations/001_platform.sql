-- 自架版的基礎：帳號表、角色與 auth.uid()。
-- 伺服器替每個請求開一個交易，執行
--   set local role authenticated;  select set_config('request.jwt.claim.sub', <使用者 id>, true);
-- 之後所有查詢都受 002_schema.sql 的 Row Level Security 保護。

do $$
begin
  if not exists (select 1 from pg_roles where rolname = 'anon') then create role anon nologin; end if;
  if not exists (select 1 from pg_roles where rolname = 'authenticated') then create role authenticated nologin; end if;
end $$;

-- 伺服器登入用的角色必須能切換成 authenticated
do $$
begin
  if not exists (
    select 1 from pg_auth_members m
    join pg_roles r on r.oid = m.roleid and r.rolname = 'authenticated'
    join pg_roles u on u.oid = m.member and u.rolname = current_user
  ) and not (select rolsuper from pg_roles where rolname = current_user) then
    execute format('grant authenticated to %I', current_user);
  end if;
end $$;

create schema if not exists auth;
create schema if not exists extensions;
grant usage on schema auth, extensions, public to anon, authenticated;

create table auth.users (
  id             uuid primary key default gen_random_uuid(),
  username       text unique check (username ~ '^[a-z0-9_.]{3,32}$'),
  password_hash  text,
  email          text,
  tokens_valid_after timestamptz not null default '-infinity',  -- 改密碼時更新，讓舊的登入失效
  created_at     timestamptz not null default now()
);

-- 目前請求的使用者（伺服器在交易開頭設定）
create function auth.uid() returns uuid
language sql stable as $$
  select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
$$;
grant execute on function auth.uid() to anon, authenticated;

alter default privileges in schema public grant select, insert, update, delete on tables to authenticated;
alter default privileges in schema public grant usage, select on sequences to authenticated;
