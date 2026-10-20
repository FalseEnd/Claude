package tw.xiaoliangkou.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

// 與資料庫 app_* 函式回傳的 JSON 對應（欄位為 camelCase）

@Serializable data class Profile(
    val id: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val birthday: String? = null,
    val email: String? = null,
    /** 身高、血型、喜歡的…（key 見 [ProfileInfo]），兩個人都看得到 */
    val info: JsonObject = JsonObject(emptyMap()),
) {
    val birthDate: LocalDate? get() = birthday?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}

@Serializable data class Couple(
    val id: String,
    val togetherSince: String? = null,
    val createdAt: String? = null,
    /** 兩個人共用的 GIPHY API key（資料庫 schema 4 起才有）：只拿來搜尋貼圖 */
    val giphyKey: String? = null,
) {
    // key 不能跟著狀態被印出來（例外訊息、測試失敗的訊息）
    override fun toString() = "Couple(id=$id, togetherSince=$togetherSince, createdAt=$createdAt, giphyKey=${giphyKey?.let { "***" }})"
}

@Serializable data class Sharing(
    val userId: String,
    val shareLocation: Boolean = false,
    val shareDevice: Boolean = false,
    val shareExpenses: Boolean = false,
    val shareMood: Boolean = true,
    val shareCycle: Boolean = false,
    val hiddenCategories: List<String> = emptyList(),
    val pausedUntil: String? = null,
) {
    val pausedUntilInstant: Instant? get() = pausedUntil?.toInstantOrNull()?.takeIf { it.isAfter(Instant.now()) }
    val paused get() = pausedUntilInstant != null
    /** 實際有在分享位置（有開、沒暫停） */
    val locationLive get() = shareLocation && !paused
    val deviceLive get() = shareDevice && !paused
}

@Serializable data class MeResponse(
    val user: Profile,
    val couple: Couple? = null,
    val partner: Profile? = null,
    val mySharing: Sharing? = null,
    val partnerSharing: Sharing? = null,
    val partnerOnline: Boolean = false,
)

@Serializable data class Invite(val code: String, val expiresAt: String? = null)

@Serializable data class DeviceStatus(
    val userId: String,
    val batteryLevel: Int? = null,
    val isCharging: Boolean? = null,
    val network: String? = null,
    val networkLabel: String? = null,
    val notificationsSilenced: Boolean? = null,
    val stepsToday: Int? = null,
    val lastActiveAt: String? = null,
    val deviceModel: String? = null,
    val updatedAt: String? = null,
    /** 手機名稱（設定 › 關於手機 › 裝置名稱），資料庫 schema 3 起才有 */
    val deviceName: String? = null,
    /** 電信（中華電信、台灣大哥大…），沒有 SIM 卡是 null */
    val carrier: String? = null,
)

@Serializable data class LocationDto(
    val userId: String,
    val lat: Double,
    val lng: Double,
    val accuracyM: Double? = null,
    val speedMps: Double? = null,
    val activity: String? = null,
    val recordedAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable data class PartnerStatus(val device: DeviceStatus? = null, val location: LocationDto? = null)

@Serializable data class Place(
    val id: String,
    val coupleId: String? = null,
    val createdBy: String? = null,
    val name: String,
    val icon: String = "pin",
    val lat: Double,
    val lng: Double,
    val radiusM: Int = 150,
    val notifyArrive: Boolean = true,
    val notifyLeave: Boolean = true,
    val watchUser: String? = null,
)

@Serializable data class Expense(
    val id: String,
    val userId: String? = null,
    val amount: Double? = null,
    val merchant: String,
    val category: String = "other",
    val source: String = "manual",
    val occurredAt: String,
    val visibility: String = "shared",
    val revealOn: String? = null,
    val guessable: Boolean = false,
    val theirGuess: Double? = null,
    val theirPoints: Int? = null,
    val myGuess: Double? = null,
    val myPoints: Int? = null,
) {
    val time: Instant get() = occurredAt.toInstantOrNull() ?: Instant.EPOCH
    /** 對方開放猜、我還沒猜：金額先藏起來 */
    val waitingForGuess get() = guessable && amount == null
}

@Serializable data class GuessResult(val actualAmount: Double, val scorePoints: Int, val errorRatio: Double)

@Serializable data class Mood(
    val id: Long,
    val userId: String,
    val mood: String,
    val note: String? = null,
    val createdAt: String,
    /** 附上的 GIPHY 貼圖 id（資料庫 schema 4 起才有） */
    val sticker: String? = null,
)

@Serializable data class Comfort(
    val id: Long,
    val coupleId: String? = null,
    val senderId: String,
    val receiverId: String,
    val kind: String,
    val message: String? = null,
    val createdAt: String,
    val readAt: String? = null,
    /** 附上的 GIPHY 貼圖 id（資料庫 schema 4 起才有） */
    val sticker: String? = null,
)

@Serializable data class Anniversary(
    val id: String,
    val coupleId: String? = null,
    val createdBy: String? = null,
    val title: String,
    val date: String,
    val kind: String = "custom",
    val repeat: String = "yearly",
    val calendar: String = "solar",
    val remindDays: List<Int> = listOf(0, 1, 7),
)

@Serializable data class DateEvent(
    val id: String,
    val coupleId: String? = null,
    val createdBy: String? = null,
    val title: String,
    val startsAt: String,
    val endsAt: String? = null,
    val allDay: Boolean = false,
    val placeId: String? = null,
    val locationText: String? = null,
    val note: String? = null,
    val status: String = "proposed",
    val remindMinutes: List<Int> = listOf(60),
) {
    val start: Instant get() = startsAt.toInstantOrNull() ?: Instant.EPOCH
    val localDate: LocalDate get() = start.atZone(ZoneId.systemDefault()).toLocalDate()
}

@Serializable data class CycleSettingsDto(
    val userId: String,
    val cycleLength: Int? = null,
    val periodLength: Int? = null,
    val lutealLength: Int = 14,
    val partnerCanEdit: Boolean = false,
    val careReminders: Boolean = true,
)

@Serializable data class PeriodDto(val id: String, val userId: String, val startDate: String, val endDate: String? = null, val recordedBy: String? = null)

@Serializable data class CycleLog(val userId: String, val logDate: String, val flow: String? = null, val symptoms: List<String> = emptyList(), val note: String? = null)

@Serializable data class CycleBundle(
    val settings: CycleSettingsDto? = null,
    val periods: List<PeriodDto> = emptyList(),
    val logs: List<CycleLog> = emptyList(),
)

@Serializable data class CycleResponse(val mine: CycleBundle = CycleBundle(), val partner: CycleBundle = CycleBundle())

@Serializable data class Activity(
    val id: Long,
    val coupleId: String? = null,
    val actorId: String? = null,
    val kind: String,
    val payload: JsonObject = JsonObject(emptyMap()),
    val createdAt: String,
) {
    val time: Instant get() = createdAt.toInstantOrNull() ?: Instant.EPOCH
}

/** 照片或語音：檔案在 Storage 的 media bucket，[path] 是 c/<配對>/… 或 u/<我>/… */
@Serializable data class MediaItem(
    val path: String,
    /** "photo" | "voice" */
    val kind: String,
    val mime: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
) {
    val isPhoto get() = kind == PHOTO
    val isVoice get() = kind == VOICE

    companion object {
        const val PHOTO = "photo"
        const val VOICE = "voice"
    }
}

/** 只有自己看得到的備註（寫給對方的小抄） */
@Serializable data class Note(
    val id: String,
    val aboutUser: String? = null,
    val body: String,
    val createdAt: String,
    val updatedAt: String? = null,
) {
    val time: Instant get() = (updatedAt ?: createdAt).toInstantOrNull() ?: Instant.EPOCH
}

/** 日曆上記錄那天的生活：文字、照片、語音 */
@Serializable data class DayEntry(
    val id: String,
    val userId: String,
    val day: String,
    val body: String = "",
    val media: List<MediaItem> = emptyList(),
    val createdAt: String,
    val updatedAt: String? = null,
) {
    val date: LocalDate? get() = runCatching { LocalDate.parse(day) }.getOrNull()
    val time: Instant get() = createdAt.toInstantOrNull() ?: Instant.EPOCH

    companion object {
        /** 跟 app_day_entries 一樣：依日期，同一天先寫的在前 */
        val ORDER: Comparator<DayEntry> = compareBy({ it.day }, { it.time })
    }
}

/** 備忘錄：[shared] 兩個人都看得到、都能編輯；[pinned] 放在桌面小工具 */
@Serializable data class Memo(
    val id: String,
    val userId: String,
    val title: String = "",
    val body: String = "",
    val media: List<MediaItem> = emptyList(),
    val shared: Boolean = true,
    val pinned: Boolean = false,
    val color: String = "pink",
    val createdAt: String,
    val updatedAt: String? = null,
) {
    val time: Instant get() = (updatedAt ?: createdAt).toInstantOrNull() ?: Instant.EPOCH

    companion object {
        val COLORS = listOf("pink", "yellow", "mint", "sky", "lavender", "peach")
        /** 跟 app_memos 一樣：釘選的在前，再來是最近改的 */
        val ORDER: Comparator<Memo> = compareByDescending<Memo> { it.pinned }.thenByDescending { it.time }
    }
}

/** app_pending_notifications 回傳的通知 */
@Serializable data class PendingNotification(
    val id: Long,
    val kind: String = "",
    val title: String = "小倆口",
    val body: String? = null,
    val data: JsonObject? = null,
    val createdAt: String? = null,
)

private val SHORT_OFFSET = Regex("([+-]\\d{2})$")

/**
 * 時間字串 → Instant。資料庫回傳 "2026-10-15T02:16:21.531234+00:00"，App 自己產生的是 "...Z"，小數秒可有可無。
 * Android 8 的 Instant.parse 不接受 +00:00，所以先用 OffsetDateTime（兩種都接受）。
 */
fun String.toInstantOrNull(): Instant? {
    val s = trim().replace(' ', 'T')
    if (s.isEmpty()) return null
    return runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull()
        // "+00" 這種沒有分鐘的時區（PostgreSQL 的文字格式）
        ?: runCatching { OffsetDateTime.parse(SHORT_OFFSET.replace(s, "$1:00")).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(s) }.getOrNull()
}
