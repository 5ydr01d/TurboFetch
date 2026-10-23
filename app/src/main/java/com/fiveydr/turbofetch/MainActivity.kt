package com.fiveydr.turbofetch

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
    val contentUri: String = ""
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
    Home("Home", "⌂"),
    Downloads("Downloads", "↓"),
    Files("Files", "▤"),
    Settings("Settings", "⚙")
}

private enum class DownloadFilter(val label: String) {
    All("All"), Active("Active"), Complete("Complete"), Problems("Issues")
}

class MainActivity : ComponentActivity() {
    private var pendingUrlAfterStoragePermission: String? = null
    private val STORAGE_PERMISSION_REQUEST = 8107
    private lateinit var downloadManager: DownloadManager
    private val preferences by lazy { getSharedPreferences("turbofetch", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        downloadManager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager

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
        var urlInput by remember { mutableStateOf("") }
        var search by remember { mutableStateOf("") }
        var filter by remember { mutableStateOf(DownloadFilter.All) }
        var rows by remember { mutableStateOf(emptyList<DownloadRow>()) }
        var loading by remember { mutableStateOf(true) }
        var maxConcurrent by remember {
            mutableIntStateOf(preferences.getInt("max_concurrent_downloads", 3).coerceIn(1, 5))
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
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text("TurboFetch", fontWeight = FontWeight.Bold)
                            Text(
                                "Fast downloads. No ads. No tracking.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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
                        HomeHeader(activeCount = activeCount, completedCount = completedCount, total = rows.size)
                        AddDownloadCard(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            onAdd = { enqueueDownload(urlInput, rows) { urlInput = ""; rows = loadRows() } }
                        )
                        SectionHeader("Recent downloads", "${rows.size} tracked")
                        if (rows.isEmpty()) {
                            EmptyState(
                                title = "Your downloads start here",
                                message = "Paste a direct file link above. Completed files stay in your history.",
                                action = "Browse downloads",
                                onAction = { tab = AppTab.Downloads }
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(rows.take(5), key = { it.id }) { row ->
                                    DownloadCard(
                                        row = row,
                                        onOpen = { openDownloadedFile(row.id) },
                                        onShare = { shareDownloadedFile(row.id) },
                                        onRetry = { retry(row, rows) { rows = loadRows() } },
                                        onPauseResume = { controlDownload(row) { rows = loadRows() } },
                                        onDismiss = { dismissOrCancel(row) { rows = loadRows() } }
                                    )
                                }
                                item {
                                    OutlinedButton(onClick = { tab = AppTab.Downloads }, modifier = Modifier.fillMaxWidth()) {
                                        Text("View all downloads")
                                    }
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
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                                            maxConcurrent = value.toInt().coerceIn(1, 5)
                                            preferences.edit().putInt("max_concurrent_downloads", maxConcurrent).apply()
                                            pumpQueue()
                                        },
                                        valueRange = 1f..5f,
                                        steps = 3
                                    )
                                    Text("Limits downloads started by TurboFetch. Lowering this value won't interrupt transfers already running.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Privacy by design", style = MaterialTheme.typography.titleMedium)
                                Text("No advertising SDKs or analytics SDKs. Download history is stored locally on this device.", style = MaterialTheme.typography.bodyMedium)
                                Text("New downloads use TurboFetch’s custom HTTP engine with pause/resume and partial-file recovery when the source supports safe byte-range requests. Segmented multi-connection downloads are not implemented yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
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
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.size(42.dp)
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
                result.add(
                    DownloadRow(
                        id = id,
                        title = title,
                        url = url,
                        status = status,
                        downloaded = item.optLong("downloadedBytes", 0L).coerceAtLeast(0L),
                        total = totalBytes.takeIf { it > 0L } ?: 0L,
                        reason = item.optString("reason", ""),
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
            removeId(row.id)
            Toast.makeText(this, "History entry dismissed; the downloaded file is not deleted", Toast.LENGTH_SHORT).show()
        }
        pumpQueue()
        refresh()
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

