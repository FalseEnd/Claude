package com.perppilot.core.strategy

import com.perppilot.core.indicators.Indicators
import com.perppilot.core.indicators.closes
import com.perppilot.core.indicators.highs
import com.perppilot.core.indicators.lows
import com.perppilot.core.indicators.volumes
import com.perppilot.core.model.Candle
import com.perppilot.core.model.Side
import com.perppilot.core.model.Timeframe
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class Regime(val label: String) { BULL("多頭趨勢"), BEAR("空頭趨勢"), NEUTRAL("震盪/無趨勢") }

enum class EntryType(val label: String) { PULLBACK("回踩續勢"), BREAKOUT("放量突破") }

@Serializable
data class EntrySignal(
    val side: Side,
    val type: String,
    /** Open time of the (closed) bar that produced the signal. */
    val barTime: Long,
    val price: Double,
    val atr: Double,
    val suggestedStop: Double,
    val strength: Double,
    val reason: String,
)

/** Live/backtest view of an open position managed by the strategy. */
@Serializable
data class PositionTrack(
    val instId: String,
    val side: Side,
    val entryTime: Long,
    val entryPrice: Double,
    val initialStop: Double,
    val stop: Double,
    val takeProfit: Double? = null,
    /** Size: contracts when live, base quantity in the backtester. */
    val size: Double,
    /** Highest high (long) / lowest low (short) seen since entry. */
    val extreme: Double,
    val barsHeld: Int = 0,
    val breakEvenDone: Boolean = false,
    val entryType: String = "",
    /** Open time of the last bar already processed by [TrendStrategy.manage]. */
    val lastBarTime: Long = 0,
    /** Strategy that manages this position. */
    val strategy: StrategyType = StrategyType.TREND,
    /** Where the trade came from: 策略 (built-in) or TradingView. */
    val origin: String = "策略",
    /** false = keep only the initial stop; exits come from the signal source. */
    val manageExits: Boolean = true,
) {
    val riskPerUnit: Double get() = abs(entryPrice - initialStop)
    fun rMultiple(price: Double): Double =
        if (riskPerUnit == 0.0) 0.0 else (price - entryPrice) * side.sign / riskPerUnit
}

data class ManageResult(val position: PositionTrack, val exitReason: String?)

/** Indicator series computed once for a symbol, aligned to the lower timeframe. */
class PreparedSeries(
    val ltf: List<Candle>,
    val htf: List<Candle>,
    /** Index of the most recent *closed* HTF bar at the close of each LTF bar, or -1. */
    val htfIndex: IntArray,
    val ema: DoubleArray,
    val rsi: DoubleArray,
    val atr: DoubleArray,
    val volSma: DoubleArray,
    val donchianHigh: DoubleArray,
    val donchianLow: DoubleArray,
    val htfEmaFast: DoubleArray,
    val htfEmaSlow: DoubleArray,
    val htfAdx: DoubleArray,
    val htfPlusDi: DoubleArray,
    val htfMinusDi: DoubleArray,
    /** Strategy-specific series (Bollinger bands, opening range, …). */
    val extras: Map<String, DoubleArray> = emptyMap(),
) {
    val size: Int get() = ltf.size

    fun withExtras(more: Map<String, DoubleArray>) = PreparedSeries(
        ltf, htf, htfIndex, ema, rsi, atr, volSma, donchianHigh, donchianLow,
        htfEmaFast, htfEmaSlow, htfAdx, htfPlusDi, htfMinusDi, extras + more,
    )
}

/**
 * Multi-timeframe trend-following strategy.
 *
 * 1. Regime (4H): EMA50 vs EMA200, price vs EMA50 and ADX strength decide whether we are allowed
 *    to look for longs, shorts, or nothing at all. Ranging markets are skipped entirely.
 * 2. Entry (1H), only in the regime direction:
 *    - Pullback: RSI resets below 45 (above 55 for shorts), price tags EMA20, then a bar closes back
 *      above EMA20 with RSI > 50 and rising (momentum resumes).
 *    - Breakout: close beyond the 20-bar Donchian channel on above-average volume.
 * 3. Exit: initial stop at 2 ATR, break-even after +1R, chandelier trailing stop at 3 ATR from the
 *    best price, trend-break exit when the 4H trend structure flips, and a time stop.
 */
class TrendStrategy(val cfg: StrategyConfig = StrategyConfig()) : Strategy {
    override val type = StrategyType.TREND
    override val ltfFrame: Timeframe get() = cfg.ltfFrame
    override val htfFrame: Timeframe get() = cfg.htfFrame
    override val ltfWarmup: Int get() = cfg.ltfWarmup
    override val htfWarmup: Int get() = cfg.htfWarmup

    override fun prepare(ltf: List<Candle>, htf: List<Candle>): PreparedSeries = prepare(ltf, htf, cfg.ltfFrame, cfg.htfFrame)

    fun prepare(
        ltf: List<Candle>,
        htf: List<Candle>,
        ltfFrame: Timeframe,
        htfFrame: Timeframe,
    ): PreparedSeries {
        val closes = ltf.closes()
        val htfCloses = htf.closes()
        val adx = Indicators.adx(htf, cfg.adxPeriod)
        val volSma = Indicators.sma(ltf.volumes(), 20)
        return PreparedSeries(
            ltf = ltf,
            htf = htf,
            htfIndex = alignHigherTimeframe(ltf, htf, ltfFrame.millis, htfFrame.millis),
            ema = Indicators.ema(closes, cfg.ltfEma),
            rsi = Indicators.rsi(closes, cfg.rsiPeriod),
            atr = Indicators.atr(ltf, cfg.atrPeriod),
            volSma = volSma,
            donchianHigh = Indicators.highest(ltf.highs(), cfg.breakoutLookback),
            donchianLow = Indicators.lowest(ltf.lows(), cfg.breakoutLookback),
            htfEmaFast = Indicators.ema(htfCloses, cfg.htfEmaFast),
            htfEmaSlow = Indicators.ema(htfCloses, cfg.htfEmaSlow),
            htfAdx = adx.adx,
            htfPlusDi = adx.plusDi,
            htfMinusDi = adx.minusDi,
        )
    }

    fun regime(p: PreparedSeries, i: Int): Regime {
        val j = p.htfIndex.getOrElse(i) { -1 }
        if (j < 0) return Regime.NEUTRAL
        val f = p.htfEmaFast[j]
        val s = p.htfEmaSlow[j]
        val a = p.htfAdx[j]
        if (f.isNaN() || s.isNaN() || a.isNaN()) return Regime.NEUTRAL
        val c = p.htf[j].close
        val strong = a >= cfg.adxMin
        return when {
            f > s && c > f && strong && p.htfPlusDi[j] > p.htfMinusDi[j] -> Regime.BULL
            f < s && c < f && strong && p.htfMinusDi[j] > p.htfPlusDi[j] -> Regime.BEAR
            else -> Regime.NEUTRAL
        }
    }

    /** True when the higher-timeframe structure no longer supports a position on [side]. */
    fun trendBroken(p: PreparedSeries, i: Int, side: Side): Boolean {
        val j = p.htfIndex.getOrElse(i) { -1 }
        if (j < 0) return false
        val f = p.htfEmaFast[j]
        val s = p.htfEmaSlow[j]
        if (f.isNaN() || s.isNaN()) return false
        val c = p.htf[j].close
        return when (side) {
            Side.LONG -> f < s || c < s
            Side.SHORT -> f > s || c > s
        }
    }

    override fun entrySignal(p: PreparedSeries, i: Int, fundingRate: Double?): EntrySignal? {
        if (i < cfg.ltfWarmup || i >= p.size) return null
        val side = when (regime(p, i)) {
            Regime.BULL -> if (cfg.allowLongs) Side.LONG else return null
            Regime.BEAR -> if (cfg.allowShorts) Side.SHORT else return null
            Regime.NEUTRAL -> return null
        }
        val bar = p.ltf[i]
        if (!cfg.session.allows(bar.time + cfg.ltfFrame.millis)) return null
        val atr = p.atr[i]
        val ema = p.ema[i]
        val rsi = p.rsi[i]
        if (atr.isNaN() || ema.isNaN() || rsi.isNaN()) return null
        val atrPct = atr / bar.close * 100.0
        if (atrPct < cfg.minAtrPct || atrPct > cfg.maxAtrPct) return null
        if (fundingRate != null) {
            val fPct = fundingRate * 100.0
            if (side == Side.LONG && fPct > cfg.maxFundingRatePct) return null
            if (side == Side.SHORT && fPct < -cfg.maxFundingRatePct) return null
        }

        val type: EntryType = when {
            cfg.useBreakout && isBreakout(p, i, side) -> EntryType.BREAKOUT
            cfg.usePullback && isPullbackResume(p, i, side) -> EntryType.PULLBACK
            else -> return null
        }
        val j = p.htfIndex[i]
        val adx = p.htfAdx[j]
        val stop = bar.close - side.sign * cfg.stopAtrMult * atr
        val reason = buildString {
            append(if (side == Side.LONG) "4H多頭" else "4H空頭")
            append("(ADX ").append("%.1f".format(adx)).append(")，1H ")
            append(type.label)
            append("，RSI ").append("%.1f".format(rsi))
        }
        return EntrySignal(
            side = side,
            type = type.name,
            barTime = bar.time,
            price = bar.close,
            atr = atr,
            suggestedStop = stop,
            strength = adx + if (type == EntryType.BREAKOUT) 5.0 else 0.0,
            reason = reason,
        )
    }

    private fun isBreakout(p: PreparedSeries, i: Int, side: Side): Boolean {
        val bar = p.ltf[i]
        val prevHigh = p.donchianHigh[i - 1]
        val prevLow = p.donchianLow[i - 1]
        val vAvg = p.volSma[i - 1]
        if (prevHigh.isNaN() || prevLow.isNaN() || vAvg.isNaN() || vAvg <= 0) return false
        if (bar.volume < cfg.breakoutVolumeMult * vAvg) return false
        val range = bar.high - bar.low
        if (range <= 0) return false
        val closeLocation = (bar.close - bar.low) / range
        return when (side) {
            Side.LONG -> bar.close > prevHigh && closeLocation >= 0.5
            Side.SHORT -> bar.close < prevLow && closeLocation <= 0.5
        }
    }

    private fun isPullbackResume(p: PreparedSeries, i: Int, side: Side): Boolean {
        val bar = p.ltf[i]
        val from = i - cfg.pullbackLookback
        if (from < 1) return false
        var rsiReset = false
        var touched = false
        for (k in from until i) {
            val r = p.rsi[k]
            if (r.isNaN() || p.ema[k].isNaN()) return false
            when (side) {
                Side.LONG -> {
                    if (r < cfg.pullbackRsi) rsiReset = true
                    if (p.ltf[k].low <= p.ema[k]) touched = true
                }
                Side.SHORT -> {
                    if (r > 100.0 - cfg.pullbackRsi) rsiReset = true
                    if (p.ltf[k].high >= p.ema[k]) touched = true
                }
            }
        }
        if (!rsiReset || !touched) return false
        val rsi = p.rsi[i]
        val prevRsi = p.rsi[i - 1]
        return when (side) {
            Side.LONG -> bar.close > p.ema[i] && bar.close > bar.open && rsi > cfg.resumeRsi && rsi > prevRsi
            Side.SHORT -> bar.close < p.ema[i] && bar.close < bar.open && rsi < 100.0 - cfg.resumeRsi && rsi < prevRsi
        }
    }

    fun initialStop(side: Side, entryPrice: Double, atr: Double): Double =
        entryPrice - side.sign * cfg.stopAtrMult * atr

    fun takeProfit(side: Side, entryPrice: Double, stop: Double): Double? =
        if (cfg.takeProfitR > 0) entryPrice + side.sign * cfg.takeProfitR * abs(entryPrice - stop) else null

    override fun initialStop(signal: EntrySignal, entry: Double): Double = initialStop(signal.side, entry, signal.atr)

    override fun takeProfit(signal: EntrySignal, entry: Double, stop: Double): Double? = takeProfit(signal.side, entry, stop)

    /**
     * Updates the protective stop after bar [i] closed and decides whether to exit at its close.
     * The stop only ever moves in the trade's favour.
     */
    override fun manage(pos: PositionTrack, p: PreparedSeries, i: Int): ManageResult {
        val bar = p.ltf[i]
        if (bar.time <= pos.lastBarTime) return ManageResult(pos, null)
        val side = pos.side
        val extreme = if (side == Side.LONG) max(pos.extreme, bar.high) else min(pos.extreme, bar.low)
        val bars = pos.barsHeld + 1
        val r = pos.riskPerUnit
        var stop = pos.stop
        var beDone = pos.breakEvenDone

        fun tighten(candidate: Double) {
            if (candidate.isNaN()) return
            stop = if (side == Side.LONG) max(stop, candidate) else min(stop, candidate)
        }

        val bestR = if (r == 0.0) 0.0 else (extreme - pos.entryPrice) * side.sign / r
        if (cfg.breakEvenR > 0 && !beDone && bestR >= cfg.breakEvenR) {
            tighten(pos.entryPrice + side.sign * 0.1 * r)
            beDone = true
        }
        val atr = p.atr[i]
        if (!atr.isNaN()) tighten(extreme - side.sign * cfg.trailAtrMult * atr)

        val updated = pos.copy(
            stop = stop,
            extreme = extreme,
            barsHeld = bars,
            breakEvenDone = beDone,
            lastBarTime = bar.time,
        )
        val exitReason = when {
            cfg.exitOnTrendBreak && trendBroken(p, i, side) -> "4H趨勢結構破壞"
            cfg.maxHoldBars > 0 && bars >= cfg.maxHoldBars && updated.rMultiple(bar.close) < 0.5 -> "時間止損"
            else -> null
        }
        return ManageResult(updated, exitReason)
    }

    companion object {
        /**
         * For every lower-timeframe bar, finds the newest higher-timeframe bar that had already
         * closed when the lower-timeframe bar closed (no look-ahead).
         */
        fun alignHigherTimeframe(ltf: List<Candle>, htf: List<Candle>, ltfMillis: Long, htfMillis: Long): IntArray {
            val out = IntArray(ltf.size) { -1 }
            var j = -1
            for (i in ltf.indices) {
                val ltfClose = ltf[i].time + ltfMillis
                while (j + 1 < htf.size && htf[j + 1].time + htfMillis <= ltfClose) j++
                out[i] = j
            }
            return out
        }
    }
}
