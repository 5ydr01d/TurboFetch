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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.TextButton
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

private enum class AppTab(val label: String, val symbol: String) {
    Home("Queue", "↓"),
    Files("Library", "▤"),
    Settings("Settings", "⚙")
}

private enum class DownloadFilter(val label: String) {
    All("All"), Active("Active"), Complete("Complete"), Problems("Issues")
}

class MainActivity : ComponentActivity() {
    private var pendingUrlAfterStoragePermission: String? = null
    private var sharedUrlOnLaunch: String? = null
    private var clipboardCandidate by mutableStateOf<String?>(null)
    private var lastClipboardPrompt: String? = null
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        inspectClipboardForDownload()
    }
    private val STORAGE_PERMISSION_REQUEST = 8107
    private lateinit var downloadManager: DownloadManager
    private val preferences by lazy { getSharedPreferences("turbofetch", Context.MODE_PRIVATE) }
    private val rowLoader by lazy { DownloadRowLoader(preferences, downloadManager) }
    private lateinit var toolImportLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var toolExportLauncher: ActivityResultLauncher<String>
    private lateinit var toolChecksumLauncher: ActivityResultLauncher<Array<String>>
    private var pendingToolExport: String = ""

    override fun onResume() {
        super.onResume()
        if (preferences.getBoolean("clipboard_detection_enabled", true)) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            runCatching { clipboard.addPrimaryClipChangedListener(clipboardListener) }
            inspectClipboardForDownload()
        }
    }

    override fun onPause() {
        runCatching {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.removePrimaryClipChangedListener(clipboardListener)
        }
        super.onPause()
    }

    private fun inspectClipboardForDownload() {
        if (!preferences.getBoolean("clipboard_detection_enabled", true)) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val copiedText = runCatching {
            if (clipboard.hasPrimaryClip()) clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
            else ""
        }.getOrDefault("")
        val candidate = DownloadLinkTools.extractUrls(copiedText)
            .firstOrNull { DownloadLinkTools.isVideoOrDocumentUrl(it) }
        if (candidate == null) {
            lastClipboardPrompt = null
            return
        }
        if (candidate == lastClipboardPrompt || clipboardCandidate == candidate) return
        lastClipboardPrompt = candidate
        clipboardCandidate = candidate
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Migrate older manually configured header overrides out of plaintext preferences.
        loadSavedToolHeaders()
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
            CompositionLocalProvider(                LocalDensity provides Density(baseDensity.density, baseDensity.fontScale * textScale)
            ) {
                MaterialTheme(colorScheme = if (darkMode) darkColorScheme(primary = androidx.compose.ui.graphics.Color(0xFFE7C99B), onPrimary = androidx.compose.ui.graphics.Color(0xFF241D16), secondary = androidx.compose.ui.graphics.Color(0xFFB7A5C8), tertiary = androidx.compose.ui.graphics.Color(0xFF9ADBC7), background = androidx.compose.ui.graphics.Color(0xFF111116), surface = androidx.compose.ui.graphics.Color(0xFF19191F), surfaceVariant = androidx.compose.ui.graphics.Color(0xFF292830)) else lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF5B465D), onPrimary = androidx.compose.ui.graphics.Color(0xFFFFFFFF), primaryContainer = androidx.compose.ui.graphics.Color(0xFFE9E0EE), onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF302438), secondary = androidx.compose.ui.graphics.Color(0xFF927D9B), tertiary = androidx.compose.ui.graphics.Color(0xFF397D70), background = androidx.compose.ui.graphics.Color(0xFFF6F2F7), surface = androidx.compose.ui.graphics.Color(0xFFF9F6FA), surfaceVariant = androidx.compose.ui.graphics.Color(0xFFECE5EF))) {
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
        var addDialogOpen by remember { mutableStateOf(false) }
        var urlInput by remember { mutableStateOf(sharedUrlOnLaunch.orEmpty()) }
        var search by remember { mutableStateOf("") }
        var filter by remember { mutableStateOf(DownloadFilter.All) }
        var rows by remember { mutableStateOf(emptyList<DownloadRow>()) }
        var loading by remember { mutableStateOf(true) }
        var maxConcurrent by remember {
            mutableIntStateOf(preferences.getInt("max_concurrent_downloads", 3).coerceIn(1, 30))
        }
        var clipboardDetectionEnabled by remember {
            mutableStateOf(preferences.getBoolean("clipboard_detection_enabled", true))
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
            containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.94f),
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
                            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) { Text("Downloads", fontWeight = FontWeight.Bold); Text("TurboFetch transfer manager", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    },
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { addDialogOpen = true }) {
                    Text("+", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                }
            },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f), tonalElevation = 0.dp) {
                    listOf(AppTab.Home, AppTab.Files, AppTab.Settings).forEach { destination ->
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
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            item(key = "transfer-dashboard") {
                                TransferDashboard(
                                    rows = rows,
                                    onPauseAll = { controlAllToolDownloads(false); rows = loadRows() },
                                    onResumeAll = { controlAllToolDownloads(true); rows = loadRows() }
                                )
                            }
                            item(key = "queue-search") {
                                OutlinedTextField(
                                    value = search,
                                    onValueChange = { search = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = { Text("Search downloads") },
                                    placeholder = { Text("Filename or URL") },
                                    shape = RoundedCornerShape(12.dp)
                                )
                            }
                            item(key = "queue-filters") {
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
                            }
                            item(key = "queue-heading") {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Text("Download queue", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    Text("$activeCount active · $waitingCount waiting", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (loading && rows.isEmpty()) {
                                item(key = "queue-loading") {
                                    Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) { Text("Loading downloads…") }
                                }
                            } else {
                                val queueRows = visibleRows.filter { !it.isComplete }
                                if (queueRows.isEmpty()) {
                                    item(key = "queue-empty") {
                                        EmptyState(
                                            title = if (search.isBlank()) "Queue is empty" else "No matching downloads",
                                            message = if (search.isBlank()) "Add a link with + or share a URL from another app. Completed files appear in Library." else "Try another filename, URL, or status filter.",
                                            action = "Open library",
                                            onAction = { tab = AppTab.Files }
                                        )
                                    }
                                } else {
                                    items(queueRows, key = { it.id }) { row ->
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
                    }
                    AppTab.Files -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("Download library", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text("${filesRows.size} completed files", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            FilterChip(selected = tab == AppTab.Home, onClick = { tab = AppTab.Home }, label = { Text("Queue") })
                        }
                        OutlinedTextField(
                            value = search,
                            onValueChange = { search = it },                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Find a file") },
                            placeholder = { Text("Search by filename") }
                        )
                        if (filesRows.isEmpty()) {
                            EmptyState(
                                title = "No completed files yet",
                                message = "When a download completes, it will appear here with Open and Share actions.",
                                action = "View downloads",
                                onAction = { tab = AppTab.Home }
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
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text("Clipboard download prompts", style = MaterialTheme.typography.titleMedium)
                                        Text("Offer video and document links copied while TurboFetch is open, or when you return to it. Android limits clipboard access in the background.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = clipboardDetectionEnabled,
                                        onCheckedChange = { enabled ->
                                            clipboardDetectionEnabled = enabled
                                            preferences.edit().putBoolean("clipboard_detection_enabled", enabled).apply()
                                            if (!enabled) clipboardCandidate = null else inspectClipboardForDownload()
                                        }
                                    )
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
        if (clipboardCandidate != null) {
            val candidate = clipboardCandidate.orEmpty()
            AlertDialog(
                onDismissRequest = { clipboardCandidate = null },
                title = { Text("Download copied link?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("This looks like a video or document link.")
                        Text(
                            DownloadLinkTools.fileNameFromUrl(candidate),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(candidate, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        clipboardCandidate = null
                        enqueueDownload(candidate, loadRows()) { }
                    }) { Text("Download") }
                },
                dismissButton = {
                    TextButton(onClick = { clipboardCandidate = null }) { Text("Not now") }
                }
            )
        }
        if (addDialogOpen) {
            AlertDialog(
                onDismissRequest = { addDialogOpen = false },
                title = { Text("Add download") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Direct download URL") },
                            placeholder = { Text("https://example.com/file.zip") }
                        )
                        Text("HTTP and HTTPS links are supported. For video pages, use the video quality tool in Settings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            enqueueDownload(urlInput, rows) {
                                urlInput = ""
                                rows = loadRows()
                                addDialogOpen = false
                            }
                        },
                        enabled = urlInput.isNotBlank()
                    ) { Text("Add to queue") }
                },
                dismissButton = { TextButton(onClick = { addDialogOpen = false }) { Text("Cancel") } }
            )
        }
    }

    @Composable
    private fun AdvancedToolsSettingsSection() {
        var directUrl by remember { mutableStateOf(preferences.getString("last_tool_url", "").orEmpty()) }
        var batchText by remember { mutableStateOf("") }
        var speedKb by remember { mutableStateOf((preferences.getLong("speed_limit_bytes_per_second", 0L) / 1024L).toString()) }
        var partsText by remember { mutableStateOf(preferences.getInt("segments_per_download", 4).toString()) }
        var concurrencyText by remember { mutableStateOf(preferences.getInt("max_concurrent_downloads", 3).toString()) }
        var autoRetryCountText by remember { mutableStateOf(preferences.getInt("auto_retry_count", 3).toString()) }
        var retryDelayText by remember { mutableStateOf(preferences.getInt("auto_retry_delay_seconds", 5).toString()) }
        var proxyHost by remember { mutableStateOf(preferences.getString("proxy_host", "").orEmpty()) }
        var proxyPort by remember { mutableStateOf(preferences.getInt("proxy_port", 0).takeIf { it > 0 }?.toString().orEmpty()) }
        var wifiOnly by remember { mutableStateOf(preferences.getBoolean("wifi_only", false)) }
        val savedToolHeaders = remember { loadSavedToolHeaders() }
        var userAgent by remember { mutableStateOf(savedToolHeaders["User-Agent"].orEmpty()) }
        var referer by remember { mutableStateOf(savedToolHeaders["Referer"].orEmpty()) }
        var cookie by remember { mutableStateOf(savedToolHeaders["Cookie"].orEmpty()) }
        var videoSiteUrl by remember { mutableStateOf("") }
        var videoSiteTitle by remember { mutableStateOf("") }
        var videoFormats by remember { mutableStateOf(emptyList<VideoSiteExtractor.Format>()) }
        var selectedVideoFormatUrl by remember { mutableStateOf("") }
        var videoFormatStatus by remember { mutableStateOf("Paste a supported video-page URL to inspect available direct formats.") }
        var inspectingVideoFormats by remember { mutableStateOf(false) }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Advanced tools", "Direct downloads, queue utilities, and transfer controls")
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
                                        videoFormatStatus = "Enter a valid HTTP/HTTPS video page URL."                                    } else {
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
                        val saved = DownloadQueueStore.snapshot(preferences)
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
                    OutlinedTextField(value = autoRetryCountText, onValueChange = { autoRetryCountText = it.filter(Char::isDigit) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Automatic retries (0–10)") })
                    OutlinedTextField(value = retryDelayText, onValueChange = { retryDelayText = it.filter(Char::isDigit) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Initial retry delay (seconds; 1–300)") })
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
                            val autoRetries = (autoRetryCountText.toIntOrNull() ?: 3).coerceIn(0, 10)
                            val retryDelay = (retryDelayText.toIntOrNull() ?: 5).coerceIn(1, 300)
                            preferences.edit().putLong("speed_limit_bytes_per_second", speed * 1024L)
                                .putInt("segments_per_download", parts).putInt("max_concurrent_downloads", concurrent)
                                .putInt("auto_retry_count", autoRetries).putInt("auto_retry_delay_seconds", retryDelay)
                                .putString("proxy_host", host).putInt("proxy_port", if (host.isBlank()) 0 else port)
                                .putBoolean("wifi_only", wifiOnly).apply()
                            DownloadRuntimeOptions.refresh(this@MainActivity)
                            CustomDownloadService.start(this@MainActivity)
                            pumpQueue()
                            Toast.makeText(this@MainActivity, "Transfer settings saved", Toast.LENGTH_SHORT).show()
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Save transfer settings") }
                    Text("Automatic retries use exponential backoff for transient timeouts, connection failures, HTTP 408/425/429 and 5xx responses. Certificate failures and other permanent errors are not retried. Parallel parts require server byte-range support and a stable validator.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            Toast.makeText(this@MainActivity, "Request headers saved securely", Toast.LENGTH_SHORT).show()
                        }.onFailure {
                            Toast.makeText(this@MainActivity, "Could not encrypt request headers; previous settings were kept.", Toast.LENGTH_LONG).show()
                        }
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

    /**
     * Loads encrypted Advanced Tools header overrides, migrating older plaintext preferences
     * the first time this version is launched. Raw legacy keys are removed after migration.
     */
    private fun loadSavedToolHeaders(): Map<String, String> {
        val encrypted = preferences.getString("tool_headers_encrypted", "").orEmpty()
        if (encrypted.isNotBlank()) {
            return runCatching { DownloadHeaderVault.decryptToolSettings(encrypted) }
                .getOrElse {
                    Toast.makeText(this, "Saved request headers could not be decrypted. Re-enter them in Settings.", Toast.LENGTH_LONG).show()
                    emptyMap()
                }
        }

        val legacy = linkedMapOf<String, String>()
        preferences.getString("tool_user_agent", "").orEmpty().takeIf { it.isNotBlank() }?.let { legacy["User-Agent"] = it }
        preferences.getString("tool_referer", "").orEmpty().takeIf { it.isNotBlank() }?.let { legacy["Referer"] = it }
        preferences.getString("tool_cookie", "").orEmpty().takeIf { it.isNotBlank() }?.let { legacy["Cookie"] = it }
        if (legacy.isEmpty()) return emptyMap()

        return try {
            val protected = DownloadHeaderVault.encryptToolSettings(legacy)
            preferences.edit()
                .putString("tool_headers_encrypted", protected)
                .remove("tool_user_agent")
                .remove("tool_referer")
                .remove("tool_cookie")
                .apply()
            legacy
        } catch (_: Exception) {
            // Never keep legacy credential strings in plaintext when migration cannot be secured.
            preferences.edit()
                .remove("tool_user_agent")
                .remove("tool_referer")
                .remove("tool_cookie")
                .apply()
            Toast.makeText(this, "Could not securely migrate saved headers. Please enter them again.", Toast.LENGTH_LONG).show()
            emptyMap()
        }
    }

    private fun saveToolHeaders(headers: Map<String, String>) {
        val cleaned = headers.filter { (name, value) -> name.isNotBlank() && value.isNotBlank() }
        val editor = preferences.edit()
            .remove("tool_user_agent")
            .remove("tool_referer")
            .remove("tool_cookie")
        if (cleaned.isEmpty()) {
            editor.remove("tool_headers_encrypted").apply()
        } else {
            // Encrypt before committing; on failure, leave the previous encrypted value intact.
            val protected = DownloadHeaderVault.encryptToolSettings(cleaned)