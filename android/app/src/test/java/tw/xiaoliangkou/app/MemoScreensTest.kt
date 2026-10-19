package tw.xiaoliangkou.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.data.DemoMedia
import tw.xiaoliangkou.app.data.DemoRepository
import tw.xiaoliangkou.app.data.Errors
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.SheetSpec
import tw.xiaoliangkou.app.ui.screens.MemoSheet
import tw.xiaoliangkou.app.ui.screens.MemoWidgetCard
import tw.xiaoliangkou.app.ui.screens.MemosScreen
import tw.xiaoliangkou.app.ui.theme.XlkTheme

/**
 * 備忘錄：便利貼列表（放在桌面的在前、對方共用的標示作者）、新增編輯（共用／只有我、放在桌面、照片語音）。
 * 截圖：./gradlew recordRoborazziDebug --tests '*MemoScreensTest*'，圖片在 app/build/screens/
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h1000dp-xhdpi")
class MemoScreensTest {
    @get:Rule val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob())
    private val demo = DemoRepository(scope).also { it.running = false }
    private val s: AppState get() = demo.state.value
    private val vm by lazy { AppViewModel(RuntimeEnvironment.getApplication()) }

    @After fun tearDown() {
        scope.cancel()
        runBlocking { vm.container.media.clear() }
    }

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            XlkTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
            }
        }
        compose.mainClock.advanceTimeBy(2500)
        compose.onRoot().captureRoboImage("build/screens/$name.png")
    }

    private fun sheet(name: String, dark: Boolean = false, content: @Composable () -> Unit) = shot(name, dark) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) { content() }
    }

    /** 示範照片直接放進快取（不用登入、不用網路） */
    private fun primeCache() = runBlocking {
        listOf(DemoMedia.PICNIC, DemoMedia.TRIP, DemoMedia.SUNSET).forEach {
            vm.container.media.put(it.path, DemoMedia.sample(it.path)!!)
            // 先解碼好：截圖不用等背景執行緒
            vm.container.media.bitmap(it.path, 512)
        }
    }

    /** 示範資料再多兩則：語音晚安（共用、放在桌面）、沒有標題的小紙條 */
    private fun moreMemos(): AppState = runBlocking {
        demo.saveMemo(null, "晚安語音 🌙", "睡不著的時候聽", listOf(DemoMedia.VOICE), true, true, "sky")
        demo.saveMemo(null, "", "週五晚上 7 點訂位 🍝\n要靠窗的位子", emptyList(), true, false, "pink")
        s
    }

    /** 用 App 真的資料來源（示範模式），存檔、刪除會真的改到資料 */
    private fun live(): AppState {
        vm.container.startDemo()
        compose.waitUntil(10_000) { vm.state.value.loaded }
        return vm.state.value
    }

    private fun waitFor(cond: () -> Boolean) = compose.waitUntil(10_000, cond)

    /** 只畫出來（不截圖），用來測操作 */
    private fun show(content: @Composable () -> Unit) {
        compose.setContent { XlkTheme { content() } }
        compose.mainClock.advanceTimeBy(1000)
    }

    /** 用 App 的狀態畫備忘錄的編輯畫面；關掉時 [closed] 會變成 true */
    private fun liveSheet(id: String?, closed: () -> Unit) = show {
        val st by vm.state.collectAsState()
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) { MemoSheet(st, vm, id, closed) }
    }

    // ------------------------------------------------------------ 截圖

    @Test fun list() {
        primeCache()
        val st = moreMemos()
        shot("50_memos") { MemosScreen(st, vm, {}) {} }
        compose.onNodeWithText("📌 在桌面").assertExists()
        compose.onNodeWithText("其他備忘錄").assertExists()
    }

    @Test fun listDark() {
        primeCache()
        val st = moreMemos()
        shot("50b_memos_dark", dark = true) { MemosScreen(st, vm, {}) {} }
    }

    @Test fun empty() {
        shot("51_memos_empty") { MemosScreen(s.copy(memos = emptyList()), vm, {}) {} }
        compose.onNodeWithText("新增第一則備忘錄").assertExists()
    }

    /** 資料庫還沒跑 upgrade-v2.sql：列表上直接說，不要等按了儲存才跳錯誤 */
    @Test fun schemaOutdated() {
        shot("51b_memos_schema") { MemosScreen(s.copy(memos = emptyList(), schemaOutdated = true), vm, {}) {} }
        compose.onNodeWithText(Errors.SCHEMA_OUTDATED).assertExists()
    }

    @Config(qualifiers = "w400dp-h1300dp-xhdpi")
    @Test fun newSheet() {
        sheet("52_sheet_memo_new") { MemoSheet(s, vm, null) {} }
        compose.onNodeWithText("新增備忘錄 📝").assertExists()
        compose.onNodeWithText("👫 共用").assertExists()
        compose.onNodeWithText("儲存").assertExists()
        compose.onAllNodesWithText("刪除").assertCountEquals(0)
    }

    /** 自己的備忘錄有照片和語音，改成只有我：提醒會搬到只有自己看得到的地方 */
    @Config(qualifiers = "w400dp-h1400dp-xhdpi")
    @Test fun editOwnSheet() {
        primeCache()
        val m = s.memo("m1")!!
        runBlocking { demo.saveMemo(m.id, m.title, m.body, listOf(DemoMedia.PICNIC, DemoMedia.SUNSET, DemoMedia.VOICE), true, true, m.color) }
        sheet("52b_sheet_memo_edit") { MemoSheet(s, vm, "m1") {} }
        compose.onNodeWithText("🔒 只有我").performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("儲存時會把照片和語音搬到只有你看得到的地方", substring = true).assertExists()
        compose.onRoot().captureRoboImage("build/screens/52b_sheet_memo_edit.png")
        compose.onNodeWithText("刪除").assertExists()
    }

    /** 對方共用的：可以一起改內容，不能改共用設定、不能刪 */
    @Config(qualifiers = "w400dp-h1300dp-xhdpi")
    @Test fun partnerSheet() {
        primeCache()
        sheet("53_sheet_memo_partner") { MemoSheet(s, vm, "m3") {} }
        compose.onNodeWithText("小雨 的備忘錄 💌").assertExists()
        compose.onNodeWithText("這是 小雨 共用的備忘錄", substring = true).assertExists()
        compose.onAllNodesWithText("🔒 只有我").assertCountEquals(0)
        compose.onAllNodesWithText("刪除").assertCountEquals(0)
    }

    @Config(qualifiers = "w400dp-h1300dp-xhdpi")
    @Test fun darkUnpairedSheet() {
        sheet("53b_sheet_memo_unpaired_dark", dark = true) { MemoSheet(s.copy(couple = null, partner = null), vm, null) {} }
        compose.onNodeWithText("還沒配對，備忘錄只有你看得到。").assertExists()
        compose.onAllNodesWithText("👫 共用").assertCountEquals(0)
    }

    /** 「桌面小工具」頁裡的備忘錄卡片：用真的小工具版面預覽 */
    @Test fun widgetCard() {
        primeCache()
        val trip = s.memo("m3")!!
        runBlocking { demo.saveMemo(trip.id, trip.title, trip.body, trip.media, trip.shared, true, trip.color) }
        shot("54_widgets_memo_card") { Column(Modifier.padding(16.dp)) { MemoWidgetCard(s, vm) } }
        compose.onNodeWithText("加到桌面").assertExists()
    }

    // ------------------------------------------------------------ 讀螢幕

    /** 放在桌面的卡片唸「已放在桌面」，圖釘併在卡片裡、不會單獨唸一次 */
    @Test fun pinnedCardReadsAsOne() {
        show { MemosScreen(s, vm, {}) {} }
        val card = compose.onNode(hasText("週末採買清單 🛒") and hasClickAction())
        card.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已放在桌面"))
        val texts = card.fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text }
        assertFalse(texts.toString(), "📌" in texts)
        val other = compose.onNode(hasText("想一起去的地方 ✈️") and hasClickAction()).fetchSemanticsNode().config
        assertFalse(SemanticsProperties.StateDescription in other)
    }

    /** 輸入框打了字之後還是有名字（標題、內容）；選顏色的圓點夠大、是一組 */
    @Config(qualifiers = "w400dp-h1300dp-xhdpi")
    @Test fun sheetFieldsHaveNames() {
        show { Column { MemoSheet(s, vm, "m1") {} } }
        compose.onNodeWithContentDescription("標題").assertExists()
        compose.onNodeWithContentDescription("內容").assertExists()
        compose.onNodeWithContentDescription("鵝黃").assertWidthIsAtLeast(48.dp)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)).assertCountEquals(1)
    }

    // ------------------------------------------------------------ 操作

    @Test fun tapOpensTheMemo() {
        var opened: SheetSpec? = null
        show { MemosScreen(s, vm, {}) { opened = it } }
        compose.onNodeWithText("想一起去的地方 ✈️").performClick()
        assertEquals(SheetSpec.MemoEdit("m3"), opened)
        compose.onNodeWithText("新增備忘錄").performClick()
        assertEquals(SheetSpec.MemoEdit(null), opened)
    }

    /** 新增：選「只有我」、放在桌面，存起來就是私人的、釘在桌面 */
    @Test fun saveFollowsSharingAndPinned() {
        live()
        var closed = false
        liveSheet(null) { closed = true }
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("我的小秘密")
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("禮物藏在衣櫃")
        compose.onNodeWithText("🔒 只有我").performClick()
        compose.onNodeWithText("放在桌面小工具").performScrollTo().performClick()
        compose.onNodeWithText("儲存").performScrollTo().performClick()
        waitFor { closed }
        val m = vm.state.value.memos.first { it.title == "我的小秘密" }
        assertEquals("禮物藏在衣櫃", m.body)
        assertFalse(m.shared)
        assertTrue(m.pinned)
        assertEquals(m, vm.state.value.pinnedMemos.first())
    }

    /** 對方共用的：改內容存起來還是對方的、還是共用 */
    @Test fun partnerMemoStaysShared() {
        live()
        var closed = false
        liveSheet("m3") { closed = true }
        val body = compose.onAllNodes(hasSetTextAction())[1]
        body.performTextClearance()
        body.performTextInput("京都賞楓 🍁\n還想去沖繩")
        compose.onNodeWithText("儲存").performScrollTo().performClick()
        waitFor { closed }
        val m = vm.state.value.memo("m3")!!
        assertEquals("京都賞楓 🍁\n還想去沖繩", m.body)
        assertTrue(m.shared)
        assertEquals(vm.state.value.partner!!.id, m.userId)
    }

    /** 刪除自己的：要再確認一次 */
    @Test fun deleteOwnMemo() {
        live()
        var closed = false
        liveSheet("m2") { closed = true }
        compose.onNodeWithText("刪除").performScrollTo().performClick()
        compose.onNodeWithText("確定刪除").performScrollTo().performClick()
        waitFor { closed }
        assertNull(vm.state.value.memo("m2"))
    }
}
