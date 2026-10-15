-- 本機測試用：模擬 Supabase 提供的 auth schema、角色與預設權限，
-- 讓 schema.sql 可以在一般 PostgreSQL 上跑 RLS 測試。

create role anon nologin;
create role authenticated nologin;
create role service_role nologin bypassrls;

create schema auth;
create schema extensions;
grant usage on schema auth, extensions, public to anon, authenticated;

create table auth.users (
  id    uuid primary key,
  email text
);

-- Supabase 從 JWT 讀出目前使用者；測試中用 set_config('request.jwt.claim.sub', …) 切換
create function auth.uid() returns uuid
language sql stable as $$
  select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
$$;

alter default privileges in schema public grant select, insert, update, delete on tables to anon, authenticated;
alter default privileges in schema public grant usage, select on sequences to anon, authenticated;
