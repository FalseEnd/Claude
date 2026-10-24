package tw.xiaoliangkou.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetDefaults
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import tw.xiaoliangkou.app.data.Banner
import tw.xiaoliangkou.app.data.SettingsStore
import tw.xiaoliangkou.app.data.toInstantOrNull
import tw.xiaoliangkou.app.ui.components.BannerCard
import tw.xiaoliangkou.app.ui.components.CuteButton
import tw.xiaoliangkou.app.ui.components.HeartBurstOverlay
import tw.xiaoliangkou.app.ui.components.LoadingDots
import tw.xiaoliangkou.app.ui.components.SoftButton
import tw.xiaoliangkou.app.ui.components.bouncyClick
import tw.xiaoliangkou.app.ui.components.rememberHeartBurst
import tw.xiaoliangkou.app.ui.screens.AccountScreen
import tw.xiaoliangkou.app.ui.screens.AnniversaryScreen
import tw.xiaoliangkou.app.ui.screens.AnniversarySheet
import tw.xiaoliangkou.app.ui.screens.AvatarSheet
import tw.xiaoliangkou.app.ui.screens.CalendarScreen
import tw.xiaoliangkou.app.ui.screens.ComfortSheet
import tw.xiaoliangkou.app.ui.screens.ConsentScreen
import tw.xiaoliangkou.app.ui.screens.CycleScreen
import tw.xiaoliangkou.app.ui.screens.DayEntrySheet
import tw.xiaoliangkou.app.ui.screens.DemoConsole
import tw.xiaoliangkou.app.ui.screens.EventSheet
import tw.xiaoliangkou.app.ui.screens.ExpenseEditSheet
import tw.xiaoliangkou.app.ui.screens.ExpenseViewSheet
import tw.xiaoliangkou.app.ui.screens.FortuneScreen
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
import tw.xiaoliangkou.app.ui.screens.WeatherScreen
import tw.xiaoliangkou.app.ui.screens.WelcomeScreen
import tw.xiaoliangkou.app.ui.screens.WidgetsScreen
import tw.xiaoliangkou.app.ui.theme.Cute
import java.time.LocalDate

/** 底部面板的種類 */
sealed interface SheetSpec {
    /** 安撫：[replyTo] 是要回應的那則收到的安撫（點收到安撫的通知打開的） */
    data class Comfort(val replyTo: Long? = null) : SheetSpec
    data class GuessIt(val id: String) : SheetSpec
    data class ExpenseEdit(val id: String?) : SheetSpec
    data class ExpenseView(val id: String) : SheetSpec
    data object ParseTest : SheetSpec
    data object Avatar : SheetSpec
    /** [name]：從地圖搜尋新增時，先填好的名稱 */
    data class PlaceEdit(val id: String?, val lat: Double, val lng: Double, val name: String = "") : SheetSpec
    data class EventEdit(val id: String?, val date: LocalDate) : SheetSpec
    data class AnniversaryEdit(val id: String?) : SheetSpec
    data class PeriodEdit(val forPartner: Boolean) : SheetSpec
    data class NoteEdit(val id: String?) : SheetSpec
    data class DayEntryEdit(val id: String?, val date: LocalDate) : SheetSpec
    data class MemoEdit(val id: String?) : SheetSpec
}

private val TABS = listOf(
    Triple("home", "首頁", Icons.Rounded.Home),
    Triple("map", "地圖", Icons.Rounded.Map),
    Triple("money", "帳本", Icons.Rounded.AccountBalanceWallet),
    Triple("calendar", "日曆", Icons.Rounded.CalendarMonth),
    Triple("more", "我的", Icons.Rounded.Person),
)

private fun bannerEmoji(kind: String) = when (kind) {
    "comfort" -> "🤗"; "mood" -> "😊"; "place" -> "📍"; "battery_low" -> "🔋"; "guess" -> "💰"
    "event" -> "📅"; "anniversary" -> "🎂"; "cycle_care" -> "🌸"; "sharing_changed" -> "🔒"; "pairing" -> "💞"
    "diary" -> "📔"; "memo" -> "📝"; else -> "💗"
}

@Composable
fun XlkRoot(vm: AppViewModel, deepLink: String?, onDeepLinkHandled: () -> Unit) {
    val prefs by vm.prefs.collectAsState()
    val repo by vm.repo.collectAsState()
    val s by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val hearts = rememberHeartBurst()
    var banner by remember { mutableStateOf<Banner?>(null) }
    var pendingRoute by remember { mutableStateOf<String?>(null) }
    val pendingInvite by vm.container.pendingInvite.collectAsState()

    LaunchedEffect(vm) {
        vm.messages.collect { msg -> snackbar.currentSnackbarData?.dismiss(); launch { snackbar.showSnackbar(msg) } }
    }
    LaunchedEffect(vm) { vm.hearts.collect { hearts.burst() } }
    LaunchedEffect(vm) { vm.container.notices.collect { vm.toast(it) } }
    LaunchedEffect(vm) {
        vm.banners.collectLatest { b ->
            banner = b
            if (b.kind == "comfort") hearts.burst(0.5f, 0.35f, 22)
            delay(4500)
            banner = null
        }
    }
    LaunchedEffect(deepLink) { if (deepLink != null) { pendingRoute = deepLink; onDeepLinkHandled() } }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val p = prefs
        when {
            p == null -> SplashScreen()
            repo == null -> StartFlow(vm)
            !s.loaded -> Connecting(vm)
            !s.paired -> UnpairedScaffold(vm, pendingRoute) { pendingRoute = null }
            !p.onboarded && !s.demo -> Box(Modifier.systemBarsPadding()) { Onboarding(s, vm) }
            else -> MainScaffold(vm, pendingRoute) { pendingRoute = null }
        }
        Box(Modifier.statusBarsPadding().padding(top = 8.dp)) {
            val b = banner
            BannerCard(b != null, bannerEmoji(b?.kind ?: ""), b?.title ?: "", b?.body) {
                pendingRoute = b?.route; banner = null
            }
        }
        HeartBurstOverlay(hearts)
        // 另一半的邀請連結：先確認要連到哪裡（示範模式、還沒登入時才會出現）
        pendingInvite?.let { inv ->
            InviteConfirmDialog(inv, p?.supabaseUrl, demo = p?.mode == SettingsStore.Mode.DEMO, onConfirm = vm.container::confirmInvite, onDismiss = vm.container::dismissInvite)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp)) { data ->
            Text(
                data.visuals.message,
                color = MaterialTheme.colorScheme.background,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 24.dp).shadow(12.dp, RoundedCornerShape(50)).clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.onBackground).padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}

/** 還沒登入：歡迎 → 連接 Supabase → 註冊／登入 */
@Composable
private fun StartFlow(vm: AppViewModel) {
    val prefs by vm.prefs.collectAsState()
    val prefill by vm.container.prefill.collectAsState()
    // 登出或登入過期後回來：網址、金鑰都還在，直接到登入
    var page by rememberSaveable { mutableStateOf(if (prefs?.configured == true && prefs?.lastEmail?.isNotEmpty() == true) "login" else "welcome") }
    // 確認了另一半的邀請：到「連接 Supabase」，網址、金鑰已經填好
    LaunchedEffect(prefill) { if (prefill != null) page = "setup" }
    val toWelcome = { vm.container.clearPrefill(); page = "welcome" }
    BackHandler(page != "welcome") { if (page == "setup") toWelcome() else page = "setup" }
    val order = listOf("welcome", "setup", "login", "register")
    AnimatedContent(page, Modifier.systemBarsPadding().imePadding(), transitionSpec = {
        val forward = order.indexOf(targetState) >= order.indexOf(initialState)
        (slideInHorizontally { if (forward) it else -it } + fadeIn()) togetherWith (slideOutHorizontally { if (forward) -it else it } + fadeOut())
    }, label = "start") { p ->
        when (p) {
            "setup" -> SupabaseSetupScreen(vm, back = toWelcome) { page = it }
            "login" -> LoginScreen(vm, back = { page = "setup" }) { page = "register" }
            "register" -> RegisterScreen(vm, back = { page = "setup" }) { page = "login" }
            else -> WelcomeScreen(vm, onOther = { page = "setup" }) { page = if (prefs?.lastEmail?.isNotEmpty() == true) "login" else "register" }
        }
    }
}

@Composable
private fun Connecting(vm: AppViewModel) {
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(4000); slow = true }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
        tw.xiaoliangkou.app.ui.components.CoupleMascots(height = 130.dp)
        Text("正在連到你們的 Supabase…", style = MaterialTheme.typography.titleMedium)
        LoadingDots()
        if (slow) {
            Text("還連不上。請確認手機有網路；免費的 Supabase 專案一週沒人用會被暫停，到 Supabase 後台按 Resume project 就好 ☁️", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CuteButton("再試一次", Modifier.fillMaxWidth()) { vm.refresh() }
            SoftButton("登出，重新設定", Modifier.fillMaxWidth()) { scope.launch { vm.container.logout() } }
        }
    }
}

@Composable
private fun Onboarding(s: tw.xiaoliangkou.app.data.AppState, vm: AppViewModel) {
    var step by rememberSaveable { mutableStateOf(0) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    AnimatedContent(step, transitionSpec = { (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it } + fadeOut()) }, label = "onboarding") { st ->
        if (st == 0) ConsentScreen(s, vm) { step = 1 }
        else PermissionsScreen(vm, back = { step = 0 }, done = { scope.launch { vm.container.setOnboarded() } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(vm: AppViewModel, pendingRoute: String?, routeHandled: () -> Unit) {
    val s by vm.state.collectAsState()
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    var sheet by remember { mutableStateOf<SheetSpec?>(null) }
    // 從通知、存好的約會指定日曆要看哪天：跳過去一次就清掉（之後再進日曆照樣從今天開始）。
    // 第二個值讓同一天再指定一次也會跳
    var calendarJump by remember { mutableStateOf<Pair<LocalDate, Long>?>(null) }
    val tabIds = TABS.map { it.first }
    val open: (String) -> Unit = { target ->
        val r = when {
            // 生活紀錄、紀念日的通知：「calendar/2026-10-15」直接打開那一天
            target.startsWith("calendar/") -> {
                runCatching { LocalDate.parse(target.substringAfter('/')) }.getOrNull()?.let { calendarJump = it to System.nanoTime() }
                "calendar"
            }
            // 約會的通知：打開那一天，在那裡答應、婉拒（還沒載入的話就是日曆）
            target.startsWith("event/") -> {
                s.events.firstOrNull { it.id == target.substringAfter('/') }?.startsAt?.toInstantOrNull()
                    ?.let { calendarJump = Fmt.localDate(it) to System.nanoTime() }
                "calendar"
            }
            // 收到安撫、對方心情不好的通知、常駐通知的「更多安撫」：首頁打開安撫（「comfort/12」是回應那一則）
            target == "comfort" || target.startsWith("comfort/") -> {
                sheet = SheetSpec.Comfort(target.substringAfter('/', "").toLongOrNull())
                "home"
            }
            else -> target
        }
        if (r in tabIds) nav.navigate(r) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        } else nav.navigate(r) { launchSingleTop = true }
    }
    val back: () -> Unit = { nav.popBackStack() }
    val showSheet: (SheetSpec) -> Unit = { sheet = it }
    LaunchedEffect(pendingRoute) { pendingRoute?.let { open(it); routeHandled() } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { if (route == null || route in tabIds) CuteBottomBar(route ?: "home", open) },
    ) { pad ->
        NavHost(
            nav, "home", Modifier.padding(pad).statusBarsPadding(),
            enterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 10 } },
            exitTransition = { fadeOut(tween(160)) },
            popEnterTransition = { fadeIn(tween(220)) },
            popExitTransition = { fadeOut(tween(160)) + slideOutHorizontally(tween(220)) { it / 10 } },
        ) {
            composable("home") { HomeScreen(s, vm, open, showSheet) }
            composable("map") { MapScreen(s, vm, showSheet) }
            composable("money") { MoneyScreen(s, vm, showSheet) }
            composable("calendar") { CalendarScreen(s, vm, showSheet, calendarJump?.first, calendarJump?.second ?: 0L) { calendarJump = null } }
            composable("more") { MoreScreen(s, vm, showSheet, open) }
            composable("mood") { MoodScreen(s, vm, back = back) }
            composable("anniv") { AnniversaryScreen(s, vm, back, showSheet) }
            composable("cycle") { CycleScreen(s, vm, back, showSheet) }
            composable("history") { HistoryScreen(s, vm, back) }
            composable("privacy") { PrivacyScreen(s, vm, back) }
            composable("notif") { NotificationScreen(s, vm, back) }
            composable("widgets") { WidgetsScreen(s, vm, back) }
            composable("profile") { MyProfileScreen(s, vm, back) }
            composable("partner") { PartnerProfileScreen(s, vm, back, showSheet) }
            composable("memos") { MemosScreen(s, vm, back, showSheet) }
            composable("fortune") { FortuneScreen(s, vm, back) }
            composable("weather") { WeatherScreen(s, vm, back, open) }
            composable("account") { AccountScreen(s, vm, back = back) }
            composable("permissions") { PermissionsScreen(vm, back, null) }
            composable("demo") { DemoConsole(s, vm, back) }
        }
    }

    sheet?.let { current -> SheetHost(s, vm, current, close = { sheet = null }) { calendarJump = it to System.nanoTime() } }
}

/** 還沒配對也能用的頁面（都有「還沒配對」的版本）；桌面的備忘錄小工具點了會到「memos」 */
internal val UNPAIRED_ROUTES = setOf("profile", "memos", "fortune")

/**
 * 還沒配對（或解除配對後）：配對頁，加上配對前也能用的我的資料、備忘錄、星座運勢與御神籤。
 * 解除配對後自己的備忘錄還在（桌面小工具也還看得到），這裡才打得開、改得了。
 * 其他的通知（例如約會、心情）等配對好再打開。
 */
@Composable
private fun UnpairedScaffold(vm: AppViewModel, pendingRoute: String?, routeHandled: () -> Unit) {
    val s by vm.state.collectAsState()
    val nav = rememberNavController()
    var sheet by remember { mutableStateOf<SheetSpec?>(null) }
    val open: (String) -> Unit = { nav.navigate(it) { launchSingleTop = true } }
    val back: () -> Unit = { nav.popBackStack() }
    LaunchedEffect(pendingRoute) { pendingRoute?.takeIf { it in UNPAIRED_ROUTES }?.let { open(it); routeHandled() } }
    NavHost(
        nav, "pairing", Modifier.fillMaxSize().systemBarsPadding(),
        enterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 10 } },
        exitTransition = { fadeOut(tween(160)) },
        popEnterTransition = { fadeIn(tween(220)) },
        popExitTransition = { fadeOut(tween(160)) + slideOutHorizontally(tween(220)) { it / 10 } },
    ) {
        composable("pairing") { PairingScreen(s, vm, open) }
        composable("profile") { MyProfileScreen(s, vm, back) }
        composable("memos") { MemosScreen(s, vm, back) { sheet = it } }
        composable("fortune") { FortuneScreen(s, vm, back) }
    }
    sheet?.let { current -> SheetHost(s, vm, current, close = { sheet = null }) {} }
}

/** 打開的底部面板。[onEventSaved]：存好約會後日曆跳到那天 */
@Composable
private fun SheetHost(s: tw.xiaoliangkou.app.data.AppState, vm: AppViewModel, current: SheetSpec, close: () -> Unit, onEventSaved: (LocalDate) -> Unit) = key(current) {
    // 會寫一大段字、放照片和語音的面板：還沒儲存就滑掉、點外面、按返回時先問一下
    val guarded = current is SheetSpec.DayEntryEdit || current is SheetSpec.MemoEdit || current is SheetSpec.NoteEdit
    XlkSheet(guarded, close) {
        when (current) {
            is SheetSpec.Comfort -> ComfortSheet(s, vm, replyTo = current.replyTo, close = close)
            is SheetSpec.GuessIt -> GuessSheet(s, vm, current.id, close)
            is SheetSpec.ExpenseEdit -> ExpenseEditSheet(s, vm, current.id, close)
            is SheetSpec.ExpenseView -> ExpenseViewSheet(s, current.id, close)
            SheetSpec.ParseTest -> ParseTestSheet(vm, close)
            SheetSpec.Avatar -> AvatarSheet(s, vm, close)
            is SheetSpec.PlaceEdit -> PlaceSheet(s, vm, current.id, current.lat, current.lng, current.name, close)
            is SheetSpec.EventEdit -> EventSheet(s, vm, current.id, current.date) { onEventSaved(current.date); close() }
            is SheetSpec.AnniversaryEdit -> AnniversarySheet(s, vm, current.id, close)
            is SheetSpec.PeriodEdit -> PeriodSheet(vm, current.forPartner, close)
            is SheetSpec.NoteEdit -> NoteSheet(s, vm, current.id, close)
            is SheetSpec.DayEntryEdit -> DayEntrySheet(s, vm, current.id, current.date, close)
            is SheetSpec.MemoEdit -> MemoSheet(s, vm, current.id, close)
        }
    }
}

/** 面板裡還沒儲存的內容（面板用 [SheetDraftGuard] 回報） */
internal class SheetDraft {
    var dirty = false
}

internal val LocalSheetDraft = staticCompositionLocalOf<SheetDraft?> { null }

/** 放在面板裡：[dirty] 表示有寫了還沒儲存的內容（或正在錄音），關掉面板前會先問 */
@Composable
fun SheetDraftGuard(dirty: Boolean) {
    val draft = LocalSheetDraft.current ?: return
    SideEffect { draft.dirty = dirty }
    // 面板換成別的畫面（例如「已經不在了」）：不用再問
    DisposableEffect(draft) { onDispose { draft.dirty = false } }
}

/**
 * 底部面板。[guarded]：有還沒儲存的內容時，往下滑、點外面、按返回都先問要不要放棄，
 * 不會一下子就把寫好的字、剛上傳的照片和語音丟掉。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun XlkSheet(guarded: Boolean, close: () -> Unit, content: @Composable () -> Unit) {
    val draft = remember { SheetDraft() }
    var askDiscard by remember { mutableStateOf(false) }
    // 往下滑、點外面：先擋下來問
    val confirm = remember(draft) {
        { v: SheetValue -> if (v == SheetValue.Hidden && draft.dirty) { askDiscard = true; false } else true }
    }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = confirm)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = close, sheetState = state, containerColor = MaterialTheme.colorScheme.background,
        // 系統的返回鍵不會經過上面的檢查：要問的面板自己處理返回
        properties = if (guarded) ModalBottomSheetProperties(shouldDismissOnBackPress = false) else ModalBottomSheetDefaults.properties,
    ) {
        if (guarded) BackHandler {
            if (draft.dirty) askDiscard = true
            else scope.launch { state.hide() }.invokeOnCompletion { if (!state.isVisible) close() }
        }
        CompositionLocalProvider(LocalSheetDraft provides draft) {
            Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
                content()
            }
        }
        if (askDiscard) AlertDialog(
            onDismissRequest = { askDiscard = false },
            title = { Text("還沒儲存喔") },
            text = { Text("剛剛寫的還沒儲存，要放棄這次寫的內容嗎？") },
            confirmButton = { TextButton(onClick = { askDiscard = false }) { Text("繼續編輯") } },
            dismissButton = {
                TextButton(onClick = { askDiscard = false; draft.dirty = false; close() }) { Text("放棄", color = MaterialTheme.colorScheme.error) }
            },
        )
    }
}

@Composable
private fun CuteBottomBar(route: String, open: (String) -> Unit) {
    Row(
        Modifier.navigationBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp).fillMaxWidth()
            .shadow(18.dp, RoundedCornerShape(30.dp), ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
            .clip(RoundedCornerShape(30.dp)).background(Cute.colors.card).padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TABS.forEach { (id, label, icon) -> TabItem(id == route, label, icon, Modifier.weight(1f)) { open(id) } }
    }
}

@Composable
private fun TabItem(selected: Boolean, label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, label = "tabBg")
    val fg by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, label = "tabFg")
    val lift by animateFloatAsState(if (selected) -3f else 0f, spring(dampingRatio = 0.35f, stiffness = 400f), label = "tabLift")
    val scale by animateFloatAsState(if (selected) 1.12f else 1f, spring(dampingRatio = 0.35f, stiffness = 400f), label = "tabScale")
    Column(
        modifier.height(56.dp).clip(RoundedCornerShape(24.dp)).background(bg).bouncyClick(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, label, tint = fg, modifier = Modifier.size(24.dp).graphicsLayer { translationY = lift.dp.toPx(); scaleX = scale; scaleY = scale })
        Text(label, style = MaterialTheme.typography.labelSmall, color = fg)
    }
}
