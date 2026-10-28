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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import tw.xiaoliangkou.app.core.PaymentParser
import tw.xiaoliangkou.app.data.Api
import tw.xiaoliangkou.app.data.AppException
import tw.xiaoliangkou.app.data.MediaItem
import tw.xiaoliangkou.app.data.ProfileInfo
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

    /** 有沒有丟出錯誤就好（錯誤代碼交給資料庫決定） */
    private suspend fun fails(what: String, block: suspend () -> Unit) {
        try { apiCall { block() } } catch (e: AppException) { return }
        throw AssertionError("應該要失敗：$what")
    }

    /** 通知的 data、歷史紀錄的 payload 裡的貼圖 id（沒附貼圖時資料庫給 null） */
    private fun JsonObject?.sticker() = (this?.get("sticker") as? JsonPrimitive)?.contentOrNull

    /** 上傳的檔案：之後解除配對時再檢查一次權限 */
    private class V2Files(val shared: MediaItem, val sharedBytes: ByteArray, val secret: MediaItem, val secretBytes: ByteArray)

    /** 個人資料、只有自己看得到的備註、日曆紀錄、備忘錄、照片與語音 */
    private suspend fun v2Features(zhe: Client, yu: Client): V2Files {
        val health = apiCall { anon().api.health() }
        assertEquals(RemoteRepository.TEMP_SCHEMA, (health["schema"] as JsonPrimitive).content.toInt())
        zhe.repo.refreshAll()
        assertFalse(zhe.repo.state.value.schemaOutdated)
        assertFalse(zhe.repo.state.value.deviceInfoOutdated)
        assertFalse(zhe.repo.state.value.stickersOutdated)
        val couple = zhe.repo.state.value.couple!!.id

        // 個人資料：對方看得到（app_me 的 partner.info），生日一起存
        val info = ProfileInfo.build(mapOf(ProfileInfo.HEIGHT to "176", ProfileInfo.BLOOD_TYPE to "a", ProfileInfo.LIKES to "拉麵", ProfileInfo.PHONE to ""))
        zhe.repo.updateProfileInfo("阿哲", "1998-03-21", info)
        assertEquals(176.0, ProfileInfo.number(zhe.repo.state.value.me!!.info, ProfileInfo.HEIGHT)!!, 0.0)
        assertEquals("1998-03-21", zhe.repo.state.value.me!!.birthday)
        assertNull(ProfileInfo.text(zhe.repo.state.value.me!!.info, ProfileInfo.PHONE))
        eventually("對方看到個人資料") { yu.repo.state.value.partner?.info?.let { ProfileInfo.text(it, ProfileInfo.BLOOD_TYPE) } == "A" }
        assertEquals("拉麵", ProfileInfo.text(yu.repo.state.value.partner!!.info, ProfileInfo.LIKES))
        assertEquals("1998-03-21", yu.repo.state.value.partner!!.birthday)
        failsWith("invalid_input") { apiCall { zhe.api.updateProfile(buildJsonObject { put("info", JsonPrimitive("不是物件")) }) } }

        // 只有自己看得到的備註：對方完全查不到，也改不了、刪不掉
        zhe.repo.saveNote(null, "她喜歡半糖少冰")
        val note = zhe.repo.state.value.notes.single()
        assertEquals(yu.userId, note.aboutUser)
        zhe.repo.saveNote(note.id, "她喜歡半糖少冰、不要珍珠")
        assertEquals("她喜歡半糖少冰、不要珍珠", zhe.repo.state.value.notes.single().body)
        yu.repo.refreshAll()
        assertTrue(yu.repo.state.value.notes.isEmpty())
        failsWith("not_found") { yu.api.saveNote(buildJsonObject { put("id", note.id); put("body", "偷改") }) }
        runCatching { apiCall { yu.api.deleteNote(note.id) } }
        zhe.repo.refreshAll()
        assertEquals("她喜歡半糖少冰、不要珍珠", zhe.repo.state.value.notes.single().body)
        zhe.repo.saveNote(null, "生日想要珍珠耳環")
        assertEquals("生日想要珍珠耳環", zhe.repo.state.value.notes.first().body) // 新的在前
        zhe.repo.deleteNote(note.id)
        assertEquals(1, zhe.repo.state.value.notes.size)

        // 照片、語音：c/<配對>/… 兩個人都讀得到；u/<我>/… 只有自己
        val photoBytes = ByteArray(2048) { (it * 31).toByte() }
        val photo = zhe.repo.uploadMedia(photoBytes, "image/jpeg", MediaItem.PHOTO, shared = true, width = 4, height = 3)
        assertTrue(photo.path, photo.path.startsWith("c/$couple/"))
        assertArrayEquals(photoBytes, zhe.repo.downloadMedia(photo.path))
        assertArrayEquals(photoBytes, yu.repo.downloadMedia(photo.path))
        val voice = yu.repo.uploadMedia(byteArrayOf(0, 0, 0, 24, 102, 116, 121, 112), "audio/mp4", MediaItem.VOICE, shared = true, durationMs = 1200)
        assertTrue(voice.path.endsWith(".m4a"))
        val secretBytes = byteArrayOf(7, 7, 7)
        val secret = zhe.repo.uploadMedia(secretBytes, "image/png", MediaItem.PHOTO, shared = false)
        assertTrue(secret.path, secret.path.startsWith("u/${zhe.userId}/"))
        assertArrayEquals(secretBytes, zhe.repo.downloadMedia(secret.path))
        fails("對方讀我的私人檔案") { yu.repo.downloadMedia(secret.path) }
        fails("上傳到對方的私人資料夾") { yu.api.uploadMedia("u/${zhe.userId}/${UUID.randomUUID()}.jpg", "image/jpeg", photoBytes) }
        fails("上傳到別的配對") { zhe.api.uploadMedia("c/${UUID.randomUUID()}/${UUID.randomUUID()}.jpg", "image/jpeg", photoBytes) }
        failsWith("invalid_media") { zhe.api.uploadMedia("c/$couple/${UUID.randomUUID()}.txt", "text/plain", photoBytes) }

        // 日曆：記錄那天的生活，兩個人都看得到，對方會收到通知
        val today = LocalDate.now()
        zhe.repo.saveDayEntry(null, today, "一起吃了火鍋 🍲", listOf(photo))
        val entry = zhe.repo.state.value.dayEntriesOn(today).single()
        assertEquals(listOf(photo), entry.media)
        assertEquals(zhe.userId, entry.userId)
        eventually("對方看到日曆紀錄") { yu.repo.state.value.dayEntriesOn(today).any { it.id == entry.id } }
        eventually("日曆紀錄通知") { yu.notes.any { it.kind == "diary" } }
        yu.repo.saveDayEntry(null, today, "", listOf(voice))
        eventually("自己看到對方的語音") { zhe.repo.state.value.dayEntriesOn(today).size == 2 }
        assertEquals(listOf(voice), zhe.repo.state.value.dayEntriesOn(today).first { it.userId == yu.userId }.media)
        // media 不能指到別的配對或私人資料夾（資料庫擋；直接呼叫 RPC，不經過 App 搬檔案）；不能改、刪對方的
        suspend fun saveRaw(media: List<MediaItem>) = zhe.api.saveDayEntry(buildJsonObject {
            put("day", today.toString()); put("body", "偷看"); put("media", Api.mediaJson(media))
        })
        failsWith("invalid_media") { saveRaw(listOf(MediaItem("c/${UUID.randomUUID()}/x.jpg", MediaItem.PHOTO))) }
        failsWith("invalid_media") { saveRaw(listOf(secret)) }
        // 從 App 存：私人檔案先複製到共用的地方，對方讀得到；別的配對的檔案讀不到，直接拿掉
        zhe.repo.saveDayEntry(null, today, "私人的", listOf(secret, MediaItem("c/${UUID.randomUUID()}/x.jpg", MediaItem.PHOTO)))
        val copied = zhe.repo.state.value.dayEntriesOn(today).single { it.body == "私人的" }
        assertTrue(copied.media.single().path, copied.media.single().path.startsWith("c/$couple/"))
        assertArrayEquals(secretBytes, yu.repo.downloadMedia(copied.media.single().path))
        zhe.repo.deleteDayEntry(copied.id)
        eventually("刪掉複製的紀錄") { zhe.repo.state.value.dayEntriesOn(today).size == 2 && yu.repo.state.value.dayEntries.none { it.id == copied.id } }
        fails("改對方的紀錄") { yu.repo.saveDayEntry(entry.id, today, "改掉", emptyList()) }
        runCatching { apiCall { yu.api.deleteDayEntry(entry.id) } }
        zhe.repo.loadDayEntries(today.minusDays(1), today.plusDays(1))
        assertEquals("一起吃了火鍋 🍲", zhe.repo.state.value.dayEntries.single { it.id == entry.id }.body)
        // 換到很久以前的月份：那段期間沒有紀錄，目前的紀錄還在
        zhe.repo.loadDayEntries(today.minusMonths(8).withDayOfMonth(1), today.minusMonths(7))
        assertEquals(2, zhe.repo.state.value.dayEntriesOn(today).size)
        zhe.repo.saveDayEntry(entry.id, today.minusDays(1), "其實是昨天", listOf(photo))
        eventually("對方看到改過的日期") { yu.repo.state.value.dayEntries.any { it.id == entry.id && it.day == today.minusDays(1).toString() } }
        zhe.repo.deleteDayEntry(entry.id)
        eventually("對方看到刪除") { yu.repo.state.value.dayEntries.none { it.id == entry.id } }

        // 備忘錄：共用的兩個人都能改，只有建立的人能刪、能改共用設定
        zhe.repo.saveMemo(null, "週末採買清單", "雞蛋、牛奶", listOf(photo), shared = true, pinned = true, color = "yellow")
        val memo = zhe.repo.state.value.memos.single()
        assertEquals(listOf(photo), memo.media)
        eventually("對方看到共用備忘錄") { yu.repo.state.value.memos.any { it.id == memo.id } }
        eventually("備忘錄通知") { yu.notes.any { it.kind == "memo" } }
        yu.repo.saveMemo(memo.id, memo.title, "雞蛋、牛奶、草莓", memo.media + voice, shared = true, pinned = true, color = "pink")
        eventually("自己看到對方改的內容") { zhe.repo.state.value.memo(memo.id)?.body == "雞蛋、牛奶、草莓" }
        assertEquals("pink", zhe.repo.state.value.memo(memo.id)!!.color)
        assertEquals(zhe.userId, zhe.repo.state.value.memo(memo.id)!!.userId)
        runCatching { yu.repo.saveMemo(memo.id, memo.title, "改成私人", memo.media, shared = false, pinned = true, color = "pink") }
        runCatching { apiCall { yu.api.saveMemo(buildJsonObject { put("id", memo.id); put("shared", false) }) } }
        runCatching { apiCall { yu.api.deleteMemo(memo.id) } }
        zhe.repo.refreshAll()
        yu.repo.refreshAll()
        assertTrue("對方不能改成私人", zhe.repo.state.value.memo(memo.id)!!.shared)
        assertTrue("對方不能刪", yu.repo.state.value.memo(memo.id) != null)

        // 私人備忘錄：對方看不到；照片要放在 u/<我>/
        zhe.repo.saveMemo(null, "生日驚喜", "珍珠耳環", listOf(secret), shared = false, pinned = false, color = "lavender")
        val surprise = zhe.repo.state.value.memos.first { it.title == "生日驚喜" }
        assertFalse(surprise.shared)
        yu.repo.refreshAll()
        assertTrue(yu.repo.state.value.memos.none { it.id == surprise.id })
        failsWith("invalid_media") { zhe.api.saveMemo(buildJsonObject {
            put("title", "x"); put("shared", false); put("media", Api.mediaJson(listOf(photo)))
        }) }
        failsWith("invalid_media") { zhe.api.saveMemo(buildJsonObject {
            put("title", "x"); put("shared", true); put("media", Api.mediaJson(listOf(secret)))
        }) }
        // 改成共用：照片自動搬到共用的地方，對方就看得到、讀得到
        zhe.repo.saveMemo(surprise.id, surprise.title, surprise.body, surprise.media, shared = true, pinned = false, color = "lavender")
        val moved = zhe.repo.state.value.memo(surprise.id)!!
        assertTrue(moved.shared)
        assertTrue(moved.media.single().path.startsWith("c/$couple/"))
        eventually("對方看到改成共用的備忘錄") { yu.repo.state.value.memo(surprise.id) != null }
        assertArrayEquals(secretBytes, yu.repo.downloadMedia(moved.media.single().path))
        zhe.repo.deleteMemo(surprise.id)
        eventually("對方看到刪除的備忘錄") { yu.repo.state.value.memo(surprise.id) == null }
        // 改成只有自己：照片搬到 u/<我>/，共用資料夾裡的原始檔刪掉，對方就讀不到了
        val draft = zhe.repo.uploadMedia(photoBytes, "image/jpeg", MediaItem.PHOTO, shared = true)
        assertArrayEquals(photoBytes, yu.repo.downloadMedia(draft.path))
        zhe.repo.saveMemo(null, "偷偷準備", "驚喜派對", listOf(draft), shared = false, pinned = false, color = "peach")
        val hidden = zhe.repo.state.value.memos.first { it.title == "偷偷準備" }
        assertFalse(hidden.shared)
        assertTrue(hidden.media.single().path, hidden.media.single().path.startsWith("u/${zhe.userId}/"))
        assertArrayEquals(photoBytes, zhe.repo.downloadMedia(hidden.media.single().path))
        fails("對方讀改成私人前的照片") { yu.repo.downloadMedia(draft.path) }
        zhe.repo.deleteMemo(hidden.id)
        assertTrue(zhe.repo.state.value.memo(memo.id)!!.pinned)
        // 上面的 secret 改成共用時已經搬走、原始檔刪掉了：解除配對後要檢查的私人檔案，用一張還放在私人備忘錄裡的
        val kept = zhe.repo.uploadMedia(secretBytes, "image/png", MediaItem.PHOTO, shared = false)
        zhe.repo.saveMemo(null, "自己留著", "", listOf(kept), shared = false, pinned = false, color = "mint")
        return V2Files(photo, photoBytes, kept, secretBytes)
    }

    /** 解除配對後：原本共用的檔案對方讀不到了，自己的私人檔案還在；沒配對不能寫日曆，備忘錄只能私人 */
    private suspend fun afterUnpair(zhe: Client, yu: Client, files: V2Files) {
        eventually("解除後看不到小貓") { yu.repo.state.value.pet == null && zhe.repo.state.value.pet == null }
        fails("解除後讀原本共用的檔案") { yu.repo.downloadMedia(files.shared.path) }
        assertArrayEquals(files.secretBytes, zhe.repo.downloadMedia(files.secret.path))
        failsWith("not_paired") { yu.repo.uploadMedia(byteArrayOf(1), "image/jpeg", MediaItem.PHOTO, shared = true) }
        failsWith("not_paired") { apiCall { yu.api.saveDayEntry(buildJsonObject { put("day", LocalDate.now().toString()); put("body", "一個人") }) } }
        assertTrue(yu.repo.state.value.dayEntries.isEmpty())
        yu.repo.saveMemo(null, "自己的", "一個人的備忘錄", emptyList(), shared = true, pinned = false, color = "pink")
        assertFalse(yu.repo.state.value.memos.first { it.title == "自己的" }.shared)
        assertTrue("解除後看不到對方的備忘錄", yu.repo.state.value.memos.all { it.userId == yu.userId })
        zhe.repo.refreshAll()
        assertTrue(zhe.repo.state.value.memos.all { it.userId == zhe.userId })
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

            // 頭像：存在 profiles.avatar_url（照片約 30–40 KB），對方的畫面靠 Realtime 自動更新
            val photo = "data:image/jpeg;base64," + "A".repeat(40_000)
            zhe.repo.updateAvatar(photo)
            assertEquals(photo, zhe.repo.state.value.me?.avatarUrl)
            eventually("對方看到新的照片頭像") { yu.repo.state.value.partner?.avatarUrl == photo }
            zhe.repo.updateAvatar("emoji:🐻")
            eventually("對方看到表情頭像") { yu.repo.state.value.partner?.avatarUrl == "emoji:🐻" }
            zhe.repo.updateAvatar(null)
            assertNull(zhe.repo.state.value.me?.avatarUrl)
            assertEquals("阿哲", zhe.repo.state.value.me?.displayName)

            // 分享設定與手機狀態
            zhe.repo.updateSharing(buildJsonObject { put("location", true); put("device", true); put("expenses", true) })
            yu.repo.updateSharing(buildJsonObject { put("location", true); put("device", true); put("cycle", true) })
            eventually("分享變更通知") { yu.notes.any { it.kind == "sharing_changed" } }
            zhe.repo.reportDevice(buildJsonObject {
                put("batteryLevel", 55); put("isCharging", false); put("network", "wifi"); put("stepsToday", 3210); put("deviceModel", "Google Pixel 8")
                put("deviceName", "阿哲的 Pixel"); put("carrier", "中華電信"); put("batteryTemp", 41.26); put("thermal", 2)
            })
            zhe.repo.reportLocation(25.0330, 121.5654, 12f, 0f)
            eventually("對方畫面自動更新電量") { yu.repo.state.value.partnerStatus.device?.batteryLevel == 55 }
            assertEquals("手機溫度到小數點一位", 41.3, yu.repo.state.value.partnerStatus.device!!.batteryTemp!!, 0.0)
            assertEquals(2, yu.repo.state.value.partnerStatus.device!!.thermal)
            eventually("對方在線上") { yu.repo.state.value.partnerOnline }
            assertEquals(25.0330, yu.repo.state.value.partnerStatus.location!!.lat, 1e-6)
            yu.repo.state.value.partnerStatus.device!!.let {
                assertEquals("阿哲的 Pixel", it.deviceName)
                assertEquals("Google Pixel 8", it.deviceModel)
                assertEquals("中華電信", it.carrier)
            }
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

            // 0.2.3 多的心情和安撫：存得進去，通知用新的文字，「需要抱抱」的一樣問要不要安撫
            assertTrue(yu.repo.state.value.moreMoodsReady)
            yu.repo.postMood("lonely", "你出差好久")
            eventually("新的心情同步") { zhe.repo.state.value.moods.any { it.mood == "lonely" } }
            eventually("新的心情通知") { zhe.notes.any { it.title == "小雨 現在覺得寂寞" && it.body == "你出差好久。要不要安撫一下？" } }
            val night = async { yu.repo.banners.first { it.kind == "comfort" && it.title.contains("晚安") } }
            zhe.repo.sendComfort("night", null)
            assertEquals("阿哲 跟你說晚安，好夢", withTimeout(6000) { night.await() }.title)

            // GIPHY 的 API key：一對情侶一把，誰設定都可以，兩個人都讀得到；交往紀念日不能被動到
            val giphyKey = "xlkTestKey_$run"
            assertNull(zhe.repo.state.value.giphyKey)
            zhe.repo.setGiphyKey(giphyKey)
            assertEquals(giphyKey, zhe.repo.state.value.giphyKey)
            assertEquals(LocalDate.parse("2025-05-20"), zhe.repo.state.value.togetherSince)
            eventually("對方拿到 GIPHY key") { yu.repo.state.value.giphyKey == giphyKey }
            failsWith("invalid_input") { zhe.repo.setGiphyKey("壞 key") } // App 先擋
            failsWith("invalid_input") { zhe.api.setGiphyKey(buildJsonObject { put("key", "壞 key") }) } // 資料庫也擋
            assertEquals(giphyKey, apiCall { yu.api.me() }.couple?.giphyKey)
            yu.repo.setGiphyKey("  ") // 空白是清掉
            assertNull(yu.repo.state.value.giphyKey)
            eventually("對方看到 key 清掉了") { zhe.repo.state.value.giphyKey == null }
            yu.repo.setGiphyKey(giphyKey)
            eventually("對方又拿到 GIPHY key") { zhe.repo.state.value.giphyKey == giphyKey }

            // GIPHY 貼圖：心情、安撫可以附一張（只存 id），通知與歷史紀錄帶著 id；奇怪的 id App 和資料庫都擋
            val sticker = "3oriO0OEd9QIDdllqo"
            yu.repo.postMood("happy", null, sticker)
            assertEquals(sticker, yu.repo.state.value.moods.first { it.userId == yu.userId }.sticker)
            eventually("對方看到貼圖心情") { zhe.repo.state.value.moods.any { it.mood == "happy" && it.sticker == sticker } }
            eventually("貼圖心情通知") { zhe.notes.any { it.kind == "mood" && it.data.sticker() == sticker } }
            assertEquals("附上一張貼圖 ✨", zhe.notes.last { it.kind == "mood" && it.data.sticker() == sticker }.body)
            failsWith("invalid_input") { yu.repo.postMood("happy", null, "../x") }
            failsWith("invalid_input") { yu.api.postMood(buildJsonObject { put("mood", "happy"); put("sticker", "../x") }) }
            failsWith("invalid_input") { zhe.api.postComfort(buildJsonObject { put("kind", "hug"); put("sticker", "a b") }) }
            val stickerBanner = async { yu.repo.banners.first { it.kind == "comfort" && it.body == "傳了一張貼圖給你，點開看看 👀" } }
            zhe.repo.sendComfort("hug", null, sticker)
            assertEquals(sticker, zhe.repo.state.value.comforts.first { it.senderId == zhe.userId }.sticker)
            withTimeout(6000) { stickerBanner.await() }
            eventually("收到貼圖安撫") { yu.repo.state.value.comforts.any { it.receiverId == yu.userId && it.sticker == sticker } }
            val got = yu.repo.state.value.comforts.first { it.receiverId == yu.userId && it.sticker == sticker }
            assertNull(got.readAt)
            val gotNote = yu.notes.last { it.kind == "comfort" && it.data.sticker() == sticker }
            assertEquals(got.id, (gotNote.data?.get("comfort_id") as? JsonPrimitive)?.longOrNull)

            // 已讀：只有收到的人標得了（送的人呼叫不會改到任何東西）；送的人那邊跟著更新
            apiCall { zhe.api.readComfort(got.id) }
            assertNull(apiCall { yu.api.comforts() }.first { it.id == got.id }.readAt)
            yu.repo.readComfort(got.id)
            assertNotNull(yu.repo.state.value.comforts.first { it.id == got.id }.readAt)
            eventually("送的人看到已讀") { zhe.repo.state.value.comforts.first { it.id == got.id }.readAt != null }
            // 首頁的「回抱」：抱回去，不附貼圖也不寫話時，對方收到原本的文字
            val hugBack = async { zhe.repo.banners.first { it.kind == "comfort" && it.body == "點開回應一下吧" } }
            yu.repo.sendComfort("hug", null)
            withTimeout(6000) { hugBack.await() }
            assertNull(zhe.notes.last { it.kind == "comfort" }.data.sticker())
            // 打開歷史紀錄：收到的全部標成已讀（「晚上請你吃好料」那則也是），自己送出去的不算。
            // schema 4 以後一次標完（app_read_comfort 的 {"all": true}）
            assertEquals(RemoteRepository.TEMP_SCHEMA, yu.repo.state.value.dbSchema)
            assertTrue(yu.repo.state.value.comforts.any { it.receiverId == yu.userId && it.readAt == null })
            yu.repo.markComfortsRead()
            assertTrue(yu.repo.state.value.comforts.none { it.receiverId == yu.userId && it.readAt == null })
            assertTrue(apiCall { yu.api.comforts() }.none { it.receiverId == yu.userId && it.readAt == null })
            assertNull(apiCall { zhe.api.comforts() }.first { it.senderId == yu.userId }.readAt)
            eventually("送的人看到全部已讀") { zhe.repo.state.value.comforts.filter { it.receiverId == yu.userId }.all { it.readAt != null } }
            // 阿哲的 {"all": true} 標的是他自己收到的（剛剛的回抱）
            apiCall { zhe.api.readAllComforts() }
            assertNotNull(apiCall { zhe.api.comforts() }.first { it.senderId == yu.userId }.readAt)
            eventually("歷史紀錄帶著貼圖") {
                zhe.repo.state.value.history.let { h ->
                    h.any { it.kind == "mood" && it.payload.sticker() == sticker } && h.any { it.kind == "comfort" && it.payload.sticker() == sticker }
                }
            }
            // key 不會跟著歷史紀錄出去（通知的部分最後再檢查一次全部）
            listOf(zhe, yu).forEach { c -> assertTrue(apiCall { c.api.history(limit = 200) }.none { giphyKey in it.payload.toString() }) }

            // 一起養的小貓：阿哲領養，小雨即時看到、餵牠；吃飽了再餵被擋下來
            eventually("還沒有小貓") { zhe.repo.state.value.pet != null && zhe.repo.state.value.pet!!.pet == null }
            zhe.repo.adoptPet("湯圓", "white")
            eventually("對方看到小貓") { yu.repo.state.value.pet?.pet?.name == "湯圓" }
            eventually("領養通知") { yu.notes.any { it.kind == "pet" && it.title == "阿哲 領養了一隻小貓 🐱" } }
            assertEquals("80 分不算餓，沒有成長值", 0, yu.repo.petAction("feed", "can").xp)
            eventually("自己看到對方餵了") { zhe.repo.state.value.pet?.today?.feeds == 1 && zhe.repo.state.value.pet!!.events.first().userId == yu.userId }
            eventually("餵食通知") { zhe.notes.any { it.kind == "pet" && it.title == "小雨 餵 湯圓 吃了罐罐 🥫" } }
            failsWith("pet_full") { zhe.repo.petAction("feed", "kibble") }
            repeat(3) { zhe.repo.petAction("pet", null) }
            assertEquals(6, zhe.repo.state.value.pet!!.pet!!.xp)
            eventually("對方看到成長值") { yu.repo.state.value.pet?.pet?.xp == 6 && yu.repo.state.value.pet?.today?.pets == 3 }

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
            // LINE錢包的付款訊息：同一個去重 key 只記一筆；記下的 id 之後可以補上店名
            val auto = PaymentParser.parse("付款\nNT$ 135\n付款完成。\n商店名稱\n星巴克 信義新天地門市\n付款後餘額\nNT$ 111")!!
            assertEquals("星巴克 信義新天地門市", auto.merchant)
            val autoId = zhe.repo.addAutoExpense(auto, "k1")
            assertNotNull(autoId)
            assertNull("同一筆不重記", zhe.repo.addAutoExpense(auto, "k1"))
            zhe.repo.saveExpense(autoId, buildJsonObject { put("merchant", "星巴克"); put("category", "drink") })
            assertEquals("星巴克", zhe.repo.state.value.myExpenses.first { it.id == autoId }.merchant)
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

            // 2026-10 新功能（upgrade-v2.sql）
            val v2 = v2Features(zhe, yu)

            // 解除配對
            yu.repo.unpair()
            eventually("解除後看不到對方") { !zhe.repo.state.value.paired && zhe.repo.state.value.partnerStatus.device == null }
            // 被解除的那一邊只收到 couple_members 變了：日曆（對方的語音紀錄）、寫給對方的備註也要跟著拿掉，不用等重新整理
            eventually("解除後日曆、備註跟著拿掉") {
                zhe.repo.state.value.dayEntries.isEmpty() && zhe.repo.state.value.notes.none { it.aboutUser == yu.userId }
            }
            assertNotNull(zhe.repo.state.value.me)
            afterUnpair(zhe, yu, v2)
            // GIPHY key 跟著配對：解除後兩邊都讀不到，也不能再設定
            assertNull(zhe.repo.state.value.giphyKey)
            assertNull(apiCall { yu.api.me() }.couple)
            failsWith("not_paired") { yu.repo.setGiphyKey(giphyKey) }
            // 從頭到尾，key 沒有出現在任何一則通知裡
            listOf(zhe, yu).forEach { c ->
                assertTrue(synchronized(c.notes) { c.notes.toList() }.none { giphyKey in "${it.title}${it.body}${it.data}" })
            }
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
