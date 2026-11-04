package tw.xiaoliangkou.app.ui.components

import android.content.res.Resources
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import tw.xiaoliangkou.app.R
import tw.xiaoliangkou.app.core.Pet
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** 小貓的心情，也用來決定身邊的心情小插圖。 */
enum class CatFace { OK, HAPPY, HUNGRY, SAD, DIRTY, SLEEPING, EATING, PURRING, PLAYING }

fun catFace(mood: Pet.Mood) = when (mood) {
    Pet.Mood.HUNGRY -> CatFace.HUNGRY
    Pet.Mood.DIRTY -> CatFace.DIRTY
    Pet.Mood.LONELY -> CatFace.SAD
    Pet.Mood.HAPPY -> CatFace.HAPPY
    Pet.Mood.OK -> CatFace.OK
    Pet.Mood.SLEEPING -> CatFace.SLEEPING
}

fun catFaceFor(prop: String?): CatFace? = when (prop) {
    "kibble", "can", "fish" -> CatFace.EATING
    "wand", "laser", "yarn" -> CatFace.PLAYING
    "bath" -> CatFace.SAD
    "brush", "pet" -> CatFace.PURRING
    else -> null
}

/**
 * 十五種毛色都使用透明底手繪風 PNG，不以 emoji、像素畫或 SVG 畫貓。
 * 4 × 4 圖集的前十五格依 [Pet.coats] 排列；每格保留透明邊，縮放時不會露出鄰格。
 * 長大、照顧道具和心情提示疊在插圖周圍；[animated] 為 false 時，品種清單完全靜止。
 */
@Composable
fun PetCat(coat: String, stage: Int, face: CatFace, modifier: Modifier = Modifier, prop: String? = null, bounce: Int = 0, animated: Boolean = true) {
    val resources = LocalContext.current.resources
    val atlas = remember(resources) { CatIllustrations.atlas(resources) }
    val cell = remember(coat) { Pet.coats.indexOfFirst { it.key == coat }.coerceAtLeast(0) }
    val description = "${Pet.coat(coat).label}，${face.description()}"
    val artModifier = modifier.semantics { contentDescription = description }
    if (!animated) {
        Canvas(artModifier) { drawPet(atlas, cell, stage, face, prop, 0f, 0.25f, 0f) }
        return
    }
    val transition = rememberInfiniteTransition(label = "illustratedPet")
    val breathe by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(if (face == CatFace.SLEEPING) 3400 else 2400, easing = LinearEasing)), label = "breathe",
    )
    val play by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "care")
    val hop = remember { Animatable(0f) }
    LaunchedEffect(bounce) {
        if (bounce > 0) {
            hop.snapTo(0f)
            hop.animateTo(1f, tween(560, easing = LinearEasing))
        }
    }
    Canvas(artModifier) { drawPet(atlas, cell, stage, face, prop, hop.value, breathe, play) }
}

/** 同一張圖集只解碼一次，品種清單同時顯示十五隻時不重複佔用點陣記憶體。 */
private object CatIllustrations {
    @Volatile private var image: ImageBitmap? = null

    fun atlas(resources: Resources): ImageBitmap = image ?: synchronized(this) {
        image ?: ImageBitmap.imageResource(resources, R.drawable.cat_coats).also { image = it }
    }
}

private fun CatFace.description() = when (this) {
    CatFace.OK -> "乖乖坐著"
    CatFace.HAPPY -> "很開心"
    CatFace.HUNGRY -> "肚子餓了"
    CatFace.SAD -> "想要陪伴"
    CatFace.DIRTY -> "需要洗澡"
    CatFace.SLEEPING -> "睡著了"
    CatFace.EATING -> "正在吃飯"
    CatFace.PURRING -> "舒服地呼嚕"
    CatFace.PLAYING -> "開心玩耍"
}

private val INK = Color(0xFF725345)
private val STAGE_SCALE = floatArrayOf(0.70f, 0.79f, 0.87f, 0.95f, 1f)

/** 圖集只決定毛色，照顧行為仍由原本的 app 狀態驅動。 */
private fun DrawScope.drawPet(atlas: ImageBitmap, cell: Int, stage: Int, face: CatFace, prop: String?, hop: Float, breathe: Float, fast: Float) {
    val st = stage.coerceIn(0, 4)
    val side = minOf(size.height * 1.02f, size.width * 1.02f) * STAGE_SCALE[st]
    val cx = size.width / 2
    val floor = size.height * 0.94f
    val breath = sin(breathe * 2 * PI).toFloat()
    val jump = sin(hop * PI).toFloat() * side * 0.11f
    // 正規化圖集的腳掌落在 286/320；整隻（包含右側尾巴）置中，頭略偏左。
    val top = floor - side * (286f / 320f) - jump
    val left = cx - side / 2
    val r = side * 0.23f
    val c = Offset(cx - side * 0.04f, top + side * 0.38f)
    drawOval(Color(0x167C5A49), Offset(cx - side * 0.28f, floor - side * 0.035f), Size(side * 0.56f * (1f - jump / side), side * 0.07f))
    if (prop == "laser") laserDot(Offset(cx + sin(fast * 2 * PI).toFloat() * side * 0.34f, floor - side * 0.03f), r)
    val cellW = atlas.width / 4
    val cellH = atlas.height / 4
    // 留一個來源像素，避免高品質取樣讀到鄰格。
    val source = IntOffset(cell % 4 * cellW + 1, cell / 4 * cellH + 1)
    val sourceSize = IntSize((cellW - 2).coerceAtLeast(1), (cellH - 2).coerceAtLeast(1))
    val pivot = Offset(cx, floor - jump)
    rotate(if (face == CatFace.PLAYING) sin(fast * 2 * PI).toFloat() * 2.4f else 0f, pivot) {
        scale(1f + breath * 0.008f, 1f + breath * 0.014f, pivot) {
            drawImage(atlas, source, sourceSize, IntOffset(left.roundToInt(), top.roundToInt()), IntSize(side.roundToInt().coerceAtLeast(1), side.roundToInt().coerceAtLeast(1)), filterQuality = FilterQuality.High)
            accessories(c, r, st, top + side * 0.60f, side * 0.55f)
            if (face == CatFace.DIRTY) dirt(Offset(cx, top + side * 0.56f), side * 0.5f, side * 0.25f, r)
        }
    }
    when (prop) {
        "kibble", "can", "fish" -> bowl(prop, Offset(cx, floor), r, fast)
        "wand" -> wand(c, r * 0.85f, fast, size.width)
        "yarn" -> yarn(Offset(cx - side * 0.29f + sin(fast * 2 * PI).toFloat() * side * 0.03f, floor - r * 0.25f), r, fast)
        "bath" -> bubbles(c, r, fast, floor)
        "brush" -> brush(Offset(cx + side * 0.20f, top + side * 0.60f), r, fast)
    }
    if (face == CatFace.SLEEPING) {
        zzz(Offset(cx + side * 0.20f, top + side * 0.18f), r, breathe)
    } else if (face != CatFace.OK) {
        moodIllustration(face, Offset(cx + side * 0.29f, top + side * 0.23f), side * 0.075f, fast)
    }
}

/** 小小的心情雲朵，不覆蓋手繪貓咪的五官。 */
private fun DrawScope.moodIllustration(face: CatFace, at: Offset, r: Float, phase: Float) {
    val y = at.y + sin(phase * 2 * PI).toFloat() * r * 0.14f
    val c = Offset(at.x, y)
    val ink = INK.copy(alpha = 0.82f)
    val stroke = Stroke(r * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawCircle(Color(0xF2FFF9F2), r * 1.14f, c)
    drawCircle(Color(0x24725345), r * 1.14f, c, style = Stroke(r * 0.06f))
    drawCircle(Color(0xF2FFF9F2), r * 0.23f, Offset(c.x - r * 0.9f, c.y + r * 1.1f))
    when (face) {
        CatFace.HUNGRY, CatFace.EATING -> {
            val bowl = Path().apply {
                moveTo(c.x - r * 0.62f, c.y - r * 0.05f)
                quadraticTo(c.x - r * 0.50f, c.y + r * 0.68f, c.x, c.y + r * 0.58f)
                quadraticTo(c.x + r * 0.50f, c.y + r * 0.68f, c.x + r * 0.62f, c.y - r * 0.05f)
                close()
            }
            drawPath(bowl, Color(0xFFEBA99C))
            drawPath(bowl, ink, style = stroke)
            listOf(-0.3f, 0f, 0.3f).forEach { x -> drawCircle(Color(0xFFBA875B), r * 0.14f, Offset(c.x + x * r, c.y - r * 0.08f)) }
        }
        CatFace.SAD -> {
            val drop = Path().apply {
                moveTo(c.x, c.y - r * 0.67f)
                cubicTo(c.x - r * 0.85f, c.y + r * 0.12f, c.x - r * 0.45f, c.y + r * 0.66f, c.x, c.y + r * 0.66f)
                cubicTo(c.x + r * 0.45f, c.y + r * 0.66f, c.x + r * 0.85f, c.y + r * 0.12f, c.x, c.y - r * 0.67f)
                close()
            }
            drawPath(drop, Color(0xFFABD0DA))
            drawPath(drop, ink, style = stroke)
            drawOval(Color.White.copy(alpha = 0.8f), Offset(c.x - r * 0.30f, c.y + r * 0.12f), Size(r * 0.18f, r * 0.30f))
        }
        CatFace.DIRTY -> {
            listOf(-0.4f to 0.15f, 0.1f to -0.35f, 0.43f to 0.28f).forEach { (x, y) ->
                drawCircle(Color(0xFFAC8A68), r * 0.27f, Offset(c.x + x * r, c.y + y * r))
            }
        }
        CatFace.PLAYING -> {
            val star = Path().apply {
                moveTo(c.x, c.y - r * 0.72f)
                quadraticTo(c.x + r * 0.10f, c.y - r * 0.14f, c.x + r * 0.66f, c.y)
                quadraticTo(c.x + r * 0.10f, c.y + r * 0.14f, c.x, c.y + r * 0.72f)
                quadraticTo(c.x - r * 0.10f, c.y + r * 0.14f, c.x - r * 0.66f, c.y)
                quadraticTo(c.x - r * 0.10f, c.y - r * 0.14f, c.x, c.y - r * 0.72f)
                close()
            }
            drawPath(star, Color(0xFFF3D083))
            drawPath(star, ink, style = stroke)
        }
        else -> {
            val heart = heartPath(c.x, c.y + r * 0.14f, r * 1.55f)
            drawPath(heart, Color(0xFFEBA99C))
            drawPath(heart, ink, style = stroke)
        }
    }
}

/** 長大的標誌：幼貓戴鈴鐺項圈、少年貓多一個蝴蝶結、成貓圍上圍巾、大胖貓戴皇冠 */
private fun DrawScope.accessories(c: Offset, r: Float, stage: Int, bodyTop: Float, bw: Float) {
    if (stage in 1..2 || stage == 4) {
        val y = bodyTop + r * 0.04f
        drawArc(Color(0xFFE6A39D), 20f, 140f, false, Offset(c.x - bw * 0.32f, y - r * 0.18f), Size(bw * 0.64f, r * 0.3f), style = Stroke(width = r * 0.1f, cap = StrokeCap.Round))
        drawCircle(Color(0xFFEAC577), r * 0.1f, Offset(c.x, y + r * 0.14f))
        drawLine(Color(0xFFA8884E), Offset(c.x - r * 0.06f, y + r * 0.15f), Offset(c.x + r * 0.06f, y + r * 0.15f), r * 0.025f, StrokeCap.Round)
    }
    if (stage == 3) {
        val y = bodyTop + r * 0.02f
        val scarf = Path().apply {
            moveTo(c.x - bw * 0.34f, y - r * 0.02f)
            quadraticTo(c.x, y + r * 0.2f, c.x + bw * 0.34f, y - r * 0.02f)
            lineTo(c.x + bw * 0.12f, y + r * 0.42f)
            lineTo(c.x - bw * 0.02f, y + r * 0.16f)
            close()
        }
        drawPath(scarf, Color(0xFFADCBD1))
        drawCircle(Color.White.copy(alpha = 0.8f), r * 0.035f, Offset(c.x + bw * 0.08f, y + r * 0.2f))
        drawCircle(Color.White.copy(alpha = 0.8f), r * 0.035f, Offset(c.x - bw * 0.12f, y + r * 0.1f))
    }
    if (stage == 2) {
        // 右耳旁的粉紅蝴蝶結
        val bx = c.x + r * 0.90f
        val by = c.y - r * 0.82f
        val left = Path().apply { moveTo(bx, by); lineTo(bx - r * 0.24f, by - r * 0.14f); lineTo(bx - r * 0.24f, by + r * 0.14f); close() }
        val right = Path().apply { moveTo(bx, by); lineTo(bx + r * 0.24f, by - r * 0.14f); lineTo(bx + r * 0.24f, by + r * 0.14f); close() }
        drawPath(left, Color(0xFFE8AEA7)); drawPath(right, Color(0xFFE8AEA7))
        drawPath(left, Color(0xFFE8AEA7), style = Stroke(width = r * 0.06f, join = StrokeJoin.Round))
        drawPath(right, Color(0xFFE8AEA7), style = Stroke(width = r * 0.06f, join = StrokeJoin.Round))
        drawCircle(Color(0xFFBD7F7E), r * 0.07f, Offset(bx, by))
    }
    if (stage == 4) {
        val y = c.y - r * 0.8f
        val crown = Path().apply {
            moveTo(c.x - r * 0.36f, y)
            lineTo(c.x - r * 0.4f, y - r * 0.34f)
            lineTo(c.x - r * 0.18f, y - r * 0.16f)
            lineTo(c.x, y - r * 0.42f)
            lineTo(c.x + r * 0.18f, y - r * 0.16f)
            lineTo(c.x + r * 0.4f, y - r * 0.34f)
            lineTo(c.x + r * 0.36f, y)
            close()
        }
        drawPath(crown, Color(0xFFF0CE83))
        drawPath(crown, Color(0xFFBF9A58), style = Stroke(width = r * 0.035f, join = StrokeJoin.Round))
        drawCircle(Color(0xFFE6A39D), r * 0.06f, Offset(c.x, y - r * 0.12f))
    }
}

/** 髒髒的：身上幾塊泥巴 */
private fun DrawScope.dirt(top: Offset, bw: Float, bh: Float, r: Float) {
    val mud = Color(0xFF9C7A5B).copy(alpha = 0.55f)
    drawOval(mud, Offset(top.x - bw * 0.38f, top.y + bh * 0.3f), Size(r * 0.3f, r * 0.18f))
    drawOval(mud, Offset(top.x + bw * 0.18f, top.y + bh * 0.55f), Size(r * 0.24f, r * 0.15f))
    drawOval(mud, Offset(top.x - bw * 0.05f, top.y + bh * 0.62f), Size(r * 0.16f, r * 0.1f))
}

private fun DrawScope.bowl(item: String, floor: Offset, r: Float, fast: Float) {
    val w = r * 1.25f
    val top = floor.y - r * 0.42f
    if (item == "can") {
        // 打開的罐頭
        drawRect(Color(0xFFC9D2DE), Offset(floor.x - w * 0.32f, top), Size(w * 0.64f, r * 0.4f))
        drawRect(Color(0xFFADCBD1), Offset(floor.x - w * 0.32f, top + r * 0.1f), Size(w * 0.64f, r * 0.16f))
        drawOval(Color(0xFFE3E9F0), Offset(floor.x - w * 0.32f, top - r * 0.08f), Size(w * 0.64f, r * 0.16f))
        drawOval(Color(0xFFF2A3A0), Offset(floor.x - w * 0.27f, top - r * 0.05f), Size(w * 0.54f, r * 0.1f))
        return
    }
    val bowl = Path().apply {
        moveTo(floor.x - w / 2, top + r * 0.06f)
        lineTo(floor.x + w / 2, top + r * 0.06f)
        lineTo(floor.x + w * 0.38f, floor.y - r * 0.02f)
        lineTo(floor.x - w * 0.38f, floor.y - r * 0.02f)
        close()
    }
    if (item == "kibble") {
        listOf(-0.3f, -0.12f, 0.06f, 0.24f, -0.2f, 0.14f).forEachIndexed { i, dx ->
            val hop = if (i == (fast * 6).toInt() % 6) r * 0.05f else 0f
            drawCircle(Color(0xFFB0703B), r * 0.07f, Offset(floor.x + dx * w, top + (if (i > 3) -r * 0.08f else 0f) - hop))
        }
    } else {
        // 小魚乾
        val fx = floor.x
        val fy = top - r * 0.04f
        drawOval(Color(0xFFB8C7D9), Offset(fx - r * 0.3f, fy - r * 0.09f), Size(r * 0.5f, r * 0.18f))
        val tail = Path().apply { moveTo(fx + r * 0.16f, fy); lineTo(fx + r * 0.34f, fy - r * 0.1f); lineTo(fx + r * 0.34f, fy + r * 0.1f); close() }
        drawPath(tail, Color(0xFFB8C7D9))
        drawCircle(INK, r * 0.025f, Offset(fx - r * 0.2f, fy - r * 0.02f))
    }
    drawPath(bowl, Color(0xFFE6ACA8))
    drawLine(Color.White.copy(alpha = 0.7f), Offset(floor.x - w * 0.42f, top + r * 0.16f), Offset(floor.x + w * 0.42f, top + r * 0.16f), r * 0.05f)
    drawCircle(Color.White.copy(alpha = 0.85f), r * 0.05f, Offset(floor.x - w * 0.12f, top + r * 0.26f))
    drawCircle(Color.White.copy(alpha = 0.85f), r * 0.05f, Offset(floor.x + w * 0.12f, top + r * 0.26f))
}

private fun DrawScope.wand(head: Offset, r: Float, fast: Float, width: Float) {
    val swing = sin(fast * 4 * PI).toFloat() * r * 0.5f
    val handle = Offset(width * 0.97f, head.y - r * 1.6f)
    val tip = Offset(head.x + r * 1.2f, head.y - r * 1.75f)
    drawLine(Color(0xFFB98A5E), handle, tip, r * 0.06f, StrokeCap.Round)
    val feather = Offset(tip.x - r * 0.3f + swing, tip.y + r * 0.6f + abs(swing) * 0.2f)
    drawLine(INK.copy(alpha = 0.5f), tip, feather, r * 0.02f)
    listOf(Color(0xFFE8AEA7) to -25f, Color(0xFFEAC577) to 0f, Color(0xFFADCBD1) to 25f).forEach { (col, deg) ->
        rotate(deg + swing / r * 20f, feather) {
            drawOval(col, Offset(feather.x - r * 0.06f, feather.y), Size(r * 0.12f, r * 0.36f))
        }
    }
}

private fun DrawScope.laserDot(at: Offset, r: Float) {
    drawCircle(Color(0x55FF2D2D), r * 0.14f, at)
    drawCircle(Color(0xFFFF2D2D), r * 0.06f, at)
    drawCircle(Color.White.copy(alpha = 0.8f), r * 0.02f, at)
}

private fun DrawScope.yarn(at: Offset, r: Float, fast: Float) {
    val rr = r * 0.32f
    drawCircle(Color(0xFFC7B8D6), rr, at)
    rotate(fast * 360f, at) {
        listOf(-0.5f, 0f, 0.5f).forEach { k ->
            drawArc(Color(0xFFA18CAF), 200f, 140f, false, Offset(at.x - rr * (1f - abs(k) * 0.3f), at.y - rr * (0.8f - k)), Size(rr * 2f * (1f - abs(k) * 0.3f), rr * 1.6f), style = Stroke(width = r * 0.035f))
        }
    }
    val string = Path().apply {
        moveTo(at.x + rr * 0.8f, at.y + rr * 0.5f)
        quadraticTo(at.x + rr * 2f, at.y + rr * 1.2f, at.x + rr * 2.8f, at.y + rr * 0.6f)
    }
    drawPath(string, Color(0xFFA18CAF), style = Stroke(width = r * 0.03f, cap = StrokeCap.Round))
}

private fun DrawScope.bubbles(c: Offset, r: Float, fast: Float, floor: Float) {
    // 頭上的泡沫
    listOf(-0.4f to -0.9f, -0.1f to -1.0f, 0.25f to -0.95f, 0.5f to -0.82f).forEach { (dx, dy) ->
        drawCircle(Color.White, r * 0.16f, Offset(c.x + dx * r, c.y + dy * r))
        drawCircle(Color(0xFFBFE3FF), r * 0.16f, Offset(c.x + dx * r, c.y + dy * r), style = Stroke(width = r * 0.02f))
    }
    // 往上飄的泡泡
    for (i in 0 until 8) {
        val phase = (fast + i / 8f) % 1f
        val x = c.x + cos(i * 2.1).toFloat() * r * (1.3f + (i % 3) * 0.25f) + sin(phase * 2 * PI).toFloat() * r * 0.1f
        val y = floor - phase * (floor - (c.y - r * 1.6f))
        val size = r * (0.08f + (i % 3) * 0.04f)
        drawCircle(Color(0xFFBFE3FF).copy(alpha = 0.35f * (1f - phase) + 0.1f), size, Offset(x, y))
        drawCircle(Color.White.copy(alpha = 0.9f * (1f - phase)), size, Offset(x, y), style = Stroke(width = r * 0.02f))
    }
}

private fun DrawScope.brush(at: Offset, r: Float, fast: Float) {
    val x = at.x + sin(fast * 4 * PI).toFloat() * r * 0.18f
    rotate(-30f, Offset(x, at.y)) {
        drawRect(Color(0xFFC08A5B), Offset(x + r * 0.12f, at.y - r * 0.05f), Size(r * 0.5f, r * 0.1f))
        drawRect(Color(0xFFE7BEB6), Offset(x - r * 0.2f, at.y - r * 0.12f), Size(r * 0.34f, r * 0.24f))
        for (i in 0 until 4) drawLine(Color(0xFFBD7F7E), Offset(x - r * 0.16f + i * r * 0.09f, at.y + r * 0.12f), Offset(x - r * 0.16f + i * r * 0.09f, at.y + r * 0.2f), r * 0.025f)
    }
    // 亮晶晶
    listOf(-0.4f to -0.3f, 0.3f to -0.5f).forEachIndexed { i, (dx, dy) ->
        val s = r * (0.06f + 0.04f * sin((fast + i * 0.5f) * 2 * PI).toFloat().coerceAtLeast(0f))
        val p = Offset(at.x + dx * r, at.y + dy * r)
        val star = Path().apply {
            moveTo(p.x, p.y - s * 2); lineTo(p.x + s * 0.5f, p.y - s * 0.5f); lineTo(p.x + s * 2, p.y); lineTo(p.x + s * 0.5f, p.y + s * 0.5f)
            lineTo(p.x, p.y + s * 2); lineTo(p.x - s * 0.5f, p.y + s * 0.5f); lineTo(p.x - s * 2, p.y); lineTo(p.x - s * 0.5f, p.y - s * 0.5f); close()
        }
        drawPath(star, Color(0xFFFFD45E))
    }
}

/** 睡覺時飄起來的 Z */
private fun DrawScope.zzz(at: Offset, r: Float, phase: Float) {
    for (i in 0 until 3) {
        val p = (phase + i / 3f) % 1f
        val s = r * (0.12f + i * 0.04f)
        val o = Offset(at.x + p * r * 0.4f + i * r * 0.18f, at.y - p * r * 0.6f - i * r * 0.22f)
        val z = Path().apply {
            moveTo(o.x - s / 2, o.y - s / 2); lineTo(o.x + s / 2, o.y - s / 2); lineTo(o.x - s / 2, o.y + s / 2); lineTo(o.x + s / 2, o.y + s / 2)
        }
        drawPath(z, Color(0xFF9C8CD9).copy(alpha = 1f - p * 0.7f), style = Stroke(width = r * 0.045f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
