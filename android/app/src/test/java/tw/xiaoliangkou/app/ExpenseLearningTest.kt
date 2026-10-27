package tw.xiaoliangkou.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tw.xiaoliangkou.app.data.MerchantMemory
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.screens.ExpenseEditSheet
import tw.xiaoliangkou.app.ui.theme.XlkTheme

/** 改了開銷的店名、選了類別：記起來，下次同一家店就照這樣記 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h1200dp-xhdpi")
class ExpenseLearningTest {
    @get:Rule val compose = createComposeRule()

    private val vm by lazy { AppViewModel(RuntimeEnvironment.getApplication()) }

    private fun loaded() {
        vm.container.startDemo()
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end && vm.container.repo.value?.state?.value?.loaded != true) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
    }

    @Test fun renamingAndPickingACategoryIsRemembered() {
        loaded()
        val s = vm.container.repo.value!!.state.value
        val e = s.myExpenses.first()
        val memory: MerchantMemory = vm.container.merchants
        compose.setContent { XlkTheme { Box(Modifier.padding(16.dp)) { ExpenseEditSheet(s, vm, e.id) {} } } }
        compose.onNodeWithText(e.merchant).performTextReplacement("阿明的店")
        compose.onNodeWithText("禮物").performClick()
        compose.onNodeWithText("儲存").performClick()
        compose.waitForIdle()
        assertEquals(MerchantMemory.Learned("阿明的店", "gift"), memory.recall(e.merchant))
        assertEquals(MerchantMemory.Learned("阿明的店", "gift"), memory.recall("阿明的店"))
    }

    @Test fun onlyChangingTheAmountTeachesNothing() {
        loaded()
        val s = vm.container.repo.value!!.state.value
        val e = s.myExpenses.first()
        compose.setContent { XlkTheme { Box(Modifier.padding(16.dp)) { ExpenseEditSheet(s, vm, e.id) {} } } }
        compose.onNodeWithText("儲存").performClick()
        compose.waitForIdle()
        assertNull(vm.container.merchants.recall(e.merchant))
    }
}
