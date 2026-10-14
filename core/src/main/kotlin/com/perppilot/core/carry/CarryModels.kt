package com.perppilot.core.carry

import kotlinx.serialization.Serializable

/**
 * Delta-neutral funding carry: hold the coin on spot and short the same quantity of the USDT
 * perpetual. Price moves cancel out; the position collects funding while it is positive.
 */
@Serializable
data class CarryConfig(
    val enabled: Boolean = false,
    val coins: List<String> = listOf("BTC", "ETH"),
    /** Share of USDT equity allocated to carry, split equally across coins. */
    val capitalPct: Double = 50.0,
    /** Enter (or stay) while the trailing average funding per 8h is at least this, in percent. */
    val entryAvgPct: Double = 0.0,
    /** Exit when the trailing average funding per 8h drops below this, in percent. */
    val exitAvgPct: Double = -0.005,
    /** Number of settlements in the trailing average (21 × 8h = 7 days). */
    val lookback: Int = 21,
    /** Leverage on the short leg (cross margin). Higher = less USDT tied up but less buffer. */
    val perpLeverage: Int = 2,
    /** Shrink both legs back to target when the short's notional has grown this much (price rally). */
    val rebalanceGrowthPct: Double = 25.0,
    /** Re-hedge when spot and perp quantities differ by more than this. */
    val hedgeTolerancePct: Double = 3.0,
)

@Serializable
data class CarryPosition(
    val coin: String,
    val spotQty: Double,
    val contracts: Double,
    val entryPrice: Double,
    val entryTime: Long,
    /** Estimated funding collected so far (USDT). */
    val fundingEarned: Double = 0.0,
    /** Last settlement time already added to [fundingEarned]. */
    val lastFundingTime: Long = 0,
)

@Serializable
data class CarryCoinStatus(
    val coin: String,
    val avgRatePct: Double,
    val lastRatePct: Double,
    val annualizedPct: Double,
    val inPosition: Boolean,
    val note: String = "",
)

@Serializable
data class CarryState(
    val positions: Map<String, CarryPosition> = emptyMap(),
    val status: List<CarryCoinStatus> = emptyList(),
    val closedFunding: Double = 0.0,
    val lastRun: Long = 0,
)

@Serializable
data class FundingSnapshot(
    val instId: String,
    val ratePct: Double,
    val nextFundingTime: Long,
) {
    /** Annualized assuming 3 settlements a day. */
    val annualizedPct: Double get() = ratePct * 3 * 365
}

/** Spot + funding operations the carry engine needs on top of [com.perppilot.core.exchange.TradingApi]. */
interface CarryApi {
    /** Settled funding rates for a perpetual, newest first, as fractions. */
    suspend fun fundingHistory(instId: String, limit: Int): List<Pair<Long, Double>>
    /** Current funding of every perpetual. */
    suspend fun allFunding(): List<FundingSnapshot>
    suspend fun spotLotSize(instId: String): Pair<Double, Double>
    /** Available balance of [ccy] (e.g. BTC). */
    suspend fun assetBalance(ccy: String): Double
    /** Market order on spot sized in base currency. Returns filled base quantity (after fees when known). */
    suspend fun spotMarket(instId: String, buy: Boolean, baseQty: Double, clientOrderId: String): Double
    /** Plain market order on a perpetual without attached stops. */
    suspend fun perpMarket(instId: String, sell: Boolean, contracts: Double, marginMode: String, hedgeMode: Boolean, reduceOnly: Boolean, clientOrderId: String)
}
