package tw.xiaoliangkou.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.xiaoliangkou.app.core.Horoscope
import tw.xiaoliangkou.app.core.Horoscope.Element
import java.time.LocalDate
import kotlin.math.abs

class HoroscopeTest {
    private fun d(s: String) = LocalDate.parse(s)
    private val signs = Horoscope.signs
    private val pairs = signs.flatMap { a -> signs.filter { it.id >= a.id }.map { a to it } }

    @Test fun signsInOrder() {
        assertEquals((0..11).toList(), signs.map { it.id })
        assertEquals(
            listOf("牡羊座", "金牛座", "雙子座", "巨蟹座", "獅子座", "處女座", "天秤座", "天蠍座", "射手座", "摩羯座", "水瓶座", "雙魚座"),
            signs.map { it.name },
        )
        signs.forEach { assertTrue(it.name, it.emoji.startsWith(Char(0x2648 + it.id).toString())) }
        assertEquals(List(3) { listOf(Element.FIRE, Element.EARTH, Element.AIR, Element.WATER) }.flatten(), signs.map { it.element })
        assertEquals("3/21–4/19", signs[0].range)
        assertEquals("12/22–1/19", signs[9].range)
    }

    @Test fun everyDateHasOneSign() {
        // 閏年整年：每天剛好屬於一個星座，而且星座的日子是連續的一段
        var date = d("2024-01-01")
        val days = mutableMapOf<Int, Int>()
        var changes = 0
        var last = Horoscope.signOf(date.minusDays(1))
        while (date.year == 2024) {
            val s = Horoscope.signOf(date)
            if (s != last) changes++
            last = s
            days.merge(s.id, 1, Int::plus)
            date = date.plusDays(1)
        }
        assertEquals(12, changes)
        assertEquals(366, days.values.sum())
        assertEquals(12, days.size)
        assertTrue(days.values.all { it in 29..32 })
    }

    @Test fun boundaries() {
        val cases = mapOf(
            "2026-03-20" to "雙魚座", "2026-03-21" to "牡羊座", "2026-04-19" to "牡羊座", "2026-04-20" to "金牛座",
            "2026-05-20" to "金牛座", "2026-05-21" to "雙子座", "2026-06-21" to "雙子座", "2026-06-22" to "巨蟹座",
            "2026-07-22" to "巨蟹座", "2026-07-23" to "獅子座", "2026-08-22" to "獅子座", "2026-08-23" to "處女座",
            "2026-09-22" to "處女座", "2026-09-23" to "天秤座", "2026-10-23" to "天秤座", "2026-10-24" to "天蠍座",
            "2026-11-22" to "天蠍座", "2026-11-23" to "射手座", "2026-12-21" to "射手座", "2026-12-22" to "摩羯座",
            "2026-12-31" to "摩羯座", "2027-01-01" to "摩羯座", "2027-01-19" to "摩羯座", "2027-01-20" to "水瓶座",
            "2027-02-18" to "水瓶座", "2027-02-19" to "雙魚座", "2024-02-29" to "雙魚座",
        )
        cases.forEach { (date, name) -> assertEquals(date, name, Horoscope.signOf(d(date)).name) }
        signs.forEach { s ->
            val start = s.start.atYear(2026)
            assertEquals(s, Horoscope.signOf(start))
            assertEquals(s, Horoscope.signOf(s.end.atYear(2026)))
            assertEquals(signs[(s.id + 11) % 12], Horoscope.signOf(start.minusDays(1)))
        }
    }

    @Test fun dailyIsDeterministic() {
        signs.forEach { s ->
            for (i in 0L..40L) {
                val date = d("2026-10-16").plusDays(i * 9)
                assertEquals(Horoscope.daily(s, date), Horoscope.daily(s, LocalDate.of(date.year, date.month, date.dayOfMonth)))
            }
        }
    }

    @Test fun dailyVariesByDateAndSign() {
        val start = d("2026-10-01")
        signs.forEach { s ->
            val month = (0L until 30L).map { Horoscope.daily(s, start.plusDays(it)) }
            val distinct = month.map { listOf(it.aspects, it.luckyColor, it.luckyNumber, it.advice) }.toSet()
            assertEquals(30, distinct.size)
            assertTrue(month.map { it.love.stars }.toSet().size >= 3)
            assertTrue(month.map { it.advice }.toSet().size >= 10)
        }
        val today = signs.map { Horoscope.daily(it, start) }
        assertTrue(today.map { it.aspects.map { a -> a.stars } }.toSet().size >= 8)
    }

    @Test fun noSameLineTwoDaysInARow() {
        // 也跨過 1970-01-01（epoch day 變負數）
        listOf(d("2026-01-01") to 400L, d("1969-11-01") to 90L).forEach { (start, count) ->
            signs.forEach { s ->
                var prev = Horoscope.daily(s, start.minusDays(1))
                for (i in 0L until count) {
                    val day = Horoscope.daily(s, start.plusDays(i))
                    day.aspects.zip(prev.aspects).forEach { (a, b) -> assertNotEquals(b.text, a.text) }
                    assertNotEquals(prev.advice, day.advice)
                    prev = day
                }
            }
        }
    }

    @Test fun dailyContent() {
        val stars = mutableSetOf<Int>()
        signs.forEach { s ->
            for (i in 0L until 120L) {
                val day = Horoscope.daily(s, d("2026-01-01").plusDays(i))
                assertEquals(s, day.sign)
                assertEquals(listOf("綜合", "愛情", "工作", "財運", "健康"), day.aspects.map { it.label })
                day.aspects.forEach { stars += it.stars; assertTrue(it.stars in 1..5); assertTrue(it.text.isNotBlank()) }
                assertTrue(day.luckyNumber in 1..9)
                assertTrue(day.luckyColor in Horoscope.colors)
                assertTrue(day.advice in Horoscope.advices)
                val avg = listOf(day.love, day.work, day.money, day.health).map { it.stars }.average()
                assertTrue(abs(day.overall.stars - avg) <= 0.5)
                // 句子跟星數一致：高分不會抽到要人放慢的句子
                val pools = listOf(Horoscope.overallLines, Horoscope.loveLines, Horoscope.workLines, Horoscope.moneyLines, Horoscope.healthLines)
                day.aspects.zip(pools).forEach { (a, pool) -> assertTrue(a.text, a.text in pool.forStars(a.stars)) }
            }
        }
        assertEquals((1..5).toSet(), stars)
    }

    @Test fun phraseBank() {
        listOf(Horoscope.overallLines, Horoscope.loveLines, Horoscope.workLines, Horoscope.moneyLines, Horoscope.healthLines).forEach {
            assertTrue(it.all.size >= 15)
            assertTrue(it.good.isNotEmpty() && it.okay.isNotEmpty() && it.soft.isNotEmpty())
            assertEquals(it.all.size, it.all.toSet().size)
        }
        assertTrue(Horoscope.advices.size >= 15)
        assertEquals(Horoscope.advices.size, Horoscope.advices.toSet().size)
        assertTrue(Horoscope.colors.size >= 12)
        Horoscope.colors.forEach {
            assertTrue(it.name, it.name.length in 3..4 && it.name.all { c -> c in '一'..'鿿' })
            assertEquals(0xFF000000, it.argb and 0xFF000000)
        }
    }

    @Test fun matchAllPairs() {
        assertEquals(78, pairs.size)
        val texts = pairs.map { (a, b) ->
            val m = Horoscope.match(a, b)
            assertTrue("${a.name}${b.name}", m.score in 60..98)
            assertTrue(m.text.isNotBlank() && m.label.isNotBlank())
            listOf(a, b).forEach { s -> assertTrue(m.text, m.text.contains(s.name) || m.text.contains(s.trait) || m.text.contains(s.gift)) }
            val r = Horoscope.match(b, a)
            assertEquals(m.score, r.score)
            assertEquals(m.text, r.text)
            assertEquals(a, m.a)
            assertEquals(a, r.b)
            m.text
        }
        assertEquals(78, texts.toSet().size)
    }

    @Test fun matchFollowsElements() {
        fun gap(a: Horoscope.Sign, b: Horoscope.Sign) = minOf(abs(a.id - b.id), 12 - abs(a.id - b.id))
        val scores = pairs.groupBy({ (a, b) -> gap(a, b) }, { (a, b) -> Horoscope.match(a, b).score })
        // 同元素（三分相）最合，火風、土水（六分相）其次，四分相需要多磨合
        assertTrue(scores.getValue(4).min() > scores.getValue(2).max())
        assertTrue(scores.getValue(2).min() > scores.getValue(3).max())
        assertTrue(scores.getValue(6).min() > scores.getValue(3).max())
        assertTrue(scores.getValue(3).all { it < 70 })
        assertTrue(scores.values.flatten().toSet().size >= 20)
        assertTrue(Horoscope.match(signs[3], signs[7]).score >= 90)
    }

    @Test fun matchOfDay() {
        pairs.forEach { (a, b) ->
            val base = Horoscope.match(a, b).score
            val days = (0L until 20L).map { Horoscope.matchOfDay(a, b, d("2026-10-16").plusDays(it)) }
            days.forEach {
                assertTrue(it.score in 60..98)
                assertTrue(abs(it.score - base) <= 3)
            }
            assertTrue(days.map { it.score }.toSet().size > 1)
            assertEquals(Horoscope.matchOfDay(a, b, d("2026-10-16")), Horoscope.matchOfDay(a, b, d("2026-10-16")))
            assertEquals(Horoscope.matchOfDay(a, b, d("2026-10-16")).score, Horoscope.matchOfDay(b, a, d("2026-10-16")).score)
        }
    }

    @Test fun moreUrl() {
        signs.forEach { assertEquals("https://astro.click108.com.tw/daily_${it.id}.php?iAstro=${it.id}", Horoscope.moreUrl(it)) }
        assertEquals("https://astro.click108.com.tw/daily_11.php?iAstro=11", Horoscope.moreUrl(Horoscope.signOf(d("2026-03-01"))))
    }
}
