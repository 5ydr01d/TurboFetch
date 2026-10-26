package com.fiveydr.turbofetch

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Presentational Compose components for the download screen.
 *
 * State ownership and download actions stay in MainActivity; these components only render
 * the supplied state and emit user actions through callbacks.
 */
@Composable
internal fun HomeHeader(activeCount: Int, completedCount: Int, total: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.76f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
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
internal fun StatPill(label: String, count: Int) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(count.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun AdvancedToolsCard(onBrowser: () -> Unit, onTools: () -> Unit) {
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
internal fun AddDownloadCard(value: String, onValueChange: (String) -> Unit, onAdd: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
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
internal fun SectionHeader(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun EmptyState(title: String, message: String, action: String, onAction: () -> Unit) {
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
internal fun DownloadCard(
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.72f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(iconText, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(row.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(row.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = statusColor(row.status).copy(alpha = 0.13f)
                    ) {
                        Text(
                            row.status,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
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
internal fun statusColor(status: String): androidx.compose.ui.graphics.Color = when (status) {
    "Complete" -> androidx.compose.ui.graphics.Color(0xFF16845B)
    "Failed", "Record unavailable", "Cancelled" -> MaterialTheme.colorScheme.error
    "Paused by Android" -> androidx.compose.ui.graphics.Color(0xFFB7791F)
    else -> MaterialTheme.colorScheme.primary
}

