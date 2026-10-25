package tw.xiaoliangkou.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
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
}
