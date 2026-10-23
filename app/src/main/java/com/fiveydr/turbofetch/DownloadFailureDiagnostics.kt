package com.fiveydr.turbofetch

import java.io.IOException
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLException

/** Converts low-level transfer failures into actionable, security-preserving UI diagnostics. */
internal object DownloadFailureDiagnostics {
    data class Details(
        val category: String,
        val message: String,
        val technicalDetails: String,
    )

    fun describe(error: Throwable): Details {
        val chain = generateSequence(error) { it.cause }.take(12).toList()
        val certificateFailure = chain.any {
            it is CertPathValidatorException ||
                it.javaClass.name.contains("CertificateException", ignoreCase = true) ||
                it.message.orEmpty().contains("trust anchor", ignoreCase = true) ||
                it.message.orEmpty().contains("certification path", ignoreCase = true)
        }
        val tlsFailure = certificateFailure || chain.any { it is SSLException }
        val technical = chain.mapNotNull { item ->
            item.message?.takeIf { it.isNotBlank() }?.let { "${item.javaClass.simpleName}: $it" }
        }.distinct().joinToString(" → ").take(500).ifBlank { error.javaClass.simpleName }

        if (certificateFailure) {
            return Details(
                category = "TLS / certificate",
                message = "Android could not verify this server's TLS certificate chain. TurboFetch keeps certificate checks enabled and will not bypass an untrusted certificate. Check the phone's date/time and retry; if only this host fails, its HTTPS certificate chain or download endpoint needs fixing.",
                technicalDetails = technical,
            )
        }
        if (tlsFailure) {
            return Details(
                category = "TLS / secure connection",
                message = "The secure connection failed during TLS negotiation. Retry once; if it persists, check the server's HTTPS configuration and the phone's network/date settings.",
                technicalDetails = technical,
            )
        }

        val message = error.message ?: "Download failed"
        val category = when {
            message.contains("HTTP", ignoreCase = true) ||
                message.contains("range", ignoreCase = true) ||
                message.contains("Content-Range", ignoreCase = true) -> "Server response"
            message.contains("storage", ignoreCase = true) ||
                message.contains("file", ignoreCase = true) ||
                message.contains("directory", ignoreCase = true) -> "Storage"
            message.contains("timeout", ignoreCase = true) ||
                message.contains("connect", ignoreCase = true) ||
                message.contains("network", ignoreCase = true) -> "Network"
            error is IOException -> "Transfer"
            else -> "Transfer"
        }
        return Details(category, message, technical)
    }
}
