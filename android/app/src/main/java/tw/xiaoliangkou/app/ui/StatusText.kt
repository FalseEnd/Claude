package tw.xiaoliangkou.app.ui

import tw.xiaoliangkou.app.core.Catalog
import tw.xiaoliangkou.app.core.Dates
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.data.toInstantOrNull
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * 對方狀態的文字，常駐通知和桌面小工具共用。只會出現對方有分享的項目；
 * 時間一律寫成「14:05」，通知或小工具放久了也不會變成錯的「剛剛」。
 */
object StatusText {
    const val PLACE = "place"
    const val BATTERY = "battery"
    const val MOOD = "mood"
    const val PHONE = "phone"
    const val NETWORK = "network"
    const val STEPS = "steps"

    /** 設定畫面的選項，也是顯示的順序 */
    val options = listOf(PLACE to "位置與距離", BATTERY to "電量", MOOD to "今天的心情", PHONE to "手機使用", NETWORK to "網路", STEPS to "步數")
    val DEFAULTS = setOf(PLACE, BATTERY, MOOD, PHONE)

    data class Item(val key: String, val emoji: String, val text: String) {
        override fun toString() = "$emoji $text"
    }

    /**
     * [items]：照 [options] 的順序、對方有分享的項目；[note]：暫停分享、沒有分享或還沒收到資料時的說明；
     * [updatedAt]：手機狀態或位置最後更新的時間。
     */
    data class Status(val name: String, val items: List<Item>, val note: String?, val updatedAt: Instant?) {
        fun only(keys: Set<String>) = copy(items = items.filter { it.key in keys })
        fun item(key: String) = items.firstOrNull { it.key == key }
    }

    /**
     * [live]：會一直更新的地方（常駐通知每分鐘重算）可以寫「正在用手機」；
     * 桌面小工具不會隨時更新，只寫「14:05 用過手機」這種放久了也不會錯的說法。
     */
    fun of(s: AppState, now: Instant = Instant.now(), live: Boolean = true): Status {
        val name = s.partnerName
        val sharing = s.partnerSharing
        val d = s.partnerStatus.device?.takeIf { sharing?.deviceLive == true }
        val loc = s.partnerStatus.location?.takeIf { sharing?.locationLive == true }
        fun hm(i: Instant) = Fmt.time(i)
        val items = buildList {
            if (loc != null) {
                val far = Derived.distanceToPartner(s)?.let { " · 相距 ${Fmt.distance(it)}" }.orEmpty()
                add(Item(PLACE, "📍", Derived.partnerWhere(s) + far))
            }
            d?.batteryLevel?.let { level ->
                when {
                    d.isCharging == true -> add(Item(BATTERY, "⚡", "$level% 充電中"))
                    level <= 20 -> add(Item(BATTERY, "🪫", "$level% 電量偏低"))
                    else -> add(Item(BATTERY, "🔋", "$level%"))
                }
            }
            if (sharing?.shareMood != false) {
                Derived.moodToday(s, s.partner?.id)?.let { m ->
                    Catalog.mood(m.mood)?.let { c -> add(Item(MOOD, c.emoji, c.label + m.note?.takeIf { it.isNotBlank() }?.let { "：" + it.take(14) }.orEmpty())) }
                }
            }
            if (d != null) {
                val active = d.lastActiveAt?.toInstantOrNull()
                when {
                    d.notificationsSilenced == true -> add(Item(PHONE, "🔕", "勿擾中"))
                    active == null -> Unit
                    !live -> add(Item(PHONE, "📱", "${hm(active)} 用過手機"))
                    Duration.between(active, now).seconds < 120 -> add(Item(PHONE, "📱", "正在用手機"))
                    else -> add(Item(PHONE, "😴", "休息中 · ${hm(active)} 用過手機"))
                }
                when (d.network) {
                    "wifi" -> add(Item(NETWORK, "📶", "Wi-Fi" + d.networkLabel?.let { "（$it）" }.orEmpty()))
                    // 有電信就寫電信（「中華電信（約 30 Mbps）」），舊版 App 沒回報電信時寫「行動網路」
                    "cellular" -> add(Item(NETWORK, "📶", (d.carrier ?: "行動網路") + d.networkLabel?.let { "（$it）" }.orEmpty()))
                    "offline" -> add(Item(NETWORK, "📵", "沒有網路"))
                }
                d.stepsToday?.let { add(Item(STEPS, "👣", "${Fmt.int(it)} 步")) }
            }
        }.sortedBy { item -> options.indexOfFirst { it.first == item.key } }
        val note = when {
            !s.paired -> "還沒配對"
            sharing?.paused == true -> "$name 暫停分享到 ${hm(sharing.pausedUntilInstant!!)}，時間到會自動恢復"
            sharing?.shareLocation != true && sharing?.shareDevice != true -> "$name 目前沒有分享位置和手機狀態"
            items.isEmpty() -> "還沒收到 $name 的狀態，等對方打開小倆口 📡"
            else -> null
        }
        val updated = listOfNotNull(d?.updatedAt?.toInstantOrNull(), loc?.updatedAt?.toInstantOrNull()).maxOrNull()
        return Status(name, items, note, updated)
    }

    /** 「在一起第 N 天」；還沒設定在一起的日子就是 null */
    fun daysTogether(s: AppState, today: LocalDate = LocalDate.now()): Int? = s.togetherSince?.let { Dates.daysTogether(it, today) }
}
