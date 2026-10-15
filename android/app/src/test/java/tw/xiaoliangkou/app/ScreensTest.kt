package tw.xiaoliangkou.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.data.DemoRepository
import tw.xiaoliangkou.app.data.InviteLink
import tw.xiaoliangkou.app.data.SettingsStore
import tw.xiaoliangkou.app.data.Session
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.screens.AccountScreen
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
import tw.xiaoliangkou.app.ui.screens.InviteConfirmDialog
import tw.xiaoliangkou.app.ui.screens.LoginScreen
import tw.xiaoliangkou.app.ui.screens.MoneyScreen
import tw.xiaoliangkou.app.ui.screens.MoodScreen
import tw.xiaoliangkou.app.ui.screens.MoreScreen
import tw.xiaoliangkou.app.ui.screens.PairingScreen
import tw.xiaoliangkou.app.ui.screens.PermissionsScreen
import tw.xiaoliangkou.app.ui.screens.PlaceSheet
import tw.xiaoliangkou.app.ui.screens.PrivacyScreen
import tw.xiaoliangkou.app.ui.screens.RegisterScreen
import tw.xiaoliangkou.app.ui.screens.SupabaseSetupScreen
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

    /** 設定 Supabase 網址、金鑰（空字串＝還沒設定）與上次登入的 Email；設定存在手機的 DataStore，每個測試共用 */
    private fun configure(url: String, key: String, email: String? = null) {
        val end = System.currentTimeMillis() + 10_000
        while (vm.prefs.value == null && System.currentTimeMillis() < end) { ShadowLooper.idleMainLooper(); Thread.sleep(10) }
        runBlocking {
            val st = vm.container.settings
            if (email != null) st.setSession(Session("a", "r", 0, "u", email))
            st.clear()
            st.setSupabase(url, key)
            vm.container.reloadPrefs()
        }
    }
    private fun configured() = configure("https://abcdefgh.supabase.co", "sb_publishable_Xy7kQ2mN9pL4rT8vW1zA3b", "azhe@example.com")

    @Test fun welcome() = shot("01_welcome") { WelcomeScreen(vm) {} }
    @Test fun setup() {
        configure("", "")
        shot("02_setup") { SupabaseSetupScreen(vm, {}) {} }
    }
    @Test fun setupConnected() {
        configured()
        shot("02b_setup_connected") { SupabaseSetupScreen(vm, {}) {} }
    }
    // ---------------------------------------------------------------- 另一半的邀請連結：先確認，不會自動連線或存起來
    private val inviteLink = InviteLink.build("https://qrstuvwxyz.supabase.co", "sb_publishable_Qw3rTy7UiOp1AsDfGh9JkL")

    /** 確認視窗是真的 Dialog（另一個視窗），所以截整個螢幕 */
    @OptIn(ExperimentalRoborazziApi::class)
    private fun screenShot(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            XlkTheme { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() } }
        }
        compose.mainClock.advanceTimeBy(2500)
        captureScreenRoboImage("build/screens/$name.png")
    }

    private fun idleUntil(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!cond() && System.currentTimeMillis() < end) { ShadowLooper.idleMainLooper(); Thread.sleep(10) }
        assertTrue(cond())
    }

    @Config(qualifiers = "w400dp-h860dp-xhdpi")
    @Test fun inviteConfirm() {
        configure("", "")
        screenShot("02e_invite_confirm") {
            SupabaseSetupScreen(vm, {}) {}
            InviteConfirmDialog("https://qrstuvwxyz.supabase.co" to "sb_publishable_Qw3rTy7UiOp1AsDfGh9JkL", "", demo = false, {}, {})
        }
    }

    @Config(qualifiers = "w400dp-h860dp-xhdpi")
    @Test fun inviteWarning() {
        configured()
        screenShot("02f_invite_warning") {
            WelcomeScreen(vm) {}
            InviteConfirmDialog("https://attacker.example" to "sb_publishable_xxxxxxxxxx", "https://abcdefgh.supabase.co", demo = true, {}, {})
        }
    }

    /** 貼了後台網址（會自動換成 Project URL）和混進全形字的金鑰：先在手機上提醒，不送出去 */
    @Test fun setupHints() {
        configure("https://supabase.com/dashboard/project/abcdefghijklmnopqrst/settings/api", "ｓｂ_publishable_Xy7kQ2mN9pL4rT8vW1zA3b")
        shot("02h_setup_hints") { SupabaseSetupScreen(vm, {}) {} }
        compose.onNodeWithText("測試連線").assertIsNotEnabled()
    }

    /** 點邀請連結：只出現確認視窗；按「幫我填好」也只是填好欄位，測試連線成功前都不會存 */
    @Test fun inviteOnlyPrefills() {
        configured()
        val c = vm.container
        c.openInvite(inviteLink)
        idleUntil { c.pendingInvite.value != null }
        assertEquals("https://qrstuvwxyz.supabase.co", c.pendingInvite.value!!.first)
        assertNull(c.prefill.value)
        c.confirmInvite()
        idleUntil { c.prefill.value != null }
        assertNull(c.pendingInvite.value)
        // 還是原本的設定
        assertEquals("https://abcdefgh.supabase.co", runBlocking { c.settings.load().supabaseUrl })
        assertEquals("sb_publishable_Xy7kQ2mN9pL4rT8vW1zA3b", runBlocking { c.settings.load().supabaseKey })
        shot("02g_setup_prefilled") { SupabaseSetupScreen(vm, {}) {} }
        compose.onNodeWithText("測試連線").assertExists()
        c.clearPrefill()
    }

    /** 示範模式中點邀請連結：按「要」之前都還在示範模式；按「不要」就什麼都沒變 */
    @Test fun inviteDoesNotLeaveDemoByItself() {
        configured()
        val c = vm.container
        c.startDemo()
        idleUntil { c.prefs.value?.mode == SettingsStore.Mode.DEMO }
        c.openInvite(inviteLink)
        idleUntil { c.pendingInvite.value != null }
        assertTrue(c.repo.value is DemoRepository)
        c.dismissInvite()
        ShadowLooper.idleMainLooper()
        assertTrue(c.repo.value is DemoRepository)
        assertNull(c.prefill.value)
        assertEquals("https://abcdefgh.supabase.co", runBlocking { c.settings.load().supabaseUrl })
        // 這次按「要」：離開示範模式，網址、金鑰只是先填好
        c.openInvite(inviteLink)
        idleUntil { c.pendingInvite.value != null }
        c.confirmInvite()
        idleUntil { c.prefill.value != null && c.repo.value == null }
        assertEquals(SettingsStore.Mode.NONE, c.prefs.value?.mode)
        assertEquals("https://abcdefgh.supabase.co", runBlocking { c.settings.load().supabaseUrl })
        c.clearPrefill()
    }

    @Test fun login() {
        configured()
        shot("02c_login") { LoginScreen(vm, {}) {} }
    }
    @Test fun register() {
        configured()
        shot("02d_register") { RegisterScreen(vm, {}) {} }
    }
    @Test fun account() {
        configured()
        shot("26_account") { AccountScreen(s, vm) {} }
    }
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
