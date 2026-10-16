package tw.xiaoliangkou.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.xiaoliangkou.app.core.PaymentParser
import tw.xiaoliangkou.app.core.PaymentParser.Notice
import tw.xiaoliangkou.app.core.PaymentParser.Result

/** 自動記帳：各種付款通知（特別是 LINE Pay 改成「LINE錢包」官方帳號之後的格式） */
class PaymentParserTest {
    private val line = PaymentParser.LINE_APP

    private fun pay(pkg: String, title: String?, vararg lines: String): PaymentParser.Parsed {
        val r = PaymentParser.parseNotification(Notice(pkg, title, lines.toList()))
        assertTrue("應該要記帳，但是：$r", r is Result.Payment)
        return (r as Result.Payment).parsed
    }

    private fun skip(pkg: String, title: String?, vararg lines: String): String {
        val r = PaymentParser.parseNotification(Notice(pkg, title, lines.toList()))
        assertTrue("不該記帳，但是：$r", r is Result.Skipped)
        return (r as Result.Skipped).reason
    }

    // LINE錢包 的付款訊息（2026-10 實際收到的版面）：付款 / NT$ 24 / 付款完成。/ 時間 / 商店名稱 OK超商 / 付款後餘額 NT$ 111
    private val lineMoney = listOf("付款", "NT$ 24", "付款完成。", "2026.10.16 12:22 (GMT+0800)", "商店名稱", "OK超商", "付款後餘額", "NT$ 111")

    @Test fun lineWalletPaymentCard() {
        val p = pay(line, "LINE錢包", lineMoney.joinToString("\n"))
        assertEquals(24.0, p.amount, 0.0)
        assertEquals("OK超商", p.merchant)
        assertEquals("linepay", p.source)
        assertEquals("daily", p.category)
        assertTrue(p.knownMerchant)
    }

    @Test fun lineWalletSameContentOnOneLine() {
        // 通知只顯示一行時，欄位之間只有空白
        val p = pay(line, "LINE錢包", lineMoney.joinToString(" "))
        assertEquals(24.0, p.amount, 0.0)
        assertEquals("OK超商", p.merchant)
        assertEquals("daily", p.category)
    }

    @Test fun lineWalletOtherPossibleWordings() {
        assertEquals("OK超商", pay(line, "LINE錢包", "您已於OK超商付款NT$24").merchant)
        assertEquals("OK超商", pay(line, "LINE錢包", "付款完成 NT$24（OK超商）").merchant)
        assertEquals("OK超商", pay(line, "LINE錢包", "OK超商 NT$24 付款完成").merchant)
        assertEquals("OK超商", pay(line, "LINE錢包", "付款完成\nNT$24\nOK超商").merchant)
        assertEquals("全家便利商店 松仁店", pay(line, "LINE錢包", "付款完成 NT$65。商店名稱：全家便利商店 松仁店").merchant)
        // 通知裡沒有「完成」兩個字也要記
        assertEquals("OK超商", pay(line, "LINE錢包", "付款 NT$ 24 商店名稱 OK超商").merchant)
        // 標題只寫 LINE、內文開頭才是帳號名稱的版本
        assertEquals(24.0, pay(line, "LINE", "LINE錢包：付款完成 NT$24 商店名稱 OK超商").amount, 0.0)
    }

    @Test fun lineWalletWithoutStoreName() {
        // LINE 官方說明：直接刷卡的消費，通知不一定有店名
        val p = pay(line, "LINE錢包", "付款完成。\nNT$ 120")
        assertEquals(120.0, p.amount, 0.0)
        assertEquals("LINE Pay 消費", p.merchant)
        assertFalse(p.knownMerchant)
        assertEquals("linepay", p.source)
    }

    @Test fun lineWalletTopUpAndTransfersAreNotSpending() {
        assertTrue(skip(line, "LINE錢包", "NT$ 300\n儲值完成。\n2026.10.15 17:36 (GMT+0800)\n銀行名稱\n連線銀行\n餘額\nNT$ 350").contains("儲值"))
        skip(line, "LINE錢包", "收款完成 NT$ 500")
        skip(line, "LINE錢包", "付款失敗 NT$ 24")
        skip(line, "LINE錢包", "退款完成 NT$ 24 商店名稱 OK超商")
    }

    @Test fun lineChatsAndPromotionsAreIgnored() {
        assertEquals("LINE 的聊天訊息", skip(line, "小雨", "我剛剛付款 NT$300 了"))
        skip(line, "大學同學群組", "阿哲: 晚餐消費 NT$1,200 大家一人 300")
        // 銀行官方帳號的廣告
        skip(line, "國泰世華銀行", "刷卡滿 NT$3,000 享 5% 回饋，立即登錄")
        skip(line, "LINE錢包", "LINE Pay 優惠：付款滿 NT$100 送 10 點")
    }

    @Test fun balanceIsNotTheAmount() {
        assertEquals(24.0, PaymentParser.amountOf("付款後餘額 NT$ 111，本次付款 NT$ 24")!!, 0.0)
        assertEquals(1280.0, PaymentParser.amountOf("可用額度 NT$50,000，消費金額：1,280")!!, 0.0)
    }

    @Test fun bankAndWalletApps() {
        val card = pay("com.cathaybk.mymobibank.android", "國泰世華", "您的卡片末四碼1234於10/15 12:03消費新台幣1,280元，商店：UNIQLO 微風南山店")
        assertEquals("UNIQLO 微風南山店", card.merchant)
        assertEquals("card", card.source)
        val noStore = pay("com.esunbank", "玉山銀行", "【玉山銀行】您的信用卡於10/16 12:03 刷卡 NT$500")
        assertEquals("信用卡消費", noStore.merchant)
        // Google 錢包：標題是店名、內文是金額，沒有「付款」兩個字
        val gw = pay("com.google.android.apps.walletnfcrel", "Starbucks", "NT$135.00 with Visa •••• 1234")
        assertEquals("Starbucks", gw.merchant)
        assertEquals("drink", gw.category)
        assertEquals(65.0, pay("com.jkos.app", "街口支付", "您已付款 65 元給 全家便利商店 松仁店").amount, 0.0)
        // 簡訊驗證碼不是一筆花費
        skip("com.google.android.apps.messaging", "國泰世華", "您的交易驗證碼為123456，交易金額NT$500")
    }

    @Test fun categoryFallsBackToTheWholeNotice() {
        assertEquals("food", pay("com.ubercab.eats", "Uber Eats", "付款完成 NT$320 給 阿嬤的廚房").category)
        // 付款方式（一卡通、悠遊卡）不算交通
        assertEquals("other", pay(line, "LINE錢包", "付款完成 NT$80 付款方式：一卡通MONEY").category)
    }

    @Test fun onlyPaymentRelatedNoticesAreKeptInTheLog() {
        assertTrue(PaymentParser.looksPaymentRelated(Notice(line, "LINE錢包", listOf("NT$ 300 儲值完成。"))))
        assertFalse("朋友的聊天不留紀錄", PaymentParser.looksPaymentRelated(Notice(line, "小雨", listOf("我剛剛付款 NT$300 了"))))
        assertFalse(PaymentParser.looksPaymentRelated(Notice("com.instagram.android", "小雨", listOf("說你好可愛"))))
        assertTrue(PaymentParser.looksPaymentRelated(Notice("com.esunbank", "玉山銀行", listOf("刷卡 NT$500"))))
    }
}
