# 小倆口：情侶 App

可愛的 Android 情侶 App，資料存在你們自己的免費 Supabase 專案裡。不用開口也知道彼此的狀態，並把在一起的日子記下來。目前版本 **0.2.0**。

> **已經在用 0.1 版？先更新資料庫 🛠️**
>
> 0.2 的新功能（個人資料、備註、日曆的生活紀錄、備忘錄、照片和語音）要在你們的 Supabase 多建幾張表和一個放照片的空間：到 Supabase 後台的 **SQL Editor** 執行一次 [`supabase/upgrade-v2.sql`](supabase/upgrade-v2.sql)（跟當初的 setup.sql 一樣，從 GitHub 的 **Raw** 頁面複製），看到「更新完成 🎉」就好。兩個人其中一個做一次就好，已經存的資料不受影響。
>
> **0.2.1**：之前跑過 upgrade-v2.sql 的話，再跑一次（多了手機名稱和電信），對方的手機名稱和電信才會顯示；沒跑其他功能也都能用。
>
> 新版 App 先裝也沒關係：還沒更新時舊功能照常能用，用到新功能的畫面會提醒「資料庫需要更新」。步驟見 [docs/SUPABASE_SETUP.md 的「更新資料庫」](docs/SUPABASE_SETUP.md#更新資料庫2026-10-新功能個人資料日曆紀錄備忘錄)，App 的「我的 › Supabase 與帳號」也有一步一步的說明和「重新檢查」按鈕。之前加到桌面的小工具會維持原本的大小，長按拉成一格高就好。

- **配對**：6 碼配對碼，雙方各自決定分享哪些資料，隨時可以暫停或解除
- **頭像**：從相簿選照片（可以移動、縮放）或選可愛的表情，只有你們兩個看得到
- **個人資料與私人備註**：暱稱、生日（自動算年齡和星座）、身高、體重、電話、血型、喜歡和不喜歡的…兩個人互相看得到；給對方寫的備註只有自己看得到 🔒
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
   已經在用 0.1 版的不用重做，只要執行 [`supabase/upgrade-v2.sql`](supabase/upgrade-v2.sql)（見最上面）。
2. **安裝 App**：把 `XiaoLiangKou.apk` 傳到手機安裝，或自己建置（見下方）。
3. 打開 App →「連接 Supabase」→ 貼上網址與金鑰 → 註冊 → 在「我的」把邀請連結傳給另一半 → 配對。

電腦不用開著，手機在哪裡都能連線。不想先設定？App 裡選「先看看示範」就能體驗所有畫面，還可以在「示範控制台」扮演對方。

## 隱私：誰看得到什麼

權限規則寫在資料庫裡（PostgreSQL 的 Row Level Security 和 Storage 的規則），App 裡的金鑰繞不過去。

| 資料 | 誰看得到 |
|---|---|
| 手機狀態、位置、開銷、經期 | 對方只看得到你開啟分享、而且沒有暫停的項目 |
| 我的資料（身高、血型、電話…） | 你們兩個 |
| 給對方的備註 🔒 | 只有自己。對方查不到，也不會從通知、即時更新或歷史紀錄知道你有沒有寫、什麼時候寫 |
| 日曆的生活紀錄 | 你們兩個；各自只能改、刪自己寫的。解除配對後雙方都看不到 |
| 共用備忘錄 | 你們兩個都能看、能改；只有建立的人能刪除或改成私人。解除配對後只剩建立的人看得到 |
| 私人備忘錄 | 只有自己，連什麼時候寫、什麼時候改，對方都不會知道 |

照片和語音存在 Supabase Storage 一個不公開的 `media` 空間，每個檔案都要登入才讀得到：

- `c/<配對>/`：日曆和共用備忘錄的照片語音，只有目前配對的兩個人讀得到、傳得上去。
- `u/<自己>/`：私人備忘錄的照片語音，只有自己讀得到。
- 資料庫會檢查每筆紀錄、備忘錄的照片只能指到這兩個資料夾。備忘錄改成共用或私人時，App 會把照片語音搬到對的資料夾；改成私人時，共用資料夾裡的原始檔會刪掉。
- 檔案只有上傳的人刪得掉。解除配對後，原本共用的檔案雙方都讀不到；但檔案不會自動刪除，想清空可以到 Supabase 後台的 **Storage** › `media` 手動刪。手機裡的照片快取在登出時清掉。

## 專案內容

| 路徑 | 內容 |
|---|---|
| [`android/`](android) | Android App（Kotlin、Jetpack Compose、Material 3） |
| [`supabase/`](supabase) | 資料庫結構、權限規則（Row Level Security）、App 呼叫的資料庫函式、即時通知與排程提醒、照片與語音的 Storage 規則；[`setup.sql`](supabase/setup.sql) 是貼到 SQL Editor 執行的完整版本，[`upgrade-v2.sql`](supabase/upgrade-v2.sql) 是 0.1 版的資料庫升級到 0.2 用的 |
| [`docs/DESIGN.md`](docs/DESIGN.md) | 設計文件：介面風格、功能規格、隱私原則、系統架構 |
| [`docs/SUPABASE_SETUP.md`](docs/SUPABASE_SETUP.md) | Supabase 設定教學、從 0.1 版更新資料庫 |
| [`prototype/`](prototype) | 最早的網頁互動原型；`core.js` 是經期預測等共用邏輯的參考實作 |

## 建置 App

需要 JDK 17 以上與 Android SDK（裝 Android Studio 最簡單）：

```sh
cd android
./gradlew assembleRelease      # 產生 app/build/outputs/apk/release/app-release.apk
```

Windows 用 `gradlew.bat assembleRelease`。正式上架前請換成自己的簽章金鑰（`android/app/build.gradle.kts` 的 `signingConfig`）。

版本號在 `android/app/build.gradle.kts`（目前 `versionName` 0.2.0、`versionCode` 2），裝好後在 App「我的」最下面看得到。

## 測試

```sh
npm test                                      # 共用邏輯（JS）
npm run test:db                               # 資料庫（見下方）
cd android && ./gradlew testDebugUnitTest     # App 核心邏輯與畫面
cd android && ./gradlew recordRoborazziDebug  # 不用模擬器，把每個畫面畫成圖片（app/build/screens/）
```

`npm run test:db` 會用 Supabase CLI 在本機啟動 Supabase（包含 Storage），測資料庫權限、App 用的函式、即時通知、排程、照片與語音的上傳下載和權限，也會拿 0.1 版的資料庫執行 `upgrade-v2.sql`、拿空的資料庫執行 `setup.sql` 各測一次。需要 Docker、psql 與 Node.js 22 以上；之前用不含 Storage 的參數啟動過 Supabase 的話，先執行 `npx supabase stop`。

0.2 新增的 App 測試（只跑一個：`./gradlew testDebugUnitTest --tests '*MemoScreensTest*'`；畫成圖片：`./gradlew recordRoborazziDebug --tests '*MemoScreensTest*'`）：

| 測試 | 內容 |
|---|---|
| `ProfileScreensTest` | 我的資料、對方的資料、只有自己看得到的備註 |
| `JournalScreensTest` | 日曆上有紀錄的日子、當天的生活紀錄、記錄這天 |
| `MemoScreensTest`、`MemoWidgetTest` | 備忘錄列表與編輯；備忘錄小工具在 3×2、2×1、拉大和大字時的排版 |
| `WidgetRowScreensTest`、`StatusWidgetTest` | 一格高的「在一起」「對方狀態」小工具，拉高時換成大版面 |
| `FortuneScreensTest`、`HoroscopeTest`、`OmikujiTest` | 星座運勢與御神籤的畫面、搖手機抽籤；星座的日期邊界、同一天結果固定、78 組速配；100 籤、每籤四句五言、吉凶分布 |
| `MediaUiTest` | 照片縮圖、看大圖、語音、選照片和錄音的按鈕 |
| `DataV2Test` | 新功能的資料層（示範模式）與照片快取 |
| `RemoteV2Test` | 連 Supabase 時呼叫的函式與參數、Storage 的網址、資料庫還沒更新時的提醒（用假的 Supabase） |

App 對真的 Supabase 跑完整流程（含 0.2 的個人資料、備註、日曆紀錄、備忘錄、照片的權限）：先 `npx supabase start`，專案裡不能已經有帳號（可以先 `npx supabase db reset`），再執行
`XLK_TEST_SUPABASE_URL=http://127.0.0.1:54321 XLK_TEST_SUPABASE_KEY=<publishable key> ./gradlew testDebugUnitTest`。

## 更新紀錄

- **0.2.1**：
  - 定位：允許權限後馬上開始分享位置，地圖的「我的位置」不用打開分享也能定位，沒有 Google 服務的手機也能定位。
  - 步數：部分 OPPO 手機也能計步，重新開機也不會歸零。
  - 手機狀態：顯示手機名稱、電信、網速，首頁也看得到自己的手機狀態。
  - 電話可以從 SIM 卡帶入。
  - 字放大時，按鈕和卡片上的字不會被切到。
  - 資料庫：再執行一次 [`upgrade-v2.sql`](supabase/upgrade-v2.sql)。
- **0.2.0**：個人資料與私人備註、日曆記錄那天的生活（文字、照片、語音）、備忘錄與備忘錄小工具、星座運勢與淺草寺觀音籤、「在一起」「對方狀態」小工具改成一格高。資料庫要執行 [`upgrade-v2.sql`](supabase/upgrade-v2.sql)。
- **0.1.0**：第一版。

## 授權與素材

- 字型：[jf open 粉圓](https://github.com/justfont/open-huninn-font)（SIL Open Font License 1.1）
- 地圖：© [OpenStreetMap](https://www.openstreetmap.org/copyright) 貢獻者
- 網頁原型圖示：[Lucide](https://lucide.dev)（ISC License）
- 籤詩：元三大師觀音百籤（淺草寺的觀音百籤），古籍、屬於公有領域；App 只收錄籤號、吉凶和四句籤詩原文
- 解籤：App 用瀏覽器打開[台灣好廟網](https://qiangua.temple01.com/qianshi.php?t=fs_akt100)的解籤頁，沒有複製網站的解說內容
- 星座運勢：App 用內建的句子依星座和日期產生，僅供娛樂；「看更多」連到[科技紫微網](https://astro.click108.com.tw/)
