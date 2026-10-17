package tw.xiaoliangkou.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tw.xiaoliangkou.app.ui.components.breakCost
import tw.xiaoliangkou.app.ui.components.breakSpots
import tw.xiaoliangkou.app.ui.components.planLines

/** TidyText 的換行規劃：在標點、空白後面換行，不拆詞、不留孤字、行數不變 */
class TidyTextTest {
    /** 假的字寬：中文字 1、英數字 0.55、空白 0.3、表情 1.1 */
    private fun xs(s: String): (Int) -> Float {
        val x = FloatArray(s.length + 1)
        for (i in s.indices) x[i + 1] = x[i] + when {
            s[i].isWhitespace() -> 0.3f
            s[i].code < 0x2000 -> 0.55f
            Character.isSurrogate(s[i]) -> 0.55f
            else -> 1f
        }
        return { x[it] }
    }

    private fun wrap(s: String, width: Float, lines: Int = 2): List<String> {
        val starts = planLines(s, breakSpots(s), xs(s), width, lines) ?: return listOf("(null)")
        return (listOf(0) + starts.toList() + s.length).zipWithNext { a, b -> s.substring(a, b).trimEnd() }
    }

    @Test fun breaksAfterPunctuationInsteadOfInsideWords() {
        assertEquals(listOf("自動記帳（不給看、", "禮物模式除外）"), wrap("自動記帳（不給看、禮物模式除外）", 12f))
        assertEquals(listOf("文字、照片、語音，", "可以放在桌面"), wrap("文字、照片、語音，可以放在桌面", 12.3f))
        assertEquals(listOf("今天的運勢、兩人速配、", "抽一支籤"), wrap("今天的運勢、兩人速配、抽一支籤", 12.3f))
        assertEquals(listOf("扮演 小雨，", "看看會跳出什麼通知"), wrap("扮演 小雨，看看會跳出什麼通知", 12.3f))
        assertEquals(listOf("在桌面看在一起的天數、", "小雨 的狀態、備忘錄"), wrap("在桌面看在一起的天數、小雨 的狀態、備忘錄", 12.3f))
    }

    @Test fun keepsNamesTogetherAndAvoidsOrphans() {
        // 在名字前面的空白換行，新的一行不用「的」開頭
        assertEquals(listOf("通知列顯示", "小雨 的狀態"), wrap("通知列顯示 小雨 的狀態", 8f))
        assertEquals(listOf("電量、充電、網路、", "螢幕、步數"), wrap("電量、充電、網路、螢幕、步數", 12f))
        assertEquals(listOf("看電影", "（等你回覆）"), wrap("看電影（等你回覆）", 7f))
        // 括號裡的不拆開，數字和單位也不拆開
        assertEquals(listOf("錄音中… 再按一下停止", "（最長 3 分鐘）"), wrap("錄音中… 再按一下停止（最長 3 分鐘）", 14f))
        assertEquals(listOf("約", "30 Mbps"), wrap("約 30 Mbps", 4f))
        // 沒有標點時在「的」後面換，不拆「電量」
        assertEquals(listOf("常駐通知會顯示對方的", "電量、位置、心情"), wrap("常駐通知會顯示對方的電量、位置、心情", 11f))
    }

    @Test fun neverUsesMoreLinesOrOverflows() {
        val s = "暫停位置、手機狀態與開銷。小雨 會看到「暫停中」與恢復時間，不會以為你失聯。"
        val lines = wrap(s, 14f, 3)
        assertEquals(3, lines.size)
        lines.forEach { l -> assert(xs(l)(l.length) <= 14f) { l } }
        // 一行塞不下又沒地方換（一長串英文）：不規劃，照一般的 Text
        assertNull(planLines("abcdefghijklmnop", breakSpots("abcdefghijklmnop"), xs("abcdefghijklmnop"), 4f, 2))
    }

    @Test fun legalBreakSpots() {
        fun can(s: String, p: Int) = breakCost(s, p) != null
        assertEquals(false, can("Wi-Fi", 3)) // 英文字中間
        assertEquals(false, can("10/22", 3))
        assertEquals(false, can("電量、充電", 2)) // 「、」不放行首
        assertEquals(false, can("（等你", 1)) // 「（」不放行尾
        assertEquals(false, can("514 天", 4)) // 不換行空格兩邊
        assertEquals(false, can("❤️好", 1)) // 表情和變體選擇符中間
        assertEquals(0f, breakCost("電量、充電", 3))
        assertEquals(0f, breakCost("小雨 也看得到", 3))
        assertEquals(1.5f, breakCost("看得到", 2))
        assertEquals(false, can("約 30 Mbps", 5)) // 數字和單位
        assertEquals(0f, breakCost("約 30 Mbps", 2))
    }
}
