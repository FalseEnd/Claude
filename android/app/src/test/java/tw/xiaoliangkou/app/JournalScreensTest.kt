package tw.xiaoliangkou.app

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tw.xiaoliangkou.app.data.CoupleRepository
import tw.xiaoliangkou.app.data.DayEntry
import tw.xiaoliangkou.app.data.DemoMedia
import tw.xiaoliangkou.app.data.DemoRepository
import tw.xiaoliangkou.app.data.MediaItem
import tw.xiaoliangkou.app.data.RemoteRepository
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.SheetSpec
import tw.xiaoliangkou.app.ui.screens.CalendarScreen
import tw.xiaoliangkou.app.ui.screens.DayEntrySheet
import tw.xiaoliangkou.app.ui.screens.JournalMark
import tw.xiaoliangkou.app.ui.screens.LoadJournal
import tw.xiaoliangkou.app.ui.screens.journalMarks
import tw.xiaoliangkou.app.ui.screens.journalRange
import tw.xiaoliangkou.app.ui.theme.XlkTheme
import java.time.LocalDate
import java.time.YearMonth
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

    /** 自己寫的點了可以編輯，對方寫的不行 */
    @Test fun onlyOwnEntriesOpenTheEditor() {
        addMyEntryYesterday()
        val day = today.minusDays(1)
        var opened: SheetSpec? = null
        compose.setContent {
            XlkTheme {
                val s by demo.state.collectAsState()
                CalendarScreen(s, vm, { opened = it }, day)
            }
        }
        compose.onNodeWithText("中午吃鼎泰豐", substring = true).performClick()
        compose.waitForIdle()
        assertNull(opened)
        compose.onNodeWithText("晚上一起散步回家", substring = true).performClick()
        assertEquals(SheetSpec.DayEntryEdit(mine(day).id, day), opened)
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

    /** 對方的紀錄打開只能看 */
    @Test fun partnerEntryIsReadOnly() {
        val e = demo.state.value.dayEntriesOn(today.minusDays(1)).first { it.userId == DemoRepository.PARTNER }
        compose.setContent {
            XlkTheme {
                val s by demo.state.collectAsState()
                DayEntrySheet(s, vm, e.id, today.minusDays(1)) {}
            }
        }
        compose.onNodeWithText("只有 小雨 可以修改這則紀錄").assertExists()
        compose.onNodeWithText("儲存").assertDoesNotExist()
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
    }

    /** 換月份：載入那個月（在背景執行緒），載入過的、啟動時已經有的都不重複載入 */
    @Test fun monthChangesLoadOnce() {
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
        val loaded = mutableListOf(RemoteRepository.defaultDayRange())
        val expected = mutableListOf<Pair<LocalDate, LocalDate>>()
        fun settle(m: YearMonth) {
            val r = journalRange(m)
            if (loaded.none { !r.first.isBefore(it.first) && !r.second.isAfter(it.second) }) { loaded += r; expected += r }
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(400)
            compose.waitUntil(WAIT) { calls.size == expected.size }
            compose.waitForIdle()
        }
        compose.setContent { LoadJournal(repo, month, ready = true) {} }
        settle(now)
        listOf(now.plusMonths(1), now, now.plusMonths(1), now.plusMonths(2), now.minusMonths(3), now.plusMonths(2), now.minusMonths(3)).forEach { m ->
            month = m
            settle(m)
        }
        assertTrue(expected.size >= 3)
        assertEquals(expected, calls.toList())
        assertTrue("要在背景執行緒載入", !onMain)
    }

    companion object {
        /** 其他建置同時在跑時 Robolectric 會比較慢，等久一點 */
        const val WAIT = 30_000L
    }
}
