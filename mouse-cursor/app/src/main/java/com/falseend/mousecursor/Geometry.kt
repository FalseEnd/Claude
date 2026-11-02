package com.falseend.mousecursor

/** 純計算，與 frameworks/base/libs/input/MouseCursorController.cpp 的旋轉換算一致 */
object Geometry {
    // 與 android.view.Surface.ROTATION_* 相同
    const val ROT_0 = 0
    const val ROT_90 = 1
    const val ROT_180 = 2
    const val ROT_270 = 3

    /**
     * 螢幕方向改變時，求游標在新方向下的座標，使它停在同一個實體位置。
     * oldW/oldH、newW/newH 是「該方向下」的螢幕寬高。
     */
    fun rotate(
        x: Float, y: Float,
        oldW: Float, oldH: Float, oldRot: Int,
        newW: Float, newH: Float, newRot: Int,
    ): Pair<Float, Float> {
        var px = x + 0.5f
        var py = y + 0.5f
        var t: Float
        when (oldRot) { // 還原舊旋轉 → 自然方向
            ROT_90 -> { t = px; px = oldH - py; py = t }
            ROT_180 -> { px = oldW - px; py = oldH - py }
            ROT_270 -> { t = px; px = py; py = oldW - t }
        }
        when (newRot) { // 套用新旋轉
            ROT_90 -> { t = px; px = py; py = newH - t }
            ROT_180 -> { px = newW - px; py = newH - py }
            ROT_270 -> { t = px; px = newW - py; py = t }
        }
        return (px - 0.5f) to (py - 0.5f)
    }
}
