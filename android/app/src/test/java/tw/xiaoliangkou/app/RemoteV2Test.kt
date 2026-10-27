package tw.xiaoliangkou.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
import tw.xiaoliangkou.app.data.ProfileInfo
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
        // 建立時 init 也會自己跑一次 refreshAll：等它跑完，不然它晚到的結果會蓋掉測試接著存的資料。
        // 整套測試一起跑時機器很忙，要連續 3 次（750 毫秒）都沒有新的請求才算跑完
        var n = -1
        var quiet = 0
        while (quiet < 3) {
            delay(250)
            if (seen.size == n) quiet++ else { n = seen.size; quiet = 0 }
        }
        return r
    }

    @Test fun oldDatabaseAsksForUpgrade() = runBlocking<Unit> {
        rpc("app_health") { """{"ok":true,"time":"2026-10-16T00:00:00Z"}""" }
        val r = repo()
        assertTrue(r.state.value.schemaOutdated)
        // 新的 RPC 找不到（PGRST202）：訊息請使用者執行 upgrade-v2.sql
        val e = failsWith(Errors.SCHEMA_OUTDATED_CODE) { r.saveNote(null, "她喜歡半糖少冰") }
        assertEquals(Errors.SCHEMA_OUTDATED, e.message)
        // 舊的 app_update_profile 不認得 info：暱稱、生日存好了，填了其他資料的話要提醒更新
        rpc("app_update_profile") { """{"id":"u-1","displayName":"阿哲","avatarUrl":null,"birthday":"1998-05-20"}""" }
        failsWith(Errors.SCHEMA_OUTDATED_CODE) { r.updateProfileInfo(" 阿哲 ", "1998-05-20", ProfileInfo.build(mapOf(ProfileInfo.HEIGHT to "172"))) }
        val sent = calls("POST /rest/v1/rpc/app_update_profile").last().json
        assertEquals("阿哲", sent["displayName"]!!.jsonPrimitive.content)
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
        // 有變動時：重新載入預設區間（跟著今天走）＋最近換到的那段，更早看過的不再重抓
        val today = LocalDate.now()
        val older = today.minusMonths(10) to today.minusMonths(9)
        r.loadDayEntries(older.first, older.second)
        val latest = today.minusMonths(6) to today.minusMonths(5)
        r.loadDayEntries(latest.first, latest.second)
        val before = calls("POST /rest/v1/rpc/app_day_entries").size
        r.refreshAll()
        val reloaded = calls("POST /rest/v1/rpc/app_day_entries").drop(before).map {
            LocalDate.parse(it.json["from"]!!.jsonPrimitive.content) to LocalDate.parse(it.json["to"]!!.jsonPrimitive.content)
        }
        assertEquals(listOf(RemoteRepository.defaultDayRange(), latest), reloaded)

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
        // 以前配對時的照片已經讀不到了：不去下載，直接拿掉，備忘錄照樣存得了
        r.saveMemo("m1", "以前的", "", listOf(MediaItem("c/old/a.jpg", MediaItem.PHOTO)), shared = true, pinned = false, color = "pink")
        assertTrue(calls("GET /storage/").isEmpty())
        assertTrue(calls("POST /storage/").isEmpty())
        assertEquals("[]", calls("POST /rest/v1/rpc/app_save_memo").last().json["media"].toString())
    }

    @Test fun privateMemoMediaLeavesTheSharedFolder() = runBlocking<Unit> {
        rpc("app_health") { """{"ok":true,"schema":2}""" }
        val today = LocalDate.now()
        // 日曆也用到同一張照片：那張不能刪
        rpc("app_day_entries") { """[{"id":"d1","userId":"u-1","day":"$today","media":[{"path":"c/c-1/diary.jpg","kind":"photo"}],"createdAt":"2026-10-15T02:16:21+00:00"}]""" }
        val media = listOf("c/c-1/a.jpg", "c/c-1/diary.jpg", "c/c-1/gone.jpg", "c/old/b.jpg", "u/u-1/mine.jpg")
            .joinToString(",", "[", "]") { """{"path":"$it","kind":"photo"}""" }
        rpc("app_memos") { "[${memo("m1", "u-1", true, media)}]" }
        rpc("app_save_memo") { memo("m1", "u-1", false) }
        val r = repo()
        routes["GET /storage/v1/object/authenticated/media/c/c-1/a.jpg"] = { Res(200, byteArrayOf(1, 2, 3), "image/jpeg") }
        routes["GET /storage/v1/object/authenticated/media/c/c-1/diary.jpg"] = { Res(200, byteArrayOf(4), "image/jpeg") }
        json("GET /storage/v1/object/authenticated/media/c/c-1/gone.jpg", 400) { """{"statusCode":"404","error":"not_found","message":"Object not found"}""" }
        json("POST /storage/v1/object/media/") { """{"Key":"x"}""" }
        json("DELETE /storage/v1/object/media") { "[]" }

        val m = r.state.value.memos.single()
        r.saveMemo(m.id, m.title, m.body, m.media, shared = false, pinned = false, color = "pink")
        // 搬到 u/<我>/；被刪掉的、以前配對的拿掉，本來就在 u/<我>/ 的不動
        val saved = calls("POST /rest/v1/rpc/app_save_memo").last().json
        assertEquals("false", saved["shared"]!!.jsonPrimitive.content)
        val paths = saved["media"]!!.jsonArray.map { it.jsonObject["path"]!!.jsonPrimitive.content }
        assertEquals(3, paths.size)
        assertTrue(paths.toString(), paths.all { it.startsWith("u/u-1/") })
        assertEquals("u/u-1/mine.jpg", paths.last())
        assertTrue(calls("GET /storage/v1/object/authenticated/media/c/old/").isEmpty())
        assertEquals(2, calls("POST /storage/v1/object/media/u/u-1/").size)
        // 共用資料夾裡的原始檔刪掉，對方就列不出來、讀不到（日曆還在用的那張留著）；
        // 已經不見的那張也從備忘錄拿掉了，順便清掉。以前配對的資料夾刪不掉，不用送
        val del = calls("DELETE /storage/v1/object/media").single()
        assertEquals("""{"prefixes":["c/c-1/a.jpg","c/c-1/gone.jpg"]}""", del.body.decodeToString())
        assertEquals("Bearer a-1", del.header("Authorization"))
        assertEquals(key, del.header("apikey"))

        // 一直是共用的、照片沒拿掉：不刪任何檔案
        rpc("app_memos") { "[${memo("m1", "u-1", true, """[{"path":"c/c-1/a.jpg","kind":"photo"}]""")}]" }
        r.refreshAll()
        r.saveMemo(m.id, m.title, "改內容", listOf(MediaItem("c/c-1/a.jpg", MediaItem.PHOTO)), shared = true, pinned = false, color = "pink")
        assertEquals(1, calls("DELETE /storage/").size)
        // 刪不掉（例如是對方上傳的）也沒關係，備忘錄已經存好了
        json("DELETE /storage/v1/object/media", 400) { """{"statusCode":"403","error":"Unauthorized","message":"row-level security"}""" }
        routes["GET /storage/v1/object/authenticated/media/c/c-1/new.jpg"] = { Res(200, byteArrayOf(5), "image/jpeg") }
        r.saveMemo(null, "新的", "", listOf(MediaItem("c/c-1/new.jpg", MediaItem.PHOTO, "image/jpeg")), shared = false, pinned = false, color = "pink")
        assertEquals(2, calls("DELETE /storage/").size)
        assertTrue(calls("POST /rest/v1/rpc/app_save_memo").last().json["media"]!!.jsonArray.single().jsonObject["path"]!!.jsonPrimitive.content.startsWith("u/u-1/"))
    }

    /** 生活紀錄拿掉、刪掉的照片語音，還有上傳了沒存的：從 Storage 刪掉（別的地方還在用的留著） */
    @Test fun dayEntryMediaIsCleanedUp() = runBlocking<Unit> {
        rpc("app_health") { """{"ok":true,"schema":2}""" }
        val today = LocalDate.now()
        fun media(vararg p: String) = p.joinToString(",", "[", "]") { """{"path":"$it","kind":"photo"}""" }
        rpc("app_day_entries") { """[{"id":"d1","userId":"u-1","day":"$today","media":${media("c/c-1/a.jpg", "c/c-1/b.jpg", "c/c-1/memo.jpg")},"createdAt":"2026-10-15T02:16:21+00:00"}]""" }
        rpc("app_memos") { "[${memo("m1", "u-1", true, media("c/c-1/memo.jpg"))}]" }
        rpc("app_save_day_entry") { req ->
            val b = req.json
            """{"id":"d1","userId":"u-1","day":${b["day"]},"body":${b["body"]},"media":${b["media"]},"createdAt":"2026-10-15T02:16:21+00:00"}"""
        }
        rpc("app_delete_day_entry") { """{"ok":true}""" }
        json("DELETE /storage/v1/object/media") { "[]" }
        val r = repo()
        fun deleted() = calls("DELETE /storage/v1/object/media").map { req ->
            req.json["prefixes"]!!.jsonArray.map { it.jsonPrimitive.content }
        }
        val a = MediaItem("c/c-1/a.jpg", MediaItem.PHOTO)

        // 編輯時拿掉 b 和 memo：b 刪掉，備忘錄還在用的留著
        r.saveDayEntry("d1", today, "改過", listOf(a))
        assertEquals(listOf(listOf("c/c-1/b.jpg")), deleted())
        // 沒拿掉東西：不刪
        r.saveDayEntry("d1", today, "又改", listOf(a))
        assertEquals(1, deleted().size)
        // 刪掉整則：它的照片也刪掉
        r.deleteDayEntry("d1")
        assertEquals(listOf("c/c-1/a.jpg"), deleted().last())
        // 上傳了沒存：共用的、自己的刪掉；還在用的、別人資料夾的不刪
        r.discardMedia(listOf("c/c-1/new.jpg", "u/u-1/mine.m4a", "u/u-2/theirs.jpg", "c/c-1/memo.jpg").map { MediaItem(it, MediaItem.PHOTO) })
        assertEquals(listOf("c/c-1/new.jpg", "u/u-1/mine.m4a"), deleted().last())
        // 刪不掉也沒關係
        json("DELETE /storage/v1/object/media", 400) { """{"statusCode":"403","error":"Unauthorized","message":"row-level security"}""" }
        r.discardMedia(listOf(MediaItem("c/c-1/x.jpg", MediaItem.PHOTO)))
        assertEquals(4, deleted().size)
    }

    @Test fun dayWindowsFollowTodayAndTheLatestMonth() {
        val d = RemoteRepository.defaultDayRange(LocalDate.of(2026, 10, 16))
        assertEquals(LocalDate.of(2026, 8, 15) to LocalDate.of(2026, 11, 16), d)
        assertEquals(listOf(d), RemoteRepository.dayWindows(d, null))
        assertEquals(listOf(d), RemoteRepository.dayWindows(d, LocalDate.of(2026, 9, 24) to LocalDate.of(2026, 11, 7)))
        // 接在一起的合成一段，離很遠的分開抓（不會抓中間好幾個月）
        assertEquals(
            listOf(LocalDate.of(2026, 7, 25) to LocalDate.of(2026, 11, 16)),
            RemoteRepository.dayWindows(d, LocalDate.of(2026, 7, 25) to LocalDate.of(2026, 8, 14)),
        )
        assertEquals(
            listOf(d, LocalDate.of(2026, 3, 25) to LocalDate.of(2026, 5, 7)),
            RemoteRepository.dayWindows(d, LocalDate.of(2026, 3, 25) to LocalDate.of(2026, 5, 7)),
        )
    }
}
