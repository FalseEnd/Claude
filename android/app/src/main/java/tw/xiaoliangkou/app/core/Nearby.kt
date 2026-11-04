package tw.xiaoliangkou.app.core

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 藍牙偵測「就在身邊」：兩支手機都在背景發出很小的藍牙訊號，同時聽有沒有對方的。
 *
 * 訊號裡不放名字、帳號或手機的資料，只放一串 [TOKEN_BYTES] 個 byte 的代號，每 [WINDOW_SECONDS] 秒（15 分鐘）換一次：
 * 代號 = HMAC-SHA256(配對的鑰匙, 「自己的 user id : 第幾個 15 分鐘」) 的前 8 個 byte。
 * 鑰匙從這一對的 couple id 算出來（只有你們兩個讀得到），所以只有對方的手機認得出你；
 * 路人收到的只是一串一直在換、對不上任何人的亂碼，也沒辦法把不同時段的代號連在一起追蹤。
 * 兩支手機的時鐘可能差幾分鐘：前後各多認一個時段。
 */
object Nearby {
    /** 小倆口的藍牙廣播用的 service data UUID（隨機產生、固定不變） */
    val SERVICE_UUID: UUID = UUID.fromString("6c0c5f2a-8e3d-4f7b-9a51-58e2d1c4b7a9")
    const val WINDOW_SECONDS = 15 * 60L
    const val TOKEN_BYTES = 8
    /** 太弱的訊號（大概二、三十公尺外，或隔了好幾道牆）不算在身邊 */
    const val MIN_RSSI = -92
    /** 多久沒收到對方的訊號就不算在身邊了（手機會省電、訊號偶爾漏掉，不要一下就跳掉） */
    const val LOST_AFTER_MS = 4 * 60_000L

    fun key(coupleId: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest("xlk-nearby-v1:$coupleId".toByteArray())

    fun window(at: Instant): Long = Math.floorDiv(at.epochSecond, WINDOW_SECONDS)

    /** 下一次換代號的時間 */
    fun nextWindowAt(at: Instant): Instant = Instant.ofEpochSecond((window(at) + 1) * WINDOW_SECONDS)

    /** [userId] 在第 [window] 個時段廣播的代號 */
    fun token(key: ByteArray, userId: String, window: Long): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal("$userId:$window".toByteArray()).copyOf(TOKEN_BYTES)
    }

    /** 對方這時候可能在廣播的代號：這個時段和前後各一個 */
    fun partnerTokens(key: ByteArray, partnerId: String, at: Instant): List<ByteArray> {
        val w = window(at)
        return (w - 1..w + 1).map { token(key, partnerId, it) }
    }

    /** 收到的 service data 是不是對方的代號 */
    fun matches(data: ByteArray?, expected: List<ByteArray>): Boolean =
        data != null && data.size >= TOKEN_BYTES && expected.any { it.contentEquals(data.copyOf(TOKEN_BYTES)) }

    /**
     * 判斷「就在身邊」：收到夠強的對方訊號就算，[lostAfterMs] 內都沒再收到才不算。
     * [since] 是這次在一起從什麼時候開始（不在身邊是 null）
     */
    class Tracker(private val lostAfterMs: Long = LOST_AFTER_MS) {
        var since: Instant? = null
            private set
        private var lastSeen: Instant? = null

        /** 收到對方的訊號：回傳「在不在身邊」有沒有變 */
        fun seen(at: Instant, rssi: Int): Boolean {
            if (rssi < MIN_RSSI) return false
            lastSeen = at
            if (since != null) return false
            since = at
            return true
        }

        /** 定時檢查：太久沒收到就不算在身邊，回傳有沒有變 */
        fun tick(at: Instant): Boolean {
            val last = lastSeen ?: return false
            if (since == null || at.toEpochMilli() - last.toEpochMilli() <= lostAfterMs) return false
            since = null
            return true
        }

        /** 關掉偵測（設定關了、藍牙關了、解除配對）：回傳有沒有變 */
        fun reset(): Boolean {
            lastSeen = null
            if (since == null) return false
            since = null
            return true
        }
    }
}
