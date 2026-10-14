package com.perppilot.core

import com.perppilot.core.engine.EngineSettings
import com.perppilot.core.engine.EventKind
import com.perppilot.core.engine.InMemoryEngineStore
import com.perppilot.core.engine.TradingEngine
import com.perppilot.core.model.Side
import com.perppilot.core.model.Timeframe
import com.perppilot.core.signals.ExternalSignalConfig
import com.perppilot.core.signals.NtfyClient
import com.perppilot.core.signals.SignalAction
import com.perppilot.core.signals.TradingViewParser
import com.perppilot.core.engine.EngineEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalsTest {
    @Test
    fun normalizesTradingViewTickers() {
        assertEquals("BTC-USDT-SWAP", TradingViewParser.normalizeSymbol("BTCUSDT.P"))
        assertEquals("BTC-USDT-SWAP", TradingViewParser.normalizeSymbol("OKX:BTCUSDT.P"))
        assertEquals("ETH-USDT-SWAP", TradingViewParser.normalizeSymbol("eth-usdt"))
        assertEquals("SOL-USDT-SWAP", TradingViewParser.normalizeSymbol("SOL-USDT-SWAP"))
        assertEquals("DOGE-USDT-SWAP", TradingViewParser.normalizeSymbol("DOGEUSDTPERP"))
    }

    @Test
    fun parsesPineAndStrategyAlertFormats() {
        val a = TradingViewParser.parse(
            """{"secret":"s","action":"long","symbol":"BTCUSDT.P","price":65000.5,"stop":64000,"time":"2026-10-14T13:00:05Z"}""", 0, "m1",
        )!!
        assertEquals(SignalAction.LONG.name, a.action)
        assertEquals(64000.0, a.stop!!, 0.0)
        assertEquals(1791982805000L, a.time)
        assertEquals("s", a.secret)

        // {{strategy.order.action}} = sell while {{strategy.market_position}} = flat → close, not short.
        val b = TradingViewParser.parse("""{"action":"sell","position":"flat","ticker":"ETHUSDT.P"}""", 123, "m2")!!
        assertEquals(SignalAction.CLOSE.name, b.action)
        assertEquals(123L, b.time)
        assertEquals("m2", b.id)

        assertNull(TradingViewParser.parse("not json", 0, "x"))
        assertNull(TradingViewParser.parse("""{"action":"hold","symbol":"BTC"}""", 0, "x"))
    }

    @Test
    fun parsesNtfyStream() {
        val body = """
            {"id":"a1","time":1700000000,"event":"open","topic":"t"}
            {"id":"a2","time":1700000001,"event":"message","topic":"t","message":"{\"action\":\"long\"}"}
            {"id":"a3","time":1700000002,"event":"keepalive","topic":"t"}
        """.trimIndent()
        val msgs = NtfyClient("https://ntfy.sh", "t").parseLines(body)
        assertEquals(1, msgs.size)
        assertEquals("a2", msgs[0].id)
        assertEquals(1_700_000_001_000L, msgs[0].time)
        assertEquals("{\"action\":\"long\"}", msgs[0].text)
    }

    private val end = 1_000L * Timeframe.H4.millis
    private fun exchange(): FakeExchange {
        val ltf = TestData.trend(300, Timeframe.H4, end - Timeframe.H4.millis, start = 60_000.0, stepPct = 0.05)
        val htf = TestData.trend(300, Timeframe.D1, end - Timeframe.D1.millis, start = 30_000.0, stepPct = 0.2)
        return FakeExchange(ltf, htf)
    }

    @Test
    fun executesValidAlertsAndRejectsBadOnes() = runTest {
        val ex = exchange()
        val store = InMemoryEngineStore()
        val events = mutableListOf<EngineEvent>()
        val now = end + 60_000
        val engine = TradingEngine(ex, ex, store, { events += it }, clock = { now }, fillWaitMs = 0)
        val settings = EngineSettings(watchlist = listOf("BTC-USDT-SWAP"), autoTrade = true)
        val cfg = ExternalSignalConfig(enabled = true, secret = "s3cret", appManagesExits = false)
        val price = ex.ltf.last().close
        fun msg(json: String, id: String, t: Long = now) = TradingViewParser.parse(json, t, id)!!

        // Wrong secret, stale alert and non-watchlist symbol are all rejected.
        engine.handleExternal(listOf(
            msg("""{"secret":"nope","action":"long","symbol":"BTCUSDT.P"}""", "1"),
            msg("""{"secret":"s3cret","action":"long","symbol":"BTCUSDT.P"}""", "2", t = now - 3_600_000),
            msg("""{"secret":"s3cret","action":"long","symbol":"PEPEUSDT.P"}""", "3"),
        ), settings, cfg)
        assertTrue(ex.orders.isEmpty())
        assertTrue(events.any { it.kind == EventKind.ERROR && it.message.contains("密鑰") })

        // Valid long with an explicit stop → order sized on that stop; replaying the same id is ignored.
        val stop = price * 0.98
        val good = msg("""{"secret":"s3cret","action":"long","symbol":"BTCUSDT.P","price":$price,"stop":$stop}""", "4")
        engine.handleExternal(listOf(good), settings, cfg)
        engine.handleExternal(listOf(good), settings, cfg)
        assertEquals(1, ex.orders.size)
        val track = store.load().positions.getValue("BTC-USDT-SWAP")
        assertEquals("TradingView", track.origin)
        assertEquals(false, track.manageExits)
        assertEquals(Side.LONG, track.side)
        assertEquals(stop, track.initialStop, price * 0.0001)

        // Close alert flattens and journals the trade.
        engine.handleExternal(listOf(msg("""{"secret":"s3cret","action":"close","symbol":"BTCUSDT.P"}""", "5")), settings, cfg)
        assertTrue(ex.positions.isEmpty())
        assertEquals("TradingView 平倉訊號", store.load().journal.single().exitReason)
    }

    @Test
    fun rejectsStopOnWrongSide() = runTest {
        val ex = exchange()
        val events = mutableListOf<EngineEvent>()
        val now = end + 60_000
        val engine = TradingEngine(ex, ex, InMemoryEngineStore(), { events += it }, clock = { now }, fillWaitMs = 0)
        val price = ex.ltf.last().close
        val sig = TradingViewParser.parse("""{"action":"short","symbol":"BTCUSDT.P","stop":${price * 0.9}}""", now, "x")!!
        engine.handleExternal(listOf(sig), EngineSettings(watchlist = listOf("BTC-USDT-SWAP"), autoTrade = true), ExternalSignalConfig(enabled = true))
        assertTrue(ex.orders.isEmpty())
        assertTrue(events.any { it.message.contains("止損價") })
    }
}
