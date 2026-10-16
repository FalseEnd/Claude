package tw.xiaoliangkou.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.xiaoliangkou.app.core.Omikuji
import kotlin.random.Random

class OmikujiTest {
    @Test fun hundredLotsOfFourFiveCharLines() {
        assertEquals((1..100).toList(), Omikuji.lots.map { it.no })
        Omikuji.lots.forEach { lot ->
            assertEquals("第 ${lot.no} 籤", 4, lot.poem.size)
            lot.poem.forEach { line ->
                assertEquals("第 ${lot.no} 籤「$line」", 5, line.codePointCount(0, line.length))
                assertTrue("第 ${lot.no} 籤「$line」有標點或空白", line.all { Character.isIdeographic(it.code) })
            }
        }
        assertEquals(listOf("七寶浮圖塔", "高峰頂上安", "眾人皆仰望", "莫作等閒看"), Omikuji.lots[0].poem)
    }

    @Test fun luckDistributionMatchesSensoji() {
        val counts = Omikuji.lots.groupingBy { it.luck }.eachCount()
        assertEquals(mapOf("大吉" to 17, "吉" to 35, "半吉" to 5, "小吉" to 4, "末小吉" to 3, "末吉" to 6, "凶" to 30), counts)
        assertEquals("大吉", Omikuji.lots[0].luck)
    }

    @Test fun url() {
        assertEquals("https://qiangua.temple01.com/qianshi.php?t=fs_akt100&s=1", Omikuji.url(1))
        assertEquals("https://qiangua.temple01.com/qianshi.php?t=fs_akt100&s=100", Omikuji.url(100))
    }

    @Test fun drawCoversAllLots() {
        assertEquals(Omikuji.draw(Random(7)), Omikuji.draw(Random(7)))
        val r = Random(42)
        val seen = (1..3000).map { Omikuji.draw(r) }
        assertTrue(seen.all { it in Omikuji.lots })
        assertEquals(100, seen.map { it.no }.toSet().size)
    }
}
