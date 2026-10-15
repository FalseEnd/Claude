# 在自己電腦架設小倆口伺服器

伺服器與資料庫都跑在你自己的電腦上，所有位置、開銷、經期資料只存在這台電腦，不經過任何雲端服務。App 不使用 Firebase，通知直接從你的電腦送到兩支手機。

```
 你的手機 ─┐                         ┌─ 小倆口伺服器（Node.js）
           ├── Wi-Fi 或 Tailscale ──→│
 對方手機 ─┘                         └─ PostgreSQL 資料庫（Docker volume）
                                       ↑ 都在你的電腦上
```

## 1. 需要準備

- 一台盡量保持開機的電腦：Windows 10/11、macOS 或 Linux 都可以
- [Docker Desktop](https://www.docker.com/products/docker-desktop/)（Windows / macOS）或 Docker Engine（Linux）
- 兩支 Android 8.0 以上的手機

## 2. 啟動伺服器

1. 下載這個專案（`git clone`，或在 GitHub 按 Code › Download ZIP 後解壓縮）。
2. （選用）把 `.env.example` 複製成 `.env`，修改資料庫密碼。不建立 `.env` 也能用預設值啟動。
3. 在專案資料夾開啟終端機（Windows：在資料夾空白處按右鍵 › 在終端機中開啟），執行：

   ```sh
   docker compose up -d
   ```

   第一次會下載映像檔，大約需要幾分鐘。
4. 用瀏覽器打開 <http://localhost:8080>，看到「小倆口伺服器運作中」就成功了。

之後電腦開機、Docker Desktop 啟動時，伺服器會自動跟著啟動（`restart: unless-stopped`）。

常用指令：

| 做什麼 | 指令 |
|---|---|
| 停止 | `docker compose down` |
| 看紀錄 | `docker compose logs -f server` |
| 更新到新版 | `git pull` 後執行 `docker compose up -d --build` |

## 3. 讓手機連到你的電腦

### 在家（同一個 Wi-Fi）

1. 查電腦的區網位址。Windows：開啟命令提示字元輸入 `ipconfig`，找到 Wi-Fi 或乙太網路的「IPv4 位址」，例如 `192.168.1.23`。macOS：系統設定 › 網路。
2. App 的「伺服器網址」填 `http://192.168.1.23:8080`。
3. 連不上的話，通常是 Windows 防火牆擋住了。用系統管理員身分開啟 PowerShell 執行：

   ```powershell
   New-NetFirewallRule -DisplayName "XiaoLiangKou" -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow
   ```

### 出門在外（建議：Tailscale）

只靠家裡 Wi-Fi 的話，離開家就收不到位置與通知。最簡單又安全的做法是 [Tailscale](https://tailscale.com/)（個人使用免費）：

1. 在電腦和兩支手機都安裝 Tailscale，並登入。對方可以用自己的帳號，再由你在 Tailscale 管理頁把這台電腦分享給對方。
2. Tailscale 會給電腦一個 `100.x.y.z` 的位址。App 的伺服器網址改填 `http://100.x.y.z:8080`，在家或在外面都能用。

> 不建議在路由器上直接開放 8080 埠到網際網路：連線沒有加密。Tailscale 的連線本身是加密的。

## 4. 安裝 App 與第一次設定

1. 把 `XiaoLiangKou.apk` 傳到手機安裝（需要允許「安裝不明來源的應用程式」）。
2. 打開 App，選「連接我的伺服器」，填入上一步的網址，按「測試連線」。
3. 兩個人各自註冊帳號。伺服器預設最多 2 個帳號，滿了之後別人就無法註冊（`.env` 的 `MAX_USERS`）。
4. 一個人產生配對碼，另一個人輸入，接著各自選擇要分享哪些資料。
5. 依 App 的引導開啟權限：
   - **位置：一律允許**：才能在背景分享位置與偵測標點進出。
   - **通知**：才收得到抱抱、抵達通知。
   - **電池最佳化：不限制**：不然手機休眠時會收不到通知。小米、OPPO、vivo、三星等手機還要在系統設定裡允許「自動啟動」或「背景活動」。
   - **通知存取權**（選用）：自動記帳讀取 LINE Pay、街口、銀行的付款通知。
   - **體能活動**（選用）：計算步數。

App 還有「先看看示範」模式，不需要伺服器就能預覽所有畫面。

## 5. 備份與還原

所有資料都在 Docker volume `xiaoliangkou_db-data` 裡。建議每週備份一次：

```sh
docker compose exec db pg_dump -U xlk -f /tmp/backup.sql xiaoliangkou
docker compose cp db:/tmp/backup.sql ./backup.sql
```

還原到一個全新的伺服器：

```sh
docker compose up -d db
docker compose cp ./backup.sql db:/tmp/backup.sql
docker compose exec db psql -U xlk -d xiaoliangkou -f /tmp/backup.sql
docker compose up -d
```

想直接查看資料，可以用 pgAdmin 或 DBeaver 連 `localhost:5433`，帳號 `xlk`，密碼是 `.env` 的 `DB_PASSWORD`（預設 `xiaoliangkou`）。這個埠只開放給電腦本身，手機與區網其他裝置連不到。

> 用 `docker compose down -v` 會**刪除資料庫**，平常停止只要 `docker compose down`。

## 6. 不使用 Docker

1. 安裝 Node.js 20 以上與 PostgreSQL 16。
2. 建立資料庫（伺服器第一次啟動會建立 `anon`、`authenticated` 兩個角色，所以連線帳號需要 CREATEROLE 權限，最簡單是用 `postgres` 帳號）：

   ```sh
   createdb -U postgres xiaoliangkou
   ```

3. 啟動：

   ```sh
   cd server
   npm install
   DATABASE_URL=postgres://postgres:你的密碼@localhost:5432/xiaoliangkou npm start
   ```

   Windows PowerShell 用 `$env:DATABASE_URL="postgres://..."; npm start`。

## 7. 疑難排解

| 狀況 | 檢查 |
|---|---|
| App 顯示「連不到伺服器」 | 網址開頭是 `http://` 不是 `https://`；手機和電腦在同一個 Wi-Fi 或都開著 Tailscale；防火牆允許 8080 |
| 手機在睡眠時收不到通知 | 電池最佳化改成「不限制」，並允許自動啟動 |
| 對方位置很久沒更新 | 對方的位置權限是否為「一律允許」、是否暫停分享、電腦是否開機 |
| 註冊時顯示「已經滿了」 | `.env` 調高 `MAX_USERS` 後執行 `docker compose up -d` |
| 忘記密碼 | 在電腦上執行 `docker compose exec db psql -U xlk xiaoliangkou`，刪除該帳號後重新註冊（會一併刪除該帳號的資料） |
