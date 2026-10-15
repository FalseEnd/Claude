package tw.xiaoliangkou.app.service

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.launch
import tw.xiaoliangkou.app.container
import tw.xiaoliangkou.app.core.PaymentParser
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
