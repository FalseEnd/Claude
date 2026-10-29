package tw.xiaoliangkou.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import tw.xiaoliangkou.app.core.Pet
import tw.xiaoliangkou.app.core.Pet.Mood
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** 一起養的小貓：規則和資料庫（supabase/tests/rls_smoke.sql 的「一起養的小貓」）算出一樣的數字 */
class PetTest {
    /** 台北 10/28 早上 10 點 */
    private val t0 = Instant.parse("2026-10-28T02:00:00Z")
    private val day = LocalDate.of(2026, 10, 28)

    private fun refused(code: String, block: () -> Unit) {
        try {
            block()
            fail("應該被擋下：$code")
        } catch (e: Pet.Refused) {
            assertEquals(code, e.code)
        }
    }

    /** 和 rls_smoke.sql 一模一樣的一天 */
    @Test fun sameDayAsTheDatabase() {
        var s = Pet.newborn(t0)
        var today = Pet.Today()
        fun act(action: String, item: String? = null): Pet.Outcome =
            Pet.act(s, today, action, item).also { s = it.state; today = it.today }

        // 餵罐罐：80 不算餓（≥ 70）沒有成長值，飽足 100、開心 88；第一次餵，連續照顧 1 天
        val fed = act("feed", "can")
        assertEquals(0, fed.xp)
        assertEquals(100.0, s.fullness, 0.0)
        assertEquals(88.0, s.happiness, 0.0)
        assertEquals(1, s.streak)
        assertEquals(day, s.lastFedOn)
        refused("pet_full") { act("feed", "can") }
        refused("invalid_input") { act("feed", "cake") }
        refused("invalid_input") { act("dance") }

        // 20 小時後：飽足 40、開心 48、乾淨 76
        s = Pet.decay(s, t0.plus(Duration.ofHours(20)))
        assertEquals(40.0, s.fullness, 0.0)
        assertEquals(48.0, s.happiness, 0.0)
        assertEquals(76.0, s.cleanliness, 1e-9)

        assertEquals("餓了餵有成長值", 8, act("feed", "kibble").xp)
        assertEquals("無聊陪玩有成長值", 10, act("play", "wand").xp)
        assertEquals(2, act("pet").xp)
        assertEquals(2, act("pet").xp)
        val petted = act("pet")
        assertTrue("今天的任務都做完了", petted.bonus)
        assertEquals(22, petted.xp)
        assertEquals(44, s.xp)
        assertEquals(82.0, s.happiness, 1e-9)
        assertEquals(Pet.Today(feeds = 2, plays = 1, pets = 3, treats = 0), today)
        assertEquals("任務獎勵一天一次", 2, act("pet").xp)

        // 小魚乾一天 3 次；第 4 次飽足 94 還吃得下，但是給太多了
        repeat(3) { act("feed", "fish") }
        assertEquals(94.0, s.fullness, 1e-9)
        assertEquals(3, today.treats)
        refused("pet_treats") { act("feed", "fish") }

        val bathed = act("bath")
        assertEquals("72 分不算髒，洗澡沒有成長值", 0, bathed.xp)
        assertEquals(100.0, s.cleanliness, 0.0)
        refused("pet_clean") { act("bath") }
        refused("pet_clean") { act("brush") }

        s = s.copy(xp = 99)
        val grown = act("pet")
        assertEquals("幼貓", grown.grewTo?.label)
        assertEquals(101, s.xp)

        s = s.copy(fullness = 10.0)
        refused("pet_hungry") { act("play", "yarn") }
    }

    @Test fun decayGetsSadderWhenHungry() {
        val s = Pet.State(fullness = 30.0, happiness = 80.0, cleanliness = 50.0, xp = 0, at = t0)
        // 10 小時：飽足 30 → 0（3.33 小時後掉到 20 以下，之後 6.67 小時開心多掉）
        val later = Pet.decay(s, t0.plus(Duration.ofHours(10)))
        assertEquals(0.0, later.fullness, 0.0)
        assertEquals(80 - 20 - (10 - 10 / 3.0), later.happiness, 1e-9)
        assertEquals(38.0, later.cleanliness, 1e-9)
        assertEquals(t0.plus(Duration.ofHours(10)), later.at)
        assertEquals("時間沒有往前不變", s, Pet.decay(s, t0.minusSeconds(60)))
        // 很久沒來：都停在 0，不會變負的
        val gone = Pet.decay(s, t0.plus(Duration.ofDays(30)))
        assertEquals(0.0, gone.happiness, 0.0)
        assertEquals(0.0, gone.cleanliness, 0.0)
    }

    @Test fun streak() {
        val yesterday = day.minusDays(1)
        val s = Pet.State(fullness = 40.0, happiness = 50.0, cleanliness = 80.0, xp = 0, streak = 6, lastFedOn = yesterday, at = t0)
        val seventh = Pet.act(s, Pet.Today(), "feed", "kibble")
        assertEquals(7, seventh.state.streak)
        assertTrue("滿 7 天", seventh.streakBonus)
        assertEquals(8 + Pet.STREAK_BONUS, seventh.xp)
        // 同一天再餵：天數不變、不會再拿一次
        val again = Pet.act(seventh.state, seventh.today, "feed", "fish")
        assertEquals(7, again.state.streak)
        assertFalse(again.streakBonus)
        // 中間斷了一天：重新算
        val broken = Pet.act(s.copy(lastFedOn = day.minusDays(2)), Pet.Today(), "feed", "kibble")
        assertEquals(1, broken.state.streak)
        assertFalse(broken.streakBonus)
        assertEquals(6, Pet.streakNow(s, day))
        assertEquals(0, Pet.streakNow(s, day.plusDays(1)))
        assertEquals(0, Pet.streakNow(s.copy(lastFedOn = null), day))
    }

    @Test fun careRules() {
        val s = Pet.State(fullness = 50.0, happiness = 90.0, cleanliness = 70.0, xp = 0, at = t0)
        assertEquals("開心的時候陪玩不加成長值", 0, Pet.act(s, Pet.Today(), "play", "laser").xp)
        val brushed = Pet.act(s, Pet.Today(), "brush", null)
        assertEquals(6, brushed.xp)
        assertEquals(95.0, brushed.state.cleanliness, 0.0)
        assertEquals(96.0, brushed.state.happiness, 0.0)
        val dirty = Pet.act(s.copy(cleanliness = 40.0), Pet.Today(), "bath", null)
        assertEquals(15, dirty.xp)
        assertEquals(80.0, dirty.state.happiness, 0.0)
        assertEquals("摸摸一天最多 15 次有成長值", 0, Pet.act(s, Pet.Today(pets = 15), "pet", null).xp)
        val played = Pet.act(s.copy(happiness = 30.0), Pet.Today(), "play", "yarn")
        assertEquals(46.0, played.state.fullness, 0.0)
        assertEquals(64.0, played.state.cleanliness, 0.0)
        assertEquals(1, played.today.plays)
    }

    @Test fun stages() {
        assertEquals("小奶貓", Pet.stageOf(0).label)
        assertEquals("小奶貓", Pet.stageOf(99).label)
        assertEquals("幼貓", Pet.stageOf(100).label)
        assertEquals("少年貓", Pet.stageOf(400).label)
        assertEquals("成貓", Pet.stageOf(1000).label)
        assertEquals("大胖貓", Pet.stageOf(5000).label)
        assertEquals("幼貓", Pet.nextStage(0)?.label)
        assertEquals("成貓", Pet.nextStage(400)?.label)
        assertNull(Pet.nextStage(2000))
        assertEquals("orange", Pet.coat("nope").key)
        assertEquals("三花貓", Pet.coat("calico").label)
    }

    @Test fun moods() {
        val noon = Instant.parse("2026-10-28T04:00:00Z")
        val night = Instant.parse("2026-10-28T16:00:00Z") // 台北半夜 12 點
        fun s(f: Double, h: Double, c: Double) = Pet.State(fullness = f, happiness = h, cleanliness = c, xp = 0, at = noon)
        assertEquals(Mood.HUNGRY, Pet.mood(s(20.0, 90.0, 90.0), noon))
        assertEquals("餓了半夜也會叫", Mood.HUNGRY, Pet.mood(s(20.0, 90.0, 90.0), night))
        assertEquals(Mood.SLEEPING, Pet.mood(s(80.0, 90.0, 90.0), night))
        assertEquals(Mood.DIRTY, Pet.mood(s(80.0, 90.0, 10.0), noon))
        assertEquals(Mood.LONELY, Pet.mood(s(80.0, 10.0, 90.0), noon))
        assertEquals(Mood.HAPPY, Pet.mood(s(80.0, 90.0, 90.0), noon))
        assertEquals(Mood.OK, Pet.mood(s(50.0, 60.0, 90.0), noon))
    }

    /** 品種和資料庫的 pet_coats() 一樣（App 選得到的，資料庫都存得進去） */
    @Test fun coatsMatchTheDatabase() {
        val sql = java.io.File("../../supabase/setup.sql").readText()
        val body = sql.substringAfterLast("create or replace function public.pet_coats()").substringAfter("array[").substringBefore("]")
        val keys = Regex("'([^']*)'").findAll(body).map { it.groupValues[1] }.toList()
        assertEquals(Pet.coats.map { it.key }, keys)
        assertEquals(15, Pet.coats.size)
        assertEquals(6, Pet.coats.count { it.since == 0 })
        assertEquals(Pet.coats.size, Pet.coats.map { it.label }.toSet().size)
    }

    @Test fun names() {
        assertEquals("小橘", Pet.cleanName("  小橘 "))
        assertEquals("一二三四五六七八九十一二", Pet.cleanName("一二三四五六七八九十一二"))
        assertNull(Pet.cleanName("一二三四五六七八九十一二三"))
        assertNull(Pet.cleanName("   "))
    }
}
