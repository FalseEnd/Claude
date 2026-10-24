package tw.xiaoliangkou.app

import android.app.Notification
import android.appwidget.AppWidgetManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.data.Avatars
import tw.xiaoliangkou.app.data.DemoRepository
import tw.xiaoliangkou.app.data.Sharing
import tw.xiaoliangkou.app.service.Notifier
import tw.xiaoliangkou.app.service.ServiceNotice
import tw.xiaoliangkou.app.service.StatusActionReceiver
import tw.xiaoliangkou.app.ui.StatusText
import tw.xiaoliangkou.app.widget.PartnerWidget
import tw.xiaoliangkou.app.widget.TogetherWidget
import tw.xiaoliangkou.app.widget.WidgetSnapshot
import tw.xiaoliangkou.app.widget.Widgets
import java.time.Instant
import java.time.LocalDate
import kotlin.math.roundToInt

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
        assertEquals(listOf("📍 現在在哪？", "🤗 抱抱", "💞 更多安撫"), n.actions.map { it.title.toString() })
        // 「更多安撫」打開 App 的安撫
        val more = Shadows.shadowOf(n.actions[2].actionIntent).savedIntent
        assertEquals(MainActivity::class.java.name, more.component?.className)
        assertEquals("comfort", more.getStringExtra(Notifier.EXTRA_ROUTE))
        // 對方沒分享位置：沒有「現在在哪？」，多一個親親（最多三個按鈕）
        val noLocation = notifier.serviceNotification(ServiceNotice.of(s.copy(partnerSharing = s.partnerSharing!!.copy(shareLocation = false)), true, StatusText.DEFAULTS))
        assertEquals(listOf("🤗 抱抱", "😘 親親", "💞 更多安撫"), noLocation.actions.map { it.title.toString() })
        val kiss = Shadows.shadowOf(noLocation.actions[1].actionIntent).savedIntent
        assertEquals(StatusActionReceiver.COMFORT, kiss.action)
        assertEquals("kiss", kiss.getStringExtra(StatusActionReceiver.EXTRA_KIND))
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        val plain = notifier.serviceNotification(ServiceNotice.of(s, false, StatusText.DEFAULTS))
        assertEquals(Notifier.CH_SERVICE, plain.channelId)
        assertEquals(0, NotificationCompat.getActionCount(plain))
    }

    // ------------------------------------------------------------ 桌面小工具
    private fun views(v: android.widget.RemoteViews): View = v.apply(context, FrameLayout(context))
    private fun View.text(id: Int) = findViewById<TextView>(id).let { if (it.visibility == View.VISIBLE) it.text.toString() else null }

    /** 照小工具的大小（dp）排好版面 */
    private fun laidOut(v: android.widget.RemoteViews, w: Int, h: Int): View {
        val d = context.resources.displayMetrics.density
        val frame = FrameLayout(context)
        val view = v.apply(context, frame)
        frame.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        fun exactly(dp: Int) = View.MeasureSpec.makeMeasureSpec((dp * d).roundToInt(), View.MeasureSpec.EXACTLY)
        frame.measure(exactly(w), exactly(h))
        frame.layout(0, 0, frame.measuredWidth, frame.measuredHeight)
        return view
    }

    private fun View.name() = runCatching { context.resources.getResourceEntryName(id) }.getOrDefault(javaClass.simpleName)

    /** 看得到的東西都在小工具裡面、每行字的高度夠；不會變「…」的字（天數、名字旁的時間…）要整個放得下 */
    private fun assertFits(root: View, what: String) {
        val bounds = Rect(0, 0, root.width, root.height)
        fun walk(v: View, x: Int, y: Int) {
            if (v.visibility != View.VISIBLE) return
            val r = Rect(x, y, x + v.width, y + v.height)
            assertTrue("$what：${v.name()} $r 超出小工具 $bounds", bounds.contains(r))
            if (v is TextView) {
                val text = v.text.toString()
                assertTrue("$what：${v.name()}「$text」被壓扁", v.height - v.compoundPaddingTop - v.compoundPaddingBottom >= v.layout.height)
                if (v.ellipsize == null) {
                    assertTrue("$what：${v.name()}「$text」被切掉", v.paint.measureText(text) <= v.width - v.compoundPaddingLeft - v.compoundPaddingRight + 1f)
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) v.getChildAt(i).let { walk(it, x + it.left, y + it.top) }
        }
        walk(root, 0, 0)
    }

    private val today = LocalDate.now()
    private val days get() = tw.xiaoliangkou.app.core.Dates.daysTogether(s.togetherSince!!, today)

    @Test fun widgetsShowTogetherDaysAndPartnerStatus() {
        val snap = WidgetSnapshot.of(s)
        assertEquals(WidgetSnapshot.Kind.PAIRED, snap.kind)
        // 存起來再讀回來要一樣（App 沒在跑時小工具用存著的這份）
        val json = Json.encodeToString(WidgetSnapshot.serializer(), snap)
        assertEquals(snap, Json.decodeFromString(WidgetSnapshot.serializer(), json))

        // 一格高的 2×1：頭像、天數、一行小字
        val together = views(Widgets.together(context, snap, today))
        assertEquals("%,d".format(days), together.text(R.id.together_days))
        assertEquals("天", together.text(R.id.together_unit))
        assertEquals(View.VISIBLE, together.findViewById<View>(R.id.heart).visibility)
        // 2×1 放不下「在一起（示範）」：小字要寫「示範」
        assertNull(together.text(R.id.together_label))
        assertTrue(together.text(R.id.together_sub)!!.startsWith("示範"))
        for ((w, h) in listOf(196 to 56, 130 to 96, 150 to 120, 160 to 160)) {
            val v = views(Widgets.together(context, snap, today, size = SizeF(w.toFloat(), h.toFloat())))
            assertTrue("${w}×$h 要看得出是示範", v.text(R.id.together_label) == "在一起（示範）" || v.text(R.id.together_sub)!!.startsWith("示範"))
        }
        // 拉寬就放得下「在一起」三個字（示範模式還有「（示範）」），小字就不用再寫
        val wide = views(Widgets.together(context, snap, today, size = SizeF(320f, 56f)))
        assertEquals("在一起（示範）", wide.text(R.id.together_label))
        assertEquals("第 520 天還有 6 天 🎉", wide.text(R.id.together_sub))
        val real = snap.copy(demo = false)
        assertEquals("在一起", views(Widgets.together(context, real, today, size = SizeF(200f, 56f))).text(R.id.together_label))
        assertEquals("520 倒數 6 天", views(Widgets.together(context, real, today)).text(R.id.together_sub))
        // 一個月內沒有里程碑：小字寫兩個人的名字
        val noMilestone = real.copy(togetherSince = today.minusDays(40).toString())
        assertEquals("阿哲♥小雨", views(Widgets.together(context, noMilestone, today)).text(R.id.together_sub))
        assertEquals("阿哲 ♥ 小雨", views(Widgets.together(context, noMilestone, today, size = SizeF(320f, 56f))).text(R.id.together_sub))

        // 一格高的 4×1：名字、一行狀態（整項整項放，不會切在一半）、🤗
        assertEquals(listOf("📍 小雨公司", "🔋 34%", "😩 好累", "🔕 勿擾中"), Widgets.statusParts(snap).take(4))
        val partner = views(Widgets.partner(context, snap))
        assertEquals("小雨（示範）", partner.text(R.id.partner_name))
        assertTrue(partner.text(R.id.status)!!.startsWith("📍 小雨公司 · 🔋 34% · 😩 好累"))
        assertTrue(partner.text(R.id.updated)!!.endsWith("更新"))
        assertEquals("🤗", partner.text(R.id.hug_button))
        // 🤗 可以按的範圍至少 48dp 寬
        val hug = laidOut(Widgets.partner(context, snap), 320, 56).findViewById<View>(R.id.hug_button)
        assertTrue(hug.width >= (48 * context.resources.displayMetrics.density).roundToInt())
        // 窄一點：狀態少放幾項；名字太長就不寫更新時間
        val narrow = views(Widgets.partner(context, snap.copy(demo = false), size = SizeF(240f, 56f)))
        assertEquals("📍 小雨公司 · 🔋 34%", narrow.text(R.id.status))
        assertTrue(narrow.text(R.id.updated)!!.endsWith("更新"))
        assertNull(views(Widgets.partner(context, snap.copy(partnerName = "全世界最可愛的小雨"), size = SizeF(240f, 56f))).text(R.id.updated))

        // 直向手機上比較高的一格：還是一行，字和頭像大一點
        assertNotNull(views(Widgets.partner(context, snap, size = SizeF(276f, 102f))).text(R.id.status))
        // 拉到兩格高：換成大版面，每項分行
        val tall = views(Widgets.partner(context, snap, size = SizeF(320f, 140f)))
        assertTrue(tall.text(R.id.place)!!.startsWith("📍 在 小雨公司"))
        assertEquals("🔋 34%　😩 好累：開了一整天的會", tall.text(R.id.line1))
        assertTrue(tall.text(R.id.updated)!!.contains("在一起第 "))
        assertEquals("🤗 抱抱", tall.text(R.id.hug_button))
        // 窄的 3×2 放不下大版面：一格高的排法往下多放幾行，一樣整項整項放
        val narrowTall = views(Widgets.partner(context, snap, size = SizeF(196f, 220f))).text(R.id.status)!!.lines()
        assertTrue(narrowTall.toString(), narrowTall.size >= 3)
        assertTrue(narrowTall.joinToString(" · ").startsWith("📍 小雨公司 · 🔋 34% · 😩 好累"))
        val big = views(Widgets.together(context, snap, today, size = SizeF(160f, 160f)))
        assertEquals("%,d".format(days), big.text(R.id.together_days))
        // 示範資料在一起第 514 天：一個月內有第 520 天（示範模式放不下「在一起（示範）」，小字寫「示範」）
        assertEquals("示範 · 520 倒數 6 天", big.text(R.id.together_sub))
        val bigReal = views(Widgets.together(context, snap.copy(demo = false), today, size = SizeF(160f, 160f)))
        assertEquals("在一起", bigReal.text(R.id.together_label))
        assertEquals("第 520 天還有 6 天 🎉", bigReal.text(R.id.together_sub))
    }

    @Test fun pausedOrNothingSharedShowsWhy() {
        val paused = WidgetSnapshot.of(sharing(pausedUntil = Instant.now().plusSeconds(3600).toString()))
        assertTrue(Widgets.statusParts(paused).first().startsWith("暫停分享到 "))
        val nothing = WidgetSnapshot.of(sharing(location = false, device = false, mood = false))
        assertEquals(listOf("目前沒有分享位置和手機狀態"), Widgets.statusParts(nothing))
        assertEquals("目前沒有分享位置和手機狀態", views(Widgets.partner(context, nothing)).text(R.id.status))
        // 只有一句說明又放不下：留逗號前面那段，不要切在一半
        val pausedOnly = WidgetSnapshot.of(sharing(mood = false, pausedUntil = Instant.now().plusSeconds(3600).toString()))
        assertTrue(Widgets.statusParts(pausedOnly).single().contains("，時間到會自動恢復"))
        val status = views(Widgets.partner(context, pausedOnly, size = SizeF(240f, 56f))).text(R.id.status)!!
        assertTrue(status, status.matches(Regex("暫停分享到 \\d{1,2}:\\d{2}")))
        val waiting = WidgetSnapshot.of(s).copy(place = null, items = emptyList(), note = "還沒收到 小雨 的狀態，等對方打開小倆口 📡")
        assertEquals("還沒收到 小雨 的狀態", views(Widgets.partner(context, waiting, size = SizeF(240f, 56f))).text(R.id.status))
    }

    @Test fun widgetsBeforeSignInOrPairing() {
        val out = WidgetSnapshot.of(null)
        assertEquals(WidgetSnapshot.Kind.SIGNED_OUT, out.kind)
        val together = views(Widgets.together(context, out))
        assertEquals("💞", together.text(R.id.together_days))
        assertNull(together.text(R.id.together_unit))
        assertEquals("還沒登入", together.text(R.id.together_sub))
        assertEquals("登入後顯示在一起的天數", views(Widgets.together(context, out, size = SizeF(320f, 56f))).text(R.id.together_sub))
        val partner = views(Widgets.partner(context, out))
        assertEquals("登入並配對後，這裡會顯示對方的狀態", partner.text(R.id.status))
        assertNull(partner.text(R.id.hug_button))
        assertNull(partner.text(R.id.updated))
        assertNull(views(Widgets.partner(context, out, size = SizeF(320f, 140f))).text(R.id.hug_button))

        val unpaired = WidgetSnapshot.of(s.copy(partner = null, couple = null))
        assertEquals(WidgetSnapshot.Kind.UNPAIRED, unpaired.kind)
        assertNull("還沒配對不能留著對方的資料", unpaired.partnerAvatar)
        assertEquals("配對後開始數在一起的天數", views(Widgets.together(context, unpaired, size = SizeF(320f, 56f))).text(R.id.together_sub))
    }

    private val sizes = listOf(110, 130, 150, 170, 196, 250, 276, 320, 400).flatMap { w -> listOf(40, 48, 56, 72, 96, 102, 120, 140, 160, 220).map { w to it } }

    private fun assertWidgetsFit(label: String) {
        val snaps = listOf(
            "示範" to WidgetSnapshot.of(s),
            "正式" to WidgetSnapshot.of(s).copy(demo = false),
            "長名字" to WidgetSnapshot.of(s).copy(myName = "大寶貝阿哲哲", partnerName = "全世界最可愛的小雨"),
            "暫停" to WidgetSnapshot.of(sharing(pausedUntil = Instant.now().plusSeconds(3600).toString())),
            "沒登入" to WidgetSnapshot.of(null),
            "沒配對" to WidgetSnapshot.of(s.copy(partner = null, couple = null)),
        )
        for ((name, snap) in snaps) for ((w, h) in sizes) {
            val together = laidOut(Widgets.together(context, snap, today, size = SizeF(w.toFloat(), h.toFloat())), w, h)
            assertFits(together, "$label 在一起 $name ${w}×$h")
            assertNotNull("$label ${w}×$h 要看得到天數", together.text(R.id.together_days))
            if (w < 180) continue
            val partner = laidOut(Widgets.partner(context, snap, size = SizeF(w.toFloat(), h.toFloat())), w, h)
            assertFits(partner, "$label 對方狀態 $name ${w}×$h")
            assertNotNull(partner.text(R.id.partner_name))
        }
    }

    /** 一格高（40dp 起）到拉大，各種寬度：都不會被切掉 */
    @Test fun widgetsFitEverySize() = assertWidgetsFit("")

    @Test @Config(fontScale = 1.3f) fun widgetsFitWithLargeFont() = assertWidgetsFit("大字")

    /** Android 14 以上字體最大可以調到 2 倍 */
    @Test @Config(fontScale = 2.0f) fun widgetsFitWithHugeFont() = assertWidgetsFit("特大字")

    /** 真的放到桌面：照桌面給的大小畫（Android 12 以上一份 RemoteViews 帶著直向、橫向兩種大小） */
    @Test fun widgetsFollowTheirSizeOnTheHomeScreen() {
        val manager = AppWidgetManager.getInstance(context)
        val shadow = Shadows.shadowOf(manager)
        val row = shadow.createWidget(PartnerWidget::class.java, R.layout.widget_partner)
        val tall = shadow.createWidget(PartnerWidget::class.java, R.layout.widget_partner)
        fun sizes(vararg s: SizeF) = Bundle().apply { putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(*s)) }
        manager.updateAppWidgetOptions(row, sizes(SizeF(320f, 96f), SizeF(560f, 52f)))
        manager.updateAppWidgetOptions(tall, sizes(SizeF(320f, 200f), SizeF(560f, 120f)))
        Widgets.updateAll(context, WidgetSnapshot.of(s))
        assertNotNull(shadow.getViewFor(row).text(R.id.status))
        assertTrue(shadow.getViewFor(tall).text(R.id.place)!!.startsWith("📍"))

        val together = shadow.createWidget(TogetherWidget::class.java, R.layout.widget_together)
        Widgets.updateAll(context, WidgetSnapshot.of(s))
        assertEquals("%,d".format(days), shadow.getViewFor(together).text(R.id.together_days))
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
