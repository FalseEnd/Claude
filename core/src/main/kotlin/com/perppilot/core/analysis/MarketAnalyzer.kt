package com.perppilot.core.analysis

import com.perppilot.core.indicators.Indicators
import com.perppilot.core.indicators.closes
import com.perppilot.core.model.Candle
import com.perppilot.core.model.FundingInfo
import com.perppilot.core.model.Side
import com.perppilot.core.model.Ticker
import com.perppilot.core.strategy.EntrySignal
import com.perppilot.core.strategy.Regime
import com.perppilot.core.strategy.TrendStrategy
import kotlin.math.abs
import kotlin.math.roundToInt

data class Factor(val name: String, val value: String, val score: Double)

data class TradePlan(
    val side: Side,
    val entry: Double,
    val stop: Double,
    val target1: Double,
    val target2: Double,
    val riskPct: Double,
)

data class AnalysisSnapshot(
    val instId: String,
    val ticker: Ticker?,
    val fundingRatePct: Double?,
    val nextFundingTime: Long?,
    val openInterestUsd: Double?,
    val longShortRatio: Double?,
    val regime: Regime,
    val htfAdx: Double,
    val rsi1h: Double,
    val rsi4h: Double,
    val atrPct: Double,
    val macdHist: Double,
    val bbWidthPct: Double,
    val ema20: Double,
    val htfEmaFast: Double,
    val htfEmaSlow: Double,
    val breakoutHigh: Double,
    val breakoutLow: Double,
    val score: Int,
    val bias: String,
    val factors: List<Factor>,
    val signal: EntrySignal?,
    val plan: TradePlan?,
    val ltf: List<Candle>,
)

/** Turns raw market data into a scored, explainable view of one perpetual contract. */
object MarketAnalyzer {

    fun analyze(
        instId: String,
        ltf: List<Candle>,
        htf: List<Candle>,
        strategy: TrendStrategy,
        ticker: Ticker? = null,
        funding: FundingInfo? = null,
        openInterestUsd: Double? = null,
        longShortRatio: Double? = null,
    ): AnalysisSnapshot {
        val p = strategy.prepare(ltf, htf)
        val i = p.size - 1
        val j = p.htfIndex.getOrElse(i) { -1 }
        val closes = ltf.closes()
        val close = ltf.lastOrNull()?.close ?: 0.0
        val macd = Indicators.macd(closes)
        val bb = Indicators.bollinger(closes)
        val ema50 = Indicators.ema(closes, 50)
        val rsi4h = Indicators.rsi(htf.closes(), 14)

        fun at(a: DoubleArray, k: Int) = if (k in a.indices) a[k] else Double.NaN

        val regime = strategy.regime(p, i)
        val adx = at(p.htfAdx, j)
        val emaF = at(p.htfEmaFast, j)
        val emaS = at(p.htfEmaSlow, j)
        val htfClose = if (j >= 0) htf[j].close else Double.NaN
        val rsi = at(p.rsi, i)
        val atr = at(p.atr, i)
        val ema20 = at(p.ema, i)
        val hist = at(macd.histogram, i)

        val factors = mutableListOf<Factor>()
        val trendWeight = if (!adx.isNaN() && adx < 20) 0.6 else 1.0
        if (!emaF.isNaN() && !emaS.isNaN()) {
            factors += Factor("4H 均線排列", if (emaF > emaS) "EMA50 > EMA200" else "EMA50 < EMA200", (if (emaF > emaS) 20.0 else -20.0) * trendWeight)
            factors += Factor("4H 價格位置", if (htfClose > emaF) "站上 EMA50" else "跌破 EMA50", (if (htfClose > emaF) 10.0 else -10.0) * trendWeight)
        }
        if (!adx.isNaN()) factors += Factor("4H 趨勢強度", "ADX %.1f".format(adx), 0.0)
        if (!ema20.isNaN()) {
            factors += Factor("1H 短線趨勢", if (close > ema20) "站上 EMA20" else "跌破 EMA20", if (close > ema20) 10.0 else -10.0)
        }
        val e50 = at(ema50, i)
        if (!ema20.isNaN() && !e50.isNaN()) {
            factors += Factor("1H 均線", if (ema20 > e50) "EMA20 > EMA50" else "EMA20 < EMA50", if (ema20 > e50) 5.0 else -5.0)
        }
        if (!rsi.isNaN()) factors += Factor("1H RSI", "%.1f".format(rsi), (rsi - 50.0) / 50.0 * 15.0)
        if (!hist.isNaN()) factors += Factor("1H MACD 柱", if (hist > 0) "多方動能" else "空方動能", if (hist > 0) 10.0 else -10.0)

        val fundingPct = funding?.rate?.times(100.0)
        if (fundingPct != null) {
            val s = when {
                fundingPct > 0.05 -> -10.0
                fundingPct > 0.03 -> -5.0
                fundingPct < -0.05 -> 10.0
                fundingPct < -0.03 -> 5.0
                else -> 0.0
            }
            factors += Factor("資金費率(反向指標)", "%.4f%%".format(fundingPct), s)
        }
        if (longShortRatio != null) {
            val s = when {
                longShortRatio > 2.5 -> -5.0
                longShortRatio < 0.8 -> 5.0
                else -> 0.0
            }
            factors += Factor("多空人數比(反向指標)", "%.2f".format(longShortRatio), s)
        }
        val score = factors.sumOf { it.score }.coerceIn(-100.0, 100.0).roundToInt()
        val bias = when {
            score >= 50 -> "強烈看多"
            score >= 20 -> "偏多"
            score > -20 -> "中性／震盪"
            score > -50 -> "偏空"
            else -> "強烈看空"
        }

        val signal = strategy.entrySignal(p, i, funding?.rate)
        val plan = signal?.let { sig ->
            val risk = abs(sig.price - sig.suggestedStop)
            TradePlan(
                side = sig.side,
                entry = sig.price,
                stop = sig.suggestedStop,
                target1 = sig.price + sig.side.sign * 2 * risk,
                target2 = sig.price + sig.side.sign * 3 * risk,
                riskPct = risk / sig.price * 100.0,
            )
        }

        return AnalysisSnapshot(
            instId = instId,
            ticker = ticker,
            fundingRatePct = fundingPct,
            nextFundingTime = funding?.nextFundingTime,
            openInterestUsd = openInterestUsd,
            longShortRatio = longShortRatio,
            regime = regime,
            htfAdx = adx,
            rsi1h = rsi,
            rsi4h = rsi4h.lastOrNull() ?: Double.NaN,
            atrPct = if (close > 0) atr / close * 100.0 else Double.NaN,
            macdHist = hist,
            bbWidthPct = bb.width(i) * 100.0,
            ema20 = ema20,
            htfEmaFast = emaF,
            htfEmaSlow = emaS,
            breakoutHigh = at(p.donchianHigh, i),
            breakoutLow = at(p.donchianLow, i),
            score = score,
            bias = bias,
            factors = factors,
            signal = signal,
            plan = plan,
            ltf = ltf,
        )
    }
}
