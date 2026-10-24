package com.fiveydr.turbofetch

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64
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
    private const val MAX_HEADER_COUNT = 128
    private const val MAX_HEADER_VALUE_BYTES = 64 * 1024
    private const val MAX_ENVELOPE_BYTES = 2 * 1024 * 1024

    fun encrypt(headers: Map<String, String>, associatedData: String): String =
        encryptWithKey(headers, associatedData, getOrCreateKey())

    fun decrypt(envelope: String, associatedData: String): Map<String, String> =
        decryptWithKey(envelope, associatedData, getOrCreateKey())

    /** Injectable key form keeps the cryptographic envelope testable without Android Keystore. */
    internal fun encryptWithKey(
        headers: Map<String, String>,
        associatedData: String,
        key: SecretKey
    ): String {
        require(associatedData.isNotBlank()) { "Encrypted headers must be bound to a download" }
        val entries = headers.filter { (name, value) -> name.isNotBlank() && value.isNotBlank() }
        require(entries.isNotEmpty()) { "Cannot encrypt an empty header map" }
        require(entries.size <= MAX_HEADER_COUNT) { "Too many request headers" }

        val payload = encodeHeaders(entries)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(associatedData.toByteArray(StandardCharsets.UTF_8))
        val encrypted = cipher.doFinal(payload)
        val iv = cipher.iv
        check(iv.size == IV_SIZE_BYTES) { "Unexpected AES-GCM IV size" }
        val envelope = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, envelope, 0, iv.size)
        System.arraycopy(encrypted, 0, envelope, iv.size, encrypted.size)
        return ENVELOPE_PREFIX + Base64.getEncoder().encodeToString(envelope)
    }

    internal fun decryptWithKey(
        envelope: String,
        associatedData: String,
        key: SecretKey
    ): Map<String, String> {
        require(envelope.startsWith(ENVELOPE_PREFIX)) { "Unsupported encrypted-header format" }
        require(associatedData.isNotBlank()) { "Encrypted headers must be bound to a download" }
        val encoded = envelope.removePrefix(ENVELOPE_PREFIX)
        require(encoded.length <= MAX_ENVELOPE_BYTES * 2) { "Encrypted request headers are too large" }
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size in (IV_SIZE_BYTES + 1)..MAX_ENVELOPE_BYTES) {
            "Encrypted request headers are incomplete or too large"
        }
        val iv = bytes.copyOfRange(0, IV_SIZE_BYTES)
        val encrypted = bytes.copyOfRange(IV_SIZE_BYTES, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(AUTH_TAG_BITS, iv))
        cipher.updateAAD(associatedData.toByteArray(StandardCharsets.UTF_8))
        return decodeHeaders(cipher.doFinal(encrypted))
    }

    fun associatedDataForDownload(id: Long, url: String): String {
        require(id != 0L) { "Download ID must be nonzero" }
        require(url.isNotBlank()) { "Download URL must be nonblank" }
        return "turbofetch-download-v1\n$id\n$url"
    }

    private fun encodeHeaders(headers: Map<String, String>): ByteArray =
        ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(headers.size)
                headers.forEach { (name, value) ->
                    writeField(output, name)
                    writeField(output, value)
                }
            }
            buffer.toByteArray()
        }

    private fun writeField(output: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_HEADER_VALUE_BYTES) { "A request header value is too large" }
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun decodeHeaders(payload: ByteArray): Map<String, String> =
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            val count = input.readInt()
            require(count in 1..MAX_HEADER_COUNT) { "Invalid encrypted header count" }
            val headers = linkedMapOf<String, String>()
            repeat(count) {
                val name = readField(input)
                val value = readField(input)
                require(name.isNotBlank() && value.isNotBlank()) { "Invalid encrypted request header" }
                headers[name] = value
            }
            require(input.available() == 0) { "Unexpected trailing encrypted-header data" }
            headers
        }

    private fun readField(input: DataInputStream): String {
        val size = input.readInt()
        require(size in 1..MAX_HEADER_VALUE_BYTES && size <= input.available()) {
            "Invalid encrypted request-header field length"
        }
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
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
