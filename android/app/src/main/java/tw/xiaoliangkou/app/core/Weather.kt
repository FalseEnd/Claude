package tw.xiaoliangkou.app.core

import kotlin.math.roundToInt

/**
 * 某個地方現在的天氣（Open-Meteo 的預報＋空氣品質）。抓不到的項目是 null，畫面顯示「—」。
 * [rainChance]、[uvMax] 是接下來 12 小時裡最高的；[high]、[low] 是今天的最高、最低溫
 */
data class WeatherNow(
    val temp: Double,
    val code: Int,
    val isDay: Boolean = true,
    val feelsLike: Double? = null,
    val humidity: Int? = null,
    val visibilityM: Double? = null,
    val windKmh: Double? = null,
    val rainChance: Int? = null,
    val uvMax: Double? = null,
    val high: Double? = null,
    val low: Double? = null,
    /** 美國 EPA 的 AQI（台灣環境部的 AQI 也是同一套分級） */
    val aqi: Int? = null,
    val pm25: Double? = null,
    /** 抓下來的時間（毫秒） */
    val fetchedAt: Long = 0,
)

/** 天氣的文字、分級和外出建議（不碰網路，方便測試） */
object Weather {
    /** WMO 天氣代碼 → (表情, 說明) */
    fun describe(code: Int, isDay: Boolean = true): Pair<String, String> = when (code) {
        0 -> (if (isDay) "☀️" else "🌙") to "晴天"
        1 -> (if (isDay) "🌤️" else "🌙") to "大致晴朗"
        2 -> "⛅" to "多雲"
        3 -> "☁️" to "陰天"
        45, 48 -> "🌫️" to "起霧"
        51, 53, 55 -> "🌦️" to "毛毛雨"
        56, 57 -> "🌧️" to "凍雨"
        61 -> "🌦️" to "小雨"
        63 -> "🌧️" to "下雨"
        65 -> "🌧️" to "大雨"
        66, 67 -> "🌧️" to "凍雨"
        71, 73, 75, 77 -> "🌨️" to "下雪"
        80 -> "🌦️" to "陣雨"
        81 -> "🌧️" to "較強的陣雨"
        82 -> "⛈️" to "豪雨"
        85, 86 -> "🌨️" to "陣雪"
        95 -> "⛈️" to "雷雨"
        96, 99 -> "⛈️" to "雷雨夾冰雹"
        else -> "🌡️" to "天氣"
    }

    fun isRaining(code: Int) = code in 51..67 || code in 80..82 || code >= 95
    fun isStorm(code: Int) = code >= 95
    private fun isHeavyRain(code: Int) = code == 65 || code == 67 || code == 82

    /** 空氣品質的分級（環境部 AQI 的六級）：(說明, 0～5) */
    fun aqiLevel(aqi: Int): Pair<String, Int> = when {
        aqi <= 50 -> "良好" to 0
        aqi <= 100 -> "普通" to 1
        aqi <= 150 -> "對敏感族群不健康" to 2
        aqi <= 200 -> "對所有族群不健康" to 3
        aqi <= 300 -> "非常不健康" to 4
        else -> "危害" to 5
    }

    /** 紫外線指數的分級（中央氣象署的五級） */
    fun uvLevel(uv: Double): String = when {
        uv < 3 -> "低量級"
        uv < 6 -> "中量級"
        uv < 8 -> "高量級"
        uv < 11 -> "過量級"
        else -> "危險級"
    }

    fun visibilityText(m: Double): String = when {
        m >= 10_000 -> "10 公里以上"
        m >= 1_000 -> "${(m / 100).roundToInt() / 10.0} 公里".replace(".0 ", " ")
        else -> "${(m / 10).roundToInt() * 10} 公尺"
    }

    fun deg(t: Double) = "${t.roundToInt()}°"

    enum class Outing(val emoji: String, val text: String) {
        GOOD("😊", "很適合出門"),
        OK("🙂", "可以出門，注意一下"),
        CAREFUL("⚠️", "出門要多注意"),
        STAY("🏠", "能不出門就待在室內"),
    }

    /**
     * 一條建議。[concern] 是看到什麼（「降雨機率 80%」），[todo] 是要做什麼（「記得帶傘」）；
     * [kind] 是提醒對方時用哪一種安撫（[Catalog.comfort]）
     */
    data class Tip(val emoji: String, val concern: String, val todo: String, val level: Outing, val kind: String)

    data class Advice(val outing: Outing, val tips: List<Tip>)

    fun advice(w: WeatherNow): Advice {
        val tips = mutableListOf<Tip>()
        val feels = w.feelsLike ?: w.temp
        val rain = w.rainChance ?: 0

        if (isStorm(w.code)) tips += Tip("⛈️", "有雷雨", "避開空曠的地方，能待室內就待室內", Outing.STAY, "safe")
        else if (isHeavyRain(w.code)) tips += Tip("☔", "雨下很大", "記得帶傘，路上小心", Outing.STAY, "safe")
        else if (isRaining(w.code)) tips += Tip("☔", "正在下雨", "記得帶傘", Outing.CAREFUL, "safe")
        else if (rain >= 60) tips += Tip("☔", "降雨機率 $rain%", "記得帶傘", Outing.CAREFUL, "safe")
        else if (rain >= 30) tips += Tip("🌂", "可能會下雨（$rain%）", "包包放一把折傘", Outing.OK, "safe")

        w.aqi?.let { aqi ->
            val (label, level) = aqiLevel(aqi)
            when {
                level >= 3 -> tips += Tip("😷", "空氣品質$label（AQI $aqi）", "戴口罩，少待在戶外", Outing.STAY, "safe")
                level == 2 -> tips += Tip("😷", "空氣品質$label（AQI $aqi）", "戴口罩，別在戶外運動", Outing.CAREFUL, "safe")
            }
        }

        w.uvMax?.let { uv ->
            val v = uv.roundToInt()
            when {
                uv >= 11 -> tips += Tip("🧴", "紫外線${uvLevel(uv)}（$v）", "中午盡量別出門，做好防曬", Outing.CAREFUL, "water")
                uv >= 8 -> tips += Tip("🧴", "紫外線${uvLevel(uv)}（$v）", "擦防曬、戴帽子或撐陽傘", Outing.CAREFUL, "water")
                uv >= 6 -> tips += Tip("🧴", "紫外線${uvLevel(uv)}（$v）", "記得擦防曬", Outing.OK, "water")
            }
        }

        val hot = maxOf(feels, w.high ?: feels)
        val cold = minOf(feels, w.low ?: feels)
        when {
            hot >= 36 -> tips += Tip("🥵", "熱到 ${deg(hot)}", "多喝水，小心中暑", Outing.CAREFUL, "water")
            hot >= 32 -> tips += Tip("💧", "很熱（${deg(hot)}）", "多補充水分", Outing.OK, "water")
        }
        when {
            cold <= 10 -> tips += Tip("🧣", "冷到 ${deg(cold)}", "穿暖一點，注意保暖", Outing.CAREFUL, "warm")
            cold <= 15 -> tips += Tip("🧥", "有點涼（${deg(cold)}）", "帶件外套", Outing.OK, "warm")
            w.high != null && w.low != null && w.high - w.low >= 8 ->
                tips += Tip("🧥", "早晚溫差 ${(w.high - w.low).roundToInt()}°", "帶件薄外套", Outing.OK, "warm")
        }

        w.visibilityM?.let { m ->
            when {
                m < 1_000 -> tips += Tip("🌫️", "能見度只有 ${visibilityText(m)}", "騎車開車放慢、打開大燈", Outing.CAREFUL, "safe")
                m < 3_000 -> tips += Tip("🌫️", "有點霧（能見度 ${visibilityText(m)}）", "騎車開車小心", Outing.OK, "safe")
            }
        }

        w.windKmh?.let { k ->
            when {
                k >= 62 -> tips += Tip("🌬️", "風非常大", "別騎車，遠離招牌和樹", Outing.STAY, "safe")
                k >= 39 -> tips += Tip("🌬️", "風很大", "騎車小心，雨傘容易開花", Outing.CAREFUL, "safe")
            }
        }

        w.humidity?.let { h ->
            when {
                h >= 90 && !isRaining(w.code) && feels >= 24 -> tips += Tip("💦", "很悶濕（濕度 $h%）", "穿透氣一點的衣服", Outing.OK, "water")
                h <= 35 -> tips += Tip("🫧", "很乾燥（濕度 $h%）", "多喝水、擦乳液", Outing.OK, "water")
            }
        }

        val sorted = tips.sortedByDescending { it.level.ordinal }
        return Advice(sorted.maxOfOrNull { it.level } ?: Outing.GOOD, sorted)
    }

    /**
     * 提醒對方的話（送安撫的悄悄話，資料庫最多 200 字）：最要緊的兩條。
     * 例如「你那邊正在下雨、有點涼（12°），記得帶傘、帶件外套喔 ☔」
     */
    fun reminder(a: Advice): String {
        val top = a.tips.take(2)
        if (top.isEmpty()) return "你那邊今天天氣不錯，有空出去走走吧 ☀️"
        val text = "你那邊${top.joinToString("、") { it.concern }}，${top.joinToString("、") { it.todo }}喔 ${top.first().emoji}"
        return text.take(200)
    }

    /** 提醒對方時送哪一種安撫（最要緊那一條的）；天氣好就比個心 */
    fun reminderKind(a: Advice): String = a.tips.firstOrNull()?.kind ?: "heart"
}
