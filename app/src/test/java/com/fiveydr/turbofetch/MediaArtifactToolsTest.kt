package com.fiveydr.turbofetch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaArtifactToolsTest {
    @Test
    fun classifiesCommonArtifactTypesFromExtensions() {
        assertEquals(MediaArtifactTools.Kind.IMAGE, MediaArtifactTools.classify("https://cdn.example/photo.avif"))
        assertEquals(MediaArtifactTools.Kind.VIDEO, MediaArtifactTools.classify("https://cdn.example/movie.mp4"))
        assertEquals(MediaArtifactTools.Kind.AUDIO, MediaArtifactTools.classify("https://cdn.example/track.flac"))
        assertEquals(MediaArtifactTools.Kind.DOCUMENT, MediaArtifactTools.classify("https://cdn.example/report.docx"))
        assertEquals(MediaArtifactTools.Kind.ARCHIVE, MediaArtifactTools.classify("https://cdn.example/archive.7z"))
        assertEquals(MediaArtifactTools.Kind.STREAM, MediaArtifactTools.classify("https://cdn.example/master.m3u8"))
    }

    @Test
    fun mimeTypeTakesPrecedenceOverExtension() {
        assertEquals(
            MediaArtifactTools.Kind.VIDEO,
            MediaArtifactTools.classify("https://cdn.example/download", "video/mp4; codecs=avc1")
        )
        assertEquals(
            MediaArtifactTools.Kind.DOCUMENT,
            MediaArtifactTools.classify("https://cdn.example/file", "application/pdf")
        )
    }

    @Test
    fun extractsQualityMarkersWithoutInventingQualities() {
        assertEquals(1080, MediaArtifactTools.qualityHeightFrom("Example title 1080p.mp4"))
        assertEquals(2160, MediaArtifactTools.qualityHeightFrom("format=2160p"))
        assertNull(MediaArtifactTools.qualityHeightFrom("best quality"))
        assertEquals(128, MediaArtifactTools.bitrateFrom("audio 128kbps"))
        assertEquals(1500, MediaArtifactTools.bitrateFrom("audio 1.5 Mbps"))
    }

    @Test
    fun createsArtifactOnlyForHttpUrlsAndUsesPageTitleFallback() {
        val artifact = MediaArtifactTools.artifact("https://cdn.example/lecture%20notes.pdf", "", "application/pdf")
        assertNotNull(artifact)
        assertEquals("lecture notes.pdf", artifact?.title)
        assertEquals(MediaArtifactTools.Kind.DOCUMENT, artifact?.kind)
        assertNull(MediaArtifactTools.artifact("javascript:alert(1)"))
        assertTrue(MediaArtifactTools.PAGE_SCAN_SCRIPT.contains("querySelectorAll"))
    }
}
