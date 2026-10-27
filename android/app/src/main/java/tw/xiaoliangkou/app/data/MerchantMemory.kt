package tw.xiaoliangkou.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import tw.xiaoliangkou.app.core.PaymentParser

/**
 * 記住使用者改過的商家：自動記帳記成「TAIPEI 123 CO」、類別「其他」，你改成「好丘 / 餐飲」之後，
 * 下次同一家店的付款通知就直接記成「好丘 / 餐飲」；自己記帳時選過的類別也記著。
 * 只存在這支手機（SharedPreferences），最多 [MAX] 家，最久沒用到的先忘。
 *
 * 比對時不分大小寫、不管空白和標點；找不到一樣的，就找開頭一樣的（學過「好丘」，「好丘 信義店」也算）
 */
class MerchantMemory(private val prefs: SharedPreferences, private val clock: () -> Long = System::currentTimeMillis) {
    constructor(context: Context) : this(context.getSharedPreferences("merchant_memory", Context.MODE_PRIVATE))

    data class Learned(val merchant: String, val category: String)

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), ListSerializer(String.serializer()))

    /** 正規化的原本名字 → [新名字, 類別, 最後用到的時間] */
    private var map: MutableMap<String, List<String>> =
        runCatching { json.decodeFromString(serializer, prefs.getString(K, null) ?: "{}").toMutableMap() }.getOrDefault(mutableMapOf())

    private fun save() {
        if (map.size > MAX) map = map.entries.sortedByDescending { it.value.getOrNull(2)?.toLongOrNull() ?: 0 }.take(MAX).associate { it.toPair() }.toMutableMap()
        prefs.edit().putString(K, json.encodeToString(serializer, map)).apply()
    }

    /** 記住：[original]（通知裡的名字，或自己打的名字）以後記成 [merchant]、[category] */
    @Synchronized
    fun remember(original: String, merchant: String, category: String) {
        val k = key(original)
        if (k.length < 2 || PaymentParser.isPlaceholder(original)) return
        map[k] = listOf(merchant.trim().take(60), category, clock().toString())
        save()
    }

    /** 學過的名字和類別；沒學過是 null */
    @Synchronized
    fun recall(name: String): Learned? {
        val k = key(name)
        if (k.length < 2) return null
        val hit = map[k] ?: map.entries.filter { it.key.length >= 2 && k.startsWith(it.key) }.maxByOrNull { it.key.length }?.value ?: return null
        val merchant = hit.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
        val category = hit.getOrNull(1) ?: return null
        return Learned(merchant, category)
    }

    /** 自動記帳：通知裡的店名學過的話，換成學過的名字和類別 */
    fun apply(p: PaymentParser.Parsed): PaymentParser.Parsed {
        if (!p.knownMerchant) return p
        val l = recall(p.merchant) ?: return p
        // 學到的類別是「其他」（只改了店名）：用改好的店名再猜一次
        val category = l.category.takeIf { it != "other" } ?: PaymentParser.categorizeMerchant(l.merchant).takeIf { it != "other" } ?: p.category
        return p.copy(merchant = l.merchant, category = category)
    }

    @Synchronized
    fun clear() {
        map.clear()
        prefs.edit().remove(K).apply()
    }

    companion object {
        const val MAX = 300
        private const val K = "learned"

        /** 不分大小寫、拿掉空白和標點 */
        fun key(name: String) = name.lowercase().filter { it.isLetterOrDigit() }
    }
}
