package com.fiveydr.turbofetch

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Common classification and variant-grouping helpers for browser artifact discovery.
 *
 * This deliberately handles discoverable URLs only. It does not claim to extract
 * protected streams or merge separate DASH/HLS audio and video tracks.
 */
internal object MediaArtifactTools {
    enum class Kind(val label: String) {
        IMAGE("Image"), VIDEO("Video"), AUDIO("Audio"), DOCUMENT("Document"),
        ARCHIVE("Archive"), APP("Application"), STREAM("Streaming manifest"), OTHER("Other")
    }

    data class Artifact(
        val url: String,
        val title: String,
        val kind: Kind,
        val mimeType: String = "",
        val qualityHeight: Int? = null,
        val bitrateKbps: Int? = null,
        val format: String = "",
        val fileSizeBytes: Long? = null
    ) {
        val qualityLabel: String
            get() = when {
                qualityHeight != null -> "${qualityHeight}p"
                bitrateKbps != null -> "${bitrateKbps} kbps"
                else -> format.ifBlank { kind.label }
            }
    }

    private val extensionKinds = mapOf(
        "jpg" to Kind.IMAGE, "jpeg" to Kind.IMAGE, "png" to Kind.IMAGE,
        "gif" to Kind.IMAGE, "webp" to Kind.IMAGE, "avif" to Kind.IMAGE,
        "bmp" to Kind.IMAGE, "svg" to Kind.IMAGE, "tif" to Kind.IMAGE, "tiff" to Kind.IMAGE,
        "mp4" to Kind.VIDEO, "m4v" to Kind.VIDEO, "mkv" to Kind.VIDEO,
        "mov" to Kind.VIDEO, "webm" to Kind.VIDEO, "avi" to Kind.VIDEO, "3gp" to Kind.VIDEO,
        "mp3" to Kind.AUDIO, "m4a" to Kind.AUDIO, "aac" to Kind.AUDIO,
        "flac" to Kind.AUDIO, "wav" to Kind.AUDIO, "ogg" to Kind.AUDIO, "opus" to Kind.AUDIO,
        "pdf" to Kind.DOCUMENT, "doc" to Kind.DOCUMENT, "docx" to Kind.DOCUMENT,
        "odt" to Kind.DOCUMENT, "rtf" to Kind.DOCUMENT, "txt" to Kind.DOCUMENT,
        "csv" to Kind.DOCUMENT, "xls" to Kind.DOCUMENT, "xlsx" to Kind.DOCUMENT,
        "ods" to Kind.DOCUMENT, "ppt" to Kind.DOCUMENT, "pptx" to Kind.DOCUMENT,
        "odp" to Kind.DOCUMENT, "epub" to Kind.DOCUMENT, "mobi" to Kind.DOCUMENT,
        "json" to Kind.DOCUMENT, "xml" to Kind.DOCUMENT, "yaml" to Kind.DOCUMENT, "yml" to Kind.DOCUMENT,
        "log" to Kind.DOCUMENT, "md" to Kind.DOCUMENT, "markdown" to Kind.DOCUMENT, "ics" to Kind.DOCUMENT,
        "pages" to Kind.DOCUMENT, "numbers" to Kind.DOCUMENT, "key" to Kind.DOCUMENT, "psd" to Kind.DOCUMENT,
        "zip" to Kind.ARCHIVE, "rar" to Kind.ARCHIVE, "7z" to Kind.ARCHIVE,
        "tar" to Kind.ARCHIVE, "gz" to Kind.ARCHIVE, "bz2" to Kind.ARCHIVE,
        "xz" to Kind.ARCHIVE, "zst" to Kind.ARCHIVE, "iso" to Kind.ARCHIVE,
        "apk" to Kind.APP, "aab" to Kind.APP, "exe" to Kind.APP, "msi" to Kind.APP,
        "dmg" to Kind.APP, "pkg" to Kind.APP, "deb" to Kind.APP, "rpm" to Kind.APP,
        "m3u8" to Kind.STREAM, "mpd" to Kind.STREAM
    )

    fun classify(url: String, mimeType: String = ""): Kind {
        val mime = mimeType.substringBefore(';').trim().lowercase(Locale.ROOT)
        when {
            mime.startsWith("image/") -> return Kind.IMAGE
            mime.startsWith("video/") -> return Kind.VIDEO
            mime.startsWith("audio/") -> return Kind.AUDIO
            mime == "application/pdf" ||
                mime.contains("officedocument") ||
                mime.contains("msword") ||
                mime.contains("spreadsheet") ||
                mime.contains("presentation") ||
                mime.startsWith("text/") -> return Kind.DOCUMENT
            mime.contains("zip") || mime.contains("compressed") || mime.contains("x-7z") -> return Kind.ARCHIVE
        }
        val path = runCatching { URI(url).rawPath.orEmpty() }.getOrDefault("")
        val ext = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extensionKinds[ext] ?: Kind.OTHER
    }

    fun artifact(url: String, title: String = "", mimeType: String = ""): Artifact? {
        if (!DownloadLinkTools.isHttpUrl(url)) return null
        val kind = classify(url, mimeType)
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val fileName = uri.rawPath.orEmpty().substringAfterLast('/').ifBlank { "download" }
        val decodedName = runCatching {
            URLDecoder.decode(fileName.replace("+", "%2B"), StandardCharsets.UTF_8.name())
        }.getOrDefault(fileName)
        val label = title.trim().ifBlank { decodedName }
        val quality = qualityHeightFrom(label + " " + url)
        val bitrate = bitrateFrom(label + " " + url)
        val ext = uri.rawPath.orEmpty().substringAfterLast('.', "").uppercase(Locale.ROOT)
        return Artifact(url, label, kind, mimeType, quality, bitrate, ext)
    }

    /** Finds common resolution markers such as 2160p, 1080p, 720p or 480p. */
    fun qualityHeightFrom(text: String): Int? {
        val match = Regex("""(?i)(?<!\d)(2160|1440|1080|900|720|576|540|480|360|240|144)\s*p\b""")
            .find(text) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    /** Finds bitrate labels such as 128kbps or 1.5 Mbps. */
    fun bitrateFrom(text: String): Int? {
        val match = Regex("""(?i)(\d+(?:[.,]\d+)?)\s*(k|m)?\s*bits?/s|(?i)(\d+(?:[.,]\d+)?)\s*(kbps|mbps)""")
            .find(text) ?: return null
        val number = (match.groupValues[1].ifBlank { match.groupValues[3] })
            .replace(',', '.').toDoubleOrNull() ?: return null
        val unit = (match.groupValues[2].ifBlank { match.groupValues[4] }).lowercase(Locale.ROOT)
        return (number * if (unit.startsWith("m")) 1000 else 1).toInt()
    }

    /**
     * Browser-side scanner for directly exposed page assets. Results are JSON and must
     * be treated as untrusted input by the native bridge before queueing.
     */
    const val PAGE_SCAN_SCRIPT: String = """(function() {
      const out = new Map();
      const add = (url, title, mime) => {
        try {
          const u = new URL(url, location.href);
          if (!['http:', 'https:'].includes(u.protocol)) return;
          if (u.href.startsWith('blob:') || u.href.startsWith('data:')) return;
          const key = u.href;
          if (!out.has(key)) out.set(key, {url:key, title:(title || '').trim(), mimeType:mime || ''});
        } catch (_) {}
      };
      const fileLike = /\.(?:avif|bmp|csv|doc|docx|epub|flac|gif|jpeg|jpg|m4a|m4v|mkv|mov|mp3|mp4|ogg|pdf|png|ppt|pptx|rtf|svg|tif|tiff|txt|wav|webm|webp|xls|xlsx|odt|ods|odp|zip|rar|7z)(?:$|[?#])/i;
      document.querySelectorAll('a[href], img[src], img[srcset], video[src], audio[src], source[src], link[rel="download"][href]').forEach(el => {
        const tag = el.tagName.toLowerCase();
        const declaredMime = el.getAttribute('type') || '';
        const href = el.getAttribute('href') || '';
        const title = el.getAttribute('download') || el.getAttribute('alt') ||
          el.getAttribute('title') || el.getAttribute('aria-label') || '';
        if (tag === 'a' && !el.hasAttribute('download') && !fileLike.test(href) &&
            !/^(?:image|video|audio)\//i.test(declaredMime) &&
            !/application\/(?:pdf|msword|vnd\.openxmlformats|vnd\.ms-|vnd\.oasis)/i.test(declaredMime)) return;
        const inferredMime = declaredMime || (tag === 'img' ? 'image/*' : tag === 'video' || tag === 'source' ? 'video/*' : tag === 'audio' ? 'audio/*' : '');
        if (tag === 'img') {
          const width = el.naturalWidth || el.width || 0;
          const height = el.naturalHeight || el.height || 0;
          if (width > 0 && height > 0 && width < 160 && height < 120) return;
        }
        ['href','src'].forEach(attr => {
          if (!el.getAttribute(attr)) return;
          add(el.getAttribute(attr), title || document.title, inferredMime);
        });
        if (el.getAttribute('srcset')) el.getAttribute('srcset').split(',').forEach(item => add(item.trim().split(/\s+/)[0], title || document.title, 'image/*'));
      });
      document.querySelectorAll('video').forEach(v => {
        if (v.currentSrc) add(v.currentSrc, v.getAttribute('title') || document.title, 'video/*');
      });
      return JSON.stringify({page:location.href, title:document.title, artifacts:Array.from(out.values())});
    })()"""
}
