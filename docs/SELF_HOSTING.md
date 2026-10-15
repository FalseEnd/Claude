# 在自己電腦架設小倆口伺服器

伺服器和資料庫都跑在你自己的電腦上。位置、開銷、經期這些資料只存在這台電腦，不經過任何雲端服務。App 不使用 Firebase，通知直接從你的電腦送到兩支手機。

```
 你的手機 ─┐                         ┌─ 小倆口伺服器（Node.js）
           ├── Wi-Fi 或 Tailscale ──→│
 對方手機 ─┘                         └─ PostgreSQL 資料庫
                                       ↑ 都在你的電腦上
```

有兩種架法，選一種就好：

| | **A. 雙擊啟動（推薦）** | B. Docker |
|---|---|---|
| 要安裝 | Node.js、PostgreSQL（都是免費的一般安裝程式） | Docker Desktop |
| 啟動 | 雙擊 `start-windows.bat` | `docker compose up -d` |
| 適合 | 大部分人，尤其是 Windows | 已經在用 Docker 的人 |

## 1. 需要準備

- 一台盡量保持開機的電腦：Windows 10/11、macOS 或 Linux
- 兩支 Android 8.0 以上的手機
- 這個專案的檔案：在 GitHub 按 **Code › Download ZIP**，解壓縮到好找的地方，例如「文件\小倆口」（資料夾名稱可以有中文）

## 2. 安裝與啟動（A. 雙擊啟動）

### Windows

1. **安裝 Node.js**：到 [nodejs.org](https://nodejs.org/zh-tw/download) 下載標示 **LTS** 的 Windows 安裝程式（.msi），一路按「Next」。「Tools for Native Modules」不用勾。
2. **安裝 PostgreSQL**：到 [PostgreSQL 下載頁](https://www.enterprisedb.com/downloads/postgres-postgresql-downloads) 下載最新版本的 Windows x86-64 安裝程式，一路按「Next」：
   - 設定 **Password** 的那一頁：輸入一組密碼並**記下來**，等一下會用到一次。
   - Port 保持 `5432`，其他都用預設值。
   - 最後的「Stack Builder」不用勾，直接按 Finish。
3. **雙擊小倆口資料夾裡的 `start-windows.bat`**。如果跳出「開啟檔案 - 安全性警告」，按「執行」。第一次啟動會：
   1. 自動下載伺服器需要的套件（大約 1 分鐘，需要網路）。
   2. 問你 PostgreSQL 的管理員密碼（就是第 2 步記下來的那組）。只用這一次來建立小倆口專用的資料庫帳號，不會存起來。
   3. 建立資料庫，把設定存在 `server\.env`。
   4. Windows 跳出「Windows 安全性警訊」時，勾選「**私人網路**」再按「**允許存取**」，手機才連得到。
   5. 自動打開瀏覽器，看到「小倆口伺服器運作中」就成功了。
4. 黑色視窗會列出要填進 App 的網址，例如 `http://192.168.1.23:8080`。

之後每次只要雙擊 `start-windows.bat`。**這個視窗就是伺服器**：關掉它，伺服器就會停止。在視窗裡點一下會讓伺服器暫停（標題出現「選取」），按 Esc 就會繼續。

> 還沒裝 Node.js 或 PostgreSQL 就雙擊也沒關係，啟動程式會打開對應的安裝說明。

### macOS

1. 安裝 [Node.js](https://nodejs.org/zh-tw/download) 的 LTS 版本（.pkg）。
2. 安裝 [Postgres.app](https://postgresapp.com/downloads.html)：拖進「應用程式」，打開後按「Initialize」，並在設定裡勾選登入時自動啟動。
3. 雙擊 `start-mac.command`。第一次 Mac 會擋下網路上下載的檔案：
   - macOS 15 以上：先雙擊一次，出現警告後按「完成」，再到「系統設定 › 隱私權與安全性」，往下捲到「已阻擋 start-mac.command」，按「強制打開」並輸入密碼，然後再雙擊一次。
   - macOS 14 以前：在檔案上按右鍵 ›「打開」›「打開」。
   - 會用終端機的話，也可以執行 `xattr -dr com.apple.quarantine 小倆口資料夾` 一次解除。
4. Postgres.app 不需要密碼。它跳出「允許連線」的視窗時，按「OK」。

用 Homebrew 的話：`brew install node postgresql@17 && brew services start postgresql@17`，再執行 `./start-mac.command`。

### Linux

```sh
sudo apt install postgresql                                  # 或用你的發行版的套件管理員
sudo -u postgres psql -c "\password postgres"                # 幫管理員設定密碼
# Node.js 需要 20 以上（還要有 npm）：發行版的版本太舊的話，用 https://nodejs.org 或 nvm 安裝
./start-linux.sh
```

Fedora／RHEL 預設用 ident 驗證本機連線：請把 `pg_hba.conf`（`/var/lib/pgsql/data`）裡 `127.0.0.1/32` 和 `::1/128` 那兩行的 `ident` 改成 `scram-sha-256`，再 `sudo systemctl reload postgresql`。

## 3. 讓伺服器一直開著

- **開機自動啟動**：
  - Windows：雙擊 `autostart-windows.bat`。之後登入 Windows 時，伺服器會自動在背景啟動，工作列上會有一個縮小的視窗，別關掉它。再執行一次就可以取消。PostgreSQL 安裝時已經設定成開機自動啟動。
  - macOS：在「終端機」執行 `./start-mac.command --install-autostart`（可以把檔案拖進終端機視窗，再輸入後面的參數）。之後登入時會自動打開一個終端機視窗執行伺服器，別關掉它。
  - Linux：`./start-linux.sh --install-autostart`（systemd 使用者服務，在背景執行）。要停止：`systemctl --user stop xiaoliangkou`；取消自動啟動：`./start-linux.sh --remove-autostart`。
- **別讓電腦睡著**：電腦睡眠時伺服器也會暫停，對方就看不到你的位置、收不到通知。Windows：設定 › 系統 › 電源 › 插上電源時「永不」進入睡眠；Mac：系統設定 › 電池 › 選項 › 「在顯示器關閉時防止自動進入睡眠」。

## 4. 讓手機連到你的電腦

### 在家（同一個 Wi-Fi）

1. 伺服器視窗會列出電腦的區網網址，例如 `http://192.168.1.23:8080`。也可以用瀏覽器打開 <http://localhost:8080> 查看。列出好幾個的話，通常是跟手機同一個 Wi-Fi 的那個（常見是 `192.168.` 開頭）；連不上就換另一個試試。
2. 把這個網址填進 App 的「伺服器網址」。
3. 連不上的話，通常是 Windows 防火牆擋住了：
   - 確認 Wi-Fi 的網路類型是「**私人網路**」（設定 › 網路和網際網路 › Wi-Fi › 你的網路）。設成「公用網路」時，防火牆會擋住所有連入的連線。
   - 當初沒有按「允許存取」的話，用系統管理員身分開啟 PowerShell 執行：

     ```powershell
     New-NetFirewallRule -DisplayName "XiaoLiangKou" -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow -Profile Private
     ```

### 出門在外（建議：Tailscale）

只靠家裡 Wi-Fi 的話，離開家就收不到位置與通知。最簡單又安全的做法是 [Tailscale](https://tailscale.com/)（個人使用免費）：

1. 在電腦和兩支手機都安裝 Tailscale 並登入。對方可以用自己的帳號，再由你在 Tailscale 管理頁把這台電腦分享給對方。
2. Tailscale 會給電腦一個 `100.x.y.z` 的位址，伺服器視窗也會標示出來。App 的伺服器網址改填 `http://100.x.y.z:8080`，在家或在外面都能用。

> 不建議在路由器上直接把 8080 埠開放到網際網路：連線沒有加密。Tailscale 的連線本身是加密的。

## 5. 安裝 App 與第一次設定

1. 把 `XiaoLiangKou.apk` 傳到手機安裝（需要允許「安裝不明來源的應用程式」）。
2. 打開 App，選「連接我的伺服器」，填入上一步的網址，按「測試連線」。
3. 兩個人各自註冊帳號。伺服器預設最多 2 個帳號，滿了之後別人就無法註冊（`server/.env` 的 `MAX_USERS`）。
4. 一個人產生配對碼，另一個人輸入，接著各自選擇要分享哪些資料。
5. 依 App 的引導開啟權限：
   - **位置：一律允許**：才能在背景分享位置與偵測標點進出。
   - **通知**：才收得到抱抱、抵達通知。
   - **電池最佳化：不限制**：不然手機休眠時會收不到通知。小米、OPPO、vivo、三星等手機還要在系統設定裡允許「自動啟動」或「背景活動」。
   - **通知存取權**（選用）：自動記帳讀取 LINE Pay、街口、銀行的付款通知。
   - **體能活動**（選用）：計算步數。

App 還有「先看看示範」模式，不需要伺服器就能預覽所有畫面。

## 6. 備份與還原

**自動備份**：伺服器開著的時候，每天會自動備份一次到小倆口資料夾的 `backups`，保留最近 14 份。

**手動備份**：Windows 雙擊 `backup-windows.bat`；macOS / Linux 執行 `./start-mac.command --backup`（或 `./start-linux.sh --backup`）。

電腦壞掉時，存在同一台電腦的備份也會一起不見。建議在 `server/.env` 加一行，把備份直接存到雲端硬碟同步的資料夾：

```
BACKUP_DIR=C:\Users\你的名字\OneDrive\小倆口備份
```

**還原**：

1. 先關掉伺服器視窗。
2. Windows：把備份檔（`.sql`）**拖曳到 `restore-windows.bat` 上**。macOS / Linux：`./start-mac.command --restore backups/檔名.sql`。
3. 確認後輸入 PostgreSQL 管理員密碼。還原前會先自動備份目前的資料，選錯檔案也救得回來。
4. 完成後再雙擊 `start-windows.bat`。

**搬到新電腦**：在新電腦照第 2 節安裝並啟動一次 → 關掉伺服器視窗 → 用上面的方法還原舊電腦的備份檔。順便把舊電腦的 `server/.app-secret` 複製過去，兩支手機就不用重新登入。

### 更新到新版

1. 關掉伺服器視窗。
2. 下載新版的 ZIP，解壓縮後**覆蓋**原本的小倆口資料夾（`server/.env`、`server/.app-secret`、`backups` 都會保留）。
   想放在新的資料夾也可以，但要把這三樣從舊資料夾複製過去，不然新資料夾會重新設定資料庫帳號，舊資料夾就不能用了。
3. 雙擊啟動檔。第一次會自動更新伺服器套件，資料庫也會自動升級。

## 7. 忘記密碼

**忘記 App 的密碼**：Windows 雙擊 `reset-password-windows.bat`，選擇帳號後會產生一組臨時密碼（macOS / Linux：`./start-mac.command --reset-password 帳號`）。用臨時密碼登入後，到 App 的「我的 › 伺服器與帳號 › 改密碼」換成自己的密碼。

**忘記 PostgreSQL 管理員的密碼**（只有重新設定或還原時才需要）：

1. 用記事本（以系統管理員身分）打開 `C:\Program Files\PostgreSQL\版本\data\pg_hba.conf`。
2. 把 `127.0.0.1/32` 和 `::1/128` 那兩行最後的 `scram-sha-256` 暫時改成 `trust`，存檔。
3. 按 Win 鍵搜尋「服務」，把 `postgresql-x64-版本` 重新啟動。
4. 開啟命令提示字元執行：`"C:\Program Files\PostgreSQL\版本\bin\psql" -U postgres -c "\password postgres"`，設定新密碼。
5. 把 `pg_hba.conf` 改回 `scram-sha-256`，再重新啟動一次服務。

## 8. 進階設定

啟動程式的設定在 `server/.env`（第一次啟動時自動產生），改完重新啟動伺服器就會生效：

| 設定 | 預設 | 說明 |
|---|---|---|
| `PORT` | `8080` | 伺服器埠號 |
| `MAX_USERS` | `2` | 最多幾個帳號可以註冊 |
| `BACKUP_DIR` | 小倆口資料夾的 `backups` | 備份存放位置 |
| `AUTO_BACKUP_KEEP` | `14` | 自動備份保留幾份；`0` 關閉自動備份 |
| `DATABASE_URL` | 自動產生 | 小倆口專用的資料庫帳號 `xlk`，密碼是隨機產生的 |

啟動檔可以加參數，例如在命令提示字元執行 `start-windows.bat --setup`：

| 參數 | 做什麼 |
|---|---|
| `--setup` | 重新設定資料庫帳號（重灌 PostgreSQL 之後用；平常偵測到密碼不對也會自動重新設定） |
| `--port 埠號` | 這次用其他埠號啟動（搭配 `--setup` 會記住） |
| `--pg-port 埠號` | PostgreSQL 不是用 5432 埠時指定（預設會自動試 5432–5434） |
| `--backup`、`--restore 檔案`、`--reset-password 帳號` | 備份、還原、重設 App 密碼 |
| `--install-autostart`、`--remove-autostart` | 開機自動啟動、取消 |
| `--help` | 列出所有參數 |

想直接查看資料，可以用 PostgreSQL 附的 pgAdmin 連 `localhost:5432`，帳號 `xlk`，密碼在 `server/.env` 的 `DATABASE_URL` 裡（`xlk:` 和 `@` 中間那一段）。

## 9. B. 使用 Docker

已經有 [Docker Desktop](https://www.docker.com/products/docker-desktop/)（Windows / macOS）或 Docker Engine（Linux）的話：

1. （選用）把 `.env.example` 複製成 `.env`，修改資料庫密碼。不建立 `.env` 也能用預設值啟動。
2. 在專案資料夾開啟終端機，執行 `docker compose up -d`。第一次會下載映像檔，大約需要幾分鐘。
3. 用瀏覽器打開 <http://localhost:8080>。Docker 看不到電腦的網路位址，請用 `ipconfig`（Windows）查「IPv4 位址」，App 填 `http://那個位址:8080`。

之後電腦開機、Docker Desktop 啟動時，伺服器會自動跟著啟動。

| 做什麼 | 指令 |
|---|---|
| 停止 | `docker compose down`（加上 `-v` 會**刪除資料庫**） |
| 看紀錄 | `docker compose logs -f server` |
| 更新到新版 | 下載新版後執行 `docker compose up -d --build` |
| 備份 | `docker compose exec db pg_dump -U xlk --no-owner -f /tmp/backup.sql xiaoliangkou` 再 `docker compose cp db:/tmp/backup.sql ./backup.sql` |
| 還原到全新的資料庫 | `docker compose up -d db`、`docker compose cp ./backup.sql db:/tmp/backup.sql`、`docker compose exec db psql -U xlk -d xiaoliangkou -f /tmp/backup.sql`、`docker compose up -d` |

想從 Docker 版改成雙擊啟動：用上表備份，執行 `docker compose down` 停掉 Docker 版，照第 2 節安裝並啟動一次，關掉伺服器視窗後用第 6 節的方法還原 `backup.sql`。

## 10. 疑難排解

| 狀況 | 怎麼辦 |
|---|---|
| 「這台電腦好像還沒安裝 PostgreSQL」 | 照打開的說明安裝 PostgreSQL，再啟動一次 |
| 「PostgreSQL 已經安裝了，但是沒有在執行」 | Windows：Win 鍵搜尋「服務」，找到 `postgresql-x64-版本`，按右鍵 ›「啟動」，並把啟動類型設成「自動」。Mac：打開 Postgres.app |
| 「三次都登入失敗」 | 確認是安裝 PostgreSQL 時設定的密碼；忘記了請看第 7 節 |
| 「PostgreSQL 不接受用密碼登入」 | 照訊息修改 `pg_hba.conf`（多半是 Fedora／RHEL 的 ident 設定） |
| 「無法使用備份資料夾」 | 隨身碟或雲端硬碟沒接上，或 `server/.env` 的 `BACKUP_DIR` 打錯；自動備份失敗不會影響伺服器 |
| 「找不到 npm」 | Linux 的 Node.js 套件沒附 npm：`sudo apt install npm`，或從 nodejs.org 安裝 |
| 雙擊後顯示「Please extract the ZIP file first」 | 還在 ZIP 裡面：在 ZIP 檔上按右鍵 ›「解壓縮全部」，到解壓縮出來的資料夾再雙擊 |
| 「埠號 8080 已經被使用」 | 可能有別的程式（或 Docker 版的小倆口）占用，在 `server/.env` 把 `PORT` 改成例如 `8090`，App 的網址也要跟著改 |
| 「下載套件失敗」 | 第一次啟動需要網路；確認連上網路後再試一次 |
| App 顯示「連不到伺服器」 | 網址開頭是 `http://` 不是 `https://`；伺服器視窗有開著；手機和電腦在同一個 Wi-Fi 或都開著 Tailscale；防火牆與網路類型見第 4 節 |
| 伺服器好像卡住、App 一直轉圈 | 伺服器視窗標題出現「選取」時，按 Esc |
| 手機在睡眠時收不到通知 | 電池最佳化改成「不限制」，並允許自動啟動；電腦不要進入睡眠 |
| 對方位置很久沒更新 | 對方的位置權限是否為「一律允許」、是否暫停分享、電腦是否開機 |
| 註冊時顯示「已經滿了」 | `server/.env` 調高 `MAX_USERS` 後重新啟動伺服器 |
