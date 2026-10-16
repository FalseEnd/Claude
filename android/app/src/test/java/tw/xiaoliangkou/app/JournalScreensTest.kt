package tw.xiaoliangkou.app

import android.Manifest
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf
import tw.xiaoliangkou.app.data.CoupleRepository
import tw.xiaoliangkou.app.data.DayEntry
import tw.xiaoliangkou.app.data.DemoMedia
import tw.xiaoliangkou.app.data.DemoRepository
import tw.xiaoliangkou.app.data.Limits
import tw.xiaoliangkou.app.data.MediaItem
import tw.xiaoliangkou.app.data.RemoteRepository
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.SheetSpec
import tw.xiaoliangkou.app.ui.screens.CalendarScreen
import tw.xiaoliangkou.app.ui.screens.DayEntrySheet
import tw.xiaoliangkou.app.ui.screens.JournalMark
import tw.xiaoliangkou.app.ui.screens.LoadJournal
import tw.xiaoliangkou.app.ui.screens.entryTime
import tw.xiaoliangkou.app.ui.screens.journalNeedsLoad
import tw.xiaoliangkou.app.ui.screens.journalMarks
import tw.xiaoliangkou.app.ui.screens.journalRange
import tw.xiaoliangkou.app.ui.theme.XlkTheme
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.Collections

/**
 * 日曆的「這天的生活」：月曆上的標記、當天的紀錄、記錄這天的面板。
 * 截圖：./gradlew recordRoborazziDebug --tests '*JournalScreensTest*'，圖片在 app/build/screens/
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h1600dp-xhdpi")
class JournalScreensTest {
    @get:Rule val compose = createComposeRule()

    private val vm by lazy { AppViewModel(RuntimeEnvironment.getApplication()) }
    private lateinit var demo: DemoRepository
    private val today = LocalDate.now()

    /** 示範模式：照片、語音從示範資料來源下載（不用網路） */
    @Before fun setUp() {
        vm.container.startDemo()
        demo = vm.repo.value as DemoRepository
        demo.running = false
        runBlocking { vm.container.media.clear() }
    }

    @After fun tearDown() = runBlocking { vm.container.media.clear() }

    private fun photoCount() = compose.onAllNodesWithContentDescription("照片").fetchSemanticsNodes().size

    private fun shot(name: String, dark: Boolean = false, photos: Int = 0, content: @Composable () -> Unit) {
        compose.setContent {
            XlkTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
            }
        }
        compose.mainClock.advanceTimeBy(2500)
        if (photos > 0) compose.waitUntil(WAIT) { photoCount() >= photos }
        compose.onRoot().captureRoboImage("build/screens/$name.png")
    }

    private fun sheet(name: String, photos: Int = 0, content: @Composable () -> Unit) = shot(name, photos = photos) {
        Column(Modifier.padding(20.dp)) { content() }
    }

    /** 昨天：小雨的照片＋語音，再加一則我補寫的 */
    private fun addMyEntryYesterday() = runBlocking {
        demo.saveDayEntry(null, today.minusDays(1), "晚上一起散步回家，月亮好圓 🌕 走到一半下起小雨，兩個人撐一把傘", listOf(DemoMedia.SUNSET))
    }

    private fun mine(day: LocalDate): DayEntry = demo.state.value.dayEntriesOn(day).first { it.userId == DemoRepository.ME }

    @Test fun calendarJournal() {
        addMyEntryYesterday()
        shot("45_calendar_journal", photos = 2) {
            val s by demo.state.collectAsState()
            CalendarScreen(s, vm, {}, today.minusDays(1))
        }
        compose.onNodeWithText("這天的生活").assertExists()
        compose.onNodeWithText("🎙️ 0:03").assertExists()
        compose.onNodeWithText("記錄這天 ✍️").assertExists()
        compose.onNodeWithText("在這天約會").assertExists()
    }

    @Test fun calendarJournalDark() {
        addMyEntryYesterday()
        shot("46_calendar_journal_dark", dark = true, photos = 2) {
            val s by demo.state.collectAsState()
            CalendarScreen(s, vm, {}, today.minusDays(1))
        }
    }

    @Test fun entrySheetWithMedia() {
        runBlocking {
            demo.saveDayEntry(null, today, "下班買了雞蛋糕 🧇 還有拍到超可愛的晚霞，錄了一段路邊阿伯唱歌給你聽", listOf(DemoMedia.PICNIC, DemoMedia.TRIP, DemoMedia.VOICE))
        }
        val e = demo.state.value.dayEntriesOn(today).last()
        sheet("47_sheet_day_entry", photos = 2) {
            val s by demo.state.collectAsState()
            DayEntrySheet(s, vm, e.id, today) {}
        }
        compose.onNodeWithText("編輯這天的紀錄").assertExists()
        compose.onNodeWithText("儲存").assertIsEnabled()
        compose.onNodeWithText("刪除這則紀錄").assertExists()
        compose.onNodeWithText("📷 照片").assertExists()
        compose.onNodeWithText("🎙️ 錄音").assertExists()
        // 移除一張照片
        compose.onAllNodesWithContentDescription("移除")[0].performClick()
        compose.waitUntil(WAIT) { photoCount() == 1 }
    }

    @Test fun newEntrySheet() {
        var closed = false
        sheet("48_sheet_day_entry_new") {
            val s by demo.state.collectAsState()
            DayEntrySheet(s, vm, null, today.minusDays(3)) { closed = true }
        }
        compose.onNodeWithText("記錄這天的生活 ✍️").assertExists()
        compose.onNodeWithText("刪除這則紀錄").assertDoesNotExist()
        // 沒寫字也沒照片：不能存
        compose.onNodeWithText("儲存").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("去夜市套圈圈，套到一隻小熊 🧸")
        compose.onNodeWithText("儲存").assertIsEnabled().performClick()
        compose.waitUntil(WAIT) { closed }
        val e = demo.state.value.dayEntriesOn(today.minusDays(3)).single()
        assertEquals(DemoRepository.ME, e.userId)
        assertEquals("去夜市套圈圈，套到一隻小熊 🧸", e.body)
    }

    @Test fun calendarEmptyDay() {
        val day = today.minusDays(3)
        assertTrue(demo.state.value.dayEntriesOn(day).isEmpty())
        var opened: SheetSpec? = null
        shot("49_calendar_journal_empty") {
            val s by demo.state.collectAsState()
            CalendarScreen(s, vm, { opened = it }, day)
        }
        compose.onNodeWithText("這天還沒有紀錄，補一點回憶吧 ✨").assertExists()
        compose.onNodeWithText("記錄這天 ✍️").performClick()
        assertEquals(SheetSpec.DayEntryEdit(null, day), opened)
    }

    /** 點紀錄打開它：自己寫的是編輯，對方寫的是只能看的全文 */
    @Test fun entriesOpenTheirSheet() {
        addMyEntryYesterday()
        val day = today.minusDays(1)
        val theirs = demo.state.value.dayEntriesOn(day).first { it.userId == DemoRepository.PARTNER }
        var opened: SheetSpec? = null
        compose.setContent {
            XlkTheme {
                val s by demo.state.collectAsState()
                CalendarScreen(s, vm, { opened = it }, day)
            }
        }
        compose.onNodeWithText("中午吃鼎泰豐", substring = true).performClick()
        compose.waitForIdle()
        assertEquals(SheetSpec.DayEntryEdit(theirs.id, day), opened)
        compose.onNodeWithText("晚上一起散步回家", substring = true).performClick()
        assertEquals(SheetSpec.DayEntryEdit(mine(day).id, day), opened)
    }

    /** 很長的紀錄在日曆裡只顯示前 6 行 */
    @Test fun longEntriesAreClipped() {
        val day = today.minusDays(2)
        val long = (1..30).joinToString("\n") { "第 $it 行：今天也很開心" }
        runBlocking { demo.saveDayEntry(null, day, long, emptyList()) }
        compose.setContent {
            XlkTheme {
                val s by demo.state.collectAsState()
                CalendarScreen(s, vm, {}, day)
            }
        }
        val node = compose.onNodeWithText(long, useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        assertEquals(6, layouts.single().lineCount)
        assertTrue(layouts.single().hasVisualOverflow)
    }

    /** 資料庫還沒更新：先說要更新，不讓人寫到一半才失敗 */
    @Test fun outdatedDatabaseExplainsFirst() {
        shot("49_calendar_journal_outdated") {
            val s by demo.state.collectAsState()
            CalendarScreen(s.copy(schemaOutdated = true), vm, {}, today.minusDays(3))
        }
        compose.onNodeWithText("upgrade-v2.sql", substring = true).assertExists()
        compose.onNodeWithText("記錄這天 ✍️").assertDoesNotExist()
        compose.onNodeWithText("這天還沒有紀錄，補一點回憶吧 ✨").assertDoesNotExist()
    }

    /** 打開的紀錄已經不見了：不會變成新增（一存就多一則） */
    @Test fun goneEntryDoesNotTurnIntoNew() {
        var closed = false
        compose.setContent {
            XlkTheme {
                val s by demo.state.collectAsState()
                DayEntrySheet(s, vm, "不見了", today) { closed = true }
            }
        }
        compose.onNodeWithText("找不到這則紀錄").assertExists()
        compose.onNodeWithText("儲存").assertDoesNotExist()
        compose.onNodeWithText("關閉").performClick()
        assertTrue(closed)
    }

    /** 自己刪掉之後、面板收起來之前，照樣顯示原本的內容 */
    @Test fun deletingKeepsTheFormUntilClosed() {
        val day = today.minusDays(6)
        val e = mine(day)
        compose.setContent {
            XlkTheme {
                val s by demo.state.collectAsState()
                DayEntrySheet(s, vm, e.id, day) {}
            }
        }
        compose.onNodeWithText("刪除這則紀錄").performClick()
        compose.onNodeWithText("再按一次就刪除").performClick()
        compose.waitUntil(WAIT) { demo.state.value.dayEntries.none { it.id == e.id } }
        compose.waitForIdle()
        compose.onNodeWithText("編輯這天的紀錄").assertExists()
        compose.onNodeWithText("找不到這則紀錄").assertDoesNotExist()
    }

    /** 錄音中不能按儲存（存了面板關掉，那段錄音會不見） */
    @Test fun cannotSaveWhileRecording() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        compose.setContent {
            XlkTheme {
                val s by demo.state.collectAsState()
                DayEntrySheet(s, vm, mine(today).id, today) {}
            }
        }
        compose.onNodeWithText("儲存").assertIsEnabled()
        compose.onNodeWithText("🎙️ 錄音").performClick()
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("先停止錄音再儲存").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("先停止錄音再儲存").assertIsNotEnabled()
        compose.onNodeWithText("取消").performClick()
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("儲存").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("儲存").assertIsEnabled()
    }

    /** 已經在日曆上時，從通知打開別天：跳到那一天 */
    @Test fun jumpsToTheDayFromANotification() {
        var target by mutableStateOf<LocalDate?>(null)
        compose.setContent {
            XlkTheme {
                val s by demo.state.collectAsState()
                CalendarScreen(s, vm, {}, target)
            }
        }
        val day = today.minusMonths(2).withDayOfMonth(15)
        target = day
        compose.waitForIdle()
        compose.onNodeWithText("${day.monthValue} 月 15 日", substring = true).assertExists()
        compose.onNodeWithText("${day.year} 年 ${day.monthValue} 月").assertExists()
    }

    /** 刪除要按兩次 */
    @Test fun deleteNeedsConfirmation() {
        val day = today.minusDays(6)
        val e = mine(day)
        var closed = false
        compose.setContent {
            XlkTheme {
                val s by demo.state.collectAsState()
                DayEntrySheet(s, vm, e.id, day) { closed = true }
            }
        }
        compose.onNodeWithText("刪除這則紀錄").performClick()
        compose.waitForIdle()
        assertTrue(demo.state.value.dayEntries.any { it.id == e.id })
        compose.onNodeWithText("再按一次就刪除").performClick()
        compose.waitUntil(WAIT) { closed }
        assertTrue(demo.state.value.dayEntries.none { it.id == e.id })
    }

    /** 對方的紀錄打開只能看（可以看全文） */
    @Test fun partnerEntryIsReadOnly() {
        val e = demo.state.value.dayEntriesOn(today.minusDays(1)).first { it.userId == DemoRepository.PARTNER }
        sheet("47_sheet_day_entry_partner", photos = 1) {
            val s by demo.state.collectAsState()
            DayEntrySheet(s, vm, e.id, today.minusDays(1)) {}
        }
        compose.onNodeWithText("小雨 的紀錄").assertExists()
        compose.onNodeWithText("只有 小雨 可以修改這則紀錄").assertExists()
        compose.onNodeWithText("儲存").assertDoesNotExist()
        compose.onNodeWithText("刪除這則紀錄").assertDoesNotExist()
    }

    /** 選照片：不開系統的相簿，直接回傳這張圖 */
    private fun photoPicker(): ActivityResultRegistryOwner {
        val file = File(RuntimeEnvironment.getApplication().cacheDir, "pick-${System.nanoTime()}.png")
        val bmp = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(255, 182, 193)) }
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = Uri.fromFile(file)
        return object : ActivityResultRegistryOwner {
            override val activityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                    @Suppress("UNCHECKED_CAST")
                    dispatchResult(requestCode, (if (contract is ActivityResultContracts.PickMultipleVisualMedia) listOf(uri) else uri) as O)
                }
            }
        }
    }

    /** 在面板裡上傳一張照片，回傳它（還沒存） */
    private fun uploadInSheet(day: LocalDate, show: () -> Boolean, onClose: () -> Unit): MediaItem {
        val picker = photoPicker()
        compose.setContent {
            XlkTheme {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) {
                    val s by demo.state.collectAsState()
                    if (show()) DayEntrySheet(s, vm, null, day, onClose)
                }
            }
        }
        val before = demoFiles()
        compose.onNodeWithText("📷 照片").performClick()
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("1 / ${Limits.MEDIA}").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(WAIT) { !vm.busy }
        return MediaItem((demoFiles() - before).single(), MediaItem.PHOTO)
    }

    /** 示範模式上傳的檔案都在記憶體裡 */
    @Suppress("UNCHECKED_CAST")
    private fun demoFiles(): Set<String> =
        (DemoRepository::class.java.getDeclaredField("files").apply { isAccessible = true }.get(demo) as Map<String, ByteArray>).keys.toSet()

    private fun exists(m: MediaItem) = m.path in demoFiles()

    /** 上傳了沒存就關掉面板：照片刪掉 */
    @Test fun unsavedUploadsAreDiscarded() {
        var show by mutableStateOf(true)
        val m = uploadInSheet(today.minusDays(2), { show }) {}
        assertTrue(exists(m))
        show = false
        compose.waitForIdle()
        compose.waitUntil(WAIT) { !exists(m) }
    }

    /** 存起來的照片：面板關掉也不能刪 */
    @Test fun savedUploadsAreKept() {
        var show by mutableStateOf(true)
        val m = uploadInSheet(today.minusDays(2), { show }) { show = false }
        compose.onNodeWithText("儲存").assertIsEnabled().performClick()
        compose.waitUntil(WAIT) { !show }
        compose.waitForIdle()
        Thread.sleep(500)
        assertTrue(exists(m))
        assertEquals(listOf(m.path), demo.state.value.dayEntriesOn(today.minusDays(2)).single().media.map { it.path })
    }

    @Test fun marks() {
        val photo = MediaItem("c/x/a.jpg", MediaItem.PHOTO)
        val voice = MediaItem("c/x/a.m4a", MediaItem.VOICE)
        fun entry(id: String, day: String, media: List<MediaItem> = emptyList()) = DayEntry(id, "u", day, "嗨", media, "2026-10-01T00:00:00Z")
        val marks = journalMarks(
            listOf(
                entry("1", "2026-10-01"),
                entry("2", "2026-10-01", listOf(photo)),
                entry("3", "2026-10-02", listOf(voice)),
                entry("4", "2026-10-03"),
                entry("5", "壞掉的日期"),
            ),
        )
        assertEquals(
            mapOf(LocalDate.of(2026, 10, 1) to JournalMark.PHOTO, LocalDate.of(2026, 10, 2) to JournalMark.DOT, LocalDate.of(2026, 10, 3) to JournalMark.DOT),
            marks,
        )
        assertEquals(LocalDate.of(2026, 9, 24) to LocalDate.of(2026, 11, 7), journalRange(YearMonth.of(2026, 10)))
        assertEquals(LocalDate.of(2028, 1, 25) to LocalDate.of(2028, 3, 7), journalRange(YearMonth.of(2028, 2)))

        // 預設區間（8/15～11/16）裡的月份不用載入；外面的要載，剛載過的不重複
        val default = RemoteRepository.defaultDayRange(LocalDate.of(2026, 10, 16))
        val oct = journalRange(YearMonth.of(2026, 10))
        val dec = journalRange(YearMonth.of(2026, 12))
        val may = journalRange(YearMonth.of(2026, 5))
        assertTrue(!journalNeedsLoad(oct, null, default))
        assertTrue(!journalNeedsLoad(journalRange(YearMonth.of(2026, 9)), may, default))
        assertTrue(journalNeedsLoad(dec, null, default))
        assertTrue(journalNeedsLoad(journalRange(YearMonth.of(2026, 8)), null, default)) // 7/25 起：一部分在外面
        assertTrue(!journalNeedsLoad(dec, dec, default))
        assertTrue(journalNeedsLoad(dec, may, default))
    }

    /** 寫下的時間：當天寫的只有幾點；事後寫的是「補寫」、事先寫的是「提前寫」，最近的用今天、昨天 */
    @Test fun entryTimes() {
        val zone = ZoneId.systemDefault()
        val now = LocalDate.of(2026, 10, 16)
        fun at(d: LocalDate, h: Int, m: Int) = d.atTime(LocalTime.of(h, m)).atZone(zone).toInstant().toString()
        fun e(day: LocalDate, created: String, updated: String? = null) = DayEntry("x", "u", day.toString(), "嗨", emptyList(), created, updated)
        assertEquals("13:10", entryTime(e(now.minusDays(1), at(now.minusDays(1), 13, 10)), now))
        assertEquals("今天 18:06 補寫", entryTime(e(now.minusDays(1), at(now, 18, 6)), now))
        assertEquals("昨天 21:00 補寫", entryTime(e(now.minusDays(5), at(now.minusDays(1), 21, 0)), now))
        assertEquals("10/2 09:30 補寫", entryTime(e(now.minusDays(30), at(LocalDate.of(2026, 10, 2), 9, 30)), now))
        assertEquals("2025/12/31 23:59 補寫", entryTime(e(LocalDate.of(2025, 12, 1), at(LocalDate.of(2025, 12, 31), 23, 59)), now))
        assertEquals("今天 08:00 提前寫", entryTime(e(now.plusDays(3), at(now, 8, 0)), now))
        // 寫完一分鐘以後又改過
        assertEquals("13:10 · 改過", entryTime(e(now, at(now, 13, 10), at(now, 15, 0)), now))
        assertEquals("13:10", entryTime(e(now, at(now, 13, 10), at(now, 13, 10)), now))
    }

    /** 讀螢幕時：有紀錄的日子念得出來，小相機不會被念成「相機」 */
    @Test fun dayCellsDescribeTheirMarks() {
        addMyEntryYesterday()
        val day = today.minusDays(1)
        compose.setContent {
            XlkTheme {
                val s by demo.state.collectAsState()
                CalendarScreen(s, vm, {}, day)
            }
        }
        val cell = hasText("${day.dayOfMonth}") and SemanticsMatcher("有生活紀錄和照片") {
            it.config.getOrNull(SemanticsProperties.StateDescription)?.contains("有生活紀錄和照片") == true
        }
        compose.onNode(cell).assertExists()
        // 選到的那天
        compose.onNode(cell and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).assertExists()
    }

    /**
     * 換月份：在背景執行緒載入那個月；預設區間裡的不載，剛載過的不重複，
     * 但翻回更早看過的月份要再載一次（有變動時只會重抓最近載入的那段）
     */
    @Test fun monthChangesLoad() {
        val calls = Collections.synchronizedList(mutableListOf<Pair<LocalDate, LocalDate>>())
        var onMain = false
        val repo = object : CoupleRepository by demo {
            override suspend fun loadDayEntries(from: LocalDate, to: LocalDate) {
                if (Looper.myLooper() == Looper.getMainLooper()) onMain = true
                calls += from to to
            }
        }
        val now = YearMonth.from(today)
        var month by mutableStateOf(now)
        var last: Pair<LocalDate, LocalDate>? = null
        val expected = mutableListOf<Pair<LocalDate, LocalDate>>()
        fun settle(m: YearMonth) {
            val r = journalRange(m)
            if (journalNeedsLoad(r, last)) { last = r; expected += r }
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(400)
            compose.waitUntil(WAIT) { calls.size == expected.size }
            compose.waitForIdle()
        }
        compose.setContent { LoadJournal(repo, month, ready = true) {} }
        settle(now)
        val far = now.minusMonths(3)
        val farther = now.plusMonths(3)
        listOf(now.plusMonths(1), now, now.plusMonths(1), farther, far, farther, far, now, far).forEach { m ->
            month = m
            settle(m)
        }
        assertEquals(expected, calls.toList())
        // 看過的遠方月份翻回來會再載入
        assertTrue(calls.count { it == journalRange(far) } >= 2)
        assertEquals(2, calls.count { it == journalRange(farther) })
        assertTrue("要在背景執行緒載入", !onMain)
    }

    /** 生活紀錄的通知帶著那天：打開日曆的那一天 */
    @Test fun diaryNotificationsOpenTheirDay() {
        assertEquals("calendar/2026-10-15", RemoteRepository.routeFor("diary", buildJsonObject { put("day", "2026-10-15") }))
        assertEquals("calendar", RemoteRepository.routeFor("diary"))
        assertEquals("calendar", RemoteRepository.routeFor("diary", buildJsonObject { put("day", "../memos") }))
        assertEquals("calendar", RemoteRepository.routeFor("event", buildJsonObject { put("day", "2026-10-15") }))
    }

    companion object {
        /** 其他建置同時在跑時 Robolectric 會比較慢，等久一點 */
        const val WAIT = 30_000L
    }
}
