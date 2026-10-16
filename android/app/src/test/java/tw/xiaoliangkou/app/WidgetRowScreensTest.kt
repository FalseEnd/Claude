package tw.xiaoliangkou.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.SizeF
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
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
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.screens.WidgetsScreen
import tw.xiaoliangkou.app.ui.theme.XlkTheme
import tw.xiaoliangkou.app.widget.WidgetSnapshot
import tw.xiaoliangkou.app.widget.Widgets
import java.time.Instant

/**
 * 一格高的桌面小工具：用真的 RemoteViews 照桌面上常見的大小畫出來，檢查排版。
 * 執行：./gradlew recordRoborazziDebug --tests '*WidgetRowScreensTest*'，圖片在 app/build/screens/
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h1600dp-xhdpi")
class WidgetRowScreensTest {
    @get:Rule val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob())
    private val demo = DemoRepository(scope).also { it.running = false }
    private val s: AppState get() = demo.state.value
    private val vm by lazy { AppViewModel(RuntimeEnvironment.getApplication()) }

    @After fun tearDown() = scope.cancel()

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            XlkTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
            }
        }
        compose.mainClock.advanceTimeBy(2500)
        compose.onRoot().captureRoboImage("build/screens/$name.png")
    }

    private fun testPhoto(): Bitmap {
        val bmp = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 900f, 1200f, 0xFFFFC2D6.toInt(), 0xFF8EC5FF.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 900f, 1200f, p)
        p.shader = null
        p.color = 0xFFFFE0B2.toInt(); c.drawCircle(450f, 560f, 260f, p)
        p.color = 0xFF5D4037.toInt(); c.drawCircle(360f, 500f, 30f, p); c.drawCircle(540f, 500f, 30f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 22f; c.drawArc(330f, 520f, 570f, 700f, 20f, 140f, false, p)
        return bmp
    }

    /** 我是照片頭像、對方是表情頭像（跟 App 裡的預覽一樣） */
    private fun photoAvatar() = runBlocking {
        demo.updateAvatar(Avatars.encodePhoto(Avatars.crop(testPhoto(), Avatars.cropOf(900, 1200, 600f, 1.3f, 0f, 60f))))
    }

    private class Tile(val label: String, val size: SizeF, val view: (FrameLayout) -> View)

    private fun remote(label: String, w: Int, h: Int, views: (android.content.Context, SizeF) -> RemoteViews) =
        SizeF(w.toFloat(), h.toFloat()).let { size -> Tile(label, size) { frame -> views(frame.context, size).apply(frame.context, frame) } }

    private fun together(snap: WidgetSnapshot, w: Int, h: Int, label: String = "${w}×${h}dp") =
        remote(label, w, h) { context, size -> Widgets.together(context, snap, interactive = false, size = size) }

    private fun partner(snap: WidgetSnapshot, w: Int, h: Int, label: String = "${w}×${h}dp") =
        remote(label, w, h) { context, size -> Widgets.partner(context, snap, interactive = false, size = size) }

    /** 桌面挑小工具時的預覽：直接用版面的預設值 */
    private fun preview(layout: Int, w: Int, h: Int, label: String) =
        Tile(label, SizeF(w.toFloat(), h.toFloat())) { frame -> LayoutInflater.from(frame.context).inflate(layout, frame, false) }

    /** 像桌布的背景，一列可以放好幾個小工具 */
    @Composable
    private fun Board(dark: Boolean, vararg rows: List<Tile>) {
        val wallpaper = if (dark) listOf(Color(0xFF1E2238), Color(0xFF3A2440)) else listOf(Color(0xFFB9D7FF), Color(0xFFFFC9DC))
        Column(
            Modifier.fillMaxWidth().testTag("board").background(Brush.verticalGradient(wallpaper)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    row.forEach { tile ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(tile.label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.9f))
                            WidgetView(tile)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun WidgetView(tile: Tile) = AndroidView(
        factory = { FrameLayout(it) },
        update = { frame -> frame.removeAllViews(); frame.addView(tile.view(frame)) },
        modifier = Modifier.size(tile.size.width.dp, tile.size.height.dp),
    )

    private fun board(name: String, dark: Boolean = false, vararg rows: List<Tile>) {
        compose.setContent { XlkTheme(dark = dark) { Board(dark, *rows) } }
        compose.mainClock.advanceTimeBy(1500)
        compose.onNodeWithTag("board").captureRoboImage("build/screens/$name.png")
    }

    private fun sharing(location: Boolean = true, device: Boolean = true, mood: Boolean = true, pausedUntil: String? = null) =
        s.copy(partnerSharing = Sharing(s.partner!!.id, shareLocation = location, shareDevice = device, shareMood = mood, pausedUntil = pausedUntil))

    /** 一格高：2×1、4×1，還有直向手機上一格比較高的樣子 */
    @Test fun oneRow() {
        photoAvatar()
        val snap = WidgetSnapshot.of(s)
        board(
            "60_widgets_row", false,
            listOf(together(snap, 150, 56, "在一起 2×1 · 150×56"), together(snap, 196, 56, "196×56")),
            listOf(partner(snap, 320, 56, "對方狀態 4×1 · 320×56")),
            listOf(together(snap, 130, 96, "直向 2×1 · 130×96"), together(snap, 110, 40, "最小 110×40")),
            listOf(partner(snap, 240, 56, "3×1 · 240×56")),
            listOf(partner(snap, 320, 96, "直向 4×1 · 320×96")),
            listOf(together(snap, 320, 56, "拉寬 4×1 · 320×56")),
        )
    }

    /** 拉高：還沒到兩格時整列置中，兩格以上換大版面 */
    @Test fun resizedTaller() {
        photoAvatar()
        val snap = WidgetSnapshot.of(s)
        board(
            "61_widgets_tall", false,
            listOf(partner(snap, 320, 120, "4×1 拉高 · 320×120")),
            listOf(partner(snap, 320, 140, "4×2 · 320×140")),
            listOf(together(snap, 150, 120, "2×1 拉高 · 150×120"), together(snap, 160, 160, "2×2 · 160×160")),
            listOf(together(snap, 320, 100, "直向 4×1 · 320×100")),
        )
    }

    @Test @Config(qualifiers = "+night") fun dark() {
        photoAvatar()
        val snap = WidgetSnapshot.of(s)
        board(
            "62_widgets_dark", true,
            listOf(together(snap, 150, 56, "2×1 · 150×56"), together(snap, 196, 64, "196×64")),
            listOf(partner(snap, 320, 56, "4×1 · 320×56")),
            listOf(partner(snap, 320, 140, "4×2 · 320×140")),
        )
    }

    /** 還沒登入、還沒配對、暫停分享、沒有分享 */
    @Test fun states() {
        val paused = WidgetSnapshot.of(sharing(pausedUntil = Instant.now().plusSeconds(3600).toString()))
        val nothing = WidgetSnapshot.of(sharing(location = false, device = false, mood = false))
        val out = WidgetSnapshot.of(null)
        val unpaired = WidgetSnapshot.of(s.copy(partner = null, couple = null))
        val real = WidgetSnapshot.of(s).copy(demo = false)
        board(
            "63_widgets_states", false,
            listOf(together(real, 150, 56, "正式使用 · 150×56"), together(real, 200, 56, "200×56")),
            listOf(together(real, 196, 100, "直向 2×1 · 196×100"), together(out, 150, 56, "還沒登入")),
            listOf(together(unpaired, 196, 56, "還沒配對")),
            listOf(partner(out, 320, 56, "還沒登入")),
            listOf(partner(unpaired, 240, 56, "還沒配對 · 240×56")),
            listOf(partner(paused, 320, 56, "暫停分享")),
            listOf(partner(nothing, 320, 56, "沒有分享")),
            listOf(preview(R.layout.widget_together, 150, 56, "挑小工具時的預覽 · 2×1")),
            listOf(preview(R.layout.widget_partner, 320, 56, "挑小工具時的預覽 · 4×1")),
        )
    }

    @Test fun widgetsScreen() {
        photoAvatar()
        shot("64_widgets_screen") { WidgetsScreen(s, vm) {} }
    }
}
