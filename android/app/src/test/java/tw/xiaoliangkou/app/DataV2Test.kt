package tw.xiaoliangkou.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tw.xiaoliangkou.app.data.Api
import tw.xiaoliangkou.app.data.AppException
import tw.xiaoliangkou.app.data.CoupleRepository
import tw.xiaoliangkou.app.data.DayEntry
import tw.xiaoliangkou.app.data.DemoMedia
import tw.xiaoliangkou.app.data.DemoRepository
import tw.xiaoliangkou.app.data.Errors
import tw.xiaoliangkou.app.data.Limits
import tw.xiaoliangkou.app.data.MediaCache
import tw.xiaoliangkou.app.data.MediaItem
import tw.xiaoliangkou.app.data.MediaPaths
import tw.xiaoliangkou.app.data.Memo
import tw.xiaoliangkou.app.data.Note
import tw.xiaoliangkou.app.data.PhotoTools
import tw.xiaoliangkou.app.data.Profile
import tw.xiaoliangkou.app.data.ProfileInfo
import tw.xiaoliangkou.app.ui.components.formatDuration
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

/** 2026-10 新功能的資料層：個人資料、備註、日曆紀錄、備忘錄、照片與語音（示範模式與快取） */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DataV2Test {
    private val scope = CoroutineScope(SupervisorJob())
    private val demo = DemoRepository(scope).also { it.running = false }
    private val context get() = RuntimeEnvironment.getApplication()
    private val today = LocalDate.now()

    @After fun tearDown() = scope.cancel()

    private suspend fun failsWith(code: String, block: suspend () -> Unit): AppException {
        try { block() } catch (e: AppException) { assertEquals(e.message, code, e.code); return e }
        throw AssertionError("應該要失敗：$code")
    }

    // ---------------------------------------------------------------- 個人資料
    @Test fun profileInfoBuildStripsEmptyAndKeepsNumbers() {
        val info = ProfileInfo.build(mapOf(
            ProfileInfo.HEIGHT to "160", ProfileInfo.WEIGHT to "48.5", ProfileInfo.PHONE to "  ",
            ProfileInfo.BLOOD_TYPE to "o", ProfileInfo.MBTI to "enfp", ProfileInfo.LIKES to " 珍奶 ", ProfileInfo.SHOE_SIZE to null,
        ))
        assertEquals(setOf("heightCm", "weightKg", "bloodType", "mbti", "likes"), info.keys)
        assertEquals(JsonPrimitive(160L), info["heightCm"])
        assertEquals(JsonPrimitive(48.5), info["weightKg"])
        assertEquals("O", ProfileInfo.text(info, ProfileInfo.BLOOD_TYPE))
        assertEquals("ENFP", ProfileInfo.text(info, ProfileInfo.MBTI))
        assertEquals("珍奶", ProfileInfo.text(info, ProfileInfo.LIKES))
        assertNull(ProfileInfo.text(info, ProfileInfo.PHONE))
        assertEquals("160", ProfileInfo.text(info, ProfileInfo.HEIGHT))
        assertEquals(160.0, ProfileInfo.number(info, ProfileInfo.HEIGHT)!!, 0.0)
        assertEquals("48.5 kg", ProfileInfo.display(info, ProfileInfo.field(ProfileInfo.WEIGHT)!!))
        assertEquals(listOf("heightCm", "weightKg", "bloodType", "mbti", "likes"), ProfileInfo.filled(info).map { it.first.key })

        // 看不懂的數字、0 都不存；清空欄位就移除；新版 App 才有的欄位不會被洗掉
        val base = JsonObject(info + ("futureKey" to JsonPrimitive("留著")) + ("intro" to JsonPrimitive(" ")))
        val next = ProfileInfo.build(mapOf(ProfileInfo.HEIGHT to "abc", ProfileInfo.WEIGHT to "0", ProfileInfo.LIKES to "", ProfileInfo.JOB to "設計師"), base)
        assertEquals(setOf("bloodType", "mbti", "futureKey", "job"), next.keys)
        assertEquals(JsonPrimitive(165.5), ProfileInfo.build(mapOf(ProfileInfo.HEIGHT to "165,5"))["heightCm"])

        // 舊資料把數字存成字串也讀得到；值的長度有上限
        val old = JsonObject(mapOf("heightCm" to JsonPrimitive("165"), "intro" to JsonPrimitive(42)))
        assertEquals(165.0, ProfileInfo.number(old, ProfileInfo.HEIGHT)!!, 0.0)
        assertEquals("42", ProfileInfo.text(old, ProfileInfo.INTRO))
        assertEquals(200, ProfileInfo.text(ProfileInfo.build(mapOf(ProfileInfo.INTRO to "好".repeat(500))), ProfileInfo.INTRO)!!.length)
        assertEquals("", ProfileInfo.values(info)[ProfileInfo.PHONE])
        assertEquals("160", ProfileInfo.values(info)[ProfileInfo.HEIGHT])
    }

    @Test fun profileInfoFieldsCoverEveryKey() {
        assertEquals(
            listOf("heightCm", "weightKg", "phone", "bloodType", "mbti", "job", "hobbies", "likes", "dislikes", "allergies", "clothesSize", "shoeSize", "intro"),
            ProfileInfo.fields.map { it.key },
        )
        assertEquals(listOf("身高", "體重"), ProfileInfo.fields.filter { it.numeric }.map { it.label })
        assertEquals("cm", ProfileInfo.field(ProfileInfo.HEIGHT)!!.unit)
        assertTrue(ProfileInfo.fields.all { it.emoji.isNotBlank() && it.label.isNotBlank() })
        assertEquals(listOf("A", "B", "O", "AB"), ProfileInfo.bloodTypes)
        // 填滿每一欄也不會超過資料庫的 4000 bytes
        val full = ProfileInfo.build(ProfileInfo.fields.associate { it.key to if (it.numeric) "999" else "好".repeat(500) })
        assertTrue(full.toString().toByteArray().size <= Limits.INFO_BYTES)
    }

    @Test fun ageCountsBirthdays() {
        val b = LocalDate.of(1999, 11, 21)
        assertEquals(26, ProfileInfo.age(b, LocalDate.of(2026, 11, 20)))
        assertEquals(27, ProfileInfo.age(b, LocalDate.of(2026, 11, 21)))
        assertEquals(0, ProfileInfo.age(b, LocalDate.of(1999, 11, 21)))
        assertEquals(0, ProfileInfo.age(LocalDate.of(2030, 1, 1), LocalDate.of(2026, 1, 1)))
        // 2/29 出生：平年 3/1 才加一歲
        val leap = LocalDate.of(2000, 2, 29)
        assertEquals(26, ProfileInfo.age(leap, LocalDate.of(2027, 2, 28)))
        assertEquals(27, ProfileInfo.age(leap, LocalDate.of(2027, 3, 1)))
        assertEquals(28, ProfileInfo.age(leap, LocalDate.of(2028, 2, 29)))
    }

    // ---------------------------------------------------------------- 輸入檢查、錯誤訊息
    @Test fun limitsGiveFriendlyErrors() = runBlocking<Unit> {
        val photo = MediaItem("c/1/a.jpg", MediaItem.PHOTO)
        failsWith("invalid_input") { Limits.note(" ") }
        failsWith("invalid_input") { Limits.note("好".repeat(Limits.NOTE + 1)) }
        Limits.note("她喜歡半糖少冰")
        failsWith("invalid_input") { Limits.dayEntry("", emptyList()) }
        Limits.dayEntry("", listOf(photo))
        failsWith("invalid_input") { Limits.dayEntry("太多了", List(13) { photo }) }
        failsWith("invalid_input") { Limits.memo("", " ", emptyList(), "pink") }
        failsWith("invalid_input") { Limits.memo("標".repeat(61), "", emptyList(), "pink") }
        failsWith("invalid_input") { Limits.memo("採買", "", emptyList(), "red") }
        Memo.COLORS.forEach { Limits.memo("採買", "", emptyList(), it) }
        failsWith("invalid_input") { Limits.profile(" ", null, JsonObject(emptyMap())) }
        failsWith("invalid_input") { Limits.profile("阿哲", "2026-13-01", JsonObject(emptyMap())) }
        Limits.profile("阿哲", "1998-05-20", JsonObject(emptyMap()))
        failsWith("invalid_media") { Limits.file(ByteArray(0)) }
        failsWith("too_large") { Limits.file(ByteArray(Limits.FILE_BYTES + 1)) }
    }

    @Test fun missingNewFunctionsMeanUpgradeNeeded() {
        fun pg(code: String, message: String, status: Int = 404) = Errors.from(status, """{"code":"$code","details":null,"hint":null,"message":"$message"}""")
        pg("PGRST202", "Could not find the function public.app_notes without parameters in the schema cache").let {
            assertEquals(Errors.SCHEMA_OUTDATED_CODE, it.code)
            assertEquals(Errors.SCHEMA_OUTDATED, it.message)
            assertTrue(it.message!!.contains("upgrade-v2.sql"))
        }
        assertEquals(Errors.SCHEMA_OUTDATED_CODE, pg("PGRST202", "Could not find the function public.app_save_memo(color, title) in the schema cache").code)
        // 舊功能的函式找不到：setup.sql 根本還沒跑
        assertEquals("not_setup", pg("PGRST202", "Could not find the function public.app_me without parameters in the schema cache").code)
        assertEquals("照片或語音的檔案不正確，請重新加入一次", pg("P0001", "invalid_media", 400).message)
    }

    @Test fun mapsStorageErrors() {
        fun st(status: Int, body: String) = Errors.storage(status, body)
        assertEquals(Errors.SCHEMA_OUTDATED_CODE, st(400, """{"statusCode":"404","error":"Bucket not found","message":"Bucket not found"}""").code)
        st(400, """{"statusCode":"404","error":"not_found","message":"Object not found"}""").let {
            assertEquals("not_found", it.code); assertEquals("找不到檔案，可能已經被刪除了", it.message)
        }
        assertEquals("forbidden", st(400, """{"statusCode":"403","error":"Unauthorized","message":"new row violates row-level security policy"}""").code)
        assertEquals("too_large", st(413, """{"statusCode":"413","error":"Payload too large","message":"The object exceeded the maximum allowed size"}""").code)
        assertEquals("invalid_media", st(415, """{"statusCode":"415","error":"invalid_mime_type","message":"mime type text/plain is not supported"}""").code)
        assertEquals("jwt_expired", st(400, """{"statusCode":"400","error":"InvalidJWT","message":"jwt expired"}""").code)
        assertEquals("server_error", st(502, "<html>Bad gateway</html>").code)
    }

    @Test fun mediaPathsStayInsideTheBucket() = runBlocking<Unit> {
        assertTrue(Regex("""^c/couple-1/[0-9a-f-]{36}\.jpg$""").matches(MediaPaths.forCouple("couple-1", "image/jpeg")))
        assertTrue(Regex("""^u/user-1/[0-9a-f-]{36}\.m4a$""").matches(MediaPaths.forUser("user-1", "audio/mp4")))
        assertTrue(MediaPaths.under("demo/", "audio/wav").endsWith(".wav"))
        assertTrue(MediaPaths.isSafe("c/123e4567-e89b-12d3-a456-426614174000/abc.jpg"))
        listOf("../x.jpg", "c/../x.jpg", "c//x.jpg", "/c/x.jpg", "c/x/y.jpg?download", "https://evil.example/x.jpg", "c/x/..", "c/x/ y.jpg", "c").forEach {
            assertFalse(it, MediaPaths.isSafe(it))
        }
        failsWith("invalid_media") { MediaPaths.checked("c/../../auth/v1/user") }
        assertEquals("image/jpeg", MediaPaths.mimeOf("c/1/a.JPG"))
        assertEquals("audio/mp4", MediaPaths.mimeOf("c/1/a.m4a"))
    }

    // ---------------------------------------------------------------- 跟資料庫 RPC 的 JSON 對應
    @Test fun modelsMatchRpcJson() {
        val note = Api.json.decodeFromString(Note.serializer(), """{"id":"n1","aboutUser":"u2","body":"半糖少冰","createdAt":"2026-10-15T02:16:21.5+00:00","updatedAt":null}""")
        assertEquals("u2", note.aboutUser)
        val entry = Api.json.decodeFromString(DayEntry.serializer(), """{"id":"d1","userId":"u1","day":"2026-10-15","body":"","media":[{"path":"c/c1/a.jpg","kind":"photo","mime":"image/jpeg","width":1600,"height":1200},{"path":"c/c1/b.m4a","kind":"voice","durationMs":12000}],"createdAt":"2026-10-15T02:16:21+00:00","updatedAt":"2026-10-15T02:16:21+00:00"}""")
        assertEquals(LocalDate.of(2026, 10, 15), entry.date)
        assertEquals(listOf(true, false), entry.media.map { it.isPhoto })
        assertEquals(12000L, entry.media[1].durationMs)
        val memo = Api.json.decodeFromString(Memo.serializer(), """{"id":"m1","userId":"u1","title":"採買","body":"","media":[],"shared":false,"pinned":true,"color":"mint","createdAt":"2026-10-15T02:16:21+00:00","updatedAt":"2026-10-16T02:16:21+00:00"}""")
        assertFalse(memo.shared); assertTrue(memo.pinned); assertEquals("mint", memo.color)
        val p = Api.json.decodeFromString(Profile.serializer(), """{"id":"u1","displayName":"小雨","birthday":"1999-11-21","info":{"heightCm":160,"bloodType":"O"}}""")
        assertEquals("O", ProfileInfo.text(p.info, ProfileInfo.BLOOD_TYPE))
        // 舊的資料庫沒有 info
        assertEquals(JsonObject(emptyMap()), Api.json.decodeFromString(Profile.serializer(), """{"id":"u1","displayName":"小雨"}""").info)
        // 存進資料庫的 media：沒有的欄位不送
        assertEquals(
            """[{"path":"c/1/a.jpg","kind":"photo","mime":"image/jpeg","width":4,"height":3},{"path":"c/1/b.m4a","kind":"voice","durationMs":1200}]""",
            Api.mediaJson(listOf(MediaItem("c/1/a.jpg", "photo", "image/jpeg", 4, 3), MediaItem("c/1/b.m4a", "voice", durationMs = 1200))).toString(),
        )
        // 排序：釘選的在前，再來是最近改的；日曆依日期、同一天先寫的在前
        val memos = listOf(memo.copy(id = "a", pinned = false, updatedAt = "2026-10-17T00:00:00Z"), memo.copy(id = "b"), memo.copy(id = "c", pinned = false))
        assertEquals(listOf("b", "a", "c"), memos.sortedWith(Memo.ORDER).map { it.id })
        val days = listOf(entry.copy(id = "x", day = "2026-10-16"), entry.copy(id = "y", createdAt = "2026-10-15T09:00:00Z"), entry)
        assertEquals(listOf("d1", "y", "x"), days.sortedWith(DayEntry.ORDER).map { it.id })
    }

    // ---------------------------------------------------------------- 示範模式
    @Test fun demoHasCuteSampleData() {
        val s = demo.state.value
        val her = s.partner!!.info
        assertEquals("160", ProfileInfo.text(her, ProfileInfo.HEIGHT))
        assertEquals("O", ProfileInfo.text(her, ProfileInfo.BLOOD_TYPE))
        assertTrue(ProfileInfo.text(her, ProfileInfo.LIKES)!!.contains("珍珠奶茶"))
        assertEquals(s.anniversaries.first { it.title == "小雨生日" }.date, s.partner!!.birthday)
        assertNotNull(s.me!!.birthDate)
        assertTrue(ProfileInfo.filled(s.me!!.info).size >= 8)

        assertEquals(2, s.notes.size)
        assertTrue(s.notes.all { it.aboutUser == s.partner!!.id })
        assertTrue(s.notes[0].body.contains("半糖少冰"))
        assertTrue(s.notes[1].body.contains("耳環"))

        assertEquals(3, s.dayEntries.map { it.day }.distinct().size)
        assertTrue(s.dayEntriesOn(today).isNotEmpty())
        assertTrue(s.dayEntries.any { e -> e.media.any { it.isPhoto } })
        assertTrue(s.dayEntries.any { e -> e.media.any { it.isVoice } })
        assertEquals(s.dayEntries.sortedWith(DayEntry.ORDER), s.dayEntries)

        assertEquals(3, s.memos.size)
        assertEquals("週末採買清單 🛒", s.memos.first().title)
        assertEquals(listOf(s.memos.first()), s.pinnedMemos)
        assertTrue(s.memos.first().shared)
        assertTrue(s.memos.any { !it.shared && it.userId == s.myId })
        assertTrue(s.memos.any { it.shared && it.userId == s.partner!!.id })
        assertTrue(s.notifPrefs.keys.containsAll(listOf("diary", "memo")))
        assertFalse(s.schemaOutdated)
    }

    @Test fun demoProfileAndNotes() = runBlocking<Unit> {
        val info = ProfileInfo.build(mapOf(ProfileInfo.HEIGHT to "177", ProfileInfo.LIKES to "拉麵"))
        demo.updateProfileInfo("阿哲哲", "1998-05-20", info)
        demo.state.value.me!!.let { assertEquals("阿哲哲", it.displayName); assertEquals("1998-05-20", it.birthday); assertEquals(info, it.info) }
        demo.updateProfileInfo("阿哲", null, info)
        assertNull(demo.state.value.me!!.birthday)
        failsWith("invalid_input") { demo.updateProfileInfo("", null, info) }

        demo.saveNote(null, "她怕冷，冷氣房要帶外套")
        val n = demo.state.value.notes.first()
        assertEquals("她怕冷，冷氣房要帶外套", n.body)
        assertEquals(3, demo.state.value.notes.size)
        demo.saveNote(n.id, "她怕冷，記得帶外套 🧥")
        assertEquals("她怕冷，記得帶外套 🧥", demo.state.value.notes.first { it.id == n.id }.body)
        failsWith("not_found") { demo.saveNote("nope", "x") }
        failsWith("invalid_input") { demo.saveNote(null, "  ") }
        demo.deleteNote(n.id)
        assertEquals(2, demo.state.value.notes.size)
    }

    @Test fun demoDayEntries() = runBlocking<Unit> {
        val photo = demo.uploadMedia(byteArrayOf(1, 2, 3), "image/jpeg", MediaItem.PHOTO, shared = true, width = 4, height = 3)
        demo.saveDayEntry(null, today, "一起吃了火鍋 🍲", listOf(photo))
        val mine = demo.state.value.dayEntriesOn(today).last()
        assertEquals("一起吃了火鍋 🍲", mine.body)
        assertEquals(listOf(photo), mine.media)
        assertEquals(demo.state.value.myId, mine.userId)
        demo.saveDayEntry(mine.id, today.minusDays(1), "改到昨天", emptyList())
        assertEquals("改到昨天", demo.state.value.dayEntriesOn(today.minusDays(1)).first { it.id == mine.id }.body)
        val hers = demo.state.value.dayEntries.first { it.userId != demo.state.value.myId }
        failsWith("forbidden") { demo.saveDayEntry(hers.id, today, "偷改", emptyList()) }
        failsWith("forbidden") { demo.deleteDayEntry(hers.id) }
        failsWith("invalid_input") { demo.saveDayEntry(null, today, " ", emptyList()) }
        demo.loadDayEntries(today.minusMonths(3), today.minusMonths(2))
        demo.deleteDayEntry(mine.id)
        assertTrue(demo.state.value.dayEntries.none { it.id == mine.id })
        assertEquals(demo.state.value.dayEntries.sortedWith(DayEntry.ORDER), demo.state.value.dayEntries)
    }

    @Test fun demoMemos() = runBlocking<Unit> {
        val me = demo.state.value.myId
        demo.saveMemo(null, "秘密計畫", "生日驚喜", emptyList(), shared = false, pinned = true, color = "lavender")
        val secret = demo.state.value.memos.first()
        assertEquals("秘密計畫", secret.title)
        assertFalse(secret.shared)
        assertEquals(me, secret.userId)
        assertEquals(2, demo.state.value.pinnedMemos.size)

        // 對方共用的：可以改內容、釘選、顏色，不能改成私人、不能刪
        val hers = demo.state.value.memos.first { it.userId != me }
        demo.saveMemo(hers.id, hers.title, "京都、墾丁、九份、綠島", hers.media, shared = false, pinned = true, color = "sky")
        demo.state.value.memo(hers.id)!!.let {
            assertEquals("京都、墾丁、九份、綠島", it.body); assertTrue(it.shared); assertTrue(it.pinned); assertEquals("sky", it.color)
        }
        failsWith("forbidden") { demo.deleteMemo(hers.id) }
        failsWith("invalid_input") { demo.saveMemo(null, "", "", emptyList(), shared = true, pinned = false, color = "pink") }
        failsWith("not_found") { demo.saveMemo("nope", "x", "", emptyList(), shared = true, pinned = false, color = "pink") }

        // 自己的可以改成共用、刪掉
        demo.saveMemo(secret.id, secret.title, secret.body, secret.media, shared = true, pinned = false, color = "peach")
        assertTrue(demo.state.value.memo(secret.id)!!.shared)
        assertEquals(demo.state.value.memos.sortedWith(Memo.ORDER), demo.state.value.memos)
        demo.deleteMemo(secret.id)
        assertNull(demo.state.value.memo(secret.id))
    }

    @Test fun demoMediaWorksWithoutServer() = runBlocking<Unit> {
        val bytes = byteArrayOf(9, 8, 7, 6)
        val item = demo.uploadMedia(bytes, "audio/mp4", MediaItem.VOICE, shared = false, durationMs = 1200)
        assertTrue(item.path, Regex("""^demo/[0-9a-f-]{36}\.m4a$""").matches(item.path))
        assertEquals(MediaItem(item.path, "voice", "audio/mp4", durationMs = 1200), item)
        assertArrayEquals(bytes, demo.downloadMedia(item.path))
        failsWith("invalid_media") { demo.uploadMedia(ByteArray(0), "image/jpeg", MediaItem.PHOTO, true) }
        failsWith("not_found") { demo.downloadMedia("demo/nope.jpg") }

        // 示範照片是畫出來的 PNG，語音是 WAV
        val sample = demo.state.value.dayEntries.flatMap { it.media }.first { it.isPhoto }
        val png = demo.downloadMedia(sample.path)
        val bmp = BitmapFactory.decodeByteArray(png, 0, png.size)
        assertEquals(sample.width, bmp.width)
        assertEquals(sample.height, bmp.height)
        assertSame(png, demo.downloadMedia(sample.path))
        val voice = demo.state.value.dayEntries.flatMap { it.media }.first { it.isVoice }
        val wav = demo.downloadMedia(voice.path)
        assertEquals("RIFF", wav.copyOfRange(0, 4).decodeToString())
        assertEquals(44 + 16_000 * 2 * voice.durationMs!! / 1000, wav.size.toLong())
        demo.state.value.memos.flatMap { it.media }.forEach { assertTrue(demo.downloadMedia(it.path).isNotEmpty()) }
    }

    @Test fun demoPartnerWritesDiaryAndMemo() = runBlocking<Unit> {
        withTimeout(10_000) {
            val diary = async(start = CoroutineStart.UNDISPATCHED) { demo.banners.first { it.kind == "diary" } }
            demo.demoAction("diary")
            assertEquals("calendar", diary.await().route)
            val memo = async(start = CoroutineStart.UNDISPATCHED) { demo.banners.first { it.kind == "memo" } }
            demo.demoAction("memo")
            assertEquals("memos", memo.await().route)
        }
        val s = demo.state.value
        assertEquals(2, s.dayEntriesOn(today).size)
        assertEquals(4, s.memos.size)
    }

    // ---------------------------------------------------------------- 照片、語音的快取
    /** 示範資料，但數一下下載了幾次，也可以假裝沒網路 */
    private class CountingRepo(private val d: DemoRepository) : CoupleRepository by d {
        val downloads = AtomicInteger()
        @Volatile var offline = false
        override suspend fun downloadMedia(path: String): ByteArray {
            downloads.incrementAndGet()
            if (offline) throw AppException("連不到 Supabase，請確認網路", "offline")
            return d.downloadMedia(path)
        }
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test fun mediaCacheDownloadsOnceAndDecodesThumbnails() = runBlocking<Unit> {
        val repo = CountingRepo(demo)
        val cache = MediaCache(context) { repo }
        cache.clear()
        val path = DemoMedia.PICNIC.path

        val f = cache.file(path)!!
        assertEquals(File(File(context.cacheDir, "media"), sha1(path)), f)
        assertTrue(f.length() > 0)
        assertNotNull(cache.file(path))
        assertEquals(1, repo.downloads.get())

        val small = cache.bitmap(path, 200)!!
        assertEquals(200, small.width)
        assertEquals(150, small.height)
        assertSame(small, cache.bitmap(path, 200))
        assertSame(small, cache.cachedBitmap(path, 200))
        assertEquals(640, cache.bitmap(path, 4000)!!.width) // 不放大
        assertEquals(1, repo.downloads.get())

        // 好幾個縮圖同時要同一個檔案：只下載一次
        cache.clear()
        repo.downloads.set(0)
        (1..6).map { async(Dispatchers.IO) { cache.file(path) } }.awaitAll().forEach { assertNotNull(it) }
        assertEquals(1, repo.downloads.get())

        // 剛上傳的檔案直接放進快取，不用再下載
        val bytes = demo.downloadMedia(DemoMedia.TRIP.path)
        cache.put("c/1/new.png", bytes)
        assertArrayEquals(bytes, cache.file("c/1/new.png")!!.readBytes())
        assertNotNull(cache.bitmap("c/1/new.png", 100))
        assertEquals(1, repo.downloads.get())

        // 沒網路：回傳 null，不會記住失敗，有網路之後再試就好
        repo.offline = true
        assertNull(cache.file(DemoMedia.DINNER.path))
        assertNull(cache.bitmap(DemoMedia.DINNER.path, 100))
        repo.offline = false
        assertNotNull(cache.bitmap(DemoMedia.DINNER.path, 100))

        // 不是圖片的檔案解不出縮圖
        assertNull(cache.bitmap(DemoMedia.VOICE.path, 100))
        assertNotNull(cache.file(DemoMedia.VOICE.path))

        // 登出時清掉
        cache.clear()
        assertNull(cache.cachedBitmap(path, 200))
        assertTrue(File(context.cacheDir, "media").listFiles().orEmpty().isEmpty())
    }

    @Test fun mediaCacheWithoutRepositoryReturnsNull() = runBlocking<Unit> {
        val cache = MediaCache(context) { null }
        cache.clear()
        assertNull(cache.file(DemoMedia.PICNIC.path))
        assertNull(cache.bitmap(DemoMedia.PICNIC.path, 100))
    }

    @Test fun compressesPhotosForUpload() {
        val big = Bitmap.createBitmap(2000, 1500, Bitmap.Config.ARGB_8888)
        val (bytes, size) = PhotoTools.compress(big)
        assertEquals(1600, size.width)
        assertEquals(1200, size.height)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(1600, decoded.width)
        val tiny = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
        assertEquals(40, PhotoTools.compress(tiny).second.width)
    }

    @Test fun formatsVoiceLength() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:03", formatDuration(3200))
        assertEquals("0:12", formatDuration(12_000))
        assertEquals("1:05", formatDuration(65_000))
        assertEquals("3:00", formatDuration(179_800))
    }
}
