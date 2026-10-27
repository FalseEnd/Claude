package tw.xiaoliangkou.app

import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import tw.xiaoliangkou.app.core.EInvoice
import tw.xiaoliangkou.app.data.EInvoiceAccount
import tw.xiaoliangkou.app.data.EInvoiceApi
import tw.xiaoliangkou.app.data.EInvoiceException
import tw.xiaoliangkou.app.data.sharedHttp
import tw.xiaoliangkou.app.ui.screens.readText
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime

/** 雲端發票：財政部 API 的回應、消費明細 CSV、店名，和送給財政部的參數 */
class EInvoiceTest {
    private fun inv(num: String, y: String, m: Int, d: Int, time: String, seller: String, amount: String, status: String = "已確認") =
        """{"rowNum":"1","invNum":"$num","cardType":"3J0002","cardNo":"/ABC1234","sellerName":"$seller","invStatus":"$status",""" +
            """"invDonatable":"true","amount":"$amount","invPeriod":"11410","sellerBan":"12345678","sellerAddress":"","invoiceTime":"$time",""" +
            """"buyerBan":"","currency":"","donateMark":"0","invDate":{"year":"$y","month":"$m","date":"$d","day":"1","hours":"0","minutes":"0","seconds":"0","time":"1761580800000","timezoneOffset":"-480"}}"""

    private fun page(code: String = "200", vararg invoices: String) =
        """{"v":"0.6","code":"$code","msg":"執行成功","onlyWinningInv":"N","details":[${invoices.joinToString(",")}]}"""

    @Test fun parsesTheCarrierInvoiceHeader() {
        val p = EInvoice.parseHeader(page("200",
            inv("AB12345678", "114", 10, 27, "12:34:56", "統一超商股份有限公司台北市第一一七分公司", "65"),
            inv("CD87654321", "2025", 10, 26, "08:05:00", "好丘股份有限公司", "1,280"),
            inv("EF11112222", "114", 10, 25, "10:00:00", "某某公司", "99", status = "作廢"),
        ))
        assertEquals("200", p.code)
        assertEquals(listOf("AB12345678", "CD87654321"), p.invoices.map { it.number })
        val a = p.invoices[0]
        assertEquals("民國年", LocalDate.of(2025, 10, 27), a.date)
        assertEquals(LocalTime.of(12, 34, 56), a.time)
        assertEquals(65.0, a.amount, 0.0)
        assertEquals(1280.0, p.invoices[1].amount, 0.0)
        assertEquals(LocalDate.of(2025, 10, 26), p.invoices[1].date)
        assertTrue(EInvoice.parseHeader(page("996")).invoices.isEmpty())
        try { EInvoice.parseHeader("<html>"); fail() } catch (e: IllegalArgumentException) {}
    }

    @Test fun parsesTheExportedCsv() {
        val csv = """
            表頭=M|載具名稱|載具號碼|發票日期|商店統編|商店店名|發票號碼|總金額|發票狀態|
            明細=D|發票號碼|小計|品項名稱|
            M|手機條碼|/ABC1234|20251027|22555003|統一超商股份有限公司台北市第一一七分公司|AB12345678|65|已確認|
            D|AB12345678|35|鮮奶茶|
            D|AB12345678|30|茶葉蛋|
            M|手機條碼|/ABC1234|20251026|12345678|好丘股份有限公司|CD87654321|1,280|已確認|
            M|手機條碼|/ABC1234|20251025|12345678|作廢的店|EF11112222|99|作廢|
            M|手機條碼|/ABC1234|20251027|22555003|統一超商股份有限公司台北市第一一七分公司|AB12345678|65|已確認|
        """.trimIndent()
        val list = EInvoice.parseCsv(csv)
        assertEquals("同一張只算一次、作廢的不算", listOf("AB12345678", "CD87654321"), list.map { it.number })
        assertEquals(LocalDate.of(2025, 10, 27), list[0].date)
        assertNull("CSV 沒有時間", list[0].time)
        assertEquals(1280.0, list[1].amount, 0.0)
        // 逗號分隔、欄位順序不一樣、民國年：也盡量讀
        val other = EInvoice.parseCsv("發票號碼,日期,店名,金額\nGH-12345678,114/10/20,路易莎咖啡股份有限公司,120\n")
        assertEquals(listOf("GH12345678"), other.map { it.number })
        assertEquals(LocalDate.of(2025, 10, 20), other[0].date)
        assertEquals(120.0, other[0].amount, 0.0)
        assertEquals("路易莎咖啡股份有限公司", other[0].seller)
        assertTrue(EInvoice.parseCsv("隨便的檔案\nhello,world").isEmpty())
    }

    @Test fun csvInBig5IsReadToo() {
        val text = "M|手機條碼|/ABC1234|20251027|12345678|好丘股份有限公司|CD87654321|320|已確認|"
        assertEquals(text, readText(text.toByteArray(charset("Big5"))))
        assertEquals(text, readText(text.toByteArray()))
    }

    @Test fun sellerNamesBecomeStoreNames() {
        assertEquals("7-ELEVEN", EInvoice.storeName("統一超商股份有限公司台北市第一一七分公司"))
        assertEquals("全家", EInvoice.storeName("全家便利商店股份有限公司"))
        assertEquals("家樂福", EInvoice.storeName("家福股份有限公司"))
        assertEquals("美廉社", EInvoice.storeName("三商家購股份有限公司"))
        assertEquals("Uber Eats", EInvoice.storeName("優食台灣股份有限公司"))
        assertEquals("好丘", EInvoice.storeName("好丘股份有限公司"))
        assertEquals("阿明小吃", EInvoice.storeName("阿明小吃企業社"))
        assertEquals("某某", EInvoice.storeName("某某有限公司台中分公司"))
        assertTrue(EInvoice.CARD.matches("/ABC1234"))
        assertTrue(EInvoice.CARD.matches("/A.B+-12"))
        assertFalse(EInvoice.CARD.matches("ABC1234"))
        assertFalse(EInvoice.CARD.matches("/abc1234"))
    }

    // ================================================================ 送給財政部的請求

    private val sent = mutableListOf<Map<String, String>>()

    private fun api(answer: (Map<String, String>) -> Pair<Int, String>?): EInvoiceApi {
        val http: OkHttpClient = sharedHttp.newBuilder().addInterceptor { chain ->
            val req: Request = chain.request()
            val form = req.body as FormBody
            val params = (0 until form.size).associate { form.name(it) to form.value(it) }
            sent += params + ("url" to req.url.toString()) + ("method" to req.method)
            val (code, body) = answer(params) ?: throw IOException("offline")
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("X").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return EInvoiceApi(http, uuid = { "uuid-1" }, clock = { 1_761_500_000_000L })
    }

    private val account = EInvoiceAccount("/ABC1234", "secret", "EINV123")

    @Test fun sendsTheRightParametersMonthByMonth() = runBlocking<Unit> {
        val a = api { 200 to page("200", inv("AB12345678", "114", 10, 27, "12:00:00", "好丘", "65")) }
        val list = a.invoices(account, LocalDate.of(2025, 9, 28), LocalDate.of(2025, 10, 27))
        assertEquals("兩個月各查一次，同一張只算一次", 1, list.size)
        assertEquals(2, sent.size)
        val first = sent[0]
        assertEquals("https://api.einvoice.nat.gov.tw/PB2CAPIVAN/invServ/InvServ", first["url"])
        assertEquals("POST", first["method"])
        assertEquals("carrierInvChk", first["action"])
        assertEquals("0.6", first["version"])
        assertEquals("3J0002", first["cardType"])
        assertEquals("/ABC1234", first["cardNo"])
        assertEquals("secret", first["cardEncrypt"])
        assertEquals("EINV123", first["appID"])
        assertEquals("uuid-1", first["uuid"])
        assertEquals("N", first["onlyWinningInv"])
        assertEquals("2025/09/28", first["startDate"])
        assertEquals("2025/09/30", first["endDate"])
        assertEquals("2025/10/01", sent[1]["startDate"])
        assertEquals("2025/10/27", sent[1]["endDate"])
        assertEquals("1761500010", first["timeStamp"])
        assertEquals("1761500180", first["expTimeStamp"])
        assertNull("第一頁不用帶 page", first["page"])
    }

    @Test fun nextPageWhenTooMany() = runBlocking<Unit> {
        val a = api { p ->
            if (p["page"] == null) 200 to page("996", inv("AB00000001", "114", 10, 1, "12:00:00", "A", "10"))
            else 200 to page("200", inv("AB00000002", "114", 10, 2, "12:00:00", "B", "20"))
        }
        val list = a.invoices(account, LocalDate.of(2025, 10, 1), LocalDate.of(2025, 10, 5))
        assertEquals(listOf("AB00000001", "AB00000002"), list.map { it.number })
        assertEquals("2", sent[1]["page"])
    }

    @Test fun errorsSayWhatIsWrong() = runBlocking<Unit> {
        suspend fun message(answer: Pair<Int, String>?): String? = try {
            api { answer }.invoices(account, LocalDate.of(2025, 10, 1), LocalDate.of(2025, 10, 5)); null
        } catch (e: EInvoiceException) { e.message }
        assertEquals("手機條碼的驗證碼不對", message(200 to page("919")))
        assertEquals("AppID 不能用（還沒通過審查、打錯了，或被停權）", message(200 to page("998")))
        assertEquals("查太多次了，等一下再試", message(429 to ""))
        assertEquals("財政部的電子發票平台暫時有點狀況，等一下再試", message(500 to ""))
        assertEquals("財政部的電子發票平台暫時有點狀況，等一下再試", message(200 to "<html>"))
        assertEquals("連不到財政部的電子發票平台", message(null))
        assertTrue(message(200 to page("999"))!!.startsWith("查詢失敗（999"))
    }
}
