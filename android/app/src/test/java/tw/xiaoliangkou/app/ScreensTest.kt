package tw.xiaoliangkou.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
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
import tw.xiaoliangkou.app.ui.screens.AnniversaryScreen
import tw.xiaoliangkou.app.ui.screens.CalendarScreen
import tw.xiaoliangkou.app.ui.screens.ComfortSheet
import tw.xiaoliangkou.app.ui.screens.CycleScreen
import tw.xiaoliangkou.app.ui.screens.DemoConsole
import tw.xiaoliangkou.app.ui.screens.EventSheet
import tw.xiaoliangkou.app.ui.screens.ExpenseEditSheet
import tw.xiaoliangkou.app.ui.screens.GuessSheet
import tw.xiaoliangkou.app.ui.screens.HistoryScreen
import tw.xiaoliangkou.app.ui.screens.HomeScreen
import tw.xiaoliangkou.app.ui.screens.MoneyScreen
import tw.xiaoliangkou.app.ui.screens.MoodScreen
import tw.xiaoliangkou.app.ui.screens.MoreScreen
import tw.xiaoliangkou.app.ui.screens.PairingScreen
import tw.xiaoliangkou.app.ui.screens.PermissionsScreen
import tw.xiaoliangkou.app.ui.screens.PlaceSheet
import tw.xiaoliangkou.app.ui.screens.PrivacyScreen
import tw.xiaoliangkou.app.ui.screens.ServerSetupScreen
import tw.xiaoliangkou.app.ui.screens.WelcomeScreen
import tw.xiaoliangkou.app.ui.theme.XlkTheme
import java.time.LocalDate

/**
 * 不需要模擬器：用 Robolectric 把每個畫面畫成圖片，檢查排版。
 * 執行：./gradlew recordRoborazziDebug --tests '*ScreensTest*'，圖片在 app/build/screens/
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h1600dp-xhdpi")
class ScreensTest {
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

    private fun sheet(name: String, content: @Composable () -> Unit) = shot(name) {
        Column(Modifier.padding(20.dp)) { content() }
    }

    @Test fun welcome() = shot("01_welcome") { WelcomeScreen(vm) {} }
    @Test fun server() = shot("02_server") { ServerSetupScreen(vm) {} }
    @Test fun pairing() = shot("03_pairing") { PairingScreen(s, vm) }
    @Test fun home() = shot("04_home") { HomeScreen(s, vm, {}, {}) }
    @Test fun homeDark() = shot("05_home_dark", dark = true) { HomeScreen(s, vm, {}, {}) }
    @Test fun money() = shot("06_money") { MoneyScreen(s, vm) {} }
    @Test fun moneyMine() {
        shot("07_money_mine") { MoneyScreen(s, vm) {} }
        compose.onNodeWithText("我的帳本").performClick()
        compose.mainClock.advanceTimeBy(2500)
        compose.onRoot().captureRoboImage("build/screens/07_money_mine.png")
    }
    @Test fun calendar() = shot("08_calendar") { CalendarScreen(s, vm, {}) }
    @Test fun mood() = shot("09_mood") { MoodScreen(s, vm) {} }
    @Test fun cycle() = shot("10_cycle") { CycleScreen(s, vm, {}, {}) }
    @Test fun anniversaries() = shot("11_anniversaries") { AnniversaryScreen(s, vm, {}, {}) }
    @Test fun more() = shot("12_more") { MoreScreen(s, vm) {} }
    @Test fun privacy() = shot("13_privacy") { PrivacyScreen(s, vm) {} }
    @Test fun history() = shot("14_history") { HistoryScreen(s, vm) {} }
    @Test fun demoConsole() = shot("15_demo") { DemoConsole(s, vm) {} }
    @Test fun permissions() = shot("16_permissions") { PermissionsScreen(vm, {}, {}) }
    @Test fun comfortSheet() = sheet("17_sheet_comfort") { ComfortSheet(s, vm) {} }
    @Test fun guessSheet() = sheet("18_sheet_guess") { GuessSheet(s, vm, s.partnerExpenses.first { it.waitingForGuess }.id) {} }
    @Test fun expenseSheet() = sheet("19_sheet_expense") { ExpenseEditSheet(s, vm, null) {} }
    @Test fun eventSheet() = sheet("20_sheet_event") { EventSheet(s, vm, null, LocalDate.now()) {} }
    @Test fun placeSheet() = sheet("21_sheet_place") { PlaceSheet(s, vm, null, 25.04, 121.56) {} }
    @Test fun cycleDark() = shot("22_cycle_dark", dark = true) { CycleScreen(s, vm, {}, {}) }

    /** 整個 App（示範模式）：底部分頁、導覽、地圖 */
    @Test fun appShell() {
        val app = RuntimeEnvironment.getApplication() as XlkApp
        app.container.startDemo()
        compose.setContent { XlkTheme { tw.xiaoliangkou.app.ui.XlkRoot(vm, null) {} } }
        compose.waitUntil(10_000) { vm.prefs.value != null && vm.state.value.loaded }
        compose.mainClock.advanceTimeBy(2500)
        compose.onRoot().captureRoboImage("build/screens/23_app_home.png")
        compose.onNodeWithText("地圖").performClick()
        compose.mainClock.advanceTimeBy(1500)
        compose.onRoot().captureRoboImage("build/screens/24_app_map.png")
        compose.onNodeWithText("我的").performClick()
        compose.mainClock.advanceTimeBy(1500)
        compose.onRoot().captureRoboImage("build/screens/25_app_more.png")
    }
}
