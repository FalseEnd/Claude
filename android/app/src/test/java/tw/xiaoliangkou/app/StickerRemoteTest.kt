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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.xiaoliangkou.app.data.Api
import tw.xiaoliangkou.app.data.AppException
import tw.xiaoliangkou.app.data.Couple
import tw.xiaoliangkou.app.data.Errors
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
 * GIPHY 貼圖的資料層：新的資料庫存得到、舊的資料庫先擋下來（或送出去才發現沒存到）、
 * 收到的安撫標成看過了、兩個人共用的 GIPHY key。用假的 Supabase
 */
class StickerRemoteTest {
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

    private val sticker = "L4c8BNDK4STL7B5LdV"
    private val key = "abcdEFGH1234ijklMNOP5678qrstUVWX"

    private fun me(giphyKey: String? = null) =
        """{"user":{"id":"u-1","displayName":"阿哲"},"couple":{"id":"c-1","togetherSince":"2025-05-20"${giphyKey?.let { ",\"giphyKey\":\"$it\"" }.orEmpty()}},""" +
            """"partner":{"id":"u-2","displayName":"小雨"},"partnerOnline":false}"""
    private fun mood(id: Long, sticker: String?) =
        """{"id":$id,"userId":"u-1","mood":"happy","note":null,"createdAt":"2026-10-20T02:16:21+00:00"${sticker?.let { ",\"sticker\":\"$it\"" }.orEmpty()}}"""
    private fun comfort(id: Long, from: String, to: String, sticker: String? = null, readAt: String? = null) =
        """{"id":$id,"coupleId":"c-1","senderId":"$from","receiverId":"$to","kind":"hug","message":null,"createdAt":"2026-10-20T02:16:21+00:00",""" +
            """"readAt":${readAt?.let { "\"$it\"" } ?: "null"}${sticker?.let { ",\"sticker\":\"$it\"" }.orEmpty()}}"""

    /** 開 App：資料庫是 [schema] 版（null＝還沒有 schema 的 v1） */
    private suspend fun repo(schema: Int?, comforts: String = "[]"): RemoteRepository {
        rpc("app_me") { me() }
        rpc("app_health") { if (schema == null) """{"ok":true}""" else """{"ok":true,"schema":$schema}""" }
        rpc("app_moods") { "[]" }
        rpc("app_comforts") { comforts }
        for (f in listOf("app_notes", "app_memos", "app_day_entries", "app_places", "app_expenses", "app_partner_expenses", "app_anniversaries", "app_events", "app_history")) rpc(f) { "[]" }
        rpc("app_notification_prefs") { "{}" }
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

    @Test fun stickersAreSavedOnSchemaFour() = runBlocking<Unit> {
        val r = repo(4)
        assertFalse(r.state.value.stickersOutdated)
        rpc("app_post_mood") { mood(1, sticker) }
        rpc("app_moods") { "[${mood(1, sticker)}]" }
        r.postMood("happy", "", sticker)
        assertEquals(sticker, calls("app_post_mood").single().json["sticker"]?.toString()?.trim('"'))
        assertEquals(sticker, r.state.value.moods.single().sticker)

        rpc("app_send_comfort") { comfort(5, "u-1", "u-2", sticker) }
        rpc("app_comforts") { "[${comfort(5, "u-1", "u-2", sticker)}]" }
        r.sendComfort("hug", null, sticker)
        assertEquals(sticker, calls("app_send_comfort").single().json["sticker"]?.toString()?.trim('"'))
        assertEquals(sticker, r.state.value.comforts.single().sticker)

        // 沒有附貼圖：不送 sticker（舊版的 App 也是這樣送）
        rpc("app_post_mood") { mood(2, null) }
        r.postMood("calm", null)
        assertFalse("sticker" in calls("app_post_mood").last().json)
        r.sendComfort("pat", "辛苦了", " ")
        assertFalse("空白的貼圖當作沒有", "sticker" in calls("app_send_comfort").last().json)
    }

    @Test fun schemaThreeRefusesStickersUpFront() = runBlocking<Unit> {
        val r = repo(3)
        assertTrue(r.state.value.stickersOutdated)
        assertFalse(r.state.value.deviceInfoOutdated)
        val e = fails(RemoteRepository.STICKERS_OUTDATED_CODE) { r.postMood("happy", null, sticker) }
        assertTrue(e.message!!.contains("upgrade-v2.sql"))
        fails(RemoteRepository.STICKERS_OUTDATED_CODE) { r.sendComfort("hug", null, sticker) }
        assertTrue("沒送出去", calls("app_post_mood").isEmpty() && calls("app_send_comfort").isEmpty())
        // 沒附貼圖的照常送
        rpc("app_post_mood") { mood(1, null) }
        r.postMood("happy", null)
        assertEquals(1, calls("app_post_mood").size)
        // 整個 v2 都還沒跑：說要執行 upgrade-v2.sql
        val v1 = repo(null)
        fails(Errors.SCHEMA_OUTDATED_CODE) { v1.postMood("happy", null, sticker) }
    }

    /** 還不知道資料庫的版本（或剛換回舊的資料庫）：舊的資料庫默默略過 sticker，心情還是存好了 */
    @Test fun oldDatabaseDroppingTheStickerIsNoticed() = runBlocking<Unit> {
        val r = repo(4)
        rpc("app_post_mood") { mood(1, null) }
        val e = fails(RemoteRepository.STICKERS_OUTDATED_CODE) { r.postMood("happy", null, sticker) }
        assertEquals(RemoteRepository.MOOD_STICKER_NOT_SAVED, e.message)
        assertTrue(e.message!!.startsWith("心情更新了"))
        assertEquals("心情只送一次", 1, calls("app_post_mood").size)
        assertEquals(3, r.state.value.dbSchema)
        assertTrue(r.state.value.stickersOutdated)
        // 接下來直接擋下來
        fails(RemoteRepository.STICKERS_OUTDATED_CODE) { r.sendComfort("hug", null, sticker) }
        assertTrue(calls("app_send_comfort").isEmpty())

        val r2 = repo(4)
        rpc("app_send_comfort") { comfort(9, "u-1", "u-2") }
        val e2 = fails(RemoteRepository.STICKERS_OUTDATED_CODE) { r2.sendComfort("hug", null, sticker) }
        assertEquals(RemoteRepository.COMFORT_STICKER_NOT_SAVED, e2.message)
        assertTrue(r2.state.value.stickersOutdated)
    }

    @Test fun badStickerIdsNeverLeaveThePhone() = runBlocking<Unit> {
        val r = repo(4)
        for (bad in listOf("../x", "a b", "貼圖", "x".repeat(65))) {
            fails("invalid_input") { r.postMood("happy", null, bad) }
            fails("invalid_input") { r.sendComfort("hug", null, bad) }
        }
        assertTrue(calls("app_post_mood").isEmpty() && calls("app_send_comfort").isEmpty())
    }

    /** 假的 app_read_comfort：記下送來的參數，{"all": true} 把收到、還沒看過的全部標起來；[during] 在請求進來時（還沒回應前）跑 */
    private fun readRoute(read: MutableSet<Long>, received: List<Long>, during: (Req) -> Unit = {}) = rpc("app_read_comfort") { req ->
        during(req)
        if (req.json["all"]?.toString() == "true") read += received else read += req.json["id"].toString().toLong()
        """{"ok":true}"""
    }

    private fun comforts(read: Set<Long>): String {
        fun at(id: Long) = if (id in read) "2026-10-20T03:00:00+00:00" else null
        return "[${comfort(4, "u-2", "u-1", readAt = at(4))},${comfort(3, "u-2", "u-1", sticker, at(3))},${comfort(2, "u-1", "u-2")},${comfort(1, "u-2", "u-1", readAt = at(1))}]"
    }

    /** schema 4：一則是 {"id": n}；全部標成看過只要一次 {"all": true}。送出去之前畫面就先標好了（紅點馬上消失） */
    @Test fun receivedComfortsAreMarkedRead() = runBlocking<Unit> {
        val read = Collections.synchronizedSet(mutableSetOf<Long>())
        val seenRead = Collections.synchronizedList(mutableListOf<Set<Long>>())
        val r = repo(4)
        rpc("app_comforts") { comforts(read) }
        readRoute(read, listOf(4, 3, 1)) { seenRead += r.state.value.comforts.filter { it.readAt != null }.map { it.id }.toSet() }
        r.refreshAll()
        val before = calls("app_comforts").size
        r.readComfort(3)
        assertEquals(listOf("""{"id":3}"""), calls("app_read_comfort").map { it.json.toString() })
        assertEquals("畫面先標成看過了（還在送的時候）", setOf(3L), seenRead.single())
        assertNotNull(r.state.value.comforts.first { it.id == 3L }.readAt)
        assertNull(r.state.value.comforts.first { it.id == 1L }.readAt)

        // 全部標成看過了：一次就好，送出去之前畫面就先標好了；最後重新載入一次
        r.markComfortsRead()
        assertEquals(listOf("""{"id":3}""", """{"all":true}"""), calls("app_read_comfort").map { it.json.toString() })
        assertEquals(setOf(4L, 3L, 1L), seenRead.last())
        assertEquals(before + 2, calls("app_comforts").size)
        assertTrue(r.state.value.comforts.filter { it.receiverId == "u-1" }.all { it.readAt != null })
        assertNull("自己送出去的不用標", r.state.value.comforts.first { it.id == 2L }.readAt)
        // 沒有沒看過的：什麼都不用送
        r.markComfortsRead()
        assertEquals(2, calls("app_read_comfort").size)
    }

    /** schema 3 的資料庫還不認得 all：一則一則標，第一則送出去之前全部都先標好了 */
    @Test fun oldDatabaseMarksComfortsOneByOne() = runBlocking<Unit> {
        val read = Collections.synchronizedSet(mutableSetOf<Long>())
        val seenRead = Collections.synchronizedList(mutableListOf<Set<Long>>())
        val r = repo(3)
        rpc("app_comforts") { comforts(read) }
        readRoute(read, listOf(4, 3, 1)) { seenRead += r.state.value.comforts.filter { it.readAt != null }.map { it.id }.toSet() }
        r.refreshAll()
        r.markComfortsRead()
        assertEquals(listOf("""{"id":4}""", """{"id":3}""", """{"id":1}"""), calls("app_read_comfort").map { it.json.toString() })
        assertEquals(setOf(4L, 3L, 1L), seenRead.first())
        assertTrue(r.state.value.comforts.filter { it.receiverId == "u-1" }.all { it.readAt != null })
    }

    @Test fun failedReadMarkComesBack() = runBlocking<Unit> {
        val r = repo(4, comforts = "[${comfort(3, "u-2", "u-1")},${comfort(1, "u-2", "u-1")}]")
        rpc("app_read_comfort", 500) { """{"message":"upstream timeout"}""" }
        fails("server_error") { r.readComfort(3) }
        assertTrue("沒送到：改回沒看過，下次再標", r.state.value.comforts.all { it.readAt == null })
        fails("server_error") { r.markComfortsRead() }
        assertTrue(r.state.value.comforts.all { it.readAt == null })
    }

    /** 送出去了就是送出去了：之後重新整理失敗（網路不穩）不能說失敗，不然使用者再按一次，對方就收到兩次 */
    @Test fun sentEvenIfTheReloadFails() = runBlocking<Unit> {
        val r = repo(4)
        rpc("app_send_comfort") { comfort(9, "u-1", "u-2") }
        rpc("app_comforts", 503) { """{"message":"upstream timeout"}""" }
        r.sendComfort("hug", null)
        assertEquals(1, calls("app_send_comfort").size)
        rpc("app_post_mood") { mood(1, null) }
        rpc("app_moods", 503) { """{"message":"upstream timeout"}""" }
        r.postMood("happy", null)
        assertEquals(1, calls("app_post_mood").size)
        // 貼圖有沒有存到看的是送出去回傳的那一筆，跟重新整理無關
        rpc("app_send_comfort") { comfort(10, "u-1", "u-2") }
        fails(RemoteRepository.STICKERS_OUTDATED_CODE) { r.sendComfort("hug", null, sticker) }
    }

    @Test fun giphyKeyIsSharedByTheCouple() = runBlocking<Unit> {
        val r = repo(4)
        assertNull(r.state.value.giphyKey)
        rpc("app_set_giphy_key") { """{"id":"c-1","togetherSince":"2025-05-20","giphyKey":"$key"}""" }
        rpc("app_me") { me(key) }
        r.setGiphyKey("  $key\n")
        assertEquals(key, calls("app_set_giphy_key").single().json["key"].toString().trim('"'))
        assertEquals(key, r.state.value.giphyKey)
        assertEquals("2025-05-20", r.state.value.couple?.togetherSince)

        // 清掉：不送 key
        rpc("app_me") { me() }
        r.setGiphyKey(" ")
        assertEquals(JsonObject(emptyMap()), calls("app_set_giphy_key").last().json)
        assertNull(r.state.value.giphyKey)
        r.setGiphyKey(null)
        assertEquals(3, calls("app_set_giphy_key").size)

        // 格式不對的不送出去
        fails("invalid_input") { r.setGiphyKey("我的 key") }
        fails("invalid_input") { r.setGiphyKey("short") }
        assertEquals(3, calls("app_set_giphy_key").size)

        // key 不能跟著狀態被印出來
        assertFalse(Couple("c-1", giphyKey = key).toString().contains(key))
        assertFalse(r.state.value.copy(couple = Couple("c-1", giphyKey = key)).toString().contains(key))
    }

    /** 還沒跑過 upgrade-v2.sql 的 0.1 資料庫：不能附貼圖、不能存 key，說的是「資料庫需要更新」，不是「再執行一次」 */
    @Test fun v1DatabaseHasNoStickers() = runBlocking<Unit> {
        val r = repo(null)
        val st = r.state.value
        assertTrue(st.schemaOutdated)
        assertFalse("stickersOutdated 只說 schema 2、3", st.stickersOutdated)
        assertFalse(st.stickersReady)
        val e = fails(Errors.SCHEMA_OUTDATED_CODE) { r.setGiphyKey(key) }
        assertEquals(Errors.SCHEMA_OUTDATED, e.message)
        assertTrue("沒送出去", calls("app_set_giphy_key").isEmpty())
        assertTrue(r.state.value.schemaOutdated)
    }

    @Test fun stickersReadyFollowsTheSchema() = runBlocking<Unit> {
        val st = repo(4).state.value
        assertTrue(st.stickersReady)
        assertTrue("還不知道版本：先當作可以", st.copy(dbSchema = null).stickersReady)
        assertFalse(st.copy(dbSchema = 3).stickersReady)
        assertFalse(st.copy(dbSchema = 2).stickersReady)
        assertFalse(st.copy(schemaOutdated = true, dbSchema = 0).stickersReady)
    }

    @Test fun oldDatabaseCannotStoreTheKey() = runBlocking<Unit> {
        val r = repo(4)
        // schema 3：還沒有 app_set_giphy_key（假伺服器回 PGRST202）
        val e = fails(RemoteRepository.STICKERS_OUTDATED_CODE) { r.setGiphyKey(key) }
        assertEquals(RemoteRepository.STICKERS_OUTDATED, e.message)
        assertTrue(r.state.value.stickersOutdated)
        // 還沒配對
        rpc("app_set_giphy_key", 400) { """{"code":"P0001","message":"not_paired"}""" }
        fails("not_paired") { r.setGiphyKey(key) }
    }
}
