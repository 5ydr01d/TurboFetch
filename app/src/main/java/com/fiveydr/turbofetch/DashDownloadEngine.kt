package com.fiveydr.turbofetch

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Download-only MPEG-DASH VOD support.
 *
 * Handles static MPDs using SegmentTemplate/SegmentTimeline, SegmentList, or direct media
 * BaseURLs. When video and audio are separate representations, the tracks are assembled and
 * muxed into one MP4 with Android's native media APIs. Live MPDs, DRM ContentProtection and
 * SegmentBase/SIDX index parsing are not supported.
 */
internal class DashDownloadEngine {
    private enum class Kind { VIDEO, AUDIO }

    private data class Segment(
        val url: String,
        val rangeOffset: Long? = null,
        val rangeLength: Long? = null
    )

    private data class TimelineEntry(
        val start: Long?,
        val duration: Long,
        val repeat: Int
    )

    private data class Template(
        val media: String?,
        val initialization: String?,
        val startNumber: Long?,
        val timescale: Long?,
        val duration: Long?,
        val timeline: List<TimelineEntry>?
    )

    private data class Representation(
        val kind: Kind,
        val id: String,
        val bandwidth: Long,
        val height: Int,
        val mimeType: String,
        val baseUrl: String,
        val segments: List<Segment>,
        val directUrl: String?,
        val extension: String
    )

    private class RateLimitedException(message: String, val retryAfterSeconds: Long?) : IOException(message)

    companion object {
        private const val MAX_MANIFEST_BYTES = 8 * 1024 * 1024
        private const val MAX_SEGMENTS = 10_000
        private const val MAX_SEGMENT_ATTEMPTS = 4
        private const val BUFFER_BYTES = 128 * 1024
        private const val MAX_NESTED_REPEATS = 30_000

        fun isCandidate(url: String): Boolean = runCatching {
            URI(url).path.orEmpty().lowercase().endsWith(".mpd")
        }.getOrDefault(false)
    }

    fun download(
        sourceUrl: String,
        destination: File,
        control: ResumableHttpEngine.Control,
        requestHeaders: Map<String, String> = emptyMap(),
        onProgress: (ResumableHttpEngine.Progress) -> Unit = {}
    ): ResumableHttpEngine.Result {
        val manifest = fetchManifest(sourceUrl, requestHeaders, control, destination)
        val tracks = parseManifest(sourceUrl, manifest)
        val video = tracks.filter { it.kind == Kind.VIDEO }
            .maxWithOrNull(compareBy<Representation> { it.height }.thenBy { it.bandwidth })
        val audio = tracks.filter { it.kind == Kind.AUDIO }
            .maxByOrNull { it.bandwidth }

        if (video == null && audio == null) throw IOException("No downloadable audio or video representations were found in the DASH manifest")

        // A direct MP4/M4A BaseURL can use the existing validator-aware HTTP engine without
        // fragment concatenation or a separate output stage.
        if (video?.directUrl != null && audio == null) {
            return downloadDirect(video, destination, control, requestHeaders, onProgress)
        }
        if (audio?.directUrl != null && video == null) {
            return downloadDirect(audio, destination, control, requestHeaders, onProgress)
        }

        val workDir = File(destination.parentFile, destination.name + ".dash-work")
        if (!workDir.exists() && !workDir.mkdirs()) throw IOException("Cannot create DASH work directory")
        val fingerprint = sha256(sourceUrl + "\n" + manifest)
        val fingerprintFile = File(workDir, "manifest.sha256")
        if (!fingerprintFile.isFile || runCatching { fingerprintFile.readText() }.getOrNull() != fingerprint) {
            workDir.listFiles()?.forEach { it.deleteRecursively() }
            if (!workDir.exists() && !workDir.mkdirs()) throw IOException("Cannot reset DASH work directory")
            fingerprintFile.writeText(fingerprint)
        }

        var preserveWork = false
        try {
            val videoFile = video?.let {
                downloadTrack(it, "video", workDir, control, requestHeaders, onProgress)
            }
            val audioFile = audio?.let {
                downloadTrack(it, "audio", workDir, control, requestHeaders, onProgress)
            }
            checkControl(control, destination)

            val extension = when {
                videoFile != null && audioFile != null -> "mp4"
                video != null -> video.extension
                else -> audio?.extension ?: "m4a"
            }
            val stem = destination.name.substringBeforeLast('.', destination.name)
            val output = File(destination.parentFile, stem + ".dash-output." + extension)
            if (videoFile != null && audioFile != null) {
                if (extension != "mp4") throw IOException("Separate WebM/MP4 tracks cannot be muxed into the same output container")
                MediaTrackMuxer.mux(videoFile, audioFile, output)
            } else {
                val source = videoFile ?: audioFile ?: throw IOException("No DASH track was downloaded")
                if (source.length() <= 0L) throw IOException("The assembled DASH track is empty")
                copyAtomically(source, output)
            }
            if (!output.isFile || output.length() <= 0L) throw IOException("DASH assembly produced an empty output file")
            workDir.deleteRecursively()
            onProgress(ResumableHttpEngine.Progress(output.length(), output.length(), 0L, true))
            return ResumableHttpEngine.Result(output, output.length(), true, null, null)
        } catch (error: ResumableHttpEngine.PausedException) {
            preserveWork = true
            throw error
        } catch (error: ResumableHttpEngine.CancelledException) {
            File(destination.parentFile, destination.nameWithoutExtension + ".dash-output.mp4").delete()
            File(destination.parentFile, destination.nameWithoutExtension + ".dash-output.m4a").delete()
            File(destination.parentFile, destination.nameWithoutExtension + ".dash-output.webm").delete()
            throw error
        } finally {
            if (!preserveWork && control.isCancelled()) workDir.deleteRecursively()
        }
    }

    private fun downloadDirect(
        representation: Representation,
        destination: File,
        control: ResumableHttpEngine.Control,
        headers: Map<String, String>,
        onProgress: (ResumableHttpEngine.Progress) -> Unit
    ): ResumableHttpEngine.Result {
        checkControl(control, destination)
        val stem = destination.name.substringBeforeLast('.', destination.name)
        val extension = representation.extension.ifBlank { if (representation.kind == Kind.AUDIO) "m4a" else "mp4" }
        val output = File(destination.parentFile, stem + ".dash-output." + extension)
        return ResumableHttpEngine().download(representation.directUrl!!, output, control, headers, onProgress)
    }

    private fun fetchManifest(
        url: String,
        headers: Map<String, String>,
        control: ResumableHttpEngine.Control,
        partialFile: File
    ): String {
        checkControl(control, partialFile)
        var connection: HttpURLConnection? = null
        return try {
            connection = DownloadRuntimeOptions.openConnection(url).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty(
                    "User-Agent",
                    headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: "TurboFetch/1.0"
                )
                DownloadRuntimeOptions.applyHeaders(this, headers)
                connect()
            }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("DASH manifest request failed with HTTP " + code)
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    checkControl(control, partialFile)
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    if (output.size() + n > MAX_MANIFEST_BYTES) throw IOException("DASH manifest exceeds the size limit")
                    output.write(buffer, 0, n)
                }
                output.toByteArray()
            }
            String(bytes, StandardCharsets.UTF_8)
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseManifest(documentUrl: String, xml: String): List<Representation> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isExpandEntityReferences = false
        runCatching { factory.setXIncludeAware(false) }
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }

        val root = try {
            factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
        } catch (error: Exception) {
            throw IOException("The URL did not return a valid DASH MPD XML document", error)
        }
        if (root.localNameOrTag() != "MPD") throw IOException("The XML document is not a DASH MPD")
        if (root.attr("type").equals("dynamic", ignoreCase = true)) {
            throw IOException("This DASH manifest is live/dynamic. TurboFetch currently downloads finite VOD manifests only.")
        }
        if (root.descendants().any { it.localNameOrTag() == "ContentProtection" }) {
            throw IOException("The DASH manifest declares ContentProtection. DRM-protected streams are not downloaded.")
        }

        val periods = root.children("Period")
        if (periods.isEmpty()) throw IOException("The DASH MPD has no Period")
        if (periods.size > 1) throw IOException("This DASH manifest has multiple Periods; multi-period stitching is not supported yet.")
        val period = periods.first()
        val durationSeconds = parseDuration(period.attr("duration")) ?: parseDuration(root.attr("mediaPresentationDuration"))
        val rootBase = childBaseUrl(root, documentUrl)
        val periodBase = childBaseUrl(period, rootBase)
        val rootTemplate = readTemplate(root)
        val periodTemplate = merge(rootTemplate, readTemplate(period))
        val result = mutableListOf<Representation>()

        for (adaptation in period.children("AdaptationSet")) {
            val adaptationBase = childBaseUrl(adaptation, periodBase)
            val adaptationTemplate = merge(periodTemplate, readTemplate(adaptation))
            val representations = adaptation.children("Representation").ifEmpty { listOf(adaptation) }
            for (representation in representations) {
                val base = childBaseUrl(representation, adaptationBase)
                val mimeType = representation.attr("mimeType") ?: adaptation.attr("mimeType").orEmpty()
                val codecs = representation.attr("codecs") ?: adaptation.attr("codecs").orEmpty()
                val contentType = representation.attr("contentType") ?: adaptation.attr("contentType").orEmpty()
                val width = representation.attr("width")?.toIntOrNull()
                    ?: adaptation.attr("width")?.toIntOrNull() ?: 0
                val height = representation.attr("height")?.toIntOrNull()
                    ?: adaptation.attr("height")?.toIntOrNull() ?: 0
                val kind = classify(mimeType, contentType, codecs, width, height) ?: continue
                val id = representation.attr("id") ?: (kind.name.lowercase() + "-" + result.size)
                val bandwidth = representation.attr("bandwidth")?.toLongOrNull()
                    ?: adaptation.attr("bandwidth")?.toLongOrNull() ?: 0L
                val template = merge(adaptationTemplate, readTemplate(representation))
                val segmentListElement = representation.child("SegmentList")
                    ?: adaptation.child("SegmentList")
                    ?: period.child("SegmentList")
                val segments = when {
                    template?.media != null -> expandTemplate(base, template, id, bandwidth, durationSeconds)
                    segmentListElement != null -> expandSegmentList(base, segmentListElement)
                    else -> emptyList()
                }
                val directUrl = if (segments.isEmpty() && isDirectMediaUrl(base)) base else null
                if (segments.isEmpty() && directUrl == null) continue
                result += Representation(
                    kind = kind,
                    id = id,
                    bandwidth = bandwidth,
                    height = height,
                    mimeType = mimeType,
                    baseUrl = base,
                    segments = segments,
                    directUrl = directUrl,
                    extension = chooseExtension(mimeType, codecs, base, kind)
                )
            }
        }
        if (result.isEmpty()) {
            throw IOException("No supported DASH BaseURL, SegmentTemplate or SegmentList representations were found")
        }
        return result
    }

    private fun expandTemplate(
        baseUrl: String,
        template: Template,
        representationId: String,
        bandwidth: Long,
        durationSeconds: Double?
    ): List<Segment> {
        val media = template.media ?: return emptyList()
        val timescale = (template.timescale ?: 1L).coerceAtLeast(1L)
        val startNumber = template.startNumber ?: 1L
        val timeline = template.timeline
        val times = if (timeline != null) {
            expandTimeline(timeline, timescale, durationSeconds)
        } else {
            val duration = template.duration ?: throw IOException("DASH SegmentTemplate has no duration or SegmentTimeline")
            if (duration <= 0L) throw IOException("DASH SegmentTemplate duration must be positive")
            val presentationDuration = durationSeconds
                ?: if (!hasNumberOrTimePlaceholder(media)) null else throw IOException("DASH segment count cannot be inferred without a finite presentation duration")
            if (presentationDuration == null) listOf(0L)
            else {
                val count = kotlin.math.ceil((presentationDuration * timescale) / duration.toDouble()).toInt()
                if (count !in 1..MAX_SEGMENTS) throw IOException("DASH template expands to too many segments")
                List(count) { it.toLong() * duration }
            }
        }

        val init = template.initialization?.let {
            Segment(resolveUrl(baseUrl, substitute(it, representationId, bandwidth, startNumber, times.firstOrNull())))
        }
        val mediaSegments = if (!hasNumberOrTimePlaceholder(media)) {
            listOf(Segment(resolveUrl(baseUrl, substitute(media, representationId, bandwidth, startNumber, times.firstOrNull()))))
        } else {
            if (times.isEmpty()) throw IOException("DASH SegmentTimeline contains no media segments")
            if (times.size > MAX_SEGMENTS) throw IOException("DASH template expands to too many segments")
            times.mapIndexed { index, time ->
                val url = substitute(media, representationId, bandwidth, startNumber + index, time)
                Segment(resolveUrl(baseUrl, url))
            }
        }
        return buildList {
            init?.let { add(it) }
            addAll(mediaSegments)
        }
    }

    private fun expandTimeline(
        entries: List<TimelineEntry>,
        timescale: Long,
        durationSeconds: Double?
    ): List<Long> {
        val result = mutableListOf<Long>()
        var current = 0L
        entries.forEachIndexed { index, entry ->
            if (entry.duration <= 0L) throw IOException("DASH SegmentTimeline has an invalid segment duration")
            if (entry.start != null) current = entry.start
            val repeats = when {
                entry.repeat >= 0 -> entry.repeat + 1
                else -> {
                    val nextStart = entries.drop(index + 1).firstOrNull { it.start != null }?.start
                    val end = nextStart ?: durationSeconds?.let { (it * timescale).toLong() }
                        ?: throw IOException("A repeating DASH SegmentTimeline needs a finite duration")
                    kotlin.math.ceil(((end - current).coerceAtLeast(0L)).toDouble() / entry.duration.toDouble()).toInt()
                }
            }
            if (repeats < 0 || result.size + repeats > MAX_SEGMENTS) {
                throw IOException("DASH SegmentTimeline expands to too many segments")
            }
            repeat(repeats) {
                result += current
                current += entry.duration
            }
        }
        return result
    }

    private fun readTemplate(element: Element): Template? {
        val node = element.child("SegmentTemplate") ?: return null
        val timeline = node.child("SegmentTimeline")?.children("S")?.map { s ->
            TimelineEntry(
                start = s.attr("t")?.toLongOrNull(),
                duration = s.attr("d")?.toLongOrNull() ?: 0L,
                repeat = s.attr("r")?.toIntOrNull() ?: 0
            )
        }?.takeIf { it.isNotEmpty() }
        return Template(
            media = node.attr("media"),
            initialization = node.attr("initialization"),
            startNumber = node.attr("startNumber")?.toLongOrNull(),
            timescale = node.attr("timescale")?.toLongOrNull(),
            duration = node.attr("duration")?.toLongOrNull(),
            timeline = timeline
        )
    }

    private fun merge(parent: Template?, child: Template?): Template? {
        if (parent == null) return child
        if (child == null) return parent
        return Template(
            media = child.media ?: parent.media,
            initialization = child.initialization ?: parent.initialization,
            startNumber = child.startNumber ?: parent.startNumber,
            timescale = child.timescale ?: parent.timescale,
            duration = child.duration ?: parent.duration,
            timeline = child.timeline ?: parent.timeline
        )
    }

    private fun expandSegmentList(baseUrl: String, list: Element): List<Segment> {
        val segments = mutableListOf<Segment>()
        list.child("Initialization")?.let { init ->
            val source = init.attr("sourceURL")
                ?: if (init.attr("range") != null) baseUrl else null
            if (source != null) {
                val range = parseRange(init.attr("range"))
                segments += Segment(resolveUrl(baseUrl, source), range?.first, range?.second)
            }
        }
        for (entry in list.children("SegmentURL")) {
            val media = entry.attr("media") ?: continue
            val range = parseRange(entry.attr("mediaRange"))
            segments += Segment(resolveUrl(baseUrl, media), range?.first, range?.second)
            if (segments.size > MAX_SEGMENTS) throw IOException("DASH SegmentList contains too many segments")
        }
        return segments
    }

    private fun parseRange(value: String?): Pair<Long, Long>? {
        if (value.isNullOrBlank()) return null
        val pieces = value.split("-")
        if (pieces.size != 2) throw IOException("Invalid DASH byte range")
        val start = pieces[0].toLongOrNull() ?: throw IOException("Invalid DASH byte-range start")
        val end = pieces[1].toLongOrNull() ?: throw IOException("Invalid DASH byte-range end")
        if (start < 0L || end < start) throw IOException("Invalid DASH byte range")
        return start to (end - start + 1L)
    }

    private fun classify(mimeType: String, contentType: String, codecs: String, width: Int, height: Int): Kind? {
        val mime = mimeType.lowercase()
        val type = contentType.lowercase()
        val codec = codecs.lowercase()
        if (type == "video" || mime.startsWith("video/") || width > 0 || height > 0 ||
            listOf("avc1", "avc3", "hev1", "hvc1", "vp09", "vp9", "av01").any { codec.contains(it) }
        ) return Kind.VIDEO
        if (type == "audio" || mime.startsWith("audio/") ||
            listOf("mp4a", "opus", "vorbis", "ac-3", "ec-3", "flac").any { codec.contains(it) }
        ) return Kind.AUDIO
        return null
    }

    private fun chooseExtension(mimeType: String, codecs: String, baseUrl: String, kind: Kind): String {
        val mime = mimeType.lowercase()
        val pathExtension = runCatching { URI(baseUrl).path.substringAfterLast('.', "").lowercase() }.getOrDefault("")
        if (pathExtension in setOf("mp4", "m4a", "webm", "mkv", "ts", "aac")) return pathExtension
        return when {
            mime.contains("webm") || codecs.contains("vp9", true) || codecs.contains("vp09", true) -> "webm"
            kind == Kind.AUDIO -> "m4a"
            else -> "mp4"
        }
    }

    private fun isDirectMediaUrl(url: String): Boolean {
        val extension = runCatching { URI(url).path.substringAfterLast('.', "").lowercase() }.getOrDefault("")
        return extension in setOf("mp4", "m4v", "m4a", "webm", "mkv", "ts", "aac", "mp3", "ogg")
    }

    private fun downloadTrack(
        representation: Representation,
        trackName: String,
        workDir: File,
        control: ResumableHttpEngine.Control,
        headers: Map<String, String>,
        onProgress: (ResumableHttpEngine.Progress) -> Unit
    ): File {
        if (representation.directUrl != null) {
            val direct = File(workDir, trackName + "." + representation.extension)
            return ResumableHttpEngine().download(representation.directUrl, direct, control, headers, onProgress).file
        }
        if (representation.segments.isEmpty()) throw IOException("The DASH representation has no downloadable segments")

        val completeBytes = AtomicLong(0L)
        val inFlight = ConcurrentHashMap<Int, AtomicLong>()
        val wasResumed = representation.segments.indices.any { index ->
            isCompleteSegment(segmentFile(workDir, trackName, index))
        }
        val poolSize = minOf(8, DownloadRuntimeOptions.segmentCount.coerceIn(2, 8), representation.segments.size).coerceAtLeast(1)
        val executor = Executors.newFixedThreadPool(poolSize)
        try {
            val futures = representation.segments.mapIndexed { index, segment ->
                executor.submit(Callable {
                    checkControl(control, File(workDir, trackName))
                    val target = segmentFile(workDir, trackName, index)
                    if (!isCompleteSegment(target)) {
                        inFlight[index] = AtomicLong(0L)
                        downloadSegment(segment, target, control, headers) { bytes ->
                            inFlight[index]?.set(bytes)
                            val total = completeBytes.get() + inFlight.values.sumOf { it.get() }
                            onProgress(ResumableHttpEngine.Progress(total, null, 0L, wasResumed))
                        }
                        inFlight.remove(index)
                    }
                    if (!isCompleteSegment(target)) throw IOException("DASH segment " + index + " did not pass its completion check")
                    completeBytes.addAndGet(target.length())
                    target
                })
            }
            futures.forEach { future ->
                try {
                    future.get()
                } catch (error: ExecutionException) {
                    val cause = error.cause ?: error
                    when (cause) {
                        is ResumableHttpEngine.PausedException -> throw cause
                        is ResumableHttpEngine.CancelledException -> throw cause
                        is IOException -> throw cause
                        else -> throw IOException("DASH segment download failed", cause)
                    }
                }
            }
        } finally {
            executor.shutdownNow()
            runCatching { executor.awaitTermination(20, TimeUnit.SECONDS) }
        }

        checkControl(control, File(workDir, trackName))
        val output = File(workDir, trackName + "." + representation.extension)
        val temporary = File(workDir, trackName + ".assembling")
        FileOutputStream(temporary).use { out ->
            representation.segments.indices.forEach { index ->
                checkControl(control, File(workDir, trackName))
                FileInputStream(segmentFile(workDir, trackName, index)).use { it.copyTo(out, BUFFER_BYTES) }
            }
            out.fd.sync()
        }
        if (temporary.length() <= 0L) throw IOException("The assembled DASH track is empty")
        if (output.exists() && !output.delete()) throw IOException("Cannot replace the previous DASH track")
        if (!temporary.renameTo(output)) copyAtomically(temporary, output)
        return output
    }

    private fun downloadSegment(
        segment: Segment,
        target: File,
        control: ResumableHttpEngine.Control,
        headers: Map<String, String>,
        onProgress: (Long) -> Unit
    ) {
        var lastError: Exception? = null
        for (attempt in 0 until MAX_SEGMENT_ATTEMPTS) {
            checkControl(control, target)
            var connection: HttpURLConnection? = null
            try {
                connection = DownloadRuntimeOptions.openConnection(segment.url).apply {
                    connectTimeout = 15_000
                    readTimeout = 45_000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty(
                        "User-Agent",
                        headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: "TurboFetch/1.0"
                    )
                    DownloadRuntimeOptions.applyHeaders(this, headers)
                    if (segment.rangeOffset != null && segment.rangeLength != null) {
                        setRequestProperty("Range", "bytes=" + segment.rangeOffset + "-" +
                            (segment.rangeOffset + segment.rangeLength - 1L))
                    }
                    connect()
                }
                val code = connection.responseCode
                if (code == 429 || code == 503) {
                    throw RateLimitedException("HTTP " + code + " while fetching a DASH segment",
                        connection.getHeaderField("Retry-After")?.toLongOrNull())
                }
                if (code !in 200..299) throw IOException("HTTP " + code + " while fetching a DASH segment")
                if (segment.rangeOffset != null && segment.rangeLength != null) {
                    if (code != HttpURLConnection.HTTP_PARTIAL) throw IOException("The server ignored a DASH byte-range request")
                    val expectedStart = segment.rangeOffset
                    val expectedEnd = expectedStart + segment.rangeLength - 1L
                    val range = Regex("^bytes\\s+(\\d+)-(\\d+)/(?:\\d+|\\*)$", RegexOption.IGNORE_CASE)
                        .find(connection.getHeaderField("Content-Range").orEmpty())
                        ?: throw IOException("The server returned an invalid DASH Content-Range header")
                    if (range.groupValues[1].toLongOrNull() != expectedStart ||
                        range.groupValues[2].toLongOrNull() != expectedEnd
                    ) throw IOException("The server returned an unexpected DASH byte range")
                }

                var written = 0L
                val temporary = File(target.parentFile, target.name + ".tmp")
                connection.inputStream.use { input ->
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            checkControl(control, target)
                            if (Thread.currentThread().isInterrupted) throw IOException("DASH segment worker interrupted")
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (n == 0) continue
                            output.write(buffer, 0, n)
                            written += n
                            onProgress(written)
                        }
                        output.fd.sync()
                    }
                }
                if (segment.rangeLength != null && written != segment.rangeLength) {
                    throw IOException("DASH byte-range segment was incomplete")
                }
                if (written <= 0L) throw IOException("DASH server returned an empty segment")
                if (target.exists() && !target.delete()) throw IOException("Cannot replace DASH segment file")
                if (!temporary.renameTo(target)) copyAtomically(temporary, target)
                File(target.parentFile, target.name + ".done").writeText(target.length().toString())
                return
            } catch (error: ResumableHttpEngine.PausedException) {
                throw error
            } catch (error: ResumableHttpEngine.CancelledException) {
                throw error
            } catch (error: Exception) {
                lastError = error
                runCatching { File(target.parentFile, target.name + ".tmp").delete() }
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
        throw IOException("DASH segment failed after " + MAX_SEGMENT_ATTEMPTS + " attempts: " +
            (lastError?.message ?: "unknown network error"), lastError)
    }

    private fun isCompleteSegment(file: File): Boolean {
        val marker = File(file.parentFile, file.name + ".done")
        if (!file.isFile || !marker.isFile) return false
        val expected = runCatching { marker.readText().trim().toLongOrNull() }.getOrNull() ?: return false
        return expected > 0L && file.length() == expected
    }

    private fun segmentFile(workDir: File, trackName: String, index: Int): File =
        File(workDir, trackName + "-segment-" + index.toString().padStart(6, '0') + ".bin")

    private fun childBaseUrl(element: Element, inherited: String): String {
        val value = element.child("BaseURL")?.textContent?.trim().orEmpty()
        return if (value.isBlank()) inherited else resolveUrl(inherited, value)
    }

    private fun classify(mimeType: String, contentType: String, codecs: String, width: Int, height: Int): Kind? {
        val mime = mimeType.lowercase()
        val type = contentType.lowercase()
        val codec = codecs.lowercase()
        if (type == "video" || mime.startsWith("video/") || width > 0 || height > 0 ||
            listOf("avc1", "avc3", "hev1", "hvc1", "vp09", "vp9", "av01").any { codec.contains(it) }
        ) return Kind.VIDEO
        if (type == "audio" || mime.startsWith("audio/") ||
            listOf("mp4a", "opus", "vorbis", "ac-3", "ec-3", "flac").any { codec.contains(it) }
        ) return Kind.AUDIO
        return null
    }

    private fun chooseExtension(mimeType: String, codecs: String, baseUrl: String, kind: Kind): String {
        val mime = mimeType.lowercase()
        val pathExtension = runCatching { URI(baseUrl).path.substringAfterLast('.', "").lowercase() }.getOrDefault("")
        if (pathExtension in setOf("mp4", "m4a", "webm", "mkv", "ts", "aac")) return pathExtension
        return when {
            mime.contains("webm") || codecs.contains("vp9", true) || codecs.contains("vp09", true) -> "webm"
            kind == Kind.AUDIO -> "m4a"
            else -> "mp4"
        }
    }

    private fun isDirectMediaUrl(url: String): Boolean {
        val extension = runCatching { URI(url).path.substringAfterLast('.', "").lowercase() }.getOrDefault("")
        return extension in setOf("mp4", "m4v", "m4a", "webm", "mkv", "ts", "aac", "mp3", "ogg")
    }

    private fun parseDuration(value: String?): Double? {
        if (value.isNullOrBlank()) return null
        val match = Regex("""P(?:(\d+(?:\.\d+)?)D)?(?:T(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?)?""")
            .matchEntire(value.trim()) ?: return null
        val days = match.groupValues[1].toDoubleOrNull() ?: 0.0
        val hours = match.groupValues[2].toDoubleOrNull() ?: 0.0
        val minutes = match.groupValues[3].toDoubleOrNull() ?: 0.0
        val seconds = match.groupValues[4].toDoubleOrNull() ?: 0.0
        return (days * 86_400.0 + hours * 3_600.0 + minutes * 60.0 + seconds).takeIf { it > 0.0 }
    }

    private fun substitute(template: String, id: String, bandwidth: Long, number: Long?, time: Long?): String {
        val sentinel = "\u0000"
        var out = template.replace("\$\$", sentinel)
            .replace("\$RepresentationID\$", id)
            .replace("\$Bandwidth\$", bandwidth.toString())
        val numberPattern = Regex(Regex.escape("$" + "Number") + "(%0(\\d+)d)?" + Regex.escape("$"))
        out = numberPattern.replace(out) { m ->
            if (number == null) m.value else {
                val width = m.groupValues[2].toIntOrNull()
                if (width != null) number.toString().padStart(width, '0') else number.toString()
            }
        }
        val timePattern = Regex(Regex.escape("$" + "Time") + "(%0(\\d+)d)?" + Regex.escape("$"))
        out = timePattern.replace(out) { m ->
            if (time == null) m.value else {
                val width = m.groupValues[2].toIntOrNull()
                if (width != null) time.toString().padStart(width, '0') else time.toString()
            }
        }
        return out.replace(sentinel, "$")
    }

    private fun hasNumberOrTimePlaceholder(value: String): Boolean =
        value.contains("$" + "Number") || value.contains("$" + "Time")

    private fun resolveUrl(base: String, relative: String): String {
        val value = relative.trim().trim('"')
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) return value
        return try {
            URI(base).resolve(value).toString()
        } catch (error: Exception) {
            throw IOException("Unable to resolve DASH segment URL", error)
        }
    }

    private fun copyAtomically(source: File, destination: File) {
        val temporary = File(destination.parentFile, destination.name + ".tmp")
        FileInputStream(source).use { input ->
            FileOutputStream(temporary).use { output -> input.copyTo(output, BUFFER_BYTES); output.fd.sync() }
        }
        if (destination.exists() && !destination.delete()) {
            temporary.delete()
            throw IOException("Cannot replace an existing DASH output file")
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            throw IOException("Cannot finalize assembled DASH output")
        }
    }

    private fun checkControl(control: ResumableHttpEngine.Control, partialFile: File) {
        if (control.isCancelled()) throw ResumableHttpEngine.CancelledException(partialFile)
        if (control.isPaused()) throw ResumableHttpEngine.PausedException(partialFile)
    }

    private fun sleepWithControl(milliseconds: Long, control: ResumableHttpEngine.Control, file: File) {
        var remaining = milliseconds.coerceAtLeast(0L)
        while (remaining > 0L) {
            checkControl(control, file)
            val slice = minOf(remaining, 200L)
            try {
                Thread.sleep(slice)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                checkControl(control, file)
                throw IOException("Interrupted while retrying a DASH segment", error)
            }
            remaining -= slice
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun Element.localNameOrTag(): String = (localName ?: tagName).substringAfter(':')
    private fun Element.attr(name: String): String? = getAttribute(name).takeIf { it.isNotBlank() }
    private fun Element.children(name: String): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
            .filter { it.localNameOrTag() == name }
    private fun Element.child(name: String): Element? = children(name).firstOrNull()
    private fun Element.descendants(): List<Element> {
        val result = mutableListOf<Element>()
        fun collect(parent: Element) {
            for (i in 0 until parent.childNodes.length) {
                val child = parent.childNodes.item(i) as? Element ?: continue
                result += child
                collect(child)
            }
        }
        collect(this)
        return result
    }
}
