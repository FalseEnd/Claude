package tw.xiaoliangkou.app

import com.sun.net.httpserver.HttpServer
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
import tw.xiaoliangkou.app.data.RealtimeClient
import tw.xiaoliangkou.app.data.RemoteRepository
import tw.xiaoliangkou.app.data.Session
import tw.xiaoliangkou.app.data.SessionManager
import tw.xiaoliangkou.app.data.Supabase
import tw.xiaoliangkou.app.data.SupabaseClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/** 資料庫更新說明的連結、備忘錄小工具等備忘錄真的載入了才畫 */
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

    // ---------------------------------------------------------------- 假的 Supabase
    private val routes = ConcurrentHashMap<String, () -> Pair<Int, String>>()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0).apply {
        createContext("/") { ex ->
            ex.requestBody.readBytes()
            val name = ex.requestURI.path.substringAfterLast('/')
            val (status, body) = routes[name]?.invoke()
                ?: (404 to """{"code":"PGRST202","message":"Could not find the function public.$name without parameters in the schema cache"}""")
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun tearDown() { scope.cancel(); server.stop(0) }

    private suspend fun repo(): RemoteRepository {
        routes["app_me"] = { 200 to """{"user":{"id":"u-1","displayName":"阿哲"},"couple":{"id":"c-1"},"partner":{"id":"u-2","displayName":"小雨"},"partnerOnline":false}""" }
        val key = "sb_publishable_test"
        val session = SessionManager(base, key, Session("a-1", "r-1", System.currentTimeMillis() / 1000 + 3600, "u-1", "azhe@example.com"))
        return RemoteRepository(SupabaseClient(base, key, session).api, RealtimeClient(scope), scope) {}
    }

    @Test fun memoWidgetWaitsForMemos() = runBlocking<Unit> {
        // 帳號資料到了、備忘錄還沒拿到（失敗）：loaded 但 memosLoaded 還是 false，小工具不會被畫成空的
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
