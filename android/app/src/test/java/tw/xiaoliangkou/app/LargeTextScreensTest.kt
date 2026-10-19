package tw.xiaoliangkou.app

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.activity.ComponentDialog
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import tw.xiaoliangkou.app.core.Omikuji
import tw.xiaoliangkou.app.data.AppException
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.data.Avatars
import tw.xiaoliangkou.app.data.CoupleRepository
import tw.xiaoliangkou.app.data.DemoMedia
import tw.xiaoliangkou.app.data.DemoRepository
import tw.xiaoliangkou.app.data.DeviceStatus
import tw.xiaoliangkou.app.data.MediaItem
import tw.xiaoliangkou.app.data.PartnerStatus
import tw.xiaoliangkou.app.data.PlaceHit
import tw.xiaoliangkou.app.data.Session
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.XlkRoot
import tw.xiaoliangkou.app.ui.XlkSheet
import tw.xiaoliangkou.app.ui.components.BannerCard
import tw.xiaoliangkou.app.ui.components.MediaPickerRow
import tw.xiaoliangkou.app.ui.components.MediaThumbs
import tw.xiaoliangkou.app.ui.components.VoiceChip
import tw.xiaoliangkou.app.ui.screens.AccountScreen
import tw.xiaoliangkou.app.ui.screens.AnniversaryScreen
import tw.xiaoliangkou.app.ui.screens.AnniversarySheet
import tw.xiaoliangkou.app.ui.screens.AvatarCropDialog
import tw.xiaoliangkou.app.ui.screens.AvatarSheet
import tw.xiaoliangkou.app.ui.screens.CalendarScreen
import tw.xiaoliangkou.app.ui.screens.ComfortSheet
import tw.xiaoliangkou.app.ui.screens.ConsentScreen
import tw.xiaoliangkou.app.ui.screens.CycleScreen
import tw.xiaoliangkou.app.ui.screens.DatePickerPopup
import tw.xiaoliangkou.app.ui.screens.DayEntrySheet
import tw.xiaoliangkou.app.ui.screens.DemoConsole
import tw.xiaoliangkou.app.ui.screens.EventSheet
import tw.xiaoliangkou.app.ui.screens.ExpenseEditSheet
import tw.xiaoliangkou.app.ui.screens.ExpenseViewSheet
import tw.xiaoliangkou.app.ui.screens.FortuneContent
import tw.xiaoliangkou.app.ui.screens.FortuneStore
import tw.xiaoliangkou.app.ui.screens.FortuneTab
import tw.xiaoliangkou.app.ui.screens.GuessSheet
import tw.xiaoliangkou.app.ui.screens.HistoryScreen
import tw.xiaoliangkou.app.ui.screens.HomeScreen
import tw.xiaoliangkou.app.ui.screens.InviteConfirmDialog
import tw.xiaoliangkou.app.ui.screens.LoginScreen
import tw.xiaoliangkou.app.ui.screens.MapScreen
import tw.xiaoliangkou.app.ui.screens.MemoSheet
import tw.xiaoliangkou.app.ui.screens.MemosScreen
import tw.xiaoliangkou.app.ui.screens.MoneyScreen
import tw.xiaoliangkou.app.ui.screens.MoodScreen
import tw.xiaoliangkou.app.ui.screens.MoreScreen
import tw.xiaoliangkou.app.ui.screens.MyProfileScreen
import tw.xiaoliangkou.app.ui.screens.NoteSheet
import tw.xiaoliangkou.app.ui.screens.NotificationScreen
import tw.xiaoliangkou.app.ui.screens.PairingScreen
import tw.xiaoliangkou.app.ui.screens.ParseTestSheet
import tw.xiaoliangkou.app.ui.screens.PartnerProfileScreen
import tw.xiaoliangkou.app.ui.screens.PeriodSheet
import tw.xiaoliangkou.app.ui.screens.PermissionsScreen
import tw.xiaoliangkou.app.ui.screens.PlaceSheet
import tw.xiaoliangkou.app.ui.screens.PrivacyScreen
import tw.xiaoliangkou.app.ui.screens.RegisterScreen
import tw.xiaoliangkou.app.ui.screens.SplashScreen
import tw.xiaoliangkou.app.ui.screens.SupabaseSetupScreen
import tw.xiaoliangkou.app.ui.screens.TimePickerPopup
import tw.xiaoliangkou.app.ui.screens.WelcomeScreen
import tw.xiaoliangkou.app.ui.screens.WidgetsScreen
import tw.xiaoliangkou.app.ui.theme.XlkTheme
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.random.Random

/**
 * 系統字體放大（1.3 倍）、360dp 寬的窄手機：把每個畫面、面板、對話框都用示範資料畫出來，找出被切到的字。
 * 使用者的 ASUS、OPPO 手機都把字放大了，畫面寬約 360–411dp；系統語言是繁體中文（日期選擇器等系統元件的字也跟著變）。
 *
 * 執行：./gradlew testDebugUnitTest --rerun -Proborazzi.test.record=true --tests '*LargeTextScreensTest*'
 * 圖片在 app/build/screens-large/：
 * - 視窗故意開得很高，整頁一次畫完；最後只剩背景的部分切掉，中間超過 96dp 的空白縮成 32dp。
 * - 還是比 1000dp 高的頁面，從卡片之間的空白切成幾張（NN_名稱_1.png、_2.png…），每張的字都看得清楚。
 * - 對話框照一般手機的高度（800dp）截整個螢幕。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rTW-w360dp-h2000dp-xhdpi", fontScale = 1.3f)
class LargeTextScreensTest {
    @get:Rule val compose = createComposeRule()

    private val app get() = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob())
    private val demo = DemoRepository(scope).also { it.running = false }
    private val s: AppState get() = demo.state.value
    private val vm by lazy { AppViewModel(app) }
    private val today = LocalDate.now()

    @After fun tearDown() {
        scope.cancel()
        runBlocking { vm.container.media.clear() }
    }

    // ================================================================ 截圖

    private fun show(dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            XlkTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
            }
        }
        compose.mainClock.advanceTimeBy(2500)
    }

    /** 截 App 的視窗；[tidy]：切掉多的背景、太高就分段 */
    private fun capture(name: String, tidy: Boolean = true) {
        val file = File(DIR, "$name.png")
        compose.onRoot().captureRoboImage(file.path)
        if (tidy) tidy(file)
    }

    /** 對話框是另一個視窗：截整個螢幕 */
    @OptIn(ExperimentalRoborazziApi::class)
    private fun screen(name: String) {
        compose.waitForIdle()
        val file = File(DIR, "$name.png")
        captureScreenRoboImage(file.path)
        if (file.exists()) assertTrue("$name 沒有畫出東西", !blank(file))
    }

    private fun shot(name: String, dark: Boolean = false, tidy: Boolean = true, content: @Composable () -> Unit) {
        show(dark, content)
        capture(name, tidy)
    }

    /** 面板：跟 App 裡的底部面板一樣，左右 20dp、可以捲 */
    private fun sheet(name: String, dark: Boolean = false, content: @Composable () -> Unit) = shot(name, dark) { SheetBody(content) }

    @Composable
    private fun SheetBody(content: @Composable () -> Unit) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 32.dp)) { content() }
    }

    private fun waitPhotos(n: Int) = compose.waitUntil(WAIT) { compose.onAllNodesWithContentDescription("照片").fetchSemanticsNodes().size >= n }

    private fun has(text: String, substring: Boolean = false) = compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    // ================================================================ 資料

    /** 設定 Supabase 網址、金鑰（空字串＝還沒設定）與上次登入的 Email */
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

    /** App 真的示範模式資料來源：照片、語音從這裡下載 */
    private fun liveDemo(): DemoRepository {
        vm.container.startDemo()
        val live = vm.repo.value as DemoRepository
        live.running = false
        runBlocking { vm.container.media.clear() }
        return live
    }

    /** 換掉 App 目前的資料來源 */
    private fun setRepo(repo: CoupleRepository) {
        @Suppress("UNCHECKED_CAST")
        val field = vm.container.javaClass.getDeclaredField("_repo").apply { isAccessible = true }.get(vm.container) as MutableStateFlow<CoupleRepository?>
        field.value = repo
    }

    /** 示範照片直接放進快取（不用登入、不用網路） */
    private fun primeCache(vararg photos: MediaItem) = runBlocking {
        photos.forEach {
            vm.container.media.put(it.path, DemoMedia.sample(it.path)!!)
            vm.container.media.bitmap(it.path, 512)
        }
    }

    /** 測試用的「照片」：粉色漸層加一張笑臉 */
    private fun testPhoto(): Bitmap {
        val bmp = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 900f, 1200f, 0xFFFFC2D6.toInt(), 0xFF8EC5FF.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 900f, 1200f, p)
        p.shader = null
        p.color = 0xFFFFE0B2.toInt(); c.drawCircle(450f, 560f, 260f, p)
        p.color = 0xFF5D4037.toInt(); c.drawCircle(360f, 500f, 30f, p); c.drawCircle(540f, 500f, 30f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 22f; c.drawArc(330f, 520f, 570f, 700f, 20f, 140f, false, p)
        return bmp
    }

    private fun photoAvatar() = runBlocking {
        demo.updateAvatar(Avatars.encodePhoto(Avatars.crop(testPhoto(), Avatars.cropOf(900, 1200, 600f, 1.3f, 0f, 60f))))
    }

    /**
     * 使用者截圖裡的首頁：正式使用（不是示範）、對方用行動網路（約 30 Mbps）、手機型號很長、
     * 還沒有位置、今天的步數沒有回報。
     */
    private fun phoneState(me: String, partner: String, model: String, battery: Int, screenMinutesAgo: Long, name: String? = null, carrier: String? = null): AppState {
        val now = Instant.now()
        return s.copy(
            demo = false,
            me = s.me!!.copy(displayName = me),
            partner = s.partner!!.copy(displayName = partner),
            partnerStatus = PartnerStatus(
                DeviceStatus(
                    DemoRepository.PARTNER, batteryLevel = battery, isCharging = false, network = "cellular", networkLabel = "約 30 Mbps",
                    notificationsSilenced = false, stepsToday = null, lastActiveAt = now.minusSeconds(screenMinutesAgo * 60).toString(),
                    deviceModel = model, updatedAt = now.minusSeconds(120).toString(), deviceName = name, carrier = carrier,
                ),
                location = null,
            ),
        )
    }

    /** 星座運勢固定生日，每天截圖都一樣：阿哲是摩羯座、小雨是射手座 */
    private val fortuneState: AppState
        get() = s.copy(me = s.me?.copy(birthday = "1998-01-08"), partner = s.partner?.copy(birthday = "1999-12-05"))
    private val fortuneNow = LocalDateTime.of(2026, 10, 16, 21, 30)

    // ================================================================ 開始使用

    @Config(qualifiers = PHONE)
    @Test fun splash() = shot("01_splash", tidy = false) { SplashScreen() }

    @Config(qualifiers = PHONE)
    @Test fun welcome() = shot("02_welcome", tidy = false) { WelcomeScreen(vm) {} }

    @Test fun setup() {
        configure("", "")
        shot("03_setup") { SupabaseSetupScreen(vm, {}) {} }
    }

    @Test fun setupConnected() {
        configured()
        shot("04_setup_connected") { SupabaseSetupScreen(vm, {}) {} }
    }

    /** 貼了後台網址和混進全形字的金鑰：提示文字 */
    @Test fun setupHints() {
        configure("https://supabase.com/dashboard/project/abcdefghijklmnopqrst/settings/api", "ｓｂ_publishable_Xy7kQ2mN9pL4rT8vW1zA3b")
        shot("05_setup_hints") { SupabaseSetupScreen(vm, {}) {} }
    }

    @Test fun login() {
        configured()
        shot("06_login") { LoginScreen(vm, {}) {} }
    }

    @Test fun register() {
        configured()
        shot("07_register") { RegisterScreen(vm, {}) {} }
    }

    @Config(qualifiers = PHONE)
    @Test fun inviteConfirm() {
        configure("", "")
        show {
            SupabaseSetupScreen(vm, {}) {}
            InviteConfirmDialog("https://qrstuvwxyz.supabase.co" to "sb_publishable_Qw3rTy7UiOp1AsDfGh9JkL", "", demo = false, {}, {})
        }
        screen("08_dialog_invite_confirm")
    }

    @Config(qualifiers = PHONE)
    @Test fun inviteWarning() {
        configured()
        show {
            WelcomeScreen(vm) {}
            InviteConfirmDialog("https://attacker.example" to "sb_publishable_xxxxxxxxxx", "https://abcdefgh.supabase.co", demo = true, {}, {})
        }
        screen("09_dialog_invite_warning")
    }

    /** 連 Supabase 連很久：「還連不上」的說明和按鈕 */
    @Config(qualifiers = PHONE)
    @Test fun connecting() {
        configured()
        setRepo(object : CoupleRepository by demo {
            override val state = MutableStateFlow(AppState())
        })
        compose.setContent { XlkTheme { XlkRoot(vm, null) {} } }
        compose.mainClock.advanceTimeBy(5000)
        compose.waitUntil(WAIT) { has("還連不上", substring = true) }
        capture("10_connecting", tidy = false)
    }

    @Test fun pairing() = shot("11_pairing") { PairingScreen(s, vm) }

    /** 還沒配對：配對前也能用的我的資料、備忘錄、運勢 */
    @Config(qualifiers = TALL)
    @Test fun pairingUnpaired() = shot("12_pairing_unpaired") { PairingScreen(s.copy(couple = null, partner = null), vm) {} }

    @Config(qualifiers = TALL)
    @Test fun consent() = shot("13_consent") { ConsentScreen(s, vm) {} }

    @Config(qualifiers = TALL)
    @Test fun permissions() = shot("14_permissions") { PermissionsScreen(vm, {}, {}) }

    // ================================================================ 首頁

    @Config(qualifiers = TALL)
    @Test fun home() = shot("15_home") { HomeScreen(s, vm, {}, {}) }

    @Config(qualifiers = TALL)
    @Test fun homeDark() = shot("16_home_dark", dark = true) { HomeScreen(s, vm, {}, {}) }

    /** 使用者的截圖（ASUS）：「梓梓 的手機」＋「asus ASUSAI2501C」、行動網路、沒有步數 */
    @Config(qualifiers = TALL)
    @Test fun homePhoneAsusDark() = shot("17_home_phone_asus_dark", dark = true) {
        HomeScreen(phoneState("梓梓的小宝", "梓梓", "asus ASUSAI2501C", 76, 3), vm, {}, {})
    }

    /** 使用者的截圖（OPPO）：「梓梓的小宝 的手機」＋「OPPO CPH2689」 */
    @Config(qualifiers = TALL)
    @Test fun homePhoneOppoDark() = shot("18_home_phone_oppo_dark", dark = true) {
        HomeScreen(phoneState("梓梓", "梓梓的小宝", "OPPO CPH2689", 55, 2), vm, {}, {})
    }

    @Config(qualifiers = TALL)
    @Test fun homePhoneAsusLight() = shot("19_home_phone_asus") {
        HomeScreen(phoneState("梓梓的小宝", "梓梓", "asus ASUSAI2501C", 76, 3), vm, {}, {})
    }

    /** 手機名稱、電信（資料庫更新後）：「ROG Phone 9 · asus ASUSAI2501C」「中華電信」 */
    @Config(qualifiers = TALL)
    @Test fun homePhoneNameCarrier() = shot("19b_home_phone_name_carrier") {
        HomeScreen(phoneState("梓梓的小宝", "梓梓", "asus ASUSAI2501C", 76, 3, name = "梓梓的 ROG Phone 9", carrier = "中華電信"), vm, {}, {})
    }

    /** 我的手機：上面切到「我」 */
    @Config(qualifiers = TALL)
    @Test fun homeMyPhone() {
        show { HomeScreen(phoneState("梓梓", "梓梓的小宝", "OPPO CPH2689", 55, 2, name = "OPPO Find X8 Pro", carrier = "遠傳電信"), vm, {}, {}) }
        compose.onNode(hasText("我") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
        compose.mainClock.advanceTimeBy(1000)
        assertTrue("看得到自己的手機", has("我的手機"))
        capture("19c_home_my_phone")
    }

    /** 我暫停分享（上面的提醒＋恢復按鈕）、對方也暫停了（卡片裡的說明） */
    @Config(qualifiers = TALL)
    @Test fun homePaused() {
        val until = Instant.now().plusSeconds(3600).toString()
        val st = phoneState("梓梓的小宝", "梓梓", "OPPO CPH2689", 55, 2).let {
            it.copy(mySharing = it.mySharing!!.copy(pausedUntil = until), partnerSharing = it.partnerSharing!!.copy(pausedUntil = until))
        }
        shot("20_home_paused") { HomeScreen(st, vm, {}, {}) }
    }

    /** 整個 App（示範模式）：底部分頁列 */
    @Config(qualifiers = PHONE)
    @Test fun appShell() {
        liveDemo()
        compose.setContent { XlkTheme { XlkRoot(vm, null) {} } }
        compose.waitUntil(WAIT) { vm.prefs.value != null && vm.state.value.loaded }
        compose.mainClock.advanceTimeBy(2500)
        capture("21_app_home", tidy = false)
        compose.onNodeWithText("我的").performClick()
        compose.mainClock.advanceTimeBy(1500)
        capture("22_app_more", tidy = false)
    }

    /** App 裡跳出來的通知卡片 */
    @Test fun banners() = shot("23_banners") {
        Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            BannerCard(true, "🤗", "梓梓的小宝 給你一個抱抱", "今天辛苦了，晚上一起吃熱炒好不好？我已經在路上了") {}
            BannerCard(true, "🔋", "梓梓 的手機電量剩 15%", "提醒對方充電，或傳個訊息關心一下") {}
            BannerCard(true, "📍", "梓梓 抵達 梓梓公司", null) {}
        }
    }

    // ================================================================ 帳本、日曆、照顧

    @Config(qualifiers = TALL)
    @Test fun money() = shot("24_money") { MoneyScreen(s, vm) {} }

    @Config(qualifiers = TALL)
    @Test fun moneyMine() {
        show { MoneyScreen(s, vm) {} }
        compose.onNodeWithText("我的帳本").performClick()
        compose.mainClock.advanceTimeBy(2500)
        capture("25_money_mine")
    }

    @Config(qualifiers = TALL)
    @Test fun calendar() {
        val live = liveDemo()
        show {
            val st by live.state.collectAsState()
            CalendarScreen(st, vm, {})
        }
        capture("26_calendar")
    }

    /** 昨天：日曆下面的「這天的生活」（照片、語音） */
    @Config(qualifiers = TALL)
    @Test fun calendarDay() {
        val live = liveDemo()
        runBlocking {
            live.saveDayEntry(null, today.minusDays(1), "晚上一起散步回家，月亮好圓 🌕 走到一半下起小雨，兩個人撐一把傘", listOf(DemoMedia.SUNSET))
        }
        show {
            val st by live.state.collectAsState()
            CalendarScreen(st, vm, {}, today.minusDays(1))
        }
        waitPhotos(2)
        capture("27_calendar_day")
    }

    @Config(qualifiers = TALL)
    @Test fun calendarEmptyDay() {
        val live = liveDemo()
        show {
            val st by live.state.collectAsState()
            CalendarScreen(st, vm, {}, today.minusDays(3))
        }
        capture("28_calendar_empty_day")
    }

    @Config(qualifiers = TALL)
    @Test fun mood() = shot("29_mood") { MoodScreen(s, vm) {} }

    @Config(qualifiers = TALL)
    @Test fun cycle() = shot("30_cycle") { CycleScreen(s, vm, {}, {}) }

    @Config(qualifiers = TALL)
    @Test fun anniversaries() = shot("31_anniversaries") { AnniversaryScreen(s, vm, {}, {}) }

    // ================================================================ 我的

    @Config(qualifiers = TALL)
    @Test fun more() = shot("32_more") { MoreScreen(s, vm) {} }

    /** 正式使用（不是示範）：權限、Supabase 與帳號、解除配對 */
    @Config(qualifiers = TALL)
    @Test fun moreReal() = shot("33_more_real") { MoreScreen(s.copy(demo = false), vm) {} }

    /** 分享與隱私：暫停分享的三個按鈕「1 小時」「3 小時」「到今晚」 */
    @Config(qualifiers = TALL)
    @Test fun privacy() = shot("34_privacy") { PrivacyScreen(s, vm) {} }

    @Config(qualifiers = TALL)
    @Test fun privacyDark() = shot("35_privacy_dark", dark = true) { PrivacyScreen(s, vm) {} }

    /** 暫停中：「到 22:30」和「立即恢復」，對方也暫停了 */
    @Config(qualifiers = TALL)
    @Test fun privacyPaused() {
        val until = Instant.now().plusSeconds(3600).toString()
        shot("36_privacy_paused") { PrivacyScreen(s.copy(mySharing = s.mySharing!!.copy(pausedUntil = until), partnerSharing = s.partnerSharing!!.copy(pausedUntil = until)), vm) {} }
    }

    @Config(qualifiers = TALL)
    @Test fun history() = shot("37_history") { HistoryScreen(s, vm) {} }

    @Config(qualifiers = TALL)
    @Test fun demoConsole() = shot("38_demo_console") { DemoConsole(s, vm) {} }

    @Config(qualifiers = TALL)
    @Test fun notificationSettings() = shot("39_notification_settings") { NotificationScreen(s, vm) {} }

    @Config(qualifiers = TALL)
    @Test fun notificationSettingsOutdated() = shot("40_notification_settings_outdated") { NotificationScreen(s.copy(schemaOutdated = true), vm) {} }

    @Config(qualifiers = TALL)
    @Test fun widgets() {
        photoAvatar()
        shot("41_widgets") { WidgetsScreen(s, vm) {} }
    }

    @Config(qualifiers = TALL)
    @Test fun account() {
        configured()
        shot("42_account") { AccountScreen(s, vm) {} }
    }

    /** 資料庫還沒更新：更新步驟的卡片 */
    @Config(qualifiers = TALL)
    @Test fun accountUpgrade() {
        configured()
        shot("43_account_upgrade") { AccountScreen(s.copy(schemaOutdated = true), vm) {} }
    }

    /** 跑過舊的 upgrade-v2.sql：只差手機名稱和電信，溫和地提醒再跑一次 */
    @Config(qualifiers = TALL)
    @Test fun accountMinorUpgrade() {
        configured()
        shot("43b_account_minor_upgrade") { AccountScreen(s.copy(dbSchema = 2), vm) {} }
        assertTrue(has("可以再更新一次資料庫"))
    }

    // ================================================================ 地圖

    private val hits = listOf(
        PlaceHit("台北101", "臺北市信義區信義路五段7號", 25.0338, 121.5645),
        PlaceHit("台北101購物中心", "臺北市信義區市府路45號", 25.0340, 121.5643),
        PlaceHit("台北101觀景台", "臺北市信義區信義路五段7號89樓", 25.0336, 121.5650),
    )

    @Config(qualifiers = TALL)
    @Test fun map() = shot("44_map") { MapScreen(s, vm, {}) { _, _ -> hits } }

    /** 搜尋結果 → 選一個 → 地圖下面的卡片（新增標點） */
    @Config(qualifiers = TALL)
    @Test fun mapSearch() {
        show { MapScreen(s, vm, {}) { _, _ -> hits } }
        val field = compose.onNode(hasSetTextAction())
        field.performTextInput("台北101")
        field.performImeAction()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitUntil(WAIT) { has("台北101購物中心") }
        capture("45_map_search")
        compose.onNodeWithText("台北101購物中心").performClick()
        compose.mainClock.advanceTimeBy(1500)
        capture("46_map_pin")
    }

    @Config(qualifiers = TALL)
    @Test fun mapSearchEmpty() {
        show { MapScreen(s, vm, {}) { _, _ -> emptyList() } }
        val field = compose.onNode(hasSetTextAction())
        field.performTextInput("asdfghjkl")
        field.performImeAction()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitUntil(WAIT) { has("找不到", substring = true) }
        capture("47_map_search_empty")
    }

    // ================================================================ 資料、備忘錄、運勢

    @Config(qualifiers = TALL)
    @Test fun myProfile() = shot("48_my_profile") { MyProfileScreen(s, vm) {} }

    @Config(qualifiers = TALL)
    @Test fun partnerProfile() = shot("49_partner_profile") { PartnerProfileScreen(s, vm, {}) {} }

    @Config(qualifiers = TALL)
    @Test fun memos() {
        primeCache(DemoMedia.PICNIC, DemoMedia.TRIP, DemoMedia.SUNSET)
        val st = runBlocking {
            demo.saveMemo(null, "晚安語音 🌙", "睡不著的時候聽", listOf(DemoMedia.VOICE), true, true, "sky")
            demo.saveMemo(null, "", "週五晚上 7 點訂位 🍝\n要靠窗的位子", emptyList(), true, false, "pink")
            s
        }
        shot("50_memos") { MemosScreen(st, vm, {}) {} }
    }

    @Test fun memosEmpty() = shot("51_memos_empty") { MemosScreen(s.copy(memos = emptyList()), vm, {}) {} }

    @Config(qualifiers = TALL)
    @Test fun fortuneHoroscope() = shot("52_fortune_horoscope") { FortuneContent(fortuneState, {}, FortuneTab.HOROSCOPE, { fortuneNow }) }

    @Config(qualifiers = TALL)
    @Test fun fortunePickSign() {
        val noBirthdays = fortuneState.copy(me = fortuneState.me?.copy(birthday = null), partner = fortuneState.partner?.copy(birthday = null))
        shot("53_fortune_pick_sign") { FortuneContent(noBirthdays, {}, FortuneTab.HOROSCOPE, { fortuneNow }) }
    }

    @Config(qualifiers = TALL)
    @Test fun fortuneOmikuji() {
        val store = FortuneStore(app, fortuneState.myId.orEmpty())
        store.add(23, fortuneNow.minusHours(3))
        store.add(100, fortuneNow.minusMinutes(40))
        shot("54_fortune_omikuji") { FortuneContent(fortuneState, {}, FortuneTab.OMIKUJI, { fortuneNow }) }
    }

    /** 抽到籤：籤詩、解籤、紀錄 */
    @Config(qualifiers = TALL)
    @Test fun fortuneOmikujiResult() {
        val seed = (0..1000).first { Omikuji.draw(Random(it)).luck == "末小吉" }
        show { FortuneContent(fortuneState, {}, FortuneTab.OMIKUJI, { fortuneNow }, Random(seed)) }
        compose.onNodeWithText("搖一搖抽籤").performClick()
        compose.mainClock.advanceTimeBy(4000)
        compose.waitForIdle()
        capture("55_fortune_omikuji_result")
    }

    // ================================================================ 面板

    @Test fun comfortSheet() = sheet("60_sheet_comfort") { ComfortSheet(s, vm) {} }

    @Test fun guessSheet() = sheet("61_sheet_guess") { GuessSheet(s, vm, s.partnerExpenses.first { it.waitingForGuess }.id) {} }

    @Test fun expenseNewSheet() = sheet("62_sheet_expense_new") { ExpenseEditSheet(s, vm, null) {} }

    @Test fun expenseEditSheet() = sheet("63_sheet_expense_edit") { ExpenseEditSheet(s, vm, s.myExpenses.first().id) {} }

    @Test fun expenseViewSheet() = sheet("64_sheet_expense_view") {
        ExpenseViewSheet(s, (s.partnerExpenses.firstOrNull { !it.waitingForGuess } ?: s.partnerExpenses.first()).id) {}
    }

    /** 測試自動記帳：LINE錢包的付款格式，和最近收到的付款通知 */
    @Test fun parseTestSheet() {
        val book = vm.container.autoBookkeeping
        fun notice(title: String, text: String) = androidx.core.app.NotificationCompat.Builder(app, "x")
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(text).build()
        runBlocking {
            book.clear()
            book.handle(tw.xiaoliangkou.app.core.PaymentParser.LINE_APP, notice("LINE錢包", "NT$ 300 儲值完成。 銀行名稱 連線銀行 餘額 NT$ 350"), System.currentTimeMillis() - 3_600_000)
            book.handle(tw.xiaoliangkou.app.core.PaymentParser.LINE_APP, notice("LINE錢包", "付款 NT$ 24 付款完成。 商店名稱 OK超商 付款後餘額 NT$ 111"), System.currentTimeMillis() - 120_000)
        }
        sheet("65_sheet_parse_test") { ParseTestSheet(vm) {} }
    }

    @Test fun avatarSheet() = sheet("66_sheet_avatar") { AvatarSheet(s, vm) {} }

    /** 從地圖搜尋新增：名稱先填好 */
    @Test fun placeSheet() = sheet("67_sheet_place") { PlaceSheet(s, vm, null, 25.0340, 121.5643, "台北101購物中心") {} }

    @Test fun placeEditSheet() = sheet("68_sheet_place_edit") { PlaceSheet(s, vm, s.places.first().id, s.places.first().lat, s.places.first().lng) {} }

    @Test fun eventNewSheet() = sheet("69_sheet_event_new") { EventSheet(s, vm, null, today) {} }

    @Test fun eventEditSheet() = sheet("70_sheet_event_edit") { EventSheet(s, vm, "e1", today) {} }

    @Test fun anniversarySheet() = sheet("71_sheet_anniversary") { AnniversarySheet(s, vm, null) {} }

    @Test fun anniversaryEditSheet() = sheet("72_sheet_anniversary_edit") { AnniversarySheet(s, vm, "a4") {} }

    @Test fun periodSheet() = sheet("73_sheet_period") { PeriodSheet(vm, forPartner = true) {} }

    @Test fun noteSheet() = sheet("74_sheet_note") { NoteSheet(s, vm, "n2") {} }

    @Test fun noteNewSheet() = sheet("75_sheet_note_new") { NoteSheet(s, vm, null) {} }

    @Test fun dayEntryNewSheet() {
        val live = liveDemo()
        sheet("76_sheet_day_entry_new") {
            val st by live.state.collectAsState()
            DayEntrySheet(st, vm, null, today.minusDays(3)) {}
        }
    }

    @Test fun dayEntryEditSheet() {
        val live = liveDemo()
        runBlocking {
            live.saveDayEntry(null, today, "下班買了雞蛋糕 🧇 還有拍到超可愛的晚霞，錄了一段路邊阿伯唱歌給你聽", listOf(DemoMedia.PICNIC, DemoMedia.TRIP, DemoMedia.VOICE))
        }
        val e = live.state.value.dayEntriesOn(today).last()
        show {
            SheetBody {
                val st by live.state.collectAsState()
                DayEntrySheet(st, vm, e.id, today) {}
            }
        }
        waitPhotos(2)
        capture("77_sheet_day_entry_edit")
    }

    @Test fun dayEntryPartnerSheet() {
        val live = liveDemo()
        val e = live.state.value.dayEntriesOn(today.minusDays(1)).first { it.userId == DemoRepository.PARTNER }
        show {
            SheetBody {
                val st by live.state.collectAsState()
                DayEntrySheet(st, vm, e.id, today.minusDays(1)) {}
            }
        }
        waitPhotos(1)
        capture("78_sheet_day_entry_partner")
    }

    @Test fun memoNewSheet() = sheet("79_sheet_memo_new") { MemoSheet(s, vm, null) {} }

    /** 自己的備忘錄：照片、語音，改成「只有我」的提醒 */
    @Test fun memoEditSheet() {
        primeCache(DemoMedia.PICNIC, DemoMedia.SUNSET)
        val m = s.memo("m1")!!
        runBlocking { demo.saveMemo(m.id, m.title, m.body, listOf(DemoMedia.PICNIC, DemoMedia.SUNSET, DemoMedia.VOICE), true, true, m.color) }
        show { SheetBody { MemoSheet(s, vm, "m1") {} } }
        compose.onNodeWithText("🔒 只有我").performClick()
        compose.mainClock.advanceTimeBy(1000)
        capture("80_sheet_memo_edit")
    }

    @Test fun memoPartnerSheet() {
        primeCache(DemoMedia.PICNIC, DemoMedia.TRIP, DemoMedia.SUNSET)
        sheet("81_sheet_memo_partner") { MemoSheet(s, vm, "m3") {} }
    }

    // ================================================================ 照片與語音

    /** 照片縮圖、語音膠囊、選照片／錄音按鈕 */
    @Test fun mediaPickerAndVoiceChip() {
        val photos = listOf(DemoMedia.PICNIC, DemoMedia.DINNER, DemoMedia.TRIP)
        primeCache(*photos.toTypedArray())
        show {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                MediaThumbs(photos + DemoMedia.VOICE, vm, onRemove = {})
                MediaThumbs(listOf(MediaItem("c/x/gone.jpg", MediaItem.PHOTO)), vm, size = 64.dp)
                VoiceChip(MediaItem("c/x/v.m4a", MediaItem.VOICE, durationMs = 65_000), vm)
                MediaPickerRow(vm, shared = true, onAdded = {})
            }
        }
        waitPhotos(3)
        capture("82_media_picker_voice")
    }

    /** 錄音中，和錄好了卻傳不上去（重新上傳／丟掉這段） */
    @Config(shadows = [ShadowWritingMediaRecorder::class])
    @Test fun voiceRecordingAndUnsent() {
        val live = liveDemo()
        var offline = false
        setRepo(object : CoupleRepository by live {
            override suspend fun uploadMedia(bytes: ByteArray, mime: String, kind: String, shared: Boolean, width: Int?, height: Int?, durationMs: Long?): MediaItem {
                if (offline) throw AppException("連不到 Supabase，請確認網路", "network")
                return live.uploadMedia(bytes, mime, kind, shared, width, height, durationMs)
            }
        })
        File(app.cacheDir, "voice").deleteRecursively()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        show {
            SheetBody {
                val st by vm.state.collectAsState()
                DayEntrySheet(st, vm, null, today) {}
            }
        }
        compose.onNodeWithText("🎙️ 錄音").performClick()
        compose.waitUntil(WAIT) { has("⏹ 停止", substring = true) }
        ShadowSystemClock.advanceBy(Duration.ofSeconds(12))
        compose.mainClock.advanceTimeBy(1000)
        capture("83_voice_recording")
        offline = true
        compose.onNodeWithText("⏹ 停止", substring = true).performClick()
        compose.waitUntil(WAIT) { !vm.busy && has("重新上傳") }
        compose.mainClock.advanceTimeBy(1000)
        capture("84_voice_unsent")
    }

    // ================================================================ 對話框（一般手機的高度）

    @Config(qualifiers = PHONE)
    @Test fun unpairDialog() {
        show { MoreScreen(s.copy(demo = false), vm) {} }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("解除配對"))
        compose.onNodeWithText("解除配對").performClick()
        compose.mainClock.advanceTimeBy(1000)
        screen("90_dialog_unpair")
    }

    @Config(qualifiers = PHONE)
    @Test fun deleteNoteDialog() {
        show { PartnerProfileScreen(s, vm, {}) {} }
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("刪除備註"))
        compose.onAllNodesWithContentDescription("刪除備註")[0].performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(1000)
        screen("91_dialog_delete_note")
    }

    /** 寫到一半的面板按返回：「還沒儲存喔」 */
    @Config(qualifiers = PHONE)
    @Test fun discardDraftDialog() {
        compose.setContent { XlkTheme { XlkSheet(guarded = true, close = {}) { NoteSheet(s, vm, null) {} } } }
        compose.mainClock.advanceTimeBy(1500)
        compose.onNode(hasSetTextAction()).performTextInput("想要的禮物：那雙米白色的帆布鞋")
        compose.mainClock.advanceTimeBy(500)
        val dialog = ShadowDialog.getLatestDialog() as ComponentDialog
        compose.runOnUiThread { dialog.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(1000)
        compose.waitUntil(WAIT) { has("還沒儲存喔") }
        compose.mainClock.advanceTimeBy(500)
        // 整個螢幕截圖時，面板的視窗會畫在這個對話框上面：只截對話框的視窗
        val alert = ShadowDialog.getLatestDialog()
        assertTrue(alert !== dialog)
        val view = alert.window!!.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        bmp.captureRoboImage(File(DIR, "92_dialog_discard_draft.png").path)
    }

    /** 我的資料改了沒存就按返回 */
    @Config(qualifiers = PHONE)
    @Test fun profileUnsavedDialog() {
        show { MyProfileScreen(s, vm) {} }
        compose.onNode(hasSetTextAction() and hasText("176")).performScrollTo().performTextReplacement("177")
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithContentDescription("返回").performClick()
        compose.mainClock.advanceTimeBy(1000)
        screen("93_dialog_profile_unsaved")
    }

    @Config(qualifiers = PHONE)
    @Test fun birthdayPicker() {
        show { MyProfileScreen(s.copy(me = s.me!!.copy(birthday = null)), vm) {} }
        compose.onNodeWithText("還沒填，點一下選日期").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(1000)
        screen("94_dialog_birthday_picker")
    }

    @Config(qualifiers = PHONE)
    @Test fun datePicker() {
        show { DatePickerPopup(today.toString(), {}) {} }
        screen("95_dialog_date_picker")
    }

    @Config(qualifiers = PHONE)
    @Test fun timePicker() {
        show { TimePickerPopup(LocalTime.of(19, 0), {}) {} }
        screen("96_dialog_time_picker")
    }

    @Config(qualifiers = PHONE)
    @Test fun avatarCrop() {
        compose.setContent { XlkTheme { AvatarCropDialog(testPhoto(), {}) {} } }
        compose.mainClock.advanceTimeBy(1500)
        screen("97_dialog_avatar_crop")
    }

    /** 麥克風被拒絕到不再詢問：「要打開麥克風權限」 */
    @Config(qualifiers = PHONE)
    @Test fun micBlockedDialog() {
        val deny = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                    @Suppress("UNCHECKED_CAST")
                    dispatchResult(requestCode, false as O)
                }
            }
        }
        show {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides deny) {
                SheetBody { DayEntrySheet(s, vm, null, today) {} }
            }
        }
        compose.onNodeWithText("🎙️ 錄音").performClick()
        compose.waitUntil(WAIT) { has("要打開麥克風權限") }
        screen("98_dialog_mic_blocked")
    }

    /** 點縮圖看大圖 */
    @Config(qualifiers = PHONE)
    @Test fun mediaViewer() {
        val photos = listOf(DemoMedia.PICNIC, DemoMedia.DINNER, DemoMedia.TRIP)
        primeCache(*photos.toTypedArray())
        show { Column(Modifier.padding(20.dp)) { MediaThumbs(photos, vm) } }
        waitPhotos(3)
        compose.onAllNodesWithContentDescription("照片")[1].performClick()
        compose.waitUntil(WAIT) { compose.onAllNodesWithContentDescription("照片 2").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(1000)
        screen("99_dialog_media_viewer")
    }

    companion object {
        const val DIR = "build/screens-large"
        /** 一般手機的高度（對話框、整頁固定高度的畫面） */
        const val PHONE = "zh-rTW-w360dp-h800dp-xhdpi"
        /** 很長的頁面：整頁一次畫完，截圖後再切掉多的空白、分段 */
        const val TALL = "zh-rTW-w360dp-h3600dp-xhdpi"
        /** 其他建置同時在跑時 Robolectric 會比較慢，等久一點 */
        const val WAIT = 30_000L

        /** 一張圖最高 1000dp（xhdpi 是 2000px）：看圖時不會被縮小 */
        private const val MAX = 2000
        /** 超過 96dp 的空白縮成 32dp */
        private const val GAP = 192
        private const val KEEP = 64
        /** 最後留一點邊 */
        private const val PAD = 32
        /** 找不到卡片之間的空白可以切時，上下兩張重疊 80dp */
        private const val OVERLAP = 160

        private fun near(a: Int, b: Int) =
            (0..16 step 8).all { sh -> kotlin.math.abs(((a shr sh) and 0xFF) - ((b shr sh) and 0xFF)) <= 6 }

        /** 背景色：左邊邊緣最常見的顏色（頁面左右都有留白） */
        private fun backgroundOf(img: Bitmap): Int =
            (0 until img.height step 4).map { img.getPixel(1, it) }.groupingBy { it }.eachCount().maxBy { it.value }.key

        private fun blankRows(img: Bitmap, bg: Int): BooleanArray {
            val row = IntArray(img.width)
            return BooleanArray(img.height) { y -> img.getPixels(row, 0, img.width, 0, y, img.width, 1); row.all { near(it, bg) } }
        }

        private fun write(bmp: Bitmap, file: File) = file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

        private fun blank(file: File): Boolean {
            val img = BitmapFactory.decodeFile(file.path)
            val first = img.getPixel(0, 0)
            return blankRows(img, first).all { it }
        }

        /**
         * 視窗開得很高：切掉最後只剩背景的部分、中間太長的空白縮短；
         * 還是太高就從卡片之間的空白切成幾張（name_1.png、name_2.png…），原本那張刪掉。
         */
        fun tidy(file: File) {
            val base = file.nameWithoutExtension
            val dir = file.absoluteFile.parentFile
            dir.listFiles().orEmpty().filter { Regex("${Regex.escape(base)}_\\d+\\.png").matches(it.name) }.forEach { it.delete() }
            if (!file.exists()) return // 沒有加 -Proborazzi.test.record=true 時不會存檔
            val img = BitmapFactory.decodeFile(file.path)
            val bg = backgroundOf(img)
            val blank = blankRows(img, bg)
            val last = blank.indices.lastOrNull { !blank[it] }
            assertTrue("${file.name} 沒有畫出東西", last != null)
            if (last!! >= img.height - 4) println("⚠️ ${file.name}：畫面最下面還有東西，視窗可能不夠高")

            // 要留下的列
            val end = minOf(img.height, last + 1 + PAD)
            val keep = ArrayList<Int>(end)
            var y = 0
            while (y < end) {
                if (!blank[y]) { keep += y; y++; continue }
                var e = y
                while (e < end && blank[e]) e++
                if (e - y > GAP && e < end) {
                    for (r in y until y + KEEP / 2) keep += r
                    for (r in e - KEEP / 2 until e) keep += r
                } else for (r in y until e) keep += r
                y = e
            }
            val out = Bitmap.createBitmap(img.width, keep.size, Bitmap.Config.ARGB_8888)
            val row = IntArray(img.width)
            keep.forEachIndexed { i, r -> img.getPixels(row, 0, img.width, 0, r, img.width, 1); out.setPixels(row, 0, img.width, 0, i, img.width, 1) }
            val outBlank = BooleanArray(keep.size) { blank[keep[it]] }

            // 分段：每張差不多高，從最接近的卡片之間的空白切
            val parts = mutableListOf<IntRange>()
            var start = 0
            while (start < keep.size) {
                val left = keep.size - start
                if (left <= MAX) { parts += start until keep.size; break }
                val limit = start + MAX
                val target = start + left / ((left + MAX - 1) / MAX)
                val cut = (start + MAX / 2 until limit).filter { outBlank[it] }.minByOrNull { kotlin.math.abs(it - target) }
                if (cut != null) {
                    // 切在這段空白的中間
                    var top = cut
                    while (top > start + 1 && outBlank[top - 1]) top--
                    var bottom = cut
                    while (bottom < limit - 1 && outBlank[bottom + 1]) bottom++
                    val mid = (top + bottom) / 2 + 1
                    parts += start until mid
                    start = mid
                } else {
                    parts += start until limit
                    start = limit - OVERLAP
                }
            }
            if (parts.size == 1) {
                write(out, file)
            } else {
                file.delete()
                parts.forEachIndexed { i, p ->
                    write(Bitmap.createBitmap(out, 0, p.first, out.width, p.last - p.first + 1), File(dir, "${base}_${i + 1}.png"))
                }
            }
        }
    }
}
