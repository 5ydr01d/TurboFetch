package com.fiveydr.turbofetch

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A small HTTP transfer kernel that safely resumes a .part file when the origin supports
 * byte ranges. It deliberately does not own scheduling, notifications, or Android storage APIs;
 * those belong to the app integration layer.
 */
internal class ResumableHttpEngine(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 20_000,
    private val bufferSize: Int = 64 * 1024,
) {
    data class Progress(
        val transferredBytes: Long,
        val totalBytes: Long?,
        val bytesPerSecond: Long,
        val resumed: Boolean,
    )

    data class Result(
        val file: File,
        val bytes: Long,
        val resumed: Boolean,
        val etag: String?,
        val lastModified: String?,
    )

    class PausedException(val partialFile: File) : IOException("Download paused")
    class CancelledException(val partialFile: File) : IOException("Download cancelled")

    class Control {
        private val pauseFlag = AtomicBoolean(false)
        private val cancelFlag = AtomicBoolean(false)
        fun pause() { pauseFlag.set(true) }
        fun resume() { pauseFlag.set(false) }
        fun cancel() { cancelFlag.set(true) }
        internal fun isPaused() = pauseFlag.get()
        internal fun isCancelled() = cancelFlag.get()
    }

    fun download(
        sourceUrl: String,
        destination: File,
        control: Control = Control(),
        onProgress: (Progress) -> Unit = {},
    ): Result {
        require(sourceUrl.startsWith("https://", true) || sourceUrl.startsWith("http://", true)) {
            "Only HTTP and HTTPS URLs are supported"
        }
        destination.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Cannot create download directory") }
        val part = File(destination.parentFile, destination.name + ".part")
        val metadata = File(destination.parentFile, destination.name + ".part.properties")
        val properties = Properties()
        if (metadata.isFile) runCatching { FileInputStream(metadata).use(properties::load) }
        val storedUrl = properties.getProperty("url")
        if (part.exists() && storedUrl != sourceUrl) {
            part.delete()
            metadata.delete()
            properties.clear()
        }
        val existing = if (part.isFile) part.length() else 0L
        val storedEtag = properties.getProperty("etag")
        val storedModified = properties.getProperty("lastModified")
        val requestResume = existing > 0L && storedUrl == sourceUrl && (!storedEtag.isNullOrBlank() || !storedModified.isNullOrBlank())

        var connection: HttpURLConnection? = null
        try {
            checkControl(control, part)
            connection = (URL(sourceUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "TurboFetch/0.2")
                setRequestProperty("Accept-Encoding", "identity")
                if (requestResume) {
                    setRequestProperty("Range", "bytes=$existing-")
                    setRequestProperty("If-Range", storedEtag?.takeIf { it.isNotBlank() } ?: storedModified)
                }
                connect()
            }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Server returned HTTP $code")

            val append = requestResume && code == HttpURLConnection.HTTP_PARTIAL &&
                parseRangeStart(connection.getHeaderField("Content-Range")) == existing
            // If Range is ignored, or the server's range does not match our partial length,
            // restart from byte zero. Never append a full response to an old partial file.
            val startBytes = if (append) existing else 0L
            val total = parseRangeTotal(connection.getHeaderField("Content-Range"))
                ?: connection.getHeaderFieldLong("Content-Length", -1L).takeIf { it >= 0L }?.plus(startBytes)
            val etag = connection.getHeaderField("ETag")
            val lastModified = connection.getHeaderField("Last-Modified")
            properties.clear()
            properties.setProperty("url", sourceUrl)
            etag?.let { properties.setProperty("etag", it) }
            lastModified?.let { properties.setProperty("lastModified", it) }
            FileOutputStream(metadata).use { properties.store(it, "TurboFetch resumable download metadata") }

            val beganAt = System.nanoTime()
            var lastReportedBytes = startBytes
            var lastReportedAt = beganAt
            var transferred = startBytes
            connection.inputStream.use { input ->
                FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(bufferSize)
                    while (true) {
                        checkControl(control, part)
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        transferred += read
                        val now = System.nanoTime()
                        if (now - lastReportedAt >= 250_000_000L || transferred == total) {
                            val elapsed = (now - lastReportedAt).coerceAtLeast(1L)
                            val speed = ((transferred - lastReportedBytes) * 1_000_000_000L / elapsed).coerceAtLeast(0L)
                            onProgress(Progress(transferred, total, speed, append))
                            lastReportedBytes = transferred
                            lastReportedAt = now
                        }
                    }
                    output.fd.sync()
                }
            }
            if (total != null && part.length() != total) {
                throw IOException("Incomplete response: expected $total bytes, received ${part.length()}")
            }
            if (destination.exists() && !destination.delete()) throw IOException("Cannot replace existing destination file")
            if (!part.renameTo(destination)) {
                FileInputStream(part).use { input ->
                    FileOutputStream(destination).use { output -> input.copyTo(output); output.fd.sync() }
                }
                if (!part.delete()) destination.delete()
            }
            metadata.delete()
            onProgress(Progress(destination.length(), total ?: destination.length(), 0L, append))
            return Result(destination, destination.length(), append, etag, lastModified)
        } finally {
            connection?.disconnect()
        }
    }

    private fun checkControl(control: Control, part: File) {
        if (control.isCancelled()) {
            part.delete()
            throw CancelledException(part)
        }
        if (control.isPaused()) throw PausedException(part)
    }

    private fun parseRangeStart(value: String?): Long? =
        Regex("^bytes\\s+(\\d+)-\\d+/(?:\\d+|\\*)$", RegexOption.IGNORE_CASE)
            .find(value.orEmpty())?.groupValues?.get(1)?.toLongOrNull()

    private fun parseRangeTotal(value: String?): Long? =
        Regex("^bytes\\s+\\d+-\\d+/(\\d+)$", RegexOption.IGNORE_CASE)
            .find(value.orEmpty())?.groupValues?.get(1)?.toLongOrNull()
}
