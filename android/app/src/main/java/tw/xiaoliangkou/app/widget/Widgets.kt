package tw.xiaoliangkou.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import kotlinx.serialization.json.Json
import tw.xiaoliangkou.app.MainActivity
import tw.xiaoliangkou.app.R
import tw.xiaoliangkou.app.core.Dates
import tw.xiaoliangkou.app.data.Avatars
import tw.xiaoliangkou.app.service.Notifier
import tw.xiaoliangkou.app.service.StatusActionReceiver
import tw.xiaoliangkou.app.ui.Fmt
import java.io.File
import java.time.Instant
import java.time.LocalDate

/** 桌面小工具「在一起」：兩個人的頭像、在一起的天數 */
class TogetherWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        runCatching { manager.updateAppWidget(ids, Widgets.together(context, Widgets.load(context))) }
    }
}

/** 桌面小工具「對方狀態」：對方在哪、電量、心情、手機使用，還有「抱抱」按鈕 */
class PartnerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        runCatching { manager.updateAppWidget(ids, Widgets.partner(context, Widgets.load(context))) }
    }
}

/**
 * 畫桌面小工具。App 的資料一變就由 [publish] 存一份、重畫；
 * 系統每 30 分鐘（或開機、更新 App 後）叫小工具更新時，用存著的那份重畫，天數也會跟著換日。
 */
object Widgets {
    private const val FILE = "widget_snapshot.json"
    private const val ME = 0xFF6FAEFF.toInt()
    private const val PARTNER = 0xFFFF7FA8.toInt()
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var current: WidgetSnapshot? = null

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun load(context: Context): WidgetSnapshot = current ?: synchronized(this) {
        current ?: (runCatching { json.decodeFromString<WidgetSnapshot>(file(context).readText()) }.getOrNull() ?: WidgetSnapshot())
            .also { current = it }
    }

    /** App 裡的資料變了：存起來，重畫桌面上的小工具 */
    fun publish(context: Context, snap: WidgetSnapshot) {
        if (snap == load(context)) return
        current = snap
        runCatching { file(context).writeText(json.encodeToString(WidgetSnapshot.serializer(), snap)) }
        updateAll(context, snap)
    }

    fun updateAll(context: Context, snap: WidgetSnapshot = load(context)) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        fun ids(cls: Class<*>) = runCatching { manager.getAppWidgetIds(ComponentName(context, cls)) }.getOrNull() ?: IntArray(0)
        ids(TogetherWidget::class.java).takeIf { it.isNotEmpty() }?.let { runCatching { manager.updateAppWidget(it, together(context, snap)) } }
        ids(PartnerWidget::class.java).takeIf { it.isNotEmpty() }?.let { runCatching { manager.updateAppWidget(it, partner(context, snap)) } }
    }

    /** 請桌面加入小工具（大部分 Android 8 以上的桌面都支援）；不支援就回傳 false */
    fun pin(context: Context, provider: Class<out AppWidgetProvider>): Boolean {
        val manager = AppWidgetManager.getInstance(context) ?: return false
        return runCatching { manager.isRequestPinAppWidgetSupported && manager.requestPinAppWidget(ComponentName(context, provider), null, null) }
            .getOrDefault(false)
    }

    private fun px(context: Context, dp: Int) = (dp * context.resources.displayMetrics.density).toInt()

    private fun days(snap: WidgetSnapshot, today: LocalDate): Int? =
        snap.togetherSince?.let { runCatching { Dates.daysTogether(LocalDate.parse(it), today) }.getOrNull() }

    private fun openApp(context: Context, route: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(Notifier.EXTRA_ROUTE, route)
        }
        return PendingIntent.getActivity(context, 7_000 + route.length, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** [interactive] 為 false 時不接按鈕（App 裡的預覽用，免得一點就真的送出抱抱） */
    fun together(context: Context, snap: WidgetSnapshot, today: LocalDate = LocalDate.now(), interactive: Boolean = true): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_together)
        val size = px(context, 50)
        val ring = px(context, 2).toFloat()
        val n = if (snap.kind == WidgetSnapshot.Kind.PAIRED) days(snap, today) else null
        if (snap.kind == WidgetSnapshot.Kind.SIGNED_OUT) {
            v.setImageViewResource(R.id.me_avatar, R.drawable.widget_avatar_me)
        } else {
            v.setImageViewBitmap(R.id.me_avatar, Avatars.circle(context, snap.myAvatar, snap.myName, ME, size, ring))
        }
        if (snap.kind == WidgetSnapshot.Kind.PAIRED) {
            v.setImageViewBitmap(R.id.partner_avatar, Avatars.circle(context, snap.partnerAvatar, snap.partnerName, PARTNER, size, ring))
        } else {
            v.setImageViewResource(R.id.partner_avatar, R.drawable.widget_avatar_partner)
        }
        v.setTextViewText(R.id.together_label, if (snap.kind == WidgetSnapshot.Kind.PAIRED) "在一起" + if (snap.demo) "（示範）" else "" else "小倆口")
        v.setTextViewText(R.id.together_days, n?.let { Fmt.int(it) } ?: "💞")
        v.setViewVisibility(R.id.together_unit, if (n != null) View.VISIBLE else View.GONE)
        val since = snap.togetherSince?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val sub = when (snap.kind) {
            WidgetSnapshot.Kind.SIGNED_OUT -> "登入後顯示在一起的天數"
            WidgetSnapshot.Kind.UNPAIRED -> "配對後開始數在一起的天數"
            WidgetSnapshot.Kind.PAIRED -> {
                // 一個月內有里程碑（第 100、520…天）就提醒，不然寫兩個人的名字
                val next = since?.let { Dates.upcomingMilestones(it, today, 1).firstOrNull() }?.takeIf { it.daysLeft in 1..30 }
                next?.let { "第 ${it.day} 天還有 ${it.daysLeft} 天 🎉" } ?: "${snap.myName} ♥ ${snap.partnerName}"
            }
        }
        v.setTextViewText(R.id.together_sub, sub)
        if (interactive) v.setOnClickPendingIntent(R.id.widget_root, openApp(context, "home"))
        return v
    }

    fun partner(context: Context, snap: WidgetSnapshot, now: Instant = Instant.now(), interactive: Boolean = true): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_partner)
        if (snap.kind != WidgetSnapshot.Kind.PAIRED) {
            v.setImageViewResource(R.id.partner_avatar, R.drawable.widget_avatar_partner)
            v.setTextViewText(R.id.partner_name, "小倆口")
            v.setTextViewText(
                R.id.place,
                if (snap.kind == WidgetSnapshot.Kind.SIGNED_OUT) "登入並配對後，這裡會顯示對方的狀態" else "配對後，這裡會顯示對方的狀態",
            )
            listOf(R.id.line1, R.id.line2, R.id.updated, R.id.hug_button).forEach { v.setViewVisibility(it, View.GONE) }
            if (interactive) v.setOnClickPendingIntent(R.id.widget_root, openApp(context, "home"))
            return v
        }
        v.setImageViewBitmap(R.id.partner_avatar, Avatars.circle(context, snap.partnerAvatar, snap.partnerName, PARTNER, px(context, 60), px(context, 2).toFloat()))
        v.setTextViewText(R.id.partner_name, snap.partnerName + if (snap.demo) "（示範）" else "")
        // 第一行：在哪；接下來兩行：其他項目兩個一行；沒有的就說明原因
        val rows = buildList {
            add(snap.place?.let { "📍 $it" } ?: snap.note ?: "還沒有狀態")
            snap.items.chunked(2).take(2).forEach { add(it.joinToString("　")) }
            if (snap.place != null && snap.note != null && size < 3) add(snap.note)
        }
        listOf(R.id.place, R.id.line1, R.id.line2).forEachIndexed { i, id ->
            val text = rows.getOrNull(i)
            v.setViewVisibility(id, if (text != null) View.VISIBLE else View.GONE)
            if (text != null) v.setTextViewText(id, text)
        }
        val today = LocalDate.now()
        val updated = snap.updatedAtMs?.let { Instant.ofEpochMilli(it) }?.let { at ->
            val day = Fmt.localDate(at)
            (if (day == Fmt.localDate(now)) Fmt.time(at) else "${Fmt.md(day)} ${Fmt.time(at)}") + " 更新"
        }
        v.setTextViewText(R.id.updated, listOfNotNull(updated, days(snap, today)?.let { "在一起第 ${Fmt.int(it)} 天" }).joinToString(" · ").ifEmpty { " " })
        v.setViewVisibility(R.id.updated, View.VISIBLE)
        v.setViewVisibility(R.id.hug_button, View.VISIBLE)
        if (interactive) {
            v.setOnClickPendingIntent(R.id.widget_root, openApp(context, "home"))
            v.setOnClickPendingIntent(R.id.hug_button, StatusActionReceiver.intent(context, StatusActionReceiver.HUG))
        }
        return v
    }
}
