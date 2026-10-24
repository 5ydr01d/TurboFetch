package com.fiveydr.turbofetch

/** Immutable presentation model for a download row in the queue and history UI. */
internal data class DownloadRow(
    val id: Long,
    val title: String,
    val url: String,
    val status: String,
    val downloaded: Long,
    val total: Long,
    val reason: String = "",
    val createdAt: Long = id,
    val speed: Long = 0L,
    val custom: Boolean = false,
    val contentUri: String = "",
    val technicalDetails: String = ""
) {
    val percent: Int
        get() = if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else 0
    val isActive: Boolean get() = status == "Downloading" || status == "Queued" || status == "Paused by Android"
    val isWaiting: Boolean get() = status == "Waiting in queue"
    val isBusy: Boolean get() = isActive || isWaiting || status == "Paused"
    val isComplete: Boolean get() = status == "Complete"
    val isProblem: Boolean get() = status == "Failed" || status == "Record unavailable" || status == "Cancelled"
}
