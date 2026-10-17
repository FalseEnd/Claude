package tw.xiaoliangkou.app

import android.Manifest
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tw.xiaoliangkou.app.core.StepCounter
import tw.xiaoliangkou.app.service.Locator
import tw.xiaoliangkou.app.service.ShareService

/** 定位：沒有 Google 服務也要定位得到；權限、定位開關的狀態；網速文字 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocatorTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val lm get() = app.getSystemService(LocationManager::class.java)

    private fun loc(provider: String, lat: Double, lng: Double, ageMs: Long = 0) = Location(provider).apply {
        latitude = lat; longitude = lng; accuracy = 20f
        time = System.currentTimeMillis() - ageMs
        elapsedRealtimeNanos = (SystemClock.elapsedRealtime() - ageMs) * 1_000_000
    }

    @Test fun statusFollowsPermissionAndLocationSwitch() {
        assertEquals(Locator.Status.NO_PERMISSION, Locator.status(app))
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(lm).setLocationEnabled(false)
        assertEquals(Locator.Status.OFF, Locator.status(app))
        shadowOf(lm).setLocationEnabled(true)
        assertEquals(Locator.Status.OK, Locator.status(app))
    }

    @Test fun noPermissionNoLocation() = runBlocking {
        assertNull(Locator.current(app, googleMs = 200, systemMs = 200))
    }

    @Test fun systemLocationWinsTheRaceWithoutGoogle() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(lm).setLocationEnabled(true)
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(lm).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        val started = System.currentTimeMillis()
        // 在背景跑，主執行緒要一直處理定位的回呼
        val job = CoroutineScope(Dispatchers.Default).async { Locator.current(app, googleMs = 300, systemMs = 8_000) }
        val end = started + 6_000
        var sent = false
        while (!job.isCompleted && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            // GPS 在室內等不到；網路定位先回來
            if (!sent && System.currentTimeMillis() - started > 800) {
                shadowOf(lm).simulateLocation(loc(LocationManager.NETWORK_PROVIDER, 25.0330, 121.5654))
                sent = true
            }
            Thread.sleep(20)
        }
        val got = runBlocking { job.await() }!!
        assertEquals(LocationManager.NETWORK_PROVIDER, got.provider)
        assertEquals(25.0330, got.latitude, 1e-6)
        assertTrue("不用等到 8 秒的期限", System.currentTimeMillis() - started < 5_000)
    }

    @Test fun lastKnownIgnoresOldFixes() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(lm).setLocationEnabled(true)
        shadowOf(lm).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        shadowOf(lm).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, loc(LocationManager.NETWORK_PROVIDER, 24.0, 121.0, ageMs = 60 * 60_000L))
        assertNull("一小時前的位置不算", Locator.lastKnown(app))
        shadowOf(lm).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, loc(LocationManager.NETWORK_PROVIDER, 24.5, 121.5, ageMs = 60_000L))
        assertEquals(24.5, Locator.lastKnown(app)!!.latitude, 1e-6)
    }

    @Test fun networkSpeedText() {
        assertEquals("約 30 Mbps", ShareService.Net.speedLabel(30_000))
        assertEquals("約 146 Mbps", ShareService.Net.speedLabel(145_600))
        assertEquals("約 2 Mbps", ShareService.Net.speedLabel(1_500))
        assertEquals("約 384 Kbps", ShareService.Net.speedLabel(384))
        assertNull(ShareService.Net.speedLabel(0))
    }
}

/** 今日步數：服務晚啟動、重新開機、沒有計步器只能自己數的手機、從舊版更新 */
class StepCounterTest {
    private val h = 60 * 60_000L
    private val day = 100L
    private val midnight = 1_000_000_000_000L
    private val bootedYesterday = midnight - 30 * h
    private var state: StepCounter.State? = null

    private fun read(total: Long, session: String = "boot-1", since: Long = bootedYesterday, at: Long = midnight + 12 * h, d: Long = day): Long {
        val dayStart = midnight + (d - day) * 24 * h
        val (next, steps) = StepCounter.next(state, StepCounter.Reading(d, total, session, since, at, dayStart))
        state = next
        return steps
    }

    @Test fun countsFromTheFirstReading() {
        assertEquals(0, read(5_000))
        assertEquals(1_200, read(6_200))
    }

    @Test fun firstReadingAfterBootingTodayCountsEverything() {
        assertEquals(3_000, read(3_000, since = midnight + 7 * h))
    }

    @Test fun rebootKeepsTodaysSteps() {
        read(5_000); read(5_400)
        // 重新開機後走了 6,000 步才讀到（數字沒有變小，但開機時間不一樣）
        assertEquals(400 + 6_000, read(6_000, session = "boot-2", since = midnight + 13 * h))
        assertEquals(400 + 6_500, read(6_500, session = "boot-2", since = midnight + 13 * h))
    }

    @Test fun detectorRestartsWithTheService() {
        read(0, session = "svc-a"); read(300, session = "svc-a")
        assertEquals(305, read(5, session = "svc-b"))
    }

    @Test fun updatingFromTheOldVersionDoesNotGetStuckAtZero() {
        // 舊版只存了基準 5,000；之後手機重新開機，計步器從 0 開始
        state = StepCounter.State(day, base = 5_000, carry = 0, last = 5_000, lastAt = 0, session = null)
        assertEquals(300, read(300, since = midnight + 8 * h))
        assertEquals(800, read(800, since = midnight + 8 * h))
        assertEquals(2_000, read(2_000, since = midnight + 8 * h))
    }

    @Test fun newDayStartsFromLateLastNightOnly() {
        read(10_000, at = midnight - 2 * h, d = day - 1)
        read(12_000, at = midnight - 1 * h, d = day - 1)
        // 昨晚 11 點還有讀到：從 12,000 算起，早上服務晚啟動也不會少算
        assertEquals(3_000, read(15_000, at = midnight + 12 * h))

        state = null
        read(10_000, at = midnight - 8 * h, d = day - 1)
        // 昨天下午之後就沒讀到：不知道晚上走了多少，從現在算起
        assertEquals(0, read(15_000, at = midnight + 12 * h))
    }

    @Test fun rebootOvernightCountsSinceBoot() {
        read(12_000, at = midnight - 1 * h, d = day - 1)
        assertEquals(2_500, read(2_500, session = "boot-2", since = midnight + 2 * h))
    }

    @Test fun neverNegative() {
        read(5_000)
        assertEquals(0, read(5_000))
    }
}
