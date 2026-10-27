package tw.xiaoliangkou.app

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tw.xiaoliangkou.app.core.PaymentParser
import tw.xiaoliangkou.app.data.MerchantMemory
import tw.xiaoliangkou.app.service.AutoBookkeeping

/** 記住改過的商家：下次同一家店的付款通知直接記成改好的名字和類別 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MerchantMemoryTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("memory_test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
    private var now = 1_000L
    private fun memory() = MerchantMemory(prefs) { now++ }

    @Test fun remembersAndMatchesLoosely() {
        val m = memory()
        m.remember("HAOCHIU TAIPEI", "好丘", "food")
        assertEquals(MerchantMemory.Learned("好丘", "food"), m.recall("HAOCHIU TAIPEI"))
        assertEquals("不分大小寫、空白、標點", MerchantMemory.Learned("好丘", "food"), m.recall("haochiu-taipei"))
        assertEquals("開頭一樣的分店也算", MerchantMemory.Learned("好丘", "food"), m.recall("HAOCHIU TAIPEI 101"))
        assertNull(m.recall("HAO"))
        // 重開 App 還記得
        assertEquals(MerchantMemory.Learned("好丘", "food"), memory().recall("HAOCHIU TAIPEI"))
    }

    @Test fun placeholdersAreNotLearned() {
        val m = memory()
        m.remember("LINE Pay 消費", "好丘", "food")
        assertNull(m.recall("LINE Pay 消費"))
        val p = PaymentParser.Parsed(24.0, "LINE Pay 消費", "linepay", "other", knownMerchant = false)
        assertEquals(p, m.apply(p))
    }

    @Test fun applyUsesLearnedOrGuessesFromTheNewName() {
        val m = memory()
        m.remember("TW 1234 CO", "阿明炒飯", "other")
        val p = m.apply(PaymentParser.Parsed(85.0, "TW 1234 CO", "card", "other"))
        assertEquals("阿明炒飯", p.merchant)
        assertEquals("只改了名字：用新名字猜", "food", p.category)
        m.remember("TW 1234 CO", "阿明炒飯", "gift")
        assertEquals("gift", m.apply(PaymentParser.Parsed(85.0, "TW 1234 CO", "card", "other")).category)
    }

    @Test fun keepsOnlyTheMostRecent() {
        val m = memory()
        repeat(MerchantMemory.MAX + 20) { m.remember("店家$it", "店家$it", "food") }
        assertNull("最久沒用到的先忘", memory().recall("店家0"))
        assertEquals("food", memory().recall("店家${MerchantMemory.MAX + 19}")?.category)
        m.clear()
        assertNull(memory().recall("店家${MerchantMemory.MAX + 19}"))
    }

    private class FakeLedger : AutoBookkeeping.Ledger {
        val added = mutableListOf<PaymentParser.Parsed>()
        override suspend fun add(p: PaymentParser.Parsed, dedupeKey: String): String { added += p; return "e${added.size}" }
        override suspend fun rename(id: String, merchant: String, category: String) = Unit
    }

    @Test fun autoBookkeepingUsesWhatItLearned() = runBlocking {
        val m = memory()
        m.remember("HAOCHIU TAIPEI", "好丘", "food")
        val ledger = FakeLedger()
        val book = AutoBookkeeping(context, { ledger }, { true }, m)
        book.clear()
        val n = androidx.core.app.NotificationCompat.Builder(context, "x").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("玉山銀行").setContentText("刷卡通知 您末四碼1234消費 NT$320 商店：HAOCHIU TAIPEI").build()
        book.handle("com.esunbank", n, 1_800_000_000_000L)
        assertEquals(listOf("好丘" to "food"), ledger.added.map { it.merchant to it.category })
    }
}
