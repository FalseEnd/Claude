package com.perppilot.core

import com.perppilot.core.exchange.ExchangeException
import com.perppilot.core.exchange.okx.OkxClient
import com.perppilot.core.exchange.okx.OkxCredentials
import com.perppilot.core.exchange.okx.OkxSigner
import com.perppilot.core.model.Side
import com.perppilot.core.model.Timeframe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class OkxClientTest {
    private lateinit var server: MockWebServer
    private val creds = OkxCredentials("key", "secret", "pass")

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun client(demo: Boolean = true) =
        OkxClient(creds, demo = demo, baseUrl = server.url("/").toString(), clock = { 1_700_000_000_000L })

    private fun ok(data: String) = MockResponse().setBody("""{"code":"0","msg":"","data":$data}""")

    @Test
    fun candlesDropUnconfirmedBarAndSortAscending() = runTest {
        server.enqueue(ok("""[
            ["1700007200000","3","4","2","3.5","10","1","1","0"],
            ["1700003600000","2","3","1","2.5","10","1","1","1"],
            ["1700000000000","1","2","0.5","1.5","12","1","1","1"]]"""))
        val c = client().candles("BTC-USDT-SWAP", Timeframe.H1, 100)
        assertEquals(listOf(1_700_000_000_000L, 1_700_003_600_000L), c.map { it.time })
        assertEquals(12.0, c.first().volume, 0.0)
        val req = server.takeRequest()
        assertEquals("/api/v5/market/candles?instId=BTC-USDT-SWAP&bar=1H&limit=300", req.path)
        assertEquals("1", req.getHeader("x-simulated-trading"))
    }

    @Test
    fun marketOrderIsSignedAndCarriesAttachedStop() = runTest {
        server.enqueue(ok("""[{"instId":"BTC-USDT-SWAP","ctVal":"0.01","ctMult":"1","lotSz":"0.01","minSz":"0.01","tickSz":"0.1","lever":"100"}]"""))
        server.enqueue(ok("""[{"ts":"1700000000000"}]"""))
        server.enqueue(ok("""[{"ordId":"123","clOrdId":"pp1","sCode":"0","sMsg":""}]"""))
        val res = client().openMarket("BTC-USDT-SWAP", Side.LONG, 1.23, "isolated", hedgeMode = false,
            stopLoss = 59_400.04, takeProfit = null, clientOrderId = "pp1")
        assertEquals("123", res.orderId)
        server.takeRequest() // instruments
        server.takeRequest() // server time
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v5/trade/order", req.path)
        val body = req.body.readUtf8()
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals("buy", json["side"]!!.jsonPrimitive.content)
        assertEquals("1.23", json["sz"]!!.jsonPrimitive.content)
        assertNull(json["posSide"])
        val algo = json["attachAlgoOrds"]!!.jsonArray[0].jsonObject
        assertEquals("59400", algo["slTriggerPx"]!!.jsonPrimitive.content)
        assertEquals("-1", algo["slOrdPx"]!!.jsonPrimitive.content)
        val ts = req.getHeader("OK-ACCESS-TIMESTAMP")!!
        assertEquals(OkxSigner.sign("secret", ts, "POST", "/api/v5/trade/order", body), req.getHeader("OK-ACCESS-SIGN"))
        assertEquals("key", req.getHeader("OK-ACCESS-KEY"))
        assertEquals("pass", req.getHeader("OK-ACCESS-PASSPHRASE"))
        assertEquals("1", req.getHeader("x-simulated-trading"))
    }

    @Test
    fun liveModeOmitsSimulatedHeaderAndHedgeModeSendsPosSide() = runTest {
        server.enqueue(ok("""[{"ts":"1700000000000"}]"""))
        server.enqueue(ok("""[{}]"""))
        client(demo = false).setLeverage("ETH-USDT-SWAP", 5, "isolated", Side.SHORT, hedgeMode = true)
        server.takeRequest()
        val req = server.takeRequest()
        assertNull(req.getHeader("x-simulated-trading"))
        val json = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("short", json["posSide"]!!.jsonPrimitive.content)
        assertEquals("5", json["lever"]!!.jsonPrimitive.content)
    }

    @Test
    fun errorCodesBecomeExceptions() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":"1","msg":"","data":[{"sCode":"51008","sMsg":"Insufficient margin"}]}"""))
        server.enqueue(MockResponse().setBody("""{"code":"1","msg":"","data":[{"sCode":"51008","sMsg":"Insufficient margin"}]}"""))
        try {
            client().ticker("BTC-USDT-SWAP")
            fail("expected exception")
        } catch (e: ExchangeException) {
            assertEquals("1", e.code)
            assertTrue(e.message!!.contains("Insufficient margin"))
        }
    }

    @Test
    fun parsesPositionsInBothPositionModes() = runTest {
        server.enqueue(ok("""[{"ts":"1700000000000"}]"""))
        server.enqueue(ok("""[
            {"instId":"BTC-USDT-SWAP","pos":"-3","posSide":"net","avgPx":"60000","markPx":"59000","upl":"30","lever":"5","liqPx":"70000","mgnMode":"isolated","cTime":"1"},
            {"instId":"ETH-USDT-SWAP","pos":"2","posSide":"long","avgPx":"3000","markPx":"3100","upl":"2","lever":"5","liqPx":"","mgnMode":"cross","cTime":"2"},
            {"instId":"SOL-USDT-SWAP","pos":"0","posSide":"net","avgPx":"","markPx":"","upl":"0","lever":"5","liqPx":"","mgnMode":"cross","cTime":"3"}]"""))
        val ps = client().positions()
        assertEquals(2, ps.size)
        assertEquals(Side.SHORT, ps[0].side)
        assertEquals(3.0, ps[0].contracts, 0.0)
        assertEquals(Side.LONG, ps[1].side)
        assertNull(ps[1].liquidationPrice)
    }
}
