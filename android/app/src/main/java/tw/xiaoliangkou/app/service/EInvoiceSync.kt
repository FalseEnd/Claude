package tw.xiaoliangkou.app.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tw.xiaoliangkou.app.core.EInvoice
import tw.xiaoliangkou.app.core.PaymentParser
import tw.xiaoliangkou.app.data.CoupleRepository
import tw.xiaoliangkou.app.data.EInvoiceException
import tw.xiaoliangkou.app.data.EInvoiceStore
import tw.xiaoliangkou.app.data.InvoiceSource
import tw.xiaoliangkou.app.data.MerchantMemory
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 發票載具自動記帳：每 [INTERVAL_MS] 查一次手機條碼裡的雲端發票，新的發票記一筆（來源：發票載具）。
 * 雲端發票通常消費後 1～2 天才上傳，所以每次從上次查到的那天往前 [OVERLAP_DAYS] 天開始查；同一張發票只記一次。
 *
 * 同一筆消費付款通知已經記過的（同樣金額、時間差 [SAME_PURCHASE] 以內；CSV 沒有時間就看同一天）不再記，
 * 那筆如果沒有店名（「LINE Pay 消費」），就補上發票上的店名
 */
class EInvoiceSync(
    val store: EInvoiceStore,
    private val source: () -> InvoiceSource,
    private val memory: MerchantMemory?,
    private val repo: () -> CoupleRepository?,
    private val today: () -> LocalDate = { LocalDate.now(TAIPEI) },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** 新增幾筆、幫付款通知記的補上幾個店名、已經記過幾張 */
    data class Outcome(val added: Int, val named: Int, val already: Int) {
        val text get() = when {
            added == 0 && named == 0 -> "沒有新的發票"
            else -> listOfNotNull("新增 $added 筆".takeIf { added > 0 }, "補上 $named 個店名".takeIf { named > 0 }).joinToString("、")
        }
    }

    private val lock = Mutex()

    /** 該自動同步了：打開了、而且離上次試超過 [INTERVAL_MS] */
    fun due(): Boolean = store.enabled && store.account != null && clock() - store.lastTry >= INTERVAL_MS

    /** 向財政部查最近的發票並記帳；失敗丟 [EInvoiceException]（結果也寫在 [EInvoiceStore.lastResult]） */
    suspend fun sync(): Outcome = lock.withLock {
        val account = store.account ?: throw EInvoiceException("還沒設定手機條碼、驗證碼和 AppID")
        val r = repo() ?: throw EInvoiceException("請先登入")
        store.lastTry = clock()
        val to = today()
        // 第一次查最近 [FIRST_DAYS] 天；之後從上次查到的那天往前幾天開始（最多查 6 個月內的）
        val from = maxOf(store.lastDay?.minusDays(OVERLAP_DAYS) ?: to.minusDays(FIRST_DAYS), to.minusMonths(6).withDayOfMonth(1))
        try {
            val invoices = source().invoices(account, from, to)
            val outcome = record(r, invoices)
            store.lastDay = to
            store.lastResult = "${stamp()} 同步完成：${outcome.text}"
            outcome
        } catch (e: CancellationException) {
            throw e
        } catch (e: EInvoiceException) {
            store.lastResult = "${stamp()} 同步失敗：${e.message}"
            throw e
        }
    }

    /** 匯入電子發票平台下載的消費明細 CSV（沒有 AppID 也能用） */
    suspend fun importCsv(text: String): Outcome = lock.withLock {
        val invoices = EInvoice.parseCsv(text)
        if (invoices.isEmpty()) throw EInvoiceException("檔案裡找不到發票（要用電子發票平台「消費明細」匯出的 CSV）")
        val r = repo() ?: throw EInvoiceException("請先登入")
        record(r, invoices)
    }

    private suspend fun record(r: CoupleRepository, invoices: List<EInvoice.Invoice>): Outcome {
        var added = 0
        var named = 0
        var already = 0
        val mine = r.state.value.myExpenses.filter { it.source != "einvoice" && it.amount != null }.toMutableList()
        for (inv in invoices.sortedBy { it.at }) {
            val at = inv.at.atZone(TAIPEI).toInstant()
            val store = EInvoice.storeName(inv.seller)
            val learned = memory?.recall(inv.seller) ?: memory?.recall(store)
            val merchant = learned?.merchant ?: store
            val category = learned?.category?.takeIf { it != "other" } ?: PaymentParser.categorizeMerchant(merchant)
            // 付款通知（或自己）已經記過同一筆：不重複記，沒有店名的補上
            val same = mine.firstOrNull { e ->
                e.amount == inv.amount && if (inv.time != null) Duration.between(e.time, at).abs() <= SAME_PURCHASE
                else e.time.atZone(TAIPEI).toLocalDate() == inv.date
            }
            if (same != null) {
                mine -= same
                if (PaymentParser.isPlaceholder(same.merchant) && merchant.isNotBlank()) {
                    r.saveExpense(same.id, buildJsonObject { put("merchant", merchant.take(60)); put("category", category) })
                    named++
                } else {
                    already++
                }
                continue
            }
            if (r.addInvoiceExpense(inv.number, merchant.ifBlank { "雲端發票 ${inv.number}" }, category, inv.amount, at)) added++ else already++
        }
        if (added > 0) r.reloadExpenses()
        return Outcome(added, named, already)
    }

    private fun stamp() = Instant.ofEpochMilli(clock()).atZone(TAIPEI).format(STAMP)

    companion object {
        val TAIPEI: ZoneId = ZoneId.of("Asia/Taipei")
        /** 多久自動查一次：6 小時 */
        const val INTERVAL_MS = 6 * 60 * 60_000L
        const val FIRST_DAYS = 7L
        const val OVERLAP_DAYS = 3L
        val SAME_PURCHASE: Duration = Duration.ofMinutes(90)
        private val STAMP = DateTimeFormatter.ofPattern("M/d HH:mm")
    }
}
