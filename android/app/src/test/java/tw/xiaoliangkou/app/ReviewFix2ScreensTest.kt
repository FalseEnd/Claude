package tw.xiaoliangkou.app

import androidx.activity.ComponentDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.data.DemoRepository
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.SheetDraftGuard
import tw.xiaoliangkou.app.ui.XlkRoot
import tw.xiaoliangkou.app.ui.XlkSheet
import tw.xiaoliangkou.app.ui.screens.CalendarScreen
import tw.xiaoliangkou.app.ui.screens.PairingScreen
import tw.xiaoliangkou.app.ui.theme.XlkTheme
import java.time.LocalDate
import java.time.YearMonth

/**
 * 第二輪審查修正：寫到一半的面板不會一滑就不見、日曆只跳一次、沒配對也打得開備忘錄。
 * 截圖：./gradlew testDebugUnitTest -Proborazzi.test.record=true --tests '*ReviewFix2ScreensTest*'，圖片在 app/build/screens/
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h1600dp-xhdpi")
class ReviewFix2ScreensTest {
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

    // ---------------------------------------------------------------- 還沒儲存的面板

    private var closed = 0

    /** 一個會問的面板，[dirty] 表示裡面有還沒儲存的內容 */
    private fun openSheet(dirty: Boolean): ComponentDialog {
        compose.setContent {
            XlkTheme {
                var open by remember { mutableStateOf(true) }
                if (open) XlkSheet(guarded = true, close = { open = false; closed++ }) {
                    SheetDraftGuard(dirty)
                    Text("今天去淡水看夕陽，風好大")
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("今天去淡水看夕陽，風好大").assertExists()
        return ShadowDialog.getLatestDialog() as ComponentDialog
    }

    private fun pressBack(dialog: ComponentDialog) {
        compose.runOnUiThread { dialog.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test fun backAsksBeforeThrowingAwayADraft() {
        val dialog = openSheet(dirty = true)
        pressBack(dialog)
        compose.onNodeWithText("還沒儲存喔").assertExists()
        assertEquals(0, closed)
        // 繼續編輯：面板還在
        compose.onNodeWithText("繼續編輯").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("還沒儲存喔").assertDoesNotExist()
        compose.onNodeWithText("今天去淡水看夕陽，風好大").assertExists()
        assertEquals(0, closed)
        // 再按一次返回，這次放棄
        pressBack(dialog)
        compose.onNodeWithText("放棄").performClick()
        compose.waitForIdle()
        assertEquals(1, closed)
        compose.onNodeWithText("今天去淡水看夕陽，風好大").assertDoesNotExist()
    }

    @Test fun swipingDownAsksToo() {
        openSheet(dirty = true)
        compose.onNodeWithText("今天去淡水看夕陽，風好大").performTouchInput { swipeDown(startY = top, endY = top + 1200f, durationMillis = 120) }
        compose.waitForIdle()
        compose.onNodeWithText("還沒儲存喔").assertExists()
        assertEquals(0, closed)
        compose.onNodeWithText("今天去淡水看夕陽，風好大").assertExists()
    }

    @Test fun nothingWrittenClosesRightAway() {
        val dialog = openSheet(dirty = false)
        pressBack(dialog)
        compose.onNodeWithText("還沒儲存喔").assertDoesNotExist()
        assertEquals(1, closed)
    }

    // ---------------------------------------------------------------- 日曆只跳一次

    @Test fun calendarJumpsOnceAndAgainForTheSameDay() {
        val target = LocalDate.now().minusMonths(7).withDayOfMonth(14)
        val title = { d: LocalDate -> YearMonth.from(d).let { "${it.year} 年 ${it.monthValue} 月" } }
        var jump by mutableStateOf<Pair<LocalDate, Long>?>(target to 1L)
        var shown by mutableStateOf(true)
        compose.setContent {
            XlkTheme { if (shown) CalendarScreen(s, vm, {}, jump?.first, jump?.second ?: 0L) { jump = null } }
        }
        compose.waitForIdle()
        compose.onNodeWithText(title(target)).assertExists()
        assertNull("跳過去以後就清掉", jump)
        // 換到下個月，再從通知打開同一天：一樣跳回去
        compose.onNodeWithContentDescription("下個月").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(title(target.plusMonths(1))).assertExists()
        jump = target to 2L
        compose.waitForIdle()
        compose.onNodeWithText(title(target)).assertExists()
        assertNull(jump)
        // 離開日曆再回來：從今天開始，不會又回到通知的那天
        shown = false
        compose.waitForIdle()
        shown = true
        compose.waitForIdle()
        compose.onNodeWithText(title(LocalDate.now())).assertExists()
        compose.onNodeWithText(title(target)).assertDoesNotExist()
    }

    // ---------------------------------------------------------------- 沒配對也打得開自己的東西

    @Test fun pairingScreenOpensPersonalPages() {
        val routes = mutableListOf<String>()
        shot("03b_pairing_personal") { PairingScreen(s.copy(couple = null, partner = null), vm) { routes += it } }
        compose.onNodeWithText("配對前也可以先用").performScrollTo().assertExists()
        compose.onNodeWithText("我的資料").performScrollTo().performClick()
        compose.onNodeWithText("備忘錄").performScrollTo().performClick()
        compose.onNodeWithText("星座運勢與御神籤").performScrollTo().performClick()
        assertEquals(listOf("profile", "memos", "fortune"), routes)
    }

    @Test fun pairingScreenWithoutNavigationHasNoMenu() {
        compose.setContent { XlkTheme { PairingScreen(s.copy(couple = null, partner = null), vm) } }
        compose.waitForIdle()
        compose.onNodeWithText("配對前也可以先用").assertDoesNotExist()
    }

    /** 解除配對後點桌面的備忘錄小工具：打開備忘錄，不是停在配對頁 */
    @Test fun unpairedMemoWidgetOpensMemos() {
        vm.container.startDemo()
        compose.waitUntil(10_000) { vm.state.value.loaded }
        @Suppress("UNCHECKED_CAST")
        (vm.container.repo.value!!.state as MutableStateFlow<AppState>).update { it.copy(couple = null, partner = null) }
        compose.waitUntil(10_000) { !vm.state.value.paired }
        assertTrue(vm.state.value.memos.isNotEmpty())
        var link by mutableStateOf<String?>("memos")
        compose.setContent { XlkTheme { XlkRoot(vm, link) { link = null } } }
        compose.waitUntil(10_000) { link == null }
        compose.mainClock.advanceTimeBy(1500)
        compose.onNodeWithText("新增備忘錄").assertExists()
        compose.onNodeWithText("我的配對碼").assertDoesNotExist()
        compose.onRoot().captureRoboImage("build/screens/53c_memos_unpaired.png")
        // 返回：回到配對頁
        compose.onNodeWithContentDescription("返回").performClick()
        compose.mainClock.advanceTimeBy(1500)
        compose.onNodeWithText("我的配對碼").assertExists()
        // 其他通知（例如日曆）等配對好再開
        link = "calendar"
        compose.mainClock.advanceTimeBy(1500)
        assertFalse(link == null)
        compose.onNodeWithText("我的配對碼").assertExists()
    }
}
