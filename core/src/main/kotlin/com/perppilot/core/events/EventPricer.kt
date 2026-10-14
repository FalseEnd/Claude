package com.perppilot.core.events

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Fair-value model for binary event contracts.
 *
 * The underlying index is modelled as driftless geometric Brownian motion over the remaining life
 * of the contract, with volatility estimated from recent index candles. This is the same digital
 * option maths (N(d2) with r = 0) a market maker would start from; it says nothing about direction,
 * so any edge must come from the market price disagreeing with realised volatility.
 */
object EventPricer {

    /** Standard normal CDF (Abramowitz–Stegun 7.1.26 via erf, |error| < 1.5e-7). */
    fun normCdf(x: Double): Double {
        val t = 1.0 / (1.0 + 0.3275911 * abs(x) / sqrt(2.0))
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t *
            exp(-x * x / 2.0)
        return if (x >= 0) 0.5 * (1.0 + y) else 0.5 * (1.0 - y)
    }

    /**
     * Per-minute volatility of log returns from closes sampled every [minutesPerBar] minutes.
     * Uses an exponentially weighted variance (RiskMetrics-style) so recent moves count more.
     */
    fun perMinuteVol(closes: List<Double>, minutesPerBar: Double = 1.0, lambda: Double = 0.94, floor: Double = 0.0001): Double {
        if (closes.size < 3) return floor
        val rets = closes.zipWithNext { a, b -> ln(b / a) }
        var variance = rets.take(10).map { it * it }.average()
        for (r in rets.drop(10)) variance = lambda * variance + (1 - lambda) * r * r
        return max(sqrt(variance / minutesPerBar), floor)
    }

    /** P(S_T >= K) for driftless GBM. */
    fun probAbove(spot: Double, strike: Double, volPerMin: Double, minutes: Double): Double {
        if (strike <= 0) return 1.0
        if (minutes <= 0 || volPerMin <= 0) return if (spot >= strike) 1.0 else 0.0
        val s = volPerMin * sqrt(minutes)
        return normCdf(ln(spot / strike) / s - s / 2)
    }

    /** Probability that the index touches [barrier] before expiry (reflection principle). */
    fun probHit(spot: Double, barrier: Double, up: Boolean, volPerMin: Double, minutes: Double): Double {
        if (up && spot >= barrier || !up && spot <= barrier) return 1.0
        if (minutes <= 0) return 0.0
        val s = volPerMin * sqrt(minutes)
        val d = abs(ln(barrier / spot)) / s
        return (2.0 * (1.0 - normCdf(d))).coerceIn(0.0, 1.0)
    }

    /** Model probability that the YES outcome wins, or null when the market cannot be priced. */
    fun fairYes(market: EventMarket, spot: Double, volPerMin: Double, now: Long): Double? {
        val minutes = (market.expTime - now) / 60_000.0
        val floor = market.floorStrike ?: return null
        if (floor <= 0 && market.method != EventMethod.BETWEEN) return null
        return when (market.method) {
            EventMethod.PRICE_UP_DOWN, EventMethod.PRICE_ABOVE -> probAbove(spot, floor, volPerMin, minutes)
            EventMethod.BETWEEN -> {
                val cap = market.capStrike ?: Double.POSITIVE_INFINITY
                val upper = if (cap.isInfinite()) 0.0 else probAbove(spot, cap, volPerMin, minutes)
                (probAbove(spot, floor, volPerMin, minutes) - upper).coerceIn(0.0, 1.0)
            }
            EventMethod.HIT -> probHit(spot, floor, market.hitDir != "dn", volPerMin, minutes)
            EventMethod.UNKNOWN -> null
        }
    }
}
