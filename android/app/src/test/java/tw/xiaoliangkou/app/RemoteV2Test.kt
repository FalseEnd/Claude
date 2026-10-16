package tw.xiaoliangkou.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.xiaoliangkou.app.data.Api
import tw.xiaoliangkou.app.data.AppException
import tw.xiaoliangkou.app.data.Errors
import tw.xiaoliangkou.app.data.MediaItem
import tw.xiaoliangkou.app.data.RealtimeClient
import tw.xiaoliangkou.app.data.RemoteRepository
import tw.xiaoliangkou.app.data.Session
import tw.xiaoliangkou.app.data.SessionManager
import tw.xiaoliangkou.app.data.SupabaseClient
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.time.LocalDate
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** 連 Supabase 時的新功能：RPC 的參數、Storage 的網址與標頭、資料庫還沒更新時的提醒（用假的 Supabase） */
class RemoteV2Test {
    private class Req(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray) {
        fun header(name: String) = headers[name.lowercase()]
        val json: JsonObject get() = Api.json.parseToJsonElement(body.decodeToString()).jsonObject
    }
    private class Res(val status: Int, val body: ByteArray, val type: String = "application/json")

    /** 極簡 HTTP 伺服器：照「方法 路徑」找 handler，路徑以 / 結尾的 key 可以比對開頭；內容是 bytes（照片、語音不會壞掉） */
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
                val req = Req(method, target.substringBefore('?'), headers, body)
                seen += req
                val key = "$method ${req.path}"
                val handler = routes[key] ?: routes.entries.filter { (k, _) -> k.endsWith("/") && key.startsWith(k) }.maxByOrNull { e -> e.key.length }?.value
                val res = handler?.invoke(req) ?: Res(404, """{"code":"PGRST202","message":"Could not find the function public.${req.path.substringAfterLast('/')} without parameters in the schema cache"}""".toByteArray())
                it.getOutputStream().apply {
                    write("HTTP/1.1 ${res.status} X\r\nContent-Type: ${res.type}\r\nContent-Length: ${res.body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(res.body)
                    flush()
                }
            }
        }
    }.also { it.isDaemon = true; it.start() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun tearDown() { scope.cancel(); server.close() }

    private fun json(key: String, status: Int = 200, body: (Req) -> String) { routes[key] = { r -> Res(status, body(r).toByteArray()) } }
    private fun rpc(name: String, status: Int = 200, body: (Req) -> String) = json("POST /rest/v1/rpc/$name", status, body)
    private fun calls(key: String) = seen.toList().filter { "${it.method} ${it.path}".startsWith(key) }

    private val key = "sb_publishable_test"
    private fun client() = SupabaseClient(base, key, SessionManager(base, key, Session("a-1", "r-1", System.currentTimeMillis() / 1000 + 3600, "u-1", "azhe@example.com")))

    private val paired = """{"user":{"id":"u-1","displayName":"阿哲"},"couple":{"id":"c-1"},"partner":{"id":"u-2","displayName":"小雨"},"partnerOnline":false}"""
    private val single = """{"user":{"id":"u-1","displayName":"阿哲"},"partnerOnline":false}"""

    private fun memo(id: String, user: String, shared: Boolean, media: String = "[]") =
        """{"id":"$id","userId":"$user","title":"秘密","body":"","media":$media,"shared":$shared,"pinned":false,"color":"pink","createdAt":"2026-10-15T02:16:21+00:00"}"""

    private suspend fun failsWith(code: String, block: suspend () -> Unit): AppException {
        try { block() } catch (e: AppException) { assertEquals(e.message, code, e.code); return e }
        throw AssertionError("應該要失敗：$code")
    }

    private suspend fun repo(me: String = paired): RemoteRepository {
        rpc("app_me") { me }
        val r = RemoteRepository(client().api, RealtimeClient(scope), scope) {}
        r.refreshAll()
        return r
    }

    @Test fun oldDatabaseAsksForUpgrade() = runBlocking<Unit> {
        rpc("app_health") { """{"ok":true,"time":"2026-10-16T00:00:00Z"}""" }
        val r = repo()
        assertTrue(r.state.value.schemaOutdated)
        // 新的 RPC 找不到（PGRST202）：訊息請使用者執行 upgrade-v2.sql
        val e = failsWith(Errors.SCHEMA_OUTDATED_CODE) { r.saveNote(null, "她喜歡半糖少冰") }
        assertEquals(Errors.SCHEMA_OUTDATED, e.message)
        // 舊的 app_update_profile 不認得 info：暱稱、生日存好了，但要提醒更新
        rpc("app_update_profile") { """{"id":"u-1","displayName":"阿哲","avatarUrl":null,"birthday":"1998-05-20"}""" }
        failsWith(Errors.SCHEMA_OUTDATED_CODE) { r.updateProfileInfo("阿哲", "1998-05-20", JsonObject(emptyMap())) }
        val sent = calls("POST /rest/v1/rpc/app_update_profile").last().json
        assertEquals("1998-05-20", sent["birthday"]!!.jsonPrimitive.content)
        assertTrue("info" in sent)

        // 跑過 upgrade-v2.sql 之後
        rpc("app_health") { """{"ok":true,"schema":2}""" }
        rpc("app_notes") { "[]" }
        rpc("app_memos") { "[]" }
        rpc("app_day_entries") { "[]" }
        r.refreshAll()
        assertFalse(r.state.value.schemaOutdated)

        // Storage 還沒有 media bucket
        json("POST /storage/v1/object/media/", 400) { """{"statusCode":"404","error":"Bucket not found","message":"Bucket not found"}""" }
        failsWith(Errors.SCHEMA_OUTDATED_CODE) { r.uploadMedia(byteArrayOf(1), "image/jpeg", MediaItem.PHOTO, shared = true) }
        assertTrue(r.state.value.schemaOutdated)
    }

    @Test fun uploadsAndDownloadsThroughStorage() = runBlocking<Unit> {
        rpc("app_health") { """{"ok":true,"schema":2}""" }
        val r = repo()
        json("POST /storage/v1/object/media/") { req -> """{"Key":"media${req.path.removePrefix("/storage/v1/object/media")}"}""" }
        val bytes = ByteArray(300) { (it * 7).toByte() } // 不是文字的 bytes 也要原封不動
        val photo = r.uploadMedia(bytes, "image/jpeg", MediaItem.PHOTO, shared = true, width = 1600, height = 1200)
        assertTrue(photo.path, Regex("""^c/c-1/[0-9a-f-]{36}\.jpg$""").matches(photo.path))
        assertEquals(MediaItem(photo.path, "photo", "image/jpeg", 1600, 1200), photo)
        val up = calls("POST /storage/v1/object/media/").single()
        assertEquals("/storage/v1/object/media/${photo.path}", up.path)
        assertEquals("image/jpeg", up.header("Content-Type"))
        assertEquals(key, up.header("apikey"))
        assertEquals("Bearer a-1", up.header("Authorization"))
        assertArrayEquals(bytes, up.body)

        val voice = r.uploadMedia(byteArrayOf(1, 2, 3), "audio/mp4", MediaItem.VOICE, shared = false, durationMs = 1200)
        assertTrue(voice.path, Regex("""^u/u-1/[0-9a-f-]{36}\.m4a$""").matches(voice.path))
        assertEquals("audio/mp4", calls("POST /storage/v1/object/media/u/").single().header("Content-Type"))

        routes["GET /storage/v1/object/authenticated/media/${photo.path}"] = { Res(200, bytes, "image/jpeg") }
        assertArrayEquals(bytes, r.downloadMedia(photo.path))
        assertEquals("Bearer a-1", calls("GET /storage/v1/object/authenticated/media/").single().header("Authorization"))
        // 舊版 Storage：HTTP 400，真正的狀態在 statusCode
        json("GET /storage/v1/object/authenticated/media/", 400) { """{"statusCode":"404","error":"not_found","message":"Object not found"}""" }
        failsWith("not_found") { r.downloadMedia("c/c-1/gone.jpg") }
        // 怪怪的路徑不送出去
        failsWith("invalid_media") { r.downloadMedia("c/../../auth/v1/admin") }
        failsWith("too_large") { r.uploadMedia(ByteArray(10 * 1024 * 1024 + 1), "image/jpeg", MediaItem.PHOTO, shared = true) }
    }

    @Test fun sendsNewRpcBodies() = runBlocking<Unit> {
        rpc("app_health") { """{"ok":true,"schema":2}""" }
        rpc("app_notes") { """[{"id":"n1","aboutUser":"u-2","body":"半糖少冰","createdAt":"2026-10-15T02:16:21+00:00"}]""" }
        rpc("app_save_note") { """{"id":"n1","aboutUser":"u-2","body":"半糖少冰","createdAt":"2026-10-15T02:16:21+00:00"}""" }
        rpc("app_delete_note") { """{"ok":true}""" }
        rpc("app_day_entries") { """[{"id":"d1","userId":"u-2","day":"2026-10-15","body":"火鍋","createdAt":"2026-10-15T02:16:21+00:00"}]""" }
        rpc("app_save_day_entry") { req ->
            val b = req.json
            """{"id":"d2","userId":"u-1","day":${b["day"]},"body":${b["body"]},"media":${b["media"]},"createdAt":"2026-10-16T02:16:21+00:00"}"""
        }
        rpc("app_delete_day_entry") { """{"ok":true}""" }
        rpc("app_memos") { "[${memo("m1", "u-1", false)}]" }
        rpc("app_save_memo") { memo("m1", "u-1", true) }
        val r = repo()
        assertEquals("半糖少冰", r.state.value.notes.single().body)
        assertEquals(1, r.state.value.dayEntries.size)
        // 預設載入今天前 62 天～後 31 天
        calls("POST /rest/v1/rpc/app_day_entries").first().json.let {
            assertEquals(LocalDate.now().minusDays(62).toString(), it["from"]!!.jsonPrimitive.content)
            assertEquals(LocalDate.now().plusDays(31).toString(), it["to"]!!.jsonPrimitive.content)
        }

        r.saveNote(null, "她喜歡半糖少冰")
        assertEquals("""{"body":"她喜歡半糖少冰"}""", calls("POST /rest/v1/rpc/app_save_note").last().body.decodeToString())
        r.saveNote("n1", "改過")
        assertEquals("n1", calls("POST /rest/v1/rpc/app_save_note").last().json["id"]!!.jsonPrimitive.content)
        r.deleteNote("n1")
        assertEquals("""{"id":"n1"}""", calls("POST /rest/v1/rpc/app_delete_note").last().body.decodeToString())

        // 換月：載入那段期間，合併進目前的紀錄
        r.loadDayEntries(LocalDate.of(2026, 3, 25), LocalDate.of(2026, 5, 7))
        calls("POST /rest/v1/rpc/app_day_entries").last().json.let {
            assertEquals("2026-03-25", it["from"]!!.jsonPrimitive.content); assertEquals("2026-05-07", it["to"]!!.jsonPrimitive.content)
        }
        val photo = MediaItem("c/c-1/a.jpg", "photo", "image/jpeg", 4, 3)
        r.saveDayEntry(null, LocalDate.of(2026, 10, 16), "一起吃了火鍋", listOf(photo))
        calls("POST /rest/v1/rpc/app_save_day_entry").last().json.let {
            assertFalse("id" in it)
            assertEquals("2026-10-16", it["day"]!!.jsonPrimitive.content)
            assertEquals("""[{"path":"c/c-1/a.jpg","kind":"photo","mime":"image/jpeg","width":4,"height":3}]""", it["media"].toString())
        }
        assertEquals(listOf("d1", "d2"), r.state.value.dayEntries.map { it.id })
        assertEquals(listOf(photo), r.state.value.dayEntries.last().media)
        failsWith("invalid_input") { r.saveDayEntry(null, LocalDate.now(), "  ", emptyList()) }
        r.deleteDayEntry("d2")
        assertEquals(listOf("d1"), r.state.value.dayEntries.map { it.id })

        // 備忘錄：私人改成共用時，照片搬到兩個人共用的地方再存
        val secret = byteArrayOf(5, 4, 3, 2, 1)
        rpc("app_memos") { "[${memo("m1", "u-1", false, """[{"path":"u/u-1/old.jpg","kind":"photo","mime":"image/jpeg"}]""")}]" }
        r.refreshAll()
        routes["GET /storage/v1/object/authenticated/media/u/u-1/old.jpg"] = { Res(200, secret, "image/jpeg") }
        json("POST /storage/v1/object/media/") { """{"Key":"x"}""" }
        val m = r.state.value.memos.single()
        r.saveMemo(m.id, "秘密", "", m.media, shared = true, pinned = true, color = "mint")
        val moved = calls("POST /storage/v1/object/media/").single()
        assertTrue(moved.path, moved.path.startsWith("/storage/v1/object/media/c/c-1/"))
        assertArrayEquals(secret, moved.body)
        calls("POST /rest/v1/rpc/app_save_memo").last().json.let {
            assertEquals("m1", it["id"]!!.jsonPrimitive.content)
            assertEquals("true", it["shared"]!!.jsonPrimitive.content)
            assertEquals("true", it["pinned"]!!.jsonPrimitive.content)
            assertEquals("mint", it["color"]!!.jsonPrimitive.content)
            assertEquals(moved.path.removePrefix("/storage/v1/object/media/"), it["media"]!!.jsonArray.single().jsonObject["path"]!!.jsonPrimitive.content)
        }

        // 對方共用的備忘錄：不送 shared（不能改共用設定）
        rpc("app_memos") { "[${memo("m2", "u-2", true)}]" }
        r.refreshAll()
        r.saveMemo("m2", "想去的地方", "京都", emptyList(), shared = false, pinned = false, color = "sky")
        assertFalse("shared" in calls("POST /rest/v1/rpc/app_save_memo").last().json)
        failsWith("invalid_input") { r.saveMemo(null, "", "", emptyList(), shared = true, pinned = false, color = "pink") }
    }

    @Test fun unpairedHasNoSharedSpace() = runBlocking<Unit> {
        rpc("app_health") { """{"ok":true,"schema":2}""" }
        rpc("app_day_entries") { """[{"id":"d1","userId":"u-2","day":"2026-10-15","body":"火鍋","createdAt":"2026-10-15T02:16:21+00:00"}]""" }
        val r = repo(single)
        assertTrue(r.state.value.dayEntries.isEmpty())
        assertTrue(calls("POST /rest/v1/rpc/app_day_entries").isEmpty())
        failsWith("not_paired") { r.uploadMedia(byteArrayOf(1), "image/jpeg", MediaItem.PHOTO, shared = true) }
        // 沒配對：新的備忘錄只能是私人的
        rpc("app_save_memo") { memo("m1", "u-1", false) }
        rpc("app_memos") { "[]" }
        r.saveMemo(null, "自己的", "", emptyList(), shared = true, pinned = false, color = "pink")
        assertEquals("false", calls("POST /rest/v1/rpc/app_save_memo").last().json["shared"]!!.jsonPrimitive.content)
    }
}
