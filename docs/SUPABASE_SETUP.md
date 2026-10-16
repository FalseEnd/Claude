# 設定 Supabase（免費）

小倆口的資料存在你們自己的 [Supabase](https://supabase.com) 專案裡。Supabase 是雲端的 PostgreSQL 資料庫服務，免費方案就夠兩個人用。設定好之後，電腦不用開著，手機在哪裡都能連線。

整個設定大約 10 分鐘，只需要做一次。另一半不用做這些步驟，只要點你傳過去的邀請連結就好。

```
 你的手機 ─┐                      ┌─ 登入（Supabase Auth）
           ├──── 網際網路（HTTPS）──→├─ 資料庫（PostgreSQL，每一筆資料都有權限規則）
 對方手機 ─┘                      ├─ 照片與語音（Supabase Storage，不公開）
                                  └─ 即時通知（Supabase Realtime）、排程提醒（pg_cron）
```

## 1. 註冊 Supabase、建立專案

1. 打開 <https://supabase.com>，按「Start your project」，用 GitHub 帳號或 Email 註冊。
2. 如果畫面先請你建立組織（Create a new organization）：**Name** 隨意，**Type** 選 **Personal**，**Plan** 選 **Free**（$0），按「Create organization」。
3. 接著建立專案（Create a new project，之後要再建可以按「New project」）：
   - **Project name**：隨意，例如 `xiaoliangkou`
   - **Database password**：按「Generate a password」並記下來（平常用不到，忘記也能在後台重設）
   - **Region**：選 **Asia-Pacific**（亞太，離台灣比較近）。下面如果有「Need a specific region?」，也可以點開改選 **Northeast Asia (Tokyo)** 或 **Southeast Asia (Singapore)**
   - **Security**：**Enable Data API** 要勾著（預設就勾好了，App 透過它讀寫資料）。另外兩個選項（Automatically expose new tables、Enable automatic RLS）勾不勾都可以，維持預設就好
4. 按「Create new project」，等一兩分鐘直到專案準備好。

## 2. 建立資料表

1. 打開這個專案的 [`supabase/setup.sql`](../supabase/setup.sql)，在 GitHub 上按檔案右上角的 **Raw**，會打開一個純文字頁面，從這個頁面全選、複製。
   > 一定要從 **Raw** 純文字頁面複製。從其他地方（例如聊天軟體、筆記 App、網頁預覽）複製，`$` 可能會變成 `\$`，或換行被吃掉，執行時就會出現 syntax error。
2. 回到 Supabase 後台，左邊選 **SQL Editor**，會出現一個空白的編輯區（沒有的話，按左上角的 **＋** › **Create a new snippet**）。
3. 把剛剛複製的內容全部貼上，按編輯區右下方的 **Run**。
4. 跳出「Potential issue detected」的提醒是正常的（檔案裡有定期清掉過期通知的指令），按 **Run query** 繼續。
5. 等幾秒鐘，下方結果出現「設定完成 🎉 回到說明文件做第 3 步」就完成了。如果出現紅色的 Error，代表沒有設定成功，可以把錯誤訊息截圖下來問人。

> 只要執行一次。整個檔案是「全部成功，或全部不算」：如果不小心又按了一次，會出現「already exists」的錯誤，不會影響已經建立好的資料。

## 3. 關掉 Email 確認信

小倆口用 Email 和密碼登入。Supabase 預設會寄確認信，但內建的寄信服務每小時只能寄 2 封，而且只寄給 Supabase 團隊成員的信箱（另一半根本收不到），所以請關掉：

1. 左邊選 **Authentication**，再選 **Configuration** 底下的 **Sign In / Providers**。
2. 在最上面的 **User Signups** 區塊，把 **Confirm email** 關掉，按 **Save changes**。

## 4. 複製網址和金鑰

1. 按專案頁面上方的 **Connect**，裡面就有網址和金鑰。也可以分開找：
   - 網址：**Project Settings › Data API**，**Overview** 裡的 **API URL**
   - 金鑰：**Project Settings › API Keys**，**Publishable key** 那一格
2. 記下兩樣東西：
   - **Project URL**（API URL）：像 `https://abcdefghijkl.supabase.co`
   - **Publishable key**：`sb_publishable_` 開頭的那一串（舊專案的 **anon** key 暫時也能用，但 Supabase 預計 2026 年底停用舊金鑰，請優先用 Publishable key）

> 這把金鑰本來就是設計給 App 用的公開金鑰，放在手機裡沒關係。真正保護資料的是資料庫裡的權限規則：每個人只看得到自己的資料，和對方願意分享給你的部分。
> **不要**把 `sb_secret_` 開頭的金鑰（或 service_role key）填進 App，那把可以繞過所有權限。

最簡單的方式：把網址和金鑰用 LINE 傳給自己，再在手機上複製貼上。

## 5. 在 App 裡連線

1. 打開小倆口 App，按下面的「使用其他 Supabase 專案」。（App 預設連到作者的專案，直接按「開始使用」會連到那裡）
2. 貼上 **Project URL** 和 **Publishable key**，按「測試連線」。
3. 用 Email、密碼和暱稱註冊。
4. 到「我的 › Supabase 與帳號」，按「分享給另一半」，用 LINE 傳出邀請連結。
5. 另一半點連結就會打開小倆口，確認畫面上的網址是你們的（`xxxx.supabase.co`）後按「幫我填好」，再按「測試連線」。註冊之後，一個人產生配對碼，另一個人輸入，就完成配對了。

為了你們的隱私，一個 Supabase 專案最多只能註冊 2 個帳號。

## 更新資料庫（2026-10 新功能：個人資料、日曆紀錄、備忘錄）

App 0.2 加了個人資料（身高、血型、喜歡的東西…）、只有自己看得到的備註、在日曆記錄那天的生活（可以放照片和語音）、備忘錄和桌面小工具。如果你們在這之前就照第 2 步執行過 setup.sql，要再執行一個更新檔，才能用這些新功能（還沒更新時，App 會提醒「資料庫需要更新」）：

1. 打開這個專案的 [`supabase/upgrade-v2.sql`](../supabase/upgrade-v2.sql)，跟第 2 步一樣按 **Raw**，從純文字頁面全選、複製。
2. 到 Supabase 後台的 **SQL Editor**，按左上角的 **＋** › **Create a new snippet** 開一個空白的編輯區，貼上後按 **Run**。
3. 如果跳出「Potential issue detected」的提醒，是因為檔案裡會先移除舊的權限規則再重新建立，按 **Run query** 繼續。
4. 下方結果出現「更新完成 🎉」就好了，重新打開 App 就能用新功能。

> 只要執行一次，兩個人其中一個做就好。不小心多跑一次也沒關係，已經存的資料不會受影響。
> 如果是在這次更新之後才建立的專案，第 2 步的 setup.sql 已經包含這些內容，不用再執行。

照片和語音存在 Supabase 的 **Storage**（檔案儲存），更新檔會自動建立一個不公開的 `media` 空間（之前自己建過叫 `media` 的空間也會改成不公開）：日曆和共用備忘錄的照片只有你們兩個看得到，私人備忘錄的照片只有自己看得到；解除配對後，原本共用的照片雙方都讀不到了。

解除配對或刪除帳號時，照片和語音檔不會跟著刪除，還會留在 **Storage** › `media` 佔用免費額度；想清空的話，可以到那裡手動刪除。

## 免費方案夠用嗎？

| 項目 | 免費額度 | 小倆口大約用多少 |
|---|---|---|
| 資料庫 | 500 MB | 一年大約幾 MB |
| 傳輸量 | 每月 5 GB | 每月幾十 MB |
| 即時連線 | 同時 200 條、每月 200 萬則訊息 | 2 條、每月幾萬則 |
| 檔案儲存（照片、語音） | 1 GB | 一張照片約 300 KB、一分鐘語音約 500 KB，大約可以放三千張照片 |

要注意的是，**大約一週沒人使用，免費專案會被暫停**（暫停前 Supabase 會先寄信提醒）。資料不會不見，到 Supabase 後台打開專案，按「Resume project」就能恢復。但暫停太久（目前官方寫 90 天）就不能在後台一鍵恢復，只能下載備份，所以收到通知請早點恢復。平常每天在用的話不會發生。

## 常見問題

| 狀況 | 怎麼辦 |
|---|---|
| App 說「金鑰不正確」 | 確認貼的是 Publishable key（或 anon key），前後沒有多餘的空白 |
| App 說「資料庫還沒設定好」 | 第 2 步的 setup.sql 還沒執行，或執行時有錯誤 |
| App 說「資料庫需要更新」 | 照上面「更新資料庫」那一節執行 upgrade-v2.sql |
| setup.sql 已經執行過，App 還是說「資料庫還沒設定好」 | 到 **Project Settings › Data API**：**Overview** 裡的 **Enable Data API** 要開著，**Settings** 裡的 **Exposed schemas** 要有 `public` |
| 註冊後要求收確認信 | 第 3 步的 Confirm email 沒有關掉 |
| 註冊時說「已經有兩個人了」 | 這個專案已經有 2 個帳號。要換人的話，到 **Authentication › Users** 刪掉不用的帳號（會一併刪除他的資料，但照片和語音檔要到 **Storage** › `media` 手動刪除）。如果那個帳號還在配對中，留下來的人會自動解除配對並收到通知，之後就能和新帳號重新配對 |
| 忘記 App 密碼 | 到 **SQL Editor** 執行下面這行（把 Email 和新密碼換掉，新密碼至少 6 個字）。下方出現你的 Email 就是改好了；顯示「No rows returned」代表 Email 打錯了。再用新密碼登入，然後到 App 裡改成自己的密碼：<br>`update auth.users set encrypted_password = extensions.crypt('新密碼', extensions.gen_salt('bf')) where lower(email) = lower('你的Email') returning email;` |
| 專案被暫停了 | Supabase 後台會顯示「Project … is paused」，按「Resume project」，等幾分鐘就好 |
| 想看資料 | Supabase 後台的 **Table Editor** 可以看到每張表，照片和語音在 **Storage** 的 `media` 裡 |
| 想備份 | 免費方案沒有自動備份。可以在 **Table Editor** 按表名旁邊的 **⋯** › **Export data** › **Export table as CSV**，把重要的表存下來，或升級 Pro 方案（每天自動備份） |
