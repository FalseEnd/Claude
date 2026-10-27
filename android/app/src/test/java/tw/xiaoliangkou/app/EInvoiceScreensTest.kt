package tw.xiaoliangkou.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import tw.xiaoliangkou.app.core.EInvoice
import tw.xiaoliangkou.app.data.EInvoiceException
import tw.xiaoliangkou.app.data.EInvoiceStore
import tw.xiaoliangkou.app.data.InvoiceSource
import tw.xiaoliangkou.app.service.EInvoiceSync
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.screens.EInvoiceSheet
import tw.xiaoliangkou.app.ui.theme.XlkTheme
import java.time.LocalDate
import java.time.LocalTime

/**
 * 發票載具的設定畫面：填資料、測試、同步、失敗時說原因。
 * 截圖：./gradlew testDebugUnitTest -Proborazzi.test.record=true --tests 'tw.xiaoliangkou.app.EInvoiceScreensTest'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h1600dp-xhdpi")
class EInvoiceScreensTest {
    @get:Rule val compose = createComposeRule()

    private val app = RuntimeEnvironment.getApplication()
    private val vm by lazy { AppViewModel(app) }
    private val store = EInvoiceStore(app.getSharedPreferences("einv_screen", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
    private var failure: String? = null
    private val source = InvoiceSource { _, _, to ->
        failure?.let { throw EInvoiceException(it) }
        listOf(EInvoice.Invoice("AB00000001", to, LocalTime.NOON, "好丘股份有限公司", 320.0))
    }

    private fun loaded(): EInvoiceSync {
        vm.container.startDemo()
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end && vm.container.repo.value?.state?.value?.loaded != true) {
            ShadowLooper.idleMainLooper(); Thread.sleep(10)
        }
        return EInvoiceSync(store, { source }, vm.container.merchants, { vm.container.repo.value }, { LocalDate.now() })
    }

    private fun show(sync: EInvoiceSync) {
        compose.setContent {
            XlkTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(20.dp)) {
                    EInvoiceSheet(vm, close = {}, sync = sync)
                }
            }
        }
    }

    private fun has(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun idleUntil(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 20_000
        while (!cond() && System.currentTimeMillis() < end) { ShadowLooper.idleMainLooper(); compose.mainClock.advanceTimeBy(50); Thread.sleep(10) }
        assertTrue(cond())
    }

    @Test fun setUpAndSync() {
        val sync = loaded()
        show(sync)
        assertTrue(has("怎麼拿到 AppID"))
        assertTrue(has("匯入消費明細 CSV"))
        compose.onNodeWithText("手機條碼").performTextReplacement("abc1234")
        assertTrue("少了斜線", has("手機條碼是斜線開頭再 7 碼"))
        compose.onNodeWithText("abc1234".uppercase()).performTextReplacement("/abc1234")
        compose.onNodeWithText("驗證碼（申請手機條碼時設定的）").performTextReplacement("secret")
        compose.onNodeWithText("AppID（向財政部申請的）").performTextReplacement("EINV123")
        compose.onNodeWithText("儲存並測試").performClick()
        idleUntil { store.enabled }
        assertEquals("/ABC1234", store.card)
        idleUntil { has("同步完成：新增 1 筆") }
        assertTrue(has("自動同步"))
        assertTrue(vm.container.repo.value!!.state.value.myExpenses.any { it.source == "einvoice" && it.merchant == "好丘" })
        compose.onRoot().captureRoboImage("build/screens/72_einvoice.png")
    }

    @Test fun wrongVerifyCodeIsExplainedAndSyncStaysOff() {
        val sync = loaded()
        failure = "手機條碼的驗證碼不對"
        show(sync)
        compose.onNodeWithText("手機條碼").performTextReplacement("/ABC1234")
        compose.onNodeWithText("驗證碼（申請手機條碼時設定的）").performTextReplacement("wrong")
        compose.onNodeWithText("AppID（向財政部申請的）").performTextReplacement("EINV123")
        compose.onNodeWithText("儲存並測試").performClick()
        idleUntil { has("手機條碼的驗證碼不對") }
        assertTrue("查不到就不打開自動同步", !store.enabled)
        compose.onRoot().captureRoboImage("build/screens/72b_einvoice_error.png")
    }
}
