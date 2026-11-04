package tw.xiaoliangkou.app.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.service.NearbyRadar
import tw.xiaoliangkou.app.service.ShareService
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.Fmt
import tw.xiaoliangkou.app.ui.components.CuteCard
import tw.xiaoliangkou.app.ui.components.SoftButton
import tw.xiaoliangkou.app.ui.components.TidyText
import tw.xiaoliangkou.app.ui.components.ToggleRow

/** 藍牙偵測「就在身邊」：開關、權限、藍牙有沒有打開、現在偵測到了沒 */
@Composable
internal fun NearbyCard(s: AppState, vm: AppViewModel) {
    val context = LocalContext.current
    val prefs by vm.prefs.collectAsState()
    val on = prefs?.nearbyBluetooth != false
    val scope = rememberCoroutineScope()
    // 從設定頁（藍牙、權限）回來時重新看一次
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        tick++
        if (!s.demo) ShareService.start(context)
    }
    @Suppress("UNUSED_VARIABLE") val t = tick
    val problem = if (s.demo) null else NearbyRadar.problem(context)
    CuteCard {
        ToggleRow("藍牙偵測在身邊", on, "兩支手機靠近（大約 10 公尺內）時寫「就在你身邊」，標點的抵達、離開也不再跳通知", "💞") { v ->
            scope.launch { vm.container.setNearbyBluetooth(v) }
            if (v && !s.demo && !NearbyRadar.hasPermission(context)) ask.launch(NearbyRadar.permissions)
        }
        if (!on) return@CuteCard
        val status = when {
            s.partnerNearby -> "💞 ${s.partnerName} 就在你身邊（${Fmt.time(s.partnerNearSince!!)} 起）"
            s.demo -> "示範模式：到「我的 › 示範控制台」讓 ${s.partnerName} 走到你身邊看看"
            problem != null -> problem
            else -> "偵測中… ${s.partnerName} 的手機也要更新到最新版、打開這個開關才偵測得到"
        }
        TidyText(status, style = MaterialTheme.typography.bodyMedium)
        if (problem != null) {
            if (!NearbyRadar.hasPermission(context)) SoftButton(if (android.os.Build.VERSION.SDK_INT >= 31) "允許「附近的裝置」" else "允許位置", small = true) {
                ask.launch(NearbyRadar.permissions)
            }
            else if (problem == "藍牙沒有打開") SoftButton("打開藍牙設定", small = true) {
                runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            }
        }
        TidyText(
            "手機只發出一串每 15 分鐘換一次的亂碼，只有 ${s.partnerName} 的手機認得出來，路人收到也不知道是誰；暫停分享時不發出。不用網路和 GPS，結果也不會上傳。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
