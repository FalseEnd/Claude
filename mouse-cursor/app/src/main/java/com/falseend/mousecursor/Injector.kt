package com.falseend.mousecursor

import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import java.lang.reflect.Method

/**
 * 只在 Shizuku（shell uid）程序裡使用：透過 IInputManager.injectInputEvent 注入事件。
 * shell 有 INJECT_EVENTS 權限，所以不需要 root。
 *
 * 滑鼠模式完全照 InputReader (CursorInputMapper) 產生的事件順序：
 *   放開：BUTTON_RELEASE →（全部放開時）UP → HOVER_MOVE
 *   按下：（第一顆鍵時）DOWN → BUTTON_PRESS
 *   移動：按著鍵 = MOVE，沒按 = HOVER_MOVE；滾輪 = SCROLL
 * 注入的 SOURCE_MOUSE 事件會以游標座標決定送到哪個視窗（MotionEvent.setSource 會自動設定）。
 */
class Injector {
    private val manager: Any
    private val inject: Method
    private val setActionButton: Method? = runCatching {
        MotionEvent::class.java.getMethod("setActionButton", Int::class.javaPrimitiveType)
    }.getOrNull()

    private val mouseDeviceId: Int
    private val touchDeviceId: Int

    var clickMode = MODE_MOUSE

    private var buttons = 0
    private var downTime = 0L
    private var touchDown = false

    init {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, "input") as IBinder
        manager = Class.forName("android.hardware.input.IInputManager\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
        inject = Class.forName("android.hardware.input.IInputManager")
            .getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        mouseDeviceId = findDevice(InputDevice.SOURCE_MOUSE)
        touchDeviceId = findDevice(InputDevice.SOURCE_TOUCHSCREEN)
    }

    private fun findDevice(source: Int): Int = runCatching {
        InputDevice.getDeviceIds().firstOrNull { id ->
            InputDevice.getDevice(id)?.supportsSource(source) == true
        }
    }.getOrNull() ?: 0

    private fun send(ev: InputEvent): Boolean {
        val ok = inject.invoke(manager, ev, MODE_ASYNC) as? Boolean ?: true
        if (ev is MotionEvent) ev.recycle()
        return ok
    }

    /** 測試能否注入（在目前位置送一個 hover），失敗會丟例外 */
    fun probe(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        send(mouse(now, now, MotionEvent.ACTION_HOVER_MOVE, x, y, 0))
    }

    /** 一個 SYN_REPORT 的結果。newButtons 為滑鼠按鍵 bit（1 左 2 右 4 中） */
    fun update(x: Float, y: Float, moved: Boolean, newButtons: Int, wheel: Int) {
        if (clickMode == MODE_TOUCH) { updateTouch(x, y, moved, newButtons); return }
        val now = SystemClock.uptimeMillis()
        val old = buttons
        val released = old and newButtons.inv()
        val pressed = newButtons and old.inv()

        // 放開
        var state = old
        for (b in BITS) if (released and b != 0) {
            state = state and b.inv()
            send(mouse(downTime, now, MotionEvent.ACTION_BUTTON_RELEASE, x, y, toAndroid(state), toAndroid(b)))
        }
        if (old != 0 && state == 0) {
            send(mouse(downTime, now, MotionEvent.ACTION_UP, x, y, 0))
            send(mouse(now, now, MotionEvent.ACTION_HOVER_MOVE, x, y, 0))
        } else if (moved) {
            val action = if (state != 0) MotionEvent.ACTION_MOVE else MotionEvent.ACTION_HOVER_MOVE
            send(mouse(if (state != 0) downTime else now, now, action, x, y, toAndroid(state)))
        }

        // 按下
        if (pressed != 0) {
            if (state == 0) {
                downTime = now
                send(mouse(downTime, now, MotionEvent.ACTION_DOWN, x, y, toAndroid(newButtons)))
            }
            for (b in BITS) if (pressed and b != 0) {
                state = state or b
                send(mouse(downTime, now, MotionEvent.ACTION_BUTTON_PRESS, x, y, toAndroid(state), toAndroid(b)))
            }
        }
        buttons = newButtons

        if (wheel != 0) {
            val ev = mouse(now, now, MotionEvent.ACTION_SCROLL, x, y, toAndroid(newButtons),
                vscroll = wheel.toFloat())
            send(ev)
        }
    }

    /** 觸控模式：左鍵 = 手指按住，其餘按鍵不處理（給只吃觸控的遊戲） */
    private fun updateTouch(x: Float, y: Float, moved: Boolean, newButtons: Int) {
        val now = SystemClock.uptimeMillis()
        val left = newButtons and 1 != 0
        when {
            left && !touchDown -> {
                downTime = now; touchDown = true
                send(touch(downTime, now, MotionEvent.ACTION_DOWN, x, y))
            }
            !left && touchDown -> {
                touchDown = false
                send(touch(downTime, now, MotionEvent.ACTION_UP, x, y))
            }
            left && moved -> send(touch(downTime, now, MotionEvent.ACTION_MOVE, x, y))
        }
        buttons = newButtons
    }

    /** 側鍵：照 InputReader 的 synthesizeButtonKeys 轉成返回 / 前進鍵 */
    fun key(keyCode: Int, down: Boolean) {
        val now = SystemClock.uptimeMillis()
        send(KeyEvent(now, now, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, keyCode, 0,
            0, mouseDeviceId, 0, 0, InputDevice.SOURCE_MOUSE))
    }

    /** 關閉接管時把還按著的鍵放開，避免遊戲以為一直按著 */
    fun releaseAll(x: Float, y: Float) {
        runCatching {
            if (clickMode == MODE_TOUCH) updateTouch(x, y, false, 0) else update(x, y, false, 0, 0)
        }
        buttons = 0; touchDown = false
    }

    private fun mouse(
        down: Long, now: Long, action: Int, x: Float, y: Float, buttonState: Int,
        actionButton: Int = 0, vscroll: Float = 0f,
    ): MotionEvent {
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y
            pressure = if (buttonState != 0) 1f else 0f
            size = 1f
            if (vscroll != 0f) setAxisValue(MotionEvent.AXIS_VSCROLL, vscroll)
        }
        val ev = MotionEvent.obtain(down, now, action, 1, arrayOf(props), arrayOf(coords),
            0, buttonState, 1f, 1f, mouseDeviceId, 0, InputDevice.SOURCE_MOUSE, 0)
        if (actionButton != 0) setActionButton?.invoke(ev, actionButton)
        return ev
    }

    private fun touch(down: Long, now: Long, action: Int, x: Float, y: Float): MotionEvent {
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y; pressure = 1f; size = 0.05f
        }
        return MotionEvent.obtain(down, now, action, 1, arrayOf(props), arrayOf(coords),
            0, 0, 1f, 1f, touchDeviceId, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    private fun toAndroid(bits: Int): Int =
        (if (bits and 1 != 0) MotionEvent.BUTTON_PRIMARY else 0) or
            (if (bits and 2 != 0) MotionEvent.BUTTON_SECONDARY else 0) or
            (if (bits and 4 != 0) MotionEvent.BUTTON_TERTIARY else 0)

    companion object {
        const val MODE_MOUSE = 0
        const val MODE_TOUCH = 1
        private const val MODE_ASYNC = 0
        private val BITS = intArrayOf(1, 2, 4)
    }
}
