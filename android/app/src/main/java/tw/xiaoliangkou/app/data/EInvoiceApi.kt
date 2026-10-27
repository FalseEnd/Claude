package tw.xiaoliangkou.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import tw.xiaoliangkou.app.core.EInvoice
import java.io.IOException
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 查不到雲端發票：[message] 是給人看的原因 */
class EInvoiceException(message: String, val code: String? = null, cause: Throwable? = null) : Exception(message, cause)

/** 手機條碼載具：條碼、驗證碼，和向財資中心申請的 AppID */
data class EInvoiceAccount(val card: String, val verifyCode: String, val appId: String)

/** 查載具裡的雲端發票；畫面測試時換成假的 */
fun interface InvoiceSource {
    /** [from]～[to] 的發票（含頭尾）；失敗丟 [EInvoiceException] */
    suspend fun invoices(account: EInvoiceAccount, from: LocalDate, to: LocalDate): List<EInvoice.Invoice>
}

/**
 * 財政部電子發票整合服務平台的「載具發票表頭查詢」（電子發票應用 API 規格 2.0，action=carrierInvChk、version 0.6）。
 * 要用使用者自己向財資中心申請的 AppID（這個查詢不用 APIKey 加簽）。一次查一個月，發票太多（996）就翻下一頁。
 * 只連財政部，不帶 Supabase 的金鑰和登入的 token
 */
class EInvoiceApi(
    private val http: OkHttpClient = sharedHttp,
    private val uuid: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val base: String = BASE,
) : InvoiceSource {

    override suspend fun invoices(account: EInvoiceAccount, from: LocalDate, to: LocalDate): List<EInvoice.Invoice> {
        val out = linkedMapOf<String, EInvoice.Invoice>()
        var month = YearMonth.from(from)
        while (!month.isAfter(YearMonth.from(to))) {
            val start = maxOf(from, month.atDay(1))
            val end = minOf(to, month.atEndOfMonth())
            for (page in 1..MAX_PAGES) {
                val p = query(account, start, end, page)
                p.invoices.forEach { out.putIfAbsent(it.number, it) }
                if (p.code != "996") break
            }
            month = month.plusMonths(1)
        }
        return out.values.toList()
    }

    private suspend fun query(a: EInvoiceAccount, start: LocalDate, end: LocalDate, page: Int): EInvoice.Page {
        val now = clock() / 1000
        val form = FormBody.Builder()
            .add("version", "0.6")
            .add("cardType", "3J0002")
            .add("cardNo", a.card)
            .add("expTimeStamp", (now + 180).toString())
            .add("action", "carrierInvChk")
            .add("timeStamp", (now + 10).toString())
            .add("startDate", start.format(DAY))
            .add("endDate", end.format(DAY))
            .add("onlyWinningInv", "N")
            .add("uuid", uuid())
            .add("appID", a.appId)
            .add("cardEncrypt", a.verifyCode)
            .apply { if (page > 1) add("page", page.toString()) }
            .build()
        val (status, text) = try {
            fetch(Request.Builder().url("$base/PB2CAPIVAN/invServ/InvServ").post(form).build()) { it.code to it.body?.string().orEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw EInvoiceException("連不到財政部的電子發票平台", cause = e)
        }
        if (status == 429) throw EInvoiceException(MESSAGES.getValue("429"), "429")
        if (status !in 200..299) throw EInvoiceException("財政部的電子發票平台暫時有點狀況，等一下再試")
        val p = try {
            EInvoice.parseHeader(text)
        } catch (e: IllegalArgumentException) {
            throw EInvoiceException("財政部的電子發票平台暫時有點狀況，等一下再試", cause = e)
        }
        if (p.code != "200" && p.code != "996") throw EInvoiceException(MESSAGES[p.code] ?: "查詢失敗（${p.code} ${p.msgOrEmpty()}）", p.code)
        return p
    }

    private fun EInvoice.Page.msgOrEmpty() = message.take(40)

    private suspend fun <T> fetch(req: Request, read: (Response) -> T): T = suspendCancellableCoroutine { cont ->
        val call = http.newCall(req)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
            override fun onResponse(call: Call, response: Response) {
                val r = try {
                    response.use(read)
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                    return
                }
                cont.resume(r)
            }
        })
    }

    companion object {
        const val BASE = "https://api.einvoice.nat.gov.tw"
        /** 申請 AppID、看消費明細的地方 */
        const val PORTAL = "https://www.einvoice.nat.gov.tw/"
        private const val MAX_PAGES = 20
        private val DAY = DateTimeFormatter.ofPattern("yyyy/MM/dd")

        /** 財政部的訊息回應碼 → 說明 */
        val MESSAGES = mapOf(
            "429" to "查太多次了，等一下再試",
            "903" to "手機條碼或日期格式不對",
            "919" to "手機條碼的驗證碼不對",
            "950" to "今天查太多次了，明天再試",
            "951" to "財政部的電子發票平台連線逾時，等一下再試",
            "953" to "手機條碼不對",
            "997" to "這支手機被財政部擋下來了",
            "998" to "AppID 不能用（還沒通過審查、打錯了，或被停權）",
        )
    }
}
