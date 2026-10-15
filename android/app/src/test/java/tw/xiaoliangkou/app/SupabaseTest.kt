package tw.xiaoliangkou.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import tw.xiaoliangkou.app.data.Api
import tw.xiaoliangkou.app.data.AppException
import tw.xiaoliangkou.app.data.Errors
import tw.xiaoliangkou.app.data.GoTrueSession
import tw.xiaoliangkou.app.data.InviteLink
import tw.xiaoliangkou.app.data.RealtimeClient
import tw.xiaoliangkou.app.data.RealtimeClient.Event
import tw.xiaoliangkou.app.data.RealtimeClient.Frame
import tw.xiaoliangkou.app.data.Session
import tw.xiaoliangkou.app.data.SessionManager
import tw.xiaoliangkou.app.data.Supabase
import tw.xiaoliangkou.app.data.SupabaseClient
import tw.xiaoliangkou.app.data.apiCall
import tw.xiaoliangkou.app.data.toInstantOrNull
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.time.Instant
import java.util.Base64
import java.util.Collections

/** Supabase 相關的小零件：時間格式、錯誤訊息、邀請連結、Realtime 訊息、登入狀態換新 */
class SupabaseTest {
    // ---------------------------------------------------------------- 時間
    @Test fun parsesDatabaseAndAppTimestamps() {
        val t = Instant.parse("2026-10-15T02:16:21.531234Z")
        assertEquals(t, "2026-10-15T02:16:21.531234+00:00".toInstantOrNull())
        assertEquals(t, "2026-10-15T02:16:21.531234Z".toInstantOrNull())
        assertEquals(t, "2026-10-15T10:16:21.531234+08:00".toInstantOrNull())
        assertEquals(t, "2026-10-15 02:16:21.531234+00".toInstantOrNull())
        assertEquals(Instant.parse("2026-10-15T02:16:21Z"), "2026-10-15T02:16:21+00:00".toInstantOrNull())
        assertEquals(Instant.parse("2026-10-15T02:16:21Z"), "2026-10-15T02:16:21Z".toInstantOrNull())
        assertEquals(Instant.parse("2026-10-15T02:16:21.123456789Z"), "2026-10-15T02:16:21.123456789+00:00".toInstantOrNull())
        assertEquals(Instant.parse("2026-10-15T02:16:21.500Z"), "2026-10-15T02:16:21.5Z".toInstantOrNull())
        assertNull("".toInstantOrNull())
        assertNull("昨天".toInstantOrNull())
        assertNull("2026-10-15".toInstantOrNull())
    }

    // ---------------------------------------------------------------- 錯誤訊息
    @Test fun mapsDatabaseErrors() {
        fun pg(code: String, message: String, status: Int = 400) =
            Errors.from(status, """{"code":"$code","details":null,"hint":null,"message":"$message"}""")

        pg("P0001", "not_paired").let { assertEquals("還沒有配對", it.message); assertEquals("not_paired", it.code); assertEquals(400, it.status) }
        assertEquals("配對碼無效或已過期，請對方重新產生", pg("P0001", "invite_invalid").message)
        assertEquals("傳太快了，休息一下再傳", pg("P0001", "rate_limited").message)
        pg("PT404", "not_found", 404).let { assertEquals("not_found", it.code); assertEquals(404, it.status) }
        assertEquals("something_new", pg("P0001", "something_new").message)
        assertEquals("forbidden", pg("42501", "permission denied for table moods", 403).code)
        assertEquals("沒有權限（對方可能沒有分享這項資料）", pg("42501", "new row violates row-level security policy", 403).message)
        assertEquals("not_authenticated", pg("42501", "permission denied for function app_me", 401).code)
        assertEquals("conflict", pg("23505", "duplicate key value", 409).code)
        assertEquals("invalid_reference", pg("23503", "violates foreign key constraint", 409).code)
        for (c in listOf("23502", "23514", "22P02", "22007", "22008", "22003", "22023", "2201B")) {
            assertEquals(c, "輸入的資料格式不正確", pg(c, "bad").message)
        }
        // setup.sql 還沒執行：PostgREST 找不到函式
        assertEquals("not_setup", pg("PGRST202", "Could not find the function public.app_me", 404).code)
        assertEquals("not_authenticated", pg("PGRST303", "JWT expired", 401).code)
        // API Gateway：金鑰錯誤
        Errors.from(401, """{"message":"Invalid API key","hint":"Double check your Supabase `anon` or `service_role` API key."}""").let {
            assertEquals("bad_key", it.code)
        }
        assertEquals("server_error", Errors.from(503, "<html>Service Unavailable</html>").code)
    }

    @Test fun mapsAuthErrors() {
        fun gt(status: Int, code: String, msg: String) = Errors.from(status, """{"code":$status,"error_code":"$code","msg":"$msg"}""")

        assertEquals("Email 或密碼不正確", gt(400, "invalid_credentials", "Invalid login credentials").message)
        assertEquals("這個 Email 已經註冊過了", gt(422, "user_already_exists", "User already registered").message)
        assertEquals("這個 Email 已經註冊過了", gt(422, "email_exists", "Email address already exists").message)
        gt(500, "unexpected_failure", "Database error saving new user").let {
            assertEquals("這個小倆口已經有兩個人了，或資料庫還沒設定好", it.message)
            assertEquals("registration_closed", it.code)
        }
        // 較新的 GoTrue 直接轉傳資料庫的錯誤
        Errors.from(500, """{"code":"P0001","message":"registration_closed"}""").let {
            assertEquals("這個小倆口已經有兩個人了，請用原本的 Email 登入", it.message)
            assertEquals("registration_closed", it.code)
        }
        assertEquals("密碼至少要 6 個字", gt(422, "weak_password", "Password should be at least 6 characters.").message)
        assertEquals("email_confirmation_on", gt(400, "email_not_confirmed", "Email not confirmed").code)
        assertEquals("invalid_email", gt(400, "validation_failed", "Unable to validate email address: invalid format").code)
        assertEquals("rate_limited", gt(429, "over_request_rate_limit", "Request rate limit reached").code)
        assertEquals("not_authenticated", gt(400, "refresh_token_not_found", "Invalid Refresh Token: Refresh Token Not Found").code)
        // 舊版 GoTrue 的格式
        assertEquals("Email 或密碼不正確", Errors.from(400, """{"error":"invalid_grant","error_description":"Invalid login credentials"}""").message)

        assertTrue(Errors.isDeadRefresh(400, """{"error_code":"refresh_token_not_found"}"""))
        assertTrue(Errors.isDeadRefresh(401, """{"error_code":"session_not_found"}"""))
        assertFalse(Errors.isDeadRefresh(401, """{"message":"Invalid API key"}"""))
        assertFalse(Errors.isDeadRefresh(429, """{"error_code":"over_request_rate_limit"}"""))
        assertFalse(Errors.isDeadRefresh(502, "<html>Bad Gateway</html>"))
    }

    @Test fun apiCallTurnsFailuresIntoChinese() = runBlocking {
        val http = HttpException(Response.error<Any>(400, """{"code":"P0001","message":"already_paired"}""".toResponseBody()))
        expect("已經和別人配對了，請先解除配對") { apiCall { throw http } }
        expect("連不到 Supabase，請確認網路") { apiCall { throw IOException("timeout") } }
    }

    @Test fun setupHints() {
        assertEquals("bad_key", Supabase.setupHint(Errors.from(401, """{"message":"Invalid API key"}""")).code)
        assertEquals("not_setup", Supabase.setupHint(Errors.from(404, """{"code":"PGRST202","message":"Could not find the function public.app_health"}""")).code)
        assertEquals("offline", Supabase.setupHint(AppException("x", "offline")).code)
        // 網址貼錯：404 但不是 PostgREST 的「找不到函式」，或回傳的根本不是 JSON（網頁）
        Supabase.setupHint(Errors.from(404, "<html>Not Found</html>")).let { assertEquals("wrong_url", it.code); assertEquals(Supabase.WRONG_URL, it.message) }
        assertEquals("wrong_url", Supabase.setupHint(Errors.from(404, "{}")).code)
        assertEquals("wrong_url", Supabase.setupHint(AppException("x", "not_json", null)).code)
    }

    @Test fun htmlHealthResponseMeansWrongUrl() = runBlocking {
        // 例如貼了 supabase.com 的後台網址：回 200，但內容是網頁
        route("/rest/v1/rpc/app_health") { _ -> 200 to "<!DOCTYPE html><html><head><title>Supabase</title></head><body>dashboard</body></html>" }
        val e = try {
            apiCall { SupabaseClient(base, "sb_publishable_test", null).api.health() }
            null
        } catch (e: AppException) { e }
        assertEquals("not_json", e!!.code)
        assertEquals("wrong_url", Supabase.setupHint(e).code)
        // 真的 JSON 但格式不對，還是「setup.sql 不是最新的」
        route("/rest/v1/rpc/app_me") { _ -> 200 to """{"user":"nope"}""" }
        expect("Supabase 回傳的資料格式不對，資料庫可能還不是最新的 setup.sql") { apiCall { SupabaseClient(base, "sb_publishable_test", null).api.me() } }
    }

    // ---------------------------------------------------------------- 網址、金鑰、邀請連結
    @Test fun normalizesUrlsAndKeys() {
        assertEquals("https://abcd.supabase.co", Supabase.normalizeUrl(" abcd.supabase.co/ "))
        assertEquals("https://abcd.supabase.co", Supabase.normalizeUrl("http://abcd.supabase.co/rest/v1/"))
        assertEquals("http://192.168.1.5:54321", Supabase.normalizeUrl("http://192.168.1.5:54321/"))
        assertEquals("sb_publishable_abc", Supabase.normalizeKey(" sb_publishable_\nabc "))
        // 零寬空白、BOM、方向控制字元等看不到的字元也去掉
        assertEquals("sb_publishable_abc", Supabase.normalizeKey("\uFEFF sb_publishable_\u200Babc\u2060\u200E "))
        assertEquals("https://abcd.supabase.co", Supabase.normalizeUrl("\u200Bhttps://abcd.supabase.co\uFEFF"))

        // 瀏覽器網址列上的後台網址 → 專案的 Project URL
        val ref = "abcdefghijklmnopqrst"
        assertEquals(ref, Supabase.dashboardRef("https://supabase.com/dashboard/project/$ref"))
        assertNull(Supabase.dashboardRef("https://$ref.supabase.co"))
        for (raw in listOf(
            "https://supabase.com/dashboard/project/$ref",
            "https://supabase.com/dashboard/project/$ref/settings/api-keys",
            " supabase.com/dashboard/project/$ref?showConnect=true ",
            "https://app.supabase.com/project/$ref/editor",
        )) assertEquals(raw, "https://$ref.supabase.co", Supabase.normalizeUrl(raw))
        // 其他後台頁面、看不懂的網址：測試連線前就擋下
        assertTrue(Supabase.urlProblem(Supabase.normalizeUrl("https://supabase.com/dashboard/projects"))!!.contains("Data API"))
        assertTrue(Supabase.urlProblem(Supabase.normalizeUrl("https://例子.tw")) != null)
        assertNull(Supabase.urlProblem("https://abcd.supabase.co"))
        assertNull(Supabase.urlProblem("http://10.0.2.2:54321"))
        assertTrue(Supabase.isOfficialHost(Supabase.hostOf("https://abcd.supabase.co/rest/v1")))
        assertFalse(Supabase.isOfficialHost(Supabase.hostOf("https://abcd.supabase.co.evil.example")))
        assertEquals("10.0.2.2", Supabase.hostOf("http://10.0.2.2:54321"))

        fun jwt(role: String) = "eyJhbGciOiJIUzI1NiJ9." +
            Base64.getUrlEncoder().withoutPadding().encodeToString("""{"role":"$role","iss":"supabase"}""".toByteArray()) + ".sig"
        assertTrue(Supabase.isJwt(jwt("anon")))
        assertFalse(Supabase.isJwt("sb_publishable_abc"))
        assertTrue(Supabase.isSecretKey("sb_secret_abc"))
        assertTrue(Supabase.isSecretKey(jwt("service_role")))
        assertFalse(Supabase.isSecretKey(jwt("anon")))
        assertFalse(Supabase.isSecretKey("sb_publishable_abc"))

        // 金鑰會放進 HTTP 標頭：只接受一般的英數字與符號，而且要像 Supabase 的金鑰
        assertNull(Supabase.keyProblem("sb_publishable_abc"))
        assertNull(Supabase.keyProblem("sb_publishable_ACJWlzQHlZjBrEguHvfOxg_3BJgxAaH"))
        assertNull(Supabase.keyProblem(jwt("anon")))
        assertEquals(Supabase.SECRET_KEY, Supabase.keyProblem("sb_secret_abc"))
        assertEquals(Supabase.SECRET_KEY, Supabase.keyProblem(jwt("service_role")))
        for (bad in listOf("sb_publishable_ab…cd", "ｓｂ_publishable_abc", "sb_publishable_ab\u200Bcd", "sb_publishable_小倆口")) {
            assertTrue(bad, Supabase.keyProblem(bad)!!.contains("全形字"))
        }
        assertTrue(Supabase.keyProblem("hello-world-key")!!.contains("不是 Supabase 的金鑰"))
        assertTrue(Supabase.keyProblem("sb_publishable_")!!.contains("不是 Supabase 的金鑰"))
        assertTrue(Supabase.keyProblem("")!!.contains("請貼上"))
    }

    @Test fun inviteLinkRoundTrip() {
        val url = "https://abcd.supabase.co"
        val key = "eyJhbGciOiJIUzI1NiJ9.eyJyb2xlIjoiYW5vbiJ9.a+b/c="
        val link = InviteLink.build(url, key)
        assertTrue(link, link.startsWith("xiaoliangkou://connect?url=https%3A%2F%2Fabcd.supabase.co&key="))
        assertEquals(url to key, InviteLink.parse(link))
        // 整段分享訊息貼上也看得懂
        assertEquals(url to key, InviteLink.parse(InviteLink.shareText(url, key)))
        assertEquals(url to "sb_publishable_x1y2z3", InviteLink.parse("xiaoliangkou://connect/?key=sb_publishable_x1y2z3&url=https%3A%2F%2Fabcd.supabase.co%2F"))
        assertEquals("http://10.0.2.2:54321" to "sb_publishable_local", InviteLink.parse("XiaoLiangKou://connect?url=http%3A%2F%2F10.0.2.2%3A54321&key=sb_publishable_local"))

        assertNull(InviteLink.parse("https://abcd.supabase.co"))
        assertNull(InviteLink.parse("xiaoliangkou://connect?url=https%3A%2F%2Fabcd.supabase.co"))
        assertNull(InviteLink.parse("xiaoliangkou://connect?url=javascript%3Aalert(1)&key=sb_publishable_x1y2z3"))
        assertNull(InviteLink.parse("xiaoliangkou://connect?url=https%3A%2F%2Fabcd.supabase.co&key=short"))

        // 看不到的字元去掉；全形字、刪節號、secret 金鑰一律看不懂
        assertEquals(
            "https://abc.supabase.co" to "sb_publishable_abcdef",
            InviteLink.parse("xiaoliangkou://connect?url=https%3A%2F%2Fabc.supabase.co&key=sb_publishable_%E2%80%8Babcdef"),
        )
        assertNull(InviteLink.parse("xiaoliangkou://connect?url=https%3A%2F%2Fabc.supabase.co&key=sb_publishable_abc%E2%80%A6def"))
        assertNull(InviteLink.parse("xiaoliangkou://connect?url=https%3A%2F%2Fabc.supabase.co&key=%EF%BD%93b_publishable_abcdef"))
        assertNull(InviteLink.parse("xiaoliangkou://connect?url=https%3A%2F%2Fabc.supabase.co&key=sb_secret_abcdefghij"))
        assertNull(InviteLink.parse("xiaoliangkou://connect?url=https%3A%2F%2Fsupabase.com%2Fdashboard%2Fprojects&key=sb_publishable_abcdef"))
        // 後台網址也換成 Project URL
        assertEquals(
            "https://abcdefghijklmnopqrst.supabase.co" to "sb_publishable_abcdef",
            InviteLink.parse("xiaoliangkou://connect?url=https%3A%2F%2Fsupabase.com%2Fdashboard%2Fproject%2Fabcdefghijklmnopqrst&key=sb_publishable_abcdef"),
        )
    }

    @Test fun inviteNeedsConfirmationWithWarnings() {
        // 官方網址、手機上還沒有設定：不用特別提醒
        InviteLink.review("https://abcd.supabase.co", "", demo = false).let {
            assertEquals("abcd.supabase.co", it.host)
            assertTrue(it.official)
            assertEquals(emptyList<String>(), it.warnings)
        }
        assertEquals(emptyList<String>(), InviteLink.review("https://abcd.supabase.co", "https://abcd.supabase.co", demo = false).warnings)
        // 陌生的網址、跟手機上存的不一樣、在示範模式：全部都要說
        InviteLink.review("https://attacker.example", "https://abcd.supabase.co", demo = true).let {
            assertEquals("attacker.example", it.host)
            assertFalse(it.official)
            assertEquals(3, it.warnings.size)
            assertTrue(it.warnings[0], it.warnings[0].contains("不是 Supabase 官方的網址"))
            assertTrue(it.warnings[1], it.warnings[1].contains("abcd.supabase.co") && it.warnings[1].contains("密碼"))
            assertTrue(it.warnings[2], it.warnings[2].contains("示範模式"))
        }
        InviteLink.review("http://10.0.2.2:54321", null, demo = false).let {
            assertEquals("10.0.2.2", it.host)
            assertTrue(it.warnings.any { w -> "http" in w })
        }
    }

    // ---------------------------------------------------------------- Realtime（Phoenix 協定）
    @Test fun realtimeMessages() {
        assertEquals(
            "wss://abcd.supabase.co/realtime/v1/websocket?apikey=sb_publishable_x&vsn=1.0.0",
            RealtimeClient.socketUrl("https://abcd.supabase.co/", "sb_publishable_x"),
        )
        assertEquals(
            "ws://127.0.0.1:54321/realtime/v1/websocket?apikey=a%2Bb&vsn=1.0.0",
            RealtimeClient.socketUrl("http://127.0.0.1:54321", "a+b"),
        )

        val join = Api.json.parseToJsonElement(RealtimeClient.joinMessage("u-1", "jwt-1", "1")).jsonObject
        assertEquals("realtime:xlk-u-1", join["topic"]!!.jsonPrimitive.content)
        assertEquals("phx_join", join["event"]!!.jsonPrimitive.content)
        assertEquals("1", join["ref"]!!.jsonPrimitive.content)
        assertEquals("1", join["join_ref"]!!.jsonPrimitive.content)
        val payload = join["payload"]!!.jsonObject
        assertEquals("jwt-1", payload["access_token"]!!.jsonPrimitive.content)
        val changes = payload["config"]!!.jsonObject["postgres_changes"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("notification_outbox", "realtime_events"), changes.map { it["table"]!!.jsonPrimitive.content })
        assertEquals(listOf("recipient_id=eq.u-1", "user_id=eq.u-1"), changes.map { it["filter"]!!.jsonPrimitive.content })
        assertTrue(changes.all { it["event"]!!.jsonPrimitive.content == "INSERT" && it["schema"]!!.jsonPrimitive.content == "public" })

        val hb = Api.json.parseToJsonElement(RealtimeClient.heartbeatMessage("7")).jsonObject
        assertEquals("phoenix", hb["topic"]!!.jsonPrimitive.content)
        assertEquals("heartbeat", hb["event"]!!.jsonPrimitive.content)
        val at = Api.json.parseToJsonElement(RealtimeClient.accessTokenMessage("u-1", "jwt-2", "8")).jsonObject
        assertEquals("access_token", at["event"]!!.jsonPrimitive.content)
        assertEquals("jwt-2", at["payload"]!!.jsonObject["access_token"]!!.jsonPrimitive.content)
    }

    @Test fun parsesRealtimeFrames() {
        assertEquals(
            Frame.Reply("1", true),
            RealtimeClient.parse("""{"event":"phx_reply","payload":{"response":{"postgres_changes":[{"id":1,"event":"INSERT","schema":"public","table":"notification_outbox"}]},"status":"ok"},"ref":"1","topic":"realtime:xlk-u"}"""),
        )
        assertEquals(
            Frame.Reply("1", false),
            RealtimeClient.parse("""{"event":"phx_reply","payload":{"response":{"reason":"Invalid JWT"},"status":"error"},"ref":"1","topic":"realtime:xlk-u"}"""),
        )
        assertEquals(Frame.Reply("9", true), RealtimeClient.parse("""{"event":"phx_reply","payload":{"response":{},"status":"ok"},"ref":"9","topic":"phoenix"}"""))
        assertEquals(
            Frame.System(true, "Subscribed to PostgreSQL"),
            RealtimeClient.parse("""{"event":"system","payload":{"channel":"xlk-u","extension":"postgres_changes","message":"Subscribed to PostgreSQL","status":"ok"},"ref":null,"topic":"realtime:xlk-u"}"""),
        )

        val outbox = """{"event":"postgres_changes","payload":{"data":{"columns":[],"commit_timestamp":"2026-10-15T02:16:21.531Z","errors":null,
            "record":{"id":42,"recipient_id":"u","kind":"comfort","title":"小雨 給你一個抱抱","body":"晚上請你吃好料","data":{"comfortId":7},"created_at":"2026-10-15T02:16:21.531234+00:00","sent_at":null},
            "schema":"public","table":"notification_outbox","type":"INSERT"},"ids":[123]},"ref":null,"topic":"realtime:xlk-u"}"""
        val n = (RealtimeClient.parse(outbox) as Frame.Change).event as Event.Notification
        assertEquals(42L, n.id)
        assertEquals("comfort", n.kind)
        assertEquals("小雨 給你一個抱抱", n.title)
        assertEquals("晚上請你吃好料", n.body)
        assertEquals("7", n.data!!["comfortId"]!!.jsonPrimitive.content)

        // jsonb 偶爾以字串傳來、body 為 null
        val str = RealtimeClient.parse(change("notification_outbox", """{"id":"43","kind":"mood","title":"心情","body":null,"data":"{\"mood\":\"sad\"}"}""")) as Frame.Change
        assertEquals(Event.Notification(43, "mood", "心情", "", JsonObject(mapOf("mood" to kotlinx.serialization.json.JsonPrimitive("sad")))), str.event)
        // 內容不完整（例如太大被截掉）：要用 RPC 補抓
        assertEquals(Frame.Change(null), RealtimeClient.parse(change("notification_outbox", """{"id":44}""")))

        assertEquals(
            Frame.Change(Event.Changed("moods", "u-2")),
            RealtimeClient.parse(change("realtime_events", """{"id":1,"user_id":"u","kind":"changed","table_name":"moods","op":"INSERT","by_user":"u-2","created_at":"2026-10-15T02:16:21+00:00"}""")),
        )
        assertEquals(Frame.Change(Event.Locate), RealtimeClient.parse(change("realtime_events", """{"id":2,"user_id":"u","kind":"locate","table_name":null,"op":null,"by_user":"u-2"}""")))
        assertEquals(Frame.Ignore, RealtimeClient.parse(change("realtime_events", """{"id":3,"kind":"party"}""")))
        assertEquals(Frame.Ignore, RealtimeClient.parse(change("moods", """{"id":3}""")))
        assertEquals(Frame.Closed, RealtimeClient.parse("""{"event":"phx_close","payload":{},"ref":"1","topic":"realtime:xlk-u"}"""))
        assertEquals(Frame.Closed, RealtimeClient.parse("""{"event":"phx_error","payload":{},"ref":"1","topic":"realtime:xlk-u"}"""))
        assertEquals(Frame.Ignore, RealtimeClient.parse("""{"event":"presence_state","payload":{},"ref":null,"topic":"realtime:xlk-u"}"""))
        assertEquals(Frame.Ignore, RealtimeClient.parse("not json"))
    }

    private fun change(table: String, record: String) =
        """{"event":"postgres_changes","payload":{"data":{"record":$record,"schema":"public","table":"$table","type":"INSERT"},"ids":[1]},"ref":null,"topic":"realtime:xlk-u"}"""

    // ---------------------------------------------------------------- 登入狀態：快過期或 401 時換新 token
    /** 假的 Supabase：極簡 HTTP 伺服器（每個連線只處理一個請求） */
    private class Req(val target: String, val headers: Map<String, String>, val body: String) {
        fun header(name: String) = headers[name.lowercase()]
    }
    private val routes = mutableMapOf<String, (Req) -> Pair<Int, String>>()
    private val seen: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val base = "http://127.0.0.1:${server.localPort}"
    private val serverThread = Thread {
        while (!server.isClosed) {
            val sock = try { server.accept() } catch (e: SocketException) { break }
            sock.use {
                val input = it.getInputStream().buffered()
                fun line() = generateSequence { input.read().takeIf { b -> b >= 0 } }.takeWhile { b -> b != '\n'.code }
                    .map { b -> b.toByte() }.toList().toByteArray().decodeToString().trimEnd('\r')
                val target = line().split(' ').getOrElse(1) { "/" }
                val headers = generateSequence { line().takeIf { l -> l.isNotEmpty() } }
                    .associate { l -> l.substringBefore(':').trim().lowercase() to l.substringAfter(':').trim() }
                val body = ByteArray(headers["content-length"]?.toInt() ?: 0).also { b -> var n = 0; while (n < b.size) n += input.read(b, n, b.size - n) }.decodeToString()
                seen += "$target apikey=${headers["apikey"]} auth=${headers["authorization"]}"
                val handler = routes[target.substringBefore('?')]
                val (status, out) = handler?.invoke(Req(target, headers, body)) ?: (404 to "{}")
                val bytes = out.toByteArray()
                it.getOutputStream().apply {
                    write("HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(bytes)
                    flush()
                }
            }
        }
    }.also { it.isDaemon = true; it.start() }

    @After fun stopServer() = server.close()

    private fun route(path: String, handler: (Req) -> Pair<Int, String>) { routes[path] = handler }

    private fun session(access: String, expiresAt: Long = System.currentTimeMillis() / 1000 + 3600) = Session(access, "r-1", expiresAt, "u-1", "azhe@example.com")

    private val me = """{"user":{"id":"u-1","displayName":"阿哲","email":"azhe@example.com"},"partnerOnline":false}"""
    private val refreshed = """{"access_token":"a-2","token_type":"bearer","expires_in":3600,"refresh_token":"r-2","user":{"id":"u-1","email":"azhe@example.com"}}"""

    @Test fun refreshesOn401AndRetriesOnce() = runBlocking {
        route("/rest/v1/rpc/app_me") { r ->
            if (r.header("Authorization") == "Bearer a-2") 200 to me
            else 401 to """{"code":"PGRST303","details":null,"hint":null,"message":"JWT expired"}"""
        }
        route("/auth/v1/token") { r ->
            assertTrue(r.body, r.body.contains("\"refresh_token\":\"r-1\""))
            200 to refreshed
        }
        val sm = SessionManager(base, "sb_publishable_test", session("a-1"))
        val changed = mutableListOf<Session>()
        sm.onChange = { changed += it }
        val r = apiCall { SupabaseClient(base, "sb_publishable_test", sm).api.me() }
        assertEquals("azhe@example.com", r.user.email)
        assertEquals("a-2", sm.current!!.accessToken)
        assertEquals("r-2", sm.current!!.refreshToken)
        assertEquals(listOf("a-2"), changed.map { it.accessToken })
        assertEquals(
            listOf(
                "/rest/v1/rpc/app_me apikey=sb_publishable_test auth=Bearer a-1",
                "/auth/v1/token?grant_type=refresh_token apikey=sb_publishable_test auth=null",
                "/rest/v1/rpc/app_me apikey=sb_publishable_test auth=Bearer a-2",
            ),
            seen.toList(),
        )
    }

    @Test fun refreshesShortlyBeforeExpiry() = runBlocking {
        route("/rest/v1/rpc/app_me") { r -> if (r.header("Authorization") == "Bearer a-2") 200 to me else 401 to "{}" }
        route("/auth/v1/token") { _ -> 200 to refreshed }
        val sm = SessionManager(base, "k", session("a-1", expiresAt = System.currentTimeMillis() / 1000 + 20))
        apiCall { SupabaseClient(base, "k", sm).api.me() }
        assertEquals(listOf("/auth/v1/token?grant_type=refresh_token", "/rest/v1/rpc/app_me"), seen.map { it.substringBefore(' ') })
        assertEquals("a-2", sm.freshToken())
    }

    @Test fun revokedRefreshTokenLogsOut() = runBlocking {
        route("/rest/v1/rpc/app_me") { _ -> 401 to """{"code":"PGRST303","message":"JWT expired"}""" }
        route("/auth/v1/token") { _ -> 400 to """{"code":400,"error_code":"refresh_token_not_found","msg":"Invalid Refresh Token: Refresh Token Not Found"}""" }
        val sm = SessionManager(base, "k", session("a-1"))
        var expired = 0
        sm.onExpired = { expired++ }
        expect(Errors.RELOGIN) { apiCall { SupabaseClient(base, "k", sm).api.me() } }
        assertEquals(1, expired)
        assertNull(sm.current)
    }

    @Test fun networkTroubleKeepsSession() {
        // 換新時連不到：保留原本的登入，不要登出
        val sm = SessionManager("http://127.0.0.1:1", "k", session("a-1", expiresAt = 0))
        var expired = 0
        sm.onExpired = { expired++ }
        assertEquals("a-1", sm.validToken())
        assertEquals(0, expired)
    }

    @Test fun signUpWithoutSessionMeansConfirmEmailIsOn() = runBlocking {
        route("/auth/v1/signup") { r ->
            assertTrue(r.body, r.body.contains("\"display_name\":\"阿哲\""))
            200 to """{"id":"u-9","aud":"authenticated","role":"","email":"azhe@example.com","confirmation_sent_at":"2026-10-15T02:16:21Z"}"""
        }
        expect(Errors.CONFIRM_EMAIL) { apiCall { SupabaseClient(base, "k", null).signUp("azhe@example.com", "secret1", "阿哲") } }
    }

    @Test fun signInMapsErrorsAndParsesSession() = runBlocking {
        route("/auth/v1/token") { r ->
            if (r.body.contains("secret1")) 200 to refreshed
            else 400 to """{"code":400,"error_code":"invalid_credentials","msg":"Invalid login credentials"}"""
        }
        val c = SupabaseClient(base, "k", null)
        val s = apiCall { c.signIn("azhe@example.com", "secret1") }
        assertEquals("u-1", s.userId)
        assertEquals("azhe@example.com", s.email)
        assertTrue(s.expiresAt > System.currentTimeMillis() / 1000 + 3500)
        expect("Email 或密碼不正確") { apiCall { c.signIn("azhe@example.com", "wrong!") } }
        assertTrue(seen.all { "apikey=k" in it })
    }

    @Test fun goTrueSessionNeedsTokens() {
        assertNull(GoTrueSession(accessToken = "a").toSession())
        assertNull(GoTrueSession("a", "r", 60, null).toSession())
        // 沒有 user 時從 JWT 的 sub 取得使用者 id
        val jwt = "eyJhbGciOiJIUzI1NiJ9." + Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"u-7"}""".toByteArray()) + ".sig"
        val s = GoTrueSession(jwt, "r", 60, null).toSession("me@example.com", nowSec = 1000)!!
        assertEquals(Session(jwt, "r", 1060, "u-7", "me@example.com"), s)
    }

    // ---------------------------------------------------------------- 金鑰有怪字元：不能讓 App 當掉
    @Test fun badHeaderCharactersBecomeFriendlyErrors() = runBlocking {
        route("/rest/v1/rpc/app_me") { _ -> 200 to me }
        // OkHttp 不接受非 ASCII 的標頭：以前會在背景執行緒丟例外讓 App 當掉，現在變成可以顯示的錯誤
        val weird = "sb_publishable_ab…cd"
        val e = try {
            withTimeout(10_000) { apiCall { SupabaseClient(base, weird, null).api.me() } }
            null
        } catch (e: AppException) { e }
        assertEquals("bad_key", e!!.code)
        // 登入後也一樣（換新 token 失敗不會丟例外，也不會被當成要重新登入）
        val sm = SessionManager(base, weird, session("a-1", expiresAt = 0))
        var expired = 0
        sm.onExpired = { expired++ }
        assertEquals("a-1", sm.validToken())
        assertEquals(0, expired)
        expect("網址或金鑰裡有看不懂的字元，請到「連接 Supabase」重新貼一次 🔑") { withTimeout(10_000) { apiCall { SupabaseClient(base, weird, sm).api.me() } } }
        assertTrue(seen.toString(), seen.isEmpty())
    }

    // ---------------------------------------------------------------- 登出：token 過期了要先換新，登入才會真的被撤銷
    @Test fun logoutRefreshesExpiredTokenFirst() = runBlocking {
        route("/auth/v1/token") { _ -> 200 to refreshed }
        route("/auth/v1/logout") { _ -> 204 to "" }
        val sm = SessionManager(base, "k", session("a-1", expiresAt = 0))
        SupabaseClient(base, "k", sm).logout()
        assertEquals(
            listOf(
                "/auth/v1/token?grant_type=refresh_token apikey=k auth=null",
                "/auth/v1/logout?scope=local apikey=k auth=Bearer a-2",
            ),
            seen.toList(),
        )
    }

    @Test fun logoutFallsBackWhenRefreshFails() = runBlocking {
        // 換新暫時失敗（伺服器出錯）：用原本的 token 試試看
        route("/auth/v1/token") { _ -> 502 to "<html>Bad Gateway</html>" }
        route("/auth/v1/logout") { _ -> 204 to "" }
        SupabaseClient(base, "k", SessionManager(base, "k", session("a-1", expiresAt = 0))).logout()
        assertEquals("/auth/v1/logout?scope=local apikey=k auth=Bearer a-1", seen.last())

        // refresh token 已經失效：這個登入本來就沒了，不用再登出
        seen.clear()
        route("/auth/v1/token") { _ -> 400 to """{"error_code":"refresh_token_not_found","msg":"Invalid Refresh Token"}""" }
        SupabaseClient(base, "k", SessionManager(base, "k", session("a-1", expiresAt = 0))).logout()
        assertEquals(listOf("/auth/v1/token?grant_type=refresh_token"), seen.map { it.substringBefore(' ') })

        // token 還很新：直接登出
        seen.clear()
        SupabaseClient(base, "k", SessionManager(base, "k", session("a-9"))).logout()
        assertEquals(listOf("/auth/v1/logout?scope=local apikey=k auth=Bearer a-9"), seen.toList())
    }

    // ---------------------------------------------------------------- Realtime 連不上：改用 RPC 補抓通知
    @Test fun pollsPendingNotificationsWhileRealtimeIsDown() = runBlocking {
        val acked: MutableList<String> = Collections.synchronizedList(mutableListOf())
        route("/rest/v1/rpc/app_pending_notifications") { _ ->
            200 to """[{"id":6,"kind":"place","title":"小雨 到家了","body":null,"data":{"placeId":"p1"}},{"id":5,"kind":"comfort","title":"小雨 給你一個抱抱","body":"晚上請你吃好料"}]"""
        }
        route("/rest/v1/rpc/app_ack_notifications") { r -> acked += r.body; 200 to """{"acked":2}""" }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val rt = RealtimeClient(scope)
            assertFalse(rt.pollIfDisconnected()) // 還沒開始：什麼都不做
            val got: MutableList<Event> = Collections.synchronizedList(mutableListOf())
            scope.launch(start = CoroutineStart.UNDISPATCHED) { rt.events.collect { got += it } }
            // WebSocket 連不上（拿不到 token 就不連），但 API 可以用
            rt.start(base, "k", "u-1", { null }, SupabaseClient(base, "k", null).api)
            assertFalse(rt.connected.value)
            assertTrue(rt.pollIfDisconnected())
            withTimeout(5_000) { while (got.size < 2 || acked.isEmpty()) delay(20) }
            // 依 id 排序送出，第二次補抓到同樣的通知不會重複跳出來
            assertTrue(rt.pollIfDisconnected())
            delay(1_000)
            assertEquals(listOf(5L, 6L), got.map { (it as Event.Notification).id })
            assertEquals("晚上請你吃好料", (got[0] as Event.Notification).body)
            assertTrue(acked.toString(), acked.first().contains("5") && acked.first().contains("6"))
            rt.stop()
            assertFalse(rt.pollIfDisconnected())
        } finally {
            scope.cancel()
        }
    }

    private suspend fun expect(message: String, block: suspend () -> Unit) {
        try {
            block()
            fail("應該要失敗：$message")
        } catch (e: AppException) {
            assertEquals(message, e.message)
        }
    }
}
