#!/usr/bin/env bash
# 在臨時 PostgreSQL 叢集上執行：
#   1. 資料庫權限測試（db/tests/rls_smoke.sql）
#   2. API + WebSocket 端對端測試（test/*.test.js）
# 需要本機安裝 PostgreSQL 15+（initdb / pg_ctl / psql）與 Node.js 20+。
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
bin="$(dirname "$(command -v pg_ctl || ls /usr/lib/postgresql/*/bin/pg_ctl | tail -1)")"
work="$(mktemp -d)"
as_pg=()
if [ "$(id -u)" = 0 ]; then
  chown postgres "$work"
  as_pg=(runuser -u postgres --)
fi
cleanup() { "${as_pg[@]}" "$bin/pg_ctl" -D "$work/data" -m immediate stop >/dev/null 2>&1 || true; rm -rf "$work"; }
trap cleanup EXIT

"${as_pg[@]}" "$bin/initdb" -A trust -D "$work/data" -U postgres -E UTF8 --locale=C.UTF-8 >/dev/null
"${as_pg[@]}" "$bin/pg_ctl" -D "$work/data" -o "-k $work -c listen_addresses=''" -l "$work/log" -w start >/dev/null

psql_() { "${as_pg[@]}" "$bin/psql" -h "$work" -U postgres -v ON_ERROR_STOP=1 -q "$@"; }

echo "== 資料庫權限測試"
psql_ -d postgres -c 'create database xlk_rls' -c 'create database xlk_api'
for f in "$root"/db/migrations/*.sql; do psql_ -d xlk_rls -f "$f"; done
psql_ -d xlk_rls -o /dev/null -f "$root/db/tests/rls_smoke.sql" 2>&1 | sed 's/^psql:[^ ]* NOTICE:  //'

echo "== API 與 WebSocket 測試"
cd "$root"
DATABASE_URL="postgresql://postgres@localhost/xlk_api?host=$work" node --test test/*.test.js
