package tw.xiaoliangkou.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import tw.xiaoliangkou.app.data.AppException
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.data.Comfort
import tw.xiaoliangkou.app.data.CoupleRepository
import tw.xiaoliangkou.app.data.DemoRepository
import tw.xiaoliangkou.app.data.Giphy
import tw.xiaoliangkou.app.data.GiphyException
import tw.xiaoliangkou.app.data.GiphyKind
import tw.xiaoliangkou.app.data.GiphyPage
import tw.xiaoliangkou.app.data.GiphySearcher
import tw.xiaoliangkou.app.data.Mood
import tw.xiaoliangkou.app.data.RemoteRepository
import tw.xiaoliangkou.app.data.Session
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.Derived
import tw.xiaoliangkou.app.ui.components.GiphyPickerDialog
import tw.xiaoliangkou.app.ui.components.StickerImage
import tw.xiaoliangkou.app.ui.screens.AccountScreen
import tw.xiaoliangkou.app.ui.screens.ComfortSheet
import tw.xiaoliangkou.app.ui.screens.HistoryScreen
import tw.xiaoliangkou.app.ui.screens.HomeScreen
import tw.xiaoliangkou.app.ui.screens.MoodScreen
import tw.xiaoliangkou.app.ui.screens.MoreScreen
import tw.xiaoliangkou.app.ui.theme.XlkTheme
import java.time.Duration
import java.time.Instant

/**
 * GIPHY 貼圖：心情、安撫附上貼圖，首頁收到的安撫、歷史紀錄、選貼圖的視窗、帳號裡的 key。
 * 貼圖是假的（[TestXlkApp]），搜尋也是假的（[FakeGiphy]），不會連到 GIPHY。
 * 截圖：./gradlew testDebugUnitTest -Proborazzi.test.record=true --tests 'tw.xiaoliangkou.app.StickerScreensTest'，圖片在 app/build/screens/
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h1600dp-xhdpi")
class StickerScreensTest {
    @get:Rule val compose = createComposeRule()

    private val app get() = RuntimeEnvironment.getApplication() as TestXlkApp
    private val scope = CoroutineScope(SupervisorJob())
    private val demo = DemoRepository(scope).also { it.running = false }
    private val s: AppState get() = demo.state.value
    private val vm by lazy { AppViewModel(app) }
    private val giphy = FakeGiphy()

    /** 正式使用（不是示範）、已經設定好 GIPHY 的 key */
    private val withKey: AppState get() = s.copy(demo = false, couple = s.couple!!.copy(giphyKey = FakeGiphy.KEY))
    /** 正式使用，還沒有 GIPHY 的 key */
    private val noKey: AppState get() = s.copy(demo = false, couple = s.couple!!.copy(giphyKey = null))

    @After fun tearDown() = scope.cancel()

    // ================================================================ 工具

    private fun show(dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            XlkTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
            }
        }
        compose.mainClock.advanceTimeBy(2500)
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("build/screens/$name.png")

    /** 選貼圖的視窗是另一個視窗：截整個螢幕 */
    @OptIn(ExperimentalRoborazziApi::class)
    private fun screen(name: String) {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        captureScreenRoboImage("build/screens/$name.png")
    }

    private fun has(text: String, substring: Boolean = false) = compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    /** 等 [n] 張貼圖載好（載好才有說明文字） */
    private fun waitFor(description: String, n: Int = 1) =
        compose.waitUntil(WAIT) { compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().size >= n }

    private fun waitStickers(n: Int) = waitFor("貼圖", n)

    private fun idleUntil(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + WAIT
        while (!cond() && System.currentTimeMillis() < end) { ShadowLooper.idleMainLooper(); compose.mainClock.advanceTimeBy(50); Thread.sleep(10) }
        assertTrue(cond())
    }

    /** 換掉 App 目前的資料來源（vm.act 用的是它） */
    private fun setRepo(repo: CoupleRepository) {
        @Suppress("UNCHECKED_CAST")
        val field = vm.container.javaClass.getDeclaredField("_repo").apply { isAccessible = true }.get(vm.container) as MutableStateFlow<CoupleRepository?>
        field.value = repo
    }

    /** 「最近用過」的貼圖（最近的在前）；設定存在同一個 DataStore，每個測試先清乾淨 */
    private fun recents(vararg ids: String) {
        idleUntil { vm.prefs.value != null }
        runBlocking {
            vm.container.settings.clear()
            ids.reversed().forEach { vm.container.addRecentSticker(it) }
            vm.container.reloadPrefs()
        }
    }

    /** 帳號頁要有 Supabase 的設定（邀請另一半的卡片） */
    private fun configured() {
        idleUntil { vm.prefs.value != null }
        runBlocking {
            val st = vm.container.settings
            st.setSession(Session("a", "r", 0, "u", "azhe@example.com"))
            st.clear()
            st.setSupabase("https://abcdefgh.supabase.co", "sb_publishable_Xy7kQ2mN9pL4rT8vW1zA3b")
            vm.container.reloadPrefs()
        }
    }

    /** 搜尋結果的格子：視窗裡唯一上下捲的清單（關鍵字那一排是左右捲） */
    private val grid = hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

    // ================================================================ 心情

    /** 今天的心情附了貼圖：心情、悄悄話、貼圖都先填好，可以換一張或移除 */
    @Test fun moodWithSticker() {
        val mine = Mood(9001, DemoRepository.ME, "happy", "今天被主管誇獎了", Instant.now().toString(), sticker = DemoRepository.STICKER)
        show { MoodScreen(s.copy(moods = listOf(mine) + s.moods), vm, giphy) {} }
        waitStickers(1)
        compose.onNodeWithText("換一張").assertExists()
        compose.onNodeWithText("移除").assertExists()
        capture("36_mood_sticker")
        compose.onNodeWithText("移除").performClick()
        compose.onNodeWithText("加一張 GIPHY 貼圖").assertExists()
        assertTrue("還沒打開選貼圖的視窗，不會查 GIPHY", giphy.asked.isEmpty())
    }

    /** 這 7 天：名字換成頭像（很長的名字也不會被切成「梓梓」），唸得出是誰 */
    @Test fun moodWeekShowsAvatars() {
        show { MoodScreen(s.copy(partner = s.partner!!.copy(displayName = "梓梓的小宝")), vm, giphy) {} }
        compose.onNodeWithContentDescription("梓梓的小宝").assertExists()
        compose.onNodeWithContentDescription("我").assertExists()
    }

    /** 選一張貼圖：心情頁出現貼圖，「更新心情」把貼圖一起送出去，也記到「最近用過」 */
    @Test fun pickingAStickerPostsItWithTheMood() {
        recents()
        val posted = mutableListOf<Triple<String, String?, String?>>()
        setRepo(object : CoupleRepository by demo {
            override suspend fun postMood(mood: String, note: String?, sticker: String?) { posted += Triple(mood, note, sticker) }
        })
        var back = false
        show { MoodScreen(withKey, vm, giphy) { back = true } }
        compose.onNodeWithText("開心").performClick()
        compose.onNodeWithText("加一張 GIPHY 貼圖").performClick()
        // 依選的心情先查「happy」（視窗很高、不用捲就看得到最後一張：不會自己接著載第二頁）
        waitFor(FakeGiphy.description("happy", 1))
        assertEquals(listOf("happy" to 0), giphy.asked.map { it.query to it.offset })
        compose.onNodeWithContentDescription(FakeGiphy.description("happy", 3)).performClick()
        waitStickers(1)
        compose.onNodeWithText("換一張").assertExists()
        compose.onNodeWithText("更新心情").performClick()
        idleUntil { back }
        assertEquals(listOf(Triple("happy", "", "shappy3")), posted)
        idleUntil { vm.prefs.value?.recentStickers == listOf("shappy3") }
    }

    /** 資料庫還存不了貼圖：沒有「加一張 GIPHY 貼圖」，只說要再跑一次 upgrade-v2.sql */
    @Test fun outdatedDatabaseHidesTheMoodStickerButton() {
        show { MoodScreen(s.copy(dbSchema = 3), vm, giphy) {} }
        compose.onNodeWithText("加一張 GIPHY 貼圖").assertDoesNotExist()
        compose.onNodeWithText("再執行一次 upgrade-v2.sql 就能用 GIPHY 貼圖").assertExists()
    }

    // ================================================================ 安撫

    /** 選了摸摸頭：先查「head pat」，挑一張、寫悄悄話，送出時三樣一起送 */
    @Test fun pickingAStickerSendsItWithTheComfort() {
        recents()
        val sent = mutableListOf<Triple<String, String?, String?>>()
        setRepo(object : CoupleRepository by demo {
            override suspend fun sendComfort(kind: String, message: String?, sticker: String?) { sent += Triple(kind, message, sticker) }
        })
        var closed = false
        show { Column(Modifier.padding(20.dp)) { ComfortSheet(withKey, vm, giphy) { closed = true } } }
        compose.onNodeWithText("摸摸頭").performClick()
        compose.onNodeWithText("加一張 GIPHY 貼圖").performClick()
        waitFor(FakeGiphy.description("head pat", 1))
        assertEquals("head pat" to 0, giphy.asked.first().let { it.query to it.offset })
        assertTrue(giphy.asked.all { it.query == "head pat" })
        compose.onNodeWithContentDescription(FakeGiphy.description("head pat", 2)).performClick()
        waitStickers(1)
        compose.onNode(hasSetTextAction()).performTextInput("今天辛苦了，晚上帶你去吃好吃的")
        compose.mainClock.advanceTimeBy(1000)
        capture("37_sheet_comfort_sticker")
        compose.onNodeWithText("送出摸摸頭").performClick()
        idleUntil { closed }
        assertEquals(listOf(Triple("pat", "今天辛苦了，晚上帶你去吃好吃的", "sheadpat2")), sent)
    }

    @Test fun outdatedDatabaseHidesTheComfortStickerButton() {
        show { Column(Modifier.padding(20.dp)) { ComfortSheet(s.copy(dbSchema = 3), vm, giphy) {} } }
        compose.onNodeWithText("加一張 GIPHY 貼圖").assertDoesNotExist()
        compose.onNodeWithText("再執行一次 upgrade-v2.sql 就能用 GIPHY 貼圖").assertExists()
    }

    // ================================================================ 首頁、歷史紀錄

    /** 對方今天的心情附了貼圖 */
    private fun homeState(): AppState {
        val pm = Derived.moodToday(s, DemoRepository.PARTNER)!!
        return s.copy(moods = s.moods.map { if (it.id == pm.id) it.copy(sticker = FakeGiphy.STICKER_B) else it })
    }

    /** 首頁最上面：對方傳來、還沒看的安撫（貼圖、悄悄話、回抱）；對方的心情附了貼圖，下面看得出是什麼心情 */
    @Test fun homeReceivedComfort() {
        show { HomeScreen(homeState(), vm, {}, {}) }
        waitStickers(2)
        compose.onNodeWithText("收到抱抱了，晚安").assertExists()
        compose.onNodeWithText("😩 好累").assertExists()
        capture("38_home_received_comfort")
    }

    @Test fun homeReceivedComfortDark() {
        show(dark = true) { HomeScreen(homeState(), vm, {}, {}) }
        waitStickers(2)
        capture("38b_home_received_comfort_dark")
    }

    /** 「收到了」標成看過；「回抱 🤗」先標成看過（卡片馬上收起來）再送一個抱抱 */
    @Test fun receivedComfortButtons() {
        val done = mutableListOf<String>()
        setRepo(object : CoupleRepository by demo {
            override suspend fun readComfort(id: Long) { done += "read $id" }
            override suspend fun sendComfort(kind: String, message: String?, sticker: String?) { done += "send $kind $message $sticker" }
        })
        val c = s.comforts.first { it.receiverId == s.myId && it.readAt == null }
        show { HomeScreen(s, vm, {}, {}) }
        compose.onNodeWithText("收到了").performClick()
        idleUntil { done.isNotEmpty() && !vm.busy }
        assertEquals(listOf("read ${c.id}"), done)
        compose.onNodeWithText("回抱 🤗").performClick()
        idleUntil { done.size == 3 && !vm.busy }
        assertEquals(listOf("read ${c.id}", "read ${c.id}", "send hug null null"), done)
    }

    /** 標不起來（網路不穩）就不抱：卡片還在，不會變成抱了卻又跳出來、再抱一次 */
    @Test fun hugBackNeedsTheReadMarkFirst() {
        val messages = mutableListOf<String>()
        scope.launch(Dispatchers.Unconfined) { vm.messages.collect { messages += it } }
        var sent = 0
        setRepo(object : CoupleRepository by demo {
            override suspend fun readComfort(id: Long) { throw AppException("連不到 Supabase，請確認網路", "offline") }
            override suspend fun sendComfort(kind: String, message: String?, sticker: String?) { sent++ }
        })
        show { HomeScreen(s, vm, {}, {}) }
        compose.onNodeWithText("回抱 🤗").performClick()
        idleUntil { messages.isNotEmpty() && !vm.busy }
        assertEquals(listOf("連不到 Supabase，請確認網路"), messages)
        assertEquals(0, sent)
    }

    /** 0.2.1 以前從來沒有標過看過：首頁只拿最近 3 天的出來，「收到了」之後換下一則最近的，不會把以前的一則一則翻出來 */
    @Test fun homeSkipsOldUnreadComforts() {
        val now = Instant.now()
        fun comfort(id: Long, ago: Duration, message: String) =
            Comfort(id, senderId = DemoRepository.PARTNER, receiverId = DemoRepository.ME, kind = "hug", message = message, createdAt = now.minus(ago).toString())
        val db = MutableStateFlow(s.copy(comforts = listOf(
            comfort(9003, Duration.ofHours(2), "剛剛的抱抱"), comfort(9002, Duration.ofHours(30), "昨天的抱抱"), comfort(9001, Duration.ofDays(10), "十天前的抱抱"),
        )))
        setRepo(object : CoupleRepository by demo {
            override val state = db
            override suspend fun readComfort(id: Long) = db.update { st -> st.copy(comforts = st.comforts.map { if (it.id == id) it.copy(readAt = now.toString()) else it }) }
        })
        show {
            val st by db.collectAsState()
            HomeScreen(st, vm, {}, {})
        }
        compose.onNodeWithText("剛剛的抱抱").assertExists()
        compose.onNodeWithText("收到了").performClick()
        idleUntil { !vm.busy && db.value.comforts.first { it.id == 9003L }.readAt != null }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("昨天的抱抱").assertExists()
        compose.onNodeWithText("收到了").performClick()
        idleUntil { !vm.busy && db.value.comforts.first { it.id == 9002L }.readAt != null }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("十天前的抱抱").assertDoesNotExist()
        compose.onNodeWithText("收到了").assertDoesNotExist()
    }

    /** 看過了就不再出現在首頁 */
    @Test fun readComfortLeavesHome() {
        val st = s.copy(comforts = s.comforts.map { it.copy(readAt = it.readAt ?: Instant.now().toString()) })
        show { HomeScreen(st, vm, {}, {}) }
        compose.onNodeWithText("收到了").assertDoesNotExist()
    }

    /** 歷史紀錄：心情、安撫附的貼圖在文字下面；打開就把收到的安撫標成看過（安安靜靜，不會一直重複） */
    @Test fun historyStickersAndMarksRead() {
        var marked = 0
        setRepo(object : CoupleRepository by demo {
            override suspend fun markComfortsRead() { marked++ }
        })
        runBlocking { demo.postMood("happy", "今天被主管誇獎了", FakeGiphy.STICKER_B) }
        show { HistoryScreen(s, vm) {} }
        waitStickers(2)
        idleUntil { marked == 1 }
        capture("39_history_sticker")
        compose.onNodeWithText("心情").performClick()
        compose.mainClock.advanceTimeBy(1000)
        assertEquals(1, marked)
        assertTrue(!vm.busy)
    }

    /** 打開歷史紀錄馬上就離開：標成看過交給 ViewModel 做完，不會只標到一半、剩下的又跑回來 */
    @Test fun historyMarkingOutlivesTheScreen() {
        val gate = CompletableDeferred<Unit>()
        var started = 0
        var marked = 0
        setRepo(object : CoupleRepository by demo {
            override suspend fun markComfortsRead() { started++; gate.await(); marked++ }
        })
        var open by mutableStateOf(true)
        show { if (open) HistoryScreen(s, vm) {} }
        idleUntil { started == 1 }
        open = false
        compose.waitForIdle()
        gate.complete(Unit)
        idleUntil { marked == 1 }
    }

    // ================================================================ 資料庫還不能存貼圖

    /** 0.1 的資料庫（還沒跑過 upgrade-v2.sql）：不出現「加一張 GIPHY 貼圖」、選貼圖的視窗和 key 的設定，說的是「執行」不是「再執行一次」 */
    @Test fun v1DatabaseHasNoStickerButtons() {
        val posted = mutableListOf<String?>()
        setRepo(object : CoupleRepository by demo {
            override suspend fun postMood(mood: String, note: String?, sticker: String?) { posted += sticker }
        })
        // 今天的心情附了貼圖（在別的資料庫記的）：送出去時也不帶貼圖，不會整個心情被擋下來
        val mine = Mood(9001, DemoRepository.ME, "happy", null, Instant.now().toString(), sticker = DemoRepository.STICKER)
        val v1 = s.copy(demo = false, schemaOutdated = true, dbSchema = 0, moods = listOf(mine) + s.moods)
        var back = false
        show { MoodScreen(v1, vm, giphy) { back = true } }
        compose.onNodeWithText("加一張 GIPHY 貼圖").assertDoesNotExist()
        compose.onNodeWithText("換一張").assertDoesNotExist()
        compose.onNodeWithText("執行 upgrade-v2.sql 就能用 GIPHY 貼圖").assertExists()
        assertTrue(!has("再執行一次", substring = true))
        compose.onNodeWithText("更新心情").performClick()
        idleUntil { back }
        assertEquals(listOf<String?>(null), posted)
    }

    @Test fun v1DatabaseHasNoComfortStickerButton() {
        show { Column(Modifier.padding(20.dp)) { ComfortSheet(s.copy(demo = false, schemaOutdated = true, dbSchema = 0), vm, giphy) {} } }
        compose.onNodeWithText("加一張 GIPHY 貼圖").assertDoesNotExist()
        compose.onNodeWithText("執行 upgrade-v2.sql 就能用 GIPHY 貼圖").assertExists()
    }

    @Config(qualifiers = PHONE)
    @Test fun v1DatabasePicker() {
        recents()
        picker(noKey.copy(schemaOutdated = true, dbSchema = 0))
        assertTrue(has("執行 upgrade-v2.sql 就能用 GIPHY 貼圖"))
        assertTrue("不教怎麼申請 key（存不進去）", !has("先設定 GIPHY 的 key"))
        assertTrue(giphy.asked.isEmpty() && giphy.checked.isEmpty())
    }

    @Test fun v1DatabaseAccountHasNoKeyForm() {
        configured()
        show { AccountScreen(noKey.copy(schemaOutdated = true, dbSchema = 0), vm, giphy) {} }
        assertTrue(has("資料庫還存不了貼圖和 key：照上面的步驟執行一次 upgrade-v2.sql 就能設定"))
        assertTrue(!has("GIPHY 的 API key"))
        assertTrue(!has("儲存"))
    }

    /** 沒有分享心情：說明不會說「對方會看到」（安撫跟心情的分享無關，照常） */
    @Test fun moodStickerHintWhenNotSharing() {
        val hidden = s.copy(mySharing = s.mySharing!!.copy(shareMood = false))
        var comfort by mutableStateOf(false)
        show { if (comfort) Column(Modifier.padding(20.dp)) { ComfortSheet(hidden, vm, giphy) {} } else MoodScreen(hidden, vm, giphy) {} }
        compose.onNodeWithText("選填（目前沒有分享心情，對方看不到）").assertExists()
        compose.onNodeWithText("選填，對方會看到會動的貼圖").assertDoesNotExist()
        comfort = true
        compose.waitForIdle()
        compose.onNodeWithText("選填，對方會看到會動的貼圖").assertExists()
    }

    /** 選好的貼圖放在淺粉紅的底上：黑色線條的貼圖在深色模式也看得見 */
    @Test fun stickerHasALightBackingInDarkMode() {
        var view: View? = null
        show(dark = true) {
            view = LocalView.current
            Box(Modifier.padding(20.dp).testTag("sticker")) { StickerImage(DemoRepository.STICKER, Modifier.size(120.dp)) }
        }
        waitStickers(1)
        compose.waitForIdle()
        val v = view!!
        val img = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { v.draw(Canvas(img)) }
        // 貼圖左邊中間（四周留的邊）
        val box = compose.onNodeWithTag("sticker").getBoundsInRoot()
        val density = v.resources.displayMetrics.density
        val px = img.getPixel(((box.left.value + 2) * density).toInt(), ((box.top.value + box.bottom.value) / 2 * density).toInt())
        assertEquals(listOf(0xFC, 0xEA, 0xF0), listOf(android.graphics.Color.red(px), android.graphics.Color.green(px), android.graphics.Color.blue(px)))
    }

    // ================================================================ 選貼圖的視窗（一般手機的高度）

    private fun picker(st: AppState, dark: Boolean = false, suggestion: Pair<String, String>? = "抱抱" to "hug", searcher: GiphySearcher = giphy) =
        show(dark) { GiphyPickerDialog(st, vm, suggestion, {}, {}, searcher) }

    /** GIPHY 的規定：選貼圖的視窗一定看得到官方的「Powered by GIPHY」 */
    private fun attributed() = compose.onNodeWithContentDescription("Powered by GIPHY").assertIsDisplayed()

    /** 還沒有 key：為什麼要、三個步驟、貼上 key，對方可能已經設定好了 */
    @Config(qualifiers = PHONE)
    @Test fun pickerSetup() {
        recents()
        picker(noKey)
        assertTrue(has("先設定 GIPHY 的 key"))
        attributed()
        screen("73_giphy_setup")
        assertTrue("還沒有 key，不會查 GIPHY", giphy.asked.isEmpty())
    }

    @Config(qualifiers = PHONE)
    @Test fun pickerSetupDark() {
        recents()
        picker(noKey, dark = true)
        screen("73b_giphy_setup_dark")
    }

    /** 搜尋結果：先查推薦的「抱抱」；有最近用過的貼圖就排在最前面 */
    @Config(qualifiers = PHONE)
    @Test fun pickerResults() {
        recents(DemoRepository.STICKER, FakeGiphy.STICKER_B)
        picker(withKey)
        waitFor(FakeGiphy.description("hug", 1))
        assertTrue(has("最近用過"))
        attributed()
        screen("74_giphy_results")
        assertEquals(listOf(GiphyKind.STICKERS to "hug"), giphy.asked.map { it.kind to it.query })
    }

    @Config(qualifiers = PHONE)
    @Test fun pickerResultsDark() {
        recents(DemoRepository.STICKER)
        picker(withKey, dark = true)
        waitFor(FakeGiphy.description("hug", 1))
        screen("74b_giphy_results_dark")
    }

    /** 第一頁還沒回來：淡淡的格子 */
    @Config(qualifiers = PHONE)
    @Test fun pickerLoading() {
        recents()
        giphy.hang = true
        picker(withKey)
        screen("74c_giphy_loading")
    }

    /** 最近用過：用 id 顯示，不用查 GIPHY */
    @Config(qualifiers = PHONE)
    @Test fun pickerRecent() {
        recents(DemoRepository.STICKER, FakeGiphy.STICKER_B, FakeGiphy.STICKER_C)
        picker(withKey)
        waitFor(FakeGiphy.description("hug", 1))
        compose.onNodeWithText("最近用過").performClick()
        compose.waitUntil(WAIT) { compose.onAllNodesWithContentDescription("最近用過的貼圖", substring = true).fetchSemanticsNodes().size == 3 }
        screen("74d_giphy_recent")
        assertEquals(1, giphy.asked.size)
        // 跟搜尋結果一樣，貼圖離格子的邊 6 dp（StickerImage 自己留的邊，格子不再多留一次）
        val cell = compose.onNodeWithContentDescription("最近用過的貼圖 1").getBoundsInRoot()
        val image = compose.onNodeWithContentDescription("最近用過的貼圖 1", useUnmergedTree = true).getBoundsInRoot()
        assertEquals(12f, ((cell.right - cell.left) - (image.right - image.left)).value, 0.5f)
    }

    /** 連不到 GIPHY：說明和「重試」 */
    @Config(qualifiers = PHONE)
    @Test fun pickerNetworkError() {
        recents()
        giphy.fail = GiphyException.Reason.NETWORK
        picker(withKey)
        compose.waitUntil(WAIT) { has("連不到 GIPHY") }
        screen("75_giphy_error")
        giphy.fail = null
        compose.onNodeWithText("重試").performClick()
        waitFor(FakeGiphy.description("hug", 1))
        assertEquals(2, giphy.asked.size)
    }

    /** key 不能用了：直接換一個（同一張設定卡片） */
    @Config(qualifiers = PHONE)
    @Test fun pickerBadKey() {
        recents()
        giphy.fail = GiphyException.Reason.BAD_KEY
        picker(withKey)
        compose.waitUntil(WAIT) { has("GIPHY 的 key 不能用了") }
        attributed()
        screen("75b_giphy_bad_key")
    }

    /** key 不能用了，對方也還沒換：「再檢查一次」再問 GIPHY 一次，還是不行就寫在按鈕下面（卡片留著） */
    @Config(qualifiers = PHONE)
    @Test fun badKeyRecheckWithTheSameKey() {
        recents()
        var refreshed = 0
        setRepo(object : CoupleRepository by demo {
            override val state = MutableStateFlow(withKey)
            override suspend fun refreshAll() { refreshed++ }
        })
        giphy.fail = GiphyException.Reason.BAD_KEY
        giphy.keyFails = GiphyException.Reason.BAD_KEY
        picker(withKey)
        compose.waitUntil(WAIT) { has("GIPHY 的 key 不能用了") }
        compose.onNodeWithText("再檢查一次").performScrollTo().performClick()
        idleUntil { has("還是原來那個 key：請照上面的步驟換一個新的") }
        assertEquals(1, refreshed)
        assertEquals(listOf(FakeGiphy.KEY), giphy.checked)
        assertTrue(has("GIPHY 的 key 不能用了"))
        assertEquals(1, giphy.asked.size)
    }

    /** GIPHY 又讓這個 key 能用了（暫時的 401／403）：「再檢查一次」就回到搜尋，不用關掉視窗重開 */
    @Config(qualifiers = PHONE)
    @Test fun badKeyRecheckWhenItWorksAgain() {
        recents()
        setRepo(object : CoupleRepository by demo {
            override val state = MutableStateFlow(withKey)
            override suspend fun refreshAll() {}
        })
        giphy.fail = GiphyException.Reason.BAD_KEY
        picker(withKey)
        compose.waitUntil(WAIT) { has("GIPHY 的 key 不能用了") }
        giphy.fail = null
        compose.onNodeWithText("再檢查一次").performScrollTo().performClick()
        waitFor(FakeGiphy.description("hug", 1))
        assertEquals(listOf(FakeGiphy.KEY), giphy.checked)
        assertEquals(listOf("hug", "hug"), giphy.asked.map { it.query })
    }

    /** key 不能用了，又貼一次同一個 key（GIPHY 說可以了）：存好就再查一次 */
    @Config(qualifiers = PHONE)
    @Test fun badKeySavedAgainSearchesAgain() {
        recents()
        val saved = mutableListOf<String?>()
        setRepo(object : CoupleRepository by demo {
            override suspend fun setGiphyKey(key: String?) { saved += key }
        })
        giphy.fail = GiphyException.Reason.BAD_KEY
        picker(withKey)
        compose.waitUntil(WAIT) { has("GIPHY 的 key 不能用了") }
        giphy.fail = null
        compose.onNode(hasSetTextAction()).performTextInput(FakeGiphy.KEY)
        compose.onNodeWithText("儲存").performScrollTo().performClick()
        waitFor(FakeGiphy.description("hug", 1))
        assertEquals(listOf<String?>(FakeGiphy.KEY), saved)
        assertEquals(2, giphy.asked.size)
    }

    @Config(qualifiers = PHONE)
    @Test fun pickerEmpty() {
        recents()
        picker(withKey, searcher = { GiphyPage(emptyList(), null) })
        compose.waitUntil(WAIT) { has("找不到，換個關鍵字試試") }
        screen("75c_giphy_empty")
    }

    /** 這個小時用太多次了：先從最近用過的選 */
    @Config(qualifiers = PHONE)
    @Test fun pickerRateLimited() {
        recents(DemoRepository.STICKER)
        giphy.fail = GiphyException.Reason.RATE_LIMITED
        picker(withKey)
        compose.waitUntil(WAIT) { has("GIPHY 這個小時用太多次了，等一下再試 🙏") }
        assertTrue(has("可以先從「最近用過」選一張"))
        screen("76_giphy_rate_limited")
    }

    /** 示範模式：不搜尋 GIPHY（不呼叫 API），先用示範的貼圖試試；那張貼圖一樣是從 GIPHY 載入的，畫面上也這樣說 */
    @Config(qualifiers = PHONE)
    @Test fun pickerDemo() {
        recents()
        picker(s)
        waitFor("示範貼圖")
        assertTrue(has("示範模式不能搜尋 GIPHY"))
        assertTrue(has("示範的貼圖一樣是從 GIPHY 載入的", substring = true))
        attributed()
        screen("77_giphy_demo")
        assertTrue(giphy.asked.isEmpty())
        assertEquals("只載了示範的那張", listOf(Giphy.stickerUrl(DemoRepository.STICKER)), app.stickers.requests.distinct())
    }

    @Config(qualifiers = PHONE)
    @Test fun pickerUnpaired() {
        recents()
        picker(noKey.copy(couple = null, partner = null))
        assertTrue(has("配對後才能用 GIPHY 貼圖"))
        attributed()
        assertTrue(giphy.asked.isEmpty())
    }

    /** 注音還在選字（「ㄅㄠˋ」）時停下來不查；選好字（「抱抱」）才查 */
    @Config(qualifiers = PHONE)
    @Test fun zhuyinIsNotSearchedWhileComposing() {
        recents()
        picker(withKey)
        waitFor(FakeGiphy.description("hug", 1))
        val field = compose.onNode(hasSetTextAction())
        field.performTextInput("ㄅㄠˋ")
        compose.mainClock.advanceTimeBy(2000)
        field.performTextReplacement("抱ㄅㄠ")
        compose.mainClock.advanceTimeBy(2000)
        assertEquals(listOf("hug"), giphy.asked.map { it.query })
        field.performTextReplacement("抱抱")
        compose.mainClock.advanceTimeBy(1000)
        idleUntil { giphy.asked.size == 2 }
        assertEquals("抱抱", giphy.asked.last().query)
    }

    /** 很高的螢幕（平板）不用捲就看得到最後一格：不會自己一頁一頁載下去，按「載入更多」才載 */
    @Test fun tallScreenLoadsMoreOnlyWhenAsked() {
        recents()
        picker(withKey)
        waitFor(FakeGiphy.description("hug", 24))
        compose.mainClock.advanceTimeBy(1500)
        assertEquals(listOf(0), giphy.asked.map { it.offset })
        compose.onNodeWithText("載入更多").performScrollTo().performClick()
        waitFor(FakeGiphy.description("hug", 25))
        assertEquals(listOf(0, 24), giphy.asked.map { it.offset })
    }

    /** 免費的 key 一小時只能查 100 次：打字停下來才查、捲到最後才載下一頁、畫面重畫不會再查 */
    @Config(qualifiers = PHONE)
    @Test fun pickerSavesGiphyCalls() {
        var st by mutableStateOf(withKey)
        show { GiphyPickerDialog(st, vm, "抱抱" to "hug", {}, {}, giphy) }
        waitFor(FakeGiphy.description("hug", 1))
        assertEquals(listOf("hug"), giphy.asked.map { it.query })

        val field = compose.onNode(hasSetTextAction())
        field.performTextInput("c")
        compose.mainClock.advanceTimeBy(200)
        field.performTextInput("a")
        compose.mainClock.advanceTimeBy(200)
        field.performTextInput("t")
        compose.mainClock.advanceTimeBy(300)
        assertEquals("還在打字", 1, giphy.asked.size)
        compose.mainClock.advanceTimeBy(500)
        waitFor(FakeGiphy.description("cat", 1))
        assertEquals(listOf("hug", "cat"), giphy.asked.map { it.query })

        // 畫面重畫（例如收到新的安撫）不會再查一次
        st = st.copy(comforts = emptyList())
        compose.mainClock.advanceTimeBy(1000)
        assertEquals(2, giphy.asked.size)

        // 捲到最後一張才載下一頁
        compose.onNode(grid).performScrollToIndex(23)
        idleUntil { giphy.asked.size == 3 }
        assertEquals("cat" to 24, giphy.asked.last().let { it.query to it.offset })

        // GIF：同一個關鍵字從頭開始
        compose.onNodeWithText("GIF").performClick()
        idleUntil { giphy.asked.size == 4 }
        assertEquals(Triple(GiphyKind.GIFS, "cat", 0), giphy.asked.last().let { Triple(it.kind, it.query, it.offset) })
    }

    /** 選一張：回傳 id、記到最近用過、關掉視窗 */
    @Config(qualifiers = PHONE)
    @Test fun pickingReturnsTheIdAndCloses() {
        recents()
        var picked: String? = null
        var closed by mutableStateOf(false)
        show { if (!closed) GiphyPickerDialog(withKey, vm, null, { picked = it }, { closed = true }, giphy) }
        // 沒有推薦的就是熱門
        waitFor(FakeGiphy.description("trending", 1))
        assertEquals(listOf(""), giphy.asked.map { it.query })
        compose.onNodeWithText("親親").performClick()
        waitFor(FakeGiphy.description("kiss", 1))
        compose.onNodeWithContentDescription(FakeGiphy.description("kiss", 5)).performClick()
        idleUntil { closed }
        assertEquals("skiss5", picked)
        idleUntil { vm.prefs.value?.recentStickers == listOf("skiss5") }
    }

    /** 設定 key：格式不對、GIPHY 說不能用都不存；試查成功才存進 Supabase */
    @Config(qualifiers = PHONE)
    @Test fun setupCardChecksTheKeyBeforeSaving() {
        recents()
        val saved = mutableListOf<String?>()
        setRepo(object : CoupleRepository by demo {
            override suspend fun setGiphyKey(key: String?) { saved += key }
        })
        picker(noKey)
        val field = compose.onNode(hasSetTextAction())
        field.performTextInput("not a key")
        compose.onNodeWithText("儲存").performClick()
        compose.waitUntil(WAIT) { has(RemoteRepository.BAD_GIPHY_KEY) }
        assertTrue("格式就不對：不用問 GIPHY", giphy.checked.isEmpty())
        assertTrue(saved.isEmpty())

        giphy.keyFails = GiphyException.Reason.BAD_KEY
        field.performTextReplacement(FakeGiphy.KEY)
        compose.onNodeWithText("儲存").performClick()
        idleUntil { has("GIPHY 說這個 key 不能用", substring = true) }
        assertEquals(listOf(FakeGiphy.KEY), giphy.checked)
        assertTrue(saved.isEmpty())

        giphy.keyFails = null
        compose.onNodeWithText("儲存").performClick()
        idleUntil { saved.isNotEmpty() }
        assertEquals(listOf<String?>(FakeGiphy.KEY), saved)
        assertEquals(2, giphy.checked.size)
    }

    /** 對方可能已經設定了：再檢查一次，還是沒有就寫在下面（視窗會蓋住 App 的提示） */
    @Config(qualifiers = PHONE)
    @Test fun setupCardRecheck() {
        recents()
        var refreshed = 0
        setRepo(object : CoupleRepository by demo {
            override val state = MutableStateFlow(noKey)
            override suspend fun refreshAll() { refreshed++ }
        })
        picker(noKey)
        compose.onNodeWithText("再檢查一次").performClick()
        idleUntil { has("小雨 也還沒設定，照上面的步驟設定一下吧") }
        assertEquals(1, refreshed)
    }

    // ================================================================ 帳號

    @Test fun accountGiphySet() {
        configured()
        show { AccountScreen(withKey, vm, giphy) {} }
        compose.onNodeWithText("設定好了 ✓").assertExists()
        compose.onNodeWithText("xlkT•••").assertExists()
        capture("78_account_giphy_set")
    }

    @Test fun accountGiphyUnset() {
        configured()
        show { AccountScreen(noKey, vm, giphy) {} }
        compose.onNodeWithText("還沒設定").assertExists()
        capture("78b_account_giphy_unset")
    }

    /** key 和密碼的欄位要讓鍵盤知道是密碼：不自動校正，也不會把打的字記進鍵盤的字典 */
    @Test fun keyAndPasswordFieldsUseThePasswordKeyboard() {
        configured()
        var view: View? = null
        show {
            view = LocalView.current
            AccountScreen(noKey, vm, giphy) {}
        }
        for (label in listOf("GIPHY 的 API key", "目前的密碼")) {
            compose.onNode(hasSetTextAction() and hasText(label)).performScrollTo().performClick()
            compose.waitForIdle()
            val info = EditorInfo()
            assertNotNull(label, view!!.onCreateInputConnection(info))
            assertEquals(label, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, info.inputType and (InputType.TYPE_MASK_CLASS or InputType.TYPE_MASK_VARIATION))
            assertEquals("不自動校正", 0, info.inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        }
    }

    /** 移除 key 要先確認；兩個人都會受影響 */
    @Test fun accountRemovesTheKey() {
        configured()
        val saved = mutableListOf<String?>()
        setRepo(object : CoupleRepository by demo {
            override suspend fun setGiphyKey(key: String?) { saved += key }
        })
        show { AccountScreen(withKey, vm, giphy) {} }
        compose.onNodeWithText("移除 key").performClick()
        compose.onNodeWithText("移除 GIPHY 的 key？").assertExists()
        compose.onNodeWithText("移除").performClick()
        idleUntil { saved.isNotEmpty() }
        assertEquals(listOf<String?>(null), saved)
    }

    /** schema 3 的資料庫：溫和地提醒再跑一次 upgrade-v2.sql，列出會多什麼 */
    @Test fun accountStickerUpgrade() {
        configured()
        show { AccountScreen(s.copy(demo = false, dbSchema = 3), vm, giphy) {} }
        assertTrue(has("可以再更新一次資料庫"))
        assertTrue(has("心情和安撫可以附上 GIPHY 貼圖"))
        assertTrue(!has("看得到對方的手機名稱和電信"))
        capture("79_account_upgrade_stickers")
    }

    /** 再跑一次 upgrade-v2.sql 之後按「重新檢查」：還沒好就說還是舊的，好了就說多了什麼 */
    @Test fun stickerUpgradeRecheckToasts() {
        val messages = mutableListOf<String>()
        scope.launch(Dispatchers.Unconfined) { vm.messages.collect { messages += it } }
        val db = MutableStateFlow(s.copy(demo = false, dbSchema = 3))
        setRepo(object : CoupleRepository by demo {
            override val state = db
        })
        show { AccountScreen(db.value, vm, giphy) {} }
        compose.onNodeWithText("我更新好了，重新檢查").performScrollTo().performClick()
        idleUntil { messages.isNotEmpty() && !vm.busy }
        assertEquals("還是舊的資料庫：確認 SQL Editor 下方出現「更新完成 🎉」，再檢查一次", messages.last())

        // 只補到貼圖、還沒有更多心情：還是舊的 upgrade-v2.sql
        db.value = db.value.copy(dbSchema = RemoteRepository.STICKER_SCHEMA)
        compose.onNodeWithText("我更新好了，重新檢查").performScrollTo().performClick()
        idleUntil { messages.size == 2 }
        assertEquals("還是舊的資料庫：確認 SQL Editor 下方出現「更新完成 🎉」，再檢查一次", messages.last())

        // 補到更多心情，還沒有小貓和手機溫度：還是舊的 upgrade-v2.sql
        db.value = db.value.copy(dbSchema = RemoteRepository.MOODS_SCHEMA)
        compose.onNodeWithText("我更新好了，重新檢查").performScrollTo().performClick()
        idleUntil { messages.size == 3 }
        assertEquals("還是舊的資料庫：確認 SQL Editor 下方出現「更新完成 🎉」，再檢查一次", messages.last())

        db.value = db.value.copy(dbSchema = RemoteRepository.TEMP_SCHEMA)
        compose.onNodeWithText("我更新好了，重新檢查").performScrollTo().performClick()
        idleUntil { messages.size == 4 }
        assertEquals("資料庫更新好了，手機溫度、養小貓、更多心情和安撫、GIPHY 貼圖都有了 🎉", messages.last())
    }

    /** 「我的」選單的說明跟著列出再更新一次會多什麼 */
    @Test fun moreMenuListsWhatTheUpgradeAdds() {
        var st by mutableStateOf(s.copy(demo = false, dbSchema = 3))
        show { MoreScreen(st, vm) {} }
        assertTrue(has("可以再更新一次資料庫（手機溫度、養小貓、更多心情和安撫、GIPHY 貼圖）📱"))
        st = st.copy(dbSchema = 2)
        compose.waitForIdle()
        assertTrue(has("可以再更新一次資料庫（手機溫度、養小貓、更多心情和安撫、GIPHY 貼圖、手機名稱、電信）📱"))
        st = st.copy(dbSchema = 4)
        compose.waitForIdle()
        assertTrue(has("可以再更新一次資料庫（手機溫度、養小貓、更多心情和安撫）📱"))
        st = st.copy(dbSchema = 5)
        compose.waitForIdle()
        assertTrue(has("可以再更新一次資料庫（手機溫度、養小貓、更多安撫）📱"))
        st = st.copy(dbSchema = 6)
        compose.waitForIdle()
        assertTrue(has("可以再更新一次資料庫（手機溫度、養小貓）📱"))
        st = st.copy(dbSchema = 7)
        compose.waitForIdle()
        assertTrue(has("可以再更新一次資料庫（手機溫度）📱"))
        st = st.copy(dbSchema = 8)
        compose.waitForIdle()
        assertTrue(has("連線、邀請另一半、GIPHY 貼圖、改密碼、登出"))
    }

    companion object {
        /** 一般手機的高度（全螢幕的視窗） */
        const val PHONE = "w400dp-h860dp-xhdpi"
        const val WAIT = 10_000L
    }
}
