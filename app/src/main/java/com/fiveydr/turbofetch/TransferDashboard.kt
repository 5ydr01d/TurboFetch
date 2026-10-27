package com.fiveydr.turbofetch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.max

private data class SpeedPoint(val time: Long, val speed: Long)

@Composable
internal fun TransferDashboard(rows: List<DownloadRow>, onPauseAll: () -> Unit, onResumeAll: () -> Unit) {
    val history = remember { mutableStateListOf<SpeedPoint>() }
    val currentRows by rememberUpdatedState(rows)
    var window by remember { mutableStateOf("30s") }
    var expanded by remember { mutableStateOf(false) }
    val active = rows.filter { it.isBusy && !it.isWaiting && it.status != "Paused" }
    val speed = active.sumOf { it.speed.coerceAtLeast(0L) }
    val waiting = rows.count { it.isWaiting }
    val complete = rows.count { it.isComplete }
    val issues = rows.count { it.isProblem || it.status == "Failed" }
    val remaining = active.sumOf { (it.total - it.downloaded).coerceAtLeast(0L) }
    val eta = if (speed > 0L && remaining > 0L) remaining / speed else -1L
    LaunchedEffect(Unit) {
        while (true) {
            history.add(SpeedPoint(System.currentTimeMillis(), currentRows.filter { it.isBusy && !it.isWaiting && it.status != "Paused" }.sumOf { it.speed.coerceAtLeast(0L) }))
            val cutoff = System.currentTimeMillis() - 3600000L
            while (history.isNotEmpty() && history.first().time < cutoff) history.removeAt(0)
            while (history.size > 2400) history.removeAt(0)
            delay(1500L)
        }
    }
    val duration = when (window) { "5m" -> 300000L; "1h" -> 3600000L; else -> 30000L }
    val visible = history.filter { it.time >= System.currentTimeMillis() - duration }
    val shape = RoundedCornerShape(12.dp)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainer, shape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape).padding(horizontal = 11.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    Text("TRANSFER MONITOR", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(formatBytes(speed), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                        Text("/s", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 3.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(if (active.isEmpty()) "IDLE" else "● LIVE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = if (active.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF16845B))
                    Text("Peak ${'$'}{formatBytes(history.maxOfOrNull { it.speed } ?: 0L)}/s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (expanded) {
                SpeedHistoryGraph(visible.map { it.speed }, Modifier.fillMaxWidth().height(34.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("30s", "5m", "1h").forEach { option ->
                        val selected = window == option
                        Surface(shape = RoundedCornerShape(6.dp), color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface, contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, modifier = Modifier.clickable { window = option }) {
                            Text(option, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text("Local samples", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                CompactMetric("ACTIVE", active.size.toString())
                MetricDivider()
                CompactMetric("QUEUED", waiting.toString())
                MetricDivider()
                CompactMetric("DONE", complete.toString())
                MetricDivider()
                CompactMetric("ISSUES", issues.toString())
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onPauseAll, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 7.dp, vertical = 6.dp)) { Text("Ⅱ Pause all", maxLines = 1) }
            Button(onClick = onResumeAll, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 7.dp, vertical = 6.dp)) { Text("▶ Resume / retry", maxLines = 1) }
            Surface(shape = RoundedCornerShape(9.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.size(40.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(9.dp)).clickable { expanded = !expanded }) { Box(contentAlignment = Alignment.Center) { Text(if (expanded) "⌃" else "···", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) } }
        }
        if (expanded) Text("Estimated remaining: ${'$'}{if (eta >= 0L) formatEta(eta) else "—"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CompactMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MetricDivider() { Box(Modifier.width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.outlineVariant)) }
@Composable
private fun DashboardMetric(label: String, value: String, caption: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainer, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(horizontal = 9.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}
@Composable
private fun SpeedHistoryGraph(points: List<Long>, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.16f)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        for (i in 0..3) {
            val y = h * i / 3f
            drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1.dp.toPx())
        }
        if (points.isEmpty()) return@Canvas
        val peak = max(1L, points.maxOrNull() ?: 1L).toFloat()
        val path = Path()
        points.forEachIndexed { index, value ->
            val x = if (points.size == 1) w else w * index / (points.size - 1).toFloat()
            val y = h - (value.toFloat() / peak) * (h * 0.88f) - h * 0.06f
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, lineColor, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}
