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

/** 地理圍欄用的距離和資料（判斷進出見 [GeofenceTracker]） */
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

    /** ([lat], [lng]) 在哪些標點的半徑裡（示範模式開始時、改了標點時用） */
    fun containing(fences: List<Fence>, lat: Double, lng: Double): Set<String> =
        fences.filter { distance(it.lat, it.lng, lat, lng) <= it.radius }.map { it.id }.toSet()
}

/**
 * 在被偵測的那支手機上判斷進出標點。手機的定位會飄（室內、省電定位常常差 50～150 公尺），
 * 只看一次定位的話，明明待在標點裡也會一直「離開、抵達」，所以：
 * 1. 看精確度：離開要整個誤差圈都在「半徑＋緩衝」外面；抵達要中心在半徑內，而且誤差不能比標點大太多
 * 2. 要確認：不是很明確的進出，要隔至少 [CONFIRM_MS] 的兩次定位都一樣才算（中間有一次不一樣就重來）
 * 3. 剛進出過的標點，[COOLDOWN_MS] 內反方向一定要確認過才算
 * 4. 一下子跳很遠（比 [MAX_SPEED] 還快）、比上一次舊、精確度差於 [MAX_ACCURACY] 的定位都不用
 */
class GeofenceTracker(initialInside: Set<String> = emptySet()) {
    /** 一次定位：[accuracy] 是誤差半徑（公尺），[at] 是定位的時間（毫秒） */
    data class Fix(val lat: Double, val lng: Double, val accuracy: Double, val at: Long)

    private data class Pending(val arrive: Boolean, val since: Long)

    private val inside = initialInside.toMutableSet()
    private val pending = mutableMapOf<String, Pending>()
    private val lastChange = mutableMapOf<String, Long>()
    private var last: Fix? = null

    val insideNow: Set<String> get() = inside.toSet()

    /** 有還沒確認的進出：過 [CONFIRM_MS] 再抓一次精確的位置來確認 */
    val waiting: Boolean get() = pending.isNotEmpty()

    fun update(fences: List<Geo.Fence>, fix: Fix): Geo.Result {
        // 刪掉的、改成偵測對方的標點就忘了
        val ids = fences.map { it.id }.toSet()
        inside.retainAll(ids)
        pending.keys.retainAll(ids)
        if (fix.accuracy > MAX_ACCURACY || !believable(fix)) return Geo.Result(insideNow, emptyList())
        last = fix
        val transitions = mutableListOf<Geo.Transition>()
        for (f in fences) {
            val d = Geo.distance(f.lat, f.lng, fix.lat, fix.lng)
            val was = f.id in inside
            val buffer = max(MIN_BUFFER, f.radius * 0.5)
            val (evidence, clear) = if (was) {
                val out = d - fix.accuracy > f.radius + buffer
                out to (out && d - fix.accuracy > f.radius + buffer + FAR)
            } else {
                val inn = d <= f.radius && fix.accuracy <= max(f.radius, MIN_ARRIVE_ACCURACY)
                inn to (inn && d + fix.accuracy <= f.radius)
            }
            if (!evidence) {
                pending.remove(f.id)
                continue
            }
            val arrive = !was
            val p = pending[f.id]?.takeIf { it.arrive == arrive }
            val confirmed = p != null && fix.at - p.since >= CONFIRM_MS
            val cooling = lastChange[f.id]?.let { fix.at - it < COOLDOWN_MS } == true
            if (confirmed || (clear && !cooling)) {
                if (arrive) inside += f.id else inside -= f.id
                pending.remove(f.id)
                lastChange[f.id] = fix.at
                transitions += Geo.Transition(f.id, arrive)
            } else if (p == null) {
                pending[f.id] = Pending(arrive, fix.at)
            }
        }
        return Geo.Result(insideNow, transitions)
    }

    /** 比上一次舊、或一下子跳太遠（扣掉兩次的誤差還比 [MAX_SPEED] 快）的定位不用 */
    private fun believable(fix: Fix): Boolean {
        val p = last ?: return true
        if (fix.at < p.at) return false
        val seconds = (fix.at - p.at) / 1000.0
        if (seconds > JUMP_WINDOW_S) return true
        val jump = Geo.distance(p.lat, p.lng, fix.lat, fix.lng) - fix.accuracy - p.accuracy
        return jump <= 0 || jump / max(seconds, 1.0) <= MAX_SPEED
    }

    companion object {
        /** 精確度差於這個（公尺）的定位不拿來判斷 */
        const val MAX_ACCURACY = 150.0
        /** 離開時半徑外面的緩衝：半徑的一半，至少這麼多（公尺） */
        const val MIN_BUFFER = 50.0
        /** 標點很小時，抵達還能接受的精確度（公尺） */
        const val MIN_ARRIVE_ACCURACY = 100.0
        /** 離開「很明確」：整個誤差圈都在緩衝外面再遠這麼多（公尺），不用等確認 */
        const val FAR = 300.0
        /** 不明確的進出要隔多久再確認一次 */
        const val CONFIRM_MS = 2 * 60_000L
        /** 剛進出過的標點，這段時間內反方向一定要確認過 */
        const val COOLDOWN_MS = 10 * 60_000L
        /** 比這個還快（公尺／秒，約時速 250 公里）的跳動當作定位飄掉 */
        const val MAX_SPEED = 70.0
        /** 只檢查 5 分鐘內的兩次定位有沒有亂跳 */
        const val JUMP_WINDOW_S = 300.0
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
