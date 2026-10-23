package com.fiveydr.turbofetch

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Calendar
import java.util.Locale

class AdvancedToolsActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("turbofetch", Context.MODE_PRIVATE) }
    private lateinit var urlInput: EditText
    private lateinit var batchInput: EditText
    private lateinit var speedInput: EditText
    private lateinit var segmentsInput: EditText
    private lateinit var concurrencyInput: EditText
    private lateinit var proxyHostInput: EditText
    private lateinit var proxyPortInput: EditText
    private lateinit var wifiOnly: CheckBox
    private lateinit var userAgentInput: EditText
    private lateinit var refererInput: EditText
    private lateinit var cookieInput: EditText
    private var pendingExport = ""

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readTextDocument(uri)?.let { queueText(it, "Imported links") }
    }
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) writeTextDocument(uri, pendingExport)
    }
    private val hashLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) calculateChecksums(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(20))
        }
        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(24))
        }
        scroll.addView(column)
        outer.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        outer.addView(TextView(this).apply {
            text = "Private by design · settings and link history remain on this device"
            textSize = 12f
            setPadding(0, dp(10), 0, 0)
        })
        setContentView(outer)

        addText(column, "TurboFetch tools", 25f, true)
        addText(column, "Browser · batch links · scheduler · transfer controls · checksums", 13f, false)
        addButton(column, "Open built-in browser") { startActivity(Intent(this, TurboBrowserActivity::class.java)) }

        section(column, "Add and batch")
        urlInput = editText(column, "Direct URL (HTTP/HTTPS)", prefs.getString("last_tool_url", "").orEmpty())
        addButton(column, "Queue URL") { queueText(urlInput.text.toString(), "Added manually") }
        batchInput = editText(column, "Paste many URLs or a link list", "")
        batchInput.minLines = 4
        batchInput.maxLines = 8
        batchInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        batchInput.isSingleLine = false
        addButton(column, "Queue all links in text") { queueText(batchInput.text.toString(), "Batch download") }
        addButton(column, "Import links from a text file") { importLauncher.launch(arrayOf("text/plain", "text/*", "application/octet-stream")) }
        addButton(column, "Import links from clipboard") {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this).toString() else ""
            if (text.isBlank()) toast("Clipboard is empty") else queueText(text, "Clipboard import")
        }
        addButton(column, "Export download URLs") {
            val rows = JSONArray(prefs.getString("downloads", "[]") ?: "[]")
            val urls = (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.optString("url") }
                .filter { DownloadLinkTools.isHttpUrl(it) }.distinct()
            pendingExport = urls.joinToString(separator = "\n", postfix = if (urls.isNotEmpty()) "\n" else "")
            if (pendingExport.isBlank()) toast("There are no download URLs to export")
            else exportLauncher.launch("turbofetch-links.txt")
        }

        section(column, "Queue controls")
        addButton(column, "Pause all active downloads") { controlAll(resume = false) }
        addButton(column, "Resume paused / retry failed downloads") { controlAll(resume = true) }
        addButton(column, "Clear completed history (files stay saved)") { clearCompletedHistory() }

        section(column, "Schedule a download")
        addText(column, "Choose a URL above, then set a future date and time. TurboFetch will add it to the queue when the alarm fires.", 13f, false)
        addButton(column, "Schedule the URL above…") { scheduleUrl() }

        section(column, "Transfer settings")
        speedInput = editText(column, "Global speed limit (KB/s; 0 = unlimited)", (prefs.getLong("speed_limit_bytes_per_second", 0L) / 1024L).toString(), InputType.TYPE_CLASS_NUMBER)
        segmentsInput = editText(column, "Parallel parts per large file (2–16)", prefs.getInt("segments_per_download", 4).toString(), InputType.TYPE_CLASS_NUMBER)
        concurrencyInput = editText(column, "Simultaneous downloads (1–30)", prefs.getInt("max_concurrent_downloads", 3).toString(), InputType.TYPE_CLASS_NUMBER)
        proxyHostInput = editText(column, "HTTP proxy host (blank = direct)", prefs.getString("proxy_host", "").orEmpty())
        proxyPortInput = editText(column, "HTTP proxy port (blank/0 = direct)", prefs.getInt("proxy_port", 0).takeIf { it > 0 }?.toString().orEmpty(), InputType.TYPE_CLASS_NUMBER)
        wifiOnly = CheckBox(this).apply {
            text = "Download only while connected to Wi-Fi"
            textSize = 15f
            isChecked = prefs.getBoolean("wifi_only", false)
        }
        column.addView(wifiOnly)
        addButton(column, "Save transfer settings") { saveTransferSettings() }
        addText(column, "Parallel parts apply only to large files where the server supports stable byte ranges. Proxy settings affect TurboFetch downloads. Wi-Fi-only queues wait while Wi-Fi is unavailable.", 12f, false)

        section(column, "Request headers")
        addText(column, "Optional headers apply to new downloads queued from these tools. Cookies are stored locally with the download record; avoid shared devices.", 12f, false)
        userAgentInput = editText(column, "User-Agent (optional)", "")
        refererInput = editText(column, "Referer (optional)", "")
        cookieInput = editText(column, "Cookie (optional)", "")
        addText(column, "These fields apply only to downloads queued from this screen.", 12f, false)

        section(column, "File integrity")
        addButton(column, "Calculate MD5 and SHA-256 for a file…") { hashLauncher.launch(arrayOf("*/*")) }
        addText(column, "Checksums help confirm a file has not changed; they do not prove that a file is safe.", 12f, false)
    }

    private fun queueText(text: String, description: String) {
        val urls = DownloadLinkTools.extractUrls(text)
        if (urls.isEmpty()) { toast("No valid HTTP/HTTPS links found"); return }
        val currentRows = JSONArray(prefs.getString("downloads", "[]") ?: "[]")
        val used = mutableSetOf<String>()
        for (i in 0 until currentRows.length()) used.add(currentRows.optJSONObject(i)?.optString("title").orEmpty().lowercase(Locale.ROOT))
        val headers = linkedMapOf<String, String>()
        if (::userAgentInput.isInitialized && userAgentInput.text.isNotBlank()) headers["User-Agent"] = userAgentInput.text.toString().trim()
        if (::refererInput.isInitialized && refererInput.text.isNotBlank()) headers["Referer"] = refererInput.text.toString().trim()
        if (::cookieInput.isInitialized && cookieInput.text.isNotBlank()) headers["Cookie"] = cookieInput.text.toString().trim()
        urls.forEach { url ->
            var title = DownloadLinkTools.fileNameFromUrl(url)
            if (title.lowercase(Locale.ROOT) in used) {
                val dot = title.lastIndexOf('.')
                val stem = if (dot > 0) title.substring(0, dot) else title
                val ext = if (dot > 0) title.substring(dot) else ""
                var n = 1
                while ("$stem ($n)$ext".lowercase(Locale.ROOT) in used) n++
                title = "$stem ($n)$ext"
            }
            used.add(title.lowercase(Locale.ROOT))
            DownloadQueueScheduler.add(this, url, title, description, headers)
            prefs.edit().putString("last_tool_url", url).apply()
        }
        CustomDownloadService.start(this)
        toast("Added ${urls.size} link${if (urls.size == 1) "" else "s"} to the queue")
    }

    private fun saveTransferSettings() {
        val speedKb = speedInput.text.toString().trim().toLongOrNull() ?: 0L
        val parts = segmentsInput.text.toString().trim().toIntOrNull() ?: 4
        val concurrent = concurrencyInput.text.toString().trim().toIntOrNull() ?: 3
        val host = proxyHostInput.text.toString().trim()
        val port = proxyPortInput.text.toString().trim().toIntOrNull() ?: 0
        if (host.isNotBlank() && port !in 1..65535) { toast("Enter a proxy port from 1 to 65535"); return }
        prefs.edit()
            .putLong("speed_limit_bytes_per_second", speedKb.coerceAtLeast(0L) * 1024L)
            .putInt("segments_per_download", parts.coerceIn(2, 16))
            .putInt("max_concurrent_downloads", concurrent.coerceIn(1, 30))
            .putString("proxy_host", if (host.isBlank()) "" else host)
            .putInt("proxy_port", if (host.isBlank()) 0 else port)
            .putBoolean("wifi_only", wifiOnly.isChecked)
            .apply()
        DownloadRuntimeOptions.refresh(this)
        CustomDownloadService.start(this)
        toast("Settings saved · parts ${parts.coerceIn(2, 16)} · simultaneous downloads ${concurrent.coerceIn(1, 30)}")
    }

    private fun scheduleUrl() {
        val url = urlInput.text.toString().trim()
        if (!DownloadLinkTools.isHttpUrl(url)) { toast("Enter a valid URL in the first field first"); return }
        val now = Calendar.getInstance()
        DatePickerDialog(this, { _, year, month, day ->
            val date = Calendar.getInstance().apply {
                set(Calendar.YEAR, year); set(Calendar.MONTH, month); set(Calendar.DAY_OF_MONTH, day)
            }
            TimePickerDialog(this, { _, hour, minute ->
                date.set(Calendar.HOUR_OF_DAY, hour); date.set(Calendar.MINUTE, minute)
                date.set(Calendar.SECOND, 0); date.set(Calendar.MILLISECOND, 0)
                runCatching {
                    DownloadScheduleManager.schedule(this, url, DownloadLinkTools.fileNameFromUrl(url), date.timeInMillis)
                }.onSuccess {
                    toast("Scheduled for ${DateFormat.getDateTimeInstance().format(date.time)}")
                }.onFailure { toast(it.message ?: "Could not schedule download") }
            }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show()
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).apply {
            datePicker.minDate = now.timeInMillis
        }.show()
    }

    private fun controlAll(resume: Boolean) {
        val rows = JSONArray(prefs.getString("downloads", "[]") ?: "[]")
        val ids = mutableListOf<Long>()
        for (i in 0 until rows.length()) {
            val item = rows.optJSONObject(i) ?: continue
            if (!item.optBoolean("custom", false)) continue
            val phase = item.optString("phase")
            if (resume && (phase == CustomDownloadService.PHASE_PAUSED || phase == CustomDownloadService.PHASE_FAILED)) {
                item.put("phase", CustomDownloadService.PHASE_WAITING).put("reason", "")
                ids.add(item.optLong("id"))
            } else if (!resume && (phase == CustomDownloadService.PHASE_DOWNLOADING || phase == CustomDownloadService.PHASE_WAITING)) ids.add(item.optLong("id"))
        }
        prefs.edit().putString("downloads", rows.toString()).apply()
        ids.forEach { CustomDownloadService.start(this, if (resume) CustomDownloadService.ACTION_RESUME else CustomDownloadService.ACTION_PAUSE, it) }
        CustomDownloadService.start(this)
        toast(if (resume) "Resume/retry requested for ${ids.size} downloads" else "Pause requested for ${ids.size} downloads")
    }

    private fun clearCompletedHistory() {
        val saved = JSONArray(prefs.getString("downloads", "[]") ?: "[]")
        val keep = JSONArray()
        var removed = 0
        for (i in 0 until saved.length()) {
            val item = saved.optJSONObject(i) ?: continue
            if (item.optString("phase") == CustomDownloadService.PHASE_COMPLETE || item.optString("phase") == "complete") removed++ else keep.put(item)
        }
        prefs.edit().putString("downloads", keep.toString()).apply()
        toast("Removed $removed completed history entries. Downloaded files were not deleted.")
    }

    private fun readTextDocument(uri: Uri): String? = runCatching {
        contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
    }.onFailure { toast("Could not read selected file") }.getOrNull()

    private fun writeTextDocument(uri: Uri, text: String) {
        runCatching {
            contentResolver.openOutputStream(uri, "w")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) }
                ?: error("Could not open output file")
        }.onSuccess { toast("Exported link list") }.onFailure { toast("Export failed: ${it.message}") }
    }

    private fun calculateChecksums(uri: Uri) {
        toast("Calculating checksums…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val md5 = MessageDigest.getInstance("MD5")
                    val sha = MessageDigest.getInstance("SHA-256")
                    contentResolver.openInputStream(uri)?.use { input ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            md5.update(buffer, 0, count); sha.update(buffer, 0, count)
                        }
                    } ?: error("Cannot open selected file")
                    "MD5  ${md5.digest().toHex()}\nSHA-256  ${sha.digest().toHex()}"
                }
            }
            result.onSuccess { value ->
                android.app.AlertDialog.Builder(this@AdvancedToolsActivity)
                    .setTitle("File checksums").setMessage(value)
                    .setPositiveButton("Copy") { _, _ ->
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("File checksums", value))
                        toast("Checksums copied")
                    }.setNegativeButton("Close", null).show()
            }.onFailure { toast("Checksum failed: ${it.message}") }
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun section(parent: LinearLayout, value: String) {
        parent.addView(TextView(this).apply {
            text = value; textSize = 19f; setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(22), 0, dp(7))
        })
    }

    private fun addText(parent: LinearLayout, value: String, size: Float, bold: Boolean) {
        parent.addView(TextView(this).apply {
            text = value; textSize = size; if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(6))
        })
    }

    private fun editText(parent: LinearLayout, hintText: String, value: String, input: Int = InputType.TYPE_CLASS_TEXT): EditText {
        val field = EditText(this).apply {
            hint = hintText; setText(value); textSize = 15f; inputType = input
            singleLine = input != (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        parent.addView(field, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(5) })
        return field
    }

    private fun addButton(parent: LinearLayout, label: String, action: () -> Unit) {
        parent.addView(Button(this).apply { text = label; setOnClickListener { action() } },
            LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3); bottomMargin = dp(3) })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()
}
