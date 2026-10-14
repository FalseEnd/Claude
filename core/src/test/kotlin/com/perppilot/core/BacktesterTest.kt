package com.perppilot.core

import com.perppilot.core.backtest.BacktestConfig
import com.perppilot.core.backtest.Backtester
import com.perppilot.core.backtest.SymbolData
import com.perppilot.core.model.Candle
import com.perppilot.core.model.CandleUtils
import com.perppilot.core.model.Timeframe
import com.perppilot.core.risk.RiskConfig
import com.perppilot.core.strategy.StrategyConfig
import com.perppilot.core.strategy.TrendStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BacktesterTest {
    private val h = Timeframe.H1.millis

    /** Uptrend made of 20 rising bars followed by 5 falling bars, with mild noise. */
    private fun sawtooth(bars: Int, seed: Int = 7): List<Candle> {
        val rnd = Random(seed)
        var price = 100.0
        return (0 until bars).map { k ->
            val up = k % 25 < 20
            val step = if (up) 0.006 else -0.012
            val open = price
            val close = price * (1 + step + (rnd.nextDouble() - 0.5) * 0.002)
            price = close
            val vol = if (up && k % 25 == 0) 400.0 else 100.0 + rnd.nextDouble() * 20
            Candle(k * h, open, maxOf(open, close) * 1.002, minOf(open, close) * 0.998, close, vol)
        }
    }

    private fun data(ltf: List<Candle>) =
        SymbolData("TEST-USDT-SWAP", ltf, CandleUtils.aggregate(ltf, h, Timeframe.H4.millis))

    @Test
    fun trendingMarketIsProfitableAfterCosts() {
        val bt = Backtester(TrendStrategy(TestData.h1Config), RiskConfig())
        val r = bt.run(listOf(data(sawtooth(4000))))
        assertTrue("expected trades", r.metrics.trades > 5)
        assertTrue("expected profit, got ${r.metrics.totalReturnPct}", r.metrics.finalEquity > r.metrics.initialEquity)
        assertTrue(r.metrics.totalFees > 0)
        assertEquals(r.metrics.trades, r.metrics.longTrades + r.metrics.shortTrades)
    }

    @Test
    fun flatMarketProducesNoTrades() {
        val flat = (0 until 2000).map { Candle(it * h, 100.0, 100.1, 99.9, 100.0, 100.0) }
        val r = Backtester(TrendStrategy(TestData.h1Config), RiskConfig(), BacktestConfig(initialEquity = 5000.0)).run(listOf(data(flat)))
        assertEquals(0, r.metrics.trades)
        assertEquals(5000.0, r.metrics.finalEquity, 1e-9)
    }

    @Test
    fun truncatingFutureDataDoesNotChangePastTrades() {
        val full = sawtooth(4000, seed = 11)
        val cut = 3000
        val bt = Backtester(TrendStrategy(TestData.h1Config), RiskConfig())
        val a = bt.run(listOf(data(full))).trades.filter { it.exitTime < full[cut - 1].time }
        val b = bt.run(listOf(data(full.take(cut)))).trades.filter { it.exitTime < full[cut - 1].time }
        assertTrue(a.isNotEmpty())
        assertEquals(a, b)
    }

    @Test
    fun riskPerTradeBoundsLossesNearOneR() {
        val r = Backtester(TrendStrategy(TestData.h1Config), RiskConfig(riskPerTradePct = 1.0)).run(listOf(data(sawtooth(4000, seed = 3))))
        // Gaps and costs can push a loss slightly past -1R, but never far beyond it.
        assertTrue(r.trades.all { it.rMultiple > -1.5 })
    }
}
