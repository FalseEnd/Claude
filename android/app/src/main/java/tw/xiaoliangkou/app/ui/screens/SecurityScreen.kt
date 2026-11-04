package tw.xiaoliangkou.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import tw.xiaoliangkou.app.data.AppLock
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.BiometricAuth
import tw.xiaoliangkou.app.ui.PinDialog
import tw.xiaoliangkou.app.ui.components.ChoiceChip
import tw.xiaoliangkou.app.ui.components.CuteCard
import tw.xiaoliangkou.app.ui.components.Note
import tw.xiaoliangkou.app.ui.components.ScreenHeader
import tw.xiaoliangkou.app.ui.components.SoftButton
import tw.xiaoliangkou.app.ui.components.ToggleRow
import tw.xiaoliangkou.app.ui.components.findActivity

/** 打開的視窗：選解鎖方式、設定新的、關掉前確認、變更前確認 */
private enum class PinStep { CHOOSE, SET, VERIFY_OFF, VERIFY_CHANGE }

/** App 安全性：進入 App 要解鎖（數字密碼或圖形，可以再加指紋或臉部）、離開多久要再解鎖 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SecurityScreen(vm: AppViewModel, back: () -> Unit, lock: AppLock = vm.container.lock, biometrics: BiometricAuth = vm.container.biometrics) {
    val context = LocalContext.current
    // 設定改了就重畫（AppLock 存在 SharedPreferences，改的時候 version 會加一）
    val version by lock.version.collectAsState()
    val enabled = remember(version) { lock.enabled }
    val biometric = remember(version) { lock.biometric }
    val pinLength = remember(version) { lock.pinLength }
    val relockAfter = remember(version) { lock.relockAfterMs }
    var step by remember { mutableStateOf<PinStep?>(null) }
    var newType by remember { mutableStateOf(AppLock.Type.PIN) }
    val type = remember(version) { lock.type }
    val bioProblem = biometrics.problem(context)
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenHeader("App 安全性", onBack = back) }
        item {
            CuteCard {
                ToggleRow("進入 App 要解鎖", enabled, if (!enabled) "打開 App 時要輸入密碼或畫圖形，也可以用指紋或臉部" else if (type == AppLock.Type.PATTERN) "用圖形解鎖（連 $pinLength 個點）" else "密碼是 $pinLength 位數字", "🔒") { on ->
                    step = if (on) PinStep.CHOOSE else PinStep.VERIFY_OFF
                }
                if (enabled) {
                    ToggleRow("用指紋或臉部解鎖", biometric, bioProblem ?: "用手機設定好的指紋或臉部辨識，不行再用密碼或圖形", "👆", enabled = bioProblem == null) { on ->
                        if (!on) lock.setBiometric(false)
                        else context.findActivity()?.let { a ->
                            // 先試一次，確定這支手機用得了
                            biometrics.prompt(a, "確認用指紋或臉部解鎖", onSuccess = { lock.setBiometric(true); vm.toast("之後可以用指紋或臉部解鎖了") }, onCancel = {})
                        }
                    }
                    SoftButton("變更密碼或圖形", Modifier.fillMaxWidth()) { step = PinStep.VERIFY_CHANGE }
                }
            }
        }
        if (enabled) item {
            CuteCard {
                Text("離開 App 多久要再解鎖", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppLock.RELOCK_CHOICES.forEach { (ms, label) -> ChoiceChip(label, relockAfter == ms) { lock.setRelockAfter(ms) } }
                }
                Text(
                    "選照片、看權限視窗時也算離開 App；選「馬上」的話回來都要再解鎖一次",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Note(
                "指紋、臉部用的是手機系統設定好的；有些手機的臉部解鎖只能解鎖手機、不開放給 App，這時只會用指紋。" +
                    "密碼和圖形只存在這支手機（加密過，不會上傳）。忘記的話，在解鎖畫面按「忘記密碼？」登出再登入，App 鎖就會關掉，資料都還在。" +
                    "通知和桌面小工具不受 App 鎖影響；不想在通知列顯示對方的狀態，可以到「通知設定」關掉。打開 App 鎖後，最近使用的 App 畫面不會顯示內容（Android 13 以上）。",
                "🛡️",
            )
        }
    }
    when (step) {
        PinStep.CHOOSE -> AlertDialog(
            onDismissRequest = { step = null },
            title = { Text("用什麼解鎖？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SoftButton("數字密碼（4～8 位）", Modifier.fillMaxWidth()) { newType = AppLock.Type.PIN; step = PinStep.SET }
                    SoftButton("圖形（連 4 個點以上）", Modifier.fillMaxWidth()) { newType = AppLock.Type.PATTERN; step = PinStep.SET }
                    if (bioProblem == null) Text("設定好之後，也可以用指紋或臉部解鎖", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { step = null }) { Text("取消") } },
        )
        PinStep.SET -> PinDialog(lock, verify = false, onDismiss = { step = null }, newType = newType) { secret ->
            val first = !lock.enabled
            lock.setPin(secret!!, newType)
            if (first && bioProblem == null) lock.setBiometric(true)
            vm.toast(if (first) "App 鎖打開了" else if (newType == AppLock.Type.PATTERN) "圖形改好了" else "密碼改好了")
            step = null
        }
        PinStep.VERIFY_OFF -> PinDialog(lock, verify = true, onDismiss = { step = null }) {
            lock.disable()
            vm.toast("App 鎖關掉了")
            step = null
        }
        PinStep.VERIFY_CHANGE -> PinDialog(lock, verify = true, onDismiss = { step = null }) { step = PinStep.CHOOSE }
        null -> Unit
    }
}
