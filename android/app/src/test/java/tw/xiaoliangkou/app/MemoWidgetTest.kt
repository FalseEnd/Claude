package tw.xiaoliangkou.app

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Bitmap
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.data.DemoMedia
import tw.xiaoliangkou.app.data.DemoRepository
import tw.xiaoliangkou.app.data.MediaCache
import tw.xiaoliangkou.app.ui.theme.XlkTheme
import tw.xiaoliangkou.app.widget.MemoWidget
import tw.xiaoliangkou.app.widget.MemoWidgetSnapshot
import java.io.File
import kotlin.math.min

/**
 * 桌面小工具「備忘錄」：放在桌面的備忘錄依小工具大小排版（3×2、2×1、拉大），照片縮圖、沒有釘選時的提示。
 * 截圖：./gradlew recordRoborazziDebug --tests '*MemoWidgetTest*'，圖片在 app/build/screens/
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h900dp-xhdpi")
class MemoWidgetTest {
    @get:Rule val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob())
    private val demo = DemoRepository(scope).also { it.running = false }
    private val s: AppState get() = demo.state.value
    private val context get() = RuntimeEnvironment.getApplication()
    private val media by lazy { MediaCache(context) { null } }

    @After fun tearDown() {
        scope.cancel()
        runBlocking { media.clear() }
    }

    /** 示範照片直接放進快取（不用登入、不用網路） */
    private fun primeCache() = runBlocking {
        listOf(DemoMedia.PICNIC, DemoMedia.TRIP).forEach { media.put(it.path, DemoMedia.sample(it.path)!!) }
    }

    /** 把三則都放在桌面：我的採買清單、小雨的旅行（有照片）、我的語音備忘 */
    private fun pinThree(): AppState = runBlocking {
        val trip = s.memo("m3")!!
        demo.saveMemo(trip.id, trip.title, trip.body, trip.media, trip.shared, true, trip.color)
        demo.saveMemo(null, "晚安語音 🌙", "睡不著的時候聽", listOf(DemoMedia.VOICE), true, true, "sky")
        s
    }

    private fun apply(v: RemoteViews, w: Int, h: Int): View {
        val frame = FrameLayout(context)
        val view = v.apply(context, frame)
        frame.addView(view)
        val dm = context.resources.displayMetrics.density
        frame.measure(View.MeasureSpec.makeMeasureSpec((w * dm).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec((h * dm).toInt(), View.MeasureSpec.EXACTLY))
        frame.layout(0, 0, frame.measuredWidth, frame.measuredHeight)
        return view
    }

    private fun View.shown(id: Int) = findViewById<View>(id)?.visibility == View.VISIBLE
    private fun View.text(id: Int) = findViewById<TextView>(id).let { if (it.visibility == View.VISIBLE) it.text.toString() else null }

    /** 小工具裡的便利貼（由上往下或並排，略過中間的空隙） */
    private fun View.notes(): List<View> = listOf(R.id.memo_list, R.id.memo_row).flatMap { id ->
        val list = findViewById<ViewGroup>(id)
        if (list.visibility != View.VISIBLE) emptyList()
        else (0 until list.childCount).map { list.getChildAt(it) }.filter { it.findViewById<View>(R.id.memo_title) != null }
    }

    /** [this] 在 [root] 裡的位置（y） */
    private fun View.topIn(root: View): Int {
        var y = 0
        var v: View = this
        while (v !== root) { y += v.top; v = v.parent as View }
        return y
    }

    /**
     * 每一段字都完整顯示，沒有被切掉半行：TextView 的高度放得下它要顯示的每一行，而且整段都在小工具裡面。
     * [noEllipsis] 為 true 時也檢查沒有被 TextView 加…（內容只在兩項中間換行）。
     */
    private fun assertFits(root: View, label: String, noEllipsis: Boolean = false) {
        val texts = root.notes().flatMap { n -> listOf(R.id.memo_title, R.id.memo_body, R.id.memo_meta).map { n.findViewById<TextView>(it) } } +
            listOf(R.id.memo_header_title, R.id.memo_header_more, R.id.memo_empty_text).map { root.findViewById<TextView>(it) }
        val shown = texts.filter { tv -> generateSequence<View>(tv) { it.parent as? View }.takeWhile { it !== root.parent }.all { it.visibility == View.VISIBLE } }
        assertTrue("$label 有字", shown.isNotEmpty())
        for (tv in shown) {
            val layout = tv.layout
            assertNotNull("$label「${tv.text}」", layout)
            val lines = min(layout.lineCount, tv.maxLines)
            val need = layout.getLineTop(lines) + tv.compoundPaddingTop + tv.compoundPaddingBottom
            assertTrue("$label「${tv.text}」被切掉：高 ${tv.height}，要 $need", tv.height >= need)
            assertTrue("$label「${tv.text}」超出小工具", tv.topIn(root) + tv.height <= root.height)
            if (noEllipsis && tv.id == R.id.memo_body) {
                (0 until lines).forEach { assertEquals("$label「${tv.text}」第 ${it + 1} 行被加了…", 0, layout.getEllipsisCount(it)) }
            }
        }
    }

    /** 常用的大小：3×2（直的、橫的）、2×1、最小、4×1、拉大、矮的 3×2 */
    private val sizes = listOf(250 to 180, 340 to 120, 160 to 72, 110 to 48, 330 to 72, 330 to 280, 250 to 110, 250 to 230)

    /** 三則、一則、沒有放在桌面、還沒登入：每種大小都放得下 */
    private fun assertAllSizesFit(noEllipsis: Boolean, pinned: Pair<MemoWidgetSnapshot, Map<String, Bitmap>> = threePinned()) {
        val (three, thumbs) = pinned
        val one = three.copy(memos = three.memos.take(1), total = 1)
        val none = MemoWidgetSnapshot.of(s.copy(memos = emptyList()))
        for ((w, h) in sizes) {
            for ((name, snap) in listOf("三則" to three, "一則" to one, "沒有放" to none, "沒登入" to MemoWidgetSnapshot.of(null))) {
                assertFits(render(snap, w, h, thumbs), "${w}×$h $name", noEllipsis)
            }
        }
    }

    private fun render(snap: MemoWidgetSnapshot, w: Int, h: Int, thumbs: Map<String, Bitmap> = emptyMap()) =
        apply(MemoWidget.render(context, snap, w.toFloat(), h.toFloat(), thumbs), w, h)

    // ------------------------------------------------------------ 資料

    @Test fun snapshotHasPinnedMemosOnly() {
        val snap = MemoWidgetSnapshot.of(s)
        assertTrue(snap.signedIn)
        assertTrue(snap.demo)
        assertEquals(listOf("m1"), snap.memos.map { it.id })
        assertEquals("週末採買清單 🛒", snap.memos[0].title)
        assertNull("自己的不用寫作者", snap.memos[0].author)
        assertEquals(1, snap.total)
        // 存起來再讀回來要一樣（App 沒在跑時小工具用存著的這份）
        assertEquals(snap, Json.decodeFromString(MemoWidgetSnapshot.serializer(), Json.encodeToString(MemoWidgetSnapshot.serializer(), snap)))

        val three = MemoWidgetSnapshot.of(pinThree())
        assertEquals(3, three.total)
        val trip = three.memos.first { it.id == "m3" }
        assertEquals("小雨", trip.author)
        assertEquals(DemoMedia.TRIP.path, trip.photo)
        assertEquals(1, trip.photos)
        assertEquals(1, three.memos.first { it.title.startsWith("晚安") }.voices)

        // 最多 3 則，但記得一共有幾則
        runBlocking { demo.saveMemo(null, "", "多一則", emptyList(), true, true, "peach") }
        val four = MemoWidgetSnapshot.of(s)
        assertEquals(MemoWidgetSnapshot.MAX, four.memos.size)
        assertEquals(4, four.total)

        assertFalse(MemoWidgetSnapshot.of(null).signedIn)
        assertTrue(MemoWidgetSnapshot.of(s.copy(memos = s.memos.map { it.copy(pinned = false) })).memos.isEmpty())
    }

    // ------------------------------------------------------------ 依大小排版

    @Test fun threeByTwoShowsTitlesAndFirstLines() {
        primeCache()
        val one = MemoWidgetSnapshot.of(s)
        val v = render(one, 250, 180)
        assertTrue(v.shown(R.id.memo_header))
        assertEquals("備忘錄（示範）", v.text(R.id.memo_header_title))
        assertFalse(v.shown(R.id.memo_empty))
        val note = v.notes().single()
        assertEquals("週末採買清單 🛒", note.text(R.id.memo_title))
        // 只有一則：內容一行一項
        assertEquals("🥚 雞蛋\n🥛 鮮奶\n🍓 草莓\n🧻 衛生紙\n🐱 貓砂", note.text(R.id.memo_body))

        val snap = MemoWidgetSnapshot.of(pinThree())
        val thumbs = runBlocking { MemoWidget.thumbs(snap, media) }
        assertNotNull(thumbs[DemoMedia.TRIP.path])
        val three = render(snap, 250, 180, thumbs)
        val notes = three.notes()
        assertEquals(3, notes.size)
        val trip = notes.first { it.text(R.id.memo_title) == "想一起去的地方 ✈️" }
        assertTrue("有照片就顯示縮圖", trip.findViewById<ImageView>(R.id.memo_thumb).let { it.visibility == View.VISIBLE && it.drawable != null })
        assertEquals("小雨", trip.text(R.id.memo_meta))
        assertNotNull(trip.text(R.id.memo_body))
        // 每一張都在小工具裡面，沒有被切掉
        val bottom = three.findViewById<View>(R.id.memo_list).let { it.top + it.height }
        notes.forEach { n -> assertTrue(n.bottom <= bottom) }
        // 採買清單擠成一行：停在完整的項目，不會切在「衛…」
        val shopping = notes.first { it.text(R.id.memo_title) == "週末採買清單 🛒" }.text(R.id.memo_body)!!
        assertTrue(shopping, shopping.endsWith("　…") && "衛" !in shopping)
        assertFits(three, "3×2", noEllipsis = true)
    }

    /** 並排的時候每張的標題都對齊（從上面開始寫） */
    @Test fun sideBySideTitlesLineUp() {
        val (three, thumbs) = threePinned()
        for ((w, h) in listOf(340 to 120, 330 to 72)) {
            val v = render(three, w, h, thumbs)
            val tops = v.notes().map { it.findViewById<View>(R.id.memo_title).topIn(v) }
            assertEquals("${w}×$h", 3, tops.size)
            assertEquals("${w}×$h 標題對齊：$tops", 1, tops.distinct().size)
        }
    }

    /** 標準字型：每種大小都不會切到字，內容只在兩項中間換行 */
    @Test fun everySizeFits() = assertAllSizesFit(noEllipsis = true)

    /** 系統字型調大（1.3 倍，「最大」）：一樣不會切到字 */
    @Config(fontScale = 1.3f)
    @Test fun everySizeFitsWithLargeFont() {
        val (three, thumbs) = threePinned()
        assertAllSizesFit(noEllipsis = false, three to thumbs)
        // 3×2 放了三則：放不下三則就放兩則，標題列寫還有幾則
        val v = render(three, 250, 180, thumbs)
        assertEquals(2, v.notes().size)
        assertEquals("還有 1 則", v.text(R.id.memo_header_more))
    }

    /** Android 14 以上可以調到 2 倍（非線性放大）：小的大小只放標題也不能切到 */
    @Config(fontScale = 2.0f)
    @Test fun everySizeFitsWithHugeFont() = assertAllSizesFit(noEllipsis = false)

    /** 盡量三則都放得下：由上往下排、又寬又矮就並排、標題列只在不會少放一則時才放 */
    @Test fun layoutFollowsTheWidgetSize() {
        fun p(w: Int, h: Int, n: Int = 3) = MemoWidget.plan(w.toFloat(), h.toFloat(), n)
        with(p(250, 180)) { assertEquals(3, count); assertFalse(side); assertFalse(header) }
        with(p(250, 180, 1)) { assertEquals(1, count); assertTrue(header) }
        with(p(250, 230)) { assertEquals(3, count); assertTrue("一般手機的 3×2 放得下標題列", header) }
        with(p(340, 120)) { assertEquals(3, count); assertTrue("橫的桌面：並排", side) }
        with(p(330, 72)) { assertEquals(3, count); assertTrue(side) }
        with(p(250, 110)) { assertEquals(2, count); assertTrue(side) }
        assertEquals("2×1 只放第一則", 1, p(160, 72).count)
        assertEquals(1, p(110, 40).count)
        with(p(330, 400)) { assertEquals(3, count); assertTrue(header); assertFalse(side) }
        // 每張便利貼至少放得下標題和一行內容
        listOf(p(250, 180), p(340, 120), p(330, 72), p(250, 110), p(330, 280)).forEach { assertTrue(it.toString(), it.noteH >= 49f && it.noteW >= 100f) }

        // 系統字型調大（1.3 倍）：一樣的大小少放一則，每張還是放得下標題和一行內容
        val big = MemoWidget.Companion.Fonts { it * 1.3f }
        fun b(w: Int, h: Int, n: Int = 3) = MemoWidget.plan(w.toFloat(), h.toFloat(), n, big)
        with(b(250, 180)) { assertEquals(2, count); assertFalse(side) }
        with(b(340, 120)) { assertEquals(2, count); assertTrue(side) }
        assertEquals(1, b(160, 72).count)
        listOf(b(250, 180), b(340, 120), b(330, 280)).forEach { assertTrue(it.toString(), it.noteH >= 12f + big.height(14f) + 1f + big.height(12f)) }
    }

    /** 內容排進幾行：只在兩項中間換行，不會把一項拆成兩行 */
    @Test fun bodyLinesBreakBetweenItems() {
        val trip = listOf("京都賞楓 🍁", "墾丁看海 🌊", "九份吃芋圓")
        assertEquals("京都賞楓 🍁\n墾丁看海 🌊\n九份吃芋圓", MemoWidget.pack(trip, 3, 200f, 12f))
        assertEquals("京都賞楓 🍁　墾丁看海 🌊\n九份吃芋圓", MemoWidget.pack(trip, 2, 200f, 12f))
        // 最後一行放不下：停在最後一個完整的項目，接「…」
        assertEquals("京都賞楓 🍁　墾丁看海 🌊　…", MemoWidget.pack(trip, 1, 200f, 12f))
        assertEquals("京都賞楓 🍁　墾丁看海 🌊　九份吃芋圓", MemoWidget.pack(trip, 1, 260f, 12f))
        val shopping = listOf("🥚 雞蛋", "🥛 鮮奶", "🍓 草莓", "🧻 衛生紙", "🐱 貓砂")
        assertEquals("🥚 雞蛋　🥛 鮮奶\n🍓 草莓　…", MemoWidget.pack(shopping, 2, 120f, 12f))
        assertEquals("🥚 雞蛋　🥛 鮮奶　🍓 草莓　🧻 衛生紙\n🐱 貓砂", MemoWidget.pack(shopping, 2, 300f, 12f))
        // 一項就太長：照樣放（TextView 會加…）
        assertEquals("🥚 雞蛋", MemoWidget.pack(shopping, 1, 40f, 12f))
        assertEquals("京都賞楓 🍁…", MemoWidget.pack(trip, 1, 86f, 12f))
        assertEquals("", MemoWidget.pack(shopping, 0, 120f, 12f))
    }

    @Test fun twoByOneShowsJustTheFirstMemo() {
        primeCache()
        val snap = MemoWidgetSnapshot.of(pinThree())
        val thumbs = runBlocking { MemoWidget.thumbs(snap, media) }
        for ((w, h) in listOf(160 to 72, 110 to 48)) {
            val v = render(snap, w, h, thumbs)
            assertFalse("${w}×$h 不放標題列", v.shown(R.id.memo_header))
            val note = v.notes().single()
            assertEquals(snap.memos[0].title, note.text(R.id.memo_title))
            val title = note.findViewById<TextView>(R.id.memo_title)
            assertTrue("${w}×$h 標題沒有被切掉", title.height >= title.lineHeight)
        }
    }

    @Test fun biggerWidgetsShowMoreLinesAndHowManyAreHidden() {
        runBlocking { repeat(2) { demo.saveMemo(null, "清單 ${it + 1}", "一\n二\n三", emptyList(), true, true, "pink") } }
        val snap = MemoWidgetSnapshot.of(pinThree())
        assertEquals(5, snap.total)
        val v = render(snap, 330, 400)
        assertEquals(3, v.notes().size)
        assertEquals("還有 2 則", v.text(R.id.memo_header_more))
    }

    @Test fun hintWhenNothingIsPinned() {
        val none = MemoWidgetSnapshot.of(s.copy(memos = s.memos.map { it.copy(pinned = false) }))
        val v = render(none, 250, 180)
        assertTrue(v.shown(R.id.memo_empty))
        assertTrue(v.notes().isEmpty())
        assertEquals("在備忘錄裡打開『放在桌面』", v.text(R.id.memo_empty_text))
        // 窄的時候自己斷行
        assertEquals("在備忘錄裡打開\n『放在桌面』", render(none, 160, 72).text(R.id.memo_empty_text))
        assertEquals("登入後，放在桌面的\n備忘錄會出現在這裡", render(MemoWidgetSnapshot.of(null), 250, 180).text(R.id.memo_empty_text))
    }

    /** Android 12 以上：桌面給的每種大小都畫一份 */
    @Test fun sizedViewsForEachLauncherSize() {
        val snap = MemoWidgetSnapshot.of(pinThree())
        val options = Bundle().apply {
            putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(SizeF(250f, 180f), SizeF(160f, 72f)))
        }
        val views = MemoWidget.views(context, snap, options, emptyMap())
        assertNotNull(views.apply(context, FrameLayout(context)))
        assertNotNull(MemoWidget.views(context, snap, null, emptyMap()).apply(context, FrameLayout(context)))
        // 選小工具時看到的預覽也是 RemoteViews：只能用小工具允許的元件
        assertNotNull(RemoteViews(context.packageName, R.layout.widget_memo_preview).apply(context, FrameLayout(context)))
    }

    private fun waitUntil(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!cond()) {
            assertTrue(what, System.currentTimeMillis() < end)
            Thread.sleep(50)
        }
    }

    /**
     * App 的資料變了：存一份資料；桌面上有這個小工具才抓照片縮圖（剛加到桌面時補抓），
     * 抓不到的之後再試；取消釘選或登出就刪掉縮圖。
     */
    @Test fun publishSavesSnapshotAndThumbnails() {
        primeCache()
        val snap = MemoWidgetSnapshot.of(pinThree())
        val dir = File(context.filesDir, "widget_memo")
        fun thumbs() = dir.listFiles()?.size ?: 0

        // 桌面上還沒有這個小工具：只存資料，不抓縮圖
        assertTrue(runBlocking { MemoWidget.publish(context, snap, media) })
        assertEquals(snap, MemoWidget.load(context))
        assertEquals(0, thumbs())

        // 加到桌面：onUpdate 補抓縮圖
        val manager = AppWidgetManager.getInstance(context)
        shadowOf(manager).bindAppWidgetId(7, ComponentName(context, MemoWidget::class.java))
        MemoWidget().onUpdate(context, manager, intArrayOf(7))
        waitUntil("加到桌面後補抓縮圖") { thumbs() == 1 }

        // 照片還抓不到（沒網路、對方還沒上傳好）：回傳 false，之後 refresh 再抓
        val ghost = "c/demo/memo/ghost.jpg"
        val missing = snap.copy(memos = snap.memos.map { if (it.photo != null) it.copy(photo = ghost) else it })
        assertFalse(runBlocking { MemoWidget.publish(context, missing, media) })
        assertEquals("舊的縮圖用不到了", 0, thumbs())
        runBlocking { media.put(ghost, DemoMedia.sample(DemoMedia.TRIP.path)!!) }
        assertTrue(runBlocking { MemoWidget.refresh(context, media) })
        assertEquals(1, thumbs())

        // 登出：縮圖刪掉
        assertTrue(runBlocking { MemoWidget.publish(context, MemoWidgetSnapshot.of(null), media) })
        assertFalse(MemoWidget.load(context).signedIn)
        assertEquals(0, thumbs())
    }

    // ------------------------------------------------------------ 截圖

    @Composable
    private fun Widget(label: String, views: RemoteViews, w: Int, h: Int) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, color = Color.White, style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
            AndroidView(
                factory = { FrameLayout(it) },
                update = { frame -> frame.removeAllViews(); frame.addView(views.apply(frame.context, frame)) },
                modifier = Modifier.width(w.dp).height(h.dp),
            )
        }
    }

    /** 放在桌布上看：[sizes] 是（標籤、快照、寬、高） */
    private fun wallpaper(name: String, dark: Boolean = false, items: List<Triple<String, MemoWidgetSnapshot, Pair<Int, Int>>>, thumbs: Map<String, Bitmap> = emptyMap()) {
        val all = items.map { (label, snap, size) -> Triple(label, MemoWidget.render(context, snap, size.first.toFloat(), size.second.toFloat(), thumbs, interactive = false), size) }
        compose.setContent {
            XlkTheme(dark = dark) {
                val bg = if (dark) listOf(Color(0xFF2E2A45), Color(0xFF1B2433)) else listOf(Color(0xFF9FC7F5), Color(0xFFF7B6CC))
                Column(
                    Modifier.fillMaxSize().background(Brush.verticalGradient(bg)).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    all.forEach { (label, views, size) -> Widget(label, views, size.first, size.second) }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.onRoot().captureRoboImage("build/screens/$name.png")
    }

    private fun threePinned(): Pair<MemoWidgetSnapshot, Map<String, Bitmap>> {
        primeCache()
        val snap = MemoWidgetSnapshot.of(pinThree())
        return snap to runBlocking { MemoWidget.thumbs(snap, media) }
    }

    @Test fun shot3x2() {
        val one = MemoWidgetSnapshot.of(s)
        val (three, thumbs) = threePinned()
        wallpaper("54b_widget_memo_3x2", items = listOf(
            Triple("3×2：放了三則", three, 250 to 180),
            Triple("3×2：放了一則", one, 250 to 180),
            Triple("3×2（橫的桌面）", three, 340 to 120),
        ), thumbs = thumbs)
    }

    @Test fun shot2x1() {
        val (three, thumbs) = threePinned()
        val photoFirst = three.copy(memos = three.memos.sortedByDescending { it.photo != null })
        wallpaper("54c_widget_memo_2x1", items = listOf(
            Triple("2×1：第一則", three, 160 to 72),
            Triple("2×1：有照片", photoFirst, 160 to 72),
            Triple("2×1（最小）", three, 110 to 48),
            Triple("4×1", photoFirst, 330 to 72),
            Triple("4×3：拉大", three, 330 to 280),
        ), thumbs = thumbs)
    }

    @Config(qualifiers = "+night")
    @Test fun shotDark() {
        val (three, thumbs) = threePinned()
        val none = MemoWidgetSnapshot.of(s.copy(memos = emptyList()))
        wallpaper("54d_widget_memo_dark", dark = true, items = listOf(
            Triple("3×2", three, 250 to 180),
            Triple("2×1", three, 160 to 72),
            Triple("還沒放", none, 250 to 180),
        ), thumbs = thumbs)
    }

    /** 系統字型「最大」（1.3 倍） */
    @Config(fontScale = 1.3f)
    @Test fun shotLargeFont() {
        val (three, thumbs) = threePinned()
        val none = MemoWidgetSnapshot.of(s.copy(memos = emptyList()))
        wallpaper("54f_widget_memo_font130", items = listOf(
            Triple("3×2（字型最大）", three, 250 to 180),
            Triple("2×1", three, 160 to 72),
            Triple("3×2（橫的桌面）", three, 340 to 120),
            Triple("2×1：還沒放", none, 160 to 72),
        ), thumbs = thumbs)
    }

    /** Android 14 以上的 2 倍字型 */
    @Config(fontScale = 2.0f)
    @Test fun shotHugeFont() {
        val (three, thumbs) = threePinned()
        val none = MemoWidgetSnapshot.of(s.copy(memos = emptyList()))
        wallpaper("54g_widget_memo_font200", items = listOf(
            Triple("3×2（2 倍字型）", three, 250 to 180),
            Triple("2×1", three, 160 to 72),
            Triple("3×2（橫的桌面）", three, 340 to 120),
            Triple("2×1（最小）：還沒放", none, 110 to 48),
        ), thumbs = thumbs)
    }

    @Test fun shotEmpty() {
        val none = MemoWidgetSnapshot.of(s.copy(memos = emptyList()))
        wallpaper("54e_widget_memo_empty", items = listOf(
            Triple("3×2：還沒放", none, 250 to 180),
            Triple("2×1：還沒放", none, 160 to 72),
            Triple("還沒登入", MemoWidgetSnapshot.of(null), 250 to 180),
        ))
    }
}
