package com.falseend.mousecursor

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * 全域游標狀態與懸浮窗。
 * - 位置來源 1：Shizuku 讀到的原始位移 [onRawMotion]（經 [PointerModel] 換算）
 * - 位置來源 2：真實座標 [setAbsolute]（無障礙直接追蹤 / 本 App 畫面內的 hover，會順便同步）
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
    private var originX = 0
    private var originY = 0
    private var lastButtons = 0
    @Volatile private var framePending = false
    @Volatile private var pendingClick = 0

    /** 校準用：每次經模型換算後的位移 (px) */
    @Volatile var modelDeltaListener: ((Float, Float) -> Unit)? = null
    /** UI 顯示用：最近事件來源 */
    @Volatile var lastSource = ""
    @Volatile var shizukuEventCount = 0L
    @Volatile var a11yEventCount = 0L

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        prefs = Prefs(app)
        reloadSettings()
        updateScreenSize()
        x = screenW / 2; y = screenH / 2
    }

    fun reloadSettings() {
        model.sensitivity = prefs.sensitivity100 / 100f
        model.acceleration = prefs.acceleration
        model.systemSpeed = runCatching {
            Settings.System.getInt(app.contentResolver, "pointer_speed")
        }.getOrDefault(0).coerceIn(-7, 7)
        main.post { applyStyle() }
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
            updateScreenSize()
            clamp()
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

    @Suppress("DEPRECATION")
    private fun updateScreenSize() {
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            screenW = b.width().toFloat(); screenH = b.height().toFloat()
        } else {
            val dm = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            screenW = dm.widthPixels.toFloat(); screenH = dm.heightPixels.toFloat()
        }
    }

    private fun clamp() {
        synchronized(lock) {
            x = x.coerceIn(0f, screenW - 1)
            y = y.coerceIn(0f, screenH - 1)
        }
    }

    /** Shizuku 執行緒呼叫 */
    fun onRawMotion(dx: Int, dy: Int, buttons: Int, timeUs: Long) {
        shizukuEventCount++
        if (dx != 0 || dy != 0) {
            val (mx, my) = model.transform(dx, dy, timeUs)
            modelDeltaListener?.invoke(mx, my)
            if (!a11yTrackingLive()) {
                synchronized(lock) {
                    // 撞到螢幕邊緣時自然被夾住，等於自動重新對齊
                    x = (x + mx).coerceIn(0f, screenW - 1)
                    y = (y + my).coerceIn(0f, screenH - 1)
                }
                lastSource = "Shizuku"
            }
        }
        val pressed = buttons and lastButtons.inv()
        lastButtons = buttons
        if (pressed != 0) pendingClick = pressed
        scheduleFrame()
    }

    /** 真實座標（無障礙追蹤或 App 內 hover） */
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
    }

    /** 無障礙追蹤最近 300ms 內有收到事件時，以它為準（精準） */
    @Volatile var lastA11yEventMs = 0L
    private fun a11yTrackingLive() =
        android.os.SystemClock.uptimeMillis() - lastA11yEventMs < 300

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
