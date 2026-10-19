package tw.xiaoliangkou.app.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/** 電話號碼：整理成台灣的寫法、從 SIM 卡讀自己的號碼（「我的資料」的「從 SIM 卡帶入」） */
object PhoneNumbers {
    /** +886 912 345 678、886912345678、0912345678 → 0912-345-678；市話等其他號碼只去掉空白和符號 */
    fun normalize(raw: String?): String? {
        val s = raw?.trim()?.filter { it.isDigit() || it == '+' }?.takeIf { it.isNotEmpty() } ?: return null
        val local = when {
            s.startsWith("+886") -> "0" + s.removePrefix("+886").trimStart('0')
            s.startsWith("00886") -> "0" + s.removePrefix("00886").trimStart('0')
            s.startsWith("886") && s.length >= 11 -> "0" + s.removePrefix("886").trimStart('0')
            else -> s
        }
        if (Regex("09\\d{8}").matches(local)) return "${local.substring(0, 4)}-${local.substring(4, 7)}-${local.substring(7)}"
        // 太短的不是電話（有些手機沒存號碼時會給「0」或空的）
        return local.takeIf { it.count(Char::isDigit) >= 6 }
    }

    /** SIM 卡讀到的號碼：沒有、一個、好幾個（雙卡）；重複的只算一次 */
    sealed interface Pick {
        data object None : Pick
        data class One(val number: String) : Pick
        data class Many(val numbers: List<String>) : Pick
    }

    fun pick(raw: List<String?>): Pick {
        val numbers = raw.mapNotNull(::normalize).distinct()
        return when (numbers.size) {
            0 -> Pick.None
            1 -> Pick.One(numbers.single())
            else -> Pick.Many(numbers)
        }
    }

    fun canReadSim(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED

    /**
     * 從 SIM 卡讀自己的號碼（要 READ_PHONE_NUMBERS）。台灣很多 SIM 卡沒有存號碼，讀到空的很正常。
     * 不列出全部的 SIM 卡（那要更多權限）：用通話、簡訊、上網預設的那幾張，雙卡通常就涵蓋了。
     */
    @SuppressLint("MissingPermission", "HardwareIds")
    @Suppress("DEPRECATION")
    fun readFromSim(context: Context): List<String?> {
        if (!canReadSim(context)) return emptyList()
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return emptyList()
        val sm = context.getSystemService(SubscriptionManager::class.java)
        val ids = runCatching {
            listOf(
                SubscriptionManager.getDefaultVoiceSubscriptionId(), SubscriptionManager.getDefaultSmsSubscriptionId(),
                SubscriptionManager.getDefaultDataSubscriptionId(), SubscriptionManager.getDefaultSubscriptionId(),
            ).filter { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }.distinct()
        }.getOrDefault(emptyList())
        val out = mutableListOf<String?>()
        for (id in ids) {
            if (Build.VERSION.SDK_INT >= 33 && sm != null) out += runCatching { sm.getPhoneNumber(id) }.getOrNull()
            out += runCatching { tm.createForSubscriptionId(id).line1Number }.getOrNull()
        }
        if (out.none { !it.isNullOrBlank() }) out += runCatching { tm.line1Number }.getOrNull()
        return out
    }
}
