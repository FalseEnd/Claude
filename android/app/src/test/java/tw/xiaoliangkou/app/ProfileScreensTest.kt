package tw.xiaoliangkou.app

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.data.DemoRepository
import tw.xiaoliangkou.app.data.ProfileInfo
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.SheetSpec
import tw.xiaoliangkou.app.ui.screens.MyProfileScreen
import tw.xiaoliangkou.app.ui.screens.NoteSheet
import tw.xiaoliangkou.app.ui.screens.PartnerProfileScreen
import tw.xiaoliangkou.app.ui.theme.XlkTheme

/**
 * 我的資料、對方的資料與只有自己看得到的備註。
 * 截圖：./gradlew recordRoborazziDebug --tests '*ProfileScreensTest*'，圖片在 app/build/screens/
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h1600dp-xhdpi")
class ProfileScreensTest {
    @get:Rule val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob())
    private val demo = DemoRepository(scope).also { it.running = false }
    private val s: AppState get() = demo.state.value
    private val vm by lazy { AppViewModel(RuntimeEnvironment.getApplication()) }

    @After fun tearDown() = scope.cancel()

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            XlkTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
            }
        }
        compose.mainClock.advanceTimeBy(2500)
        compose.onRoot().captureRoboImage("build/screens/$name.png")
    }

    private fun sheet(name: String, content: @Composable () -> Unit) = shot(name) {
        Column(Modifier.padding(20.dp)) { content() }
    }

    /** 真的走示範模式的資料來源（vm.act 才存得進去），畫面跟著 state 更新 */
    private fun live(content: @Composable (AppState) -> Unit): DemoRepository {
        vm.container.startDemo()
        val repo = vm.repo.value as DemoRepository
        repo.running = false
        compose.setContent {
            val st by vm.state.collectAsState()
            XlkTheme { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { if (st.loaded) content(st) } }
        }
        idleUntil { vm.state.value.loaded }
        compose.mainClock.advanceTimeBy(500)
        return repo
    }

    private fun idleUntil(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!cond() && System.currentTimeMillis() < end) { ShadowLooper.idleMainLooper(); compose.mainClock.advanceTimeBy(50); Thread.sleep(10) }
        assertTrue(cond())
    }

    @Test @Config(qualifiers = "w400dp-h1800dp-xhdpi")
    fun myProfile() = shot("40_my_profile") { MyProfileScreen(s, vm) {} }

    @Test fun partnerProfile() = shot("41_partner_profile") { PartnerProfileScreen(s, vm, {}) {} }

    @Test fun noteSheet() = sheet("42_sheet_note") { NoteSheet(s, vm, "n2") {} }

    @Test fun newNoteSheet() = sheet("42_sheet_note_new") { NoteSheet(s, vm, null) {} }

    @Test fun partnerProfileDark() = shot("43_partner_profile_dark", dark = true) { PartnerProfileScreen(s, vm, {}) {} }

    @Test fun unpaired() = shot("44_partner_unpaired") { PartnerProfileScreen(s.copy(partner = null, couple = null), vm, {}) {} }

    /** 沒填資料的新帳號：每一格都有淡淡的範例文字 */
    @Test @Config(qualifiers = "w400dp-h900dp-xhdpi")
    fun emptyProfile() {
        val blank = s.copy(me = s.me!!.copy(birthday = null, info = kotlinx.serialization.json.JsonObject(emptyMap())))
        shot("40_my_profile_empty") { MyProfileScreen(blank, vm) {} }
    }

    /** 身高打錯：顯示錯誤、不能儲存；改對了才存得進去，對方的畫面也會看到 */
    @Test @Config(qualifiers = "w400dp-h900dp-xhdpi")
    fun myProfileValidatesAndSaves() {
        val repo = live { st -> MyProfileScreen(st, vm) {} }
        compose.onNodeWithText("都儲存好了 ✓").assertExists()
        compose.onNodeWithText("儲存").assertIsNotEnabled()

        val height = compose.onNode(hasSetTextAction() and hasText("176"))
        height.performTextReplacement("1760")
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("要在 50–250 之間").assertExists()
        compose.onNodeWithText("有欄位要修正一下 ✋").assertExists()
        compose.onNodeWithText("儲存").assertIsNotEnabled()
        compose.onRoot().captureRoboImage("build/screens/40_my_profile_error.png")

        compose.onNode(hasSetTextAction() and hasText("1760")).performTextReplacement("１７７")   // 全形數字也看得懂
        compose.onNode(hasSetTextAction() and hasText("ISTJ")).performTextReplacement("enfj")
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("有修改還沒儲存").assertExists()
        compose.onNodeWithText("ENFJ").assertExists()
        compose.onNodeWithText("儲存").assertIsEnabled().performClick()
        idleUntil { ProfileInfo.number(repo.state.value.me!!.info, ProfileInfo.HEIGHT) == 177.0 }
        assertEquals("ENFJ", ProfileInfo.text(repo.state.value.me!!.info, ProfileInfo.MBTI))
        // 其他欄位原封不動
        assertEquals("軟體工程師", ProfileInfo.text(repo.state.value.me!!.info, ProfileInfo.JOB))
        idleUntil { runCatching { compose.onNodeWithText("都儲存好了 ✓").assertExists() }.isSuccess }
    }

    /** 編輯到一半畫面重畫（例如資料從別台手機更新）：輸入的內容不能不見 */
    @Test @Config(qualifiers = "w400dp-h900dp-xhdpi")
    fun editsSurviveStateUpdates() {
        val repo = live { st -> MyProfileScreen(st, vm) {} }
        compose.onNode(hasSetTextAction() and hasText("176")).performTextReplacement("180")
        compose.mainClock.advanceTimeBy(300)
        kotlinx.coroutines.runBlocking { repo.updateAvatar(tw.xiaoliangkou.app.data.Avatars.emoji("🐻")) }
        compose.mainClock.advanceTimeBy(500)
        compose.onNode(hasSetTextAction() and hasText("180")).assertExists()
        compose.onNodeWithText("有修改還沒儲存").assertExists()
    }

    /** 點電話會打開撥號畫面（不會直接打出去） */
    @Test fun partnerPhoneOpensDialer() {
        var opened: SheetSpec? = null
        compose.setContent { XlkTheme { PartnerProfileScreen(s, vm, {}) { opened = it } } }
        compose.onNodeWithText("撥打").performClick()
        val intent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertNotNull(intent)
        assertEquals(Intent.ACTION_DIAL, intent.action)
        assertEquals("tel:0912345678", intent.data.toString())

        compose.onNodeWithText("新增").performClick()
        assertEquals(SheetSpec.NoteEdit(null), opened)
        compose.onNodeWithText("她喜歡半糖少冰", substring = true).performScrollTo().performClick()
        assertEquals(SheetSpec.NoteEdit("n1"), opened)
    }

    /** 刪除備註要先確認 */
    @Test fun deleteNoteAsksFirst() {
        val repo = live { st -> PartnerProfileScreen(st, vm, {}) {} }
        compose.onNodeWithText("小雨 看不到這些備註", substring = true).assertExists()
        compose.onAllNodesWithContentDescription("刪除備註")[0].performScrollTo().performClick()
        compose.onNodeWithText("刪除這則備註？").assertExists()
        assertEquals(2, repo.state.value.notes.size)
        compose.onNodeWithText("刪除").performClick()
        idleUntil { repo.state.value.notes.size == 1 }
        assertTrue(repo.state.value.notes.none { it.id == "n1" })
    }

    /** 新增備註：點靈感會帶出開頭，存好就出現在對方的頁面 */
    @Test fun addNote() {
        var closed = false
        val repo = live { st -> Column(Modifier.padding(20.dp)) { NoteSheet(st, vm, null) { closed = true } } }
        compose.onNodeWithText("儲存").assertIsNotEnabled()
        compose.onNodeWithText("想要的禮物").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("那雙米白色的帆布鞋")
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("儲存").assertIsEnabled().performClick()
        idleUntil { closed }
        assertEquals("想要的禮物：那雙米白色的帆布鞋", repo.state.value.notes.first().body)
        assertEquals(3, repo.state.value.notes.size)
    }
}
