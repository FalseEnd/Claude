package tw.xiaoliangkou.app.data

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import java.io.IOException

/**
 * App 用到的所有資料操作。每一個都是 Supabase 資料庫裡的 app_* 函式（PostgREST RPC），
 * 參數整包放在 JSON body；原本網址裡的 id、日期也放進 body。回傳的欄位是 camelCase。
 * 照片、語音放在 Storage 的 media bucket（[storage]）。
 */
class Api(private val rest: RestService, private val storage: StorageService? = null) {
    suspend fun health(): JsonObject = call("app_health")

    suspend fun me(): MeResponse = call("app_me")
    suspend fun updateProfile(body: JsonObject): Profile = call("app_update_profile", body)
    /** 回傳原始 JSON：舊的資料庫不認得 info，回傳裡就不會有 info */
    suspend fun updateProfileRaw(body: JsonObject): JsonObject = call("app_update_profile", body)
    suspend fun updateCouple(body: JsonObject): Couple = call("app_update_couple", body)
    suspend fun invite(): Invite = call("app_create_invite")
    suspend fun accept(body: JsonObject): JsonObject = call("app_accept_invite", body)
    suspend fun unpair(): JsonObject = call("app_unpair")

    suspend fun updateSharing(body: JsonObject): Sharing = call("app_update_sharing", body)
    suspend fun pauseSharing(body: JsonObject): JsonObject = call("app_pause_sharing", body)

    suspend fun putDevice(body: JsonObject): JsonObject = call("app_report_device", body)
    suspend fun putLocation(body: JsonObject): JsonObject = call("app_report_location", body)
    suspend fun partnerStatus(): PartnerStatus = call("app_partner_status")
    suspend fun locatePartner(): JsonObject = call("app_locate_partner")

    suspend fun places(): List<Place> = call("app_places")
    suspend fun createPlace(body: JsonObject): Place = call("app_create_place", body)
    suspend fun updatePlace(id: String, body: JsonObject): Place = call("app_update_place", body.with("id", id))
    suspend fun deletePlace(id: String): JsonObject = call("app_delete_place", idBody(id))
    suspend fun placeEvent(id: String, body: JsonObject): JsonObject = call("app_place_event", body.with("id", id))

    suspend fun expenses(from: String? = null, to: String? = null): List<Expense> = call("app_expenses", range(from, to))
    suspend fun createExpense(body: JsonObject): JsonObject = call("app_create_expense", body)
    suspend fun updateExpense(id: String, body: JsonObject): Expense = call("app_update_expense", body.with("id", id))
    suspend fun deleteExpense(id: String): JsonObject = call("app_delete_expense", idBody(id))
    suspend fun partnerExpenses(from: String? = null, to: String? = null): List<Expense> = call("app_partner_expenses", range(from, to))
    suspend fun guess(id: String, body: JsonObject): GuessResult = call("app_guess", body.with("id", id))

    suspend fun moods(days: Int = 14): List<Mood> = call("app_moods", buildJsonObject { put("days", days) })
    suspend fun postMood(body: JsonObject): Mood = call("app_post_mood", body)
    suspend fun comforts(limit: Int = 50): List<Comfort> = call("app_comforts", buildJsonObject { put("limit", limit) })
    suspend fun postComfort(body: JsonObject): Comfort = call("app_send_comfort", body)
    suspend fun readComfort(id: Long): JsonObject = call("app_read_comfort", buildJsonObject { put("id", id) })

    suspend fun anniversaries(): List<Anniversary> = call("app_anniversaries")
    suspend fun createAnniversary(body: JsonObject): Anniversary = call("app_create_anniversary", body)
    suspend fun updateAnniversary(id: String, body: JsonObject): Anniversary = call("app_update_anniversary", body.with("id", id))
    suspend fun deleteAnniversary(id: String): JsonObject = call("app_delete_anniversary", idBody(id))

    suspend fun events(): List<DateEvent> = call("app_events")
    suspend fun createEvent(body: JsonObject): DateEvent = call("app_create_event", body)
    suspend fun updateEvent(id: String, body: JsonObject): DateEvent = call("app_update_event", body.with("id", id))
    suspend fun answerEvent(id: String, body: JsonObject): DateEvent = call("app_answer_event", body.with("id", id))
    suspend fun deleteEvent(id: String): JsonObject = call("app_delete_event", idBody(id))

    suspend fun cycle(): CycleResponse = call("app_cycle")
    suspend fun saveCycleSettings(body: JsonObject): CycleSettingsDto = call("app_save_cycle_settings", body)
    suspend fun stopCycle(): JsonObject = call("app_stop_cycle")
    suspend fun addPeriod(body: JsonObject): PeriodDto = call("app_add_period", body)
    suspend fun updatePeriod(id: String, body: JsonObject): PeriodDto = call("app_update_period", body.with("id", id))
    suspend fun deletePeriod(id: String): JsonObject = call("app_delete_period", idBody(id))
    suspend fun saveCycleLog(date: String, body: JsonObject): CycleLog = call("app_save_cycle_log", body.with("date", date))

    suspend fun history(before: Long? = null, limit: Int = 60, kinds: List<String>? = null): List<Activity> = call(
        "app_history",
        buildJsonObject {
            if (before != null) put("before", before)
            put("limit", limit)
            if (kinds != null) putJsonArray("kinds") { kinds.forEach { add(JsonPrimitive(it)) } }
        },
    )
    suspend fun notificationPrefs(): Map<String, Boolean> = call("app_notification_prefs")
    suspend fun setNotificationPrefs(body: JsonObject): JsonObject = call("app_set_notification_prefs", body)

    // 只有自己看得到的備註
    suspend fun notes(): List<Note> = call("app_notes")
    suspend fun saveNote(body: JsonObject): Note = call("app_save_note", body)
    suspend fun deleteNote(id: String): JsonObject = call("app_delete_note", idBody(id))

    // 日曆的生活紀錄（日期 YYYY-MM-DD；沒給就是今天往前 62 天到往後 31 天）
    suspend fun dayEntries(from: String? = null, to: String? = null): List<DayEntry> = call("app_day_entries", range(from, to))
    suspend fun saveDayEntry(body: JsonObject): DayEntry = call("app_save_day_entry", body)
    suspend fun deleteDayEntry(id: String): JsonObject = call("app_delete_day_entry", idBody(id))

    // 備忘錄
    suspend fun memos(): List<Memo> = call("app_memos")
    suspend fun saveMemo(body: JsonObject): Memo = call("app_save_memo", body)
    suspend fun deleteMemo(id: String): JsonObject = call("app_delete_memo", idBody(id))

    /** 上傳到 media bucket（檔名都是新的 uuid，不覆寫） */
    suspend fun uploadMedia(path: String, mime: String, bytes: ByteArray) {
        val s = storage ?: throw AppException("這個連線不能上傳檔案", "no_storage")
        storageCall { s.upload(MediaPaths.BUCKET, MediaPaths.checked(path), bytes.toRequestBody(mime.toMediaTypeOrNull())).close() }
    }

    suspend fun downloadMedia(path: String): ByteArray {
        val s = storage ?: throw AppException("這個連線不能下載檔案", "no_storage")
        return storageCall { s.download(MediaPaths.BUCKET, MediaPaths.checked(path)).use { it.bytes() } }
    }

    /** 刪掉檔案（只有上傳的人刪得掉，其他的 Storage 會直接略過） */
    suspend fun deleteMedia(paths: List<String>) {
        if (paths.isEmpty()) return
        val s = storage ?: throw AppException("這個連線不能刪除檔案", "no_storage")
        val body = buildJsonObject { putJsonArray("prefixes") { paths.forEach { add(JsonPrimitive(MediaPaths.checked(it))) } } }
        storageCall { s.remove(MediaPaths.BUCKET, body.toBody()).close() }
    }

    /** Storage 的錯誤格式跟 PostgREST 不一樣，自己轉成中文 */
    private suspend fun <T> storageCall(block: suspend () -> T): T = try {
        block()
    } catch (e: HttpException) {
        throw Errors.storage(e.code(), e.response()?.errorBody()?.string())
    }

    /** 還沒送到這支手機的通知（最近 3 天） */
    suspend fun pendingNotifications(): List<PendingNotification> = call("app_pending_notifications")
    suspend fun ackNotifications(ids: List<Long>): JsonObject =
        call("app_ack_notifications", buildJsonObject { putJsonArray("ids") { ids.forEach { add(JsonPrimitive(it)) } } })

    /** 呼叫 app_* 函式，把回傳的 JSON 轉成 [T] */
    suspend inline fun <reified T> call(name: String, body: JsonObject = EMPTY): T =
        json.decodeFromJsonElement(serializer<T>(), raw(name, body))

    suspend fun raw(name: String, body: JsonObject = EMPTY): JsonElement =
        rest.rpc(name, body.toBody()).use { r ->
            val text = r.string()
            try {
                json.parseToJsonElement(text)
            } catch (e: SerializationException) {
                throw NotJsonException()
            }
        }

    companion object {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true; encodeDefaults = true }
        val EMPTY = JsonObject(emptyMap())

        fun mediaJson(media: List<MediaItem>): JsonElement = json.encodeToJsonElement(ListSerializer(MediaItem.serializer()), media)

        private fun JsonObject.with(key: String, value: String) = JsonObject(this + (key to JsonPrimitive(value)))
        private fun idBody(id: String) = buildJsonObject { put("id", id) }
        private fun range(from: String?, to: String?) = buildJsonObject {
            if (from != null) put("from", from)
            if (to != null) put("to", to)
        }
    }
}

/** 回傳的根本不是 JSON（例如一個網頁）：多半是網址貼錯了，或 Wi-Fi 要先登入 */
class NotJsonException : SerializationException("response is not JSON")

/** 給畫面顯示的錯誤訊息 */
class AppException(message: String, val code: String? = null, val status: Int? = null) : Exception(message)

/** Supabase（PostgREST、Auth、API Gateway）回傳的錯誤 → 溫暖的中文 */
object Errors {
    const val CONFIRM_EMAIL = "請到 Supabase 後台關掉 Confirm email（說明文件第 3 步），再註冊一次"
    const val NOT_SETUP = "資料庫還沒設定好：請在 Supabase 的 SQL Editor 執行 setup.sql（說明文件第 2 步）"
    const val BAD_KEY = "Supabase 金鑰不正確，請重新貼一次 Publishable key（或 anon key）"
    const val RELOGIN = "請重新登入"
    const val SCHEMA_OUTDATED = "資料庫需要更新：請到 Supabase 的 SQL Editor 執行 upgrade-v2.sql（說明在「我的 › Supabase 與帳號」）"
    const val SCHEMA_OUTDATED_CODE = "schema_outdated"

    /** 2026-10 新增的函式：找不到代表還沒執行 upgrade-v2.sql，不是整個 setup.sql 沒跑 */
    val V2_FUNCTIONS = setOf(
        "app_notes", "app_save_note", "app_delete_note",
        "app_day_entries", "app_save_day_entry", "app_delete_day_entry",
        "app_memos", "app_save_memo", "app_delete_memo",
    )
    private val FUNCTION_NAME = Regex("""public\.(\w+)""")

    // 資料庫函式丟出的錯誤代碼（raise exception '<code>'）
    val DB_MESSAGES = mapOf(
        "not_authenticated" to RELOGIN,
        "already_paired" to "已經和別人配對了，請先解除配對",
        "not_paired" to "還沒有配對",
        "invite_invalid" to "配對碼無效或已過期，請對方重新產生",
        "cannot_pair_with_self" to "這是你自己的配對碼，請輸入對方的",
        "invalid_duration" to "暫停時間要在 24 小時內",
        "expense_not_guessable" to "這筆開銷不能猜",
        "invalid_guess" to "請輸入金額",
        "rate_limited" to "傳太快了，休息一下再傳",
        "registration_closed" to "這個小倆口已經有兩個人了，請用原本的 Email 登入",
        "not_found" to "找不到資料，可能已經被刪除了",
        "nothing_to_update" to "沒有要更新的欄位",
        "invalid_kind" to "種類不正確",
        "invalid_status" to "狀態不正確",
        "invalid_range" to "日期格式不正確",
        "invalid_media" to "照片或語音的檔案不正確，請重新加入一次",
    )

    private val INVALID_INPUT = setOf("23502", "23514", "22P02", "22007", "22008", "22003", "22023", "2201B")

    fun from(status: Int, body: String?): AppException {
        val obj = runCatching { Api.json.parseToJsonElement(body ?: "").jsonObject }.getOrNull()
        fun str(k: String) = runCatching { (obj?.get(k) as? JsonPrimitive)?.contentOrNull }.getOrNull()
        val message = str("message")
        val goTrueCode = str("error_code") ?: str("error")
        val goTrueMsg = str("msg") ?: str("error_description")
        if (goTrueCode != null || goTrueMsg != null) return auth(status, goTrueCode, goTrueMsg ?: message)
        if (message != null && message.contains("API key", ignoreCase = true)) return AppException(BAD_KEY, "bad_key", status)
        val code = str("code")
        return when {
            // raise exception '<code>'（P0001），或 using errcode = 'PT404' 這類指定 HTTP 狀態的
            code == "P0001" || code?.startsWith("PT") == true -> {
                val c = message ?: "error"
                AppException(DB_MESSAGES[c] ?: c, c, status)
            }
            // 沒有登入（anon）呼叫只開放給登入者的函式
            code == "42501" && status == 401 -> AppException(RELOGIN, "not_authenticated", status)
            code == "42501" -> AppException("沒有權限（對方可能沒有分享這項資料）", "forbidden", status)
            code == "23505" -> AppException("資料重複了", "conflict", status)
            code == "23503" -> AppException("關聯的資料不存在", "invalid_reference", status)
            code in INVALID_INPUT -> AppException("輸入的資料格式不正確", "invalid_input", status)
            // 找不到新功能的函式：還沒執行 upgrade-v2.sql
            code == "PGRST202" && FUNCTION_NAME.find(message.orEmpty())?.groupValues?.get(1) in V2_FUNCTIONS ->
                AppException(SCHEMA_OUTDATED, SCHEMA_OUTDATED_CODE, status)
            // 找不到函式：setup.sql 還沒執行
            code == "PGRST202" || code == "PGRST205" || code == "42883" -> AppException(NOT_SETUP, "not_setup", status)
            code == "PGRST301" || code == "PGRST302" || code == "PGRST303" || status == 401 -> AppException(RELOGIN, "not_authenticated", status)
            status >= 500 -> AppException("Supabase 暫時有點狀況，等一下再試（專案被暫停的話，到後台按 Resume project）", "server_error", status)
            else -> AppException(message ?: "Supabase 回應 $status", code, status)
        }
    }

    /** Supabase Auth 的錯誤（新版是 error_code + msg，舊版是 error + error_description） */
    private fun auth(status: Int, code: String?, msg: String?): AppException {
        val m = msg.orEmpty().lowercase()
        val (c, text) = when {
            code == "invalid_credentials" || "invalid login credentials" in m -> "invalid_login" to "Email 或密碼不正確"
            code == "user_already_exists" || code == "email_exists" || "already registered" in m -> "email_taken" to "這個 Email 已經註冊過了"
            "database error saving new user" in m -> "registration_closed" to "這個小倆口已經有兩個人了，或資料庫還沒設定好"
            code == "weak_password" || "password should be" in m -> "weak_password" to "密碼至少要 6 個字"
            code == "email_not_confirmed" || "email not confirmed" in m -> "email_confirmation_on" to CONFIRM_EMAIL
            code == "same_password" -> "same_password" to "新密碼要和舊密碼不一樣喔"
            code == "email_address_invalid" || ("email" in m && ("invalid" in m || "validate" in m)) -> "invalid_email" to "Email 格式不太對，再檢查一下"
            code == "signup_disabled" || code == "email_provider_disabled" || "signups not allowed" in m ->
                "signup_disabled" to "Supabase 後台關掉了 Email 註冊：請到 Authentication › Sign In / Providers 打開 Email"
            code?.startsWith("over_") == true || status == 429 -> "rate_limited" to "試太多次了，休息一下再試"
            "api key" in m -> "bad_key" to BAD_KEY
            code in setOf("refresh_token_not_found", "refresh_token_already_used", "session_not_found", "session_expired", "bad_jwt", "no_authorization", "user_not_found", "invalid_grant") ||
                status == 401 -> "not_authenticated" to RELOGIN
            status >= 500 -> "server_error" to "Supabase 暫時有點狀況，等一下再試"
            else -> "auth_error" to (msg ?: "登入時發生錯誤（$status）")
        }
        return AppException(text, c, status)
    }

    /**
     * Supabase Storage 的錯誤：{"statusCode":"404","error":"not_found","message":"Object not found"}。
     * 舊版的 HTTP 狀態一律是 400，真正的狀態在 statusCode 裡。
     */
    fun storage(status: Int, body: String?): AppException {
        val obj = runCatching { Api.json.parseToJsonElement(body ?: "").jsonObject }.getOrNull()
        fun str(k: String) = runCatching { (obj?.get(k) as? JsonPrimitive)?.contentOrNull }.getOrNull()
        val code = str("statusCode")?.toIntOrNull() ?: status
        val text = listOfNotNull(str("code"), str("error"), str("message")).joinToString(" ").lowercase()
        return when {
            "bucket not found" in text || "nosuchbucket" in text -> AppException(SCHEMA_OUTDATED, SCHEMA_OUTDATED_CODE, status)
            "api key" in text -> AppException(BAD_KEY, "bad_key", status)
            "jwt" in text || "invalidjwt" in text -> AppException("登入狀態過期了，請再試一次", "jwt_expired", status)
            code == 413 || "too large" in text || "maximum allowed size" in text || "entitytoolarge" in text ->
                AppException("檔案太大了（最多 10 MB）", "too_large", status)
            code == 415 || "mime" in text -> AppException("不支援這種檔案格式", "invalid_media", status)
            code == 409 || "already exists" in text || "duplicate" in text -> AppException("檔案已經存在", "conflict", status)
            code == 404 || "not found" in text || "nosuchkey" in text -> AppException("找不到檔案，可能已經被刪除了", "not_found", status)
            code == 403 || "row-level security" in text || "unauthorized" in text || "accessdenied" in text ->
                AppException("沒有權限存取這個檔案", "forbidden", status)
            code == 401 -> AppException(RELOGIN, "not_authenticated", status)
            code >= 500 -> AppException("Supabase 暫時有點狀況，等一下再試", "server_error", status)
            else -> AppException("檔案傳送失敗，等一下再試試看（$code）", "storage_error", status)
        }
    }

    /** 換新 token 失敗時，是不是 refresh token 已經不能用了（要重新登入），而不是暫時的問題 */
    fun isDeadRefresh(status: Int, body: String?): Boolean =
        status in 400..499 && status != 408 && status != 429 && body?.contains("API key", ignoreCase = true) != true
}

suspend fun <T> apiCall(block: suspend () -> T): T = try {
    block()
} catch (e: HttpException) {
    throw Errors.from(e.code(), e.response()?.errorBody()?.string())
} catch (e: InvalidRequestException) {
    throw AppException("網址或金鑰裡有看不懂的字元，請到「連接 Supabase」重新貼一次 🔑", "bad_key")
} catch (e: IOException) {
    throw AppException("連不到 Supabase，請確認網路", "offline")
} catch (e: NotJsonException) {
    throw AppException("收到的不是 Supabase 的資料：網址可能貼錯了，或 Wi-Fi 需要先登入", "not_json")
} catch (e: SerializationException) {
    throw AppException("Supabase 回傳的資料格式不對，資料庫可能還不是最新的 setup.sql", "bad_response")
}
