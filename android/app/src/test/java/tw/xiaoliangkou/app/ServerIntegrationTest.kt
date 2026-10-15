package tw.xiaoliangkou.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import tw.xiaoliangkou.app.core.PaymentParser
import tw.xiaoliangkou.app.data.Api
import tw.xiaoliangkou.app.data.AppException
import tw.xiaoliangkou.app.data.RealtimeClient
import tw.xiaoliangkou.app.data.RemoteRepository
import tw.xiaoliangkou.app.data.SessionManager
import tw.xiaoliangkou.app.data.SupabaseClient
import tw.xiaoliangkou.app.data.apiCall
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * App 的資料層對真正的 Supabase 跑一遍：註冊登入、RPC 的 JSON 欄位、Realtime 通知與自動更新、token 換新。
 * 需要已經套用 supabase/migrations 的專案，而且還沒有人註冊（一個小倆口只能有兩個人），例如本機：
 *   supabase start && supabase db reset
 *   XLK_TEST_SUPABASE_URL=http://127.0.0.1:54321 XLK_TEST_SUPABASE_KEY=<ANON_KEY> ./gradlew testDebugUnitTest --tests '*ServerIntegrationTest*'
 * 沒設定環境變數時會略過。
 */
class ServerIntegrationTest {
    private val url: String? = System.getenv("XLK_TEST_SUPABASE_URL")
    private val key: String? = System.getenv("XLK_TEST_SUPABASE_KEY")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val run = UUID.randomUUID().toString().take(8)

    @Before fun requireSupabase() = Assume.assumeTrue("set XLK_TEST_SUPABASE_URL and XLK_TEST_SUPABASE_KEY to run", url != null && key != null)
    @After fun tearDown() = scope.cancel()

    private suspend fun eventually(what: String, ms: Long = 10_000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (check()) return; delay(100) }
        throw AssertionError("等不到：$what")
    }

    private suspend fun failsWith(code: String, block: suspend () -> Unit) {
        try { apiCall { block() }; throw AssertionError("應該要失敗：$code") } catch (e: AppException) { assertEquals(e.message, code, e.code) }
    }

    private fun email(name: String) = "$name+$run@example.com"
    private fun anon() = SupabaseClient(url!!, key!!, null)

    private class Client(
        val email: String, val session: SessionManager, val supabase: SupabaseClient, val api: Api,
        val repo: RemoteRepository, val realtime: RealtimeClient, val notes: MutableList<RealtimeClient.Event.Notification>,
    ) {
        val userId get() = session.current!!.userId
    }

    private suspend fun signUp(name: String, displayName: String): Client {
        val s = try {
            apiCall { anon().signUp(email(name), "secret1", displayName) }
        } catch (e: AppException) {
            if (e.code == "registration_closed") throw AssertionError("這個 Supabase 已經有人註冊了，請先 supabase db reset", e)
            throw e
        }
        val sm = SessionManager(url!!, key!!, s)
        val c = SupabaseClient(url!!, key!!, sm)
        val rt = RealtimeClient(scope)
        sm.onChange = { rt.updateToken(it.accessToken) }
        val notes = java.util.Collections.synchronizedList(mutableListOf<RealtimeClient.Event.Notification>())
        val repo = RemoteRepository(c.api, rt, scope) { notes += it }
        rt.start(url!!, key!!, s.userId, { sm.freshToken() }, c.api)
        return Client(email(name), sm, c, c.api, repo, rt, notes)
    }

    @Test fun fullFlowAgainstSupabase() = runBlocking {
        withTimeout(120_000) {
            assertEquals(true, apiCall { anon().api.health() }["ok"]?.let { (it as JsonPrimitive).content.toBoolean() })

            // 註冊、登入
            val zhe = signUp("azhe", "阿哲")
            failsWith("email_taken") { anon().signUp(zhe.email, "secret1", "阿哲") }
            failsWith("invalid_login") { anon().signIn(zhe.email, "wrong-password") }
            failsWith("weak_password") { anon().signUp(email("weak"), "123", "弱") }
            assertEquals(zhe.userId, apiCall { anon().signIn(zhe.email, "secret1") }.userId)
            val yu = signUp("xiaoyu", "小雨")
            eventually("Realtime 連線與訂閱") { listOf(zhe, yu).all { it.realtime.connected.value && it.realtime.subscribed.value } }
            val meZhe = apiCall { zhe.api.me() }
            assertEquals(zhe.email, meZhe.user.email)
            assertEquals("阿哲", meZhe.user.displayName)
            assertNull(meZhe.couple)
            failsWith("not_paired") { zhe.repo.locatePartner() }

            // 配對
            val code = zhe.repo.createInvite().code
            yu.repo.acceptInvite(code, "2025-05-20")
            zhe.repo.refreshAll()
            val s = zhe.repo.state.value
            assertTrue(s.paired)
            assertEquals("小雨", s.partnerName)
            assertEquals(LocalDate.parse("2025-05-20"), s.togetherSince)
            eventually("配對通知") { zhe.notes.any { it.kind == "pairing" } }

            // 分享設定與手機狀態
            zhe.repo.updateSharing(buildJsonObject { put("location", true); put("device", true); put("expenses", true) })
            yu.repo.updateSharing(buildJsonObject { put("location", true); put("device", true); put("cycle", true) })
            eventually("分享變更通知") { yu.notes.any { it.kind == "sharing_changed" } }
            zhe.repo.reportDevice(buildJsonObject {
                put("batteryLevel", 55); put("isCharging", false); put("network", "wifi"); put("stepsToday", 3210); put("deviceModel", "Pixel 8")
            })
            zhe.repo.reportLocation(25.0330, 121.5654, 12f, 0f)
            eventually("對方畫面自動更新電量") { yu.repo.state.value.partnerStatus.device?.batteryLevel == 55 }
            eventually("對方在線上") { yu.repo.state.value.partnerOnline }
            assertEquals(25.0330, yu.repo.state.value.partnerStatus.location!!.lat, 1e-6)
            zhe.repo.reportDevice(buildJsonObject { put("batteryLevel", 15); put("isCharging", false); put("network", "cellular") })
            eventually("低電量通知") { yu.notes.any { it.kind == "battery_low" && it.title.contains("15%") } }

            // 暫停分享
            zhe.repo.pauseSharing(30)
            eventually("暫停後看不到位置") { yu.repo.state.value.partnerSharing?.paused == true && yu.repo.state.value.partnerStatus.location == null }
            zhe.repo.pauseSharing(0)

            // 心情與安撫：對方的畫面靠 Realtime 的 changed 自動更新
            yu.repo.postMood("sad", "被主管念了")
            eventually("心情同步") { zhe.repo.state.value.moods.any { it.mood == "sad" && it.note == "被主管念了" } }
            eventually("心情通知") { zhe.notes.any { it.kind == "mood" } }
            val banner = async { yu.repo.banners.first { it.kind == "comfort" } }
            zhe.repo.sendComfort("hug", "晚上請你吃好料")
            val b = withTimeout(6000) { banner.await() }
            assertTrue(b.title, b.title.contains("抱抱"))
            assertEquals("晚上請你吃好料", b.body)

            // 地點與地理圍欄
            zhe.repo.savePlace(null, buildJsonObject {
                put("name", "小雨公司"); put("icon", "work"); put("lat", 25.036); put("lng", 121.567); put("radiusM", 250); put("watch", "partner")
            })
            eventually("對方看到標點") { yu.repo.state.value.places.size == 1 }
            val place = yu.repo.state.value.places.first()
            assertEquals(yu.repo.state.value.myId, place.watchUser)
            yu.repo.reportPlaceEvent(place.id, arrive = true)
            eventually("抵達通知") { zhe.notes.any { it.kind == "place" && it.title.contains("已抵達 小雨公司") } }

            // 自動記帳與猜花費
            zhe.repo.addAutoExpense(PaymentParser.parse("LINE Pay｜付款完成 NT$135 商店：星巴克 信義新天地門市")!!, "k1")
            zhe.repo.addAutoExpense(PaymentParser.parse("LINE Pay｜付款完成 NT$135 商店：星巴克 信義新天地門市")!!, "k1")
            zhe.repo.saveExpense(null, buildJsonObject { put("merchant", "路易莎咖啡"); put("amount", 95); put("category", "drink"); put("guessable", true) })
            eventually("帳本同步") { zhe.repo.state.value.myExpenses.size == 2 }
            eventually("對方看到待猜開銷") { yu.repo.state.value.partnerExpenses.any { it.waitingForGuess } }
            val pending = yu.repo.state.value.partnerExpenses.first { it.waitingForGuess }
            assertNull(pending.amount)
            val g = yu.repo.guess(pending.id, 90.0)
            assertEquals(95.0, g.actualAmount, 0.0)
            assertEquals(80, g.scorePoints)
            eventually("猜完看到金額與分數") { yu.repo.state.value.partnerExpenses.first { it.id == pending.id }.myPoints == 80 }
            eventually("猜花費通知") { zhe.notes.any { it.kind == "guess" } }
            eventually("自己看到對方猜的金額") { zhe.repo.state.value.myExpenses.any { it.theirGuess == 90.0 } }

            // 約會
            zhe.repo.saveEvent(null, buildJsonObject {
                put("title", "吃火鍋"); put("startsAt", Instant.now().plusSeconds(86400).toString()); put("locationText", "老四川")
                putJsonArray("remindMinutes") { add(JsonPrimitive(60)) }
            })
            eventually("對方收到邀約") { yu.repo.state.value.events.any { it.title == "吃火鍋" } }
            val ev = yu.repo.state.value.events.first { it.title == "吃火鍋" }
            assertEquals("proposed", ev.status)
            yu.repo.answerEvent(ev.id, "confirmed")
            eventually("答應的通知") { zhe.notes.any { it.kind == "event" && it.title.contains("答應了") } }

            // 紀念日
            zhe.repo.saveAnniversary(null, buildJsonObject {
                put("title", "小雨生日"); put("date", "1999-11-21"); put("kind", "birthday"); put("repeat", "yearly")
                putJsonArray("remindDays") { add(JsonPrimitive(0)); add(JsonPrimitive(7)) }
            })
            eventually("紀念日同步") { yu.repo.state.value.anniversaries.any { it.title == "小雨生日" && it.remindDays == listOf(0, 7) } }

            // 經期：小雨記錄並分享，阿哲看得到預測、看不到症狀
            yu.repo.saveCycleSettings(buildJsonObject { put("careReminders", true); put("partnerCanEdit", true) })
            yu.repo.addPeriod(false, LocalDate.now().minusDays(26).toString(), LocalDate.now().minusDays(22).toString())
            yu.repo.saveCycleLog(LocalDate.now().toString(), "light", listOf("經痛"))
            eventually("經期同步") { zhe.repo.state.value.cycle.partner.periods.size == 1 }
            val z = zhe.repo.state.value
            assertTrue(z.cycleVisible)
            assertTrue(!z.cycleOwnerIsMe)
            assertTrue(z.cycle.partner.logs.isEmpty())
            assertEquals(1, yu.repo.state.value.cycle.mine.logs.size)
            zhe.repo.addPeriod(true, LocalDate.now().plusDays(2).toString(), null)
            eventually("幫對方記錄") { yu.repo.state.value.cycle.mine.periods.size == 2 }

            // 歷史紀錄與通知設定
            eventually("歷史紀錄") { zhe.repo.state.value.history.map { it.kind }.containsAll(listOf("paired", "mood", "comfort", "guess", "place_arrive")) }
            zhe.repo.setNotifPref("comfort", false)
            assertEquals(false, zhe.repo.state.value.notifPrefs["comfort"])

            // 「現在在哪」
            val locate = async { yu.realtime.events.first { it is RealtimeClient.Event.Locate } }
            assertTrue(zhe.repo.locatePartner())
            withTimeout(10_000) { locate.await() }

            // 斷線期間的通知：重新連上時用 app_pending_notifications 補抓，並標記已送達
            yu.realtime.stop()
            val before = yu.notes.size
            zhe.repo.sendComfort("pat", "斷線也收得到")
            yu.realtime.start(url!!, key!!, yu.userId, { yu.session.freshToken() }, yu.api)
            eventually("補抓斷線期間的通知") { yu.notes.drop(before).any { it.kind == "comfort" && it.body == "斷線也收得到" } }
            eventually("通知已確認送達") { runBlocking { apiCall { yu.api.pendingNotifications() } }.isEmpty() }
            assertEquals(1, yu.notes.count { it.body == "斷線也收得到" })

            // access token 換新：API 照常、Realtime 收到新 token 後繼續收通知
            val oldToken = zhe.session.current!!.accessToken
            assertNotNull(zhe.session.refreshBlocking(null))
            assertTrue(zhe.session.current!!.accessToken != oldToken)
            zhe.repo.refreshAll()
            zhe.repo.setNotifPref("comfort", true)
            yu.repo.sendComfort("hug", "換新 token 之後")
            eventually("換新 token 後的通知") { zhe.notes.any { it.body == "換新 token 之後" } }
            yu.repo.postMood("happy", "換新 token 之後")
            eventually("換新 token 後的自動更新") { zhe.repo.state.value.moods.any { it.note == "換新 token 之後" } }

            // 改密碼：先用舊密碼確認，再改
            apiCall { yu.supabase.updatePassword(yu.session.current!!.accessToken, "secret2") }
            failsWith("invalid_login") { anon().signIn(yu.email, "secret1") }
            assertEquals(yu.userId, apiCall { anon().signIn(yu.email, "secret2") }.userId)

            // 解除配對
            yu.repo.unpair()
            eventually("解除後看不到對方") { !zhe.repo.state.value.paired && zhe.repo.state.value.partnerStatus.device == null }
            assertNotNull(zhe.repo.state.value.me)
            zhe.realtime.stop(); yu.realtime.stop()
            // 登出後 refresh token 失效：換新失敗就要請使用者重新登入
            apiCall { zhe.supabase.signOut(zhe.session.current!!.accessToken) }
            var expired = false
            zhe.session.onExpired = { expired = true }
            assertNull(zhe.session.refreshBlocking(null))
            assertTrue("登出後應該要重新登入", expired)
        }
    }
}
