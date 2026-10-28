package tw.xiaoliangkou.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.xiaoliangkou.app.data.Api
import tw.xiaoliangkou.app.data.AppException
import tw.xiaoliangkou.app.data.Errors
import tw.xiaoliangkou.app.data.PetResult
import tw.xiaoliangkou.app.data.RealtimeClient
import tw.xiaoliangkou.app.data.RemoteRepository
import tw.xiaoliangkou.app.data.Session
import tw.xiaoliangkou.app.data.SessionManager
import tw.xiaoliangkou.app.data.SupabaseClient
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 一起養的小貓（Supabase 模式）：載入、照顧、被規則擋下來時重新載入、舊的資料庫（schema 6 以前）先提醒更新。
 * 用假的 Supabase
 */
class PetRemoteTest {
    private class Req(val method: String, val path: String, val body: ByteArray) {
        val json: JsonObject get() = Api.json.parseToJsonElement(body.decodeToString().ifEmpty { "{}" }).jsonObject
    }
    private class Res(val status: Int, val body: ByteArray, val type: String = "application/json")

    /** 極簡 HTTP 伺服器：照「方法 路徑」找 handler，找不到就回 PGRST202（舊的資料庫沒有這個函式） */
    private val routes = ConcurrentHashMap<String, (Req) -> Res>()
    private val seen: MutableList<Req> = Collections.synchronizedList(mutableListOf())
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val base = "http://127.0.0.1:${server.localPort}"
    private val serverThread = Thread {
        while (!server.isClosed) {
            val sock = try { server.accept() } catch (e: SocketException) { break }
            sock.use {
                val input = it.getInputStream().buffered()
                fun line() = generateSequence { input.read().takeIf { b -> b >= 0 } }.takeWhile { b -> b != '\n'.code }
                    .map { b -> b.toByte() }.toList().toByteArray().decodeToString().trimEnd('\r')
                val (method, target) = line().split(' ').let { p -> p.getOrElse(0) { "GET" } to p.getOrElse(1) { "/" } }
                val headers = generateSequence { line().takeIf { l -> l.isNotEmpty() } }
                    .associate { l -> l.substringBefore(':').trim().lowercase() to l.substringAfter(':').trim() }
                val body = ByteArray(headers["content-length"]?.toInt() ?: 0).also { b -> var n = 0; while (n < b.size) n += input.read(b, n, b.size - n) }
                val req = Req(method, target.substringBefore('?'), body)
                seen += req
                val res = routes["$method ${req.path}"]?.invoke(req)
                    ?: Res(404, """{"code":"PGRST202","message":"Could not find the function public.${req.path.substringAfterLast('/')} without parameters in the schema cache"}""".toByteArray())
                it.getOutputStream().apply {
                    write("HTTP/1.1 ${res.status} X\r\nContent-Type: ${res.type}\r\nContent-Length: ${res.body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(res.body)
                    flush()
                }
            }
        }
    }.also { it.isDaemon = true; it.start() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val realtime = RealtimeClient(scope)

    @After fun tearDown() { scope.cancel(); server.close() }

    private fun rpc(name: String, status: Int = 200, body: (Req) -> String) { routes["POST /rest/v1/rpc/$name"] = { r -> Res(status, body(r).toByteArray()) } }
    private fun calls(name: String) = seen.toList().filter { it.path == "/rest/v1/rpc/$name" }

    private fun me(giphyKey: String? = null) =
        """{"user":{"id":"u-1","displayName":"阿哲"},"couple":{"id":"c-1","togetherSince":"2025-05-20"${giphyKey?.let { ",\"giphyKey\":\"$it\"" }.orEmpty()}},""" +
            """"partner":{"id":"u-2","displayName":"小雨"},"partnerOnline":false}"""

    /** 開 App：資料庫是 [schema] 版（null＝還沒有 schema 的 v1） */
    private suspend fun repo(schema: Int?, comforts: String = "[]", prefs: String = "{}"): RemoteRepository {
        rpc("app_me") { me() }
        rpc("app_health") { if (schema == null) """{"ok":true}""" else """{"ok":true,"schema":$schema}""" }
        rpc("app_moods") { "[]" }
        rpc("app_comforts") { comforts }
        for (f in listOf("app_notes", "app_memos", "app_day_entries", "app_places", "app_expenses", "app_partner_expenses", "app_anniversaries", "app_events", "app_history")) rpc(f) { "[]" }
        rpc("app_notification_prefs") { prefs }
        rpc("app_cycle") { "{}" }
        rpc("app_partner_status") { "{}" }
        val session = SessionManager(base, "sb_publishable_test", Session("a-1", "r-1", System.currentTimeMillis() / 1000 + 3600, "u-1", "azhe@example.com"))
        val r = RemoteRepository(SupabaseClient(base, "sb_publishable_test", session).api, realtime, scope) {}
        r.refreshAll()
        settle()
        return r
    }

    /** 等到沒有請求進來（建立時 init 也會自己跑一次 refreshAll） */
    private suspend fun settle() {
        var n = -1
        while (n != seen.size) { n = seen.size; delay(250) }
    }

    private suspend fun fails(code: String, block: suspend () -> Unit): AppException {
        try {
            block()
        } catch (e: AppException) {
            assertEquals(e.message, code, e.code)
            return e
        }
        throw AssertionError("應該要失敗：$code")
    }

    private val pet = """{"name":"麻糬","coat":"orange","fullness":66.5,"happiness":72,"cleanliness":61,"xp":168,"streak":12,""" +
        """"lastFedOn":"2026-10-28","bonusOn":null,"adoptedBy":"u-2","adoptedAt":"2026-10-17T12:00:00+00:00","updatedAt":"2026-10-28T02:00:00+00:00"}"""
    private val events = """[{"id":3,"userId":"u-2","action":"feed","item":"can","xp":12,"createdAt":"2026-10-28T02:00:00+00:00"}]"""
    private fun payload(p: String? = pet, result: String? = null) =
        """{"pet":${p ?: "null"},"today":{"feeds":1,"plays":0,"pets":2,"treats":0},"events":$events${result?.let { ",\"result\":$it" }.orEmpty()}}"""

    @Test fun loadsAndCaresForTheCat() = runBlocking<Unit> {
        rpc("app_pet") { payload() }
        rpc("app_pet_action") { payload(pet.replace("\"xp\":168", "\"xp\":200"), """{"xp":32,"bonus":true,"streakBonus":false,"grewTo":null}""") }
        val r = repo(RemoteRepository.PET_SCHEMA)
        val s = r.state.value
        assertTrue(s.petReady)
        assertFalse(s.petOutdated)
        val p = s.pet!!.pet!!
        assertEquals("麻糬", p.name)
        assertEquals(66.5, p.fullness, 0.0)
        assertEquals(2, s.pet!!.today.pets)
        assertEquals("can", s.pet!!.events.single().item)
        assertEquals(12, p.state().streak)

        val res = r.petAction("play", "laser")
        assertEquals(PetResult(32, bonus = true), res)
        assertEquals("""{"action":"play","item":"laser"}""", calls("app_pet_action").last().json.toString())
        assertEquals(200, r.state.value.pet!!.pet!!.xp)
        assertNull("回傳的 result 不放進 state", r.state.value.pet!!.result)
        r.petAction("pet", null)
        assertEquals("""{"action":"pet"}""", calls("app_pet_action").last().json.toString())
    }

    @Test fun refusalsReloadTheCat() = runBlocking<Unit> {
        rpc("app_pet") { payload() }
        rpc("app_pet_action", 400) { """{"code":"P0001","message":"pet_full","details":"牠吃不下了，晚點再餵","hint":null}""" }
        val r = repo(RemoteRepository.PET_SCHEMA)
        val before = calls("app_pet").size
        val e = fails("pet_full") { r.petAction("feed", "can") }
        assertEquals("牠吃不下了，晚點再餵", e.message)
        // 可能是對方剛餵過：重新載入
        assertEquals(before + 1, calls("app_pet").size)
    }

    @Test fun adoptingAndRenaming() = runBlocking<Unit> {
        rpc("app_pet") { payload(null) }
        rpc("app_adopt_pet") { payload(pet.replace("麻糬", "湯圓")) }
        rpc("app_rename_pet") { payload(pet.replace("麻糬", "大湯圓")) }
        val r = repo(RemoteRepository.PET_SCHEMA)
        assertNull("還沒領養", r.state.value.pet!!.pet)
        fails("invalid_input") { r.adoptPet("一二三四五六七八九十一二三", "orange") }
        fails("invalid_input") { r.adoptPet("   ", "orange") }
        assertTrue("名字不對不用問資料庫", calls("app_adopt_pet").isEmpty())
        r.adoptPet(" 湯圓 ", "white")
        assertEquals("""{"name":"湯圓","coat":"white"}""", calls("app_adopt_pet").single().json.toString())
        assertEquals("湯圓", r.state.value.pet!!.pet!!.name)
        r.renamePet("大湯圓")
        assertEquals("大湯圓", r.state.value.pet!!.pet!!.name)
    }

    @Test fun partnerAdoptedFirst() = runBlocking<Unit> {
        rpc("app_pet") { payload(null) }
        rpc("app_adopt_pet", 400) { """{"code":"P0001","message":"pet_exists","details":"你們已經有一隻貓咪了","hint":null}""" }
        val r = repo(RemoteRepository.PET_SCHEMA)
        rpc("app_pet") { payload() }
        assertEquals("你們已經有一隻貓咪了", fails("pet_exists") { r.adoptPet("湯圓", "white") }.message)
        assertEquals("顯示對方領養的那隻", "麻糬", r.state.value.pet!!.pet!!.name)
    }

    /** 跑過舊的 upgrade-v2.sql（schema 6）：其他功能照常，小貓要再更新一次資料庫 */
    @Test fun oldDatabaseHasNoCat() = runBlocking<Unit> {
        val r = repo(RemoteRepository.MOODS_SCHEMA, prefs = """{"comfort":true,"memo":true}""")
        val asked = calls("app_pet").size
        r.refreshAll()
        assertEquals("知道版本之後就不問了", asked, calls("app_pet").size)
        val s = r.state.value
        assertFalse(s.schemaOutdated)
        assertTrue(s.petOutdated)
        assertFalse(s.petReady)
        assertNull(s.pet)
        fails(Errors.PET_OUTDATED_CODE) { r.petAction("feed", "can") }
        fails(Errors.PET_OUTDATED_CODE) { r.adoptPet("湯圓", "white") }
        assertTrue(calls("app_pet_action").isEmpty())
        // 通知設定裡的「小貓」：資料庫不認得，說要更新（不是整個資料庫要更新）
        rpc("app_set_notification_prefs") { """{"ok":true}""" }
        fails(Errors.PET_OUTDATED_CODE) { r.setNotifPref("pet", false) }
        assertFalse(r.state.value.schemaOutdated)
    }

    @Test fun notificationsOpenTheCat() {
        assertEquals("pet", RemoteRepository.routeFor("pet"))
        assertEquals("牠肚子太餓了，先餵牠吃點東西", Errors.DB_MESSAGES["pet_hungry"])
        assertEquals(Errors.PET_OUTDATED_CODE, Errors.from(404, """{"code":"PGRST202","message":"Could not find the function public.app_pet_action(jsonb) in the schema cache"}""").code)
    }
}
