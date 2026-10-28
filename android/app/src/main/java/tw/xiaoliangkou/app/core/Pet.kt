package tw.xiaoliangkou.app.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max

/**
 * 兩個人一起養的小貓：規則（和資料庫 supabase/migrations/20261028000010_pet.sql 的一樣；PetTest 會比對）。
 *
 * - 三個狀態 0～100：飽足、開心、乾淨。每小時飽足 -3（大約一天半就餓扁，所以每天都要上線餵）、開心 -2
 *   （餓肚子的時候再多 -1）、乾淨 -1.2。資料庫只存上次更新的值和時間，看的時候才算到現在
 * - 照顧得到「成長值」：真的有幫到牠才有（不餓時餵、不無聊時陪玩不算），摸摸一天最多 15 次有成長值
 * - 成長值到了就長大：小奶貓 → 幼貓 → 少年貓 → 成貓 → 大胖貓
 * - 每天的任務：餵飯、陪玩、摸摸 3 次，都做完多 20 成長值；每天都有人餵就累積「連續照顧」，每滿 7 天多 30
 */
object Pet {
    val TAIPEI: ZoneId = ZoneId.of("Asia/Taipei")

    const val FULLNESS_PER_HOUR = 3.0
    const val HAPPINESS_PER_HOUR = 2.0
    /** 飽足低於 [HUNGRY_LINE] 時開心多掉的 */
    const val HUNGRY_SADNESS_PER_HOUR = 1.0
    const val HUNGRY_LINE = 20.0
    const val CLEANLINESS_PER_HOUR = 1.2

    const val TREATS_PER_DAY = 3
    const val PET_XP_PER_DAY = 15
    const val TASK_PETS = 3
    const val TASK_BONUS = 20
    const val STREAK_BONUS = 30
    const val STREAK_EVERY = 7
    const val NAME_MAX = 12

    data class Stage(val key: String, val label: String, val xp: Int, val emoji: String)

    val stages = listOf(
        Stage("newborn", "小奶貓", 0, "🍼"),
        Stage("kitten", "幼貓", 100, "🐾"),
        Stage("junior", "少年貓", 400, "🎀"),
        Stage("adult", "成貓", 1000, "🐱"),
        Stage("chonk", "大胖貓", 2000, "👑"),
    )

    fun stageOf(xp: Int): Stage = stages.last { xp >= it.xp }
    fun nextStage(xp: Int): Stage? = stages.firstOrNull { it.xp > xp }

    data class Coat(val key: String, val label: String)

    val coats = listOf(
        Coat("orange", "橘貓"), Coat("gray", "灰貓"), Coat("white", "白貓"),
        Coat("black", "黑貓"), Coat("calico", "三花貓"), Coat("tuxedo", "賓士貓"),
    )

    fun coat(key: String?) = coats.firstOrNull { it.key == key } ?: coats.first()

    /** 牠的狀態（[at] 是算到哪個時間） */
    data class State(
        val fullness: Double,
        val happiness: Double,
        val cleanliness: Double,
        val xp: Int,
        val streak: Int = 0,
        val lastFedOn: LocalDate? = null,
        val bonusOn: LocalDate? = null,
        val at: Instant,
    ) {
        val stage get() = stageOf(xp)
    }

    /** 從 [s] 的時間過到 [at]：狀態慢慢往下掉 */
    fun decay(s: State, at: Instant): State {
        val h = max(0.0, (at.toEpochMilli() - s.at.toEpochMilli()) / 3_600_000.0)
        if (h == 0.0) return s
        // 飽足掉到 [HUNGRY_LINE] 以下之後的那幾個小時，開心掉得比較快
        val hungryHours = max(0.0, h - max(0.0, (s.fullness - HUNGRY_LINE) / FULLNESS_PER_HOUR))
        return s.copy(
            fullness = max(0.0, s.fullness - FULLNESS_PER_HOUR * h),
            happiness = max(0.0, s.happiness - HAPPINESS_PER_HOUR * h - HUNGRY_SADNESS_PER_HOUR * hungryHours),
            cleanliness = max(0.0, s.cleanliness - CLEANLINESS_PER_HOUR * h),
            at = at,
        )
    }

    /** 一種照顧：[action] 是 feed、play、pet、bath、brush；餵食、玩耍要選 [items] 裡的一種 */
    data class Item(val key: String, val label: String, val emoji: String, val fullness: Int, val happiness: Int, val cleanliness: Int, val xp: Int)

    val foods = listOf(
        Item("kibble", "乾乾", "🥣", 30, 2, 0, 8),
        Item("can", "罐罐", "🥫", 45, 8, 0, 12),
        Item("fish", "小魚乾", "🐟", 10, 15, 0, 6),
    )
    val toys = listOf(
        Item("wand", "逗貓棒", "🎣", -6, 20, -4, 10),
        Item("laser", "雷射筆", "🔴", -8, 25, -2, 10),
        Item("yarn", "毛線球", "🧶", -4, 15, -6, 8),
    )

    fun food(key: String?) = foods.firstOrNull { it.key == key }
    fun toy(key: String?) = toys.firstOrNull { it.key == key }

    /** 今天（台北時間）已經做過的：餵了幾次、玩了幾次、摸了幾次、小魚乾給了幾次 */
    data class Today(val feeds: Int = 0, val plays: Int = 0, val pets: Int = 0, val treats: Int = 0) {
        val tasksDone get() = feeds > 0 && plays > 0 && pets >= TASK_PETS
    }

    /** 做不了的照顧：[code] 和資料庫丟的錯誤一樣 */
    class Refused(val code: String, message: String) : Exception(message)

    /** 照顧之後：新的狀態、今天做過的、這次得到的成長值、有沒有拿到每日任務和連續照顧的獎勵、有沒有長大 */
    data class Outcome(val state: State, val today: Today, val xp: Int, val bonus: Boolean, val streakBonus: Boolean, val grewTo: Stage?)

    fun localDay(at: Instant): LocalDate = at.atZone(TAIPEI).toLocalDate()

    /**
     * 照顧一次（[s] 要先 [decay] 到現在）。做不了的丟 [Refused]：
     * pet_full 吃不下了、pet_hungry 太餓了不想玩、pet_clean 已經很乾淨、pet_treats 小魚乾今天給太多了
     */
    fun act(s: State, today: Today, action: String, item: String?): Outcome {
        var f = s.fullness
        var h = s.happiness
        var c = s.cleanliness
        var xp: Int
        var t = today
        var streak = s.streak
        var lastFed = s.lastFedOn
        val day = localDay(s.at)
        when (action) {
            "feed" -> {
                val food = food(item) ?: throw Refused("invalid_input", "沒有這種食物")
                if (f >= 95) throw Refused("pet_full", "牠吃不下了，晚點再餵")
                if (food.key == "fish" && today.treats >= TREATS_PER_DAY) throw Refused("pet_treats", "小魚乾今天給太多了，明天再給")
                xp = if (f < 70) food.xp else 0
                f += food.fullness; h += food.happiness
                t = t.copy(feeds = t.feeds + 1, treats = t.treats + if (food.key == "fish") 1 else 0)
                if (lastFed != day) {
                    streak = if (lastFed == day.minusDays(1)) streak + 1 else 1
                    lastFed = day
                }
            }
            "play" -> {
                val toy = toy(item) ?: throw Refused("invalid_input", "沒有這種玩具")
                if (f < 15) throw Refused("pet_hungry", "牠肚子太餓了，先餵牠吃點東西")
                xp = if (h < 85) toy.xp else 0
                f += toy.fullness; h += toy.happiness; c += toy.cleanliness
                t = t.copy(plays = t.plays + 1)
            }
            "pet" -> {
                xp = if (today.pets < PET_XP_PER_DAY) 2 else 0
                h += 4
                t = t.copy(pets = t.pets + 1)
            }
            "bath" -> {
                if (c >= 90) throw Refused("pet_clean", "牠已經很乾淨了")
                xp = if (c < 60) 15 else 0
                c = 100.0; h -= 10
            }
            "brush" -> {
                if (c >= 98) throw Refused("pet_clean", "牠已經很乾淨了")
                xp = if (c < 85) 6 else 0
                c += 25; h += 6
            }
            else -> throw Refused("invalid_input", "不知道要做什麼")
        }
        // 今天的任務第一次全部做完
        val bonus = !today.tasksDone && t.tasksDone && s.bonusOn != day
        if (bonus) xp += TASK_BONUS
        // 連續照顧每滿 7 天（今天第一次餵的時候）
        val streakBonus = action == "feed" && s.lastFedOn != day && streak > 0 && streak % STREAK_EVERY == 0
        if (streakBonus) xp += STREAK_BONUS
        val next = s.copy(
            fullness = f.coerceIn(0.0, 100.0), happiness = h.coerceIn(0.0, 100.0), cleanliness = c.coerceIn(0.0, 100.0),
            xp = s.xp + xp, streak = streak, lastFedOn = lastFed, bonusOn = if (bonus) day else s.bonusOn,
        )
        val grew = if (next.stage != s.stage) next.stage else null
        return Outcome(next, t, xp, bonus, streakBonus, grew)
    }

    /** 連續照顧幾天（今天和昨天都沒有人餵就斷了） */
    fun streakNow(s: State, today: LocalDate): Int =
        if (s.lastFedOn != null && (s.lastFedOn == today || s.lastFedOn == today.minusDays(1))) s.streak else 0

    /** 牠現在的樣子：餓了、髒了、寂寞、很開心、普通；晚上 11 點到早上 7 點在睡覺 */
    enum class Mood(val text: String, val emoji: String) {
        HUNGRY("肚子好餓…", "🥺"),
        DIRTY("身上髒髒的，想洗澡", "💦"),
        LONELY("好無聊，陪我玩", "😿"),
        HAPPY("好開心！最喜歡你們了", "😻"),
        OK("今天也很乖", "😺"),
        SLEEPING("呼嚕呼嚕…睡著了", "💤"),
    }

    fun mood(s: State, at: Instant = s.at): Mood {
        val hour = at.atZone(TAIPEI).hour
        return when {
            s.fullness < 25 -> Mood.HUNGRY
            hour >= 23 || hour < 7 -> Mood.SLEEPING
            s.cleanliness < 25 -> Mood.DIRTY
            s.happiness < 25 -> Mood.LONELY
            s.happiness >= 80 && s.fullness >= 60 -> Mood.HAPPY
            else -> Mood.OK
        }
    }

    /** 名字：拿掉前後空白，1～12 個字；不行是 null */
    fun cleanName(name: String): String? = name.trim().takeIf { it.codePointCount(0, it.length) in 1..NAME_MAX }

    /** 一隻剛領養的小貓 */
    fun newborn(at: Instant) = State(fullness = 80.0, happiness = 80.0, cleanliness = 100.0, xp = 0, at = at)

    /** 摸摸時牠說的話（隨機一句） */
    val purrs = listOf("喵～", "呼嚕呼嚕…", "再摸一下嘛", "喵嗚～ 好舒服", "蹭蹭你", "最喜歡你了 💕")
}
