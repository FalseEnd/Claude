package tw.xiaoliangkou.app

import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSensorManager
import tw.xiaoliangkou.app.core.Horoscope
import tw.xiaoliangkou.app.core.Omikuji
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.data.DemoRepository
import tw.xiaoliangkou.app.ui.screens.FortuneContent
import tw.xiaoliangkou.app.ui.screens.FortuneStore
import tw.xiaoliangkou.app.ui.screens.FortuneTab
import tw.xiaoliangkou.app.ui.screens.ShakeGate
import tw.xiaoliangkou.app.ui.screens.chineseNumber
import tw.xiaoliangkou.app.ui.theme.XlkTheme
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.random.Random

/**
 * 星座運勢與御神籤：截圖（55～59）＋抽籤、搖手機、紀錄的行為。
 * 執行：./gradlew recordRoborazziDebug --tests '*FortuneScreensTest*'，圖片在 app/build/screens/
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h1600dp-xhdpi")
class FortuneScreensTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob())
    private val demo = DemoRepository(scope).also { it.running = false }

    /** 固定生日，截圖每天都一樣：阿哲是摩羯座、小雨是射手座 */
    private val s: AppState
        get() = demo.state.value.let { st ->
            st.copy(me = st.me?.copy(birthday = "1998-01-08"), partner = st.partner?.copy(birthday = "1999-12-05"))
        }
    private val now = LocalDateTime.of(2026, 10, 16, 21, 30)
    private val clock = { now }
    private val store by lazy { FortuneStore(RuntimeEnvironment.getApplication(), s.myId.orEmpty()) }

    @After fun tearDown() = scope.cancel()

    private fun shot(name: String, dark: Boolean = false, act: () -> Unit = {}, content: @Composable () -> Unit) {
        compose.setContent {
            XlkTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
            }
        }
        act()
        compose.mainClock.advanceTimeBy(2500)
        compose.onRoot().captureRoboImage("build/screens/$name.png")
    }

    /** 讓 Random(seed) 第一次抽到想要的吉凶 */
    private fun seedFor(luck: String) = (0..1000).first { Omikuji.draw(Random(it)).luck == luck }

    private fun drawAndWait() {
        compose.onNodeWithText("搖一搖抽籤").performClick()
        compose.mainClock.advanceTimeBy(4000)
        compose.waitForIdle()
    }

    @Test fun horoscope() {
        shot("55_fortune_horoscope") { FortuneContent(s, {}, FortuneTab.HOROSCOPE, clock) }
        compose.onNodeWithText("阿哲 · 摩羯座的今日運勢").assertExists()
        compose.onNodeWithText("依星座與日期產生，僅供娛樂").assertExists()
        compose.onNodeWithText("看更多 ›").assertHasClickAction().assertHeightIsAtLeast(48.dp)
        val match = Horoscope.matchOfDay(Horoscope.signs[9], Horoscope.signs[8], now.toLocalDate())
        compose.onNodeWithText(match.text).assertExists()
    }

    @Test fun horoscopeTomorrowDark() {
        shot("58_fortune_horoscope_dark", dark = true, act = { compose.onNodeWithText("明天 10/17").performClick() }) {
            FortuneContent(s, {}, FortuneTab.HOROSCOPE, clock)
        }
        compose.onNodeWithText("阿哲 · 摩羯座的明日運勢").assertExists()
        val tomorrow = Horoscope.daily(Horoscope.signs[9], LocalDate.of(2026, 10, 17))
        compose.onNodeWithText(tomorrow.advice).assertExists()
    }

    @Test fun pickSignWithoutBirthday() {
        val noBirthdays = s.copy(me = s.me?.copy(birthday = null), partner = s.partner?.copy(birthday = null))
        shot("59_fortune_pick_sign") { FortuneContent(noBirthdays, {}, FortuneTab.HOROSCOPE, clock) }
        compose.onNodeWithText("你是什麼星座？").assertExists()
        // 還沒有星座就先不顯示今天／明天
        assertTrue(compose.onAllNodesWithText("今天 10/16").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("到「我的 › 我的資料」填上生日，就會自動算出星座").assertExists()
        compose.onNodeWithText("獅子座").performClick()
        compose.mainClock.advanceTimeBy(1000)
        assertEquals("獅子座", store.sign("me:${s.myId}")?.name)
        compose.onNodeWithText("阿哲 · 獅子座的今日運勢").assertExists()
        // 還不知道對方的星座，就先不顯示速配
        assertTrue(compose.onAllNodesWithText("今天的速配指數", substring = true).fetchSemanticsNodes().isEmpty())
        compose.onRoot().captureRoboImage("build/screens/59_fortune_sign_manual.png")
        // 手動選的可以再換
        compose.onNodeWithText("手動選的 · 換一個").assertHasClickAction().assertHeightIsAtLeast(48.dp).performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("你是什麼星座？").assertExists()
        compose.onNodeWithText("收起").performClick()
        compose.mainClock.advanceTimeBy(1000)
        assertTrue(compose.onAllNodesWithText("你是什麼星座？").fetchSemanticsNodes().isEmpty())
    }

    /** 點對方的卡片就看對方的運勢；標題寫名字，同星座也分得出來 */
    @Test fun switchToPartner() {
        compose.setContent { XlkTheme { FortuneContent(s, {}, FortuneTab.HOROSCOPE, clock) } }
        compose.onNode(isSelectable() and hasText("阿哲")).assertIsSelected()
        compose.onNode(isSelectable() and hasText("小雨")).assertIsNotSelected().performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNode(isSelectable() and hasText("小雨")).assertIsSelected()
        compose.onNodeWithText("小雨 · 射手座的今日運勢").assertExists()
    }

    @Test fun omikujiIdleWithHistory() {
        store.add(62, now.minusDays(1))
        store.add(23, now.minusHours(3))
        store.add(1, now.minusMinutes(40))
        store.add(7, now.minusMinutes(5))
        shot("56_fortune_omikuji_idle") { FortuneContent(s, {}, FortuneTab.OMIKUJI, clock) }
        compose.onNodeWithText("東京淺草觀音寺一百籤").assertExists()
        compose.onNodeWithText("3 支").assertExists()
        compose.onNodeWithText("籤詩：元三大師觀音百籤（淺草寺）；解籤：台灣好廟網").assertExists()
        // 昨天的不算「今天抽到的籤」
        assertTrue(compose.onAllNodesWithText("第 62 籤").fetchSemanticsNodes().isEmpty())
        // 籤筒對 TalkBack 是一整個圖，不會一個字一個字唸
        compose.onNodeWithContentDescription("觀音籤筒").assertExists()
        assertTrue(compose.onAllNodesWithText("觀").fetchSemanticsNodes().isEmpty())
    }

    @Test fun omikujiResult() {
        val seed = seedFor("大吉")
        val expected = Omikuji.draw(Random(seed))
        store.add(23, now.minusHours(2))
        shot("57_fortune_omikuji_result", act = ::drawAndWait) { FortuneContent(s, {}, FortuneTab.OMIKUJI, clock, Random(seed)) }
        assertEquals(2, compose.onAllNodesWithText("第 ${expected.no} 籤").fetchSemanticsNodes().size) // 籤詩卡＋紀錄
        compose.onNodeWithText("看完整解籤 ›").assertExists()
        // 抽到的籤號和吉凶會被 TalkBack 唸出來
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite) and hasText("第 ${expected.no} 籤")).assertExists()
        // 只有籤詩卡上有「再抽一支」，上面的按鈕維持「搖一搖抽籤」
        assertEquals(1, compose.onAllNodesWithText("再抽一支").fetchSemanticsNodes().size)
        compose.onNodeWithText("搖一搖抽籤").assertExists()
        val draws = store.draws()
        assertEquals(listOf(expected.no, 23), draws.map { it.no })
        assertEquals(now, draws.first().at)
    }

    @Test fun omikujiBadLuckDark() {
        val seed = seedFor("凶")
        val expected = Omikuji.draw(Random(seed))
        shot("58_fortune_omikuji_dark", dark = true, act = ::drawAndWait) { FortuneContent(s, {}, FortuneTab.OMIKUJI, clock, Random(seed)) }
        compose.onNodeWithText("抽到凶別擔心", substring = true).assertExists()
        assertEquals(expected.no, store.draws().single().no)
    }

    /** 系統字體調大（1.3 倍）：籤詩格子、紀錄的「第 100 籤」都不能被切到 */
    @Test fun omikujiLargeText() {
        val seed = seedFor("末小吉")
        store.add(100, now.minusHours(1))
        shot("57_fortune_omikuji_large_text", act = ::drawAndWait) {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 1.3f)) {
                FortuneContent(s, {}, FortuneTab.OMIKUJI, clock, Random(seed))
            }
        }
        compose.onNodeWithText("第 100 籤").assertExists()
        assertEquals(2, store.draws().size)
    }

    /** 切到星座運勢再回來，剛抽到的籤還在 */
    @Test fun resultSurvivesTabSwitch() {
        compose.setContent { XlkTheme { FortuneContent(s, {}, FortuneTab.OMIKUJI, clock, Random(5)) } }
        drawAndWait()
        val no = Omikuji.draw(Random(5)).no
        compose.onNodeWithText("🔮 星座運勢").performClick()
        compose.mainClock.advanceTimeBy(1000)
        assertTrue(compose.onAllNodesWithText("看完整解籤 ›").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("⛩️ 御神籤").performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("看完整解籤 ›").assertExists()
        assertEquals(2, compose.onAllNodesWithText("第 $no 籤").fetchSemanticsNodes().size)
        assertEquals(1, store.draws().size)
    }

    /** 一般手機的高度：抽完會自動捲到籤詩，再抽一次也看得到結果 */
    @Config(qualifiers = "w400dp-h740dp-xhdpi")
    @Test fun resultScrollsIntoViewOnPhone() {
        compose.setContent { XlkTheme { FortuneContent(s, {}, FortuneTab.OMIKUJI, clock, Random(11)) } }
        drawAndWait()
        compose.onNodeWithText("看完整解籤 ›").assertIsDisplayed()
        compose.onNodeWithText("再抽一支").performClick()
        compose.mainClock.advanceTimeBy(4000)
        compose.waitForIdle()
        compose.onNodeWithText("看完整解籤 ›").assertIsDisplayed()
        assertEquals(2, store.draws().size)
    }

    @Test fun shakeToDrawOnlyWhileVisible() {
        val sm = compose.activity.getSystemService(SensorManager::class.java)
        val shadow = shadowOf(sm)
        shadow.addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))
        compose.setContent { XlkTheme { FortuneContent(s, {}, FortuneTab.OMIKUJI, clock, Random(3)) } }
        compose.waitForIdle()
        assertEquals(1, shadow.listeners.size)
        compose.onNodeWithText("也可以直接搖手機 📳").assertExists()

        // 輕輕晃不算
        shadow.sendSensorEventToListeners(event(0f, 3f, 9.8f))
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(4000)
        assertTrue(store.draws().isEmpty())

        shadow.sendSensorEventToListeners(event(26f, 8f, 9.8f))
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(4000)
        compose.waitForIdle()
        assertEquals(Omikuji.draw(Random(3)).no, store.draws().single().no)

        // App 到背景就不聽
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        assertTrue(shadow.listeners.isEmpty())
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        assertEquals(1, shadow.listeners.size)

        // 換到星座運勢就取消
        compose.onNodeWithText("🔮 星座運勢").performClick()
        compose.waitForIdle()
        assertTrue(shadow.listeners.isEmpty())
    }

    private fun event(x: Float, y: Float, z: Float) = ShadowSensorManager.createSensorEvent(3, Sensor.TYPE_ACCELEROMETER).apply {
        values[0] = x; values[1] = y; values[2] = z
    }

    /** 搖一下只抽一支：動畫中還在搖、動畫一結束手還在搖，都不能多抽 */
    @Test fun oneShakeDrawsOnce() {
        val gate = ShakeGate(1500)
        assertTrue(gate.spike(0, enabled = true))
        assertFalse(gate.spike(300, enabled = true)) // 同一次搖
        assertFalse(gate.spike(1200, enabled = false)) // 抽籤動畫中
        assertFalse(gate.spike(2600, enabled = true)) // 動畫剛結束，手還在搖
        assertTrue(gate.spike(4200, enabled = true)) // 停了一下再搖，才是新的一支
        assertFalse(gate.spike(9000, enabled = false))
    }

    /** 抽籤紀錄跟著帳號：換人登入看不到上一個人的籤 */
    @Test fun drawsBelongToTheAccount() {
        store.add(7, now)
        assertTrue(FortuneStore(RuntimeEnvironment.getApplication(), "someone-else").draws().isEmpty())
        assertEquals(listOf(7), store.draws().map { it.no })
    }

    @Test fun storeKeepsLastTen() {
        repeat(13) { store.add(it + 1, now.plusMinutes(it.toLong())) }
        val draws = store.draws()
        assertEquals(FortuneStore.MAX, draws.size)
        assertEquals((13 downTo 4).toList(), draws.map { it.no })
        assertFalse(draws.any { it.lot == null })
    }

    @Test fun chineseNumbers() {
        assertEquals(listOf("一", "九", "十", "十一", "二十", "二十三", "九十九", "一百"), listOf(1, 9, 10, 11, 20, 23, 99, 100).map(::chineseNumber))
    }
}
