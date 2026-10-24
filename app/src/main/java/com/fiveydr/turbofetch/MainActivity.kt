package com.fiveydr.turbofetch

import android.app.DownloadManager
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Calendar
import java.util.Locale
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

private data class DownloadRow(
    val id: Long,
    val title: String,
    val url: String,
    val status: String,
    val downloaded: Long,
    val total: Long,
    val reason: String = "",
    val createdAt: Long = id,
    val speed: Long = 0L,
    val custom: Boolean = false,
    val contentUri: String = "",
    val technicalDetails: String = ""
) {
    val percent: Int
        get() = if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else 0
    val isActive: Boolean get() = status == "Downloading" || status == "Queued" || status == "Paused by Android"
    val isWaiting: Boolean get() = status == "Waiting in queue"
    val isBusy: Boolean get() = isActive || isWaiting || status == "Paused"
    val isComplete: Boolean get() = status == "Complete"
    val isProblem: Boolean get() = status == "Failed" || status == "Record unavailable" || status == "Cancelled"
}

private enum class AppTab(val label: String, val symbol: String) {
    Home("Downloads", "↓"),
    Downloads("Queue", "☷"),
    Files("Files", "▤"),
    Settings("Settings", "⚙")
}

private enum class DownloadFilter(val label: String) {
    All("All"), Active("Active"), Complete("Complete"), Problems("Issues")
}

class MainActivity : ComponentActivity() {
    private var pendingUrlAfterStoragePermission: String? = null
    private var sharedUrlOnLaunch: String? = null
    private val STORAGE_PERMISSION_REQUEST = 8107
    private lateinit var downloadManager: DownloadManager
    private val preferences by lazy { getSharedPreferences("turbofetch", Context.MODE_PRIVATE) }
    private lateinit var toolImportLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var toolExportLauncher: ActivityResultLauncher<String>
    private lateinit var toolChecksumLauncher: ActivityResultLauncher<Array<String>>
    private var pendingToolExport: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        downloadManager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        toolImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) runCatching {
                contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            }.onSuccess { text -> if (!text.isNullOrBlank()) queueToolText(text, "Imported links") else Toast.makeText(this, "File is empty or unreadable", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(this, "Could not read selected file", Toast.LENGTH_SHORT).show() }
        }
        toolExportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) runCatching {
                contentResolver.openOutputStream(uri, "w")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(pendingToolExport) }
                    ?: error("Could not open output file")
            }.onSuccess { Toast.makeText(this, "Exported download URLs", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(this, "Export failed: ${it.message}", Toast.LENGTH_SHORT).show() }
        }
        toolChecksumLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) calculateToolChecksums(uri)
        }
        val sharedText = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }.orEmpty()
        sharedUrlOnLaunch = DownloadLinkTools.extractUrls(sharedText).firstOrNull()

        setContent {
            var darkMode by remember { mutableStateOf(preferences.getBoolean("dark_mode", false)) }
            var textScale by remember { mutableFloatStateOf(preferences.getFloat("text_scale", 1f).coerceIn(0.9f, 1.3f)) }
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(baseDensity.density, baseDensity.fontScale * textScale)
            ) {
                MaterialTheme(colorScheme = if (darkMode) darkColorScheme() else lightColorScheme()) {
                    TurboFetchApp(
                        darkMode = darkMode,
                        textScale = textScale,
                        onDarkModeChange = {
                            darkMode = it
                            preferences.edit().putBoolean("dark_mode", it).apply()
                        },
                        onTextScaleChange = {
                            textScale = it
                            preferences.edit().putFloat("text_scale", it).apply()
                        }
                    )
                }
            }
        }
        handleBrowserDownloadIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleBrowserDownloadIntent(intent)
    }

    private fun handleBrowserDownloadIntent(sourceIntent: Intent?) {
        if (sourceIntent?.action != "com.fiveydr.turbofetch.action.BROWSER_DOWNLOAD") return
        val url = sourceIntent.getStringExtra("com.fiveydr.turbofetch.extra.DOWNLOAD_URL")
            ?.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
            ?: return
        // Consume the handoff marker so activity recreation cannot enqueue the same file twice.
        setIntent(Intent(sourceIntent).apply {
            removeExtra("com.fiveydr.turbofetch.extra.DOWNLOAD_URL")
        })
        val userAgent = sourceIntent.getStringExtra("com.fiveydr.turbofetch.extra.USER_AGENT").orEmpty()
        val referer = sourceIntent.getStringExtra("com.fiveydr.turbofetch.extra.REFERER").orEmpty()
        val cookie = sourceIntent.getStringExtra("com.fiveydr.turbofetch.extra.COOKIE").orEmpty()
        preferences.edit()
            .putString("tool_user_agent", userAgent)
            .putString("tool_referer", referer)
            .putString("tool_cookie", cookie)
            .apply()
        window.decorView.post {
            queueToolText(url, "Feather Browser download")
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun TurboFetchApp(
        darkMode: Boolean,
        textScale: Float,
        onDarkModeChange: (Boolean) -> Unit,
        onTextScaleChange: (Float) -> Unit
    ) {
        var tab by remember { mutableStateOf(AppTab.Home) }
        var urlInput by remember { mutableStateOf(sharedUrlOnLaunch.orEmpty()) }
        var search by remember { mutableStateOf("") }
        var filter by remember { mutableStateOf(DownloadFilter.All) }
        var rows by remember { mutableStateOf(emptyList<DownloadRow>()) }
        var loading by remember { mutableStateOf(true) }
        var maxConcurrent by remember {
            mutableIntStateOf(preferences.getInt("max_concurrent_downloads", 3).coerceIn(1, 30))
        }

        LaunchedEffect(Unit) {
            pumpQueue()
            while (true) {
                rows = loadRows()
                loading = false
                if (rows.any { it.isWaiting }) pumpQueue()
                delay(if (rows.any { it.isBusy }) 1500L else 5000L)
            }
        }

        val activeCount = rows.count { it.isActive }
        val waitingCount = rows.count { it.isWaiting }
        val completedCount = rows.count { it.isComplete }
        val visibleRows = rows.filter { row ->
            val matchesSearch = search.isBlank() ||
                row.title.contains(search, ignoreCase = true) ||
                row.url.contains(search, ignoreCase = true)
            val matchesFilter = when (filter) {
                DownloadFilter.All -> true
                DownloadFilter.Active -> row.isBusy
                DownloadFilter.Complete -> row.isComplete
                DownloadFilter.Problems -> row.isProblem || row.status == "Failed"
            }
            matchesSearch && matchesFilter
        }
        val filesRows = rows.filter { it.isComplete && searchMatches(it, search) }

        Scaffold(
            contentWindowInsets = WindowInsets.statusBars.union(WindowInsets.navigationBars),
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            Surface(
                                shape = RoundedCornerShape(9.dp),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("T", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Black)
                                }
                            }
                            Text("TurboFetch", fontWeight = FontWeight.Bold)
                        }
                    },
                    actions = {
                        androidx.compose.material3.TextButton(onClick = {
                            startActivity(Intent(this@MainActivity, com.example.MainActivity::class.java))
                        }) { Text("Browser") }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    AppTab.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = tab == destination,
                            onClick = { tab = destination },
                            icon = { Text(destination.symbol, style = MaterialTheme.typography.titleLarge) },
                            label = { Text(destination.label) }
                        )
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (tab) {
                    AppTab.Home -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = urlInput,
                                onValueChange = { urlInput = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                label = { Text("Download link") },
                                placeholder = { Text("Paste URL") },
                                shape = RoundedCornerShape(12.dp)
                            )
                            Button(
                                onClick = { enqueueDownload(urlInput, rows) { urlInput = ""; rows = loadRows() } },
                                enabled = urlInput.isNotBlank(),
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("↓") }
                        }
                        OutlinedTextField(
                            value = search,
                            onValueChange = { search = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Search downloads") },
                            placeholder = { Text("Filename or URL") },
                            shape = RoundedCornerShape(12.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            DownloadFilter.entries.forEach { option ->
                                FilterChip(
                                    selected = filter == option,
                                    onClick = { filter = option },
                                    label = { Text(option.label) }
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 1.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Downloads", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text("${activeCount} active  ·  ${completedCount} completed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (loading && rows.isEmpty()) {
                            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                Text("Loading downloads…")
                            }
                        } else if (visibleRows.isEmpty()) {
                            EmptyState(
                                title = if (search.isBlank()) "No downloads yet" else "No matching downloads",
                                message = if (search.isBlank()) "Paste a link above or open the browser to find a file." else "Try another filename, URL, or status filter.",
                                action = "Open browser",
                                onAction = { startActivity(Intent(this@MainActivity, com.example.MainActivity::class.java)) }
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                items(visibleRows, key = { it.id }) { row ->
                                    DownloadCard(
                                        row = row,
                                        onOpen = { openDownloadedFile(row.id) },
                                        onShare = { shareDownloadedFile(row.id) },
                                        onRetry = { retry(row, rows) { rows = loadRows() } },
                                        onPauseResume = { controlDownload(row) { rows = loadRows() } },
                                        onDismiss = { dismissOrCancel(row) { rows = loadRows() } }
                                    )
                                }
                            }
                        }
                    }
                    AppTab.Downloads -> {
                        SectionHeader("Download manager", "$activeCount active · $waitingCount waiting · $completedCount complete")
                        OutlinedTextField(
                            value = search,
                            onValueChange = { search = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Search downloads") },
                            placeholder = { Text("Filename or source URL") }
                        )
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            DownloadFilter.entries.forEach { option ->
                                FilterChip(
                                    selected = filter == option,
                                    onClick = { filter = option },
                                    label = { Text(option.label) }
                                )
                            }
                        }
                        if (loading && rows.isEmpty()) {
                            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                Text("Loading download history…")
                            }
                        } else if (visibleRows.isEmpty()) {
                            EmptyState(
                                title = if (search.isBlank()) "Nothing in this view" else "No matching downloads",
                                message = if (search.isBlank()) "Add a link from Home to start a download." else "Try another filename, URL, or status filter.",
                                action = "Add download",
                                onAction = { tab = AppTab.Home }
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(visibleRows, key = { it.id }) { row ->
                                    DownloadCard(
                                        row = row,
                                        onOpen = { openDownloadedFile(row.id) },
                                        onShare = { shareDownloadedFile(row.id) },
                                        onRetry = { retry(row, rows) { rows = loadRows() } },
                                        onPauseResume = { controlDownload(row) { rows = loadRows() } },
                                        onDismiss = { dismissOrCancel(row) { rows = loadRows() } }
                                    )
                                }
                            }
                        }
                    }
                    AppTab.Files -> {
                        SectionHeader("Downloaded files", "$filesRows.size available in history")
                        OutlinedTextField(
                            value = search,
                            onValueChange = { search = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Find a file") },
                            placeholder = { Text("Search by filename") }
                        )
                        if (filesRows.isEmpty()) {
                            EmptyState(
                                title = "No completed files yet",
                                message = "When a download completes, it will appear here with Open and Share actions.",
                                action = "View downloads",
                                onAction = { tab = AppTab.Downloads }
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(filesRows, key = { it.id }) { row ->
                                    DownloadCard(
                                        row = row,
                                        onOpen = { openDownloadedFile(row.id) },
                                        onShare = { shareDownloadedFile(row.id) },
                                        onRetry = { retry(row, rows) { rows = loadRows() } },
                                        onPauseResume = { controlDownload(row) { rows = loadRows() } },
                                        onDismiss = { dismissOrCancel(row) { rows = loadRows() } }
                                    )
                                }
                            }
                        }
                    }
                    AppTab.Settings -> {
                        Column(
                            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                        SectionHeader("Settings", "Make TurboFetch yours")
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text("Dark theme", style = MaterialTheme.typography.titleMedium)
                                        Text("Use a darker interface in low light.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(checked = darkMode, onCheckedChange = onDarkModeChange)
                                }
                                HorizontalDivider()
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Text size", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                        Text("${(textScale * 100).toInt()}%", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                                    }
                                    Slider(
                                        value = textScale,
                                        onValueChange = onTextScaleChange,
                                        valueRange = 0.9f..1.3f,
                                        steps = 3
                                    )
                                    Text("Adjust text for comfortable reading.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                HorizontalDivider()
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Simultaneous downloads", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                        Text("$maxConcurrent", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                                    }
                                    Slider(
                                        value = maxConcurrent.toFloat(),
                                        onValueChange = { value ->
                                            maxConcurrent = value.toInt().coerceIn(1, 30)
                                            preferences.edit().putInt("max_concurrent_downloads", maxConcurrent).apply()
                                            pumpQueue()
                                        },
                                        valueRange = 1f..30f,
                                        steps = 28
                                    )
                                    Text("Limits downloads started by TurboFetch. Lowering this value won't interrupt transfers already running.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                HorizontalDivider()
                                var segmentCount by remember {
                                    mutableIntStateOf(preferences.getInt("segment_count", 4).coerceIn(2, 8))
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Connections per large file", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                        Text("$segmentCount", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                                    }
                                    Slider(
                                        value = segmentCount.toFloat(),
                                        onValueChange = { value ->
                                            segmentCount = value.toInt().coerceIn(2, 8)
                                            preferences.edit().putInt("segment_count", segmentCount).apply()
                                        },
                                        valueRange = 2f..8f,
                                        steps = 5
                                    )
                                    Text("Uses parallel byte ranges for large files (8 MB+), only when the server supports ranges and provides a stable validator. Unsupported links use the resumable single-connection engine. A running transfer keeps its current setting.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        AdvancedToolsSettingsSection()
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Privacy by design", style = MaterialTheme.typography.titleMedium)
                                Text("No advertising SDKs or analytics SDKs. Download history is stored locally on this device.", style = MaterialTheme.typography.bodyMedium)
                                Text("Large files may use parallel byte-range connections when the server supports ranges and provides a stable validator. Otherwise TurboFetch uses the single-stream resumable engine. Failed transfers retain a diagnostic category and the server/storage error message.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun AdvancedToolsSettingsSection() {
        var directUrl by remember { mutableStateOf(preferences.getString("last_tool_url", "").orEmpty()) }
        var batchText by remember { mutableStateOf("") }
        var speedKb by remember { mutableStateOf((preferences.getLong("speed_limit_bytes_per_second", 0L) / 1024L).toString()) }
        var partsText by remember { mutableStateOf(preferences.getInt("segments_per_download", 4).toString()) }
        var concurrencyText by remember { mutableStateOf(preferences.getInt("max_concurrent_downloads", 3).toString()) }
        var proxyHost by remember { mutableStateOf(preferences.getString("proxy_host", "").orEmpty()) }
        var proxyPort by remember { mutableStateOf(preferences.getInt("proxy_port", 0).takeIf { it > 0 }?.toString().orEmpty()) }
        var wifiOnly by remember { mutableStateOf(preferences.getBoolean("wifi_only", false)) }
        var userAgent by remember { mutableStateOf(preferences.getString("tool_user_agent", "").orEmpty()) }
        var referer by remember { mutableStateOf(preferences.getString("tool_referer", "").orEmpty()) }
        var cookie by remember { mutableStateOf(preferences.getString("tool_cookie", "").orEmpty()) }
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
        val artifactWebView = remember { WebView(this@MainActivity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
        } }

        // Load the latest scan automatically when Finder opens; Feather stores a scan on every page navigation.
        LaunchedEffect(Unit) {
            val browserPrefs = getSharedPreferences("turbofetch_browser_artifacts", Context.MODE_PRIVATE)
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
                        lifecycleScope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    VideoSiteExtractor.inspect(this@MainActivity, savedUrl)
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
                                lifecycleScope.launch {
                                    runCatching {
                                        withContext(Dispatchers.IO) {
                                            VideoSiteExtractor.inspect(this@MainActivity, videoSiteUrl.trim())
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
                                    queueToolText(chosen.url, "Video site: ${videoSiteTitle} ${chosen.label}", chosen.headers)
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
                                    queueToolText(selected.joinToString("\n") { it.url }, "Website artifact scan")
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
                    Button(onClick = { queueToolText(directUrl, "Added manually") }, modifier = Modifier.fillMaxWidth()) { Text("Queue URL") }
                    OutlinedTextField(value = batchText, onValueChange = { batchText = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 7, label = { Text("Paste multiple URLs or a link list") })
                    Button(onClick = { queueToolText(batchText, "Batch download") }, modifier = Modifier.fillMaxWidth()) { Text("Queue all links in text") }
                    OutlinedButton(onClick = { toolImportLauncher.launch(arrayOf("text/plain", "text/*", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth()) { Text("Import links from a text file") }
                    OutlinedButton(onClick = {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = clipboard.primaryClip
                        val copied = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this@MainActivity).toString() else ""
                        if (copied.isBlank()) Toast.makeText(this@MainActivity, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                        else { batchText = copied; queueToolText(copied, "Clipboard import") }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Import links from clipboard") }
                    OutlinedButton(onClick = {
                        val saved = JSONArray(preferences.getString("downloads", "[]") ?: "[]")
                        pendingToolExport = (0 until saved.length()).mapNotNull { saved.optJSONObject(it)?.optString("url") }
                            .filter { DownloadLinkTools.isHttpUrl(it) }.distinct().joinToString("\n", postfix = if (saved.length() > 0) "\n" else "")
                        if (pendingToolExport.isBlank()) Toast.makeText(this@MainActivity, "There are no download URLs to export", Toast.LENGTH_SHORT).show()
                        else toolExportLauncher.launch("turbofetch-links.txt")
                    }, modifier = Modifier.fillMaxWidth()) { Text("Export download URLs") }
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Queue controls", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    OutlinedButton(onClick = { controlAllToolDownloads(resume = false) }, modifier = Modifier.fillMaxWidth()) { Text("Pause all active downloads") }
                    OutlinedButton(onClick = { controlAllToolDownloads(resume = true) }, modifier = Modifier.fillMaxWidth()) { Text("Resume paused / retry failed downloads") }
                    OutlinedButton(onClick = { clearToolCompletedHistory() }, modifier = Modifier.fillMaxWidth()) { Text("Clear completed history (files stay saved)") }
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Download scheduler", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Schedule the URL above for a future date and time.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { scheduleToolUrl(directUrl) }, modifier = Modifier.fillMaxWidth()) { Text("Schedule this URL…") }
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
                            Toast.makeText(this@MainActivity, "Enter a proxy port from 1 to 65535", Toast.LENGTH_SHORT).show()
                        } else {
                            val speed = (speedKb.toLongOrNull() ?: 0L).coerceAtLeast(0L)
                            val parts = (partsText.toIntOrNull() ?: 4).coerceIn(2, 16)
                            val concurrent = (concurrencyText.toIntOrNull() ?: 3).coerceIn(1, 30)
                            preferences.edit().putLong("speed_limit_bytes_per_second", speed * 1024L)
                                .putInt("segments_per_download", parts).putInt("max_concurrent_downloads", concurrent)
                                .putString("proxy_host", host).putInt("proxy_port", if (host.isBlank()) 0 else port)
                                .putBoolean("wifi_only", wifiOnly).apply()
                            DownloadRuntimeOptions.refresh(this@MainActivity)
                            CustomDownloadService.start(this@MainActivity)
                            pumpQueue()
                            Toast.makeText(this@MainActivity, "Transfer settings saved", Toast.LENGTH_SHORT).show()
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Save transfer settings") }
                    Text("Parallel parts require server byte-range support and a stable validator. Proxy and Wi-Fi-only options affect TurboFetch downloads.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Request headers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Optional headers apply to new downloads queued from these tools. Cookies are stored locally; avoid shared devices.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(value = userAgent, onValueChange = { userAgent = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("User-Agent (optional)") })
                    OutlinedTextField(value = referer, onValueChange = { referer = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Referer (optional)") })
                    OutlinedTextField(value = cookie, onValueChange = { cookie = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Cookie (optional)") })
                    Text("These fields apply only to downloads queued from this section.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = {
                        preferences.edit().putString("tool_user_agent", userAgent).putString("tool_referer", referer).putString("tool_cookie", cookie).apply()
                        Toast.makeText(this@MainActivity, "Request headers saved for this session", Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.fillMaxWidth()) { Text("Save request headers") }
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("File integrity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Button(onClick = { toolChecksumLauncher.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Calculate MD5 and SHA-256 for a file…") }
                    Text("Checksums help confirm a file has not changed; they do not prove that a file is safe.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    private fun queueToolText(text: String, description: String, extraHeaders: Map<String, String> = emptyMap()) {
        val urls = DownloadLinkTools.extractUrls(text)
        if (urls.isEmpty()) { Toast.makeText(this, "No valid HTTP/HTTPS links found", Toast.LENGTH_SHORT).show(); return }
        val currentRows = JSONArray(preferences.getString("downloads", "[]") ?: "[]")
        val used = mutableSetOf<String>()
        for (i in 0 until currentRows.length()) used.add(currentRows.optJSONObject(i)?.optString("title").orEmpty().lowercase(Locale.ROOT))
        val headers = linkedMapOf<String, String>()
        preferences.getString("tool_user_agent", "").orEmpty().takeIf { it.isNotBlank() }?.let { headers["User-Agent"] = it.trim() }
        preferences.getString("tool_referer", "").orEmpty().takeIf { it.isNotBlank() }?.let { headers["Referer"] = it.trim() }
        preferences.getString("tool_cookie", "").orEmpty().takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it.trim() }
        extraHeaders.forEach { (key, value) -> if (key.isNotBlank() && value.isNotBlank()) headers[key] = value }
        urls.forEach { url ->
            var title = DownloadLinkTools.fileNameFromUrl(url)
            if (title.lowercase(Locale.ROOT) in used) {
                val dot = title.lastIndexOf('.')
                val stem = if (dot > 0) title.substring(0, dot) else title
                val ext = if (dot > 0) title.substring(dot) else ""
                var n = 1
                while ("${stem} ($n)$ext".lowercase(Locale.ROOT) in used) n++
                title = "${stem} ($n)$ext"
            }
            used.add(title.lowercase(Locale.ROOT))
            DownloadQueueScheduler.add(this, url, title, description, headers)
            preferences.edit().putString("last_tool_url", url).apply()
        }
        CustomDownloadService.start(this)
        Toast.makeText(this, "Added ${urls.size} link${if (urls.size == 1) "" else "s"} to the queue", Toast.LENGTH_SHORT).show()
    }

    private fun controlAllToolDownloads(resume: Boolean) {
        val saved = JSONArray(preferences.getString("downloads", "[]") ?: "[]")
        val ids = mutableListOf<Long>()
        for (i in 0 until saved.length()) {
            val item = saved.optJSONObject(i) ?: continue
            if (!item.optBoolean("custom", false)) continue
            val phase = item.optString("phase")
            if (resume && (phase == CustomDownloadService.PHASE_PAUSED || phase == CustomDownloadService.PHASE_FAILED)) {
                item.put("phase", CustomDownloadService.PHASE_WAITING).put("reason", "")
                ids.add(item.optLong("id"))
            } else if (!resume && (phase == CustomDownloadService.PHASE_DOWNLOADING || phase == CustomDownloadService.PHASE_WAITING)) ids.add(item.optLong("id"))
        }
        preferences.edit().putString("downloads", saved.toString()).apply()
        ids.forEach { CustomDownloadService.start(this, if (resume) CustomDownloadService.ACTION_RESUME else CustomDownloadService.ACTION_PAUSE, it) }
        CustomDownloadService.start(this)
        Toast.makeText(this, if (resume) "Resume/retry requested for ${ids.size} downloads" else "Pause requested for ${ids.size} downloads", Toast.LENGTH_SHORT).show()
    }

    private fun clearToolCompletedHistory() {
        val saved = JSONArray(preferences.getString("downloads", "[]") ?: "[]")
        val keep = JSONArray()
        var removed = 0
        for (i in 0 until saved.length()) {
            val item = saved.optJSONObject(i) ?: continue
            if (item.optString("phase") == CustomDownloadService.PHASE_COMPLETE || item.optString("phase") == "complete") removed++ else keep.put(item)
        }
        preferences.edit().putString("downloads", keep.toString()).apply()
        Toast.makeText(this, "Removed $removed completed history entries. Downloaded files were not deleted.", Toast.LENGTH_SHORT).show()
    }

    private fun scheduleToolUrl(input: String) {
        val url = input.trim()
        if (!DownloadLinkTools.isHttpUrl(url)) { Toast.makeText(this, "Enter a valid URL in the Direct URL field first", Toast.LENGTH_SHORT).show(); return }
        val now = Calendar.getInstance()
        DatePickerDialog(this, { _, year, month, day ->
            val date = Calendar.getInstance().apply { set(Calendar.YEAR, year); set(Calendar.MONTH, month); set(Calendar.DAY_OF_MONTH, day) }
            TimePickerDialog(this, { _, hour, minute ->
                date.set(Calendar.HOUR_OF_DAY, hour); date.set(Calendar.MINUTE, minute); date.set(Calendar.SECOND, 0); date.set(Calendar.MILLISECOND, 0)
                runCatching { DownloadScheduleManager.schedule(this, url, DownloadLinkTools.fileNameFromUrl(url), date.timeInMillis) }
                    .onSuccess { Toast.makeText(this, "Scheduled for ${DateFormat.getDateTimeInstance().format(date.time)}", Toast.LENGTH_LONG).show() }
                    .onFailure { Toast.makeText(this, it.message ?: "Could not schedule download", Toast.LENGTH_SHORT).show() }
            }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show()
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).apply { datePicker.minDate = now.timeInMillis }.show()
    }

    private fun calculateToolChecksums(uri: Uri) {
        Toast.makeText(this, "Calculating checksums…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val md5 = MessageDigest.getInstance("MD5")
                    val sha = MessageDigest.getInstance("SHA-256")
                    contentResolver.openInputStream(uri)?.use { input ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            md5.update(buffer, 0, count); sha.update(buffer, 0, count)
                        }
                    } ?: error("Cannot open selected file")
                    "MD5  ${md5.digest().joinToString("") { "%02x".format(it) }}\nSHA-256  ${sha.digest().joinToString("") { "%02x".format(it) }}"
                }
            }
            result.onSuccess { value ->
                android.app.AlertDialog.Builder(this@MainActivity).setTitle("File checksums").setMessage(value)
                    .setPositiveButton("Copy") { _, _ ->
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("File checksums", value))
                        Toast.makeText(this@MainActivity, "Checksums copied", Toast.LENGTH_SHORT).show()
                    }.setNegativeButton("Close", null).show()
            }.onFailure { Toast.makeText(this@MainActivity, "Checksum failed: ${it.message}", Toast.LENGTH_SHORT).show() }
        }
    }

    @Composable
    private fun HomeHeader(activeCount: Int, completedCount: Int, total: Int) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Your downloads, under control", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("A clean, private place for every file.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatPill("Active", activeCount)
                    StatPill("Complete", completedCount)
                    StatPill("Tracked", total)
                }
            }
        }
    }

    @Composable
    private fun StatPill(label: String, count: Int) {
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(count.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    @Composable
    private fun AdvancedToolsCard(onBrowser: () -> Unit, onTools: () -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Download suite", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Built-in browser, batch links, scheduler, proxy, speed limits and file checksums.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onBrowser, modifier = Modifier.weight(1f)) { Text("Browser") }
                    Button(onClick = onTools, modifier = Modifier.weight(1f)) { Text("Advanced tools") }
                }
            }
        }
    }

    @Composable
    private fun AddDownloadCard(value: String, onValueChange: (String) -> Unit, onAdd: () -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Add a download", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Direct file URL") },
                    placeholder = { Text("https://example.com/file.zip") },
                    singleLine = true
                )
                Button(onClick = onAdd, modifier = Modifier.fillMaxWidth(), enabled = value.isNotBlank()) {
                    Text("↓   Start download")
                }
                Text("Direct HTTP/HTTPS links · No ads · No account", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    @Composable
    private fun SectionHeader(title: String, subtitle: String) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    private fun EmptyState(title: String, message: String, action: String, onAction: () -> Unit) {
        Box(Modifier.fillMaxWidth().padding(vertical = 26.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(64.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("↓", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onAction) { Text(action) }
            }
        }
    }

    @Composable
    private fun DownloadCard(
        row: DownloadRow,
        onOpen: () -> Unit,
        onShare: () -> Unit,
        onRetry: () -> Unit,
        onPauseResume: () -> Unit,
        onDismiss: () -> Unit
    ) {
        val iconText = when (row.title.substringAfterLast('.', "").lowercase()) {
            "apk" -> "APK"
            "zip", "rar", "7z", "gz" -> "ZIP"
            "pdf" -> "PDF"
            "mp4", "mkv", "webm", "mov" -> "VID"
            "mp3", "m4a", "wav", "ogg" -> "AUD"
            "jpg", "jpeg", "png", "webp", "gif" -> "IMG"
            "doc", "docx", "txt", "rtf" -> "DOC"
            else -> "FILE"
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(iconText, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(row.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(row.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = statusColor(row.status).copy(alpha = 0.13f)
                        ) {
                            Text(
                                row.status,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = statusColor(row.status)
                            )
                        }
                    }
                }
                if (row.isActive || row.isComplete || row.status == "Paused") {
                    if (row.total > 0L) {
                        LinearProgressIndicator(
                            progress = { row.percent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = if (row.isComplete) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (row.isComplete) "Downloaded ${formatBytes(row.downloaded)}" else "${row.percent}% · ${formatBytes(row.downloaded)} / ${formatBytes(row.total)}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            if (!row.isComplete) Text("${row.percent}%", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        }
                    } else if (row.isActive || row.status == "Paused") {
                        Text("${formatBytes(row.downloaded)} downloaded", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    if (row.custom && row.status == "Downloading") {
                        val etaSeconds = if (row.speed > 0L && row.total > row.downloaded) (row.total - row.downloaded) / row.speed else -1L
                        Text(
                            "${if (row.speed > 0L) "${formatBytes(row.speed)}/s" else "Calculating speed…"} · ${if (etaSeconds >= 0L) formatEta(etaSeconds) else "ETA —"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (row.reason.isNotBlank()) {
                    Text(row.reason, style = MaterialTheme.typography.bodySmall, color = if (row.isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (row.isProblem && row.technicalDetails.isNotBlank()) {
                    Text(
                        "Technical details: ${row.technicalDetails}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (row.isComplete) {
                        Button(onClick = onOpen, modifier = Modifier.weight(1f)) { Text("Open") }
                        OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) { Text("Share") }
                    } else if (row.isProblem) {
                        Button(onClick = onRetry, modifier = Modifier.weight(1f)) { Text("Retry") }
                    } else if (row.isWaiting) {
                        Text("Waiting for a free download slot", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    }
                    if (row.custom && (row.status == "Downloading" || row.status == "Paused")) {
                        OutlinedButton(onClick = onPauseResume) { Text(if (row.status == "Paused") "Resume" else "Pause") }
                    }
                    OutlinedButton(onClick = onDismiss) {
                        Text(if (row.isBusy) "Cancel" else "Dismiss")
                    }
                }
            }
        }
    }

    @Composable
    private fun statusColor(status: String): androidx.compose.ui.graphics.Color = when (status) {
        "Complete" -> androidx.compose.ui.graphics.Color(0xFF16845B)
        "Failed", "Record unavailable", "Cancelled" -> MaterialTheme.colorScheme.error
        "Paused by Android" -> androidx.compose.ui.graphics.Color(0xFFB7791F)
        else -> MaterialTheme.colorScheme.primary
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != STORAGE_PERMISSION_REQUEST) return
        val pending = pendingUrlAfterStoragePermission
        pendingUrlAfterStoragePermission = null
        if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED && !pending.isNullOrBlank()) {
            enqueueDownload(pending, loadRows()) { }
        } else if (pending != null) {
            Toast.makeText(this, "Storage permission is needed to publish downloads on this Android version", Toast.LENGTH_LONG).show()
        }
    }

    private fun enqueueDownload(input: String, existing: List<DownloadRow>, onSuccess: () -> Unit) {
        val cleanUrl = input.trim()
        val uri = runCatching { Uri.parse(cleanUrl) }.getOrNull()
        if ((uri?.scheme != "https" && uri?.scheme != "http") || uri.host.isNullOrBlank()) {
            Toast.makeText(this, "Enter a valid direct HTTP or HTTPS URL", Toast.LENGTH_SHORT).show()
            return
        }
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q &&
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            pendingUrlAfterStoragePermission = cleanUrl
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE_PERMISSION_REQUEST)
            return
        }
        try {
            val title = uniqueTitle(fileNameFrom(uri), existing)
            addToQueue(cleanUrl, title, "Downloading with TurboFetch")
            pumpQueue()
            Toast.makeText(this, "Added to TurboFetch queue", Toast.LENGTH_SHORT).show()
            onSuccess()
        } catch (error: Exception) {
            Toast.makeText(this, "Could not queue download: ${error.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun fileNameFrom(uri: Uri): String {
        val raw = uri.lastPathSegment
            ?.let { runCatching { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }.getOrNull() }
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?: "download-${System.currentTimeMillis()}.bin"
        return sanitizeFileName(raw)
    }

    private fun uniqueTitle(baseName: String, rows: List<DownloadRow>): String {
        val used = rows.map { it.title.lowercase() }.toSet()
        if (baseName.lowercase() !in used) return baseName
        val dot = baseName.lastIndexOf('.')
        val stem = if (dot > 0) baseName.substring(0, dot) else baseName
        val ext = if (dot > 0) baseName.substring(dot) else ""
        var n = 1
        while ("$stem ($n)$ext".lowercase() in used) n++
        return "$stem ($n)$ext"
    }

    private fun addToQueue(url: String, title: String, description: String) {
        DownloadQueueScheduler.add(this, url, title, description)
    }

    private fun pumpQueue() {
        CustomDownloadService.start(this)
    }

    private fun removeId(id: Long) {
        DownloadQueueScheduler.remove(this, id)
    }

    private fun loadRows(): List<DownloadRow> {
        val saved = JSONArray(preferences.getString("downloads", "[]") ?: "[]")
        val result = mutableListOf<DownloadRow>()
        for (i in 0 until saved.length()) {
            val item = saved.optJSONObject(i) ?: continue
            val id = item.optLong("id")
            val url = item.optString("url")
            val title = item.optString("title", "Download")
            val phase = item.optString("phase", "managed")
            val createdAt = item.optLong("createdAt", id)

            if (item.optBoolean("custom", false)) {
                val status = when (phase) {
                    CustomDownloadService.PHASE_WAITING -> "Waiting in queue"
                    CustomDownloadService.PHASE_DOWNLOADING -> "Downloading"
                    CustomDownloadService.PHASE_PAUSED -> "Paused"
                    CustomDownloadService.PHASE_COMPLETE -> "Complete"
                    CustomDownloadService.PHASE_FAILED -> "Failed"
                    CustomDownloadService.PHASE_CANCELLED -> "Cancelled"
                    else -> "Queued"
                }
                val totalBytes = item.optLong("totalBytes", -1L)
                val rawReason = item.optString("reason", "")
                val failureType = item.optString("failureType", "")
                val displayReason = if (phase == CustomDownloadService.PHASE_FAILED && failureType.isNotBlank() && rawReason.isNotBlank()) {
                    "$failureType · $rawReason"
                } else rawReason
                result.add(
                    DownloadRow(
                        id = id,
                        title = title,
                        url = url,
                        status = status,
                        downloaded = item.optLong("downloadedBytes", 0L).coerceAtLeast(0L),
                        total = totalBytes.takeIf { it > 0L } ?: 0L,
                        reason = displayReason,
                        technicalDetails = item.optString("lastError", ""),
                        createdAt = createdAt,
                        speed = item.optLong("bytesPerSecond", 0L).coerceAtLeast(0L),
                        custom = true,
                        contentUri = item.optString("contentUri", "")
                    )
                )
                continue
            }

            if (phase == "waiting") {
                result.add(DownloadRow(id, title, url, "Waiting in queue", 0, 0, "", createdAt))
                continue
            }
            if (phase == "failed-local") {
                result.add(DownloadRow(id, title, url, "Failed", 0, 0, item.optString("reason", "Could not start download"), createdAt))
                continue
            }
            val cursor = downloadManager.query(DownloadManager.Query().setFilterById(id))
            cursor.use {
                if (it != null && it.moveToFirst()) {
                    val statusValue = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val downloaded = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val reasonValue = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    val status = when (statusValue) {
                        DownloadManager.STATUS_PENDING -> "Queued"
                        DownloadManager.STATUS_RUNNING -> "Downloading"
                        DownloadManager.STATUS_PAUSED -> "Paused by Android"
                        DownloadManager.STATUS_SUCCESSFUL -> "Complete"
                        DownloadManager.STATUS_FAILED -> "Failed"
                        else -> "Unknown"
                    }
                    val reason = when {
                        statusValue == DownloadManager.STATUS_FAILED -> "Download failed · reason code $reasonValue"
                        statusValue == DownloadManager.STATUS_PAUSED -> "Paused by Android · reason code $reasonValue"
                        else -> ""
                    }
                    result.add(DownloadRow(id, title, url, status, downloaded, total, reason, createdAt))
                } else {
                    result.add(DownloadRow(id, title, url, "Record unavailable", 0, 0, "Android no longer exposes this download record. The history entry was kept.", createdAt))
                }
            }
        }
        return result.sortedByDescending { it.createdAt }
    }

    private fun dismissOrCancel(row: DownloadRow, refresh: () -> Unit) {
        if (row.isBusy) {
            if (row.custom) {
                CustomDownloadService.start(this, CustomDownloadService.ACTION_CANCEL, row.id)
            } else if (row.id > 0L) {
                downloadManager.remove(row.id)
                removeId(row.id)
            }
            Toast.makeText(this, "Download cancelled", Toast.LENGTH_SHORT).show()
        } else {
            if (row.custom && !row.isComplete) deletePartialTransfer(row.id)
            removeId(row.id)
            Toast.makeText(this, "History entry dismissed; the downloaded file is not deleted", Toast.LENGTH_SHORT).show()
        }
        pumpQueue()
        refresh()
    }

    private fun deletePartialTransfer(id: Long) {
        val saved = JSONArray(preferences.getString("downloads", "[]") ?: "[]")
        for (i in 0 until saved.length()) {
            val item = saved.optJSONObject(i) ?: continue
            if (item.optLong("id") != id) continue
            val destination = item.optString("destination", "")
            if (destination.isNotBlank()) {
                val base = File(destination)
                File(destination + ".part").delete()
                File(destination + ".part.properties").delete()
                File(destination + ".segments.properties").delete()
                base.parentFile?.listFiles()
                    ?.filter { it.name.startsWith(base.name + ".segment-") }
                    ?.forEach { it.delete() }
                File(base.parentFile, base.name + ".assembling").delete()
            }
            return
        }
    }

    private fun controlDownload(row: DownloadRow, refresh: () -> Unit) {
        if (!row.custom) return
        val action = if (row.status == "Paused") CustomDownloadService.ACTION_RESUME else CustomDownloadService.ACTION_PAUSE
        CustomDownloadService.start(this, action, row.id)
        refresh()
    }

    private fun retry(row: DownloadRow, existing: List<DownloadRow>, refresh: () -> Unit) {
        if (row.custom) {
            DownloadQueueScheduler.requeue(this, row.id)
            CustomDownloadService.start(this)
            Toast.makeText(this, "Retry queued · saved partial data will be checked", Toast.LENGTH_SHORT).show()
            refresh()
            return
        }
        val uri = runCatching { Uri.parse(row.url) }.getOrNull()
        if (uri == null || uri.host.isNullOrBlank() || (uri.scheme != "http" && uri.scheme != "https")) {
            Toast.makeText(this, "The saved URL is not valid. Add the link again.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val title = uniqueTitle(row.title, existing)
            addToQueue(row.url, title, "Retrying with TurboFetch")
            pumpQueue()
            Toast.makeText(this, "Retry added to queue", Toast.LENGTH_SHORT).show()
            refresh()
        } catch (error: Exception) {
            Toast.makeText(this, "Retry could not be queued: ${error.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun uriForDownload(id: Long): Uri? {
        val saved = JSONArray(preferences.getString("downloads", "[]") ?: "[]")
        for (i in 0 until saved.length()) {
            val item = saved.optJSONObject(i) ?: continue
            if (item.optLong("id") != id) continue
            val stored = item.optString("contentUri", "")
            if (stored.isNotBlank()) return runCatching { Uri.parse(stored) }.getOrNull()
            break
        }
        return downloadManager.getUriForDownloadedFile(id)
    }

    private fun openDownloadedFile(id: Long) {
        try {
            val uri = uriForDownload(id)
            if (uri == null) {
                Toast.makeText(this, "Downloaded file could not be located", Toast.LENGTH_LONG).show()
                return
            }
            val mimeType = contentResolver.getType(uri) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "No compatible app could open this file, or the file is unavailable", Toast.LENGTH_LONG).show()
        }
    }

    private fun shareDownloadedFile(id: Long) {
        try {
            val uri = uriForDownload(id)
            if (uri == null) {
                Toast.makeText(this, "Downloaded file could not be located", Toast.LENGTH_LONG).show()
                return
            }
            val share = Intent(Intent.ACTION_SEND).apply {
                type = contentResolver.getType(uri) ?: "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "Share downloaded file"))
        } catch (_: Exception) {
            Toast.makeText(this, "Could not share this file", Toast.LENGTH_LONG).show()
        }
    }
}

private fun formatEta(seconds: Long): String {
    if (seconds < 0L) return "—"
    if (seconds < 60L) return "${seconds}s"
    if (seconds < 3600L) return "${seconds / 60}m ${seconds % 60}s"
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return "${hours}h ${minutes}m"
}

private fun searchMatches(row: DownloadRow, query: String): Boolean =
    query.isBlank() || row.title.contains(query, ignoreCase = true) || row.url.contains(query, ignoreCase = true)

