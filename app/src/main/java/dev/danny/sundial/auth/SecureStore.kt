package dev.danny.sundial.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small key/value store whose values are encrypted with an AES-256-GCM key held in
 * the Android Keystore, so OAuth tokens never sit in plaintext in shared prefs.
 */
class SecureStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("sundial_secure", Context.MODE_PRIVATE)

    fun putString(key: String, value: String?) {
        val editor = prefs.edit()
        if (value == null) editor.remove(key) else editor.putString(key, encrypt(value))
        editor.apply()
    }

    fun getString(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return when (val result = decrypt(stored)) {
            is DecryptResult.Ok -> result.value
            // Genuinely unreadable forever (key rotated away, value corrupted):
            // drop it so the app returns to a consistent state.
            DecryptResult.Unreadable -> {
                prefs.edit().remove(key).apply()
                null
            }
            // Transient keystore trouble says nothing about the data — keep it.
            DecryptResult.Unavailable -> null
        }
    }

    /**
     * Whether a value exists for [key], readable or not. Lets callers separate
     * "never stored / gone" from "stored but the keystore cannot answer right now".
     */
    fun isStored(key: String): Boolean = prefs.contains(key)

    fun putLong(key: String, value: Long) = putString(key, value.toString())

    fun getLong(key: String, default: Long = 0L): Long = getString(key)?.toLongOrNull() ?: default

    fun clear(vararg keys: String) {
        val editor = prefs.edit()
        keys.forEach { editor.remove(it) }
        editor.apply()
    }

    // ---- crypto --------------------------------------------------------

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + cipherText.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private sealed interface DecryptResult {
        data class Ok(val value: String) : DecryptResult
        /** This ciphertext can never be read again — wrong key or corrupted value. */
        data object Unreadable : DecryptResult
        /** The keystore could not answer right now; the data itself may be fine. */
        data object Unavailable : DecryptResult
    }

    private fun decrypt(stored: String): DecryptResult {
        val combined = try {
            Base64.decode(stored, Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            return DecryptResult.Unreadable
        }
        if (combined.size <= IV_LENGTH) return DecryptResult.Unreadable
        val iv = combined.copyOfRange(0, IV_LENGTH)
        val cipherText = combined.copyOfRange(IV_LENGTH, combined.size)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            DecryptResult.Ok(String(cipher.doFinal(cipherText), Charsets.UTF_8))
        } catch (_: AEADBadTagException) {
            DecryptResult.Unreadable
        } catch (_: Exception) {
            // KeyStore/keymaster can fail transiently (busy right after boot or an
            // OTA). Note this key never uses setUserAuthenticationRequired, so
            // lock-screen changes do NOT invalidate it.
            DecryptResult.Unavailable
        }
    }

    // Synchronized so two threads racing the first read cannot both generate the
    // alias — the loser's key would silently orphan everything the winner encrypted.
    @Synchronized
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        try {
            (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        } catch (_: UnrecoverableKeyException) {
            // The key blob itself is dead (a documented post-OTA keymaster failure
            // mode) — without this the store would return Unavailable forever and
            // never self-heal. Regenerate: the orphaned ciphertext then fails AEAD
            // verification and is cleaned up through the Unreadable path.
            runCatching { keyStore.deleteEntry(KEY_ALIAS) }
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "sundial_master_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}
