package tw.xiaoliangkou.app

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import tw.xiaoliangkou.app.data.InviteLink
import tw.xiaoliangkou.app.service.Notifier
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.XlkRoot
import tw.xiaoliangkou.app.ui.theme.XlkTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private var deepLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        deepLink = intent?.getStringExtra(Notifier.EXTRA_ROUTE)
        if (savedInstanceState == null) openInvite(intent)
        setContent {
            XlkTheme {
                XlkRoot(vm, deepLink) { deepLink = null }
            }
        }
    }

    /** 深色模式切換時畫面不會重建（見 AndroidManifest 的 configChanges）：狀態列、導覽列的圖示顏色和視窗底色要自己跟著換 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        enableEdgeToEdge()
        val night = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        window.setBackgroundDrawableResource(if (night) R.color.night else R.color.blush)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(Notifier.EXTRA_ROUTE)?.let { deepLink = it }
        openInvite(intent)
    }

    /** 另一半分享的 xiaoliangkou://connect?url=…&key=… 連線邀請 */
    private fun openInvite(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme.equals(InviteLink.SCHEME, ignoreCase = true)) vm.container.openInvite(uri.toString())
    }
}
