package com.falseend.mousecursor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Point
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())

    private lateinit var tvStatus: TextView
    private lateinit var tvPad: TextView
    private lateinit var btnStart: Button
    private lateinit var tvSens: TextView
    private lateinit var seekSens: SeekBar
    private lateinit var spModel: Spinner

    private var calibration: Calibration? = null

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
        tvSens = findViewById(R.id.sensLabel)
        seekSens = findViewById(R.id.sens)
        spModel = findViewById(R.id.model)

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
        CursorHub.rawListener = null
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
        sb.append("游標服務：").append(CursorService.status).append('\n')
        if (CursorService.running && prefs.takeover && !CursorHub.takeoverActive &&
            CursorService.takeoverError.isNotEmpty()
        ) sb.append("接管失敗原因：").append(CursorService.takeoverError).append('\n')
        sb.append("顯示方式：").append(CursorHub.overlayDescription()).append('\n')
        sb.append("加速曲線：").append(Prefs.MODEL_NAMES[CursorHub.resolveModelType().ordinal + 1])
            .append("，系統游標速度 ").append(CursorHub.systemPointerSpeed()).append('\n')
        if (CursorService.devices.isNotEmpty()) sb.append("滑鼠：\n").append(CursorService.devices)
        tvStatus.text = sb.toString()
        btnStart.text = if (CursorService.running) "停止游標" else "啟動游標（Shizuku）"
    }

    // ------------------------------------------------------------ 設定

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
        color.onItemSelectedListener = itemSelected { prefs.colorIndex = it; CursorHub.reloadSettings() }

        spModel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Prefs.MODEL_NAMES)
        spModel.setSelection(prefs.modelType + 1)
        spModel.onItemSelectedListener = itemSelected {
            if (prefs.modelType != it - 1) {
                prefs.modelType = it - 1; CursorHub.reloadSettings(); refresh()
            }
        }

        seekSens.max = 400 - 10
        seekSens.progress = prefs.sensitivity100 - 10
        updateSensLabel()
        seekSens.setOnSeekBarChangeListener(simpleSeek {
            prefs.sensitivity100 = it + 10
            updateSensLabel()
            CursorHub.reloadSettings()
        })

        val takeover = findViewById<CheckBox>(R.id.takeover)
        takeover.isChecked = prefs.takeover
        takeover.setOnCheckedChangeListener { _, b ->
            prefs.takeover = b
            CursorService.applyTakeover(this)
            main.postDelayed({ refresh() }, 1800)
        }
        val clickMode = findViewById<Spinner>(R.id.clickMode)
        clickMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Prefs.CLICK_MODES)
        clickMode.setSelection(prefs.clickMode)
        clickMode.onItemSelectedListener = itemSelected {
            if (prefs.clickMode != it) { prefs.clickMode = it; CursorService.applyTakeover(this) }
        }

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
        tvSens.text = "靈敏度微調：×%.2f".format(prefs.sensitivity100 / 100f)
    }

    private fun simpleSeek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }

    private fun itemSelected(f: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) = f(pos)
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }

    // ------------------------------------------------------------ 校準

    /**
     * 在本 App 畫面裡系統游標看得到，hover 事件給的就是「真正」的位置。
     * 把同一段時間的原始位移同時餵給三種加速模型，每 120ms 比較一次位移，
     * 以最小平方法求出每個模型的倍率，挑誤差最小的那個。
     */
    private fun startCalibration() {
        if (!CursorService.running) { toast("請先啟動游標（Shizuku）"); return }
        if (CursorHub.takeoverActive) {
            toast("接管模式下游標就是點擊位置，不需要校準；直接調整靈敏度即可"); return
        }
        if (calibration != null) return
        val cal = Calibration(CursorHub.systemPointerSpeed())
        calibration = cal
        CursorHub.rawListener = { dx, dy, t -> cal.onRaw(dx, dy, t) }
        tvPad.text = "校準中（8 秒）：請在畫面中間大範圍畫圈、忽快忽慢地移動滑鼠，不要碰到螢幕邊緣…"
        main.postDelayed({ finishCalibration() }, 8000)
    }

    private fun finishCalibration() {
        val cal = calibration ?: return
        calibration = null
        CursorHub.rawListener = null
        val results = cal.solve()
        if (results == null) {
            tvPad.text = "校準失敗：有效移動太少，請在畫面中間多移動一些再試一次。"
            return
        }
        val best = results.minBy { it.error }
        prefs.modelType = best.type.ordinal
        val sens = (best.scale * 100).toInt().coerceIn(10, 400)
        prefs.sensitivity100 = sens
        spModel.setSelection(prefs.modelType + 1)
        seekSens.progress = sens - 10
        updateSensLabel()
        CursorHub.reloadSettings()
        refresh()
        val detail = results.joinToString("\n") {
            "${if (it === best) "✔" else "　"} ${Prefs.MODEL_NAMES[it.type.ordinal + 1]}：誤差 %.1f%%，倍率 ×%.2f"
                .format(it.error * 100, it.scale)
        }
        tvPad.text = "校準完成！\n$detail\n\n誤差越小越準；超過 15% 可以再做一次。"
    }

    private class Calibration(systemSpeed: Int) {
        private val models = PointerModel.Type.entries.map { t ->
            PointerModel().apply { type = t; this.systemSpeed = systemSpeed; sensitivity = 1f }
        }
        private class Bucket(n: Int) {
            var rx = 0.0; var ry = 0.0
            val mx = DoubleArray(n); val my = DoubleArray(n)
            var valid = true
        }
        private val buckets = HashMap<Long, Bucket>()
        private val lock = Any()
        private var lastX = Float.NaN
        private var lastY = Float.NaN

        private fun bucket(): Bucket =
            buckets.getOrPut(SystemClock.uptimeMillis() / BUCKET_MS) { Bucket(models.size) }

        fun onRaw(dx: Int, dy: Int, t: Long) = synchronized(lock) {
            val b = bucket()
            models.forEachIndexed { i, m ->
                val (x, y) = m.transform(dx, dy, t)
                b.mx[i] += x; b.my[i] += y
            }
        }

        fun onHover(ev: MotionEvent, screenW: Int, screenH: Int) = synchronized(lock) {
            val b = bucket()
            val x = ev.rawX; val y = ev.rawY
            when (ev.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_EXIT -> {
                    b.valid = false; lastX = Float.NaN
                }
            }
            // 碰到螢幕邊緣時真實游標被夾住、模型沒有，這段不採用
            if (x < EDGE || y < EDGE || x > screenW - EDGE || y > screenH - EDGE) b.valid = false
            if (!lastX.isNaN()) { b.rx += x - lastX; b.ry += y - lastY }
            lastX = x; lastY = y
        }

        class Result(val type: PointerModel.Type, val scale: Double, val error: Double)

        fun solve(): List<Result>? = synchronized(lock) {
            // 只用前後相鄰也都有效的區段，減少延遲造成的邊界誤差
            val keys = buckets.keys.sorted()
            val use = keys.filter { k ->
                buckets[k]!!.valid && buckets[k - 1]?.valid != false && buckets[k + 1]?.valid != false
            }.map { buckets[it]!! }
            val realPath = use.sumOf { kotlin.math.hypot(it.rx, it.ry) }
            if (use.size < 15 || realPath < 1500) return null
            models.indices.map { i ->
                var rm = 0.0; var mm = 0.0; var rr = 0.0
                for (b in use) {
                    rm += b.rx * b.mx[i] + b.ry * b.my[i]
                    mm += b.mx[i] * b.mx[i] + b.my[i] * b.my[i]
                    rr += b.rx * b.rx + b.ry * b.ry
                }
                val k = if (mm > 0) rm / mm else 1.0
                var res = 0.0
                for (b in use) {
                    val ex = b.rx - k * b.mx[i]; val ey = b.ry - k * b.my[i]
                    res += ex * ex + ey * ey
                }
                Result(models[i].type, k, kotlin.math.sqrt(res / rr))
            }
        }

        companion object {
            const val BUCKET_MS = 120L
            const val EDGE = 12
        }
    }

    // ------------------------------------------------------------ 本畫面的真實滑鼠

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.isFromSource(InputDevice.SOURCE_MOUSE) && ev.actionMasked in HOVER_ACTIONS) {
            calibration?.let {
                val p = Point()
                @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealSize(p)
                it.onHover(ev, p.x, p.y)
            }
            // 在本 App 內時，把懸浮游標對齊到真實游標
            if (CursorService.running && !CursorHub.takeoverActive &&
                ev.actionMasked != MotionEvent.ACTION_HOVER_EXIT
            ) {
                CursorHub.setAbsolute(ev.rawX, ev.rawY, "App 同步")
            }
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private val HOVER_ACTIONS = setOf(
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_EXIT)
    }
}
