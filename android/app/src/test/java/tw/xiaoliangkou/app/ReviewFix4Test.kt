package tw.xiaoliangkou.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
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

/**
 * 跑完 upgrade-v2.sql 不用重開 App 就會發現、對方解除配對時前任的資料跟著拿掉、
 * 存好了只是重新整理失敗不會說沒存到、舊的資料庫只改暱稱生日不會跳錯誤。用假的 Supabase
 */
class ReviewFix4Test {
    private class Req(val method: String, val path: String, val body: ByteArray)
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
    private val realtime = RealtimeClient(scope)

    @After fun tearDown() { scope.cancel(); server.close() }

    private fun json(key: String, status: Int = 200, body: (Req) -> String) { routes[key] = { r -> Res(status, body(r).toByteArray()) } }
    private fun rpc(name: String, status: Int = 200, body: (Req) -> String) = json("POST /rest/v1/rpc/$name", status, body)
    private fun count(name: String) = seen.toList().count { it.path == "/rest/v1/rpc/$name" }

    private val paired = """{"user":{"id":"u-1","displayName":"阿哲"},"couple":{"id":"c-1"},"partner":{"id":"u-2","displayName":"小雨"},"partnerOnline":false}"""
    private val single = """{"user":{"id":"u-1","displayName":"阿哲"},"partnerOnline":false}"""
    private val v1Health = """{"ok":true,"time":"2026-10-16T00:00:00Z"}"""
    private val v2Health = """{"ok":true,"schema":4}"""

    private fun memo(id: String, user: String, shared: Boolean, pinned: Boolean = false, media: String = "[]") =
        """{"id":"$id","userId":"$user","title":"便利貼","body":"","media":$media,"shared":$shared,"pinned":$pinned,"color":"pink","createdAt":"2026-10-15T02:16:21+00:00"}"""
    private fun note(id: String, about: String, body: String) = """{"id":"$id","aboutUser":"$about","body":"$body","createdAt":"2026-10-15T02:16:21+00:00"}"""
    private fun entry(id: String, user: String, day: LocalDate) = """{"id":"$id","userId":"$user","day":"$day","body":"今天去看海","media":[],"createdAt":"2026-10-15T02:16:21+00:00"}"""

    private suspend fun repo(me: String = paired): RemoteRepository {
        rpc("app_me") { me }
        json("DELETE /storage/v1/object/media") { "[]" }
        val key = "sb_publishable_test"
        val session = SessionManager(base, key, Session("a-1", "r-1", System.currentTimeMillis() / 1000 + 3600, "u-1", "azhe@example.com"))
        val r = RemoteRepository(SupabaseClient(base, key, session).api, realtime, scope) {}
        r.refreshAll()
        settle()
        return r
    }

    /** 等到沒有請求進來（建立時 init 也會自己跑一次 refreshAll，跑完才算開好 App） */
    private suspend fun settle() {
        var n = -1
        while (n != seen.size) { n = seen.size; delay(250) }
    }

    private suspend fun until(what: String, cond: () -> Boolean) {
        try {
            withTimeout(5_000) { while (!cond()) delay(20) }
        } catch (e: Exception) {
            throw AssertionError("等不到：$what")
        }
    }

    /** 假裝 Realtime 收到「資料表變了」（就是對方手機改了資料） */
    @Suppress("UNCHECKED_CAST")
    private suspend fun changed(table: String) {
        val f = RealtimeClient::class.java.getDeclaredField("_events").apply { isAccessible = true }
        val events = f.get(realtime) as MutableSharedFlow<RealtimeClient.Event>
        until("RemoteRepository 開始收 Realtime") { events.subscriptionCount.value > 0 }
        assertTrue(events.tryEmit(RealtimeClient.Event.Changed(table, "u-2")))
    }

    private fun upgradeDatabase(notes: String = "[]", memos: String = "[]") {
        rpc("app_health") { v2Health }
        rpc("app_notes") { notes }
        rpc("app_memos") { memos }
        rpc("app_day_entries") { "[]" }
    }

    /** 跑完 upgrade-v2.sql 回到 App（ON_START）：再檢查一次，提醒拿掉、新功能的資料載進來，不用把 App 整個關掉 */
    @Test fun upgradeIsNoticedWhenComingBack() = runBlocking<Unit> {
        rpc("app_health") { v1Health }
        val r = repo()
        assertTrue(r.state.value.schemaOutdated)

        // 還沒更新：只多問一次 app_health，不會整個重新載入
        val me = count("app_me")
        val health = count("app_health")
        r.recheckSchema()
        until("再問一次 app_health") { count("app_health") == health + 1 }
        delay(200)
        assertTrue(r.state.value.schemaOutdated)
        assertEquals(me, count("app_me"))

        // 更新好了
        upgradeDatabase(notes = "[${note("n1", "u-2", "她喜歡半糖少冰")}]", memos = "[${memo("m1", "u-2", shared = true, pinned = true)}]")
        r.recheckSchema()
        until("提醒拿掉") { !r.state.value.schemaOutdated }
        until("備註、備忘錄載進來") { r.state.value.notes.isNotEmpty() && r.state.value.memos.isNotEmpty() }
        assertEquals(listOf("m1"), r.state.value.pinnedMemos.map { it.id })

        // 已經是新的：回到 App 不用再問
        settle()
        val after = count("app_health")
        r.recheckSchema()
        delay(200)
        assertEquals(after, count("app_health"))
    }

    /** 跑過舊的 upgrade-v2.sql（schema 2）：新功能都能用，只是還沒有手機名稱和電信；回到 App 再問一次，但不用整個重新載入 */
    @Test fun schemaTwoOnlyMissesPhoneNames() = runBlocking<Unit> {
        upgradeDatabase()
        rpc("app_health") { """{"ok":true,"schema":2}""" }
        val r = repo()
        assertFalse(r.state.value.schemaOutdated)
        assertTrue(r.state.value.deviceInfoOutdated)

        val me = count("app_me")
        val health = count("app_health")
        r.recheckSchema()
        until("再問一次 app_health") { count("app_health") == health + 1 }
        delay(200)
        assertTrue(r.state.value.deviceInfoOutdated)
        assertEquals("不用整個重新載入", me, count("app_me"))

        // 再跑一次 upgrade-v2.sql 之後
        rpc("app_health") { """{"ok":true,"schema":3}""" }
        r.recheckSchema()
        until("手機名稱的提醒拿掉") { !r.state.value.deviceInfoOutdated }
        assertEquals(me, count("app_me"))
    }

    /** 跑過 0.2.1 的 upgrade-v2.sql（schema 3）：只差 GIPHY 貼圖；回到 App 再問一次，但不用整個重新載入 */
    @Test fun schemaThreeOnlyMissesStickers() = runBlocking<Unit> {
        upgradeDatabase()
        rpc("app_health") { """{"ok":true,"schema":3}""" }
        val r = repo()
        assertFalse(r.state.value.schemaOutdated)
        assertFalse(r.state.value.deviceInfoOutdated)
        assertTrue(r.state.value.stickersOutdated)

        val me = count("app_me")
        val health = count("app_health")
        r.recheckSchema()
        until("再問一次 app_health") { count("app_health") == health + 1 }
        delay(200)
        assertTrue(r.state.value.stickersOutdated)
        assertEquals("不用整個重新載入", me, count("app_me"))

        // 再跑一次 upgrade-v2.sql 之後
        rpc("app_health") { v2Health }
        r.recheckSchema()
        until("貼圖的提醒拿掉") { !r.state.value.stickersOutdated }
        assertEquals(me, count("app_me"))
        // 已經是最新的：回到 App 不用再問
        settle()
        val after = count("app_health")
        r.recheckSchema()
        delay(200)
        assertEquals(after, count("app_health"))
    }

    /** 另一半那支手機：資料庫是對方更新的，收到新功能的變動、新功能的呼叫成功時就發現 */
    @Test fun partnerPhoneNoticesUpgradeFromRealtime() = runBlocking<Unit> {
        rpc("app_health") { v1Health }
        val r = repo()
        assertTrue(r.state.value.schemaOutdated)
        upgradeDatabase(notes = "[${note("n1", "u-2", "生日想要拍立得")}]", memos = "[${memo("m1", "u-2", shared = true)}]")
        changed("memos")
        until("提醒拿掉") { !r.state.value.schemaOutdated }
        // 開 App 時沒載到的備註也一起重新載入
        until("備註載進來") { r.state.value.notes.map { it.id } == listOf("n1") }
    }

    /** 對方解除配對：前任共用（放在桌面）的備忘錄、日曆、寫給前任的備註都拿掉，不用等 App 重開 */
    @Test fun partnerUnpairingDropsTheirData() = runBlocking<Unit> {
        val today = LocalDate.now()
        upgradeDatabase(notes = "[${note("n1", "u-2", "怕冷")}]", memos = "[${memo("m1", "u-1", shared = true)},${memo("m2", "u-2", shared = true, pinned = true)}]")
        rpc("app_day_entries") { "[${entry("d1", "u-2", today)}]" }
        val r = repo()
        assertEquals(listOf("m2"), r.state.value.pinnedMemos.map { it.id })
        assertEquals(listOf("d1"), r.state.value.dayEntries.map { it.id })

        // 小雨按了解除配對：我這邊只收到 couple_members 變了
        rpc("app_me") { single }
        rpc("app_notes") { "[]" }
        rpc("app_memos") { "[${memo("m1", "u-1", shared = false)}]" }
        changed("couple_members")
        until("前任的備忘錄拿掉") { r.state.value.memos.map { it.id } == listOf("m1") }
        assertTrue(r.state.value.pinnedMemos.isEmpty())
        until("日曆、備註拿掉") { r.state.value.dayEntries.isEmpty() && r.state.value.notes.isEmpty() }
        assertNull(r.state.value.couple)
    }

    /** 跟新的人配對（對方接受了我的邀請）：以前配對的生活紀錄，就算是之前翻過、現在沒在看的月份也不能留著 */
    @Test fun newCoupleDoesNotShowOldDiary() = runBlocking<Unit> {
        upgradeDatabase()
        val old = LocalDate.now().minusYears(2)
        rpc("app_day_entries") { "[${entry("old", "u-2", old)}]" }
        val r = repo()
        r.loadDayEntries(old.withDayOfMonth(1), old.withDayOfMonth(old.lengthOfMonth()))
        assertTrue(r.state.value.dayEntries.any { it.id == "old" })
        // 再翻到別的月份：那個月的紀錄還留在記憶體裡
        rpc("app_day_entries") { "[]" }
        val other = LocalDate.now().minusYears(1)
        r.loadDayEntries(other.withDayOfMonth(1), other.withDayOfMonth(other.lengthOfMonth()))
        assertTrue(r.state.value.dayEntries.any { it.id == "old" })

        rpc("app_me") { """{"user":{"id":"u-1","displayName":"阿哲"},"couple":{"id":"c-2"},"partner":{"id":"u-3","displayName":"小晴"},"partnerOnline":false}""" }
        changed("couple_members")
        until("換成新的配對") { r.state.value.couple?.id == "c-2" }
        until("以前配對的紀錄拿掉") { r.state.value.dayEntries.none { it.id == "old" } }
    }

    /** 存好了、只是接著重新整理失敗（網路不穩）：不能說沒存到，不然使用者再按一次就存成兩筆 */
    @Test fun saveSucceedsEvenIfReloadFails() = runBlocking<Unit> {
        upgradeDatabase(notes = "[${note("n1", "u-2", "舊的")}]", memos = "[${memo("m1", "u-1", shared = true)}]")
        val r = repo()
        rpc("app_memos", 500) { """{"message":"upstream timeout"}""" }
        rpc("app_notes", 500) { """{"message":"upstream timeout"}""" }

        rpc("app_save_memo") { memo("m9", "u-1", shared = true, pinned = true) }
        r.saveMemo(null, "便利貼", "", emptyList<MediaItem>(), shared = true, pinned = true, color = "pink")
        assertEquals(listOf("m9", "m1"), r.state.value.memos.map { it.id })
        assertEquals(1, count("app_save_memo"))

        rpc("app_delete_memo") { """{"ok":true}""" }
        r.deleteMemo("m1")
        assertEquals(listOf("m9"), r.state.value.memos.map { it.id })

        rpc("app_save_note") { note("n2", "u-2", "新的") }
        r.saveNote(null, "新的")
        assertEquals(listOf("n2", "n1"), r.state.value.notes.map { it.id })
        // 改舊的：留在原本的位置
        rpc("app_save_note") { note("n1", "u-2", "改過了") }
        r.saveNote("n1", "改過了")
        assertEquals(listOf("新的", "改過了"), r.state.value.notes.map { it.body })

        rpc("app_delete_note") { """{"ok":true}""" }
        r.deleteNote("n2")
        assertEquals(listOf("n1"), r.state.value.notes.map { it.id })
    }

    /** 還沒更新的資料庫：只改暱稱、生日就是全部存好了，不會跳「資料庫需要更新」；有填其他資料才提醒 */
    @Test fun oldDatabaseSavesNameAndBirthdayQuietly() = runBlocking<Unit> {
        rpc("app_health") { v1Health }
        rpc("app_update_profile") { """{"id":"u-1","displayName":"阿哲","avatarUrl":null,"birthday":"1998-05-20"}""" }
        val r = repo()
        r.updateProfileInfo("阿哲", "1998-05-20", JsonObject(emptyMap()))
        assertTrue(r.state.value.schemaOutdated)

        // 改了生日又填了身高：生日存好了，身高要等更新
        val height = ProfileInfo.build(mapOf(ProfileInfo.HEIGHT to "172"))
        val e = try { r.updateProfileInfo("阿哲", "1998-05-21", height); null } catch (e: AppException) { e }
        assertEquals(Errors.SCHEMA_OUTDATED_CODE, e?.code)
        assertEquals(RemoteRepository.PROFILE_INFO_OUTDATED, e?.message)

        // 只填了身高：照原本的訊息
        rpc("app_me") { """{"user":{"id":"u-1","displayName":"阿哲","birthday":"1998-05-20"},"couple":{"id":"c-1"},"partner":{"id":"u-2","displayName":"小雨"},"partnerOnline":false}""" }
        r.refreshAll()
        val e2 = try { r.updateProfileInfo("阿哲", "1998-05-20", height); null } catch (e: AppException) { e }
        assertEquals(Errors.SCHEMA_OUTDATED, e2?.message)

        // 更新好之後存個人資料：提醒拿掉
        upgradeDatabase()
        rpc("app_update_profile") { """{"id":"u-1","displayName":"阿哲","birthday":"1998-05-20","info":{"heightCm":172}}""" }
        r.updateProfileInfo("阿哲", "1998-05-20", height)
        until("提醒拿掉") { !r.state.value.schemaOutdated }
        assertFalse(r.state.value.schemaOutdated)
    }
}
