package com.perppilot.core

import com.perppilot.core.model.Candle
import com.perppilot.core.model.Side
import com.perppilot.core.model.Timeframe
import com.perppilot.core.strategy.EntryType
import com.perppilot.core.strategy.PositionTrack
import com.perppilot.core.strategy.Regime
import com.perppilot.core.strategy.StrategyConfig
import com.perppilot.core.strategy.TrendStrategy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyTest {
    private val h = Timeframe.H1.millis
    private val end = 1_000L * Timeframe.H4.millis // aligned reference time
    private val strategy = TrendStrategy(TestData.h1Config)

    private fun htfUp() = TestData.trend(300, Timeframe.H4, end - Timeframe.H4.millis, stepPct = 0.5)
    private fun htfDown() = TestData.trend(300, Timeframe.H4, end - Timeframe.H4.millis, stepPct = -0.5)

    @Test
    fun higherTimeframeAlignmentHasNoLookAhead() {
        val ltf = (0 until 9).map { Candle(it * h, 1.0, 1.0, 1.0, 1.0, 1.0) }
        val htf = (0 until 3).map { Candle(it * 4 * h, 1.0, 1.0, 1.0, 1.0, 1.0) }
        val idx = TrendStrategy.alignHigherTimeframe(ltf, htf, h, 4 * h)
        // LTF bar k closes at (k+1)h; HTF bar j closes at 4(j+1)h.
        assertArrayEquals(intArrayOf(-1, -1, -1, 0, 0, 0, 0, 1, 1), idx)
    }

    @Test
    fun detectsRegimes() {
        val ltf = TestData.trend(300, Timeframe.H1, end - h)
        assertEquals(Regime.BULL, strategy.regime(strategy.prepare(ltf, htfUp()), ltf.lastIndex))
        assertEquals(Regime.BEAR, strategy.regime(strategy.prepare(ltf, htfDown()), ltf.lastIndex))
    }

    @Test
    fun volumeBreakoutInBullRegimeGivesLongSignal() {
        val base = TestData.trend(300, Timeframe.H1, end - h)
        val ltf = base.dropLast(1) + base.last().copy(volume = 500.0)
        val sig = requireNotNull(strategy.entrySignal(strategy.prepare(ltf, htfUp()), ltf.lastIndex, fundingRate = 0.0001))
        assertEquals(Side.LONG, sig.side)
        assertEquals(EntryType.BREAKOUT.name, sig.type)
        assertTrue(sig.suggestedStop < sig.price)
        // Without the volume spike, a plain trend bar is not an entry.
        assertNull(strategy.entrySignal(strategy.prepare(base, htfUp()), base.lastIndex))
    }

    @Test
    fun crowdedFundingBlocksLongs() {
        val base = TestData.trend(300, Timeframe.H1, end - h)
        val ltf = base.dropLast(1) + base.last().copy(volume = 500.0)
        assertNull(strategy.entrySignal(strategy.prepare(ltf, htfUp()), ltf.lastIndex, fundingRate = 0.001))
    }

    @Test
    fun noLongsAgainstBearRegime() {
        val base = TestData.trend(300, Timeframe.H1, end - h)
        val ltf = base.dropLast(1) + base.last().copy(volume = 500.0)
        assertNull(strategy.entrySignal(strategy.prepare(ltf, htfDown()), ltf.lastIndex))
    }

    @Test
    fun pullbackThenResumptionGivesLongSignal() {
        val trend = TestData.trend(290, Timeframe.H1, end - 6 * h)
        var t = trend.last().time
        var price = trend.last().close
        val extra = mutableListOf<Candle>()
        repeat(4) {
            t += h
            val c = price * 0.985
            extra += TestData.candle(t, price, price * 1.001, c * 0.999, c)
            price = c
        }
        t += h
        val c = price * 1.04
        extra += TestData.candle(t, price, c * 1.001, price * 0.999, c)
        val ltf = trend + extra
        val sig = requireNotNull(strategy.entrySignal(strategy.prepare(ltf, htfUp()), ltf.lastIndex))
        assertEquals(EntryType.PULLBACK.name, sig.type)
        assertEquals(Side.LONG, sig.side)
    }

    @Test
    fun manageMovesStopToBreakEvenAndTrailsButNeverLoosens() {
        val ltf = TestData.trend(300, Timeframe.H1, end - h, stepPct = 0.3)
        val p = strategy.prepare(ltf, htfUp())
        val entryIdx = 250
        val entry = ltf[entryIdx].close
        val stop = entry * 0.98
        var pos = PositionTrack("X", Side.LONG, ltf[entryIdx].time, entry, stop, stop, null, 1.0, entry, lastBarTime = ltf[entryIdx].time)
        var prevStop = pos.stop
        for (k in entryIdx + 1..ltf.lastIndex) {
            val r = strategy.manage(pos, p, k)
            assertNull(r.exitReason)
            pos = r.position
            assertTrue(pos.stop >= prevStop)
            prevStop = pos.stop
        }
        assertTrue(pos.breakEvenDone)
        assertTrue(pos.stop > entry)
        assertEquals(ltf.size - 1 - entryIdx, pos.barsHeld)
        // Re-processing an already handled bar is a no-op.
        assertEquals(pos, strategy.manage(pos, p, ltf.lastIndex).position)
    }

    @Test
    fun exitsWhenHigherTimeframeTrendBreaks() {
        val ltf = TestData.trend(300, Timeframe.H1, end - h)
        val p = strategy.prepare(ltf, htfDown())
        val e = ltf[290].close
        val pos = PositionTrack("X", Side.LONG, ltf[290].time, e, e * 0.98, e * 0.98, null, 1.0, e, lastBarTime = ltf[290].time)
        assertEquals("4H趨勢結構破壞", strategy.manage(pos, p, 291).exitReason)
    }
}
