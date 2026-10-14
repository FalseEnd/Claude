package com.perppilot.core

import com.perppilot.core.backtest.BacktestConfig
import com.perppilot.core.backtest.Backtester
import com.perppilot.core.backtest.SymbolData
import com.perppilot.core.model.Candle
import com.perppilot.core.model.Side
import com.perppilot.core.model.Timeframe
import com.perppilot.core.risk.RiskConfig
import com.perppilot.core.strategy.EntrySignal
import com.perppilot.core.strategy.ManageResult
import com.perppilot.core.strategy.MeanRevConfig
import com.perppilot.core.strategy.MeanReversionStrategy
import com.perppilot.core.strategy.OrbConfig
import com.perppilot.core.strategy.OrbStrategy
import com.perppilot.core.strategy.PositionTrack
import com.perppilot.core.strategy.PreparedSeries
import com.perppilot.core.strategy.Strategy
import com.perppilot.core.strategy.StrategyType
import com.perppilot.core.strategy.TrendStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class IntradayStrategiesTest {
    private val ny = ZoneId.of("America/New_York")
    private val m5 = Timeframe.M5.millis

    /** Flat 5m bars at 100 from 00:00 NY on a Wednesday, with an opening range of 99–101. */
    private fun orbDay(after: (Int, Double) -> Double): List<Candle> {
        val start = LocalDateTime.of(2026, 10, 14, 0, 0).atZone(ny).toInstant().toEpochMilli()
        var price = 100.0
        return (0 until 288).map { k ->
            val minute = k * 5
            val t = start + k * m5
            when {
                minute in 570 until 585 -> Candle(t, 100.0, 101.0, 99.0, 100.0, 100.0) // 09:30–09:45 range
                minute < 570 -> Candle(t, 100.0, 100.2, 99.8, 100.0, 100.0)
                else -> {
                    val next = after(minute, price)
                    val c = Candle(t, price, maxOf(price, next) + 0.05, minOf(price, next) - 0.05, next, 100.0)
                    price = next
                    c
                }
            }
        }
    }

    private val htf = TestData.trend(300, Timeframe.H1, LocalDateTime.of(2026, 10, 13, 23, 0).atZone(ny).toInstant().toEpochMilli())

    @Test
    fun orbTakesOnlyTheFirstBreakoutAndExitsAtTheClose() {
        // Price drifts inside the range, breaks out at 10:00, keeps rising.
        val day = orbDay { minute, p -> if (minute < 600) 100.2 else if (minute == 600) 101.5 else p + 0.01 }
        val s = OrbStrategy(OrbConfig())
        val p = s.prepare(day, htf)
        val idx = day.indexOfFirst { it.close == 101.5 }
        val sig = requireNotNull(s.entrySignal(p, idx))
        assertEquals(Side.LONG, sig.side)
        assertEquals(99.0, s.initialStop(sig, 101.6), 0.0)
        assertEquals(101.6 + 2 * 2.6, s.takeProfit(sig, 101.6, 99.0)!!, 1e-9)
        // Later bars are not new breakouts.
        assertNull(s.entrySignal(p, idx + 3))
        // Inside the range: nothing.
        assertNull(s.entrySignal(p, idx - 1))
        // The position is closed at 16:00 New York time.
        var pos = PositionTrack("X", Side.LONG, day[idx + 1].time, 101.6, 99.0, 99.0, null, 1.0, 101.6, lastBarTime = day[idx].time)
        var exitAt = -1
        for (k in idx + 1 until day.size) {
            val r = s.manage(pos, p, k)
            pos = r.position
            if (r.exitReason != null) { exitAt = k; break }
        }
        val local = java.time.Instant.ofEpochMilli(day[exitAt].time + m5).atZone(ny)
        assertEquals(16, local.hour)
        assertEquals(0, local.minute)
    }

    @Test
    fun orbRespectsTrendFilterAndEntryDeadline() {
        val down = orbDay { minute, p -> if (minute < 600) 100.2 else if (minute == 600) 98.5 else p }
        val s = OrbStrategy(OrbConfig())
        val p = s.prepare(down, htf) // higher timeframe is an uptrend → short breakout rejected
        assertNull(s.entrySignal(p, down.indexOfFirst { it.close == 98.5 }))
        val late = orbDay { minute, p -> if (minute < 750) 100.2 else if (minute == 750) 101.5 else p }
        val pl = s.prepare(late, htf)
        assertNull(s.entrySignal(pl, late.indexOfFirst { it.close == 101.5 })) // 12:30 is after the 12:00 deadline
    }

    @Test
    fun meanReversionFadesStretchedMovesAndExitsAtTheMean() {
        val t0 = 1_000L * Timeframe.H4.millis
        val m15 = Timeframe.M15.millis
        val bars = ArrayList<Candle>()
        var price = 100.0
        for (k in 0 until 200) {
            val next = 100.0 + if (k % 2 == 0) 0.1 else -0.1
            bars += Candle(t0 + k * m15, price, maxOf(price, next) + 0.05, minOf(price, next) - 0.05, next, 100.0)
            price = next
        }
        // Three sharp down bars push far below the lower band.
        listOf(99.0, 98.0, 97.0).forEach { c ->
            bars += Candle(bars.last().time + m15, price, price + 0.05, c - 0.05, c, 100.0); price = c
        }
        val flatHtf = (0 until 120).map { Candle(t0 - (120 - it) * Timeframe.H4.millis, 100.0, 100.3, 99.7, if (it % 2 == 0) 100.1 else 99.9, 1.0) }
        val s = MeanReversionStrategy(MeanRevConfig())
        val p = s.prepare(bars, flatHtf)
        val sig = requireNotNull(s.entrySignal(p, bars.lastIndex))
        assertEquals(Side.LONG, sig.side)
        // Price snaps back above the 20-bar mean → exit.
        val rebound = bars + Candle(bars.last().time + m15, 97.0, 100.5, 97.0, 100.4, 100.0)
        val p2 = s.prepare(rebound, flatHtf)
        val pos = PositionTrack("X", Side.LONG, bars.last().time, 97.0, 95.0, 95.0, null, 1.0, 97.0, lastBarTime = bars.last().time)
        assertEquals("回歸均線", s.manage(pos, p2, rebound.lastIndex).exitReason)
    }

    /** Enters long on the first evaluated bar and never exits on its own. */
    private class HoldStrategy(override val ltfFrame: Timeframe) : Strategy {
        override val type = StrategyType.TREND
        override val htfFrame = ltfFrame
        override val ltfWarmup = 0
        override val htfWarmup = 0
        private val base = TrendStrategy(TestData.h1Config.copy(ltfFrame = ltfFrame, htfFrame = ltfFrame))
        override fun prepare(ltf: List<Candle>, htf: List<Candle>) = base.prepare(ltf, htf)
        override fun entrySignal(p: PreparedSeries, i: Int, fundingRate: Double?) =
            if (i == 20) EntrySignal(Side.LONG, "X", p.ltf[i].time, p.ltf[i].close, 1.0, 50.0, 1.0, "") else null
        override fun initialStop(signal: EntrySignal, entry: Double) = entry * 0.98
        override fun takeProfit(signal: EntrySignal, entry: Double, stop: Double): Double? = null
        override fun manage(pos: PositionTrack, p: PreparedSeries, i: Int) =
            ManageResult(pos.copy(barsHeld = pos.barsHeld + 1, lastBarTime = p.ltf[i].time), null)
    }

    @Test
    fun fundingScalesWithBarLength() {
        fun fundingPaid(frame: Timeframe): Double {
            val bars = (0 until 101).map { Candle(it * frame.millis, 100.0, 100.0, 100.0, 100.0, 1.0) }
            val cfg = BacktestConfig(takerFeePct = 0.0, slippagePct = 0.0, fundingPctPer8h = 0.01)
            val r = Backtester(HoldStrategy(frame), RiskConfig(maxOpenPositions = 1), cfg).run(listOf(SymbolData("X", bars, bars)))
            val t = r.trades.single()
            return t.fees / (t.quantity * 100.0) // as a fraction of notional
        }
        // Held for 79 bars (filled at bar 21, managed through bar 99, closed at the end).
        val h1 = fundingPaid(Timeframe.H1)
        val h4 = fundingPaid(Timeframe.H4)
        assertEquals(4.0, h4 / h1, 1e-6)
        assertTrue(h4 > 0)
    }
}
