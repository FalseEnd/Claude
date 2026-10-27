package tw.xiaoliangkou.app.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.time.LocalDate
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 發票載具的設定：手機條碼、驗證碼、AppID、要不要自動同步、上次同步到哪天。
 * 只存在這支手機；驗證碼用 Android Keystore 的金鑰加密後才存（手機不支援 Keystore 時才存原文，例如測試環境）
 */
class EInvoiceStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("einvoice", Context.MODE_PRIVATE))

    val card: String get() = prefs.getString(K_CARD, "").orEmpty()
    val appId: String get() = prefs.getString(K_APP, "").orEmpty()
    val verifyCode: String get() = prefs.getString(K_VERIFY, null)?.let(Secrets::open).orEmpty()
    val account: EInvoiceAccount? get() = if (card.isNotEmpty() && appId.isNotEmpty() && verifyCode.isNotEmpty()) EInvoiceAccount(card, verifyCode, appId) else null

    /** 測試通過、打開自動同步 */
    var enabled: Boolean
        get() = prefs.getBoolean(K_ON, false)
        set(v) = prefs.edit().putBoolean(K_ON, v).apply()

    /** 上次同步成功查到哪一天（下次從前幾天開始查，晚上傳的發票也補得到） */
    var lastDay: LocalDate?
        get() = prefs.getString(K_DAY, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        set(v) = prefs.edit().putString(K_DAY, v?.toString()).apply()

    /** 上次試著同步的時間（成功、失敗都算，避免一直重試） */
    var lastTry: Long
        get() = prefs.getLong(K_TRY, 0)
        set(v) = prefs.edit().putLong(K_TRY, v).apply()

    /** 上次同步的結果（給人看的一句話） */
    var lastResult: String
        get() = prefs.getString(K_RESULT, "").orEmpty()
        set(v) = prefs.edit().putString(K_RESULT, v).apply()

    /** 給財政部看的這支手機的編號（API 要的 UUID，第一次用到時產生） */
    val uuid: String
        get() = prefs.getString(K_UUID, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(K_UUID, it).apply() }

    fun save(card: String, verifyCode: String, appId: String) {
        val changed = card != this.card || appId != this.appId || verifyCode != this.verifyCode
        prefs.edit()
            .putString(K_CARD, card)
            .putString(K_VERIFY, Secrets.seal(verifyCode))
            .putString(K_APP, appId)
            .apply { if (changed) remove(K_DAY).remove(K_RESULT).putBoolean(K_ON, false) }
            .apply()
    }

    /** 刪掉載具設定（登出時也會刪） */
    fun clear() {
        val id = prefs.getString(K_UUID, null)
        prefs.edit().clear().apply { if (id != null) putString(K_UUID, id) }.apply()
    }

    private companion object {
        const val K_CARD = "card"
        const val K_VERIFY = "verify"
        const val K_APP = "app_id"
        const val K_ON = "enabled"
        const val K_DAY = "last_day"
        const val K_TRY = "last_try"
        const val K_RESULT = "last_result"
        const val K_UUID = "uuid"
    }
}

/** 用 Android Keystore 的 AES 金鑰加密一小段字（金鑰不會離開手機） */
private object Secrets {
    private const val ALIAS = "xlk_einvoice"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    fun seal(plain: String): String = runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        "k:" + b64(c.iv) + ":" + b64(c.doFinal(plain.toByteArray()))
    }.getOrElse { "p:$plain" }

    fun open(sealed: String): String? = when {
        sealed.startsWith("p:") -> sealed.removePrefix("p:")
        sealed.startsWith("k:") -> runCatching {
            val (iv, data) = sealed.removePrefix("k:").split(":").map { Base64.decode(it, Base64.NO_WRAP) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(c.doFinal(data))
        }.getOrNull()
        else -> null
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
}
