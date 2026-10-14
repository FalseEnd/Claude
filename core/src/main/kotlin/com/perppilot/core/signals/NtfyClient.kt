package com.perppilot.core.signals

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

/**
 * Minimal ntfy (https://ntfy.sh) client. TradingView posts its alert to `server/topic` as a
 * webhook; the phone polls the same topic. No account or own server is needed.
 */
class NtfyClient(
    private val server: String,
    private val topic: String,
    private val http: OkHttpClient = OkHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    val webhookUrl: String get() = server.trimEnd('/') + "/" + topic

    /** Messages after message id [since] (or from the last [fallback] window when null), oldest first. */
    suspend fun poll(since: String?, fallback: String = "10m"): List<RelayMessage> {
        val url = "$webhookUrl/json?poll=1&since=" + URLEncoder.encode(since ?: fallback, "UTF-8")
        return parseLines(execute(Request.Builder().url(url).get().build()))
    }

    /** Publishes [text] to the topic (used by the in-app "send test alert" button). */
    suspend fun publish(text: String) {
        execute(Request.Builder().url(webhookUrl).post(text.toRequestBody()).build())
    }

    /** Executes with up to two retries when the public server rate-limits (HTTP 429). */
    private suspend fun execute(request: Request): String {
        var attempt = 0
        while (true) {
            val (code, body) = withContext(Dispatchers.IO) {
                http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
            }
            if (code in 200..299) return body
            if (code != 429 || attempt >= 2) throw IllegalStateException("ntfy HTTP $code ${body.take(160)}")
            attempt++
            delay(2_000L * attempt)
        }
    }

    fun parseLines(body: String): List<RelayMessage> = body.lineSequence()
        .filter { it.isNotBlank() }
        .mapNotNull { line -> runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() }
        .filter { (it["event"] as? JsonPrimitive)?.contentOrNull == "message" }
        .mapNotNull { o ->
            val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val time = (o["time"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()?.times(1000) ?: 0L
            RelayMessage(id, time, o.relayText() ?: return@mapNotNull null)
        }
        .toList()
}
