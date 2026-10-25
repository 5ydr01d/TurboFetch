package com.fiveydr.turbofetch

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import java.util.Locale

/**
 * Uses yt-dlp to inspect supported video pages, but returns only directly queueable
 * formats. TurboFetch's own queue handles the actual HTTP transfer.
 *
 * Video-only DASH variants are intentionally excluded because the current queue does not
 * mux a separate audio stream into them.
 */
internal object VideoSiteExtractor {
    data class Format(
        val id: String,
        val label: String,
        val url: String,
        val extension: String,
        val height: Int,
        val bitrateKbps: Int,
        val hasVideo: Boolean,
        val hasAudio: Boolean,
        val fileSize: Long,
        val headers: Map<String, String>
    ) {
    }

    data class Inspection(val title: String, val formats: List<Format>)

    @Volatile private var initialized = false

    private fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                YoutubeDL.getInstance().init(context.applicationContext)
                initialized = true
            }
        }
    }

    fun inspect(context: Context, pageUrl: String): Inspection {
        require(DownloadLinkTools.isHttpUrl(pageUrl)) { "Enter a valid HTTP/HTTPS video page URL." }
        ensureInitialized(context)
        val info = YoutubeDL.getInstance().getInfo(pageUrl)
        val title = read(info, "title")?.toString().orEmpty().ifBlank { "Video" }
        val rawFormats = read(info, "formats") as? Iterable<*> ?: emptyList<Any>()
        val formats = rawFormats.mapNotNull { raw ->
            if (raw == null) return@mapNotNull null
            val directUrl = read(raw, "url")?.toString().orEmpty()
            if (!DownloadLinkTools.isHttpUrl(directUrl)) return@mapNotNull null
            val id = read(raw, "formatId", "format_id")?.toString().orEmpty()
            val ext = read(raw, "ext")?.toString().orEmpty()
            val height = number(read(raw, "height")).toInt()
                .takeIf { it > 0 }
                ?: qualityHeightFrom(read(raw, "resolution")?.toString().orEmpty())
                ?: 0
            val bitrate = number(read(raw, "tbr", "abr")).toInt().coerceAtLeast(0)
            val vcodec = read(raw, "vcodec")?.toString().orEmpty()
            val acodec = read(raw, "acodec")?.toString().orEmpty()
            val hasVideo = vcodec.isNotBlank() && !vcodec.equals("none", true)
            val hasAudio = acodec.isNotBlank() && !acodec.equals("none", true)
            val size = number(read(raw, "filesize", "filesizeApprox", "filesize_approx")).toLong().coerceAtLeast(0)
            val note = read(raw, "formatNote", "format_note")?.toString().orEmpty()
            val quality = if (height > 0) "${height}p" else note.ifBlank { "Audio / unknown quality" }
            val type = when {
                hasVideo && hasAudio -> "video + audio"
                hasVideo -> "video only"
                hasAudio -> "audio only"
                else -> "unknown codecs"
            }
            val sizeText = if (size > 0) " · ${formatBytes(size)}" else ""
            val bitrateText = if (bitrate > 0) " · ${bitrate} kb/s" else ""
            val label = listOf(quality, ext.uppercase(Locale.ROOT).ifBlank { "stream" }, type)
                .joinToString(" · ") + bitrateText + sizeText
            val rawHeaders = read(raw, "httpHeaders", "http_headers") as? Map<*, *> ?: emptyMap<Any, Any>()
            val allowedHeaders = setOf("user-agent", "referer", "origin", "cookie", "accept", "accept-language")
            val headers = rawHeaders.entries.mapNotNull { entry ->
                val key = entry.key?.toString()?.trim().orEmpty()
                val value = entry.value?.toString()?.trim().orEmpty()
                if (key.lowercase(Locale.ROOT) in allowedHeaders && value.isNotBlank()) key to value else null
            }.toMap()
            Format(id, label, directUrl, ext, height, bitrate, hasVideo, hasAudio, size, headers)
        }.distinctBy { it.url }
            // Keep audio-bearing formats: progressive video+audio or audio-only.
            .filter { it.hasAudio }
            .sortedWith(compareByDescending<Format> { it.hasVideo }.thenByDescending { it.height }.thenByDescending { it.bitrateKbps })
        return Inspection(title, formats)
    }

    private fun read(instance: Any, vararg names: String): Any? {
        for (name in names) {
            val cap = name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
            val getterNames = listOf("get$cap", "is$cap", name)
            for (getterName in getterNames) {
                val methodValue = runCatching {
                    instance.javaClass.methods.firstOrNull { it.name == getterName && it.parameterCount == 0 }?.invoke(instance)
                }.getOrNull()
                if (methodValue != null) return methodValue
            }
            val fieldValue = runCatching {
                instance.javaClass.fields.firstOrNull { it.name == name }?.get(instance)
            }.getOrNull()
            if (fieldValue != null) return fieldValue
        }
        return null
    }

    private fun qualityHeightFrom(value: String): Int? {
        val normalized = value.trim().lowercase(Locale.ROOT)
        val explicit = Regex("""(\\d{3,4})\\s*p""").find(normalized)?.groupValues?.get(1)?.toIntOrNull()
        if (explicit != null) return explicit
        return Regex("""\\d+\\s*[x×]\\s*(\\d{3,4})""").find(normalized)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun number(value: Any?): Double = when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
