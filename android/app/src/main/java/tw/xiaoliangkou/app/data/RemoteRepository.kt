package tw.xiaoliangkou.app.data

import kotlinx.coroutines.CancellationException
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import tw.xiaoliangkou.app.core.Catalog
import tw.xiaoliangkou.app.core.PaymentParser
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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

    /** 最近一次換月載入的日曆區間：有變動時跟預設區間（跟著今天走）一起重新載入 */
    @Volatile private var browsing: Pair<LocalDate, LocalDate>? = null

    /** 正在跑 [refreshAll]（裡面會檢查資料庫版本，不用另外再檢查） */
    private val refreshing = AtomicInteger(0)
    private val rechecking = AtomicBoolean(false)

    init {
        jobs += scope.launch {
            realtime.events.collect { e ->
                when (e) {
                    is RealtimeClient.Event.Notification -> {
                        onNotification(e)
                        _banners.emit(Banner(e.title, e.body.ifBlank { null }, e.kind, routeFor(e.kind, e.data)))
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
        "profiles", "sharing_settings" -> listOf(::loadMe, ::loadPartnerStatus, ::loadPlaces, ::loadHistory)
        // 配對變了（對方解除配對、接受了我的邀請）：對方的備忘錄、日曆的生活紀錄、寫給對方的備註都要跟著換，全部重新載入。
        // 不然解除配對後，桌面小工具還會一直顯示前任放在桌面的備忘錄
        "couples", "couple_members" -> listOf(::refreshAll)
        "device_status", "locations" -> listOf(::loadMe, ::loadPartnerStatus) // 「對方在線上」也跟著更新
        "places", "place_events" -> listOf(::loadPlaces)
        "expenses", "expense_guesses" -> listOf(::loadExpenses)
        "moods" -> listOf(::loadMoods)
        "comforts" -> listOf(::loadComforts)
        "anniversaries" -> listOf(::loadAnniversaries)
        "events" -> listOf(::loadEvents)
        "cycle_settings", "cycle_periods" -> listOf(::loadCycle)
        "activity_log" -> listOf(::loadHistory)
        "day_entries" -> listOf(::loadCurrentDays)
        "memos" -> listOf(::loadMemos)
        else -> emptyList()
    }

    // ---------------------------------------------------------------- 讀取
    private suspend fun loadMe() {
        val me = apiCall { api.me() }
        _state.update {
            it.copy(
                loaded = true, me = me.user, couple = me.couple, partner = me.partner,
                mySharing = me.mySharing, partnerSharing = me.partnerSharing, partnerOnline = me.partnerOnline,
                // 換了配對：以前配對的生活紀錄不能再顯示（重新載入時只會換掉載入的那幾個月）
                dayEntries = if (it.couple?.id == me.couple?.id) it.dayEntries else emptyList(),
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
    private suspend fun loadNotes() { val n = v2 { api.notes() }; _state.update { it.copy(notes = n) } }
    private suspend fun loadMemos() {
        val m = try {
            v2 { api.memos() }
        } catch (e: AppException) {
            // 舊的資料庫沒有備忘錄：確定是空的，小工具不用再等
            if (e.code == Errors.SCHEMA_OUTDATED_CODE) _state.update { it.copy(memos = emptyList(), memosLoaded = true) }
            throw e
        }
        _state.update { it.copy(memos = m, memosLoaded = true) }
    }
    private suspend fun loadCurrentDays() { dayWindows(defaultDayRange(), browsing).forEach { (from, to) -> loadDays(from, to) } }

    /** 載入 [from]～[to] 的生活紀錄，取代 state 裡這段期間的舊資料（沒配對就沒有紀錄） */
    private suspend fun loadDays(from: LocalDate, to: LocalDate) {
        if (_state.value.couple == null) { _state.update { it.copy(dayEntries = emptyList()) }; return }
        val rows = v2 { api.dayEntries(from.toString(), to.toString()) }
        val a = from.toString()
        val b = to.toString()
        val ids = rows.mapTo(HashSet()) { it.id }
        _state.update { st ->
            val kept = st.dayEntries.filterNot { it.day in a..b || it.id in ids }
            st.copy(dayEntries = (kept + rows).sortedWith(DayEntry.ORDER))
        }
    }

    /** 資料庫有沒有跑過 upgrade-v2.sql：app_health 的 schema 要 >= 2（3 才有手機名稱與電信，4 才有 GIPHY 貼圖） */
    private suspend fun checkSchema() {
        val h = apiCall { api.health() }
        val schema = (h["schema"] as? JsonPrimitive)?.intOrNull ?: 0
        _state.update { it.copy(schemaOutdated = schema < SCHEMA, dbSchema = schema) }
    }

    /**
     * 新功能的 RPC、Storage：資料庫還沒更新的話標記起來，畫面上提醒使用者。
     * 舊功能都能用、只有新函式找不到，就是還沒執行 upgrade-v2.sql。
     */
    private suspend fun <T> v2(block: suspend () -> T): T {
        val r = try {
            apiCall { block() }
        } catch (e: AppException) {
            if (e.code == Errors.SCHEMA_OUTDATED_CODE || e.code == "not_setup") {
                _state.update { it.copy(schemaOutdated = true) }
                throw AppException(Errors.SCHEMA_OUTDATED, Errors.SCHEMA_OUTDATED_CODE, e.status)
            }
            throw e
        }
        // 之前說資料庫要更新，這次新功能卻成功了：大概是剛跑完 upgrade-v2.sql（例如另一半那支手機），再確認一次
        recheckSchema()
        return r
    }

    /**
     * 標記成「資料庫需要更新」之後，再問一次 app_health（回到 App、新功能的呼叫成功時）。
     * 使用者跑完 upgrade-v2.sql 回到 App：提醒拿掉，新功能的資料全部重新載入。
     * 背景服務讓 App 一直活著、即時連線也沒斷，不這樣做的話要等 App 整個重開才會發現。
     * 還是舊的資料庫的話只多一次 app_health，同時只會有一個在檢查
     */
    fun recheckSchema() {
        val st = _state.value
        val behind = st.schemaOutdated || st.deviceInfoOutdated || st.stickersOutdated || st.moodsOutdated
        if (!behind || refreshing.get() > 0 || !rechecking.compareAndSet(false, true)) return
        scope.launch {
            try {
                checkSchema()
                // 剛跑完 upgrade-v2.sql：新功能的資料重新載入（只差手機名稱、電信、貼圖、新的心情的話不用，對方下次回報就有）
                if (st.schemaOutdated && !_state.value.schemaOutdated) refreshAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 沒網路之類的：下次回到 App 再檢查
            } finally {
                rechecking.set(false)
            }
        }
    }

    override suspend fun refreshAll() {
        refreshing.incrementAndGet()
        try {
            // 即時連線沒在運作的話，通知也順便用 RPC 補抓
            realtime.pollIfDisconnected()
            loadMe()
            coroutineScope {
                listOf(::loadPartnerStatus, ::loadPlaces, ::loadExpenses, ::loadMoods, ::loadComforts, ::loadAnniversaries,
                    ::loadEvents, ::loadCycle, ::loadHistory, ::loadPrefs, ::checkSchema, ::loadNotes, ::loadCurrentDays, ::loadMemos)
                    .map { async { runCatching { it() } } }.awaitAll()
            }
        } finally {
            refreshing.decrementAndGet()
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

    // 送出去了就是送出去了：重新整理失敗（網路不穩）不能說失敗，不然使用者再按一次，對方就收到兩次
    override suspend fun postMood(mood: String, note: String?, sticker: String?) {
        val s = checkSticker(sticker)
        val extra = checkMoreMoods(Catalog.mood(mood)?.extra == true)
        val row = moreMoods(extra) { apiCall { api.postMood(buildJsonObject { put("mood", mood); put("note", note ?: ""); if (s != null) put("sticker", s) }) } }
        reloadQuietly(::loadMoods)
        stickerSaved(s, row.sticker, MOOD_STICKER_NOT_SAVED)
    }
    override suspend fun sendComfort(kind: String, message: String?, sticker: String?) {
        val s = checkSticker(sticker)
        val extra = checkMoreMoods(Catalog.comfort(kind)?.extra == true)
        val row = moreMoods(extra) { apiCall { api.postComfort(buildJsonObject { put("kind", kind); put("message", message ?: ""); if (s != null) put("sticker", s) }) } }
        reloadQuietly(::loadComforts)
        stickerSaved(s, row.sticker, COMFORT_STICKER_NOT_SAVED)
    }

    /** 要附的貼圖：資料庫還存不了就先擋下來（舊的資料庫會默默略過不認得的欄位，貼圖就不見了） */
    private fun checkSticker(sticker: String?): String? {
        val s = sticker?.takeIf { it.isNotBlank() } ?: return null
        if (!Giphy.isValidId(s)) throw AppException(BAD_STICKER, "invalid_input")
        val st = _state.value
        if (st.schemaOutdated) throw AppException(Errors.SCHEMA_OUTDATED, Errors.SCHEMA_OUTDATED_CODE)
        if (st.stickersOutdated) throw AppException(STICKERS_OUTDATED, STICKERS_OUTDATED_CODE)
        return s
    }

    /** 送出去了，回傳的那筆卻沒有貼圖：資料庫其實還是舊的（還不知道版本、或剛換回舊的資料庫） */
    private fun stickerSaved(sent: String?, saved: String?, message: String) {
        if (sent == null || saved == sent) return
        stickersBehind()
        throw AppException(message, STICKERS_OUTDATED_CODE)
    }

    /** 記下資料庫還沒有貼圖（之後回到 App 會再檢查一次） */
    private fun stickersBehind() = _state.update { it.copy(dbSchema = (it.dbSchema ?: STICKER_SCHEMA).coerceAtMost(STICKER_SCHEMA - 1)) }

    /** 0.2.3 才多的心情、安撫（[extra]）：資料庫還存不了就先擋下來 */
    private fun checkMoreMoods(extra: Boolean): Boolean {
        val st = _state.value
        if (extra && st.schemaOutdated) throw AppException(Errors.SCHEMA_OUTDATED, Errors.SCHEMA_OUTDATED_CODE)
        if (extra && st.moodsOutdated) throw AppException(MOODS_OUTDATED, MOODS_OUTDATED_CODE)
        return extra
    }

    /** 還不知道資料庫的版本就送了新的種類，被舊的資料庫的 check 擋下來：記下資料庫還是舊的，說要更新 */
    private suspend fun <T> moreMoods(extra: Boolean, send: suspend () -> T): T = try {
        send()
    } catch (e: AppException) {
        if (!extra || e.code != "invalid_input") throw e
        _state.update { it.copy(dbSchema = (it.dbSchema ?: MOODS_SCHEMA).coerceAtMost(MOODS_SCHEMA - 1)) }
        throw AppException(MOODS_OUTDATED, MOODS_OUTDATED_CODE, e.status)
    }

    override suspend fun readComfort(id: Long) = markRead(listOf(id))

    override suspend fun markComfortsRead() {
        val st = _state.value
        val me = st.myId ?: return
        // schema 4 以後一次標完；舊的資料庫只能一則一則標
        markRead(st.comforts.filter { it.receiverId == me && it.readAt == null }.map { it.id }, all = (st.dbSchema ?: 0) >= STICKER_SCHEMA)
    }

    /**
     * 畫面先標成看過了（紅點馬上消失），再告訴資料庫：[all] 是一次標完收到的全部，不然一則一則送。
     * 沒送到的改回來，下次再標
     */
    private suspend fun markRead(ids: List<Long>, all: Boolean = false) {
        if (ids.isEmpty()) return
        val now = Instant.now().toString()
        _state.update { st -> st.copy(comforts = st.comforts.map { if (it.id in ids && it.readAt == null) it.copy(readAt = now) else it }) }
        val done = mutableSetOf<Long>()
        try {
            if (all) {
                apiCall { api.readAllComforts() }
                done += ids
            } else {
                ids.forEach { id -> apiCall { api.readComfort(id) }; done += id }
            }
        } catch (e: Exception) {
            _state.update { st -> st.copy(comforts = st.comforts.map { if (it.id !in done && it.id in ids && it.readAt == now) it.copy(readAt = null) else it }) }
            throw e
        }
        reloadQuietly(::loadComforts)
    }

    override suspend fun setGiphyKey(key: String?) {
        // 還沒跑過 upgrade-v2.sql（0.1 的資料庫）：整個資料庫都要更新，不是「再執行一次」
        if (_state.value.schemaOutdated) throw AppException(Errors.SCHEMA_OUTDATED, Errors.SCHEMA_OUTDATED_CODE)
        val k = key?.trim()?.takeIf { it.isNotEmpty() }
        if (k != null && !Giphy.isValidKey(k)) throw AppException(BAD_GIPHY_KEY, "invalid_input")
        try {
            // 沒有 key 就是清掉
            apiCall { api.setGiphyKey(buildJsonObject { if (k != null) put("key", k) }) }
        } catch (e: AppException) {
            // 還沒有 app_set_giphy_key：資料庫是 schema 3 以前的
            if (e.code == Errors.SCHEMA_OUTDATED_CODE) { stickersBehind(); throw AppException(STICKERS_OUTDATED, STICKERS_OUTDATED_CODE, e.status) }
            throw e
        }
        loadMe()
        recheckSchema()
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
        // 舊的資料庫不認得新的通知種類（生活紀錄、備忘錄）：存的時候直接略過、也不會回傳，開關只會默默彈回去
        if (kind !in _state.value.notifPrefs) {
            _state.update { it.copy(schemaOutdated = true) }
            throw AppException(Errors.SCHEMA_OUTDATED, Errors.SCHEMA_OUTDATED_CODE)
        }
        recheckSchema()
    }

    // ---------------------------------------------------------------- 個人資料、備註
    override suspend fun updateProfileInfo(displayName: String, birthday: String?, info: JsonObject) {
        val name = displayName.trim()
        Limits.profile(name, birthday, info)
        val prev = _state.value.me
        val row = apiCall {
            api.updateProfileRaw(buildJsonObject { put("displayName", name); put("birthday", birthday); put("info", info) })
        }
        loadMe()
        if ("info" in row) { recheckSchema(); return }
        // 舊的資料庫不認得 info：暱稱、生日存好了，個人資料沒存到。
        // 沒有填其他資料（只改暱稱、生日）就是全部存好了，不要跳「資料庫需要更新」
        _state.update { it.copy(schemaOutdated = true) }
        if (info.isEmpty()) return
        val savedBasics = prev != null && (prev.displayName != name || prev.birthday != birthday)
        throw AppException(if (savedBasics) PROFILE_INFO_OUTDATED else Errors.SCHEMA_OUTDATED, Errors.SCHEMA_OUTDATED_CODE)
    }

    // 存好之後先把回傳的那筆放進畫面，再重新整理一次：重新整理失敗（網路不穩）也不會說沒存到，害使用者再按一次、存成兩筆
    override suspend fun saveNote(id: String?, body: String) {
        Limits.note(body)
        val n = v2 { api.saveNote(buildJsonObject { if (id != null) put("id", id); put("body", body) }) }
        // 跟 app_notes 一樣新寫的在最上面，改過的留在原本的位置
        _state.update { st ->
            st.copy(notes = if (st.notes.any { it.id == n.id }) st.notes.map { if (it.id == n.id) n else it } else listOf(n) + st.notes)
        }
        reloadQuietly(::loadNotes)
    }
    override suspend fun deleteNote(id: String) {
        v2 { api.deleteNote(id) }
        _state.update { st -> st.copy(notes = st.notes.filterNot { it.id == id }) }
        reloadQuietly(::loadNotes)
    }

    /** 已經存好了，重新整理只是順便：失敗的話等即時連線的變動或下次重新整理 */
    private suspend fun reloadQuietly(load: suspend () -> Unit) {
        try { load() } catch (e: AppException) { }
    }

    // ---------------------------------------------------------------- 日曆的生活紀錄
    override suspend fun loadDayEntries(from: LocalDate, to: LocalDate) {
        if (to.isBefore(from)) return
        browsing = from to to
        loadDays(from, to)
    }

    override suspend fun saveDayEntry(id: String?, day: LocalDate, body: String, media: List<MediaItem>) {
        Limits.dayEntry(body, media)
        val old = id?.let { x -> _state.value.dayEntries.firstOrNull { it.id == x } }
        // 日曆是兩個人共用的：照片一定要放在 c/<配對>/
        val moved = relocate(media, shared = true)
        val row = v2 {
            api.saveDayEntry(buildJsonObject {
                if (id != null) put("id", id)
                put("day", day.toString()); put("body", body); put("media", Api.mediaJson(moved.media))
            })
        }
        _state.update { st ->
            st.copy(dayEntries = (st.dayEntries.filterNot { it.id == row.id } + row).sortedWith(DayEntry.ORDER))
        }
        // 這次拿掉的照片、語音：沒有其他地方用到就刪掉
        if (old != null) deleteUnused(old.media.map { it.path }, exceptMemo = null)
    }

    override suspend fun deleteDayEntry(id: String) {
        val old = _state.value.dayEntries.firstOrNull { it.id == id }
        v2 { api.deleteDayEntry(id) }
        _state.update { st -> st.copy(dayEntries = st.dayEntries.filterNot { it.id == id }) }
        if (old != null) deleteUnused(old.media.map { it.path }, exceptMemo = null)
    }

    // ---------------------------------------------------------------- 備忘錄
    override suspend fun saveMemo(id: String?, title: String, body: String, media: List<MediaItem>, shared: Boolean, pinned: Boolean, color: String) {
        Limits.memo(title, body, media, color)
        val st = _state.value
        val old = st.memo(id)
        // 對方共用的備忘錄：不能改共用設定，照片也一直放在共用的地方
        val theirs = old != null && old.userId != st.myId
        val nowShared = old?.takeIf { theirs }?.shared ?: (shared && st.couple != null)
        val moved = relocate(media, nowShared)
        val row = v2 {
            api.saveMemo(buildJsonObject {
                if (id != null) put("id", id)
                put("title", title); put("body", body); put("media", Api.mediaJson(moved.media))
                if (!theirs) put("shared", nowShared)
                put("pinned", pinned); put("color", color)
            })
        }
        _state.update { s -> s.copy(memos = (s.memos.filterNot { it.id == row.id } + row).sortedWith(Memo.ORDER)) }
        // 這次拿掉的照片、語音，和搬走的原始檔（改成只有自己時在共用資料夾、改成共用時在自己的資料夾）：
        // 沒有其他地方用到就刪掉。共用資料夾裡的不刪，對方還是列得出來、讀得到
        val keep = moved.media.mapTo(HashSet()) { it.path }
        deleteUnused((old?.media.orEmpty().map { it.path } + moved.from).filter { it !in keep }.distinct(), exceptMemo = row.id)
        reloadQuietly(::loadMemos)
    }

    /** 搬好的 media，和搬走的原始檔 */
    private class Relocated(val media: List<MediaItem>, val from: List<String>)

    /**
     * 改成共用或私人時，照片、語音要搬到對的地方（c/<配對>/ 或 u/<我>/）：下載再重新上傳。
     * 以前的配對留下來的檔案已經讀不到了，直接拿掉（資料庫存的時候也會拿掉）。
     */
    private suspend fun relocate(media: List<MediaItem>, shared: Boolean): Relocated {
        val prefix = mediaPrefix(shared)
        val couple = "c/${_state.value.couple?.id}/"
        val from = mutableListOf<String>()
        val out = media.mapNotNull { m ->
            when {
                m.path.startsWith(prefix) -> m
                m.path.startsWith("c/") && !m.path.startsWith(couple) -> null
                else -> {
                    val mime = m.mime ?: MediaPaths.mimeOf(m.path)
                    val bytes = try {
                        v2 { api.downloadMedia(m.path) }
                    } catch (e: AppException) {
                        if (e.code == "not_found" || e.code == "forbidden") return@mapNotNull null
                        throw e
                    }
                    val path = MediaPaths.under(prefix, mime)
                    v2 { api.uploadMedia(path, mime, bytes) }
                    from += m.path
                    m.copy(path = path, mime = mime)
                }
            }
        }
        return Relocated(out, from)
    }

    /**
     * 刪掉共用資料夾（和自己資料夾）裡已經沒有用到的檔案（盡量刪，失敗就算了；只有上傳的人刪得掉）。
     * 以前的配對留下來的檔案已經讀不到，也刪不掉，不用送
     */
    private suspend fun deleteUnused(paths: List<String>, exceptMemo: String?) {
        val st = _state.value
        val used = (st.memos.filter { it.id != exceptMemo }.flatMap { it.media } + st.dayEntries.flatMap { it.media }).mapTo(HashSet()) { it.path }
        val mine = st.myId?.let { "u/$it/" }
        val couple = st.couple?.id?.let { "c/$it/" }
        val unused = paths.filter { (couple != null && it.startsWith(couple) || mine != null && it.startsWith(mine)) && it !in used }
        if (unused.isEmpty()) return
        try { apiCall { api.deleteMedia(unused) } } catch (e: AppException) { }
    }

    private fun mediaPrefix(shared: Boolean): String {
        val st = _state.value
        return if (shared) "c/${st.couple?.id ?: throw AppException(Errors.DB_MESSAGES.getValue("not_paired"), "not_paired")}/"
        else "u/${st.myId ?: throw AppException(Errors.RELOGIN, "not_authenticated")}/"
    }

    override suspend fun deleteMemo(id: String) {
        val old = _state.value.memo(id)
        v2 { api.deleteMemo(id) }
        _state.update { s -> s.copy(memos = s.memos.filterNot { it.id == id }) }
        // 它的照片、語音也刪掉（日曆或別的備忘錄還在用的留著）
        if (old != null) deleteUnused(old.media.map { it.path }, exceptMemo = id)
        reloadQuietly(::loadMemos)
    }

    // ---------------------------------------------------------------- 照片、語音
    override suspend fun uploadMedia(bytes: ByteArray, mime: String, kind: String, shared: Boolean, width: Int?, height: Int?, durationMs: Long?): MediaItem {
        Limits.file(bytes)
        val path = MediaPaths.under(mediaPrefix(shared), mime)
        v2 { api.uploadMedia(path, mime, bytes) }
        return MediaItem(path, kind, mime, width, height, durationMs)
    }

    override suspend fun downloadMedia(path: String): ByteArray = v2 { api.downloadMedia(path) }

    override suspend fun discardMedia(items: List<MediaItem>) = deleteUnused(items.map { it.path }, exceptMemo = null)

    // ---------------------------------------------------------------- 背景服務
    override suspend fun reportDevice(body: JsonObject) { apiCall { api.putDevice(body) } }
    override suspend fun reportLocation(lat: Double, lng: Double, accuracy: Float?, speed: Float?, recordedAt: java.time.Instant?) {
        apiCall {
            api.putLocation(buildJsonObject {
                put("lat", lat); put("lng", lng)
                if (recordedAt != null) put("recordedAt", recordedAt.toString())
                if (accuracy != null) put("accuracyM", accuracy)
                if (speed != null) put("speedMps", speed)
            })
        }
        _state.update { it.copy(myLocation = LatLng(lat, lng)) }
    }
    override suspend fun reportPlaceEvent(placeId: String, arrive: Boolean) {
        apiCall { api.placeEvent(placeId, buildJsonObject { put("kind", if (arrive) "arrive" else "leave") }) }
    }
    override suspend fun addAutoExpense(parsed: PaymentParser.Parsed, dedupeKey: String): String? {
        val row = apiCall {
            api.createExpense(buildJsonObject {
                put("amount", parsed.amount); put("merchant", parsed.merchant.take(60)); put("category", parsed.category)
                put("source", "notification"); put("dedupeKey", dedupeKey)
            })
        }
        loadExpenses()
        return (row["id"] as? JsonPrimitive)?.contentOrNull
    }

    override fun setMyDevice(d: DeviceStatus) = _state.update { it.copy(myDevice = d) }

    override fun setMyLocation(lat: Double, lng: Double) = _state.update { it.copy(myLocation = LatLng(lat, lng)) }

    companion object {
        /** 這版 App 需要的資料庫版本（app_health 的 schema） */
        const val SCHEMA = 2
        /** 有手機名稱與電信的資料庫版本：沒有也沒關係，只是對方看不到 */
        const val DEVICE_SCHEMA = 3
        /** 有 GIPHY 貼圖的資料庫版本：沒有的話心情、安撫照樣能送，只是不能附貼圖 */
        const val STICKER_SCHEMA = 4
        /** 有 0.2.3 多的心情和安撫的資料庫版本：沒有的話只能選原本的 */
        const val MOODS_SCHEMA = 5

        const val STICKERS_OUTDATED_CODE = "stickers_outdated"
        /** 舊的資料庫（schema 3 以前）存不了貼圖 */
        const val STICKERS_OUTDATED = "資料庫還不能存 GIPHY 貼圖：請到 Supabase 的 SQL Editor 再執行一次 upgrade-v2.sql（說明在「我的 › Supabase 與帳號」）"
        const val MOOD_STICKER_NOT_SAVED = "心情更新了，但資料庫還沒更新，貼圖沒有存到：請再執行一次 upgrade-v2.sql"
        const val COMFORT_STICKER_NOT_SAVED = "安撫送出去了，但資料庫還沒更新，貼圖沒有存到：請再執行一次 upgrade-v2.sql"
        const val BAD_STICKER = "這張貼圖不能用，換一張試試"
        const val BAD_GIPHY_KEY = "這看起來不是 GIPHY 的 API key：請到 GIPHY Developers 複製整串 key 🔑"
        const val MOODS_OUTDATED_CODE = "moods_outdated"
        /** 舊的資料庫（schema 4 以前）存不了新的心情和安撫 */
        const val MOODS_OUTDATED = "資料庫還沒有新的心情和安撫：請到 Supabase 的 SQL Editor 再執行一次 upgrade-v2.sql（說明在「我的 › Supabase 與帳號」）"

        /** 舊的資料庫：暱稱、生日存好了，身高、星座這些還存不進去 */
        const val PROFILE_INFO_OUTDATED = "暱稱和生日存好了；其他資料要等資料庫更新才存得進去（說明在「我的 › Supabase 與帳號」）"

        fun defaultDayRange(today: LocalDate = LocalDate.now()) = today.minusDays(62) to today.plusDays(31)

        /** 有變動時要重新載入的日曆區間：預設區間＋最近換到的區間，連在一起的話合成一段 */
        fun dayWindows(default: Pair<LocalDate, LocalDate>, browsing: Pair<LocalDate, LocalDate>?): List<Pair<LocalDate, LocalDate>> {
            val b = browsing ?: return listOf(default)
            val apart = b.first.isAfter(default.second.plusDays(1)) || default.first.isAfter(b.second.plusDays(1))
            return if (apart) listOf(default, b) else listOf(minOf(default.first, b.first) to maxOf(default.second, b.second))
        }

        /** 點通知、卡片要打開的畫面；生活紀錄直接打開那一天（calendar/2026-10-15） */
        fun routeFor(kind: String, data: JsonObject? = null) = when (kind) {
            "place" -> "map"
            "guess" -> "money"
            "diary" -> dateIn(data, "day")?.let { "calendar/$it" } ?: "calendar"
            "anniversary" -> dateIn(data, "date")?.let { "calendar/$it" } ?: "calendar"
            // 約你、答應、婉拒：打開那個約會的那天
            "event" -> textIn(data, "event_id")?.takeIf { UUID.matches(it) }?.let { "event/$it" } ?: "calendar"
            // 收到安撫：直接回應那一則
            "comfort" -> textIn(data, "comfort_id")?.toLongOrNull()?.let { "comfort/$it" } ?: "comfort"
            // 對方心情不好（「要不要安撫一下？」）：直接打開安撫
            "mood" -> if (Catalog.mood(textIn(data, "mood"))?.needsComfort == true) "comfort" else "home"
            "memo" -> "memos"
            "cycle_care" -> "cycle"
            "battery_low" -> "home"
            "sharing_changed", "pairing" -> "privacy"
            else -> "home"
        }

        private val UUID = Regex("^[0-9a-fA-F-]{36}$")
        private fun textIn(data: JsonObject?, key: String) = (data?.get(key) as? JsonPrimitive)?.contentOrNull
        private fun dateIn(data: JsonObject?, key: String) = textIn(data, key)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    }
}
