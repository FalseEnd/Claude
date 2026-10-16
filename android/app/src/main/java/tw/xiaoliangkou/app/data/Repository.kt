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
) {
    val paired get() = couple != null && partner != null
    val myId get() = me?.id
    val partnerName get() = partner?.displayName ?: "對方"
    val togetherSince: LocalDate? get() = couple?.togetherSince?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

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

    // 心情與安撫
    suspend fun postMood(mood: String, note: String?)
    suspend fun sendComfort(kind: String, message: String?)

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

    // 背景服務回報（示範模式不做事）
    suspend fun reportDevice(body: JsonObject) {}
    suspend fun reportLocation(lat: Double, lng: Double, accuracy: Float?, speed: Float?) {}
    suspend fun reportPlaceEvent(placeId: String, arrive: Boolean) {}
    /** 回傳新記帳的 id；同一筆已經記過就是 null */
    suspend fun addAutoExpense(parsed: PaymentParser.Parsed, dedupeKey: String): String? = null
}
