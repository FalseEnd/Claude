package com.perppilot.core.model

import kotlinx.serialization.Serializable

/** One OHLCV bar. [time] is the bar open time in epoch milliseconds (UTC). */
@Serializable
data class Candle(
    val time: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
)

@Serializable
enum class Timeframe(val okx: String, val millis: Long, val label: String) {
    M15("15m", 15 * 60_000L, "15分"),
    H1("1H", 3_600_000L, "1小時"),
    H4("4H", 4 * 3_600_000L, "4小時"),
    D1("1Dutc", 24 * 3_600_000L, "日線");
}

@Serializable
enum class Side {
    LONG, SHORT;

    val sign: Int get() = if (this == LONG) 1 else -1
    val label: String get() = if (this == LONG) "做多" else "做空"
    fun opposite(): Side = if (this == LONG) SHORT else LONG
}

/** Contract specification needed to convert risk into a valid order size. */
@Serializable
data class InstrumentSpec(
    val instId: String,
    /** Face value of one contract in base currency (e.g. 0.01 BTC). */
    val ctVal: Double,
    val ctMult: Double = 1.0,
    /** Order size increment in contracts. */
    val lotSz: Double,
    /** Minimum order size in contracts. */
    val minSz: Double,
    val tickSz: Double,
    val maxLeverage: Int,
) {
    /** Base-currency quantity represented by one contract. */
    val baseQtyPerContract: Double get() = ctVal * ctMult
}

@Serializable
data class Ticker(
    val instId: String,
    val last: Double,
    val open24h: Double,
    val high24h: Double,
    val low24h: Double,
    /** 24h volume in quote currency (USDT). */
    val volQuote24h: Double,
    val time: Long,
) {
    val change24hPct: Double get() = if (open24h > 0) (last - open24h) / open24h * 100.0 else 0.0
}

@Serializable
data class FundingInfo(
    val instId: String,
    /** Current period funding rate, e.g. 0.0001 = 0.01% per 8h. */
    val rate: Double,
    val nextFundingTime: Long,
)

@Serializable
data class AccountBalance(
    /** Total equity in USD. */
    val totalEquity: Double,
    /** Available equity of the settlement currency (USDT). */
    val availableEquity: Double,
    val unrealizedPnl: Double,
)

@Serializable
data class ExchangePosition(
    val instId: String,
    val side: Side,
    /** Absolute size in contracts. */
    val contracts: Double,
    val avgPrice: Double,
    val markPrice: Double,
    val unrealizedPnl: Double,
    val leverage: Double,
    val liquidationPrice: Double?,
    val marginMode: String,
    /** Raw posSide reported by the exchange: net / long / short. */
    val posSide: String,
    val openTime: Long,
)

@Serializable
data class StopOrder(
    val algoId: String,
    val instId: String,
    val stopLoss: Double?,
    val takeProfit: Double?,
    val side: String,
)

@Serializable
data class OrderResult(
    val orderId: String,
    val clientOrderId: String,
    val avgPrice: Double?,
    val filledContracts: Double?,
)

@Serializable
data class ClosedPositionRecord(
    val instId: String,
    val side: Side,
    val openPrice: Double,
    val closePrice: Double,
    val realizedPnl: Double,
    val openTime: Long,
    val closeTime: Long,
    /** Exchange close type: e.g. "close", "liquidation", "adl". */
    val closeType: String,
)

@Serializable
data class AccountConfig(
    /** "net_mode" or "long_short_mode". */
    val positionMode: String,
    /** OKX account level: 1 simple (no derivatives), 2 single-ccy margin, 3 multi-ccy, 4 portfolio. */
    val accountLevel: Int,
) {
    val supportsDerivatives: Boolean get() = accountLevel >= 2
    val hedgeMode: Boolean get() = positionMode == "long_short_mode"
}
