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
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.xiaoliangkou.app.core.Catalog
import tw.xiaoliangkou.app.data.Api
import tw.xiaoliangkou.app.data.AppException
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.data.Errors
import tw.xiaoliangkou.app.data.RealtimeClient
import tw.xiaoliangkou.app.data.RemoteRepository
import tw.xiaoliangkou.app.data.Session
import tw.xiaoliangkou.app.data.SessionManager
import tw.xiaoliangkou.app.data.SupabaseClient
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 0.2.3 多的心情和安撫：新的資料庫送得出去、舊的資料庫先擋下來（或被擋下來才發現），
 * 種類和文字和資料庫的一樣。用假的 Supabase，資料庫的部分讀 supabase/setup.sql
 */
class MoreMoodsTest {
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

    @Test fun latestSchemaSendsTheNewOnes() = runBlocking<Unit> {
        val r = repo(RemoteRepository.MOODS_SCHEMA)
        assertTrue(r.state.value.moreMoodsReady)
        assertFalse(r.state.value.moodsOutdated)
        rpc("app_post_mood") { mood(1, null) }
        r.postMood("jealous", "你跟同事聊得好開心")
        assertEquals("\"jealous\"", calls("app_post_mood").single().json["mood"].toString())
        rpc("app_send_comfort") { comfort(5, "u-1", "u-2") }
        r.sendComfort("night", null)
        r.sendComfort("kitty", null)
        assertEquals(listOf("\"night\"", "\"kitty\""), calls("app_send_comfort").map { it.json["kind"].toString() })
    }

    /** 跑過 0.2.3 的 upgrade-v2.sql（schema 5）：0.2.3 多的都能送，0.2.6 再多的安撫先擋下來 */
    @Test fun schemaFiveRefusesOnlyTheNewestComforts() = runBlocking<Unit> {
        val r = repo(5)
        assertTrue(r.state.value.moodsOutdated)
        assertFalse(r.state.value.moreMoodsReady)
        rpc("app_post_mood") { mood(1, null) }
        rpc("app_send_comfort") { comfort(5, "u-1", "u-2") }
        r.postMood("jealous", null)
        r.sendComfort("night", null)
        val e = fails(RemoteRepository.MOODS_OUTDATED_CODE) { r.sendComfort("gift", null) }
        assertTrue(e.message!!.contains("upgrade-v2.sql"))
        assertEquals(1, calls("app_send_comfort").size)
    }

    /** 以為資料庫是新的，送了 0.2.6 的安撫卻被 schema 5 的 check 擋下來：記成 5，0.2.3 的照樣能選 */
    @Test fun aSchemaFiveDatabaseRejectingANewComfortIsNoticed() = runBlocking<Unit> {
        val r = repo(RemoteRepository.MOODS_SCHEMA)
        rpc("app_send_comfort", status = 400) {
            """{"code":"23514","message":"new row for relation \"comforts\" violates check constraint \"comforts_kind_check\""}"""
        }
        fails(RemoteRepository.MOODS_OUTDATED_CODE) { r.sendComfort("joke", null) }
        assertEquals(5, r.state.value.dbSchema)
        assertTrue(r.state.value.canPick(5))
        assertFalse(r.state.value.canPick(6))
    }

    /** 跑過舊的 upgrade-v2.sql（schema 4）：原本的照常送，新的先擋下來、不送出去 */
    @Test fun schemaFourRefusesTheNewOnesUpFront() = runBlocking<Unit> {
        val r = repo(4)
        assertTrue(r.state.value.moodsOutdated)
        assertFalse(r.state.value.moreMoodsReady)
        val e = fails(RemoteRepository.MOODS_OUTDATED_CODE) { r.postMood("busy", null) }
        assertTrue(e.message!!.contains("upgrade-v2.sql"))
        fails(RemoteRepository.MOODS_OUTDATED_CODE) { r.sendComfort("water", null) }
        assertTrue("沒送出去", calls("app_post_mood").isEmpty() && calls("app_send_comfort").isEmpty())
        rpc("app_post_mood") { mood(1, null) }
        rpc("app_send_comfort") { comfort(5, "u-1", "u-2") }
        r.postMood("happy", null)
        r.sendComfort("hug", null)
        assertEquals(1, calls("app_post_mood").size)
        assertEquals(1, calls("app_send_comfort").size)
        // 整個 v2 都還沒跑：說要執行 upgrade-v2.sql
        val v1 = repo(null)
        fails(Errors.SCHEMA_OUTDATED_CODE) { v1.postMood("excited", null) }
    }

    /** 還不知道資料庫的版本就送了新的心情，舊的資料庫的 check 擋下來：說要更新，之後先擋下來 */
    @Test fun anOldDatabaseRejectingANewMoodIsNoticed() = runBlocking<Unit> {
        val r = repo(5)
        rpc("app_post_mood", status = 400) {
            """{"code":"23514","message":"new row for relation \"moods\" violates check constraint \"moods_mood_check\""}"""
        }
        val e = fails(RemoteRepository.MOODS_OUTDATED_CODE) { r.postMood("shy", null) }
        assertEquals(RemoteRepository.MOODS_OUTDATED, e.message)
        assertEquals(4, r.state.value.dbSchema)
        assertTrue(r.state.value.moodsOutdated)
        fails(RemoteRepository.MOODS_OUTDATED_CODE) { r.sendComfort("love", null) }
        assertTrue(calls("app_send_comfort").isEmpty())
        // 原本的心情被擋下來（格式不對）就照原本的錯誤說
        rpc("app_post_mood", status = 400) { """{"code":"23514","message":"too long"}""" }
        fails("invalid_input") { r.postMood("happy", "x") }
    }

    @Test fun readinessFollowsTheSchema() {
        val base = AppState(loaded = true)
        assertTrue("還不知道版本先當作可以", base.moreMoodsReady)
        assertFalse(base.moodsOutdated)
        assertFalse(base.copy(dbSchema = 4).moreMoodsReady)
        assertTrue(base.copy(dbSchema = 4).moodsOutdated)
        assertFalse(base.copy(dbSchema = 5).moreMoodsReady)
        assertTrue(base.copy(dbSchema = 5).canPick(5))
        assertFalse(base.copy(dbSchema = 5).canPick(6))
        assertTrue(base.copy(dbSchema = 6).moreMoodsReady)
        assertTrue("一開始就有的一定可以", base.copy(schemaOutdated = true, dbSchema = 0).canPick(0))
        assertFalse("0.1 的資料庫另外提醒", base.copy(schemaOutdated = true, dbSchema = 0).moodsOutdated)
        assertFalse(base.copy(schemaOutdated = true, dbSchema = 0).moreMoodsReady)
    }

    // ================================================================ 和資料庫一樣

    /** 所有 migrations 接在一起（只留最後一次的定義才算數） */
    private val sql: String by lazy { File("../../supabase/setup.sql").readText() }

    /** 最後一個 [prefix] 後面、括號裡的 'a', 'b', … */
    private fun lastList(prefix: String): List<String> {
        val at = sql.lastIndexOf(prefix)
        assertTrue("setup.sql 裡找不到 $prefix", at >= 0)
        val body = sql.substring(at + prefix.length).substringBefore(")")
        return Regex("'([^']*)'").findAll(body).map { it.groupValues[1] }.toList()
    }

    /** 最後一次定義的 [function] 裡的 when 'key' then 'text' */
    private fun lastCases(function: String): Map<String, String> {
        val at = sql.lastIndexOf("function public.$function(")
        assertTrue("setup.sql 裡找不到 $function", at >= 0)
        val body = sql.substring(at).substringAfter("$$").substringBefore("$$")
        return Regex("when '([^']*)' then '([^']*)'").findAll(body).associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test fun moodsMatchTheDatabase() {
        assertEquals(Catalog.moods.map { it.key }.toSet(), lastList("add constraint moods_mood_check check (mood in (").toSet())
        assertEquals(Catalog.moods.associate { it.key to it.label }, lastCases("mood_label"))
        // 「需要抱抱」：對方的通知會問要不要安撫
        assertEquals(Catalog.moods.filter { it.needsComfort }.map { it.key }.toSet(), lastList("case when new.mood in (").toSet())
        assertEquals("21 種心情", 21, Catalog.moods.size)
        assertEquals("沒有重複的", Catalog.moods.size, Catalog.moods.map { it.key }.toSet().size)
    }

    @Test fun comfortsMatchTheDatabase() {
        assertEquals(Catalog.comforts.map { it.key }.toSet() + "custom", lastList("add constraint comforts_kind_check check (kind in (").toSet())
        assertEquals(Catalog.comforts.associate { it.key to it.push }, lastCases("comfort_text"))
        assertEquals("29 種安撫", 29, Catalog.comforts.size)
        assertEquals("沒有重複的", Catalog.comforts.size, Catalog.comforts.map { it.key }.toSet().size)
    }

    /** 標成 extra 的正好是 0.2.2 以前的資料庫存不了的：舊的資料庫只會看到原本的選項 */
    @Test fun extrasAreExactlyTheOnesOldDatabasesReject() {
        val v1 = File("../../supabase/migrations/20261015000001_schema.sql").readText()
        fun list(table: String, prefix: String) =
            Regex("'([^']*)'").findAll(v1.substringAfter("create table public.$table (").substringAfter(prefix).substringBefore(")")).map { it.groupValues[1] }.toSet()
        assertEquals(Catalog.moods.filterNot { it.extra }.map { it.key }.toSet(), list("moods", "check (mood in ("))
        assertEquals(Catalog.comforts.filterNot { it.extra }.map { it.key }.toSet() + "custom", list("comforts", "check (kind in ("))
    }

    /** since = 5 的正好是 0.2.3 的資料庫多的，since = 6 的是 0.2.6 再多的 */
    @Test fun sinceMatchesTheMigrationThatAddedIt() {
        fun kinds(file: String) = Regex("'([^']*)'").findAll(
            File("../../supabase/migrations/$file").readText().substringAfter("add constraint comforts_kind_check check (kind in (").substringBefore(")"),
        ).map { it.groupValues[1] }.toSet()
        val v5 = kinds("20261024000008_more_moods.sql")
        val v6 = kinds("20261024000009_more_comforts.sql")
        assertEquals(v5 - "custom", Catalog.comforts.filter { it.since <= 5 }.map { it.key }.toSet())
        assertEquals(v6 - v5, Catalog.comforts.filter { it.since == 6 }.map { it.key }.toSet())
        assertTrue(Catalog.comforts.all { it.since in setOf(0, 5, 6) })
    }
}
