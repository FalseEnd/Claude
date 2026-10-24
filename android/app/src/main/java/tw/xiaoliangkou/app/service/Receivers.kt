package tw.xiaoliangkou.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.widget.Toast
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import tw.xiaoliangkou.app.AppContainer
import tw.xiaoliangkou.app.container
import tw.xiaoliangkou.app.core.Catalog
import tw.xiaoliangkou.app.data.AppException
import tw.xiaoliangkou.app.data.SettingsStore

/**
 * 自動記帳：讀取 LINE Pay（LINE錢包）、街口、銀行等 App 的付款通知。
 * 需要使用者在系統設定開啟「通知存取權」；怎麼判斷、怎麼去重見 [AutoBookkeeping] 和 [tw.xiaoliangkou.app.core.PaymentParser]。
 */
class PaymentListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val c = applicationContext.container
        c.scope.launch {
            // App 可能才剛被叫起來：先等設定載入，才知道有沒有登入、有沒有開自動記帳
            withTimeoutOrNull(10_000) { c.prefs.first { it != null } }
            runCatching { c.autoBookkeeping.handle(sbn.packageName, sbn.notification, sbn.postTime) }
        }
    }
}

/** 開機後自動恢復背景連線 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val c = context.container
        c.scope.launch {
            if (c.settings.load().mode == SettingsStore.Mode.REMOTE) ShareService.start(context)
        }
    }
}

/** 常駐通知、桌面小工具上的按鈕：「現在在哪？」請對方手機回報位置、「抱抱」「親親」直接送出去 */
class StatusActionReceiver : BroadcastReceiver() {
    companion object {
        const val LOCATE = "tw.xiaoliangkou.app.action.LOCATE"
        const val HUG = "tw.xiaoliangkou.app.action.HUG"
        /** 送一種安撫（[EXTRA_KIND]） */
        const val COMFORT = "tw.xiaoliangkou.app.action.COMFORT"
        const val EXTRA_KIND = "kind"

        fun comfort(context: Context, kind: String): PendingIntent = PendingIntent.getBroadcast(
            context, (COMFORT + kind).hashCode(),
            Intent(context, StatusActionReceiver::class.java).setAction(COMFORT).putExtra(EXTRA_KIND, kind),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        fun intent(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            context, action.hashCode(),
            Intent(context, StatusActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != LOCATE && action != HUG && action != COMFORT) return
        // 只送得出原本就有的種類（這個 App 自己的按鈕才會送來，還是檢查一下）
        val kind = if (action == COMFORT) intent.getStringExtra(EXTRA_KIND)?.takeIf { Catalog.comfort(it) != null } ?: return else "hug"
        val pending = goAsync()
        val c = context.applicationContext.container
        c.scope.launch {
            val msg = quickAction(c, if (action == LOCATE) null else kind)
            Toast.makeText(context.applicationContext, msg, Toast.LENGTH_SHORT).show()
            pending.finish()
        }
    }
}

/**
 * 常駐通知的按鈕、「更多安撫」的小視窗：[kind] 是送哪一種安撫，null 是「現在在哪？」。回傳要跳出來的提示。
 * App 可能才剛被叫起來：等設定和資料載入（最多幾秒）
 */
internal suspend fun quickAction(c: AppContainer, kind: String?): String = try {
    val repo = withTimeoutOrNull(6_000) {
        c.prefs.first { it != null }
        c.repo.value?.also { r -> r.state.first { it.loaded } }
    }
    val st = repo?.state?.value
    when {
        repo == null || st == null -> "請先打開小倆口登入"
        !st.paired -> "還沒配對喔"
        kind == null -> if (repo.locatePartner()) "已請 ${st.partnerName} 的手機回報位置 📍" else "${st.partnerName} 的手機目前不在線上"
        else -> {
            repo.sendComfort(kind, null)
            val comfort = Catalog.comfort(kind)
            if (kind == "hug") "已經抱抱 ${st.partnerName} 🤗" else "已送出${comfort?.label}給 ${st.partnerName} ${comfort?.emoji}"
        }
    }
} catch (e: AppException) {
    e.message ?: "沒有送出，請稍後再試"
} catch (e: Exception) {
    "沒有送出，請確認網路連線"
}
