package tw.xiaoliangkou.app

import android.Manifest
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
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

    @Test fun fallsBackToTheSystemWhenGoogleCannotHelp() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(lm).setLocationEnabled(true)
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        shadowOf(lm).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        shadowOf(lm).simulateLocation(loc(LocationManager.NETWORK_PROVIDER, 25.0330, 121.5654))
        val got = Locator.current(app, googleMs = 300, systemMs = 1_000)!!
        assertEquals(25.0330, got.latitude, 1e-6)
        assertEquals(121.5654, got.longitude, 1e-6)
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

/** 今日步數：服務晚啟動、重新開機、沒有計步器只能自己數的手機 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StepsTodayTest {
    private val settings get() = tw.xiaoliangkou.app.data.SettingsStore(RuntimeEnvironment.getApplication())

    @Test fun countsFromTheFirstReadingAndCarriesAcrossReboots() = runBlocking {
        val st = settings
        st.clear()
        assertEquals(0, st.stepsToday(100, 5_000))
        assertEquals(1_200, st.stepsToday(100, 6_200))
        // 重新開機：計步器從 0 開始，今天已經走的 1,200 步不能不見
        assertEquals(1_200 + 300, st.stepsToday(100, 300))
        assertEquals(1_200 + 800, st.stepsToday(100, 800))
    }

    @Test fun newDayStartsFromYesterdaysLastReading() = runBlocking {
        val st = settings
        st.clear()
        st.stepsToday(100, 10_000)
        st.stepsToday(100, 12_000)
        // 隔天中午服務才讀到：從昨天最後看到的 12,000 算起，不會從中午才開始算
        assertEquals(3_000, st.stepsToday(101, 15_000))
        // 隔了好幾天：不知道之前走了多少，就從現在算起
        assertEquals(0, st.stepsToday(105, 20_000))
    }
}
