package tw.xiaoliangkou.app

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import tw.xiaoliangkou.app.data.RemoteRepository.Companion.routeFor

/** 點通知（和 App 開著時跳出的卡片）打開的畫面：要回應的直接打開回應的地方 */
class NotificationRoutesTest {
    @Test fun receivedComfortOpensTheReply() {
        assertEquals("comfort/42", routeFor("comfort", buildJsonObject { put("comfort_id", 42) }))
        assertEquals("comfort", routeFor("comfort"))
        assertEquals("comfort", routeFor("comfort", buildJsonObject { put("comfort_id", "../x") }))
    }

    @Test fun aMoodThatNeedsAHugOpensComfort() {
        assertEquals("comfort", routeFor("mood", buildJsonObject { put("mood", "sad") }))
        assertEquals("comfort", routeFor("mood", buildJsonObject { put("mood", "jealous") }))
        assertEquals("home", routeFor("mood", buildJsonObject { put("mood", "happy") }))
        assertEquals("home", routeFor("mood"))
    }

    @Test fun datesOpenTheirDay() {
        val id = "0f8fad5b-d9cb-469f-a165-70867728950e"
        assertEquals("event/$id", routeFor("event", buildJsonObject { put("event_id", id) }))
        assertEquals("calendar", routeFor("event", buildJsonObject { put("event_id", "../memos") }))
        assertEquals("calendar", routeFor("event"))
        assertEquals("calendar/2026-11-28", routeFor("anniversary", buildJsonObject { put("date", "2026-11-28") }))
        assertEquals("calendar", routeFor("anniversary", buildJsonObject { put("date", "nope") }))
    }
}
