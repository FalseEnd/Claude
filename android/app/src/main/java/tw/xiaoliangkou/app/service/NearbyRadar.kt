package tw.xiaoliangkou.app.service

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tw.xiaoliangkou.app.core.Nearby
import java.time.Instant

/**
 * 藍牙偵測「就在身邊」（規則見 [Nearby]）：背景服務裡同時廣播自己的代號、掃描對方的代號。
 * - 廣播：低耗電模式、中等功率（大約十公尺），不能連線，只有 service data；暫停分享時不廣播（對方偵測不到你）
 * - 掃描：只收小倆口的 service data（螢幕關著時系統只讓有條件的掃描繼續），低耗電模式
 * - 每 15 分鐘換代號時，廣播和掃描都重開一次（掃描開太久系統會把它降級）
 * - 每 30 秒看一下是不是太久沒收到對方的訊號了
 * 結果用 [onChange] 告訴資料來源：這次在一起從什麼時候開始，不在身邊是 null
 */
class NearbyRadar(
    private val context: Context,
    private val scope: CoroutineScope,
    private val now: () -> Instant = Instant::now,
    private val onChange: (Instant?) -> Unit,
) {
    /** 這一對的 couple id、自己和對方的 user id；[advertise] false 是只聽不廣播（暫停分享時） */
    data class Config(val coupleId: String, val myId: String, val partnerId: String, val advertise: Boolean)

    companion object {
        const val CHECK_MS = 30_000L

        /** 要問的權限：Android 12 起是「附近的裝置」，以前是位置 */
        val permissions: Array<String>
            get() = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

        fun hasPermission(context: Context) =
            permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

        /** 現在不能偵測的原因（可以就是 null） */
        fun problem(context: Context): String? {
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return "這支手機沒有藍牙低功耗，不能偵測"
            val adapter = adapter(context) ?: return "這支手機沒有藍牙，不能偵測"
            if (!hasPermission(context)) return if (Build.VERSION.SDK_INT >= 31) "要允許「附近的裝置」權限" else "要允許「位置」權限（舊的 Android 掃描藍牙要用）"
            if (!runCatching { adapter.isEnabled }.getOrDefault(false)) return "藍牙沒有打開"
            return null
        }

        private fun adapter(context: Context): BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    }

    private val tracker = Nearby.Tracker()
    private var config: Config? = null
    private var job: Job? = null
    private var scanCallback: ScanCallback? = null
    private var advertiseCallback: AdvertiseCallback? = null
    /** 這個時段對方會廣播的代號（每個時段算一次） */
    private var expected: Pair<Long, List<ByteArray>>? = null

    /** 設定改了（配對、暫停分享、開關）：重新開始；null 是關掉 */
    fun update(c: Config?) {
        if (c == config && job?.isActive == true) return
        config = c
        restart()
    }

    /** 藍牙剛打開、剛允許權限：再試一次 */
    fun refresh() = restart()

    fun stop() = update(null)

    private fun restart() {
        job?.cancel()
        stopRadio()
        val c = config
        if (c == null || problem(context) != null) {
            if (tracker.reset()) onChange(null)
            return
        }
        startRadio(c)
        job = scope.launch {
            var window = Nearby.window(now())
            while (isActive) {
                delay(CHECK_MS)
                if (tracker.tick(now())) onChange(tracker.since)
                val w = Nearby.window(now())
                if (w != window) {
                    window = w
                    stopRadio()
                    startRadio(c)
                }
            }
        }
    }

    /** 收到一則掃描結果（拆出來讓測試直接呼叫） */
    internal fun onScan(serviceData: ByteArray?, rssi: Int) {
        val c = config ?: return
        val at = now()
        val w = Nearby.window(at)
        val tokens = expected?.takeIf { it.first == w }?.second
            ?: Nearby.partnerTokens(Nearby.key(c.coupleId), c.partnerId, at).also { expected = w to it }
        if (Nearby.matches(serviceData, tokens) && tracker.seen(at, rssi)) onChange(tracker.since)
    }

    @SuppressLint("MissingPermission")
    private fun startRadio(c: Config) {
        val adapter = adapter(context) ?: return
        val uuid = ParcelUuid(Nearby.SERVICE_UUID)
        runCatching {
            val scanner = adapter.bluetoothLeScanner ?: return@runCatching
            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) =
                    onScan(result.scanRecord?.getServiceData(uuid), result.rssi)

                override fun onBatchScanResults(results: MutableList<ScanResult>) =
                    results.forEach { onScan(it.scanRecord?.getServiceData(uuid), it.rssi) }
            }
            val filter = ScanFilter.Builder().setServiceData(uuid, ByteArray(0)).build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
            scanner.startScan(listOf(filter), settings, cb)
            scanCallback = cb
        }
        if (!c.advertise) return
        runCatching {
            val advertiser = adapter.bluetoothLeAdvertiser ?: return@runCatching
            val token = Nearby.token(Nearby.key(c.coupleId), c.myId, Nearby.window(now()))
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(false)
                .setTimeout(0)
                .build()
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceData(uuid, token)
                .build()
            val cb = object : AdvertiseCallback() {}
            advertiser.startAdvertising(settings, data, cb)
            advertiseCallback = cb
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopRadio() {
        val adapter = adapter(context)
        scanCallback?.let { cb -> runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) } }
        advertiseCallback?.let { cb -> runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(cb) } }
        scanCallback = null
        advertiseCallback = null
    }
}
