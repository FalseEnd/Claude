# 小倆口：情侶 App

可愛的 Android 情侶 App，資料存在你們自己的免費 Supabase 專案裡。不用開口也知道彼此的狀態，並把在一起的日子記下來。

- **配對**：6 碼配對碼，雙方各自決定分享哪些資料，隨時可以暫停或解除
- **頭像**：從相簿選照片（可以移動、縮放）或選可愛的表情，只有你們兩個看得到
- **個人資料與私人備註**：暱稱、生日（自動算年齡和星座）、身高、體重、電話、血型…兩個人互相看得到；給對方寫的備註只有自己看得到 🔒
- **對方的手機**：電量與充電、網路、螢幕與勿擾、步數、裝置型號；通知列常駐顯示對方的狀態，按一下就能問「現在在哪？」或送抱抱
- **桌面小工具**：「在一起」（2×1）顯示兩人頭像和天數；「對方狀態」（4×1）顯示在哪、電量、心情，還能直接抱抱，兩個都只要一格高；「備忘錄」顯示放在桌面的備忘錄和照片縮圖
- **地圖與標點**：即時位置與距離；搜尋地址直接定位；設定標點，對方抵達或離開時通知；「現在在哪？」
- **自動記帳**：讀取 LINE Pay（LINE錢包）、街口、Google 錢包、銀行的付款通知，自動抓店名和分類，同一筆消費只記一次；禮物模式讓驚喜不被拆穿
- **猜花費**：對方開放的消費先藏起金額，猜完揭曉計分，猜中還有紙花
- **心情與安撫**：設定今天的心情，對方心情不好時一鍵送抱抱、摸摸頭
- **紀念日與約會**：在一起天數、生日、週年、520 與 1314；約會邀請、答應、提醒
- **日曆生活紀錄**：除了約會，還能在日曆上用文字、照片、語音記錄那天的生活，兩個人一起寫
- **備忘錄**：文字、照片、語音，可以只有自己看或和另一半共用，還能放在桌面小工具
- **星座運勢與御神籤**：今天、明天的星座運勢和兩人速配指數；搖一搖抽淺草觀音寺一百籤，再連到台灣好廟網看解籤
- **經期**：預測下次經期、排卵日、易孕期、相對安全期，經期前提醒伴侶多關心
- **歷史紀錄**：所有互動的時間軸

粉嫩配色、粉圓字型、會眨眼的麻糬吉祥物，加上飄愛心、紙花、彈跳按鈕等動畫。

## 快速開始

1. **建立免費的 Supabase 專案**（大約 10 分鐘，只要做一次）：註冊 [Supabase](https://supabase.com)，在 SQL Editor 執行 [`supabase/setup.sql`](supabase/setup.sql)，關掉 Confirm email，複製專案網址與 Publishable key。步驟見 [docs/SUPABASE_SETUP.md](docs/SUPABASE_SETUP.md)。
   **已經在用 0.1 版的**：到 SQL Editor 再執行一次 [`supabase/upgrade-v2.sql`](supabase/upgrade-v2.sql)（一樣從 Raw 複製，看到「更新完成 🎉」就好，兩個人其中一個做就好），新功能才能用，說明見 [docs/SUPABASE_SETUP.md](docs/SUPABASE_SETUP.md) 的「更新資料庫」。App 裡也可以在「我的 › Supabase 與帳號」看到步驟。之前加到桌面的小工具會維持原本的大小，長按小工具拉成一格高就好。
2. **安裝 App**：把 `XiaoLiangKou.apk` 傳到手機安裝，或自己建置（見下方）。
3. 打開 App →「連接 Supabase」→ 貼上網址與金鑰 → 註冊 → 在「我的」把邀請連結傳給另一半 → 配對。

電腦不用開著，手機在哪裡都能連線。不想先設定？App 裡選「先看看示範」就能體驗所有畫面，還可以在「示範控制台」扮演對方。

## 專案內容

| 路徑 | 內容 |
|---|---|
| [`android/`](android) | Android App（Kotlin、Jetpack Compose、Material 3） |
| [`supabase/`](supabase) | 資料庫結構、權限規則（Row Level Security）、App 呼叫的資料庫函式、即時通知與排程提醒；[`setup.sql`](supabase/setup.sql) 是貼到 SQL Editor 執行的完整版本，[`upgrade-v2.sql`](supabase/upgrade-v2.sql) 是 0.1 版的資料庫升級到 0.2 用的 |
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
