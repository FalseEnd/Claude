package com.falseend.mousecursor

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** 畫一個標準箭頭游標，熱點 (尖端) 位於 (pad, pad) */
class CursorView(context: Context) : View(context) {
    var fillColor = 0xFFFFFFFF.toInt()
    var strokeColor = 0xFF000000.toInt()
    var cursorPx = 48f
        set(v) { field = v; requestLayout(); invalidate() }

    val pad: Float get() = cursorPx * 0.6f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    private var ringProgress = -1f
    private var ringColor = 0

    // 箭頭外形 (12 x 19 單位)
    private val shape = floatArrayOf(
        0f, 0f, 0f, 16f, 3.8f, 12.4f, 6.6f, 18.8f, 9f, 17.8f, 6.3f, 11.6f, 11.5f, 11.6f
    )

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = (cursorPx + pad * 2).toInt()
        setMeasuredDimension(s, s)
    }

    fun click(buttons: Int) {
        ringColor = when {
            buttons and 2 != 0 -> 0xFFFF9500.toInt()
            buttons and 4 != 0 -> 0xFF34C759.toInt()
            else -> 0xFF0A84FF.toInt()
        }
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
            addUpdateListener { ringProgress = it.animatedValue as Float; invalidate() }
            doOnEnd { ringProgress = -1f; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val u = cursorPx / 19f
        val p = pad
        if (ringProgress >= 0f) {
            ring.color = ringColor
            ring.alpha = ((1f - ringProgress) * 255).toInt()
            ring.strokeWidth = cursorPx * 0.12f
            canvas.drawCircle(p, p, pad * (0.3f + 0.7f * ringProgress), ring)
        }
        path.reset()
        path.moveTo(p + shape[0] * u, p + shape[1] * u)
        var i = 2
        while (i < shape.size) {
            path.lineTo(p + shape[i] * u, p + shape[i + 1] * u); i += 2
        }
        path.close()
        stroke.color = strokeColor
        stroke.strokeWidth = u * 1.6f
        canvas.drawPath(path, stroke)
        fill.color = fillColor
        canvas.drawPath(path, fill)
    }

    private inline fun ValueAnimator.doOnEnd(crossinline f: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) = f()
        })
    }
}
