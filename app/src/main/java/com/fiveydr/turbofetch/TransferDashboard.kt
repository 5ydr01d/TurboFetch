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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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

    Column(verticalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
        val glassShape = RoundedCornerShape(26.dp)
        Card(
            modifier = Modifier.fillMaxWidth().clip(glassShape),
            shape = glassShape,
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(glassShape)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.80f)
                            )
                        )
                    )
            ) {
                Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("TRANSFER CONTROL CENTER", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Text("Live download speed", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        }
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.76f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.48f))
                        ) {
                            Row(Modifier.padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Box(Modifier.size(7.dp).background(MaterialTheme.colorScheme.tertiary, RoundedCornerShape(50)))
                                Text("LIVE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(formatBytes(speed), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
                        Text("/s", modifier = Modifier.padding(start = 5.dp, bottom = 5.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.weight(1f))
                        Text("Peak " + formatBytes(history.maxOfOrNull { it.speed } ?: 0L) + "/s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                    SpeedHistoryGraph(visible.map { it.speed }, Modifier.fillMaxWidth().height(68.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("30s", "5m", "1h").forEach { option ->
                            val selected = window == option
                            Surface(
                                shape = RoundedCornerShape(9.dp),
                                color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.92f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.64f),
                                border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)),
                                modifier = Modifier.clickable { window = option }
                            ) {
                                Text(option, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Text("On-device samples", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            DashboardMetric("ACTIVE", active.size.toString(), "transfers", Modifier.weight(1f))
            DashboardMetric("QUEUED", waiting.toString(), "waiting", Modifier.weight(1f))
            DashboardMetric("DONE", complete.toString(), "files", Modifier.weight(1f))
        }
        if (expanded) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                DashboardMetric("EST. REMAINING", if (eta >= 0L) formatEta(eta) else "—", "active files", Modifier.weight(1f))
                DashboardMetric("ISSUES", issues.toString(), "need attention", Modifier.weight(1f))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onPauseAll, modifier = Modifier.weight(1f)) { Text("Ⅱ Pause all") }
            Button(onClick = onResumeAll, modifier = Modifier.weight(1f)) { Text("▶ Resume / retry") }
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.size(46.dp).clickable { expanded = !expanded }) {
                Box(contentAlignment = Alignment.Center) { Text(if (expanded) "⌃" else "···", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun DashboardMetric(label: String, value: String, caption: String, modifier: Modifier = Modifier) {
    Card(
            modifier,
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.68f)),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
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
