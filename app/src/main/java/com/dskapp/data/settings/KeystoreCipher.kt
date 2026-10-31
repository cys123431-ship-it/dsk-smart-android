package com.dskapp.data.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with a non-exportable key in AndroidKeyStore.
 *
 * We need exactly one secret (the API key); a full crypto library is not worth its
 * initialisation cost. The Keystore holds the key material, we only persist the IV +
 * ciphertext blob.
 */
internal class KeystoreCipher(private val alias: String = "dsk_api_key") {

    /** Returns Base64(NO_WRAP) of [iv (12 bytes) + ciphertext+tag]. */
    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // ENCRYPT_MODE without explicit IV: Keystore generates a fresh random IV for us.
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val out = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(ciphertext, 0, out, iv.size, ciphertext.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /** Returns null when the blob is corrupt or the Keystore key was invalidated/removed. */
    fun decrypt(blob: String): String? = runCatching {
        val bytes = Base64.decode(blob, Base64.NO_WRAP)
        // Needs at least the IV plus the 16-byte GCM tag to be meaningful.
        require(bytes.size > IV_BYTES)
        val iv = bytes.copyOfRange(0, IV_BYTES)
        val ciphertext = bytes.copyOfRange(IV_BYTES, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }.getOrNull()

    /** Deletes the Keystore entry (used when the stored blob can no longer be decrypted). */
    fun deleteKey() {
        runCatching {
            val keyStore = loadKeyStore()
            if (keyStore.containsAlias(alias)) {
                keyStore.deleteEntry(alias)
            }
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = loadKeyStore()
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return createKey()
    }

    private fun createKey(): SecretKey {
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
