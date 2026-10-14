package com.perppilot.core.events

/** OKX event-contract operations (instType = EVENTS). */
interface EventApi {
    suspend fun eventSeries(): List<EventSeries>
    /** [state]: live / expired / null for all. [after] paginates expired markets by expiry time. */
    suspend fun eventMarkets(seriesId: String, state: String? = "live", after: Long? = null): List<EventMarket>
    suspend fun eventQuote(instId: String): EventQuote
    /** Current index price, e.g. for BTC-USDT. */
    suspend fun indexPrice(underlying: String): Double
    /** Recent closed index candles' close prices, oldest first. [bar] e.g. "1m", "1H". */
    suspend fun indexCloses(underlying: String, bar: String, limit: Int = 100): List<Double>

    suspend fun eventPositions(): List<EventPosition>
    /** Limit buy of [contracts] on [outcome] at no more than [price]. Returns the order id. */
    suspend fun buyEvent(instId: String, outcome: Outcome, contracts: Double, price: Double, clientOrderId: String): String
}
