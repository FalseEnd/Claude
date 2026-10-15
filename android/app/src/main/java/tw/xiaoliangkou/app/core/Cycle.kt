package tw.xiaoliangkou.app.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * 經期預測，與 prototype/core.js 的演算法相同（伺服器的關懷提醒也用那一份）。
 * - 週期長度：最近 6 個週期的平均，15–60 天以外視為漏記
 * - 排卵日：下次經期前 [Settings.lutealLength] 天（預設 14）
 * - 易孕期：排卵日前 5 天到後 1 天，其餘為「相對安全期」
 */
object Cycle {
    data class Period(val start: LocalDate, val end: LocalDate? = null)
    data class Settings(val cycleLength: Int? = null, val periodLength: Int? = null, val lutealLength: Int? = null)
    data class Stats(val cycleLength: Int, val periodLength: Int, val lutealLength: Int, val samples: Int, val irregular: Boolean)

    enum class Phase { PERIOD, FERTILE, OVULATION, SAFE, LATE }

    data class DayInfo(
        val phase: Phase?,
        val predicted: Boolean = false,
        val cycleDay: Int? = null,
        /** SAFE 時：true = 濾泡期（排卵前），false = 黃體期 */
        val follicular: Boolean? = null,
    )

    data class Prediction(
        val stats: Stats,
        val cycleStart: LocalDate,
        val cycleDay: Int,
        val nextStart: LocalDate,
        val daysUntilNext: Int,
        val ovulation: LocalDate,
        val fertileStart: LocalDate,
        val fertileEnd: LocalDate,
        val lateDays: Int,
        val today: DayInfo,
    )

    private const val DEFAULT_CYCLE = 28
    private const val DEFAULT_PERIOD = 5
    private const val DEFAULT_LUTEAL = 14
    private const val WINDOW = 6
    private const val PROJECT = 6

    private fun days(a: LocalDate, b: LocalDate) = ChronoUnit.DAYS.between(a, b).toInt()

    fun stats(periods: List<Period>, settings: Settings = Settings()): Stats {
        val sorted = periods.sortedBy { it.start }
        val cycles = sorted.zipWithNext { a, b -> days(a.start, b.start) }.filter { it in 15..60 }.takeLast(WINDOW)
        val lengths = sorted.mapNotNull { p -> p.end?.let { days(p.start, it) + 1 } }.filter { it in 1..10 }.takeLast(WINDOW)
        return Stats(
            cycleLength = settings.cycleLength ?: if (cycles.isEmpty()) DEFAULT_CYCLE else cycles.average().roundToInt(),
            periodLength = settings.periodLength ?: if (lengths.isEmpty()) DEFAULT_PERIOD else lengths.average().roundToInt(),
            lutealLength = settings.lutealLength ?: DEFAULT_LUTEAL,
            samples = cycles.size,
            irregular = cycles.size >= 3 && (cycles.max() - cycles.min()) > 7,
        )
    }

    private data class Start(val start: LocalDate, val end: LocalDate, val predicted: Boolean)

    private fun starts(periods: List<Period>, stats: Stats, today: LocalDate?): List<Start> {
        val sorted = periods.sortedBy { it.start }
        if (sorted.isEmpty()) return emptyList()
        val out = sorted.map { Start(it.start, it.end ?: it.start.plusDays(stats.periodLength - 1L), false) }.toMutableList()
        var next = sorted.last().start.plusDays(stats.cycleLength.toLong())
        // 超過一整個週期沒有記錄，視為忘了記：把預測推到今天之後
        while (today != null && days(next, today) >= stats.cycleLength) next = next.plusDays(stats.cycleLength.toLong())
        repeat(PROJECT) {
            out += Start(next, next.plusDays(stats.periodLength - 1L), true)
            next = next.plusDays(stats.cycleLength.toLong())
        }
        return out
    }

    fun dayPhase(date: LocalDate, periods: List<Period>, settings: Settings, today: LocalDate): DayInfo {
        val stats = stats(periods, settings)
        val list = starts(periods, stats, today)
        if (list.isEmpty() || date < list.first().start) return DayInfo(null)
        for (i in list.indices) {
            val s = list[i]
            val next = list.getOrNull(i + 1) ?: return DayInfo(null)
            if (date >= next.start) continue
            val cycleDay = days(s.start, date) + 1
            if (date <= s.end) return DayInfo(Phase.PERIOD, s.predicted, cycleDay)
            val ovulation = next.start.minusDays(stats.lutealLength.toLong())
            val predicted = s.predicted || next.predicted
            return when {
                date == ovulation -> DayInfo(Phase.OVULATION, predicted, cycleDay)
                date >= ovulation.minusDays(5) && date <= ovulation.plusDays(1) -> DayInfo(Phase.FERTILE, predicted, cycleDay)
                else -> DayInfo(Phase.SAFE, predicted, cycleDay, follicular = date < ovulation)
            }
        }
        return DayInfo(null)
    }

    fun predict(periods: List<Period>, settings: Settings, today: LocalDate): Prediction? {
        val sorted = periods.sortedBy { it.start }
        if (sorted.isEmpty()) return null
        val stats = stats(sorted, settings)
        val list = starts(sorted, stats, today)
        var current = list.lastOrNull { it.start <= today }?.start ?: sorted.first().start
        var next = list.first { it.start > today }.start
        val expected = sorted.last().start.plusDays(stats.cycleLength.toLong())
        var late = days(expected, today)
        // 預測日已過、但還在一個週期內：視為延遲，而不是直接跳到下一個週期
        if (late in 1 until stats.cycleLength) {
            current = sorted.last().start
            next = expected
        } else {
            late = 0
        }
        val ovulation = next.minusDays(stats.lutealLength.toLong())
        return Prediction(
            stats = stats,
            cycleStart = current,
            cycleDay = days(current, today) + 1,
            nextStart = next,
            daysUntilNext = days(today, next),
            ovulation = ovulation,
            fertileStart = ovulation.minusDays(5),
            fertileEnd = ovulation.plusDays(1),
            lateDays = late,
            today = if (late > 0) DayInfo(Phase.LATE) else dayPhase(today, sorted, settings, today),
        )
    }
}
