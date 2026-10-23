package com.fiveydr.turbofetch

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Advances the app-managed queue after a system-managed transfer completes or fails.
 * The actual transfer is still owned by Android's DownloadManager.
 */
class DownloadCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val pendingResult = goAsync()
        Thread {
            try {
                DownloadQueueScheduler.pump(context.applicationContext)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }
}
