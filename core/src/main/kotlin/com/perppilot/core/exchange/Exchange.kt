package com.perppilot.core.exchange

import com.perppilot.core.model.AccountBalance
import com.perppilot.core.model.AccountConfig
import com.perppilot.core.model.Candle
import com.perppilot.core.model.ClosedPositionRecord
import com.perppilot.core.model.ExchangePosition
import com.perppilot.core.model.FundingInfo
import com.perppilot.core.model.InstrumentSpec
import com.perppilot.core.model.OrderResult
import com.perppilot.core.model.Side
import com.perppilot.core.model.StopOrder
import com.perppilot.core.model.Ticker
import com.perppilot.core.model.Timeframe

class ExchangeException(message: String, val code: String? = null) : Exception(message)

/** Public market data. Implementations must return candles in ascending time order. */
interface MarketData {
    val name: String

    /** Most recent *closed* candles, oldest first. */
    suspend fun candles(instId: String, timeframe: Timeframe, limit: Int): List<Candle>

    /** Closed candles from [startTime] until now, oldest first (paginates as needed). */
    suspend fun historyCandles(
        instId: String,
        timeframe: Timeframe,
        startTime: Long,
        onProgress: ((Int) -> Unit)? = null,
    ): List<Candle>

    suspend fun ticker(instId: String): Ticker
    suspend fun tickers(): List<Ticker>
    suspend fun funding(instId: String): FundingInfo?
    /** Open interest in USD, when available. */
    suspend fun openInterestUsd(instId: String): Double?
    /** Ratio of accounts net long vs net short for the base coin, when available. */
    suspend fun longShortRatio(instId: String): Double?
    suspend fun instrument(instId: String): InstrumentSpec
}

/** Authenticated trading operations for USDT-margined perpetual swaps. */
interface TradingApi {
    suspend fun accountConfig(): AccountConfig
    suspend fun balance(): AccountBalance
    suspend fun positions(): List<ExchangePosition>
    suspend fun setLeverage(instId: String, leverage: Int, marginMode: String, side: Side, hedgeMode: Boolean)

    /** Market order that opens a position with an exchange-side stop loss (and optional take profit) attached. */
    suspend fun openMarket(
        instId: String,
        side: Side,
        contracts: Double,
        marginMode: String,
        hedgeMode: Boolean,
        stopLoss: Double,
        takeProfit: Double?,
        clientOrderId: String,
    ): OrderResult

    suspend fun order(instId: String, orderId: String): OrderResult

    /** Pending protective (stop / tp) algo orders for [instId]. */
    suspend fun stopOrders(instId: String): List<StopOrder>

    /** Moves the stop-loss trigger of an existing algo order. */
    suspend fun amendStop(instId: String, algoId: String, newStop: Double)

    /** Places a stop-loss that closes the whole position. */
    suspend fun placeStop(instId: String, side: Side, marginMode: String, hedgeMode: Boolean, stop: Double): String

    suspend fun cancelStop(instId: String, algoId: String)

    /** Closes the whole position at market and cancels its attached orders. */
    suspend fun closePosition(instId: String, side: Side, marginMode: String, hedgeMode: Boolean)

    suspend fun closedPositions(instId: String, since: Long): List<ClosedPositionRecord>
}

interface Exchange : MarketData, TradingApi
