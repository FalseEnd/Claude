package tw.xiaoliangkou.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import android.util.LruCache
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import tw.xiaoliangkou.app.R
import tw.xiaoliangkou.app.ui.components.drawIllustratedSymbol
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 頭像存在 profiles.avatar_url（資料庫原本就有這個欄位，不用改資料庫），只有兩個人看得到：
 * - 照片：data:image/jpeg;base64,…（裁成正方形、256×256，大約 15–30 KB）
 * - 表情：emoji:🐰
 * - 沒設定：顯示暱稱的最後一個字
 */
object Avatars {
    const val PHOTO_PX = 256
    private const val PHOTO_PREFIX = "data:image/jpeg;base64,"
    private const val EMOJI_PREFIX = "emoji:"
    /** 存進資料庫的照片上限，超過就降低畫質 */
    const val MAX_BYTES = 40_000

    val emojis = listOf("🐰", "🐻", "🐱", "🐶", "🦊", "🐼", "🐨", "🐯", "🐹", "🐷", "🐥", "🐧", "🦄", "🐸", "🐮", "🐵")

    /** 舊表情字串沿用作儲存識別值；顯示為原創插圖並提供可讀的協助工具名稱。 */
    fun illustrationLabel(symbol: String): String = when (symbol) {
        "🐰" -> "兔兔"; "🐻" -> "小熊"; "🐱" -> "小貓"; "🐶" -> "小狗"
        "🦊" -> "狐狸"; "🐼" -> "貓熊"; "🐨" -> "無尾熊"; "🐯" -> "老虎"
        "🐹" -> "倉鼠"; "🐷" -> "小豬"; "🐥" -> "小雞"; "🐧" -> "企鵝"
        "🦄" -> "獨角獸"; "🐸" -> "青蛙"; "🐮" -> "乳牛"; "🐵" -> "猴子"
        else -> "動物"
    }

    fun emoji(e: String) = EMOJI_PREFIX + e
    fun emojiOf(avatar: String?): String? =
        avatar?.takeIf { it.startsWith(EMOJI_PREFIX) }?.removePrefix(EMOJI_PREFIX)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 16 }
    fun isPhoto(avatar: String?) = avatar?.startsWith("data:image/") == true

    // 同一張照片會在很多地方顯示（首頁、地圖、通知、桌面小工具），解碼一次就留著
    private val photos = LruCache<String, Bitmap>(6)

    fun photoOf(avatar: String?): Bitmap? {
        if (avatar == null || !isPhoto(avatar)) return null
        photos.get(avatar)?.let { return it }
        val bytes = runCatching { Base64.decode(avatar.substringAfter("base64,", ""), Base64.DEFAULT) }.getOrNull() ?: return null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        photos.put(avatar, bmp)
        return bmp
    }

    /** 裁好的正方形照片 → 存進資料庫的字串 */
    fun encodePhoto(square: Bitmap): String {
        val scaled = if (square.width == PHOTO_PX && square.height == PHOTO_PX) square
        else Bitmap.createScaledBitmap(square, PHOTO_PX, PHOTO_PX, true)
        var quality = 86
        while (true) {
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= MAX_BYTES || quality <= 40) return PHOTO_PREFIX + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            quality -= 12
        }
    }

    /** 從相簿讀照片：先縮到最長邊 [maxSide] 以內（大照片不會吃光記憶體），再依拍照方向轉正 */
    fun load(context: Context, uri: Uri, maxSide: Int = 2048): Bitmap? = runCatching {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: return null
        val degrees = cr.openInputStream(uri)?.use { s ->
            when (ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        if (degrees == 0f) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
    }.getOrNull()

    /** 原圖上要裁的正方形：[left]、[top]、邊長 [side]（原圖的像素） */
    data class Crop(val left: Int, val top: Int, val side: Int)

    /**
     * 裁切框的換算：照片先放大到短邊剛好填滿 [viewport]（螢幕像素），再乘上 [zoom]；
     * [offsetX]、[offsetY] 是照片中心離框中心的距離（螢幕像素）。
     */
    fun cropOf(width: Int, height: Int, viewport: Float, zoom: Float, offsetX: Float, offsetY: Float): Crop {
        val scale = viewport / min(width, height) * zoom
        val side = (viewport / scale).roundToInt().coerceIn(1, min(width, height))
        val cx = width / 2f - offsetX / scale
        val cy = height / 2f - offsetY / scale
        val left = (cx - side / 2f).roundToInt().coerceIn(0, width - side)
        val top = (cy - side / 2f).roundToInt().coerceIn(0, height - side)
        return Crop(left, top, side)
    }

    /** 拖曳時照片不能離開裁切框：回傳最多能偏移多少 */
    fun maxOffset(width: Int, height: Int, viewport: Float, zoom: Float): Pair<Float, Float> {
        val scale = viewport / min(width, height) * zoom
        return max(0f, (width * scale - viewport) / 2) to max(0f, (height * scale - viewport) / 2)
    }

    fun crop(src: Bitmap, c: Crop): Bitmap {
        val square = Bitmap.createBitmap(src, c.left, c.top, c.side, c.side)
        return if (c.side == PHOTO_PX) square else Bitmap.createScaledBitmap(square, PHOTO_PX, PHOTO_PX, true)
    }

    /** 表情頭像的底色：代表色調淡 */
    fun softColor(color: Int) = ColorUtils.blendARGB(color, Color.WHITE, 0.72f)

    /** 暱稱的最後一個字（「阿哲」→「哲」） */
    fun initial(name: String) = name.trim().lastOrNull()?.toString() ?: "?"

    /**
     * 圓形頭像 Bitmap（地圖標記、通知、桌面小工具用）：照片、表情或暱稱的最後一個字，外圈 [ringPx] 寬的白邊。
     */
    fun circle(context: Context, avatar: String?, name: String, color: Int, sizePx: Int, ringPx: Float): Bitmap {
        val size = sizePx.coerceAtLeast(8)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = size / 2f
        val inner = r - ringPx
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        p.color = Color.WHITE
        c.drawCircle(r, r, r, p)
        val photo = photoOf(avatar)
        val emoji = emojiOf(avatar)
        when {
            photo != null -> {
                val shader = BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                val side = min(photo.width, photo.height).toFloat()
                val src = RectF((photo.width - side) / 2, (photo.height - side) / 2, (photo.width + side) / 2, (photo.height + side) / 2)
                shader.setLocalMatrix(Matrix().apply { setRectToRect(src, RectF(r - inner, r - inner, r + inner, r + inner), Matrix.ScaleToFit.FILL) })
                p.shader = shader
                c.drawCircle(r, r, inner, p)
                p.shader = null
            }
            emoji != null -> {
                p.color = softColor(color)
                c.drawCircle(r, r, inner, p)
                drawIllustratedSymbol(c, emoji, r - inner * 0.9f, r - inner * 0.9f, inner * 1.8f)
            }
            else -> {
                p.color = color
                c.drawCircle(r, r, inner, p)
                p.color = Color.WHITE
                p.textSize = inner * 0.95f
                p.textAlign = Paint.Align.CENTER
                p.typeface = runCatching { ResourcesCompat.getFont(context, R.font.huninn) }.getOrNull() ?: Typeface.DEFAULT_BOLD
                c.drawText(initial(name), r, r - (p.descent() + p.ascent()) / 2, p)
            }
        }
        return bmp
    }
}
