package tw.xiaoliangkou.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.widget.Toast
import androidx.core.app.RemoteInput
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
        /** 安撫附的悄悄話（早上天氣通知的「提醒對方」） */
        const val EXTRA_MESSAGE = "message"

        /** 早上天氣通知的「提醒對方」：送 [kind] 的安撫，悄悄話是要注意的事 */
        fun weatherReminder(context: Context, kind: String, message: String): PendingIntent = PendingIntent.getBroadcast(
            context, "weather".hashCode(),
            Intent(context, StatusActionReceiver::class.java).setAction(COMFORT).putExtra(EXTRA_KIND, kind).putExtra(EXTRA_MESSAGE, message),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

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

        /** 在安撫、心情的通知上回覆：[text] 是「💬 回覆」（打的字由 RemoteInput 放進來），不然是「🤗 回抱」 */
        const val REPLY = "tw.xiaoliangkou.app.action.REPLY"
        const val KEY_REPLY = "reply"
        const val EXTRA_NOTIFICATION = "notification"
        const val EXTRA_COMFORT = "comfort_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        /** 打字回覆送「我在 💬」的安撫，悄悄話就是打的字 */
        const val REPLY_KIND = Catalog.REPLY_KIND

        fun reply(context: Context, notification: Long, comfortId: Long?, title: String, text: Boolean): PendingIntent {
            val i = Intent(context, StatusActionReceiver::class.java).setAction(REPLY)
                .putExtra(EXTRA_NOTIFICATION, notification).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_TEXT, text)
            if (comfortId != null) i.putExtra(EXTRA_COMFORT, comfortId)
            // 要讓系統把打的字放進來，打字的那個要是 MUTABLE（只送到這個 App 自己的 receiver）
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (text && android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getBroadcast(context, "$REPLY/$notification/$text".hashCode(), i, flags)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == REPLY) return onReply(context, intent)
        if (action != LOCATE && action != HUG && action != COMFORT) return
        // 只送得出原本就有的種類（這個 App 自己的按鈕才會送來，還是檢查一下）
        val kind = if (action == COMFORT) intent.getStringExtra(EXTRA_KIND)?.takeIf { Catalog.comfort(it) != null } ?: return else "hug"
        val message = if (action == COMFORT) intent.getStringExtra(EXTRA_MESSAGE)?.trim()?.take(200)?.ifEmpty { null } else null
        val pending = goAsync()
        val c = context.applicationContext.container
        c.scope.launch {
            val msg = quickAction(c, if (action == LOCATE) null else kind, message)
            Toast.makeText(context.applicationContext, msg, Toast.LENGTH_SHORT).show()
            pending.finish()
        }
    }

    /** 在通知上回覆：送出去、把收到的那則安撫標成看過，再換掉通知 */
    private fun onReply(context: Context, intent: Intent) {
        val id = intent.getLongExtra(StatusActionReceiver.EXTRA_NOTIFICATION, -1)
        val title = intent.getStringExtra(StatusActionReceiver.EXTRA_TITLE).orEmpty()
        val comfortId = intent.getLongExtra(StatusActionReceiver.EXTRA_COMFORT, -1).takeIf { it >= 0 }
        val typing = intent.getBooleanExtra(StatusActionReceiver.EXTRA_TEXT, false)
        val typed = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(StatusActionReceiver.KEY_REPLY)?.toString()?.trim()?.take(Catalog.MESSAGE_MAX)
        val c = context.applicationContext.container
        // 打字回覆卻沒打字：當作抱抱
        val text = typed?.ifEmpty { null }.takeIf { typing }
        val pending = goAsync()
        c.scope.launch {
            val r = replyFromNotification(c, comfortId, text)
            val kind = if (comfortId != null) "comfort" else "mood"
            c.notifier.replied(id, title, r.second, r.first, kind, comfortId)
            pending.finish()
        }
    }
}

/**
 * 回覆：[text] 是打的字（送「我在 💬」的安撫），null 是回抱。先把 [comfortId] 標成看過（App 首頁那張卡片就收起來）。
 * 回傳（送出去了沒, 通知上要寫的字）
 */
internal suspend fun replyFromNotification(c: AppContainer, comfortId: Long?, text: String?): Pair<Boolean, String> = try {
    val repo = withTimeoutOrNull(6_000) {
        c.prefs.first { it != null }
        c.repo.value?.also { r -> r.state.first { it.loaded } }
    }
    val st = repo?.state?.value
    when {
        repo == null || st == null -> false to "請先打開小倆口登入"
        !st.paired -> false to "還沒配對喔"
        else -> {
            if (comfortId != null) runCatching { repo.readComfort(comfortId) }
            if (text != null) {
                repo.sendComfort(StatusActionReceiver.REPLY_KIND, text)
                true to "已回覆 ${st.partnerName}：$text 💬"
            } else {
                repo.sendComfort("hug", null)
                true to "已經抱抱 ${st.partnerName} 🤗"
            }
        }
    }
} catch (e: AppException) {
    false to "沒有送出：${e.message ?: "請稍後再試"}"
} catch (e: Exception) {
    false to "沒有送出，請確認網路連線後再試一次"
}

/**
 * 常駐通知的按鈕、「更多安撫」的小視窗：[kind] 是送哪一種安撫，null 是「現在在哪？」；[message] 是附的悄悄話。回傳要跳出來的提示。
 * App 可能才剛被叫起來：等設定和資料載入（最多幾秒）
 */
internal suspend fun quickAction(c: AppContainer, kind: String?, message: String? = null): String = try {
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
            repo.sendComfort(kind, message)
            val comfort = Catalog.comfort(kind)
            if (message != null) "已經提醒 ${st.partnerName} 了 💌"
            else if (kind == "hug") "已經抱抱 ${st.partnerName} 🤗" else "已送出${comfort?.label}給 ${st.partnerName} ${comfort?.emoji}"
        }
    }
} catch (e: AppException) {
    e.message ?: "沒有送出，請稍後再試"
} catch (e: Exception) {
    "沒有送出，請確認網路連線"
}
