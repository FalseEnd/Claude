package tw.xiaoliangkou.app

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.data.Avatars
import tw.xiaoliangkou.app.data.DemoRepository
import tw.xiaoliangkou.app.data.Sharing
import tw.xiaoliangkou.app.service.Notifier
import tw.xiaoliangkou.app.service.ServiceNotice
import tw.xiaoliangkou.app.ui.StatusText
import tw.xiaoliangkou.app.widget.WidgetSnapshot
import tw.xiaoliangkou.app.widget.Widgets
import java.time.Instant
import java.time.LocalDate

/** 頭像、常駐通知顯示對方狀態、桌面小工具 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class StatusWidgetTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val demo = DemoRepository(scope).also { it.running = false }
    private val s: AppState get() = demo.state.value
    private val context get() = RuntimeEnvironment.getApplication()

    @After fun tearDown() = scope.cancel()

    private fun sharing(location: Boolean = true, device: Boolean = true, mood: Boolean = true, pausedUntil: String? = null) =
        s.copy(partnerSharing = Sharing(s.partner!!.id, shareLocation = location, shareDevice = device, shareMood = mood, pausedUntil = pausedUntil))

    // ------------------------------------------------------------ 對方狀態的文字
    @Test fun statusListsWhatThePartnerShares() {
        val st = StatusText.of(s)
        assertEquals(listOf("place", "battery", "mood", "phone", "network", "steps"), st.items.map { it.key })
        assertTrue(st.item("place")!!.text.startsWith("在 小雨公司 · 相距 "))
        assertEquals("🔋 34%", st.item("battery").toString())
        assertEquals("😩 好累：開了一整天的會", st.item("mood").toString())
        assertNull(st.note)
        // 桌面小工具不會隨時更新：不寫「正在用手機」，只寫幾點用過
        assertTrue(StatusText.of(s, live = false).items.none { it.text == "正在用手機" })
    }

    @Test fun statusHidesWhatIsNotShared() {
        val noDevice = StatusText.of(sharing(device = false))
        assertEquals(listOf("place", "mood"), noDevice.items.map { it.key })
        val nothing = StatusText.of(sharing(location = false, device = false, mood = false))
        assertTrue(nothing.items.isEmpty())
        assertEquals("小雨 目前沒有分享位置和手機狀態", nothing.note)
        val paused = StatusText.of(sharing(pausedUntil = Instant.now().plusSeconds(3600).toString()))
        assertTrue(paused.items.none { it.key == "place" || it.key == "battery" })
        assertTrue(paused.note!!.startsWith("小雨 暫停分享到 "))
    }

    // ------------------------------------------------------------ 常駐通知
    @Test fun ongoingNotificationShowsPartnerStatus() {
        val n = ServiceNotice.of(s, showStatus = true, items = StatusText.DEFAULTS)
        assertTrue(n.status)
        assertTrue(n.title, n.title.startsWith("小雨 在 小雨公司"))
        assertTrue(n.text.contains("🔋 34%") && n.text.contains("好累"))
        assertFalse("位置已經在標題了", n.big!!.contains("📍"))
        assertTrue(n.sub!!.startsWith("在一起第 "))
        assertEquals(Avatars.emoji("🐰"), n.avatar)
        assertTrue(n.locate && n.hug)
        // 只選電量
        val battery = ServiceNotice.of(s, true, setOf(StatusText.BATTERY))
        assertEquals("小雨", battery.title)
        assertEquals("🔋 34%", battery.text)
        // 關掉、或還沒配對：只說明背景連線
        assertFalse(ServiceNotice.of(s, false, StatusText.DEFAULTS).status)
        assertFalse(ServiceNotice.of(s.copy(partner = null, couple = null), true, StatusText.DEFAULTS).status)
    }

    @Test fun notificationHasAvatarAndButtons() {
        val notifier = Notifier(context).also { it.createChannels() }
        val n = notifier.serviceNotification(ServiceNotice.of(s, true, StatusText.DEFAULTS))
        assertEquals(Notifier.CH_STATUS, n.channelId)
        assertTrue(n.extras.getCharSequence(Notification.EXTRA_TITLE).toString().startsWith("小雨 在"))
        assertNotNull(n.getLargeIcon())
        assertEquals(listOf("📍 現在在哪？", "🤗 抱抱"), n.actions.map { it.title.toString() })
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        val plain = notifier.serviceNotification(ServiceNotice.of(s, false, StatusText.DEFAULTS))
        assertEquals(Notifier.CH_SERVICE, plain.channelId)
        assertEquals(0, NotificationCompat.getActionCount(plain))
    }

    // ------------------------------------------------------------ 桌面小工具
    private fun views(v: android.widget.RemoteViews): View = v.apply(context, FrameLayout(context))
    private fun View.text(id: Int) = findViewById<TextView>(id).let { if (it.visibility == View.VISIBLE) it.text.toString() else null }

    @Test fun widgetsShowTogetherDaysAndPartnerStatus() {
        val snap = WidgetSnapshot.of(s)
        assertEquals(WidgetSnapshot.Kind.PAIRED, snap.kind)
        // 存起來再讀回來要一樣（App 沒在跑時小工具用存著的這份）
        val json = Json.encodeToString(WidgetSnapshot.serializer(), snap)
        assertEquals(snap, Json.decodeFromString(WidgetSnapshot.serializer(), json))

        val today = LocalDate.now()
        val days = tw.xiaoliangkou.app.core.Dates.daysTogether(s.togetherSince!!, today)
        val together = views(Widgets.together(context, snap, today))
        assertEquals("%,d".format(days), together.text(R.id.together_days))
        assertEquals("天", together.text(R.id.together_unit))

        val partner = views(Widgets.partner(context, snap))
        assertEquals("小雨（示範）", partner.text(R.id.partner_name))
        assertTrue(partner.text(R.id.place)!!.startsWith("📍 在 小雨公司"))
        assertEquals("🔋 34%　😩 好累：開了一整天的會", partner.text(R.id.line1))
        assertTrue(partner.text(R.id.updated)!!.contains("在一起第 "))
        assertEquals("🤗 抱抱", partner.text(R.id.hug_button))
    }

    @Test fun widgetsBeforeSignInOrPairing() {
        val out = WidgetSnapshot.of(null)
        assertEquals(WidgetSnapshot.Kind.SIGNED_OUT, out.kind)
        val together = views(Widgets.together(context, out))
        assertEquals("💞", together.text(R.id.together_days))
        assertNull(together.text(R.id.together_unit))
        assertEquals("登入後顯示在一起的天數", together.text(R.id.together_sub))
        val partner = views(Widgets.partner(context, out))
        assertEquals("登入並配對後，這裡會顯示對方的狀態", partner.text(R.id.place))
        assertNull(partner.text(R.id.hug_button))

        val unpaired = WidgetSnapshot.of(s.copy(partner = null, couple = null))
        assertEquals(WidgetSnapshot.Kind.UNPAIRED, unpaired.kind)
        assertNull("還沒配對不能留著對方的資料", unpaired.partnerAvatar)
        assertEquals("配對後開始數在一起的天數", views(Widgets.together(context, unpaired)).text(R.id.together_sub))
    }

    // ------------------------------------------------------------ 頭像
    @Test fun photoAvatarsAreSmallSquareJpegs() {
        val src = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(255, 180, 200)) }
        val square = Avatars.crop(src, Avatars.cropOf(1200, 900, 600f, 1f, 0f, 0f))
        assertEquals(Avatars.PHOTO_PX, square.width)
        val encoded = Avatars.encodePhoto(square)
        assertTrue(encoded.startsWith("data:image/jpeg;base64,"))
        assertTrue("存進資料庫的字串要小", encoded.length < Avatars.MAX_BYTES * 4 / 3 + 40)
        assertTrue(Avatars.isPhoto(encoded))
        assertEquals(Avatars.PHOTO_PX, Avatars.photoOf(encoded)!!.width)
        assertNull(Avatars.photoOf("data:image/jpeg;base64,not-an-image"))
        assertEquals("🐰", Avatars.emojiOf(Avatars.emoji("🐰")))
        assertNull(Avatars.emojiOf("https://example.com/a.png"))
        assertEquals("哲", Avatars.initial(" 阿哲 "))
    }

    @Test fun cropFollowsZoomAndDrag() {
        // 900×1200 的直式照片放進 600 px 的框：短邊剛好填滿
        assertEquals(Avatars.Crop(0, 150, 900), Avatars.cropOf(900, 1200, 600f, 1f, 0f, 0f))
        // 放大兩倍：裁中間一半
        assertEquals(Avatars.Crop(225, 375, 450), Avatars.cropOf(900, 1200, 600f, 2f, 0f, 0f))
        // 把照片往右拖 100 px：框裡看到的是照片偏左的部分
        assertEquals(Avatars.Crop(150, 375, 450), Avatars.cropOf(900, 1200, 600f, 2f, 100f, 0f))
        // 拖太遠也不會裁到照片外面
        assertEquals(Avatars.Crop(0, 0, 450), Avatars.cropOf(900, 1200, 600f, 2f, 5000f, 5000f))
        // 照片顯示成 600×800：左右不能動，上下各可以移 100 px
        assertEquals(0f to 100f, Avatars.maxOffset(900, 1200, 600f, 1f))
    }

    @Test fun circleAvatarsHaveTransparentCorners() {
        val photo = Avatars.encodePhoto(Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) })
        for (avatar in listOf(photo, Avatars.emoji("🐻"), null)) {
            val bmp = Avatars.circle(context, avatar, "小雨", 0xFFFF7FA8.toInt(), 120, 4f)
            assertEquals(120, bmp.width)
            assertEquals(0, Color.alpha(bmp.getPixel(2, 2)))
            assertEquals(255, Color.alpha(bmp.getPixel(60, 60)))
        }
    }
}
