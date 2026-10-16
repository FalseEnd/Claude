package tw.xiaoliangkou.app.data

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/** 照片、語音在 Storage 的路徑 */
object MediaPaths {
    const val BUCKET = "media"

    /** 只接受 App 自己產生的那種路徑（英數字、uuid），不能有 .. 跑到別的網址 */
    private val SAFE = Regex("""^[A-Za-z0-9_-]+(/[A-Za-z0-9_-][A-Za-z0-9_.-]*)+$""")

    fun isSafe(path: String) = SAFE.matches(path) && path.split('/').none { it == "." || it == ".." }

    fun checked(path: String): String = path.takeIf(::isSafe) ?: throw AppException("檔案路徑不正確", "invalid_media")

    fun ext(mime: String) = when (mime.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
        "audio/aac" -> "aac"
        "audio/mpeg" -> "mp3"
        "audio/wav", "audio/x-wav" -> "wav"
        else -> "bin"
    }

    /** 副檔名 → MIME（舊資料沒有記 mime 時用） */
    fun mimeOf(path: String) = when (path.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "m4a", "mp4" -> "audio/mp4"
        "aac" -> "audio/aac"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }

    /** [prefix] 下面的新檔名：<prefix><uuid>.jpg */
    fun under(prefix: String, mime: String) = "$prefix${UUID.randomUUID()}.${ext(mime)}"

    /** 兩個人共用（日曆、共用備忘錄）：c/<配對>/<uuid>.jpg */
    fun forCouple(coupleId: String, mime: String) = under("c/$coupleId/", mime)

    /** 只有自己（私人備忘錄）：u/<我>/<uuid>.jpg */
    fun forUser(userId: String, mime: String) = under("u/$userId/", mime)
}

object PhotoTools {
    const val MAX_SIDE = 1600
    const val QUALITY = 82

    /** 縮到最長邊 [maxSide] 以內、存成 JPEG */
    fun compress(bmp: Bitmap, maxSide: Int = MAX_SIDE, quality: Int = QUALITY): Pair<ByteArray, IntSize> {
        val scale = maxSide.toFloat() / max(bmp.width, bmp.height)
        val out = if (scale >= 1f) bmp
        else Bitmap.createScaledBitmap(bmp, (bmp.width * scale).roundToInt().coerceAtLeast(1), (bmp.height * scale).roundToInt().coerceAtLeast(1), true)
        val bytes = ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
        return bytes to IntSize(out.width, out.height)
    }
}

/** 從相簿選的照片：轉正、最長邊 1600、JPEG 82；讀不到回傳 null（在背景執行緒呼叫） */
fun compressPhoto(context: Context, uri: Uri): Pair<ByteArray, IntSize>? =
    Avatars.load(context, uri, PhotoTools.MAX_SIDE)?.let { runCatching { PhotoTools.compress(it) }.getOrNull() }

/**
 * 錄音：AAC（.m4a）、44.1 kHz、單聲道、64 kbps，最長 3 分鐘。
 * 錄到上限時會自己停下來，並呼叫 [onLimit]（畫面再呼叫 [stop] 拿檔案）。
 * [start]、[stop]、[cancel] 會碰到檔案，請在背景執行緒呼叫；不用了一定要 [cancel]，麥克風才會放開。
 */
class VoiceRecorder(private val context: Context) {
    companion object {
        const val MAX_MS = 180_000L
        const val MIN_MS = 500L
        const val MIME = "audio/mp4"
    }

    @Volatile private var recorder: MediaRecorder? = null
    private var file: File? = null
    @Volatile private var startedAt = 0L
    @Volatile var onLimit: () -> Unit = {}

    val isRecording get() = recorder != null
    /** 錄了多久（毫秒） */
    val elapsedMs: Long get() = if (recorder == null) 0 else (SystemClock.elapsedRealtime() - startedAt).coerceAtMost(MAX_MS)

    @Synchronized
    fun start(): File {
        cancel()
        val dir = File(context.cacheDir, "voice").apply { mkdirs() }
        val f = File(dir, "${UUID.randomUUID()}.m4a")
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44_100)
            r.setAudioChannels(1)
            r.setAudioEncodingBitRate(64_000)
            r.setMaxDuration(MAX_MS.toInt())
            r.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) onLimit() }
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            f.delete()
            throw AppException("沒辦法錄音：麥克風可能正在被別的 App 使用", "recorder")
        }
        recorder = r
        file = f
        startedAt = SystemClock.elapsedRealtime()
        return f
    }

    /** 停止並回傳檔案與長度；太短（不到半秒）會丟出錯誤 */
    @Synchronized
    fun stop(): Pair<File, Long> {
        val r = recorder ?: throw AppException("還沒開始錄音", "recorder")
        val f = file!!
        val duration = elapsedMs
        recorder = null
        file = null
        // 錄到上限時已經自己停了，再 stop 可能會丟例外，檔案還是好的
        val ok = runCatching { r.stop() }.isSuccess || duration >= MAX_MS - 1000
        r.release()
        if (!ok || duration < MIN_MS || f.length() == 0L) {
            f.delete()
            throw AppException("錄音太短了，再錄一次 🎙️", "too_short")
        }
        return f to duration
    }

    @Synchronized
    fun cancel() {
        val r = recorder ?: return
        recorder = null
        runCatching { r.stop() }
        r.release()
        file?.delete()
        file = null
    }
}

/**
 * 播放語音：同時只播一個，[playing] 是正在播的檔案。
 * [play] 會讀檔案，請在背景執行緒呼叫；播完的通知在主執行緒。離開畫面時記得 [stop]。
 */
class VoicePlayer {
    private var player: MediaPlayer? = null
    private var done: (() -> Unit)? = null
    private val _playing = MutableStateFlow<File?>(null)
    val playing: StateFlow<File?> = _playing

    val isPlaying get() = player != null

    /** 播到哪裡（毫秒） */
    val positionMs: Long
        @Synchronized get() = player?.let { runCatching { it.currentPosition.toLong() }.getOrNull() } ?: 0

    /** 播完、被停止或播放失敗都會呼叫 [onDone] */
    @Synchronized
    fun play(file: File, onDone: () -> Unit) {
        stop()
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            p.setDataSource(file.absolutePath)
            p.setOnCompletionListener { stopIf(p) }
            p.setOnErrorListener { _, _, _ -> stopIf(p); true }
            p.prepare()
            p.start()
        } catch (e: Exception) {
            p.release()
            onDone()
            throw AppException("這段語音播不出來", "player")
        }
        player = p
        done = onDone
        _playing.value = file
    }

    @Synchronized
    private fun stopIf(p: MediaPlayer) { if (player === p) stop() }

    @Synchronized
    fun stop() {
        val p = player ?: return
        player = null
        runCatching { p.stop() }
        p.release()
        _playing.value = null
        done?.also { done = null }?.invoke()
    }
}
