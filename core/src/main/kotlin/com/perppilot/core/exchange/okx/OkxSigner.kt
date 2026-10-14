package com.perppilot.core.exchange.okx

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** OKX v5 request signing: Base64(HMAC-SHA256(secret, timestamp + METHOD + requestPath + body)). */
object OkxSigner {
    private val formatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun timestamp(epochMillis: Long): String = formatter.format(Instant.ofEpochMilli(epochMillis))

    fun prehash(timestamp: String, method: String, requestPath: String, body: String): String =
        timestamp + method.uppercase() + requestPath + body

    fun sign(secret: String, timestamp: String, method: String, requestPath: String, body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal(prehash(timestamp, method, requestPath, body).toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(digest)
    }
}
