package tw.xiaoliangkou.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tw.xiaoliangkou.app.ui.theme.Cute
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** 輕輕左右搖擺 */
@Composable
fun Wiggle(degrees: Float = 6f, periodMs: Int = 1600, content: @Composable () -> Unit) {
    val t = rememberInfiniteTransition(label = "wiggle")
    val r by t.animateFloat(-degrees, degrees, infiniteRepeatable(tween(periodMs / 2, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "r")
    Box(Modifier.graphicsLayer { rotationZ = r }) { content() }
}

/** 像心跳一樣「咚咚」放大 */
@Composable
fun HeartBeat(content: @Composable () -> Unit) {
    val t = rememberInfiniteTransition(label = "beat")
    val s by t.animateFloat(
        1f, 1f,
        infiniteRepeatable(keyframes {
            durationMillis = 1400
            1f at 0; 1.12f at 120; 1f at 260; 1.08f at 380; 1f at 560
        }),
        label = "s",
    )
    Box(Modifier.graphicsLayer { scaleX = s; scaleY = s }) { content() }
}

fun heartPath(cx: Float, cy: Float, size: Float): Path = Path().apply {
    val w = size
    val h = size * 0.92f
    moveTo(cx, cy + h * 0.35f)
    cubicTo(cx - w * 0.55f, cy - h * 0.05f, cx - w * 0.35f, cy - h * 0.62f, cx, cy - h * 0.28f)
    cubicTo(cx + w * 0.35f, cy - h * 0.62f, cx + w * 0.55f, cy - h * 0.05f, cx, cy + h * 0.35f)
    close()
}

/**
 * 「小倆口」吉祥物：兩顆麻糬，中間一條紅線牽著跳動的愛心。
 * 會上下晃、會眨眼；[partnerSad] 時右邊那顆會掉眼淚。
 */
@Composable
fun CoupleMascots(
    modifier: Modifier = Modifier,
    leftColor: Color = Cute.colors.me,
    rightColor: Color = Cute.colors.partner,
    partnerSad: Boolean = false,
    height: Dp = 120.dp,
) {
    val t = rememberInfiniteTransition(label = "mascot")
    val bob by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "bob")
    val blink by t.animateFloat(
        1f, 1f,
        infiniteRepeatable(keyframes { durationMillis = 3600; 1f at 0; 1f at 3300; 0.1f at 3400; 1f at 3500 }),
        label = "blink",
    )
    val beat by t.animateFloat(
        1f, 1f,
        infiniteRepeatable(keyframes { durationMillis = 1400; 1f at 0; 1.22f at 120; 1f at 260; 1.14f at 380; 1f at 560 }),
        label = "beat",
    )
    val thread = Cute.colors.thread
    val ink = Color(0xFF4A2B39)
    val cheek = Color(0xFFFF9EB8)
    Canvas(modifier.fillMaxWidth().height(height)) {
        val w = size.width
        val h = size.height
        val r = minOf(h * 0.36f, w * 0.16f)
        val y = h * 0.62f
        val leftX = w * 0.26f
        val rightX = w * 0.74f
        val bobL = sin(bob * 2 * PI).toFloat() * r * 0.08f
        val bobR = sin(bob * 2 * PI + PI / 2).toFloat() * r * 0.08f
        // 紅線（跟著兩顆麻糬一起晃）
        val p = Path().apply {
            moveTo(leftX + r * 0.85f, y - r * 0.15f + bobL)
            cubicTo(w * 0.42f, y - r * 1.1f, w * 0.58f, y + r * 0.2f, rightX - r * 0.85f, y - r * 0.15f + bobR)
        }
        drawPath(p, thread, style = Stroke(width = r * 0.08f, cap = StrokeCap.Round))
        val hc = Offset(w * 0.5f, y - r * 0.55f + (bobL + bobR) / 2)
        drawPath(heartPath(hc.x, hc.y, r * 0.55f * beat), thread)
        mochi(Offset(leftX, y + bobL), r, leftColor, ink, cheek, blink, sad = false)
        mochi(Offset(rightX, y + bobR), r, rightColor, ink, cheek, blink, sad = partnerSad)
    }
}

private fun DrawScope.mochi(c: Offset, r: Float, body: Color, ink: Color, cheek: Color, blink: Float, sad: Boolean) {
    // 身體：下面稍微扁一點的麻糬
    val path = Path().apply {
        moveTo(c.x - r, c.y + r * 0.55f)
        cubicTo(c.x - r * 1.08f, c.y - r * 0.9f, c.x + r * 1.08f, c.y - r * 0.9f, c.x + r, c.y + r * 0.55f)
        quadraticTo(c.x, c.y + r * 0.85f, c.x - r, c.y + r * 0.55f)
        close()
    }
    drawPath(path, body)
    drawOval(Color.White.copy(alpha = 0.35f), Offset(c.x - r * 0.55f, c.y - r * 0.55f), Size(r * 0.45f, r * 0.25f))
    val eyeY = c.y - r * 0.05f
    val eyeH = r * 0.2f * blink
    listOf(-1f, 1f).forEach { side ->
        drawOval(ink, Offset(c.x + side * r * 0.32f - r * 0.07f, eyeY - eyeH / 2), Size(r * 0.14f, eyeH.coerceAtLeast(r * 0.02f)))
        drawOval(cheek, Offset(c.x + side * r * 0.55f - r * 0.12f, c.y + r * 0.14f), Size(r * 0.24f, r * 0.13f))
    }
    val mouth = Path().apply {
        if (sad) {
            moveTo(c.x - r * 0.1f, c.y + r * 0.26f)
            quadraticTo(c.x, c.y + r * 0.16f, c.x + r * 0.1f, c.y + r * 0.26f)
        } else {
            moveTo(c.x - r * 0.1f, c.y + r * 0.16f)
            quadraticTo(c.x, c.y + r * 0.28f, c.x + r * 0.1f, c.y + r * 0.16f)
        }
    }
    drawPath(mouth, ink, style = Stroke(width = r * 0.05f, cap = StrokeCap.Round))
    if (sad) {
        val tear = Path().apply {
            val tx = c.x + r * 0.36f
            val ty = eyeY + r * 0.16f
            moveTo(tx, ty)
            cubicTo(tx - r * 0.1f, ty + r * 0.16f, tx + r * 0.1f, ty + r * 0.16f, tx, ty)
        }
        drawPath(tear, Color(0xFF8EC9FF))
    }
}

/** 飄起來的愛心：呼叫 [burst] 就會從指定位置冒出一串 */
class HeartBurstState {
    internal data class Particle(val x: Float, val y: Float, val vx: Float, val vy: Float, val size: Float, val color: Color, val rot: Float, val start: Long, val life: Long)
    internal val particles = mutableStateListOf<Particle>()
    internal var frame by mutableLongStateOf(0L)
    internal var origin: Offset? = null
    private val palette = listOf(Color(0xFFFF6F96), Color(0xFFFF9EB8), Color(0xFFE8455F), Color(0xFFFFB38A), Color(0xFFB9A6FF))

    /** x、y 為 0..1 的相對位置（預設為畫面中下方） */
    fun burst(x: Float = 0.5f, y: Float = 0.75f, count: Int = 16) {
        val now = System.nanoTime()
        repeat(count) {
            particles += Particle(
                x, y, Random.nextFloat() * 0.5f - 0.25f, -(0.35f + Random.nextFloat() * 0.45f), 14f + Random.nextFloat() * 22f,
                palette.random(), Random.nextFloat() * 40f - 20f, now + it * 25_000_000L, 1_500_000_000L + Random.nextLong(600_000_000L),
            )
        }
    }
}

@Composable
fun rememberHeartBurst() = remember { HeartBurstState() }

@Composable
fun HeartBurstOverlay(state: HeartBurstState, modifier: Modifier = Modifier) {
    LaunchedEffect(state.particles.size > 0) {
        while (state.particles.isNotEmpty()) {
            withFrameNanos { now ->
                state.frame = now
                state.particles.removeAll { now - it.start > it.life }
            }
        }
    }
    if (state.particles.isEmpty()) return
    Canvas(modifier.fillMaxSize()) {
        val now = state.frame
        state.particles.forEach { p ->
            val t = ((now - p.start).toFloat() / p.life).coerceIn(0f, 1f)
            if (now < p.start) return@forEach
            val px = (p.x + p.vx * t + sin(t * 8f + p.rot) * 0.02f) * size.width
            val py = (p.y + p.vy * t) * size.height
            val s = p.size.dp.toPx() * (0.6f + 0.6f * sin(t * PI.toFloat()).coerceAtLeast(0.3f))
            rotate(p.rot * (1 - t), Offset(px, py)) {
                drawPath(heartPath(px, py, s), p.color.copy(alpha = (1f - t * t).coerceIn(0f, 1f)))
            }
        }
    }
}

/** 彩色紙花，猜中、紀念日用 */
@Composable
fun Confetti(trigger: Int, modifier: Modifier = Modifier) {
    data class Bit(val x: Float, val vx: Float, val vy: Float, val color: Color, val spin: Float, val w: Float)
    val colors = listOf(Cute.colors.partner, Cute.colors.me, Cute.colors.butter, Cute.colors.mint, Cute.colors.lavender, Cute.colors.peach)
    val bits = remember(trigger) { List(if (trigger > 0) 70 else 0) { Bit(Random.nextFloat(), Random.nextFloat() * 0.3f - 0.15f, Random.nextFloat() * 0.4f + 0.5f, colors.random(), Random.nextFloat() * 720f, 6f + Random.nextFloat() * 6f) } }
    val progress = remember(trigger) { Animatable(0f) }
    LaunchedEffect(trigger) { if (trigger > 0) progress.animateTo(1f, tween(2200, easing = LinearEasing)) }
    if (trigger == 0 || progress.value >= 1f) return
    Canvas(modifier.fillMaxSize()) {
        val t = progress.value
        bits.forEach { b ->
            val x = (b.x + b.vx * t) * size.width
            val y = (-0.1f + b.vy * t * 1.6f + t * t * 0.4f) * size.height
            rotate(b.spin * t, Offset(x, y)) {
                drawRect(b.color.copy(alpha = 1f - t * 0.6f), Offset(x, y), Size(b.w.dp.toPx(), b.w.dp.toPx() * 0.5f))
            }
        }
    }
}

/** 可愛的電池：電量動畫、低電量變紅並抖動、充電時閃電跳動 */
@Composable
fun BatteryPill(level: Int, charging: Boolean, modifier: Modifier = Modifier) {
    val fill by animateFloatAsState(level.coerceIn(0, 100) / 100f, tween(900, easing = FastOutSlowInEasing), label = "battery")
    val low = level <= 20 && !charging
    val color = when { charging -> Cute.colors.good; low -> Cute.colors.bad; level <= 40 -> Cute.colors.butter; else -> Cute.colors.mint }
    val t = rememberInfiniteTransition(label = "battery")
    val shake by t.animateFloat(-2f, 2f, infiniteRepeatable(tween(120), RepeatMode.Reverse), label = "shake")
    val bolt by t.animateFloat(0.85f, 1.15f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "bolt")
    Row(modifier.graphicsLayer { rotationZ = if (low) shake else 0f }, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(54.dp).height(26.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.22f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fill).clip(RoundedCornerShape(10.dp)).background(color))
            if (charging) Icon(Icons.Rounded.Bolt, "充電中", tint = Color.White, modifier = Modifier.align(Alignment.Center).size(18.dp).graphicsLayer { scaleX = bolt; scaleY = bolt })
        }
        Box(Modifier.width(4.dp).height(10.dp).clip(RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp)).background(color.copy(alpha = 0.5f)))
    }
}

/** 數字會從 0 滾到目標值 */
@Composable
fun CountUpText(value: Int, style: androidx.compose.ui.text.TextStyle, prefix: String = "", suffix: String = "", color: Color = Color.Unspecified) {
    var target by remember { mutableStateOf(0) }
    LaunchedEffect(value) { target = value }
    val shown by animateIntAsState(target, tween(900, easing = FastOutSlowInEasing), label = "count")
    Text(prefix + "%,d".format(shown) + suffix, style = style, color = color)
}

/** 依類別的長條圖，依序長出來 */
@Composable
fun CategoryBars(items: List<Triple<String, String, Double>>, color: Color, money: (Double) -> String) {
    val max = items.maxOfOrNull { it.third }?.takeIf { it > 0 } ?: 1.0
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEachIndexed { i, (emoji, label, amount) ->
            var started by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { started = true }
            val f by animateFloatAsState(if (started) (amount / max).toFloat() else 0f, tween(700, delayMillis = i * 90, easing = FastOutSlowInEasing), label = "bar")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(emoji, modifier = Modifier.width(28.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.weight(1f).height(14.dp)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(f.coerceAtLeast(0.02f)).clip(RoundedCornerShape(7.dp)).background(color))
                }
                Text(money(amount), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), modifier = Modifier.width(84.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
    }
}

/** 經期圓環：每天一格依階段上色，進場時一格一格畫出來 */
@Composable
fun CycleRing(
    days: List<Color>,
    todayIndex: Int?,
    modifier: Modifier = Modifier,
    center: @Composable BoxScope.() -> Unit,
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(days.size) { progress.snapTo(0f); progress.animateTo(1f, tween(1100, easing = FastOutSlowInEasing)) }
    val markerColor = MaterialTheme.colorScheme.onSurface
    val ringBg = Cute.colors.card
    Box(modifier.size(230.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val n = days.size.coerceAtLeast(1)
            val stroke = size.minDimension * 0.085f
            val r = size.minDimension / 2 - stroke
            val shown = (n * progress.value).toInt()
            days.forEachIndexed { i, c ->
                if (i >= shown) return@forEachIndexed
                val start = -90f + i * 360f / n + 0.9f
                drawArc(c, start, 360f / n - 1.8f, false, Offset(size.width / 2 - r, size.height / 2 - r), Size(r * 2, r * 2), style = Stroke(stroke, cap = StrokeCap.Butt))
            }
            if (todayIndex != null && progress.value >= 1f) {
                val a = Math.toRadians((-90.0 + (todayIndex + 0.5) * 360.0 / n))
                val p = Offset(size.width / 2 + r * cos(a).toFloat(), size.height / 2 + r * sin(a).toFloat())
                drawCircle(ringBg, stroke * 0.75f, p)
                drawCircle(markerColor, stroke * 0.5f, p)
            }
        }
        center()
    }
}

/** 三顆跳動的小圓點，等待時用 */
@Composable
fun LoadingDots(color: Color = MaterialTheme.colorScheme.primary) {
    val t = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { i ->
            val y by t.animateFloat(0f, 1f, infiniteRepeatable(keyframes { durationMillis = 900; 0f at (i * 120); 1f at (i * 120 + 200); 0f at (i * 120 + 400) }), label = "d$i")
            Box(Modifier.offset(y = (-8 * y).dp).size(10.dp).clip(RoundedCornerShape(50)).background(color))
        }
    }
}

/** 地圖上對方頭像外圈的雷達波 */
@Composable
fun PulseRing(color: Color, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "pulse")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800)), label = "p")
    Canvas(modifier) {
        drawCircle(color.copy(alpha = (1 - p) * 0.35f), size.minDimension / 2 * (0.5f + p * 0.5f))
    }
}

fun DrawScope.roundBar(color: Color, x: Float, y: Float, w: Float, h: Float) =
    drawRoundRect(color, Offset(x, y), Size(w, h), CornerRadius(h / 2, h / 2))

@Suppress("unused")
private fun DrawScope.dot(color: Color, at: Offset, r: Float) = translate(at.x, at.y) { drawCircle(color, r, Offset.Zero) }
