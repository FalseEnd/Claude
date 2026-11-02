package com.falseend.mousecursor

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Choreographer
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * 全域游標狀態與懸浮窗。
 * - 位置來源 1：Shizuku 讀到的原始位移 [onRawMotion]（經 [PointerModel] 換算）
 * - 位置來源 2：真實座標 [setAbsolute]（在本 App 畫面內時用 hover 事件同步）
 * - 螢幕旋轉時照 Android MouseCursorController 的做法，把游標換算到同一個實體位置
 *   （遊戲橫向、桌面直向，切換 App 時一定會旋轉）
 * - 繪製：優先用無障礙服務的 TYPE_ACCESSIBILITY_OVERLAY（100% 不透明、被系統視為可信任視窗）；
 *   沒開無障礙時退回 TYPE_APPLICATION_OVERLAY，並把透明度壓在 0.8 以下，
 *   否則 Android 12+ 會擋掉穿過懸浮窗的點擊 (untrusted touch)。
 */
object CursorHub {
    private const val TAG = "CursorHub"
    private val main = Handler(Looper.getMainLooper())

    private lateinit var app: Context
    private lateinit var prefs: Prefs
    val model = PointerModel()

    private val users = mutableSetOf<String>()
    private var root: FrameLayout? = null
    private var cursor: CursorView? = null
    private var windowManager: WindowManager? = null
    private var hostIsA11y = false

    private val lock = Any()
    @Volatile private var x = 0f
    @Volatile private var y = 0f
    @Volatile private var screenW = 1080f
    @Volatile private var screenH = 2400f
    private var rotation = 0
    private var originX = 0
    private var originY = 0
    private var lastButtons = 0
    @Volatile private var framePending = false
    @Volatile private var pendingClick = 0

    /** 接管模式下由 Shizuku 服務負責位置與注入；這裡只負責把設定/旋轉同步過去 */
    interface Controller {
        fun setScreen(w: Int, h: Int)
        fun setPosition(x: Float, y: Float)
        fun setMotion(type: Int, systemSpeed: Int, sensitivity: Float)
    }
    @Volatile var controller: Controller? = null
    @Volatile var takeoverActive = false

    /** 校準用：原始位移 (counts) 與核心時間戳 */
    @Volatile var rawListener: ((Int, Int, Long) -> Unit)? = null
    /** UI 顯示用：最近事件來源 */
    @Volatile var lastSource = ""
    @Volatile var shizukuEventCount = 0L

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        prefs = Prefs(app)
        reloadSettings()
        initDisplay()
        x = screenW / 2; y = screenH / 2
        app.getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, main)
    }

    fun reloadSettings() {
        model.sensitivity = prefs.sensitivity100 / 100f
        model.systemSpeed = systemPointerSpeed()
        model.type = resolveModelType()
        pushMotion()
        main.post { applyStyle() }
    }

    fun pushMotion() {
        controller?.setMotion(model.type.ordinal, model.systemSpeed, model.sensitivity)
    }

    /** 把目前位置與螢幕大小交給服務（開啟接管前、旋轉後） */
    fun pushState() {
        val c = controller ?: return
        c.setScreen(screenW.toInt(), screenH.toInt())
        c.setPosition(x, y)
    }

    fun systemPointerSpeed(): Int = runCatching {
        Settings.System.getInt(app.contentResolver, "pointer_speed")
    }.getOrDefault(0).coerceIn(-7, 7)

    /** Android 16 的「游標加速」開關 */
    fun systemAccelEnabled(): Boolean = runCatching {
        Settings.System.getInt(app.contentResolver, "mouse_pointer_acceleration_enabled", 1) != 0
    }.getOrDefault(true)

    fun resolveModelType(): PointerModel.Type {
        val t = prefs.modelType
        return if (t in PointerModel.Type.entries.indices) PointerModel.Type.entries[t]
        else PointerModel.defaultType(systemAccelEnabled())
    }

    // ------------------------------------------------------------ 生命週期

    fun acquire(tag: String) = main.post {
        users += tag
        ensureOverlay()
    }

    fun release(tag: String) = main.post {
        users -= tag
        if (users.isEmpty()) removeOverlay()
    }

    val isShowing: Boolean get() = root != null

    /** 無障礙服務連線 / 斷線時呼叫，換一個更好的視窗類型 */
    fun onHostChanged() = main.post {
        if (root != null) {
            removeOverlay(); ensureOverlay()
        }
    }

    private fun ensureOverlay() {
        if (root != null || users.isEmpty()) return
        val a11y = CursorAccessibilityService.instance
        val host: Context = a11y ?: app
        if (a11y == null && !Settings.canDrawOverlays(app)) {
            Log.w(TAG, "沒有懸浮窗權限，也沒開無障礙，無法顯示")
            return
        }
        hostIsA11y = a11y != null
        val wm = host.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val type = if (hostIsA11y) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.title = "MouseCursorOverlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) lp.fitInsetsTypes = 0
        if (!hostIsA11y && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val im = app.getSystemService(InputManager::class.java)
            lp.alpha = minOf(0.8f, im.maximumObscuringOpacityForTouch)
        }

        val frame = FrameLayout(host)
        val cv = CursorView(host)
        frame.addView(cv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        frame.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            originX = loc[0]; originY = loc[1]
            onDisplayMaybeChanged()
            render()
        }
        try {
            wm.addView(frame, lp)
        } catch (e: Exception) {
            Log.e(TAG, "addView 失敗", e)
            return
        }
        windowManager = wm
        root = frame
        cursor = cv
        applyStyle()
        render()
        bumpIdle()
    }

    private fun removeOverlay() {
        val r = root ?: return
        runCatching { windowManager?.removeViewImmediate(r) }
        root = null; cursor = null; windowManager = null
        main.removeCallbacks(idleHide)
    }

    fun overlayDescription(): String = when {
        root == null -> "未顯示"
        hostIsA11y -> "無障礙懸浮層（100% 不透明）"
        else -> "一般懸浮窗（透明度 80%，Android 限制）"
    }

    // ------------------------------------------------------------ 位置

    private fun display(): Display =
        app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)

    /** 同時讀方向與尺寸；前後兩次方向一致才算一致的快照 */
    @Suppress("DEPRECATION")
    private fun snapshot(): Triple<Int, Float, Float> {
        val d = display()
        val p = Point()
        var r = d.rotation
        repeat(3) {
            d.getRealSize(p)
            val r2 = d.rotation
            if (r2 == r) return Triple(r, p.x.toFloat(), p.y.toFloat())
            r = r2
        }
        return Triple(r, p.x.toFloat(), p.y.toFloat())
    }

    private fun odd(rot: Int) = rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270

    /** 自然方向（未旋轉）的螢幕尺寸 */
    private var naturalW = 0f
    private var naturalH = 0f

    private fun initDisplay() {
        val (r, w, h) = snapshot()
        rotation = r
        screenW = w; screenH = h
        if (odd(r)) { naturalW = h; naturalH = w } else { naturalW = w; naturalH = h }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) { onDisplayMaybeChanged(); render() }
        }
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
    }

    /**
     * 與 frameworks/base/libs/input/MouseCursorController.cpp setDisplayViewport() 相同：
     * - 未旋轉的實體尺寸變了（摺疊機展開等）→ 系統會把游標放回中央
     * - 只有方向變了 → 先還原舊旋轉回到「自然方向」座標，再套用新旋轉
     */
    private fun onDisplayMaybeChanged() {
        val (newRot, w, h) = snapshot()
        val nw = if (odd(newRot)) h else w
        val nh = if (odd(newRot)) w else h
        synchronized(lock) {
            val sameNatural = kotlin.math.abs(nw - naturalW) <= 1 && kotlin.math.abs(nh - naturalH) <= 1
            if (!sameNatural) {
                // 剛好長寬對調 = 方向與尺寸還沒同步更新的過渡讀值，等下一次回呼
                if (kotlin.math.abs(nw - naturalH) <= 1 && kotlin.math.abs(nh - naturalW) <= 1) return
                naturalW = nw; naturalH = nh
                rotation = newRot
                screenW = w; screenH = h
                x = w / 2; y = h / 2
                model.reset()
                pushState()
                return
            }
            if (newRot != rotation) {
                val oldW = if (odd(rotation)) naturalH else naturalW
                val oldH = if (odd(rotation)) naturalW else naturalH
                val (rx, ry) = Geometry.rotate(x, y, oldW, oldH, rotation, w, h, newRot)
                x = rx; y = ry
                rotation = newRot
                model.reset()
            }
            screenW = w; screenH = h
            x = x.coerceIn(0f, screenW - 1)
            y = y.coerceIn(0f, screenH - 1)
        }
        pushState()
    }

    /** Shizuku 執行緒呼叫 */
    fun onRawMotion(dx: Int, dy: Int, buttons: Int, timeUs: Long) {
        shizukuEventCount++
        if (dx != 0 || dy != 0) {
            rawListener?.invoke(dx, dy, timeUs)
            synchronized(lock) {
                val (mx, my) = model.transform(dx, dy, timeUs)
                // 撞到螢幕邊緣時自然被夾住，和系統游標一樣，等於自動重新對齊
                x = (x + mx).coerceIn(0f, screenW - 1)
                y = (y + my).coerceIn(0f, screenH - 1)
            }
            lastSource = "Shizuku"
        }
        val pressed = buttons and lastButtons.inv()
        lastButtons = buttons
        if (pressed != 0) pendingClick = pressed
        scheduleFrame()
    }

    /** 接管模式：服務算好的位置（= 實際注入點擊的位置） */
    fun onServicePosition(px: Float, py: Float, pressed: Int) {
        synchronized(lock) { x = px; y = py }
        lastSource = "接管"
        if (pressed != 0) pendingClick = pressed
        scheduleFrame()
    }

    /** 真實座標（App 內 hover） */
    fun setAbsolute(ax: Float, ay: Float, source: String, buttonsPressed: Int = 0) {
        synchronized(lock) {
            x = ax.coerceIn(0f, screenW - 1); y = ay.coerceIn(0f, screenH - 1)
        }
        lastSource = source
        if (buttonsPressed != 0) pendingClick = buttonsPressed
        scheduleFrame()
    }

    fun recenter() {
        setAbsolute(screenW / 2, screenH / 2, "重設")
        controller?.setPosition(screenW / 2, screenH / 2)
    }

    private val frameCallback = Choreographer.FrameCallback {
        framePending = false
        render()
        val c = pendingClick
        if (c != 0) {
            pendingClick = 0
            if (prefs.clickEffect) cursor?.click(c)
        }
        bumpIdle()
    }

    private fun scheduleFrame() {
        if (framePending) return
        framePending = true
        main.post { Choreographer.getInstance().postFrameCallback(frameCallback) }
    }

    private fun render() {
        val cv = cursor ?: return
        cv.translationX = x - originX - cv.pad
        cv.translationY = y - originY - cv.pad
    }

    private val idleHide = Runnable { cursor?.animate()?.alpha(0f)?.setDuration(250)?.start() }

    private fun bumpIdle() {
        val cv = cursor ?: return
        if (cv.alpha < 1f) { cv.animate().cancel(); cv.alpha = 1f }
        main.removeCallbacks(idleHide)
        val sec = prefs.idleHideSec
        if (sec > 0) main.postDelayed(idleHide, sec * 1000L)
    }

    private fun applyStyle() {
        val cv = cursor ?: return
        val (fill, stroke) = Prefs.COLORS[prefs.colorIndex.coerceIn(0, Prefs.COLORS.lastIndex)].second
        cv.fillColor = fill
        cv.strokeColor = stroke
        cv.cursorPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, prefs.sizeDp.toFloat(), app.resources.displayMetrics)
        cv.visibility = View.VISIBLE
        render()
        bumpIdle()
    }
}
