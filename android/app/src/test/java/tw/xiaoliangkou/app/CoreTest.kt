package tw.xiaoliangkou.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.xiaoliangkou.app.core.Cycle
import tw.xiaoliangkou.app.core.Dates
import tw.xiaoliangkou.app.core.Geo
import tw.xiaoliangkou.app.core.Guess
import tw.xiaoliangkou.app.core.PaymentParser
import java.time.LocalDate

/** 與 prototype/core.test.js 相同的案例，確保 App 與伺服器的演算法一致 */
class CoreTest {
    private fun d(s: String) = LocalDate.parse(s)

    private val periods = listOf(
        Cycle.Period(d("2026-06-01"), d("2026-06-05")),
        Cycle.Period(d("2026-06-30"), d("2026-07-05")),
        Cycle.Period(d("2026-07-28"), d("2026-08-02")),
        Cycle.Period(d("2026-08-25"), d("2026-08-29")),
        Cycle.Period(d("2026-09-22"), d("2026-09-27")),
    )

    @Test fun daysTogetherAndAnniversaries() {
        assertEquals(514, Dates.daysTogether(d("2025-05-20"), d("2026-10-15")))
        assertEquals(Dates.Occurrence(d("2027-05-20"), 217, 4), Dates.nextOccurrence(d("2023-05-20"), d("2026-10-15"), true))
        assertEquals(d("2026-02-28"), Dates.nextOccurrence(d("2024-02-29"), d("2026-01-10"), true).date)
        assertEquals(d("2028-02-29"), Dates.nextOccurrence(d("2024-02-29"), d("2027-03-01"), true).date)
        assertEquals(70, Dates.nextOccurrence(d("2026-12-24"), d("2026-10-15"), false).daysLeft)
    }

    @Test fun milestones() {
        val m = Dates.upcomingMilestones(d("2025-05-20"), d("2026-10-15"))
        assertEquals(listOf(520, 600, 700), m.map { it.day })
        assertEquals(d("2026-10-21"), m[0].date)
        assertEquals(6, m[0].daysLeft)
        assertEquals(listOf(100, 200), Dates.upcomingMilestones(d("2026-10-15"), d("2026-10-15"), 2).map { it.day })
    }

    @Test fun cycleStats() {
        val s = Cycle.stats(periods)
        assertEquals(28, s.cycleLength)
        assertEquals(6, s.periodLength)
        assertEquals(4, s.samples)
        val gap = Cycle.stats(listOf(Cycle.Period(d("2026-01-01")), Cycle.Period(d("2026-04-01")), Cycle.Period(d("2026-04-29"))))
        assertEquals(28, gap.cycleLength)
        assertEquals(1, gap.samples)
        assertEquals(32, Cycle.stats(periods, Cycle.Settings(cycleLength = 32)).cycleLength)
    }

    @Test fun predictNextPeriod() {
        val p = Cycle.predict(periods, Cycle.Settings(), d("2026-10-15"))!!
        assertEquals(d("2026-09-22"), p.cycleStart)
        assertEquals(24, p.cycleDay)
        assertEquals(d("2026-10-20"), p.nextStart)
        assertEquals(5, p.daysUntilNext)
        assertEquals(d("2026-10-06"), p.ovulation)
        assertEquals(d("2026-10-01"), p.fertileStart)
        assertEquals(d("2026-10-07"), p.fertileEnd)
        assertEquals(Cycle.DayInfo(Cycle.Phase.SAFE, predicted = true, cycleDay = 24, follicular = false), p.today)
    }

    @Test fun latePeriodAndMissedLogs() {
        val late = Cycle.predict(periods, Cycle.Settings(), d("2026-10-25"))!!
        assertEquals(5, late.lateDays)
        assertEquals(34, late.cycleDay)
        assertEquals(Cycle.Phase.LATE, late.today.phase)
        val missed = Cycle.predict(listOf(Cycle.Period(d("2026-08-01"), d("2026-08-05"))), Cycle.Settings(), d("2026-10-15"))!!
        assertEquals(0, missed.lateDays)
        assertEquals(d("2026-09-26"), missed.cycleStart)
        assertEquals(d("2026-10-24"), missed.nextStart)
    }

    @Test fun dayPhases() {
        fun at(s: String) = Cycle.dayPhase(d(s), periods, Cycle.Settings(), d("2026-10-15"))
        assertNull(at("2026-05-30").phase)
        assertEquals(Cycle.Phase.PERIOD, at("2026-09-25").phase)
        assertEquals(true, at("2026-09-30").follicular)
        assertEquals(Cycle.Phase.FERTILE, at("2026-10-02").phase)
        assertEquals(Cycle.Phase.OVULATION, at("2026-10-06").phase)
        assertEquals(false, at("2026-10-09").follicular)
        assertEquals(true, at("2026-10-20").predicted)
        assertEquals(Cycle.Phase.OVULATION, at("2026-08-11").phase)
    }

    @Test fun geofenceHysteresis() {
        val home = Geo.Fence("home", 25.0330, 121.5654, 100.0)
        // 往東移動：經度每 0.001 度約 100 公尺
        fun east(m: Double) = 121.5654 + m / 100_800.0
        var r = Geo.step(listOf(home), emptySet(), 25.0330, east(300.0))
        assertTrue(r.transitions.isEmpty())
        r = Geo.step(listOf(home), r.inside, 25.0330, east(90.0))
        assertEquals(listOf(Geo.Transition("home", true)), r.transitions)
        r = Geo.step(listOf(home), r.inside, 25.0330, east(115.0))
        assertTrue("緩衝區內不算離開", r.transitions.isEmpty())
        r = Geo.step(listOf(home), r.inside, 25.0330, east(135.0))
        assertEquals(listOf(Geo.Transition("home", false)), r.transitions)
        val d = Geo.distance(25.0340, 121.5645, 25.0478, 121.5170)
        assertTrue(d in 4900.0..5200.0)
    }

    @Test fun guessScoring() {
        assertEquals(100, Guess.score(135.0, 135.0).points)
        assertEquals(80, Guess.score(130.0, 135.0).points)
        assertEquals(20, Guess.score(100.0, 135.0).points)
        assertEquals(5, Guess.score(1000.0, 135.0).points)
    }

    @Test fun paymentNotifications() {
        val line = PaymentParser.parse("LINE Pay｜付款完成 NT$135 商店：星巴克 信義新天地門市")!!
        assertEquals(PaymentParser.Parsed(135.0, "星巴克 信義新天地門市", "linepay", "drink"), line)
        val card = PaymentParser.parse("【國泰世華】您的卡片末四碼1234於10/15 12:03消費新台幣1,280元，商店：UNIQLO 微風南山店")!!
        assertEquals(1280.0, card.amount, 0.0)
        assertEquals("UNIQLO 微風南山店", card.merchant)
        assertEquals("shopping", card.category)
        val jko = PaymentParser.parse("街口支付：您已付款 65 元給 全家便利商店 松仁店")!!
        assertEquals("全家便利商店 松仁店", jko.merchant)
        assertEquals("daily", jko.category)
        assertEquals("台灣高鐵", PaymentParser.parse("Apple Pay 交易：台灣高鐵 NT$1,490")!!.merchant)
        assertNull(PaymentParser.parse("您的包裹已送達"))
        assertNull("聊天訊息不算付款", PaymentParser.parsePaymentNotification("小雨", "晚餐大概 NT$300 吧"))
        assertEquals(65.0, PaymentParser.parsePaymentNotification("街口支付", "您已付款 65 元給 全家")!!.amount, 0.0)
    }
}
