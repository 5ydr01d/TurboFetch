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

class HlsDownloadEngineTest {
    private lateinit var server: ServerSocket
    private lateinit var serverThread: Thread
    private lateinit var root: File
    @Volatile private var running = true
    private val requestCount = AtomicInteger(0)

    private val segmentOne = byteArrayOf(0x47, 0x11, 0x22, 0x33, 0x44)
    private val segmentTwo = byteArrayOf(0x47, 0x55, 0x66, 0x77, 0x00)

    @Before
    fun setUp() {
        root = Files.createTempDirectory("turbofetch-hls-test").toFile()
        running = true
        requestCount.set(0)
        server = ServerSocket(0)
        serverThread = Thread {
            while (running) {
                try {
                    val socket = server.accept()
                    Thread { respond(socket) }.apply { isDaemon = true; start() }
                } catch (_: Exception) {
                    if (running) throw RuntimeException("HLS test server failed")
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
    fun detectsHlsManifestUrlsWithoutQueryStringAssumptions() {
        assertTrue(HlsDownloadEngine.isCandidate(url("/clip.m3u8")))
        assertTrue(HlsDownloadEngine.isCandidate(url("/clip.m3u8?token=abc")))
        assertFalse(HlsDownloadEngine.isCandidate(url("/clip.mp4")))
        assertFalse(HlsDownloadEngine.isCandidate("not a URL"))
    }

    @Test
    fun downloadsMasterPlaylistAndAssemblesFiniteVodSegments() {
        val destination = File(root, "video.ts")
        val result = AdaptiveDownloadEngine().download(url("/master.m3u8"), destination)

        assertEquals((segmentOne.size + segmentTwo.size).toLong(), result.bytes)
        assertTrue(result.file.name.endsWith(".ts"))
        assertArrayEquals(segmentOne + segmentTwo, result.file.readBytes())
        assertTrue("The master, variant, and two segments should be requested", requestCount.get() >= 4)
        assertFalse("Successful transfers should remove their segment work directory",
            File(destination.parentFile, destination.name + ".hls-work").exists())
        assertFalse("The original destination must not be left as an empty file", destination.exists())
    }

    @Test
    fun rejectsLivePlaylistRatherThanMarkingItComplete() {
        val destination = File(root, "live.ts")
        try {
            AdaptiveDownloadEngine().download(url("/live.m3u8"), destination)
            throw AssertionError("Expected live HLS to be rejected")
        } catch (error: java.io.IOException) {
            assertTrue(error.message.orEmpty().contains("live", ignoreCase = true))
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
            val body = when (path) {
                "/master.m3u8" -> """
                    #EXTM3U
                    #EXT-X-STREAM-INF:BANDWIDTH=1280000,CODECS="avc1.4d401f,mp4a.40.2"
                    variant.m3u8
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
                "/variant.m3u8" -> """
                    #EXTM3U
                    #EXT-X-TARGETDURATION:2
                    #EXT-X-MEDIA-SEQUENCE:0
                    #EXTINF:2.0,
                    segment-1.ts
                    #EXTINF:2.0,
                    segment-2.ts
                    #EXT-X-ENDLIST
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
                "/segment-1.ts" -> segmentOne
                "/segment-2.ts" -> segmentTwo
                "/live.m3u8" -> """
                    #EXTM3U
                    #EXT-X-TARGETDURATION:2
                    #EXTINF:2.0,
                    segment-1.ts
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
                else -> byteArrayOf()
            }
            val status = if (path in setOf("/master.m3u8", "/variant.m3u8", "/segment-1.ts", "/segment-2.ts", "/live.m3u8")) {
                "200 OK"
            } else {
                "404 Not Found"
            }
            val headers = "HTTP/1.1 " + status + "\r\nContent-Length: " + body.size + "\r\nConnection: close\r\n\r\n"
            client.getOutputStream().apply {
                write(headers.toByteArray(StandardCharsets.ISO_8859_1))
                write(body)
                flush()
            }
        }
    }

    private fun url(path: String): String = "http://127.0.0.1:" + server.localPort + path
}
