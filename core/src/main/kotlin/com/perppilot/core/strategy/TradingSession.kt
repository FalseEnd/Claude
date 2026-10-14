package com.perppilot.core.strategy

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/**
 * Time windows in which new entries are allowed. Windows are defined in the local time of the
 * relevant market, so daylight-saving shifts are handled automatically. Exits are never restricted.
 */
@Serializable
enum class TradingSession(
    val label: String,
    private val zone: String,
    private val startMin: Int,
    private val endMin: Int,
    private val weekdaysOnly: Boolean,
) {
    ALL("全天候", "UTC", 0, 24 * 60, false),
    WEEKDAYS("僅週一至週五", "UTC", 0, 24 * 60, true),
    US_REGULAR("美股盤中 09:30–16:00 ET", "America/New_York", 9 * 60 + 30, 16 * 60, true),
    US_OPEN("美股開盤時段 08:30–11:30 ET", "America/New_York", 8 * 60 + 30, 11 * 60 + 30, true),
    EUROPE_US_OVERLAP("歐美重疊 08:00–12:00 ET", "America/New_York", 8 * 60, 12 * 60, true),
    ASIA("亞洲盤 09:00–17:00 台北", "Asia/Taipei", 9 * 60, 17 * 60, false);

    fun allows(epochMillis: Long): Boolean {
        if (this == ALL) return true
        val t = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.of(zone))
        if (weekdaysOnly && (t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY)) return false
        val m = t.hour * 60 + t.minute
        return m in startMin until endMin
    }
}
