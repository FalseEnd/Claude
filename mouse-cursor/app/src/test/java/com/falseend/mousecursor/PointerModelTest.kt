package com.falseend.mousecursor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PointerModelTest {
    /** 以固定速度移動 [ms] 毫秒（1000Hz 回報率），回傳總位移 px */
    private fun run(type: PointerModel.Type, countsPerMs: Int, ms: Int, speed: Int = 0): Float {
        val m = PointerModel().apply { this.type = type; systemSpeed = speed }
        var sum = 0f
        for (i in 1..ms) sum += m.transform(countsPerMs, 0, i * 1000L).first
        return sum
    }

    @Test fun legacySlowIsUnscaled() {
        // 1 count/ms = 1000 counts/s → 介於 500~3000，倍率 1 + 500/2500*2 = 1.4
        val mid = run(PointerModel.Type.LEGACY, 1, 200)
        assertEquals(280f, mid, 280f * 0.03f)
        // 每 4ms 1 count = 250 counts/s → 低於 500，不加速
        val m = PointerModel().apply { type = PointerModel.Type.LEGACY }
        var slow = 0f
        for (i in 1..90) slow += m.transform(1, 0, i * 4000L).first
        assertEquals(90f, slow, 0.01f)
    }

    @Test fun legacyFastIsTripled() {
        val fast = run(PointerModel.Type.LEGACY, 10, 200) // 10000 counts/s
        assertEquals(2000f * 3f, fast, 2000f * 3f * 0.03f)
    }

    @Test fun curvedFastMatchesTopSegment() {
        // 10000 counts/s @800CPI = 317.5 mm/s → 第四段
        val common = 0.64 * 10 / 10.0
        val mm = 10000 / 800.0 * 25.4
        val gain = common * (15.04 - 1107.556 / mm)
        val total = run(PointerModel.Type.CURVED, 10, 200)
        assertEquals((2000 * gain).toFloat(), total, (2000 * gain * 0.03).toFloat())
    }

    @Test fun flatIsConstant() {
        val a = run(PointerModel.Type.FLAT, 1, 100)
        val b = run(PointerModel.Type.FLAT, 10, 100)
        assertEquals(a * 10, b, 0.01f)
        assertEquals(100f * 2.25f, a, 0.01f) // speed 0 → 1 + 7*2.5/14
    }

    @Test fun speedSettingScalesLegacy() {
        val base = run(PointerModel.Type.LEGACY, 1, 100, speed = 0)
        val fast = run(PointerModel.Type.LEGACY, 1, 100, speed = 4)
        assertTrue(fast > base * 1.8f)
    }
}
