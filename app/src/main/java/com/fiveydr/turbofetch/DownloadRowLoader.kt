package com.fiveydr.turbofetch

import android.app.DownloadManager
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * Reads saved queue metadata and reconciles legacy Android DownloadManager rows.
 * Kept separate from the Activity so UI code doesn't own persistence decoding.
 */
internal class DownloadRowLoader(
    private val preferences: SharedPreferences,
    private val downloadManager: DownloadManager,
) {
    fun loadRows(): List<DownloadRow> {
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
    
}
