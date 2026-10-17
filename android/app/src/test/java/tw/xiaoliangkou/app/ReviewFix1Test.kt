package tw.xiaoliangkou.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.xiaoliangkou.app.data.AppException
import tw.xiaoliangkou.app.data.Errors
import tw.xiaoliangkou.app.data.RealtimeClient
import tw.xiaoliangkou.app.data.RemoteRepository
import tw.xiaoliangkou.app.data.Session
import tw.xiaoliangkou.app.data.SessionManager
import tw.xiaoliangkou.app.data.Supabase
import tw.xiaoliangkou.app.data.SupabaseClient
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap

/** 資料庫更新說明的連結、舊資料庫上新通知種類的開關、備忘錄小工具等備忘錄真的載入了才畫 */
class ReviewFix1Test {
    @Test fun sqlEditorLinkOnlyForSupabaseCloud() {
        assertEquals("https://supabase.com/dashboard/project/abcdefgh/sql/new", Supabase.sqlEditorUrl("https://abcdefgh.supabase.co"))
        assertEquals("https://supabase.com/dashboard/project/mdlevoygfciwzlqtsids/sql/new", Supabase.sqlEditorUrl(Supabase.DEFAULT_URL))
        assertNull(Supabase.sqlEditorUrl("http://127.0.0.1:54321"))
        assertNull(Supabase.sqlEditorUrl("https://supabase.example.com"))
        assertNull(Supabase.sqlEditorUrl("https://a.b.supabase.co"))
        assertNull(Supabase.sqlEditorUrl(""))
        assertNull(Supabase.sqlEditorUrl(null))
        assertTrue(Supabase.UPGRADE_SQL_URL.endsWith("/supabase/upgrade-v2.sql"))
    }

    // ---------------------------------------------------------------- 假的 Supabase（只回 RPC：路徑最後一段是函式名稱）
    private val routes = ConcurrentHashMap<String, () -> Pair<Int, String>>()
    private val calls = ConcurrentHashMap<String, MutableList<String>>()
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
                val body = ByteArray(headers["content-length"]?.toInt() ?: 0).also { b -> var n = 0; while (n < b.size) n += input.read(b, n, b.size - n) }
                val name = target.substringBefore('?').substringAfterLast('/')
                calls.getOrPut(name) { java.util.Collections.synchronizedList(mutableListOf()) } += body.decodeToString()
                val (status, text) = routes[name]?.invoke()
                    ?: (404 to """{"code":"PGRST202","message":"Could not find the function public.$name without parameters in the schema cache"}""")
                val bytes = text.toByteArray()
                it.getOutputStream().apply {
                    write("HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(bytes)
                    flush()
                }
            }
        }
    }.also { it.isDaemon = true; it.start() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun tearDown() { scope.cancel(); server.close() }

    private fun repo(): RemoteRepository {
        routes["app_me"] = { 200 to """{"user":{"id":"u-1","displayName":"阿哲"},"couple":{"id":"c-1"},"partner":{"id":"u-2","displayName":"小雨"},"partnerOnline":false}""" }
        val key = "sb_publishable_test"
        val session = SessionManager(base, key, Session("a-1", "r-1", System.currentTimeMillis() / 1000 + 3600, "u-1", "azhe@example.com"))
        return RemoteRepository(SupabaseClient(base, key, session).api, RealtimeClient(scope), scope) {}
    }

    private val v1Kinds = listOf("pairing", "place", "mood", "comfort", "battery_low", "guess", "event", "anniversary", "cycle_care", "sharing_changed")
    private fun prefs(kinds: List<String>, off: Set<String> = emptySet()) = kinds.joinToString(",", "{", "}") { "\"$it\":${it !in off}" }

    @Test fun newNotificationKindOnOldDatabaseSaysUpgrade() = runBlocking<Unit> {
        // 0.1 的資料庫：app_set_notification_prefs 默默略過 diary，回傳 ok；重新讀也不會有 diary
        routes["app_health"] = { 200 to """{"ok":true}""" }
        routes["app_notification_prefs"] = { 200 to prefs(v1Kinds) }
        routes["app_set_notification_prefs"] = { 200 to """{"ok":true}""" }
        val r = repo()
        r.refreshAll()
        val e = try { r.setNotifPref("diary", false); null } catch (e: AppException) { e }
        assertEquals(Errors.SCHEMA_OUTDATED_CODE, e?.code)
        assertEquals(Errors.SCHEMA_OUTDATED, e?.message)
        assertTrue(r.state.value.schemaOutdated)
        // 舊的通知種類照常可以設定
        routes["app_notification_prefs"] = { 200 to prefs(v1Kinds, off = setOf("mood")) }
        r.setNotifPref("mood", false)
        assertEquals(false, r.state.value.notifPrefs["mood"])
    }

    @Test fun newNotificationKindAfterUpgradeSaves() = runBlocking<Unit> {
        // 跑過 upgrade-v2.sql：新的通知種類照常存好，不會說要更新
        routes["app_health"] = { 200 to """{"ok":true,"schema":2}""" }
        routes["app_notification_prefs"] = { 200 to prefs(v1Kinds + listOf("diary", "memo"), off = setOf("memo")) }
        routes["app_set_notification_prefs"] = { 200 to """{"ok":true}""" }
        listOf("app_notes", "app_memos", "app_day_entries").forEach { routes[it] = { 200 to "[]" } }
        val r = repo()
        r.refreshAll()
        r.setNotifPref("memo", false)
        assertEquals(false, r.state.value.notifPrefs["memo"])
        assertTrue(calls["app_set_notification_prefs"].orEmpty().any { "\"memo\":false" in it })
        assertFalse(r.state.value.schemaOutdated)
    }

    @Test fun memoWidgetWaitsForMemos() = runBlocking<Unit> {
        // 帳號資料到了、備忘錄還沒拿到（失敗）：loaded 但 memosLoaded 還是 false，小工具不會被畫成空的
        routes["app_health"] = { 200 to """{"ok":true,"schema":2}""" }
        routes["app_memos"] = { 500 to """{"message":"upstream timeout"}""" }
        val r = repo()
        r.refreshAll()
        assertTrue(r.state.value.loaded)
        assertFalse(r.state.value.memosLoaded)

        routes["app_memos"] = { 200 to """[{"id":"m1","userId":"u-1","title":"買牛奶","body":"","media":[],"shared":true,"pinned":true,"color":"pink","createdAt":"2026-10-15T02:16:21+00:00"}]""" }
        r.refreshAll()
        assertTrue(r.state.value.memosLoaded)
        assertEquals(listOf("m1"), r.state.value.pinnedMemos.map { it.id })
    }

    @Test fun oldDatabaseHasNoMemos() = runBlocking<Unit> {
        // 還沒執行 upgrade-v2.sql：找不到 app_memos，確定沒有備忘錄，小工具不用一直等
        val r = repo()
        r.refreshAll()
        assertTrue(r.state.value.schemaOutdated)
        assertTrue(r.state.value.memosLoaded)
        assertTrue(r.state.value.memos.isEmpty())
    }
}
