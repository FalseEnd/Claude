package tw.xiaoliangkou.app.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.service.ShareService
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.components.CoupleMascots
import tw.xiaoliangkou.app.ui.components.CuteButton
import tw.xiaoliangkou.app.ui.components.CuteCard
import tw.xiaoliangkou.app.ui.components.CuteField
import tw.xiaoliangkou.app.ui.components.EmojiBubble
import tw.xiaoliangkou.app.ui.components.HeartBeat
import tw.xiaoliangkou.app.ui.components.LoadingDots
import tw.xiaoliangkou.app.ui.components.Note
import tw.xiaoliangkou.app.ui.components.Pill
import tw.xiaoliangkou.app.ui.components.ScreenHeader
import tw.xiaoliangkou.app.ui.components.Segmented
import tw.xiaoliangkou.app.ui.components.SoftButton
import tw.xiaoliangkou.app.ui.components.ToggleRow
import tw.xiaoliangkou.app.ui.components.bouncyClick
import tw.xiaoliangkou.app.ui.theme.Cute
import java.time.LocalDate

@Composable
fun SplashScreen() {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CoupleMascots(height = 140.dp)
        Text("小倆口", style = MaterialTheme.typography.displayMedium)
        Box(Modifier.padding(top = 16.dp)) { LoadingDots() }
    }
}

@Composable
fun WelcomeScreen(vm: AppViewModel, onServer: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Cute.colors.partnerSoft, MaterialTheme.colorScheme.background, Cute.colors.meSoft)))
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.weight(1f))
        CoupleMascots(height = 170.dp)
        HeartBeat { Text("小倆口", style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.primary) }
        Text("用一條紅線，牽起兩個人的日常", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.weight(1f))
        CuteButton("連接我的伺服器", Modifier.fillMaxWidth(), icon = Icons.Rounded.Link, onClick = onServer)
        SoftButton("先看看示範", Modifier.fillMaxWidth(), icon = Icons.Rounded.Science) { vm.container.startDemo() }
        Text("伺服器架在你自己的電腦上，資料不經過任何雲端。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
fun ServerSetupScreen(vm: AppViewModel, back: () -> Unit) {
    val prefs = vm.prefs.value
    var url by remember { mutableStateOf(prefs?.serverUrl?.ifEmpty { null } ?: "http://") }
    var tested by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf("register") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenHeader("連接伺服器", onBack = back) }
        item {
            CuteCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    EmojiBubble("🖥️", Cute.colors.meSoft, 44.dp)
                    Text("1. 填入電腦的伺服器網址", style = MaterialTheme.typography.titleMedium)
                }
                CuteField(url, { url = it.trim(); tested = false }, "伺服器網址", placeholder = "http://192.168.1.23:8080", keyboard = KeyboardType.Uri,
                    supporting = "在電腦上雙擊啟動檔（Windows 是 start-windows.bat）後，視窗裡會列出要填的網址")
                CuteButton(if (tested) "連上了 ✅" else "測試連線", Modifier.fillMaxWidth(), icon = Icons.Rounded.Wifi, enabled = url.length > 8 && !vm.busy) {
                    vm.account(onDone = { tested = true; vm.toast("連上你的伺服器了 🎉") }) { vm.container.testServer(url) }
                }
                Note("在家用同一個 Wi-Fi 就能連；出門在外建議電腦和手機都裝 Tailscale，填 100.x.x.x 開頭的網址。", "🏠")
            }
        }
        item {
            AnimatedVisibility(tested, enter = expandVertically() + fadeIn()) {
                CuteCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        EmojiBubble("🔑", Cute.colors.partnerSoft, 44.dp)
                        Text("2. 註冊或登入", style = MaterialTheme.typography.titleMedium)
                    }
                    Segmented(listOf("register" to "第一次使用", "login" to "我已經有帳號"), mode) { mode = it }
                    CuteField(username, { username = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' || c == '.' }.take(32) }, "帳號", placeholder = "英文小寫或數字，例如 azhe")
                    CuteField(password, { password = it }, "密碼", password = true, supporting = if (mode == "register") "至少 6 個字" else null)
                    if (mode == "register") CuteField(name, { name = it.take(30) }, "暱稱", placeholder = "對方會看到的名字")
                    CuteButton(if (mode == "register") "註冊" else "登入", Modifier.fillMaxWidth(), enabled = username.length >= 3 && password.length >= 6 && (mode == "login" || name.isNotBlank()) && !vm.busy) {
                        vm.account {
                            if (mode == "register") vm.container.register(url, username, password, name.trim())
                            else vm.container.login(url, username, password)
                        }
                    }
                    if (vm.busy) Box(Modifier.align(Alignment.CenterHorizontally)) { LoadingDots() }
                }
            }
        }
    }
}

@Composable
fun PairingScreen(s: AppState, vm: AppViewModel) {
    val context = LocalContext.current
    var code by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var since by remember { mutableStateOf(LocalDate.now().toString()) }
    val scope = rememberCoroutineScope()
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                CoupleMascots(height = 120.dp, rightColor = MaterialTheme.colorScheme.outline)
                Text("嗨 ${s.me?.displayName ?: ""}，來配對另一半吧", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text("一個人產生配對碼，另一個人輸入", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            CuteCard {
                Text("我的配對碼", style = MaterialTheme.typography.titleMedium)
                if (code == null) CuteButton("產生配對碼", Modifier.fillMaxWidth(), icon = Icons.Rounded.Favorite, enabled = !vm.busy) { vm.act { code = it.createInvite().code } }
                else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                        code!!.forEach { ch ->
                            Box(Modifier.size(44.dp, 54.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                                Text(ch.toString(), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    }
                    Text("24 小時內有效", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SoftButton("複製", Modifier.weight(1f), icon = Icons.Rounded.ContentCopy) {
                            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("配對碼", code))
                            vm.toast("已複製配對碼")
                        }
                        CuteButton("傳給對方", Modifier.weight(1f), icon = Icons.Rounded.Share) {
                            val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "打開小倆口，輸入配對碼 $code 跟我配對 💞") }
                            context.startActivity(Intent.createChooser(send, "傳送配對碼"))
                        }
                    }
                }
            }
        }
        item {
            CuteCard {
                Text("或輸入對方的配對碼", style = MaterialTheme.typography.titleMedium)
                CuteField(input, { input = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(6) }, "配對碼", placeholder = "例如 K7Q2M9")
                DateField("在一起的日子", since) { since = it }
                CuteButton("配對", Modifier.fillMaxWidth(), enabled = input.length == 6 && !vm.busy) {
                    vm.act("配對成功！💞", hearts = true) { it.acceptInvite(input, since) }
                }
            }
        }
        item { Text("配對後兩個人各自決定要分享什麼，隨時可以暫停或解除。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
        item { SoftButton("登出", Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Rounded.Logout) { scope.launch { vm.container.logout() } } }
    }
}

@Composable
fun ConsentScreen(s: AppState, vm: AppViewModel, next: () -> Unit) {
    val sh = s.mySharing
    fun set(key: String, v: Boolean) = vm.act { it.updateSharing(buildJsonObject { put(key, v) }) }
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("已和 ${s.partnerName} 配對 💞", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                Text("決定要分享什麼", style = MaterialTheme.typography.headlineSmall)
                Text("每一項之後都能改，改了對方會知道", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            CuteCard {
                ToggleRow("位置", sh?.shareLocation == true, "對方看得到你在哪、抵達或離開標點", "📍") { set("location", it) }
                ToggleRow("手機狀態", sh?.shareDevice == true, "電量、網路、螢幕、步數", "🔋") { set("device", it) }
                ToggleRow("開銷", sh?.shareExpenses == true, "自動記帳，可以玩猜花費", "💰") { set("expenses", it) }
                ToggleRow("心情", sh?.shareMood == true, "對方會知道你今天過得如何", "😊") { set("mood", it) }
            }
        }
        item {
            CuteCard {
                ToggleRow("記錄我的經期", s.cycle.mine.settings != null, "預測下次經期、排卵日；要不要分享給對方可以再決定", "🌸") { on ->
                    vm.act { if (on) it.saveCycleSettings(buildJsonObject { put("careReminders", true) }) else it.stopCycle() }
                }
            }
        }
        item { CuteButton("下一步：開啟權限", Modifier.fillMaxWidth(), onClick = next) }
    }
}

private fun granted(context: Context, p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

@Composable
fun PermissionsScreen(vm: AppViewModel, back: (() -> Unit)?, done: (() -> Unit)?) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val refresh: (Any?) -> Unit = { tick++; ShareService.start(context) }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), refresh)
    val location = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions(), refresh)
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), refresh)
    val activity = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), refresh)

    @Suppress("UNUSED_VARIABLE") val t = tick
    val hasNotif = Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS)
    val hasLoc = granted(context, Manifest.permission.ACCESS_FINE_LOCATION) || granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    val hasBg = Build.VERSION.SDK_INT < 29 || granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    val hasBattery = context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
    val hasListener = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    val hasActivity = Build.VERSION.SDK_INT < 29 || granted(context, Manifest.permission.ACTIVITY_RECOGNITION)

    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenHeader("權限與背景執行", onBack = back) }
        item { Text("小倆口要在背景工作，才能即時收到抱抱、分享位置。一個一個打開吧：", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            Step("🔔", "通知", "收到抱抱、抵達、約會提醒", hasNotif) { if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
        item {
            Step("📍", "位置", "分享位置、判斷有沒有進出標點", hasLoc) {
                location.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        }
        if (Build.VERSION.SDK_INT >= 29) item {
            Step("🌙", "位置：一律允許", "手機放口袋時也能判斷抵達、離開（會打開設定頁，請選「一律允許」）", hasBg, enabled = hasLoc) {
                background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
        item {
            Step("🔋", "不限制電池用量", "手機休眠時也保持連線，通知才不會延遲", hasBattery) {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
                }.onFailure { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }
        }
        item {
            Step("🤖", "自動記帳（選用）", "讀取 LINE Pay、街口、銀行的付款通知。打開設定後找到「小倆口自動記帳」", hasListener) {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        if (Build.VERSION.SDK_INT >= 29) item {
            Step("👣", "體能活動（選用）", "計算今天走了幾步", hasActivity) { activity.launch(Manifest.permission.ACTIVITY_RECOGNITION) }
        }
        item { Note("小米、OPPO、vivo、三星等手機，請另外在系統設定允許小倆口「自動啟動」或「背景活動」，不然可能收不到通知。", "📱") }
        if (done != null) item { CuteButton(if (hasNotif && hasLoc) "完成，開始使用 💞" else "先跳過，之後再說", Modifier.fillMaxWidth(), onClick = done) }
    }
}

@Composable
private fun Step(emoji: String, title: String, desc: String, ok: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(if (ok) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f) else Cute.colors.card)
            .bouncyClick(enabled = enabled && !ok, onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EmojiBubble(emoji, if (ok) Cute.colors.card else MaterialTheme.colorScheme.primaryContainer, 46.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (ok) Text("✅", style = MaterialTheme.typography.titleLarge)
        else Pill(if (enabled) "開啟" else "先開位置", if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

