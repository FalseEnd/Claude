package tw.xiaoliangkou.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.graphics.Bitmap
import android.os.Looper
import kotlinx.coroutines.Job
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tw.xiaoliangkou.app.data.Giphy
import tw.xiaoliangkou.app.data.RealtimeClient
import tw.xiaoliangkou.app.data.giphyHttp
import tw.xiaoliangkou.app.service.Notifier
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 背景收到附了貼圖的通知：先發文字的，抓到不會動的那張再換成有大圖的同一則；用假的 GIPHY，不連網路 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class StickerNotificationTest {
    private val sticker = "L4c8BNDK4STL7B5LdV"
    private val app get() = RuntimeEnvironment.getApplication() as TestXlkApp
    private val nm get() = app.getSystemService(NotificationManager::class.java)

    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    /** 關著的話，假的 GIPHY 等它打開才回應（模擬網路慢） */
    @Volatile private var gate = CountDownLatch(0)

    private lateinit var png: ByteArray

    @Before fun setUp() {
        // 先畫好（第一次用到 Robolectric 的繪圖要載入一陣子，不要算在 5 秒的抓圖時間裡）
        png = ByteArrayOutputStream().also { FakeStickers.bitmap(sticker).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        app.container.giphy = Giphy(
            giphyHttp.newBuilder().addInterceptor { chain ->
                val req = chain.request()
                requests += req.url.toString()
                gate.await(10, TimeUnit.SECONDS)
                Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(png.toResponseBody("image/gif".toMediaType())).build()
            }.build(),
            sdk = 34,
        )
    }

    private fun comfort(id: Long, sticker: String?, body: String = "傳了一張貼圖給你，點開看看 👀") = RealtimeClient.Event.Notification(
        id, "comfort", "小雨 給了你一個大大的抱抱", body,
        buildJsonObject { put("comfort_id", 7); put("sticker", sticker) },
    )

    /** 通知列上 outbox id 這一則（沒有就是 null） */
    private fun shown(id: Long): Notification? = nm.activeNotifications.firstOrNull { it.id == id.toInt() + 100 }?.notification
    private fun Notification.template() = extras.getString(Notification.EXTRA_TEMPLATE)
    private fun Notification.hasPicture() = extras.containsKey(Notification.EXTRA_PICTURE) || extras.containsKey(Notification.EXTRA_PICTURE_ICON)

    /** 背景抓圖的工作做完（回到主執行緒的那一段要讓 Looper 跑） */
    private fun finish(job: Job?) {
        val end = System.currentTimeMillis() + 10_000
        while (job != null && !job.isCompleted) {
            if (System.currentTimeMillis() > end) throw AssertionError("等不到抓圖做完")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun textFirstThenTheSameNotificationGetsThePicture() {
        gate = CountDownLatch(1)
        val job = app.container.postNotification(comfort(41, sticker)) { true }
        // 圖還沒抓到：文字的已經在了
        val text = shown(41)!!
        assertEquals("android.app.Notification\$BigTextStyle", text.template())
        assertFalse(text.hasPicture())
        assertEquals(0, text.flags and Notification.FLAG_ONLY_ALERT_ONCE)

        gate.countDown()
        finish(job)
        val big = shown(41)!!
        assertEquals("android.app.Notification\$BigPictureStyle", big.template())
        assertTrue(big.hasPicture())
        assertNotNull(big.getLargeIcon())
        assertTrue("換圖不會再響一次", big.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals("傳了一張貼圖給你，點開看看 👀", big.extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT).toString())
        assertEquals("小雨 給了你一個大大的抱抱", big.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("同一則，不會多一則", 1, nm.activeNotifications.size)
        assertEquals(listOf("https://media.giphy.com/media/$sticker/200w_s.gif"), requests.toList())
    }

    @Test fun invalidStickerIsNeverFetched() {
        assertNull(app.container.postNotification(comfort(42, "../../evil")) { true })
        assertNull(app.container.postNotification(comfort(43, null)) { true })
        assertNull(app.container.postNotification(RealtimeClient.Event.Notification(44, "mood", "小雨 現在覺得開心", "", null)) { true })
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(requests.isEmpty())
        for (id in 42L..44L) assertEquals("android.app.Notification\$BigTextStyle", shown(id)!!.template())
    }

    @Test fun dismissedNotificationIsNotPostedAgain() {
        gate = CountDownLatch(1)
        val job = app.container.postNotification(comfort(45, sticker)) { true }
        assertNotNull(shown(45))
        // 抓圖的時候使用者把通知滑掉了
        nm.cancel(45 + 100)
        gate.countDown()
        finish(job)
        assertEquals(1, requests.size)
        assertNull(shown(45))
        assertTrue(nm.activeNotifications.isEmpty())
    }

    @Test fun notPostedAfterLoggingOut() {
        var current = true
        gate = CountDownLatch(1)
        val job = app.container.postNotification(comfort(46, sticker)) { current }
        current = false
        gate.countDown()
        finish(job)
        assertEquals(1, requests.size)
        assertEquals("登出了就留著文字的", "android.app.Notification\$BigTextStyle", shown(46)!!.template())
    }

    /** 很長的悄悄話：大圖下面只放得下一兩行，所以照樣展開成全文，貼圖放在右邊（一樣不會再響一次） */
    @Test fun longMessageKeepsItsFullText() {
        val long = "今天辛苦了，晚上一起吃熱炒好不好？我已經在路上了，" + "等一下到了打給你，記得多穿一件外套喔。".repeat(3)
        val job = app.container.postNotification(comfort(48, sticker, long)) { true }
        finish(job)
        val n = shown(48)!!
        assertEquals("android.app.Notification\$BigTextStyle", n.template())
        assertEquals(long, n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
        assertFalse(n.hasPicture())
        assertNotNull("貼圖在右邊", n.getLargeIcon())
        assertTrue(n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(1, requests.size)
    }

    @Test fun shortTextGetsTheBigPicture() {
        assertTrue("附上一張貼圖 ✨".length <= Notifier.PICTURE_BODY)
        val job = app.container.postNotification(comfort(49, sticker, "附上一張貼圖 ✨")) { true }
        finish(job)
        assertEquals("android.app.Notification\$BigPictureStyle", shown(49)!!.template())
    }

    /** Android 12 以前把 App 的通知整個關掉：通知不會出現，也就不用向 GIPHY 抓圖 */
    @Test fun nothingFetchedWhenNotificationsAreOff() {
        shadowOf(nm).setNotificationsEnabled(false)
        assertNull(app.container.postNotification(comfort(50, sticker)) { true })
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(requests.isEmpty())
    }

    /** 「抱抱、心情與悄悄話」這個頻道被封鎖了：一樣不抓圖 */
    @Test fun nothingFetchedWhenTheChannelIsBlocked() {
        nm.createNotificationChannel(NotificationChannel(Notifier.CH_LOVE, "抱抱、心情與悄悄話", NotificationManager.IMPORTANCE_NONE))
        assertEquals(NotificationManager.IMPORTANCE_NONE, nm.getNotificationChannel(Notifier.CH_LOVE).importance)
        assertFalse(app.container.notifier.canShow("comfort"))
        assertTrue("其他頻道照常", app.container.notifier.canShow("place"))
        assertNull(app.container.postNotification(comfort(51, sticker)) { true })
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(requests.isEmpty())
    }

    @Test fun nothingWithoutNotificationPermission() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertNull(app.container.postNotification(comfort(47, sticker)) { true })
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(nm.activeNotifications.isEmpty())
        assertTrue("不能發通知就不用抓圖", requests.isEmpty())
    }
}
