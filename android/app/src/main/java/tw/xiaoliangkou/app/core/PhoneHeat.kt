package tw.xiaoliangkou.app.core

import java.util.Locale

/**
 * 手機溫度：電池的溫度（°C，系統的電池廣播就有），加上系統判斷的過熱等級（Android 10 以上的 thermal status，0～6）。
 * 兩個都看，比較熱的那個算數：有的手機電池還不燙，系統已經在降速了。
 */
object PhoneHeat {
    enum class Level(val label: String, val emoji: String, val tip: String) {
        COLD("有點冰", "🥶", "天氣冷電池掉得比較快，放在口袋裡"),
        NORMAL("正常", "😊", "溫度正常"),
        WARM("有點熱", "🥵", "先別邊充電邊玩，讓它休息一下"),
        HOT("很燙", "🔥", "先停下來，拿掉保護殼、別放在太陽下"),
    }

    /** 40° 以上有點熱、45° 以上很燙、5° 以下有點冰 */
    const val WARM_C = 40.0
    const val HOT_C = 45.0
    const val COLD_C = 5.0

    /** PowerManager 的 THERMAL_STATUS_MODERATE、SEVERE */
    const val THERMAL_MODERATE = 2
    const val THERMAL_SEVERE = 3

    /** 兩個都沒有（舊的手機、舊的 App、舊的資料庫）是 null */
    fun level(tempC: Double?, thermal: Int?): Level? {
        if (tempC == null && thermal == null) return null
        val byThermal = when {
            thermal == null -> Level.NORMAL
            thermal >= THERMAL_SEVERE -> Level.HOT
            thermal >= THERMAL_MODERATE -> Level.WARM
            else -> Level.NORMAL
        }
        val byTemp = when {
            tempC == null -> Level.NORMAL
            tempC >= HOT_C -> Level.HOT
            tempC >= WARM_C -> Level.WARM
            tempC <= COLD_C -> Level.COLD
            else -> Level.NORMAL
        }
        // 系統說在降速就聽系統的；不然看電池溫度（冰的時候系統不會說什麼）
        return if (byThermal.ordinal > byTemp.ordinal) byThermal else byTemp
    }

    /** 「36.5°C」 */
    fun text(tempC: Double): String = String.format(Locale.ROOT, "%.1f°C", tempC)

    /** 電池廣播的 EXTRA_TEMPERATURE 是 0.1°C 為單位；沒有或不合理（-40° 以下、100° 以上）是 null */
    fun fromBattery(tenths: Int?): Double? = tenths?.takeIf { it in -400..1000 }?.let { it / 10.0 }
}
