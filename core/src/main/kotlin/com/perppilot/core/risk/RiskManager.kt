package com.perppilot.core.risk

import com.perppilot.core.model.InstrumentSpec
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

@Serializable
data class RiskConfig(
    /** Equity risked per trade if the stop is hit, in percent. */
    val riskPerTradePct: Double = 1.0,
    /** Leverage set on the exchange. Only affects margin usage; risk is set by the stop distance. */
    val leverage: Int = 5,
    val maxOpenPositions: Int = 3,
    /** Sum of open risk across positions, in percent of equity. */
    val maxTotalRiskPct: Double = 3.0,
    /** Stop opening trades for the rest of the UTC day after losing this much. */
    val dailyLossLimitPct: Double = 3.0,
    /** Kill switch: halt trading when equity falls this far below its peak. */
    val maxDrawdownPct: Double = 15.0,
    /** Pause after this many losing trades in a row. */
    val maxConsecutiveLosses: Int = 3,
    val cooldownHours: Int = 12,
    /** "isolated" or "cross". */
    val marginMode: String = "isolated",
)

@Serializable
data class RiskState(
    val peakEquity: Double = 0.0,
    /** Days since epoch (UTC) that [dayStartEquity] refers to. */
    val dayKey: Long = -1,
    val dayStartEquity: Double = 0.0,
    val consecutiveLosses: Int = 0,
    val cooldownUntil: Long = 0,
    val haltReason: String? = null,
    /** 0 = halted until manually reset. */
    val haltUntil: Long = 0,
) {
    fun isHalted(now: Long): Boolean = haltReason != null && (haltUntil == 0L || now < haltUntil)
    fun dailyPnlPct(equity: Double): Double =
        if (dayStartEquity > 0) (equity - dayStartEquity) / dayStartEquity * 100.0 else 0.0
    fun drawdownPct(equity: Double): Double =
        if (peakEquity > 0) (peakEquity - equity) / peakEquity * 100.0 else 0.0
}

data class SizeResult(
    val contracts: Double,
    /** Base-currency quantity (e.g. BTC). */
    val quantity: Double,
    val notional: Double,
    val riskAmount: Double,
    val margin: Double,
)

data class Decision(val allowed: Boolean, val reason: String = "")

object RiskManager {
    const val DAY_MS = 86_400_000L
    const val HOUR_MS = 3_600_000L

    /**
     * Updates peak/daily bookkeeping with the latest equity and applies the circuit breakers.
     * [drawdownAutoResumeHours] = 0 keeps a drawdown halt until it is manually reset (live trading);
     * the backtester passes a positive value so it can keep simulating after the kill switch fires.
     */
    fun onEquity(
        state: RiskState,
        equity: Double,
        now: Long,
        cfg: RiskConfig,
        drawdownAutoResumeHours: Int = 0,
    ): RiskState {
        var s = state
        // An expired timed halt is cleared, with the peak reset so the kill switch can re-arm.
        if (s.haltReason != null && s.haltUntil != 0L && now >= s.haltUntil) {
            s = s.copy(haltReason = null, haltUntil = 0, peakEquity = equity)
        }
        val day = Math.floorDiv(now, DAY_MS)
        if (day != s.dayKey) s = s.copy(dayKey = day, dayStartEquity = equity)
        if (equity > s.peakEquity) s = s.copy(peakEquity = equity)

        if (s.haltReason == null && cfg.maxDrawdownPct > 0 && s.drawdownPct(equity) >= cfg.maxDrawdownPct) {
            val until = if (drawdownAutoResumeHours > 0) now + drawdownAutoResumeHours * HOUR_MS else 0L
            s = s.copy(haltReason = "回撤達 %.1f%%，觸發熔斷".format(s.drawdownPct(equity)), haltUntil = until)
        }
        if (s.haltReason == null && cfg.dailyLossLimitPct > 0 && s.dailyPnlPct(equity) <= -cfg.dailyLossLimitPct) {
            s = s.copy(haltReason = "當日虧損 %.1f%%，暫停至 UTC 次日".format(-s.dailyPnlPct(equity)), haltUntil = (day + 1) * DAY_MS)
        }
        return s
    }

    fun onTradeClosed(state: RiskState, pnl: Double, now: Long, cfg: RiskConfig): RiskState {
        if (pnl >= 0) return state.copy(consecutiveLosses = 0)
        val losses = state.consecutiveLosses + 1
        return if (cfg.maxConsecutiveLosses > 0 && losses >= cfg.maxConsecutiveLosses) {
            state.copy(consecutiveLosses = 0, cooldownUntil = now + cfg.cooldownHours * HOUR_MS)
        } else {
            state.copy(consecutiveLosses = losses)
        }
    }

    fun resetHalt(state: RiskState, equity: Double): RiskState =
        state.copy(haltReason = null, haltUntil = 0, peakEquity = equity, consecutiveLosses = 0, cooldownUntil = 0)

    /** Whether a new position may be opened given the current book. */
    fun canOpen(
        state: RiskState,
        now: Long,
        cfg: RiskConfig,
        openPositions: Int,
        openRiskPct: Double,
    ): Decision = when {
        state.isHalted(now) -> Decision(false, state.haltReason ?: "已暫停")
        now < state.cooldownUntil -> Decision(false, "連續虧損冷卻中")
        openPositions >= cfg.maxOpenPositions -> Decision(false, "持倉數已達上限 ${cfg.maxOpenPositions}")
        openRiskPct + cfg.riskPerTradePct > cfg.maxTotalRiskPct + 1e-9 -> Decision(false, "總風險敞口已達上限")
        else -> Decision(true)
    }

    /**
     * Fixed-fractional position sizing: lose [RiskConfig.riskPerTradePct] of equity if the stop is hit.
     * Returns null when the trade cannot be sized safely (too small for the exchange minimum, or
     * the stop is so wide that the liquidation price could come first).
     */
    fun size(
        equity: Double,
        entry: Double,
        stop: Double,
        cfg: RiskConfig,
        spec: InstrumentSpec? = null,
        availableMargin: Double = equity,
    ): SizeResult? {
        val stopDist = abs(entry - stop)
        if (equity <= 0 || entry <= 0 || stopDist <= 0) return null
        val leverage = max(1, cfg.leverage)
        // Isolated margin: liquidation sits roughly 1/leverage away. Keep the stop well inside it.
        if (stopDist / entry > 0.5 / leverage) return null

        val riskAmount = equity * cfg.riskPerTradePct / 100.0
        var qty = riskAmount / stopDist
        // Margin budget: never commit more than an equal share of buying power per position.
        val maxNotional = minOf(
            equity * leverage * 0.95 / max(1, cfg.maxOpenPositions),
            availableMargin * leverage * 0.95,
        )
        if (qty * entry > maxNotional) qty = maxNotional / entry

        var contracts = qty
        if (spec != null) {
            val perContract = spec.baseQtyPerContract
            if (perContract <= 0 || spec.lotSz <= 0) return null
            contracts = floor(qty / perContract / spec.lotSz + 1e-9) * spec.lotSz
            contracts = roundToStep(contracts, spec.lotSz)
            if (contracts < spec.minSz - 1e-12) return null
            qty = contracts * perContract
        }
        if (qty <= 0) return null
        val notional = qty * entry
        return SizeResult(
            contracts = contracts,
            quantity = qty,
            notional = notional,
            riskAmount = qty * stopDist,
            margin = notional / leverage,
        )
    }

    fun roundToStep(value: Double, step: Double): Double {
        if (step <= 0) return value
        val decimals = decimalsOf(step)
        return java.math.BigDecimal(value).setScale(decimals, java.math.RoundingMode.HALF_UP).toDouble()
    }

    /** Rounds a price to the instrument tick, towards the safe side if [up] is specified. */
    fun roundPrice(price: Double, tick: Double, up: Boolean? = null): Double {
        if (tick <= 0) return price
        val steps = price / tick
        val n = when (up) {
            true -> kotlin.math.ceil(steps - 1e-9)
            false -> floor(steps + 1e-9)
            null -> kotlin.math.round(steps).toDouble()
        }
        return roundToStep(n * tick, tick)
    }

    fun decimalsOf(step: Double): Int =
        java.math.BigDecimal(step.toString()).stripTrailingZeros().scale().coerceAtLeast(0)
}
