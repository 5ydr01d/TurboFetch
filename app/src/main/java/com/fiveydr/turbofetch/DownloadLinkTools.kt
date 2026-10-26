package com.fiveydr.turbofetch

import java.net.URI
import java.net.URL

internal object DownloadLinkTools {
    private val candidatePattern = Regex("""(?i)\bhttps?://[^\s<>"']+""")

    private val videoExtensions = setOf(
        "mp4", "m4v", "mkv", "mov", "webm", "avi", "flv", "wmv", "3gp", "ts", "m3u8", "mpd"
    )
    private val documentExtensions = setOf(
        "pdf", "doc", "docx", "odt", "rtf", "txt", "csv", "xls", "xlsx", "ppt", "pptx", "epub"
    )

    fun extractUrls(text: String): List<String> {
        val found = LinkedHashSet<String>()
        candidatePattern.findAll(text).forEach { match ->
            val candidate = match.value.trim().trimEnd('.', ',', ';', ':', ')', ']', '}', '>')
            if (isHttpUrl(candidate)) found.add(candidate)
        }
        return found.toList()
    }

    fun isHttpUrl(value: String): Boolean {
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return false
        return (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
            !uri.host.isNullOrBlank()
    }

    /**
     * True only for video/stream or document URLs. Archives, installers, images, audio, and
     * generic URLs are excluded to avoid unwanted clipboard prompts.
     */
    fun isVideoOrDocumentUrl(value: String): Boolean {
        if (!isHttpUrl(value)) return false
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return false
        val extension = uri.path.orEmpty().substringAfterLast('/', "")
            .substringAfterLast('.', "")
            .lowercase()
        return extension in videoExtensions || extension in documentExtensions
    }

    fun fileNameFromUrl(value: String): String {
        val path = runCatching { URL(value).path }.getOrDefault("")
        val candidate = path.substringAfterLast('/').takeIf { it.isNotBlank() } ?: "download.bin"
        val safe = candidate.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_").trim().trim('.')
        return safe.take(180).ifBlank { "download.bin" }
    }

    /**
     * Keeps in-progress transfer files isolated per queue item. Display names may legitimately
     * repeat, but their .part, validator, and segmented scratch files must never collide.
     */
    fun transferFileName(downloadId: Long, displayName: String): String {
        val safe = displayName
            .replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_")
            .trim()
            .trim('.')
            .take(180)
            .ifBlank { "download.bin" }
        return "$downloadId-$safe"
    }

    fun likelyMediaOrFile(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val path = uri.path.orEmpty().lowercase()
        val suffix = listOf(
            ".apk", ".zip", ".rar", ".7z", ".gz", ".tar", ".pdf", ".exe", ".dmg",
            ".mp4", ".m4v", ".mkv", ".mov", ".webm", ".mp3", ".m4a", ".aac", ".flac",
            ".wav", ".ogg", ".opus", ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp",
            ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".txt", ".csv", ".epub",
            ".iso", ".bin", ".m3u", ".m3u8", ".mpd", ".ts"
        )
        return suffix.any { path.endsWith(it) } || uri.rawQuery.orEmpty().contains("download=", true)
    }
}
