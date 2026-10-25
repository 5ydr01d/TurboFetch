package com.fiveydr.turbofetch

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.io.FileInputStream
import java.util.Properties
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException

/**
 * Uses parallel byte ranges for large, range-capable files. Any unsupported server falls back
 * to the resumable single-stream engine. Segment scratch files are never exposed as completed
 * downloads; the normal engine remains the authoritative resume path.
 */
internal class AdaptiveDownloadEngine(
    private val singleStream: ResumableHttpEngine = ResumableHttpEngine(),
    private val segmentCount: Int = 4,
    private val minimumSegmentedBytes: Long = 8L * 1024L * 1024L,
) {
    fun download(
        sourceUrl: String,
        destination: File,
        control: ResumableHttpEngine.Control = ResumableHttpEngine.Control(),
        requestHeaders: Map<String, String> = emptyMap(),
        onProgress: (ResumableHttpEngine.Progress) -> Unit = {},
    ): ResumableHttpEngine.Result {
        // HLS playlists are segmented media downloads, not ordinary file byte ranges.
        // Route them through the VOD segment engine before inspecting a .part file.
        if (HlsDownloadEngine.isCandidate(sourceUrl)) {
            return HlsDownloadEngine().download(sourceUrl, destination, control, requestHeaders, onProgress)
        }
        if (DashDownloadEngine.isCandidate(sourceUrl)) {
            return DashDownloadEngine().download(sourceUrl, destination, control, requestHeaders, onProgress)
        }
        val existingPart = File(destination.parentFile, destination.name + ".part")
        // A saved single-stream partial always goes through the proven validator-based resume path.
        if (existingPart.length() > 0L) return singleStream.download(sourceUrl, destination, control, requestHeaders, onProgress)
        val probe = probe(sourceUrl, requestHeaders) ?: return singleStream.download(sourceUrl, destination, control, requestHeaders, onProgress)
        val configuredSegments = DownloadRuntimeOptions.segmentCount.coerceIn(2, 16)
        if (probe.total < minimumSegmentedBytes || configuredSegments < 2) {
            return singleStream.download(sourceUrl, destination, control, requestHeaders, onProgress)
        }
        val count = minOf(configuredSegments, maxOf(2, ((probe.total + minimumSegmentedBytes - 1) / minimumSegmentedBytes).toInt()))
            .coerceAtMost(16)
        val scratch = (0 until count).map { File(destination.parentFile, destination.name + ".segment-" + it) }
        val manifest = File(destination.parentFile, destination.name + ".segments.properties")
        val saved = Properties()
        if (manifest.isFile) runCatching { FileInputStream(manifest).use(saved::load) }
        val sameTransfer = saved.getProperty("url") == sourceUrl &&
            saved.getProperty("total") == probe.total.toString() &&
            saved.getProperty("etag") == probe.etag.orEmpty() &&
            saved.getProperty("lastModified") == probe.lastModified.orEmpty() &&
            saved.getProperty("count") == count.toString()
        if (!sameTransfer) {
            scratch.forEach { it.delete() }
            saved.clear()
            saved.setProperty("url", sourceUrl)
            saved.setProperty("total", probe.total.toString())
            saved.setProperty("etag", probe.etag.orEmpty())
            saved.setProperty("lastModified", probe.lastModified.orEmpty())
            saved.setProperty("count", count.toString())
            FileOutputStream(manifest).use { saved.store(it, "TurboFetch segmented transfer") }
        }
        val aggregateBytes = AtomicLong(scratch.sumOf { it.length() })
        var preserveScratch = false
        var fallbackToSingleStream = false
        val startedAt = System.nanoTime()
        val activeConnections = ConcurrentHashMap.newKeySet<HttpURLConnection>()
        val executor = Executors.newFixedThreadPool(count)
        try {
            val futures = (0 until count).map { index ->
                val start = probe.total * index / count
                val end = probe.total * (index + 1) / count - 1
                executor.submit(Callable {
                    var reportedForSegment = scratch[index].length()
                    fetchRange(sourceUrl, scratch[index], start, end, probe, requestHeaders, control, activeConnections) { current ->
                        val delta = (current - reportedForSegment).coerceAtLeast(0L)
                        reportedForSegment = current
                        val totalDone = aggregateBytes.addAndGet(delta)
                        val elapsed = ((System.nanoTime() - startedAt) / 1_000_000_000L).coerceAtLeast(1L)
                        onProgress(ResumableHttpEngine.Progress(totalDone, probe.total, totalDone / elapsed, false))
                    }
                })
            }
            futures.forEach { it.get() }
            if (control.isCancelled()) throw ResumableHttpEngine.CancelledException(existingPart)
            if (control.isPaused()) throw ResumableHttpEngine.PausedException(existingPart)
            val assembled = File(destination.parentFile, destination.name + ".assembling")
            FileOutputStream(assembled).use { output ->
                scratch.forEach { file -> file.inputStream().use { input -> input.copyTo(output) } }
                output.fd.sync()
            }
            if (assembled.length() != probe.total) {
                assembled.delete()
                throw IOException("Segment assembly size mismatch; expected ${probe.total} bytes")
            }
            if (destination.exists() && !destination.delete()) {
                assembled.delete()
                throw IOException("Cannot replace existing destination file")
            }
            if (!assembled.renameTo(destination)) {
                assembled.inputStream().use { input -> destination.outputStream().use { input.copyTo(it) } }
                assembled.delete()
            }
            onProgress(ResumableHttpEngine.Progress(probe.total, probe.total, 0L, false))
            return ResumableHttpEngine.Result(destination, destination.length(), false, probe.etag, probe.lastModified)
        } catch (error: Exception) {
            // Future.get() wraps worker failures; unwrap it before classifying the failure.
            val cause = error.cause ?: error
            preserveScratch = cause is ResumableHttpEngine.PausedException
            val message = cause.message.orEmpty()
            // Some hosts/CDNs accept the initial probe but later ignore or alter byte ranges.
            // Never append/assemble such responses. Discard segment state and retry safely with
            // the single-stream engine, which will start from byte zero when no .part exists.
            fallbackToSingleStream = cause is IOException && (
                message.startsWith("Server stopped honoring byte ranges") ||
                    message.startsWith("Server returned an invalid Content-Range") ||
                    message.startsWith("Server returned an unexpected segment range") ||
                    message.startsWith("File changed during segmented download (ETag mismatch)")
                )
            // Defer cleanup until workers have stopped; deleting segment files here can race
            // with another worker still writing to one of them.
            if (!fallbackToSingleStream) throw cause
        } finally {
            executor.shutdownNow()
            // Explicitly close sockets so a slow segment worker cannot keep writing while fallback
            // starts. Then wait for workers before deleting scratch files.
            activeConnections.forEach { runCatching { it.disconnect() } }
            runCatching { executor.awaitTermination(25, TimeUnit.SECONDS) }
            if (!preserveScratch) {
                scratch.forEach { it.delete() }
                manifest.delete()
            }
        }
        if (fallbackToSingleStream) {
            return singleStream.download(sourceUrl, destination, control, requestHeaders, onProgress)
        }
        throw IOException("Segmented download ended without a result")
    }

    private data class Probe(val total: Long, val etag: String?, val lastModified: String?)

    private fun probe(sourceUrl: String, requestHeaders: Map<String, String>): Probe? {
        var connection: HttpURLConnection? = null
        return try {
            connection = DownloadRuntimeOptions.openConnection(sourceUrl).apply {
                connectTimeout = 12_000
                readTimeout = 12_000
                requestMethod = "GET"
                setRequestProperty("Range", "bytes=0-0")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "TurboFetch/0.3")
                DownloadRuntimeOptions.applyHeaders(this, requestHeaders)
                connect()
            }
            if (connection.responseCode != HttpURLConnection.HTTP_PARTIAL) return null
            val range = Regex("^bytes\\s+0-0/(\\d+)$", RegexOption.IGNORE_CASE)
                .find(connection.getHeaderField("Content-Range").orEmpty()) ?: return null
            val total = range.groupValues[1].toLongOrNull() ?: return null
            if (total <= 0L) return null
            val etag = connection.getHeaderField("ETag")
            val lastModified = connection.getHeaderField("Last-Modified")
            // Without a stable validator, parallel ranges could silently combine different versions.
            if (etag.isNullOrBlank() && lastModified.isNullOrBlank()) return null
            Probe(total, etag, lastModified)
        } catch (error: SSLException) {
            // Do not disguise a certificate/trust failure as an unsupported range server and
            // silently retry the same host through another engine. The diagnostic layer needs
            // the original TLS exception to explain the actual failure.
            throw error
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun fetchRange(
        sourceUrl: String,
        target: File,
        start: Long,
        end: Long,
        probe: Probe,
        requestHeaders: Map<String, String>,
        control: ResumableHttpEngine.Control,
        activeConnections: MutableSet<HttpURLConnection>,
        onBytes: (Long) -> Unit,
    ) {
        var connection: HttpURLConnection? = null
        val expectedLength = end - start + 1
        val existing = target.length()
        if (existing > expectedLength) {
            target.delete()
        }
        val savedLength = target.length().coerceAtMost(expectedLength)
        if (savedLength == expectedLength) return
        val requestStart = start + savedLength
        val hostPermit = HostConnectionLimiter.acquire(sourceUrl)
        try {
            connection = DownloadRuntimeOptions.openConnection(sourceUrl).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                requestMethod = "GET"
                setRequestProperty("Range", "bytes=$requestStart-$end")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "TurboFetch/0.3")
                val validator = probe.etag?.takeIf { it.isNotBlank() } ?: probe.lastModified
                if (!validator.isNullOrBlank()) setRequestProperty("If-Range", validator)
                DownloadRuntimeOptions.applyHeaders(this, requestHeaders)
            }
            activeConnections.add(connection)
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("Server stopped honoring byte ranges; retry to use single-stream mode")
            }
            val actualRange = Regex("^bytes\\s+(\\d+)-(\\d+)/(\\d+)$", RegexOption.IGNORE_CASE)
                .find(connection.getHeaderField("Content-Range").orEmpty())
                ?: throw IOException("Server returned an invalid Content-Range header")
            if (actualRange.groupValues[1].toLong() != requestStart ||
                actualRange.groupValues[2].toLong() != end ||
                actualRange.groupValues[3].toLong() != probe.total
            ) throw IOException("Server returned an unexpected segment range")
            val responseEtag = connection.getHeaderField("ETag")
            if (!probe.etag.isNullOrBlank() && !responseEtag.isNullOrBlank() && probe.etag != responseEtag) {
                throw IOException("File changed during segmented download (ETag mismatch)")
            }
            var current = savedLength
            connection.inputStream.use { input ->
                FileOutputStream(target, savedLength > 0L).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (control.isCancelled()) throw ResumableHttpEngine.CancelledException(target)
                        if (control.isPaused()) throw ResumableHttpEngine.PausedException(target)
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        DownloadRuntimeOptions.throttle(read)
                        output.write(buffer, 0, read)
                        current += read
                        onBytes(current)
                    }
                    output.fd.sync()
                }
            }
            if (current != expectedLength) throw IOException("Segment incomplete: received $current bytes")
        } finally {
            try {
                connection?.let {
                    activeConnections.remove(it)
                    it.disconnect()
                }
            } finally {
                hostPermit.release()
            }
        }
    }
}
