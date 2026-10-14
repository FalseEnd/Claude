package com.perppilot.core.strategy

import com.perppilot.core.indicators.Indicators
import com.perppilot.core.indicators.closes
import com.perppilot.core.model.Candle
import com.perppilot.core.model.Side
import com.perppilot.core.model.Timeframe
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

@Serializable
data class MeanRevConfig(
    val ltfFrame: Timeframe = Timeframe.M15,
    val htfFrame: Timeframe = Timeframe.H4,
    val bbLength: Int = 20,
    val bbMult: Double = 2.0,
    val rsiLength: Int = 2,
    /** Long when RSI is below this, short when above 100 − this. */
    val rsiExtreme: Double = 10.0,
    /** Only fade when the higher timeframe is not trending (ADX below this). 0 = off. */
    val maxHtfAdx: Double = 20.0,
    val stopAtrMult: Double = 1.5,
    val maxHoldBars: Int = 12,
    val session: TradingSession = TradingSession.ALL,
)

/**
 * Short-term mean reversion: in a non-trending higher timeframe, fade closes outside the
 * Bollinger band when a 2-period RSI is extreme; exit when price returns to the middle band.
 */
class MeanReversionStrategy(val cfg: MeanRevConfig = MeanRevConfig()) : Strategy {
    override val type = StrategyType.MEAN_REVERSION
    override val ltfFrame get() = cfg.ltfFrame
    override val htfFrame get() = cfg.htfFrame
    override val ltfWarmup: Int get() = max(cfg.bbLength, 14) + 2
    override val htfWarmup: Int get() = 60
    private val base = TrendStrategy(StrategyConfig(ltfFrame = cfg.ltfFrame, htfFrame = cfg.htfFrame))

    override fun prepare(ltf: List<Candle>, htf: List<Candle>): PreparedSeries {
        val closes = ltf.closes()
        val bb = Indicators.bollinger(closes, cfg.bbLength, cfg.bbMult)
        return base.prepare(ltf, htf).withExtras(
            mapOf(
                "bbMid" to bb.middle, "bbUp" to bb.upper, "bbLow" to bb.lower,
                "rsiFast" to Indicators.rsi(closes, cfg.rsiLength),
            ),
        )
    }

    override fun entrySignal(p: PreparedSeries, i: Int, fundingRate: Double?): EntrySignal? {
        if (i < ltfWarmup) return null
        val bar = p.ltf[i]
        if (!cfg.session.allows(bar.time + cfg.ltfFrame.millis)) return null
        if (cfg.maxHtfAdx > 0) {
            val j = p.htfIndex[i]
            val adx = if (j >= 0) p.htfAdx[j] else Double.NaN
            if (adx.isNaN() || adx >= cfg.maxHtfAdx) return null
        }
        val up = p.extras.getValue("bbUp")[i]
        val lo = p.extras.getValue("bbLow")[i]
        val rsi = p.extras.getValue("rsiFast")[i]
        val atr = p.atr[i]
        if (up.isNaN() || lo.isNaN() || rsi.isNaN() || atr.isNaN()) return null
        val side = when {
            bar.close < lo && rsi < cfg.rsiExtreme -> Side.LONG
            bar.close > up && rsi > 100 - cfg.rsiExtreme -> Side.SHORT
            else -> return null
        }
        return EntrySignal(
            side = side,
            type = StrategyType.MEAN_REVERSION.name,
            barTime = bar.time,
            price = bar.close,
            atr = atr,
            suggestedStop = bar.close - side.sign * cfg.stopAtrMult * atr,
            strength = if (side == Side.LONG) 100 - rsi else rsi,
            reason = "收盤在布林通道${if (side == Side.LONG) "下緣之下" else "上緣之上"}，RSI(${cfg.rsiLength}) ${"%.0f".format(rsi)}，大週期無趨勢",
        )
    }

    override fun initialStop(signal: EntrySignal, entry: Double): Double = entry - signal.side.sign * cfg.stopAtrMult * signal.atr

    override fun takeProfit(signal: EntrySignal, entry: Double, stop: Double): Double? = null

    override fun manage(pos: PositionTrack, p: PreparedSeries, i: Int): ManageResult {
        val bar = p.ltf[i]
        if (bar.time <= pos.lastBarTime) return ManageResult(pos, null)
        val extreme = if (pos.side == Side.LONG) max(pos.extreme, bar.high) else min(pos.extreme, bar.low)
        val updated = pos.copy(extreme = extreme, barsHeld = pos.barsHeld + 1, lastBarTime = bar.time)
        val mid = p.extras.getValue("bbMid")[i]
        val reverted = !mid.isNaN() && (bar.close - mid) * pos.side.sign >= 0
        val exit = when {
            reverted -> "回歸均線"
            cfg.maxHoldBars > 0 && updated.barsHeld >= cfg.maxHoldBars -> "時間到"
            else -> null
        }
        return ManageResult(updated, exit)
    }
}
