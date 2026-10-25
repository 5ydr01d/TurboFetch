package com.fiveydr.turbofetch

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class DashDownloadEngineTest {
    private lateinit var server: ServerSocket
    private lateinit var serverThread: Thread
    private lateinit var root: File
    @Volatile private var running = true
    private val requestCount = AtomicInteger(0)

    private val initialization = byteArrayOf(0x00, 0x00, 0x00, 0x08, 0x66, 0x74, 0x79, 0x70)
    private val segmentOne = byteArrayOf(0x00, 0x00, 0x00, 0x05, 0x6d, 0x6f, 0x6f)
    private val segmentTwo = byteArrayOf(0x00, 0x00, 0x00, 0x05, 0x6d, 0x64, 0x61)

    @Before
    fun setUp() {
        root = Files.createTempDirectory("turbofetch-dash-test").toFile()
        running = true
        requestCount.set(0)
        server = ServerSocket(0)
        serverThread = Thread {
            while (running) {
                try {
                    val socket = server.accept()
                    Thread { respond(socket) }.apply { isDaemon = true; start() }
                } catch (_: Exception) {
                    if (running) throw RuntimeException("DASH test server failed")
                }
            }
        }.apply { isDaemon = true; start() }
    }

    @After
    fun tearDown() {
        running = false
        runCatching { server.close() }
        runCatching { serverThread.join(1000) }
        root.deleteRecursively()
    }

    @Test
    fun detectsMpdUrlsWithQueryStrings() {
        assertTrue(DashDownloadEngine.isCandidate(url("/manifest.mpd")))
        assertTrue(DashDownloadEngine.isCandidate(url("/manifest.mpd?token=abc")))
        assertFalse(DashDownloadEngine.isCandidate(url("/video.mp4")))
        assertFalse(DashDownloadEngine.isCandidate("not a URL"))
    }

    @Test
    fun downloadsStaticSegmentTemplateAndAssemblesTrack() {
        val destination = File(root, "video.mp4")
        val result = AdaptiveDownloadEngine().download(url("/manifest.mpd"), destination)

        assertArrayEquals(initialization + segmentOne + segmentTwo, result.file.readBytes())
        assertEquals((initialization.size + segmentOne.size + segmentTwo.size).toLong(), result.bytes)
        assertTrue(result.file.name.endsWith(".mp4"))
        assertTrue("The manifest, initialization file, and two segments should be requested", requestCount.get() >= 4)
        assertFalse("DASH checkpoints should be removed after success",
            File(destination.parentFile, destination.name + ".dash-work").exists())
    }

    @Test
    fun rejectsDynamicDashManifest() {
        val destination = File(root, "live.mp4")
        try {
            AdaptiveDownloadEngine().download(url("/live.mpd"), destination)
            throw AssertionError("Expected dynamic MPD to be rejected")
        } catch (error: java.io.IOException) {
            assertTrue(error.message.orEmpty().contains("live", ignoreCase = true))
        }
        assertFalse(destination.exists())
    }

    @Test
    fun rejectsProtectedManifest() {
        val destination = File(root, "protected.mp4")
        try {
            AdaptiveDownloadEngine().download(url("/drm.mpd"), destination)
            throw AssertionError("Expected DRM-protected MPD to be rejected")
        } catch (error: java.io.IOException) {
            assertTrue(error.message.orEmpty().contains("ContentProtection", ignoreCase = true))
        }
        assertFalse(destination.exists())
    }

    private fun respond(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 5000
            val input = BufferedInputStream(client.getInputStream())
            val request = ByteArrayOutputStream()
            var matched = 0
            val marker = byteArrayOf(13, 10, 13, 10)
            while (matched < marker.size) {
                val value = input.read()
                if (value < 0) return
                request.write(value)
                matched = when {
                    value == marker[matched].toInt() -> matched + 1
                    value == marker[0].toInt() -> 1
                    else -> 0
                }
            }

            requestCount.incrementAndGet()
            val firstLine = request.toString(StandardCharsets.ISO_8859_1.name()).lineSequence().firstOrNull().orEmpty()
            val path = firstLine.split(' ').getOrNull(1)?.substringBefore('?').orEmpty()
            val body: ByteArray = when (path) {
                "/manifest.mpd" -> (
                    """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT4S">""" +
                    """<Period><AdaptationSet contentType="video" mimeType="video/mp4">""" +
                    """<BaseURL>vod/</BaseURL><Representation id="vid" bandwidth="100000" width="320" height="180">""" +
                    """<SegmentTemplate timescale="1" duration="2" initialization="init.mp4" media="seg-""" + "\$" + "Number" + "\$" + """.m4s"/>""" +
                    """</Representation></AdaptationSet></Period></MPD>"""
                ).toByteArray(StandardCharsets.UTF_8)
                "/vod/init.mp4" -> initialization
                "/vod/seg-1.m4s" -> segmentOne
                "/vod/seg-2.m4s" -> segmentTwo
                "/live.mpd" -> """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="dynamic"><Period/></MPD>"""
                    .toByteArray(StandardCharsets.UTF_8)
                "/drm.mpd" -> """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static"><Period><AdaptationSet><ContentProtection schemeIdUri="urn:uuid:test"/></AdaptationSet></Period></MPD>"""
                    .toByteArray(StandardCharsets.UTF_8)
                else -> byteArrayOf()
            }
            val known = path in setOf(
                "/manifest.mpd", "/vod/init.mp4", "/vod/seg-1.m4s", "/vod/seg-2.m4s", "/live.mpd", "/drm.mpd"
            )
            val status = if (known) "200 OK" else "404 Not Found"
            val headers = "HTTP/1.1 " + status + "\r\nContent-Length: " + body.size +
                "\r\nConnection: close\r\n\r\n"
            client.getOutputStream().apply {
                write(headers.toByteArray(StandardCharsets.ISO_8859_1))
                write(body)
                flush()
            }
        }
    }

    private fun url(path: String): String = "http://127.0.0.1:" + server.localPort + path
}
