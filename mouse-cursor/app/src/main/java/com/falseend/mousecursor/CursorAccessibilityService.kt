package com.falseend.mousecursor

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * 無障礙服務，兩個用途：
 * 1. 提供 TYPE_ACCESSIBILITY_OVERLAY 視窗：永遠在最上層、100% 不透明、不會擋到點擊。
 * 2.（實驗）Android 14+ 的「觀察」模式：系統把滑鼠事件複製一份給本服務、原事件照常送給遊戲，
 *    這樣拿到的是系統算好的「真實游標座標」，完全不會飄。此 API 在 SDK 中是隱藏的，
 *    不是每台手機都支援，所以必須先通過 App 內的測試才會常駐開啟。
 */
class CursorAccessibilityService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private var lastButtonState = 0
    private var trackingOn = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        CursorHub.init(this)
        CursorHub.onHostChanged()
        if (Prefs(this).a11yTracking) {
            if (setTracking(true)) CursorHub.acquire(TAG_TRACK)
        }
    }

    override fun onMotionEvent(event: MotionEvent) {
        if (!event.isFromSource(InputDevice.SOURCE_MOUSE)) return
        CursorHub.lastA11yEventMs = SystemClock.uptimeMillis()
        CursorHub.a11yEventCount++
        val pressed = event.buttonState and lastButtonState.inv()
        lastButtonState = event.buttonState
        val p = (if (pressed and MotionEvent.BUTTON_PRIMARY != 0) 1 else 0) or
            (if (pressed and MotionEvent.BUTTON_SECONDARY != 0) 2 else 0) or
            (if (pressed and MotionEvent.BUTTON_TERTIARY != 0) 4 else 0)
        CursorHub.setAbsolute(event.rawX, event.rawY, "無障礙", p)
    }

    /**
     * 開關「觀察滑鼠」。只有在隱藏 API setObservedMotionEventSources 存在時才會設定
     * motionEventSources —— 否則滑鼠事件會被本服務吃掉、遊戲收不到。
     */
    fun setTracking(enable: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        val info = serviceInfo ?: return false
        return try {
            if (enable) {
                HiddenApiBypass.addHiddenApiExemptions("Landroid/accessibilityservice/")
                val m = AccessibilityServiceInfo::class.java.getMethod(
                    "setObservedMotionEventSources", Int::class.javaPrimitiveType)
                m.invoke(info, InputDevice.SOURCE_MOUSE)
                info.motionEventSources = InputDevice.SOURCE_MOUSE
            } else {
                info.motionEventSources = 0
                runCatching {
                    AccessibilityServiceInfo::class.java.getMethod(
                        "setObservedMotionEventSources", Int::class.javaPrimitiveType)
                        .invoke(info, 0)
                }
            }
            serviceInfo = info
            trackingOn = enable
            true
        } catch (e: Throwable) {
            Log.w(TAG, "此系統不支援觀察模式: $e")
            if (enable) runCatching { info.motionEventSources = 0; serviceInfo = info }
            trackingOn = false
            false
        }
    }

    /** App 內測試：開啟 [seconds] 秒後自動關閉，除非 [confirmTest] 被呼叫 */
    fun startTest(seconds: Long): Boolean {
        val ok = setTracking(true)
        if (ok) {
            CursorHub.acquire(TAG_TRACK)
            main.removeCallbacks(revert)
            main.postDelayed(revert, seconds * 1000)
        }
        return ok
    }

    fun confirmTest() {
        main.removeCallbacks(revert)
        Prefs(this).a11yTracking = true
    }

    fun stopTracking() {
        main.removeCallbacks(revert)
        Prefs(this).a11yTracking = false
        setTracking(false)
        CursorHub.release(TAG_TRACK)
    }

    private val revert = Runnable { stopTracking() }

    val isTracking: Boolean get() = trackingOn

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        CursorHub.release(TAG_TRACK)
        CursorHub.onHostChanged()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CursorA11y"
        const val TAG_TRACK = "a11y-track"
        @Volatile var instance: CursorAccessibilityService? = null
    }
}
