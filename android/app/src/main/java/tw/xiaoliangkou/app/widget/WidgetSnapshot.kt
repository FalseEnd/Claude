package tw.xiaoliangkou.app.widget

import kotlinx.serialization.Serializable
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.ui.StatusText

/**
 * 桌面小工具要顯示的資料。App 沒在跑時小工具也要畫得出來，所以存一份在手機裡（見 [Widgets]）。
 * 天數、「幾點更新」在畫的時候才算，放久了也不會錯。
 */
@Serializable
data class WidgetSnapshot(
    val kind: Kind = Kind.SIGNED_OUT,
    val demo: Boolean = false,
    val myName: String = "我",
    val myAvatar: String? = null,
    val partnerName: String = "對方",
    val partnerAvatar: String? = null,
    val togetherSince: String? = null,
    /** 「在 公司 · 相距 2.4 公里」（對方沒分享位置就是 null） */
    val place: String? = null,
    /** 其他項目：「🔋 85%」「😊 開心」… */
    val items: List<String> = emptyList(),
    /** 暫停分享、沒有分享或還沒收到資料時的說明 */
    val note: String? = null,
    val updatedAtMs: Long? = null,
) {
    enum class Kind { SIGNED_OUT, UNPAIRED, PAIRED }

    companion object {
        fun of(s: AppState?): WidgetSnapshot {
            if (s == null) return WidgetSnapshot()
            val me = s.me?.displayName ?: "我"
            if (!s.paired) return WidgetSnapshot(Kind.UNPAIRED, s.demo, me, s.me?.avatarUrl)
            val st = StatusText.of(s, live = false)
            return WidgetSnapshot(
                kind = Kind.PAIRED,
                demo = s.demo,
                myName = me,
                myAvatar = s.me?.avatarUrl,
                partnerName = s.partnerName,
                partnerAvatar = s.partner?.avatarUrl,
                togetherSince = s.couple?.togetherSince,
                place = st.item(StatusText.PLACE)?.text,
                items = st.items.filter { it.key != StatusText.PLACE }.map { it.toString() },
                note = st.note,
                updatedAtMs = st.updatedAt?.toEpochMilli(),
            )
        }
    }
}
