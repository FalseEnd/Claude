package com.falseend.mousecursor

import kotlin.math.hypot
import kotlin.math.pow

/**
 * 把滑鼠原始位移 (counts) 換算成螢幕像素，盡量模擬 Android 的游標加速
 * （InputReader 的 VelocityControl：scale = 2^(speed/4)，速度 500~3000 之間線性加速到 3 倍）。
 * 不同手機/版本曲線不同，所以另外提供「靈敏度」倍率與自動校準。
 */
class PointerModel {
    /** 系統設定 pointer_speed，-7..7 */
    var systemSpeed = 0
    var sensitivity = 1f
    var acceleration = true

    private var lastUs = 0L
    private var velocity = 0.0

    fun transform(dx: Int, dy: Int, timeUs: Long): Pair<Float, Float> {
        val base = 2.0.pow(systemSpeed / 4.0)
        var scale = base * sensitivity
        if (acceleration) {
            val dtUs = timeUs - lastUs
            val dist = hypot(dx.toDouble(), dy.toDouble())
            if (dtUs in 1..100_000) {
                val inst = dist / (dtUs / 1e6)
                velocity = velocity * 0.5 + inst * 0.5
            } else {
                velocity = 0.0
            }
            val speed = velocity * base
            val factor = when {
                speed <= LOW -> 1.0
                speed >= HIGH -> ACCEL
                else -> 1.0 + (speed - LOW) / (HIGH - LOW) * (ACCEL - 1.0)
            }
            scale *= factor
        }
        lastUs = timeUs
        return (dx * scale).toFloat() to (dy * scale).toFloat()
    }

    companion object {
        private const val LOW = 500.0
        private const val HIGH = 3000.0
        private const val ACCEL = 3.0
    }
}
