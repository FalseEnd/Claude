package com.perppilot.core.strategy

import com.perppilot.core.model.Candle
import com.perppilot.core.model.Timeframe
import kotlinx.serialization.Serializable

/** A complete trading strategy: signals, initial risk and position management. */
interface Strategy {
    val type: StrategyType
    val ltfFrame: Timeframe
    val htfFrame: Timeframe
    val ltfWarmup: Int
    val htfWarmup: Int

    fun prepare(ltf: List<Candle>, htf: List<Candle>): PreparedSeries

    /** Evaluates the closed bar [i]. [fundingRate] is a fraction (0.0001 = 0.01%). */
    fun entrySignal(p: PreparedSeries, i: Int, fundingRate: Double? = null): EntrySignal?

    /** Protective stop for a fill at [entry]. */
    fun initialStop(signal: EntrySignal, entry: Double): Double

    fun takeProfit(signal: EntrySignal, entry: Double, stop: Double): Double?

    /** Updates the stop after bar [i] closed and decides whether to exit at its close. */
    fun manage(pos: PositionTrack, p: PreparedSeries, i: Int): ManageResult
}

@Serializable
enum class StrategyType(val label: String, val description: String) {
    TREND("趨勢跟隨", "日線定方向、4 小時回踩或突破進場，移動止損抱趨勢"),
    ORB("開盤區間突破", "美股開盤後的區間突破，當日收盤前平倉"),
    MEAN_REVERSION("均值回歸", "無趨勢時於布林通道外緣反向進場，回到均線出場"),
}

object StrategyFactory {
    fun create(type: StrategyType, trend: StrategyConfig, orb: OrbConfig, meanRev: MeanRevConfig): Strategy = when (type) {
        StrategyType.TREND -> TrendStrategy(trend)
        StrategyType.ORB -> OrbStrategy(orb)
        StrategyType.MEAN_REVERSION -> MeanReversionStrategy(meanRev)
    }
}
