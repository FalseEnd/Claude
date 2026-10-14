package com.perppilot.core.backtest

import com.perppilot.core.model.Candle
import com.perppilot.core.model.Side
import com.perppilot.core.risk.RiskConfig
import com.perppilot.core.risk.RiskManager
import com.perppilot.core.risk.RiskState
import com.perppilot.core.strategy.EntrySignal
import com.perppilot.core.strategy.PositionTrack
import com.perppilot.core.strategy.PreparedSeries
import com.perppilot.core.strategy.TrendStrategy
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

@Serializable
data class BacktestConfig(
    val initialEquity: Double = 10_000.0,
    /** Taker fee per side, percent of notional (OKX VIP0 swap taker: 0.05%). */
    val takerFeePct: Double = 0.05,
    /** Adverse slippage per fill, percent of price. */
    val slippagePct: Double = 0.02,
    /** Funding charged on every open position each 8h, regardless of side (conservative). */
    val fundingPctPer8h: Double = 0.01,
    /** After the drawdown kill switch fires, resume after this many hours (simulates a manual review). */
    val drawdownPauseHours: Int = 72,
    /** Only trades whose signal bar is at/after this time are taken (indicators still warm up before it). */
    val startTime: Long = 0,
    val endTime: Long = Long.MAX_VALUE,
)

data class SymbolData(val instId: String, val ltf: List<Candle>, val htf: List<Candle>)

@Serializable
data class BacktestTrade(
    val instId: String,
    val side: Side,
    val entryType: String,
    val entryTime: Long,
    val exitTime: Long,
    val entryPrice: Double,
    val exitPrice: Double,
    val quantity: Double,
    val pnl: Double,
    val fees: Double,
    val rMultiple: Double,
    val barsHeld: Int,
    val exitReason: String,
)

@Serializable
data class BacktestMetrics(
    val initialEquity: Double,
    val finalEquity: Double,
    val totalReturnPct: Double,
    val cagrPct: Double,
    val maxDrawdownPct: Double,
    val sharpe: Double,
    val calmar: Double,
    val trades: Int,
    val winRatePct: Double,
    val profitFactor: Double,
    val expectancyR: Double,
    val avgWinR: Double,
    val avgLossR: Double,
    val maxConsecutiveLosses: Int,
    val avgBarsHeld: Double,
    val exposurePct: Double,
    val totalFees: Double,
    val longTrades: Int,
    val shortTrades: Int,
    val killSwitchTriggers: Int,
    val days: Double,
)

data class BacktestResult(
    val metrics: BacktestMetrics,
    val trades: List<BacktestTrade>,
    /** (time, marked-to-market equity) sampled every bar. */
    val equityCurve: List<Pair<Long, Double>>,
)

/**
 * Bar-by-bar portfolio simulator that shares the exact strategy and risk code used for live trading.
 *
 * - Signals are generated on bar close and filled at the next bar's open (no look-ahead).
 * - Stops are checked against the bar's high/low; a gap through the stop fills at the open.
 * - If a stop and a take-profit are both inside one bar, the stop is assumed to fill first.
 * - Fees, slippage and funding are charged on every fill / holding period.
 */
class Backtester(
    private val strategy: TrendStrategy,
    private val risk: RiskConfig,
    private val config: BacktestConfig = BacktestConfig(),
) {
    private class OpenPos(var track: PositionTrack, val riskAmount: Double, var fees: Double)

    fun run(data: List<SymbolData>, onProgress: ((Double) -> Unit)? = null): BacktestResult {
        val prepared = data.associate { it.instId to strategy.prepare(it.ltf, it.htf) }
        val indexByTime: Map<String, Map<Long, Int>> = prepared.mapValues { (_, p) ->
            HashMap<Long, Int>(p.size * 2).apply { p.ltf.forEachIndexed { i, c -> put(c.time, i) } }
        }
        val timeline = prepared.values.flatMap { p -> p.ltf.map { it.time } }.toSortedSet().toList()
        val fee = config.takerFeePct / 100.0
        val slip = config.slippagePct / 100.0
        val fundingPerBar = config.fundingPctPer8h / 100.0 / 8.0

        var cash = config.initialEquity
        val open = LinkedHashMap<String, OpenPos>()
        val pending = LinkedHashMap<String, EntrySignal>()
        val trades = ArrayList<BacktestTrade>()
        val curve = ArrayList<Pair<Long, Double>>(timeline.size)
        var riskState = RiskState()
        var lastEquity = cash
        var barsWithExposure = 0
        var killSwitch = 0
        var lastHalt: String? = null
        val lastClose = HashMap<String, Double>()

        fun closePos(instId: String, pos: OpenPos, rawPrice: Double, time: Long, reason: String) {
            val t = pos.track
            val px = rawPrice * (1 - t.side.sign * slip)
            val exitFee = t.size * px * fee
            val gross = t.size * (px - t.entryPrice) * t.side.sign
            cash += gross - exitFee
            val totalFees = pos.fees + exitFee
            val pnl = gross - totalFees
            trades += BacktestTrade(
                instId = instId,
                side = t.side,
                entryType = t.entryType,
                entryTime = t.entryTime,
                exitTime = time,
                entryPrice = t.entryPrice,
                exitPrice = px,
                quantity = t.size,
                pnl = pnl,
                fees = totalFees,
                rMultiple = if (pos.riskAmount > 0) pnl / pos.riskAmount else 0.0,
                barsHeld = t.barsHeld,
                exitReason = reason,
            )
            open.remove(instId)
            riskState = RiskManager.onTradeClosed(riskState, pnl, time, risk)
        }

        timeline.forEachIndexed { step, time ->
            // 1) Fill pending entries at this bar's open.
            if (pending.isNotEmpty()) {
                val queued = pending.entries.sortedByDescending { it.value.strength }.map { it.key to it.value }
                pending.clear()
                for ((instId, sig) in queued) {
                    val p = prepared.getValue(instId)
                    val i = indexByTime.getValue(instId)[time] ?: continue
                    if (open.containsKey(instId)) continue
                    val openRiskPct = open.values.sumOf { it.riskAmount } / max(lastEquity, 1e-9) * 100.0
                    val decision = RiskManager.canOpen(riskState, time, risk, open.size, openRiskPct)
                    if (!decision.allowed) continue
                    val bar = p.ltf[i]
                    val entry = bar.open * (1 + sig.side.sign * slip)
                    val stop = strategy.initialStop(sig.side, entry, sig.atr)
                    val size = RiskManager.size(lastEquity, entry, stop, risk) ?: continue
                    val entryFee = size.notional * fee
                    cash -= entryFee
                    open[instId] = OpenPos(
                        track = PositionTrack(
                            instId = instId,
                            side = sig.side,
                            entryTime = bar.time,
                            entryPrice = entry,
                            initialStop = stop,
                            stop = stop,
                            takeProfit = strategy.takeProfit(sig.side, entry, stop),
                            size = size.quantity,
                            extreme = entry,
                            entryType = sig.type,
                            lastBarTime = bar.time - 1,
                        ),
                        riskAmount = size.riskAmount,
                        fees = entryFee,
                    )
                }
            }

            // 2) Intrabar stop / take-profit, then 3) bar-close management.
            for ((instId, pos) in open.entries.toList()) {
                val i = indexByTime.getValue(instId)[time] ?: continue
                val p = prepared.getValue(instId)
                val bar = p.ltf[i]
                val t = pos.track
                val stopHit = if (t.side == Side.LONG) bar.low <= t.stop else bar.high >= t.stop
                val tp = t.takeProfit
                val tpHit = tp != null && (if (t.side == Side.LONG) bar.high >= tp else bar.low <= tp)
                if (stopHit) {
                    val fill = if (t.side == Side.LONG) min(bar.open, t.stop) else max(bar.open, t.stop)
                    closePos(instId, pos, fill, time, if (t.breakEvenDone) "移動止損" else "止損")
                    continue
                }
                if (tpHit && tp != null) {
                    val fill = if (t.side == Side.LONG) max(bar.open, tp) else min(bar.open, tp)
                    closePos(instId, pos, fill, time, "止盈")
                    continue
                }
                cash -= t.size * bar.close * fundingPerBar
                pos.fees += t.size * bar.close * fundingPerBar
                val managed = strategy.manage(t, p, i)
                pos.track = managed.position
                if (managed.exitReason != null) closePos(instId, pos, bar.close, time, managed.exitReason)
            }

            // 4) Mark to market.
            for ((instId, idx) in indexByTime) {
                idx[time]?.let { lastClose[instId] = prepared.getValue(instId).ltf[it].close }
            }
            val unrealized = open.entries.sumOf { (instId, pos) ->
                val px = lastClose[instId] ?: pos.track.entryPrice
                pos.track.size * (px - pos.track.entryPrice) * pos.track.side.sign
            }
            lastEquity = cash + unrealized
            curve += time to lastEquity
            if (open.isNotEmpty()) barsWithExposure++
            riskState = RiskManager.onEquity(riskState, lastEquity, time, risk, config.drawdownPauseHours)
            if (riskState.haltReason != null && riskState.haltReason != lastHalt && riskState.haltReason!!.contains("回撤")) killSwitch++
            lastHalt = riskState.haltReason

            // 5) New signals on bar close, filled next bar.
            if (time >= config.startTime && time <= config.endTime) {
                for ((instId, p) in prepared) {
                    if (open.containsKey(instId)) continue
                    val i = indexByTime.getValue(instId)[time] ?: continue
                    if (i + 1 >= p.size) continue
                    val sig = strategy.entrySignal(p, i) ?: continue
                    pending[instId] = sig
                }
            }
            if (onProgress != null && step % 500 == 0) onProgress(step.toDouble() / timeline.size)
        }

        // Close whatever is still open at the last price so the result is fully realized.
        val endTime = timeline.lastOrNull() ?: 0L
        for ((instId, pos) in open.entries.toList()) {
            closePos(instId, pos, lastClose[instId] ?: pos.track.entryPrice, endTime, "回測結束平倉")
        }
        if (curve.isNotEmpty()) curve[curve.lastIndex] = endTime to cash
        val activeCurve = curve.filter { it.first >= config.startTime }
        val metrics = computeMetrics(activeCurve.ifEmpty { curve }, trades, barsWithExposure, timeline.size, killSwitch)
        onProgress?.invoke(1.0)
        return BacktestResult(metrics, trades, activeCurve.ifEmpty { curve })
    }

    private fun computeMetrics(
        curve: List<Pair<Long, Double>>,
        trades: List<BacktestTrade>,
        exposedBars: Int,
        totalBars: Int,
        killSwitch: Int,
    ): BacktestMetrics {
        val start = curve.firstOrNull()?.second ?: config.initialEquity
        val end = curve.lastOrNull()?.second ?: config.initialEquity
        val days = if (curve.size > 1) (curve.last().first - curve.first().first) / 86_400_000.0 else 0.0
        var peak = Double.NEGATIVE_INFINITY
        var maxDd = 0.0
        for ((_, e) in curve) {
            peak = max(peak, e)
            if (peak > 0) maxDd = max(maxDd, (peak - e) / peak * 100.0)
        }
        // Daily returns for the Sharpe ratio.
        val daily = curve.groupBy { Math.floorDiv(it.first, 86_400_000L) }.toSortedMap().values.map { it.last().second }
        val rets = daily.zipWithNext { a, b -> if (a > 0) b / a - 1 else 0.0 }
        val mean = rets.average().takeIf { !it.isNaN() } ?: 0.0
        val sd = if (rets.size > 1) sqrt(rets.sumOf { (it - mean) * (it - mean) } / (rets.size - 1)) else 0.0
        val sharpe = if (sd > 0) mean / sd * sqrt(365.0) else 0.0
        val totalReturn = (end / start - 1) * 100.0
        val cagr = if (days > 1 && end > 0) ((end / start).pow(365.0 / days) - 1) * 100.0 else totalReturn
        val wins = trades.filter { it.pnl > 0 }
        val losses = trades.filter { it.pnl <= 0 }
        val grossWin = wins.sumOf { it.pnl }
        val grossLoss = -losses.sumOf { it.pnl }
        var streak = 0
        var maxStreak = 0
        for (t in trades.sortedBy { it.exitTime }) {
            if (t.pnl <= 0) { streak++; maxStreak = max(maxStreak, streak) } else streak = 0
        }
        return BacktestMetrics(
            initialEquity = start,
            finalEquity = end,
            totalReturnPct = totalReturn,
            cagrPct = cagr,
            maxDrawdownPct = maxDd,
            sharpe = sharpe,
            calmar = if (maxDd > 0) cagr / maxDd else 0.0,
            trades = trades.size,
            winRatePct = if (trades.isEmpty()) 0.0 else wins.size * 100.0 / trades.size,
            profitFactor = if (grossLoss > 0) grossWin / grossLoss else if (grossWin > 0) Double.POSITIVE_INFINITY else 0.0,
            expectancyR = trades.map { it.rMultiple }.average().takeIf { !it.isNaN() } ?: 0.0,
            avgWinR = wins.map { it.rMultiple }.average().takeIf { !it.isNaN() } ?: 0.0,
            avgLossR = losses.map { it.rMultiple }.average().takeIf { !it.isNaN() } ?: 0.0,
            maxConsecutiveLosses = maxStreak,
            avgBarsHeld = trades.map { it.barsHeld.toDouble() }.average().takeIf { !it.isNaN() } ?: 0.0,
            exposurePct = if (totalBars > 0) exposedBars * 100.0 / totalBars else 0.0,
            totalFees = trades.sumOf { it.fees },
            longTrades = trades.count { it.side == Side.LONG },
            shortTrades = trades.count { it.side == Side.SHORT },
            killSwitchTriggers = killSwitch,
            days = days,
        )
    }
}
