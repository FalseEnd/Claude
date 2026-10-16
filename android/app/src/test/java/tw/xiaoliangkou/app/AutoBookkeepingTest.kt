package tw.xiaoliangkou.app

import android.app.Notification
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tw.xiaoliangkou.app.core.PaymentParser
import tw.xiaoliangkou.app.service.AutoBookkeeping

/** 自動記帳：真的系統通知（LINE 一則通知疊好幾則訊息、重複通知、同一筆消費的好幾則通知） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoBookkeepingTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val line = PaymentParser.LINE_APP

    /** 假的帳本：記下新增和改名 */
    private class FakeLedger : AutoBookkeeping.Ledger {
        val added = mutableListOf<PaymentParser.Parsed>()
        val renamed = mutableListOf<Triple<String, String, String>>()
        override suspend fun add(p: PaymentParser.Parsed, dedupeKey: String): String { added += p; return "e${added.size}" }
        override suspend fun rename(id: String, merchant: String, category: String) { renamed += Triple(id, merchant, category) }
    }

    private val ledger = FakeLedger()
    private var on = true
    private var signedIn = true
    private val book by lazy { AutoBookkeeping(context, { ledger.takeIf { signedIn } }, { on }) }

    @Before fun clean() = runBlocking { book.clear() }

    private val topUp = "NT$ 300\n儲值完成。\n2026.10.15 17:36 (GMT+0800)\n銀行名稱\n連線銀行\n餘額\nNT$ 350"
    private val payment = "付款\nNT$ 24\n付款完成。\n2026.10.16 12:22 (GMT+0800)\n商店名稱\nOK超商\n付款後餘額\nNT$ 111"

    /** LINE 聊天的通知：同一個聊天室的訊息疊在一起 */
    private fun lineChat(from: String, vararg messages: String): Notification {
        val sender = Person.Builder().setName(from).build()
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("我").build())
        messages.forEachIndexed { i, m -> style.addMessage(m, 1_000L + i, sender) }
        return NotificationCompat.Builder(context, "x").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(from).setContentText(messages.last()).setStyle(style).build()
    }

    private fun plain(title: String, text: String): Notification =
        NotificationCompat.Builder(context, "x").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).build()

    @Test fun lineWalletMessagesAreHandledOneByOne() = runBlocking {
        val t = 1_800_000_000_000L
        // 先儲值、再付款，兩則訊息在同一則通知裡
        book.handle(line, lineChat("LINE錢包", topUp, payment), t)
        assertEquals(1, ledger.added.size)
        assertEquals(PaymentParser.Parsed(24.0, "OK超商", "linepay", "daily"), ledger.added.single())
        val log = book.entries.value
        assertEquals(listOf("記下", "沒記"), log.map { it.outcome })
        assertTrue(log[1].detail.contains("儲值"))
        assertEquals("LINE", log[0].app)
        assertEquals("LINE錢包", log[0].title)

        // LINE 再通知一次（舊訊息還在）：不會重記、也不會多一筆紀錄
        book.handle(line, lineChat("LINE錢包", topUp, payment), t + 60_000)
        assertEquals(1, ledger.added.size)
        assertEquals(2, book.entries.value.size)
    }

    @Test fun samePurchaseFromBankThenLineWallet() = runBlocking {
        val t = 1_800_000_000_000L
        // 銀行先通知（沒有店名），LINE錢包後到（有店名）：只記一筆，幫第一筆補上店名
        book.handle("com.esunbank", plain("玉山銀行", "【玉山銀行】您的信用卡於10/16 12:22 刷卡 NT$24"), t)
        assertEquals("信用卡消費", ledger.added.single().merchant)
        book.handle(line, lineChat("LINE錢包", payment), t + 30_000)
        assertEquals(1, ledger.added.size)
        assertEquals(Triple("e1", "OK超商", "daily"), ledger.renamed.single())
        assertEquals("補上店名", book.entries.value.first().outcome)
        // 再一則同金額的通知：已經有店名了，不再改
        book.handle("com.cathaybk", plain("國泰世華", "您的卡片於OK超商消費 NT$24"), t + 60_000)
        assertEquals(1, ledger.renamed.size)
        assertTrue(book.entries.value.first().detail.contains("同一筆消費"))
        // 十分鐘以後同樣金額：是另一筆
        book.handle(line, lineChat("LINE錢包", payment.replace("12:22", "12:40")), t + 11 * 60_000)
        assertEquals(2, ledger.added.size)
    }

    @Test fun chatsAreNotLoggedAndSettingsAreRespected() = runBlocking {
        val t = 1_800_000_000_000L
        book.handle(line, lineChat("小雨", "我剛剛付款 NT$300 了"), t)
        assertTrue("朋友的聊天不記帳、也不留紀錄", book.entries.value.isEmpty() && ledger.added.isEmpty())

        on = false
        book.handle(line, lineChat("LINE錢包", payment), t)
        assertEquals("沒記", book.entries.value.first().outcome)
        assertTrue(book.entries.value.first().detail.startsWith("自動記帳已關閉"))

        on = true
        signedIn = false
        book.handle("com.jkos.app", plain("街口支付", "您已付款 65 元給 全家便利商店 松仁店"), t)
        assertTrue(book.entries.value.first().detail.startsWith("還沒登入"))
        assertTrue(ledger.added.isEmpty())
    }

    @Test fun logSurvivesRestartAndCanBeCleared() = runBlocking {
        book.handle(line, lineChat("LINE錢包", payment), 1_800_000_000_000L)
        // App 重新開：讀回同樣的紀錄，同一則訊息也不會再記一次
        val again = AutoBookkeeping(context, { ledger }, { true })
        assertEquals(book.entries.value, again.entries.value)
        again.handle(line, lineChat("LINE錢包", payment), 1_800_000_100_000L)
        assertEquals(1, ledger.added.size)
        again.clear()
        assertTrue(AutoBookkeeping(context, { ledger }, { true }).entries.value.isEmpty())
    }
}
