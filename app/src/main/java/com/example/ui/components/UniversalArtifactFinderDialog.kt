package com.example.ui.components

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fiveydr.turbofetch.MediaArtifactTools
import com.fiveydr.turbofetch.VideoSiteExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.util.Locale

private enum class ArtifactSort(val label: String) {
    RECOMMENDED("Recommended"),
    SMALLEST("Smallest first"),
    LARGEST("Largest first")
}

@Composable
fun UniversalArtifactFinderDialog(
    pageUrl: String,
    onDismiss: () -> Unit,
    onDownload: (url: String, userAgent: String, referer: String, cookie: String) -> Unit
) {
    val context = LocalContext.current
    var artifacts by remember(pageUrl) { mutableStateOf<List<MediaArtifactTools.Artifact>>(emptyList()) }
    var formats by remember(pageUrl) { mutableStateOf<List<VideoSiteExtractor.Format>>(emptyList()) }
    var scanMessage by remember(pageUrl) { mutableStateOf("Finding downloadable media and documents…") }
    var videoMessage by remember(pageUrl) { mutableStateOf("") }
    var loadingVideo by remember(pageUrl) { mutableStateOf(false) }
    var probingSizes by remember(pageUrl) { mutableStateOf(false) }
    var selectedKind by remember(pageUrl) { mutableStateOf<MediaArtifactTools.Kind?>(null) }
    var sortMode by remember(pageUrl) { mutableStateOf(ArtifactSort.RECOMMENDED) }
    var inspectStreamUrl by remember(pageUrl) { mutableStateOf("") }
    var streamFormats by remember(pageUrl) { mutableStateOf<List<VideoSiteExtractor.Format>>(emptyList()) }
    var streamInspectionMessage by remember(pageUrl) { mutableStateOf("") }
    var inspectingStream by remember(pageUrl) { mutableStateOf(false) }

    LaunchedEffect(pageUrl) {
        val prefs = context.getSharedPreferences("turbofetch_browser_artifacts", Context.MODE_PRIVATE)
        val savedJson = prefs.getString("turbofetch_artifact_scan_json", null)
        val savedUrl = prefs.getString("turbofetch_artifact_scan_url", null)
        val allowedKinds = setOf(
            MediaArtifactTools.Kind.IMAGE,
            MediaArtifactTools.Kind.VIDEO,
            MediaArtifactTools.Kind.AUDIO,
            MediaArtifactTools.Kind.DOCUMENT,
            MediaArtifactTools.Kind.STREAM
        )
        val found = runCatching {
            if (savedJson.isNullOrBlank() || savedUrl != pageUrl) emptyList()
            else {
                val root = JSONObject(savedJson)
                val array = root.optJSONArray("artifacts") ?: JSONArray()
                buildList {
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        val url = item.optString("url")
                        val title = item.optString("title")
                        val mime = item.optString("mimeType")
                        MediaArtifactTools.artifact(url, title, mime)
                            ?.takeIf { it.kind in allowedKinds }
                            ?.let { add(it) }
                    }
                }.distinctBy { it.url }
            }
        }.getOrDefault(emptyList())
        artifacts = found
        scanMessage = if (found.isEmpty()) {
            "No direct images, audio, video, or documents were found. Some sites protect media or expose it only as segmented streams."
        } else {
            "${found.size} useful item(s) found"
        }

        // Probe only a bounded number of direct assets. Failed HEAD requests simply leave size unknown.
        if (found.isNotEmpty()) {
            probingSizes = true
            val sized = withContext(Dispatchers.IO) {
                coroutineScope {
                    found.take(40).map { artifact ->
                        async {
                            val size = probeContentLength(artifact.url)
                            artifact.copy(fileSizeBytes = size)
                        }
                    }.awaitAll() + found.drop(40)
                }
            }
            artifacts = sized
            probingSizes = false
        }

        val host = runCatching { android.net.Uri.parse(pageUrl).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        val likelyVideo = host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtu.be" || host.endsWith(".vimeo.com") ||
            host == "dailymotion.com" || host.endsWith(".dailymotion.com") ||
            host == "twitch.tv" || host.endsWith(".twitch.tv")
        if (likelyVideo) {
            loadingVideo = true
            videoMessage = "Checking available video and audio qualities…"
            try {
                formats = withContext(Dispatchers.IO) {
                    VideoSiteExtractor.inspect(context.applicationContext, pageUrl).formats
                }
                videoMessage = if (formats.isEmpty()) "No directly downloadable audio-bearing formats were returned for this page." else ""
            } catch (e: Exception) {
                videoMessage = "Quality extraction unavailable for this page: ${e.message ?: "unsupported or restricted media"}"
            } finally {
                loadingVideo = false
            }
        }
    }

    LaunchedEffect(inspectStreamUrl) {
        if (inspectStreamUrl.isBlank()) return@LaunchedEffect
        inspectingStream = true
        streamInspectionMessage = "Inspecting embedded stream for downloadable qualities…"
        streamFormats = emptyList()
        try {
            val inspection = withContext(Dispatchers.IO) {
                VideoSiteExtractor.inspect(context.applicationContext, inspectStreamUrl)
            }
            streamFormats = inspection.formats
            streamInspectionMessage = if (inspection.formats.isEmpty()) {
                "The stream was detected, but no directly downloadable audio-bearing formats were found. It may require site cookies or protected playback."
            } else {
                "Found ${inspection.formats.size} downloadable quality option(s)."
            }
        } catch (e: Exception) {
            streamInspectionMessage = "Could not extract this stream: ${e.message ?: "the site may require protected playback"}"
        } finally {
            inspectingStream = false
        }
    }

    val filteredArtifacts = artifacts
        .filter { selectedKind == null || it.kind == selectedKind || (selectedKind == MediaArtifactTools.Kind.VIDEO && it.kind == MediaArtifactTools.Kind.STREAM) }
        .let { list ->
            when (sortMode) {
                ArtifactSort.RECOMMENDED -> list
                ArtifactSort.SMALLEST -> list.sortedWith(
                    compareBy<MediaArtifactTools.Artifact> { it.fileSizeBytes == null }
                        .thenBy { it.fileSizeBytes ?: Long.MAX_VALUE }
                )
                ArtifactSort.LARGEST -> list.sortedWith(
                    compareBy<MediaArtifactTools.Artifact> { it.fileSizeBytes == null }
                        .thenByDescending { it.fileSizeBytes ?: 0L }
                )
            }
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Universal Artifact Downloader") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(pageUrl, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(scanMessage, style = MaterialTheme.typography.bodyMedium)

                Text("Type", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(selected = selectedKind == null, onClick = { selectedKind = null }, label = { Text("All") })
                    listOf(
                        MediaArtifactTools.Kind.IMAGE,
                        MediaArtifactTools.Kind.AUDIO,
                        MediaArtifactTools.Kind.VIDEO,
                        MediaArtifactTools.Kind.DOCUMENT
                    ).forEach { kind ->
                        FilterChip(
                            selected = selectedKind == kind,
                            onClick = { selectedKind = if (selectedKind == kind) null else kind },
                            label = { Text(kind.label) }
                        )
                    }
                }

                Text("Sort by size", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ArtifactSort.values().forEach { mode ->
                        FilterChip(
                            selected = sortMode == mode,
                            onClick = { sortMode = mode },
                            label = { Text(mode.label) }
                        )
                    }
                }
                if (probingSizes) {
                    Text("Checking file sizes where the server allows it…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                if (filteredArtifacts.isEmpty() && artifacts.isNotEmpty()) {
                    Text("No items match this type filter.", style = MaterialTheme.typography.bodyMedium)
                }
                filteredArtifacts.forEach { artifact ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(artifact.title.ifBlank { artifact.kind.label }, style = MaterialTheme.typography.titleSmall)
                        val sizeText = artifact.fileSizeBytes?.let(::formatSize) ?: "Size unknown"
                        Text(
                            "${artifact.kind.label} · ${artifact.qualityLabel} · $sizeText",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(artifact.url, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (artifact.kind == MediaArtifactTools.Kind.STREAM) {
                            OutlinedButton(
                                onClick = { inspectStreamUrl = artifact.url },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !inspectingStream
                            ) {
                                Text(if (inspectingStream && inspectStreamUrl == artifact.url) "Inspecting stream…" else "Find video qualities")
                            }
                        } else {
                            OutlinedButton(
                                onClick = { onDownload(artifact.url, "", pageUrl, "") },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Download ${artifact.kind.label.lowercase(Locale.ROOT)}") }
                        }
                    }
                    HorizontalDivider()
                }

                if (loadingVideo) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator()
                        Text(videoMessage, style = MaterialTheme.typography.bodySmall)
                    }
                } else if (videoMessage.isNotBlank()) {
                    Text(videoMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (inspectStreamUrl.isNotBlank()) {
                    Text(streamInspectionMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (inspectingStream) CircularProgressIndicator()
                    streamFormats.forEach { format ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(format.label, style = MaterialTheme.typography.titleSmall)
                            Button(
                                onClick = {
                                    val headers = format.headers
                                    fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value.orEmpty()
                                    onDownload(format.url, header("User-Agent"), header("Referer").ifBlank { pageUrl }, header("Cookie"))
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(if (format.hasVideo) "Download video quality" else "Download audio quality") }
                        }
                        HorizontalDivider()
                    }
                }

                formats.forEach { format ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(format.label, style = MaterialTheme.typography.titleSmall)
                        Button(
                            onClick = {
                                val headers = format.headers
                                fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value.orEmpty()
                                onDownload(format.url, header("User-Agent"), header("Referer").ifBlank { pageUrl }, header("Cookie"))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (format.hasVideo) "Download video quality" else "Download audio quality") }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

private fun probeContentLength(url: String): Long? {
    val connection = (URL(url).openConnection() as? HttpURLConnection) ?: return null
    return try {
        connection.requestMethod = "HEAD"
        connection.connectTimeout = 2500
        connection.readTimeout = 2500
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        connection.connect()
        val size = connection.contentLengthLong
        if (connection.responseCode in 200..299 && size > 0L) size else null
    } catch (_: Exception) {
        null
    } finally {
        connection.disconnect()
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
