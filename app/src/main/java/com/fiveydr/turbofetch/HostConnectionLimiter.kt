package com.fiveydr.turbofetch

import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * Per-host connection cap adapted from ClintBrowser’s downloader design.
 * It prevents many parallel downloads from overwhelming one host while keeping
 * different hosts independent. Each permit is released by the request owner.
 */
internal object HostConnectionLimiter {
    private const val MAX_CONNECTIONS_PER_HOST = 32
    private val semaphores = ConcurrentHashMap<String, Semaphore>()

    fun acquire(rawUrl: String): Semaphore {
        val host = runCatching { URI(rawUrl).host.orEmpty().lowercase() }.getOrDefault("")
        val semaphore = if (host.isBlank()) Semaphore(MAX_CONNECTIONS_PER_HOST, true)
            else semaphores.computeIfAbsent(host) { Semaphore(MAX_CONNECTIONS_PER_HOST, true) }
        semaphore.acquire()
        return semaphore
    }
}
