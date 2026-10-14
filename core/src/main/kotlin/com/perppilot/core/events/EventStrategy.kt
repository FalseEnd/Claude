package com.perppilot.core.events

import kotlinx.serialization.Serializable
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

@Serializable
data class EventStrategyConfig(
    val series: List<String> = listOf("BTC-UPDOWN-15MIN", "ETH-UPDOWN-15MIN", "BTC-ABOVE-DAILY"),
    /** Minimum expected value per contract after fees and slippage (0.05 = 5 cents per 1 USDT payout). */
    val minEdge: Double = 0.05,
    /** Taker fee coefficient: fee per contract = feeK × P × (1 − P). Check your OKX fee tier. */
    val feeK: Double = 0.07,
    /** Extra price paid over the quoted ask (thin books). */
    val slippage: Double = 0.01,
    /** Fraction of the Kelly stake to use (0.25 = quarter Kelly). */
    val kellyFraction: Double = 0.25,
    /** Hard cap per event, percent of equity. */
    val maxStakePct: Double = 1.0,
    /** Stop betting for the UTC day after losing this percent of equity on events. */
    val dailyLossLimitPct: Double = 3.0,
    val maxOpenBets: Int = 3,
    /** Ignore lottery tickets and near-certainties. */
    val minPrice: Double = 0.10,
    val maxPrice: Double = 0.90,
    /** Do not trade the last minutes (settlement averaging, thin books) … */
    val minMinutesLeft: Double = 3.0,
    /** … or contracts that are too far from expiry for the volatility model to be meaningful. */
    val maxMinutesLeft: Double = 24 * 60.0,
    val autoTrade: Boolean = false,
    val notify: Boolean = true,
)

@Serializable
data class EventOpportunity(
    val instId: String,
    val seriesId: String,
    val method: EventMethod,
    val outcome: Outcome,
    val spot: Double,
    val strike: Double,
    val minutesLeft: Double,
    val fairProb: Double,
    val ask: Double,
    /** Expected value per contract after fees and slippage. */
    val edge: Double,
    /** Highest price we are willing to pay. */
    val limitPrice: Double,
    val kellyFraction: Double,
)

object EventStrategy {

    fun fee(price: Double, k: Double): Double = k * price * (1 - price)

    /**
     * Evaluates both sides of one market. Returns the better side if its expected value per
     * contract, after fees and slippage, is at least [EventStrategyConfig.minEdge].
     */
    fun evaluate(
        market: EventMarket,
        quote: EventQuote,
        spot: Double,
        volPerMin: Double,
        now: Long,
        cfg: EventStrategyConfig,
    ): EventOpportunity? {
        if (!market.isLive) return null
        val minutes = (market.expTime - now) / 60_000.0
        if (minutes < cfg.minMinutesLeft || minutes > cfg.maxMinutesLeft) return null
        val pYes = EventPricer.fairYes(market, spot, volPerMin, now) ?: return null
        return Outcome.entries.mapNotNull { outcome ->
            val ask = quote.ask(outcome) ?: return@mapNotNull null
            if (ask < cfg.minPrice || ask > cfg.maxPrice) return@mapNotNull null
            val q = if (outcome == Outcome.YES) pYes else 1 - pYes
            val cost = ask + cfg.slippage
            val edge = q - cost - fee(cost, cfg.feeK)
            if (edge < cfg.minEdge) return@mapNotNull null
            // Highest price that still leaves minEdge after fees (solve approximately, tick 0.01).
            var limit = floor((q - cfg.minEdge) * 100) / 100
            while (limit > 0 && q - limit - fee(limit, cfg.feeK) < cfg.minEdge) limit -= 0.01
            limit = min(max(limit, 0.0), cfg.maxPrice)
            if (limit < ask) return@mapNotNull null
            EventOpportunity(
                instId = market.instId,
                seriesId = market.seriesId,
                method = market.method,
                outcome = outcome,
                spot = spot,
                strike = market.floorStrike ?: 0.0,
                minutesLeft = minutes,
                fairProb = q,
                ask = ask,
                edge = edge,
                limitPrice = limit,
                kellyFraction = kelly(q, cost + fee(cost, cfg.feeK)),
            )
        }.maxByOrNull { it.edge }
    }

    /** Kelly fraction for a binary bet costing [cost] that pays 1 with probability [q]. */
    fun kelly(q: Double, cost: Double): Double = if (cost >= 1 || cost <= 0) 0.0 else max(0.0, (q - cost) / (1 - cost))

    /** Number of contracts to buy (lot 0.1) for the given equity. */
    fun contracts(op: EventOpportunity, equity: Double, cfg: EventStrategyConfig, lotSz: Double = 0.1, minSz: Double = 0.1): Double {
        val stake = min(equity * op.kellyFraction * cfg.kellyFraction, equity * cfg.maxStakePct / 100.0)
        val raw = stake / op.limitPrice
        val n = floor(raw / lotSz + 1e-9) * lotSz
        return if (n + 1e-9 < minSz) 0.0 else Math.round(n * 1000) / 1000.0
    }
}
