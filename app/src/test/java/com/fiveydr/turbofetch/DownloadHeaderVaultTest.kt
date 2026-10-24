package com.fiveydr.turbofetch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import javax.crypto.spec.SecretKeySpec

class DownloadHeaderVaultTest {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val headers = linkedMapOf(
        "Cookie" to "session-cookie-value",
        "User-Agent" to "TurboFetch-test-agent",
        "Referer" to "https://example.test/watch"
    )
    private val associatedData = DownloadHeaderVault.associatedDataForDownload(
        -7L,
        "https://example.test/private-file"
    )

    @Test
    fun encryptedHeadersRoundTripWithoutExposingPlaintext() {
        val envelope = DownloadHeaderVault.encryptWithKey(headers, associatedData, key)

        assertTrue(envelope.startsWith("v1:"))
        assertFalse(envelope.contains("session-cookie-value"))
        assertFalse(envelope.contains("Cookie"))
        assertEquals(headers, DownloadHeaderVault.decryptWithKey(envelope, associatedData, key))
    }

    @Test
    fun encryptedHeadersCannotBeMovedToAnotherDownloadOrUrl() {
        val envelope = DownloadHeaderVault.encryptWithKey(headers, associatedData, key)
        val wrongAssociatedData = DownloadHeaderVault.associatedDataForDownload(
            -8L,
            "https://example.test/private-file"
        )

        try {
            DownloadHeaderVault.decryptWithKey(envelope, wrongAssociatedData, key)
            throw AssertionError("Expected the authenticated header envelope to be rejected")
        } catch (_: AEADBadTagException) {
            // Correct: AES-GCM authentication binds the ciphertext to the queue ID and URL.
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownEnvelopeVersion() {
        DownloadHeaderVault.decryptWithKey("v2:invalid", associatedData, key)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingDownloadIdentity() {
        DownloadHeaderVault.associatedDataForDownload(0L, "https://example.test/file")
    }
}
