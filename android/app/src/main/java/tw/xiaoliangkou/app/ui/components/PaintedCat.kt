package tw.xiaoliangkou.app.ui.components

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import tw.xiaoliangkou.app.R
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** 各姿勢都以同一條腳掌基線對齊；臉的位置則依插畫構圖決定。 */
internal enum class CatDrawing(val resource: Int, val mouth: Offset) {
    SITTING(R.drawable.cat_coats, Offset(.453f, .461f)),
    EATING(R.drawable.cat_eating, Offset(.312f, .865f)),
    GROOMING(R.drawable.cat_grooming, Offset(.453f, .526f)),
    SLEEPING(R.drawable.cat_sleeping, Offset(.29f, .775f)),
}

/** 一次只解碼目前選用的品種，每個快取項目約 400 KiB。 */
internal object CatBitmaps {
    private val cells = LruCache<Pair<CatDrawing, Int>, Bitmap>(18)

    @Suppress("DEPRECATION")
    @Synchronized
    fun get(resources: Resources, drawing: CatDrawing, cell: Int): Bitmap {
        val key = drawing to cell
        cells.get(key)?.let { return it }
        return resources.openRawResource(drawing.resource).use { stream ->
            val decoder = BitmapRegionDecoder.newInstance(stream, false)
            if (decoder == null) {
                val atlas = BitmapFactory.decodeResource(resources, drawing.resource)
                val w = atlas.width / 4
                val h = atlas.height / 4
                Bitmap.createBitmap(atlas, cell % 4 * w, cell / 4 * h, w, h)
            } else {
                try {
                    val w = decoder.width / 4
                    val h = decoder.height / 4
                    val x = cell % 4 * w
                    val y = cell / 4 * h
                    decoder.decodeRegion(Rect(x, y, x + w, y + h), null)
                } finally {
                    decoder.recycle()
                }
            }
        }.also { cells.put(key, it) }
    }
}

internal class PaintedCat(val drawing: CatDrawing, private val bitmap: Bitmap, cell: Int) {
    private val grid = 32
    private val vertices = FloatArray((grid + 1) * (grid + 1) * 2)
    private val destination = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val face = drawing.mouth
    private val eyeY = if (cell == 12) .344f else .362f
    private val eyeCenter = if (cell == 14) .473f else .453f

    fun draw(scope: DrawScope, left: Float, top: Float, side: Float, pose: CatPose, opacity: Float) = with(scope) {
        if (opacity <= 0f) return@with
        paint.alpha = (opacity * 255).roundToInt()
        if (drawing == CatDrawing.SLEEPING || pose == neutral) {
            destination.set(left, top, left + side, top + side)
            drawIntoCanvas { it.nativeCanvas.drawBitmap(bitmap, null, destination, paint) }
            return@with
        }
        val sitting = drawing == CatDrawing.SITTING
        val grooming = drawing == CatDrawing.GROOMING
        val eating = drawing == CatDrawing.EATING
        // 抬掌、埋頭的插畫已經有完整輪廓，只需要在頸部、下顎接續小動作。
        val headX = if (sitting) pose.headX else 0f
        val headY = when (drawing) {
            CatDrawing.SITTING -> pose.headY
            CatDrawing.GROOMING -> .019f * pose.lick
            CatDrawing.EATING -> .023f * pose.jaw - .012f
            CatDrawing.SLEEPING -> 0f
        }
        val roll = if (sitting) pose.headRoll else if (grooming) -.025f * pose.lick else 0f
        var i = 0
        for (row in 0..grid) for (column in 0..grid) {
            val x = column.toFloat() / grid
            val y = row.toFloat() / grid
            val head = if (eating) (1f - abs(x - face.x) / .32f).coerceIn(0f, 1f) * ((y - .45f) / .3f).coerceIn(0f, 1f)
                else ((.77f - y) / .29f).coerceIn(0f, 1f)
            val tail = ((x - .66f) / .12f).coerceIn(0f, 1f) * ((y - .43f) / .15f).coerceIn(0f, 1f)
            val paw = if (sitting) (1f - abs(x - .39f) / .16f).coerceAtLeast(0f) * ((y - .58f) / .29f).coerceIn(0f, 1f) else 0f
            val ear = if (sitting) ((.29f - y) / .18f).coerceIn(0f, 1f) else 0f
            val eye = min(abs(x - (eyeCenter - .075f)), abs(x - (eyeCenter + .075f)))
            val lid = if (sitting) min(((.07f - eye) / .035f).coerceIn(0f, 1f),
                ((.082f - abs(y - eyeY)) / .045f).coerceIn(0f, 1f)) * pose.eyesClosed else 0f
            val dx = head * (headX - (y - face.y - .08f) * roll) + paw * pose.pawX +
                tail * pose.tail - ear * (x - face.x) * pose.ears * .18f
            val dy = head * (headY + (x - face.x) * roll) - paw * pose.paw +
                tail * pose.tailY - ear * pose.ears * .035f + lid * (eyeY + .009f - y) * .94f
            vertices[i++] = left + (x + dx) * side
            vertices[i++] = top + (y + dy) * side
        }
        drawIntoCanvas { it.nativeCanvas.drawBitmapMesh(bitmap, grid, grid, vertices, 0, null, 0, paint) }
        if (pose.lick > .04f && (grooming || sitting)) {
            val amount = pose.lick
            val x = face.x + headX + .08f * roll
            drawOval(Color(0xFFCC827C).copy(alpha = opacity * amount), Offset(left + side * (x - .012f),
                top + side * (face.y + headY - .007f)), Size(side * .024f, side * (.008f + .032f * amount)))
        }
    }
}

private val neutral = CatPose()
