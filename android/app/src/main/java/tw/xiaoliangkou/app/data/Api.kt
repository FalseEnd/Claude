package tw.xiaoliangkou.app.data

import kotlinx.serialization.SerializationException
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
import retrofit2.HttpException
import java.io.IOException

/**
 * App 用到的所有資料操作。每一個都是 Supabase 資料庫裡的 app_* 函式（PostgREST RPC），
 * 參數整包放在 JSON body；原本網址裡的 id、日期也放進 body。回傳的欄位是 camelCase。
 */
class Api(private val rest: RestService) {
    suspend fun health(): JsonObject = call("app_health")

    suspend fun me(): MeResponse = call("app_me")
    suspend fun updateProfile(body: JsonObject): Profile = call("app_update_profile", body)
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
