package com.fiveydr.turbofetch

import android.content.SharedPreferences
import org.json.JSONArray

/**
 * Single synchronization boundary for the JSON-backed download queue.
 *
 * Queue metadata remains in SharedPreferences for compatibility with existing installs, but
 * queue reads and read-modify-write operations must go through this store. The scheduler object
 * is the shared monitor used by the scheduler and foreground service, so UI-originated changes
 * cannot overwrite a concurrent service transition with an old snapshot.
 */
internal object DownloadQueueStore {
    private const val KEY_DOWNLOADS = "downloads"
    private const val EMPTY_QUEUE = "[]"

    private val monitor: Any
        get() = DownloadQueueScheduler

    /** Returns an independent snapshot, safe for read-only iteration outside the lock. */
    fun snapshot(preferences: SharedPreferences): JSONArray =
        synchronized(monitor) { readLocked(preferences) }

    /** Atomically replaces the stored queue using the same lock as service transitions. */
    fun replace(preferences: SharedPreferences, rows: JSONArray) {
        synchronized(monitor) { writeLocked(preferences, rows) }
    }

    /**
     * Performs a read-modify-write transaction. If [change] throws, no partial JSON is persisted.
     * The callback must only mutate the supplied array and should not launch asynchronous work.
     */
    fun <T> transaction(preferences: SharedPreferences, change: (JSONArray) -> T): T =
        synchronized(monitor) {
            val rows = readLocked(preferences)
            val result = change(rows)
            writeLocked(preferences, rows)
            result
        }

    /** Internal helpers for callers that already hold the shared queue monitor. */
    internal fun readLocked(preferences: SharedPreferences): JSONArray =
        JSONArray(preferences.getString(KEY_DOWNLOADS, EMPTY_QUEUE) ?: EMPTY_QUEUE)

    internal fun writeLocked(preferences: SharedPreferences, rows: JSONArray) {
        preferences.edit().putString(KEY_DOWNLOADS, rows.toString()).apply()
    }
}
