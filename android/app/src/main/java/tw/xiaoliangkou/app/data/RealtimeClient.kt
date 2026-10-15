package tw.xiaoliangkou.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Supabase Realtime（Phoenix 協定）：收通知、收「資料變了」、收「對方想知道你在哪」。
 * - 加入自己的頻道，訂閱 notification_outbox 與 realtime_events 的新增
 * - 每 25 秒送一次心跳；access token 換新時告訴 Realtime
 * - 連上（或重新連上）時用 app_pending_notifications 補抓斷線期間的通知；收到的通知用 app_ack_notifications 標記已送達
 * 斷線會自動重連（2 秒起，最長 60 秒）。
 * Realtime 連不上（例如 Wi-Fi、VPN 擋掉 WebSocket）但 API 還能用時，由 [pollIfDisconnected] 改用 RPC 定期補抓通知。
 */
class RealtimeClient(private val scope: CoroutineScope) {
    sealed interface Event {
        data class Notification(val id: Long, val kind: String, val title: String, val body: String, val data: JsonObject?) : Event
        data class Changed(val table: String, val byUserId: String?) : Event
        data object Locate : Event
    }

    /** 一則 Realtime 訊息解析後的結果 */
    sealed interface Frame {
        /** phx_reply：加入頻道、心跳、換 token 的回覆 */
        data class Reply(val ref: String?, val ok: Boolean) : Frame
        /** postgres_changes：[event] 為 null 代表內容不完整（例如太大被截掉），要用 RPC 補抓 */
        data class Change(val event: Event?) : Frame
        /** system：訂閱資料表成功或失敗、token 過期等 */
        data class System(val ok: Boolean, val message: String?) : Frame
        /** 頻道被關閉或出錯，要重新連線 */
        data object Closed : Frame
        data object Ignore : Frame
    }

    private class Config(val wsUrl: String, val userId: String, val token: suspend () -> String?, val api: Api?)

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 128)
    val events: SharedFlow<Event> = _events
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected
    private val _subscribed = MutableStateFlow(false)
    /** 資料表訂閱也完成了（Realtime 回覆「Subscribed to PostgreSQL」），之後的變動都收得到 */
    val subscribed: StateFlow<Boolean> = _subscribed

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()
    @Volatile private var config: Config? = null
    @Volatile private var socket: WebSocket? = null
    @Volatile private var running = false
    @Volatile private var joinRef: String? = null
    @Volatile private var heartbeatRef: String? = null
    @Volatile private var sentToken: String? = null
    @Volatile private var fetchAgain = false
    private var connectJob: Job? = null
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var fetchJob: Job? = null
    private var ackJob: Job? = null
    private var backoffMs = 2_000L
    private val ref = AtomicInteger(0)
    private val seen = ArrayDeque<Long>()
    private val toAck = LinkedHashSet<Long>()

    /**
     * 開始連線。[token] 回傳目前有效的 access token（快過期時會先換新），
     * [api] 用來補抓與確認通知。
     */
    fun start(url: String, key: String, userId: String, token: suspend () -> String?, api: Api?) {
        stop()
        config = Config(socketUrl(url, key), userId, token, api)
        running = true
        connect()
    }

    fun stop() {
        running = false
        listOf(connectJob, reconnectJob, heartbeatJob, fetchJob, ackJob).forEach { it?.cancel() }
        synchronized(toAck) { toAck.clear() }
        val ws = socket
        socket = null
        ws?.close(1000, "bye")
        _connected.value = false
        _subscribed.value = false
        config = null
    }

    /** 網路恢復時呼叫，立刻重連而不是等退避時間 */
    fun reconnectNow() {
        if (!running || _connected.value) return
        reconnectJob?.cancel()
        backoffMs = 2_000L
        connect()
    }

    /**
     * 即時通知收不到時（沒連上，或資料表訂閱還沒成功）用 RPC 補抓一次。
     * App 回到前景、重新整理、背景服務每幾分鐘都會呼叫；跟即時收到的通知走同一條去重、確認送達的路。
     */
    fun pollIfDisconnected(): Boolean {
        if (!running || (_connected.value && _subscribed.value)) return false
        fetchPending()
        return true
    }

    /** access token 換新了：告訴 Realtime，頻道才不會因為 token 過期被關掉 */
    fun updateToken(token: String) {
        val ws = socket ?: return
        val cfg = config ?: return
        if (!_connected.value || token == sentToken) return
        sentToken = token
        ws.send(accessTokenMessage(cfg.userId, token, nextRef()))
    }

    private fun nextRef() = ref.incrementAndGet().toString()

    private fun connect() {
        val cfg = config ?: return
        connectJob?.cancel()
        socket?.let { old -> socket = null; old.cancel() }
        connectJob = scope.launch {
            val token = runCatching { cfg.token() }.getOrNull()
            if (!running || config !== cfg) return@launch
            if (token == null) { scheduleReconnect(null); return@launch }
            socket = client.newWebSocket(Request.Builder().url(cfg.wsUrl).build(), Listener(cfg, token))
        }
    }

    private inner class Listener(private val cfg: Config, private val token: String) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== socket) return
            val r = nextRef()
            joinRef = r
            sentToken = token
            heartbeatRef = null
            webSocket.send(joinMessage(cfg.userId, token, r))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== socket) return
            when (val f = parse(text)) {
                is Frame.Reply -> when {
                    f.ref == null -> Unit
                    f.ref == joinRef -> if (f.ok) joined(webSocket) else webSocket.cancel()
                    f.ref == heartbeatRef -> heartbeatRef = null
                }
                is Frame.Change -> f.event?.let(::deliver) ?: fetchPending()
                // 資料表訂閱完成：再補抓一次，避免剛好漏掉加入頻道到訂閱完成之間的通知
                is Frame.System -> if (f.ok) { _subscribed.value = true; fetchPending() }
                Frame.Closed -> webSocket.cancel()
                Frame.Ignore -> Unit
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = scheduleReconnect(webSocket)
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = scheduleReconnect(webSocket)
    }

    private fun joined(ws: WebSocket) {
        _connected.value = true
        backoffMs = 2_000L
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && ws === socket) {
                delay(HEARTBEAT_MS)
                if (ws !== socket) break
                // 上一次心跳沒有回應：連線其實已經斷了
                if (heartbeatRef != null) { ws.cancel(); break }
                val r = nextRef()
                heartbeatRef = r
                ws.send(heartbeatMessage(r))
                // token 快過期就先換新，並告訴 Realtime
                runCatching { config?.token?.invoke() }.getOrNull()?.let(::updateToken)
            }
        }
        fetchPending()
    }

    private fun scheduleReconnect(ws: WebSocket?) {
        if (ws != null && ws !== socket) return
        socket = null
        _connected.value = false
        _subscribed.value = false
        heartbeatJob?.cancel()
        if (!running) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
            if (running) connect()
        }
    }

    // ---------------------------------------------------------------- 通知：補抓、去重、確認送達
    private fun fetchPending() {
        val api = config?.api ?: return
        fetchAgain = true
        if (fetchJob?.isActive == true) return
        fetchJob = scope.launch {
            while (fetchAgain) {
                fetchAgain = false
                val list = runCatching { api.pendingNotifications() }.getOrNull() ?: break
                list.sortedBy { it.id }.forEach { n -> deliver(Event.Notification(n.id, n.kind, n.title, n.body.orEmpty(), n.data)) }
            }
        }
    }

    private fun deliver(e: Event) {
        if (e is Event.Notification) {
            queueAck(e.id)
            synchronized(seen) {
                if (e.id in seen) return
                seen.addLast(e.id)
                if (seen.size > 200) seen.removeFirst()
            }
        }
        _events.tryEmit(e)
    }

    private fun queueAck(id: Long) {
        val api = config?.api ?: return
        synchronized(toAck) { toAck += id }
        if (ackJob?.isActive == true) return
        ackJob = scope.launch {
            delay(500) // 一次確認一批
            while (true) {
                val ids = synchronized(toAck) { toAck.toList().also { toAck.clear() } }
                if (ids.isEmpty()) break
                if (runCatching { api.ackNotifications(ids) }.isFailure) break // 沒確認到的，下次連上補抓時會再確認
            }
        }
    }

    companion object {
        const val HEARTBEAT_MS = 25_000L

        fun topic(userId: String) = "realtime:xlk-$userId"

        fun socketUrl(baseUrl: String, key: String): String {
            val http = Supabase.normalizeUrl(baseUrl)
            val ws = if (http.startsWith("https://")) "wss://" + http.removePrefix("https://") else "ws://" + http.removePrefix("http://")
            return "$ws/realtime/v1/websocket?apikey=${URLEncoder.encode(key, "UTF-8")}&vsn=1.0.0"
        }

        fun joinMessage(userId: String, token: String, ref: String): String = buildJsonObject {
            put("topic", topic(userId))
            put("event", "phx_join")
            putJsonObject("payload") {
                putJsonObject("config") {
                    putJsonObject("broadcast") { put("self", false) }
                    putJsonObject("presence") { put("key", "") }
                    put("private", false)
                    putJsonArray("postgres_changes") {
                        addJsonObject {
                            put("event", "INSERT"); put("schema", "public"); put("table", "notification_outbox"); put("filter", "recipient_id=eq.$userId")
                        }
                        addJsonObject {
                            put("event", "INSERT"); put("schema", "public"); put("table", "realtime_events"); put("filter", "user_id=eq.$userId")
                        }
                    }
                }
                put("access_token", token)
            }
            put("ref", ref)
            put("join_ref", ref)
        }.toString()

        fun heartbeatMessage(ref: String): String = buildJsonObject {
            put("topic", "phoenix"); put("event", "heartbeat"); putJsonObject("payload") {}; put("ref", ref)
        }.toString()

        fun accessTokenMessage(userId: String, token: String, ref: String): String = buildJsonObject {
            put("topic", topic(userId)); put("event", "access_token")
            putJsonObject("payload") { put("access_token", token) }
            put("ref", ref)
        }.toString()

        private fun JsonObject.str(k: String) = (get(k) as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.obj(k: String) = get(k) as? JsonObject
        private fun JsonObject.long(k: String) = (get(k) as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }

        /** jsonb 欄位通常是物件，保險起見也接受字串形式的 JSON */
        private fun asObject(e: JsonElement?): JsonObject? = when (e) {
            is JsonObject -> e
            is JsonPrimitive -> if (e.isString) runCatching { Api.json.parseToJsonElement(e.content).jsonObject }.getOrNull() else null
            else -> null
        }

        fun parse(text: String): Frame {
            val obj = runCatching { Api.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return Frame.Ignore
            val payload = obj.obj("payload")
            return when (obj.str("event")) {
                "phx_reply" -> Frame.Reply(obj.str("ref"), payload?.str("status") == "ok")
                "postgres_changes" -> parseChange(payload?.obj("data"))
                "system" -> Frame.System(payload?.str("status") == "ok", payload?.str("message"))
                "phx_close", "phx_error" -> Frame.Closed
                else -> Frame.Ignore
            }
        }

        private fun parseChange(data: JsonObject?): Frame {
            val record = data?.obj("record") ?: return Frame.Ignore
            return when (data.str("table")) {
                "notification_outbox" -> {
                    val id = record.long("id")
                    val title = record.str("title")
                    if (id == null || title == null) Frame.Change(null)
                    else Frame.Change(Event.Notification(id, record.str("kind").orEmpty(), title, record.str("body").orEmpty(), asObject(record["data"])))
                }
                "realtime_events" -> when (record.str("kind")) {
                    "changed" -> Frame.Change(Event.Changed(record.str("table_name").orEmpty(), record.str("by_user")))
                    "locate" -> Frame.Change(Event.Locate)
                    else -> Frame.Ignore
                }
                else -> Frame.Ignore
            }
        }
    }
}
