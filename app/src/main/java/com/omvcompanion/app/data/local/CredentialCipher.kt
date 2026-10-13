package com.omvcompanion.app.data.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts secrets with an AES-256-GCM key that lives in the Android Keystore
 * (never leaves the secure hardware / TEE where available).
 */
class CredentialUnavailableException(cause: Throwable? = null) : IllegalStateException("Saved password cannot be decrypted. Unlock the device and re-enter and save the password to restore background monitoring.", cause)

class CredentialCipher(private val alias: String = "omv_companion_credentials") {

    private val keyStore: KeyStore by lazy { KeyStore.getInstance(KEYSTORE).apply { load(null) } }

    @Synchronized
    private fun key(keyAlias: String, create: Boolean = true): SecretKey {
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        if (!create) throw CredentialUnavailableException()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key("$alias.background")) }
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return "v2:" + Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    /** Empty means no saved data; unreadable ciphertext is an actionable error. */
    fun decrypt(encoded: String): String {
        if (encoded.isEmpty()) return ""
        return decryptSavedSecret(encoded) {
            val bytes = Base64.decode(encoded.removePrefix("v2:"), Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, IV_SIZE)
            val data = bytes.copyOfRange(IV_SIZE, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
                .apply { init(Cipher.DECRYPT_MODE, key(if (encoded.startsWith("v2:")) "$alias.background" else alias, create = false), GCMParameterSpec(TAG_BITS, iv)) }
            String(cipher.doFinal(data), Charsets.UTF_8)
        }
    }

    @Synchronized fun deleteLegacyKey() { if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias) }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}

internal fun decryptSavedSecret(encoded: String, decode: () -> String): String {
    if (encoded.isEmpty()) return ""
    return try { decode().also { if (it.isEmpty()) throw CredentialUnavailableException() } }
    catch (e: CredentialUnavailableException) { throw e }
    catch (e: Exception) { throw CredentialUnavailableException(e) }
}
