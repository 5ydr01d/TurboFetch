package com.fiveydr.turbofetch

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadQueuePolicyTest {
    @Test
    fun availableSlotsRespectConfiguredLimit() {
        assertEquals(3, queueSlotsAvailable(activeDownloads = 0, maxConcurrentDownloads = 3))
        assertEquals(1, queueSlotsAvailable(activeDownloads = 2, maxConcurrentDownloads = 3))
        assertEquals(0, queueSlotsAvailable(activeDownloads = 3, maxConcurrentDownloads = 3))
    }

    @Test
    fun concurrencyLimitIsBoundedBetweenOneAndFive() {
        assertEquals(1, queueSlotsAvailable(activeDownloads = 0, maxConcurrentDownloads = -4))
        assertEquals(5, queueSlotsAvailable(activeDownloads = 0, maxConcurrentDownloads = 10))
    }

    @Test
    fun selectionPreservesFifoOrderAndOnlyUsesFreeSlots() {
        assertEquals(
            listOf(-10L, -11L),
            selectQueuedIds(listOf(-10L, -11L, -12L), activeDownloads = 1, maxConcurrentDownloads = 3)
        )
    }

    @Test
    fun noIdsAreSelectedWhenAtCapacity() {
        assertEquals(
            emptyList<Long>(),
            selectQueuedIds(listOf(-10L, -11L), activeDownloads = 2, maxConcurrentDownloads = 2)
        )
    }

    @Test
    fun negativeActiveCountIsTreatedAsZero() {
        assertEquals(2, queueSlotsAvailable(activeDownloads = -3, maxConcurrentDownloads = 2))
    }
}
