package com.perppilot.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey as JceKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores API credentials encrypted with an AES-256-GCM key that lives in the Android Keystore
 * (hardware-backed on most devices) and never leaves it.
 */
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("secure_store", Context.MODE_PRIVATE)

    private fun key(): JceKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun put(name: SecretKey, value: String) {
        val v = value.trim()
        if (v.isEmpty()) {
            prefs.edit { remove(name.id) }
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val payload = cipher.iv + cipher.doFinal(v.toByteArray(Charsets.UTF_8))
        prefs.edit { putString(name.id, Base64.encodeToString(payload, Base64.NO_WRAP)) }
    }

    fun get(name: SecretKey): String? {
        val raw = prefs.getString(name.id, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(raw, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_LEN))
            String(cipher.doFinal(bytes, IV_LEN, bytes.size - IV_LEN), Charsets.UTF_8)
        }.getOrNull()
    }

    fun has(name: SecretKey): Boolean = prefs.contains(name.id)

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "perppilot_secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LEN = 12
    }
}
