package com.perppilot.core

import com.perppilot.core.model.Candle
import com.perppilot.core.model.Timeframe
import com.perppilot.core.strategy.StrategyConfig

object TestData {
    /** Tests use the 1H entry / 4H regime pair with the original thresholds. */
    val h1Config = StrategyConfig(ltfFrame = Timeframe.H1, htfFrame = Timeframe.H4, adxMin = 20.0, trailAtrMult = 3.0)

    /**
     * Steady trend: every bar moves [stepPct] percent, with small wicks, so the higher-timeframe
     * regime is unambiguous and every bar closes above the previous bar's high.
     */
    fun trend(
        count: Int,
        frame: Timeframe,
        endOpenTime: Long,
        start: Double = 100.0,
        stepPct: Double = 0.3,
        wickPct: Double = 0.1,
        volume: Double = 100.0,
    ): List<Candle> {
        val firstTime = endOpenTime - (count - 1) * frame.millis
        var price = start
        return (0 until count).map { k ->
            val open = price
            val close = price * (1 + stepPct / 100)
            price = close
            Candle(
                time = firstTime + k * frame.millis,
                open = open,
                high = maxOf(open, close) * (1 + wickPct / 100),
                low = minOf(open, close) * (1 - wickPct / 100),
                close = close,
                volume = volume,
            )
        }
    }

    fun candle(time: Long, o: Double, h: Double, l: Double, c: Double, v: Double = 100.0) = Candle(time, o, h, l, c, v)
}
