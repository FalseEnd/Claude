# 滑鼠游標（MouseCursor）

在 **MapleStory Worlds** 等會把系統游標藏起來的 Android 遊戲裡，強制在畫面最上層畫出一個滑鼠游標。**不需要 root。**

## 為什麼一般 App 做不到？

遊戲用 `PointerIcon.TYPE_NULL`（或 Pointer Capture）把游標藏起來後，滑鼠事件只會送給遊戲本身，
一般 App 完全拿不到滑鼠座標。本 App 透過 **Shizuku** 以 `shell` 身分直接讀 `/dev/input/eventX`
的滑鼠原始位移，再照 AOSP 的公式自己算出游標位置：

- **加速曲線**：實作了 Android 14 以前的 `SimpleVelocityControl`、Android 16 的 `CurvedVelocityControl`
  （800 CPI、四段曲線）與關閉加速時的固定倍率；速度估計同樣用 LSQ2（100ms 視窗二次擬合）。
- **自動校準**：在本 App 畫面中系統游標看得到，把同一段移動同時餵給三種模型，
  以最小平方法求倍率並挑誤差最小者（Android 15 兩種曲線都有可能，靠這步判斷）。
- **螢幕旋轉**：照 `MouseCursorController::setDisplayViewport()` 把游標換算到同一個實體位置，
  所以從直向的桌面切回橫向的遊戲，游標不會跑掉。
- **邊緣對齊**：游標撞到螢幕邊緣會被夾住，和系統游標一樣，推到角落就能完全重新對齊。

> 曾嘗試 Android 14+ 無障礙「觀察滑鼠事件」，但它需要 `ACCESSIBILITY_MOTION_EVENT_OBSERVING`
> （signature 權限），一般 App 拿不到，沒有權限時滑鼠反而會被吃掉，因此不採用。

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
3. 第一次使用務必按「自動校準」，在畫面中間大範圍、忽快忽慢地移動滑鼠 8 秒（不要碰到邊緣）。
   完成後會顯示三種曲線的誤差，自動選最準的。
4. 進入 MapleStory Worlds，就能看到游標。通知列有「置中 / 顯示隱藏 / 停止」快捷鍵。
5. 若玩久了有一點偏移，把滑鼠往任一角落推一下就會重新對齊。

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
| `PointerModel.kt` | AOSP 游標加速模型（讀系統 `pointer_speed`） |
| `Geometry.kt` | 螢幕旋轉時的游標座標換算 |
| `CursorHub.kt` | 游標位置、旋轉處理、懸浮窗、閒置隱藏、點擊波紋 |
| `CursorAccessibilityService.kt` | 無障礙懸浮層（只畫游標，不讀畫面、不攔截輸入） |
| `MainActivity.kt` | 設定畫面、自動校準 |

單元測試：`gradle testDebugUnitTest`（旋轉換算、加速模型）
