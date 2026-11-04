package tw.xiaoliangkou.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tw.xiaoliangkou.app.data.AppLock
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.LockGate
import tw.xiaoliangkou.app.ui.PatternGrid
import tw.xiaoliangkou.app.ui.PinDialog
import tw.xiaoliangkou.app.ui.screens.SecurityScreen
import tw.xiaoliangkou.app.ui.theme.XlkTheme

/**
 * App 鎖：鎖定畫面、密碼、指紋或臉部、忘記密碼；App 安全性的設定。
 * 截圖：./gradlew testDebugUnitTest -Proborazzi.test.record=true --tests 'tw.xiaoliangkou.app.LockScreensTest'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class LockScreensTest {
    @get:Rule val compose = createComposeRule()

    private var now = 1_000_000L
    private val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("lock_ui_test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
    private fun newLock() = AppLock(prefs, clock = { now }, iterations = 1_000)
    private val bio = FakeBiometrics()
    private val vm by lazy { AppViewModel(RuntimeEnvironment.getApplication()) }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { XlkTheme { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() } } }
        compose.waitForIdle()
    }

    private fun capture(name: String, large: Boolean = false) =
        captureScreenRoboImage(if (large) "build/screens-large/$name.png" else "build/screens/$name.png")

    private fun has(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun type(pin: String) = pin.forEach { compose.onNodeWithText(it.toString()).performClick() }

    /** 鎖起來之前看到的 App：一個會記住的數字 */
    @Composable
    private fun Counter() {
        var n by rememberSaveable { mutableIntStateOf(0) }
        TextButton(onClick = { n++ }) { Text("按了 $n 次") }
    }

    private fun locked(pin: String = "2580"): AppLock {
        newLock().setPin(pin)
        return newLock()
    }

    @Test fun rightPinUnlocksAndTheAppIsWhereItWas() {
        val lock = newLock().also { it.setPin("2580") }
        show { LockGate(lock, bio, onForgot = {}, onBack = {}) { Counter() } }
        repeat(3) { compose.onNodeWithText("按了 $it 次").performClick() }
        // 離開 App 超過 1 分鐘再回來
        lock.onBackground()
        now += 61_000
        lock.onForeground()
        compose.waitForIdle()
        assertTrue(has("小倆口已上鎖"))
        assertFalse("鎖著時看不到 App", has("按了"))
        assertEquals("沒開指紋就不問", 0, bio.asked)
        type("2580")
        compose.waitForIdle()
        assertFalse(lock.locked.value)
        assertTrue("回到原本的狀態", has("按了 3 次"))
    }

    @Test fun wrongPinSaysHowManyTriesAreLeft() {
        val lock = locked()
        show { LockGate(lock, bio, onForgot = {}, onBack = {}) { Counter() } }
        type("1111")
        compose.waitForIdle()
        assertTrue(has("密碼不對，還可以試 4 次"))
        repeat(4) { type("1111") }
        compose.waitForIdle()
        assertTrue(has("錯太多次了，30 秒後再試"))
        assertTrue(lock.locked.value)
        capture("80b_lock_wait")
    }

    @Test fun fingerprintOrFaceFirst() {
        newLock().apply { setPin("2580"); setBiometric(true) }
        val lock = newLock()
        show { LockGate(lock, bio, onForgot = {}, onBack = {}) { Counter() } }
        assertEquals(1, bio.asked)
        assertFalse(lock.locked.value)
        assertTrue(has("按了 0 次"))
    }

    @Test fun fingerprintCancelledFallsBackToThePin() {
        newLock().apply { setPin("258046"); setBiometric(true) }
        val lock = newLock()
        bio.succeed = false
        show { LockGate(lock, bio, onForgot = {}, onBack = {}) { Counter() } }
        assertEquals(1, bio.asked)
        assertTrue(has("輸入密碼，或用指紋、臉部解鎖"))
        capture("80_lock")
        // 再按指紋的按鈕
        bio.succeed = true
        compose.onNodeWithContentDescription("用指紋或臉部解鎖").performClick()
        compose.waitForIdle()
        assertEquals(2, bio.asked)
        assertTrue(has("按了 0 次"))
    }

    @Test fun fingerprintOffOnThisPhoneUsesThePin() {
        newLock().apply { setPin("2580"); setBiometric(true) }
        val lock = newLock()
        bio.problem = "這支手機還沒設定指紋或臉部辨識"
        show { LockGate(lock, bio, onForgot = {}, onBack = {}) { Counter() } }
        assertEquals(0, bio.asked)
        assertTrue(has("輸入密碼解鎖"))
    }

    @Test fun forgotPinLogsOut() {
        val lock = locked()
        var forgot = false
        show { LockGate(lock, bio, onForgot = { forgot = true }, onBack = {}) { Counter() } }
        compose.onNodeWithText("忘記密碼？").performClick()
        assertTrue(has("登出後用 Email 和密碼重新登入"))
        compose.onNodeWithText("登出").performClick()
        assertTrue(forgot)
    }

    @Test fun quickWindowHasNoForgotLink() {
        val lock = locked()
        show { LockGate(lock, bio, onForgot = null, onBack = {}) { Counter() } }
        assertFalse(has("忘記密碼"))
    }

    // ================================================================ App 安全性

    @Test fun turningOnAsksForTheSamePinTwice() {
        val lock = newLock()
        show { SecurityScreen(vm, back = {}, lock = lock, biometrics = bio) }
        capture("81_security_off")
        compose.onNodeWithText("進入 App 要解鎖").performClick()
        assertTrue(has("用什麼解鎖？"))
        compose.onNodeWithText("數字密碼（4～8 位）").performClick()
        assertTrue(has("設定密碼"))
        type("1234")
        compose.onNodeWithText("下一步").performClick()
        assertTrue(has("再輸入一次"))
        type("1235")
        assertTrue(has("兩次不一樣，請重新設定"))
        assertFalse(lock.enabled)
        type("123456")
        compose.onNodeWithText("下一步").performClick()
        type("123456")
        compose.waitForIdle()
        assertTrue(lock.enabled)
        assertEquals(6, lock.pinLength)
        assertTrue("手機可以就順便打開指紋", lock.biometric)
        assertFalse("剛設定好不用解鎖", lock.locked.value)
        assertTrue(has("密碼是 6 位數字"))
        assertTrue(has("離開 App 多久要再解鎖"))
        capture("81_security_on")
    }

    @Test fun turningOffNeedsThePin() {
        val lock = newLock().also { it.setPin("2580") }
        show { SecurityScreen(vm, back = {}, lock = lock, biometrics = bio) }
        compose.onNodeWithText("進入 App 要解鎖").performClick()
        assertTrue(has("輸入目前的密碼"))
        type("1111")
        assertTrue(has("密碼不對"))
        assertTrue(lock.enabled)
        type("2580")
        compose.waitForIdle()
        assertFalse(lock.enabled)
    }

    @Test fun relockChoicesAndNoBiometricsOnThisPhone() {
        val lock = newLock().also { it.setPin("2580") }
        bio.problem = "Android 10 以上才能用指紋或臉部解鎖"
        show { SecurityScreen(vm, back = {}, lock = lock, biometrics = bio) }
        assertTrue(has("Android 10 以上才能用指紋或臉部解鎖"))
        compose.onNodeWithText("5 分鐘").performClick()
        assertEquals(5 * 60_000L, lock.relockAfterMs)
        compose.onNodeWithText("馬上").performClick()
        assertEquals(0L, lock.relockAfterMs)
    }

    @Test fun changingThePin() {
        val lock = newLock().also { it.setPin("2580") }
        show { SecurityScreen(vm, back = {}, lock = lock, biometrics = bio) }
        compose.onNodeWithText("變更密碼或圖形").performClick()
        type("2580")
        compose.waitForIdle()
        compose.onNodeWithText("數字密碼（4～8 位）").performClick()
        assertTrue(has("設定密碼"))
        type("9999")
        compose.onNodeWithText("下一步").performClick()
        type("9999")
        compose.waitForIdle()
        assertEquals(AppLock.Result.Ok, newLock().check("9999"))
    }

    // ================================================================ 圖形

    /** 用手指畫 [nodes]（0～8，左上到右下）：每個點的中心按過去 */
    private fun draw(vararg nodes: Int) {
        compose.onNodeWithContentDescription("畫圖形的地方：3 乘 3 的點").performTouchInput {
            val side = width.toFloat()
            fun at(i: Int) = PatternGrid.center(i, side)
            down(at(nodes.first()))
            nodes.drop(1).forEach { moveTo(at(it)) }
            up()
        }
        compose.waitForIdle()
    }

    @Test fun patternGrid() {
        assertEquals(0, PatternGrid.nodeAt(50f, 50f, 300f))
        assertEquals(4, PatternGrid.nodeAt(150f, 150f, 300f))
        assertEquals(8, PatternGrid.nodeAt(260f, 240f, 300f))
        assertEquals("點和點中間不算", null, PatternGrid.nodeAt(100f, 50f, 300f))
        assertEquals(null, PatternGrid.nodeAt(-5f, 50f, 300f))
        assertEquals("0 連到 2 經過 1", 1, PatternGrid.between(0, 2))
        assertEquals(4, PatternGrid.between(0, 8))
        assertEquals(4, PatternGrid.between(6, 2))
        assertEquals(3, PatternGrid.between(0, 6))
        assertEquals(null, PatternGrid.between(0, 1))
        assertEquals("像馬走日不經過", null, PatternGrid.between(0, 5))
        assertTrue(AppLock.isValidPattern("0124"))
        assertFalse("太短", AppLock.isValidPattern("012"))
        assertFalse("重複", AppLock.isValidPattern("01210"))
        assertFalse(AppLock.isValidPattern("0129"))
    }

    @Test fun patternUnlocks() {
        newLock().setPin("03678", AppLock.Type.PATTERN)
        val lock = newLock()
        bio.succeed = false
        show { LockGate(lock, bio, onForgot = {}, onBack = {}) { Counter() } }
        assertTrue(has("畫出圖形解鎖"))
        draw(0, 1, 2)
        assertTrue(has("至少連 4 個點"))
        draw(0, 1, 2, 5)
        assertTrue(has("圖形不對，還可以試 4 次"))
        assertTrue(has("忘記圖形？"))
        capture("80c_lock_pattern")
        // 0 直接拉到 6 會經過 3
        draw(0, 6, 7, 8)
        assertFalse(lock.locked.value)
        assertTrue(has("按了 0 次"))
    }

    @Test fun settingAPattern() {
        val lock = newLock()
        bio.problem = "這支手機不支援指紋或臉部辨識"
        show { SecurityScreen(vm, back = {}, lock = lock, biometrics = bio) }
        compose.onNodeWithText("進入 App 要解鎖").performClick()
        compose.onNodeWithText("圖形（連 4 個點以上）").performClick()
        assertTrue(has("畫出圖形"))
        draw(4, 5)
        assertTrue(has("至少連 4 個點"))
        draw(0, 4, 8, 5, 2)
        assertTrue(has("再畫一次"))
        draw(0, 4, 8, 5, 1)
        assertTrue(has("兩次不一樣，請重新畫"))
        draw(0, 4, 8, 5, 2)
        draw(0, 4, 8, 5, 2)
        assertTrue(lock.enabled)
        assertEquals(AppLock.Type.PATTERN, lock.type)
        assertFalse("這支手機不能用指紋", lock.biometric)
        assertTrue(has("用圖形解鎖（連 5 個點）"))
        assertEquals(AppLock.Result.Ok, newLock().check("04852"))
    }

    // ================================================================ 字放大（360 dp、1.3 倍）

    @Config(qualifiers = "zh-rTW-w360dp-h800dp-xhdpi", fontScale = 1.3f)
    @Test fun lockWithLargeText() {
        newLock().apply { setPin("2580"); setBiometric(true) }
        val lock = newLock()
        bio.succeed = false
        show { LockGate(lock, bio, onForgot = {}, onBack = {}) { Counter() } }
        capture("80_lock", large = true)
    }

    @Config(qualifiers = "zh-rTW-w360dp-h800dp-xhdpi", fontScale = 1.3f)
    @Test fun newPinDialogWithLargeText() {
        val lock = newLock()
        show { PinDialog(lock, verify = false, onDismiss = {}) {} }
        type("12")
        capture("81b_new_pin", large = true)
    }

    @Config(qualifiers = "zh-rTW-w360dp-h800dp-xhdpi", fontScale = 1.3f)
    @Test fun patternLockWithLargeText() {
        newLock().apply { setPin("03678", AppLock.Type.PATTERN); setBiometric(true) }
        val lock = newLock()
        bio.succeed = false
        show { LockGate(lock, bio, onForgot = {}, onBack = {}) { Counter() } }
        capture("80c_lock_pattern", large = true)
    }
}
