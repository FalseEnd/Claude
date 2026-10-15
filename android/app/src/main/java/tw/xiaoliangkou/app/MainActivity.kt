package tw.xiaoliangkou.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
        setContent {
            XlkTheme {
                XlkRoot(vm, deepLink) { deepLink = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(Notifier.EXTRA_ROUTE)?.let { deepLink = it }
    }
}
