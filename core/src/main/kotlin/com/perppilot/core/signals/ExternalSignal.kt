package com.perppilot.core.signals

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.time.Instant

enum class SignalAction(val label: String) { LONG("開多"), SHORT("開空"), CLOSE("平倉"), CLOSE_LONG("平多"), CLOSE_SHORT("平空") }

/** A trade instruction from an outside source such as a TradingView alert. */
@Serializable
data class ExternalSignal(
    val id: String,
    val time: Long,
    val action: String,
    val instId: String,
    val price: Double? = null,
    val stop: Double? = null,
    val takeProfit: Double? = null,
    val riskPct: Double? = null,
    val secret: String? = null,
    val note: String? = null,
    val source: String = "TradingView",
) {
    val signalAction: SignalAction? get() = runCatching { SignalAction.valueOf(action) }.getOrNull()
}

@Serializable
data class ExternalSignalConfig(
    val enabled: Boolean = false,
    /** ntfy server; https://ntfy.sh or a self-hosted instance. */
    val ntfyServer: String = "https://ntfy.sh",
    /** Long random topic name: it is the webhook address, keep it private. */
    val ntfyTopic: String = "",
    /** Must match the "secret" field of every alert. */
    val secret: String = "",
    /** Ignore alerts older than this (phone was asleep, relay delay, …). */
    val maxAgeSeconds: Int = 300,
    /** Only trade symbols that are in the watchlist. */
    val requireWatchlist: Boolean = true,
    /** true: the app trails stops / exits like its own trades; false: only the initial stop + TV close alerts. */
    val appManagesExits: Boolean = true,
    /** Close an opposite position before opening a new one. */
    val allowReverse: Boolean = true,
)

/** Parses TradingView alert messages (JSON) into [ExternalSignal]s. */
object TradingViewParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Accepted fields (aliases in brackets):
     * action [side, order]: long|buy|short|sell|close|exit|flat|close_long|close_short
     * symbol [ticker, instId]: BTCUSDT.P, OKX:BTCUSDT.P, BTC-USDT-SWAP, …
     * price [close], stop [sl], tp [takeProfit], risk [riskPct], secret [passphrase], time [timenow], id
     * position [market_position]: TradingView strategy {{strategy.market_position}}; "flat" turns the alert into a close.
     */
    fun parse(text: String, receivedAt: Long, fallbackId: String): ExternalSignal? {
        val o = runCatching { json.parseToJsonElement(text.trim()).jsonObject }.getOrNull() ?: return null
        fun s(vararg keys: String): String? = keys.firstNotNullOfOrNull { k ->
            (o[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        }
        fun d(vararg keys: String): Double? = s(*keys)?.toDoubleOrNull()?.takeIf { it > 0 }

        val rawAction = s("action", "side", "order")?.lowercase() ?: return null
        val position = s("position", "market_position")?.lowercase()
        val action = when {
            position == "flat" -> SignalAction.CLOSE
            rawAction in setOf("long", "buy", "open_long") -> SignalAction.LONG
            rawAction in setOf("short", "sell", "open_short") -> SignalAction.SHORT
            rawAction in setOf("close", "exit", "flat") -> SignalAction.CLOSE
            rawAction == "close_long" -> SignalAction.CLOSE_LONG
            rawAction == "close_short" -> SignalAction.CLOSE_SHORT
            else -> return null
        }
        val symbol = s("symbol", "ticker", "instId") ?: return null
        val time = s("time", "timenow")?.let { t ->
            t.toLongOrNull()?.let { if (it < 10_000_000_000L) it * 1000 else it }
                ?: runCatching { Instant.parse(t).toEpochMilli() }.getOrNull()
        } ?: receivedAt
        return ExternalSignal(
            id = s("id") ?: fallbackId,
            time = time,
            action = action.name,
            instId = normalizeSymbol(symbol),
            price = d("price", "close"),
            stop = d("stop", "sl", "stopLoss"),
            takeProfit = d("tp", "takeProfit"),
            riskPct = d("risk", "riskPct"),
            secret = s("secret", "passphrase"),
            note = s("note", "comment", "strategy"),
        )
    }

    /** BTCUSDT.P / OKX:BTCUSDT.P / BTCUSDT / BTC-USDT / BTC-USDT-SWAP → BTC-USDT-SWAP. */
    fun normalizeSymbol(raw: String): String {
        val s = raw.trim().uppercase().substringAfter(":").removeSuffix(".P").removeSuffix("PERP")
        return when {
            s.endsWith("-USDT-SWAP") -> s
            s.contains("-") -> s.substringBefore("-") + "-USDT-SWAP"
            s.endsWith("USDT") -> s.removeSuffix("USDT") + "-USDT-SWAP"
            else -> "$s-USDT-SWAP"
        }
    }
}

/** Raw message from a relay channel. */
data class RelayMessage(val id: String, val time: Long, val text: String)

fun JsonObject.relayText(): String? = (this["message"] as? JsonPrimitive)?.contentOrNull
