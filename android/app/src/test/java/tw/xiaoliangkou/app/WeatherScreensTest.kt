package tw.xiaoliangkou.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import tw.xiaoliangkou.app.core.Weather
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.data.CoupleRepository
import tw.xiaoliangkou.app.data.DemoRepository
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.screens.HomeWeatherCard
import tw.xiaoliangkou.app.ui.screens.WeatherScreen
import tw.xiaoliangkou.app.ui.theme.XlkTheme

/**
 * 天氣與外出建議：兩邊的天氣、六個數字、建議，提醒對方。
 * 截圖：./gradlew testDebugUnitTest -Proborazzi.test.record=true --tests 'tw.xiaoliangkou.app.WeatherScreensTest'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h2000dp-xhdpi")
class WeatherScreensTest {
    @get:Rule val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob())
    private val demo = DemoRepository(scope).also { it.running = false }
    private val s: AppState get() = demo.state.value
    private val vm by lazy { AppViewModel(RuntimeEnvironment.getApplication()) }
    private val weather = FakeWeather()

    @After fun tearDown() = scope.cancel()

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { XlkTheme { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() } } }
        compose.mainClock.advanceTimeBy(2500)
        compose.waitForIdle()
    }

    private fun capture(name: String, large: Boolean = false) =
        compose.onRoot().captureRoboImage(if (large) "build/screens-large/$name.png" else "build/screens/$name.png")

    private fun has(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun idleUntil(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 30_000
        while (!cond() && System.currentTimeMillis() < end) { ShadowLooper.idleMainLooper(); compose.mainClock.advanceTimeBy(50); Thread.sleep(10) }
        assertTrue(cond())
    }

    private fun setRepo(repo: CoupleRepository) {
        @Suppress("UNCHECKED_CAST")
        val field = vm.container.javaClass.getDeclaredField("_repo").apply { isAccessible = true }.get(vm.container) as MutableStateFlow<CoupleRepository?>
        field.value = repo
    }

    private fun screen(state: AppState = s) = show { WeatherScreen(state, vm, back = {}, open = {}, source = weather) }

    @Test fun bothSidesWithEverything() {
        screen()
        assertTrue(has("小雨 那邊"))
        assertTrue(has("我這邊"))
        assertTrue(has("台北市松山區 · 下雨"))
        assertTrue(has("台北市大安區 · 晴天"))
        for (label in listOf("體感", "濕度", "能見度", "降雨機率", "紫外線", "空氣品質")) assertEquals(label, 2, compose.onAllNodesWithText(label).fetchSemanticsNodes().size)
        assertTrue(has("90%"))
        assertTrue(has("AQI 118"))
        assertTrue(has("對敏感族群不健康 · PM2.5 41"))
        assertTrue(has("過量級"))
        assertTrue(has("2.4 公里"))
        assertTrue(has("外出建議：出門要多注意"))
        assertTrue(has("正在下雨：記得帶傘"))
        assertTrue(has("紫外線過量級（9）：擦防曬、戴帽子或撐陽傘"))
        assertTrue(has("Open-Meteo.com"))
        capture("70_weather")
    }

    @Test fun remindingThePartnerSendsAComfortWithTheAdvice() {
        val sent = mutableListOf<Pair<String, String?>>()
        setRepo(object : CoupleRepository by demo {
            override suspend fun sendComfort(kind: String, message: String?, sticker: String?) { sent += kind to message }
        })
        screen()
        val expected = Weather.reminder(Weather.advice(FakeWeather.RAINY_COOL))
        assertTrue(has("提醒 小雨：「$expected」"))
        compose.onNodeWithText("提醒 小雨").performClick()
        idleUntil { sent.isNotEmpty() }
        assertEquals(listOf("safe" to expected), sent)
        idleUntil { has("已經提醒了") }
    }

    /** 資料庫還沒有 0.2.6 的安撫：改送抱抱，提醒的話一樣 */
    @Test fun oldDatabaseRemindsWithAHug() {
        val sent = mutableListOf<String>()
        setRepo(object : CoupleRepository by demo {
            override suspend fun sendComfort(kind: String, message: String?, sticker: String?) { sent += kind }
        })
        screen(s.copy(dbSchema = 5))
        compose.onNodeWithText("提醒 小雨").performClick()
        idleUntil { sent.isNotEmpty() }
        assertEquals(listOf("hug"), sent)
    }

    @Test fun partnerNotSharingLocation() {
        screen(s.copy(partnerSharing = s.partnerSharing!!.copy(shareLocation = false)))
        assertTrue(has("小雨 沒有分享位置，看不到那邊的天氣"))
        assertFalse(has("提醒 小雨"))
        assertTrue("自己的照樣看得到", has("台北市大安區 · 晴天"))
        assertEquals(listOf(DemoRepository.MY_LOCATION.lat to DemoRepository.MY_LOCATION.lng), weather.asked.distinct())
    }

    @Test fun niceWeatherAndFailures() {
        weather.partner = FakeWeather.NICE
        weather.mine = FakeWeather.NICE
        screen()
        assertTrue(has("外出建議：很適合出門"))
        assertTrue(has("天氣不錯，沒什麼要特別注意的"))
        assertTrue(has("你那邊今天天氣不錯，有空出去走走吧"))
    }

    @Test fun cannotFetch() {
        weather.fail = "連不到天氣資料"
        screen()
        assertEquals(2, compose.onAllNodesWithText("連不到天氣資料").fetchSemanticsNodes().size)
    }

    @Test fun homeCard() {
        var opened: String? = null
        show { Box(Modifier.padding(16.dp)) { HomeWeatherCard(s, vm, { opened = it }, weather) } }
        assertTrue(has("今天的天氣"))
        assertTrue(has("14°"))
        assertTrue(has("33°"))
        assertTrue(has("記得帶傘"))
        assertTrue(has("擦防曬、戴帽子或撐陽傘"))
        capture("70b_home_weather")
        compose.onNodeWithText("今天的天氣").performClick()
        assertEquals("weather", opened)
    }

    @Test fun homeCardWhileLoading() {
        weather.hang = true
        show { Box(Modifier.padding(16.dp)) { HomeWeatherCard(s.copy(partnerSharing = s.partnerSharing!!.copy(shareLocation = false)), vm, {}, weather) } }
        assertTrue(has("讀取中…"))
        assertTrue(has("沒有分享位置"))
    }

    // ================================================================ 字放大（360 dp、1.3 倍）

    @Config(qualifiers = "zh-rTW-w360dp-h2400dp-xhdpi", fontScale = 1.3f)
    @Test fun weatherWithLargeText() {
        screen()
        assertTrue(has("對敏感族群不健康"))
        capture("70_weather", large = true)
    }

    @Config(qualifiers = "zh-rTW-w360dp-h900dp-xhdpi", fontScale = 1.3f)
    @Test fun homeCardWithLargeText() {
        show { Box(Modifier.padding(16.dp)) { HomeWeatherCard(s, vm, {}, weather) } }
        capture("70b_home_weather", large = true)
    }
}
