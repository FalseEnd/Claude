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

    // 直向、橫向各畫一次時共用同一張頭像，RemoteViews 裡只會存一份
    private val circles = LruCache<String, Bitmap>(6)

    private fun circle(context: Context, avatar: String?, name: String, color: Int, dp: Int): Bitmap {
        val size = px(context, dp)
        val key = "${avatar?.length}:${avatar.hashCode()}:$name:$color:$size"
        return circles.get(key) ?: Avatars.circle(context, avatar, name, color, size, px(context, 2).toFloat()).also { circles.put(key, it) }
    }

    /**
     * 量字的寬、高（dp），用來算放不放得下。[scaled]：字跟著系統字體大小；
     * 系統字很大（Android 14 以上可以到 2 倍）怎樣都放不下時，才改用不跟著放大的大小（[unit]）。
     */
    private class Ruler(context: Context, val scaled: Boolean = true) {
        private val metrics = context.resources.displayMetrics
        private val huninn = runCatching { context.resources.getFont(R.font.huninn) }.getOrNull()
        private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        val unit = if (scaled) TypedValue.COMPLEX_UNIT_SP else TypedValue.COMPLEX_UNIT_DIP

        private fun set(sp: Float, font: Boolean) {
            paint.typeface = if (font) huninn else Typeface.DEFAULT
            paint.textSize = TypedValue.applyDimension(unit, sp, metrics)
        }

        fun width(text: String, sp: Float, font: Boolean = true): Float {
            set(sp, font)
            return paint.measureText(text) / metrics.density
        }

        /** 一行字的高度，跟 TextView 一樣算（[pad]：includeFontPadding；有表情符號時行高會變大） */
        fun height(text: String, sp: Float, pad: Boolean = false): Float {
            set(sp, true)
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, 100_000).setIncludePad(pad)
                .apply { if (Build.VERSION.SDK_INT >= 28) setUseLineSpacingFromFallbacks(true) }.build()
            return layout.height / metrics.density
        }
    }

    private fun RemoteViews.size(id: Int, size: Float, r: Ruler) = setTextViewTextSize(id, r.unit, size)

    // ------------------------------------------------------------ 在一起

    /**
     * 「在一起」的文字。小字那行由長到短，放得下哪個就用哪個：
     * [subs] 是有放「在一起」三個字時用的，[bare] 是沒放時用的（示範模式要在這裡寫「示範」）。
     */
    private class Together(val label: String, val days: String, val counting: Boolean, val subs: List<String>, val bare: List<String> = subs) {
        fun subs(label: Boolean) = if (label) subs else bare
    }

    private fun togetherText(snap: WidgetSnapshot, today: LocalDate): Together {
        val n = if (snap.kind == WidgetSnapshot.Kind.PAIRED) days(snap, today) else null
        val label = if (snap.kind == WidgetSnapshot.Kind.PAIRED) "在一起" + if (snap.demo) "（示範）" else "" else "小倆口"
        val number = n?.let { Fmt.int(it) } ?: "💞"
        return when (snap.kind) {
            WidgetSnapshot.Kind.SIGNED_OUT -> Together(label, number, false, listOf("登入後顯示在一起的天數", "還沒登入"))
            WidgetSnapshot.Kind.UNPAIRED -> Together(label, number, false, listOf("配對後開始數在一起的天數", "還沒配對"))
            WidgetSnapshot.Kind.PAIRED -> {
                // 一個月內有里程碑（第 100、520…天）就提醒，不然寫兩個人的名字
                val since = snap.togetherSince?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                val next = since?.let { Dates.upcomingMilestones(it, today, 1).firstOrNull() }?.takeIf { it.daysLeft in 1..30 }
                val subs = if (next != null) listOf("第 ${next.day} 天還有 ${next.daysLeft} 天 🎉", "${next.day} 倒數 ${next.daysLeft} 天")
                else listOf("${snap.myName} ♥ ${snap.partnerName}", "${snap.myName}♥${snap.partnerName}")
                // 放不下「在一起（示範）」時，示範模式要在小字寫「示範」
                Together(label, number, n != null, subs, if (snap.demo) subs.map { "示範 · $it" } + "示範模式" else subs)
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

    /** 兩個頭像中間的愛心（橫排 14dp、左右各疊在頭像上 5dp；直排 20dp、各疊 4dp）實際佔的寬度 */
    private const val ROW_HEART = 4
    private const val STACK_HEART = 12

    /** 橫排寬度不夠時的退法：[shrink] 頭像比最大小多少 dp；[longSub] 要放得下最長的小字；[subFits] 小字要整行放得下，不然可以變「…」 */
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
        Fit(12, label = false),
    )

    /** 真的太窄時：小字變「…」 → 拿掉愛心 → 只留對方 */
    private val SMALL_FITS = listOf(
        Fit(10, label = false, subFits = false),
        Fit(12, label = false, subFits = false, heart = false),
        Fit(12, label = false, subFits = false, heart = false, me = false),
    )

    /** 決定好的排法：[stacked] 直排；[avatar] 頭像最大（dp）；[label] 放不放「在一起」；[sub] 小字那行 */
    private class TogetherPlan(
        val r: Ruler,
        val style: Style,
        val stacked: Boolean,
        val avatar: Int,
        val label: Boolean,
        val sub: String,
        val me: Boolean = true,
        val heart: Boolean = true,
    )

    /** 照小工具的大小（dp）挑排法；[last] 為 false 時怎樣都放不下就回傳 null（換不跟著系統放大的字再試一次） */
    private fun togetherPlan(r: Ruler, t: Together, w: Float, h: Float, last: Boolean): TogetherPlan? {
        val allSubs = (t.subs + t.bare).distinct()
        fun lineHeight(s: Style) = max(r.height(t.days, s.days), r.height(t.label, s.small))
        fun subHeight(s: Style) = s.sub?.let { sp -> 2 + allSubs.maxOf { r.height(it, sp) } } ?: 0f
        fun line(s: Style, label: Boolean) = (if (label) r.width(t.label, s.small) + 4 else 0f) + r.width(t.days, s.days) +
            (if (t.counting) 3 + r.width("天", s.small) else 0f)
        fun sub(s: Style, text: String) = s.sub?.let { r.width(text, it) } ?: 0f

        // 橫排：左右留白 8、頭像和愛心、間距 6、文字
        fun rowAvatar(s: Style, f: Fit) = min(s.avatar - f.shrink, (h - 2 * s.padV).toInt()).coerceAtLeast(12)
        fun column(s: Style, f: Fit): Float {
            val a = rowAvatar(s, f)
            val couple = if (f.me) 2 * a + (if (f.heart) ROW_HEART else 0) else a
            return w - 16 - couple - 6 - 1
        }
        fun rowFits(s: Style, f: Fit): Boolean {
            val col = column(s, f)
            val subs = t.subs(f.label)
            return line(s, f.label) <= col && (!f.subFits || sub(s, if (f.longSub) subs.first() else subs.last()) <= col)
        }
        fun row(s: Style, f: Fit): TogetherPlan {
            val col = column(s, f)
            val subs = t.subs(f.label)
            return TogetherPlan(r, s, false, rowAvatar(s, f), f.label, subs.firstOrNull { sub(s, it) <= col } ?: subs.last(), f.me, f.heart)
        }

        // 直排：頭像、天數、小字三行置中
        fun stackFits(s: Style) = h >= s.minHeight &&
            s.avatar + 4 + lineHeight(s) + subHeight(s) <= h - 2 * s.padV &&
            20 + max(2 * s.avatar + STACK_HEART.toFloat(), max(line(s, false), sub(s, t.bare.last()))) + 1 <= w
        fun stack(s: Style): TogetherPlan {
            val label = 20 + line(s, true) + 1 <= w && 20 + sub(s, t.subs.last()) + 1 <= w
            val subs = t.subs(label)
            return TogetherPlan(r, s, true, s.avatar, label, subs.firstOrNull { 20 + sub(s, it) + 1 <= w } ?: subs.last())
        }

        // 先看高度能放哪幾種字的大小，再照寬度挑排法
        val rows = ROW_STYLES.filter { s -> h >= s.minHeight && lineHeight(s) + subHeight(s) <= h - 2 * s.padV }
        if (rows.isEmpty() && !last) return null
        fun good(s: Style) = GOOD_FITS.firstOrNull { rowFits(s, it) }?.let { row(s, it) }
        return (if (stackFits(BIG_STACK)) stack(BIG_STACK) else null)
            ?: rows.firstOrNull()?.takeIf { it === ROW_STYLES.first() }?.let(::good)
            ?: (if (stackFits(STACK)) stack(STACK) else null)
            ?: rows.firstNotNullOfOrNull(::good)
            ?: rows.firstNotNullOfOrNull { s -> SMALL_FITS.firstOrNull { rowFits(s, it) }?.let { row(s, it) } }
            ?: if (last) row(rows.lastOrNull() ?: ROW_STYLES.last(), SMALL_FITS.last()) else null
    }

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
        val plan = togetherPlan(Ruler(context), t, w, h, last = false) ?: togetherPlan(Ruler(context, scaled = false), t, w, h, last = true)!!
        val (r, style) = plan.r to plan.style

        val v = RemoteViews(context.packageName, if (plan.stacked) R.layout.widget_together_tall else R.layout.widget_together)
        if (snap.kind == WidgetSnapshot.Kind.SIGNED_OUT) v.setImageViewResource(R.id.me_avatar, R.drawable.widget_avatar_me)
        else v.setImageViewBitmap(R.id.me_avatar, circle(context, snap.myAvatar, snap.myName, ME, 46))
        if (snap.kind == WidgetSnapshot.Kind.PAIRED) v.setImageViewBitmap(R.id.partner_avatar, circle(context, snap.partnerAvatar, snap.partnerName, PARTNER, 46))
        else v.setImageViewResource(R.id.partner_avatar, R.drawable.widget_avatar_partner)
        v.setTextViewText(R.id.together_label, t.label)
        v.setTextViewText(R.id.together_days, t.days)
        v.show(R.id.together_label, plan.label)
        v.show(R.id.together_unit, t.counting)
        v.size(R.id.together_label, style.small, r)
        v.size(R.id.together_days, style.days, r)
        v.size(R.id.together_unit, style.small, r)
        v.show(R.id.together_sub, style.sub != null)
        style.sub?.let { v.size(R.id.together_sub, it, r) }
        v.setTextViewText(R.id.together_sub, plan.sub)
        if (interactive) v.setOnClickPendingIntent(R.id.widget_root, openApp(context, "home"))

        val padH = if (plan.stacked) 10 else 8
        v.setViewPadding(R.id.widget_root, px(context, padH), px(context, style.padV), px(context, padH), px(context, style.padV))
        listOf(R.id.me_avatar, R.id.partner_avatar).forEach {
            v.setInt(it, "setMaxWidth", px(context, plan.avatar))
            if (plan.stacked) v.setInt(it, "setMaxHeight", px(context, plan.avatar))
        }
        v.show(R.id.me_avatar, plan.me)
        v.show(R.id.heart, plan.me && plan.heart)
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

    /**
     * 「對方狀態」一格高時的大小：[avatar] 頭像最大（dp）；[name] 名字、[small] 更新時間、[status] 狀態那行（sp）；
     * [padV] 上下留白（dp）；[hug] 🤗 圓圈的大小（dp）。
     */
    private class PartnerStyle(val minHeight: Int, val avatar: Int, val name: Float, val small: Float, val status: Float, val padV: Int, val hug: Int = 38)

    private val PARTNER_STYLES = listOf(
        // 直向手機上一格大約 100dp 高
        PartnerStyle(96, avatar = 60, name = 18f, small = 12f, status = 13f, padV = 8, hug = 44),
        PartnerStyle(76, avatar = 52, name = 17f, small = 12f, status = 13f, padV = 6),
        PartnerStyle(50, avatar = 40, name = 15f, small = 11f, status = 12f, padV = 5),
        PartnerStyle(0, avatar = 36, name = 14f, small = 10f, status = 11f, padV = 1),
        PartnerStyle(0, avatar = 32, name = 12f, small = 9f, status = 10f, padV = 1),
    )

    /** 🤗 按鈕：可以按的範圍 48dp 寬，左邊空 3dp */
    private const val HUG_WIDTH = 51

    /** 夠寬、夠高（大約兩格），而且每行都放得下，才換成大版面 */
    private const val TALL_MIN_WIDTH = 180f
    private const val TALL_MIN_HEIGHT = 116f

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
        val name = if (paired) snap.partnerName + if (snap.demo) "（示範）" else "" else "小倆口"
        val waiting = if (snap.kind == WidgetSnapshot.Kind.SIGNED_OUT) listOf("登入並配對後，這裡會顯示對方的狀態", "登入並配對後顯示對方狀態")
        else listOf("配對後，這裡會顯示對方的狀態", "配對後顯示對方狀態")
        val at = if (paired) updatedAt(snap, now) else null
        // 系統字很大、大版面放不下時，先試試不跟著放大的字，還是放不下才用一格高的排法
        val tall = if (w < TALL_MIN_WIDTH || h < TALL_MIN_HEIGHT) null
        else listOf(Ruler(context), Ruler(context, scaled = false)).firstNotNullOfOrNull { r -> tallRows(r, snap, name, paired, waiting, at, now, w, h)?.let { r to it } }

        val v = RemoteViews(context.packageName, if (tall != null) R.layout.widget_partner_tall else R.layout.widget_partner)
        if (paired) v.setImageViewBitmap(R.id.partner_avatar, circle(context, snap.partnerAvatar, snap.partnerName, PARTNER, 60))
        else v.setImageViewResource(R.id.partner_avatar, R.drawable.widget_avatar_partner)
        v.setTextViewText(R.id.partner_name, name)
        v.show(R.id.hug_button, paired)
        if (interactive) {
            v.setOnClickPendingIntent(R.id.widget_root, openApp(context, "home"))
            if (paired) v.setOnClickPendingIntent(R.id.hug_button, StatusActionReceiver.intent(context, StatusActionReceiver.HUG))
        }
        if (tall != null) {
            val (r, rows) = tall
            listOf(R.id.place, R.id.line1, R.id.line2, R.id.updated).zip(rows).forEach { (id, text) ->
                v.show(id, text != null)
                if (text != null) v.setTextViewText(id, text)
            }
            rows[4]?.let { v.setTextViewText(R.id.hug_button, it) }
            if (!r.scaled) {
                // 跟版面裡一樣的大小，只是不跟著系統放大
                v.size(R.id.partner_name, 17f, r)
                v.size(R.id.hug_button, 12f, r)
                listOf(R.id.place, R.id.line1, R.id.line2).forEach { v.size(it, 13f, r) }
                v.size(R.id.updated, 11f, r)
            }
            return v
        }

        // 一格高：照高度挑字的大小（🤗 按鈕也要放得下），寬度也要看得到名字和狀態第一項的開頭；字大到兩行都放不下就只留名字
        val parts = if (paired) statusParts(snap) else waiting
        val sample = if (paired) parts.joinToString(" · ") else waiting.first()
        fun avatarOf(s: PartnerStyle) = min(s.avatar, (h - 2 * s.padV).toInt())
        // 右邊留白：🤗 的圓圈和右邊緣的距離跟頭像和左邊緣差不多
        fun padEndOf(s: PartnerStyle) = if (!paired) 10 else if (s.hug > 38) 8 else 3
        fun columnOf(s: PartnerStyle) = w - 10 - avatarOf(s) - 10 - (if (paired) HUG_WIDTH else 0) - padEndOf(s) - 2
        fun tallEnough(r: Ruler, s: PartnerStyle) =
            h >= s.minHeight && max(s.hug.toFloat(), r.height(name, s.name) + 3 + r.height(sample, s.status)) <= h - 2 * s.padV
        fun roomy(r: Ruler, s: PartnerStyle) = columnOf(s) >= max(
            min(r.width(name, s.name), r.width("一二三四", s.name)),
            min(r.width(parts.first().substringBefore("，"), s.status), r.width("📍 一二三四", s.status)),
        )
        fun pick(r: Ruler) = PARTNER_STYLES.firstOrNull { tallEnough(r, it) && roomy(r, it) }
        // 系統字很大時放不下：改用不跟著放大的字，狀態那行還是看得到
        val (r, style) = Ruler(context).let { r -> pick(r)?.let { r to it } }
            ?: Ruler(context, scaled = false).let { r -> r to (pick(r) ?: PARTNER_STYLES.firstOrNull { tallEnough(r, it) } ?: PARTNER_STYLES.last()) }
        v.show(R.id.status, tallEnough(r, style))
        val avatar = avatarOf(style)
        val padEnd = padEndOf(style)
        val column = columnOf(style)
        // 名字旁邊的更新時間：放不下「更新」兩個字就只寫時間，再不行就不寫
        val nameW = min(r.width(name, style.name), 120f)
        val updated = at?.let { listOf("$it 更新", it) }?.firstOrNull { nameW + 6 + r.width(it, style.small) <= column }
        // 一般的一格（不到 64dp）只放一行，兩行會擠在一起
        val status = if (paired) statusLines(r, style, parts, column, h - 2 * style.padV - r.height(name, style.name) - 3, roomy = h >= 64)
        else waiting.firstOrNull { r.width(it, style.status) <= column } ?: waiting.last()

        v.setViewPadding(R.id.widget_root, px(context, 10), px(context, style.padV), px(context, padEnd), px(context, style.padV))
        v.setInt(R.id.partner_avatar, "setMaxWidth", px(context, avatar))
        v.size(R.id.partner_name, style.name, r)
        v.size(R.id.updated, style.small, r)
        v.size(R.id.status, style.status, r)
        v.show(R.id.updated, updated != null)
        updated?.let { v.setTextViewText(R.id.updated, it) }
        v.setTextViewText(R.id.status, status)
        v.setInt(R.id.status, "setMaxLines", if ('\n' in status) 2 else 1)
        if (paired) {
            // 🤗 是圖示，不跟著系統字體放大，不然會塞不進圓圈
            v.setInt(R.id.hug_button, "setBackgroundResource", if (style.hug > 38) R.drawable.widget_hug_big else R.drawable.widget_hug)
            v.setTextViewTextSize(R.id.hug_button, TypedValue.COMPLEX_UNIT_DIP, if (style.hug > 38) 22f else 18f)
        }
        return v
    }

    /**
     * 一格高的狀態：放得下幾項就放幾項，不要切在一半；只有一句太長的說明就留逗號前面那段，還是太長才變「…」。
     * 比較高的一格（[roomy]）、高度（[room]，dp）也夠才放第二行，一樣整項整項放；說明就把逗號後面那段放第二行。
     */
    private fun statusLines(r: Ruler, style: PartnerStyle, parts: List<String>, column: Float, room: Float, roomy: Boolean): String {
        fun fits(text: String) = r.width(text, style.status) <= column
        // 一行放得下前幾項（至少一項）
        fun take(items: List<String>): Int {
            var n = 1
            while (n < items.size && fits(items.take(n + 1).joinToString(" · "))) n++
            return n
        }
        val items = if (fits(parts.first())) parts else listOf(parts.first().substringBefore("，")) + parts.drop(1)
        val n = take(items)
        val one = items.take(n).joinToString(" · ")
        val rest = items.drop(n)
        val second = if (rest.isNotEmpty()) rest.take(take(rest)).joinToString(" · ")
        else parts.singleOrNull()?.takeIf { it != items.first() }?.substringAfter("，")
        val two = second?.let { "$one\n$it" }?.takeIf { roomy && fits(one) && r.height(it, style.status) <= room }
        return two ?: one
    }

    /**
     * 兩格高以上的大版面每行的字（在哪、其他項目兩個一行、更新時間；null 是不顯示）：第一行在哪；沒有的就說明原因。
     * 最後一個是 🤗 按鈕上的字（沒配對是 null）。照版面裡的字的大小量過，整個放不下就回傳 null（改用一格高的排法）。
     */
    private fun tallRows(
        r: Ruler,
        snap: WidgetSnapshot,
        name: String,
        paired: Boolean,
        waiting: List<String>,
        at: String?,
        now: Instant,
        w: Float,
        h: Float,
    ): List<String?>? {
        // 版面：左右留白 14、頭像 60、間距 12；上下留白 10。🤗 按鈕旁邊至少要留一點位置給名字，窄的時候只寫 🤗
        val column = w - 28 - 60 - 12
        val hug = if (!paired) null else listOf("🤗 抱抱", "🤗").firstOrNull { r.width(it, 12f) + 20 + 40 <= column } ?: return null
        val rows: List<String?> = if (!paired) {
            listOf(waiting.firstOrNull { r.width(it, 13f) <= column } ?: waiting.last(), null, null, null)
        } else {
            val lines = buildList {
                add(snap.place?.let { "📍 $it" } ?: snap.note ?: "還沒有狀態")
                snap.items.chunked(2).take(2).forEach { add(it.joinToString("　")) }
                if (snap.place != null && snap.note != null && size < 3) add(snap.note)
            }
            val together = days(snap, Fmt.localDate(now))?.let { "在一起第 ${Fmt.int(it)} 天" }
            listOf(lines.getOrNull(0), lines.getOrNull(1), lines.getOrNull(2), listOfNotNull(at?.let { "$it 更新" }, together).joinToString(" · ").ifEmpty { " " })
        }
        val head = max(r.height(name, 17f, pad = true), if (hug != null) 8 + r.height(hug, 12f, pad = true) else 0f)
        val body = rows.mapIndexed { i, text ->
            text?.let { (if (i == 0 || i == 3) 2 else 0) + r.height(it, if (i == 3) 11f else 13f, pad = true) } ?: 0f
        }.sum()
        return (rows + hug).takeIf { 20 + max(60f, head + body) <= h }
    }
}
