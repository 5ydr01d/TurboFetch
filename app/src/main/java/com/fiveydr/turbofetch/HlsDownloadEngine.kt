package com.fiveydr.turbofetch

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Download-only HLS VOD transfer engine, based on the segment-download functionality used by
 * ClintBrowser. It handles variant playlists, byte-range segments, AES-128 HLS keys and resumable
 * completed-segment checkpoints without embedding a browser.
 *
 * Live playlists and SAMPLE-AES/DRM streams are deliberately rejected rather than saved as if they
 * were complete files.
 */
internal class HlsDownloadEngine {
    private data class KeySpec(val url: String, val ivHex: String?, val method: String)
    private data class Segment(
        val url: String,
        val sequence: Long,
        val key: KeySpec?,
        val rangeOffset: Long? = null,
        val rangeLength: Long? = null,
        val isInit: Boolean = false
    )
    private data class Variant(
        val url: String,
        val bandwidth: Long,
        val codecs: String,
        val audioGroup: String
    )
    private data class Playlist(
        val url: String,
        val text: String,
        val segments: List<Segment>,
        val hasEndList: Boolean,
        val isFmp4: Boolean
    )

    // Cache AES keys only within a single transfer, never across accounts/downloads.
    private val keyCache = ConcurrentHashMap<String, ByteArray>()

    private class RateLimitedException(
        message: String,
        val retryAfterSeconds: Long?
    ) : IOException(message)

    companion object {
        private const val MAX_SEGMENT_ATTEMPTS = 4
        private const val MAX_MANIFEST_BYTES = 8 * 1024 * 1024
        private const val SEGMENT_BUFFER_SIZE = 128 * 1024

        fun isCandidate(url: String): Boolean = runCatching {
            URI(url).path.orEmpty().lowercase().endsWith(".m3u8")
        }.getOrDefault(false)
    }

    fun download(
        sourceUrl: String,
        destination: File,
        control: ResumableHttpEngine.Control,
        requestHeaders: Map<String, String> = emptyMap(),
        onProgress: (ResumableHttpEngine.Progress) -> Unit = {}
    ): ResumableHttpEngine.Result {
        val first = fetchManifest(sourceUrl, sourceUrl, requestHeaders, control, destination)
        if (!first.first.trimStart('\uFEFF', ' ', '\t', '\r', '\n').startsWith("#EXTM3U")) {
            throw IOException("The URL did not return a valid HLS playlist")
        }
        val playlist = resolveMediaPlaylist(sourceUrl, first, requestHeaders, control, destination, 0)
        if (!playlist.hasEndList) {
            throw IOException("This HLS playlist appears to be live. TurboFetch currently accepts finite VOD playlists only.")
        }
        if (playlist.segments.isEmpty()) throw IOException("The HLS playlist contains no downloadable segments")

        val workDir = File(destination.parentFile, destination.name + ".hls-work")
        if (!workDir.exists() && !workDir.mkdirs()) throw IOException("Cannot create HLS work directory")
        val fingerprint = sha256(playlist.url + "\n" + playlist.text)
        val fingerprintFile = File(workDir, "playlist.sha256")
        if (!fingerprintFile.isFile || runCatching { fingerprintFile.readText() }.getOrNull() != fingerprint) {
            workDir.listFiles()?.forEach { it.deleteRecursively() }
            if (!workDir.exists() && !workDir.mkdirs()) throw IOException("Cannot reset HLS work directory")
            fingerprintFile.writeText(fingerprint)
        }

        var preserveWork = false
        val startAt = System.nanoTime()
        val completedBytes = AtomicLong(0L)
        val inFlight = ConcurrentHashMap<Int, AtomicLong>()
        val completedFiles = mutableListOf<File>()
        val wasResumed = playlist.segments.indices.any { index ->
            val target = segmentFile(workDir, index)
            isCompleteSegment(target)
        }

        try {
            val poolSize = minOf(8, DownloadRuntimeOptions.segmentCount.coerceIn(2, 8), playlist.segments.size).coerceAtLeast(1)
            val executor = Executors.newFixedThreadPool(poolSize)
            try {
                val tasks = playlist.segments.mapIndexed { index, segment ->
                    executor.submit(Callable {
                        checkControl(control, destination)
                        val target = segmentFile(workDir, index)
                        if (!isCompleteSegment(target)) {
                            inFlight[index] = AtomicLong(0L)
                            downloadSegment(
                                segment = segment,
                                credentialOrigin = sourceUrl,
                                target = target,
                                workDir = workDir,
                                control = control,
                                requestHeaders = requestHeaders,
                                checkpointFile = File(workDir, target.name + ".done")
                            ) { currentSegmentBytes ->
                                inFlight[index]?.set(currentSegmentBytes)
                                val running = completedBytes.get() + inFlight.values.sumOf { it.get() }
                                val elapsedSeconds = ((System.nanoTime() - startAt) / 1_000_000_000L).coerceAtLeast(1L)
                                onProgress(ResumableHttpEngine.Progress(running, null, running / elapsedSeconds, wasResumed))
                            }
                            inFlight.remove(index)
                        }
                        if (!isCompleteSegment(target)) throw IOException("HLS segment " + index + " did not pass its completion check")
                        completedBytes.addAndGet(target.length())
                        target
                    })
                }
                tasks.forEach { future ->
                    try {
                        future.get()
                    } catch (error: ExecutionException) {
                        val cause = error.cause ?: error
                        when (cause) {
                            is ResumableHttpEngine.PausedException -> throw cause
                            is ResumableHttpEngine.CancelledException -> throw cause
                            is IOException -> throw cause
                            else -> throw IOException("HLS segment download failed", cause)
                        }
                    }
                }
                completedFiles += playlist.segments.indices.map { segmentFile(workDir, it) }
            } finally {
                executor.shutdownNow()
                runCatching { executor.awaitTermination(20, TimeUnit.SECONDS) }
            }

            checkControl(control, destination)
            val extension = if (playlist.isFmp4) "mp4" else "ts"
            val outputStem = destination.name.substringBeforeLast('.', destination.name)
            val output = File(destination.parentFile, outputStem + ".hls-output." + extension)
            val assembling = File(workDir, "assembled.tmp")
            FileOutputStream(assembling).use { out ->
                completedFiles.forEach { segment ->
                    checkControl(control, destination)
                    FileInputStream(segment).use { input -> input.copyTo(out, SEGMENT_BUFFER_SIZE) }
                }
                out.fd.sync()
            }
            if (assembling.length() <= 0L) {
                assembling.delete()
                throw IOException("HLS assembly produced an empty file")
            }
            if (output.exists() && !output.delete()) throw IOException("Cannot replace the previous HLS output")
            if (!assembling.renameTo(output)) {
                FileInputStream(assembling).use { input ->
                    FileOutputStream(output).use { out -> input.copyTo(out); out.fd.sync() }
                }
                assembling.delete()
            }
            onProgress(ResumableHttpEngine.Progress(output.length(), output.length(), 0L, wasResumed))
            workDir.deleteRecursively()
            return ResumableHttpEngine.Result(output, output.length(), wasResumed, null, null)
        } catch (error: ResumableHttpEngine.PausedException) {
            preserveWork = true
            throw error
        } catch (error: ResumableHttpEngine.CancelledException) {
            File(destination.parentFile, destination.nameWithoutExtension + ".hls-output.ts").delete()
            File(destination.parentFile, destination.nameWithoutExtension + ".hls-output.mp4").delete()
            throw error
        } finally {
            if (!preserveWork && control.isCancelled()) workDir.deleteRecursively()
        }
    }

    private fun resolveMediaPlaylist(
        requestedUrl: String,
        firstResponse: Pair<String, String>,
        requestHeaders: Map<String, String>,
        control: ResumableHttpEngine.Control,
        destination: File,
        depth: Int
    ): Playlist {
        if (depth > 3) throw IOException("HLS master playlist nesting is too deep")
        val text = firstResponse.first
        val finalUrl = firstResponse.second
        val variants = parseVariants(text, finalUrl)
        if (variants.isNotEmpty()) {
            val combinedAudioVariants = variants.filter {
                it.audioGroup.isBlank() &&
                    (it.codecs.isBlank() || it.codecs.contains("mp4a", ignoreCase = true))
            }
            if (combinedAudioVariants.isEmpty()) {
                throw IOException("This HLS master uses a separate audio rendition. TurboFetch will not silently save a video-only track; separate-track muxing is not available for this playlist yet.")
            }
            val chosen = combinedAudioVariants.maxByOrNull { it.bandwidth }
                ?: throw IOException("No directly downloadable HLS variant was found")
            val next = fetchManifest(chosen.url, requestedUrl, requestHeaders, control, destination)
            if (!next.first.trimStart('\uFEFF', ' ', '\t', '\r', '\n').startsWith("#EXTM3U")) {
                throw IOException("The selected HLS variant did not return a valid playlist")
            }
            return resolveMediaPlaylist(chosen.url, next, requestHeaders, control, destination, depth + 1)
        }
        return parseMediaPlaylist(finalUrl.ifBlank { requestedUrl }, text)
    }

    private fun parseVariants(text: String, baseUrl: String): List<Variant> {
        val lines = text.lines()
        val variants = mutableListOf<Variant>()
        val attrRegex = Regex("""([A-Za-z0-9\-]+)=("([^"]*)"|[^,]*)""")
        for (index in lines.indices) {
            val line = lines[index].trim()
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue
            val attrs = linkedMapOf<String, String>()
            attrRegex.findAll(line.substringAfter(':')).forEach { match ->
                attrs[match.groupValues[1].uppercase()] =
                    if (match.groupValues[3].isNotEmpty()) match.groupValues[3]
                    else match.groupValues[2].trim().trim('"')
            }
            val nextUrl = lines.drop(index + 1).firstOrNull {
                it.isNotBlank() && !it.trim().startsWith("#")
            }?.trim() ?: continue
            variants += Variant(
                url = resolveUrl(baseUrl, nextUrl),
                bandwidth = attrs["AVERAGE-BANDWIDTH"]?.toLongOrNull()
                    ?: attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L,
                codecs = attrs["CODECS"].orEmpty(),
                audioGroup = attrs["AUDIO"].orEmpty()
            )
        }
        return variants.distinctBy { it.url }
    }

    private fun parseMediaPlaylist(url: String, text: String): Playlist {
        val lines = text.lines()
        var sequence = 0L
        var priorByteRangeEnd = 0L
        var pendingRange: Pair<Long, Long>? = null
        var activeKey: KeySpec? = null
        var initSegment: Segment? = null
        var isFmp4 = false
        var hasEndList = false
        val segments = mutableListOf<Segment>()
        for (raw in lines) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                    sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                line.startsWith("#EXT-X-ENDLIST") -> hasEndList = true
                line.startsWith("#EXT-X-KEY:") -> {
                    val attrs = parseAttributes(line.substringAfter(':'))
                    val method = attrs["METHOD"] ?: "NONE"
                    activeKey = when (method.uppercase()) {
                        "NONE" -> null
                        "AES-128" -> {
                            val keyUrl = attrs["URI"] ?: throw IOException("HLS AES-128 key has no URI")
                            KeySpec(resolveUrl(url, keyUrl), attrs["IV"]?.removePrefix("0x")?.removePrefix("0X"), method)
                        }
                        else -> throw IOException("Unsupported HLS encryption method: " + method + ". DRM/SAMPLE-AES is not bypassed.")
                    }
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    val attrs = parseAttributes(line.substringAfter(':'))
                    val mapUrl = attrs["URI"] ?: throw IOException("HLS initialization segment has no URI")
                    val range = attrs["BYTERANGE"]?.let { parseByteRange(it, 0L) }
                    initSegment = Segment(resolveUrl(url, mapUrl), 0L, activeKey, range?.first, range?.second, true)
                    isFmp4 = true
                }
                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    pendingRange = parseByteRange(line.substringAfter(':'), priorByteRangeEnd)
                    priorByteRangeEnd = pendingRange!!.first + pendingRange!!.second
                }
                line.isNotBlank() && !line.startsWith("#") -> {
                    val range = pendingRange
                    pendingRange = null
                    if (line.substringBefore('?').endsWith(".m4s", true) ||
                        line.substringBefore('?').endsWith(".mp4", true)
                    ) isFmp4 = true
                    segments += Segment(
                        url = resolveUrl(url, line),
                        sequence = sequence,
                        key = activeKey,
                        rangeOffset = range?.first,
                        rangeLength = range?.second
                    )
                    sequence++
                }
            }
        }
        val all = buildList {
            initSegment?.let { add(it) }
            addAll(segments)
        }
        return Playlist(url, text, all, hasEndList, isFmp4)
    }

    private fun downloadSegment(
        segment: Segment,
        credentialOrigin: String,
        target: File,
        workDir: File,
        control: ResumableHttpEngine.Control,
        requestHeaders: Map<String, String>,
        checkpointFile: File,
        onBytes: (Long) -> Unit
    ) {
        var lastError: Exception? = null
        for (attempt in 0 until MAX_SEGMENT_ATTEMPTS) {
            checkControl(control, target)
            val encrypted = File(workDir, target.name + ".enc.tmp")
            val tempPlain = File(workDir, target.name + ".plain.tmp")
            var connection: HttpURLConnection? = null
            try {
                connection = DownloadRuntimeOptions.openConnection(segment.url).apply {
                    connectTimeout = 15_000
                    readTimeout = 45_000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("Accept-Encoding", "identity")
                    val scopedHeaders = headersForTarget(credentialOrigin, segment.url, requestHeaders)
                    setRequestProperty("User-Agent", scopedHeaders.entries.firstOrNull {
                        it.key.equals("User-Agent", true)
                    }?.value ?: "TurboFetch/1.0")
                    DownloadRuntimeOptions.applyHeaders(this, scopedHeaders)
                    if (segment.rangeOffset != null && segment.rangeLength != null) {
                        val end = segment.rangeOffset + segment.rangeLength - 1
                        setRequestProperty("Range", "bytes=" + segment.rangeOffset + "-" + end)
                    }
                    connect()
                }
                val code = connection.responseCode
                if (code == 429 || code == 503) {
                    throw RateLimitedException("HTTP " + code + " while fetching an HLS segment",
                        connection.getHeaderField("Retry-After")?.toLongOrNull())
                }
                if (code !in 200..299) throw IOException("HTTP " + code + " while fetching an HLS segment")
                if (segment.rangeOffset != null && segment.rangeLength != null) {
                    if (code != HttpURLConnection.HTTP_PARTIAL) {
                        throw IOException("The server ignored an HLS byte-range request")
                    }
                    val expectedStart = segment.rangeOffset
                    val expectedEnd = expectedStart + segment.rangeLength - 1L
                    val range = Regex("^bytes\\s+(\\d+)-(\\d+)/(?:\\d+|\\*)$", RegexOption.IGNORE_CASE)
                        .find(connection.getHeaderField("Content-Range").orEmpty())
                        ?: throw IOException("The server returned an invalid HLS Content-Range header")
                    if (range.groupValues[1].toLongOrNull() != expectedStart ||
                        range.groupValues[2].toLongOrNull() != expectedEnd
                    ) {
                        throw IOException("The server returned an unexpected HLS byte range")
                    }
                }
                var written = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(encrypted).use { output ->
                        val buffer = ByteArray(SEGMENT_BUFFER_SIZE)
                        while (true) {
                            checkControl(control, target)
                            if (Thread.currentThread().isInterrupted) throw IOException("HLS segment worker interrupted")
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            output.write(buffer, 0, count)
                            written += count
                            onBytes(written)
                        }
                        output.fd.sync()
                    }
                }
                if (segment.rangeLength != null && written != segment.rangeLength) {
                    throw IOException("HLS byte-range segment was incomplete")
                }
                if (segment.key != null) {
                    val keyBytes = fetchKey(segment.key.url, credentialOrigin, requestHeaders, control, target)
                    val iv = parseIv(segment.key.ivHex, segment.sequence)
                    decryptAes128(encrypted, tempPlain, keyBytes, iv)
                    encrypted.delete()
                    if (target.exists() && !target.delete()) throw IOException("Cannot replace HLS segment")
                    if (!tempPlain.renameTo(target)) {
                        FileInputStream(tempPlain).use { input ->
                            FileOutputStream(target).use { output -> input.copyTo(output); output.fd.sync() }
                        }
                        tempPlain.delete()
                    }
                } else {
                    if (target.exists() && !target.delete()) throw IOException("Cannot replace HLS segment")
                    if (!encrypted.renameTo(target)) {
                        FileInputStream(encrypted).use { input ->
                            FileOutputStream(target).use { output -> input.copyTo(output); output.fd.sync() }
                        }
                        encrypted.delete()
                    }
                }
                checkpointFile.writeText(target.length().toString())
                return
            } catch (error: ResumableHttpEngine.PausedException) {
                throw error
            } catch (error: ResumableHttpEngine.CancelledException) {
                throw error
            } catch (error: Exception) {
                encrypted.delete()
                tempPlain.delete()
                lastError = error
                if (attempt + 1 >= MAX_SEGMENT_ATTEMPTS) break
                val delayMs = if (error is RateLimitedException && error.retryAfterSeconds != null) {
                    (error.retryAfterSeconds * 1000L).coerceIn(500L, 30_000L)
                } else {
                    (500L shl attempt).coerceAtMost(8_000L)
                }
                sleepWithControl(delayMs, control, target)
            } finally {
                connection?.disconnect()
            }
        }
        throw IOException("HLS segment failed after " + MAX_SEGMENT_ATTEMPTS + " attempts: " +
            (lastError?.message ?: "unknown network error"), lastError)
    }

    private fun isCompleteSegment(file: File): Boolean {
        val marker = File(file.parentFile, file.name + ".done")
        if (!file.isFile || !marker.isFile) return false
        val expected = runCatching { marker.readText().trim().toLongOrNull() }.getOrNull() ?: return false
        return expected > 0L && file.length() == expected
    }

    private fun segmentFile(workDir: File, index: Int): File =
        File(workDir, "segment-" + index.toString().padStart(6, '0') + ".bin")

    private fun fetchManifest(
        url: String,
        credentialOrigin: String,
        headers: Map<String, String>,
        control: ResumableHttpEngine.Control,
        partialFile: File
    ): Pair<String, String> {
        checkControl(control, partialFile)
        var connection: HttpURLConnection? = null
        return try {
            connection = DownloadRuntimeOptions.openConnection(url).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("Accept-Encoding", "identity")
                val scopedHeaders = headersForTarget(credentialOrigin, url, headers)
                setRequestProperty("User-Agent", scopedHeaders.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: "TurboFetch/1.0")
                DownloadRuntimeOptions.applyHeaders(this, scopedHeaders)
                connect()
            }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HLS manifest request failed with HTTP " + code)
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    checkControl(control, partialFile)
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    if (output.size() + n > MAX_MANIFEST_BYTES) throw IOException("HLS playlist is too large")
                    output.write(buffer, 0, n)
                }
                output.toByteArray()
            }
            String(bytes, Charsets.UTF_8) to connection.url.toString()
        } finally {
            connection?.disconnect()
        }
    }

    private fun fetchKey(
        keyUrl: String,
        credentialOrigin: String,
        headers: Map<String, String>,
        control: ResumableHttpEngine.Control,
        partialFile: File
    ): ByteArray {
        keyCache[keyUrl]?.let { return it }
        var connection: HttpURLConnection? = null
        val bytes = try {
            connection = DownloadRuntimeOptions.openConnection(keyUrl).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("Accept-Encoding", "identity")
                val scopedHeaders = headersForTarget(credentialOrigin, keyUrl, headers)
                setRequestProperty("User-Agent", scopedHeaders.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: "TurboFetch/1.0")
                DownloadRuntimeOptions.applyHeaders(this, scopedHeaders)
                connect()
            }
            if (connection.responseCode !in 200..299) throw IOException("Unable to fetch HLS AES-128 key (HTTP " + connection.responseCode + ")")
            connection.inputStream.use { input ->
                val data = input.readBytes()
                if (data.size != 16) throw IOException("HLS AES-128 key must contain exactly 16 bytes")
                data
            }
        } finally {
            connection?.disconnect()
        }
        checkControl(control, partialFile)
        keyCache[keyUrl] = bytes
        return bytes
    }

    private fun decryptAes128(inputFile: File, outputFile: File, key: ByteArray, iv: ByteArray) {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        CipherInputStream(FileInputStream(inputFile), cipher).use { input ->
            FileOutputStream(outputFile).use { output -> input.copyTo(output, SEGMENT_BUFFER_SIZE); output.fd.sync() }
        }
    }

    private fun parseIv(value: String?, sequence: Long): ByteArray {
        if (!value.isNullOrBlank()) {
            val normalized = value.removePrefix("0x").removePrefix("0X").filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
            val padded = normalized.padStart(32, '0').takeLast(32)
            return ByteArray(16) { index ->
                padded.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }
        val iv = ByteArray(16)
        var n = sequence
        for (index in 15 downTo 0) {
            iv[index] = (n and 0xff).toByte()
            n = n ushr 8
        }
        return iv
    }

    private fun parseAttributes(text: String): Map<String, String> {
        val values = linkedMapOf<String, String>()
        val regex = Regex("""([A-Za-z0-9\-]+)=("([^"]*)"|[^,]*)""")
        regex.findAll(text).forEach { match ->
            values[match.groupValues[1].uppercase()] =
                if (match.groupValues[3].isNotEmpty()) match.groupValues[3]
                else match.groupValues[2].trim().trim('"')
        }
        return values
    }

    private fun parseByteRange(value: String, previousEnd: Long): Pair<Long, Long> {
        val parts = value.trim().split("@")
        val length = parts[0].trim().toLongOrNull() ?: throw IOException("Invalid HLS byte range")
        if (length <= 0L) throw IOException("Invalid HLS byte-range length")
        val offset = parts.getOrNull(1)?.trim()?.toLongOrNull() ?: previousEnd
        if (offset < 0L) throw IOException("Invalid HLS byte-range offset")
        return offset to length
    }

    private fun resolveUrl(base: String, relative: String): String {
        val value = relative.trim().trim('"')
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) return value
        return try {
            URI(base).resolve(value).toString()
        } catch (error: Exception) {
            throw IOException("Unable to resolve HLS URL", error)
        }
    }

    private fun headersForTarget(
        credentialOrigin: String,
        targetUrl: String,
        headers: Map<String, String>
    ): Map<String, String> {
        fun port(uri: URI): Int = if (uri.port >= 0) uri.port else when (uri.scheme?.lowercase()) {
            "https" -> 443
            "http" -> 80
            else -> -1
        }
        val sameOrigin = runCatching {
            val origin = URI(credentialOrigin)
            val target = URI(targetUrl)
            origin.scheme.equals(target.scheme, ignoreCase = true) &&
                origin.host.equals(target.host, ignoreCase = true) &&
                port(origin) == port(target)
        }.getOrDefault(false)
        if (sameOrigin) return headers
        // Never forward cookies, Authorization or a full Referer URL to another origin in a
        // manifest. Public CDNs still receive the non-sensitive browser-compatible request hints.
        return headers.filterKeys {
            it.equals("User-Agent", true) ||
                it.equals("Accept", true) ||
                it.equals("Accept-Language", true)
        }
    }

    private fun checkControl(control: ResumableHttpEngine.Control, partialFile: File) {
        if (control.isCancelled()) throw ResumableHttpEngine.CancelledException(partialFile)
        if (control.isPaused()) throw ResumableHttpEngine.PausedException(partialFile)
    }

    private fun sleepWithControl(
        milliseconds: Long,
        control: ResumableHttpEngine.Control,
        partialFile: File
    ) {
        var remaining = milliseconds.coerceAtLeast(0L)
        while (remaining > 0L) {
            checkControl(control, partialFile)
            val slice = minOf(remaining, 200L)
            try {
                Thread.sleep(slice)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                checkControl(control, partialFile)
                throw IOException("Interrupted while retrying HLS segment", error)
            }
            remaining -= slice
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}