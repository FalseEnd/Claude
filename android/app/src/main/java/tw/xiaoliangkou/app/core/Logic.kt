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

/** 猜花費計分（與伺服器 submit_guess() 相同的分級） */
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

/** 付款通知解析：LINE Pay、街口、Apple Pay / Google Pay、銀行刷卡通知 */
object PaymentParser {
    data class Parsed(val amount: Double, val merchant: String, val source: String, val category: String)

    private val categoryWords = linkedMapOf(
        "drink" to listOf("星巴克", "starbucks", "路易莎", "louisa", "cama", "50嵐", "清心", "可不可", "迷客夏", "咖啡", "coffee", "茶"),
        "food" to listOf("麥當勞", "摩斯", "肯德基", "鼎泰豐", "餐廳", "便當", "拉麵", "火鍋", "小吃", "食堂", "uber eats", "foodpanda"),
        "transport" to listOf("高鐵", "台鐵", "捷運", "悠遊卡", "uber", "計程車", "中油", "加油", "停車", "youbike"),
        "daily" to listOf("7-eleven", "統一超商", "全家", "萊爾富", "全聯", "家樂福", "屈臣氏", "康是美", "寶雅"),
        "shopping" to listOf("uniqlo", "zara", "momo", "蝦皮", "pchome", "誠品", "nike", "百貨"),
        "fun" to listOf("威秀", "電影", "netflix", "spotify", "steam", "ktv", "好樂迪", "錢櫃"),
    )

    private val amountPatterns = listOf(
        Regex("""(?:NT\$|NTD|新台幣|\$)\s*([\d,]+(?:\.\d+)?)""", RegexOption.IGNORE_CASE),
        Regex("""([\d,]+(?:\.\d+)?)\s*元"""),
    )
    private val merchantPatterns = listOf(
        Regex("""商店[:：]\s*([^\n，,。]+)"""),
        Regex("""給\s*([^\n，,。]+)"""),
        Regex("""於\s*([^\s\d][^\n，,。]*?)\s*消費"""),
        Regex("""交易[:：]\s*([^\n，,。$]+?)\s*(?:NT\$|NTD|新台幣|\$|$)""", RegexOption.IGNORE_CASE),
    )
    private val sources = listOf(
        "linepay" to Regex("""line\s*pay""", RegexOption.IGNORE_CASE),
        "jkopay" to Regex("街口"),
        "applepay" to Regex("""apple\s*pay|google\s*pay|google\s*錢包""", RegexOption.IGNORE_CASE),
        "card" to Regex("信用卡|卡片|刷卡|末四碼"),
        "easywallet" to Regex("悠遊付"),
    )
    /** 通知必須包含這些字才當作付款，避免把聊天訊息裡的金額記成帳 */
    private val paymentWords = Regex("付款|消費|交易|扣款|刷卡|支付")

    fun categorize(merchant: String): String {
        val m = merchant.lowercase()
        return categoryWords.entries.firstOrNull { (_, words) -> words.any { m.contains(it) } }?.key ?: "other"
    }

    fun parse(text: String): Parsed? {
        val amount = amountPatterns.firstNotNullOfOrNull { it.find(text) }?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
        if (amount == null || amount <= 0) return null
        val merchant = merchantPatterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotEmpty) } ?: "未知商家"
        val source = sources.firstOrNull { it.second.containsMatchIn(text) }?.first ?: "other"
        return Parsed(amount, merchant, source, categorize(merchant))
    }

    /** 從系統通知判斷是不是付款通知 */
    fun parsePaymentNotification(title: String?, text: String?): Parsed? {
        val all = listOfNotNull(title, text).joinToString(" ")
        if (!paymentWords.containsMatchIn(all)) return null
        return parse(all)
    }
}
