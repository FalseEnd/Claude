package tw.xiaoliangkou.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.xiaoliangkou.app.data.Api
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

/** 備忘錄的照片、語音：拿掉的、搬走的、整則刪掉的，都從 Storage 刪掉（別的地方還在用的留著）。用假的 Supabase */
class ReviewFix3Test {
    private class Req(val method: String, val path: String, val body: ByteArray)
    private class Res(val status: Int, val body: ByteArray, val type: String = "application/json")

    /** 極簡 HTTP 伺服器：照「方法 路徑」找 handler，路徑以 / 結尾的 key 可以比對開頭 */
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

    /** 每次刪 Storage 檔案送出的路徑 */
    private fun deleted() = calls("DELETE /storage/v1/object/media").map { req ->
        Api.json.parseToJsonElement(req.body.decodeToString()).jsonObject["prefixes"]!!.jsonArray.map { it.jsonPrimitive.content }
    }

    private fun media(vararg p: String) = p.joinToString(",", "[", "]") { """{"path":"$it","kind":"photo"}""" }
    private fun memo(id: String, user: String, shared: Boolean, media: String = "[]") =
        """{"id":"$id","userId":"$user","title":"便利貼","body":"","media":$media,"shared":$shared,"pinned":false,"color":"pink","createdAt":"2026-10-15T02:16:21+00:00"}"""

    private suspend fun repo(): RemoteRepository {
        rpc("app_me") { """{"user":{"id":"u-1","displayName":"阿哲"},"couple":{"id":"c-1"},"partner":{"id":"u-2","displayName":"小雨"},"partnerOnline":false}""" }
        rpc("app_health") { """{"ok":true,"schema":2}""" }
        json("DELETE /storage/v1/object/media") { "[]" }
        val key = "sb_publishable_test"
        val session = SessionManager(base, key, Session("a-1", "r-1", System.currentTimeMillis() / 1000 + 3600, "u-1", "azhe@example.com"))
        val r = RemoteRepository(SupabaseClient(base, key, session).api, RealtimeClient(scope), scope) {}
        r.refreshAll()
        return r
    }

    /** 編輯時拿掉的照片：刪掉；日曆還在用的那張留著 */
    @Test fun removedMemoMediaIsDeleted() = runBlocking<Unit> {
        rpc("app_day_entries") { """[{"id":"d1","userId":"u-1","day":"${LocalDate.now()}","media":${media("c/c-1/diary.jpg")},"createdAt":"2026-10-15T02:16:21+00:00"}]""" }
        rpc("app_memos") { "[${memo("m1", "u-1", true, media("c/c-1/a.jpg", "c/c-1/b.jpg", "c/c-1/diary.jpg"))}]" }
        rpc("app_save_memo") { memo("m1", "u-1", true) }
        val r = repo()
        r.saveMemo("m1", "便利貼", "", listOf(MediaItem("c/c-1/a.jpg", MediaItem.PHOTO)), shared = true, pinned = false, color = "pink")
        assertEquals(listOf(listOf("c/c-1/b.jpg")), deleted())
        // 什麼都沒拿掉：不刪
        rpc("app_memos") { "[${memo("m1", "u-1", true, media("c/c-1/a.jpg"))}]" }
        r.refreshAll()
        r.saveMemo("m1", "便利貼", "改內容", listOf(MediaItem("c/c-1/a.jpg", MediaItem.PHOTO)), shared = true, pinned = false, color = "pink")
        assertEquals(1, deleted().size)
    }

    /** 私人改成共用：照片搬到共用的地方，自己資料夾裡的原始檔刪掉（以前會一直留著） */
    @Test fun sharingAMemoDeletesThePrivateOriginals() = runBlocking<Unit> {
        rpc("app_memos") { "[${memo("m1", "u-1", false, media("u/u-1/secret.jpg"))}]" }
        rpc("app_save_memo") { memo("m1", "u-1", true) }
        routes["GET /storage/v1/object/authenticated/media/u/u-1/secret.jpg"] = { Res(200, byteArrayOf(1, 2, 3), "image/jpeg") }
        json("POST /storage/v1/object/media/") { """{"Key":"x"}""" }
        val r = repo()
        val m = r.state.value.memo("m1")!!
        r.saveMemo(m.id, m.title, m.body, m.media, shared = true, pinned = false, color = "pink")
        val sent = calls("POST /rest/v1/rpc/app_save_memo").last().body.decodeToString()
        val moved = Api.json.parseToJsonElement(sent).jsonObject["media"]!!.jsonArray.single().jsonObject["path"]!!.jsonPrimitive.content
        assertTrue(moved, moved.startsWith("c/c-1/"))
        assertEquals(listOf(listOf("u/u-1/secret.jpg")), deleted())
    }

    /** 新的備忘錄：存成私人時，剛剛上傳到共用資料夾的檔案搬走後刪掉 */
    @Test fun newPrivateMemoDeletesSharedUploads() = runBlocking<Unit> {
        rpc("app_memos") { "[]" }
        rpc("app_save_memo") { memo("m9", "u-1", false) }
        routes["GET /storage/v1/object/authenticated/media/c/c-1/new.jpg"] = { Res(200, byteArrayOf(9), "image/jpeg") }
        json("POST /storage/v1/object/media/") { """{"Key":"x"}""" }
        val r = repo()
        r.saveMemo(null, "自己的", "", listOf(MediaItem("c/c-1/new.jpg", MediaItem.PHOTO, "image/jpeg")), shared = false, pinned = false, color = "pink")
        assertEquals(listOf(listOf("c/c-1/new.jpg")), deleted())
    }

    /** 刪掉整則：它的照片、語音也刪掉；別的備忘錄還在用的、別人資料夾的不刪 */
    @Test fun deletingAMemoDeletesItsMedia() = runBlocking<Unit> {
        var memos = "[${memo("m1", "u-1", true, media("c/c-1/a.jpg", "c/c-1/both.jpg", "u/u-1/voice.m4a", "u/u-2/theirs.jpg"))},${memo("m2", "u-1", true, media("c/c-1/both.jpg"))}]"
        rpc("app_memos") { memos }
        rpc("app_delete_memo") { """{"ok":true}""" }
        val r = repo()
        memos = "[${memo("m2", "u-1", true, media("c/c-1/both.jpg"))}]"
        r.deleteMemo("m1")
        assertEquals("""{"id":"m1"}""", calls("POST /rest/v1/rpc/app_delete_memo").single().body.decodeToString())
        assertEquals(listOf(listOf("c/c-1/a.jpg", "u/u-1/voice.m4a")), deleted())
        assertNull(r.state.value.memo("m1"))
        // 刪不掉檔案（例如是對方上傳的）也沒關係，備忘錄已經刪好了
        json("DELETE /storage/v1/object/media", 400) { """{"statusCode":"403","error":"Unauthorized","message":"row-level security"}""" }
        memos = "[]"
        r.deleteMemo("m2")
        assertEquals(2, deleted().size)
        assertTrue(r.state.value.memos.isEmpty())
    }
}
