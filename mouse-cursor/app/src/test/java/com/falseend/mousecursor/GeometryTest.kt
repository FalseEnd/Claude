package com.falseend.mousecursor

import com.falseend.mousecursor.Geometry.ROT_0
import com.falseend.mousecursor.Geometry.ROT_180
import com.falseend.mousecursor.Geometry.ROT_270
import com.falseend.mousecursor.Geometry.ROT_90
import org.junit.Assert.assertEquals
import org.junit.Test

class GeometryTest {
    private val pw = 1080f; private val ph = 2400f // 直向

    private fun dims(rot: Int) = if (rot == ROT_90 || rot == ROT_270) ph to pw else pw to ph

    private fun rot(x: Float, y: Float, from: Int, to: Int): Pair<Float, Float> {
        val (ow, oh) = dims(from); val (nw, nh) = dims(to)
        return Geometry.rotate(x, y, ow, oh, from, nw, nh, to)
    }

    @Test fun portraitToLandscape90() {
        // 自然方向左上角附近的點，在 ROTATION_90（逆時針轉）時會跑到左下
        val (x, y) = rot(100f, 200f, ROT_0, ROT_90)
        assertEquals(200f, x, 0.01f)
        assertEquals(1080f - 100f - 1f, y, 0.01f)
    }

    @Test fun cornerStaysInBounds() {
        for (r in 0..3) {
            val (w, h) = dims(r)
            val (x, y) = rot(0f, 0f, ROT_0, r)
            assert(x in 0f..w - 1 && y in 0f..h - 1) { "rot $r -> $x,$y" }
        }
    }

    @Test fun roundTripAllRotations() {
        val pts = listOf(0f to 0f, 1079f to 2399f, 333.3f to 1777.7f, 540f to 1200f)
        for (a in 0..3) for (b in 0..3) for ((x0, y0) in pts) {
            // 先把直向的點轉到 a，當成起點
            val (sx, sy) = rot(x0, y0, ROT_0, a)
            val (mx, my) = rot(sx, sy, a, b)
            val (bx, by) = rot(mx, my, b, a)
            assertEquals("x $a->$b", sx, bx, 0.01f)
            assertEquals("y $a->$b", sy, by, 0.01f)
        }
    }

    @Test fun twoStepsEqualsOneStep() {
        val (ax, ay) = rot(400f, 900f, ROT_0, ROT_90)
        val (bx, by) = rot(ax, ay, ROT_90, ROT_180)
        val (cx, cy) = rot(400f, 900f, ROT_0, ROT_180)
        assertEquals(cx, bx, 0.01f); assertEquals(cy, by, 0.01f)
    }
}
