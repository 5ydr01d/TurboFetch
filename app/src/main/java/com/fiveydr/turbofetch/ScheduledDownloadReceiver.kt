package com.fiveydr.turbofetch

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

internal object DownloadScheduleManager {
    private const val PREFS = "turbofetch"
    private const val KEY = "scheduled_downloads"

    fun schedule(context: Context, url: String, title: String, atMillis: Long): Long {
        require(DownloadLinkTools.isHttpUrl(url)) { "Enter a valid HTTP or HTTPS URL" }
        require(atMillis > System.currentTimeMillis()) { "Choose a future time" }
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = System.currentTimeMillis() * 1000L + (System.nanoTime() % 1000L)
        val entries = JSONArray(prefs.getString(KEY, "[]") ?: "[]")
        entries.put(JSONObject().put("id", id).put("url", url).put("title", title).put("at", atMillis))
        prefs.edit().putString(KEY, entries.toString()).apply()
        setAlarm(context, id, url, title, atMillis)
        return id
    }

    private fun setAlarm(context: Context, id: Long, url: String, title: String, atMillis: Long) {
        val intent = Intent(context, ScheduledDownloadReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_URL, url)
            .putExtra(EXTRA_TITLE, title)
        val pending = PendingIntent.getBroadcast(
            context, id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= 23) alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        else alarm.set(AlarmManager.RTC_WAKEUP, atMillis, pending)
    }

    fun restore(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val entries = JSONArray(prefs.getString(KEY, "[]") ?: "[]")
        val now = System.currentTimeMillis()
        for (i in 0 until entries.length()) {
            val item = entries.optJSONObject(i) ?: continue
            val id = item.optLong("id")
            val at = item.optLong("at")
            if (id != 0L && at > now) {
                setAlarm(context, id, item.optString("url"), item.optString("title", "download.bin"), at)
            }
        }
    }

    fun remove(context: Context, id: Long) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val original = JSONArray(prefs.getString(KEY, "[]") ?: "[]")
        val updated = JSONArray()
        for (i in 0 until original.length()) {
            val item = original.optJSONObject(i) ?: continue
            if (item.optLong("id") != id) updated.put(item)
        }
        prefs.edit().putString(KEY, updated.toString()).apply()
    }

    const val ACTION_FIRE = "com.fiveydr.turbofetch.action.SCHEDULED_DOWNLOAD"
    const val EXTRA_ID = "scheduled_id"
    const val EXTRA_URL = "scheduled_url"
    const val EXTRA_TITLE = "scheduled_title"
}

class ScheduledDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> DownloadScheduleManager.restore(context)
            DownloadScheduleManager.ACTION_FIRE -> {
                val id = intent.getLongExtra(DownloadScheduleManager.EXTRA_ID, 0L)
                val url = intent.getStringExtra(DownloadScheduleManager.EXTRA_URL).orEmpty()
                val title = intent.getStringExtra(DownloadScheduleManager.EXTRA_TITLE).orEmpty()
                DownloadScheduleManager.remove(context, id)
                if (DownloadLinkTools.isHttpUrl(url)) {
                    DownloadQueueScheduler.add(context, url, title.ifBlank { DownloadLinkTools.fileNameFromUrl(url) }, "Scheduled download")
                    CustomDownloadService.start(context)
                }
            }
        }
    }
}
