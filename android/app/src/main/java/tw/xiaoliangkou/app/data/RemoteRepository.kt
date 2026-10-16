package tw.xiaoliangkou.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import tw.xiaoliangkou.app.core.PaymentParser
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 連 Supabase 的資料來源。收到 Realtime 的「changed」時只重抓有變動的部分，
 * 收到通知時交給 [onNotification]（前景顯示卡片、背景發系統通知）。
 */
class RemoteRepository(
    private val api: Api,
    private val realtime: RealtimeClient,
    private val scope: CoroutineScope,
    private val onNotification: (RealtimeClient.Event.Notification) -> Unit,
) : CoupleRepository {
    private val _state = MutableStateFlow(AppState())
    override val state: StateFlow<AppState> = _state
    private val _banners = MutableSharedFlow<Banner>(extraBufferCapacity = 16)
    override val banners: SharedFlow<Banner> = _banners

    private val jobs = mutableListOf<Job>()
    private val pendingTables = mutableSetOf<String>()
    private var flushJob: Job? = null

    init {
        jobs += scope.launch {
            realtime.events.collect { e ->
                when (e) {
                    is RealtimeClient.Event.Notification -> {
                        onNotification(e)
                        _banners.emit(Banner(e.title, e.body.ifBlank { null }, e.kind, routeFor(e.kind)))
                    }
                    is RealtimeClient.Event.Changed -> queueRefresh(e.table)
                    RealtimeClient.Event.Locate -> Unit // 由背景服務處理
                }
            }
        }
        // 先用 RPC 載入畫面，不必等即時連線（Realtime 連不上時 App 也能用）
        jobs += scope.launch { runCatching { refreshAll() } }
        jobs += scope.launch {
            realtime.connected.collect { c ->
                _state.update { it.copy(connected = c) }
                if (c) runCatching { refreshAll() } // 重新連上時補抓斷線期間的變化
            }
        }
    }

    private fun queueRefresh(table: String) {
        synchronized(pendingTables) { pendingTables += table }
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(300)
            val tables = synchronized(pendingTables) { pendingTables.toSet().also { pendingTables.clear() } }
            val loaders = tables.flatMap { loadersFor(it) }.toSet()
            runCatching { coroutineScope { loaders.map { async { it() } }.awaitAll() } }
        }
    }

    private fun loadersFor(table: String): List<suspend () -> Unit> = when (table) {
        "profiles", "couples", "couple_members", "sharing_settings" -> listOf(::loadMe, ::loadPartnerStatus, ::loadPlaces, ::loadHistory)
        "device_status", "locations" -> listOf(::loadMe, ::loadPartnerStatus) // 「對方在線上」也跟著更新
        "places", "place_events" -> listOf(::loadPlaces)
        "expenses", "expense_guesses" -> listOf(::loadExpenses)
        "moods" -> listOf(::loadMoods)
        "comforts" -> listOf(::loadComforts)
        "anniversaries" -> listOf(::loadAnniversaries)
        "events" -> listOf(::loadEvents)
        "cycle_settings", "cycle_periods" -> listOf(::loadCycle)
        "activity_log" -> listOf(::loadHistory)
        else -> emptyList()
    }

    // ---------------------------------------------------------------- 讀取
    private suspend fun loadMe() {
        val me = apiCall { api.me() }
        _state.update {
            it.copy(
                loaded = true, me = me.user, couple = me.couple, partner = me.partner,
                mySharing = me.mySharing, partnerSharing = me.partnerSharing, partnerOnline = me.partnerOnline,
            )
        }
    }
    private suspend fun loadPartnerStatus() {
        if (_state.value.couple == null) { _state.update { it.copy(partnerStatus = PartnerStatus()) }; return }
        val s = apiCall { api.partnerStatus() }
        _state.update { it.copy(partnerStatus = s) }
    }
    private suspend fun loadPlaces() { val p = apiCall { api.places() }; _state.update { it.copy(places = p) } }
    private suspend fun loadExpenses() {
        val from = Instant.now().minus(62, ChronoUnit.DAYS).toString()
        val mine = apiCall { api.expenses(from = from) }
        val theirs = if (_state.value.couple != null) apiCall { api.partnerExpenses(from = from) } else emptyList()
        _state.update { it.copy(myExpenses = mine, partnerExpenses = theirs) }
    }
    private suspend fun loadMoods() { val m = apiCall { api.moods(14) }; _state.update { it.copy(moods = m) } }
    private suspend fun loadComforts() { val c = apiCall { api.comforts(50) }; _state.update { it.copy(comforts = c) } }
    private suspend fun loadAnniversaries() { val a = apiCall { api.anniversaries() }; _state.update { it.copy(anniversaries = a) } }
    private suspend fun loadEvents() { val e = apiCall { api.events() }; _state.update { it.copy(events = e) } }
    private suspend fun loadCycle() { val c = apiCall { api.cycle() }; _state.update { it.copy(cycle = c) } }
    private suspend fun loadHistory() {
        val h = apiCall { api.history(limit = 60) }
        _state.update { it.copy(history = h, historyEnd = h.size < 60) }
    }
    private suspend fun loadPrefs() { val p = apiCall { api.notificationPrefs() }; _state.update { it.copy(notifPrefs = p) } }

    override suspend fun refreshAll() {
        // 即時連線沒在運作的話，通知也順便用 RPC 補抓
        realtime.pollIfDisconnected()
        loadMe()
        coroutineScope {
            listOf(::loadPartnerStatus, ::loadPlaces, ::loadExpenses, ::loadMoods, ::loadComforts, ::loadAnniversaries,
                ::loadEvents, ::loadCycle, ::loadHistory, ::loadPrefs).map { async { runCatching { it() } } }.awaitAll()
        }
    }

    override fun close() { jobs.forEach { it.cancel() }; flushJob?.cancel() }

    // ---------------------------------------------------------------- 寫入（之後由 Realtime 的 changed 重新整理畫面，這裡也先抓一次讓畫面立刻更新）
    override suspend fun createInvite(): Invite = apiCall { api.invite() }
    override suspend fun acceptInvite(code: String, togetherSince: String?) {
        apiCall { api.accept(buildJsonObject { put("code", code); if (togetherSince != null) put("togetherSince", togetherSince) }) }
        refreshAll()
    }
    override suspend fun unpair() { apiCall { api.unpair() }; refreshAll() }
    override suspend fun updateProfile(displayName: String) { apiCall { api.updateProfile(buildJsonObject { put("displayName", displayName) }) }; loadMe() }
    override suspend fun updateAvatar(avatar: String?) { apiCall { api.updateProfile(buildJsonObject { put("avatarUrl", avatar) }) }; loadMe() }
    override suspend fun setTogetherSince(date: String) { apiCall { api.updateCouple(buildJsonObject { put("togetherSince", date) }) }; loadMe() }

    override suspend fun updateSharing(patch: JsonObject) { apiCall { api.updateSharing(patch) }; loadMe() }
    override suspend fun pauseSharing(minutes: Int) { apiCall { api.pauseSharing(buildJsonObject { put("minutes", minutes) }) }; loadMe() }
    override suspend fun locatePartner(): Boolean {
        val r = apiCall { api.locatePartner() }
        return r["sent"]?.toString() == "true"
    }

    override suspend fun savePlace(id: String?, body: JsonObject) {
        apiCall { if (id == null) api.createPlace(body) else api.updatePlace(id, body) }
        loadPlaces()
    }
    override suspend fun deletePlace(id: String) { apiCall { api.deletePlace(id) }; loadPlaces() }

    override suspend fun saveExpense(id: String?, body: JsonObject) {
        apiCall { if (id == null) api.createExpense(body) else api.updateExpense(id, body) }
        loadExpenses()
    }
    override suspend fun deleteExpense(id: String) { apiCall { api.deleteExpense(id) }; loadExpenses() }
    override suspend fun guess(id: String, amount: Double): GuessResult {
        val r = apiCall { api.guess(id, buildJsonObject { put("amount", amount) }) }
        loadExpenses()
        return r
    }

    override suspend fun postMood(mood: String, note: String?) {
        apiCall { api.postMood(buildJsonObject { put("mood", mood); put("note", note ?: "") }) }
        loadMoods()
    }
    override suspend fun sendComfort(kind: String, message: String?) {
        apiCall { api.postComfort(buildJsonObject { put("kind", kind); put("message", message ?: "") }) }
        loadComforts()
    }

    override suspend fun saveAnniversary(id: String?, body: JsonObject) {
        apiCall { if (id == null) api.createAnniversary(body) else api.updateAnniversary(id, body) }
        loadAnniversaries()
    }
    override suspend fun deleteAnniversary(id: String) { apiCall { api.deleteAnniversary(id) }; loadAnniversaries() }
    override suspend fun saveEvent(id: String?, body: JsonObject) {
        apiCall { if (id == null) api.createEvent(body) else api.updateEvent(id, body) }
        loadEvents()
    }
    override suspend fun answerEvent(id: String, status: String) {
        apiCall { api.answerEvent(id, buildJsonObject { put("status", status) }) }
        loadEvents()
    }
    override suspend fun deleteEvent(id: String) { apiCall { api.deleteEvent(id) }; loadEvents() }

    override suspend fun saveCycleSettings(body: JsonObject) { apiCall { api.saveCycleSettings(body) }; loadCycle() }
    override suspend fun stopCycle() { apiCall { api.stopCycle() }; loadCycle() }
    override suspend fun addPeriod(forPartner: Boolean, start: String, end: String?) {
        apiCall {
            api.addPeriod(buildJsonObject {
                put("owner", if (forPartner) "partner" else "me"); put("startDate", start); if (end != null) put("endDate", end)
            })
        }
        loadCycle()
    }
    override suspend fun updatePeriod(id: String, body: JsonObject) { apiCall { api.updatePeriod(id, body) }; loadCycle() }
    override suspend fun deletePeriod(id: String) { apiCall { api.deletePeriod(id) }; loadCycle() }
    override suspend fun saveCycleLog(date: String, flow: String?, symptoms: List<String>) {
        apiCall {
            api.saveCycleLog(date, buildJsonObject {
                if (flow != null) put("flow", flow)
                putJsonArray("symptoms") { symptoms.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            })
        }
        loadCycle()
    }

    override suspend fun loadMoreHistory() {
        val s = _state.value
        if (s.historyEnd) return
        val more = apiCall { api.history(before = s.history.lastOrNull()?.id, limit = 60) }
        _state.update { it.copy(history = it.history + more, historyEnd = more.size < 60) }
    }
    override suspend fun setNotifPref(kind: String, enabled: Boolean) {
        apiCall { api.setNotificationPrefs(buildJsonObject { put(kind, enabled) }) }
        loadPrefs()
    }

    // ---------------------------------------------------------------- 背景服務
    override suspend fun reportDevice(body: JsonObject) { apiCall { api.putDevice(body) } }
    override suspend fun reportLocation(lat: Double, lng: Double, accuracy: Float?, speed: Float?) {
        apiCall {
            api.putLocation(buildJsonObject {
                put("lat", lat); put("lng", lng)
                if (accuracy != null) put("accuracyM", accuracy)
                if (speed != null) put("speedMps", speed)
            })
        }
        _state.update { it.copy(myLocation = LatLng(lat, lng)) }
    }
    override suspend fun reportPlaceEvent(placeId: String, arrive: Boolean) {
        apiCall { api.placeEvent(placeId, buildJsonObject { put("kind", if (arrive) "arrive" else "leave") }) }
    }
    override suspend fun addAutoExpense(parsed: PaymentParser.Parsed, dedupeKey: String) {
        apiCall {
            api.createExpense(buildJsonObject {
                put("amount", parsed.amount); put("merchant", parsed.merchant.take(60)); put("category", parsed.category)
                put("source", "notification"); put("dedupeKey", dedupeKey)
            })
        }
    }

    fun setMyLocation(lat: Double, lng: Double) = _state.update { it.copy(myLocation = LatLng(lat, lng)) }

    companion object {
        fun routeFor(kind: String) = when (kind) {
            "place" -> "map"
            "guess" -> "money"
            "event", "anniversary" -> "calendar"
            "cycle_care" -> "cycle"
            "mood", "comfort", "battery_low" -> "home"
            "sharing_changed", "pairing" -> "privacy"
            else -> "home"
        }
    }
}
