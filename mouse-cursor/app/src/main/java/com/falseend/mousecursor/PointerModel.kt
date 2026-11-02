package com.falseend.mousecursor

import android.os.Build
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 把滑鼠原始位移 (counts) 換算成螢幕像素，照抄 AOSP 的游標加速：
 *
 * - LEGACY（Android 14 以前，15 部分機型）：SimpleVelocityControl
 *   scale = 2^(pointer_speed/4)，速度 500~3000 counts/s 之間線性加速到 3 倍
 * - CURVED（Android 16，15 部分機型）：CurvedVelocityControl
 *   以 800 CPI 換成 mm/s，套四段曲線 gain = baseGain + reciprocal/speed
 * - FLAT（Android 16 關閉「游標加速」時）：固定倍率 1 + (speed+7)*2.5/14
 *
 * 速度估計與系統相同：VelocityTracker LSQ2（100ms 視窗二次擬合），
 * 停頓 40ms 清空樣本、500ms 視為停止。
 */
class PointerModel {
    enum class Type { LEGACY, CURVED, FLAT }

    var type = defaultType()
    /** 系統設定 pointer_speed，-7..7 */
    var systemSpeed = 0
    /** 額外倍率（校準結果） */
    var sensitivity = 1f

    private val ts = LongArray(MAX_SAMPLES)
    private val xs = DoubleArray(MAX_SAMPLES)
    private val ys = DoubleArray(MAX_SAMPLES)
    private var count = 0
    private var head = 0
    private var rawX = 0.0
    private var rawY = 0.0
    private var lastUs = Long.MIN_VALUE / 2

    fun reset() {
        count = 0; rawX = 0.0; rawY = 0.0; lastUs = Long.MIN_VALUE / 2
    }

    fun transform(dx: Int, dy: Int, timeUs: Long): Pair<Float, Float> {
        if (dx == 0 && dy == 0) return 0f to 0f
        if (timeUs - lastUs >= STOP_US) reset()
        if (timeUs - lastUs > ASSUME_STOPPED_US) count = 0
        lastUs = timeUs
        rawX += dx; rawY += dy
        addSample(timeUs, rawX, rawY)
        val v = velocity() // counts/s，可能為 null

        val ratio: Double = when (type) {
            Type.FLAT -> 1.0 + (systemSpeed + 7) * 2.5 / 14.0
            Type.LEGACY -> {
                val scale = 2.0.pow(systemSpeed / 4.0)
                if (v == null) scale else {
                    val speed = v * scale
                    when {
                        speed >= 3000.0 -> scale * 3.0
                        speed > 500.0 -> scale * (1 + (speed - 500.0) / 2500.0 * 2.0)
                        else -> scale
                    }
                }
            }
            Type.CURVED -> {
                val common = 0.64 * SENSITIVITY_FACTORS[systemSpeed + 7] / 10.0
                if (v == null) common * SEGMENTS[0][1] else {
                    val mm = v / MOUSE_CPI * 25.4
                    val seg = SEGMENTS.first { mm <= it[0] }
                    common * (seg[1] + seg[2] / mm)
                }
            }
        }
        val k = ratio * sensitivity
        return (dx * k).toFloat() to (dy * k).toFloat()
    }

    private fun addSample(t: Long, x: Double, y: Double) {
        ts[head] = t; xs[head] = x; ys[head] = y
        head = (head + 1) % MAX_SAMPLES
        if (count < MAX_SAMPLES) count++
    }

    /** LSQ2：對最近 100ms 的位置做二次（樣本少時一次）最小平方擬合，取最新點的斜率 */
    private fun velocity(): Double? {
        val newest = (head - 1 + MAX_SAMPLES) % MAX_SAMPLES
        val tNew = ts[newest]
        var n = 0
        // 收集視窗內樣本（由新到舊）
        val tt = DoubleArray(count); val px = DoubleArray(count); val py = DoubleArray(count)
        for (i in 0 until count) {
            val idx = (newest - i + MAX_SAMPLES) % MAX_SAMPLES
            val age = tNew - ts[idx]
            if (age > HORIZON_US) break
            tt[n] = -age / 1e6; px[n] = xs[idx]; py[n] = ys[idx]; n++
        }
        if (n < 2) return null
        val vx = slope(tt, px, n, if (n >= 3) 2 else 1) ?: return null
        val vy = slope(tt, py, n, if (n >= 3) 2 else 1) ?: return null
        return sqrt(vx * vx + vy * vy)
    }

    /** 擬合 y = b0 + b1 t (+ b2 t²)，回傳 b1（t=0 即最新點的速度） */
    private fun slope(t: DoubleArray, y: DoubleArray, n: Int, degree: Int): Double? {
        if (degree == 1) {
            var st = 0.0; var sy = 0.0; var stt = 0.0; var sty = 0.0
            for (i in 0 until n) { st += t[i]; sy += y[i]; stt += t[i] * t[i]; sty += t[i] * y[i] }
            val d = n * stt - st * st
            if (d == 0.0) return null
            return (n * sty - st * sy) / d
        }
        // 正規方程 3x3
        val s = DoubleArray(5); val r = DoubleArray(3)
        for (i in 0 until n) {
            var p = 1.0
            for (k in 0..4) { s[k] += p; if (k < 3) r[k] += p * y[i]; p *= t[i] }
        }
        val m = arrayOf(
            doubleArrayOf(s[0], s[1], s[2], r[0]),
            doubleArrayOf(s[1], s[2], s[3], r[1]),
            doubleArrayOf(s[2], s[3], s[4], r[2]),
        )
        for (c in 0 until 3) {
            var piv = c
            for (rr in c + 1 until 3) if (kotlin.math.abs(m[rr][c]) > kotlin.math.abs(m[piv][c])) piv = rr
            if (kotlin.math.abs(m[piv][c]) < 1e-18) return slope(t, y, n, 1)
            val tmp = m[c]; m[c] = m[piv]; m[piv] = tmp
            for (rr in 0 until 3) if (rr != c) {
                val f = m[rr][c] / m[c][c]
                for (k in c until 4) m[rr][k] -= f * m[c][k]
            }
        }
        return m[1][3] / m[1][1]
    }

    companion object {
        private const val MAX_SAMPLES = 20
        private const val HORIZON_US = 100_000L
        private const val ASSUME_STOPPED_US = 40_000L
        private const val STOP_US = 500_000L
        private const val MOUSE_CPI = 800.0

        /** maxSpeed(mm/s), baseGain, reciprocal */
        private val SEGMENTS = arrayOf(
            doubleArrayOf(32.002, 3.19, 0.0),
            doubleArrayOf(52.83, 4.79, -51.254),
            doubleArrayOf(119.124, 7.28, -182.737),
            doubleArrayOf(Double.POSITIVE_INFINITY, 15.04, -1107.556),
        )
        private val SENSITIVITY_FACTORS =
            doubleArrayOf(1.0, 2.0, 4.0, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0, 12.0, 13.0, 14.0, 16.0, 18.0, 20.0)

        /** 依系統版本猜測；Android 15 兩種都有可能，請用自動校準確認 */
        fun defaultType(accelEnabled: Boolean = true): Type = when {
            Build.VERSION.SDK_INT >= 36 -> if (accelEnabled) Type.CURVED else Type.FLAT
            else -> Type.LEGACY
        }
    }
}
