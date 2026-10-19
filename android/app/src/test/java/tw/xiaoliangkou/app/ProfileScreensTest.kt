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
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
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

    /** 對方什麼都還沒填、也還沒寫過備註 */
    @Test fun partnerProfileEmpty() = shot("41_partner_profile_empty") {
        PartnerProfileScreen(s.copy(partner = s.partner!!.copy(birthday = null, info = JsonObject(emptyMap())), notes = emptyList()), vm, {}) {}
    }

    /** 資料庫還沒跑 upgrade-v2.sql：先說清楚，不要顯示成「還沒填」 */
    @Test fun partnerProfileSchemaOutdated() = shot("41_partner_profile_outdated") {
        PartnerProfileScreen(s.copy(schemaOutdated = true, partner = s.partner!!.copy(info = JsonObject(emptyMap())), notes = emptyList()), vm, {}) {}
    }

    @Test @Config(qualifiers = "w400dp-h700dp-xhdpi")
    fun myProfileSchemaOutdated() = shot("40_my_profile_outdated") { MyProfileScreen(s.copy(schemaOutdated = true), vm) {} }

    /** 窄螢幕、字放大：血型不會被切掉 */
    @Test @Config(qualifiers = "w360dp-h1000dp-xhdpi", fontScale = 1.3f)
    fun myProfileLargeFont() = shot("40_my_profile_large_font") { MyProfileScreen(s, vm) {} }

    @Test @Config(qualifiers = "w360dp-h1500dp-xhdpi", fontScale = 1.6f)
    fun myProfileHugeFont() = shot("40_my_profile_huge_font") { MyProfileScreen(s, vm) {} }

    /** 還沒填生日：日曆翻到 25 年前，但不會先選好日子，要點一天才能按「確定」 */
    @OptIn(ExperimentalRoborazziApi::class)
    @Test @Config(qualifiers = "w400dp-h900dp-xhdpi")
    fun birthdayPickerStartsEmpty() {
        val repo = live { st -> MyProfileScreen(st, vm) {} }
        runBlocking { repo.state.value.me!!.let { repo.updateProfileInfo(it.displayName, null, it.info) } }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("還沒填，點一下選日期").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("確定").assertIsNotEnabled()
        captureScreenRoboImage("build/screens/40_birthday_picker.png")

        val year = java.time.LocalDate.now().minusYears(25).year
        compose.onNode(hasClickAction() and hasText("$year", substring = true) and hasText("15", substring = true)).performClick()
        compose.onNodeWithText("確定").assertIsEnabled().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("$year/1/15", substring = true).assertExists()
        compose.onNodeWithText("有修改還沒儲存").assertExists()
    }

    /** 沒改過：別台手機存的新資料直接顯示；自己改過的欄位留著，儲存時也不會蓋掉別台剛改的 */
    @Test @Config(qualifiers = "w400dp-h900dp-xhdpi")
    fun remoteUpdatesMergeWithEdits() {
        val repo = live { st -> MyProfileScreen(st, vm) {} }
        fun remote(key: String, v: String) = runBlocking {
            val me = repo.state.value.me!!
            repo.updateProfileInfo(me.displayName, me.birthday, ProfileInfo.build(mapOf(key to v), me.info))
        }
        fun eventually(check: () -> Unit) = idleUntil { runCatching(check).isSuccess }

        remote(ProfileInfo.HEIGHT, "177")
        eventually { compose.onNode(hasSetTextAction() and hasText("177")).assertExists() }
        compose.onNodeWithText("都儲存好了 ✓").assertExists()
        compose.onNodeWithText("儲存").assertIsNotEnabled()

        compose.onNode(hasSetTextAction() and hasText("ISTJ")).performTextReplacement("enfj")
        remote(ProfileInfo.JOB, "產品經理")
        eventually { compose.onNode(hasSetTextAction() and hasText("產品經理")).assertExists() }
        compose.onNode(hasSetTextAction() and hasText("ENFJ")).assertExists()
        compose.onNodeWithText("有修改還沒儲存").assertExists()

        // 存之前別台手機又改了體重：只送自己改的 MBTI，體重不會被舊的值蓋掉
        remote(ProfileInfo.WEIGHT, "70")
        compose.onNodeWithText("儲存").assertIsEnabled().performClick()
        idleUntil { ProfileInfo.text(repo.state.value.me!!.info, ProfileInfo.MBTI) == "ENFJ" }
        val info = repo.state.value.me!!.info
        assertEquals("產品經理", ProfileInfo.text(info, ProfileInfo.JOB))
        assertEquals(177.0, ProfileInfo.number(info, ProfileInfo.HEIGHT))
        assertEquals(70.0, ProfileInfo.number(info, ProfileInfo.WEIGHT))
        eventually { compose.onNodeWithText("都儲存好了 ✓").assertExists() }
        compose.onNode(hasSetTextAction() and hasText("70")).assertExists()
    }

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
        compose.onNodeWithContentDescription("撥打電話").performClick()
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
