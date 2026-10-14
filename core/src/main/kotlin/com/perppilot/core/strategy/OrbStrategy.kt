package com.perppilot.core.strategy

import com.perppilot.core.indicators.Indicators
import com.perppilot.core.indicators.closes
import com.perppilot.core.indicators.volumes
import com.perppilot.core.model.Candle
import com.perppilot.core.model.Side
import com.perppilot.core.model.Timeframe
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Serializable
data class OrbConfig(
    val ltfFrame: Timeframe = Timeframe.M5,
    val htfFrame: Timeframe = Timeframe.H1,
    val zone: String = "America/New_York",
    /** Session open, minutes after local midnight (09:30). */
    val openMinute: Int = 9 * 60 + 30,
    val rangeMinutes: Int = 15,
    /** No new entries after this local time. */
    val entryUntilMinute: Int = 12 * 60,
    /** Flat by this local time (US cash close). */
    val exitMinute: Int = 16 * 60,
    /** Stop at the opposite side of the range ("range") or at its midpoint ("mid"). */
    val stopAtMid: Boolean = false,
    val takeProfitR: Double = 2.0,
    /** Only trade in the direction of the higher-timeframe EMA50/EMA200 trend. */
    val trendFilter: Boolean = true,
    val minRangePct: Double = 0.10,
    val maxRangePct: Double = 2.5,
    /** Breakout bar volume must exceed this multiple of the 20-bar average (0 = off). */
    val volumeMult: Double = 0.0,
    val breakEvenR: Double = 0.0,
)

/**
 * Opening-range breakout around the US equity open, when crypto volume and volatility spike.
 * The first close outside the first [OrbConfig.rangeMinutes] range triggers one trade per day;
 * the stop sits at the other side of the range, the target is a fixed R multiple, and any open
 * trade is closed at the US cash close.
 */
class OrbStrategy(val cfg: OrbConfig = OrbConfig()) : Strategy {
    override val type = StrategyType.ORB
    override val ltfFrame get() = cfg.ltfFrame
    override val htfFrame get() = cfg.htfFrame
    override val ltfWarmup: Int get() = 30
    override val htfWarmup: Int get() = 210
    private val zone = ZoneId.of(cfg.zone)
    private val base = TrendStrategy(StrategyConfig(ltfFrame = cfg.ltfFrame, htfFrame = cfg.htfFrame))

    override fun prepare(ltf: List<Candle>, htf: List<Candle>): PreparedSeries {
        val n = ltf.size
        val orHigh = DoubleArray(n) { Double.NaN }
        val orLow = DoubleArray(n) { Double.NaN }
        /** Index of the first bar after the range, per bar of the same session (as Double for storage). */
        val rangeEnd = DoubleArray(n) { Double.NaN }
        val minuteOfClose = DoubleArray(n)
        val rangeStart = cfg.openMinute
        val rangeStop = cfg.openMinute + cfg.rangeMinutes
        var day = -1L
        var hi = Double.NEGATIVE_INFINITY
        var lo = Double.POSITIVE_INFINITY
        var complete = false
        var firstAfter = -1
        for (i in 0 until n) {
            val open = Instant.ofEpochMilli(ltf[i].time).atZone(zone)
            val d = open.toLocalDate().toEpochDay()
            val m = open.hour * 60 + open.minute
            minuteOfClose[i] = (m + cfg.ltfFrame.millis / 60_000).toDouble()
            if (d != day) {
                day = d; hi = Double.NEGATIVE_INFINITY; lo = Double.POSITIVE_INFINITY; complete = false; firstAfter = -1
            }
            val weekday = open.dayOfWeek != DayOfWeek.SATURDAY && open.dayOfWeek != DayOfWeek.SUNDAY
            if (!weekday) continue
            if (m >= rangeStart && m < rangeStop) {
                hi = max(hi, ltf[i].high); lo = min(lo, ltf[i].low)
            } else if (m >= rangeStop && hi > lo) {
                if (!complete) { complete = true; firstAfter = i }
                orHigh[i] = hi; orLow[i] = lo; rangeEnd[i] = firstAfter.toDouble()
            }
        }
        return base.prepare(ltf, htf).withExtras(
            mapOf(
                "orHigh" to orHigh, "orLow" to orLow, "rangeEnd" to rangeEnd, "minute" to minuteOfClose,
                "volSma" to Indicators.sma(ltf.volumes(), 20), "close" to ltf.closes(),
            ),
        )
    }

    override fun entrySignal(p: PreparedSeries, i: Int, fundingRate: Double?): EntrySignal? {
        val hi = p.extras.getValue("orHigh")[i]
        val lo = p.extras.getValue("orLow")[i]
        if (hi.isNaN() || lo.isNaN()) return null
        if (p.extras.getValue("minute")[i] > cfg.entryUntilMinute) return null
        val bar = p.ltf[i]
        val widthPct = (hi - lo) / bar.close * 100
        if (widthPct < cfg.minRangePct || widthPct > cfg.maxRangePct) return null
        // Only the first close outside the range counts.
        val start = p.extras.getValue("rangeEnd")[i].toInt()
        for (k in start until i) if (p.ltf[k].close > hi || p.ltf[k].close < lo) return null
        val side = when {
            bar.close > hi -> Side.LONG
            bar.close < lo -> Side.SHORT
            else -> return null
        }
        if (cfg.trendFilter) {
            val j = p.htfIndex[i]
            if (j < 0) return null
            val f = p.htfEmaFast[j]
            val s = p.htfEmaSlow[j]
            if (f.isNaN() || s.isNaN()) return null
            if (side == Side.LONG && f < s || side == Side.SHORT && f > s) return null
        }
        if (cfg.volumeMult > 0) {
            val v = p.extras.getValue("volSma")[i - 1]
            if (v.isNaN() || bar.volume < cfg.volumeMult * v) return null
        }
        val stop = if (cfg.stopAtMid) (hi + lo) / 2 else if (side == Side.LONG) lo else hi
        return EntrySignal(
            side = side,
            type = StrategyType.ORB.name,
            barTime = bar.time,
            price = bar.close,
            atr = p.atr[i],
            suggestedStop = stop,
            strength = 1.0 / widthPct,
            reason = "美股開盤 ${cfg.rangeMinutes} 分鐘區間 ${"%.2f".format(widthPct)}% ${if (side == Side.LONG) "向上" else "向下"}突破",
        )
    }

    override fun initialStop(signal: EntrySignal, entry: Double): Double {
        val level = signal.suggestedStop
        // Keep the structural level unless the fill already crossed it.
        return if ((entry - level) * signal.side.sign > 0) level else entry - signal.side.sign * abs(signal.price - level)
    }

    override fun takeProfit(signal: EntrySignal, entry: Double, stop: Double): Double? =
        if (cfg.takeProfitR > 0) entry + signal.side.sign * cfg.takeProfitR * abs(entry - stop) else null

    override fun manage(pos: PositionTrack, p: PreparedSeries, i: Int): ManageResult {
        val bar = p.ltf[i]
        if (bar.time <= pos.lastBarTime) return ManageResult(pos, null)
        val extreme = if (pos.side == Side.LONG) max(pos.extreme, bar.high) else min(pos.extreme, bar.low)
        var stop = pos.stop
        var be = pos.breakEvenDone
        if (cfg.breakEvenR > 0 && !be && pos.riskPerUnit > 0 && (extreme - pos.entryPrice) * pos.side.sign >= cfg.breakEvenR * pos.riskPerUnit) {
            stop = if (pos.side == Side.LONG) max(stop, pos.entryPrice) else min(stop, pos.entryPrice)
            be = true
        }
        val updated = pos.copy(stop = stop, extreme = extreme, barsHeld = pos.barsHeld + 1, breakEvenDone = be, lastBarTime = bar.time)
        val minute = p.extras.getValue("minute")[i]
        val sameDay = bar.time - pos.entryTime < 20 * 3_600_000L
        val exit = if (minute >= cfg.exitMinute || !sameDay) "美股收盤平倉" else null
        return ManageResult(updated, exit)
    }
}
