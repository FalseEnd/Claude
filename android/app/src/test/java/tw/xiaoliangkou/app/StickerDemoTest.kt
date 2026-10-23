package tw.xiaoliangkou.app

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tw.xiaoliangkou.app.core.Catalog
import tw.xiaoliangkou.app.data.AppException
import tw.xiaoliangkou.app.data.Comfort
import tw.xiaoliangkou.app.data.DemoRepository
import tw.xiaoliangkou.app.data.DemoRepository.Companion.ME
import tw.xiaoliangkou.app.data.DemoRepository.Companion.PARTNER
import tw.xiaoliangkou.app.data.RemoteRepository
import tw.xiaoliangkou.app.data.SettingsStore
import tw.xiaoliangkou.app.ui.Derived
import tw.xiaoliangkou.app.ui.components.stickerChips
import java.time.Duration
import java.time.Instant

/** 示範模式的貼圖、收到的安撫標成看過了、GIPHY key、最近用過的貼圖、心情和安撫的 GIPHY 關鍵字 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StickerDemoTest {
    private val sticker = "L4c8BNDK4STL7B5LdV"
    private val key = "abcdEFGH1234ijklMNOP5678qrstUVWX"

    private suspend fun fails(code: String, block: suspend () -> Unit) {
        try {
            block()
            fail("應該要失敗：$code")
        } catch (e: AppException) {
            assertEquals(code, e.code)
        }
    }

    @Test fun demoHasAStickerOnTheUnreadComfort() = runTest {
        val s = DemoRepository(backgroundScope).also { it.running = false }.state.value
        assertEquals(RemoteRepository.STICKER_SCHEMA, s.dbSchema)
        assertFalse(s.stickersOutdated)
        val unread = s.comforts.filter { it.receiverId == s.myId && it.readAt == null }
        assertEquals(listOf(DemoRepository.STICKER), unread.map { it.sticker })
        assertEquals("L4c8BNDK4STL7B5LdV", DemoRepository.STICKER)
        // 心情沒有貼圖（常駐通知、小工具的文字不變）
        assertTrue(s.moods.all { it.sticker == null })
        val history = s.history.first { it.kind == "comfort" && it.actorId == PARTNER }
        assertEquals(DemoRepository.STICKER, (history.payload["sticker"] as JsonPrimitive).content)
        assertNull(s.giphyKey)
    }

    @Test fun demoKeepsStickersAndThePartnerReplies() = runTest {
        val demo = DemoRepository(backgroundScope).also { it.running = false }
        demo.postMood("happy", "今天超順利", sticker)
        val m = demo.state.value.moods.first()
        assertEquals(ME, m.userId)
        assertEquals(sticker, m.sticker)
        assertEquals(sticker, (demo.state.value.history.first().payload["sticker"] as JsonPrimitive).content)
        demo.postMood("calm", null)
        assertNull(demo.state.value.moods.first().sticker)
        assertFalse("沒有貼圖就不放", "sticker" in demo.state.value.history.first().payload)

        demo.sendComfort("hug", null, sticker)
        val sent = demo.state.value.comforts.first()
        assertEquals(PARTNER, sent.receiverId)
        assertEquals(sticker, sent.sticker)
        assertEquals(sticker, (demo.state.value.history.first().payload["sticker"] as JsonPrimitive).content)
        // 小雨的回覆也是一則收到的安撫（首頁「收到的安撫」才看得到）
        testScheduler.advanceTimeBy(3_000)
        val reply = demo.state.value.comforts.first()
        assertEquals(PARTNER, reply.senderId)
        assertEquals(ME, reply.receiverId)
        assertNotNull(reply.message)
        assertNull(reply.readAt)

        fails("invalid_input") { demo.postMood("happy", null, "../evil") }
        fails("invalid_input") { demo.sendComfort("hug", null, "a b") }
    }

    @Test fun demoMarksComfortsRead() = runTest {
        val demo = DemoRepository(backgroundScope).also { it.running = false }
        demo.sendComfort("pat", "辛苦了")
        testScheduler.advanceTimeBy(3_000)
        val unread = demo.state.value.comforts.filter { it.receiverId == ME && it.readAt == null }.map { it.id }
        assertEquals(2, unread.size)
        demo.readComfort(unread.first())
        assertNotNull(demo.state.value.comforts.first { it.id == unread.first() }.readAt)
        assertNull(demo.state.value.comforts.first { it.id == unread.last() }.readAt)
        demo.markComfortsRead()
        assertTrue(demo.state.value.comforts.filter { it.receiverId == ME }.all { it.readAt != null })
        assertTrue("送出去的不用標", demo.state.value.comforts.filter { it.senderId == ME }.all { it.readAt == null })
    }

    @Test fun demoStoresTheGiphyKey() = runTest {
        val demo = DemoRepository(backgroundScope).also { it.running = false }
        demo.setGiphyKey(" $key ")
        assertEquals(key, demo.state.value.giphyKey)
        assertEquals(key, demo.state.value.couple?.giphyKey)
        demo.setGiphyKey("")
        assertNull(demo.state.value.giphyKey)
        fails("invalid_input") { demo.setGiphyKey("不是 key") }
    }

    @Test fun recentStickersAreRememberedUntilLogout() = runBlocking<Unit> {
        val settings = SettingsStore(RuntimeEnvironment.getApplication())
        try {
            settings.clear()
            assertEquals(emptyList<String>(), settings.load().recentStickers)
            settings.addRecentSticker("aaa")
            settings.addRecentSticker("bbb")
            settings.addRecentSticker("aaa")
            settings.addRecentSticker("../evil")
            assertEquals("最近的在前、不重複", listOf("aaa", "bbb"), settings.load().recentStickers)
            repeat(30) { settings.addRecentSticker("s$it") }
            val recent = settings.load().recentStickers
            assertEquals("最多 24 個", 24, recent.size)
            assertEquals(SettingsStore.RECENT_STICKERS, recent.size)
            assertEquals("s29", recent.first())
            assertEquals("舊的擠掉", "s6", recent.last())
            settings.clear()
            assertEquals("登出就清掉", emptyList<String>(), settings.load().recentStickers)
        } finally {
            settings.clear()
        }
    }

    @Test fun everyMoodAndComfortHasAGiphyQuery() {
        assertEquals("happy", Catalog.mood("happy")?.giphyQuery)
        assertEquals("love", Catalog.mood("sweet")?.giphyQuery)
        assertEquals("miss you", Catalog.mood("missyou")?.giphyQuery)
        assertEquals("head pat", Catalog.comfort("pat")?.giphyQuery)
        assertEquals("bubble tea", Catalog.comfort("treat")?.giphyQuery)
        assertTrue((Catalog.moods.map { it.giphyQuery } + Catalog.comforts.map { it.giphyQuery }).all { it.isNotBlank() && it.length <= 50 })
        assertEquals("hug", Catalog.stickerQueries.first().second)
        assertEquals(12, Catalog.stickerQueries.size)
    }

    /** 選貼圖的快速關鍵字：推薦的在最前面，同一個關鍵字、同一個名字的不會出現兩次 */
    @Test fun stickerChipsHaveNoDuplicates() {
        val cheer = Catalog.comfort("cheer")!!.let { it.label to it.giphyQuery }
        assertEquals("加油" to "you can do it", cheer)
        val chips = stickerChips(cheer)
        assertEquals(listOf(cheer, "熱門" to ""), chips.take(2))
        assertEquals("只有一個「加油」", 1, chips.count { it.first == "加油" })
        assertEquals(13, chips.size)
        val hug = stickerChips("抱抱" to "hug")
        assertEquals(1, hug.count { it.second == "hug" })
        assertEquals(13, hug.size)
        assertEquals(listOf("熱門" to "") + Catalog.stickerQueries, stickerChips(null))
        for (m in Catalog.moods.map { it.label to it.giphyQuery } + Catalog.comforts.map { it.label to it.giphyQuery }) {
            val c = stickerChips(m)
            assertEquals(m.first, c.size, c.map { it.first }.distinct().size)
            assertEquals(m.first, c.size, c.map { it.second }.distinct().size)
        }
    }

    /** 首頁「收到的安撫」只看最近 3 天收到、還沒看過的（0.2.1 以前從來沒有標過看過，以前的不會被翻出來） */
    @Test fun homeOnlyShowsRecentReceivedComforts() = runTest {
        val s = DemoRepository(backgroundScope).also { it.running = false }.state.value
        val now = Instant.parse("2026-10-21T12:00:00Z")
        fun comfort(id: Long, hoursAgo: Long, to: String = ME, readAt: String? = null) =
            Comfort(id, senderId = if (to == ME) PARTNER else ME, receiverId = to, kind = "hug", createdAt = now.minus(Duration.ofHours(hoursAgo)).toString(), readAt = readAt)
        fun received(vararg c: Comfort) = Derived.receivedComfort(s.copy(comforts = c.toList()), now)?.id
        assertEquals(5L, received(comfort(6, 1, to = PARTNER), comfort(5, 2), comfort(4, 30)))
        assertEquals("看過的跳過", 4L, received(comfort(5, 2, readAt = now.toString()), comfort(4, 30)))
        assertEquals(3L, received(comfort(3, 71)))
        assertNull("超過 3 天", received(comfort(2, 73)))
        assertNull(received(comfort(1, 24 * 20), comfort(0, 24 * 30)))
        // 示範模式昨天晚上收到的那則一定看得到
        assertEquals(DemoRepository.STICKER, Derived.receivedComfort(s)?.sticker)
    }
}
