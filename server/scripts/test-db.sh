#!/usr/bin/env bash
# 在臨時 PostgreSQL 叢集上載入 schema.sql 並執行 RLS 冒煙測試。
# 需要本機安裝 PostgreSQL 15+（initdb / pg_ctl / psql）。
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
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

run() { "${as_pg[@]}" "$bin/psql" -h "$work" -U postgres -d postgres -v ON_ERROR_STOP=1 -q "$@"; }
run -f "$here/supabase_stub.sql"
run -f "$here/../schema.sql"
run -o /dev/null -f "$here/rls_smoke.sql" 2>&1 | sed 's/^psql:[^ ]* NOTICE:  //'
echo "backend: all checks passed"
