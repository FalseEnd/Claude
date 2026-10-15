# 小倆口：情侶 App

可愛的 Android 情侶 App，伺服器與資料庫架在你自己的電腦上。不用開口也知道彼此的狀態，並把在一起的日子記下來。

- **配對**：6 碼配對碼，雙方各自決定分享哪些資料，隨時可以暫停或解除
- **對方的手機**：電量與充電、網路、螢幕與勿擾、步數、裝置型號
- **地圖與標點**：即時位置與距離；設定標點，對方抵達或離開時通知；「現在在哪？」
- **自動記帳**：讀取 LINE Pay、街口、銀行的付款通知；禮物模式讓驚喜不被拆穿
- **猜花費**：對方開放的消費先藏起金額，猜完揭曉計分，猜中還有紙花
- **心情與安撫**：設定今天的心情，對方心情不好時一鍵送抱抱、摸摸頭
- **紀念日與約會**：在一起天數、生日、週年、520 與 1314；約會邀請、答應、提醒
- **經期**：預測下次經期、排卵日、易孕期、相對安全期，經期前提醒伴侶多關心
- **歷史紀錄**：所有互動的時間軸

粉嫩配色、粉圓字型、會眨眼的麻糬吉祥物，加上飄愛心、紙花、彈跳按鈕等動畫。

## 快速開始

1. **在電腦啟動伺服器**（不需要 Docker）：
   1. 安裝 [Node.js](https://nodejs.org/zh-tw/download)（LTS 版）與 [PostgreSQL](https://www.enterprisedb.com/downloads/postgres-postgresql-downloads)（安裝時設定的密碼要記下來）。
   2. 雙擊 `start-windows.bat`（Mac：`start-mac.command`，Linux：`start-linux.sh`）。第一次會問一次 PostgreSQL 的密碼，自動建立資料庫，然後打開瀏覽器顯示「小倆口伺服器運作中」。
   3. 黑色視窗會列出要填進 App 的網址。關掉視窗，伺服器就會停止。
2. **安裝 App**：把 `XiaoLiangKou.apk` 傳到兩支手機安裝，或自己建置（見下方）。
3. 打開 App →「連接我的伺服器」→ 填電腦的網址 → 兩人各自註冊 → 配對。

| 檔案（Windows） | 做什麼 |
|---|---|
| `start-windows.bat` | 啟動伺服器（第一次會設定資料庫） |
| `autostart-windows.bat` | 登入電腦時自動啟動；再執行一次取消 |
| `backup-windows.bat` | 立刻備份（伺服器開著時每天也會自動備份到 `backups`） |
| `restore-windows.bat` | 把備份檔拖到它上面就能還原 |
| `reset-password-windows.bat` | 忘記 App 密碼時產生臨時密碼 |

手機在外面也要能連，建議電腦和手機都裝 Tailscale。防火牆、開機自動啟動、備份還原、Docker 架法等完整說明見 [docs/SELF_HOSTING.md](docs/SELF_HOSTING.md)。

不想先架伺服器？App 裡選「先看看示範」就能體驗所有畫面，還可以在「示範控制台」扮演對方。

## 專案內容

| 路徑 | 內容 |
|---|---|
| [`android/`](android) | Android App（Kotlin、Jetpack Compose、Material 3） |
| [`server/`](server) | 自架伺服器（Node.js）與 PostgreSQL 資料庫結構、權限規則、測試；[`server/scripts/launcher.js`](server/scripts/launcher.js) 是雙擊啟動檔背後的啟動程式 |
| `start-*`、`*-windows.bat` | 不用 Docker 的啟動、自動啟動、備份、還原、重設密碼 |
| [`docker-compose.yml`](docker-compose.yml) | 用 Docker 架設時使用 |
| [`docs/DESIGN.md`](docs/DESIGN.md) | 設計文件：介面風格、功能規格、隱私原則、系統架構 |
| [`docs/SELF_HOSTING.md`](docs/SELF_HOSTING.md) | 架設與連線教學 |
| [`prototype/`](prototype) | 最早的網頁互動原型；`core.js` 是伺服器也在用的共用邏輯 |

## 建置 App

需要 JDK 17 以上與 Android SDK（裝 Android Studio 最簡單）：

```sh
cd android
./gradlew assembleRelease      # 產生 app/build/outputs/apk/release/app-release.apk
```

Windows 用 `gradlew.bat assembleRelease`。正式上架前請換成自己的簽章金鑰（`android/app/build.gradle.kts` 的 `signingConfig`）。

## 測試

```sh
npm test                                   # 共用邏輯（JS）
npm run test:server                        # 資料庫權限 + API / WebSocket + 啟動程式（需要本機 PostgreSQL）
cd android && ./gradlew testDebugUnitTest  # App 核心邏輯
cd android && ./gradlew recordRoborazziDebug  # 不用模擬器，把每個畫面畫成圖片
```

## 授權與素材

- 字型：[jf open 粉圓](https://github.com/justfont/open-huninn-font)（SIL Open Font License 1.1）
- 地圖：© [OpenStreetMap](https://www.openstreetmap.org/copyright) 貢獻者
- 網頁原型圖示：[Lucide](https://lucide.dev)（ISC License）
