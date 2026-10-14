package com.perppilot.core

import com.perppilot.core.carry.CarryApi
import com.perppilot.core.carry.CarryConfig
import com.perppilot.core.carry.CarryEngine
import com.perppilot.core.carry.FundingSnapshot
import com.perppilot.core.carry.InMemoryCarryStore
import com.perppilot.core.engine.EngineEvent
import com.perppilot.core.engine.EventKind
import com.perppilot.core.model.Candle
import com.perppilot.core.model.ExchangePosition
import com.perppilot.core.model.Side
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** FakeExchange plus a spot wallet and funding history. */
class FakeCarryExchange(price: Double) : CarryApi {
    val ex = FakeExchange(listOf(Candle(0, price, price, price, price, 1.0)), emptyList())
    var spotBtc = 0.0
    var funding = List(21) { 0.0001 }
    var failPerp = false
    val log = mutableListOf<String>()
    fun setPrice(p: Double) { ex.ltf = listOf(Candle(0, p, p, p, p, 1.0)) }
    private fun price() = ex.ltf.last().close

    override suspend fun fundingHistory(instId: String, limit: Int) = funding.mapIndexed { i, r -> (1_000_000L - i * 28_800_000L) to r }
    override suspend fun allFunding() = listOf(FundingSnapshot("BTC-USDT-SWAP", 0.01, 0))
    override suspend fun spotLotSize(instId: String) = 0.00000001 to 0.00001
    override suspend fun assetBalance(ccy: String) = spotBtc
    override suspend fun spotMarket(instId: String, buy: Boolean, baseQty: Double, clientOrderId: String): Double {
        log += "spot ${if (buy) "buy" else "sell"} $baseQty"
        spotBtc += if (buy) baseQty * 0.999 else -baseQty // 0.1% fee taken in BTC on buys
        return if (buy) baseQty * 0.999 else baseQty
    }
    override suspend fun perpMarket(instId: String, sell: Boolean, contracts: Double, marginMode: String, hedgeMode: Boolean, reduceOnly: Boolean, clientOrderId: String) {
        if (failPerp) error("insufficient margin")
        log += "perp ${if (sell) "sell" else "buy"} $contracts"
        val cur = ex.positions.firstOrNull { it.instId == instId }
        val now = (cur?.contracts ?: 0.0) + if (sell) contracts else -contracts
        ex.positions.removeAll { it.instId == instId }
        if (now > 1e-9) ex.positions += ExchangePosition(instId, Side.SHORT, now, price(), price(), 0.0, 2.0, null, marginMode, "net", 0)
    }
}

class CarryTest {
    private val cfg = CarryConfig(enabled = true, coins = listOf("BTC"), capitalPct = 50.0, perpLeverage = 2)

    private fun setup(price: Double = 60_000.0): Triple<FakeCarryExchange, CarryEngine, MutableList<EngineEvent>> {
        val f = FakeCarryExchange(price)
        val events = mutableListOf<EngineEvent>()
        val engine = CarryEngine(f.ex, f.ex, f, InMemoryCarryStore(), { events += it }, clock = { 2_000_000L })
        return Triple(f, engine, events)
    }

    @Test
    fun opensMatchedLegsSizedToCapital() = runTest {
        val (f, engine, _) = setup()
        val st = engine.runCycle(cfg)
        val pos = st.positions.getValue("BTC")
        // 10k equity × 50% / (1 + 1/2) = 3333 USDT notional → 0.0555 BTC → 5.55 contracts of 0.01.
        assertEquals(5.55, pos.contracts, 1e-9)
        assertEquals(0.0555 * 0.999, f.spotBtc, 1e-9)
        assertEquals(5.55, f.ex.positions.single().contracts, 1e-9)
        assertTrue(st.status.single().inPosition)
        // Second cycle: legs within tolerance → nothing traded.
        val before = f.log.size
        engine.runCycle(cfg)
        assertEquals(before, f.log.size)
    }

    @Test
    fun trimsBothLegsAfterARally() = runTest {
        val (f, engine, events) = setup()
        engine.runCycle(cfg)
        f.setPrice(80_000.0) // notional 4440 vs target 3333 → +33%
        val pos = engine.runCycle(cfg).positions.getValue("BTC")
        assertTrue(pos.contracts < 5.55)
        assertEquals(pos.contracts * 0.01, f.spotBtc, 0.0555 * 0.03)
        assertTrue(events.any { it.title.contains("再平衡") })
    }

    @Test
    fun unwindsWhenFundingTurnsNegative() = runTest {
        val (f, engine, events) = setup()
        engine.runCycle(cfg)
        f.funding = List(21) { -0.0002 }
        val st = engine.runCycle(cfg)
        assertTrue(st.positions.isEmpty())
        assertTrue(f.ex.positions.isEmpty())
        assertEquals(0.0, f.spotBtc, 1e-6)
        assertTrue(events.any { it.kind == EventKind.CLOSE })
    }

    @Test
    fun neverLeavesNakedSpotWhenTheShortFails() = runTest {
        val (f, engine, _) = setup()
        f.failPerp = true
        val st = engine.runCycle(cfg)
        assertTrue(st.positions.isEmpty())
        assertEquals(0.0, f.spotBtc, 1e-9)
        assertTrue(f.log.any { it.startsWith("spot sell") })
    }

    @Test
    fun sellsSpotIfTheShortDisappears() = runTest {
        val (f, engine, events) = setup()
        engine.runCycle(cfg)
        f.ex.positions.clear() // e.g. liquidated or closed by hand
        val st = engine.runCycle(cfg)
        assertTrue(events.any { it.kind == EventKind.RISK })
        assertEquals(0.0, f.spotBtc, 1e-9)
        // It re-enters on the next eligible cycle, fully hedged.
        assertTrue(st.positions.isEmpty() || f.ex.positions.isNotEmpty())
    }

    @Test
    fun skipsCoinsWithDirectionalPositions() = runTest {
        val (f, engine, _) = setup()
        val st = engine.runCycle(cfg, blocked = setOf("BTC-USDT-SWAP"))
        assertTrue(st.positions.isEmpty())
        assertTrue(f.log.isEmpty())
    }

    @Test
    fun doesNotMergeWithManualPositions() = runTest {
        val (f, engine, events) = setup()
        f.ex.positions += ExchangePosition("BTC-USDT-SWAP", Side.LONG, 1.0, 60_000.0, 60_000.0, 0.0, 3.0, null, "cross", "net", 0)
        val st = engine.runCycle(cfg)
        assertTrue(st.positions.isEmpty())
        assertTrue(f.log.isEmpty())
        assertTrue(events.any { it.message.contains("其他持倉") })
    }
}
