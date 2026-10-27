package tw.xiaoliangkou.app.data

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import tw.xiaoliangkou.app.core.Cycle
import tw.xiaoliangkou.app.core.Geo
import tw.xiaoliangkou.app.core.PaymentParser
import java.time.LocalDate

data class LatLng(val lat: Double, val lng: Double)

/** 整個 App 畫面需要的資料。Supabase 模式與示範模式都產生同樣的結構。 */
data class AppState(
    val loaded: Boolean = false,
    val demo: Boolean = false,
    val connected: Boolean = false,
    val me: Profile? = null,
    val couple: Couple? = null,
    val partner: Profile? = null,
    val mySharing: Sharing? = null,
    val partnerSharing: Sharing? = null,
    val partnerOnline: Boolean = false,
    val partnerStatus: PartnerStatus = PartnerStatus(),
    val myLocation: LatLng? = null,
    val places: List<Place> = emptyList(),
    val myExpenses: List<Expense> = emptyList(),
    val partnerExpenses: List<Expense> = emptyList(),
    val moods: List<Mood> = emptyList(),
    val comforts: List<Comfort> = emptyList(),
    val anniversaries: List<Anniversary> = emptyList(),
    val events: List<DateEvent> = emptyList(),
    val cycle: CycleResponse = CycleResponse(),
    val history: List<Activity> = emptyList(),
    val historyEnd: Boolean = false,
    val notifPrefs: Map<String, Boolean> = emptyMap(),
    /** 只有自己看得到的備註 */
    val notes: List<Note> = emptyList(),
    /** 目前載入的區間（預設今天前 62 天～後 31 天，換月時用 [CoupleRepository.loadDayEntries] 補） */
    val dayEntries: List<DayEntry> = emptyList(),
    /** 自己的備忘錄＋對方共用的；釘選的在前 */
    val memos: List<Memo> = emptyList(),
    /** 備忘錄載入過了（[loaded] 只代表帳號資料到了）：備忘錄小工具等它再畫，不然會先畫成「沒有放在桌面的備忘錄」 */
    val memosLoaded: Boolean = false,
    /** 遠端資料庫還沒跑 upgrade-v2.sql */
    val schemaOutdated: Boolean = false,
    /** 資料庫的版本（app_health 的 schema），還不知道是 null */
    val dbSchema: Int? = null,
    /** 這支手機自己的狀態：直接從手機讀，不經過伺服器，沒分享也看得到 */
    val myDevice: DeviceStatus? = null,
) {
    /** 新功能都能用，只是資料庫還沒有手機名稱與電信（跑過舊的 upgrade-v2.sql）：再跑一次就好，不急 */
    val deviceInfoOutdated get() = !schemaOutdated && dbSchema != null && dbSchema < RemoteRepository.DEVICE_SCHEMA
    /** 資料庫還存不了 GIPHY 貼圖（schema 3 以前）：心情、安撫照樣能送，只是不能附貼圖。再跑一次 upgrade-v2.sql 就好 */
    val stickersOutdated get() = !schemaOutdated && dbSchema != null && dbSchema < RemoteRepository.STICKER_SCHEMA
    /**
     * 可以附 GIPHY 貼圖、設定 key（schema 4 以後）。還沒跑過 upgrade-v2.sql 的 0.1 資料庫（[schemaOutdated]）也不行；
     * 還不知道版本時先當作可以，送出去才發現沒存到會再說
     */
    val stickersReady get() = !schemaOutdated && (dbSchema ?: RemoteRepository.STICKER_SCHEMA) >= RemoteRepository.STICKER_SCHEMA
    /** 資料庫還沒有最新的心情和安撫（0.2.3 的 schema 5、0.2.6 的 schema 6）：原本的選項照樣能用，再跑一次 upgrade-v2.sql 就好 */
    val moodsOutdated get() = !schemaOutdated && dbSchema != null && dbSchema < RemoteRepository.MOODS_SCHEMA
    /** 全部的心情和安撫都能選；還不知道版本時先當作可以，送出去被擋下來會再說 */
    val moreMoodsReady get() = canPick(RemoteRepository.MOODS_SCHEMA)
    /** 資料庫存得進去 schema [since] 才多的心情、安撫（0＝一開始就有的，一定可以） */
    fun canPick(since: Int) = since == 0 || (!schemaOutdated && (dbSchema ?: RemoteRepository.MOODS_SCHEMA) >= since)
    /** 兩個人共用的 GIPHY API key，還沒設定是 null */
    val giphyKey get() = couple?.giphyKey?.takeIf { it.isNotBlank() }

    val paired get() = couple != null && partner != null
    val myId get() = me?.id
    val partnerName get() = partner?.displayName ?: "對方"
    val togetherSince: LocalDate? get() = couple?.togetherSince?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** 那天兩個人的生活紀錄（先寫的在前） */
    fun dayEntriesOn(date: LocalDate): List<DayEntry> = date.toString().let { d -> dayEntries.filter { it.day == d } }
    /** 放在桌面小工具的備忘錄 */
    val pinnedMemos: List<Memo> get() = memos.filter { it.pinned }
    fun memo(id: String?): Memo? = id?.let { i -> memos.firstOrNull { it.id == i } }

    /** 對方目前在哪些標點裡（只看偵測對方或兩人的標點） */
    val partnerInside: List<Place>
        get() {
            val loc = partnerStatus.location ?: return emptyList()
            val fences = places.filter { it.watchUser == null || it.watchUser == partner?.id }
            return fences.filter { Geo.distance(it.lat, it.lng, loc.lat, loc.lng) <= it.radiusM }
        }

    /** 要顯示誰的週期：自己有記錄就顯示自己的，否則顯示對方分享的 */
    val cycleOwnerIsMe get() = cycle.mine.settings != null || (cycle.mine.periods.isNotEmpty() && cycle.partner.periods.isEmpty())
    val cycleBundle get() = if (cycleOwnerIsMe) cycle.mine else cycle.partner
    val cycleVisible get() = cycleOwnerIsMe || cycle.partner.periods.isNotEmpty() || cycle.partner.settings != null
    val cyclePeriods: List<Cycle.Period>
        get() = cycleBundle.periods.mapNotNull { p ->
            runCatching { Cycle.Period(LocalDate.parse(p.startDate), p.endDate?.let(LocalDate::parse)) }.getOrNull()
        }
    val cycleSettings: Cycle.Settings
        get() = cycleBundle.settings.let { Cycle.Settings(it?.cycleLength, it?.periodLength, it?.lutealLength) }
}

/** 備註、日曆紀錄、備忘錄的限制（跟資料庫的 check 一樣）：先在手機上擋下，錯誤訊息比較好懂 */
object Limits {
    const val NOTE = 2000
    const val DAY_BODY = 5000
    const val MEMO_TITLE = 60
    const val MEMO_BODY = 5000
    /** 每則紀錄、備忘錄最多幾個照片或語音 */
    const val MEDIA = 12
    /** Storage 的 media bucket 單檔上限 */
    const val FILE_BYTES = 10 * 1024 * 1024

    const val NAME = 30
    /** profiles.info 整包 JSON 最多幾個 byte */
    const val INFO_BYTES = 4000

    private fun fail(text: String): Nothing = throw AppException(text, "invalid_input")

    fun profile(displayName: String, birthday: String?, info: JsonObject) {
        if (displayName.isBlank()) fail("暱稱不能是空白的")
        if (displayName.length > NAME) fail("暱稱最多 $NAME 字")
        if (birthday != null) {
            val d = runCatching { LocalDate.parse(birthday) }.getOrNull() ?: fail("生日的日期格式不正確")
            if (d.year !in 1900..2200) fail("生日的日期格式不正確")
        }
        if (info.toString().toByteArray().size > INFO_BYTES) fail("個人資料寫太多了，精簡一點吧")
    }

    fun note(body: String) {
        if (body.isBlank()) fail("寫點什麼吧 ✍️")
        if (body.length > NOTE) fail("備註最多 $NOTE 字")
    }

    fun dayEntry(body: String, media: List<MediaItem>) {
        if (body.isBlank() && media.isEmpty()) fail("寫點什麼或加一張照片吧 ✍️")
        if (body.length > DAY_BODY) fail("最多 $DAY_BODY 字")
        if (media.size > MEDIA) fail("照片和語音最多 $MEDIA 個")
    }

    fun memo(title: String, body: String, media: List<MediaItem>, color: String) {
        if (title.isBlank() && body.isBlank() && media.isEmpty()) fail("寫點什麼吧 ✍️")
        if (title.length > MEMO_TITLE) fail("標題最多 $MEMO_TITLE 字")
        if (body.length > MEMO_BODY) fail("內容最多 $MEMO_BODY 字")
        if (media.size > MEDIA) fail("照片和語音最多 $MEDIA 個")
        if (color !in Memo.COLORS) fail("顏色不正確")
    }

    fun file(bytes: ByteArray) {
        if (bytes.isEmpty()) throw AppException("檔案是空的，再試一次", "invalid_media")
        if (bytes.size > FILE_BYTES) throw AppException("檔案太大了（最多 10 MB）", "too_large")
    }
}

/** App 在前景時以卡片顯示、在背景時以系統通知顯示 */
data class Banner(val title: String, val body: String?, val kind: String, val route: String? = null)

interface CoupleRepository {
    val state: StateFlow<AppState>
    val banners: SharedFlow<Banner>

    suspend fun refreshAll()
    fun close()

    // 帳號與配對
    suspend fun createInvite(): Invite
    suspend fun acceptInvite(code: String, togetherSince: String?)
    suspend fun unpair()
    suspend fun updateProfile(displayName: String)
    /** 頭像：照片或表情（見 [Avatars]），null 是改回用暱稱 */
    suspend fun updateAvatar(avatar: String?)
    suspend fun setTogetherSince(date: String)

    // 分享
    suspend fun updateSharing(patch: JsonObject)
    suspend fun pauseSharing(minutes: Int)
    suspend fun locatePartner(): Boolean

    // 地點
    suspend fun savePlace(id: String?, body: JsonObject)
    suspend fun deletePlace(id: String)

    // 帳本
    suspend fun saveExpense(id: String?, body: JsonObject)
    suspend fun deleteExpense(id: String)
    suspend fun guess(id: String, amount: Double): GuessResult

    // 心情與安撫（[sticker] 是選填的 GIPHY 貼圖 id）
    suspend fun postMood(mood: String, note: String?, sticker: String? = null)
    suspend fun sendComfort(kind: String, message: String?, sticker: String? = null)
    /** 收到的安撫看過了 */
    suspend fun readComfort(id: Long)
    /** 收到的安撫全部標成看過了 */
    suspend fun markComfortsRead()
    /** 兩個人共用的 GIPHY API key；null 或空白是清掉 */
    suspend fun setGiphyKey(key: String?)

    // 紀念日與約會
    suspend fun saveAnniversary(id: String?, body: JsonObject)
    suspend fun deleteAnniversary(id: String)
    suspend fun saveEvent(id: String?, body: JsonObject)
    suspend fun answerEvent(id: String, status: String)
    suspend fun deleteEvent(id: String)

    // 經期
    suspend fun saveCycleSettings(body: JsonObject)
    suspend fun stopCycle()
    suspend fun addPeriod(forPartner: Boolean, start: String, end: String?)
    suspend fun updatePeriod(id: String, body: JsonObject)
    suspend fun deletePeriod(id: String)
    suspend fun saveCycleLog(date: String, flow: String?, symptoms: List<String>)

    // 歷史與通知設定
    suspend fun loadMoreHistory()
    suspend fun setNotifPref(kind: String, enabled: Boolean)

    // 個人資料與只有自己看得到的備註
    /** 暱稱、生日（null 是清掉）、個人資料（見 [ProfileInfo.build]） */
    suspend fun updateProfileInfo(displayName: String, birthday: String?, info: JsonObject)
    suspend fun saveNote(id: String?, body: String)
    suspend fun deleteNote(id: String)

    // 日曆：記錄那天的生活
    /** 換月時呼叫，合併進 state.dayEntries */
    suspend fun loadDayEntries(from: LocalDate, to: LocalDate)
    suspend fun saveDayEntry(id: String?, day: LocalDate, body: String, media: List<MediaItem>)
    suspend fun deleteDayEntry(id: String)

    // 備忘錄
    suspend fun saveMemo(id: String?, title: String, body: String, media: List<MediaItem>, shared: Boolean, pinned: Boolean, color: String)
    suspend fun deleteMemo(id: String)

    // 照片與語音
    /** 上傳照片或語音，回傳要存進 media 的 MediaItem（path 依 shared 放在 c/<couple>/ 或 u/<我>/） */
    suspend fun uploadMedia(bytes: ByteArray, mime: String, kind: String, shared: Boolean, width: Int? = null, height: Int? = null, durationMs: Long? = null): MediaItem
    /** 下載（給 MediaCache 用） */
    suspend fun downloadMedia(path: String): ByteArray
    /** 上傳了最後沒存的（面板關掉、存之前就移除）：沒有被用到的就刪掉，盡量刪、失敗就算了 */
    suspend fun discardMedia(items: List<MediaItem>) {}

    // 背景服務回報（示範模式不做事）
    suspend fun reportDevice(body: JsonObject) {}
    suspend fun reportLocation(lat: Double, lng: Double, accuracy: Float?, speed: Float?, recordedAt: java.time.Instant? = null) {}
    /** 地圖上「我」的位置：只存在這支手機，不會上傳（要分享位置才會用 [reportLocation] 回報） */
    fun setMyLocation(lat: Double, lng: Double) {}
    /** 首頁「我的手機」：只存在這支手機，不會上傳（要分享手機狀態才會用 [reportDevice] 回報） */
    fun setMyDevice(d: DeviceStatus) {}
    suspend fun reportPlaceEvent(placeId: String, arrive: Boolean) {}
    /** 回傳新記帳的 id；同一筆已經記過就是 null */
    suspend fun addAutoExpense(parsed: PaymentParser.Parsed, dedupeKey: String): String? = null
    /** 載具裡的一張發票記一筆（來源：發票載具）；同一張發票記過就回傳 false。記完要呼叫 [reloadExpenses] */
    suspend fun addInvoiceExpense(number: String, merchant: String, category: String, amount: Double, at: java.time.Instant): Boolean = false
    suspend fun reloadExpenses() {}
}
