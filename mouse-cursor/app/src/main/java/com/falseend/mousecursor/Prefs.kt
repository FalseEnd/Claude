package com.falseend.mousecursor

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("cursor", Context.MODE_PRIVATE)

    /** 游標大小 (dp) */
    var sizeDp: Int
        get() = sp.getInt("size", 24)
        set(v) = sp.edit().putInt("size", v).apply()

    /** 顏色索引，見 [COLORS] */
    var colorIndex: Int
        get() = sp.getInt("color", 0)
        set(v) = sp.edit().putInt("color", v).apply()

    /** 靈敏度倍率 ×100 */
    var sensitivity100: Int
        get() = sp.getInt("sens", 100)
        set(v) = sp.edit().putInt("sens", v).apply()

    /** 加速曲線：-1 = 自動，其餘為 [PointerModel.Type] 的 ordinal */
    var modelType: Int
        get() = sp.getInt("model", -1)
        set(v) = sp.edit().putInt("model", v).apply()

    /** 閒置幾秒後隱藏，0 = 不隱藏 */
    var idleHideSec: Int
        get() = sp.getInt("idle", 0)
        set(v) = sp.edit().putInt("idle", v).apply()

    var clickEffect: Boolean
        get() = sp.getBoolean("click", true)
        set(v) = sp.edit().putBoolean("click", v).apply()

    companion object {
        val MODEL_NAMES = listOf(
            "自動（依系統版本）",
            "舊版加速（Android 14 以前）",
            "新版曲線（Android 16 / 部分 15）",
            "無加速（Android 16 關閉游標加速）",
        )

        /** 填色 to 外框 */
        val COLORS = listOf(
            "白色" to (0xFFFFFFFF.toInt() to 0xFF000000.toInt()),
            "黑色" to (0xFF000000.toInt() to 0xFFFFFFFF.toInt()),
            "紅色" to (0xFFFF3B30.toInt() to 0xFFFFFFFF.toInt()),
            "黃色" to (0xFFFFD60A.toInt() to 0xFF000000.toInt()),
            "綠色" to (0xFF34C759.toInt() to 0xFF000000.toInt()),
            "青色" to (0xFF00E5FF.toInt() to 0xFF000000.toInt()),
            "粉紅" to (0xFFFF6EC7.toInt() to 0xFF000000.toInt()),
        )
    }
}
