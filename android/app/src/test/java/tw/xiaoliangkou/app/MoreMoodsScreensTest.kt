package tw.xiaoliangkou.app

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import tw.xiaoliangkou.app.core.Catalog
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.data.CoupleRepository
import tw.xiaoliangkou.app.data.DemoRepository
import tw.xiaoliangkou.app.service.StatusActionReceiver
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.screens.ComfortSheet
import tw.xiaoliangkou.app.ui.screens.MoodScreen
import tw.xiaoliangkou.app.ui.theme.XlkTheme

/**
 * 0.2.3 多的心情（21 種）和安撫（15 種）：分三組顯示，資料庫還沒更新時只顯示原本的，送得出去新的。
 * 截圖：./gradlew testDebugUnitTest -Proborazzi.test.record=true --tests 'tw.xiaoliangkou.app.MoreMoodsScreensTest'
 * （app/build/screens/ 和字放大的 app/build/screens-large/）
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h1800dp-xhdpi")
class MoreMoodsScreensTest {
    @get:Rule val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob())
    private val demo = DemoRepository(scope).also { it.running = false }
    private val s: AppState get() = demo.state.value
    private val vm by lazy { AppViewModel(RuntimeEnvironment.getApplication()) }
    private val giphy = FakeGiphy()

    @After fun tearDown() = scope.cancel()

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { XlkTheme { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() } } }
        compose.mainClock.advanceTimeBy(2500)
    }

    /** 安撫是從下面滑上來的：和 XlkSheet 一樣左右留 20 dp、可以捲 */
    private fun sheet(content: @Composable () -> Unit) = show { Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) { content() } }

    private fun capture(name: String, large: Boolean = false) =
        compose.onRoot().captureRoboImage(if (large) "build/screens-large/$name.png" else "build/screens/$name.png")

    private fun has(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun idleUntil(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 30_000
        while (!cond() && System.currentTimeMillis() < end) { ShadowLooper.idleMainLooper(); compose.mainClock.advanceTimeBy(50); Thread.sleep(10) }
        assertTrue(cond())
    }

    /** 換掉 App 目前的資料來源（vm.act 用的是它） */
    private fun setRepo(repo: CoupleRepository) {
        @Suppress("UNCHECKED_CAST")
        val field = vm.container.javaClass.getDeclaredField("_repo").apply { isAccessible = true }.get(vm.container) as MutableStateFlow<CoupleRepository?>
        field.value = repo
    }

    @Test fun everyMoodInThreeGroups() {
        show { MoodScreen(s, vm, giphy) {} }
        Catalog.moodGroups.forEach { assertTrue(it.title, has(it.title)) }
        Catalog.moods.forEach { assertTrue(it.label, has(it.label)) }
        assertFalse("資料庫是新的：不用提醒", has("再執行一次 upgrade-v2.sql 就有更多心情可以選"))
        capture("36c_mood_more")
    }

    /** 跑過舊的 upgrade-v2.sql（schema 4）：只有原本的 9 種，說一下怎麼拿到更多 */
    @Test fun oldDatabaseOnlyShowsTheOriginalMoods() {
        show { MoodScreen(s.copy(dbSchema = 4), vm, giphy) {} }
        Catalog.moods.forEach { assertEquals(it.label, !it.extra, has(it.label)) }
        assertTrue(has("再執行一次 upgrade-v2.sql 就有更多心情可以選"))
        capture("36d_mood_old_database")
    }

    @Test fun everyComfortInThreeGroups() {
        sheet { ComfortSheet(s, vm, giphy) {} }
        Catalog.comfortGroups.forEach { assertTrue(it.title, has(it.title)) }
        Catalog.comforts.forEach { assertTrue(it.label, has(it.label)) }
        capture("37c_sheet_comfort_more")
    }

    @Test fun oldDatabaseOnlyShowsTheOriginalComforts() {
        sheet { ComfortSheet(s.copy(dbSchema = 4), vm, giphy) {} }
        Catalog.comforts.forEach { assertEquals(it.label, !it.extra, has(it.label)) }
        assertTrue(has("再執行一次 upgrade-v2.sql 就有更多安撫可以選"))
    }

    /** 0.1 的資料庫：說「執行」，不是「再執行一次」 */
    @Test fun neverUpgradedDatabaseSaysRunIt() {
        sheet { ComfortSheet(s.copy(schemaOutdated = true, dbSchema = 0), vm, giphy) {} }
        assertTrue(has("執行 upgrade-v2.sql 就有更多安撫可以選"))
        assertFalse(has("送出") && has("晚安"))
    }

    @Test fun aNewMoodIsPosted() {
        val posted = mutableListOf<String>()
        setRepo(object : CoupleRepository by demo {
            override suspend fun postMood(mood: String, note: String?, sticker: String?) { posted += mood }
        })
        var back = false
        show { MoodScreen(s, vm, giphy) { back = true } }
        compose.onNodeWithText("吃醋").performClick()
        compose.onNodeWithText("更新心情").performClick()
        idleUntil { back }
        assertEquals(listOf("jealous"), posted)
    }

    @Test fun aNewComfortIsSent() {
        val sent = mutableListOf<String>()
        setRepo(object : CoupleRepository by demo {
            override suspend fun sendComfort(kind: String, message: String?, sticker: String?) { sent += kind }
        })
        var closed = false
        sheet { ComfortSheet(s, vm, giphy) { closed = true } }
        compose.onNodeWithText("晚安").performClick()
        compose.onNodeWithText("送出晚安").performClick()
        idleUntil { closed }
        assertEquals(listOf("night"), sent)
    }

    /** 點收到安撫的通知：安撫打開成「回應」，上面是對方傳來的那則，打開就算看過了，回什麼都可以 */
    @Test fun replyingToAReceivedComfort() {
        val received = s.comforts.first { it.receiverId == s.myId && it.readAt == null }
        val read = mutableListOf<Long>()
        val sent = mutableListOf<String>()
        setRepo(object : CoupleRepository by demo {
            override suspend fun readComfort(id: Long) { read += id }
            override suspend fun sendComfort(kind: String, message: String?, sticker: String?) { sent += kind }
        })
        var closed = false
        sheet { ComfortSheet(s, vm, giphy, replyTo = received.id) { closed = true } }
        assertTrue(has("回應 小雨 💌"))
        assertTrue(has(received.message!!))
        idleUntil { read == listOf(received.id) }
        capture("37d_sheet_comfort_reply")
        compose.onNodeWithText("愛你").performClick()
        compose.onNodeWithText("送出愛你").performClick()
        idleUntil { closed }
        assertEquals(listOf("love"), sent)
    }

    /** 常駐通知的「😘 親親」：不用打開 App 就送出去 */
    @Test fun statusNotificationSendsAComfort() {
        val app = RuntimeEnvironment.getApplication()
        vm.container.startDemo()
        idleUntil { vm.container.repo.value?.state?.value?.loaded == true }
        app.sendBroadcast(Intent(app, StatusActionReceiver::class.java).setAction(StatusActionReceiver.COMFORT).putExtra(StatusActionReceiver.EXTRA_KIND, "kiss"))
        idleUntil { vm.container.repo.value!!.state.value.comforts.any { it.senderId == s.myId && it.kind == "kiss" } }
        // 不認得的種類不送
        val before = vm.container.repo.value!!.state.value.comforts.size
        app.sendBroadcast(Intent(app, StatusActionReceiver::class.java).setAction(StatusActionReceiver.COMFORT).putExtra(StatusActionReceiver.EXTRA_KIND, "slap"))
        ShadowLooper.idleMainLooper()
        Thread.sleep(300)
        ShadowLooper.idleMainLooper()
        assertEquals(before, vm.container.repo.value!!.state.value.comforts.size)
    }

    // ================================================================ 字放大（360 dp、1.3 倍）

    @Config(qualifiers = "zh-rTW-w360dp-h2400dp-xhdpi", fontScale = 1.3f)
    @Test fun moodsWithLargeText() {
        show { MoodScreen(s, vm, giphy) {} }
        Catalog.moods.forEach { assertTrue(it.label, has(it.label)) }
        capture("29c_mood_more", large = true)
    }

    @Config(qualifiers = "zh-rTW-w360dp-h2000dp-xhdpi", fontScale = 1.3f)
    @Test fun comfortsWithLargeText() {
        sheet { ComfortSheet(s, vm, giphy) {} }
        Catalog.comforts.forEach { assertTrue(it.label, has(it.label)) }
        capture("60c_sheet_comfort_more", large = true)
    }
}
