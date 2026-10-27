package tw.xiaoliangkou.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.xiaoliangkou.app.core.PaymentParser

/** 自動記帳 0.2.13：更多通知的寫法、更多商家關鍵字、整理店名 */
class PaymentKeywordsTest {
    private fun pay(text: String, title: String? = null): PaymentParser.Parsed =
        (PaymentParser.parseNotification(PaymentParser.Notice("com.bank.app", title, listOf(text))) as PaymentParser.Result.Payment).parsed

    private fun check(text: String, merchant: String, category: String, title: String? = null) {
        val p = pay(text, title)
        assertEquals(text, merchant, p.merchant)
        assertEquals(text, category, p.category)
    }

    @Test fun moreNotificationShapes() {
        // 日期時間夾在「於」和「在」中間
        check("您的信用卡於10/27 12:34在 統一超商 消費NT$65", "統一超商", "daily")
        // 金額後面直接接店名（前面有「國內」）
        check("中信卡 末4碼1234 10/27 12:34 消費新台幣120元 國內 好丘", "好丘", "food")
        // 引號裡的店名
        check("街口支付 您已於「八方雲集 松江店」完成付款 NT$95", "八方雲集 松江店", "food")
        // 向 X 付款
        check("付款成功 您已向 春水堂 付款 NT$180", "春水堂", "drink")
        // NT 後面沒有 $
        check("台灣Pay 交易成功 NT 65 特店名稱 7-ELEVEN 民生門市", "7-ELEVEN 民生門市", "daily")
        // 支出通知（LINE Bank 這類）
        check("支出通知 您的帳戶支出 NT$499 商店：Netflix", "Netflix", "fun")
    }

    @Test fun cardCodesAreTidied() {
        check("刷卡通知 您末四碼1234消費 NT$350 商店：PXMART-TAIPEI 0012", "全聯", "daily")
        check("刷卡通知 消費 NT$280 商店：UBER *EATS", "Uber Eats", "food")
        check("刷卡通知 消費 NT$180 商店：UBER *TRIP", "UBER TRIP", "transport")
        check("刷卡通知 消費 NT$390 商店：PAYPAL *NETFLIX.COM", "Netflix", "fun")
        check("付款完成 NT$600 商店名稱 好樂迪股份有限公司", "好樂迪", "fun")
        check("刷卡通知 消費 NT$59 商店：FAMILYMART 南京店", "全家 南京店", "daily")
        assertEquals("Starbucks", PaymentParser.cleanMerchant("Starbucks"))
    }

    @Test fun moreWallets() {
        val px = pay("全支付 付款成功 NT$120 商店名稱 全聯 民生店")
        assertEquals("pxpay", px.source)
        assertEquals("daily", px.category)
        val icash = pay("icash Pay 付款完成 金額 $50")
        assertEquals("icash Pay 消費", icash.merchant)
        assertFalse(icash.knownMerchant)
        assertEquals("pipay", pay("Pi 拍錢包 付款成功 NT$88 商店：路易莎").source)
        assertTrue(PaymentParser.isPlaceholder("icash Pay 消費"))
        assertFalse(PaymentParser.isPlaceholder("路易莎"))
    }

    @Test fun longestKeywordWins() {
        assertEquals("food", PaymentParser.categorize("Uber Eats"))
        assertEquals("transport", PaymentParser.categorize("Uber"))
        assertEquals("fun", PaymentParser.categorize("Apple Music"))
        assertEquals("shopping", PaymentParser.categorize("Apple 信義A13"))
        assertEquals("不是交通", "other", PaymentParser.categorize("Business Center"))
        assertEquals("義大利麵不是樂園", "food", PaymentParser.categorizeMerchant("義大利麵屋"))
        assertEquals("food", PaymentParser.categorize("Spaghetti House 披薩"))
    }

    @Test fun brandsFromEveryCategory() {
        mapOf(
            "drink" to listOf("可不可熟成紅茶", "COMEBUY", "一芳水果茶", "金鑛咖啡", "鶴茶樓"),
            "food" to listOf("壽司郎", "饗食天堂", "三商巧福", "鬍鬚張", "勝博殿", "foodpanda"),
            "transport" to listOf("台灣大車隊", "嘟嘟房", "遠通電收", "星宇航空", "YouBike"),
            "daily" to listOf("美廉社", "大樹藥局", "中華電信", "台灣電力公司", "動物醫院"),
            "shopping" to listOf("迪卡儂", "特力屋", "寶島眼鏡", "玩具反斗城", "東森購物"),
            "fun" to listOf("星聚點", "KKTIX", "Klook", "健身工廠", "六福村"),
            "gift" to listOf("周大福", "花藝工作室", "PANDORA"),
        ).forEach { (cat, names) -> names.forEach { assertEquals(it, cat, PaymentParser.categorizeMerchant(it)) } }
    }

    @Test fun guessesFromTheNameWhenTheBrandIsUnknown() {
        assertEquals("food", PaymentParser.categorizeMerchant("巷口鍋燒意麵"))
        assertEquals("food", PaymentParser.categorizeMerchant("福來小館"))
        assertEquals("food", PaymentParser.categorizeMerchant("阿嬤花枝羹"))
        assertEquals("drink", PaymentParser.categorizeMerchant("山上的茶"))
        assertEquals("daily", PaymentParser.categorizeMerchant("三角窗五金行"))
        assertEquals("gift", PaymentParser.categorizeMerchant("小巷花坊"))
        assertEquals("other", PaymentParser.categorizeMerchant("ABC 有限"))
        // 付款通知裡的店名也會用名字猜
        check("付款完成 NT$85 商店名稱 阿明炒飯", "阿明炒飯", "food")
    }
}
