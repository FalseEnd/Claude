package tw.xiaoliangkou.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tw.xiaoliangkou.app.core.EInvoice
import tw.xiaoliangkou.app.data.DemoRepository
import tw.xiaoliangkou.app.data.EInvoiceException
import tw.xiaoliangkou.app.data.EInvoiceStore
import tw.xiaoliangkou.app.data.InvoiceSource
import tw.xiaoliangkou.app.data.MerchantMemory
import tw.xiaoliangkou.app.service.EInvoiceSync
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** 發票載具自動記帳：查哪幾天、記新的、不重複、補上付款通知沒有的店名、匯入 CSV */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EInvoiceSyncTest {
    private val app = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob())
    private val demo = DemoRepository(scope).also { it.running = false }
    private val store = EInvoiceStore(app.getSharedPreferences("einv_test", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
    private val memory = MerchantMemory(app.getSharedPreferences("einv_memory", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
    private var today = LocalDate.of(2025, 10, 27)
    private var now = 1_000_000_000L
    private val asked = mutableListOf<Pair<LocalDate, LocalDate>>()
    private var invoices = listOf<EInvoice.Invoice>()
    private var failure: String? = null
    private val source = InvoiceSource { _, from, to ->
        asked += from to to
        failure?.let { throw EInvoiceException(it) }
        invoices.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
    }
    private val sync = EInvoiceSync(store, { source }, memory, { demo }, { today }, { now })

    @After fun tearDown() = scope.cancel()

    private fun inv(n: String, d: Int, time: String?, seller: String, amount: Double) =
        EInvoice.Invoice(n, LocalDate.of(2025, 10, d), time?.let(LocalTime::parse), seller, amount)

    private fun einvoiceExpenses() = demo.state.value.myExpenses.filter { it.source == "einvoice" }

    @Test fun firstSyncRecordsTheLastWeekOnce() = runBlocking {
        store.save("/ABC1234", "secret", "EINV123")
        assertFalse("還沒打開", sync.due())
        store.enabled = true
        assertTrue(sync.due())
        invoices = listOf(
            inv("AB00000001", 26, "12:30:00", "好丘股份有限公司", 320.0),
            inv("AB00000002", 27, "08:10:00", "統一超商股份有限公司台北市第一一七分公司", 65.0),
            inv("AB00000003", 10, "08:10:00", "太久以前", 99.0),
        )
        val o = sync.sync()
        assertEquals(LocalDate.of(2025, 10, 20) to LocalDate.of(2025, 10, 27), asked.single())
        assertEquals(2, o.added)
        val added = einvoiceExpenses()
        assertEquals(setOf("好丘" to "food", "7-ELEVEN" to "daily"), added.map { it.merchant to it.category }.toSet())
        val haoqiu = added.first { it.merchant == "好丘" }
        assertEquals(LocalDate.of(2025, 10, 26).atTime(12, 30).atZone(ZoneId.of("Asia/Taipei")).toInstant(), haoqiu.time)
        assertEquals(LocalDate.of(2025, 10, 27), store.lastDay)
        assertFalse("剛同步過", sync.due())
        assertTrue(store.lastResult.contains("同步完成：新增 2 筆"))

        // 兩天後：從上次那天往前 3 天開始查，已經記過的不再記
        today = LocalDate.of(2025, 10, 29)
        now += EInvoiceSync.INTERVAL_MS
        assertTrue(sync.due())
        invoices = invoices + inv("AB00000004", 28, "19:00:00", "鼎泰豐股份有限公司", 1200.0)
        val again = sync.sync()
        assertEquals(LocalDate.of(2025, 10, 24) to LocalDate.of(2025, 10, 29), asked.last())
        assertEquals(1, again.added)
        assertEquals(2, again.already)
        assertEquals(3, einvoiceExpenses().size)
    }

    @Test fun paymentNotificationsAreNotDoubleCounted() = runBlocking {
        val tz = ZoneId.of("Asia/Taipei")
        // 付款通知記的：一筆沒有店名、一筆有店名
        for ((name, amount) in listOf("LINE Pay 消費" to 150.0, "OK超商" to 65.0)) {
            demo.saveExpense(null, buildJsonObject { put("merchant", name); put("amount", amount); put("source", "notification") })
        }
        val times = demo.state.value.myExpenses.associate { it.merchant to it.time.atZone(tz) }
        val t1 = times.getValue("LINE Pay 消費")
        val t2 = times.getValue("OK超商")
        today = t1.toLocalDate()
        invoices = listOf(
            EInvoice.Invoice("AB00000010", t1.toLocalDate(), t1.toLocalTime().plusMinutes(3), "統一星巴克股份有限公司", 150.0),
            EInvoice.Invoice("AB00000011", t2.toLocalDate(), t2.toLocalTime().minusMinutes(1), "來來超商股份有限公司", 65.0),
        )
        store.save("/ABC1234", "secret", "EINV123")
        val o = sync.sync()
        assertEquals(0, o.added)
        assertEquals(1, o.named)
        assertEquals(1, o.already)
        val mine = demo.state.value.myExpenses
        assertTrue("沒有店名的補上發票的店名", mine.any { it.merchant == "星巴克" && it.category == "drink" && it.amount == 150.0 })
        assertTrue(einvoiceExpenses().isEmpty())
    }

    @Test fun learnedMerchantsAreUsed() = runBlocking {
        memory.remember("阿明企業社", "阿明的店", "gift")
        store.save("/ABC1234", "secret", "EINV123")
        invoices = listOf(inv("AB00000020", 27, "10:00:00", "阿明企業社", 500.0))
        sync.sync()
        assertEquals(listOf("阿明的店" to "gift"), einvoiceExpenses().map { it.merchant to it.category })
    }

    @Test fun failuresAreReported() = runBlocking {
        try { sync.sync(); fail() } catch (e: EInvoiceException) { assertEquals("還沒設定手機條碼、驗證碼和 AppID", e.message) }
        store.save("/ABC1234", "wrong", "EINV123")
        failure = "手機條碼的驗證碼不對"
        try { sync.sync(); fail() } catch (e: EInvoiceException) {}
        assertTrue(store.lastResult.contains("同步失敗：手機條碼的驗證碼不對"))
        assertEquals("失敗的話下次還是從頭查", null, store.lastDay)
    }

    @Test fun csvImport() = runBlocking {
        val csv = "M|手機條碼|/ABC1234|20251027|12345678|好丘股份有限公司|CD87654321|320|已確認|\n" +
            "M|手機條碼|/ABC1234|20251026|12345678|饗食天堂股份有限公司|CD87654322|1,099|已確認|\n"
        val o = sync.importCsv(csv)
        assertEquals(2, o.added)
        assertEquals("再匯入一次不會重複", 0, sync.importCsv(csv).added)
        assertEquals(2, einvoiceExpenses().size)
        try { sync.importCsv("hello"); fail() } catch (e: EInvoiceException) { assertTrue(e.message!!.contains("找不到發票")) }
    }

    @Test fun settingsStayOnThePhoneAndChangeResetsSync() {
        store.save("/ABC1234", "secret", "EINV123")
        store.enabled = true
        store.lastDay = today
        val again = EInvoiceStore(app.getSharedPreferences("einv_test", Context.MODE_PRIVATE))
        assertEquals("/ABC1234", again.card)
        assertEquals("secret", again.verifyCode)
        assertEquals("EINV123", again.appId)
        val uuid = again.uuid
        assertEquals(uuid, again.uuid)
        // 換了手機條碼：要重新測試、從頭查
        again.save("/XYZ9876", "secret", "EINV123")
        assertFalse(again.enabled)
        assertEquals(null, again.lastDay)
        again.clear()
        assertEquals(null, again.account)
        assertEquals("清掉設定，手機的編號不變", uuid, again.uuid)
    }
}
