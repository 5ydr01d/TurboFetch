package com.fiveydr.turbofetch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLHandshakeException

class DownloadFailureDiagnosticsTest {
    @Test
    fun identifiesUntrustedCertificateChainWithoutSuggestingBypass() {
        val cause = CertPathValidatorException("Trust anchor for certification path not found")
        val error = SSLHandshakeException("Handshake failed").apply { initCause(cause) }

        val details = DownloadFailureDiagnostics.describe(error)

        assertEquals("TLS / certificate", details.category)
        assertTrue(details.message.contains("will not bypass certificate validation"))
        assertTrue(details.technicalDetails.contains("Trust anchor"))
    }

    @Test
    fun explainsWhyBrowserMayWorkWhenAppRejectsAnIncompleteChain() {
        val cause = CertPathValidatorException("Trust anchor for certification path not found")
        val error = SSLHandshakeException("Handshake failed").apply { initCause(cause) }

        val details = DownloadFailureDiagnostics.describe(error)

        assertTrue(details.message.contains("cached or can obtain a missing intermediate certificate"))
        assertTrue(details.message.contains("complete certificate chain"))
        assertTrue(details.message.contains("disabling TLS checks"))
    }
}
