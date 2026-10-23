package com.fiveydr.turbofetch

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

class ResumableHttpEngineTest {
    private lateinit var server: ServerSocket
    private lateinit var serverThread: Thread
    private lateinit var root: File
    private lateinit var payload: ByteArray
    @Volatile private var sawRange: String? = null
    private val ignoreRange = AtomicBoolean(false)
    private val ignoreNonProbeRanges = AtomicBoolean(false)
    @Volatile private var running = true

    @Before
    fun setUp() {
        root = Files.createTempDirectory("turbofetch-engine-test").toFile()
        payload = ByteArray(512 * 1024) { (it % 251).toByte() }
        server = ServerSocket(0)
        serverThread = Thread {
            while (running) {
                try {
                    val socket = server.accept()
                    Thread { respond(socket) }.start()
                } catch (_: Exception) {
                    if (running) throw RuntimeException("Test HTTP server failed")
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

    private fun respond(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 5000
            val input = BufferedInputStream(client.getInputStream())
            val request = ByteArrayOutputStream()
            var matched = 0
            val end = byteArrayOf(13, 10, 13, 10)
            while (matched < end.size) {
                val value = input.read()
                if (value < 0) return
                request.write(value)
                matched = when {
                    value == end[matched].toInt() -> matched + 1
                    value == end[0].toInt() -> 1
                    else -> 0
                }
            }
            val headers = request.toString(StandardCharsets.ISO_8859_1.name())
            val rangeHeader = headers.lineSequence().firstOrNull { it.startsWith("Range:", true) }
                ?.substringAfter(":")?.trim()
            sawRange = rangeHeader
            val parsedRange = Regex("^bytes=(\\d+)-(\\d*)$").find(rangeHeader.orEmpty())
            val requestedRangeStart = parsedRange?.groupValues?.get(1)?.toIntOrNull()
            val range = if (
                !ignoreRange.get() &&
                !(ignoreNonProbeRanges.get() && requestedRangeStart != 0)
            ) parsedRange else null
            val requestedStart = range?.groupValues?.get(1)?.toIntOrNull()
            val requestedEnd = range?.groupValues?.get(2)?.takeIf { it.isNotBlank() }?.toIntOrNull()
            val start = requestedStart?.takeIf { it in 0 until payload.size } ?: 0
            val endByte = requestedEnd?.coerceAtMost(payload.size - 1)?.takeIf { it >= start } ?: (payload.size - 1)
            val partial = range != null && requestedStart != null && requestedStart < payload.size
            val bodyLength = endByte - start + 1
            val status = if (partial) "206 Partial Content" else "200 OK"
            val responseHeaders = buildString {
                append("HTTP/1.1 $status\r\n")
                append("Content-Length: $bodyLength\r\n")
                append("ETag: \"test-etag\"\r\n")
                if (partial) append("Content-Range: bytes $start-$endByte/${payload.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
            client.getOutputStream().apply {
                write(responseHeaders.toByteArray(StandardCharsets.ISO_8859_1))
                write(payload, start, bodyLength)
                flush()
            }
        }
    }

    @Test
    fun resumesFromPartialWhenValidatorAndRangeMatch() {
        val destination = File(root, "sample.bin")
        val partial = File(root, "sample.bin.part")
        partial.writeBytes(payload.copyOfRange(0, 100_000))
        val metadata = Properties().apply {
            setProperty("url", url())
            setProperty("etag", "\"test-etag\"")
        }
        File(root, "sample.bin.part.properties").outputStream().use { metadata.store(it, null) }

        val result = ResumableHttpEngine().download(url(), destination)

        assertEquals("bytes=100000-", sawRange)
        assertTrue(result.resumed)
        assertArrayEquals(payload, destination.readBytes())
        assertEquals(payload.size.toLong(), result.bytes)
    }

    @Test
    fun restartsInsteadOfAppendingWhenServerIgnoresRange() {
        val destination = File(root, "sample.bin")
        val partial = File(root, "sample.bin.part")
        partial.writeBytes(payload.copyOfRange(0, 100_000))
        val metadata = Properties().apply {
            setProperty("url", url())
            setProperty("etag", "\"old-etag\"")
        }
        File(root, "sample.bin.part.properties").outputStream().use { metadata.store(it, null) }
        ignoreRange.set(true)

        val result = ResumableHttpEngine().download(url(), destination)

        assertTrue(sawRange != null)
        assertTrue(!result.resumed)
        assertArrayEquals(payload, destination.readBytes())
    }

    @Test
    fun segmentedDownloadAssemblesAllRanges() {
        val destination = File(root, "segmented.bin")
        val result = AdaptiveDownloadEngine(segmentCount = 3, minimumSegmentedBytes = 1L)
            .download(url(), destination)

        assertEquals(payload.size.toLong(), result.bytes)
        assertArrayEquals(payload, destination.readBytes())
    }

    @Test
    fun fallsBackToSingleStreamWhenServerStopsHonoringSegmentRanges() {
        val destination = File(root, "range-fallback.bin")
        // The initial 0-0 probe succeeds, but subsequent non-zero ranges get a full 200 response.
        ignoreNonProbeRanges.set(true)

        val result = AdaptiveDownloadEngine(segmentCount = 3, minimumSegmentedBytes = 1L)
            .download(url(), destination)

        assertEquals(payload.size.toLong(), result.bytes)
        assertArrayEquals(payload, destination.readBytes())
        assertTrue(!File(root, "range-fallback.bin.segments.properties").exists())
    }

    @Test
    fun segmentedPausePreservesPartsAndResumeCompletesFile() {
        val destination = File(root, "segmented-resume.bin")
        val control = ResumableHttpEngine.Control()
        var paused = false
        try {
            AdaptiveDownloadEngine(segmentCount = 3, minimumSegmentedBytes = 1L)
                .download(url(), destination, control) { progress ->
                    if (progress.transferredBytes >= 100_000L) control.pause()
                }
        } catch (_: ResumableHttpEngine.PausedException) {
            paused = true
        }
        assertTrue("transfer should pause", paused)
        assertTrue(File(root, "segmented-resume.bin.segments.properties").isFile)

        val result = AdaptiveDownloadEngine(segmentCount = 3, minimumSegmentedBytes = 1L)
            .download(url(), destination, ResumableHttpEngine.Control())

        assertEquals(payload.size.toLong(), result.bytes)
        assertArrayEquals(payload, destination.readBytes())
        assertTrue(!File(root, "segmented-resume.bin.segments.properties").exists())
    }

    private fun url() = "http://127.0.0.1:${server.localPort}/file"
}
