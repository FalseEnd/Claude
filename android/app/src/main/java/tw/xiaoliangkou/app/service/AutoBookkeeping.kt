package tw.xiaoliangkou.app.service

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import tw.xiaoliangkou.app.core.Catalog
import tw.xiaoliangkou.app.core.PaymentParser
import tw.xiaoliangkou.app.data.MerchantMemory
import tw.xiaoliangkou.app.ui.Fmt
import java.io.File
import java.security.MessageDigest

/**
 * 自動記帳：處理通知存取權收到的付款通知，並把最近處理過的付款通知記在手機裡
 * （「測試自動記帳」看得到每則通知的原文、有沒有記、為什麼），新的通知格式出問題時才查得出原因。
 *
 * - 一則 LINE 通知裡可能疊了好幾則訊息（例如先儲值、再付款），每則訊息分開判斷，同一則訊息只處理一次
 * - 同一筆消費常常有好幾則通知（LINE錢包、銀行 App、銀行的 LINE 帳號）：10 分鐘內同樣金額只記一筆；
 *   先記的那則沒有店名、後來的有，就幫忙補上店名
 */
class AutoBookkeeping(
    private val context: Context,
    private val ledger: () -> Ledger?,
    private val enabled: () -> Boolean,
    /** 使用者改過的商家：通知裡的店名學過的話，換成學過的名字和類別 */
    private val memory: MerchantMemory? = null,
) {
    /** 記帳的地方：登入 Supabase 時就是你們的帳本；還沒登入、示範模式是 null */
    interface Ledger {
        /** 回傳新記帳的 id；同一筆已經記過就是 null */
        suspend fun add(p: PaymentParser.Parsed, dedupeKey: String): String?
        suspend fun rename(id: String, merchant: String, category: String)
    }

    @Serializable
    data class Entry(
        val at: Long,
        val app: String,
        val title: String,
        val text: String,
        /** 「記下」「補上店名」「沒記」 */
        val outcome: String,
        val detail: String,
        val amount: Double? = null,
        val merchant: String? = null,
        val knownMerchant: Boolean = false,
        val expenseId: String? = null,
        val hash: String,
    ) {
        val recorded get() = expenseId != null
    }

    private val file = File(context.filesDir, "payment_log.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Entry.serializer())
    private val lock = Mutex()
    private val _entries = MutableStateFlow(runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList()))
    /** 最近處理過的付款通知（新的在前面） */
    val entries: StateFlow<List<Entry>> = _entries

    suspend fun clear() = lock.withLock { save(emptyList()) }

    private suspend fun save(list: List<Entry>) {
        _entries.value = list
        withContext(Dispatchers.IO) { runCatching { file.writeText(json.encodeToString(serializer, list)) } }
    }

    private suspend fun log(e: Entry) = save((listOf(e) + _entries.value).take(MAX_ENTRIES))

    private fun appName(pkg: String) = when (pkg) {
        PaymentParser.LINE_APP -> "LINE"
        else -> runCatching { context.packageManager.run { getApplicationLabel(getApplicationInfo(pkg, 0)).toString() } }.getOrDefault(pkg)
    }

    suspend fun handle(pkg: String, notification: Notification, postedAt: Long) {
        val (title, texts) = textsOf(notification)
        for (text in texts) lock.withLock { handleOne(pkg, title, text, postedAt) }
    }

    private suspend fun handleOne(pkg: String, title: String?, text: String, postedAt: Long) {
        val hash = sha("$pkg|$title|$text").take(24)
        if (_entries.value.any { it.hash == hash }) return // 同一則訊息（LINE 會把舊訊息再通知一次）
        val notice = PaymentParser.Notice(pkg, title, listOf(text))
        fun entry(outcome: String, detail: String, p: PaymentParser.Parsed? = null, id: String? = null) = Entry(
            postedAt, appName(pkg), title.orEmpty(), text.take(600), outcome, detail,
            p?.amount, p?.merchant, p?.knownMerchant == true, id, hash,
        )
        val p = when (val r = PaymentParser.parseNotification(notice)) {
            is PaymentParser.Result.Skipped -> {
                // 只記看起來和付款有關的通知；一般聊天、其他 App 的通知不留下來
                if (PaymentParser.looksPaymentRelated(notice)) log(entry("沒記", r.reason))
                return
            }
            is PaymentParser.Result.Payment -> memory?.apply(r.parsed) ?: r.parsed
        }
        val label = "${p.merchant} ${Fmt.money(p.amount)}（${Catalog.category(p.category).label}）"
        if (!enabled()) return log(entry("沒記", "自動記帳已關閉：$label", p))
        val r = ledger() ?: return log(entry("沒記", "還沒登入 Supabase（示範模式不會自動記帳）：$label", p))
        // 同一筆消費的另一則通知
        val same = _entries.value.firstOrNull { it.recorded && it.amount == p.amount && postedAt - it.at in 0..SAME_PURCHASE_MS }
        if (same != null) {
            if (!same.knownMerchant && p.knownMerchant) {
                runCatching { r.rename(same.expenseId!!, p.merchant.take(60), p.category) }.onSuccess {
                    // 記著這筆已經有店名了，同一筆消費再來通知就不用再改
                    return log(entry("補上店名", "${Fmt.time(java.time.Instant.ofEpochMilli(same.at))} 那筆改成 $label", p, same.expenseId))
                }
            }
            return log(entry("沒記", "和 ${Fmt.time(java.time.Instant.ofEpochMilli(same.at))} 記下的是同一筆消費", p))
        }
        val key = sha("${p.amount}|${postedAt / SAME_PURCHASE_MS}").take(32)
        val id = try {
            r.add(p, key)
        } catch (e: Exception) {
            return log(entry("沒記", "連不上 Supabase：${e.message ?: "請稍後再試"}", p))
        }
        log(entry(if (id != null) "記下" else "沒記", if (id != null) label else "已經記過這筆", p, id))
    }

    companion object {
        const val MAX_ENTRIES = 30
        const val SAME_PURCHASE_MS = 10 * 60_000L

        private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        /**
         * 通知的標題，和要分開判斷的每段內文：
         * 聊天類（LINE）一則訊息一段；收合成多行的（InboxStyle）一行一段；其他用展開後的全文。
         */
        fun textsOf(n: Notification): Pair<String?, List<String>> {
            val e = n.extras
            val title = (e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) ?: e.getCharSequence(Notification.EXTRA_TITLE_BIG)
                ?: e.getCharSequence(Notification.EXTRA_TITLE))?.toString()
            val messages = runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)?.messages }.getOrNull()
                ?.mapNotNull { it.text?.toString() }.orEmpty()
            val lines = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() }.orEmpty()
            val whole = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString()
            val texts = when {
                messages.isNotEmpty() -> messages
                lines.isNotEmpty() -> lines
                else -> listOfNotNull(whole)
            }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            return title to texts
        }
    }
}
