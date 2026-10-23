package com.fiveydr.turbofetch

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

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
        onProgress: (ResumableHttpEngine.Progress) -> Unit = {},
    ): ResumableHttpEngine.Result {
        val existingPart = File(destination.parentFile, destination.name + ".part")
        // A saved single-stream partial always goes through the proven validator-based resume path.
        if (existingPart.length() > 0L) return singleStream.download(sourceUrl, destination, control, onProgress)
        val probe = probe(sourceUrl) ?: return singleStream.download(sourceUrl, destination, control, onProgress)
        if (probe.total < minimumSegmentedBytes || segmentCount < 2) {
            return singleStream.download(sourceUrl, destination, control, onProgress)
        }
        val count = minOf(segmentCount, maxOf(2, ((probe.total + minimumSegmentedBytes - 1) / minimumSegmentedBytes).toInt()))
            .coerceAtMost(8)
        val scratch = (0 until count).map { File(destination.parentFile, destination.name + ".segment-" + it) }
        val aggregateBytes = AtomicLong(0L)
        val startedAt = System.nanoTime()
        val executor = Executors.newFixedThreadPool(count)
        try {
            val futures = (0 until count).map { index ->
                val start = probe.total * index / count
                val end = probe.total * (index + 1) / count - 1
                executor.submit(Callable {
                    fetchRange(sourceUrl, scratch[index], start, end, probe, control) { current ->
                        val totalDone = completedBytes.get() + current
                        val elapsed = ((System.nanoTime() - startedAt) / 1_000_000_000L).coerceAtLeast(1L)
                        onProgress(ResumableHttpEngine.Progress(totalDone, probe.total, totalDone / elapsed, false))
                    }
                    completedBytes.addAndGet(scratch[index].length())
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
            // A failed segmented transfer is never presented as a complete file. Its scratch
            // parts are discarded; a user retry starts cleanly via this adaptive path.
            scratch.forEach { it.delete() }
            throw (error.cause ?: error)
        } finally {
            executor.shutdownNow()
            scratch.forEach { it.delete() }
        }
    }

    private data class Probe(val total: Long, val etag: String?, val lastModified: String?)

    private fun probe(sourceUrl: String): Probe? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(sourceUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 12_000
                requestMethod = "GET"
                setRequestProperty("Range", "bytes=0-0")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "TurboFetch/0.3")
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
        control: ResumableHttpEngine.Control,
        onBytes: (Long) -> Unit,
    ) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(sourceUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                requestMethod = "GET"
                setRequestProperty("Range", "bytes=$start-$end")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "TurboFetch/0.3")
                val validator = probe.etag?.takeIf { it.isNotBlank() } ?: probe.lastModified
                if (!validator.isNullOrBlank()) setRequestProperty("If-Range", validator)
                connect()
            }
            if (connection.responseCode != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("Server stopped honoring byte ranges; retry to use single-stream mode")
            }
            val actualRange = Regex("^bytes\\s+(\\d+)-(\\d+)/(\\d+)$", RegexOption.IGNORE_CASE)
                .find(connection.getHeaderField("Content-Range").orEmpty())
                ?: throw IOException("Server returned an invalid Content-Range header")
            if (actualRange.groupValues[1].toLong() != start ||
                actualRange.groupValues[2].toLong() != end ||
                actualRange.groupValues[3].toLong() != probe.total
            ) throw IOException("Server returned an unexpected segment range")
            val responseEtag = connection.getHeaderField("ETag")
            if (!probe.etag.isNullOrBlank() && !responseEtag.isNullOrBlank() && probe.etag != responseEtag) {
                throw IOException("File changed during segmented download (ETag mismatch)")
            }
            var current = 0L
            connection.inputStream.use { input ->
                FileOutputStream(target, false).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (control.isCancelled()) throw ResumableHttpEngine.CancelledException(target)
                        if (control.isPaused()) throw ResumableHttpEngine.PausedException(target)
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        current += read
                        onBytes(current)
                    }
                    output.fd.sync()
                }
            }
            if (current != end - start + 1) throw IOException("Segment incomplete: received $current bytes")
        } finally {
            connection?.disconnect()
        }
    }
}
