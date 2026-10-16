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
import android.view.Gravity
import android.view.View
import android.widget.RemoteViews
import androidx.core.os.BundleCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tw.xiaoliangkou.app.MainActivity
import tw.xiaoliangkou.app.R
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.data.MediaCache
import tw.xiaoliangkou.app.service.Notifier
import java.io.File
import java.security.MessageDigest
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
    }

    /** 使用者把小工具拉大縮小：照新的大小重畫 */
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        runCatching { update(context, manager, intArrayOf(id)) }
    }

    companion object {
        private const val FILE = "widget_memo.json"
        private const val THUMBS = "widget_memo"
        /** 縮圖的像素（40dp 在高解析度螢幕上也清楚） */
        private const val THUMB_PX = 192
        /** 從 MediaCache 解碼的大小 */
        private const val DECODE_SIDE = 320

        /** 比這個矮（2×1）只放第一則 */
        private const val COMPACT_BELOW = 110f
        /** 比這個高才放「📝 備忘錄」標題列 */
        private const val HEADER_FROM = 150f
        private const val HEADER_H = 24f
        private const val GAP = 6f
        private const val PAD_V = 7f
        private const val TITLE_H = 20f
        private const val LINE_H = 17f
        private const val THUMB = 40f
        /** 一張便利貼最少要放得下標題和一行內容 */
        private const val ROW_MIN = PAD_V * 2 + TITLE_H + LINE_H
        /** 還不知道大小時（剛加到桌面）用 3×2 的大小畫 */
        private const val DEFAULT_W = 250f
        private const val DEFAULT_H = 180f

        private val json = Json { ignoreUnknownKeys = true }
        @Volatile private var current: MemoWidgetSnapshot? = null

        private fun file(context: Context) = File(context.filesDir, FILE)
        private fun thumbDir(context: Context) = File(context.filesDir, THUMBS)
        private fun thumbFile(context: Context, path: String) = File(thumbDir(context), sha1(path) + ".png")

        fun load(context: Context): MemoWidgetSnapshot = current ?: synchronized(this) {
            current ?: (runCatching { json.decodeFromString<MemoWidgetSnapshot>(file(context).readText()) }.getOrNull() ?: MemoWidgetSnapshot())
                .also { current = it }
        }

        /**
         * App 裡的資料變了：照片縮圖從 [media] 讀出來存好（在背景執行緒），存一份資料、重畫桌面上的小工具。
         * 不再用到的縮圖（取消放在桌面、登出）一起刪掉。
         */
        suspend fun publish(context: Context, snap: MemoWidgetSnapshot, media: MediaCache) {
            var fresh = false
            val keep = HashSet<String>()
            for (path in snap.memos.mapNotNull { it.photo }.distinct()) {
                val f = thumbFile(context, path)
                keep += f.name
                if (withContext(Dispatchers.IO) { f.length() > 0 }) continue
                val thumb = thumbOf(path, media) ?: continue
                withContext(Dispatchers.IO) { write(f, thumb) }
                fresh = true
            }
            withContext(Dispatchers.IO) {
                thumbDir(context).listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
                if (!fresh && snap == load(context)) return@withContext
                current = snap
                runCatching { file(context).writeText(json.encodeToString(MemoWidgetSnapshot.serializer(), snap)) }
                updateAll(context, snap)
            }
        }

        fun updateAll(context: Context, snap: MemoWidgetSnapshot = load(context)) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(context, MemoWidget::class.java)) }.getOrNull() ?: return
            update(context, manager, ids, snap)
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

        private fun openMemos(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
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
            val compact = heightDp < COMPACT_BELOW
            val header = heightDp >= HEADER_FROM
            val pad = if (compact) 6f else 8f
            v.setViewPadding(R.id.widget_root, px(context, pad), px(context, pad), px(context, pad), px(context, pad))
            v.setViewVisibility(R.id.memo_header, if (header) View.VISIBLE else View.GONE)
            v.setTextViewText(R.id.memo_header_title, "📝 備忘錄" + if (snap.demo) "（示範）" else "")
            v.removeAllViews(R.id.memo_list)

            val memos = snap.memos.take(MemoWidgetSnapshot.MAX)
            if (memos.isEmpty()) {
                v.setViewVisibility(R.id.memo_list, View.GONE)
                v.setViewVisibility(R.id.memo_empty, View.VISIBLE)
                v.setViewVisibility(R.id.memo_header_more, View.GONE)
                // 矮的時候只放一行字
                v.setViewVisibility(R.id.memo_empty_emoji, if (heightDp >= 90f) View.VISIBLE else View.GONE)
                v.setTextViewText(
                    R.id.memo_empty_text,
                    when {
                        !snap.signedIn -> "登入後，放在桌面的備忘錄會出現在這裡"
                        heightDp < 90f -> "📌 在備忘錄裡打開『放在桌面』"
                        else -> "在備忘錄裡打開『放在桌面』"
                    },
                )
                v.setInt(R.id.memo_empty_text, "setMaxLines", if (heightDp < 90f) 2 else 3)
            } else {
                v.setViewVisibility(R.id.memo_list, View.VISIBLE)
                v.setViewVisibility(R.id.memo_empty, View.GONE)
                val avail = heightDp - pad * 2 - if (header) HEADER_H else 0f
                val count = if (compact) 1 else ((avail + GAP) / (ROW_MIN + GAP)).toInt().coerceIn(1, MemoWidgetSnapshot.MAX).coerceAtMost(memos.size)
                val rowH = (avail - GAP * (count - 1)) / count
                val lines = ((rowH - PAD_V * 2 - TITLE_H) / LINE_H).toInt().coerceIn(0, 12)
                val showThumb = rowH - PAD_V * 2 >= THUMB - 2 && widthDp >= 140f
                val hidden = snap.total - count
                v.setViewVisibility(R.id.memo_header_more, if (header && hidden > 0) View.VISIBLE else View.GONE)
                v.setTextViewText(R.id.memo_header_more, "還有 $hidden 則")
                memos.take(count).forEachIndexed { i, m ->
                    if (i > 0) v.addView(R.id.memo_list, RemoteViews(context.packageName, R.layout.widget_memo_gap))
                    v.addView(R.id.memo_list, note(context, m, lines, if (showThumb) m.photo?.let(thumbs::get) else null))
                }
            }
            if (interactive) v.setOnClickPendingIntent(R.id.widget_root, openMemos(context))
            return v
        }

        /** 一張便利貼：沒有標題就用內容的第一行當標題；內容放得下就一行一項，放不下就接在一起 */
        private fun note(context: Context, m: MemoWidgetSnapshot.Item, maxLines: Int, thumb: Bitmap?): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_memo_item)
            v.setInt(R.id.memo_note, "setBackgroundResource", noteBackground(m.color))
            val bodyLines = m.body.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val title = m.title.ifBlank { bodyLines.firstOrNull() }
                ?: if (m.photos > 0) "📷 照片" else if (m.voices > 0) "🎙️ 語音" else "備忘錄"
            val rest = if (m.title.isBlank()) bodyLines.drop(1) else bodyLines
            v.setTextViewText(R.id.memo_title, title)
            val body = if (rest.size <= maxLines) rest.joinToString("\n") else rest.joinToString("　")
            v.setViewVisibility(R.id.memo_body, if (maxLines > 0 && body.isNotEmpty()) View.VISIBLE else View.GONE)
            v.setTextViewText(R.id.memo_body, body)
            v.setInt(R.id.memo_body, "setMaxLines", maxLines.coerceAtLeast(1))
            if (thumb != null) v.setImageViewBitmap(R.id.memo_thumb, thumb)
            v.setViewVisibility(R.id.memo_thumb, if (thumb != null) View.VISIBLE else View.GONE)
            val meta = listOfNotNull(
                "📷".takeIf { thumb == null && m.photos > 0 },
                "🎙️".takeIf { m.voices > 0 },
                m.author,
            ).joinToString(" ")
            v.setViewVisibility(R.id.memo_meta, if (meta.isNotEmpty()) View.VISIBLE else View.GONE)
            v.setTextViewText(R.id.memo_meta, meta)
            // 很多行的時候從上面開始寫，像真的便利貼
            v.setInt(R.id.memo_note, "setGravity", if (maxLines >= 3 && rest.size >= 2) Gravity.TOP or Gravity.START else Gravity.CENTER_VERTICAL)
            return v
        }
    }
}
