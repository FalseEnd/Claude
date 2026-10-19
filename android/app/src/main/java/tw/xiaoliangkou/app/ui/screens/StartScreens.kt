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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.StickyNote2
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.data.InviteLink
import tw.xiaoliangkou.app.data.Supabase
import tw.xiaoliangkou.app.service.ShareService
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.components.CoupleMascots
import tw.xiaoliangkou.app.ui.components.CuteButton
import tw.xiaoliangkou.app.ui.components.CuteCard
import tw.xiaoliangkou.app.ui.components.CuteField
import tw.xiaoliangkou.app.ui.components.EmojiBubble
import tw.xiaoliangkou.app.ui.components.HeartBeat
import tw.xiaoliangkou.app.ui.components.LoadingDots
import tw.xiaoliangkou.app.ui.components.MenuRow
import tw.xiaoliangkou.app.ui.components.Note
import tw.xiaoliangkou.app.ui.components.Pill
import tw.xiaoliangkou.app.ui.components.ScreenHeader
import tw.xiaoliangkou.app.ui.components.SoftButton
import tw.xiaoliangkou.app.ui.components.TidyText
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
fun WelcomeScreen(vm: AppViewModel, onOther: () -> Unit = {}, onStart: () -> Unit) {
    val prefs by vm.prefs.collectAsState()
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
        // 自動連上預設（或之前設定的）Supabase，成功就直接到註冊
        CuteButton(if (vm.busy) "連線中…" else "開始使用", Modifier.fillMaxWidth(), icon = Icons.Rounded.Link, enabled = !vm.busy) {
            val p = prefs ?: return@CuteButton
            vm.account(onDone = onStart) { vm.container.testConnection(p.supabaseUrl, p.supabaseKey) }
        }
        SoftButton("先看看示範", Modifier.fillMaxWidth(), icon = Icons.Rounded.Science) { vm.container.startDemo() }
        Text("使用其他 Supabase 專案", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.bouncyClick(onClick = onOther).padding(4.dp))
    }
}

/** 輸入框右邊的「貼上」小按鈕 */
@Composable
private fun PasteChip(onClick: () -> Unit) {
    Text(
        "貼上", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.padding(end = 8.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primaryContainer)
            .bouncyClick(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

internal fun clipboardText(context: Context): String =
    context.getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()

internal fun copyText(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
}

internal fun shareText(context: Context, text: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
    context.startActivity(Intent.createChooser(send, title))
}

/**
 * 打開另一半的邀請連結時先問一下：要連到哪裡（主機名稱）、有什麼要注意。
 * 按「幫我填好」只會填進「連接 Supabase」，要再按「測試連線」成功才會存起來。
 */
@Composable
fun InviteConfirmDialog(invite: Pair<String, String>, savedUrl: String?, demo: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp), contentAlignment = Alignment.Center) {
            InviteConfirmCard(invite, savedUrl, demo, onConfirm, onDismiss)
        }
    }
}

@Composable
private fun InviteConfirmCard(invite: Pair<String, String>, savedUrl: String?, demo: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val review = remember(invite, savedUrl, demo) { InviteLink.review(invite.first, savedUrl, demo) }
    Column(
        Modifier.widthIn(max = 420.dp).shadow(16.dp, RoundedCornerShape(28.dp)).clip(RoundedCornerShape(28.dp)).background(Cute.colors.card)
            .verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EmojiBubble("💌", Cute.colors.partnerSoft, 60.dp)
        // 字放大時換成「要連到這個 / Supabase 嗎？」，不會只剩「嗎？」一行
        TidyText("要連到這個 Supabase 嗎？", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        TidyText("收到連線邀請，確認是你們的再繼續喔", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if (review.official) Cute.colors.meSoft else Cute.colors.periodSoft).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(if (review.official) "☁️" else "❓", style = MaterialTheme.typography.titleLarge)
            Column(Modifier.weight(1f)) {
                // 網址太長時只在「.」後面換行（「qrstuvwxyz. / supabase.co」），不會切在「supabas / e」中間
                Text(review.host.replace(".", ".\u200B"), style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Text(if (review.official) "Supabase 官方網址" else "不是 Supabase 官方網址", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        review.warnings.forEach { Note(it, "⚠️", Cute.colors.butter.copy(alpha = 0.3f)) }
        if (review.warnings.isEmpty()) Note("只會先填好設定，「測試連線」成功後才會儲存", "🔒", MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f))
        if (review.official) {
            CuteButton(if (demo) "離開示範，幫我填好" else "幫我填好", Modifier.fillMaxWidth(), icon = Icons.Rounded.Link, onClick = onConfirm)
            SoftButton("不要，取消", Modifier.fillMaxWidth(), onClick = onDismiss)
        } else {
            // 不是官方網址：顯眼的大按鈕是「不要」
            CuteButton("不要，取消", Modifier.fillMaxWidth(), onClick = onDismiss)
            SoftButton(if (demo) "我確定，離開示範填好" else "我確定，幫我填好", Modifier.fillMaxWidth(), onClick = onConfirm)
        }
    }
}

@Composable
fun SupabaseSetupScreen(vm: AppViewModel, back: () -> Unit, next: (String) -> Unit) {
    val prefs by vm.prefs.collectAsState()
    val prefill by vm.container.prefill.collectAsState()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val savedUrl = prefs?.supabaseUrl.orEmpty()
    val savedKey = prefs?.supabaseKey.orEmpty()
    // 邀請連結確認後只是先填好，不會自動測試、也還沒存
    var url by remember(prefill, savedUrl) { mutableStateOf(prefill?.first ?: savedUrl) }
    var key by remember(prefill, savedKey) { mutableStateOf(prefill?.second ?: savedKey) }
    val tested = prefill == null && savedUrl.isNotEmpty() && Supabase.normalizeUrl(url) == savedUrl && Supabase.normalizeKey(key) == savedKey
    val normalized = Supabase.normalizeUrl(url)
    val dashboard = Supabase.dashboardRef(url) != null
    val urlIssue = if (url.length > 10 && !dashboard) Supabase.urlProblem(normalized) else null
    val keyIssue = if (key.length >= 10) Supabase.keyProblem(Supabase.normalizeKey(key)) else null
    val unofficial = url.length > 10 && urlIssue == null && !Supabase.isOfficialHost(Supabase.hostOf(normalized))
    val test = { vm.account(onDone = { vm.toast("連上你們的 Supabase 了 🎉") }) { vm.container.testConnection(url, key) } }
    fun paste(into: (String) -> Unit) {
        val text = clipboardText(context)
        val inv = InviteLink.parse(text)
        when {
            inv != null -> { url = inv.first; key = inv.second; vm.toast("貼上了另一半的邀請 💌") }
            text.isBlank() -> vm.toast("剪貼簿是空的，先到 Supabase 後台複製喔")
            else -> into(text.trim())
        }
    }
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenHeader("連接 Supabase", onBack = back) }
        if (prefill != null) item {
            Note("已經幫你填好另一半傳來的設定（☁️ ${Supabase.hostOf(prefill!!.first)}），按「測試連線」成功後才會存起來，接著就能註冊 💕", "💌", Cute.colors.partnerSoft)
        }
        item {
            CuteCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    EmojiBubble("☁️", Cute.colors.meSoft, 44.dp)
                    Column(Modifier.weight(1f)) {
                        TidyText("1. 貼上你們的 Supabase", style = MaterialTheme.typography.titleMedium)
                        TidyText("在 Supabase 後台上方的 Connect（或 Project Settings › Data API／API Keys）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                CuteField(url, { url = it.trim() }, "Supabase 網址", placeholder = "https://xxxx.supabase.co", keyboard = KeyboardType.Uri,
                    supporting = if (dashboard) "這是後台的網址，會自動換成 $normalized ✨" else urlIssue, isError = urlIssue != null,
                    trailing = { PasteChip { paste { url = it } } })
                // 標籤要短：浮到框線上時只有一行的位置，金鑰的種類寫在下面的說明
                CuteField(key, { key = Supabase.normalizeKey(it) }, "Supabase 金鑰", placeholder = "sb_publishable_…",
                    supporting = keyIssue ?: "Publishable key 或 anon key：公開用的金鑰，資料由資料庫的權限規則保護", isError = keyIssue != null,
                    trailing = { PasteChip { paste { key = Supabase.normalizeKey(it) } } })
                if (unofficial) Note("這不是 Supabase 官方的網址（xxxx.supabase.co）。除非你們自己架了 Supabase，不然先確認一下，登入時密碼會送到這裡喔", "⚠️", Cute.colors.butter.copy(alpha = 0.3f))
                CuteButton(if (tested) "連上了 ✅" else "測試連線", Modifier.fillMaxWidth(), icon = Icons.Rounded.Cloud,
                    enabled = url.length > 10 && key.length > 10 && urlIssue == null && keyIssue == null && !vm.busy) { test() }
                if (vm.busy) Box(Modifier.align(Alignment.CenterHorizontally)) { LoadingDots() }
            }
        }
        item {
            AnimatedVisibility(tested, enter = expandVertically() + fadeIn()) {
                CuteCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        EmojiBubble("🔑", Cute.colors.partnerSoft, 44.dp)
                        Text("2. 註冊或登入", style = MaterialTheme.typography.titleMedium)
                    }
                    CuteButton("第一次使用：註冊", Modifier.fillMaxWidth(), icon = Icons.Rounded.PersonAdd) { next("register") }
                    SoftButton("我已經有帳號：登入", Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Rounded.Login) { next("login") }
                }
            }
        }
        item {
            CuteCard(color = Cute.colors.partnerSoft, onClick = { runCatching { uriHandler.openUri(Supabase.GUIDE_URL) } }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("🐣", style = MaterialTheme.typography.titleLarge)
                    // 「看設定教學」放在說明下面（整張卡都能點），標題和說明用整個寬度，不會只剩「嗎？」一行
                    Column(Modifier.weight(1f)) {
                        TidyText("還沒有 Supabase 專案嗎？", style = MaterialTheme.typography.titleSmall)
                        TidyText("跟著設定教學一步一步來，免費、大約 10 分鐘 ☕ 另一半不用設定，等你傳邀請連結過去就好", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.AutoMirrored.Rounded.MenuBook, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Text("看設定教學", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
        item { Text("資料存在你們自己的 Supabase 專案，在哪裡都能連。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
    }
}

private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

/** 登入、註冊共用的外框 */
@Composable
private fun AuthScaffold(
    vm: AppViewModel, title: String, greeting: String, sub: String, back: () -> Unit,
    switchText: String, onSwitch: () -> Unit, content: @Composable ColumnScope.() -> Unit,
) {
    val prefs by vm.prefs.collectAsState()
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenHeader(title, onBack = back) }
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CoupleMascots(height = 110.dp)
                TidyText(greeting, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                TidyText(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        item { CuteCard(content = content) }
        item {
            Text(
                switchText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).bouncyClick(onClick = onSwitch).padding(12.dp),
            )
        }
        val host = prefs?.supabaseUrl?.substringAfter("://")?.substringBefore('/').orEmpty()
        if (host.isNotEmpty()) item {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Pill("☁️ $host", contentColor = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
fun LoginScreen(vm: AppViewModel, back: () -> Unit, toRegister: () -> Unit) {
    val prefs by vm.prefs.collectAsState()
    var email by remember { mutableStateOf(prefs?.lastEmail.orEmpty()) }
    var password by remember { mutableStateOf("") }
    AuthScaffold(vm, "登入", "歡迎回來 💕", "用註冊時的 Email 和密碼登入", back, "還沒有帳號？註冊一個", toRegister) {
        CuteField(email, { email = it.trim() }, "Email", placeholder = "you@example.com", keyboard = KeyboardType.Email)
        CuteField(password, { password = it }, "密碼", password = true, keyboard = KeyboardType.Password)
        CuteButton("登入", Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Rounded.Login, enabled = EMAIL.matches(email) && password.length >= 6 && !vm.busy) {
            vm.account { vm.container.login(email, password) }
        }
        if (vm.busy) Box(Modifier.align(Alignment.CenterHorizontally)) { LoadingDots() }
    }
}

@Composable
fun RegisterScreen(vm: AppViewModel, back: () -> Unit, toLogin: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    AuthScaffold(vm, "註冊", "嗨，初次見面 👋", "一個小倆口只能註冊兩個人：你和另一半", back, "已經有帳號了？登入", toLogin) {
        CuteField(email, { email = it.trim() }, "Email", placeholder = "you@example.com", keyboard = KeyboardType.Email)
        CuteField(password, { password = it }, "密碼", password = true, keyboard = KeyboardType.Password, supporting = "至少 6 個字")
        CuteField(name, { name = it.take(30) }, "暱稱", placeholder = "對方會看到的名字")
        CuteButton("註冊", Modifier.fillMaxWidth(), icon = Icons.Rounded.Favorite, enabled = EMAIL.matches(email) && password.length >= 6 && name.isNotBlank() && !vm.busy) {
            vm.account { vm.container.register(email, password, name) }
        }
        if (vm.busy) Box(Modifier.align(Alignment.CenterHorizontally)) { LoadingDots() }
    }
}

/** [open]：打開配對前也能用的頁面（我的資料、備忘錄、星座運勢與御神籤）；null 就不顯示 */
@Composable
fun PairingScreen(s: AppState, vm: AppViewModel, open: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    // 去看備忘錄、我的資料再回來，配對碼和打到一半的字都還在
    var code by rememberSaveable { mutableStateOf<String?>(null) }
    var input by rememberSaveable { mutableStateOf("") }
    var since by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val scope = rememberCoroutineScope()
    val prefs by vm.prefs.collectAsState()
    val p = prefs?.takeIf { it.configured && !s.demo }
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                CoupleMascots(height = 120.dp, rightColor = MaterialTheme.colorScheme.outline)
                TidyText("嗨 ${s.me?.displayName ?: ""}，來配對另一半吧", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                TidyText("一個人產生配對碼，另一個人輸入", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        if (p != null) item {
            CuteCard(color = Cute.colors.meSoft) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    EmojiBubble("💌", Cute.colors.card, 44.dp)
                    Column(Modifier.weight(1f)) {
                        Text("另一半還沒裝好？", style = MaterialTheme.typography.titleSmall)
                        Text("先傳連線邀請過去，點一下就會自動填好你們的 Supabase", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                SoftButton("分享給另一半", Modifier.fillMaxWidth(), icon = Icons.Rounded.Share) {
                    shareText(context, InviteLink.shareText(p.supabaseUrl, p.supabaseKey), "傳送連線邀請")
                }
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
                            copyText(context, "配對碼", code!!)
                            vm.toast("已複製配對碼")
                        }
                        CuteButton("傳給對方", Modifier.weight(1f), icon = Icons.Rounded.Share) {
                            val link = p?.let { "\n\n還沒連上我們的小倆口的話，先點這個連結：\n${InviteLink.build(it.supabaseUrl, it.supabaseKey)}" }.orEmpty()
                            shareText(context, "打開小倆口，輸入配對碼 $code 跟我配對 💞$link", "傳送配對碼")
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
        if (open != null) item {
            val c = Cute.colors
            CuteCard {
                Text("配對前也可以先用", style = MaterialTheme.typography.titleMedium)
                MenuRow(Icons.Rounded.Badge, c.me, c.meSoft, "我的資料", "暱稱、生日、血型…配對後對方看得到") { open("profile") }
                MenuRow(Icons.Rounded.StickyNote2, c.peach, c.peach.copy(alpha = 0.25f), "備忘錄", "現在寫的只有你看得到，也可以放在桌面") { open("memos") }
                MenuRow(Icons.Rounded.AutoAwesome, c.lavender, c.fertileSoft, "星座運勢與御神籤", "今天的運勢、抽一支籤") { open("fortune") }
            }
        }
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
        EmojiBubble(emoji, if (ok) Cute.colors.card else MaterialTheme.colorScheme.primaryContainer, 44.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // 「開啟」「先開位置」跟標題同一行，說明用整個寬度：字放大時不會被擠成一行五個字
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TidyText(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                if (ok) Text("✅", style = MaterialTheme.typography.titleMedium)
                else Pill(if (enabled) "開啟" else "先開位置", if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TidyText(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

