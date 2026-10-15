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

1. **在電腦啟動伺服器**（需要 [Docker Desktop](https://www.docker.com/products/docker-desktop/)）：

   ```sh
   docker compose up -d
   ```

   打開 <http://localhost:8080> 看到「小倆口伺服器運作中」就成功了。
2. **安裝 App**：把 `XiaoLiangKou.apk` 傳到兩支手機安裝，或自己建置（見下方）。
3. 打開 App →「連接我的伺服器」→ 填電腦的網址 → 兩人各自註冊 → 配對。

手機在外面也要能連，建議電腦和手機都裝 Tailscale。完整步驟、權限設定與備份方式見 [docs/SELF_HOSTING.md](docs/SELF_HOSTING.md)。

不想先架伺服器？App 裡選「先看看示範」就能體驗所有畫面，還可以在「示範控制台」扮演對方。

## 專案內容

| 路徑 | 內容 |
|---|---|
| [`android/`](android) | Android App（Kotlin、Jetpack Compose、Material 3） |
| [`server/`](server) | 自架伺服器（Node.js）與 PostgreSQL 資料庫結構、權限規則、測試 |
| [`docker-compose.yml`](docker-compose.yml) | 一行指令啟動伺服器＋資料庫 |
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
npm run test:server                        # 資料庫權限 + API / WebSocket（需要本機 PostgreSQL）
cd android && ./gradlew testDebugUnitTest  # App 核心邏輯
cd android && ./gradlew recordRoborazziDebug  # 不用模擬器，把每個畫面畫成圖片
```

## 授權與素材

- 字型：[jf open 粉圓](https://github.com/justfont/open-huninn-font)（SIL Open Font License 1.1）
- 地圖：© [OpenStreetMap](https://www.openstreetmap.org/copyright) 貢獻者
- 網頁原型圖示：[Lucide](https://lucide.dev)（ISC License）
