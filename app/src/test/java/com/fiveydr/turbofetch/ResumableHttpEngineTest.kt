package com.fiveydr.turbofetch

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Properties

class ResumableHttpEngineTest {
    private lateinit var server: HttpServer
    private lateinit var root: File
    private lateinit var payload: ByteArray
    private var sawRange: String? = null

    @Before
    fun setUp() {
        root = Files.createTempDirectory("turbofetch-engine-test").toFile()
        payload = ByteArray(512 * 1024) { (it % 251).toByte() }
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/file") { exchange ->
            sawRange = exchange.requestHeaders.getFirst("Range")
            val range = sawRange?.removePrefix("bytes=")?.removeSuffix("-")?.toIntOrNull()
            if (range != null && range < payload.size) {
                exchange.responseHeaders.add("ETag", "\"test-etag\"")
                exchange.responseHeaders.add("Content-Range", "bytes $range-${payload.size - 1}/${payload.size}")
                exchange.sendResponseHeaders(206, (payload.size - range).toLong())
                exchange.responseBody.use { it.write(payload, range, payload.size - range) }
            } else {
                exchange.responseHeaders.add("ETag", "\"test-etag\"")
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
        root.deleteRecursively()
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

        server.removeContext("/file")
        server.createContext("/file") { exchange ->
            sawRange = exchange.requestHeaders.getFirst("Range")
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }

        val result = ResumableHttpEngine().download(url(), destination)

        assertTrue(sawRange != null)
        assertTrue(!result.resumed)
        assertArrayEquals(payload, destination.readBytes())
    }

    private fun url() = "http://127.0.0.1:${server.address.port}/file"
}
