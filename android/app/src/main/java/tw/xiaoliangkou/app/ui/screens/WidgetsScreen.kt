package tw.xiaoliangkou.app.ui.screens

import android.appwidget.AppWidgetProvider
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddToHomeScreen
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.components.CuteButton
import tw.xiaoliangkou.app.ui.components.CuteCard
import tw.xiaoliangkou.app.ui.components.Note
import tw.xiaoliangkou.app.ui.components.ScreenHeader
import tw.xiaoliangkou.app.widget.PartnerWidget
import tw.xiaoliangkou.app.widget.TogetherWidget
import tw.xiaoliangkou.app.widget.WidgetSnapshot
import tw.xiaoliangkou.app.widget.Widgets

/** 桌面小工具：用現在的資料預覽，一鍵加到桌面 */
@Composable
fun WidgetsScreen(s: AppState, vm: AppViewModel, back: () -> Unit) {
    val context = LocalContext.current
    val snap = remember(s) { WidgetSnapshot.of(s) }
    fun pin(provider: Class<out AppWidgetProvider>) {
        if (Widgets.pin(context, provider)) vm.toast("跟著畫面上的提示放到桌面就好了 ✨")
        else vm.toast("這個桌面不能直接加入，請長按桌面空白處 › 小工具 › 小倆口")
    }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenHeader("桌面小工具", onBack = back) }
        item {
            WidgetCard("在一起", "兩個人的頭像和在一起的天數，快到 100、520 這種里程碑時會提醒", 150.dp, 150.dp,
                remember(snap) { Widgets.together(context, snap, interactive = false) }) { pin(TogetherWidget::class.java) }
        }
        item {
            WidgetCard("${s.partnerName} 的狀態", "在哪、電量、心情、手機使用；右上角可以直接抱抱 ${s.partnerName}", 320.dp, 140.dp,
                remember(snap) { Widgets.partner(context, snap, interactive = false) }) { pin(PartnerWidget::class.java) }
        }
        item {
            Note("也可以長按桌面空白處 › 小工具 › 找到「小倆口」。App 收到新的狀態就會更新小工具，系統也會每 30 分鐘更新一次。只會顯示 ${s.partnerName} 有分享的項目。", "📱")
        }
    }
}

@Composable
private fun WidgetCard(title: String, desc: String, width: Dp, height: Dp, views: RemoteViews, onPin: () -> Unit) {
    CuteCard {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // 用真的小工具版面預覽，和桌面上看到的一樣
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { FrameLayout(it) },
                update = { frame ->
                    frame.removeAllViews()
                    runCatching { frame.addView(views.apply(frame.context, frame)) }
                },
                modifier = Modifier.width(width).height(height),
            )
        }
        CuteButton("加到桌面", Modifier.fillMaxWidth(), icon = Icons.Rounded.AddToHomeScreen, onClick = onPin)
    }
}
