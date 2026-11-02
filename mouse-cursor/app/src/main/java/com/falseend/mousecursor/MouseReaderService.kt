package com.falseend.mousecursor

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.RemoteException
import android.system.Os
import android.util.Log
import android.view.KeyEvent
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

/**
 * 透過 Shizuku 以 shell (uid 2000) 身分執行的程序。
 * shell 屬於 input 群組，可以直接讀 /dev/input/eventX —— 不需要 root。
 *
 * 兩種模式：
 * - 估算：只讀原始位移回報給 App，滑鼠照常由系統處理（游標位置靠模型推算，可能有誤差）
 * - 接管：EVIOCGRAB 獨佔滑鼠（只有滑鼠，鍵盤不受影響），由這裡計算游標位置並注入
 *   滑鼠/觸控事件。畫出來的游標與實際點擊位置是同一個座標，不會有任何誤差。
 */
class MouseReaderService() : IMouseReader.Stub() {

    @Suppress("unused") // Shizuku 會優先呼叫帶 Context 的建構子
    constructor(context: Context) : this()

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
        }
    }

    @Volatile private var callback: IMouseCallback? = null
    @Volatile private var running = false
    private val readers = ConcurrentHashMap<String, Thread>()
    private val streams = ConcurrentHashMap<String, FileInputStream>()
    private val grabErrors = ConcurrentHashMap<String, String>()
    private val grabbed = ConcurrentHashMap.newKeySet<String>()
    private var scanner: Thread? = null
    private val scanLock = Object()
    private var lastDescription = ""
    @Volatile private var mice: List<InputDev> = emptyList()

    // ---- 接管模式狀態（全部在 state 鎖內存取）
    private val state = Any()
    @Volatile private var takeover = false
    private var injector: Injector? = null
    private val model = PointerModel()
    private var x = 540f
    private var y = 1200f
    private var screenW = 1080f
    private var screenH = 2400f
    private var buttons = 0
    private var sideKeys = 0

    override fun destroy() {
        stop()
        exitProcess(0)
    }

    override fun uid(): Int = Process.myUid()

    @Synchronized
    override fun start(cb: IMouseCallback): String {
        callback = cb
        if (!running) {
            running = true
            scanner = Thread({ scanLoop() }, "mouse-scan").apply { isDaemon = true; start() }
        }
        mice = findMice()
        lastDescription = describe(mice)
        return lastDescription
    }

    @Synchronized
    override fun stop() {
        running = false
        callback = null
        synchronized(state) {
            if (takeover) injector?.releaseAll(x, y)
            takeover = false
        }
        scanner?.interrupt()
        scanner = null
        closeAllStreams()
        readers.clear()
    }

    // ---------------------------------------------------------------- 接管模式

    override fun setScreen(width: Int, height: Int) = synchronized(state) {
        screenW = width.toFloat(); screenH = height.toFloat()
        x = x.coerceIn(0f, screenW - 1); y = y.coerceIn(0f, screenH - 1)
    }

    override fun setPosition(px: Float, py: Float) = synchronized(state) {
        x = px.coerceIn(0f, screenW - 1); y = py.coerceIn(0f, screenH - 1)
        model.reset()
    }

    override fun setMotion(modelType: Int, systemSpeed: Int, sensitivity: Float) = synchronized(state) {
        model.type = PointerModel.Type.entries.getOrElse(modelType) { PointerModel.defaultType() }
        model.systemSpeed = systemSpeed.coerceIn(-7, 7)
        model.sensitivity = sensitivity
    }

    override fun setTakeover(enable: Boolean, clickMode: Int): String {
        if (!enable) {
            synchronized(state) {
                if (takeover) injector?.releaseAll(x, y)
                takeover = false
            }
            restartReaders()
            return ""
        }
        // 1. 先確認能注入，否則獨佔滑鼠會讓滑鼠完全失效
        try {
            synchronized(state) {
                val inj = injector ?: Injector().also { injector = it }
                inj.clickMode = clickMode
                inj.probe(x, y)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "無法注入事件", e)
            return "無法注入事件：${e.cause ?: e}"
        }
        // 2. 鍵鼠一體的裝置不能獨佔（會連鍵盤一起搶走）
        val combo = findMice().filter { it.isKeyboard }
        if (combo.isNotEmpty()) {
            return "「${combo.joinToString { it.name }}」同時是鍵盤，獨佔會讓鍵盤失效，無法使用接管模式"
        }
        // 3. 重新開啟所有滑鼠並 EVIOCGRAB
        grabErrors.clear()
        synchronized(state) { buttons = 0; takeover = true }
        restartReaders()
        val deadline = System.currentTimeMillis() + 1500
        while (System.currentTimeMillis() < deadline) {
            val m = mice
            if (m.isNotEmpty() && m.all { it.path in grabbed || grabErrors.containsKey(it.path) }) break
            Thread.sleep(50)
        }
        val failed = grabErrors.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        if (failed.isNotEmpty() || grabbed.isEmpty()) {
            synchronized(state) { takeover = false }
            restartReaders()
            return if (failed.isNotEmpty()) "無法獨佔滑鼠：\n$failed" else "沒有偵測到滑鼠"
        }
        return ""
    }

    private fun restartReaders() {
        closeAllStreams()
        synchronized(scanLock) { scanLock.notifyAll() }
    }

    private fun closeAllStreams() {
        // 關閉 fd 會讓阻塞中的 read() 立刻丟例外，獨佔也會隨之解除
        streams.values.forEach { runCatching { it.close() } }
        streams.clear()
    }

    private fun onSync(dx: Int, dy: Int, wheel: Int, newButtons: Int, timeUs: Long) {
        val inj = injector ?: return
        var px: Float
        var py: Float
        var pressed: Int
        synchronized(state) {
            if (!takeover) return
            val moved = dx != 0 || dy != 0
            if (moved) {
                val (mx, my) = model.transform(dx, dy, timeUs)
                x = (x + mx).coerceIn(0f, screenW - 1)
                y = (y + my).coerceIn(0f, screenH - 1)
            }
            pressed = newButtons and buttons.inv()
            if (moved || newButtons != buttons || wheel != 0) {
                try {
                    inj.update(x, y, moved, newButtons, wheel)
                } catch (e: Throwable) {
                    Log.e(TAG, "注入失敗，解除接管", e)
                    takeover = false
                    Thread { restartReaders() }.start()
                }
            }
            buttons = newButtons
            px = x; py = y
        }
        safeCallback { it.onPosition(px, py, pressed) }
    }

    private fun onSideKey(bit: Int, down: Boolean) {
        val inj = injector ?: return
        synchronized(state) {
            if (!takeover) return
            val was = sideKeys and bit != 0
            if (was == down) return
            sideKeys = if (down) sideKeys or bit else sideKeys and bit.inv()
            runCatching {
                inj.key(if (bit == 1) KeyEvent.KEYCODE_BACK else KeyEvent.KEYCODE_FORWARD, down)
            }
        }
    }

    // ---------------------------------------------------------------- 掃描

    data class InputDev(val path: String, val name: String, val isKeyboard: Boolean)

    private fun scanLoop() {
        while (running) {
            val found = findMice()
            mice = found
            for (dev in found) {
                if (readers[dev.path]?.isAlive == true) continue
                val t = Thread({ readLoop(dev) }, "mouse-${dev.path.substringAfterLast('/')}")
                t.isDaemon = true
                readers[dev.path] = t
                t.start()
            }
            val desc = describe(found)
            if (desc != lastDescription) {
                lastDescription = desc
                safeCallback { it.onDevices(desc) }
            }
            try {
                synchronized(scanLock) { scanLock.wait(2000) } // 支援熱插拔 / 藍牙重連
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun describe(list: List<InputDev>): String =
        if (list.isEmpty()) "未偵測到滑鼠" else list.joinToString("\n") {
            val tag = when {
                it.path in grabbed -> "［已接管］"
                grabErrors.containsKey(it.path) -> "［接管失敗］"
                else -> ""
            }
            "$tag${it.name} (${it.path})"
        }

    /** 找出所有具有 REL_X + REL_Y 的輸入裝置（滑鼠 / 軌跡球 / 部分觸控板） */
    fun findMice(): List<InputDev> {
        val fromProc = runCatching { parseProcDevices(File("/proc/bus/input/devices").readText()) }
            .getOrNull()
        if (!fromProc.isNullOrEmpty()) return fromProc
        return runCatching { parseGetevent() }.getOrDefault(emptyList())
    }

    /** /proc 不可讀時的備案：解析 `getevent -lp` */
    private fun parseGetevent(): List<InputDev> {
        val p = ProcessBuilder("getevent", "-lp").redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor()
        val out = ArrayList<InputDev>()
        var path: String? = null
        var name = "?"
        var hasRel = false
        var hasKeyA = false
        fun flush() {
            val pth = path
            if (pth != null && hasRel) out += InputDev(pth, name, hasKeyA)
        }
        for (line in text.lines()) {
            val add = Regex("add device \\d+: (\\S+)").find(line)
            if (add != null) {
                flush()
                path = add.groupValues[1]; name = "?"; hasRel = false; hasKeyA = false
                continue
            }
            if (line.trim().startsWith("name:")) name = line.substringAfter("name:").trim().trim('"')
            if (line.contains("REL_X") || line.contains("REL_Y")) hasRel = true
            if (Regex("\\bKEY_A\\b").containsMatchIn(line)) hasKeyA = true
        }
        flush()
        return out
    }

    // ---------------------------------------------------------------- 讀取

    private fun grab(fd: FileDescriptor) {
        // Os.ioctlInt 傳入非 NULL 指標，對 EVIOCGRAB 來說就是「獨佔」
        val m2 = runCatching {
            Os::class.java.getMethod("ioctlInt", FileDescriptor::class.java, Int::class.javaPrimitiveType)
        }.getOrNull()
        if (m2 != null) { m2.invoke(null, fd, EVIOCGRAB); return }
        // Android 8~10 的舊簽章 ioctlInt(fd, cmd, Int32Ref/MutableInt)
        val m3 = Os::class.java.methods.first { it.name == "ioctlInt" && it.parameterTypes.size == 3 }
        val ref = m3.parameterTypes[2].getConstructor(Int::class.javaPrimitiveType).newInstance(1)
        m3.invoke(null, fd, EVIOCGRAB, ref)
    }

    private fun readLoop(dev: InputDev) {
        // struct input_event：64 位元 = 24 bytes，32 位元 = 16 bytes
        val is64 = Process.is64Bit()
        val evSize = if (is64) 24 else 16
        val buf = ByteArray(evSize * 64)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        var dx = 0
        var dy = 0
        var wheel = 0
        var buttons = 0
        var lastSentButtons = 0
        var input: FileInputStream? = null
        try {
            input = FileInputStream(dev.path)
            streams[dev.path] = input
            val mine = takeover
            if (mine) {
                try {
                    grab(input.fd)
                    grabbed += dev.path
                } catch (e: Throwable) {
                    grabErrors[dev.path] = (e.cause ?: e).toString()
                    Log.e(TAG, "EVIOCGRAB ${dev.path} 失敗", e)
                }
                safeCallback { cb -> cb.onDevices(describe(mice).also { lastDescription = it }) }
            }
            var filled = 0
            while (running) {
                val n = input.read(buf, filled, buf.size - filled)
                if (n <= 0) break
                filled += n
                val whole = filled / evSize * evSize
                var off = 0
                while (off < whole) {
                    val sec: Long
                    val usec: Long
                    if (is64) {
                        sec = bb.getLong(off); usec = bb.getLong(off + 8)
                    } else {
                        sec = bb.getInt(off).toLong() and 0xffffffffL
                        usec = bb.getInt(off + 4).toLong() and 0xffffffffL
                    }
                    val base = off + if (is64) 16 else 8
                    val type = bb.getShort(base).toInt() and 0xffff
                    val code = bb.getShort(base + 2).toInt() and 0xffff
                    val value = bb.getInt(base + 4)
                    when (type) {
                        EV_REL -> when (code) {
                            REL_X -> dx += value
                            REL_Y -> dy += value
                            REL_WHEEL -> wheel += value
                        }
                        EV_KEY -> {
                            val bit = when (code) {
                                BTN_LEFT -> 1; BTN_RIGHT -> 2; BTN_MIDDLE -> 4; else -> 0
                            }
                            if (bit != 0) buttons = if (value != 0) buttons or bit else buttons and bit.inv()
                            if (code == BTN_SIDE || code == BTN_EXTRA) {
                                onSideKey(if (code == BTN_SIDE) 1 else 2, value != 0)
                            }
                        }
                        EV_SYN -> if (code == SYN_REPORT &&
                            (dx != 0 || dy != 0 || wheel != 0 || buttons != lastSentButtons)
                        ) {
                            val t = sec * 1_000_000L + usec
                            if (takeover && dev.path in grabbed) {
                                onSync(dx, dy, wheel, buttons, t)
                            } else {
                                val fx = dx; val fy = dy; val fw = wheel; val fb = buttons
                                if (!safeCallback { it.onMotion(fx, fy, fw, fb, t) }) return
                            }
                            lastSentButtons = buttons
                            dx = 0; dy = 0; wheel = 0
                        }
                    }
                    off += evSize
                }
                // 保留不完整的事件
                System.arraycopy(buf, whole, buf, 0, filled - whole)
                filled -= whole
            }
        } catch (e: Exception) {
            Log.w(TAG, "讀取 ${dev.path} 結束: $e")
        } finally {
            runCatching { input?.close() }
            streams.remove(dev.path)
            grabbed -= dev.path
            readers.remove(dev.path)
            synchronized(scanLock) { scanLock.notifyAll() } // 盡快重新開啟
        }
    }

    /** 回傳 false 代表 App 端已死，整個程序結束（fd 關閉 = 自動解除獨佔） */
    private inline fun safeCallback(block: (IMouseCallback) -> Unit): Boolean {
        val cb = callback ?: return true
        return try {
            block(cb); true
        } catch (e: RemoteException) {
            Log.w(TAG, "App 端已斷線，結束: $e")
            stop()
            exitProcess(0)
        }
    }

    companion object {
        fun parseProcDevices(text: String): List<InputDev> {
            val out = ArrayList<InputDev>()
            for (block in text.split(Regex("\\n\\s*\\n"))) {
                var name = "?"
                var handler: String? = null
                var rel = 0L
                var keyLow = 0L
                for (line in block.lines()) {
                    when {
                        line.startsWith("N: Name=") -> name = line.removePrefix("N: Name=").trim('"')
                        line.startsWith("H: Handlers=") ->
                            handler = Regex("event\\d+").find(line)?.value
                        line.startsWith("B: REL=") ->
                            rel = line.removePrefix("B: REL=").trim().split(' ').last().toLongOrNull(16) ?: 0
                        // 最後一個字組 = 鍵碼 0~63；KEY_A = 30
                        line.startsWith("B: KEY=") ->
                            keyLow = line.removePrefix("B: KEY=").trim().split(' ').last()
                                .takeLast(16).toULongOrNull(16)?.toLong() ?: 0
                    }
                }
                if (handler != null && (rel and 0x3L) == 0x3L) {
                    out += InputDev("/dev/input/$handler", name, (keyLow shr KEY_A) and 1L == 1L)
                }
            }
            return out
        }

        private const val TAG = "MouseReader"
        const val EV_SYN = 0x00
        const val EV_KEY = 0x01
        const val EV_REL = 0x02
        const val SYN_REPORT = 0
        const val REL_X = 0x00
        const val REL_Y = 0x01
        const val REL_WHEEL = 0x08
        const val BTN_LEFT = 0x110
        const val BTN_RIGHT = 0x111
        const val BTN_MIDDLE = 0x112
        const val BTN_SIDE = 0x113
        const val BTN_EXTRA = 0x114
        const val KEY_A = 30
        /** _IOW('E', 0x90, int) */
        const val EVIOCGRAB = 0x40044590
    }
}
