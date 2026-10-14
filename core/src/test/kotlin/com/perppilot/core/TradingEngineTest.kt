package com.perppilot.core

import com.perppilot.core.engine.EngineEvent
import com.perppilot.core.engine.EngineSettings
import com.perppilot.core.engine.EventKind
import com.perppilot.core.engine.InMemoryEngineStore
import com.perppilot.core.engine.TradingEngine
import com.perppilot.core.exchange.Exchange
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
import com.perppilot.core.risk.RiskConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory exchange that fills market orders at the last close. */
class FakeExchange(var ltf: List<Candle>, var htf: List<Candle>) : Exchange {
    override val name = "fake"
    val spec = InstrumentSpec("BTC-USDT-SWAP", ctVal = 0.01, lotSz = 0.01, minSz = 0.01, tickSz = 0.1, maxLeverage = 100)
    var equity = 10_000.0
    val positions = mutableListOf<ExchangePosition>()
    val stops = mutableMapOf<String, StopOrder>()
    val closed = mutableListOf<ClosedPositionRecord>()
    val orders = mutableListOf<String>()
    var leverageSet = 0
    private var nextId = 1

    private fun last() = ltf.last().close

    override suspend fun candles(instId: String, timeframe: Timeframe, limit: Int) =
        (if (timeframe == Timeframe.H1) ltf else htf).takeLast(limit)
    override suspend fun historyCandles(instId: String, timeframe: Timeframe, startTime: Long, onProgress: ((Int) -> Unit)?) =
        candles(instId, timeframe, Int.MAX_VALUE)
    override suspend fun ticker(instId: String) = Ticker(instId, last(), last(), last(), last(), 1e9, 0)
    override suspend fun tickers() = listOf(ticker("BTC-USDT-SWAP"))
    override suspend fun funding(instId: String) = FundingInfo(instId, 0.0001, 0)
    override suspend fun openInterestUsd(instId: String) = 1e9
    override suspend fun longShortRatio(instId: String) = 1.0
    override suspend fun instrument(instId: String) = spec
    override suspend fun accountConfig() = AccountConfig("net_mode", 2)
    override suspend fun balance() = AccountBalance(equity, equity, 0.0)
    override suspend fun positions() = positions.toList()
    override suspend fun setLeverage(instId: String, leverage: Int, marginMode: String, side: Side, hedgeMode: Boolean) { leverageSet = leverage }

    override suspend fun openMarket(
        instId: String, side: Side, contracts: Double, marginMode: String, hedgeMode: Boolean,
        stopLoss: Double, takeProfit: Double?, clientOrderId: String,
    ): OrderResult {
        orders += "open $side $contracts sl=$stopLoss"
        positions += ExchangePosition(instId, side, contracts, last(), last(), 0.0, 5.0, null, marginMode, "net", 0)
        val id = "algo${nextId++}"
        stops[id] = StopOrder(id, instId, stopLoss, takeProfit, if (side == Side.LONG) "sell" else "buy")
        return OrderResult("ord1", clientOrderId, last(), contracts)
    }
    override suspend fun order(instId: String, orderId: String) = OrderResult(orderId, "", last(), null)
    override suspend fun stopOrders(instId: String) = stops.values.filter { it.instId == instId }
    override suspend fun amendStop(instId: String, algoId: String, newStop: Double) {
        stops[algoId] = stops.getValue(algoId).copy(stopLoss = newStop)
    }
    override suspend fun placeStop(instId: String, side: Side, marginMode: String, hedgeMode: Boolean, stop: Double): String {
        val id = "algo${nextId++}"
        stops[id] = StopOrder(id, instId, stop, null, "sell")
        return id
    }
    override suspend fun cancelStop(instId: String, algoId: String) { stops.remove(algoId) }
    override suspend fun closePosition(instId: String, side: Side, marginMode: String, hedgeMode: Boolean) {
        val p = positions.first { it.instId == instId }
        positions.remove(p)
        stops.clear()
        closed += ClosedPositionRecord(instId, side, p.avgPrice, last(), (last() - p.avgPrice) * side.sign * p.contracts * 0.01, 0, Long.MAX_VALUE / 4, "close")
    }
    override suspend fun closedPositions(instId: String, since: Long) = closed.filter { it.closeTime >= since }

    /** Simulates the exchange-side stop being triggered. */
    fun triggerStop(price: Double) {
        val p = positions.removeAt(0)
        stops.clear()
        closed += ClosedPositionRecord(p.instId, p.side, p.avgPrice, price, (price - p.avgPrice) * p.side.sign * p.contracts * 0.01, 0, Long.MAX_VALUE / 4, "close")
    }
}

class TradingEngineTest {
    private val h = Timeframe.H1.millis
    private val end = 1_000L * Timeframe.H4.millis

    private fun market(): FakeExchange {
        val base = TestData.trend(300, Timeframe.H1, end - h, start = 60_000.0)
        val ltf = base.dropLast(1) + base.last().copy(volume = 500.0) // volume breakout
        val htf = TestData.trend(300, Timeframe.H4, end - Timeframe.H4.millis, start = 30_000.0, stepPct = 0.5)
        return FakeExchange(ltf, htf)
    }

    private fun settings(auto: Boolean = true) = EngineSettings(
        watchlist = listOf("BTC-USDT-SWAP"),
        strategy = TestData.h1Config,
        risk = RiskConfig(riskPerTradePct = 1.0, leverage = 5),
        autoTrade = auto,
    )

    @Test
    fun opensWithStopTrailsAndRecordsTheClose() = runTest {
        val ex = market()
        val store = InMemoryEngineStore()
        val notified = mutableListOf<EngineEvent>()
        var now = end + 60_000
        val engine = TradingEngine(ex, ex, store, { notified += it }, clock = { now }, fillWaitMs = 0)

        // Cycle 1: breakout signal -> market order with an attached stop below price.
        val r1 = engine.runCycle(settings())
        assertTrue(r1.errors.toString(), r1.errors.isEmpty())
        assertEquals(1, ex.positions.size)
        assertEquals(5, ex.leverageSet)
        val initialStop = ex.stops.values.single().stopLoss!!
        assertTrue(initialStop < ex.ltf.last().close)
        assertTrue(notified.any { it.kind == EventKind.SIGNAL })
        assertTrue(notified.any { it.kind == EventKind.ORDER })
        val track = requireNotNull(store.load().positions["BTC-USDT-SWAP"])
        // Risk taken ~= 1% of equity.
        val risk = (track.entryPrice - track.initialStop) * track.size * 0.01
        assertTrue("risk=$risk", risk in 90.0..100.0)

        // Running again on the same bar must not open a second position.
        engine.runCycle(settings())
        assertEquals(1, ex.positions.size)
        assertEquals(1, ex.orders.size)

        // Cycle 2: 12 more rising bars -> stop moves up (break-even + trailing) on the exchange.
        val more = TestData.trend(13, Timeframe.H1, end + 11 * h, start = ex.ltf.last().close).drop(1)
        ex.ltf = ex.ltf + more
        now = end + 12 * h + 60_000
        engine.runCycle(settings())
        val trailed = ex.stops.values.single().stopLoss!!
        assertTrue("stop $initialStop -> $trailed", trailed > track.entryPrice)
        assertTrue(notified.any { it.kind == EventKind.STOP })

        // Cycle 3: the exchange stop fired while we were away -> journal entry, position forgotten.
        ex.triggerStop(trailed)
        now += h
        engine.runCycle(settings())
        val state = store.load()
        assertTrue(state.positions.isEmpty())
        val j = state.journal.single()
        assertEquals("移動止損", j.exitReason)
        assertTrue(j.realizedPnl > 0)
        assertTrue(notified.any { it.kind == EventKind.CLOSE })
    }

    @Test
    fun signalOnlyModeNeverTrades() = runTest {
        val ex = market()
        val notified = mutableListOf<EngineEvent>()
        TradingEngine(ex, ex, InMemoryEngineStore(), { notified += it }, clock = { end + 60_000 }, fillWaitMs = 0)
            .runCycle(settings(auto = false))
        assertTrue(ex.orders.isEmpty())
        assertTrue(notified.any { it.kind == EventKind.SIGNAL })
    }

    @Test
    fun drawdownKillSwitchBlocksEntries() = runTest {
        val ex = market()
        val store = InMemoryEngineStore()
        val engine = TradingEngine(ex, ex, store, {}, clock = { end + 60_000 }, fillWaitMs = 0)
        store.save(store.load().copy(risk = store.load().risk.copy(peakEquity = 20_000.0, dayKey = (end + 60_000) / 86_400_000L, dayStartEquity = 10_000.0)))
        val r = engine.runCycle(settings())
        assertTrue(ex.orders.isEmpty())
        assertTrue(r.events.any { it.kind == EventKind.RISK })
    }

    @Test
    fun skipsSymbolsWithManualPositions() = runTest {
        val ex = market()
        ex.positions += ExchangePosition("BTC-USDT-SWAP", Side.SHORT, 1.0, 60_000.0, 60_000.0, 0.0, 3.0, null, "cross", "net", 0)
        TradingEngine(ex, ex, InMemoryEngineStore(), {}, clock = { end + 60_000 }, fillWaitMs = 0).runCycle(settings())
        assertTrue(ex.orders.isEmpty())
    }
}
