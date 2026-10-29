package tw.xiaoliangkou.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import tw.xiaoliangkou.app.core.Pet
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** 小貓的表情 */
enum class CatFace { OK, HAPPY, HUNGRY, SAD, DIRTY, SLEEPING, EATING, PURRING, PLAYING }

/** 牠現在的心情 → 表情 */
fun catFace(mood: Pet.Mood) = when (mood) {
    Pet.Mood.HUNGRY -> CatFace.HUNGRY
    Pet.Mood.DIRTY -> CatFace.DIRTY
    Pet.Mood.LONELY -> CatFace.SAD
    Pet.Mood.HAPPY -> CatFace.HAPPY
    Pet.Mood.OK -> CatFace.OK
    Pet.Mood.SLEEPING -> CatFace.SLEEPING
}

/** 照顧時拿出來的東西 → 表情（吃飯閉眼嚼嚼、玩的時候眼睛睜大、洗澡有點不高興、梳毛很舒服） */
fun catFaceFor(prop: String?): CatFace? = when (prop) {
    "kibble", "can", "fish" -> CatFace.EATING
    "wand", "laser", "yarn" -> CatFace.PLAYING
    "bath" -> CatFace.SAD
    "brush", "pet" -> CatFace.PURRING
    else -> null
}

/**
 * 一起養的小貓：[coat] 毛色（見 [Pet.coats]）、[stage] 0～4（小奶貓到大胖貓：越大越圓，還會戴上項圈、蝴蝶結、圍巾、皇冠）、[face] 表情。
 * 會呼吸、眨眼、甩尾巴、抖耳朵；[prop] 是正在做的照顧（碗、罐頭、小魚乾、逗貓棒、雷射筆、毛線球、泡泡、梳子），[bounce] 每加一就跳一下。
 * 貓坐在畫布的正中間、腳踩在畫布下緣。[animated] 是 false 時不會動（一次列出很多隻的時候，例如選品種）
 */
@Composable
fun PetCat(coat: String, stage: Int, face: CatFace, modifier: Modifier = Modifier, prop: String? = null, bounce: Int = 0, animated: Boolean = true) {
    if (!animated) {
        val colors = coatColors(coat)
        Canvas(modifier) { drawPet(colors, stage, face, prop, 0f, 0.25f, 1f, 0.25f, 0f, 0f) }
        return
    }
    val t = rememberInfiniteTransition(label = "pet")
    val breathe by t.animateFloat(0f, 1f, infiniteRepeatable(tween(if (face == CatFace.SLEEPING) 3400 else 2200, easing = LinearEasing)), label = "breathe")
    val blink by t.animateFloat(
        1f, 1f,
        infiniteRepeatable(keyframes { durationMillis = 4200; 1f at 0; 1f at 3800; 0.1f at 3900; 1f at 4000 }),
        label = "blink",
    )
    val tail by t.animateFloat(0f, 1f, infiniteRepeatable(tween(if (face == CatFace.PLAYING) 900 else 2600, easing = LinearEasing)), label = "tail")
    val ear by t.animateFloat(
        0f, 0f,
        infiniteRepeatable(keyframes { durationMillis = 5600; 0f at 0; 0f at 5000; 1f at 5100; 0f at 5200; 1f at 5300; 0f at 5400 }),
        label = "ear",
    )
    val fast by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "fast")
    val hop = remember { Animatable(0f) }
    LaunchedEffect(bounce) {
        if (bounce > 0) {
            hop.snapTo(0f)
            hop.animateTo(1f, tween(560, easing = LinearEasing))
        }
    }
    val colors = coatColors(coat)
    Canvas(modifier) { drawPet(colors, stage, face, prop, hop.value, breathe, blink, tail, ear, fast) }
}

/** 畫整個畫面：影子、照顧的東西、小貓、睡覺的 Z（動畫的值從外面給） */
private fun DrawScope.drawPet(colors: CoatColors, stage: Int, face: CatFace, prop: String?, hop: Float, breathe: Float, blink: Float, tail: Float, ear: Float, fast: Float) {
    val st = stage.coerceIn(0, 4)
    // 左邊到頭和鬍鬚、右邊到尾巴：整隻放得進畫布，左右置中
    val left = maxOf(1.22f, BODY_W[st] / 2)
    val right = BODY_W[st] / 2 + 1.12f
    val r = minOf(size.height * 0.335f * STAGE_SCALE[st], size.width * 0.96f / (left + right))
    val cx = size.width / 2 + (left - right) / 2 * r
    val floor = size.height * 0.97f
    val jump = sin(hop * PI).toFloat() * r * 0.45f
    val breath = sin(breathe * 2 * PI).toFloat()
    val bodyH = BODY_H[st] * r
    val c = Offset(cx, floor - r * 0.46f - bodyH - jump + breath * r * 0.02f)
    // 地上的影子：跳起來時變小
    val shadowW = r * BODY_W[st] * 1.25f * (1f - jump / (r * 1.2f))
    drawOval(Color(0x22000000), Offset(cx - shadowW / 2, floor - r * 0.1f), Size(shadowW, r * 0.2f))
    if (prop == "laser") laserDot(Offset(cx + sin(fast * 2 * PI).toFloat() * r * 1.6f, floor - r * 0.12f), r)
    petKitty(c, r, st, colors, face, blink, sin(tail * 2 * PI).toFloat(), ear, breath, fast)
    when (prop) {
        "kibble", "can", "fish" -> bowl(prop, Offset(cx, floor), r, fast)
        "wand" -> wand(Offset(cx, c.y), r, fast, size.width)
        "yarn" -> yarn(Offset(maxOf(r * 0.34f, cx - r * BODY_W[st] * 0.5f - r * 0.3f) + sin(fast * 2 * PI).toFloat() * r * 0.18f, floor - r * 0.32f), r, fast)
        "bath" -> bubbles(c, r, fast, floor)
        "brush" -> brush(Offset(cx + r * BODY_W[st] * 0.42f, c.y + r * 0.42f + bodyH * 0.45f), r, fast)
    }
    if (face == CatFace.SLEEPING) zzz(Offset(c.x + r * 0.95f, c.y - r * 1.1f), r, breathe)
}

/** 頭上、身上的一塊花色：位置和大小是相對頭（或身體）的外框，0～1 */
private class Spot(val color: Color, val onHead: Boolean, val x: Float, val y: Float, val w: Float, val h: Float)

/**
 * 一種貓的樣子：
 * - [body] 毛色、[light] 胸前、嘴邊、腳掌的淺色、[outline] 淺色的貓描一圈邊（不然在淺色的底上看不清楚）
 * - [stripe] 虎斑的顏色、[stripes] 身上一邊幾條
 * - [spots] 一塊一塊的花色（三花、玳瑁、乳牛）、[leftEar]、[rightEar] 耳朵另外的顏色、[tail] 尾巴的顏色
 * - [points] 重點色（暹羅、布偶）：臉中間、耳朵、腳和尾巴深一點
 * - [blaze] 額頭一道白、嘴巴整片白（賓士、橘白）
 * - [iris] 眼睛的顏色：null 是深色的大眼睛；深色的貓（[dark]）用淺色的線
 * - [fold] 摺耳、[ruff] 胸前蓬蓬的毛、[round] 圓圓的大臉頰
 */
private class CoatColors(
    val body: Color,
    val stripe: Color?,
    val light: Color,
    val outline: Color?,
    val dark: Boolean = false,
    val spots: List<Spot> = emptyList(),
    val leftEar: Color? = null,
    val rightEar: Color? = null,
    val tail: Color? = null,
    val points: Color? = null,
    val blaze: Boolean = false,
    val iris: Color? = if (dark) IRIS else null,
    val stripes: Int = 2,
    val fold: Boolean = false,
    val ruff: Boolean = false,
    val round: Boolean = false,
) {
    /** 臉的中間是深色的（暹羅）：嘴巴、鬍鬚也要用淺色的線才看得到 */
    private val darkMask get() = points != null && points.luminance() < 0.25f
    val line get() = if (dark || darkMask) Color(0xFFF4E9EF) else INK
}

private val INK = Color(0xFF4A2B39)
private val CHEEK = Color(0xFFFF9EB8)
private val NOSE = Color(0xFFFF7FA0)
private val TONGUE = Color(0xFFE5607F)
private val IRIS = Color(0xFFE8D66A)

private val ORANGE = Color(0xFFF2A65A)
private val CHARCOAL = Color(0xFF4D4450)
private val BLUE_EYE = Color(0xFF6DB5F2)
private val COPPER_EYE = Color(0xFFF0A63C)

private fun coatColors(key: String) = when (key) {
    "gray" -> CoatColors(Color(0xFFAAB4C2), Color(0xFF808B9C), Color(0xFFF0F3F7), null)
    "white" -> CoatColors(Color(0xFFFFFCF9), null, Color(0xFFFFFFFF), Color(0xFFE6D7CF))
    "black" -> CoatColors(Color(0xFF3F3A47), null, Color(0xFF575060), null, dark = true)
    "calico" -> CoatColors(
        Color(0xFFFFF8F0), null, Color(0xFFFFFFFF), Color(0xFFE9DCCF), leftEar = ORANGE, rightEar = CHARCOAL, tail = ORANGE,
        spots = listOf(
            Spot(ORANGE, true, -0.1f, -0.1f, 0.5f, 0.58f), Spot(CHARCOAL, true, 0.67f, -0.08f, 0.43f, 0.47f),
            Spot(ORANGE, false, -0.12f, 0.2f, 0.45f, 0.6f), Spot(CHARCOAL, false, 0.72f, 0.05f, 0.4f, 0.45f),
        ),
    )
    "tuxedo" -> CoatColors(Color(0xFF3F3A47), null, Color(0xFFFFFFFF), null, dark = true, blaze = true)
    // 虎斑貓：咖啡色、深色的條紋多一點
    "tabby" -> CoatColors(Color(0xFFB8875A), Color(0xFF6E4A2E), Color(0xFFF3E1CC), null, stripes = 3)
    // 美國短毛貓：銀白色、黑色的粗條紋
    "silver" -> CoatColors(Color(0xFFD9DDE3), Color(0xFF4A4F5A), Color(0xFFF7F8FA), Color(0xFFC3C8D0), stripes = 3, iris = Color(0xFF7CC47F))
    // 暹羅貓：奶油色，臉、耳朵、腳、尾巴是深咖啡色，藍眼睛
    "siamese" -> CoatColors(Color(0xFFF5E6CF), null, Color(0xFFFBF3E7), Color(0xFFE6D3B8), points = Color(0xFF5C4033), iris = BLUE_EYE)
    // 布偶貓：白色長毛、淡淡的灰咖啡色重點、胸前蓬蓬的，藍眼睛
    "ragdoll" -> CoatColors(Color(0xFFFBF7F2), null, Color(0xFFFFFFFF), Color(0xFFE8DED4), points = Color(0xFF9C8676), iris = BLUE_EYE, ruff = true)
    // 英國短毛貓：藍灰色、大圓臉、橘色眼睛
    "british" -> CoatColors(Color(0xFF8E9AAB), null, Color(0xFFA9B4C3), null, iris = COPPER_EYE, round = true)
    // 摺耳貓：奶油色虎斑、耳朵摺下來、圓臉
    "fold" -> CoatColors(Color(0xFFF1D9B5), Color(0xFFD4A574), Color(0xFFFBF1E2), Color(0xFFE5CBA6), fold = true, round = true, iris = COPPER_EYE)
    // 玳瑁貓：黑色和橘色混在一起
    "tortie" -> CoatColors(
        Color(0xFF3F3A47), null, Color(0xFF5A4A44), null, dark = true, leftEar = Color(0xFFD9893F), tail = Color(0xFF3F3A47),
        spots = listOf(
            Spot(Color(0xFFD9893F), true, -0.05f, 0.0f, 0.42f, 0.5f), Spot(Color(0xFFB8682E), true, 0.62f, 0.45f, 0.3f, 0.35f),
            Spot(Color(0xFFD9893F), false, 0.55f, 0.15f, 0.42f, 0.45f), Spot(Color(0xFFB8682E), false, 0.0f, 0.5f, 0.35f, 0.4f),
        ),
    )
    // 乳牛貓：白底、大塊的黑色
    "cow" -> CoatColors(
        Color(0xFFFFFCF9), null, Color(0xFFFFFFFF), Color(0xFFE6D7CF), rightEar = CHARCOAL, tail = CHARCOAL,
        spots = listOf(
            Spot(CHARCOAL, true, 0.6f, -0.15f, 0.5f, 0.6f), Spot(CHARCOAL, true, 0.05f, 0.05f, 0.22f, 0.22f),
            Spot(CHARCOAL, false, -0.1f, 0.05f, 0.42f, 0.5f), Spot(CHARCOAL, false, 0.7f, 0.5f, 0.38f, 0.42f),
        ),
    )
    // 橘白貓：橘色、額頭到嘴巴和胸前是白的
    "gingerwhite" -> CoatColors(Color(0xFFF7A85C), Color(0xFFE07F33), Color(0xFFFFFFFF), null, blaze = true)
    else -> CoatColors(Color(0xFFF7A85C), Color(0xFFE07F33), Color(0xFFFFE6CC), null)
}

/** 花色畫在 [clip]（頭或身體）裡面 */
private fun DrawScope.spots(k: CoatColors, head: Boolean, left: Float, top: Float, w: Float, h: Float) {
    k.spots.filter { it.onHead == head }.forEach { s -> drawOval(s.color, Offset(left + s.x * w, top + s.y * h), Size(s.w * w, s.h * h)) }
}

/** 小奶貓到大胖貓：整隻的大小、身體的寬和高（以頭的半徑為單位） */
private val STAGE_SCALE = floatArrayOf(0.66f, 0.76f, 0.86f, 0.95f, 1f)
private val BODY_W = floatArrayOf(1.25f, 1.45f, 1.56f, 1.72f, 2.2f)
private val BODY_H = floatArrayOf(0.88f, 0.98f, 1.05f, 1.15f, 1.25f)

/** 一隻面向前面坐著的小貓：[c] 是頭的中心、[r] 是頭的半徑 */
private fun DrawScope.petKitty(c: Offset, r: Float, stage: Int, k: CoatColors, face: CatFace, blink: Float, swish: Float, ear: Float, breath: Float, fast: Float) {
    val bodyW = BODY_W[stage] * r
    val bodyH = BODY_H[stage] * r
    val bodyTop = c.y + r * 0.42f
    val outline = k.outline?.let { Stroke(width = r * 0.045f, join = StrokeJoin.Round) }
    // 尾巴：從右邊往外再往上捲，會甩
    val tailPath = Path().apply {
        moveTo(c.x + bodyW * 0.32f, bodyTop + bodyH * 0.85f)
        cubicTo(
            c.x + bodyW * 0.5f + r * 0.7f, bodyTop + bodyH,
            c.x + bodyW * 0.5f + r * (0.95f + swish * 0.12f), bodyTop + bodyH * 0.5f,
            c.x + bodyW * 0.5f + r * (0.75f + swish * 0.28f), bodyTop + bodyH * 0.08f,
        )
    }
    if (k.outline != null) drawPath(tailPath, k.outline, style = Stroke(width = r * 0.34f, cap = StrokeCap.Round))
    drawPath(tailPath, k.points ?: k.tail ?: k.body, style = Stroke(width = r * 0.27f, cap = StrokeCap.Round))
    if (k.stripe != null) {
        // 尾巴尖深一點
        val tip = Offset(c.x + bodyW * 0.5f + r * (0.75f + swish * 0.28f), bodyTop + bodyH * 0.08f)
        drawCircle(k.stripe, r * 0.135f, tip)
    }
    // 身體：呼吸時微微變寬
    val bw = bodyW * (1f + breath * 0.015f)
    val bodyRect = Offset(c.x - bw / 2, bodyTop)
    val bodyPath = Path().apply { addOval(androidx.compose.ui.geometry.Rect(bodyRect, Size(bw, bodyH))) }
    drawPath(bodyPath, k.body)
    clipPath(bodyPath) {
        spots(k, head = false, c.x - bw / 2, bodyTop, bw, bodyH)
        if (k.stripe != null) listOf(-1f, 1f).forEach { side ->
            (0 until k.stripes).map { 0.25f + it * 0.2f }.forEach { y ->
                drawLine(k.stripe, Offset(c.x + side * bw * 0.5f, bodyTop + bodyH * y), Offset(c.x + side * bw * 0.36f, bodyTop + bodyH * (y + 0.06f)), r * 0.07f, StrokeCap.Round)
            }
        }
        // 胸前白白的一塊（橘白、賓士的大一點）
        val chestW = if (k.blaze) 0.6f else 0.48f
        drawOval(k.light, Offset(c.x - bw * chestW / 2, bodyTop + bodyH * 0.1f), Size(bw * chestW, bodyH * 0.76f))
    }
    // 布偶貓：胸前蓬蓬的毛（一圈小半圓）
    if (k.ruff) {
        for (i in 0 until 5) {
            val x = c.x + (i - 2) * r * 0.2f
            drawCircle(k.light, r * 0.16f, Offset(x, bodyTop + r * (0.08f + (if (i % 2 == 0) 0.04f else 0f))))
        }
    }
    if (outline != null) drawPath(bodyPath, k.outline!!, style = outline)
    if (face == CatFace.DIRTY) dirt(Offset(c.x, bodyTop), bw, bodyH, r)
    // 前腳
    listOf(-1f, 1f).forEach { side ->
        val px = c.x + side * bw * 0.2f
        val pawTop = bodyTop + bodyH - r * 0.23f
        val paw = k.points?.let { lerp(it, k.body, 0.35f) } ?: k.light
        drawOval(paw, Offset(px - r * 0.22f, pawTop), Size(r * 0.44f, r * 0.27f))
        if (outline != null) drawOval(k.outline!!, Offset(px - r * 0.22f, pawTop), Size(r * 0.44f, r * 0.27f), style = outline)
        val toe = if ((k.dark && k.light != Color.White) || k.points != null) Color.White.copy(alpha = 0.45f) else lerp(k.light, INK, 0.25f)
        drawLine(toe, Offset(px - r * 0.05f, pawTop + r * 0.06f), Offset(px - r * 0.05f, pawTop + r * 0.18f), r * 0.03f, StrokeCap.Round)
        drawLine(toe, Offset(px + r * 0.06f, pawTop + r * 0.06f), Offset(px + r * 0.06f, pawTop + r * 0.18f), r * 0.03f, StrokeCap.Round)
    }
    // 耳朵：小奶貓的耳朵小一點；右耳偶爾抖一下
    val earSize = if (stage == 0) 0.85f else 1f
    if (k.fold) foldedEars(c, r, k, ear)
    else listOf(-1f, 1f).forEach { side ->
        val twitch = if (side > 0) ear * r * 0.12f else 0f
        val outer = Path().apply {
            moveTo(c.x + side * r * 0.9f, c.y - r * 0.3f)
            lineTo(c.x + side * (r * 0.8f + twitch), c.y - r * (0.3f + 0.95f * earSize) + twitch)
            lineTo(c.x + side * r * 0.22f, c.y - r * 0.8f)
            close()
        }
        val earColor = (if (side < 0) k.leftEar else k.rightEar) ?: k.points ?: k.body
        if (k.outline != null) drawPath(outer, k.outline, style = Stroke(width = r * 0.2f, join = StrokeJoin.Round))
        drawPath(outer, earColor)
        drawPath(outer, earColor, style = Stroke(width = r * 0.14f, join = StrokeJoin.Round))
        val inner = Path().apply {
            moveTo(c.x + side * r * 0.78f, c.y - r * 0.5f)
            lineTo(c.x + side * (r * 0.76f + twitch), c.y - r * (0.3f + 0.75f * earSize) + twitch)
            lineTo(c.x + side * r * 0.4f, c.y - r * 0.78f)
            close()
        }
        drawPath(inner, CHEEK)
    }
    // 大頭
    // 英國短毛、摺耳：臉頰圓一點、寬一點
    val hw = if (k.round) 1.14f else 1.05f
    val hh = if (k.round) 0.9f else 0.86f
    val headPath = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(c.x - r * hw, c.y - r * hh), Size(r * hw * 2, r * (hh + 0.86f)))) }
    if (k.outline != null) drawPath(headPath, k.outline, style = Stroke(width = r * 0.09f))
    drawPath(headPath, k.body)
    clipPath(headPath) {
        spots(k, head = true, c.x - r * hw, c.y - r * hh, r * hw * 2, r * (hh + 0.86f))
        // 暹羅、布偶：臉中間深一點（從眼睛到嘴巴）
        k.points?.let { p ->
            drawOval(p.copy(alpha = 0.85f), Offset(c.x - r * 0.62f, c.y - r * 0.3f), Size(r * 1.24f, r * 0.95f))
        }
        // 賓士、橘白：額頭一道白、嘴巴整片白
        if (k.blaze) {
            val blaze = Path().apply {
                moveTo(c.x - r * 0.12f, c.y - r * 0.9f); lineTo(c.x + r * 0.12f, c.y - r * 0.9f)
                lineTo(c.x + r * 0.32f, c.y + r * 0.2f); lineTo(c.x - r * 0.32f, c.y + r * 0.2f); close()
            }
            drawPath(blaze, k.light)
        }
    }
    drawOval(Color.White.copy(alpha = if (k.dark) 0.12f else 0.35f), Offset(c.x - r * 0.72f, c.y - r * 0.7f), Size(r * 0.42f, r * 0.22f))
    if (k.stripe != null) listOf(-0.22f, 0f, 0.22f).forEach { dx ->
        drawLine(k.stripe, Offset(c.x + dx * r, c.y - r * 0.82f), Offset(c.x + dx * r * 0.8f, c.y - r * (if (dx == 0f) 0.56f else 0.62f)), r * 0.08f, StrokeCap.Round)
    }
    // 小奶貓頭上翹起來的一撮毛
    if (stage == 0) {
        val curl = Path().apply {
            moveTo(c.x - r * 0.04f, c.y - r * 0.84f)
            cubicTo(c.x - r * 0.1f, c.y - r * 1.12f, c.x + r * 0.2f, c.y - r * 1.15f, c.x + r * 0.12f, c.y - r * 0.98f)
        }
        drawPath(curl, k.outline ?: lerp(k.body, INK, 0.15f), style = Stroke(width = r * 0.07f, cap = StrokeCap.Round))
    }
    // 嘴巴旁邊白白的一塊（重點色的貓是臉上的深色）
    drawOval(k.points?.let { lerp(it, k.body, 0.2f) } ?: k.light, Offset(c.x - r * 0.42f, c.y + r * 0.08f), Size(r * 0.84f, r * 0.5f))
    eyes(c, r, k, face, blink, fast)
    val blush = when (face) { CatFace.HAPPY, CatFace.PURRING, CatFace.EATING -> 0.9f; CatFace.SAD, CatFace.DIRTY -> 0.35f; else -> 0.6f }
    listOf(-1f, 1f).forEach { side ->
        drawOval(CHEEK.copy(alpha = blush), Offset(c.x + side * r * 0.7f - r * 0.16f, c.y + r * 0.18f), Size(r * 0.32f, r * 0.18f))
        listOf(0f, 1f).forEach { i ->
            drawLine(
                k.line.copy(alpha = 0.5f),
                Offset(c.x + side * r * 0.5f, c.y + r * (0.3f + i * 0.08f)),
                Offset(c.x + side * r * 1.2f, c.y + r * (0.2f + i * 0.18f)),
                r * 0.03f, StrokeCap.Round,
            )
        }
    }
    val nose = Path().apply {
        moveTo(c.x - r * 0.08f, c.y + r * 0.15f)
        lineTo(c.x + r * 0.08f, c.y + r * 0.15f)
        lineTo(c.x, c.y + r * 0.24f)
        close()
    }
    drawPath(nose, NOSE)
    drawPath(nose, NOSE, style = Stroke(width = r * 0.04f, join = StrokeJoin.Round))
    mouth(c, r, k, face, fast)
    accessories(c, r, stage, bodyTop, bw)
}

/** 摺耳貓：小小的耳朵往前摺，貼在頭上 */
private fun DrawScope.foldedEars(c: Offset, r: Float, k: CoatColors, ear: Float) {
    listOf(-1f, 1f).forEach { side ->
        val lift = if (side > 0) ear * r * 0.05f else 0f
        val flap = Path().apply {
            moveTo(c.x + side * r * 0.25f, c.y - r * 0.78f)
            quadraticTo(c.x + side * r * 0.62f, c.y - r * 1.12f - lift, c.x + side * r * 0.9f, c.y - r * 0.62f)
            quadraticTo(c.x + side * r * 0.62f, c.y - r * 0.7f, c.x + side * r * 0.25f, c.y - r * 0.78f)
            close()
        }
        if (k.outline != null) drawPath(flap, k.outline, style = Stroke(width = r * 0.12f, join = StrokeJoin.Round))
        drawPath(flap, (if (side < 0) k.leftEar else k.rightEar) ?: k.points ?: k.body)
        // 摺下來的耳尖
        drawLine(lerp(k.body, INK, 0.25f), Offset(c.x + side * r * 0.42f, c.y - r * 0.86f - lift * 0.5f), Offset(c.x + side * r * 0.78f, c.y - r * 0.68f), r * 0.035f, StrokeCap.Round)
    }
}

/** 眼睛：張開的（深色的貓是黃綠色的，暹羅、布偶是藍色的）、開心瞇起來的 ^ ^、睡著的 ︶ ︶ */
private fun DrawScope.eyes(c: Offset, r: Float, k: CoatColors, face: CatFace, blink: Float, fast: Float) {
    val eyeY = c.y - r * 0.02f
    listOf(-1f, 1f).forEach { side ->
        val ex = c.x + side * r * 0.43f
        when (face) {
            CatFace.HAPPY, CatFace.EATING, CatFace.PURRING -> {
                val p = Path().apply {
                    moveTo(ex - r * 0.14f, eyeY + r * 0.05f)
                    quadraticTo(ex, eyeY - r * 0.14f, ex + r * 0.14f, eyeY + r * 0.05f)
                }
                drawPath(p, k.line, style = Stroke(width = r * 0.06f, cap = StrokeCap.Round))
            }
            CatFace.SLEEPING -> {
                val p = Path().apply {
                    moveTo(ex - r * 0.14f, eyeY)
                    quadraticTo(ex, eyeY + r * 0.12f, ex + r * 0.14f, eyeY)
                }
                drawPath(p, k.line, style = Stroke(width = r * 0.055f, cap = StrokeCap.Round))
            }
            else -> {
                val big = face == CatFace.HUNGRY || face == CatFace.PLAYING
                val w = r * (if (big) 0.31f else 0.26f)
                val h = r * (if (big) 0.37f else 0.32f) * blink
                val top = Offset(ex - w / 2, eyeY - h / 2)
                if (k.iris != null) {
                    drawOval(k.iris, top, Size(w, h.coerceAtLeast(r * 0.03f)))
                    drawOval(Color(0xFF2A2430), Offset(ex - w * 0.22f, eyeY - h * 0.42f), Size(w * 0.44f, (h * 0.84f).coerceAtLeast(r * 0.02f)))
                } else {
                    drawOval(INK, top, Size(w, h.coerceAtLeast(r * 0.03f)))
                }
                if (blink > 0.5f) {
                    drawCircle(Color.White, r * (if (big) 0.075f else 0.06f), Offset(ex + r * 0.04f, eyeY - h * 0.22f))
                    drawCircle(Color.White.copy(alpha = 0.7f), r * 0.03f, Offset(ex - r * 0.05f, eyeY + h * 0.2f))
                }
                // 肚子餓：水汪汪的眼睛
                if (face == CatFace.HUNGRY && blink > 0.5f) {
                    drawArc(Color(0xFF8EC9FF).copy(alpha = 0.8f), 20f, 140f, false, Offset(ex - w * 0.55f, eyeY - h * 0.1f), Size(w * 1.1f, h * 0.7f), style = Stroke(width = r * 0.04f, cap = StrokeCap.Round))
                }
                // 難過、髒髒的：八字眉
                if (face == CatFace.SAD || face == CatFace.DIRTY) {
                    drawLine(k.line.copy(alpha = 0.7f), Offset(ex - side * r * 0.12f, eyeY - r * 0.3f), Offset(ex + side * r * 0.12f, eyeY - r * 0.24f), r * 0.045f, StrokeCap.Round)
                }
            }
        }
    }
    if (face == CatFace.SAD) {
        val tx = c.x + r * 0.46f
        val ty = eyeY + r * 0.18f + fast * r * 0.25f
        val tear = Path().apply {
            moveTo(tx, ty)
            cubicTo(tx - r * 0.11f, ty + r * 0.18f, tx + r * 0.11f, ty + r * 0.18f, tx, ty)
        }
        drawPath(tear, Color(0xFF8EC9FF).copy(alpha = 1f - fast * 0.6f))
    }
}

/** 嘴巴：ω、開心張嘴、嚼嚼、肚子餓的 o 加口水、難過的 ︿、玩的時候張嘴、睡覺的鼻涕泡泡 */
private fun DrawScope.mouth(c: Offset, r: Float, k: CoatColors, face: CatFace, fast: Float) {
    val stroke = Stroke(width = r * 0.045f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val omega = Path().apply {
        moveTo(c.x - r * 0.16f, c.y + r * 0.28f)
        quadraticTo(c.x - r * 0.08f, c.y + r * 0.4f, c.x, c.y + r * 0.26f)
        quadraticTo(c.x + r * 0.08f, c.y + r * 0.4f, c.x + r * 0.16f, c.y + r * 0.28f)
    }
    when (face) {
        CatFace.SAD, CatFace.DIRTY -> {
            val p = Path().apply {
                moveTo(c.x - r * 0.13f, c.y + r * 0.4f)
                quadraticTo(c.x, c.y + r * 0.27f, c.x + r * 0.13f, c.y + r * 0.4f)
            }
            drawPath(p, k.line, style = stroke)
        }
        CatFace.HUNGRY -> {
            drawOval(TONGUE, Offset(c.x - r * 0.07f, c.y + r * 0.29f), Size(r * 0.14f, r * 0.13f))
            drawOval(k.line, Offset(c.x - r * 0.07f, c.y + r * 0.29f), Size(r * 0.14f, r * 0.13f), style = Stroke(width = r * 0.035f))
            // 口水
            val dy = fast * r * 0.08f
            drawOval(Color(0xFF8EC9FF), Offset(c.x + r * 0.08f, c.y + r * 0.36f + dy), Size(r * 0.07f, r * 0.1f + dy))
        }
        CatFace.EATING -> {
            val open = (sin(fast * 8 * PI).toFloat() + 1f) / 2f
            drawPath(omega, k.line, style = stroke)
            drawOval(TONGUE, Offset(c.x - r * 0.08f, c.y + r * 0.32f), Size(r * 0.16f, r * (0.03f + open * 0.1f)))
        }
        CatFace.HAPPY, CatFace.PLAYING -> {
            val m = Path().apply {
                moveTo(c.x - r * 0.12f, c.y + r * 0.3f)
                quadraticTo(c.x, c.y + r * 0.56f, c.x + r * 0.12f, c.y + r * 0.3f)
                close()
            }
            drawPath(m, TONGUE)
            drawPath(omega, k.line, style = stroke)
        }
        CatFace.SLEEPING -> {
            drawPath(omega, k.line, style = stroke)
            val b = 0.06f + (sin(fast * 2 * PI).toFloat() + 1f) * 0.05f
            drawCircle(Color(0xFFBFE3FF).copy(alpha = 0.75f), r * b, Offset(c.x + r * 0.16f + r * b, c.y + r * 0.18f))
        }
        else -> drawPath(omega, k.line, style = stroke)
    }
}

/** 長大的標誌：幼貓戴鈴鐺項圈、少年貓多一個蝴蝶結、成貓圍上圍巾、大胖貓戴皇冠 */
private fun DrawScope.accessories(c: Offset, r: Float, stage: Int, bodyTop: Float, bw: Float) {
    if (stage in 1..2 || stage == 4) {
        val y = bodyTop + r * 0.04f
        drawArc(Color(0xFFFF6F91), 20f, 140f, false, Offset(c.x - bw * 0.32f, y - r * 0.18f), Size(bw * 0.64f, r * 0.3f), style = Stroke(width = r * 0.1f, cap = StrokeCap.Round))
        drawCircle(Color(0xFFFFC94D), r * 0.1f, Offset(c.x, y + r * 0.14f))
        drawLine(Color(0xFFB88A1E), Offset(c.x - r * 0.06f, y + r * 0.15f), Offset(c.x + r * 0.06f, y + r * 0.15f), r * 0.025f, StrokeCap.Round)
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
        drawPath(scarf, Color(0xFF8FC8FF))
        drawCircle(Color.White.copy(alpha = 0.8f), r * 0.035f, Offset(c.x + bw * 0.08f, y + r * 0.2f))
        drawCircle(Color.White.copy(alpha = 0.8f), r * 0.035f, Offset(c.x - bw * 0.12f, y + r * 0.1f))
    }
    if (stage == 2) {
        // 右耳旁的粉紅蝴蝶結
        val bx = c.x + r * 0.62f
        val by = c.y - r * 0.82f
        val left = Path().apply { moveTo(bx, by); lineTo(bx - r * 0.24f, by - r * 0.14f); lineTo(bx - r * 0.24f, by + r * 0.14f); close() }
        val right = Path().apply { moveTo(bx, by); lineTo(bx + r * 0.24f, by - r * 0.14f); lineTo(bx + r * 0.24f, by + r * 0.14f); close() }
        drawPath(left, Color(0xFFFF7FA8)); drawPath(right, Color(0xFFFF7FA8))
        drawPath(left, Color(0xFFFF7FA8), style = Stroke(width = r * 0.06f, join = StrokeJoin.Round))
        drawPath(right, Color(0xFFFF7FA8), style = Stroke(width = r * 0.06f, join = StrokeJoin.Round))
        drawCircle(Color(0xFFE8457A), r * 0.07f, Offset(bx, by))
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
        drawPath(crown, Color(0xFFFFCB45))
        drawPath(crown, Color(0xFFE0A41C), style = Stroke(width = r * 0.035f, join = StrokeJoin.Round))
        drawCircle(Color(0xFFFF6F91), r * 0.06f, Offset(c.x, y - r * 0.12f))
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
        drawRect(Color(0xFF8FC8FF), Offset(floor.x - w * 0.32f, top + r * 0.1f), Size(w * 0.64f, r * 0.16f))
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
    drawPath(bowl, Color(0xFFFF8FA9))
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
    listOf(Color(0xFFFF7FA8) to -25f, Color(0xFFFFC94D) to 0f, Color(0xFF8FC8FF) to 25f).forEach { (col, deg) ->
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
    drawCircle(Color(0xFFC7A4FF), rr, at)
    rotate(fast * 360f, at) {
        listOf(-0.5f, 0f, 0.5f).forEach { k ->
            drawArc(Color(0xFF9E7BE0), 200f, 140f, false, Offset(at.x - rr * (1f - abs(k) * 0.3f), at.y - rr * (0.8f - k)), Size(rr * 2f * (1f - abs(k) * 0.3f), rr * 1.6f), style = Stroke(width = r * 0.035f))
        }
    }
    val string = Path().apply {
        moveTo(at.x + rr * 0.8f, at.y + rr * 0.5f)
        quadraticTo(at.x + rr * 2f, at.y + rr * 1.2f, at.x + rr * 2.8f, at.y + rr * 0.6f)
    }
    drawPath(string, Color(0xFF9E7BE0), style = Stroke(width = r * 0.03f, cap = StrokeCap.Round))
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
        drawRect(Color(0xFFFFB3C7), Offset(x - r * 0.2f, at.y - r * 0.12f), Size(r * 0.34f, r * 0.24f))
        for (i in 0 until 4) drawLine(Color(0xFFE8457A), Offset(x - r * 0.16f + i * r * 0.09f, at.y + r * 0.12f), Offset(x - r * 0.16f + i * r * 0.09f, at.y + r * 0.2f), r * 0.025f)
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
