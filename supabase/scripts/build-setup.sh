#!/usr/bin/env bash
# 從 supabase/migrations/*.sql 產生使用者貼到 Supabase SQL Editor 的 setup.sql 與 upgrade-v2.sql（實際工作在 build-setup.js）。
#   bash supabase/scripts/build-setup.sh           產生 / 更新
#   bash supabase/scripts/build-setup.sh --check   只檢查是否和遷移檔一致（測試用）
set -euo pipefail
node "$(cd "$(dirname "$0")" && pwd)/build-setup.js" "$@"
