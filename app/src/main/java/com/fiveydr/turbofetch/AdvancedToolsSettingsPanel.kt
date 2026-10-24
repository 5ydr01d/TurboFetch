package com.fiveydr.turbofetch

import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Advanced download tools panel, separated from the Activity so the main screen can focus on
 * lifecycle integration, navigation and user actions. All Android side effects are injected
 * through explicit context/scope/callback parameters.
 */
@Composable
internal fun AdvancedToolsSettingsPanel(
    context: Context,
    preferences: SharedPreferences,
    scope: CoroutineScope,
    importLauncher: ActivityResultLauncher<Array<String>>,
    exportLauncher: ActivityResultLauncher<String>,
    checksumLauncher: ActivityResultLauncher<Array<String>>,
    onPrepareExport: (String) -> Unit,
    onQueueText: (String, String, Map<String, String>, Boolean, String?) -> Unit,
    onControlAllDownloads: (Boolean) -> Unit,
    onClearCompletedHistory: () -> Unit,
    onScheduleUrl: (String) -> Unit,
    onPumpQueue: () -> Unit,
    loadSavedToolHeaders: () -> Map<String, String>,
    saveToolHeaders: (Map<String, String>) -> Unit
) {
    var directUrl by remember { mutableStateOf(preferences.getString("last_tool_url", "").orEmpty()) }
    var batchText by remember { mutableStateOf("") }
    var pendingToolExport by remember { mutableStateOf("") }
    var speedKb by remember { mutableStateOf((preferences.getLong("speed_limit_bytes_per_second", 0L) / 1024L).toString()) }
    var partsText by remember { mutableStateOf(preferences.getInt("segments_per_download", 4).toString()) }
    var concurrencyText by remember { mutableStateOf(preferences.getInt("max_concurrent_downloads", 3).toString()) }
    var proxyHost by remember { mutableStateOf(preferences.getString("proxy_host", "").orEmpty()) }
    var proxyPort by remember { mutableStateOf(preferences.getInt("proxy_port", 0).takeIf { it > 0 }?.toString().orEmpty()) }
    var wifiOnly by remember { mutableStateOf(preferences.getBoolean("wifi_only", false)) }
    val savedToolHeaders = remember { loadSavedToolHeaders() }
    var userAgent by remember { mutableStateOf(savedToolHeaders["User-Agent"].orEmpty()) }
    var referer by remember { mutableStateOf(savedToolHeaders["Referer"].orEmpty()) }
    var cookie by remember { mutableStateOf(savedToolHeaders["Cookie"].orEmpty()) }
    var artifactPageUrl by remember { mutableStateOf("") }
    var scannedArtifacts by remember { mutableStateOf(emptyList<MediaArtifactTools.Artifact>()) }
    var selectedArtifactUrls by remember { mutableStateOf(setOf<String>()) }
    var scanStatus by remember { mutableStateOf("Load a page, then scan its directly exposed media and file links.") }
    var videoSiteUrl by remember { mutableStateOf("") }
    var videoSiteTitle by remember { mutableStateOf("") }
    var videoFormats by remember { mutableStateOf(emptyList<VideoSiteExtractor.Format>()) }
    var selectedVideoFormatUrl by remember { mutableStateOf("") }
    var videoFormatStatus by remember { mutableStateOf("Paste a supported video-page URL to inspect available direct formats.") }
    var inspectingVideoFormats by remember { mutableStateOf(false) }
    val artifactWebView = remember { WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        webViewClient = WebViewClient()
    } }

    // Load the latest scan automatically when Finder opens; Feather stores a scan on every page navigation.
    LaunchedEffect(Unit) {
        val browserPrefs = context.getSharedPreferences("turbofetch_browser_artifacts", Context.MODE_PRIVATE)
        val savedJson = browserPrefs.getString("turbofetch_artifact_scan_json", null)
        val savedUrl = browserPrefs.getString("turbofetch_artifact_scan_url", null)
        if (!savedJson.isNullOrBlank() && !savedUrl.isNullOrBlank()) {
            runCatching {
                val root = JSONObject(savedJson)
                val array = root.optJSONArray("artifacts") ?: JSONArray()
                val found = (0 until array.length()).mapNotNull { i ->
                    val item = array.optJSONObject(i) ?: return@mapNotNull null
                    MediaArtifactTools.artifact(
                        item.optString("url"),
                        item.optString("title").ifBlank { root.optString("title") },
                        item.optString("mimeType")
                    )
                }.distinctBy { it.url }
                artifactPageUrl = savedUrl
                scannedArtifacts = found
                selectedArtifactUrls = found.map { it.url }.toSet()
                scanStatus = if (found.isEmpty()) {
                    "Latest browser page: no direct assets exposed. Protected/blob-based media may not be discoverable."
                } else {
                    "Loaded ${found.size} automatically discovered items from your latest Feather Browser page."
                }
                val host = Uri.parse(savedUrl).host.orEmpty().lowercase(Locale.ROOT)
                val likelyVideoPage = host.contains("youtube.") || host.contains("youtu.be") ||
                    host.contains("vimeo.") || host.contains("dailymotion.") ||
                    host.contains("tiktok.") || host.contains("facebook.") ||
                    host.contains("instagram.") || found.any {
                        it.kind == MediaArtifactTools.Kind.VIDEO || it.kind == MediaArtifactTools.Kind.STREAM
                    }
                if (likelyVideoPage) {
                    videoSiteUrl = savedUrl
                    inspectingVideoFormats = true
                    videoFormatStatus = "Automatically checking available video/audio qualities…"
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                VideoSiteExtractor.inspect(context, savedUrl)
                            }
                        }.onSuccess { result ->
                            videoSiteTitle = result.title
                            videoFormats = result.formats
                            selectedVideoFormatUrl = result.formats.firstOrNull()?.url.orEmpty()
                            videoFormatStatus = if (result.formats.isEmpty()) {
                                "No directly queueable audio-bearing formats found. This site may need separate-track merging or may not be supported."
                            } else {
                                "Automatically found ${result.formats.size} quality/audio choices for ${result.title}."
                            }
                        }.onFailure {
                            videoFormatStatus = "Automatic quality inspection unavailable for this page: ${it.message ?: "unsupported site"}"
                        }
                        inspectingVideoFormats = false
                    }
                }
            }.onFailure {
                scanStatus = "Could not read the latest browser scan. Browse to a page again and reopen Finder."
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Advanced tools", "All tools are available here — no separate screen")
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Video site quality picker", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Uses yt-dlp to inspect supported video-hosting pages and list available direct video+audio or audio-only formats. Choose a quality, then hand its direct stream URL to TurboFetch.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = videoSiteUrl,
                    onValueChange = { videoSiteUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Video page URL") },
                    placeholder = { Text("https://video.example/watch/...") }
                )
                Button(
                    onClick = {
                        if (!DownloadLinkTools.isHttpUrl(videoSiteUrl)) {
                            videoFormatStatus = "Enter a valid HTTP/HTTPS video page URL."
                        } else {
                            inspectingVideoFormats = true
                            videoFormats = emptyList()
                            selectedVideoFormatUrl = ""
                            videoFormatStatus = "Inspecting available formats…"
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        VideoSiteExtractor.inspect(context, videoSiteUrl.trim())
                                    }
                                }.onSuccess { result ->
                                    videoSiteTitle = result.title
                                    videoFormats = result.formats
                                    selectedVideoFormatUrl = result.formats.firstOrNull()?.url.orEmpty()
                                    videoFormatStatus = if (result.formats.isEmpty()) {
                                        "No direct audio-bearing formats were exposed. This site may require separate video/audio muxing or may not be supported."
                                    } else {
                                        "Found ${result.formats.size} direct format choices for ${result.title}."
                                    }
                                }.onFailure {
                                    videoFormatStatus = "Could not extract formats: ${it.message ?: "unknown error"}"
                                }
                                inspectingVideoFormats = false
                            }
                        }
                    },
                    enabled = !inspectingVideoFormats,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (inspectingVideoFormats) "Inspecting…" else "Find video qualities") }
                Text(videoFormatStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (videoFormats.isNotEmpty()) {
                    Text(videoSiteTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    videoFormats.forEach { format ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = selectedVideoFormatUrl == format.url,
                                onClick = { selectedVideoFormatUrl = format.url }
                            )
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(format.label, style = MaterialTheme.typography.bodyMedium)
                                Text("Format ID: ${format.id.ifBlank { "n/a" }}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Button(
                        onClick = {
                            val chosen = videoFormats.firstOrNull { it.url == selectedVideoFormatUrl }
                            if (chosen == null) {
                                videoFormatStatus = "Choose a format first."
                            } else {
                                onQueueText(chosen.url, "Video site: ${videoSiteTitle} ${chosen.label}", chosen.headers, true, null)
                                videoFormatStatus = "Selected direct stream sent to the TurboFetch queue. Some sites issue expiring URLs; queue it promptly."
                            }
                        },
                        enabled = selectedVideoFormatUrl.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Queue selected quality in TurboFetch") }
                }
                Text("Important: the picker lists direct formats that already include audio, plus audio-only formats. Separate video-only DASH streams are excluded because the current TurboFetch queue does not yet mux separate tracks. DRM/protected streams are not supported.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Website artifact finder", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Scan a page for exposed image, video, audio, PDF, document, archive and application links. Select the files you want and send them to the TurboFetch queue.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = artifactPageUrl,
                    onValueChange = { artifactPageUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Website page URL") },
                    placeholder = { Text("https://example.com/page") }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (DownloadLinkTools.isHttpUrl(artifactPageUrl)) {
                            scannedArtifacts = emptyList()
                            selectedArtifactUrls = emptySet()
                            scanStatus = "Loading page…"
                            artifactWebView.loadUrl(artifactPageUrl.trim())
                        } else {
                            scanStatus = "Enter a valid HTTP or HTTPS page URL."
                        }
                    }, modifier = Modifier.weight(1f)) { Text("Load page") }
                    OutlinedButton(onClick = {
                        if (artifactWebView.url.isNullOrBlank()) {
                            scanStatus = "Load a webpage first."
                        } else {
                            scanStatus = "Scanning page assets…"
                            artifactWebView.evaluateJavascript(MediaArtifactTools.PAGE_SCAN_SCRIPT) { raw ->
                                runCatching {
                                    val decoded = org.json.JSONTokener(raw ?: "null").nextValue()
                                    val jsonText = decoded as? String ?: error("No scan data returned")
                                    val root = JSONObject(jsonText)
                                    val array = root.optJSONArray("artifacts") ?: JSONArray()
                                    val found = (0 until array.length()).mapNotNull { i ->
                                        val item = array.optJSONObject(i) ?: return@mapNotNull null
                                        MediaArtifactTools.artifact(
                                            item.optString("url"),
                                            item.optString("title").ifBlank { root.optString("title") },
                                            item.optString("mimeType")
                                        )
                                    }.distinctBy { it.url }
                                    scannedArtifacts = found
                                    selectedArtifactUrls = found.map { it.url }.toSet()
                                    scanStatus = if (found.isEmpty()) "No direct asset links found. Some streaming sites hide media behind protected or blob-based players." else "Found ${found.size} direct links. Review and select what to queue."
                                }.onFailure { scanStatus = "Scan failed: ${it.message ?: "unexpected page response"}" }
                            }
                        }
                    }, modifier = Modifier.weight(1f)) { Text("Scan page") }
                }
                AndroidView(
                    factory = { artifactWebView },
                    modifier = Modifier.fillMaxWidth().height(220.dp)
                )
                Text(scanStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (scannedArtifacts.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${selectedArtifactUrls.size} of ${scannedArtifacts.size} selected", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        OutlinedButton(onClick = {
                            selectedArtifactUrls = if (selectedArtifactUrls.size == scannedArtifacts.size) emptySet() else scannedArtifacts.map { it.url }.toSet()
                        }) { Text(if (selectedArtifactUrls.size == scannedArtifacts.size) "Select none" else "Select all") }
                    }
                    scannedArtifacts.forEach { artifact ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = artifact.url in selectedArtifactUrls,
                                onCheckedChange = { checked ->
                                    selectedArtifactUrls = if (checked) selectedArtifactUrls + artifact.url else selectedArtifactUrls - artifact.url
                                }
                            )
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(artifact.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${artifact.kind.label} · ${artifact.qualityLabel}${if (artifact.mimeType.isNotBlank()) " · ${artifact.mimeType}" else ""}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(artifact.url, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider()
                    }
                    Button(
                        onClick = {
                            val selected = scannedArtifacts.filter { it.url in selectedArtifactUrls }
                            if (selected.isEmpty()) {
                                scanStatus = "Select at least one artifact."
                            } else {
                                onQueueText(selected.joinToString("\n") { it.url }, "Website artifact scan", emptyMap(), true, null)
                                scanStatus = "Sent ${selected.size} selected links to the TurboFetch queue."
                            }
                        },
                        enabled = selectedArtifactUrls.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Queue selected artifacts") }
                }
                Text("Limits: this discovers URLs exposed to the page DOM. It does not yet extract protected tube-site streams or guarantee quality options when a site hides its media URLs.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Add and batch links", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(value = directUrl, onValueChange = { directUrl = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Direct URL (HTTP/HTTPS)") })
                Button(onClick = { onQueueText(directUrl, "Added manually", emptyMap(), true, null) }, modifier = Modifier.fillMaxWidth()) { Text("Queue URL") }
                OutlinedTextField(value = batchText, onValueChange = { batchText = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 7, label = { Text("Paste multiple URLs or a link list") })
                Button(onClick = { onQueueText(batchText, "Batch download", emptyMap(), true, null) }, modifier = Modifier.fillMaxWidth()) { Text("Queue all links in text") }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("text/plain", "text/*", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth()) { Text("Import links from a text file") }
                OutlinedButton(onClick = {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = clipboard.primaryClip
                    val copied = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(context).toString() else ""
                    if (copied.isBlank()) Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                    else { batchText = copied; onQueueText(copied, "Clipboard import", emptyMap(), true, null) }
                }, modifier = Modifier.fillMaxWidth()) { Text("Import links from clipboard") }
                OutlinedButton(onClick = {
                    val saved = DownloadQueueStore.snapshot(preferences)
                    pendingToolExport = (0 until saved.length()).mapNotNull { saved.optJSONObject(it)?.optString("url") }
                        .filter { DownloadLinkTools.isHttpUrl(it) }.distinct().joinToString("\n", postfix = if (saved.length() > 0) "\n" else "")
        onPrepareExport(pendingToolExport)
                    if (pendingToolExport.isBlank()) Toast.makeText(context, "There are no download URLs to export", Toast.LENGTH_SHORT).show()
                    else exportLauncher.launch("turbofetch-links.txt")
                }, modifier = Modifier.fillMaxWidth()) { Text("Export download URLs") }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Queue controls", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                OutlinedButton(onClick = { onControlAllDownloads(resume = false) }, modifier = Modifier.fillMaxWidth()) { Text("Pause all active downloads") }
                OutlinedButton(onClick = { onControlAllDownloads(resume = true) }, modifier = Modifier.fillMaxWidth()) { Text("Resume paused / retry failed downloads") }
                OutlinedButton(onClick = { onClearCompletedHistory() }, modifier = Modifier.fillMaxWidth()) { Text("Clear completed history (files stay saved)") }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Download scheduler", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Schedule the URL above for a future date and time.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { onScheduleUrl(directUrl) }, modifier = Modifier.fillMaxWidth()) { Text("Schedule this URL…") }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("Transfer settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(value = speedKb, onValueChange = { speedKb = it.filter(Char::isDigit) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Global speed limit (KB/s; 0 = unlimited)") })
                OutlinedTextField(value = partsText, onValueChange = { partsText = it.filter(Char::isDigit) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Parallel parts per large file (2–16)") })
                OutlinedTextField(value = concurrencyText, onValueChange = { concurrencyText = it.filter(Char::isDigit) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Simultaneous downloads (1–30)") })
                OutlinedTextField(value = proxyHost, onValueChange = { proxyHost = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("HTTP proxy host (blank = direct)") })
                OutlinedTextField(value = proxyPort, onValueChange = { proxyPort = it.filter(Char::isDigit) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("HTTP proxy port (blank/0 = direct)") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Download only on Wi-Fi", modifier = Modifier.weight(1f))
                    Switch(checked = wifiOnly, onCheckedChange = { wifiOnly = it })
                }
                Button(onClick = {
                    val host = proxyHost.trim()
                    val port = proxyPort.toIntOrNull() ?: 0
                    if (host.isNotBlank() && port !in 1..65535) {
                        Toast.makeText(context, "Enter a proxy port from 1 to 65535", Toast.LENGTH_SHORT).show()
                    } else {
                        val speed = (speedKb.toLongOrNull() ?: 0L).coerceAtLeast(0L)
                        val parts = (partsText.toIntOrNull() ?: 4).coerceIn(2, 16)
                        val concurrent = (concurrencyText.toIntOrNull() ?: 3).coerceIn(1, 30)
                        preferences.edit().putLong("speed_limit_bytes_per_second", speed * 1024L)
                            .putInt("segments_per_download", parts).putInt("max_concurrent_downloads", concurrent)
                            .putString("proxy_host", host).putInt("proxy_port", if (host.isBlank()) 0 else port)
                            .putBoolean("wifi_only", wifiOnly).apply()
                        DownloadRuntimeOptions.refresh(context)
                        CustomDownloadService.start(context)
                        onPumpQueue()
                        Toast.makeText(context, "Transfer settings saved", Toast.LENGTH_SHORT).show()
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Save transfer settings") }
                Text("Parallel parts require server byte-range support and a stable validator. Proxy and Wi-Fi-only options affect TurboFetch downloads.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Request headers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Optional headers apply to new downloads from these tools. Saved values are encrypted with Android Keystore on this device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = userAgent, onValueChange = { userAgent = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("User-Agent (optional)") })
                OutlinedTextField(value = referer, onValueChange = { referer = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Referer (optional)") })
                OutlinedTextField(value = cookie, onValueChange = { cookie = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Cookie (optional)") })
                Text("These fields apply only to downloads queued from this section.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = {
                    runCatching {
                        saveToolHeaders(
                            mapOf(
                                "User-Agent" to userAgent,
                                "Referer" to referer,
                                "Cookie" to cookie
                            )
                        )
                    }.onSuccess {
                        Toast.makeText(context, "Request headers saved securely", Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(context, "Could not encrypt request headers; previous settings were kept.", Toast.LENGTH_LONG).show()
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Save request headers") }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("File integrity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Button(onClick = { checksumLauncher.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Calculate MD5 and SHA-256 for a file…") }
                Text("Checksums help confirm a file has not changed; they do not prove that a file is safe.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
}
