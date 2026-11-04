package tw.xiaoliangkou.app

import android.app.PendingIntent
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import org.robolectric.shadows.ShadowLooper
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.data.DemoRepository
import tw.xiaoliangkou.app.service.Notifier
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.screens.AccountScreen
import tw.xiaoliangkou.app.ui.screens.DemoConsole
import tw.xiaoliangkou.app.ui.screens.MoreScreen
import tw.xiaoliangkou.app.ui.screens.NotificationScreen
import tw.xiaoliangkou.app.ui.theme.XlkTheme
import tw.xiaoliangkou.app.widget.MemoWidget
import tw.xiaoliangkou.app.widget.Widgets
import java.time.LocalDate

/**
 * 資料庫還沒更新時「我的 › Supabase 與帳號」的更新步驟、通知設定、示範控制台的新按鈕、小工具的 PendingIntent。
 * 截圖：./gradlew testDebugUnitTest -Proborazzi.test.record=true --tests '*ReviewFix1ScreensTest*'，圖片在 app/build/screens/
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h1600dp-xhdpi")
class ReviewFix1ScreensTest {
    @get:Rule val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob())
    private val demo = DemoRepository(scope).also { it.running = false }
    private val s: AppState get() = demo.state.value
    private val vm by lazy { AppViewModel(RuntimeEnvironment.getApplication()) }

    @After fun tearDown() = scope.cancel()

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            XlkTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
            }
        }
        compose.mainClock.advanceTimeBy(2500)
        compose.onRoot().captureRoboImage("build/screens/$name.png")
    }

    /** 登入了 abcdefgh.supabase.co（設定存在手機的 DataStore） */
    private fun configured() {
        val end = System.currentTimeMillis() + 10_000
        while (vm.prefs.value == null && System.currentTimeMillis() < end) { ShadowLooper.idleMainLooper(); Thread.sleep(10) }
        runBlocking {
            val st = vm.container.settings
            st.clear()
            st.setSupabase("https://abcdefgh.supabase.co", "sb_publishable_Xy7kQ2mN9pL4rT8vW1zA3b")
            vm.container.reloadPrefs()
        }
    }

    /** 用 App 真的資料來源（示範模式） */
    private fun live(): AppState {
        vm.container.startDemo()
        compose.waitUntil(10_000) { vm.state.value.loaded }
        return vm.state.value
    }

    @Test fun accountShowsUpgradeSteps() {
        configured()
        shot("26b_account_upgrade") { AccountScreen(s.copy(schemaOutdated = true), vm) {} }
        compose.onNodeWithText("資料庫需要更新").assertExists()
        compose.onNodeWithText("upgrade-v2.sql").assertExists()
        // 官方雲端專案：直接開這個專案的 SQL Editor
        compose.onNodeWithText("SQL Editor").assertExists()
        compose.onNodeWithText("看完整說明").assertExists()
        compose.onNodeWithText("我更新好了，重新檢查").assertExists()
    }

    @Test fun accountWithoutUpgradeHasNoSteps() {
        configured()
        compose.setContent { XlkTheme { AccountScreen(s, vm) {} } }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("資料庫需要更新").assertDoesNotExist()
    }

    @Test fun recheckTellsWhetherTheUpgradeWorked() {
        live()
        val messages = mutableListOf<String>()
        scope.launch(Dispatchers.Unconfined) { vm.messages.collect { messages += it } }
        compose.setContent { XlkTheme { AccountScreen(vm.state.value.copy(schemaOutdated = true), vm) {} } }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("我更新好了，重新檢查").performScrollTo().performClick()
        compose.waitUntil(10_000) { messages.isNotEmpty() }
        assertEquals("資料庫更新好了，新功能都可以用了", messages.single())
    }

    @Test fun moreScreenPointsToTheSteps() {
        compose.setContent { XlkTheme { MoreScreen(s.copy(demo = false, schemaOutdated = true), vm) {} } }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("資料庫需要更新，點這裡看怎麼做").assertExists()
    }

    @Test fun newNotificationKindsWaitForUpgrade() {
        // 還沒更新：先提醒，但還是可以按（按了會說要更新資料庫；已經更新、只是還沒重新檢查的話就直接存好）
        shot("33b_notification_settings_outdated") { NotificationScreen(s.copy(schemaOutdated = true), vm) {} }
        compose.onNodeWithText("對方記錄那天的生活").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("新的共同備忘錄").assertIsEnabled()
        // 生活紀錄、備忘錄，和 0.3.0 的小貓
        assertEquals(3, compose.onAllNodesWithText("資料庫更新後才能設定").fetchSemanticsNodes().size)
        compose.onNodeWithText("抱抱與悄悄話").assertIsEnabled()
    }

    @Test fun newNotificationKindsAfterUpgrade() {
        compose.setContent { XlkTheme { NotificationScreen(s, vm) {} } }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("對方記錄那天的生活").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("資料庫更新後才能設定").assertDoesNotExist()
    }

    @Test fun demoConsolePlaysDiaryAndMemo() {
        val before = live()
        compose.setContent { XlkTheme { DemoConsole(vm.state.value, vm) {} } }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("記錄今天的生活").performScrollTo().performClick()
        compose.onNodeWithText("新增共同備忘錄").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            vm.state.value.dayEntries.size > before.dayEntries.size && vm.state.value.memos.size > before.memos.size
        }
        val today = LocalDate.now().toString()
        assertTrue(vm.state.value.dayEntries.any { it.userId == DemoRepository.PARTNER && it.day == today && it !in before.dayEntries })
        assertTrue(vm.state.value.memos.any { it.userId == DemoRepository.PARTNER && it !in before.memos })
    }

    /** 通知的編號剛好是 7100（或 7004）時，不能把小工具「打開哪一頁」蓋掉 */
    @Test fun widgetIntentsAreNotReplacedByNotifications() {
        val context = RuntimeEnvironment.getApplication()
        val memos = MemoWidget.openMemos(context)
        val home = Widgets.openApp(context, "home")
        // 跟 Notifier 一樣的通知 PendingIntent（只有 extra 不一樣）
        fun notification(id: Int, route: String) = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(Notifier.EXTRA_ROUTE, route)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n1 = notification(7_100, "map")
        val n2 = notification(7_004, "calendar")
        assertFalse(shadowOf(memos).savedIntent.filterEquals(shadowOf(n1).savedIntent))
        assertEquals("memos", shadowOf(memos).savedIntent.getStringExtra(Notifier.EXTRA_ROUTE))
        assertEquals("home", shadowOf(home).savedIntent.getStringExtra(Notifier.EXTRA_ROUTE))
        assertEquals("map", shadowOf(n1).savedIntent.getStringExtra(Notifier.EXTRA_ROUTE))
        assertEquals("calendar", shadowOf(n2).savedIntent.getStringExtra(Notifier.EXTRA_ROUTE))
        // 小工具重畫（再拿一次）也不會改到通知
        MemoWidget.openMemos(context)
        assertEquals("map", shadowOf(n1).savedIntent.getStringExtra(Notifier.EXTRA_ROUTE))
    }
}
