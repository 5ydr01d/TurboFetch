package com.example.ui.components

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
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray

@Composable
fun UniversalArtifactFinderDialog(
    pageUrl: String,
    onDismiss: () -> Unit,
    onDownload: (url: String, userAgent: String, referer: String, cookie: String) -> Unit
) {
    val context = LocalContext.current
    var artifacts by remember(pageUrl) { mutableStateOf<List<MediaArtifactTools.Artifact>>(emptyList()) }
    var formats by remember(pageUrl) { mutableStateOf<List<VideoSiteExtractor.Format>>(emptyList()) }
    var scanMessage by remember(pageUrl) { mutableStateOf("Loading assets found on this page…") }
    var videoMessage by remember(pageUrl) { mutableStateOf("") }
    var loadingVideo by remember(pageUrl) { mutableStateOf(false) }

    LaunchedEffect(pageUrl) {
        val prefs = context.getSharedPreferences("turbofetch_browser_artifacts", android.content.Context.MODE_PRIVATE)
        val savedJson = prefs.getString("turbofetch_artifact_scan_json", null)
        val savedUrl = prefs.getString("turbofetch_artifact_scan_url", null)
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
                        MediaArtifactTools.artifact(url, title, mime)?.let { add(it) }
                    }
                }.distinctBy { it.url }
            }
        }.getOrDefault(emptyList())
        artifacts = found
        scanMessage = if (found.isEmpty()) {
            "No direct downloadable assets were found on this page. Some sites hide media behind protected or segmented streams."
        } else {
            "${found.size} asset(s) found on this page"
        }

        val likelyVideo = pageUrl.contains("youtube.com", true) ||
            pageUrl.contains("youtu.be", true) || pageUrl.contains("vimeo.com", true) ||
            pageUrl.contains("dailymotion.com", true) || pageUrl.contains("twitch.tv", true)
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Universal Artifact Downloader") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(pageUrl, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(scanMessage, style = MaterialTheme.typography.bodyMedium)
                artifacts.forEach { artifact ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(artifact.title.ifBlank { artifact.kind.label }, style = MaterialTheme.typography.titleSmall)
                        Text("${artifact.kind.label} · ${artifact.qualityLabel}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(artifact.url, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        OutlinedButton(
                            onClick = { onDownload(artifact.url, "", pageUrl, "") },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Download asset") }
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
                        ) { Text("Download this quality") }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
