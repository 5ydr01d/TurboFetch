package com.fiveydr.turbofetch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Owns custom HTTP transfers in a foreground service. Queue metadata stays in the same local
 * store as the existing UI; partial data is held privately until the file is complete, then
 * published to the user's Downloads collection.
 */
class CustomDownloadService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val controls = ConcurrentHashMap<Long, ResumableHttpEngine.Control>()
    private val lock = DownloadQueueScheduler
    private val engine by lazy { AdaptiveDownloadEngine(segmentCount = prefs.getInt("segment_count", 4).coerceIn(2, 8)) }
    private val prefs by lazy { getSharedPreferences("turbofetch", Context.MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        DownloadRuntimeOptions.refresh(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("Preparing download queue"))
        migrateLegacyRequestHeaders()
        recoverInterruptedRows()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DownloadRuntimeOptions.refresh(this)
        when (intent?.action) {
            ACTION_PAUSE -> pause(intent.getLongExtra(EXTRA_ID, 0L))
            ACTION_RESUME -> resume(intent.getLongExtra(EXTRA_ID, 0L))
            ACTION_CANCEL -> cancel(intent.getLongExtra(EXTRA_ID, 0L))
            else -> scheduleQueue()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ limits dataSync foreground services. Preserve partial files and stop cleanly.
        controls.values.forEach { it.pause() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        controls.values.forEach { it.pause() }
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * Migrate older queue rows that stored request headers as readable JSON. Completed or
     * cancelled rows no longer need credentials, so those headers are discarded instead.
     */
    private fun migrateLegacyRequestHeaders() {
        synchronized(lock) {
            val rows = readRows()
            var changed = false
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val legacy = row.optJSONObject("headers") ?: continue
                val phase = row.optString("phase")
                if (phase == PHASE_COMPLETE || phase == PHASE_CANCELLED) {
                    row.remove("headers")
                    row.remove("headersEncrypted")
                    changed = true
                    continue
                }
                if (row.optString("headersEncrypted").isNotBlank()) {
                    row.remove("headers")
                    changed = true
                    continue
                }
                val values = linkedMapOf<String, String>()
                val keys = legacy.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = legacy.optString(key)
                    if (key.isNotBlank() && value.isNotBlank()) values[key] = value
                }
                try {
                    if (values.isNotEmpty()) {
                        row.put("headersEncrypted", DownloadHeaderVault.encrypt(values, DownloadHeaderVault.associatedDataForDownload(row.optLong("id"), row.optString("url"))))
                    }
                    row.remove("headers")
                } catch (_: Exception) {
                    // Fail closed if legacy credentials cannot be protected on this device.
                    row.remove("headers")
                    row.remove("headersEncrypted")
                    row.put("phase", PHASE_FAILED)
                        .put("failureType", "Credential storage")
                        .put("reason", "Could not securely migrate saved request headers; re-add this download.")
                }
                changed = true
            }
            if (changed) writeRows(rows)
        }
    }

    private fun scheduleQueue() {
        val wifiOnly = prefs.getBoolean("wifi_only", false)
        if (wifiOnly) {
            val connectivity = getSystemService(android.net.ConnectivityManager::class.java)
            val network = connectivity.activeNetwork
            val capabilities = if (network != null) connectivity.getNetworkCapabilities(network) else null
            val onWifi = capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
            if (!onWifi) {
                updateNotification()
                if (jobs.isEmpty()) stopSelf()
                return
            }
        }
        val limit = prefs.getInt("max_concurrent_downloads", 3).coerceIn(1, 30)
        val waiting = synchronized(lock) {
            val rows = readRows()
            (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
                .filter { it.optBoolean("custom", false) && it.optString("phase") == PHASE_WAITING }
                .sortedBy { it.optLong("createdAt") }
                .map { it.optLong("id") }
        }
        val slots = (limit - jobs.size).coerceAtLeast(0)
        waiting.take(slots).forEach { startDownload(it) }
        updateNotification()
        if (jobs.isEmpty() && waiting.isEmpty()) stopSelf()
    }

    @Synchronized
    private fun startDownload(id: Long) {
        if (jobs.containsKey(id)) return
        val row = findRow(id) ?: return
        val url = row.optString("url")
        val title = row.optString("title", "download.bin")
        val savedHeaders = row.optJSONObject("headers")
        val encryptedHeaders = row.optString("headersEncrypted", "")
        val requestHeaders = try {
            when {
                encryptedHeaders.isNotBlank() -> DownloadHeaderVault.decrypt(encryptedHeaders, DownloadHeaderVault.associatedDataForDownload(id, url))
                savedHeaders != null -> {
                    // Migrate queue rows created before request-header encryption was added.
                    val legacy = linkedMapOf<String, String>()
                    val keys = savedHeaders.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = savedHeaders.optString(key)
                        if (key.isNotBlank() && value.isNotBlank()) legacy[key] = value
                    }
                    if (legacy.isNotEmpty()) {
                        val protected = DownloadHeaderVault.encrypt(legacy, DownloadHeaderVault.associatedDataForDownload(id, url))
                        updateRow(id) {
                            it.put("headersEncrypted", protected)
                            it.remove("headers")
                        }
                    } else {
                        updateRow(id) { it.remove("headers") }
                    }
                    legacy
                }
                else -> emptyMap()
            }
        } catch (error: Exception) {
            // If the Android Keystore key was removed (for example after a device restore),
            // never retry with missing credentials or silently save them in plaintext.
            updateRow(id) {
                it.put("phase", PHASE_FAILED)
                    .put("failureType", "Credential storage")
                    .put("reason", "Saved request credentials are unavailable on this installation. Re-add this download.")
                it.remove("headers")
                it.remove("headersEncrypted")
            }
            return
        }
        val directory = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir, "transfers")
        if (!directory.exists() && !directory.mkdirs()) {
            updateRow(id) { it.put("phase", PHASE_FAILED).put("reason", "Cannot create download storage directory") }
            return
        }
        val savedDestination = row.optString("destination").takeIf { it.isNotBlank() }?.let { File(it) }
        val destination = savedDestination ?: File(directory, DownloadLinkTools.transferFileName(id, title))
        val control = ResumableHttpEngine.Control()
        controls[id] = control
        updateRow(id) {
            it.put("phase", PHASE_DOWNLOADING)
                .put("reason", "")
                .put("destination", destination.absolutePath)
                .put("startedAt", System.currentTimeMillis())
        }
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = engine.download(url, destination, control, requestHeaders) { progress ->
                    updateRow(id) {
                        it.put("downloadedBytes", progress.transferredBytes)
                            .put("totalBytes", progress.totalBytes ?: -1L)
                            .put("bytesPerSecond", progress.bytesPerSecond)
                            .put("resumed", progress.resumed)
                            .put("updatedAt", System.currentTimeMillis())
                    }
                    updateNotification()
                }
                if (control.isCancelled()) {
                    result.file.delete()
                    throw ResumableHttpEngine.CancelledException(result.file)
                }
                val uri = publishToDownloads(result.file, title)
                updateRow(id) {
                    it.put("phase", PHASE_COMPLETE)
                        .put("contentUri", uri.toString())
                        .put("downloadedBytes", result.bytes)
                        .put("totalBytes", result.bytes)
                        .put("bytesPerSecond", 0)
                        .put("completedAt", System.currentTimeMillis())
                        .put("reason", "")
                    // Request headers are no longer needed once the transfer is complete.
                    // This drops cookies/authorization from persisted queue metadata.
                    it.remove("headers")
                    it.remove("headersEncrypted")
                }
                result.file.delete()
            } catch (_: ResumableHttpEngine.PausedException) {
                updateRow(id) { it.put("phase", PHASE_PAUSED).put("reason", "Paused · partial data saved for resume") }
            } catch (_: ResumableHttpEngine.CancelledException) {
                updateRow(id) { it.put("phase", PHASE_CANCELLED).put("reason", "Cancelled") }
            } catch (error: CancellationException) {
                val paused = control.isPaused()
                updateRow(id) {
                    it.put("phase", if (paused) PHASE_PAUSED else PHASE_WAITING)
                        .put("reason", if (paused) "Paused by Android's background execution limit · resume to continue" else "Interrupted · will resume when restarted")
                }
                throw error
            } catch (error: Exception) {
                val details = DownloadFailureDiagnostics.describe(error)
                updateRow(id) {
                    it.put("phase", PHASE_FAILED)
                        .put("reason", details.message)
                        .put("failureType", details.category)
                        .put("failedAt", System.currentTimeMillis())
                        .put("lastError", details.technicalDetails)
                }
            } finally {
                jobs.remove(id)
                controls.remove(id)
                scheduleQueue()
            }
        }
        jobs[id] = job
        job.start()
    }

    private fun pause(id: Long) {
        controls[id]?.pause() ?: updateRow(id) {
            if (it.optString("phase") == PHASE_WAITING || it.optString("phase") == PHASE_PAUSED) {
                it.put("phase", PHASE_PAUSED).put("reason", "Paused")
            }
        }
        updateNotification()
        scheduleQueue()
    }

    private fun resume(id: Long) {
        updateRow(id) {
            if (it.optString("phase") == PHASE_PAUSED || it.optString("phase") == PHASE_FAILED) {
                it.put("phase", PHASE_WAITING).put("reason", "")
            }
        }
        scheduleQueue()
    }

    private fun cancel(id: Long) {
        // An active engine owns its scratch files. Deleting them here races with its writer and
        // can recreate partial files after the UI already reports cancellation.
        val activeControl = controls[id]
        activeControl?.cancel()
        if (activeControl == null) {
            val row = findRow(id)
            val destination = row?.optString("destination").orEmpty()
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
        }
        updateRow(id) { it.put("phase", PHASE_CANCELLED).put("reason", "Cancelled") }
        updateNotification()
        scheduleQueue()
    }

    private fun recoverInterruptedRows() {
        synchronized(lock) {
            val rows = readRows()
            var changed = false
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                if (row.optString("phase") == "waiting" && !row.optBoolean("custom", false)) {
                    // Migrate queued entries created by the earlier DownloadManager-backed version.
                    row.put("custom", true)
                        .put("phase", PHASE_WAITING)
                        .put("reason", "Migrated to the resumable engine")
                    changed = true
                } else if (row.optBoolean("custom", false) && row.optString("phase") == PHASE_DOWNLOADING) {
                    row.put("phase", PHASE_WAITING)
                        .put("reason", "Interrupted · resuming from saved partial data")
                    changed = true
                }
            }
            if (changed) writeRows(rows)
        }
    }

    private fun publishToDownloads(source: File, title: String): Uri {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            val legacyDirectory = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "TurboFetch"
            )
            if (!legacyDirectory.exists() && !legacyDirectory.mkdirs()) {
                throw IOException("Cannot create public Downloads/TurboFetch directory")
            }
            val target = File(legacyDirectory, safeName(title))
            source.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return FileProvider.getUriForFile(this, "$packageName.fileprovider", target)
        }
        val resolver = contentResolver
        val mime = java.net.URLConnection.guessContentTypeFromName(title) ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, safeName(title))
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TurboFetch")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("Android could not create the downloaded file")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IOException("Could not write the downloaded file")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val ready = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                resolver.update(uri, ready, null, null)
            }
            return uri
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun updateRow(id: Long, change: (JSONObject) -> Unit) {
        synchronized(lock) {
            val rows = readRows()
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                if (row.optLong("id") == id) {
                    change(row)
                    writeRows(rows)
                    break
                }
            }
        }
    }

    private fun findRow(id: Long): JSONObject? = synchronized(lock) {
        val rows = readRows()
        (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
            .firstOrNull { it.optLong("id") == id }
    }

    private fun readRows() = DownloadQueueStore.snapshot(prefs)
    private fun writeRows(rows: JSONArray) { DownloadQueueStore.replace(prefs, rows) }

    private fun safeName(value: String): String {
        val cleaned = value.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.')
        return cleaned.take(180).ifBlank { "download.bin" }
    }

    private fun updateNotification() {
        val active = jobs.size
        val waiting = synchronized(lock) {
            val rows = readRows()
            (0 until rows.length()).count { rows.optJSONObject(it)?.optString("phase") == PHASE_WAITING }
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification("$active active · $waiting waiting"))
    }

    private fun notification(message: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder.setContentTitle("TurboFetch")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(jobs.isNotEmpty())
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        const val ACTION_START = "com.fiveydr.turbofetch.action.START"
        const val ACTION_PAUSE = "com.fiveydr.turbofetch.action.PAUSE"
        const val ACTION_RESUME = "com.fiveydr.turbofetch.action.RESUME"
        const val ACTION_CANCEL = "com.fiveydr.turbofetch.action.CANCEL"
        const val EXTRA_ID = "download_id"
        const val PHASE_WAITING = "custom-waiting"
        const val PHASE_DOWNLOADING = "custom-downloading"
        const val PHASE_PAUSED = "custom-paused"
        const val PHASE_COMPLETE = "custom-complete"
        const val PHASE_FAILED = "custom-failed"
        const val PHASE_CANCELLED = "custom-cancelled"
        private const val CHANNEL_ID = "turbofetch-downloads"
        private const val NOTIFICATION_ID = 2401

        fun start(context: Context, action: String = ACTION_START, id: Long? = null) {
            val intent = Intent(context, CustomDownloadService::class.java).setAction(action)
            id?.let { intent.putExtra(EXTRA_ID, it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }
}
