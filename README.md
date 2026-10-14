# PerpPilot 永續 — 加密貨幣永續合約分析與自動交易 App（Android）

PerpPilot 是一款 Android 原生 App（Kotlin + Jetpack Compose），提供：

- **永續合約分析**：K 線圖（EMA20/50、RSI、成交量）、多因子綜合評分、資金費率、持倉量、多空人數比、趨勢強度（ADX）、波動度。
- **內建趨勢策略**：日線判斷趨勢、4 小時找進場（回踩續勢／放量突破），ATR 止損、保本、移動止損。
- **OKX 自動交易**：自動計算倉位、設定槓桿、市價開倉並同時在交易所掛止損單；之後持續上移止損。支援 **模擬盤** 與實盤、單向／雙向持倉模式、逐倉／全倉。
- **完整風控**：單筆風險 %、最大持倉數、總風險上限、每日虧損上限、回撤熔斷、連虧冷卻、止損距離 vs. 強平距離檢查、不追價。
- **TradingView 串接**：TradingView 警報 → Webhook → ntfy 中繼 → App 風控檢查 → OKX 下單。附 Pine Script v6 腳本（與 App 內建策略同邏輯）。
- **事件合約（OKX Event Contracts）**：以即時波動率計算每份合約的理論機率，市價明顯偏離時通知；可選自動下注（預設關閉）。
- **通知**：Android 系統通知（訊號／開平倉／止損／風控／錯誤分頻道），可同步 Telegram。
- **回測**：App 內與命令列回測（含手續費、滑價、資金費，訊號收盤產生、下一根開盤成交，無未來函數）。

> ⚠️ **沒有任何策略能保證「穩定獲利」。** 下方是本專案實際用 OKX 歷史資料跑出來的結果，包含失敗的部分。請先用模擬盤，並只投入可承受全部損失的資金。

---

## 回測結果（誠實版）

資料：OKX 永續合約歷史 K 線；成本：taker 0.05%／邊、滑價 0.02%／邊、資金費 0.01%/8h（不論多空一律扣，偏保守）；單筆風險 1%、5x 逐倉、最多 3 倉。

### 預設策略（4H 進場／日線趨勢，ADX ≥ 30，止損 2 ATR，移動止損 4 ATR）
8 個主流合約（BTC ETH SOL XRP DOGE BNB LINK AVAX），2022-10 → 2026-10（4 年）：

| 指標 | 數值 |
|---|---|
| 總報酬 / 年化 | **+50.4% / +10.7%** |
| 最大回撤 | 18.5% |
| 盈虧比 PF / Sharpe | 1.29 / 0.61 |
| 交易數 / 勝率 | 331 / 52.3% |
| 前兩年 / 後兩年 | +10.3%（PF 1.14）/ +36.3%（PF 1.40） |
| 虧損的季度 | **17 季中 8 季虧損** |
| 未參與調參的 5 個合約（ADA LTC DOT TRX BCH） | +39.1%（PF 1.37，回撤 21.9%），但前兩年 **−8.7%**、後兩年 +52.3% |

**解讀**：這是典型的趨勢跟隨系統：靠少數大趨勢賺錢，在震盪期持續小額虧損。單看 BTC 四年是 −3.4%，組合是靠分散在多個幣種才轉正。它有正期望值的證據，但**不穩定**。

### 試過但沒有採用的
| 嘗試 | 結果 |
|---|---|
| 1 小時進場／4 小時趨勢 | 4 年 +22%，但最大回撤 **50%**、PF 1.03；交易太多，手續費＋資金費吃掉大部分利潤 |
| 只在美股開盤 08:30–11:30 ET 交易（1H） | **−40%**（PF 0.78）。最熱鬧的時段雜訊最大、止損最常被掃 |
| 只在美股盤中 09:30–16:00 ET 交易（4H） | +40.5%、回撤降到 14.8%、PF 1.36：品質略好但報酬較少，列為可選項 |
| 事件合約波動率模型（7 天，BTC/ETH/SOL 15 分鐘） | 與成交價同步時看似 +14%～+33%；**模型資訊延遲 1 分鐘後 BTC −5%、ETH −34%**。優勢來自時間差，手機無法持續取得 → 只做通知，自動下注預設關閉 |

完整數據與方法見 [docs/STRATEGY.md](docs/STRATEGY.md)。

---

## 安裝

1. 下載 APK（GitHub Actions 每次推送都會建置，於 Actions → 最新一次執行 → Artifacts 取得 `PerpPilot-apk`），或自行建置（見下）。
2. 手機允許「安裝未知來源 App」，安裝後開啟並允許通知。
3. 系統設定中將 PerpPilot 的電池最佳化設為「不受限制」，背景排程才不會被延遲。

需求：Android 8.0（API 26）以上。

## OKX 設定（先用模擬盤）

1. OKX 網頁 → 交易 → **模擬交易** → 個人中心 → 建立 **模擬盤 API**（權限：讀取＋交易；**絕對不要勾提幣**）。
2. 帳戶模式需為「單幣種保證金」或以上（「簡單模式」不能交易合約）。
3. App → 設定 → OKX 交易所：選「模擬盤」，填入 API Key / Secret / Passphrase → 測試連線。
4. App → 機器人：開啟「自動開倉」。每 15 分鐘自動執行一次，也可按「立即執行一次」。
5. 至少跑數週並對照回測，再考慮實盤。實盤需使用另一組實盤 API，並在 App 中確認風險聲明。

金鑰以 Android Keystore（AES-256-GCM）加密存放在手機本機，不會上傳也不會備份。

**手機休眠／斷網時**：每筆倉位在交易所都有止損單，帳戶仍受保護；只是移動止損與新訊號會延後到下一次執行。

## TradingView 串接

```
TradingView 警報 ──Webhook──▶ ntfy 中繼（ntfy.sh 或自架）──▶ App（每 15 秒）──▶ 風控 ──▶ OKX
```

1. App → 設定 → TradingView：按「產生 Webhook 位址與密鑰」。
2. 在 TradingView 加入 [`tradingview/PerpPilot_Trend.pine`](tradingview/PerpPilot_Trend.pine)，在輸入中填入 App 的密鑰。
3. 建立警報：條件選本策略「僅 alert() 函數呼叫」，勾選 Webhook，貼上 App 顯示的 URL。
4. 回 App 開啟「啟用 TradingView 訊號」，會常駐前景服務即時接收。

也可用自己的腳本，警報訊息使用以下 JSON（欄位可省略）：
```json
{"secret":"你的密鑰","action":"long","symbol":"{{ticker}}","price":{{close}},"stop":12345.6,"tp":13000,"risk":0.5,"time":"{{timenow}}"}
```
`action`：`long` `short` `buy` `sell` `close` `close_long` `close_short`；策略警報可直接用 `{{strategy.order.action}}` 搭配 `"position":"{{strategy.market_position}}"`（flat 會視為平倉）。

安全：密鑰不符、超過有效秒數、不在觀察清單、止損方向錯誤的訊號都會被拒絕；所有訊號一律經過與內建策略相同的風控。

注意：
- TradingView Webhook 需要付費方案。
- ntfy.sh 免費版有每日訊息額度，且 TradingView 的 Webhook 來自少數共用 IP，繁忙時可能被拒（HTTP 429）。App 產生的 `up` 開頭主題可讓支援的伺服器改以訂閱端計額度；需要可靠度請自架 ntfy：`docker run -p 80:80 binwiederhier/ntfy serve`，再於 App 中填入你的伺服器位址。

## 事件合約

OKX 事件合約（BTC/ETH/SOL 5 分鐘／15 分鐘漲跌、每日價格高於等）。每張猜對拿回 1 USDT，價格即市場隱含機率。App 用指數的即時波動率計算理論機率（無漂移的對數常態模型，等同 N(d2)），扣除手續費（K × P × (1−P)，預設 K=0.07，請依你的費率調整）後，期望值 ≥ 5 美分才提示。回測顯示此優勢無法穩定取得（見上表），因此建議只用通知。事件合約不對美國、英國、歐洲經濟區用戶開放。

## 架構

```
core/   純 Kotlin（JVM）：指標、策略、風控、回測、OKX v5 客戶端、交易引擎、事件合約、TradingView 解析、ntfy
app/    Android：Compose UI、WorkManager（15 分鐘）、前景服務（即時）、Keystore 加密、通知、Telegram
cli/    命令列：真實資料回測、參數穩健度掃描、事件合約回測、線上冒煙測試
tradingview/  Pine Script v6
```

交易引擎每輪流程：同步帳戶 → 熔斷檢查 → 對帳（交易所已平倉者寫入紀錄）→ 管理既有倉位（保本／移動止損／趨勢破壞出場，同步交易所止損單）→ 尋找新訊號 → 風控 → 下單（附止損）→ 通知。

## 建置與測試

需要 JDK 17+ 與 Android SDK（compileSdk 36）。

```bash
./gradlew :core:test                 # 單元測試（指標、簽名、風控、策略、回測無未來函數、引擎、TradingView、事件合約）
./gradlew :app:assembleDebug         # APK：app/build/outputs/apk/debug/app-debug.apk

# 用 OKX 真實歷史資料回測（會快取在 data-cache/）
./gradlew :cli:run --args="--symbols BTC-USDT-SWAP,ETH-USDT-SWAP,SOL-USDT-SWAP --days 1460 --ltf 4H --htf 1Dutc --split 0.5"
./gradlew :cli:run --args="--symbols BTC-USDT-SWAP,ETH-USDT-SWAP --days 1460 --ltf 4H --htf 1Dutc --sweep"   # 參數穩健度
./gradlew :cli:run --args="--events --days 7 --lag 1"     # 事件合約回測
./gradlew :cli:run --args="--smoke"                        # 線上 API 冒煙測試
```

## 風險聲明

本專案是技術分析與自動化工具，不構成投資建議，不保證獲利。永續合約具槓桿，可能在短時間內損失全部保證金；交易所 API、網路、手機系統都可能故障。過去的回測績效不代表未來。使用者需自行承擔所有交易決策與結果。
