package com.fiveydr.turbofetch

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.json.JSONArray
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

private data class DownloadRow(
    val id: Long,
    val title: String,
    val url: String,
    val status: String,
    val downloaded: Long,
    val total: Long,
    val reason: String = ""
) {
    val percent: Int
        get() = if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else 0
}

class MainActivity : ComponentActivity() {
    private lateinit var downloadManager: DownloadManager
    private val preferences by lazy { getSharedPreferences("turbofetch", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        downloadManager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var urlInput by remember { mutableStateOf("") }
                    var rows by remember { mutableStateOf(loadRows()) }
                    var loading by remember { mutableStateOf(false) }

                    LaunchedEffect(Unit) {
                        while (true) {
                            rows = loadRows()
                            delay(1000)
                        }
                    }

                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("TurboFetch", style = MaterialTheme.typography.headlineLarge)
                        Text(
                            "Fast, straightforward downloads. No ads. No analytics.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            label = { Text("Direct download URL") },
                            placeholder = { Text("https://example.com/file.zip") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Button(
                            onClick = {
                                val cleanUrl = urlInput.trim()
                                if (!cleanUrl.startsWith("https://", true) &&
                                    !cleanUrl.startsWith("http://", true)
                                ) {
                                    Toast.makeText(this@MainActivity, "Enter a valid HTTP or HTTPS URL", Toast.LENGTH_SHORT).show()
                                } else {
                                    try {
                                        val uri = Uri.parse(cleanUrl)
                                        if (uri.host.isNullOrBlank()) {
                                            Toast.makeText(this@MainActivity, "The URL is incomplete", Toast.LENGTH_SHORT).show()
                                        } else {
                                            val request = DownloadManager.Request(uri)
                                                .setTitle(fileNameFrom(uri))
                                                .setDescription("Downloading with TurboFetch")
                                                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                                .setAllowedOverMetered(true)
                                                .setAllowedOverRoaming(false)
                                                .setDestinationInExternalFilesDir(
                                                    this@MainActivity,
                                                    Environment.DIRECTORY_DOWNLOADS,
                                                    fileNameFrom(uri)
                                                )
                                            val id = downloadManager.enqueue(request)
                                            saveId(id, cleanUrl, fileNameFrom(uri))
                                            urlInput = ""
                                            rows = loadRows()
                                            Toast.makeText(this@MainActivity, "Download added to queue", Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (error: Exception) {
                                        Toast.makeText(this@MainActivity, "Could not start download: ${error.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Add download")
                        }

                        Divider()
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Your downloads", style = MaterialTheme.typography.titleLarge)
                            Text("${rows.size} tracked")
                        }

                        if (loading) CircularProgressIndicator()

                        if (rows.isEmpty()) {
                            Text("Your queue is empty. Paste a direct file URL above to get started.")
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(rows, key = { it.id }) { row ->
                                    Card(modifier = Modifier.fillMaxWidth()) {
                                        Column(
                                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                row.title,
                                                style = MaterialTheme.typography.titleMedium,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                row.url,
                                                style = MaterialTheme.typography.bodySmall,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(row.status, style = MaterialTheme.typography.bodyMedium)
                                            if (row.status == "Downloading") {
                                                if (row.total > 0L) {
                                                    Text("${row.percent}% · ${formatBytes(row.downloaded)} / ${formatBytes(row.total)}")
                                                } else {
                                                    Text("${formatBytes(row.downloaded)} downloaded")
                                                }
                                            }
                                            if (row.reason.isNotBlank()) {
                                                Text(row.reason, style = MaterialTheme.typography.bodySmall)
                                            }
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                if (row.status == "Failed" || row.status == "Cancelled") {
                                                    Button(onClick = {
                                                        retry(row.url, row.title)
                                                        rows = loadRows()
                                                    }) { Text("Retry") }
                                                }
                                                Button(onClick = {
                                                    if (row.status == "Downloading" || row.status == "Queued") {
                                                        downloadManager.remove(row.id)
                                                    }
                                                    removeId(row.id)
                                                    rows = loadRows()
                                                }) {
                                                    Text(if (row.status == "Downloading" || row.status == "Queued") "Cancel" else "Dismiss")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun fileNameFrom(uri: Uri): String {
        val raw = uri.lastPathSegment
            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?: "download-${System.currentTimeMillis()}.bin"
        return raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(150)
    }

    private fun saveId(id: Long, url: String, title: String) {
        val all = JSONArray(preferences.getString("downloads", "[]") ?: "[]")
        all.put(org.json.JSONObject().put("id", id).put("url", url).put("title", title))
        preferences.edit().putString("downloads", all.toString()).apply()
    }

    private fun removeId(id: Long) {
        val current = JSONArray(preferences.getString("downloads", "[]") ?: "[]")
        val updated = JSONArray()
        for (i in 0 until current.length()) {
            val item = current.optJSONObject(i) ?: continue
            if (item.optLong("id") != id) updated.put(item)
        }
        preferences.edit().putString("downloads", updated.toString()).apply()
    }

    private fun loadRows(): List<DownloadRow> {
        val saved = JSONArray(preferences.getString("downloads", "[]") ?: "[]")
        val result = mutableListOf<DownloadRow>()
        for (i in 0 until saved.length()) {
            val item = saved.optJSONObject(i) ?: continue
            val id = item.optLong("id")
            val url = item.optString("url")
            val title = item.optString("title", "Download")
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
                    val reason = if (statusValue == DownloadManager.STATUS_FAILED ||
                        statusValue == DownloadManager.STATUS_PAUSED
                    ) "Reason code: $reasonValue" else ""
                    result.add(DownloadRow(id, title, url, status, downloaded, total, reason))
                } else {
                    result.add(DownloadRow(id, title, url, "Cancelled", 0, 0))
                }
            }
        }
        return result.sortedByDescending { it.id }
    }

    private fun retry(url: String, title: String) {
        try {
            val uri = Uri.parse(url)
            val request = DownloadManager.Request(uri)
                .setTitle(title)
                .setDescription("Retrying with TurboFetch")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, title)
            val newId = downloadManager.enqueue(request)
            saveId(newId, url, title)
            Toast.makeText(this, "Retry queued", Toast.LENGTH_SHORT).show()
        } catch (error: Exception) {
            Toast.makeText(this, "Retry failed: ${error.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun formatBytes(value: Long): String {
        if (value < 1024) return "$value B"
        val kb = value / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }
}
