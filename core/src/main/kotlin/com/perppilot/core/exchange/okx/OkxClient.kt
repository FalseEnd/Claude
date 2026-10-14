package com.perppilot.core.exchange.okx

import com.perppilot.core.events.EventApi
import com.perppilot.core.events.EventMarket
import com.perppilot.core.events.EventMethod
import com.perppilot.core.events.EventPosition
import com.perppilot.core.events.EventQuote
import com.perppilot.core.events.EventSeries
import com.perppilot.core.events.Outcome
import com.perppilot.core.exchange.Exchange
import com.perppilot.core.exchange.ExchangeException
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
import com.perppilot.core.risk.RiskManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class OkxCredentials(val apiKey: String, val secret: String, val passphrase: String) {
    val isComplete: Boolean get() = apiKey.isNotBlank() && secret.isNotBlank() && passphrase.isNotBlank()
}

/**
 * OKX v5 REST client for USDT-margined perpetual swaps.
 *
 * When [demo] is true every request carries `x-simulated-trading: 1`, so orders go to OKX's
 * demo-trading environment (requires API keys created under Demo Trading).
 */
class OkxClient(
    private val credentials: OkxCredentials? = null,
    private val demo: Boolean = true,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val http: OkHttpClient = defaultHttpClient(),
    private val clock: () -> Long = System::currentTimeMillis,
) : Exchange, EventApi {

    override val name: String = if (demo) "OKX 模擬盤" else "OKX 實盤"

    private val json = Json { ignoreUnknownKeys = true }
    private val instruments = ConcurrentHashMap<String, InstrumentSpec>()
    private val timeSync = Mutex()
    @Volatile private var clockOffset: Long? = null

    // ---------------------------------------------------------------- transport

    private suspend fun syncClock(): Long = timeSync.withLock {
        clockOffset ?: run {
            val before = clock()
            val data = call("GET", "/api/v5/public/time", emptyMap(), null, auth = false)
            val server = data.first().obj().long("ts")
            val offset = server - (before + clock()) / 2
            clockOffset = offset
            offset
        }
    }

    private suspend fun get(path: String, params: Map<String, String?> = emptyMap(), auth: Boolean = false): JsonArray =
        call("GET", path, params, null, auth)

    private suspend fun post(path: String, body: JsonElement): JsonArray =
        call("POST", path, emptyMap(), body, auth = true)

    private suspend fun call(
        method: String,
        path: String,
        params: Map<String, String?>,
        body: JsonElement?,
        auth: Boolean,
    ): JsonArray {
        val query = params.filterValues { !it.isNullOrEmpty() }.entries.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }
        val requestPath = if (query.isEmpty()) path else "$path?$query"
        val bodyText = body?.toString() ?: ""
        val builder = Request.Builder().url(baseUrl.trimEnd('/') + requestPath)
            .header("Accept", "application/json")
        if (demo) builder.header("x-simulated-trading", "1")
        if (auth) {
            val creds = credentials?.takeIf { it.isComplete }
                ?: throw ExchangeException("尚未設定 OKX API 金鑰")
            val ts = OkxSigner.timestamp(clock() + syncClock())
            builder.header("OK-ACCESS-KEY", creds.apiKey)
                .header("OK-ACCESS-SIGN", OkxSigner.sign(creds.secret, ts, method, requestPath, bodyText))
                .header("OK-ACCESS-TIMESTAMP", ts)
                .header("OK-ACCESS-PASSPHRASE", creds.passphrase)
        }
        if (method == "POST") {
            builder.post(bodyText.toRequestBody(JSON_MEDIA))
        } else {
            builder.get()
        }
        val text = withContext(Dispatchers.IO) {
            http.newCall(builder.build()).execute().use { resp ->
                val s = resp.body?.string().orEmpty()
                if (!resp.isSuccessful && s.isBlank()) throw ExchangeException("HTTP ${resp.code}")
                s
            }
        }
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw ExchangeException("無法解析 OKX 回應: ${text.take(200)}")
        }
        val code = root["code"]?.jsonPrimitive?.contentOrNull ?: "-1"
        val data = root["data"] as? JsonArray ?: JsonArray(emptyList())
        if (code != "0") {
            val detail = data.firstOrNull()?.let { it as? JsonObject }?.let { d ->
                listOfNotNull(d.strOrNull("sCode"), d.strOrNull("sMsg")).joinToString(" ")
            }.orEmpty()
            val msg = root["msg"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (code == "50102") clockOffset = null
            throw ExchangeException("OKX 錯誤 $code: ${listOf(msg, detail).filter { it.isNotBlank() }.joinToString(" / ")} [$path]", code)
        }
        return data
    }

    // ---------------------------------------------------------------- market data

    override suspend fun candles(instId: String, timeframe: Timeframe, limit: Int): List<Candle> {
        if (limit > 300) {
            val start = clock() - (limit + 2L) * timeframe.millis
            return historyCandles(instId, timeframe, start).takeLast(limit)
        }
        val data = get("/api/v5/market/candles", mapOf("instId" to instId, "bar" to timeframe.okx, "limit" to "300"))
        return parseCandles(data).takeLast(limit)
    }

    override suspend fun historyCandles(
        instId: String,
        timeframe: Timeframe,
        startTime: Long,
        onProgress: ((Int) -> Unit)?,
    ): List<Candle> {
        val out = HashMap<Long, Candle>()
        var after: String? = null
        while (true) {
            val data = get(
                "/api/v5/market/history-candles",
                mapOf("instId" to instId, "bar" to timeframe.okx, "limit" to "100", "after" to after),
            )
            if (data.isEmpty()) break
            val batch = parseCandles(data)
            batch.forEach { out[it.time] = it }
            val oldest = data.minOf { it.jsonArray[0].jsonPrimitive.content.toLong() }
            onProgress?.invoke(out.size)
            if (oldest <= startTime) break
            after = oldest.toString()
            delay(110) // history-candles: 20 requests / 2s
        }
        return out.values.filter { it.time >= startTime }.sortedBy { it.time }
    }

    private fun parseCandles(data: JsonArray): List<Candle> = data.mapNotNull { row ->
        val a = row.jsonArray
        // [ts, o, h, l, c, vol, volCcy, volCcyQuote, confirm]; confirm "0" = still forming.
        if (a.size > 8 && a[8].jsonPrimitive.content == "0") return@mapNotNull null
        Candle(
            time = a[0].jsonPrimitive.content.toLong(),
            open = a[1].jsonPrimitive.content.toDouble(),
            high = a[2].jsonPrimitive.content.toDouble(),
            low = a[3].jsonPrimitive.content.toDouble(),
            close = a[4].jsonPrimitive.content.toDouble(),
            volume = a[5].jsonPrimitive.content.toDouble(),
        )
    }.sortedBy { it.time }

    override suspend fun ticker(instId: String): Ticker =
        parseTicker(get("/api/v5/market/ticker", mapOf("instId" to instId)).first().obj())

    override suspend fun tickers(): List<Ticker> =
        get("/api/v5/market/tickers", mapOf("instType" to "SWAP"))
            .map { it.obj() }
            .filter { it.str("instId").endsWith("-USDT-SWAP") }
            .map(::parseTicker)

    private fun parseTicker(o: JsonObject): Ticker {
        val last = o.dbl("last")
        return Ticker(
            instId = o.str("instId"),
            last = last,
            open24h = o.dbl("open24h"),
            high24h = o.dbl("high24h"),
            low24h = o.dbl("low24h"),
            // For swaps volCcy24h is in base currency.
            volQuote24h = o.dbl("volCcy24h") * last,
            time = o.long("ts"),
        )
    }

    override suspend fun funding(instId: String): FundingInfo? {
        val o = get("/api/v5/public/funding-rate", mapOf("instId" to instId)).firstOrNull()?.obj() ?: return null
        return FundingInfo(instId, o.dbl("fundingRate"), o.longOrNull("fundingTime") ?: 0L)
    }

    override suspend fun openInterestUsd(instId: String): Double? {
        val o = get("/api/v5/public/open-interest", mapOf("instType" to "SWAP", "instId" to instId))
            .firstOrNull()?.obj() ?: return null
        return o.dblOrNull("oiUsd") ?: o.dblOrNull("oiCcy")?.let { it * ticker(instId).last }
    }

    override suspend fun longShortRatio(instId: String): Double? {
        val ccy = instId.substringBefore("-")
        val data = get("/api/v5/rubik/stat/contracts/long-short-account-ratio", mapOf("ccy" to ccy, "period" to "1H"))
        val newest = data.firstOrNull()?.jsonArray ?: return null
        return newest.getOrNull(1)?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
    }

    override suspend fun instrument(instId: String): InstrumentSpec {
        instruments[instId]?.let { return it }
        val o = get("/api/v5/public/instruments", mapOf("instType" to "SWAP", "instId" to instId)).firstOrNull()?.obj()
            ?: throw ExchangeException("找不到合約 $instId")
        val spec = InstrumentSpec(
            instId = instId,
            ctVal = o.dbl("ctVal"),
            ctMult = o.dblOrNull("ctMult") ?: 1.0,
            lotSz = o.dbl("lotSz"),
            minSz = o.dbl("minSz"),
            tickSz = o.dbl("tickSz"),
            maxLeverage = o.dblOrNull("lever")?.toInt() ?: 20,
        )
        instruments[instId] = spec
        return spec
    }

    // ---------------------------------------------------------------- trading

    override suspend fun accountConfig(): AccountConfig {
        val o = get("/api/v5/account/config", auth = true).first().obj()
        return AccountConfig(positionMode = o.str("posMode"), accountLevel = o.str("acctLv").toIntOrNull() ?: 1)
    }

    override suspend fun balance(): AccountBalance {
        val o = get("/api/v5/account/balance", mapOf("ccy" to "USDT"), auth = true).first().obj()
        val usdt = (o["details"] as? JsonArray)?.map { it.obj() }?.firstOrNull { it.str("ccy") == "USDT" }
        val equity = usdt?.dblOrNull("eq") ?: o.dbl("totalEq")
        val avail = usdt?.dblOrNull("availEq") ?: usdt?.dblOrNull("availBal") ?: equity
        return AccountBalance(totalEquity = equity, availableEquity = avail, unrealizedPnl = usdt?.dblOrNull("upl") ?: 0.0)
    }

    override suspend fun positions(): List<ExchangePosition> =
        get("/api/v5/account/positions", mapOf("instType" to "SWAP"), auth = true).map { it.obj() }.mapNotNull { o ->
            val pos = o.dblOrNull("pos") ?: return@mapNotNull null
            if (pos == 0.0) return@mapNotNull null
            val posSide = o.str("posSide")
            val side = when (posSide) {
                "long" -> Side.LONG
                "short" -> Side.SHORT
                else -> if (pos > 0) Side.LONG else Side.SHORT
            }
            ExchangePosition(
                instId = o.str("instId"),
                side = side,
                contracts = kotlin.math.abs(pos),
                avgPrice = o.dblOrNull("avgPx") ?: 0.0,
                markPrice = o.dblOrNull("markPx") ?: 0.0,
                unrealizedPnl = o.dblOrNull("upl") ?: 0.0,
                leverage = o.dblOrNull("lever") ?: 0.0,
                liquidationPrice = o.dblOrNull("liqPx"),
                marginMode = o.str("mgnMode"),
                posSide = posSide,
                openTime = o.longOrNull("cTime") ?: 0L,
            )
        }

    override suspend fun setLeverage(instId: String, leverage: Int, marginMode: String, side: Side, hedgeMode: Boolean) {
        post("/api/v5/account/set-leverage", buildJsonObject {
            put("instId", instId)
            put("lever", leverage.toString())
            put("mgnMode", marginMode)
            if (hedgeMode && marginMode == "isolated") put("posSide", side.posSide())
        })
    }

    override suspend fun openMarket(
        instId: String,
        side: Side,
        contracts: Double,
        marginMode: String,
        hedgeMode: Boolean,
        stopLoss: Double,
        takeProfit: Double?,
        clientOrderId: String,
    ): OrderResult {
        val spec = instrument(instId)
        val body = buildJsonObject {
            put("instId", instId)
            put("tdMode", marginMode)
            put("side", if (side == Side.LONG) "buy" else "sell")
            if (hedgeMode) put("posSide", side.posSide())
            put("ordType", "market")
            put("sz", fmt(contracts, spec.lotSz))
            put("clOrdId", clientOrderId)
            put("attachAlgoOrds", buildJsonArray {
                add(buildJsonObject {
                    put("slTriggerPx", fmt(stopLoss, spec.tickSz))
                    put("slOrdPx", "-1")
                    put("slTriggerPxType", "mark")
                    if (takeProfit != null) {
                        put("tpTriggerPx", fmt(takeProfit, spec.tickSz))
                        put("tpOrdPx", "-1")
                        put("tpTriggerPxType", "last")
                    }
                })
            })
        }
        val o = post("/api/v5/trade/order", body).first().obj()
        checkSCode(o)
        return OrderResult(orderId = o.str("ordId"), clientOrderId = o.str("clOrdId"), avgPrice = null, filledContracts = null)
    }

    override suspend fun order(instId: String, orderId: String): OrderResult {
        val o = get("/api/v5/trade/order", mapOf("instId" to instId, "ordId" to orderId), auth = true).first().obj()
        return OrderResult(
            orderId = o.str("ordId"),
            clientOrderId = o.str("clOrdId"),
            avgPrice = o.dblOrNull("avgPx")?.takeIf { it > 0 },
            filledContracts = o.dblOrNull("accFillSz"),
        )
    }

    override suspend fun stopOrders(instId: String): List<StopOrder> =
        listOf("conditional", "oco").flatMap { type ->
            get(
                "/api/v5/trade/orders-algo-pending",
                mapOf("instType" to "SWAP", "instId" to instId, "ordType" to type),
                auth = true,
            ).map { it.obj() }.map { o ->
                StopOrder(
                    algoId = o.str("algoId"),
                    instId = o.str("instId"),
                    stopLoss = o.dblOrNull("slTriggerPx"),
                    takeProfit = o.dblOrNull("tpTriggerPx"),
                    side = o.str("side"),
                )
            }
        }

    override suspend fun amendStop(instId: String, algoId: String, newStop: Double) {
        val spec = instrument(instId)
        val o = post("/api/v5/trade/amend-algos", buildJsonObject {
            put("instId", instId)
            put("algoId", algoId)
            put("newSlTriggerPx", fmt(newStop, spec.tickSz))
            put("newSlOrdPx", "-1")
        }).first().obj()
        checkSCode(o)
    }

    override suspend fun placeStop(instId: String, side: Side, marginMode: String, hedgeMode: Boolean, stop: Double): String {
        val spec = instrument(instId)
        val o = post("/api/v5/trade/order-algo", buildJsonObject {
            put("instId", instId)
            put("tdMode", marginMode)
            put("side", if (side == Side.LONG) "sell" else "buy")
            if (hedgeMode) put("posSide", side.posSide()) else put("reduceOnly", true)
            put("ordType", "conditional")
            put("closeFraction", "1")
            put("slTriggerPx", fmt(stop, spec.tickSz))
            put("slOrdPx", "-1")
            put("slTriggerPxType", "mark")
        }).first().obj()
        checkSCode(o)
        return o.str("algoId")
    }

    override suspend fun cancelStop(instId: String, algoId: String) {
        post("/api/v5/trade/cancel-algos", buildJsonArray {
            add(buildJsonObject {
                put("algoId", algoId)
                put("instId", instId)
            })
        })
    }

    override suspend fun closePosition(instId: String, side: Side, marginMode: String, hedgeMode: Boolean) {
        post("/api/v5/trade/close-position", buildJsonObject {
            put("instId", instId)
            put("mgnMode", marginMode)
            if (hedgeMode) put("posSide", side.posSide())
            put("autoCxl", true)
        })
    }

    override suspend fun closedPositions(instId: String, since: Long): List<ClosedPositionRecord> =
        get("/api/v5/account/positions-history", mapOf("instType" to "SWAP", "instId" to instId), auth = true)
            .map { it.obj() }
            .filter { (it.longOrNull("uTime") ?: 0L) >= since }
            .map { o ->
                ClosedPositionRecord(
                    instId = o.str("instId"),
                    side = if (o.str("direction") == "short") Side.SHORT else Side.LONG,
                    openPrice = o.dblOrNull("openAvgPx") ?: 0.0,
                    closePrice = o.dblOrNull("closeAvgPx") ?: 0.0,
                    realizedPnl = o.dblOrNull("realizedPnl") ?: o.dblOrNull("pnl") ?: 0.0,
                    openTime = o.longOrNull("cTime") ?: 0L,
                    closeTime = o.longOrNull("uTime") ?: 0L,
                    closeType = when (o.str("type")) {
                        "3", "4" -> "liquidation"
                        "5" -> "adl"
                        else -> "close"
                    },
                )
            }
            .sortedBy { it.closeTime }

    /** Public GET returning rows of string arrays (candles-style endpoints). */
    suspend fun rawGet(path: String, params: Map<String, String?>): List<List<String>> =
        get(path, params).map { row -> row.jsonArray.map { it.jsonPrimitive.content } }

    // ---------------------------------------------------------------- event contracts

    override suspend fun eventSeries(): List<EventSeries> =
        get("/api/v5/public/event-contract/series").map { it.obj() }.map { o ->
            val st = o["settlement"] as? JsonObject
            EventSeries(
                seriesId = o.str("seriesId"),
                title = o.str("title"),
                freq = o.str("freq"),
                method = EventMethod.parse(st?.str("method").orEmpty()),
                underlying = st?.str("underlying").orEmpty(),
            )
        }

    override suspend fun eventMarkets(seriesId: String, state: String?, after: Long?): List<EventMarket> =
        get(
            "/api/v5/public/event-contract/markets",
            mapOf("seriesId" to seriesId, "state" to state, "after" to after?.toString()),
        ).map { it.obj() }.map { o ->
            // Older expired up/down markets omit instId; for them it equals the eventId.
            val instId = o.str("instId").ifEmpty { if ("-UPDOWN-" in o.str("eventId")) o.str("eventId") else "" }
            EventMarket(
                instId = instId,
                seriesId = o.str("seriesId"),
                method = EventMethod.parse(seriesMethod(o.str("seriesId"))),
                floorStrike = o.dblOrNull("floorStrike"),
                capStrike = o.strOrNull("capStrike")?.let { if (it == "INF") Double.POSITIVE_INFINITY else it.toDoubleOrNull() },
                hitDir = o.str("hitDir"),
                listTime = o.long("listTime"),
                expTime = o.long("expTime"),
                fixTime = o.long("fixTime"),
                state = o.str("state"),
                outcome = o.str("outcome").toIntOrNull() ?: 0,
                settleValue = o.dblOrNull("settleValue"),
            )
        }

    /** The series id encodes the settlement method: BTC-UPDOWN-15MIN, BTC-ABOVE-DAILY, … */
    private fun seriesMethod(seriesId: String): String = when {
        "-UPDOWN-" in seriesId -> "price_up_down"
        "-ABOVE-" in seriesId -> "price_above"
        "-BETWEEN-" in seriesId -> "between"
        "-HIT-" in seriesId -> "hit"
        else -> ""
    }

    override suspend fun eventQuote(instId: String): EventQuote {
        val o = get("/api/v5/market/ticker", mapOf("instId" to instId)).first().obj()
        return EventQuote(
            instId = instId,
            yesBid = o.dblOrNull("bidPx")?.takeIf { it > 0 },
            yesAsk = o.dblOrNull("askPx")?.takeIf { it > 0 },
            last = o.dblOrNull("last"),
            time = o.long("ts"),
        )
    }

    override suspend fun indexPrice(underlying: String): Double =
        get("/api/v5/market/index-tickers", mapOf("instId" to underlying)).first().obj().dbl("idxPx")

    override suspend fun indexCloses(underlying: String, bar: String, limit: Int): List<Double> =
        get("/api/v5/market/index-candles", mapOf("instId" to underlying, "bar" to bar, "limit" to limit.coerceAtMost(100).toString()))
            .map { it.jsonArray }
            .filter { it.size < 6 || it[5].jsonPrimitive.content != "0" }
            .sortedBy { it[0].jsonPrimitive.content.toLong() }
            .map { it[4].jsonPrimitive.content.toDouble() }

    override suspend fun eventPositions(): List<EventPosition> =
        get("/api/v5/account/positions", mapOf("instType" to "EVENTS"), auth = true).map { it.obj() }.mapNotNull { o ->
            val pos = o.dblOrNull("pos") ?: return@mapNotNull null
            if (pos == 0.0) return@mapNotNull null
            EventPosition(
                instId = o.str("instId"),
                outcome = if (o.str("outcome").lowercase() == "no" || o.str("posSide") == "short") Outcome.NO else Outcome.YES,
                contracts = kotlin.math.abs(pos),
                avgPrice = o.dblOrNull("avgPx") ?: 0.0,
            )
        }

    override suspend fun buyEvent(instId: String, outcome: Outcome, contracts: Double, price: Double, clientOrderId: String): String {
        val o = post("/api/v5/trade/order", buildJsonObject {
            put("instId", instId)
            put("tdMode", "isolated")
            put("side", "buy")
            put("outcome", outcome.api)
            put("ordType", "limit")
            put("px", fmt(price, 0.001))
            put("sz", fmt(contracts, 0.1))
            put("clOrdId", clientOrderId)
        }).first().obj()
        checkSCode(o)
        return o.str("ordId")
    }

    private fun checkSCode(o: JsonObject) {
        val sCode = o.strOrNull("sCode") ?: return
        if (sCode != "0") throw ExchangeException("OKX 拒絕: $sCode ${o.strOrNull("sMsg").orEmpty()}", sCode)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://www.okx.com"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()

        private fun Side.posSide(): String = if (this == Side.LONG) "long" else "short"

        /** Formats a number on the instrument's step without scientific notation. */
        fun fmt(value: Double, step: Double): String {
            val rounded = RiskManager.roundToStep(value, step)
            return java.math.BigDecimal(rounded.toString())
                .setScale(RiskManager.decimalsOf(step), java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString()
        }
    }
}

private fun JsonElement.obj(): JsonObject = jsonObject
private fun JsonObject.strOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
private fun JsonObject.str(key: String): String = strOrNull(key).orEmpty()
private fun JsonObject.dblOrNull(key: String): Double? = strOrNull(key)?.toDoubleOrNull()
private fun JsonObject.dbl(key: String): Double = dblOrNull(key) ?: 0.0
private fun JsonObject.longOrNull(key: String): Long? = strOrNull(key)?.toLongOrNull()
private fun JsonObject.long(key: String): Long = longOrNull(key) ?: 0L
