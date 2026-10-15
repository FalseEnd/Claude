package tw.xiaoliangkou.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * 與自架伺服器的 WebSocket：收通知、收「資料變了」、收「對方想知道你在哪」。
 * 斷線會自動重連（2 秒起，最長 60 秒）。
 */
class RealtimeClient(private val scope: CoroutineScope) {
    sealed interface Event {
        data class Notification(val id: Long, val kind: String, val title: String, val body: String, val data: JsonObject?) : Event
        data class Changed(val table: String, val byUserId: String?) : Event
        data object Locate : Event
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 128)
    val events: SharedFlow<Event> = _events
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()
    private var socket: WebSocket? = null
    private var url: String? = null
    private var reconnectJob: Job? = null
    private var backoffMs = 2_000L
    private var running = false
    private val seen = ArrayDeque<Long>()

    fun start(baseUrl: String, token: String) {
        stop()
        val http = Api.normalizeBaseUrl(baseUrl)
        url = http.replaceFirst("http", "ws") + "ws?token=" + token
        running = true
        connect()
    }

    fun stop() {
        running = false
        reconnectJob?.cancel()
        socket?.close(1000, "bye")
        socket = null
        _connected.value = false
    }

    /** 網路恢復時呼叫，立刻重連而不是等退避時間 */
    fun reconnectNow() {
        if (!running || _connected.value) return
        reconnectJob?.cancel()
        backoffMs = 2_000L
        connect()
    }

    private fun connect() {
        val target = url ?: return
        socket = client.newWebSocket(Request.Builder().url(target).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connected.value = true
                backoffMs = 2_000L
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val obj = runCatching { Api.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                    "notification" -> {
                        val id = obj["id"]?.jsonPrimitive?.longOrNull ?: return
                        webSocket.send("""{"type":"ack","id":$id}""")
                        if (id in seen) return
                        seen.addLast(id)
                        if (seen.size > 200) seen.removeFirst()
                        _events.tryEmit(
                            Event.Notification(
                                id = id,
                                kind = obj["kind"]?.jsonPrimitive?.contentOrNull ?: "",
                                title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "小倆口",
                                body = obj["body"]?.jsonPrimitive?.contentOrNull ?: "",
                                data = runCatching { obj["data"]?.jsonObject }.getOrNull(),
                            )
                        )
                    }
                    "changed" -> _events.tryEmit(
                        Event.Changed(obj["table"]?.jsonPrimitive?.contentOrNull ?: "", obj["by"]?.jsonPrimitive?.contentOrNull)
                    )
                    "locate" -> _events.tryEmit(Event.Locate)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = scheduleReconnect(webSocket)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = scheduleReconnect(webSocket)
        })
    }

    private fun scheduleReconnect(ws: WebSocket) {
        if (ws !== socket) return
        _connected.value = false
        if (!running) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
            if (running) connect()
        }
    }
}
