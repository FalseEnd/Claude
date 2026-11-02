# 滑鼠游標（MouseCursor）

在 **MapleStory Worlds** 等會把系統游標藏起來的 Android 遊戲裡，強制在畫面最上層畫出一個滑鼠游標。**不需要 root。**

## 為什麼一般 App 做不到？

遊戲用 `PointerIcon.TYPE_NULL`（或 Pointer Capture）把游標藏起來後，滑鼠事件只會送給遊戲本身，
一般 App 完全拿不到滑鼠座標。本 App 透過 **Shizuku** 以 `shell` 身分（免 root）直接讀 `/dev/input/eventX`。

### 接管模式（預設，推薦）

思路參考 [KeySync](https://github.com/aka-munan/keysync)：**不去猜系統游標在哪，而是自己當游標。**

1. 對滑鼠裝置下 `EVIOCGRAB` 獨佔，系統收不到這支滑鼠的事件。
2. 由 Shizuku 服務自己累加位移、算出游標位置。
3. 用 `IInputManager.injectInputEvent`（shell 有 `INJECT_EVENTS` 權限）在該位置注入滑鼠事件
   （左/右/中鍵、滾輪、懸停，順序與 InputReader 相同；側鍵轉成返回/前進），或選「觸控」送出手指點擊。
4. 懸浮層畫的游標就是注入點擊的座標，**移動再快、切換 App、旋轉螢幕都不會跑掉**。

和 KeySync 的差異：KeySync 用 Pointer Capture，需要讓懸浮窗取得焦點，鍵盤也會被搶走；
本 App 只獨佔滑鼠裝置，**鍵盤照常送給遊戲**。鍵鼠一體（同一個 event 節點）的接收器會被偵測出來並拒絕接管，避免鍵盤失效。

安全機制：先試注入成功才獨佔；App 或 Shizuku 一結束，fd 關閉即自動解除獨佔，滑鼠立刻還給系統。

### 估算模式（接管失敗時的備案）

滑鼠照常由系統處理，App 只讀原始位移並照 AOSP 公式推算位置：

顯示用的懸浮層：
- 開了本 App 的**無障礙服務** → `TYPE_ACCESSIBILITY_OVERLAY`，100% 不透明、蓋在遊戲全螢幕之上。
- 只有**懸浮窗權限** → 一般懸浮窗，透明度固定 80%（Android 12+ 規定，否則點擊會被系統擋掉）。

懸浮層設為「不可觸碰」，滑鼠點擊、觸控都會直接穿透到遊戲。

## 使用步驟

1. 安裝 [Shizuku](https://shizuku.rikka.app/download/)，開發者選項 → 開啟「無線偵錯」→ 在 Shizuku 裡用配對碼啟動。
2. 打開本 App：
   1. 「授權 Shizuku」
   2. 「允許懸浮窗」，建議也「開啟無障礙服務」（游標更清楚）
   3. 「啟動游標（Shizuku）」
3. 預設是接管模式，啟動後狀態列會顯示「接管中」與「［已接管］」的滑鼠；用「移動速度」調成順手的速度。
   若遊戲對滑鼠點擊沒反應，把「點擊送出方式」改成「觸控」。
4. 進入 MapleStory Worlds，就能看到游標。通知列有「置中 / 顯示隱藏 / 停止」快捷鍵。
5. 若接管失敗（狀態列會顯示原因），會自動退回估算模式；請按「自動校準」，偏移時把滑鼠推到角落即可重新對齊。

> 手機重開機後 Shizuku 會停止，要在 Shizuku 裡再按一次啟動。

## 建置

```bash
cd mouse-cursor
gradle assembleRelease   # 產出 app/build/outputs/apk/release/app-release.apk
```

推送到 GitHub 後，Actions「滑鼠游標 APK」會自動打包，可在該次執行的 Artifacts 下載。

## 檔案說明

| 檔案 | 作用 |
|---|---|
| `MouseReaderService.kt` | 在 Shizuku（shell uid）裡執行：掃描/讀取滑鼠、EVIOCGRAB 接管、計算位置，支援熱插拔／藍牙 |
| `Injector.kt` | 在 Shizuku 裡注入滑鼠 / 觸控 / 按鍵事件 |
| `CursorService.kt` | 前景服務，綁定上面的服務並把位移送給游標 |
| `PointerModel.kt` | AOSP 游標加速模型（讀系統 `pointer_speed`） |
| `Geometry.kt` | 螢幕旋轉時的游標座標換算 |
| `CursorHub.kt` | 游標位置、旋轉處理、懸浮窗、閒置隱藏、點擊波紋 |
| `CursorAccessibilityService.kt` | 無障礙懸浮層（只畫游標，不讀畫面、不攔截輸入） |
| `MainActivity.kt` | 設定畫面、自動校準 |

單元測試：`gradle testDebugUnitTest`（旋轉換算、加速模型、裝置偵測）
