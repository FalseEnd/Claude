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
import tw.xiaoliangkou.app.data.DemoRepository
import tw.xiaoliangkou.app.data.DemoRepository.Companion.ME
import tw.xiaoliangkou.app.data.DemoRepository.Companion.PARTNER
import tw.xiaoliangkou.app.data.RemoteRepository
import tw.xiaoliangkou.app.data.SettingsStore

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
            assertEquals(SettingsStore.RECENT_STICKERS, recent.size)
            assertEquals("s29", recent.first())
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
}
