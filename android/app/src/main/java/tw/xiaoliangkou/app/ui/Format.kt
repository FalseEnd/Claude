package tw.xiaoliangkou.app.ui

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import tw.xiaoliangkou.app.core.Catalog
import tw.xiaoliangkou.app.data.Activity
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Fmt {
    private val zone get() = ZoneId.systemDefault()
    private val num = NumberFormat.getIntegerInstance(Locale.TAIWAN)
    private val hm = DateTimeFormatter.ofPattern("HH:mm")
    private val week = listOf("一", "二", "三", "四", "五", "六", "日")

    fun money(v: Double?) = if (v == null) "NT$ ???" else "NT$" + num.format(Math.round(v))
    fun int(v: Int) = num.format(v)
    fun time(i: Instant): String = hm.format(i.atZone(zone))
    fun weekday(d: LocalDate) = "週" + week[d.dayOfWeek.value - 1]
    fun md(d: LocalDate) = "${d.monthValue}/${d.dayOfMonth}"

    fun dayLabel(d: LocalDate, today: LocalDate = LocalDate.now()) = when (d) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        today.plusDays(1) -> "明天"
        else -> "${md(d)}（${weekday(d).substring(1)}）"
    }

    fun daysLeft(n: Int) = when {
        n == 0 -> "今天"
        n == 1 -> "明天"
        n < 0 -> "${-n} 天前"
        else -> "$n 天後"
    }

    fun rel(i: Instant?, now: Instant = Instant.now()): String {
        if (i == null) return "—"
        val sec = Duration.between(i, now).seconds.coerceAtLeast(0)
        val d = i.atZone(zone).toLocalDate()
        val days = java.time.temporal.ChronoUnit.DAYS.between(d, now.atZone(zone).toLocalDate())
        return when {
            sec < 60 -> "剛剛"
            sec < 3600 -> "${sec / 60} 分鐘前"
            days == 0L -> "${sec / 3600} 小時前"
            days == 1L -> "昨天 ${time(i)}"
            else -> "${md(d)} ${time(i)}"
        }
    }

    fun distance(m: Double) = if (m < 1000) "${(Math.round(m / 10) * 10).coerceAtLeast(10)} 公尺" else "%.1f 公里".format(m / 1000)

    fun localDate(i: Instant): LocalDate = i.atZone(zone).toLocalDate()

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.n(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull

    /** 歷史紀錄的文字；actor 為「你」或對方名字 */
    fun history(a: Activity, myId: String?, partnerName: String): Pair<String, String> {
        val mine = a.actorId != null && a.actorId == myId
        val who = if (mine) "你" else partnerName
        val p = a.payload
        return when (a.kind) {
            "paired" -> "💞" to "你們配對成功了"
            "unpaired" -> "💔" to "$who 解除了配對"
            "place_arrive" -> "📍" to "$who 已抵達 ${p.s("place") ?: "標點"}"
            "place_leave" -> "👋" to "$who 已離開 ${p.s("place") ?: "標點"}"
            "mood" -> {
                val m = Catalog.mood(p.s("mood"))
                (m?.emoji ?: "🙂") to "$who 覺得${m?.label ?: "…"}" + (p.s("note")?.let { "：$it" } ?: "")
            }
            "comfort" -> {
                val c = Catalog.comfort(p.s("kind"))
                (c?.emoji ?: "🤗") to (if (mine) "你送出了${c?.label ?: "安撫"}" else "$partnerName ${c?.push ?: "安撫了你"}") + (p.s("message")?.let { "：$it" } ?: "")
            }
            "guess" -> "💰" to "$who 猜了 ${p.s("merchant") ?: ""}：猜 ${money(p.n("guess"))}，實際 ${money(p.n("actual"))}（${p.n("points")?.toInt() ?: 0} 分）"
            "event_created" -> "📅" to "$who 約了：${p.s("title") ?: "約會"}"
            "event_confirmed" -> "🥳" to "$who 答應了：${p.s("title") ?: "約會"}"
            "event_declined" -> "🙅" to "$who 婉拒了：${p.s("title") ?: "約會"}"
            "battery_low" -> "🔋" to "$who 的手機電量剩 ${p.n("level")?.toInt() ?: "?"}%"
            "sharing_changed" -> "🔒" to (if (p.containsKey("paused_until")) (if (p.s("paused_until") == null) "$who 恢復分享了" else "$who 暫停分享") else "$who 更新了分享設定")
            else -> "✨" to a.kind
        }
    }

    fun historyGroup(kind: String) = when {
        kind.startsWith("place") -> "place"
        kind == "mood" -> "mood"
        kind == "comfort" -> "comfort"
        kind == "guess" -> "money"
        kind.startsWith("event") -> "date"
        kind == "battery_low" -> "device"
        else -> "pair"
    }

    fun isoDate(d: LocalDate): String = d.toString()
    fun parseDate(s: String?): LocalDate? = s?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    fun jsonString(o: JsonObject, k: String) = o[k]?.jsonPrimitive?.contentOrNull
}
