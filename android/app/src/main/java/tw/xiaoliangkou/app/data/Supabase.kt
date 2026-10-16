package tw.xiaoliangkou.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Authenticator
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Supabase 專案的網址與金鑰：整理使用者貼上的內容 */
object Supabase {
    /** 預設連線的 Supabase 專案：第一次打開 App 就自動連上，直接註冊。publishable key 本來就是公開金鑰，資料由資料庫的權限規則保護 */
    const val DEFAULT_URL = "https://mdlevoygfciwzlqtsids.supabase.co"
    const val DEFAULT_KEY = "sb_publishable_9lupdPr_yHbpvggEtuSjGg_nM4zFNh-"

    /** 設定教學（說明文件） */
    const val GUIDE_URL = "https://github.com/FalseEnd/Claude/blob/claude/couples-app-design-bg9e2n/docs/SUPABASE_SETUP.md"

    /** 瀏覽器網址列的後台網址：supabase.com/dashboard/project/<ref>（舊版是 app.supabase.com/project/<ref>） */
    private val DASHBOARD = Regex("""^(?:https?://)?(?:www\.|app\.)?supabase\.com/(?:dashboard/)?project/([a-z0-9]{6,40})(?:[/?#].*)?$""", RegexOption.IGNORE_CASE)

    /** 貼上的是後台網址的話，回傳專案代號（ref） */
    fun dashboardRef(raw: String): String? = DASHBOARD.find(raw.trim())?.groupValues?.get(1)?.lowercase()

    /** 貼上的網址整理成 https://xxxx.supabase.co（沒有結尾斜線）；後台網址會換成專案的 Project URL */
    fun normalizeUrl(raw: String): String {
        dashboardRef(raw)?.let { return "https://$it.supabase.co" }
        var url = raw.filterNot(::isInvisible).trim().trimEnd('/')
        for (suffix in listOf("/rest/v1", "/auth/v1", "/realtime/v1")) url = url.removeSuffix(suffix).trimEnd('/')
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        // 官方的雲端專案一律走 https
        val host = hostOf(url)
        if (url.startsWith("http://") && isOfficialHost(host)) url = "https://" + url.removePrefix("http://")
        return url
    }

    /** 網址裡的主機名稱（例如 abcd.supabase.co） */
    fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':').lowercase()

    /** Supabase 官方雲端的專案網址 */
    fun isOfficialHost(host: String) = host.endsWith(".supabase.co") || host.endsWith(".supabase.in")

    /** 網址有問題時回傳給使用者看的說明；沒問題回傳 null（[url] 要先經過 [normalizeUrl]） */
    fun urlProblem(url: String): String? {
        val host = hostOf(url)
        return when {
            url.toHttpUrlOrNull() == null || host.isEmpty() || !url.all { it in '!'..'~' } ->
                "網址看起來不太對，請貼 Project URL，長得像 https://xxxx.supabase.co 🔗"
            host == "supabase.com" || host.endsWith(".supabase.com") ->
                "這是 Supabase 後台的網址喔！請到 Project Settings › Data API 複製 Project URL（https://xxxx.supabase.co）🔗"
            else -> null
        }
    }

    /** 看不到的字元：零寬空白、BOM、方向控制字元等（從網頁或聊天軟體複製時常常混進來） */
    private fun isInvisible(c: Char) = c.isWhitespace() || c.isISOControl() || Character.getType(c) == Character.FORMAT.toInt() ||
        c == '\u200B' || c == '\uFEFF' || c == '\u2060' || c == '\u3164' || c == '\u115F' || c == '\u1160'

    /** 去掉空白與看不到的字元；其他（全形字、…）留著，交給 [keyProblem] 擋下 */
    fun normalizeKey(raw: String) = raw.filterNot(::isInvisible)

    /** 舊式的 anon key 是 JWT；新式的 sb_publishable_ 不是 */
    fun isJwt(key: String) = key.startsWith("eyJ") && key.count { it == '.' } == 2

    /** sb_secret_ 或 service_role key 可以繞過所有權限，絕對不能放進 App */
    fun isSecretKey(key: String) = key.startsWith("sb_secret_") || jwtClaim(key, "role") == "service_role"

    private val PUBLISHABLE = Regex("""^sb_publishable_[A-Za-z0-9_-]+$""")
    private val JWT = Regex("""^eyJ[A-Za-z0-9_+/=-]+\.[A-Za-z0-9_+/=-]+\.[A-Za-z0-9_+/=-]*$""")

    const val SECRET_KEY = "這是 secret 金鑰，不能放進 App！請改貼 Publishable key（sb_publishable_ 開頭）或 anon key"

    /**
     * 金鑰有問題時回傳給使用者看的說明；沒問題回傳 null（[key] 要先經過 [normalizeKey]）。
     * 金鑰會放進 HTTP 標頭，只能是一般的英數字與符號，不然 OkHttp 會直接丟例外。
     */
    fun keyProblem(key: String): String? = when {
        key.isEmpty() -> "請貼上 Supabase 金鑰 🔑"
        !key.all { it in '!'..'~' } -> "金鑰裡混進了全形字或看不到的特殊字元，請到 Supabase 後台重新複製一次 🔑"
        isSecretKey(key) -> SECRET_KEY
        !PUBLISHABLE.matches(key) && !JWT.matches(key) ->
            "這看起來不是 Supabase 的金鑰：請貼 Publishable key（sb_publishable_ 開頭）或 anon key（eyJ 開頭）🔑"
        else -> null
    }

    fun jwtClaim(jwt: String, name: String): String? = runCatching {
        val payload = String(Base64.getUrlDecoder().decode(jwt.split('.')[1].trimEnd('=')))
        Api.json.parseToJsonElement(payload).jsonObject[name]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    const val WRONG_URL = "這個網址不是 Supabase 專案的 API：請到 Supabase 後台 Project Settings › Data API 複製 Project URL" +
        "（https://xxxx.supabase.co），不是瀏覽器網址列上的後台網址喔 🔗"

    /** 「測試連線」失敗時，換成告訴使用者該檢查哪裡的說明 */
    fun setupHint(e: AppException): AppException = when {
        e.code == "bad_key" || e.status == 401 || e.status == 403 ->
            AppException("金鑰不正確：請貼 Publishable key（sb_publishable_ 開頭）或 anon key，前後不要有空白 🔑", "bad_key", e.status)
        e.code == "not_setup" ->
            AppException("找到 Supabase 了，但資料庫還沒設定好：請先在 SQL Editor 執行 setup.sql（說明文件第 2 步）", "not_setup", e.status)
        // 回傳的不是 JSON（網頁），或 404 但不是 PostgREST 的「找不到函式」：網址貼錯了
        e.code == "not_json" || e.status == 404 -> AppException(WRONG_URL, "wrong_url", e.status)
        e.code == "offline" ->
            AppException("連不到這個網址：請確認是 https://xxxx.supabase.co，手機也有網路", "offline")
        else -> e
    }
}

/** 分享給另一半的連線邀請：xiaoliangkou://connect?url=…&key=… */
object InviteLink {
    const val SCHEME = "xiaoliangkou"
    private val pattern = Regex("""xiaoliangkou://connect/?\?(\S+)""", RegexOption.IGNORE_CASE)

    fun build(url: String, key: String) =
        "$SCHEME://connect?url=${URLEncoder.encode(url, "UTF-8")}&key=${URLEncoder.encode(key, "UTF-8")}"

    fun shareText(url: String, key: String) =
        "我們的小倆口連線邀請 💌\n裝好小倆口後點下面的連結，確認是你們的 Supabase 後就會幫你填好連線設定：\n${build(url, key)}\n\n" +
            "點不開的話，複製這整段訊息，打開小倆口的「連接 Supabase」按「貼上」就好。"

    /** 從連結或整段訊息裡找出網址與金鑰；看不懂（或金鑰、網址不對勁）就回傳 null */
    fun parse(text: String): Pair<String, String>? {
        val query = pattern.find(text)?.groupValues?.get(1) ?: return null
        val params = query.split('&').mapNotNull { kv ->
            val parts = kv.split('=', limit = 2)
            if (parts.size != 2) null else runCatching { URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8") }.getOrNull()
        }.toMap()
        val url = params["url"]?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let(Supabase::normalizeUrl)
            ?.takeIf { Supabase.urlProblem(it) == null } ?: return null
        val key = params["key"]?.let(Supabase::normalizeKey)?.takeIf { it.length >= 10 && Supabase.keyProblem(it) == null } ?: return null
        return url to key
    }

    /** 打開邀請前給使用者確認的內容：要連到哪裡、有什麼要注意 */
    data class Review(val host: String, val official: Boolean, val warnings: List<String>)

    /**
     * [savedUrl] 是手機上目前存的網址（沒有就是空字串），[demo] 代表現在在示範模式。
     * 邀請連結誰都能做，所以不是官方網址、會換掉原本的設定時都要提醒。
     */
    fun review(url: String, savedUrl: String?, demo: Boolean): Review {
        val host = Supabase.hostOf(url)
        val official = Supabase.isOfficialHost(host)
        val warnings = buildList {
            if (!official) add("這不是 Supabase 官方的網址（xxxx.supabase.co）。除非你們自己架了 Supabase，不然先別連，問問另一半喔")
            if (url.startsWith("http://")) add("這個網址沒有加密（http），只適合在自己的電腦上測試")
            if (!savedUrl.isNullOrEmpty() && savedUrl != url) {
                add("跟手機上存的 ${Supabase.hostOf(savedUrl)} 不一樣：測試成功後會換成新的，之後登入、註冊（包含密碼）都會送到這裡")
            }
            if (demo) add("會先離開示範模式，示範資料不會保留")
        }
        return Review(host, official, warnings)
    }
}

/** 登入狀態。expiresAt 是手機時間（秒），用 expires_in 算，不受手機時鐘誤差影響 */
data class Session(val accessToken: String, val refreshToken: String, val expiresAt: Long, val userId: String, val email: String?)

@Serializable data class GoTrueUser(val id: String, val email: String? = null)

@Serializable data class GoTrueSession(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val user: GoTrueUser? = null,
) {
    fun toSession(fallbackEmail: String? = null, nowSec: Long = System.currentTimeMillis() / 1000): Session? {
        val access = accessToken ?: return null
        val refresh = refreshToken ?: return null
        val uid = user?.id ?: Supabase.jwtClaim(access, "sub") ?: return null
        return Session(access, refresh, nowSec + (expiresIn ?: 3600), uid, user?.email ?: fallbackEmail)
    }
}

/** Supabase Auth（GoTrue） */
interface GoTrueService {
    @POST("auth/v1/signup") suspend fun signUp(@Body body: RequestBody): ResponseBody
    @POST("auth/v1/token?grant_type=password") suspend fun signIn(@Body body: RequestBody): ResponseBody
    @POST("auth/v1/logout") suspend fun signOut(@Header("Authorization") bearer: String, @Query("scope") scope: String = "local"): Response<Unit>
    @PUT("auth/v1/user") suspend fun updateUser(@Header("Authorization") bearer: String, @Body body: RequestBody): ResponseBody
}

/** 資料 API：每個操作都是資料庫裡的 app_* 函式 */
interface RestService {
    @POST("rest/v1/rpc/{name}") suspend fun rpc(@Path("name") name: String, @Body body: RequestBody): ResponseBody
}

private val JSON_TYPE = "application/json".toMediaType()
internal fun JsonObject.toBody(): RequestBody = toString().toRequestBody(JSON_TYPE)

/**
 * 登入狀態管理：保管 access / refresh token。
 * 快過期（或收到 401）時用 refresh token 換新；refresh token 失效（例如在別支手機改了密碼）就呼叫 [onExpired]。
 * 換新用同步的 OkHttp 呼叫，所以 OkHttp 的 Interceptor / Authenticator 也能直接用。
 */
class SessionManager(
    private val url: String,
    private val key: String,
    initial: Session?,
    private val http: OkHttpClient = sharedHttp,
) {
    @Volatile var current: Session? = initial
        private set
    /** token 換新後（存到手機、告訴即時連線） */
    @Volatile var onChange: (Session) -> Unit = {}
    /** refresh token 已失效：需要重新登入 */
    @Volatile var onExpired: () -> Unit = {}
    private val lock = Any()

    fun replace(s: Session) {
        synchronized(lock) { current = s }
        onChange(s)
    }

    /** 至少還有 [minValiditySec] 秒有效的 access token；必要時先換新。網路不通時先回傳舊的 */
    fun validToken(minValiditySec: Long = 60): String? {
        val s = current ?: return null
        if (s.expiresAt - System.currentTimeMillis() / 1000 > minValiditySec) return s.accessToken
        return (refreshBlocking(s.accessToken) ?: current)?.accessToken
    }

    suspend fun freshToken(minValiditySec: Long = 120): String? = withContext(Dispatchers.IO) { validToken(minValiditySec) }

    /**
     * 用 refresh token 換新。[failedToken] 是剛剛被拒絕的 access token：
     * 如果別的請求已經換好了，就直接用新的，不重複換。
     */
    fun refreshBlocking(failedToken: String?): Session? = synchronized(lock) { refreshLocked(failedToken) }

    private fun refreshLocked(failedToken: String?): Session? {
        val s = current ?: return null
        if (failedToken != null && s.accessToken != failedToken) return s
        val (code, body) = try {
            val req = Request.Builder()
                .url("$url/auth/v1/token?grant_type=refresh_token")
                .header("apikey", key)
                .post(buildJsonObject { put("refresh_token", s.refreshToken) }.toBody())
                .build()
            http.newCall(req).execute().use { it.code to it.body?.string() }
        } catch (e: IOException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null // 網址或金鑰裡有 HTTP 不接受的字元：當作暫時連不上，不要登出
        }
        if (code in 200..299) {
            val fresh = runCatching { Api.json.decodeFromString(GoTrueSession.serializer(), body ?: "").toSession(s.email) }.getOrNull() ?: return null
            current = fresh
            onChange(fresh)
            return fresh
        }
        if (Errors.isDeadRefresh(code, body)) {
            current = null
            onExpired()
        }
        return null
    }
}

/**
 * 請求本身就有問題（例如金鑰混進全形字，OkHttp 不接受當標頭）。
 * Interceptor 裡丟出 IOException 以外的例外，會在 OkHttp 的背景執行緒讓整個 App 當掉，所以一律包成這個。
 */
class InvalidRequestException(cause: Throwable) : IOException("invalid request: ${cause.message}", cause)

/** 包一層：Interceptor 裡發生的任何例外都變成 IOException，由呼叫的地方顯示錯誤，不會讓 App 當掉 */
internal fun safeInterceptor(block: (Interceptor.Chain) -> okhttp3.Response) = Interceptor { chain ->
    try {
        block(chain)
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        throw InvalidRequestException(e)
    }
}

/** 共用的連線池 */
private val sharedHttp: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .build()

/**
 * 連到一個 Supabase 專案：[api] 是資料操作，其餘是登入相關。
 * 每個請求都帶 apikey；登入後再帶 Authorization: Bearer <access token>，遇到 401 先換新 token 再重試一次。
 */
class SupabaseClient(val url: String, val key: String, val session: SessionManager?) {
    private val keyOnly = sharedHttp.newBuilder()
        .addInterceptor(safeInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("apikey", key).build()) })
        .build()

    private val authed = keyOnly.newBuilder()
        .addInterceptor(safeInterceptor { chain ->
            // 沒登入時：舊式 anon key 本身就是 JWT，可以當 Bearer；新式 publishable key 只放 apikey
            val token = session?.validToken() ?: key.takeIf(Supabase::isJwt)
            val req = chain.request().newBuilder().apply { if (token != null) header("Authorization", "Bearer $token") }.build()
            chain.proceed(req)
        })
        .authenticator(Authenticator { _, response ->
            val s = session ?: return@Authenticator null
            if (response.priorResponse != null) return@Authenticator null // 已經重試過一次
            val sent = response.request.header("Authorization")?.removePrefix("Bearer ") ?: return@Authenticator null
            val fresh = s.refreshBlocking(sent) ?: return@Authenticator null
            response.request.newBuilder().header("Authorization", "Bearer ${fresh.accessToken}").build()
        })
        .build()

    private fun retrofit(client: OkHttpClient) = Retrofit.Builder().baseUrl("$url/").client(client).build()

    private val auth: GoTrueService = retrofit(keyOnly).create(GoTrueService::class.java)
    val api = Api(retrofit(authed).create(RestService::class.java))

    private fun parseSession(body: ResponseBody, email: String): Session =
        Api.json.decodeFromString(GoTrueSession.serializer(), body.string()).toSession(email)
            // Confirm email 沒關：註冊成功但沒有拿到登入狀態
            ?: throw AppException(Errors.CONFIRM_EMAIL, "email_confirmation_on")

    suspend fun signUp(email: String, password: String, displayName: String): Session = parseSession(
        auth.signUp(buildJsonObject {
            put("email", email); put("password", password)
            putJsonObject("data") { put("display_name", displayName) }
        }.toBody()),
        email,
    )

    suspend fun signIn(email: String, password: String): Session =
        parseSession(auth.signIn(buildJsonObject { put("email", email); put("password", password) }.toBody()), email)

    suspend fun updatePassword(accessToken: String, password: String) {
        auth.updateUser("Bearer $accessToken", buildJsonObject { put("password", password) }.toBody()).close()
    }

    /** 登出這支手機（只撤銷這個登入，不影響別的裝置） */
    suspend fun signOut(accessToken: String) {
        auth.signOut("Bearer $accessToken")
    }

    /**
     * 登出這支手機的登入。手機睡了一整晚的話 access token 早就過期了，GoTrue 會拒絕過期的 token，
     * 登入就不會真的被撤銷，所以先在 [refreshTimeoutMs] 內換一個新的；換不到（沒網路、太慢）就用原本的試試看。
     * refresh token 已經失效的話，這個登入本來就沒了，不用再登出。
     */
    suspend fun logout(refreshTimeoutMs: Long = 5_000) {
        val sm = session ?: return
        if (sm.current == null) return
        // 換新是同步的 OkHttp 呼叫：放在另外的工作裡，時間到就不等了
        val refreshing = CoroutineScope(Dispatchers.IO).async { runCatching { sm.validToken(LOGOUT_MIN_VALIDITY_SEC) }.getOrNull() }
        val fresh = withTimeoutOrNull(refreshTimeoutMs) { refreshing.await() }
        val token = fresh ?: sm.current?.accessToken ?: return
        signOut(token)
    }

    companion object {
        /** 登出時 access token 至少還要有效這麼久（秒），不夠就先換新 */
        const val LOGOUT_MIN_VALIDITY_SEC = 30L
    }
}
