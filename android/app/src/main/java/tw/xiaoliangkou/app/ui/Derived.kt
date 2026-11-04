package tw.xiaoliangkou.app.ui

import tw.xiaoliangkou.app.core.Catalog
import tw.xiaoliangkou.app.core.Cycle
import tw.xiaoliangkou.app.core.Dates
import tw.xiaoliangkou.app.core.Geo
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.data.Comfort
import tw.xiaoliangkou.app.data.Expense
import tw.xiaoliangkou.app.data.Mood
import tw.xiaoliangkou.app.data.toInstantOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 由 AppState 算出畫面要顯示的內容 */
object Derived {
    fun moodToday(s: AppState, userId: String?): Mood? {
        val today = LocalDate.now()
        return s.moods.filter { it.userId == userId }
            .mapNotNull { m -> m.createdAt.toInstantOrNull()?.let { it to m } }
            .filter { Fmt.localDate(it.first) == today }
            .maxByOrNull { it.first }?.second
    }

    fun moodOn(s: AppState, userId: String?, day: LocalDate): Mood? =
        s.moods.filter { it.userId == userId }
            .mapNotNull { m -> m.createdAt.toInstantOrNull()?.let { it to m } }
            .filter { Fmt.localDate(it.first) == day }
            .maxByOrNull { it.first }?.second

    fun partnerNeedsComfort(s: AppState) = Catalog.mood(moodToday(s, s.partner?.id)?.mood)?.needsComfort == true

    /** 首頁「收到的安撫」只看最近幾天收到的 */
    const val RECEIVED_DAYS = 3L

    /**
     * 首頁「收到的安撫」：最近 [RECEIVED_DAYS] 天收到、還沒看過的最新一則（s.comforts 新的在前）。
     * 0.2.1 以前從來沒有標過看過：太久以前的不拿出來，更新後才不會把以前的安撫一則一則翻出來，也不會對很久以前的安撫「回抱」
     */
    fun receivedComfort(s: AppState, now: Instant = Instant.now()): Comfort? {
        val since = now.minus(RECEIVED_DAYS, ChronoUnit.DAYS)
        return s.comforts.firstOrNull { it.receiverId == s.myId && it.readAt == null && it.createdAt.toInstantOrNull()?.isAfter(since) == true }
    }

    /** 對方在哪：標點名稱 > 移動方式 */
    fun partnerWhere(s: AppState): String {
        val loc = s.partnerStatus.location ?: return "還沒有位置資料"
        val inside = s.partnerInside
        if (inside.isNotEmpty()) return "在 " + inside.joinToString("、") { it.name }
        return when (loc.activity) {
            "walking", "running" -> "步行中"
            "transit" -> "搭捷運中"
            "driving" -> "開車中"
            "cycling" -> "騎車中"
            else -> "停留中"
        }
    }

    /** 和對方的距離：藍牙偵測到就在身邊時是「就在你身邊」，不然用 GPS 算（[prefix] 是「相距」「距離你」） */
    fun distanceText(s: AppState, prefix: String): String? =
        if (s.partnerNearby) "就在你身邊" else distanceToPartner(s)?.let { "$prefix ${Fmt.distance(it)}" }

    fun distanceToPartner(s: AppState): Double? {
        val me = s.myLocation ?: return null
        val p = s.partnerStatus.location ?: return null
        return Geo.distance(me.lat, me.lng, p.lat, p.lng)
    }

    fun prediction(s: AppState): Cycle.Prediction? =
        if (!s.cycleVisible || s.cyclePeriods.isEmpty()) null else Cycle.predict(s.cyclePeriods, s.cycleSettings, LocalDate.now())

    data class Upcoming(val emoji: String, val title: String, val sub: String, val daysLeft: Int, val route: String, val date: LocalDate? = null)

    fun upcoming(s: AppState): List<Upcoming> {
        val today = LocalDate.now()
        val out = mutableListOf<Upcoming>()
        s.events.filter { it.status != "declined" && it.status != "cancelled" && !it.localDate.isBefore(today) }.forEach { e ->
            val waiting = e.status == "proposed"
            val title = e.title + if (waiting) (if (e.createdBy == s.myId) "（等對方回覆）" else "（等你回覆）") else ""
            out += Upcoming("📅", title, "${Fmt.dayLabel(e.localDate)} ${Fmt.time(e.start)}" + (e.locationText?.let { " · $it" } ?: ""), Dates.between(today, e.localDate), "calendar", e.localDate)
        }
        s.anniversaries.forEach { a ->
            val d = Fmt.parseDate(a.date) ?: return@forEach
            val occ = Dates.nextOccurrence(d, today, a.repeat == "yearly")
            if (occ.daysLeft < 0) return@forEach
            val title = a.title + if (occ.years > 0 && a.kind != "birthday") " ${occ.years} 週年" else ""
            out += Upcoming(if (a.kind == "birthday") "🎂" else "💝", title, "${Fmt.md(occ.date)}（${Fmt.weekday(occ.date).substring(1)}）", occ.daysLeft, "anniv", occ.date)
        }
        s.togetherSince?.let { since ->
            val m = Dates.upcomingMilestones(since, today, 1).first()
            out += Upcoming("✨", "在一起第 ${m.day} 天", "${Fmt.md(m.date)}（${Fmt.weekday(m.date).substring(1)}）", m.daysLeft, "anniv", m.date)
        }
        prediction(s)?.takeIf { it.lateDays == 0 && it.daysUntilNext >= 0 }?.let { p ->
            val who = if (s.cycleOwnerIsMe) "我" else s.partnerName
            out += Upcoming("🌸", "$who 的經期預計開始", "${Fmt.md(p.nextStart)} · 依過去 ${p.stats.samples} 個週期推算", p.daysUntilNext, "cycle", p.nextStart)
        }
        return out.sortedBy { it.daysLeft }
    }

    /** 近 N 天、看得到的開銷（對方的待猜開銷不算進統計） */
    fun recent(list: List<Expense>, days: Long = 30): List<Expense> {
        val since = Instant.now().minusSeconds(days * 86400)
        return list.filter { it.time.isAfter(since) }
    }

    fun surpriseHidden(e: Expense) = e.visibility == "surprise" && (Fmt.parseDate(e.revealOn)?.isAfter(LocalDate.now()) ?: true)
}
