package com.perppilot.core

import com.perppilot.core.indicators.Indicators
import com.perppilot.core.model.Candle
import com.perppilot.core.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IndicatorsTest {
    private val eps = 1e-9

    @Test
    fun emaIsSeededWithSmaAndSmoothed() {
        val e = Indicators.ema(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0), 3)
        assertTrue(e[0].isNaN() && e[1].isNaN())
        assertEquals(2.0, e[2], eps)
        assertEquals(3.0, e[3], eps)
        assertEquals(4.0, e[4], eps)
    }

    @Test
    fun smaRolls() {
        val s = Indicators.sma(doubleArrayOf(1.0, 2.0, 3.0, 4.0), 2)
        assertEquals(1.5, s[1], eps)
        assertEquals(3.5, s[3], eps)
    }

    @Test
    fun rsiMatchesHandCalculation() {
        val r = Indicators.rsi(doubleArrayOf(10.0, 11.0, 12.0, 11.0, 13.0), 2)
        assertTrue(r[1].isNaN())
        assertEquals(100.0, r[2], eps)
        assertEquals(50.0, r[3], eps)
        assertEquals(100.0 - 100.0 / 6.0, r[4], 1e-9)
    }

    @Test
    fun rsiExtremes() {
        val up = Indicators.rsi(DoubleArray(30) { it.toDouble() }, 14)
        val down = Indicators.rsi(DoubleArray(30) { 100.0 - it }, 14)
        assertEquals(100.0, up.last(), eps)
        assertEquals(0.0, down.last(), eps)
    }

    @Test
    fun atrOfConstantRangeEqualsRange() {
        val candles = (0 until 30).map { Candle(it * 1000L, 100.0, 102.0, 98.0, 100.0, 1.0) }
        assertEquals(4.0, Indicators.atr(candles, 14).last(), eps)
    }

    @Test
    fun adxIsHighAndDirectionalInStrongUptrend() {
        val c = TestData.trend(200, Timeframe.H4, 0L)
        val a = Indicators.adx(c, 14)
        assertTrue(a.adx.last() > 50)
        assertTrue(a.plusDi.last() > a.minusDi.last())
    }

    @Test
    fun bollingerOfConstantSeriesHasZeroWidth() {
        val b = Indicators.bollinger(DoubleArray(25) { 5.0 }, 20, 2.0)
        assertEquals(5.0, b.upper.last(), eps)
        assertEquals(0.0, b.width(24), eps)
    }

    @Test
    fun macdHistogramPositiveInUptrend() {
        val m = Indicators.macd(DoubleArray(100) { 100.0 * Math.pow(1.01, it.toDouble()) })
        assertTrue(m.histogram.last() > 0)
    }
}
