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
import androidx.compose.ui.test.performScrollTo
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
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.Fmt
import tw.xiaoliangkou.app.ui.screens.HomeScreen
import tw.xiaoliangkou.app.ui.screens.PrivacyScreen
import tw.xiaoliangkou.app.ui.theme.XlkTheme
import java.time.Instant

/**
 * 藍牙偵測「就在身邊」：首頁上面寫「就在你身邊」（沒分享位置也寫）、手機卡片的位置、分享與隱私裡的開關。
 * 截圖：./gradlew testDebugUnitTest -Proborazzi.test.record=true --tests 'tw.xiaoliangkou.app.NearbyScreensTest'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h1600dp-xhdpi")
class NearbyScreensTest {
    @get:Rule val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob())
    private val demo = DemoRepository(scope).also { it.running = false }
    private val s: AppState get() = demo.state.value
    private val vm by lazy { AppViewModel(RuntimeEnvironment.getApplication()) }
    private val since = Instant.now().minusSeconds(25 * 60)

    @After fun tearDown() = scope.cancel()

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { XlkTheme { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() } } }
        compose.mainClock.advanceTimeBy(1500)
        compose.waitForIdle()
    }

    private fun has(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun homeSaysTogether() {
        show { HomeScreen(s.copy(partnerNearSince = since), vm, {}, {}) }
        assertTrue(has("💞 小雨 就在你身邊"))
        assertFalse("GPS 算的距離不寫了", has("相距 "))
        assertTrue("手機卡片的位置", has("就在你身邊 💞"))
        compose.onRoot().captureRoboImage("build/screens/111_nearby_home.png")
    }

    @Test fun togetherEvenWithoutLocationSharing() {
        val st = s.copy(partnerNearSince = since, partnerSharing = s.partnerSharing!!.copy(shareLocation = false))
        show { HomeScreen(st, vm, {}, {}) }
        assertTrue(has("💞 小雨 就在你身邊"))
        assertFalse(has("小雨 沒有分享位置"))
    }

    @Test fun apartUsesGps() {
        show { HomeScreen(s, vm, {}, {}) }
        assertTrue(has("相距 "))
        assertFalse(has("就在你身邊"))
    }

    @Test fun privacyCard() {
        show { PrivacyScreen(s.copy(partnerNearSince = since), vm) {} }
        compose.onNodeWithText("藍牙偵測在身邊").performScrollTo()
        assertTrue(has("💞 小雨 就在你身邊（${Fmt.time(since)} 起）"))
        assertTrue(has("只有 小雨 的手機認得出來"))
        compose.onRoot().captureRoboImage("build/screens/112_nearby_privacy.png")
    }

    @Test fun privacyCardInDemo() {
        show { PrivacyScreen(s, vm) {} }
        compose.onNodeWithText("藍牙偵測在身邊").performScrollTo()
        assertTrue(has("示範模式：到「我的 › 示範控制台」讓 小雨 走到你身邊看看"))
    }

    @Test fun demoConsoleToggles() {
        demo.demoAction("nearby")
        val end = System.currentTimeMillis() + 5_000
        while (!s.partnerNearby && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue(s.partnerNearby)
        demo.demoAction("nearby")
        val end2 = System.currentTimeMillis() + 5_000
        while (s.partnerNearby && System.currentTimeMillis() < end2) Thread.sleep(20)
        assertFalse(s.partnerNearby)
    }
}
