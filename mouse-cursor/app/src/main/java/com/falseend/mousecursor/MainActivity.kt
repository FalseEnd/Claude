package com.falseend.mousecursor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import kotlin.math.hypot

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())

    private lateinit var tvStatus: TextView
    private lateinit var tvPad: TextView
    private lateinit var btnStart: Button
    private lateinit var btnTrackTest: Button
    private lateinit var tvSens: TextView
    private lateinit var seekSens: SeekBar

    // 校準 / 測試狀態
    private var calibrating = false
    private var realPath = 0.0
    private var modelPath = 0.0
    private var lastRealX = Float.NaN
    private var lastRealY = Float.NaN
    private var testing = false
    private var appHoverCount = 0
    private var a11yCountAtTestStart = 0L

    private val shizukuPermListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) toast("Shizuku 已授權")
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        CursorHub.init(this)

        tvStatus = findViewById(R.id.status)
        tvPad = findViewById(R.id.pad)
        btnStart = findViewById(R.id.btnStart)
        btnTrackTest = findViewById(R.id.btnTrackTest)
        tvSens = findViewById(R.id.sensLabel)
        seekSens = findViewById(R.id.sens)

        Shizuku.addRequestPermissionResultListener(shizukuPermListener)

        findViewById<Button>(R.id.btnShizuku).setOnClickListener { requestShizuku() }
        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.btnA11y).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btnStart.setOnClickListener {
            if (CursorService.running) CursorService.stop(this) else startCursor()
            main.postDelayed({ refresh() }, 300)
        }
        findViewById<Button>(R.id.btnRecenter).setOnClickListener { CursorHub.recenter() }
        findViewById<Button>(R.id.btnCalibrate).setOnClickListener { startCalibration() }
        btnTrackTest.setOnClickListener { toggleTracking() }

        setupStyle()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() {
        super.onResume()
        CursorHub.reloadSettings()
        refresh()
        main.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermListener)
        CursorHub.modelDeltaListener = null
        super.onDestroy()
    }

    private val ticker = object : Runnable {
        override fun run() {
            refresh(); main.postDelayed(this, 1000)
        }
    }

    // ------------------------------------------------------------ 權限 / 啟動

    private fun shizukuState(): String = try {
        when {
            !Shizuku.pingBinder() -> "未執行（請先開啟 Shizuku App 並啟動）"
            Shizuku.isPreV11() -> "版本太舊，請更新 Shizuku"
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> "已授權 ✔"
            else -> "已執行，尚未授權"
        }
    } catch (e: Throwable) {
        "錯誤：$e"
    }

    private fun requestShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                toast("請先安裝並啟動 Shizuku")
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://shizuku.rikka.app/download/")))
                }
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                toast("已經授權過了"); return
            }
            Shizuku.requestPermission(100)
        } catch (e: Throwable) {
            toast("Shizuku 錯誤：$e")
        }
    }

    private fun startCursor() {
        val hasA11y = CursorAccessibilityService.instance != null
        if (!hasA11y && !Settings.canDrawOverlays(this)) {
            toast("請先允許「懸浮窗」權限，或開啟本 App 的無障礙服務"); return
        }
        val ok = runCatching {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!ok) { toast("請先啟動並授權 Shizuku"); return }
        CursorService.start(this)
    }

    private fun refresh() {
        val a11y = CursorAccessibilityService.instance
        val sb = StringBuilder()
        sb.append("Shizuku：").append(shizukuState()).append('\n')
        sb.append("懸浮窗權限：").append(if (Settings.canDrawOverlays(this)) "已允許 ✔" else "未允許").append('\n')
        sb.append("無障礙服務：").append(if (a11y != null) "已開啟 ✔" else "未開啟（選用，開了游標 100% 不透明）").append('\n')
        sb.append("直接追蹤：").append(when {
            a11y?.isTracking == true -> "開啟中（收到 ${CursorHub.a11yEventCount} 筆）"
            Build.VERSION.SDK_INT < 34 -> "需要 Android 14 以上"
            else -> "關閉"
        }).append('\n')
        sb.append("游標服務：").append(CursorService.status).append('\n')
        sb.append("顯示方式：").append(CursorHub.overlayDescription()).append('\n')
        if (CursorService.devices.isNotEmpty()) sb.append("滑鼠：\n").append(CursorService.devices)
        tvStatus.text = sb.toString()
        btnStart.text = if (CursorService.running) "停止游標" else "啟動游標（Shizuku）"
        btnTrackTest.text = if (a11y?.isTracking == true) "關閉直接追蹤" else "測試直接追蹤（Android 14+ 實驗）"
    }

    // ------------------------------------------------------------ 外觀設定

    private fun setupStyle() {
        val size = findViewById<SeekBar>(R.id.size)
        val sizeLabel = findViewById<TextView>(R.id.sizeLabel)
        size.max = 64 - 12
        size.progress = prefs.sizeDp - 12
        sizeLabel.text = "游標大小：${prefs.sizeDp} dp"
        size.setOnSeekBarChangeListener(simpleSeek {
            prefs.sizeDp = it + 12
            sizeLabel.text = "游標大小：${prefs.sizeDp} dp"
            CursorHub.reloadSettings()
        })

        val color = findViewById<Spinner>(R.id.color)
        color.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            Prefs.COLORS.map { it.first })
        color.setSelection(prefs.colorIndex)
        color.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                prefs.colorIndex = pos; CursorHub.reloadSettings()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        seekSens.max = 400 - 10
        seekSens.progress = prefs.sensitivity100 - 10
        updateSensLabel()
        seekSens.setOnSeekBarChangeListener(simpleSeek {
            prefs.sensitivity100 = it + 10
            updateSensLabel()
            CursorHub.reloadSettings()
        })

        val accel = findViewById<CheckBox>(R.id.accel)
        accel.isChecked = prefs.acceleration
        accel.setOnCheckedChangeListener { _, b -> prefs.acceleration = b; CursorHub.reloadSettings() }

        val click = findViewById<CheckBox>(R.id.click)
        click.isChecked = prefs.clickEffect
        click.setOnCheckedChangeListener { _, b -> prefs.clickEffect = b }

        val idle = findViewById<SeekBar>(R.id.idle)
        val idleLabel = findViewById<TextView>(R.id.idleLabel)
        fun idleText() = if (prefs.idleHideSec == 0) "閒置隱藏：不隱藏" else "閒置隱藏：${prefs.idleHideSec} 秒"
        idle.max = 10
        idle.progress = prefs.idleHideSec
        idleLabel.text = idleText()
        idle.setOnSeekBarChangeListener(simpleSeek {
            prefs.idleHideSec = it; idleLabel.text = idleText(); CursorHub.reloadSettings()
        })
    }

    private fun updateSensLabel() {
        tvSens.text = "靈敏度：×%.2f".format(prefs.sensitivity100 / 100f)
    }

    private fun simpleSeek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }

    // ------------------------------------------------------------ 校準

    /**
     * 在本 App 畫面裡，系統游標是看得到的，hover 事件會告訴我們「真正」的位置。
     * 比較真實移動距離與模型換算距離，自動算出靈敏度倍率。
     */
    private fun startCalibration() {
        if (!CursorService.running) { toast("請先啟動游標（Shizuku）"); return }
        calibrating = true
        realPath = 0.0; modelPath = 0.0
        lastRealX = Float.NaN
        CursorHub.modelDeltaListener = { dx, dy -> modelPath += hypot(dx.toDouble(), dy.toDouble()) }
        tvPad.text = "校準中：請在這個畫面上用正常速度大範圍畫圈移動滑鼠 6 秒…"
        main.postDelayed({ finishCalibration() }, 6000)
    }

    private fun finishCalibration() {
        calibrating = false
        CursorHub.modelDeltaListener = null
        if (modelPath < 200 || realPath < 200) {
            tvPad.text = "校準失敗：移動量太少（真實 ${realPath.toInt()} px / 模型 ${modelPath.toInt()} px）"
            return
        }
        val ratio = realPath / modelPath
        val newSens = (prefs.sensitivity100 * ratio).toInt().coerceIn(10, 400)
        prefs.sensitivity100 = newSens
        seekSens.progress = newSens - 10
        updateSensLabel()
        CursorHub.reloadSettings()
        tvPad.text = "校準完成：比例 %.2f，靈敏度設為 ×%.2f\n（可以再做一次讓它更準）"
            .format(ratio, newSens / 100f)
    }

    // ------------------------------------------------------------ 直接追蹤測試

    private fun toggleTracking() {
        val a11y = CursorAccessibilityService.instance
        if (a11y == null) {
            toast("請先在無障礙設定中開啟「滑鼠游標」服務")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return
        }
        if (a11y.isTracking) { a11y.stopTracking(); refresh(); return }
        if (Build.VERSION.SDK_INT < 34) { toast("需要 Android 14 以上"); return }
        appHoverCount = 0
        a11yCountAtTestStart = CursorHub.a11yEventCount
        if (!a11y.startTest(10)) { toast("此系統不支援直接追蹤，請使用 Shizuku 模式"); return }
        testing = true
        tvPad.text = "測試中：請在這個畫面上移動滑鼠 5 秒…\n（若滑鼠突然不能用，10 秒後會自動還原）"
        main.postDelayed({ finishTrackingTest() }, 5000)
        refresh()
    }

    private fun finishTrackingTest() {
        testing = false
        val a11y = CursorAccessibilityService.instance ?: return
        val got = CursorHub.a11yEventCount - a11yCountAtTestStart
        if (got > 20 && appHoverCount > 20) {
            a11y.confirmTest()
            tvPad.text = "✔ 直接追蹤可用！游標位置將完全精準（不需要 Shizuku 也能用）。"
        } else {
            a11y.stopTracking()
            tvPad.text = "✘ 此手機不支援直接追蹤（服務收到 $got 筆、App 收到 $appHoverCount 筆）。\n請改用 Shizuku 模式。"
        }
        refresh()
    }

    // ------------------------------------------------------------ 本畫面的真實滑鼠

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.isFromSource(InputDevice.SOURCE_MOUSE) &&
            (ev.actionMasked == MotionEvent.ACTION_HOVER_MOVE || ev.actionMasked == MotionEvent.ACTION_HOVER_ENTER)
        ) {
            if (testing) appHoverCount++
            val rx = ev.rawX; val ry = ev.rawY
            if (calibrating) {
                if (!lastRealX.isNaN()) realPath += hypot((rx - lastRealX).toDouble(), (ry - lastRealY).toDouble())
                lastRealX = rx; lastRealY = ry
            }
            // 在本 App 內時，把懸浮游標對齊到真實游標
            if (CursorService.running) CursorHub.setAbsolute(rx, ry, "App 同步")
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
