package tw.xiaoliangkou.app

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tw.xiaoliangkou.app.data.AppLock
import tw.xiaoliangkou.app.data.AppLock.Result

/** App 鎖：密碼怎麼存、錯太多次要等、離開多久再鎖 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLockTest {
    private var now = 1_000_000L
    private val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("lock_test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
    private fun lock() = AppLock(prefs, clock = { now }, iterations = 1_000)

    @Test fun offUntilAPinIsSet() {
        val l = lock()
        assertFalse(l.enabled)
        assertFalse(l.locked.value)
        assertEquals(Result.Ok, l.check("0000"))
        l.setPin("2580")
        assertTrue(l.enabled)
        assertEquals(4, l.pinLength)
        assertFalse("剛設定好不用馬上解鎖", l.locked.value)
        // 下次打開 App 是鎖著的
        assertTrue(lock().locked.value)
    }

    @Test fun pinIsNeverStoredAsIs() {
        lock().setPin("13579246")
        val stored = prefs.all.values.joinToString(" ")
        assertFalse(stored, stored.contains("13579246"))
        // 同樣的密碼每次鹽不一樣
        val first = prefs.getString("hash", null)
        lock().setPin("13579246")
        assertTrue(first != prefs.getString("hash", null))
    }

    @Test fun validPins() {
        assertTrue(AppLock.isValidPin("1234"))
        assertTrue(AppLock.isValidPin("12345678"))
        assertFalse(AppLock.isValidPin("123"))
        assertFalse(AppLock.isValidPin("123456789"))
        assertFalse(AppLock.isValidPin("12a4"))
        assertFalse(AppLock.isValidPin("１２３４"))
    }

    @Test fun rightPinUnlocks() {
        lock().setPin("2580")
        val l = lock()
        assertTrue(l.locked.value)
        assertEquals(Result.Wrong(4), l.check("0000"))
        assertTrue(l.locked.value)
        assertEquals(Result.Ok, l.check("2580"))
        assertFalse(l.locked.value)
    }

    @Test fun tooManyWrongTriesWaitLongerEachTime() {
        lock().setPin("2580")
        val l = lock()
        repeat(4) { assertEquals(Result.Wrong(4 - it), l.check("1111")) }
        assertEquals(Result.Wait(30), l.check("1111"))
        assertEquals("等的時候對的也不行", Result.Wait(30), l.check("2580"))
        assertTrue(l.locked.value)
        now += 29_000
        assertEquals(1L, l.waitSeconds())
        now += 1_000
        assertEquals(0L, l.waitSeconds())
        repeat(4) { l.check("1111") }
        assertEquals("第二輪等 1 分鐘", Result.Wait(60), l.check("1111"))
        now += 60_000
        // 重新打開 App 也一樣記得錯了幾次
        val again = lock()
        repeat(4) { again.check("1111") }
        assertEquals(Result.Wait(120), again.check("1111"))
        now += 120_000
        assertEquals(Result.Ok, again.check("2580"))
        // 對了就重新算
        assertEquals(Result.Wrong(4), again.check("1111"))
    }

    @Test fun relocksAfterBeingAwayLongEnough() {
        val l = lock()
        l.setPin("2580")
        assertEquals(AppLock.DEFAULT_RELOCK_MS, l.relockAfterMs)
        l.onBackground()
        now += 59_000
        l.onForeground()
        assertFalse("離開不到 1 分鐘", l.locked.value)
        l.onBackground()
        now += 60_000
        l.onForeground()
        assertTrue(l.locked.value)
        l.check("2580")
        l.setRelockAfter(0)
        l.onBackground()
        l.onForeground()
        assertTrue("選「馬上」", l.locked.value)
        // 沒有到背景就不會鎖
        l.check("2580")
        l.onForeground()
        assertFalse(l.locked.value)
    }

    @Test fun biometricAndDisable() {
        val l = lock()
        l.setBiometric(true)
        assertFalse("沒有密碼就沒有 App 鎖", l.biometric)
        l.setPin("2580")
        l.setBiometric(true)
        assertTrue(l.biometric)
        lock().also { it.unlockWithBiometric(); assertFalse(it.locked.value) }
        l.disable()
        assertFalse(l.enabled)
        assertFalse(l.biometric)
        assertFalse(l.locked.value)
        assertFalse(lock().locked.value)
    }
}
