package com.fiveydr.turbofetch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadLinkToolsTest {
    @Test
    fun extractsDistinctHttpLinksAndTrimsSentencePunctuation() {
        val result = DownloadLinkTools.extractUrls(
            "Get https://example.com/a.zip, then https://example.com/b.mp4. " +
                "Repeat https://example.com/a.zip and ignore ftp://example.com/x"
        )
        assertEquals(listOf("https://example.com/a.zip", "https://example.com/b.mp4"), result)
    }

    @Test
    fun rejectsInvalidOrNonHttpSchemes() {
        assertFalse(DownloadLinkTools.isHttpUrl("ftp://example.com/file.zip"))
        assertFalse(DownloadLinkTools.isHttpUrl("https://"))
        assertTrue(DownloadLinkTools.isHttpUrl("https://example.com/file.zip"))
    }

    @Test
    fun transferScratchNamesAreUniqueAndSafeForRepeatedDisplayNames() {
        val first = DownloadLinkTools.transferFileName(101L, "../movie?.mp4")
        val second = DownloadLinkTools.transferFileName(102L, "../movie?.mp4")

        assertEquals("101-.._movie_.mp4", first)
        assertEquals("102-.._movie_.mp4", second)
        assertFalse(first == second)
        assertTrue(DownloadLinkTools.transferFileName(103L, "   ").endsWith("download.bin"))
    }

    @Test
    fun classifiesCommonDownloadableFileExtensions() {
        assertTrue(DownloadLinkTools.likelyMediaOrFile("https://cdn.example/video.m3u8?token=abc"))
        assertTrue(DownloadLinkTools.likelyMediaOrFile("https://cdn.example/archive.zip"))
        assertFalse(DownloadLinkTools.likelyMediaOrFile("https://example.com/article"))
    }
}
