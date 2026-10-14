package com.perppilot.core.model

object CandleUtils {
    /**
     * Aggregates lower-timeframe candles into [frameMillis] buckets aligned to the epoch.
     * Incomplete buckets (fewer than the expected number of source bars) are dropped.
     */
    fun aggregate(candles: List<Candle>, sourceMillis: Long, frameMillis: Long): List<Candle> {
        require(frameMillis % sourceMillis == 0L)
        val expected = (frameMillis / sourceMillis).toInt()
        return candles.groupBy { Math.floorDiv(it.time, frameMillis) * frameMillis }
            .toSortedMap()
            .mapNotNull { (bucket, bars) ->
                if (bars.size != expected) return@mapNotNull null
                val sorted = bars.sortedBy { it.time }
                Candle(
                    time = bucket,
                    open = sorted.first().open,
                    high = sorted.maxOf { it.high },
                    low = sorted.minOf { it.low },
                    close = sorted.last().close,
                    volume = sorted.sumOf { it.volume },
                )
            }
    }
}
