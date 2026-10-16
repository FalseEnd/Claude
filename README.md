# 小倆口：情侶 App

可愛的 Android 情侶 App，資料存在你們自己的免費 Supabase 專案裡。不用開口也知道彼此的狀態，並把在一起的日子記下來。

- **配對**：6 碼配對碼，雙方各自決定分享哪些資料，隨時可以暫停或解除
- **對方的手機**：電量與充電、網路、螢幕與勿擾、步數、裝置型號
- **地圖與標點**：即時位置與距離；搜尋地址直接定位；設定標點，對方抵達或離開時通知；「現在在哪？」
- **自動記帳**：讀取 LINE Pay、街口、銀行的付款通知；禮物模式讓驚喜不被拆穿
- **猜花費**：對方開放的消費先藏起金額，猜完揭曉計分，猜中還有紙花
- **心情與安撫**：設定今天的心情，對方心情不好時一鍵送抱抱、摸摸頭
- **紀念日與約會**：在一起天數、生日、週年、520 與 1314；約會邀請、答應、提醒
- **經期**：預測下次經期、排卵日、易孕期、相對安全期，經期前提醒伴侶多關心
- **歷史紀錄**：所有互動的時間軸

粉嫩配色、粉圓字型、會眨眼的麻糬吉祥物，加上飄愛心、紙花、彈跳按鈕等動畫。

## 快速開始

1. **建立免費的 Supabase 專案**（大約 10 分鐘，只要做一次）：註冊 [Supabase](https://supabase.com)，在 SQL Editor 執行 [`supabase/setup.sql`](supabase/setup.sql)，關掉 Confirm email，複製專案網址與 Publishable key。步驟見 [docs/SUPABASE_SETUP.md](docs/SUPABASE_SETUP.md)。
2. **安裝 App**：把 `XiaoLiangKou.apk` 傳到手機安裝，或自己建置（見下方）。
3. 打開 App →「連接 Supabase」→ 貼上網址與金鑰 → 註冊 → 在「我的」把邀請連結傳給另一半 → 配對。

電腦不用開著，手機在哪裡都能連線。不想先設定？App 裡選「先看看示範」就能體驗所有畫面，還可以在「示範控制台」扮演對方。

## 專案內容

| 路徑 | 內容 |
|---|---|
| [`android/`](android) | Android App（Kotlin、Jetpack Compose、Material 3） |
| [`supabase/`](supabase) | 資料庫結構、權限規則（Row Level Security）、App 呼叫的資料庫函式、即時通知與排程提醒；[`setup.sql`](supabase/setup.sql) 是貼到 SQL Editor 執行的完整版本 |
| [`docs/DESIGN.md`](docs/DESIGN.md) | 設計文件：介面風格、功能規格、隱私原則、系統架構 |
| [`docs/SUPABASE_SETUP.md`](docs/SUPABASE_SETUP.md) | Supabase 設定教學 |
| [`prototype/`](prototype) | 最早的網頁互動原型；`core.js` 是經期預測等共用邏輯的參考實作 |

## 建置 App

需要 JDK 17 以上與 Android SDK（裝 Android Studio 最簡單）：

```sh
cd android
./gradlew assembleRelease      # 產生 app/build/outputs/apk/release/app-release.apk
```

Windows 用 `gradlew.bat assembleRelease`。正式上架前請換成自己的簽章金鑰（`android/app/build.gradle.kts` 的 `signingConfig`）。

## 測試

```sh
npm test                                      # 共用邏輯（JS）
npm run test:db                               # 資料庫權限、App 用的函式、即時通知、排程（需要 Docker、psql 與 Node.js 22 以上，會用 Supabase CLI 在本機啟動 Supabase）
cd android && ./gradlew testDebugUnitTest     # App 核心邏輯
cd android && ./gradlew recordRoborazziDebug  # 不用模擬器，把每個畫面畫成圖片
```

App 對真的 Supabase 跑完整流程：先 `npx supabase start`，再執行
`XLK_TEST_SUPABASE_URL=http://127.0.0.1:54321 XLK_TEST_SUPABASE_KEY=<publishable key> ./gradlew testDebugUnitTest`。

## 授權與素材

- 字型：[jf open 粉圓](https://github.com/justfont/open-huninn-font)（SIL Open Font License 1.1）
- 地圖：© [OpenStreetMap](https://www.openstreetmap.org/copyright) 貢獻者
- 網頁原型圖示：[Lucide](https://lucide.dev)（ISC License）
