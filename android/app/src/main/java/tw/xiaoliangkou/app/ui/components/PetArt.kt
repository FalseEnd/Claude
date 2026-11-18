package tw.xiaoliangkou.app.ui.components

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import tw.xiaoliangkou.app.core.Pet
import kotlinx.coroutines.delay
import kotlin.random.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
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
 * 十五種毛色都使用透明底手繪插畫，依坐姿、進食、理毛和睡眠切換。
 * 4 × 4 圖集的前十五格依 [Pet.coats] 排列；每格保留透明邊，縮放時不會露出鄰格。
 * 長大、照顧道具和心情提示疊在插圖周圍；[animated] 為 false 時，品種清單完全靜止。
 */
@Composable
fun PetCat(coat: String, stage: Int, face: CatFace, modifier: Modifier = Modifier, prop: String? = null, bounce: Int = 0, animated: Boolean = true, autonomous: Boolean = false) {
    val resources = LocalContext.current.resources
    val cell = remember(coat) { Pet.coats.indexOfFirst { it.key == coat }.coerceAtLeast(0) }
    val base = if (face == CatFace.SLEEPING && prop == null) CatDrawing.SLEEPING else CatDrawing.SITTING
    val cat = remember(resources, cell, base) { PaintedCat(base, CatBitmaps.get(resources, base, cell), cell) }
    if (!animated) {
        Canvas(modifier.semantics { contentDescription = "${Pet.coat(coat).label}，${face.description()}" }) {
            drawPet(cat, null, 0f, stage, face, prop, CatPose(), .25f, 0f)
        }
        return
    }
    var idle by remember(coat, prop, bounce, face, autonomous) { mutableStateOf(CatMotion.REST) }
    LaunchedEffect(coat, prop, bounce, face, autonomous) {
        if (autonomous && prop == null && face != CatFace.SLEEPING) {
            var next = CatMotion.GROOM
            while (true) {
                delay(Random.nextLong(4500, 8500))
                idle = next
                delay(MOTION_MS.toLong())
                idle = CatMotion.REST
                next = if (next == CatMotion.GROOM) CatMotion.STRETCH else CatMotion.GROOM
            }
        }
    }
    val motion = if (prop == null) idle else CatMotion.forProp(prop)
    val actionDrawing = when (motion) {
        CatMotion.EAT -> CatDrawing.EATING
        CatMotion.GROOM -> CatDrawing.GROOMING
        else -> null
    }
    val actionCat = remember(resources, cell, actionDrawing) {
        actionDrawing?.let { PaintedCat(it, CatBitmaps.get(resources, it, cell), cell) }
    }
    val progress = remember(motion, bounce) { Animatable(0f) }
    LaunchedEffect(progress) {
        if (motion != CatMotion.REST) progress.animateTo(1f, tween(MOTION_MS, easing = LinearEasing))
    }
    val description = if (motion == CatMotion.REST) face.description() else motion.description
    val transition = rememberInfiniteTransition(label = "illustratedPet")
    val breathe by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(if (face == CatFace.SLEEPING) 3400 else 2400, easing = LinearEasing)), label = "breathe",
    )
    val blink by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(6100, easing = LinearEasing)), label = "blink",
    )
    Canvas(modifier.semantics { contentDescription = "${Pet.coat(coat).label}，$description" }) {
        val t = progress.value
        val atRest = motion == CatMotion.REST
        var pose = motion.pose(t)
        if (atRest && base == CatDrawing.SITTING) {
            val closure = (1f - abs(blink - .72f) / .027f).coerceAtLeast(0f)
            if (closure > 0f) pose = pose.copy(eyesClosed = closure)
        }
        val blend = if (actionCat == null) 0f else minOf(t / .13f, (1f - t) / .12f).coerceIn(0f, 1f)
        drawPet(cat, actionCat, blend, stage, face, prop, pose, breathe, if (atRest) breathe else t)
    }
}

internal const val MOTION_MS = 3200

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
private fun DrawScope.drawPet(cat: PaintedCat, actionCat: PaintedCat?, blend: Float, stage: Int, face: CatFace,
    prop: String?, pose: CatPose, breathe: Float, fast: Float) {
    val st = stage.coerceIn(0, 4)
    val side = minOf(size.height, size.width) * STAGE_SCALE[st]
    val cx = size.width / 2
    val floor = size.height * .93f
    val breath = sin(breathe * 2 * PI).toFloat()
    val jump = pose.lift * side
    val top = floor - side * (286f / 320f) - jump
    val left = cx - side / 2
    val r = side * .23f
    val c = Offset(left + side * .453f, top + side * .36f)
    val sleeping = cat.drawing == CatDrawing.SLEEPING
    val shadowWidth = side * (if (sleeping) .71f else .53f) * (1f - pose.lift)
    val shadowCenter = cx + pose.x * side
    drawOval(Color(0x167C5A49), Offset(shadowCenter - shadowWidth / 2, floor - side * .038f),
        Size(shadowWidth, side * .064f))
    if (prop == "laser") laserDot(Offset(cx + pose.toyX * side, floor - side * .024f), r)
    val pivot = Offset(cx, floor - jump)
    val squash = pose.squash * (1f - blend)
    translate(left = pose.x * side) {
        rotate(pose.tilt, pivot) {
            scale(1f + breath * .004f + squash * .22f, 1f + breath * .009f - squash, pivot) {
                cat.draw(this, left, top, side, pose, 1f - blend)
                actionCat?.draw(this, left, top, side, pose, blend)
                if (!sleeping && blend < .5f) {
                    val head = c + Offset(side * (pose.headX + .18f * pose.headRoll), side * pose.headY)
                    // 非坐姿時項圈會藏在下巴和胸毛後。
                    accessories(head, r, st, top + side * (.515f + pose.headY * .60f), side * .49f)
                }
                if (face == CatFace.DIRTY) dirt(Offset(cx, top + side * .56f), side * .5f, side * .25f, r)
            }
        }
    }
    when (prop) {
        "kibble", "can", "fish" -> {
            val mouth = CatDrawing.EATING.mouth
            val dishSize = r * .74f
            bowl(prop, Offset(left + side * mouth.x, top + side * mouth.y + dishSize * .42f), dishSize, pose.jaw)
        }
        "wand" -> wand(c, r * .85f, fast, size.width,
            Offset(cx + pose.toyX * side, top + jump + side * .30f))
        "yarn" -> {
            val turns = (pose.toyX + .27f) * side / (2f * PI.toFloat() * .32f * r)
            yarn(Offset(cx + pose.toyX * side, floor - r * .25f), r, turns)
        }
        "bath" -> bubbles(c + Offset(pose.headX * side, pose.headY * side), r, fast, floor)
        "brush" -> brush(Offset(cx + side * (.108f + pose.toolStroke * .09f),
            top + side * (.41f + pose.toolStroke * .22f)), r, fast)
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

// Work in units of head radius so the details stay aligned across growth stages.
private inline fun DrawScope.propAt(at: Offset, radius: Float, draw: DrawScope.() -> Unit) {
    val canvas = drawContext.canvas
    canvas.save()
    try {
        canvas.translate(at.x, at.y)
        canvas.scale(radius, radius)
        draw()
    } finally {
        canvas.restore()
    }
}

private fun DrawScope.bowl(item: String, floor: Offset, r: Float, fast: Float) = propAt(floor, r) {
    val rimY = -.42f
    drawOval(Color(0x30745343), Offset(-.66f, -.08f), Size(1.34f, .14f))
    if (item == "can") {
        // The rolled lip, food and inner edge share the same ellipse.
        drawOval(Color(0xFF899698), Offset(-.48f, -.12f), Size(.96f, .14f))
        drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(
            listOf(Color(0xFFA1AFAD), Color(0xFFEBE6D5), Color(0xFFA6B9B8), Color(0xFF859595)), -.48f, .48f
        ), Offset(-.48f, rimY), Size(.96f, .37f))
        drawOval(Color(0xFFA0ACA2), Offset(-.48f, -.29f), Size(.96f, .15f))
        drawRect(Color(0xFF98B5A8), Offset(-.48f, -.26f), Size(.96f, .12f))
        drawArc(Color(0xFFDCE8D7), 0f, 180f, false, Offset(-.475f, -.20f), Size(.95f, .12f), style = Stroke(.025f))
        drawOval(Color(0xFFDCE3DE), Offset(-.50f, -.54f), Size(1f, .23f))
        drawOval(Color(0xFF9C6C59), Offset(-.455f, -.508f), Size(.91f, .162f))
        drawOval(Color(0xFFD79B83), Offset(-.423f, -.49f), Size(.844f, .136f))
        for (i in 0..4) {
            val x = (i - 2) * .14f
            val y = -.44f - (i % 2) * .02f
            drawLine(Color(0xFFECC2A8), Offset(x, y), Offset(x + .064f, y + .013f), .021f, StrokeCap.Round)
        }
    } else {
        val ceramic = Path().apply {
            moveTo(-.63f, rimY)
            cubicTo(-.61f, -.29f, -.55f, -.09f, -.44f, -.035f)
            quadraticTo(0f, .055f, .44f, -.035f)
            cubicTo(.55f, -.09f, .61f, -.29f, .63f, rimY)
            close()
        }
        drawPath(ceramic, androidx.compose.ui.graphics.Brush.linearGradient(
            listOf(Color(0xFFF3CEC0), Color(0xFFDEA295), Color(0xFFB37A71)), Offset(-.48f, -.52f), Offset(.32f, .15f)))
        drawOval(Color(0xFFF8DBCB), Offset(-.635f, -.55f), Size(1.27f, .245f))
        drawOval(Color(0xFFA76D5C), Offset(-.565f, -.512f), Size(1.13f, .165f))
        if (item == "kibble") {
            for (i in 0..7) {
                val back = i >= 4
                val x = (i % 4 - 1.5f) * .22f + (if (back) .02f else -.03f)
                val y = if (back) -.425f else -.475f
                val nibble = if (i == 2) .012f * sin(fast * PI * 2).toFloat() else 0f
                rotate(((i * 37 % 50) - 25).toFloat(), Offset(x, y)) {
                    drawOval(Color(if (i % 3 == 0) 0xFFAB6B41 else 0xFFBD8758), Offset(x - .099f, y - .062f + nibble), Size(.20f, .113f))
                    drawLine(Color(0xFFE1AF7D), Offset(x - .044f, y - .03f + nibble), Offset(x + .008f, y - .038f + nibble), .019f, StrokeCap.Round)
                }
            }
        } else {
            rotate(-8f, Offset(0f, rimY)) {
                val fish = Path().apply {
                    moveTo(-.42f, -.455f)
                    cubicTo(-.31f, -.60f, -.02f, -.63f, .19f, -.495f)
                    lineTo(.39f, -.595f)
                    quadraticTo(.33f, -.485f, .392f, -.39f)
                    lineTo(.18f, -.451f)
                    cubicTo(.01f, -.342f, -.31f, -.341f, -.42f, -.455f)
                    close()
                }
                drawPath(fish, androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(Color(0xFF86A2AD), Color(0xFFE2DCC4)), -.6f, -.37f))
                drawCircle(INK, .024f, Offset(-.285f, -.481f))
                drawArc(Color(0xFFA0A499), -60f, 110f, false, Offset(-.34f, -.55f), Size(.19f, .16f), style = Stroke(.014f))
            }
        }
        drawArc(Color(0xFFFFE3D2), 11f, 158f, false, Offset(-.613f, -.531f), Size(1.226f, .224f), style = Stroke(.037f, cap = StrokeCap.Round))
        drawArc(Color(0x99FFF1DB), 104f, 39f, false, Offset(-.52f, -.44f), Size(.96f, .27f), style = Stroke(.023f, cap = StrokeCap.Round))
    }
}

private fun DrawScope.wand(head: Offset, r: Float, fast: Float, width: Float, feather: Offset? = null) {
    val swing = sin(fast * 4 * PI).toFloat()
    val anchor = feather ?: Offset(head.x + (.9f + .38f * swing) * r, head.y - r * 1.12f)
    val tip = Offset(anchor.x + (.27f - .13f * swing) * r, anchor.y - .67f * r)
    val handle = Offset(width * .97f, tip.y + .31f * r)
    drawLine(Color(0xFFAA7D59), handle, tip, .065f * r, StrokeCap.Round)
    val thread = Path().apply {
        moveTo(tip.x, tip.y)
        quadraticTo(tip.x + .13f * r, anchor.y - .26f * r, anchor.x, anchor.y)
    }
    drawPath(thread, Color(0xB5756B65), style = Stroke(.019f * r, cap = StrokeCap.Round))
    propAt(anchor, r) {
        for (i in 0..2) {
            rotate(-34f + i * 32f - swing * 17f, Offset.Zero) {
                val plume = Path().apply {
                    moveTo(0f, 0f)
                    cubicTo(-.145f, .16f, -.138f, .29f, -.044f, .44f)
                    cubicTo(.082f, .31f, .128f, .15f, 0f, 0f)
                    close()
                }
                val color = when (i) { 0 -> Color(0xFFCC9188); 1 -> Color(0xFFDFB76C); else -> Color(0xFF8FB6B5) }
                drawPath(plume, color)
                drawLine(Color(0xB8FFF1D4), Offset(0f, .03f), Offset(-.041f, .383f), .016f, StrokeCap.Round)
                for (j in 1..3) {
                    val y = .07f + j * .068f
                    drawLine(Color(0x8AFFF1D4), Offset(-.02f, y), Offset(.06f, y - .061f), .012f, StrokeCap.Round)
                }
            }
        }
        drawCircle(Color(0xFFD7AC69), .059f, Offset.Zero)
    }
}

private fun DrawScope.laserDot(at: Offset, r: Float) = propAt(at, r) {
    drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(
        listOf(Color(0xB8FF7D69), Color(0x59F56860), Color(0x00EF665C)), Offset.Zero, .185f), .185f, Offset.Zero)
    drawOval(Color(0xFFF96558), Offset(-.076f, -.042f), Size(.152f, .084f))
    drawCircle(Color(0xFFFFDDD0), .022f, Offset(-.009f, -.009f))
}

private fun DrawScope.yarn(at: Offset, r: Float, fast: Float) = propAt(at, r * .32f) {
    val string = Path().apply {
        moveTo(.38f, .74f)
        cubicTo(1.08f, 1.34f, 1.27f, .58f, 1.77f, .76f)
        quadraticTo(2.15f, .92f, 2.42f, .81f)
    }
    drawOval(Color(0x36745D54), Offset(-1.02f, .73f), Size(2.13f, .40f))
    drawPath(string, Color(0xFF9B7E9B), style = Stroke(.09f, cap = StrokeCap.Round))
    drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(
        listOf(Color(0xFFE5D1DC), Color(0xFFC4A4BF), Color(0xFF9F819F)), Offset(-.38f, -.52f), 1.68f), 1f, Offset.Zero)
    rotate(fast * 360f, Offset.Zero) {
        for (i in -2..2) {
            val offset = i * .19f
            val yarn = Path().apply {
                moveTo(-.80f + offset * .17f, -.38f + offset)
                quadraticTo(-.23f + offset * .22f, -.035f + offset, .33f + offset, .76f - offset * .25f)
            }
            drawPath(yarn, Color(0xCC9D809E), style = Stroke(.071f, cap = StrokeCap.Round))
            drawPath(yarn, Color(0xACF2DDE4), style = Stroke(.027f, cap = StrokeCap.Round))
        }
        for (i in 0..2) {
            val offset = i * .19f
            val winding = Path().apply {
                moveTo(-.36f + offset, -.85f)
                quadraticTo(.13f + offset, -.74f, .73f + offset * .3f, -.14f + offset)
            }
            drawPath(winding, Color(0xBC9D809E), style = Stroke(.061f, cap = StrokeCap.Round))
        }
    }
}

private fun DrawScope.bubbles(c: Offset, r: Float, fast: Float, floor: Float) {
    // Foam gathers between the ears. Airborne bubbles fade in at the fur and out near the ceiling.
    propAt(c, r) {
        for (i in 0..6) {
            val x = -.44f + i * .143f
            val radius = .11f + .036f * ((i * 5) % 3)
            val center = Offset(x, -.94f + .19f * abs(x) + (i % 2) * .043f)
            drawCircle(Color(0xE2D8E8E8), radius, center)
            drawCircle(Color(0xEDFFFBF3), radius * .82f, center - Offset(radius * .12f, radius * .14f))
        }
    }
    for (i in 0 until 7) {
        val p = (fast + i / 7f) % 1f
        val fade = sin(p * PI).toFloat()
        val x = c.x + (cos(i * 2.1).toFloat() * (1.35f + (i % 3) * .2f) + sin(p * 2 * PI).toFloat() * .13f) * r
        val y = floor - p * (floor - (c.y - r * 1.62f))
        val radius = r * (.072f + (i % 3) * .045f) * (.85f + .23f * p)
        val corner = Offset(x - radius, y - radius)
        val diameter = Size(2 * radius, 2 * radius)
        drawCircle(Color(0xFFAFDAD9).copy(alpha = .32f * fade), radius, Offset(x, y))
        drawArc(Color.White.copy(alpha = .88f * fade), 168f, 127f, false, corner, diameter, style = Stroke(.14f * radius, cap = StrokeCap.Round))
        drawArc(Color(0xFFEEC9D9).copy(alpha = .84f * fade), 12f, 112f, false, corner, diameter, style = Stroke(.13f * radius, cap = StrokeCap.Round))
        drawCircle(Color.White.copy(alpha = .93f * fade), .17f * radius, Offset(x - .37f * radius, y - .48f * radius))
    }
}

private fun DrawScope.brush(at: Offset, r: Float, fast: Float) = propAt(at, r) {
    rotate(-24f + 7f * sin(fast * 2 * PI).toFloat(), Offset.Zero) {
        // The tips meet the coat at the origin; the handle lifts away from it.
        drawLine(Color(0xFFA57D60), Offset(.03f, -.17f), Offset(.56f, -.22f), .125f, StrokeCap.Round)
        drawLine(Color(0xFFF1CBA2), Offset(.12f, -.197f), Offset(.525f, -.235f), .037f, StrokeCap.Round)
        for (i in 0..6) {
            val x = -.215f + i * .063f
            val end = -.012f - abs(x + .03f) * .12f
            drawLine(Color(0xFF886E65), Offset(x, -.15f), Offset(x - .023f, end), .032f, StrokeCap.Round)
        }
        drawOval(Color(0xFFAA8171), Offset(-.293f, -.303f), Size(.56f, .226f))
        drawOval(androidx.compose.ui.graphics.Brush.verticalGradient(
            listOf(Color(0xFFF1CBAE), Color(0xFFCB987D)), -.353f, -.115f),
            Offset(-.302f, -.354f), Size(.575f, .24f))
        drawArc(Color(0xFFF8DDC1), 208f, 81f, false, Offset(-.246f, -.318f), Size(.435f, .145f), style = Stroke(.021f, cap = StrokeCap.Round))
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
