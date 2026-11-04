package tw.xiaoliangkou.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tw.xiaoliangkou.app.ui.components.CoupleCats
import tw.xiaoliangkou.app.ui.theme.XlkTheme

/**
 * 首頁上面的兩隻小貓：一般、對方難過（掉眼淚）、深色模式、窄的手機。
 * 截圖：./gradlew testDebugUnitTest -Proborazzi.test.record=true --tests 'tw.xiaoliangkou.app.CatsScreensTest'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h420dp-xhdpi")
class CatsScreensTest {
    @get:Rule val compose = createComposeRule()

    private fun cats(name: String) {
        compose.setContent {
            XlkTheme {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    CoupleCats(height = 120.dp)
                    CoupleCats(partnerSad = true, height = 120.dp)
                    CoupleCats(height = 170.dp)
                }
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onRoot().captureRoboImage("build/screens/$name.png")
    }

    @Test fun cats() = cats("90_cats")

    @Config(qualifiers = "w400dp-h420dp-night-xhdpi")
    @Test fun catsAtNight() = cats("90b_cats_night")

    @Config(qualifiers = "w320dp-h420dp-xhdpi")
    @Test fun catsOnASmallPhone() = cats("90c_cats_small")

    /** App 圖示：圓形、圓角方形的遮罩，和單色的主題圖示（手機桌面約 48～64 dp，這裡放大看細節） */
    @Test fun appIcon() {
        compose.setContent {
            Row(Modifier.fillMaxSize().background(Color(0xFF6B6B6B)).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(CircleShape, RoundedCornerShape(28.dp)).forEach { shape ->
                    Box(Modifier.size(96.dp).clip(shape).background(colorResource(R.color.launcher_bg))) {
                        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize().scale(1.5f))
                    }
                }
                Box(Modifier.size(96.dp).clip(CircleShape).background(Color(0xFF2B3A4A))) {
                    Image(painterResource(R.drawable.ic_launcher_monochrome), null, Modifier.fillMaxSize().scale(1.5f), colorFilter = ColorFilter.tint(Color(0xFFCFE3FF)))
                }
                Box(Modifier.size(48.dp).clip(CircleShape).background(colorResource(R.color.launcher_bg))) {
                    Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize().scale(1.5f))
                }
            }
        }
        compose.onRoot().captureRoboImage("build/screens/91_app_icon.png")
    }
}
