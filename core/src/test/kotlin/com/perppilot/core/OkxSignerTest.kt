package com.perppilot.core

import com.perppilot.core.exchange.okx.OkxClient
import com.perppilot.core.exchange.okx.OkxSigner
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

class OkxSignerTest {
    @Test
    fun hmacMatchesRfc4231TestCase2() {
        // RFC 4231 test case 2: key "Jefe", data "what do ya want for nothing?"
        val sig = OkxSigner.sign("Jefe", "", "", "what do ya want for nothing?", "")
        val hex = Base64.getDecoder().decode(sig).joinToString("") { "%02x".format(it) }
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", hex)
    }

    @Test
    fun prehashConcatenatesInOkxOrder() {
        assertEquals(
            "2020-12-08T09:08:57.715ZGET/api/v5/account/balance?ccy=BTC",
            OkxSigner.prehash("2020-12-08T09:08:57.715Z", "get", "/api/v5/account/balance?ccy=BTC", ""),
        )
    }

    @Test
    fun timestampIsIsoMillisUtc() {
        assertEquals("2020-12-08T09:08:57.715Z", OkxSigner.timestamp(1607418537715L))
    }

    @Test
    fun formatsNumbersOnInstrumentStep() {
        assertEquals("0.12", OkxClient.fmt(0.123, 0.01))
        assertEquals("65000.1", OkxClient.fmt(65000.1, 0.1))
        assertEquals("3", OkxClient.fmt(3.0, 1.0))
        assertEquals("0.00001234", OkxClient.fmt(0.000012341, 0.00000001))
    }
}
