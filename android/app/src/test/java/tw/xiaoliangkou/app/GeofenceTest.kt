package tw.xiaoliangkou.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.xiaoliangkou.app.core.Geo
import tw.xiaoliangkou.app.core.GeofenceTracker
import tw.xiaoliangkou.app.core.GeofenceTracker.Fix
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** 進出標點：定位飄來飄去時不會一直「離開、抵達」，真的進出時還是會通知 */
class GeofenceTest {
    private val home = Geo.Fence("home", 25.0330, 121.5654, 100.0)
    private val fences = listOf(home)
    private val minute = 60_000L

    /** 離家 [m] 公尺（往 [deg] 度的方向，0 是往東） */
    private fun at(m: Double, accuracy: Double, t: Long, deg: Double = 0.0): Fix {
        val rad = Math.toRadians(deg)
        return Fix(home.lat + m * sin(rad) / 111_000.0, home.lng + m * cos(rad) / 100_800.0, accuracy, t)
    }

    private fun GeofenceTracker.feed(fix: Fix) = update(fences, fix).transitions

    @Test fun clearArrivalAndDepartureAreImmediate() {
        val g = GeofenceTracker()
        assertTrue(g.feed(at(400.0, 20.0, 0)).isEmpty())
        // GPS 很準、整個誤差圈都在標點裡：馬上算抵達
        assertEquals(listOf(Geo.Transition("home", true)), g.feed(at(40.0, 15.0, minute)))
        assertEquals(setOf("home"), g.insideNow)
        // 待了一陣子，走到很遠：馬上算離開
        assertTrue(g.feed(at(60.0, 20.0, 30 * minute)).isEmpty())
        assertEquals(listOf(Geo.Transition("home", false)), g.feed(at(700.0, 20.0, 31 * minute)))
        assertTrue(g.insideNow.isEmpty())
    }

    @Test fun oneDriftingFixIsNotALeave() {
        val g = GeofenceTracker(setOf("home"))
        // 室內定位突然飄到 260 公尺外（誤差 40）：先等確認
        assertTrue(g.feed(at(260.0, 40.0, 0)).isEmpty())
        assertTrue(g.waiting)
        // 下一次又回到家裡：不算離開
        assertTrue(g.feed(at(30.0, 60.0, 2 * minute)).isEmpty())
        assertFalse(g.waiting)
        assertEquals(setOf("home"), g.insideNow)
    }

    @Test fun stayingHomeWithANoisyPhoneNeverFlaps() {
        val g = GeofenceTracker(setOf("home"))
        val rnd = Random(42)
        var events = 0
        // 一整天每分鐘一次，省電定位誤差 50～150 公尺，位置在 0～220 公尺之間亂飄
        for (i in 0 until 24 * 60) {
            val acc = 50.0 + rnd.nextDouble() * 100.0
            val off = rnd.nextDouble() * minOf(220.0, acc * 1.6)
            events += g.feed(at(off, acc, i * minute, rnd.nextDouble() * 360)).size
        }
        assertEquals("待在家裡不應該有任何進出", 0, events)
    }

    @Test fun uncertainArrivalIsConfirmedTwoMinutesLater() {
        val g = GeofenceTracker()
        // 中心在標點裡，但誤差 90 公尺：先不算
        assertTrue(g.feed(at(70.0, 90.0, 0)).isEmpty())
        assertTrue(g.feed(at(60.0, 80.0, minute)).isEmpty())
        // 兩分鐘後還在：算抵達
        assertEquals(listOf(Geo.Transition("home", true)), g.feed(at(50.0, 30.0 + 60.0, 2 * minute)))
    }

    @Test fun departureWithPoorAccuracyIsConfirmed() {
        val g = GeofenceTracker(setOf("home"))
        // 走到 350 公尺外，誤差 80：整個誤差圈在緩衝外，但不夠遠，要確認
        assertTrue(g.feed(at(350.0, 80.0, 0)).isEmpty())
        assertTrue(g.feed(at(420.0, 80.0, minute)).isEmpty())
        assertEquals(listOf(Geo.Transition("home", false)), g.feed(at(500.0, 80.0, 2 * minute)))
    }

    @Test fun boundaryNoiseInsideTheBufferIsIgnored() {
        val g = GeofenceTracker(setOf("home"))
        // 半徑 100、緩衝 50：誤差圈還碰得到 150 公尺內就不算離開
        for (i in 0..10) assertTrue(g.feed(at(150.0 + (i % 6) * 5, 30.0, i * 3 * minute)).isEmpty())
        assertEquals(setOf("home"), g.insideNow)
    }

    @Test fun teleportingFixesAndBadOnesAreIgnored() {
        val g = GeofenceTracker(setOf("home"))
        g.feed(at(20.0, 15.0, 0))
        // 10 秒後跳到 3 公里外：不可能，不用
        assertTrue(g.feed(at(3000.0, 20.0, 10_000)).isEmpty())
        assertFalse(g.waiting)
        // 精確度太差、比上一次舊：不用
        assertTrue(g.feed(at(2000.0, 400.0, minute)).isEmpty())
        assertTrue(g.feed(at(2000.0, 20.0, -minute)).isEmpty())
        assertEquals(setOf("home"), g.insideNow)
        // 過了很久真的在 3 公里外：算離開
        assertEquals(listOf(Geo.Transition("home", false)), g.feed(at(3000.0, 20.0, 20 * minute)))
    }

    @Test fun rightAfterArrivingALeaveMustBeConfirmed() {
        val g = GeofenceTracker()
        assertEquals(1, g.feed(at(10.0, 10.0, 0)).size)
        // 剛到 3 分鐘就一個很遠的定位：要確認
        assertTrue(g.feed(at(900.0, 20.0, 3 * minute)).isEmpty())
        assertTrue(g.waiting)
        // 兩分鐘後還是在外面：真的走了
        assertEquals(listOf(Geo.Transition("home", false)), g.feed(at(950.0, 20.0, 5 * minute)))
        assertTrue(g.insideNow.isEmpty())
    }

    @Test fun removedPlacesAreForgotten() {
        val g = GeofenceTracker(setOf("home", "gone"))
        g.update(fences, at(10.0, 10.0, 0))
        assertEquals(setOf("home"), g.insideNow)
    }
}
