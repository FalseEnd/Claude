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
import tw.xiaoliangkou.app.container
import tw.xiaoliangkou.app.core.PaymentParser
import tw.xiaoliangkou.app.data.AppException
import tw.xiaoliangkou.app.data.RemoteRepository
import tw.xiaoliangkou.app.data.SettingsStore
import java.security.MessageDigest

/**
 * 自動記帳：讀取 LINE Pay、街口、銀行等 App 的付款通知。
 * 需要使用者在系統設定開啟「通知存取權」；只處理含「付款／消費／交易」等字眼且有金額的通知。
 */
class PaymentListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()
        val parsed = PaymentParser.parsePaymentNotification(title, text) ?: return
        val c = applicationContext.container
        c.scope.launch {
            if (c.prefs.value?.autoBookkeeping == false) return@launch
            val repo = c.repo.value as? RemoteRepository ?: return@launch
            // 同一筆交易可能被通知兩次（例如銀行與錢包 App 各一則）：用金額、商家與 10 分鐘區間去重
            val raw = "${parsed.amount}|${parsed.merchant}|${sbn.postTime / 600_000}"
            val key = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
            runCatching { repo.addAutoExpense(parsed, key) }
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

/** 常駐通知、桌面小工具上的按鈕：「現在在哪？」請對方手機回報位置、「抱抱」直接送一個抱抱 */
class StatusActionReceiver : BroadcastReceiver() {
    companion object {
        const val LOCATE = "tw.xiaoliangkou.app.action.LOCATE"
        const val HUG = "tw.xiaoliangkou.app.action.HUG"

        fun intent(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            context, action.hashCode(),
            Intent(context, StatusActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != LOCATE && action != HUG) return
        val pending = goAsync()
        val c = context.applicationContext.container
        c.scope.launch {
            val msg = try {
                // App 可能才剛被這個按鈕叫起來：等設定和資料載入（最多幾秒）
                val repo = withTimeoutOrNull(6_000) {
                    c.prefs.first { it != null }
                    c.repo.value?.also { r -> r.state.first { it.loaded } }
                }
                val st = repo?.state?.value
                when {
                    repo == null || st == null -> "請先打開小倆口登入"
                    !st.paired -> "還沒配對喔"
                    action == LOCATE -> if (repo.locatePartner()) "已請 ${st.partnerName} 的手機回報位置 📍" else "${st.partnerName} 的手機目前不在線上"
                    else -> { repo.sendComfort("hug", null); "已經抱抱 ${st.partnerName} 🤗" }
                }
            } catch (e: AppException) {
                e.message ?: "沒有送出，請稍後再試"
            } catch (e: Exception) {
                "沒有送出，請確認網路連線"
            }
            Toast.makeText(context.applicationContext, msg, Toast.LENGTH_SHORT).show()
            pending.finish()
        }
    }
}
