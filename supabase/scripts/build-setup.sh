#!/usr/bin/env bash
# 把 supabase/migrations/*.sql 依序串成一個 supabase/setup.sql（包在同一個交易裡），
# 讓使用者在 Supabase 後台的 SQL Editor 貼上執行一次就設定好。
#   bash supabase/scripts/build-setup.sh           產生 / 更新 setup.sql 與分成小份的 setup-parts/
#   bash supabase/scripts/build-setup.sh --check   只檢查 setup.sql 是否和遷移檔一致（測試用）
set -euo pipefail

dir="$(cd "$(dirname "$0")/.." && pwd)"
out="$dir/setup.sql"

build() {
  echo "-- ============================================================================="
  echo "-- 小倆口 Supabase 設定檔"
  echo "-- 用法：Supabase 後台 › SQL Editor › 貼上這個檔案的全部內容 › Run（只要執行一次）。"
  echo "-- 全部包在同一個交易裡：中途出錯會整個取消，不會留下設定到一半的資料庫。"
  echo "-- 這個檔案由 supabase/scripts/build-setup.sh 從 supabase/migrations/ 產生，請勿手動修改。"
  echo "-- ============================================================================="
  echo
  echo "begin;"
  for f in "$dir"/migrations/*.sql; do
    echo
    echo "-- >>> migrations/$(basename "$f")"
    echo
    cat "$f"
    # 檔案最後沒有換行時補上，避免和下一段黏在一起
    [ -z "$(tail -c 1 "$f")" ] || echo
  done
  echo
  echo "-- SQL Editor 會顯示最後一個查詢的結果：看到這一行就代表設定完成"
  echo "select '設定完成 🎉 回到說明文件做第 3 步' as \"小倆口\";"
  echo
  echo "commit;"
}

if [ "${1:-}" = "--check" ]; then
  if ! diff -q <(build) "$out" >/dev/null 2>&1; then
    echo "supabase/setup.sql 和 migrations 不一致，請執行 bash supabase/scripts/build-setup.sh" >&2
    exit 1
  fi
  echo "setup.sql 和 migrations 一致"
  node "$dir/scripts/split-setup.js" --check
else
  build > "$out"
  echo "已產生 $out"
  node "$dir/scripts/split-setup.js"
fi
