#!/usr/bin/env bash
# 在本機的 Supabase（Supabase CLI + Docker）上執行資料庫與 API 測試：
#   1. 檢查 supabase/setup.sql、upgrade-v2.sql 和 migrations 一致
#   2. 舊版使用者：只套用 v1 的 migrations，再執行 upgrade-v2.sql 兩次（只跑一次就好，但多跑也不能壞）；
#      先放一個自己建的公開 media 空間，更新後要變成不公開。
#      跑過舊的 upgrade-v2.sql 的資料庫（0.2.0 只有 v2、0.2.1 多了手機名稱與電信、0.2.2 多了 GIPHY 貼圖），
#      再執行一次新的 upgrade-v2.sql；
#      以前收到的安撫只在第一次執行時標成看過（supabase/tests/comfort_backlog.sql）；
#      資料表裡已經有後來才多的種類（0.2.6 的安撫、小貓的通知設定）時再執行也不能失敗（supabase/tests/newest_rows.sql）
#   3. 新專案：空的資料庫直接執行 setup.sql
#   4. supabase db reset：重建資料庫並套用 migrations
#   2–4 每次都跑資料庫冒煙測試（supabase/tests/rls_smoke.sql，在交易裡執行，最後 rollback）
#   5. Node 測試（supabase/tests/*.test.js）：Auth、RPC、Realtime、Storage 端對端，以及經期預測和 App 一致
# 需要：Docker、Supabase CLI（SUPABASE_BIN，或用 npx supabase）、psql、Node.js 22+。
# 測試完 Supabase 會繼續執行（App 的整合測試可以接著用），要停掉請執行 supabase stop。
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"

if [ -n "${SUPABASE_BIN:-}" ]; then sb=("$SUPABASE_BIN"); else sb=(npx --yes supabase); fi
command -v docker >/dev/null || { echo "需要 Docker" >&2; exit 1; }
command -v psql >/dev/null || { echo "需要 psql（PostgreSQL client）" >&2; exit 1; }
command -v node >/dev/null || { echo "需要 Node.js 22+" >&2; exit 1; }

echo "== setup.sql、upgrade-v2.sql"
bash supabase/scripts/build-setup.sh --check

echo "== 啟動本機 Supabase"
"${sb[@]}" start -x studio,imgproxy,edge-runtime,logflare,vector,mailpit,supavisor,postgres-meta >/dev/null
project="$(sed -n 's/^project_id = "\(.*\)"$/\1/p' supabase/config.toml)"
docker ps --format '{{.Names}}' | grep -qx "supabase_storage_$project" \
  || { echo "Storage 沒有啟動（之前用別的參數啟動過）：請先執行 supabase stop 再測試" >&2; exit 1; }

env_out="$("${sb[@]}" status -o env 2>/dev/null)"
val() { sed -n "s/^$1=\"\{0,1\}\([^\"]*\)\"\{0,1\}\$/\1/p" <<<"$env_out" | head -1; }
export SUPABASE_URL="$(val API_URL)"
export SUPABASE_KEY="${SUPABASE_KEY:-$(val PUBLISHABLE_KEY)}"
[ -n "$SUPABASE_KEY" ] || SUPABASE_KEY="$(val ANON_KEY)"
export SUPABASE_SERVICE_KEY="$(val SERVICE_ROLE_KEY)"
export DATABASE_URL="$(val DB_URL)"
[ -n "$SUPABASE_URL" ] && [ -n "$SUPABASE_KEY" ] && [ -n "$SUPABASE_SERVICE_KEY" ] && [ -n "$DATABASE_URL" ] \
  || { echo "讀不到 supabase status 的網址或金鑰" >&2; exit 1; }

# 成功時不顯示輸出，失敗時才印出來
quiet() { local out; out="$("$@" 2>&1)" || { echo "$out" >&2; return 1; }; }
run_sql() { quiet psql "$DATABASE_URL" -X -v ON_ERROR_STOP=1 -q -o /dev/null -f "$1"; }
backlog() { quiet psql "$DATABASE_URL" -X -v ON_ERROR_STOP=1 -q -o /dev/null -v step="$1" -f supabase/tests/comfort_backlog.sql; }
newest() { quiet psql "$DATABASE_URL" -X -v ON_ERROR_STOP=1 -q -o /dev/null -v step="$1" -f supabase/tests/newest_rows.sql; }
smoke() { psql "$DATABASE_URL" -X -v ON_ERROR_STOP=1 -q -o /dev/null -f supabase/tests/rls_smoke.sql 2>&1 | sed 's/^psql:[^ ]* NOTICE:  //'; }
schema_is_latest() {
  [ "$(psql "$DATABASE_URL" -X -tAc "select public.app_health()->>'schema'")" = 8 ] || { echo "app_health 沒有回傳 schema 8" >&2; exit 1; }
}

echo "== 舊版資料庫執行 upgrade-v2.sql（兩次）"
quiet "${sb[@]}" db reset --version 20261015000004
quiet psql "$DATABASE_URL" -X -v ON_ERROR_STOP=1 -q -c "insert into storage.buckets (id, name, public) values ('media', 'media', true)"
run_sql supabase/upgrade-v2.sql
run_sql supabase/upgrade-v2.sql
schema_is_latest
quiet smoke

echo "== 跑過舊的 upgrade-v2.sql 的資料庫再執行新的 upgrade-v2.sql（加上手機名稱、電信與 GIPHY 貼圖）"
quiet "${sb[@]}" db reset --version 20261016000005
run_sql supabase/upgrade-v2.sql
schema_is_latest
quiet smoke

echo "== 以前收到的安撫只在第一次執行新的 upgrade-v2.sql 時標成看過"
quiet "${sb[@]}" db reset --version 20261017000006
backlog seed
run_sql supabase/upgrade-v2.sql
backlog first
run_sql supabase/upgrade-v2.sql
backlog again

echo "== 0.2.1 的資料庫再執行新的 upgrade-v2.sql（加上 GIPHY 貼圖）"
quiet "${sb[@]}" db reset --version 20261017000006
run_sql supabase/upgrade-v2.sql
schema_is_latest
quiet smoke

echo "== 0.2.2 的資料庫再執行新的 upgrade-v2.sql（加上更多心情和安撫）"
quiet "${sb[@]}" db reset --version 20261020000007
run_sql supabase/upgrade-v2.sql
schema_is_latest
quiet smoke

echo "== 0.2.6 的資料庫（已經送過 0.2.6 才有的安撫）再執行新的 upgrade-v2.sql"
quiet "${sb[@]}" db reset --version 20261024000009
newest seed026
run_sql supabase/upgrade-v2.sql
newest check026
schema_is_latest

echo "== 用過最新功能的資料庫（每一種安撫和心情、小貓的通知設定、小貓）再執行一次 upgrade-v2.sql"
quiet "${sb[@]}" db reset
newest seed
run_sql supabase/upgrade-v2.sql
newest check
schema_is_latest

echo "== 新專案執行 setup.sql"
empty="$(mktemp -d)"
trap 'rm -rf "$empty"' EXIT
mkdir -p "$empty/supabase/migrations"
cp supabase/config.toml "$empty/supabase/"
quiet "${sb[@]}" db reset --workdir "$empty"
run_sql supabase/setup.sql
schema_is_latest
quiet smoke

echo "== 套用 migrations"
quiet "${sb[@]}" db reset

echo "== 資料庫冒煙測試"
smoke

echo "== Auth、RPC、Realtime 與 Storage 測試"
node --test supabase/tests/*.test.js

echo "全部通過。本機 Supabase 還在執行：$SUPABASE_URL（停止：supabase stop）"
