package tw.xiaoliangkou.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 照片、語音的快取：下載過的檔案放在 cacheDir/media/（檔名是 path 的 SHA-1），
 * 解碼好的縮圖留在記憶體。[repo] 是目前的資料來源（登出後是 null，就不下載）。
 */
class MediaCache(context: Context, private val repo: () -> CoupleRepository?) {
    companion object {
        /** 磁碟快取超過這個大小就從最舊的開始刪 */
        const val MAX_DISK_BYTES = 200L * 1024 * 1024
    }

    private val dir = File(context.cacheDir, "media")
    private val locks = HashMap<String, Mutex>()
    private val bitmaps = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).coerceAtMost(48L * 1024 * 1024).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun fileOf(path: String) = File(dir, sha1(path))

    private fun lockOf(path: String) = synchronized(locks) { locks.getOrPut(path) { Mutex() } }

    /** 檔案；還沒下載過就下載。下載失敗（沒網路、沒權限、被刪掉）回傳 null */
    suspend fun file(path: String): File? = withContext(Dispatchers.IO) {
        val f = fileOf(path)
        if (f.length() > 0) return@withContext f
        lockOf(path).withLock {
            if (f.length() > 0) return@withLock f
            val r = repo() ?: return@withLock null
            val bytes = runCatching { r.downloadMedia(path) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return@withLock null
            write(f, bytes)
            f
        }
    }

    /** 縮圖（最長邊不超過 [maxSide] 像素）；同一張圖同一個大小只解碼一次 */
    suspend fun bitmap(path: String, maxSide: Int): Bitmap? {
        val key = "$path@$maxSide"
        bitmaps.get(key)?.let { return it }
        val f = file(path) ?: return null
        val bmp = withContext(Dispatchers.Default) { decode(f, maxSide) } ?: return null
        bitmaps.put(key, bmp)
        return bmp
    }

    /** 已經在記憶體裡的縮圖（畫面第一次畫的時候不用等） */
    fun cachedBitmap(path: String, maxSide: Int): Bitmap? = bitmaps.get("$path@$maxSide")

    /** 剛上傳的檔案直接放進快取，不用再下載一次 */
    suspend fun put(path: String, bytes: ByteArray) = withContext(Dispatchers.IO) { write(fileOf(path), bytes) }

    suspend fun put(path: String, source: File) = withContext(Dispatchers.IO) { write(fileOf(path), source.readBytes()) }

    /** 登出時清掉（下一個登入的人不會看到） */
    fun clear() {
        bitmaps.evictAll()
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun write(f: File, bytes: ByteArray) {
        dir.mkdirs()
        val tmp = File(dir, "${f.name}.${System.nanoTime()}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        trim()
    }

    private fun trim() {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_DISK_BYTES) return
        for (old in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_DISK_BYTES * 3 / 4) break
            total -= old.length()
            old.delete()
        }
    }

    private fun decode(f: File, maxSide: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = maxSide.toFloat() / max(bmp.width, bmp.height)
        if (scale >= 1f) bmp
        else Bitmap.createScaledBitmap(bmp, (bmp.width * scale).roundToInt().coerceAtLeast(1), (bmp.height * scale).roundToInt().coerceAtLeast(1), true)
    }.getOrNull()

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
