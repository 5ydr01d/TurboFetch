package com.fiveydr.turbofetch

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadFormattingTest {
    @Test
    fun sanitizesPathSeparatorsAndReservedCharacters() {
        assertEquals("a__b_.zip", sanitizeFileName("a/\\b?.zip"))
        assertEquals("report_.pdf", sanitizeFileName("report:.pdf"))
    }

    @Test
    fun blankNamesGetSafeFallback() {
        assertEquals("download.bin", sanitizeFileName("   "))
    }

    @Test
    fun filenameIsLimitedTo150Characters() {
        assertEquals(150, sanitizeFileName("x".repeat(200)).length)
    }

    @Test
    fun formatsBytesAtUnitBoundaries() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("1023 B", formatBytes(1023))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.0 MB", formatBytes(1024L * 1024L))
        assertEquals("1.00 GB", formatBytes(1024L * 1024L * 1024L))
    }

    @Test
    fun formatsEtaAcrossSecondMinuteAndHourBoundaries() {
        assertEquals("—", formatEta(-1))
        assertEquals("0s", formatEta(0))
        assertEquals("59s", formatEta(59))
        assertEquals("1m 0s", formatEta(60))
        assertEquals("59m 59s", formatEta(3599))
        assertEquals("1h 0m", formatEta(3600))
        assertEquals("2h 3m", formatEta(7380))
    }
}