package tw.xiaoliangkou.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.data.DemoRepository
import tw.xiaoliangkou.app.data.RemoteRepository
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.screens.HomeScreen
import tw.xiaoliangkou.app.ui.theme.XlkTheme

/**
 * 首頁手機卡片的手機溫度：正常、對方的手機很燙（提醒對方怎麼辦）、自己的手機、舊的資料庫。
 * 截圖：./gradlew testDebugUnitTest -Proborazzi.test.record=true --tests 'tw.xiaoliangkou.app.HeatScreensTest'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h1400dp-xhdpi")
class HeatScreensTest {
    @get:Rule val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob())
    private val demo = DemoRepository(scope).also { it.running = false }
    private val s: AppState get() = demo.state.value
    private val vm by lazy { AppViewModel(RuntimeEnvironment.getApplication()) }

    @After fun tearDown() = scope.cancel()

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { XlkTheme { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() } } }
        compose.mainClock.advanceTimeBy(1500)
        compose.waitForIdle()
    }

    private fun has(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun partner(temp: Double?, thermal: Int?) =
        s.copy(partnerStatus = s.partnerStatus.copy(device = s.partnerStatus.device!!.copy(batteryTemp = temp, thermal = thermal)))

    @Test fun normalTemperature() {
        show { HomeScreen(partner(34.2, 0), vm, {}, {}) }
        assertTrue(has("手機溫度"))
        assertTrue(has("34.2°C"))
        assertTrue(has("正常 😊"))
    }

    @Test fun partnerPhoneIsHot() {
        show { HomeScreen(partner(46.3, 3), vm, {}, {}) }
        assertTrue(has("46.3°C"))
        assertTrue(has("很燙 🔥 · 提醒對方：先停下來，拿掉保護殼、別放在太陽下"))
        compose.onRoot().captureRoboImage("build/screens/110_phone_hot.png")
        // 自己的手機
        compose.onNodeWithText("我").performClick()
        compose.waitForIdle()
        assertTrue(has("31.5°C"))
    }

    @Test fun oldAppOrOldDatabase() {
        show { HomeScreen(partner(null, null).copy(dbSchema = RemoteRepository.PET_SCHEMA), vm, {}, {}) }
        assertFalse("沒有回報就不顯示", has("手機溫度"))
        assertTrue(has("資料庫再更新一次就能看到手機溫度"))
    }

    @Config(qualifiers = "zh-rTW-w360dp-h1400dp-xhdpi", fontScale = 1.3f)
    @Test fun largeText() {
        show { HomeScreen(partner(42.0, 2), vm, {}, {}) }
        assertTrue(has("有點熱 🥵"))
        compose.onRoot().captureRoboImage("build/screens-large/phone_heat.png")
    }
}
