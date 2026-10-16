package tw.xiaoliangkou.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.util.SizeF
import android.util.TypedValue
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
import kotlin.math.max
import kotlin.math.min

/** 桌面小工具「在一起」：兩個人的頭像、在一起的天數 */
class TogetherWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) =
        Widgets.draw(manager, ids) { Widgets.together(context, Widgets.load(context), size = it) }

    /** 被拉大、拉小：照新的大小重畫 */
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle?) =
        onUpdate(context, manager, intArrayOf(id))
}

/** 桌面小工具「對方狀態」：對方在哪、電量、心情，還有「抱抱」按鈕 */
class PartnerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) =
        Widgets.draw(manager, ids) { Widgets.partner(context, Widgets.load(context), size = it) }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle?) =
        onUpdate(context, manager, intArrayOf(id))
}

/**
 * 畫桌面小工具。App 的資料一變就由 [publish] 存一份、重畫；
 * 系統每 30 分鐘（或開機、更新 App 後）叫小工具更新時，用存著的那份重畫，天數也會跟著換日。
 *
 * 預設一格高。每個小工具照它在桌面上的實際大小（直向、橫向各一個）挑放得下的排法，
 * 寬度不夠就依序縮小、拿掉次要的東西，不會被切掉；拉到兩格高以上換成大版面。
 */
object Widgets {
    private const val FILE = "widget_snapshot.json"
    private const val ME = 0xFF6FAEFF.toInt()
    private const val PARTNER = 0xFFFF7FA8.toInt()
    private val json = Json { ignoreUnknownKeys = true }

    /** 預設大小（dp）：App 裡的預覽、拿不到小工具大小時用 */
    val TOGETHER_SIZE = SizeF(150f, 56f)
    val PARTNER_SIZE = SizeF(320f, 56f)

    /** 「對方狀態」高到這裡（大約兩格）、也夠寬，就換成大版面 */
    private val PARTNER_TALL = SizeF(180f, 130f)

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
        draw(manager, ids(TogetherWidget::class.java)) { together(context, snap, size = it) }
        draw(manager, ids(PartnerWidget::class.java)) { partner(context, snap, size = it) }
    }

    /** 每個小工具照自己的大小畫：Android 12 以上一份帶著每種大小的版面，以前的版本分直向、橫向 */
    internal fun draw(manager: AppWidgetManager, ids: IntArray, views: (SizeF?) -> RemoteViews) = ids.forEach { id ->
        runCatching {
            val sizes = sizesOf(manager, id)
            val rv = when {
                sizes.isEmpty() -> views(null)
                sizes.size == 1 -> views(sizes[0])
                Build.VERSION.SDK_INT >= 31 -> RemoteViews(sizes.take(16).associateWith { views(it) })
                else -> RemoteViews(views(sizes[1]), views(sizes[0]))
            }
            manager.updateAppWidget(id, rv)
        }
    }

    /** 小工具在桌面上的大小（dp）；以前的版本是［直向（最窄、最高）, 橫向（最寬、最矮）］ */
    private fun sizesOf(manager: AppWidgetManager, id: Int): List<SizeF> {
        val o = runCatching { manager.getAppWidgetOptions(id) }.getOrNull() ?: return emptyList()
        if (Build.VERSION.SDK_INT >= 31) {
            @Suppress("DEPRECATION")
            o.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)?.filter { it.width > 0 && it.height > 0 }?.distinct()
                ?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        val minW = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).toFloat()
        val maxW = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH).toFloat()
        val minH = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT).toFloat()
        val maxH = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).toFloat()
        if (minW <= 0 || minH <= 0) return emptyList()
        return listOf(SizeF(minW, max(minH, maxH)), SizeF(max(minW, maxW), minH)).distinct()
    }

    /** 請桌面加入小工具（大部分 Android 8 以上的桌面都支援）；不支援就回傳 false */
    fun pin(context: Context, provider: Class<out AppWidgetProvider>): Boolean {
        val manager = AppWidgetManager.getInstance(context) ?: return false
        return runCatching { manager.isRequestPinAppWidgetSupported && manager.requestPinAppWidget(ComponentName(context, provider), null, null) }
            .getOrDefault(false)
    }

    private fun px(context: Context, dp: Number) = (dp.toFloat() * context.resources.displayMetrics.density).toInt()

    private fun days(snap: WidgetSnapshot, today: LocalDate): Int? =
        snap.togetherSince?.let { runCatching { Dates.daysTogether(LocalDate.parse(it), today) }.getOrNull() }

    private fun openApp(context: Context, route: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(Notifier.EXTRA_ROUTE, route)
        }
        return PendingIntent.getActivity(context, 7_000 + route.length, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun RemoteViews.show(id: Int, visible: Boolean) = setViewVisibility(id, if (visible) View.VISIBLE else View.GONE)
    private fun RemoteViews.sp(id: Int, size: Float) = setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, size)

    // 直向、橫向各畫一次時共用同一張頭像，RemoteViews 裡只會存一份
    private val circles = LruCache<String, Bitmap>(6)

    private fun circle(context: Context, avatar: String?, name: String, color: Int, dp: Int): Bitmap {
        val size = px(context, dp)
        val key = "${avatar?.length}:${avatar.hashCode()}:$name:$color:$size"
        return circles.get(key) ?: Avatars.circle(context, avatar, name, color, size, px(context, 2).toFloat()).also { circles.put(key, it) }
    }

    /** 量字的寬、高（dp，跟著系統字體大小），用來算放不放得下 */
    private class Ruler(context: Context) {
        private val metrics = context.resources.displayMetrics
        private val huninn = runCatching { context.resources.getFont(R.font.huninn) }.getOrNull()
        private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)

        private fun set(sp: Float, font: Boolean) {
            paint.typeface = if (font) huninn else Typeface.DEFAULT
            paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics)
        }

        fun width(text: String, sp: Float, font: Boolean = true): Float {
            set(sp, font)
            return paint.measureText(text) / metrics.density
        }

        /** 一行字的高度，跟 TextView 一樣算（版面裡都是 includeFontPadding=false；有表情符號時行高會變大） */
        fun height(text: String, sp: Float): Float {
            set(sp, true)
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, 100_000).setIncludePad(false)
                .apply { if (Build.VERSION.SDK_INT >= 28) setUseLineSpacingFromFallbacks(true) }.build()
            return layout.height / metrics.density
        }
    }

    // ------------------------------------------------------------ 在一起

    /** 「在一起」的文字；[subShort] 是寬度不夠時的短版 */
    private class Together(val label: String, val days: String, val counting: Boolean, val sub: String, val subShort: String)

    private fun togetherText(snap: WidgetSnapshot, today: LocalDate): Together {
        val n = if (snap.kind == WidgetSnapshot.Kind.PAIRED) days(snap, today) else null
        val label = if (snap.kind == WidgetSnapshot.Kind.PAIRED) "在一起" + if (snap.demo) "（示範）" else "" else "小倆口"
        val number = n?.let { Fmt.int(it) } ?: "💞"
        return when (snap.kind) {
            WidgetSnapshot.Kind.SIGNED_OUT -> Together(label, number, false, "登入後顯示在一起的天數", "還沒登入")
            WidgetSnapshot.Kind.UNPAIRED -> Together(label, number, false, "配對後開始數在一起的天數", "還沒配對")
            WidgetSnapshot.Kind.PAIRED -> {
                // 一個月內有里程碑（第 100、520…天）就提醒，不然寫兩個人的名字
                val since = snap.togetherSince?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                val next = since?.let { Dates.upcomingMilestones(it, today, 1).firstOrNull() }?.takeIf { it.daysLeft in 1..30 }
                if (next != null) Together(label, number, n != null, "第 ${next.day} 天還有 ${next.daysLeft} 天 🎉", "${next.day} 倒數 ${next.daysLeft} 天")
                else Together(label, number, n != null, "${snap.myName} ♥ ${snap.partnerName}", "${snap.myName}♥${snap.partnerName}")
            }
        }
    }

    /**
     * 字和頭像的大小：[avatar] 頭像最大（dp）；[days] 天數、[small]「在一起」和「天」、[sub] 小字那行（sp，null：太矮不放）；
     * [padV] 上下留白（dp）；小工具至少要 [minHeight] 高才用。
     */
    private class Style(val minHeight: Int, val avatar: Int, val days: Float, val small: Float, val sub: Float?, val padV: Int)

    /** 一格高、橫排：越高字越大 */
    private val ROW_STYLES = listOf(
        Style(76, avatar = 46, days = 28f, small = 13f, sub = 12f, padV = 6),
        Style(50, avatar = 36, days = 22f, small = 12f, sub = 11f, padV = 5),
        Style(0, avatar = 32, days = 20f, small = 12f, sub = null, padV = 2),
        Style(0, avatar = 28, days = 16f, small = 11f, sub = null, padV = 1),
    )

    /** 窄但夠高時直排：拉到兩格高的大版面，和直向手機上比較高的 2×1 */
    private val BIG_STACK = Style(0, avatar = 46, days = 30f, small = 13f, sub = 12f, padV = 10)
    private val STACK = Style(0, avatar = 32, days = 24f, small = 12f, sub = 11f, padV = 5)

    /** 橫排寬度不夠時的退法：[shrink] 頭像比最大小多少 dp；[subFits] 小字要整行放得下，不然可以變「…」 */
    private data class Fit(
        val shrink: Int,
        val label: Boolean,
        val longSub: Boolean = false,
        val subFits: Boolean = true,
        val heart: Boolean = true,
        val me: Boolean = true,
    )

    /** 好看的排法，越前面越好：長的小字 → 縮頭像 → 拿掉「在一起」三個字 */
    private val GOOD_FITS = listOf(
        Fit(0, label = true, longSub = true),
        Fit(0, label = true),
        Fit(4, label = true),
        Fit(4, label = false),
        Fit(8, label = false),
        Fit(10, label = false),
    )

    /** 真的太窄時：小字變「…」 → 拿掉愛心 → 只留對方 */
    private val SMALL_FITS = listOf(
        Fit(10, label = false, subFits = false),
        Fit(12, label = false, subFits = false, heart = false),
        Fit(12, label = false, subFits = false, heart = false, me = false),
    )

    /** [interactive] 為 false 時不接按鈕（App 裡的預覽用）；[size] 是小工具的大小（dp），null 就當作 [TOGETHER_SIZE] */
    fun together(
        context: Context,
        snap: WidgetSnapshot,
        today: LocalDate = LocalDate.now(),
        interactive: Boolean = true,
        size: SizeF? = null,
    ): RemoteViews {
        val (w, h) = (size ?: TOGETHER_SIZE).let { it.width to it.height }
        val t = togetherText(snap, today)
        val r = Ruler(context)
        fun lineHeight(s: Style) = max(r.height(t.days, s.days), r.height(t.label, s.small))
        fun subHeight(s: Style) = s.sub?.let { 2 + max(r.height(t.sub, it), r.height(t.subShort, it)) } ?: 0f
        fun line(s: Style, label: Boolean) = (if (label) r.width(t.label, s.small) + 4 else 0f) + r.width(t.days, s.days) +
            (if (t.counting) 3 + r.width("天", s.small) else 0f)
        fun sub(s: Style, long: Boolean) = s.sub?.let { r.width(if (long) t.sub else t.subShort, it) } ?: 0f

        // 橫排：左右留白 8、頭像（愛心左右各疊 6dp）、間距 6、文字
        val rowHeart = r.width("💗", 12f, font = false) - 12
        fun rowAvatar(s: Style, f: Fit) = min(s.avatar - f.shrink, (h - 2 * s.padV).toInt()).coerceAtLeast(12)
        fun rowFits(s: Style, f: Fit): Boolean {
            val a = rowAvatar(s, f)
            val couple = if (f.me) 2 * a + (if (f.heart) rowHeart else 0f) else a.toFloat()
            return 16 + couple + 6 + max(line(s, f.label), if (f.subFits) sub(s, f.longSub) else 0f) + 1 <= w
        }
        // 直排：頭像、天數、小字三行置中
        val stackHeart = r.width("💗", 14f, font = false) - 12
        fun stackFits(s: Style) = h >= s.minHeight &&
            s.avatar + 4 + lineHeight(s) + subHeight(s) <= h - 2 * s.padV &&
            20 + max(2 * s.avatar + stackHeart, max(line(s, false), sub(s, false))) + 1 <= w

        // 先看高度能放哪幾種字的大小，再照寬度挑排法；null 的 Fit 是直排
        val rows = ROW_STYLES.filter { s -> h >= s.minHeight && lineHeight(s) + subHeight(s) <= h - 2 * s.padV }
            .ifEmpty { listOf(ROW_STYLES.last()) }
        fun good(s: Style) = GOOD_FITS.firstOrNull { rowFits(s, it) }?.let { s to it }
        val (style, fit) = (if (stackFits(BIG_STACK)) BIG_STACK to null else null)
            ?: rows.first().takeIf { it === ROW_STYLES.first() }?.let(::good)
            ?: (if (stackFits(STACK)) STACK to null else null)
            ?: rows.firstNotNullOfOrNull(::good)
            ?: rows.firstNotNullOfOrNull { s -> SMALL_FITS.firstOrNull { rowFits(s, it) }?.let { s to it } }
            ?: (rows.last() to SMALL_FITS.last())

        val v = RemoteViews(context.packageName, if (fit == null) R.layout.widget_together_tall else R.layout.widget_together)
        if (snap.kind == WidgetSnapshot.Kind.SIGNED_OUT) v.setImageViewResource(R.id.me_avatar, R.drawable.widget_avatar_me)
        else v.setImageViewBitmap(R.id.me_avatar, circle(context, snap.myAvatar, snap.myName, ME, 46))
        if (snap.kind == WidgetSnapshot.Kind.PAIRED) v.setImageViewBitmap(R.id.partner_avatar, circle(context, snap.partnerAvatar, snap.partnerName, PARTNER, 46))
        else v.setImageViewResource(R.id.partner_avatar, R.drawable.widget_avatar_partner)
        v.setTextViewText(R.id.together_label, t.label)
        v.setTextViewText(R.id.together_days, t.days)
        v.show(R.id.together_unit, t.counting)
        v.sp(R.id.together_label, style.small)
        v.sp(R.id.together_days, style.days)
        v.sp(R.id.together_unit, style.small)
        v.show(R.id.together_sub, style.sub != null)
        style.sub?.let { v.sp(R.id.together_sub, it) }
        if (interactive) v.setOnClickPendingIntent(R.id.widget_root, openApp(context, "home"))

        if (fit == null) {
            v.setViewPadding(R.id.widget_root, px(context, 10), px(context, style.padV), px(context, 10), px(context, style.padV))
            listOf(R.id.me_avatar, R.id.partner_avatar).forEach {
                v.setInt(it, "setMaxWidth", px(context, style.avatar))
                v.setInt(it, "setMaxHeight", px(context, style.avatar))
            }
            v.show(R.id.together_label, 20 + line(style, true) + 1 <= w)
            v.setTextViewText(R.id.together_sub, if (20 + sub(style, true) + 1 <= w) t.sub else t.subShort)
        } else {
            v.setViewPadding(R.id.widget_root, px(context, 8), px(context, style.padV), px(context, 8), px(context, style.padV))
            listOf(R.id.me_avatar, R.id.partner_avatar).forEach { v.setInt(it, "setMaxWidth", px(context, rowAvatar(style, fit))) }
            v.show(R.id.me_avatar, fit.me)
            v.show(R.id.heart, fit.me && fit.heart)
            v.show(R.id.together_label, fit.label)
            v.setTextViewText(R.id.together_sub, if (fit.longSub) t.sub else t.subShort)
        }
        return v
    }

    // ------------------------------------------------------------ 對方狀態

    /** 狀態那行的每一項：📍 在哪、🔋 電量、心情、其他；暫停分享、沒有分享時的說明放最前面 */
    internal fun statusParts(snap: WidgetSnapshot): List<String> {
        val items = buildList {
            snap.place?.let { add("📍 " + it.removePrefix("在 ").substringBefore(" · ")) }
            // 心情只留「😩 好累」，後面的話放不下
            snap.items.forEach { add(it.substringBefore("：")) }
        }
        val note = snap.note?.removePrefix(snap.partnerName)?.trim()
        return when {
            items.isEmpty() -> listOf(note ?: "還沒有狀態")
            note != null -> listOf(note.substringBefore("，")) + items
            else -> items
        }
    }

    /** 「14:05」或「10/15 14:05」 */
    private fun updatedAt(snap: WidgetSnapshot, now: Instant): String? = snap.updatedAtMs?.let { Instant.ofEpochMilli(it) }?.let { at ->
        val day = Fmt.localDate(at)
        if (day == Fmt.localDate(now)) Fmt.time(at) else "${Fmt.md(day)} ${Fmt.time(at)}"
    }

    /** 「對方狀態」一格高時的大小：[avatar] 頭像最大（dp）；[name] 名字、[small] 更新時間、[status] 狀態那行（sp）；[padV] 上下留白（dp） */
    private class PartnerStyle(val minHeight: Int, val avatar: Int, val name: Float, val small: Float, val status: Float, val padV: Int)

    private val PARTNER_STYLES = listOf(
        PartnerStyle(76, avatar = 52, name = 17f, small = 12f, status = 13f, padV = 6),
        PartnerStyle(50, avatar = 40, name = 15f, small = 11f, status = 12f, padV = 5),
        PartnerStyle(0, avatar = 36, name = 14f, small = 10f, status = 11f, padV = 1),
        PartnerStyle(0, avatar = 32, name = 12f, small = 9f, status = 10f, padV = 1),
    )

    /** [size] 是小工具的大小（dp），null 就當作 [PARTNER_SIZE] */
    fun partner(
        context: Context,
        snap: WidgetSnapshot,
        now: Instant = Instant.now(),
        interactive: Boolean = true,
        size: SizeF? = null,
    ): RemoteViews {
        val (w, h) = (size ?: PARTNER_SIZE).let { it.width to it.height }
        val paired = snap.kind == WidgetSnapshot.Kind.PAIRED
        val tall = w >= PARTNER_TALL.width && h >= PARTNER_TALL.height
        val v = RemoteViews(context.packageName, if (tall) R.layout.widget_partner_tall else R.layout.widget_partner)
        if (paired) v.setImageViewBitmap(R.id.partner_avatar, circle(context, snap.partnerAvatar, snap.partnerName, PARTNER, 60))
        else v.setImageViewResource(R.id.partner_avatar, R.drawable.widget_avatar_partner)
        val name = if (paired) snap.partnerName + if (snap.demo) "（示範）" else "" else "小倆口"
        v.setTextViewText(R.id.partner_name, name)
        v.show(R.id.hug_button, paired)
        if (interactive) {
            v.setOnClickPendingIntent(R.id.widget_root, openApp(context, "home"))
            if (paired) v.setOnClickPendingIntent(R.id.hug_button, StatusActionReceiver.intent(context, StatusActionReceiver.HUG))
        }
        val waiting = if (snap.kind == WidgetSnapshot.Kind.SIGNED_OUT) listOf("登入並配對後，這裡會顯示對方的狀態", "登入並配對後顯示對方狀態")
        else listOf("配對後，這裡會顯示對方的狀態", "配對後顯示對方狀態")
        val at = if (paired) updatedAt(snap, now) else null
        if (tall) {
            tallPartner(v, snap, paired, waiting.first(), at, now)
            return v
        }

        // 一格高：照高度挑字的大小（🤗 按鈕 38dp 也要放得下）；字大到兩行都放不下就只留名字
        val r = Ruler(context)
        val sample = if (paired) statusParts(snap).joinToString(" · ") else waiting.first()
        fun fits(s: PartnerStyle) = h >= s.minHeight && max(38f, r.height(name, s.name) + 3 + r.height(sample, s.status)) <= h - 2 * s.padV
        val style = PARTNER_STYLES.firstOrNull(::fits) ?: PARTNER_STYLES.last()
        v.show(R.id.status, fits(style))
        val avatar = min(style.avatar, (h - 2 * style.padV).toInt())
        val column = w - 18 - avatar - 10 - (if (paired) 46 else 0) - 2
        // 名字旁邊的更新時間：放不下「更新」兩個字就只寫時間，再不行就不寫
        val nameW = min(r.width(name, style.name), 120f)
        val updated = at?.let { listOf("$it 更新", it) }?.firstOrNull { nameW + 6 + r.width(it, style.small) <= column }
        // 狀態放得下幾項就放幾項，不要切在一半；第一項就太長才讓它變「…」
        fun fitted(parts: List<String>): String {
            var line = parts.first()
            for (part in parts.drop(1)) line = "$line · $part".takeIf { r.width(it, style.status) <= column } ?: break
            return line
        }
        val status = if (paired) fitted(statusParts(snap)) else waiting.firstOrNull { r.width(it, style.status) <= column } ?: waiting.last()

        v.setViewPadding(R.id.widget_root, px(context, 10), px(context, style.padV), px(context, 8), px(context, style.padV))
        v.setInt(R.id.partner_avatar, "setMaxWidth", px(context, avatar))
        v.sp(R.id.partner_name, style.name)
        v.sp(R.id.updated, style.small)
        v.sp(R.id.status, style.status)
        v.show(R.id.updated, updated != null)
        updated?.let { v.setTextViewText(R.id.updated, it) }
        v.setTextViewText(R.id.status, status)
        return v
    }

    /** 兩格高以上：第一行在哪；接下來兩行其他項目兩個一行；沒有的就說明原因 */
    private fun tallPartner(v: RemoteViews, snap: WidgetSnapshot, paired: Boolean, waiting: String, at: String?, now: Instant) {
        if (!paired) {
            v.setTextViewText(R.id.place, waiting)
            listOf(R.id.line1, R.id.line2, R.id.updated).forEach { v.show(it, false) }
            return
        }
        val rows = buildList {
            add(snap.place?.let { "📍 $it" } ?: snap.note ?: "還沒有狀態")
            snap.items.chunked(2).take(2).forEach { add(it.joinToString("　")) }
            if (snap.place != null && snap.note != null && size < 3) add(snap.note)
        }
        listOf(R.id.place, R.id.line1, R.id.line2).forEachIndexed { i, id ->
            val text = rows.getOrNull(i)
            v.show(id, text != null)
            if (text != null) v.setTextViewText(id, text)
        }
        val together = days(snap, Fmt.localDate(now))?.let { "在一起第 ${Fmt.int(it)} 天" }
        v.setTextViewText(R.id.updated, listOfNotNull(at?.let { "$it 更新" }, together).joinToString(" · ").ifEmpty { " " })
        v.show(R.id.updated, true)
    }
}
