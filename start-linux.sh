#!/bin/bash
# 小倆口伺服器（Linux）：執行 ./start-linux.sh 啟動。第一次會設定資料庫。
cd "$(dirname "$0")" || exit 1
if ! command -v node >/dev/null 2>&1 && [ -s "$HOME/.nvm/nvm.sh" ]; then . "$HOME/.nvm/nvm.sh" >/dev/null 2>&1; fi
if ! command -v node >/dev/null 2>&1; then
  echo "還沒安裝 Node.js 20 以上的版本：https://nodejs.org（或用套件管理員安裝）"
  xdg-open server/scripts/need-node.html >/dev/null 2>&1 &
  [ -t 0 ] && read -r -p "按 Enter 關閉" _
  exit 1
fi
node server/scripts/launcher.js "$@"
status=$?
if [ $status -ne 0 ] && [ -t 0 ]; then read -r -p "按 Enter 關閉" _; fi
exit $status
