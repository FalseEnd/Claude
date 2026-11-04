package tw.xiaoliangkou.app

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tw.xiaoliangkou.app.core.Nearby
import tw.xiaoliangkou.app.data.RealtimeClient
import tw.xiaoliangkou.app.data.RemoteRepository
import tw.xiaoliangkou.app.data.Session
import tw.xiaoliangkou.app.data.SessionManager
import tw.xiaoliangkou.app.data.SupabaseClient
import tw.xiaoliangkou.app.ui.Derived
import tw.xiaoliangkou.app.ui.StatusText
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.Collections

/**
 * 藍牙偵測「就在身邊」：代號（每 15 分鐘換、只有對方認得出來）、判斷在不在身邊、
 * 廣播和掃描（Robolectric 的假藍牙）、在身邊時不跳標點的通知、畫面上的字
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NearbyTest {
    private val app = RuntimeEnvironment.getApplication()
    private val couple = "8f14e45f-ceea-467a-9575-b3a0b0c3d6b1"
    private val me = "u-1"
    private val partner = "u-2"
    private val key = Nearby.key(couple)
    private val t0 = Instant.parse("2026-11-04T10:07:00Z")
    private var clock = t0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() = scope.cancel()

    // ================================================================ 代號

    @Test fun tokens() {
        val w = Nearby.window(t0)
        val a = Nearby.token(key, me, w)
        assertEquals(Nearby.TOKEN_BYTES, a.size)
        assertArrayEquals("同樣的輸入同樣的代號", a, Nearby.token(Nearby.key(couple), me, w))
        assertFalse("兩個人的代號不一樣", a.contentEquals(Nearby.token(key, partner, w)))
        assertFalse("每 15 分鐘換一次", a.contentEquals(Nearby.token(key, me, w + 1)))
        assertFalse("別對情侶的不一樣", a.contentEquals(Nearby.token(Nearby.key("another-couple"), me, w)))
        // 時段的邊界
        assertEquals(Nearby.window(Instant.parse("2026-11-04T10:00:00Z")), Nearby.window(Instant.parse("2026-11-04T10:14:59Z")))
        assertEquals(Nearby.window(Instant.parse("2026-11-04T10:00:00Z")) + 1, Nearby.window(Instant.parse("2026-11-04T10:15:00Z")))
        assertEquals(Instant.parse("2026-11-04T10:15:00Z"), Nearby.nextWindowAt(t0))
    }

    @Test fun partnerTokensAllowClockSkew() {
        val w = Nearby.window(t0)
        val expected = Nearby.partnerTokens(key, partner, t0)
        for (dw in -1L..1L) assertTrue("前後各多認一個時段", Nearby.matches(Nearby.token(key, partner, w + dw), expected))
        assertFalse("太舊的不認", Nearby.matches(Nearby.token(key, partner, w - 2), expected))
        assertFalse("自己的不算", Nearby.matches(Nearby.token(key, me, w), expected))
        assertTrue("後面多了東西也認得", Nearby.matches(Nearby.token(key, partner, w) + byteArrayOf(1, 2), expected))
        assertFalse(Nearby.matches(Nearby.token(key, partner, w).copyOf(5), expected))
        assertFalse(Nearby.matches(null, expected))
    }

    @Test fun tracker() {
        val tr = Nearby.Tracker()
        assertFalse("太弱的訊號不算", tr.seen(t0, Nearby.MIN_RSSI - 1))
        assertNull(tr.since)
        assertTrue(tr.seen(t0, -70))
        assertEquals(t0, tr.since)
        assertFalse("一直收到不用一直通知", tr.seen(t0.plusSeconds(30), -60))
        assertFalse("還沒太久", tr.tick(t0.plusSeconds(30 + 239)))
        assertTrue("4 分鐘都沒收到就不算了", tr.tick(t0.plusSeconds(30 + 241)))
        assertNull(tr.since)
        assertFalse(tr.tick(t0.plusSeconds(600)))
        assertTrue("又收到了：重新開始算", tr.seen(t0.plusSeconds(900), -80))
        assertEquals(t0.plusSeconds(900), tr.since)
        assertTrue(tr.reset())
        assertFalse(tr.reset())
    }

    // ================================================================ 廣播和掃描（假藍牙）

    private val changes: MutableList<Instant?> = Collections.synchronizedList(mutableListOf())
    private fun radar() = tw.xiaoliangkou.app.service.NearbyRadar(app, scope, { clock }) { changes += it }
    private val adapter get() = app.getSystemService(BluetoothManager::class.java).adapter

    @Before fun bluetoothOn() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE, true)
        shadowOf(adapter).setEnabled(true)
        shadowOf(adapter).setIsMultipleAdvertisementSupported(true)
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE)
    }

    @Test fun advertisesAndScans() {
        val r = radar()
        r.update(tw.xiaoliangkou.app.service.NearbyRadar.Config(couple, me, partner, advertise = true))
        val scans = shadowOf(adapter.bluetoothLeScanner).activeScans
        assertEquals(1, scans.size)
        assertEquals("只收小倆口的訊號（螢幕關著時系統只讓有條件的掃描繼續）", Nearby.SERVICE_UUID, scans.single().scanFilters().single().serviceDataUuid?.uuid)
        assertEquals(1, shadowOf(adapter.bluetoothLeAdvertiser).advertisementRequestCount)
        r.stop()
        assertTrue(shadowOf(adapter.bluetoothLeScanner).activeScans.isEmpty())
    }

    @Test fun pausedSharingOnlyListens() {
        radar().update(tw.xiaoliangkou.app.service.NearbyRadar.Config(couple, me, partner, advertise = false))
        assertEquals(1, shadowOf(adapter.bluetoothLeScanner).activeScans.size)
        assertEquals("暫停分享時不廣播，對方偵測不到", 0, shadowOf(adapter.bluetoothLeAdvertiser).advertisementRequestCount)
    }

    /** 掃描收到的：只有對方的代號、夠強的訊號才算 */
    @Test fun recognisesOnlyThePartner() {
        val r = radar()
        r.update(tw.xiaoliangkou.app.service.NearbyRadar.Config(couple, me, partner, advertise = true))
        val w = Nearby.window(clock)
        r.onScan(Nearby.token(key, me, w), -60)
        r.onScan(Nearby.token(Nearby.key("someone-else"), partner, w), -60)
        r.onScan(Nearby.token(key, partner, w), -99)
        assertTrue("自己的、別人的、太遠的都不算", changes.isEmpty())
        r.onScan(Nearby.token(key, partner, w), -70)
        assertEquals(listOf<Instant?>(t0), changes)
        r.onScan(Nearby.token(key, partner, w), -65)
        assertEquals("已經在身邊了，不用再通知", 1, changes.size)
    }

    /** 從假的掃描結果走一遍：藍牙收到的封包裡，128 位元 UUID 的 service data 後面是代號 */
    @Test fun scanResultsReachTheTracker() {
        val r = radar()
        r.update(tw.xiaoliangkou.app.service.NearbyRadar.Config(couple, me, partner, advertise = true))
        val token = Nearby.token(key, partner, Nearby.window(clock))
        val uuid = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(Nearby.SERVICE_UUID.leastSignificantBits).putLong(Nearby.SERVICE_UUID.mostSignificantBits).array()
        val packet = byteArrayOf((1 + 16 + token.size).toByte(), 0x21) + uuid + token
        val record = ScanRecord::class.java.getDeclaredMethod("parseFromBytes", ByteArray::class.java).apply { isAccessible = true }
            .invoke(null, packet) as ScanRecord
        val result = ScanResult(adapter.getRemoteDevice("00:11:22:33:44:55"), record, -72, 0L)
        shadowOf(adapter.bluetoothLeScanner).activeScans.single().scanCallback()!!.onScanResult(1, result)
        assertEquals(listOf<Instant?>(t0), changes)
    }

    @Test fun problems() {
        assertNull(tw.xiaoliangkou.app.service.NearbyRadar.problem(app))
        shadowOf(adapter).setEnabled(false)
        assertEquals("藍牙沒有打開", tw.xiaoliangkou.app.service.NearbyRadar.problem(app))
        // 藍牙關了：不掃描，原本在身邊的也不算了
        val r = radar()
        r.update(tw.xiaoliangkou.app.service.NearbyRadar.Config(couple, me, partner, advertise = true))
        assertTrue(shadowOf(adapter.bluetoothLeScanner).activeScans.isEmpty())
        shadowOf(adapter).setEnabled(true)
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_SCAN)
        assertEquals("要允許「附近的裝置」權限", tw.xiaoliangkou.app.service.NearbyRadar.problem(app))
    }

    @Test fun turningOffForgetsTheState() {
        val r = radar()
        r.update(tw.xiaoliangkou.app.service.NearbyRadar.Config(couple, me, partner, advertise = true))
        r.onScan(Nearby.token(key, partner, Nearby.window(clock)), -70)
        r.update(null)
        assertEquals(listOf(t0, null), changes)
    }

    // ================================================================ 在身邊時：畫面上的字、標點的通知

    @Test fun texts() {
        val demo = tw.xiaoliangkou.app.data.DemoRepository(scope).also { it.running = false }
        val s = demo.state.value
        assertTrue(Derived.distanceText(s, "相距")!!.startsWith("相距 "))
        val near = s.copy(partnerNearSince = t0)
        assertEquals("就在你身邊 💞", Derived.distanceText(near, "相距"))
        assertTrue(StatusText.of(near).item(StatusText.PLACE)!!.text.endsWith(" · 就在你身邊 💞"))
        // 沒分享位置，但藍牙偵測到在身邊
        val noLocation = near.copy(partnerSharing = near.partnerSharing!!.copy(shareLocation = false))
        assertEquals("💞 就在你身邊", StatusText.of(noLocation).item(StatusText.PLACE).toString())
    }

    /** 對方就在身邊時，標點的抵達、離開不跳通知（其他的照常） */
    @Test fun placeNotificationsAreQuietWhenTogether() = runBlocking {
        val realtime = RealtimeClient(scope)
        val session = SessionManager("http://127.0.0.1:9", "sb_publishable_test", Session("a", "r", System.currentTimeMillis() / 1000 + 3600, "u-1", null))
        val shown = Collections.synchronizedList(mutableListOf<String>())
        val repo = RemoteRepository(SupabaseClient("http://127.0.0.1:9", "sb_publishable_test", session).api, realtime, scope) { shown += it.kind }
        @Suppress("UNCHECKED_CAST")
        val events = RealtimeClient::class.java.getDeclaredField("_events").apply { isAccessible = true }.get(realtime) as MutableSharedFlow<RealtimeClient.Event>
        withTimeout(5_000) { while (events.subscriptionCount.value == 0) delay(20) }
        fun note(id: Long, kind: String) = RealtimeClient.Event.Notification(id, kind, "小雨 已抵達 公司", "", null)
        repo.setPartnerNearby(t0)
        events.emit(note(1, "place"))
        events.emit(note(2, "comfort"))
        withTimeout(5_000) { while (shown.isEmpty()) delay(20) }
        delay(200)
        assertEquals(listOf("comfort"), shown.toList())
        repo.setPartnerNearby(null)
        events.emit(note(3, "place"))
        withTimeout(5_000) { while (shown.size < 2) delay(20) }
        assertEquals(listOf("comfort", "place"), shown.toList())
        repo.close()
    }
}
