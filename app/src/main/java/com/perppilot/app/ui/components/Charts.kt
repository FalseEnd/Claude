package com.perppilot.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.perppilot.app.ui.theme.Accent
import com.perppilot.app.ui.theme.Blue
import com.perppilot.app.ui.theme.LocalMarketColors
import com.perppilot.app.ui.theme.Outline
import com.perppilot.app.ui.theme.Purple
import com.perppilot.app.ui.theme.TextSecondary
import com.perppilot.core.indicators.Indicators
import com.perppilot.core.indicators.closes
import com.perppilot.core.model.Candle
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class HLine(val price: Double, val color: Color, val label: String)

/**
 * Candlestick chart with EMA20/EMA50 overlays, volume and an RSI panel.
 * Pinch to zoom, drag to scroll back in time.
 */
@Composable
fun CandleChart(
    candles: List<Candle>,
    modifier: Modifier = Modifier,
    height: Dp = 300.dp,
    lines: List<HLine> = emptyList(),
) {
    if (candles.isEmpty()) return
    val colors = LocalMarketColors.current
    var visible by remember(candles.size) { mutableFloatStateOf(min(90f, candles.size.toFloat())) }
    var offset by remember(candles.size) { mutableFloatStateOf(0f) }
    val closes = remember(candles) { candles.closes() }
    val ema20 = remember(candles) { Indicators.ema(closes, 20) }
    val ema50 = remember(candles) { Indicators.ema(closes, 50) }
    val rsi = remember(candles) { Indicators.rsi(closes, 14) }

    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(candles.size) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        visible = (visible / zoom).coerceIn(20f, min(300f, candles.size.toFloat()))
                        val perBar = size.width / visible
                        offset = (offset + pan.x / perBar).coerceIn(0f, max(0f, candles.size - visible))
                    }
                },
        ) {
            val axisW = 64.dp.toPx()
            val chartW = size.width - axisW
            val volH = size.height * 0.18f
            val priceH = size.height - volH - 6.dp.toPx()
            val end = (candles.size - offset.roundToInt()).coerceIn(1, candles.size)
            val start = max(0, end - visible.roundToInt())
            val slice = candles.subList(start, end)
            var hi = slice.maxOf { it.high }
            var lo = slice.minOf { it.low }
            lines.forEach { if (it.price > 0 && it.price < hi * 1.15 && it.price > lo * 0.85) { hi = max(hi, it.price); lo = min(lo, it.price) } }
            val pad = (hi - lo) * 0.06
            hi += pad; lo -= pad
            val range = (hi - lo).takeIf { it > 0 } ?: 1.0
            fun y(p: Double) = ((hi - p) / range * priceH).toFloat()
            val step = chartW / slice.size
            val bodyW = max(1f, step * 0.7f)

            // Grid + price labels.
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.rgb(139, 148, 158)
                textSize = 10.dp.toPx()
                isAntiAlias = true
            }
            for (k in 0..4) {
                val p = lo + range * k / 4
                val yy = y(p)
                drawLine(Outline, Offset(0f, yy), Offset(chartW, yy), strokeWidth = 1f)
                drawContext.canvas.nativeCanvas.drawText(fmtPrice(p), chartW + 4.dp.toPx(), yy + 4.dp.toPx(), paint)
            }
            val maxVol = slice.maxOf { it.volume }.takeIf { it > 0 } ?: 1.0
            slice.forEachIndexed { k, c ->
                val x = k * step + step / 2
                val col = if (c.close >= c.open) colors.up else colors.down
                drawLine(col, Offset(x, y(c.high)), Offset(x, y(c.low)), strokeWidth = max(1f, step * 0.12f))
                val top = y(max(c.open, c.close))
                val bot = y(min(c.open, c.close))
                drawRect(col, Offset(x - bodyW / 2, top), Size(bodyW, max(1f, bot - top)))
                val vh = (c.volume / maxVol * volH).toFloat()
                drawRect(col.copy(alpha = 0.35f), Offset(x - bodyW / 2, size.height - vh), Size(bodyW, vh))
            }
            drawSeries(ema20, start, end, step, ::y, Accent)
            drawSeries(ema50, start, end, step, ::y, Blue)
            lines.forEach { l ->
                if (l.price in lo..hi) {
                    val yy = y(l.price)
                    drawLine(l.color, Offset(0f, yy), Offset(chartW, yy), strokeWidth = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                    paint.color = android.graphics.Color.argb(255, (l.color.red * 255).toInt(), (l.color.green * 255).toInt(), (l.color.blue * 255).toInt())
                    drawContext.canvas.nativeCanvas.drawText(l.label, 4.dp.toPx(), yy - 3.dp.toPx(), paint)
                }
            }
        }
        // RSI panel.
        Canvas(Modifier.fillMaxWidth().height(70.dp)) {
            val axisW = 64.dp.toPx()
            val chartW = size.width - axisW
            val end = (candles.size - offset.roundToInt()).coerceIn(1, candles.size)
            val start = max(0, end - visible.roundToInt())
            val step = chartW / (end - start)
            fun y(v: Double) = ((100 - v) / 100 * size.height).toFloat()
            listOf(30.0, 50.0, 70.0).forEach { lv ->
                drawLine(Outline, Offset(0f, y(lv)), Offset(chartW, y(lv)), strokeWidth = 1f,
                    pathEffect = if (lv == 50.0) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            }
            drawSeries(rsi, start, end, step, ::y, Purple)
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.rgb(139, 148, 158); textSize = 10.dp.toPx(); isAntiAlias = true
            }
            val last = rsi.getOrNull(end - 1)?.takeIf { !it.isNaN() }
            drawContext.canvas.nativeCanvas.drawText("RSI ${last?.let { "%.1f".format(it) } ?: "-"}", chartW + 4.dp.toPx(), 12.dp.toPx(), paint)
        }
    }
}

private fun DrawScope.drawSeries(values: DoubleArray, start: Int, end: Int, step: Float, y: (Double) -> Float, color: Color) {
    val path = Path()
    var started = false
    for (i in start until end) {
        val v = values.getOrNull(i) ?: continue
        if (v.isNaN()) continue
        val x = (i - start) * step + step / 2
        if (!started) { path.moveTo(x, y(v)); started = true } else path.lineTo(x, y(v))
    }
    if (started) drawPath(path, color, style = Stroke(width = 1.6f))
}

/** Simple line chart for an equity curve, with the baseline drawn dashed. */
@Composable
fun LineChart(points: List<Double>, modifier: Modifier = Modifier, height: Dp = 180.dp, baseline: Double? = null) {
    if (points.size < 2) return
    val colors = LocalMarketColors.current
    Canvas(modifier.fillMaxWidth().height(height)) {
        val hi = max(points.max(), baseline ?: points.max())
        val lo = min(points.min(), baseline ?: points.min())
        val range = (hi - lo).takeIf { it > 0 } ?: 1.0
        fun y(v: Double) = ((hi - v) / range * size.height * 0.92 + size.height * 0.04).toFloat()
        val step = size.width / (points.size - 1)
        val path = Path()
        points.forEachIndexed { i, v -> if (i == 0) path.moveTo(0f, y(v)) else path.lineTo(i * step, y(v)) }
        val color = colors.of(points.last() - (baseline ?: points.first()))
        val fill = Path().apply { addPath(path); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
        drawPath(fill, color.copy(alpha = 0.12f))
        drawPath(path, color, style = Stroke(width = 2.5f))
        baseline?.let {
            drawLine(TextSecondary, Offset(0f, y(it)), Offset(size.width, y(it)), strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        }
    }
}

fun fmtPrice(p: Double): String = when {
    p.isNaN() -> "-"
    p >= 10_000 -> "%.0f".format(p)
    p >= 100 -> "%.2f".format(p)
    p >= 1 -> "%.3f".format(p)
    else -> "%.5f".format(p)
}
