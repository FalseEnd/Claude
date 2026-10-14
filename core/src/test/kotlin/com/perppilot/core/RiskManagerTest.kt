package com.perppilot.core

import com.perppilot.core.model.InstrumentSpec
import com.perppilot.core.risk.RiskConfig
import com.perppilot.core.risk.RiskManager
import com.perppilot.core.risk.RiskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskManagerTest {
    private val btc = InstrumentSpec("BTC-USDT-SWAP", ctVal = 0.01, lotSz = 0.01, minSz = 0.01, tickSz = 0.1, maxLeverage = 100)

    @Test
    fun sizesSoThatStopLossCostsOnePercent() {
        val cfg = RiskConfig(riskPerTradePct = 1.0, leverage = 5, maxOpenPositions = 3)
        // 10k equity, entry 60000, stop 59400 (1%): risk 100 USDT -> 0.1666 BTC -> 16.66 contracts -> 16.66
        val s = requireNotNull(RiskManager.size(10_000.0, 60_000.0, 59_400.0, cfg, btc))
        assertEquals(16.66, s.contracts, 1e-9)
        assertEquals(0.1666, s.quantity, 1e-9)
        assertTrue(s.riskAmount <= 100.0 + 1e-9)
        assertEquals(s.notional / 5, s.margin, 1e-9)
    }

    @Test
    fun capsNotionalByLeverageBudget() {
        val cfg = RiskConfig(riskPerTradePct = 1.0, leverage = 3, maxOpenPositions = 3)
        // Very tight stop would ask for 100k notional; budget is 10k*3*0.95/3 = 9.5k.
        val s = RiskManager.size(10_000.0, 100.0, 99.9, cfg)!!
        assertEquals(9_500.0, s.notional, 1e-6)
        assertTrue(s.riskAmount < 100.0)
    }

    @Test
    fun rejectsTradesBelowMinimumSizeOrWithStopNearLiquidation() {
        val cfg = RiskConfig(riskPerTradePct = 0.01, leverage = 5)
        assertNull(RiskManager.size(100.0, 60_000.0, 59_000.0, cfg, btc))
        // Stop 15% away with 5x leverage (liquidation ~20%) is rejected: must be < 10%.
        assertNull(RiskManager.size(10_000.0, 100.0, 85.0, RiskConfig(leverage = 5)))
    }

    @Test
    fun dailyLossLimitHaltsUntilNextUtcDay() {
        val cfg = RiskConfig(dailyLossLimitPct = 3.0, maxDrawdownPct = 50.0)
        val day = 20_000L * RiskManager.DAY_MS
        var s = RiskManager.onEquity(RiskState(), 10_000.0, day + 1000, cfg)
        s = RiskManager.onEquity(s, 9_690.0, day + 2000, cfg)
        assertTrue(s.isHalted(day + 3000))
        assertFalse(RiskManager.canOpen(s, day + 3000, cfg, 0, 0.0).allowed)
        val nextDay = day + RiskManager.DAY_MS + 1
        s = RiskManager.onEquity(s, 9_690.0, nextDay, cfg)
        assertFalse(s.isHalted(nextDay))
        assertEquals(9_690.0, s.dayStartEquity, 1e-9)
    }

    @Test
    fun drawdownKillSwitchNeedsManualReset() {
        val cfg = RiskConfig(dailyLossLimitPct = 0.0, maxDrawdownPct = 10.0)
        var s = RiskManager.onEquity(RiskState(), 10_000.0, 0, cfg)
        s = RiskManager.onEquity(s, 12_000.0, 10 * RiskManager.DAY_MS, cfg)
        s = RiskManager.onEquity(s, 10_700.0, 20 * RiskManager.DAY_MS, cfg)
        assertTrue(s.isHalted(Long.MAX_VALUE / 2))
        s = RiskManager.resetHalt(s, 10_700.0)
        assertFalse(s.isHalted(30 * RiskManager.DAY_MS))
        assertEquals(10_700.0, s.peakEquity, 1e-9)
    }

    @Test
    fun consecutiveLossesTriggerCooldown() {
        val cfg = RiskConfig(maxConsecutiveLosses = 3, cooldownHours = 12)
        var s = RiskState()
        s = RiskManager.onTradeClosed(s, -10.0, 0, cfg)
        s = RiskManager.onTradeClosed(s, -10.0, 0, cfg)
        assertEquals(2, s.consecutiveLosses)
        s = RiskManager.onTradeClosed(s, -10.0, 1000, cfg)
        assertEquals(1000 + 12 * RiskManager.HOUR_MS, s.cooldownUntil)
        assertFalse(RiskManager.canOpen(s, 2000, cfg, 0, 0.0).allowed)
        assertTrue(RiskManager.canOpen(s, s.cooldownUntil, cfg, 0, 0.0).allowed)
    }

    @Test
    fun limitsPositionsAndTotalRisk() {
        val cfg = RiskConfig(maxOpenPositions = 2, maxTotalRiskPct = 2.0, riskPerTradePct = 1.0)
        assertFalse(RiskManager.canOpen(RiskState(), 0, cfg, 2, 0.0).allowed)
        assertFalse(RiskManager.canOpen(RiskState(), 0, cfg, 1, 1.5).allowed)
        assertTrue(RiskManager.canOpen(RiskState(), 0, cfg, 1, 1.0).allowed)
    }

    @Test
    fun roundsPricesToTick() {
        assertEquals(100.1, RiskManager.roundPrice(100.14, 0.1, up = false), 1e-12)
        assertEquals(100.2, RiskManager.roundPrice(100.11, 0.1, up = true), 1e-12)
        assertEquals(100.0, RiskManager.roundPrice(100.0, 0.1, up = true), 1e-12)
    }
}
