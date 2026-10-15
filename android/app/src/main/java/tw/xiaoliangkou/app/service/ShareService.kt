package tw.xiaoliangkou.app.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tw.xiaoliangkou.app.container
import tw.xiaoliangkou.app.core.Geo
import tw.xiaoliangkou.app.data.RealtimeClient
import tw.xiaoliangkou.app.data.RemoteRepository
import java.time.Instant
import java.time.LocalDate

/**
 * 常駐的前景服務（連自架伺服器時才啟動）：
 * - 保持 WebSocket，讓 App 在背景也收得到抱抱、抵達通知
 * - 分享位置時回報位置，並在手機上判斷是否進出標點
 * - 回報電量、充電、網路、螢幕、勿擾、步數
 */
class ShareService : LifecycleService() {
    companion object {
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, ShareService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ShareService::class.java))
        }

        fun hasLocationPermission(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private lateinit var fused: FusedLocationProviderClient
    private var locationOn = false
    private var lastSent: Location? = null
    private var lastSentAt = 0L
    private var stepTotal: Long? = null
    private var deviceJob: Job? = null
    private var receiversRegistered = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { loc -> lifecycleScope.launch { handleLocation(loc, force = false) } }
        }
    }

    private val changeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = scheduleDeviceReport(5_000)
    }

    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) { stepTotal = event.values[0].toLong() }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            container.realtime.reconnectNow()
            scheduleDeviceReport(3_000)
        }
        override fun onLost(network: Network) = scheduleDeviceReport(3_000)
    }

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        goForeground("連線中…")
        registerSignals()

        // 連線狀態與分享設定改變時，更新常駐通知與定位
        lifecycleScope.launch {
            container.repo.collectLatest { repo ->
                if (repo !is RemoteRepository) { stopLocation(); return@collectLatest }
                repo.state.map { Triple(it.connected, it.partnerName, it.mySharing?.locationLive == true) }
                    .distinctUntilChanged()
                    .collect { (connected, partner, sharingLocation) ->
                        val text = when {
                            !connected -> "連不到伺服器，會自動重試"
                            sharingLocation -> "正在和 $partner 分享位置"
                            else -> "已連線，會即時收到 $partner 的通知"
                        }
                        container.notifier.updateService(text)
                        if (sharingLocation) startLocation() else stopLocation()
                        if (connected) scheduleDeviceReport(1_000)
                    }
            }
        }
        // 對方按「現在在哪？」
        lifecycleScope.launch {
            container.realtime.events.collect { e -> if (e is RealtimeClient.Event.Locate) locateNow() }
        }
        // 每 5 分鐘回報一次手機狀態
        lifecycleScope.launch {
            while (true) {
                delay(5 * 60_000L)
                scheduleDeviceReport(0)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        goForeground(null)
        return START_STICKY
    }

    private fun goForeground(text: String?) {
        var types = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            types = if (hasLocationPermission(this)) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            if (Build.VERSION.SDK_INT >= 34) {
                types = if (hasLocationPermission(this)) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
                }
            }
        }
        val notification = container.notifier.serviceNotification(text ?: "小倆口在背景保持連線")
        runCatching { ServiceCompat.startForeground(this, Notifier.SERVICE_ID, notification, types) }
            .onFailure {
                // 例如背景啟動時沒有背景定位權限：退回不含定位的類型
                val fallback = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
                runCatching { ServiceCompat.startForeground(this, Notifier.SERVICE_ID, notification, fallback) }
            }
    }

    private fun registerSignals() {
        if (receiversRegistered) return
        receiversRegistered = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            if (Build.VERSION.SDK_INT >= 23) addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        }
        ContextCompat.registerReceiver(this, changeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(networkCallback)
        val activityOk = Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        if (activityOk) {
            val sm = getSystemService(SensorManager::class.java)
            sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let { sm.registerListener(stepListener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        }
    }

    // ---------------------------------------------------------------- 手機狀態
    private fun scheduleDeviceReport(delayMs: Long) {
        deviceJob?.cancel()
        deviceJob = lifecycleScope.launch {
            delay(delayMs)
            reportDevice()
        }
    }

    private suspend fun reportDevice() {
        val repo = container.repo.value as? RemoteRepository ?: return
        val st = repo.state.value
        if (st.mySharing?.deviceLive != true || !st.paired) return
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.let { b ->
            val l = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (l >= 0) (l * 100 / scale) else null
        }
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val network = when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            else -> "cellular"
        }
        // Wi-Fi 名稱不上傳：連 Wi-Fi 時用「目前所在的標點」當作名稱（例如「家裡」）
        val here = st.myLocation?.let { loc ->
            st.places.filter { it.watchUser == null || it.watchUser == st.myId }
                .firstOrNull { Geo.distance(it.lat, it.lng, loc.lat, loc.lng) <= it.radiusM }?.name
        }
        val nm = getSystemService(NotificationManager::class.java)
        val silenced = nm != null && nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL
        val interactive = getSystemService(PowerManager::class.java)?.isInteractive == true
        val steps = stepTotal?.let { container.settings.stepsToday(LocalDate.now().toEpochDay(), it) }
        runCatching {
            repo.reportDevice(buildJsonObject {
                if (level != null) put("batteryLevel", level)
                put("isCharging", charging)
                put("network", network)
                if (network == "wifi" && here != null) put("networkLabel", here)
                put("notificationsSilenced", silenced)
                if (steps != null) put("stepsToday", steps)
                if (interactive) put("lastActiveAt", Instant.now().toString())
                put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            })
        }
    }

    // ---------------------------------------------------------------- 位置與標點
    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (locationOn || !hasLocationPermission(this)) return
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 60_000L)
            .setMinUpdateIntervalMillis(30_000L)
            .setMinUpdateDistanceMeters(20f)
            .setMaxUpdateDelayMillis(3 * 60_000L)
            .build()
        runCatching {
            fused.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            locationOn = true
        }
    }

    private fun stopLocation() {
        if (!locationOn) return
        fused.removeLocationUpdates(locationCallback)
        locationOn = false
    }

    @SuppressLint("MissingPermission")
    private suspend fun locateNow() {
        if (!hasLocationPermission(this)) return
        val loc = runCatching { fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await() }.getOrNull()
        if (loc != null) handleLocation(loc, force = true)
        reportDevice()
    }

    private suspend fun handleLocation(loc: Location, force: Boolean) {
        val repo = container.repo.value as? RemoteRepository ?: return
        val st = repo.state.value
        if (st.mySharing?.locationLive != true || !st.paired) return
        repo.setMyLocation(loc.latitude, loc.longitude)
        val now = System.currentTimeMillis()
        val moved = lastSent?.distanceTo(loc) ?: Float.MAX_VALUE
        if (force || moved > 25f || now - lastSentAt > 4 * 60_000L) {
            runCatching {
                repo.reportLocation(loc.latitude, loc.longitude, loc.accuracy, if (loc.hasSpeed()) loc.speed else null)
                lastSent = loc
                lastSentAt = now
            }
        }
        // 精確度太差（> 150 公尺）不拿來判斷進出，避免誤報
        if (loc.accuracy > 150f) return
        val fences = st.places.filter { it.watchUser == null || it.watchUser == st.myId }
            .map { Geo.Fence(it.id, it.lat, it.lng, it.radiusM.toDouble()) }
        val previous = container.settings.insidePlaces()
        val result = Geo.step(fences, previous.intersect(fences.map { it.id }.toSet()), loc.latitude, loc.longitude)
        container.settings.setInsidePlaces(result.inside)
        for (t in result.transitions) runCatching { repo.reportPlaceEvent(t.placeId, t.arrive) }
    }

    override fun onDestroy() {
        stopLocation()
        if (receiversRegistered) {
            runCatching { unregisterReceiver(changeReceiver) }
            runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networkCallback) }
            runCatching { getSystemService(SensorManager::class.java)?.unregisterListener(stepListener) }
        }
        super.onDestroy()
    }
}
