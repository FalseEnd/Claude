package com.perppilot.core.strategy

import com.perppilot.core.model.Timeframe
import kotlinx.serialization.Serializable

/**
 * Parameters of the multi-timeframe trend strategy.
 *
 * Higher timeframe (default 4H) decides the regime, lower timeframe (default 1H) times entries.
 * All percentages are expressed in percent (1.0 = 1%).
 */
@Serializable
data class StrategyConfig(
    /** Entry / management timeframe. */
    val ltfFrame: Timeframe = Timeframe.H4,
    /** Regime timeframe. */
    val htfFrame: Timeframe = Timeframe.D1,

    // --- Regime filter (higher timeframe) ---
    val htfEmaFast: Int = 50,
    val htfEmaSlow: Int = 200,
    val adxPeriod: Int = 14,
    val adxMin: Double = 30.0,

    // --- Entry timing (lower timeframe) ---
    val ltfEma: Int = 20,
    val rsiPeriod: Int = 14,
    val usePullback: Boolean = true,
    /** Long pullback requires RSI to have dipped below this value recently (shorts use 100 - value). */
    val pullbackRsi: Double = 45.0,
    /** RSI must recover above this level (shorts: below 100 - value) on the trigger bar. */
    val resumeRsi: Double = 50.0,
    val pullbackLookback: Int = 6,
    val useBreakout: Boolean = true,
    val breakoutLookback: Int = 20,
    val breakoutVolumeMult: Double = 1.3,

    // --- Exits ---
    val atrPeriod: Int = 14,
    val stopAtrMult: Double = 2.0,
    val trailAtrMult: Double = 4.0,
    /** Move the stop to break-even (+0.1R) after price has moved this many R in our favour. 0 = off. */
    val breakEvenR: Double = 1.0,
    /** Fixed take profit in R multiples. 0 = off (let the trailing stop run the trade). */
    val takeProfitR: Double = 0.0,
    /** Close a trade that has not reached +0.5R after this many bars. 0 = off. */
    val maxHoldBars: Int = 96,
    val exitOnTrendBreak: Boolean = true,

    // --- Filters ---
    val minAtrPct: Double = 0.15,
    val maxAtrPct: Double = 4.0,
    /** Skip longs when funding is above +x% per period, shorts when below -x%. */
    val maxFundingRatePct: Double = 0.05,
    val allowLongs: Boolean = true,
    val allowShorts: Boolean = true,
    /** Only open new positions when the entry time (signal bar close) falls in this session. */
    val session: TradingSession = TradingSession.ALL,
) {
    /** Bars of history needed before the indicators are trustworthy. */
    val htfWarmup: Int get() = htfEmaSlow + adxPeriod * 2
    val ltfWarmup: Int get() = maxOf(ltfEma, rsiPeriod, atrPeriod, breakoutLookback) + pullbackLookback + 2
}
