package tw.xiaoliangkou.app.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** 日期：在一起天數、週年、里程碑 */
object Dates {
    fun daysTogether(since: LocalDate, today: LocalDate): Int = ChronoUnit.DAYS.between(since, today).toInt() + 1
    fun between(a: LocalDate, b: LocalDate): Int = ChronoUnit.DAYS.between(a, b).toInt()

    data class Occurrence(val date: LocalDate, val daysLeft: Int, val years: Int)

    /** 下一次週年；2/29 在平年以 2/28 計 */
    fun nextOccurrence(date: LocalDate, today: LocalDate, yearly: Boolean): Occurrence {
        if (!yearly) return Occurrence(date, between(today, date), 0)
        var year = max(today.year, date.year)
        while (true) {
            val d = if (date.monthValue == 2 && date.dayOfMonth == 29 && !java.time.Year.isLeap(year.toLong())) 28 else date.dayOfMonth
            val candidate = LocalDate.of(year, date.monthValue, d)
            if (candidate >= today) return Occurrence(candidate, between(today, candidate), year - date.year)
            year++
        }
    }

    data class Milestone(val day: Int, val date: LocalDate, val daysLeft: Int)

    /** 之後的里程碑：每 100 天，以及 520、1314 */
    fun upcomingMilestones(since: LocalDate, today: LocalDate, count: Int = 3): List<Milestone> {
        val current = daysTogether(since, today)
        val specials = ArrayDeque(listOf(520, 1314).filter { it >= current })
        var hundred = max(100, ((current + 99) / 100) * 100)
        val out = mutableListOf<Milestone>()
        while (out.size < count) {
            val next = if (specials.isNotEmpty() && specials.first() <= hundred) {
                specials.removeFirst().also { if (it == hundred) hundred += 100 }
            } else {
                hundred.also { hundred += 100 }
            }
            val date = since.plusDays(next - 1L)
            out += Milestone(next, date, between(today, date))
        }
        return out
    }

    fun isMilestone(day: Int) = day > 0 && (day % 100 == 0 || day == 520 || day == 1314)
}

/** 地理圍欄：進入用半徑，離開要超出半徑 + 緩衝，避免 GPS 飄移反覆觸發 */
object Geo {
    data class Fence(val id: String, val lat: Double, val lng: Double, val radius: Double)
    data class Transition(val placeId: String, val arrive: Boolean)
    data class Result(val inside: Set<String>, val transitions: List<Transition>)

    fun distance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0
        val rad = Math.PI / 180
        val dLat = (lat2 - lat1) * rad
        val dLng = (lng2 - lng1) * rad
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1 * rad) * cos(lat2 * rad) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * asin(sqrt(h))
    }

    fun step(fences: List<Fence>, previouslyInside: Set<String>, lat: Double, lng: Double): Result {
        val inside = mutableSetOf<String>()
        val transitions = mutableListOf<Transition>()
        for (f in fences) {
            val d = distance(f.lat, f.lng, lat, lng)
            val buffer = max(25.0, f.radius * 0.2)
            val was = f.id in previouslyInside
            val now = if (was) d <= f.radius + buffer else d <= f.radius
            if (now) inside += f.id
            if (now && !was) transitions += Transition(f.id, true)
            if (!now && was) transitions += Transition(f.id, false)
        }
        return Result(inside, transitions)
    }
}

/** 猜花費計分（與資料庫 submit_guess() 相同的分級） */
object Guess {
    data class Score(val points: Int, val label: String, val ratio: Double, val diff: Double)

    private val tiers = listOf(
        0.03 to (100 to "神準！心有靈犀"),
        0.10 to (80 to "超懂你"),
        0.25 to (50 to "很接近了"),
        0.50 to (20 to "差了一點"),
        Double.MAX_VALUE to (5 to "要多了解對方喔"),
    )

    fun score(guess: Double, actual: Double): Score {
        require(actual > 0) { "actual must be > 0" }
        require(guess >= 0) { "guess must be >= 0" }
        val ratio = abs(guess - actual) / actual
        val (points, label) = tiers.first { ratio <= it.first }.second
        return Score(points, label, ratio, guess - actual)
    }
}
