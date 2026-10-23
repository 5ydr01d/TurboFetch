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
        assertTrue(details.message.contains("will not bypass an untrusted certificate"))
        assertTrue(details.technicalDetails.contains("Trust anchor"))
    }
}
