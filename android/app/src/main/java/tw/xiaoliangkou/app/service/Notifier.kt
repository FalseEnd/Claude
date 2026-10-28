package tw.xiaoliangkou.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.serialization.json.JsonObject
import tw.xiaoliangkou.app.MainActivity
import tw.xiaoliangkou.app.R
import tw.xiaoliangkou.app.core.Weather
import tw.xiaoliangkou.app.data.Avatars
import tw.xiaoliangkou.app.data.RemoteRepository
import tw.xiaoliangkou.app.ui.QuickComfortActivity

/** 系統通知：依類型分到不同頻道，使用者可以在系統設定分別調整 */
class Notifier(private val context: Context) {
    companion object {
        const val CH_LOVE = "love"
        const val CH_PLACE = "place"
        const val CH_REMIND = "remind"
        const val CH_SERVICE = "service"
        const val CH_STATUS = "partner_status"
        const val CH_SHARE = "share"
        const val CH_WEATHER = "weather"
        const val CH_PET = "pet"
        const val SERVICE_ID = 1
        /** 早上的天氣通知（每天換掉前一天的） */
        const val MORNING_ID = 2
        const val EXTRA_ROUTE = "route"
        /** 常駐通知「更多安撫」的 PendingIntent（別的通知用 outbox 的 id，不會撞到）*/
        private const val MORE_COMFORTS = Int.MAX_VALUE - 1
        private const val PINK = 0xFFFF7AA2.toInt()
        private const val PARTNER = 0xFFFF7FA8.toInt()
        /** 附了貼圖時，內容在幾個字以內才展開成大圖：大圖下面的摘要只有一兩行，長的悄悄話會被切掉 */
        const val PICTURE_BODY = 18
    }

    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(
            NotificationChannel(CH_LOVE, "抱抱、心情與悄悄話", NotificationManager.IMPORTANCE_HIGH).apply { description = "對方安撫你、更新心情、猜了你的花費" },
            NotificationChannel(CH_PLACE, "抵達與離開", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "對方進出你們設定的標點、電量低" },
            NotificationChannel(CH_REMIND, "約會與紀念日", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "約會邀請、紀念日與經期關懷提醒" },
            NotificationChannel(CH_SHARE, "日曆紀錄與備忘錄", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "對方記錄了那天的生活、新增共同備忘錄" },
            NotificationChannel(CH_SERVICE, "背景連線", NotificationManager.IMPORTANCE_MIN).apply { description = "保持即時連線、分享位置時常駐"; setShowBadge(false) },
            NotificationChannel(CH_STATUS, "對方狀態", NotificationManager.IMPORTANCE_LOW).apply { description = "常駐在通知列，顯示對方的電量、位置、心情"; setShowBadge(false) },
            NotificationChannel(CH_WEATHER, "每天早上的天氣", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "兩邊今天的天氣和要注意的事" },
            NotificationChannel(CH_PET, "一起養的小貓", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "對方餵了小貓、小貓長大了、晚上還沒人餵的提醒" },
        ))
    }

    private fun channelFor(kind: String) = when (kind) {
        "comfort", "mood", "guess" -> CH_LOVE
        "place", "battery_low", "sharing_changed", "pairing" -> CH_PLACE
        "diary", "memo" -> CH_SHARE
        "pet" -> CH_PET
        else -> CH_REMIND
    }

    private fun quickComforts(): PendingIntent = PendingIntent.getActivity(
        context, MORE_COMFORTS,
        Intent(context, QuickComfortActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openApp(route: String?, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (route != null) putExtra(EXTRA_ROUTE, route)
        }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * 通知右邊的粉紅愛心（沒有貼圖、頭像的通知用）。狀態列的小圖示一直都是愛心 [R.drawable.ic_stat_heart]，
     * 但有些手機（例如 OPPO 的 ColorOS）通知裡顯示的是 App 的圖示，放一顆大的愛心才看得到
     */
    private val heart: Bitmap? by lazy {
        val px = (64 * context.resources.displayMetrics.density).toInt()
        runCatching { ContextCompat.getDrawable(context, R.drawable.notif_heart_large)?.toBitmap(px, px) }.getOrNull()
    }

    /** Android 13 起要使用者允許才能發通知 */
    fun canPost() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /**
     * 這一種通知使用者看得到：可以發通知、App 的通知沒有整個關掉、這個頻道也沒有被封鎖（還沒建立的頻道當作看得到）。
     * 背景抓貼圖之前先問：看不到的通知不用向 GIPHY 抓圖
     */
    fun canShow(kind: String): Boolean {
        if (!canPost()) return false
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        return nm.areNotificationsEnabled() && nm.getNotificationChannel(channelFor(kind))?.importance != NotificationManager.IMPORTANCE_NONE
    }

    /**
     * 資料庫來的通知（[id] 是 outbox 的 id）。
     * [picture] 是附的 GIPHY 貼圖（不會動的那張）：放在右邊，內容很短時展開是大圖；同一則補上圖片時不會再響一次
     */
    fun show(id: Long, title: String, body: String, kind: String, data: JsonObject? = null, picture: Bitmap? = null) {
        if (!canPost()) return
        val b = NotificationCompat.Builder(context, channelFor(kind))
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setColor(PINK)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(if (channelFor(kind) == CH_LOVE) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openApp(RemoteRepository.routeFor(kind, data), id.toInt()))
        when {
            picture == null -> b.setStyle(NotificationCompat.BigTextStyle().bigText(body))
            // 短短一句（「附上一張貼圖 ✨」）：展開時是大圖，字放在圖下面；大圖展開了，右邊就不用再放一次
            body.codePointCount(0, body.length) <= PICTURE_BODY ->
                b.setStyle(NotificationCompat.BigPictureStyle().bigPicture(picture).setSummaryText(body).bigLargeIcon(null as Bitmap?))
            // 長的悄悄話、心情的話：大圖下面只放得下一兩行，所以照樣展開成全文，貼圖只放在右邊
            else -> b.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }
        if (picture != null) b.setLargeIcon(picture).setOnlyAlertOnce(true) else heart?.let(b::setLargeIcon)
        runCatching { NotificationManagerCompat.from(context).notify(androidId(id), b.build()) }
    }

    /** 早上的天氣：第一行是自己這邊，展開看得到兩邊；[remind] 是「提醒對方」的按鈕，點通知打開天氣頁 */
    fun showMorning(m: Weather.Morning, remind: PendingIntent?, partnerName: String) {
        if (!canPost()) return
        val b = NotificationCompat.Builder(context, CH_WEATHER)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setColor(PINK)
            .setContentTitle(m.title)
            .setContentText(m.lines.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(m.lines.joinToString("\n")))
            .setAutoCancel(true)
            .setContentIntent(openApp("weather", MORNING_ID))
        heart?.let(b::setLargeIcon)
        if (remind != null) b.addAction(0, "💌 提醒 $partnerName", remind)
        runCatching { NotificationManagerCompat.from(context).notify(MORNING_ID, b.build()) }
    }

    /** 這則還在通知列上（使用者還沒滑掉、還沒點開） */
    fun isShowing(id: Long): Boolean = runCatching {
        context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == androidId(id) }
    }.getOrDefault(false)

    private fun androidId(id: Long) = id.toInt() + 100

    fun serviceNotification(text: String): Notification =
        NotificationCompat.Builder(context, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setColor(PINK)
            .setContentTitle("小倆口")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp(null, 0))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    /** 常駐通知：對方的狀態（頭像、位置、電量、心情…），或只說明背景連線 */
    fun serviceNotification(n: ServiceNotice): Notification {
        if (!n.status) return serviceNotification(n.text)
        val b = NotificationCompat.Builder(context, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setColor(PINK)
            .setContentTitle(n.title)
            .setContentText(n.text)
            .setSubText(n.sub)
            .setLargeIcon(largeIcon(n.avatar, n.avatarName))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp("home", 0))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        (n.big ?: n.text).takeIf { it.isNotEmpty() }?.let { b.setStyle(NotificationCompat.BigTextStyle().bigText(it)) }
        if (n.whenMs != null) b.setWhen(n.whenMs).setShowWhen(true) else b.setShowWhen(false)
        if (n.locate) b.addAction(0, "📍 現在在哪？", StatusActionReceiver.intent(context, StatusActionReceiver.LOCATE))
        if (n.hug) {
            // 通知最多三個按鈕：抱抱一定有，沒有「現在在哪？」時多一個親親；
            // 「更多安撫」打開浮在目前畫面上的小視窗，「現在在哪？」、抱抱和其他安撫全部都選得到
            b.addAction(0, "🤗 抱抱", StatusActionReceiver.intent(context, StatusActionReceiver.HUG))
            if (!n.locate) b.addAction(0, "😘 親親", StatusActionReceiver.comfort(context, "kiss"))
            b.addAction(0, "💞 更多安撫", quickComforts())
        }
        return b.build()
    }

    fun updateService(n: ServiceNotice) {
        runCatching { NotificationManagerCompat.from(context).notify(SERVICE_ID, serviceNotification(n)) }
    }

    private var iconKey: Pair<String?, String>? = null
    private var icon: Bitmap? = null

    /** 通知左邊的大頭像（同一張就不重畫） */
    private fun largeIcon(avatar: String?, name: String): Bitmap {
        val key = avatar to name
        icon?.takeIf { iconKey == key }?.let { return it }
        val px = (64 * context.resources.displayMetrics.density).toInt()
        return Avatars.circle(context, avatar, name, PARTNER, px, 0f).also { icon = it; iconKey = key }
    }
}
