package com.perppilot.core

import com.perppilot.core.events.EventMarket
import com.perppilot.core.events.EventMethod
import com.perppilot.core.events.EventPricer
import com.perppilot.core.events.EventQuote
import com.perppilot.core.events.EventStrategy
import com.perppilot.core.events.EventStrategyConfig
import com.perppilot.core.events.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventsTest {
    @Test
    fun normalCdfMatchesKnownValues() {
        assertEquals(0.5, EventPricer.normCdf(0.0), 1e-7)
        assertEquals(0.841344746, EventPricer.normCdf(1.0), 1e-6)
        assertEquals(0.022750132, EventPricer.normCdf(-2.0), 1e-6)
    }

    @Test
    fun atTheMoneyUpDownIsCoinFlipAndConvergesAtExpiry() {
        assertEquals(0.5, EventPricer.probAbove(100.0, 100.0, 0.001, 10.0), 0.01)
        assertTrue(EventPricer.probAbove(101.0, 100.0, 0.001, 10.0) > 0.99)
        assertEquals(1.0, EventPricer.probAbove(100.1, 100.0, 0.001, 0.0), 0.0)
        assertEquals(0.0, EventPricer.probAbove(99.9, 100.0, 0.001, 0.0), 0.0)
    }

    @Test
    fun touchProbabilityIsTwiceTheTerminalTail() {
        val above = EventPricer.probAbove(100.0, 102.0, 0.002, 60.0)
        val hit = EventPricer.probHit(100.0, 102.0, true, 0.002, 60.0)
        assertTrue(hit > above)
        assertEquals(2 * (1 - EventPricer.normCdf(Math.log(1.02) / (0.002 * Math.sqrt(60.0)))), hit, 1e-9)
    }

    private fun market(strike: Double, minutes: Double, now: Long) = EventMarket(
        instId = "BTC-UPDOWN-15MIN-X", seriesId = "BTC-UPDOWN-15MIN", method = EventMethod.PRICE_UP_DOWN,
        floorStrike = strike, capStrike = null, hitDir = "", listTime = 0, expTime = now + (minutes * 60_000).toLong(),
        fixTime = 1, state = "live", outcome = 0, settleValue = null,
    )

    @Test
    fun onlyTradesWhenMarketIsClearlyMispriced() {
        val now = 1_000_000L
        val cfg = EventStrategyConfig(minEdge = 0.05)
        val m = market(100.0, 10.0, now)
        // Spot 0.3% above strike with 10 min left at 0.05%/min vol: fair YES ~ 0.97 → but maxPrice 0.9 caps it.
        // Use a smaller move so fair ≈ 0.69.
        val fair = EventPricer.fairYes(m, 100.08, 0.0005, now)!!
        assertTrue(fair in 0.6..0.75)
        val cheap = EventStrategy.evaluate(m, EventQuote(m.instId, 0.48, 0.50, 0.5, now), 100.08, 0.0005, now, cfg)!!
        assertEquals(Outcome.YES, cheap.outcome)
        assertTrue(cheap.edge >= 0.05)
        assertTrue(cheap.limitPrice >= 0.50 && cheap.limitPrice <= fair - 0.05)
        // Fairly priced market → no trade.
        assertNull(EventStrategy.evaluate(m, EventQuote(m.instId, fair - 0.02, fair + 0.01, fair, now), 100.08, 0.0005, now, cfg))
        // Too close to expiry → no trade.
        assertNull(EventStrategy.evaluate(market(100.0, 2.0, now), EventQuote(m.instId, 0.1, 0.12, 0.1, now), 100.08, 0.0005, now, cfg))
    }

    @Test
    fun kellySizingIsCapped() {
        val now = 0L
        val m = market(100.0, 10.0, now)
        val cfg = EventStrategyConfig(maxStakePct = 1.0)
        val op = EventStrategy.evaluate(m, EventQuote(m.instId, 0.3, 0.32, 0.3, now), 100.1, 0.0005, now, cfg)!!
        val n = EventStrategy.contracts(op, 1000.0, cfg)
        assertTrue(n * op.limitPrice <= 10.0 + 1e-9)
        assertEquals(0.0, EventStrategy.kelly(0.4, 0.5), 0.0)
    }
}
