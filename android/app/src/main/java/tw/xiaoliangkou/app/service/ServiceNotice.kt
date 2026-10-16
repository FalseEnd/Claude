package tw.xiaoliangkou.app.service

import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.ui.StatusText
import java.time.Instant

/**
 * 常駐通知要顯示的內容（背景服務一直在跑，通知不能拿掉，乾脆拿來顯示對方的狀態）。
 * [status] 為 true：對方狀態（「對方狀態」頻道）；false：只說明背景連線（「背景連線」頻道，最不打擾）。
 */
data class ServiceNotice(
    val status: Boolean,
    val title: String,
    val text: String,
    val big: String? = null,
    val sub: String? = null,
    val whenMs: Long? = null,
    val avatar: String? = null,
    val avatarName: String = "",
    /** 通知上的按鈕：「現在在哪？」（對方有分享位置才有）、「抱抱」 */
    val locate: Boolean = false,
    val hug: Boolean = false,
) {
    companion object {
        fun of(s: AppState, showStatus: Boolean, items: Set<String>, now: Instant = Instant.now()): ServiceNotice {
            val partner = s.partnerName
            val plain = when {
                !s.connected -> "即時連線暫時中斷，會自動重試；通知可能晚幾分鐘到"
                s.mySharing?.locationLive == true -> "正在和 $partner 分享位置"
                else -> "已連線，會即時收到 $partner 的通知"
            }
            if (!showStatus || !s.paired) return ServiceNotice(false, "小倆口", plain)

            val st = StatusText.of(s, now).only(items)
            val place = st.item(StatusText.PLACE)
            val rest = st.items.filter { it.key != StatusText.PLACE }
            // 展開後每項一行；位置已經在標題了，不再重複
            val lines = rest.map { it.toString() } + listOfNotNull(st.note)
            return ServiceNotice(
                status = true,
                // 收起來時只看得到一行：標題放位置，內文放其他項目
                title = partner + place?.let { " ${it.text}" }.orEmpty(),
                text = rest.joinToString(" · ") { it.toString() }.ifEmpty { st.note ?: if (place != null) "" else plain },
                big = lines.joinToString("\n").ifEmpty { null },
                sub = if (!s.connected) "連線中斷，資料可能不是最新" else StatusText.daysTogether(s)?.let { "在一起第 $it 天" },
                whenMs = st.updatedAt?.toEpochMilli(),
                avatar = s.partner?.avatarUrl,
                avatarName = partner,
                locate = s.partnerSharing?.locationLive == true,
                hug = true,
            )
        }
    }
}
