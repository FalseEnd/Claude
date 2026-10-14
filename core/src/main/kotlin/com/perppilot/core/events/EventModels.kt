package com.perppilot.core.events

import kotlinx.serialization.Serializable

/** Settlement method of an OKX event-contract series. */
@Serializable
enum class EventMethod(val label: String) {
    PRICE_UP_DOWN("漲跌"),
    PRICE_ABOVE("價格高於"),
    BETWEEN("價格區間"),
    HIT("觸及"),
    UNKNOWN("其他");

    companion object {
        fun parse(raw: String): EventMethod = when (raw.lowercase()) {
            "price_up_down" -> PRICE_UP_DOWN
            "price_above" -> PRICE_ABOVE
            "between" -> BETWEEN
            "hit", "price_once_touch" -> HIT
            else -> UNKNOWN
        }
    }
}

@Serializable
enum class Outcome(val api: String) {
    YES("yes"), NO("no");

    fun label(method: EventMethod): String = when (method) {
        EventMethod.PRICE_UP_DOWN -> if (this == YES) "看漲 UP" else "看跌 DOWN"
        else -> if (this == YES) "是 YES" else "否 NO"
    }
}

@Serializable
data class EventSeries(
    val seriesId: String,
    val title: String,
    /** five_min / fifteen_min / hourly / daily / monthly. */
    val freq: String,
    val method: EventMethod,
    /** Index the contract settles against, e.g. BTC-USDT. */
    val underlying: String,
)

@Serializable
data class EventMarket(
    val instId: String,
    val seriesId: String,
    val method: EventMethod,
    /** Strike; for up/down it is the index price at the start of the period (0 until fixed). */
    val floorStrike: Double?,
    /** Upper bound for BETWEEN markets; +Infinity for the top bracket. */
    val capStrike: Double?,
    /** "up" / "dn" for HIT markets. */
    val hitDir: String,
    val listTime: Long,
    val expTime: Long,
    /** Time the strike was fixed (start of an up/down period); 0 when not yet fixed. */
    val fixTime: Long,
    /** preopen / live / settling / expired. */
    val state: String,
    /** 0 = pending, 1 = YES won, 2 = NO won. */
    val outcome: Int,
    val settleValue: Double?,
) {
    val isLive: Boolean get() = state == "live" && (floorStrike ?: 0.0) > 0.0
    fun winner(): Outcome? = when (outcome) {
        1 -> Outcome.YES
        2 -> Outcome.NO
        else -> null
    }
}

/** Order book top for the YES side; NO prices are derived (NO ask = 1 - YES bid). */
@Serializable
data class EventQuote(
    val instId: String,
    val yesBid: Double?,
    val yesAsk: Double?,
    val last: Double?,
    val time: Long,
) {
    fun ask(outcome: Outcome): Double? = when (outcome) {
        Outcome.YES -> yesAsk
        Outcome.NO -> yesBid?.let { 1.0 - it }
    }
}

@Serializable
data class EventPosition(
    val instId: String,
    val outcome: Outcome,
    val contracts: Double,
    val avgPrice: Double,
)
