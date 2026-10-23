package com.fiveydr.turbofetch

import android.content.Context
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * Process-wide transfer settings. Values are loaded from the app's local preferences whenever
 * the foreground service starts or receives a new command.
 */
internal object DownloadRuntimeOptions {
    @Volatile var maxBytesPerSecond: Long = 0L
        private set
    @Volatile var proxyHost: String = ""
        private set
    @Volatile var proxyPort: Int = 0
        private set
    @Volatile var segmentCount: Int = 4
        private set

    private val limiterLock = Any()
    private var limiterStartedAtNanos = 0L
    private var limiterBytes = 0L

    fun refresh(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences("turbofetch", Context.MODE_PRIVATE)
        val newRate = prefs.getLong("speed_limit_bytes_per_second", 0L).coerceAtLeast(0L)
        val newHost = prefs.getString("proxy_host", "").orEmpty().trim()
        val newPort = prefs.getInt("proxy_port", 0).coerceIn(0, 65535)
        val newSegments = prefs.getInt("segments_per_download", 4).coerceIn(2, 16)
        synchronized(limiterLock) {
            if (newRate != maxBytesPerSecond) {
                limiterStartedAtNanos = 0L
                limiterBytes = 0L
            }
            maxBytesPerSecond = newRate
            proxyHost = newHost
            proxyPort = newPort
            segmentCount = newSegments
        }
    }

    fun openConnection(rawUrl: String): HttpURLConnection {
        val host = proxyHost
        val port = proxyPort
        val proxy = if (host.isNotBlank() && port in 1..65535) {
            Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
        } else {
            Proxy.NO_PROXY
        }
        val url = URL(rawUrl)
        return (if (proxy == Proxy.NO_PROXY) url.openConnection() else url.openConnection(proxy)) as HttpURLConnection
    }

    /**
     * A shared pacing clock prevents parallel range workers from each receiving the full cap.
     * Zero means unlimited. Sleep occurs outside the lock so workers can account for their bytes.
     */
    fun throttle(bytes: Int) {
        if (bytes <= 0) return
        val rate = maxBytesPerSecond
        if (rate <= 0L) return
        val targetNanos = synchronized(limiterLock) {
            if (maxBytesPerSecond <= 0L) return
            if (limiterStartedAtNanos == 0L) limiterStartedAtNanos = System.nanoTime()
            limiterBytes += bytes.toLong()
            limiterStartedAtNanos + ((limiterBytes.toDouble() / maxBytesPerSecond.toDouble()) * 1_000_000_000.0).toLong()
        }
        val waitNanos = targetNanos - System.nanoTime()
        if (waitNanos > 0L) {
            val waitMillis = (waitNanos / 1_000_000L).coerceAtMost(1000L)
            val extraNanos = (waitNanos - waitMillis * 1_000_000L).coerceIn(0L, 999_999L)
            try {
                Thread.sleep(waitMillis, extraNanos.toInt())
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    fun applyHeaders(connection: HttpURLConnection, headers: Map<String, String>) {
        val allowed = setOf("user-agent", "referer", "cookie", "authorization", "accept", "accept-language")
        headers.forEach { (key, value) ->
            val normalized = key.trim().lowercase()
            if (normalized in allowed && value.isNotBlank()) {
                val canonical = when (normalized) {
                    "user-agent" -> "User-Agent"
                    "referer" -> "Referer"
                    "cookie" -> "Cookie"
                    "authorization" -> "Authorization"
                    "accept" -> "Accept"
                    else -> "Accept-Language"
                }
                connection.setRequestProperty(canonical, value.trim())
            }
        }
    }
}
