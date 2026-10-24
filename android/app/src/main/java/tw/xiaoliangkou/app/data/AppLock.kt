package tw.xiaoliangkou.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * App 鎖：進入 App 要輸入密碼（4～8 位數字）或畫出圖形（3×3 的點連 4 個以上），也可以用指紋或臉部辨識。
 * 圖形存成經過的點（0～8）組成的字串，和密碼一樣只存雜湊。
 * 只存在這支手機（SharedPreferences，不上傳）：密碼存加鹽的 PBKDF2，不存原本的數字。
 * 錯 [MAX_TRIES] 次要等 [WAIT_MS]，之後每再錯 [MAX_TRIES] 次等待時間加倍。
 * [locked]：App 剛打開、離開超過 [relockAfterMs] 再回來時鎖上
 */
class AppLock(
    private val prefs: SharedPreferences,
    private val clock: () -> Long = System::currentTimeMillis,
    private val iterations: Int = ITERATIONS,
) {
    constructor(context: Context) : this(context.getSharedPreferences("app_lock", Context.MODE_PRIVATE))

    val enabled: Boolean get() = prefs.getString(K_HASH, null) != null
    val pinLength: Int get() = prefs.getInt(K_LENGTH, 0)
    /** 用數字密碼還是圖形 */
    val type: Type get() = if (prefs.getString(K_TYPE, null) == Type.PATTERN.name) Type.PATTERN else Type.PIN

    enum class Type { PIN, PATTERN }
    val biometric: Boolean get() = enabled && prefs.getBoolean(K_BIOMETRIC, false)
    val relockAfterMs: Long get() = prefs.getLong(K_RELOCK, DEFAULT_RELOCK_MS)

    private val _locked = MutableStateFlow(enabled)
    val locked: StateFlow<Boolean> = _locked

    /** 設定「離開多久要再解鎖」、「用指紋或臉部」時讓畫面跟著更新 */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    private var leftAt: Long? = null

    /** 設定（或變更）密碼或圖形；打開 App 鎖 */
    fun setPin(pin: String, type: Type = Type.PIN) {
        require(if (type == Type.PIN) isValidPin(pin) else isValidPattern(pin))
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(K_SALT, b64(salt))
            .putString(K_HASH, b64(hash(pin, salt)))
            .putInt(K_LENGTH, pin.length)
            .putString(K_TYPE, type.name)
            .putInt(K_FAILS, 0)
            .remove(K_WAIT_UNTIL)
            .apply()
        _version.value++
    }

    fun setBiometric(on: Boolean) { prefs.edit().putBoolean(K_BIOMETRIC, on).apply(); _version.value++ }
    fun setRelockAfter(ms: Long) { prefs.edit().putLong(K_RELOCK, ms).apply(); _version.value++ }

    /** 關掉 App 鎖（登出時也會關，忘記密碼就靠登出再登入） */
    fun disable() {
        prefs.edit().clear().apply()
        _locked.value = false
        _version.value++
    }

    sealed interface Result {
        data object Ok : Result
        /** 錯了，還可以再試 [left] 次 */
        data class Wrong(val left: Int) : Result
        /** 錯太多次，[seconds] 秒後再試 */
        data class Wait(val seconds: Long) : Result
    }

    /** 還要等幾秒才能再試（0＝現在就可以） */
    fun waitSeconds(): Long {
        val until = prefs.getLong(K_WAIT_UNTIL, 0)
        return ((until - clock()).coerceAtLeast(0) + 999) / 1000
    }

    /** 檢查密碼；對了就解鎖 */
    fun check(pin: String): Result {
        if (!enabled) return Result.Ok
        waitSeconds().takeIf { it > 0 }?.let { return Result.Wait(it) }
        val salt = prefs.getString(K_SALT, null)?.let(::unb64) ?: return Result.Wrong(MAX_TRIES)
        val want = prefs.getString(K_HASH, null)?.let(::unb64) ?: return Result.Wrong(MAX_TRIES)
        if (MessageDigest.isEqual(want, hash(pin, salt))) {
            prefs.edit().putInt(K_FAILS, 0).remove(K_WAIT_UNTIL).apply()
            _locked.value = false
            return Result.Ok
        }
        val fails = prefs.getInt(K_FAILS, 0) + 1
        val e = prefs.edit().putInt(K_FAILS, fails)
        if (fails % MAX_TRIES != 0) {
            e.apply()
            return Result.Wrong(MAX_TRIES - fails % MAX_TRIES)
        }
        // 每錯 5 次等待加倍：30 秒、1 分鐘、2 分鐘…最多 1 小時
        val wait = (WAIT_MS shl (fails / MAX_TRIES - 1).coerceAtMost(7)).coerceAtMost(MAX_WAIT_MS)
        e.putLong(K_WAIT_UNTIL, clock() + wait).apply()
        return Result.Wait(wait / 1000)
    }

    /** 指紋或臉部辨識成功 */
    fun unlockWithBiometric() {
        prefs.edit().putInt(K_FAILS, 0).remove(K_WAIT_UNTIL).apply()
        _locked.value = false
    }

    /** App 到背景：記下時間 */
    fun onBackground() { if (enabled) leftAt = clock() }

    /** 回到前景：離開太久就鎖上 */
    fun onForeground() {
        val left = leftAt ?: return
        leftAt = null
        if (enabled && clock() - left >= relockAfterMs) _locked.value = true
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = runCatching { Base64.decode(s, Base64.NO_WRAP) }.getOrNull()

    companion object {
        const val MIN_PIN = 4
        const val MAX_PIN = 8
        const val MAX_TRIES = 5
        const val WAIT_MS = 30_000L
        const val MAX_WAIT_MS = 60 * 60 * 1000L
        const val DEFAULT_RELOCK_MS = 60_000L
        private const val ITERATIONS = 60_000

        /** 離開多久要再解鎖：(毫秒, 說明) */
        val RELOCK_CHOICES = listOf(0L to "馬上", 60_000L to "1 分鐘", 5 * 60_000L to "5 分鐘", 30 * 60_000L to "30 分鐘")

        fun isValidPin(pin: String) = pin.length in MIN_PIN..MAX_PIN && pin.all { it in '0'..'9' }

        /** 圖形：至少 [MIN_PATTERN] 個點、每個點只經過一次 */
        fun isValidPattern(p: String) = p.length in MIN_PATTERN..9 && p.all { it in '0'..'8' } && p.toSet().size == p.length
        const val MIN_PATTERN = 4

        private const val K_HASH = "hash"
        private const val K_SALT = "salt"
        private const val K_LENGTH = "length"
        private const val K_TYPE = "type"
        private const val K_BIOMETRIC = "biometric"
        private const val K_RELOCK = "relock_after"
        private const val K_FAILS = "fails"
        private const val K_WAIT_UNTIL = "wait_until"
    }
}
