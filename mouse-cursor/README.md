# 滑鼠游標（MouseCursor）

在 **MapleStory Worlds** 等會把系統游標藏起來的 Android 遊戲裡，強制在畫面最上層畫出一個滑鼠游標。**不需要 root。**

## 為什麼一般 App 做不到？

遊戲用 `PointerIcon.TYPE_NULL`（或 Pointer Capture）把游標藏起來後，滑鼠事件只會送給遊戲本身，
一般 App 完全拿不到滑鼠座標。本 App 用兩種免 root 的方式繞過：

| 模式 | 原理 | 精準度 | 需求 |
|---|---|---|---|
| **Shizuku（主要）** | 透過 Shizuku 以 `shell` 身分直接讀 `/dev/input/eventX` 的滑鼠原始位移，自己算游標位置 | 很高，可自動校準；推到螢幕角落即重新對齊 | Shizuku（無線偵錯啟動，Android 11+ 不用電腦） |
| **直接追蹤（實驗）** | Android 14+ 無障礙服務的「觀察滑鼠事件」隱藏 API，拿到系統算好的真實座標，事件照常送給遊戲 | 零誤差 | 手機需支援，App 內一鍵測試，失敗自動還原 |

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
3. 第一次使用建議按「自動校準靈敏度」，在 App 畫面裡畫圈移動滑鼠 6 秒。
4. 進入 MapleStory Worlds，就能看到游標。通知列有「置中 / 顯示隱藏 / 停止」快捷鍵。
5.（選用）Android 14 以上可按「測試直接追蹤」，若成功之後連 Shizuku 都不用開。

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
| `MouseReaderService.kt` | 在 Shizuku（shell uid）裡執行，掃描並讀取所有滑鼠裝置，支援熱插拔／藍牙 |
| `CursorService.kt` | 前景服務，綁定上面的服務並把位移送給游標 |
| `PointerModel.kt` | 模擬 Android 游標速度 / 加速曲線（讀系統 `pointer_speed`） |
| `CursorHub.kt` | 游標位置、懸浮窗、閒置隱藏、點擊波紋 |
| `CursorAccessibilityService.kt` | 無障礙懸浮層 + 直接追蹤模式 |
| `MainActivity.kt` | 設定畫面、校準、測試 |
