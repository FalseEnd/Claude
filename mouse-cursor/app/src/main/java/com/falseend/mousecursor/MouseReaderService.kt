package com.falseend.mousecursor

import android.content.Context
import android.os.Process
import android.os.RemoteException
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

/**
 * 透過 Shizuku 以 shell (uid 2000) 身分執行的程序。
 * shell 屬於 input 群組，可以直接讀 /dev/input/eventX —— 也就是不需要 root
 * 就能拿到滑鼠的原始移動量，不管遊戲有沒有把系統游標藏起來。
 */
class MouseReaderService() : IMouseReader.Stub() {

    @Suppress("unused") // Shizuku 會優先呼叫帶 Context 的建構子
    constructor(context: Context) : this()

    @Volatile private var callback: IMouseCallback? = null
    @Volatile private var running = false
    private val readers = ConcurrentHashMap<String, Thread>()
    private var scanner: Thread? = null
    private var lastDescription = ""

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
        val devices = findMice()
        lastDescription = describe(devices)
        return lastDescription
    }

    @Synchronized
    override fun stop() {
        running = false
        callback = null
        scanner?.interrupt()
        scanner = null
        readers.values.forEach { it.interrupt() }
        readers.clear()
    }

    // ---------------------------------------------------------------- 掃描

    data class InputDev(val path: String, val name: String)

    private fun scanLoop() {
        while (running) {
            val mice = findMice()
            for (dev in mice) {
                if (readers[dev.path]?.isAlive == true) continue
                val t = Thread({ readLoop(dev) }, "mouse-${dev.path.substringAfterLast('/')}")
                t.isDaemon = true
                readers[dev.path] = t
                t.start()
            }
            val desc = describe(mice)
            if (desc != lastDescription) {
                lastDescription = desc
                safeCallback { it.onDevices(desc) }
            }
            try {
                Thread.sleep(2000) // 支援熱插拔 / 藍牙滑鼠重連
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun describe(list: List<InputDev>): String =
        if (list.isEmpty()) "未偵測到滑鼠" else list.joinToString("\n") { "${it.name} (${it.path})" }

    /** 找出所有具有 REL_X + REL_Y 的輸入裝置（滑鼠 / 軌跡球 / 部分觸控板） */
    fun findMice(): List<InputDev> {
        val fromProc = runCatching { parseProcDevices(File("/proc/bus/input/devices").readText()) }
            .getOrNull()
        if (!fromProc.isNullOrEmpty()) return fromProc
        return runCatching { parseGetevent() }.getOrDefault(emptyList())
    }

    private fun parseProcDevices(text: String): List<InputDev> {
        val out = ArrayList<InputDev>()
        for (block in text.split(Regex("\\n\\s*\\n"))) {
            var name = "?"
            var handler: String? = null
            var rel = 0L
            for (line in block.lines()) {
                when {
                    line.startsWith("N: Name=") -> name = line.removePrefix("N: Name=").trim('"')
                    line.startsWith("H: Handlers=") ->
                        handler = Regex("event\\d+").find(line)?.value
                    line.startsWith("B: REL=") ->
                        rel = line.removePrefix("B: REL=").trim().split(' ').last().toLongOrNull(16) ?: 0
                }
            }
            if (handler != null && (rel and 0x3L) == 0x3L) out += InputDev("/dev/input/$handler", name)
        }
        return out
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
        fun flush() {
            val pth = path
            if (pth != null && hasRel) out += InputDev(pth, name)
        }
        for (line in text.lines()) {
            val add = Regex("add device \\d+: (\\S+)").find(line)
            if (add != null) {
                flush()
                path = add.groupValues[1]; name = "?"; hasRel = false
                continue
            }
            if (line.trim().startsWith("name:")) name = line.substringAfter("name:").trim().trim('"')
            if (line.contains("REL_X") || line.contains("REL_Y")) hasRel = true
        }
        flush()
        return out
    }

    // ---------------------------------------------------------------- 讀取

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
        try {
            FileInputStream(dev.path).use { input ->
                var filled = 0
                while (running && !Thread.currentThread().isInterrupted) {
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
                            }
                            EV_SYN -> if (code == SYN_REPORT &&
                                (dx != 0 || dy != 0 || wheel != 0 || buttons != lastSentButtons)
                            ) {
                                val t = sec * 1_000_000L + usec
                                val fx = dx; val fy = dy; val fw = wheel; val fb = buttons
                                if (!safeCallback { it.onMotion(fx, fy, fw, fb, t) }) return
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
            }
        } catch (e: Exception) {
            Log.w(TAG, "讀取 ${dev.path} 結束: $e")
        } finally {
            readers.remove(dev.path)
        }
    }

    /** 回傳 false 代表 App 端已死，整個程序結束 */
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
    }
}
