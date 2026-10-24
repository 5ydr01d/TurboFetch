package com.fiveydr.turbofetch

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts per-download HTTP headers using a non-exportable Android Keystore AES key.
 *
 * Queue items can retain authenticated request headers while paused or retryable without
 * writing raw cookies/authorization values into the downloads SharedPreferences JSON.
 */
internal object DownloadHeaderVault {
    private const val KEY_ALIAS = "com.fiveydr.turbofetch.request-headers.v1"
    private const val ENVELOPE_PREFIX = "v1:"
    private const val IV_SIZE_BYTES = 12
    private const val AUTH_TAG_BITS = 128

    fun encrypt(headers: Map<String, String>): String {
        require(headers.isNotEmpty()) { "Cannot encrypt an empty header map" }
        val payload = JSONObject()
        headers.forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank()) payload.put(name, value)
        }
        require(payload.length() > 0) { "No valid request headers to encrypt" }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(payload.toString().toByteArray(StandardCharsets.UTF_8))
        val iv = cipher.iv
        check(iv.size == IV_SIZE_BYTES) { "Unexpected AES-GCM IV size" }
        val envelope = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, envelope, 0, iv.size)
        System.arraycopy(encrypted, 0, envelope, iv.size, encrypted.size)
        return ENVELOPE_PREFIX + Base64.encodeToString(envelope, Base64.NO_WRAP)
    }

    fun decrypt(envelope: String): Map<String, String> {
        require(envelope.startsWith(ENVELOPE_PREFIX)) { "Unsupported encrypted-header format" }
        val bytes = Base64.decode(envelope.removePrefix(ENVELOPE_PREFIX), Base64.NO_WRAP)
        require(bytes.size > IV_SIZE_BYTES) { "Encrypted request headers are incomplete" }
        val iv = bytes.copyOfRange(0, IV_SIZE_BYTES)
        val encrypted = bytes.copyOfRange(IV_SIZE_BYTES, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(AUTH_TAG_BITS, iv))
        val plaintext = cipher.doFinal(encrypted)
        val payload = JSONObject(String(plaintext, StandardCharsets.UTF_8))
        val result = linkedMapOf<String, String>()
        val keys = payload.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val value = payload.optString(name)
            if (name.isNotBlank() && value.isNotBlank()) result[name] = value
        }
        return result
    }

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
