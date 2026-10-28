package tw.xiaoliangkou.app.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import tw.xiaoliangkou.app.core.PhoneHeat
import tw.xiaoliangkou.app.data.DeviceStatus
import java.time.Instant

/**
 * 讀這支手機現在的狀態：背景服務回報給對方、首頁「我的手機」都用這裡，兩邊看到的一樣。
 * 不需要額外的權限（電信名稱、手機名稱都是公開的系統資訊）。
 */
object DeviceProbe {
    data class Reading(
        val batteryLevel: Int?,
        val isCharging: Boolean,
        /** wifi、cellular、offline */
        val network: String,
        /** 系統估計的下載速度，例如「約 30 Mbps」 */
        val speed: String?,
        val silenced: Boolean,
        val interactive: Boolean,
        /** 設定 › 關於手機 › 裝置名稱（和型號一樣、沒設定時是 null） */
        val deviceName: String?,
        val deviceModel: String,
        /** 上網用的那張 SIM 卡的電信，沒有 SIM 卡是 null */
        val carrier: String?,
        /** 電池溫度（°C） */
        val batteryTemp: Double? = null,
        /** 系統的過熱等級（Android 10 以上） */
        val thermal: Int? = null,
    )

    fun read(context: Context): Reading {
        val battery = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val level = battery?.let { b ->
            val l = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (l >= 0 && scale > 0) (l * 100 / scale).coerceIn(0, 100) else null
        }
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = runCatching { cm?.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
        val network = when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            else -> "cellular"
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        val pm = context.getSystemService(PowerManager::class.java)
        val model = model(Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty())
        return Reading(
            batteryLevel = level,
            isCharging = charging,
            network = network,
            speed = caps?.let { ShareService.Net.speedLabel(it.linkDownstreamBandwidthKbps) },
            silenced = nm != null && nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL,
            interactive = pm?.isInteractive == true,
            deviceName = deviceName(rawDeviceName(context), model),
            deviceModel = model,
            carrier = carrier(context),
            batteryTemp = PhoneHeat.fromBattery(battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)),
            thermal = if (Build.VERSION.SDK_INT >= 29) runCatching { pm?.currentThermalStatus }.getOrNull() else null,
        )
    }

    /** 首頁「我的手機」用的狀態（只在這支手機，不上傳） */
    fun status(userId: String, r: Reading, steps: Int?, now: Instant = Instant.now()) = DeviceStatus(
        userId = userId,
        batteryLevel = r.batteryLevel,
        isCharging = r.isCharging,
        network = r.network,
        networkLabel = r.speed,
        notificationsSilenced = r.silenced,
        stepsToday = steps,
        lastActiveAt = if (r.interactive) now.toString() else null,
        deviceModel = r.deviceModel,
        updatedAt = now.toString(),
        deviceName = r.deviceName,
        carrier = r.carrier,
        batteryTemp = r.batteryTemp,
        thermal = r.thermal,
    )

    /** 「OPPO CPH2689」；型號裡已經有廠牌的不要重複（「samsung SM-S921B」不是「samsung samsung …」） */
    fun model(manufacturer: String, model: String): String {
        val m = model.trim()
        val brand = manufacturer.trim()
        // 型號開頭就是廠牌這個字（「Samsung Galaxy S24」）才省略；「ASUSAI2501C」只是剛好以 ASUS 開頭，還是寫「asus ASUSAI2501C」
        val hasBrand = m.startsWith(brand, ignoreCase = true) && m.getOrNull(brand.length)?.isLetterOrDigit() != true
        return if (brand.isEmpty() || hasBrand) m.ifEmpty { brand } else "$brand $m"
    }

    /** 設定 › 關於手機 › 裝置名稱：大部分手機存在 Settings.Global，有些廠牌改名稱時只改了藍牙名稱 */
    private fun rawDeviceName(context: Context): String? {
        val cr = context.contentResolver
        return listOf(
            { Settings.Global.getString(cr, Settings.Global.DEVICE_NAME) },
            { Settings.Secure.getString(cr, "bluetooth_name") },
        ).firstNotNullOfOrNull { read -> runCatching(read).getOrNull()?.takeIf { it.isNotBlank() } }
    }

    /** 手機名稱：空的、只是型號（「ASUS_AI2501C」）就不顯示，免得同樣的字出現兩次 */
    fun deviceName(raw: String?, model: String): String? {
        val name = raw?.trim()?.take(60)?.takeIf { it.isNotEmpty() } ?: return null
        fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val n = key(name)
        val m = key(model)
        // 「ASUS_AI2501C」「CPH2689」「Pixel 8」：型號已經寫了，不重複顯示
        return name.takeUnless { n.isEmpty() || n == m || m.endsWith(n) }
    }

    /** 上網用的 SIM 卡的電信（不用權限）；沒有 SIM 卡、飛航模式是 null */
    fun carrier(context: Context): String? = runCatching {
        val base = context.getSystemService(TelephonyManager::class.java) ?: return null
        val id = SubscriptionManager.getDefaultDataSubscriptionId()
        val tm = if (id != SubscriptionManager.INVALID_SUBSCRIPTION_ID) base.createForSubscriptionId(id) else base
        if (tm.simState != TelephonyManager.SIM_STATE_READY) return null
        carrierName(tm.simOperator, tm.simOperatorName, tm.networkOperatorName)
    }.getOrNull()

    /** 台灣的電信用中文名稱（SIM 卡上常常寫英文）；其他國家照 SIM 卡上的名字 */
    fun carrierName(mccMnc: String?, simName: String?, networkName: String?): String? {
        CODES[mccMnc?.trim()]?.let { return it }
        for (raw in listOf(simName, networkName)) {
            val name = raw?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val k = name.lowercase().replace(" ", "")
            NAMES.firstOrNull { (keys, _) -> keys.any { it in k } }?.let { return it.second }
            return name.take(30)
        }
        return null
    }

    private val CODES = mapOf(
        "46692" to "中華電信",
        "46697" to "台灣大哥大", "46693" to "台灣大哥大", "46699" to "台灣大哥大",
        "46601" to "遠傳電信", "46603" to "遠傳電信", "46688" to "遠傳電信",
        "46689" to "台灣之星", "46690" to "台灣之星",
        "46605" to "亞太電信",
    )

    private val NAMES = listOf(
        listOf("chunghwa", "中華電信", "中华电信", "cht") to "中華電信",
        listOf("taiwanmobile", "twm", "台灣大哥大", "台湾大哥大", "台哥大") to "台灣大哥大",
        listOf("fareastone", "遠傳", "远传", "fet") to "遠傳電信",
        listOf("tstar", "台灣之星", "台湾之星") to "台灣之星",
        listOf("aptg", "asiapacific", "亞太", "亚太") to "亞太電信",
    )
}
