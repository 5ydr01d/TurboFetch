package com.fiveydr.turbofetch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadModelsTest {
    private fun row(
        status: String,
        downloaded: Long = 0L,
        total: Long = 0L
    ) = DownloadRow(
        id = 1L,
        title = "example.bin",
        url = "https://example.invalid/example.bin",
        status = status,
        downloaded = downloaded,
        total = total
    )

    @Test
    fun percentIsSafeForUnknownTotalsAndClampedToProgressRange() {
        assertEquals(0, row("Downloading", downloaded = 20, total = 0).percent)
        assertEquals(50, row("Downloading", downloaded = 50, total = 100).percent)
        assertEquals(100, row("Complete", downloaded = 120, total = 100).percent)
    }

    @Test
    fun queueAndPausedStatesAreClassifiedConsistently() {
        assertTrue(row("Downloading").isActive)
        assertTrue(row("Queued").isActive)
        assertTrue(row("Paused by Android").isActive)
        assertTrue(row("Waiting in queue").isWaiting)
        assertTrue(row("Paused").isBusy)
        assertFalse(row("Paused").isActive)
    }

    @Test
    fun completionAndProblemStatesAreMutuallyClassified() {
        assertTrue(row("Complete").isComplete)
        assertFalse(row("Complete").isProblem)
        assertTrue(row("Failed").isProblem)
        assertTrue(row("Record unavailable").isProblem)
        assertTrue(row("Cancelled").isProblem)
    }
}
