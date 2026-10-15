#!/usr/bin/env bash
# 在本機的 Supabase（Supabase CLI + Docker）上執行資料庫與 API 測試：
#   1. 檢查 supabase/setup.sql 和 migrations 一致
#   2. supabase db reset：重建資料庫並套用 migrations
#   3. 資料庫冒煙測試（supabase/tests/rls_smoke.sql，在交易裡執行，最後 rollback）
#   4. Node 測試（supabase/tests/*.test.js）：Auth、RPC、Realtime 端對端，以及經期預測和 App 一致
# 需要：Docker、Supabase CLI（SUPABASE_BIN，或用 npx supabase）、psql、Node.js 22+。
# 測試完 Supabase 會繼續執行（App 的整合測試可以接著用），要停掉請執行 supabase stop。
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"

if [ -n "${SUPABASE_BIN:-}" ]; then sb=("$SUPABASE_BIN"); else sb=(npx --yes supabase); fi
command -v docker >/dev/null || { echo "需要 Docker" >&2; exit 1; }
command -v psql >/dev/null || { echo "需要 psql（PostgreSQL client）" >&2; exit 1; }
command -v node >/dev/null || { echo "需要 Node.js 22+" >&2; exit 1; }

echo "== setup.sql"
bash supabase/scripts/build-setup.sh --check

echo "== 啟動本機 Supabase"
"${sb[@]}" start -x studio,imgproxy,edge-runtime,logflare,vector,storage-api,mailpit,supavisor,postgres-meta >/dev/null
"${sb[@]}" db reset >/dev/null

env_out="$("${sb[@]}" status -o env 2>/dev/null)"
val() { sed -n "s/^$1=\"\{0,1\}\([^\"]*\)\"\{0,1\}\$/\1/p" <<<"$env_out" | head -1; }
export SUPABASE_URL="$(val API_URL)"
export SUPABASE_KEY="${SUPABASE_KEY:-$(val PUBLISHABLE_KEY)}"
[ -n "$SUPABASE_KEY" ] || SUPABASE_KEY="$(val ANON_KEY)"
export SUPABASE_SERVICE_KEY="$(val SERVICE_ROLE_KEY)"
export DATABASE_URL="$(val DB_URL)"
[ -n "$SUPABASE_URL" ] && [ -n "$SUPABASE_KEY" ] && [ -n "$SUPABASE_SERVICE_KEY" ] && [ -n "$DATABASE_URL" ] \
  || { echo "讀不到 supabase status 的網址或金鑰" >&2; exit 1; }

echo "== 資料庫冒煙測試"
psql "$DATABASE_URL" -X -v ON_ERROR_STOP=1 -q -o /dev/null -f supabase/tests/rls_smoke.sql 2>&1 | sed 's/^psql:[^ ]* NOTICE:  //'

echo "== Auth、RPC 與 Realtime 測試"
node --test supabase/tests/*.test.js

echo "全部通過。本機 Supabase 還在執行：$SUPABASE_URL（停止：supabase stop）"
