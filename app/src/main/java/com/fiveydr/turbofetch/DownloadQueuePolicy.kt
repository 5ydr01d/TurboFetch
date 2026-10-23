package com.fiveydr.turbofetch

/**
 * Returns free app-managed slots for a requested concurrency limit.
 * The supported setting is intentionally bounded to 1–5 downloads.
 */
internal fun queueSlotsAvailable(activeDownloads: Int, maxConcurrentDownloads: Int): Int {
    val limit = maxConcurrentDownloads.coerceIn(1, 5)
    return (limit - activeDownloads.coerceAtLeast(0)).coerceAtLeast(0)
}

/** Selects queued IDs in FIFO order, limited to the number of currently free slots. */
internal fun selectQueuedIds(
    waitingIds: List<Long>,
    activeDownloads: Int,
    maxConcurrentDownloads: Int
): List<Long> = waitingIds.take(queueSlotsAvailable(activeDownloads, maxConcurrentDownloads))
