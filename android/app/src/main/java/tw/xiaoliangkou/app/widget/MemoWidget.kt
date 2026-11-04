package tw.xiaoliangkou.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.res.ResourcesCompat
import androidx.core.os.BundleCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tw.xiaoliangkou.app.MainActivity
import tw.xiaoliangkou.app.R
import tw.xiaoliangkou.app.XlkApp
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.data.MediaCache
import tw.xiaoliangkou.app.service.Notifier
import java.io.File
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** 備忘錄小工具要顯示的資料：放在桌面的備忘錄。存一份在手機裡，App 沒在跑時也畫得出來 */
@Serializable
data class MemoWidgetSnapshot(
    val signedIn: Boolean = false,
    val demo: Boolean = false,
    /** 放在桌面的備忘錄（跟 App 裡的順序一樣），最多 [MAX] 則 */
    val memos: List<Item> = emptyList(),
    /** 放在桌面的一共有幾則 */
    val total: Int = 0,
) {
    @Serializable
    data class Item(
        val id: String,
        val title: String = "",
        val body: String = "",
        val color: String = "pink",
        /** 第一張照片的 path（縮圖存在手機裡） */
        val photo: String? = null,
        val photos: Int = 0,
        val voices: Int = 0,
        /** 對方寫的就是對方的名字 */
        val author: String? = null,
    )

    companion object {
        const val MAX = 3
        /** 小工具最多也只顯示十幾行，內容不用整篇存 */
        private const val BODY_CHARS = 400

        fun of(s: AppState?): MemoWidgetSnapshot {
            if (s == null) return MemoWidgetSnapshot()
            val pinned = s.pinnedMemos
            return MemoWidgetSnapshot(
                signedIn = true,
                demo = s.demo,
                memos = pinned.take(MAX).map { m ->
                    Item(
                        id = m.id,
                        title = m.title.trim(),
                        body = m.body.trim().take(BODY_CHARS),
                        color = m.color,
                        photo = m.media.firstOrNull { it.isPhoto }?.path,
                        photos = m.media.count { it.isPhoto },
                        voices = m.media.count { it.isVoice },
                        author = s.partnerName.takeIf { m.userId != s.myId },
                    )
                },
                total = pinned.size,
            )
        }
    }
}

/**
 * 桌面小工具「備忘錄」：放在桌面的備忘錄（最多 3 則：標題、內容前幾行、第一張照片），點了打開 App 的備忘錄。
 * 依小工具的大小排版：矮的（2×1）只放第一則，拉大就多放幾則、多顯示幾行。
 */
class MemoWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        runCatching { update(context, manager, ids) }
        fetchMissingThumbs(context)
    }

    /** 使用者把小工具拉大縮小：照新的大小重畫 */
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        runCatching { update(context, manager, intArrayOf(id)) }
    }

    /** 最後一個小工具也拿掉了：縮圖用不到了 */
    override fun onDisabled(context: Context) {
        runCatching { thumbDir(context).deleteRecursively() }
    }

    /**
     * 剛加到桌面：之前桌面上沒有這個小工具，App 就沒去抓照片縮圖，現在補抓（App 已經在跑才做得到；
     * 沒在跑的話，App 一啟動就會抓）。
     */
    private fun fetchMissingThumbs(context: Context) {
        val snap = load(context)
        if (snap.memos.none { m -> m.photo?.let { thumbFile(context, it).length() <= 0 } == true }) return
        val container = runCatching { (context.applicationContext as? XlkApp)?.container }.getOrNull() ?: return
        val pending: PendingResult? = goAsync()
        container.scope.launch(Dispatchers.IO) {
            try {
                withTimeoutOrNull(9_000) { refresh(context.applicationContext, container.media) }
            } finally {
                pending?.finish()
            }
        }
    }

    companion object {
        private const val FILE = "widget_memo.json"
        private const val THUMBS = "widget_memo"
        /** 縮圖的像素（56dp 在高解析度螢幕上也清楚） */
        private const val THUMB_PX = 192
        /** 從 MediaCache 解碼的大小 */
        private const val DECODE_SIDE = 320

        /** 比這個高才考慮放「📝 備忘錄」標題列 */
        private const val HEADER_FROM = 150f
        private const val GAP = 6f
        /** 便利貼上下的留白（widget_memo_item 的 paddingVertical），擠的時候最少留 [NOTE_PAD_MIN] */
        private const val NOTE_PAD_V = 6f
        private const val NOTE_PAD_MIN = 2f
        /** 標題、內容、標題列、沒有釘選時的提示的字級（跟 layout 一樣） */
        private const val TITLE_SP = 14f
        private const val BODY_SP = 12f
        private const val HEADER_SP = 13f
        private const val MORE_SP = 11f
        private const val META_SP = 10f
        private const val HINT_SP = 12f
        private const val HINT_EMOJI_SP = 20f
        /** 並排時每張便利貼最少要這麼寬 */
        private const val NOTE_MIN_W = 100f
        /** 還不知道大小時（剛加到桌面）用 3×2 的大小畫 */
        private const val DEFAULT_W = 250f
        private const val DEFAULT_H = 180f

        private val json = Json { ignoreUnknownKeys = true }
        @Volatile private var current: MemoWidgetSnapshot? = null
        /** 上次畫的時候系統字型大小是多少：改了就要重新排版 */
        @Volatile private var drawnFontScale = 0f
        /** 存資料、抓縮圖一次只做一件，不會讓舊的資料蓋掉新的 */
        private val lock = Mutex()

        private fun file(context: Context) = File(context.filesDir, FILE)
        private fun thumbDir(context: Context) = File(context.filesDir, THUMBS)
        private fun thumbFile(context: Context, path: String) = File(thumbDir(context), sha1(path) + ".png")

        fun load(context: Context): MemoWidgetSnapshot = current ?: synchronized(this) {
            current ?: (runCatching { json.decodeFromString<MemoWidgetSnapshot>(file(context).readText()) }.getOrNull() ?: MemoWidgetSnapshot())
                .also { current = it }
        }

        /**
         * App 裡的資料變了：存一份資料、重畫桌面上的小工具。桌面上有這個小工具才去抓照片縮圖（在背景執行緒）；
         * 不再用到的縮圖（取消放在桌面、登出、小工具拿掉了）一起刪掉。
         * 回傳 false 代表有縮圖沒抓到（網路不穩、對方的照片還沒上傳好），之後要再 [refresh]。
         */
        suspend fun publish(context: Context, snap: MemoWidgetSnapshot, media: MediaCache): Boolean =
            lock.withLock { save(context, snap, media) }

        /** 用存著的資料再試一次：補抓上次沒抓到的縮圖、小工具剛加到桌面、系統字型大小改了 */
        suspend fun refresh(context: Context, media: MediaCache): Boolean =
            lock.withLock { save(context, load(context), media) }

        private suspend fun save(context: Context, snap: MemoWidgetSnapshot, media: MediaCache): Boolean {
            val ids = withContext(Dispatchers.IO) { widgetIds(context) }
            // 桌面上沒有這個小工具就不用抓縮圖（省網路、省電）
            val paths = if (ids.isEmpty()) emptyList() else snap.memos.mapNotNull { it.photo }.distinct()
            var fresh = false
            var missing = false
            for (path in paths) {
                val f = thumbFile(context, path)
                if (withContext(Dispatchers.IO) { f.length() > 0 }) continue
                val thumb = thumbOf(path, media)
                if (thumb == null) { missing = true; continue }
                withContext(Dispatchers.IO) { write(f, thumb) }
                fresh = true
            }
            withContext(Dispatchers.IO) {
                val keep = paths.map { thumbFile(context, it).name }.toSet()
                thumbDir(context).listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
                val fontScale = context.resources.configuration.fontScale
                val same = snap == load(context)
                if (same && !fresh && fontScale == drawnFontScale) return@withContext
                if (!same) {
                    current = snap
                    runCatching { file(context).writeText(json.encodeToString(MemoWidgetSnapshot.serializer(), snap)) }
                }
                drawnFontScale = fontScale
                if (ids.isNotEmpty()) update(context, AppWidgetManager.getInstance(context), ids, snap)
            }
            return !missing
        }

        private fun widgetIds(context: Context): IntArray = runCatching {
            AppWidgetManager.getInstance(context)?.getAppWidgetIds(ComponentName(context, MemoWidget::class.java))
        }.getOrNull() ?: IntArray(0)

        /** 桌面上有沒有放這個小工具（沒有的話，備忘錄打開「放在桌面」也看不到） */
        fun isPlaced(context: Context): Boolean = widgetIds(context).isNotEmpty()

        fun updateAll(context: Context, snap: MemoWidgetSnapshot = load(context)) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            update(context, manager, widgetIds(context), snap)
        }

        private fun update(context: Context, manager: AppWidgetManager, ids: IntArray, snap: MemoWidgetSnapshot = load(context)) {
            if (ids.isEmpty()) return
            val thumbs = savedThumbs(context, snap)
            ids.forEach { id -> runCatching { manager.updateAppWidget(id, views(context, snap, manager.getAppWidgetOptions(id), thumbs)) } }
        }

        /** 依桌面給的大小排版：Android 12 以上每種可能的大小各畫一份，舊版畫直的、橫的兩份 */
        fun views(context: Context, snap: MemoWidgetSnapshot, options: Bundle?, thumbs: Map<String, Bitmap>): RemoteViews {
            fun at(w: Float, h: Float) = render(context, snap, w, h, thumbs)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && options != null) {
                val sizes = BundleCompat.getParcelableArrayList(options, AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
                    ?.filter { it.width > 0 && it.height > 0 }?.distinct()?.take(16)
                if (!sizes.isNullOrEmpty()) return RemoteViews(sizes.associateWith { at(it.width, it.height) })
            }
            val minW = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
            val maxW = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH) ?: 0
            val minH = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) ?: 0
            val maxH = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: 0
            if (minW <= 0 || maxH <= 0) return at(DEFAULT_W, DEFAULT_H)
            // 直的時候寬度最窄、高度最高；橫的時候相反
            return RemoteViews(at(maxW.toFloat(), minH.toFloat()), at(minW.toFloat(), maxH.toFloat()))
        }

        /** 存在手機裡的縮圖 */
        private fun savedThumbs(context: Context, snap: MemoWidgetSnapshot): Map<String, Bitmap> =
            snap.memos.mapNotNull { it.photo }.distinct().mapNotNull { path ->
                runCatching { BitmapFactory.decodeFile(thumbFile(context, path).path) }.getOrNull()?.let { path to it }
            }.toMap()

        /** App 裡預覽用：直接從 [media] 讀縮圖（背景執行緒） */
        suspend fun thumbs(snap: MemoWidgetSnapshot, media: MediaCache): Map<String, Bitmap> =
            snap.memos.mapNotNull { it.photo }.distinct().mapNotNull { path -> thumbOf(path, media)?.let { path to it } }.toMap()

        private suspend fun thumbOf(path: String, media: MediaCache): Bitmap? {
            val bmp = media.bitmap(path, DECODE_SIDE) ?: return null
            return withContext(Dispatchers.Default) { runCatching { roundSquare(bmp, THUMB_PX) }.getOrNull() }
        }

        /** 裁成置中的正方形、圓角 */
        private fun roundSquare(src: Bitmap, side: Int): Bitmap {
            val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            val crop = min(src.width, src.height).toFloat()
            val scale = side / crop
            val m = Matrix().apply {
                postTranslate(-(src.width - crop) / 2f, -(src.height - crop) / 2f)
                postScale(scale, scale)
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m) }
            }
            val r = side * 0.24f
            Canvas(out).drawRoundRect(RectF(0f, 0f, side.toFloat(), side.toFloat()), r, r, paint)
            return out
        }

        private fun write(f: File, bmp: Bitmap) {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, "${f.name}.${System.nanoTime()}.tmp")
            runCatching {
                tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            }
            tmp.delete()
        }

        private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        private fun px(context: Context, dp: Float) = (dp * context.resources.displayMetrics.density).toInt()

        internal fun openMemos(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                // 自己的 action：通知的 PendingIntent 編號剛好也是 7100 時，才不會被當成同一個、互相蓋掉要打開的畫面
                action = Widgets.ACTION_OPEN_PREFIX + "memos"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(Notifier.EXTRA_ROUTE, "memos")
            }
            return PendingIntent.getActivity(context, 7_100, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun noteBackground(color: String) = when (color) {
            "yellow" -> R.drawable.widget_memo_note_yellow
            "mint" -> R.drawable.widget_memo_note_mint
            "sky" -> R.drawable.widget_memo_note_sky
            "lavender" -> R.drawable.widget_memo_note_lavender
            "peach" -> R.drawable.widget_memo_note_peach
            else -> R.drawable.widget_memo_note_pink
        }

        /** 最後一行放不下的項目：接在最後一個完整的項目後面 */
        private const val MORE = "　…"

        /** 用估的寬度排（測試用） */
        internal fun pack(items: List<String>, lines: Int, widthDp: Float, em: Float): String = pack(items, lines, widthDp) { textWidth(it, em) }

        /**
         * 把每一項排進 [lines] 行裡：放得下就一行一項；放不下就把短的接在同一行（用 [sep] 隔開），
         * 只在兩項中間換行，不會把「九份」拆成兩行。最後一行放不下的不硬塞，接一個「…」。
         * 只有單獨一項就太長時才由 TextView 加…。[widthDp] 是文字可以用的寬度，[width] 量一段文字多寬（dp）。
         */
        internal fun pack(items: List<String>, lines: Int, widthDp: Float, sep: String = "　", width: (String) -> Float): String {
            if (lines <= 0 || items.isEmpty()) return ""
            if (items.size <= lines) return items.joinToString("\n")
            val out = ArrayList<String>()
            var cur = ""
            for ((i, item) in items.withIndex()) {
                val next = if (cur.isEmpty()) item else "$cur$sep$item"
                val last = out.size == lines - 1
                // 最後一行：後面還有的話，要留位置給「…」
                val fits = width(if (last && i < items.lastIndex) next + MORE else next) <= widthDp
                when {
                    cur.isEmpty() || fits -> cur = next
                    !last -> { out += cur; cur = item }
                    // 窄到連「　…」都放不下就只接「…」，再不行就不接（不要讓 TextView 切在字中間）
                    else -> { cur += listOf(MORE, "…").firstOrNull { width(cur + it) <= widthDp } ?: ""; break }
                }
            }
            out += cur
            return out.joinToString("\n")
        }

        /** 大概估一下文字多寬（中文、全形一個字寬，表情符號再寬一點，英數字半個字寬），寧可估寬一點；量不到字型時用 */
        private fun textWidth(text: String, em: Float): Float {
            var units = 0f
            var i = 0
            while (i < text.length) {
                val c = text.codePointAt(i)
                units += when {
                    c == 0x200D || c in 0xFE00..0xFE0F || c in 0x1F3FB..0x1F3FF -> 0f
                    c >= 0x1F000 || c in 0x2600..0x27BF -> 1.2f
                    c >= 0x2E80 || c == 0x2026 -> 1f
                    else -> 0.6f
                }
                i += Character.charCount(c)
            }
            return units * em * 1.05f
        }

        /**
         * 字有多大、幾行字有多高（dp）：跟著系統的字型大小（Android 14 以上大字是非線性放大，所以用 sp 換算，不是直接乘倍數）。
         * 行高是 jf open 粉圓的實測值：第一行約 1.33 倍字高，之後每行約 1.21 倍（有表情符號的行比較高），再多留一點。
         */
        internal class Fonts(private val paint: Paint? = null, private val density: Float = 1f, private val spToDp: (Float) -> Float) {
            fun dp(sp: Float) = spToDp(sp)
            fun height(sp: Float, lines: Int = 1) = if (lines <= 0) 0f else dp(sp) * (1.34f + 1.22f * (lines - 1)) + 0.5f

            /**
             * [text] 用 [sp] 的字有多寬（dp）：用小工具一樣的字型量（表情符號照系統的字型），跟 TextView 量的一樣，
             * 只多留 1dp 給像素的進位；量不到字型時用估的
             */
            fun width(text: String, sp: Float): Float {
                val p = paint ?: return textWidth(text, dp(sp))
                p.textSize = dp(sp) * density
                return p.measureText(text) / density + 1f
            }

            companion object {
                /** 標準字型大小（估寬度） */
                val STANDARD = Fonts { it }

                fun of(context: Context): Fonts {
                    val dm = context.resources.displayMetrics
                    val font = runCatching { ResourcesCompat.getFont(context, R.font.huninn) }.getOrNull()
                    val paint = font?.let { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = it } }
                    return Fonts(paint, dm.density) { TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, it, dm) / dm.density }
                }
            }
        }

        /** 「📝 備忘錄」標題列的高度（文字加上 paddingBottom） */
        private fun headerH(fonts: Fonts) = 6f + fonts.height(HEADER_SP)

        /** 一張便利貼最少要放得下標題和一行內容 */
        private fun noteMinH(fonts: Fonts) = NOTE_PAD_V * 2 + fonts.height(TITLE_SP) + 1f + fonts.height(BODY_SP)

        /** 並排時每張便利貼最少要這麼寬（字大的時候寬一點，標題才看得到幾個字） */
        private fun noteMinW(fonts: Fonts) = max(NOTE_MIN_W, fonts.dp(TITLE_SP) * 7f)

        /** 依大小排版的結果：放幾則、要不要標題列、並排還是由上往下、每張便利貼多大 */
        internal data class Plan(val count: Int, val header: Boolean, val side: Boolean, val pad: Float, val noteW: Float, val noteH: Float)

        /**
         * 盡量把放在桌面的備忘錄（最多 3 則）都放進去，每張至少要有標題和一行內容：
         * 一般由上往下排；又寬又矮（橫的桌面、4×1）放不下時改成並排；標題列只在不會少放一則時才放。
         * 2×1 這種又窄又矮的只放第一則。系統字型調大時，一樣的大小就少放幾則、少幾行。
         */
        internal fun plan(widthDp: Float, heightDp: Float, memos: Int, fonts: Fonts = Fonts.STANDARD): Plan {
            val n = memos.coerceIn(1, MemoWidgetSnapshot.MAX)
            val pad = when {
                heightDp < 56f -> 4f
                heightDp < 110f -> 6f
                else -> 8f
            }
            val innerW = widthDp - pad * 2
            val minH = noteMinH(fonts)
            val sideMax = ((innerW + GAP) / (noteMinW(fonts) + GAP)).toInt().coerceIn(1, n)
            fun at(header: Boolean): Plan {
                val availH = heightDp - pad * 2 - if (header) headerH(fonts) else 0f
                val stacked = ((availH + GAP) / (minH + GAP)).toInt().coerceIn(1, n)
                return if (sideMax > stacked && availH >= minH) {
                    Plan(sideMax, header, true, pad, (innerW - GAP * (sideMax - 1)) / sideMax, availH)
                } else {
                    Plan(stacked, header, false, pad, innerW, (availH - GAP * (stacked - 1)) / stacked)
                }
            }
            val plain = at(false)
            if (heightDp < HEADER_FROM) return plain
            return at(true).takeIf { it.count >= plain.count && it.noteH >= minH } ?: plain
        }

        /**
         * 畫 [widthDp]×[heightDp] 大小的小工具。[thumbs] 是照片 path → 縮圖；
         * [interactive] 為 false 時不接點擊（App 裡的預覽用）。
         */
        fun render(
            context: Context,
            snap: MemoWidgetSnapshot,
            widthDp: Float,
            heightDp: Float,
            thumbs: Map<String, Bitmap> = emptyMap(),
            interactive: Boolean = true,
        ): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_memo)
            val fonts = Fonts.of(context)
            val memos = snap.memos.take(MemoWidgetSnapshot.MAX)
            val plan = plan(widthDp, heightDp, memos.size, fonts)
            val pad = px(context, plan.pad)
            v.setViewPadding(R.id.widget_root, pad, pad, pad, pad)
            v.removeAllViews(R.id.memo_list)
            v.removeAllViews(R.id.memo_row)

            if (memos.isEmpty()) {
                hint(v, snap, widthDp, heightDp, plan.pad, fonts)
            } else {
                val shown = memos.take(plan.count)
                val hidden = snap.total - shown.size
                val more = "還有 $hidden 則"
                v.setViewVisibility(R.id.memo_header, if (plan.header) View.VISIBLE else View.GONE)
                v.setViewVisibility(R.id.memo_header_more, if (hidden > 0) View.VISIBLE else View.GONE)
                v.setTextViewText(R.id.memo_header_more, more)
                header(v, snap, widthDp - plan.pad * 2 - if (hidden > 0) fonts.width(more, MORE_SP) + 6f else 0f, fonts)
                v.setViewVisibility(R.id.memo_empty, View.GONE)
                val list = if (plan.side) R.id.memo_row else R.id.memo_list
                v.setViewVisibility(R.id.memo_list, if (plan.side) View.GONE else View.VISIBLE)
                v.setViewVisibility(R.id.memo_row, if (plan.side) View.VISIBLE else View.GONE)
                shown.forEachIndexed { i, m ->
                    if (i > 0) v.addView(list, RemoteViews(context.packageName, R.layout.widget_memo_gap))
                    v.addView(list, note(context, m, plan.noteW, plan.noteH, m.photo?.let(thumbs::get), plan.side, fonts))
                }
            }
            if (interactive) v.setOnClickPendingIntent(R.id.widget_root, openMemos(context))
            return v
        }

        /** 標題列「📝 備忘錄（示範）」：字大放不下時不寫（示範），不要變成「備忘…」 */
        private fun header(v: RemoteViews, snap: MemoWidgetSnapshot, widthDp: Float, fonts: Fonts) {
            val full = "備忘錄" + if (snap.demo) "（示範）" else ""
            v.setTextViewText(R.id.memo_header_title, if (fonts.width(full, HEADER_SP) <= widthDp - 12f) full else "備忘錄")
        }

        /** 還沒登入、沒有放在桌面的備忘錄：提示怎麼放。依大小和字型大小挑放得下的說法，不會被切掉 */
        private fun hint(v: RemoteViews, snap: MemoWidgetSnapshot, widthDp: Float, heightDp: Float, pad: Float, fonts: Fonts) {
            val showHeader = heightDp >= HEADER_FROM
            if (showHeader) header(v, snap, widthDp - pad * 2, fonts)
            val room = heightDp - pad * 2 - (if (showHeader) headerH(fonts) else 0f) - 8f
            val textW = widthDp - pad * 2 - 20f
            // （上面放不放 📌、可以斷行的幾段、字級），由大到小挑放得下的；只在兩段中間斷行
            val options = if (!snap.signedIn) listOf(
                Triple(true, listOf("登入後，", "放在桌面的", "備忘錄會出現在這裡"), HINT_SP),
                Triple(false, listOf("登入後，", "放在桌面的", "備忘錄會出現在這裡"), HINT_SP),
                Triple(false, listOf("登入後", "就會出現"), HINT_SP),
                Triple(false, listOf("登入後", "就會出現"), 10f),
                Triple(false, listOf("登入"), 10f),
            ) else listOf(
                Triple(true, listOf("在備忘錄裡打開", "『放在桌面』"), HINT_SP),
                Triple(false, listOf("在備忘錄裡打開", "『放在桌面』"), HINT_SP),
                Triple(false, listOf("打開", "『放在桌面』"), HINT_SP),
                Triple(false, listOf("打開", "『放在桌面』"), 10f),
                Triple(false, listOf("放在桌面"), HINT_SP),
            )
            fun text(parts: List<String>, sp: Float): Pair<String, Int> {
                // 一段一段接起來，接不下就換行
                val rows = ArrayList<String>()
                for (part in parts) {
                    val last = rows.lastOrNull()
                    if (last != null && fonts.width(last + part, sp) <= textW) rows[rows.lastIndex] = last + part else rows += part
                }
                // 只有一段就太長的話 TextView 會自己斷行
                return rows.joinToString("\n") to rows.sumOf { max(1, ceil(fonts.width(it, sp) / textW).toInt()) }
            }
            fun need(emoji: Boolean, lines: Int, sp: Float) = (if (emoji) fonts.height(HINT_EMOJI_SP) + 2f else 0f) + fonts.height(sp, lines)
            val pick = options.firstOrNull { (emoji, parts, sp) -> need(emoji, text(parts, sp).second, sp) <= room } ?: options.last()
            val (emoji, parts, sp) = pick
            val (t, all) = text(parts, sp)
            var lines = all
            // 真的放不下：寧可少一行加…，也不要切掉半行字
            while (lines > 1 && need(emoji, lines, sp) > room) lines--
            v.setViewVisibility(R.id.memo_header, if (showHeader) View.VISIBLE else View.GONE)
            v.setViewVisibility(R.id.memo_header_more, View.GONE)
            v.setViewVisibility(R.id.memo_list, View.GONE)
            v.setViewVisibility(R.id.memo_row, View.GONE)
            v.setViewVisibility(R.id.memo_empty, View.VISIBLE)
            v.setViewVisibility(R.id.memo_empty_emoji, if (emoji) View.VISIBLE else View.GONE)
            v.setTextViewText(R.id.memo_empty_emoji, "")
            v.setTextViewCompoundDrawables(R.id.memo_empty_emoji, R.drawable.widget_heart, 0, 0, 0)
            v.setTextViewText(R.id.memo_empty_text, t)
            v.setTextViewTextSize(R.id.memo_empty_text, TypedValue.COMPLEX_UNIT_SP, sp)
            v.setInt(R.id.memo_empty_text, "setMaxLines", lines)
        }

        /**
         * 一張 [w]×[h] 的便利貼：沒有標題就用內容的第一行當標題；內容放得下就一行一項，放不下就接在一起。
         * 夠大才放照片縮圖、作者。並排（[side]）時每張都從上面開始寫，標題才會對齊。
         */
        private fun note(context: Context, m: MemoWidgetSnapshot.Item, w: Float, h: Float, thumb: Bitmap?, side: Boolean, fonts: Fonts): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_memo_item)
            v.setInt(R.id.memo_note, "setBackgroundResource", noteBackground(m.color))
            // 空間很多（只放一兩則）時字大一點，不要留一大片空白
            val roomy = h >= 110f && w >= 200f
            var titleSp = if (roomy) 16f else TITLE_SP
            val bodySp = if (roomy) 14f else BODY_SP
            // 很矮的時候（最小的 1 格高、字型調很大）：上下留白少一點，還是不夠就把標題的字縮小一點，不要切掉
            while (titleSp > 10f && fonts.height(titleSp) > h - NOTE_PAD_MIN * 2) titleSp--
            val titleH = fonts.height(titleSp)
            val oneLine = titleH + 1f + fonts.height(bodySp)
            val padV = (if (h >= oneLine + NOTE_PAD_MIN * 2) (h - oneLine) / 2 else (h - titleH) / 2).coerceIn(NOTE_PAD_MIN, NOTE_PAD_V)
            val padH = px(context, if (w < 130f) 8f else 10f)
            v.setViewPadding(R.id.memo_note, padH, px(context, padV), padH, px(context, padV))
            val content = h - padV * 2
            v.setTextViewTextSize(R.id.memo_title, TypedValue.COMPLEX_UNIT_SP, titleSp)
            v.setTextViewTextSize(R.id.memo_body, TypedValue.COMPLEX_UNIT_SP, bodySp)
            var lines = 0
            while (lines < 12 && titleH + 1f + fonts.height(bodySp, lines + 1) <= content) lines++

            val bodyLines = m.body.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val title = m.title.ifBlank { bodyLines.firstOrNull() }
                ?: if (m.photos > 0) "照片" else if (m.voices > 0) "語音" else "備忘錄"
            val rest = if (m.title.isBlank()) bodyLines.drop(1) else bodyLines
            v.setTextViewText(R.id.memo_title, title)

            // 縮圖：又寬又高放大張的，不然放小張的，太擠就不放
            val big = thumb != null && w >= 220f && content >= 64f && lines >= 3
            val small = thumb != null && !big && w >= 140f && content >= 36f
            val textW = w - (if (w < 130f) 16f else 20f) - (if (big) 66f else if (small) 44f else 0f)
            val body = pack(rest, lines, textW) { fonts.width(it, bodySp) }
            v.setViewVisibility(R.id.memo_body, if (body.isNotEmpty()) View.VISIBLE else View.GONE)
            v.setTextViewText(R.id.memo_body, body)
            v.setInt(R.id.memo_body, "setMaxLines", lines.coerceAtLeast(1))
            v.setViewVisibility(R.id.memo_thumb_big, if (big) View.VISIBLE else View.GONE)
            v.setViewVisibility(R.id.memo_thumb, if (small) View.VISIBLE else View.GONE)
            if (big) v.setImageViewBitmap(R.id.memo_thumb_big, thumb)
            if (small) v.setImageViewBitmap(R.id.memo_thumb, thumb)

            // 標題旁邊的小字（📷、🎙️、對方的名字）：放了會把標題擠成「晚安…」就不放
            val meta = listOfNotNull(
                "照片".takeIf { !big && !small && m.photos > 0 && w >= 120f },
                "語音".takeIf { m.voices > 0 && w >= 120f },
                m.author?.takeIf { w - (if (small) 44f else if (big) 66f else 0f) >= 180f },
            ).joinToString(" ").takeIf { it.isNotEmpty() && fonts.width(title, titleSp) + 4f + fonts.width(it, META_SP) <= textW } ?: ""
            v.setViewVisibility(R.id.memo_meta, if (meta.isNotEmpty()) View.VISIBLE else View.GONE)
            v.setTextViewText(R.id.memo_meta, meta)
            // 很多行的時候從上面開始寫，像真的便利貼；並排的每張都從上面開始，標題才對齊
            val top = side || (lines >= 3 && rest.size >= 2) || big
            v.setInt(R.id.memo_note, "setGravity", if (top) Gravity.TOP or Gravity.START else Gravity.CENTER_VERTICAL)
            return v
        }
    }
}
