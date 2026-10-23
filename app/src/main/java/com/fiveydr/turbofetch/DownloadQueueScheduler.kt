package com.fiveydr.turbofetch

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Starts locally queued items whenever capacity becomes available.
 *
 * Android DownloadManager continues transfers independently. The receiver calls this scheduler
 * on completion so the next local queue item can begin without requiring the UI to be visible.
 */
internal object DownloadQueueScheduler {
    @Synchronized
    fun add(context: Context, url: String, title: String, description: String) {
        val preferences = context.applicationContext.getSharedPreferences("turbofetch", Context.MODE_PRIVATE)
        val saved = JSONArray(preferences.getString("downloads", "[]") ?: "[]")
        val usedIds = (0 until saved.length()).mapNotNull { saved.optJSONObject(it)?.optLong("id") }.toSet()
        var localId = -System.currentTimeMillis().coerceAtLeast(1L)
        while (localId in usedIds) localId--
        val createdAt = System.currentTimeMillis()
        saved.put(
            JSONObject()
                .put("id", localId)
                .put("url", url)
                .put("title", title)
                .put("phase", CustomDownloadService.PHASE_WAITING)
                .put("custom", true)
                .put("description", description)
                .put("createdAt", createdAt)
        )
        preferences.edit().putString("downloads", saved.toString()).apply()
    }

    @Synchronized
    fun remove(context: Context, id: Long) {
        val preferences = context.applicationContext.getSharedPreferences("turbofetch", Context.MODE_PRIVATE)
        val saved = JSONArray(preferences.getString("downloads", "[]") ?: "[]")
        val updated = JSONArray()
        for (i in 0 until saved.length()) {
            val item = saved.optJSONObject(i) ?: continue
            if (item.optLong("id") != id) updated.put(item)
        }
        preferences.edit().putString("downloads", updated.toString()).apply()
    }

    @Synchronized
    fun pump(context: Context) {
        val appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences("turbofetch", Context.MODE_PRIVATE)
        val downloadManager = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val saved = JSONArray(preferences.getString("downloads", "[]") ?: "[]")
        val limit = preferences.getInt("max_concurrent_downloads", 3).coerceIn(1, 5)
        val managedActive = countManagedActive(saved, downloadManager)
        val waitingItems = mutableListOf<JSONObject>()
        for (i in 0 until saved.length()) {
            val item = saved.optJSONObject(i) ?: continue
            if (item.optString("phase") == "waiting") waitingItems.add(item)
        }
        val idsToStart = selectQueuedIds(waitingItems.map { it.optLong("id") }, managedActive, limit).toSet()
        var changed = false
        for (item in waitingItems) {
            if (item.optLong("id") !in idsToStart) continue
            val url = item.optString("url")
            val uri = runCatching { Uri.parse(url) }.getOrNull()
            if (uri == null || uri.host.isNullOrBlank() || (uri.scheme != "https" && uri.scheme != "http")) {
                item.put("phase", "failed-local").put("reason", "Invalid or unsupported URL")
                changed = true
                continue
            }
            try {
                val title = item.optString("title", "download.bin")
                val description = item.optString("description", "Downloading with TurboFetch")
                val request = buildRequest(uri, title, description)
                val id = downloadManager.enqueue(request)
                item.put("id", id).put("phase", "managed").put("reason", "")
                changed = true
            } catch (error: Exception) {
                item.put("phase", "failed-local")
                    .put("reason", "Could not start: ${error.message ?: "unknown error"}")
                changed = true
            }
        }
        if (changed) preferences.edit().putString("downloads", saved.toString()).apply()
    }

    private fun countManagedActive(saved: JSONArray, manager: DownloadManager): Int {
        var count = 0
        for (i in 0 until saved.length()) {
            val item = saved.optJSONObject(i) ?: continue
            if (item.optString("phase") == "waiting" || item.optString("phase") == "failed-local") continue
            val id = item.optLong("id")
            if (id <= 0L) continue
            val cursor = manager.query(DownloadManager.Query().setFilterById(id))
            cursor.use {
                if (it != null && it.moveToFirst()) {
                    val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    if (status == DownloadManager.STATUS_PENDING ||
                        status == DownloadManager.STATUS_RUNNING ||
                        status == DownloadManager.STATUS_PAUSED
                    ) count++
                }
            }
        }
        return count
    }

    private fun buildRequest(uri: Uri, title: String, description: String): DownloadManager.Request {
        val request = DownloadManager.Request(uri)
            .setTitle(title)
            .setDescription(description)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationUri(
                Uri.fromFile(
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), title)
                )
            )
        request.allowScanningByMediaScanner()
        return request
    }
}
