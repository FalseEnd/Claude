package tw.xiaoliangkou.app.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import tw.xiaoliangkou.app.container
import tw.xiaoliangkou.app.core.Catalog
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.service.quickAction
import tw.xiaoliangkou.app.ui.components.SoftButton
import tw.xiaoliangkou.app.ui.components.TidyText
import tw.xiaoliangkou.app.ui.screens.GroupedChoices
import tw.xiaoliangkou.app.ui.screens.MoodBubble
import tw.xiaoliangkou.app.ui.theme.Cute
import tw.xiaoliangkou.app.ui.theme.XlkTheme

/**
 * 常駐通知的「💞 更多安撫」：浮在目前畫面上的小視窗，不用打開整個 App。
 * 原本的「現在在哪？」「抱抱」都在，其他安撫也全部可以選；點一下就送出、視窗關掉
 */
class QuickComfortActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = container
        setContent {
            val repo by c.repo.collectAsState()
            val s by remember(repo) { repo?.state ?: flowOf(AppState()) }.collectAsState(AppState())
            // 開了 App 鎖：先解鎖才看得到、送得出去
            XlkTheme { LockGate(c.lock, c.biometrics, onForgot = null, onBack = { finish() }) { QuickComfort(s, send = { kind -> send(kind) }, close = { finish() }) } }
        }
    }

    /** 送出去（[kind] 是 null 時是「現在在哪？」），跳出結果、關掉視窗 */
    private var sending = false
    private fun send(kind: String?) {
        if (sending) return
        sending = true
        val c = container
        c.scope.launch {
            val msg = quickAction(c, kind)
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}

@Composable
internal fun QuickComfort(s: AppState, send: (String?) -> Unit, close: () -> Unit) {
    var picked by remember { mutableStateOf<String?>(null) }
    // 點外面就關掉；視窗本身放在下面，手指比較好按
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = close),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.systemBarsPadding().padding(12.dp).fillMaxWidth().heightIn(max = 640.dp)
                .clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.background)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TidyText(if (s.paired) "給 ${s.partnerName} 💞" else "小倆口", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = close) { Icon(Icons.Rounded.Close, "關閉") }
            }
            if (!s.loaded || !s.paired) {
                Text(if (!s.loaded) "讀取中…" else "還沒配對喔", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            TidyText("點一下就送出去", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (s.partnerSharing?.locationLive == true) {
                SoftButton("📍 ${s.partnerName} 現在在哪？", Modifier.fillMaxWidth(), icon = Icons.Rounded.MyLocation) { send(null) }
            }
            GroupedChoices(Catalog.comfortGroups, { s.canPick(it.since) }) { c, modifier ->
                MoodBubble(c.emoji, c.label, picked == c.key, modifier) {
                    picked = c.key
                    send(c.key)
                }
            }
            Text("想附貼圖、寫悄悄話：打開小倆口的「安撫」", style = MaterialTheme.typography.labelSmall, color = Cute.colors.muted)
        }
    }
}
