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
import android.os.Build
import android.os.Looper
import android.os.SystemClock
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tw.xiaoliangkou.app.container
import tw.xiaoliangkou.app.core.Geo
import tw.xiaoliangkou.app.core.StepCounter
import tw.xiaoliangkou.app.data.RealtimeClient
import tw.xiaoliangkou.app.data.RemoteRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * 常駐的前景服務（登入 Supabase 後才啟動）：
 * - 保持即時連線（Supabase Realtime），讓 App 在背景也收得到抱抱、抵達通知；連不上時每 3 分鐘用 RPC 補抓
 * - 分享位置時回報位置，並在手機上判斷是否進出標點
 * - 回報電量、充電、網路、螢幕、勿擾、步數
 */
class ShareService : LifecycleService() {
    companion object {
        /** 即時連線斷掉時，多久用 RPC 補抓一次通知 */
        const val POLL_MS = 3 * 60_000L

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, ShareService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ShareService::class.java))
        }

        fun hasLocationPermission(context: Context) = Locator.hasPermission(context)
    }

    private lateinit var fused: FusedLocationProviderClient
    private var locationOn = false
    /** 使用者有打開位置分享（權限晚一點才給也要記得，給了就開始定位） */
    private var wantLocation = false
    private var lastSent: Location? = null
    private var lastSentAt = 0L
    private var stepTotal: Long? = null
    private var stepsRegistered = false
    /** 用的是「偵測到一步」（沒有計步器的手機）：自己數，服務重開就從 0 開始 */
    private var stepDetector = false
    private var detectedSteps = 0L
    private val startedAt = System.currentTimeMillis()
    private val serviceId = UUID.randomUUID().toString().take(8)
    /** 同時來兩個位置（馬上抓的一次和定期更新）時，一個一個處理，抵達通知才不會發兩次 */
    private val locationLock = Mutex()
    private var deviceJob: Job? = null
    /** 目前常駐通知的內容；服務被重新啟動時沿用，不要又變回「連線中」 */
    private var notice: ServiceNotice? = null
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
        override fun onSensorChanged(event: SensorEvent) {
            val first = stepTotal == null
            stepTotal = if (event.sensor.type == Sensor.TYPE_STEP_DETECTOR) ++detectedSteps else event.values[0].toLong()
            // 第一次讀到步數：不用等 5 分鐘後的定期回報
            if (first) scheduleDeviceReport(5_000)
        }
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

        lifecycleScope.launch {
            container.repo.collectLatest { repo ->
                if (repo !is RemoteRepository) { stopLocation(); return@collectLatest }
                coroutineScope {
                    // 連線狀態與分享設定改變時，開關定位、回報手機狀態
                    launch {
                        repo.state.map { it.connected to (it.mySharing?.locationLive == true) }
                            .distinctUntilChanged()
                            .collect { (connected, sharingLocation) ->
                                wantLocation = sharingLocation
                                if (sharingLocation) startLocation() else stopLocation()
                                if (connected) scheduleDeviceReport(1_000)
                            }
                    }
                    // 常駐通知：對方的狀態有變（或設定改了）就更新；每分鐘再看一次，「正在用手機」才會自己變回「休息中」
                    launch {
                        val everyMinute = flow { while (true) { emit(Unit); delay(60_000L) } }
                        combine(repo.state, container.prefs.filterNotNull(), everyMinute) { st, p, _ ->
                            ServiceNotice.of(st, p.statusNotification, p.statusItems)
                        }
                            .distinctUntilChanged()
                            .collect { notice = it; container.notifier.updateService(it) }
                    }
                }
            }
        }
        // 對方按「現在在哪？」
        lifecycleScope.launch {
            container.realtime.events.collect { e -> if (e is RealtimeClient.Event.Locate) locateNow() }
        }
        // 每 5 分鐘回報一次手機狀態；分享位置時 10 分鐘都沒有新位置（定期更新沒來），自己抓一次
        lifecycleScope.launch {
            while (true) {
                delay(5 * 60_000L)
                scheduleDeviceReport(0)
                if (wantLocation && System.currentTimeMillis() - lastSentAt > 10 * 60_000L) {
                    Locator.current(this@ShareService)?.let { handleLocation(it, force = false) }
                }
            }
        }
        // 即時連線連不上（但 API 可以用）時，每 3 分鐘用 RPC 補抓一次通知，抱抱、抵達提醒才不會一直收不到
        lifecycleScope.launch {
            while (true) {
                delay(POLL_MS)
                if (container.repo.value is RemoteRepository) container.realtime.pollIfDisconnected()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        goForeground(null)
        // 剛允許定位、體能活動權限時會再呼叫一次 start：這時才開始定位、計步
        if (wantLocation) startLocation()
        if (receiversRegistered) registerSteps()
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
        val current = notice
        val notification = if (text == null && current != null) container.notifier.serviceNotification(current)
        else container.notifier.serviceNotification(text ?: "小倆口在背景保持連線")
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
        registerSteps()
    }

    /** 計步：「體能活動」權限常常是之後才允許的，每次服務被叫起來都再檢查一次 */
    private fun registerSteps() {
        if (stepsRegistered) return
        val activityOk = Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        if (!activityOk) return
        val sm = getSystemService(SensorManager::class.java) ?: return
        // 有些手機（例如部分 OPPO）沒有開放計步器，只有「偵測到一步」；有的只有可喚醒版本，getDefaultSensor 找不到
        val sensor = stepSensor(sm, Sensor.TYPE_STEP_COUNTER) ?: stepSensor(sm, Sensor.TYPE_STEP_DETECTOR) ?: return
        stepDetector = sensor.type == Sensor.TYPE_STEP_DETECTOR
        // 偵測器要批次回報：螢幕關著時一步一步叫醒手機太耗電，不批次的話步數又會掉
        stepsRegistered = sm.registerListener(stepListener, sensor, SensorManager.SENSOR_DELAY_NORMAL, if (stepDetector) 60_000_000 else 0)
    }

    private fun stepSensor(sm: SensorManager, type: Int): Sensor? =
        sm.getDefaultSensor(type, true) ?: sm.getDefaultSensor(type) ?: sm.getSensorList(type).firstOrNull()

    /** 這一次的計步器：計步器是「這次開機以來」，偵測器是「這個服務啟動以來」 */
    private fun stepReading(total: Long): StepCounter.Reading {
        val now = System.currentTimeMillis()
        val bootAt = now - SystemClock.elapsedRealtime()
        val (session, since) = if (stepDetector) "svc-$serviceId" to startedAt else "boot-${bootAt / 60_000}" to bootAt
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        return StepCounter.Reading(today.toEpochDay(), total, session, since, now, today.atStartOfDay(zone).toInstant().toEpochMilli())
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
        val r = DeviceProbe.read(this)
        val steps = stepTotal?.let { container.settings.stepsToday(stepReading(it)) }
        // 首頁「我的手機」：自己的狀態直接放進畫面，沒分享也看得到
        repo.setMyDevice(DeviceProbe.status(st.myId.orEmpty(), r, steps))
        if (st.mySharing?.deviceLive != true || !st.paired) return
        // Wi-Fi 名稱不上傳：連 Wi-Fi 時用「目前所在的標點」當作名稱（例如「家裡」），沒分享位置就不用
        val here = st.myLocation?.takeIf { st.mySharing?.locationLive == true }?.let { loc ->
            st.places.filter { it.watchUser == null || it.watchUser == st.myId }
                .firstOrNull { Geo.distance(it.lat, it.lng, loc.lat, loc.lng) <= it.radiusM }?.name
        }
        runCatching {
            repo.reportDevice(buildJsonObject {
                r.batteryLevel?.let { put("batteryLevel", it) }
                put("isCharging", r.isCharging)
                put("network", r.network)
                // Wi-Fi 在標點裡顯示標點名稱，其他時候顯示網速（系統估計的下載速度，行動網路也有）
                val label = if (r.network == "wifi" && here != null) here else r.speed
                if (label != null) put("networkLabel", label)
                put("notificationsSilenced", r.silenced)
                if (steps != null) put("stepsToday", steps)
                if (r.interactive) put("lastActiveAt", Instant.now().toString())
                put("deviceModel", r.deviceModel)
                // 舊的資料庫不認得這兩個欄位，會直接忽略
                r.deviceName?.let { put("deviceName", it) }
                r.carrier?.let { put("carrier", it) }
            })
        }
    }

    /** 網路速度的文字 */
    object Net {
        fun speedLabel(kbps: Int): String? = when {
            kbps <= 0 -> null
            kbps >= 1000 -> "約 ${(kbps + 500) / 1000} Mbps"
            else -> "約 $kbps Kbps"
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
                .addOnFailureListener { locationOn = false } // 例如沒有 Google 服務：靠下面的定期檢查補位置
            locationOn = true
        }
        // 定期更新可能要等好幾分鐘（或手機沒有 Google 服務根本不會來）：先馬上抓一次
        lifecycleScope.launch { Locator.current(this@ShareService)?.let { handleLocation(it, force = true) } }
    }

    private fun stopLocation() {
        if (!locationOn) return
        fused.removeLocationUpdates(locationCallback)
        locationOn = false
    }

    private suspend fun locateNow() {
        // 我沒有分享位置：不用開 GPS（反正不會上傳）
        if (wantLocation) Locator.current(this)?.let { handleLocation(it, force = true) }
        reportDevice()
    }

    private suspend fun handleLocation(loc: Location, force: Boolean) = locationLock.withLock {
        val repo = container.repo.value as? RemoteRepository ?: return@withLock
        repo.setMyLocation(loc.latitude, loc.longitude)
        val st = repo.state.value
        if (st.mySharing?.locationLive != true || !st.paired) return@withLock
        val now = System.currentTimeMillis()
        // 抓不到新位置時用的是手機記得的上一個位置：照實寫上那個時間，對方才不會以為是剛剛的
        val at = loc.time.takeIf { it in 1..now } ?: now
        val moved = lastSent?.distanceTo(loc) ?: Float.MAX_VALUE
        if (force || moved > 25f || now - lastSentAt > 4 * 60_000L) {
            runCatching {
                repo.reportLocation(loc.latitude, loc.longitude, loc.accuracy, if (loc.hasSpeed()) loc.speed else null, Instant.ofEpochMilli(at))
                lastSent = loc
                lastSentAt = now
            }
        }
        // 精確度太差（> 150 公尺）或太舊（> 2 分鐘）的位置不拿來判斷進出，避免誤報
        if (loc.accuracy > 150f || now - at > 2 * 60_000L) return@withLock
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
            if (stepsRegistered) runCatching { getSystemService(SensorManager::class.java)?.unregisterListener(stepListener) }
        }
        super.onDestroy()
    }
}
