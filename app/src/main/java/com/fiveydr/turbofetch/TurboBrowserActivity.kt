package com.fiveydr.turbofetch

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

class TurboBrowserActivity : ComponentActivity() {
    private data class BrowserTab(val web: WebView, var title: String, val incognito: Boolean)

    private val prefs by lazy { getSharedPreferences("turbofetch", Context.MODE_PRIVATE) }
    private val tabs = mutableListOf<BrowserTab>()
    private val detectedLinks = ConcurrentHashMap.newKeySet<String>()
    private var activeIndex = 0
    private var privateMode = false
    private lateinit var address: EditText
    private lateinit var tabStrip: LinearLayout
    private lateinit var webContainer: FrameLayout
    private lateinit var mediaButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        val initial = intent.dataString ?: intent.getStringExtra("url") ?: "https://www.google.com"
        addTab(normalizeAddress(initial), privateMode)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF7F7FA.toInt())
        }

        // Compact browser action strip at the top; it stays separate from the page.
        val actionScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(3), dp(4), dp(3))
        }
        controls.addView(makeButton("‹") { currentTab()?.web?.let { if (it.canGoBack()) it.goBack() } })
        controls.addView(makeButton("›") { currentTab()?.web?.let { if (it.canGoForward()) it.goForward() } })
        controls.addView(makeButton("↻") { currentTab()?.web?.reload() })
        controls.addView(makeButton("Home") { currentTab()?.web?.loadUrl("https://www.google.com") })
        mediaButton = makeButton("Media 0") { showDetectedLinks() }
        controls.addView(mediaButton)
        controls.addView(makeButton("Grab") { capturePageLinks() })
        controls.addView(makeButton("★") { addBookmark() })
        controls.addView(makeButton("Saved") { showSavedItems("bookmarks", "Bookmarks") })
        controls.addView(makeButton("History") { showSavedItems("browser_history", "History") })
        controls.addView(makeButton("Find") { findInPage() })
        controls.addView(makeButton("Share") { sharePage() })
        controls.addView(makeButton("Private") {
            privateMode = !privateMode
            addTab(currentTab()?.web?.url ?: "https://www.google.com", privateMode)
            toast(if (privateMode) "Private tab opened; browsing history is not saved" else "Normal tab opened")
        })
        actionScroll.addView(controls)
        root.addView(actionScroll, LinearLayout.LayoutParams(-1, dp(48)))

        // Side-mounted vertical tabs keep tab switching reachable without taking a full row.
        val browsingArea = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        val tabsScroll = android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        tabStrip = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), dp(4), dp(3), dp(4))
        }
        tabsScroll.addView(tabStrip, android.widget.ScrollView.LayoutParams(-1, -2))
        browsingArea.addView(tabsScroll, LinearLayout.LayoutParams(dp(88), 0, 1f))

        webContainer = FrameLayout(this)
        browsingArea.addView(webContainer, LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(browsingArea, LinearLayout.LayoutParams(-1, 0, 1f))

        // Address/navigation bar is anchored at the bottom for easier one-handed use.
        val addressBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(5), dp(7), dp(7))
            setBackgroundColor(0xFFE9EAF0.toInt())
        }
        address = EditText(this).apply {
            hint = "Search or enter URL"
            setSingleLine(true)
            textSize = 15f
            setSelectAllOnFocus(false)
            setPadding(dp(12), 0, dp(8), 0)
            setBackgroundColor(0xFFFFFFFF.toInt())
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
        }
        addressBar.addView(address, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginEnd = dp(5)
        })
        addressBar.addView(makeButton("Go") { navigateFromAddress() })
        addressBar.addView(makeButton("+") { addTab("https://www.google.com", privateMode) })
        root.addView(addressBar, LinearLayout.LayoutParams(-1, -2))

        setContentView(root)
        address.setOnEditorActionListener { _, _, _ ->
            navigateFromAddress()
            true
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun addTab(url: String, incognito: Boolean) {
        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = !incognito
        web.settings.javaScriptCanOpenWindowsAutomatically = false
        web.settings.setSupportMultipleWindows(false)
        web.settings.loadsImagesAutomatically = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        web.addJavascriptInterface(object {
            @JavascriptInterface
            fun foundMany(value: String) {
                val candidates = value.split('\n').map { it.trim() }
                    .filter { DownloadLinkTools.isHttpUrl(it) && DownloadLinkTools.likelyMediaOrFile(it) }
                if (candidates.isNotEmpty()) runOnUiThread {
                    detectedLinks.addAll(candidates)
                    updateMediaButton()
                }
            }
        }, "TurboFetch")
        web.setDownloadListener { downloadUrl, _, contentDisposition, mimeType, _ ->
            val guessed = android.webkit.URLUtil.guessFileName(downloadUrl, contentDisposition, mimeType)
            confirmDownload(downloadUrl, guessed)
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                // Block pop-up windows rather than opening surprise tabs.
                return false
            }
            override fun onReceivedTitle(view: WebView?, title: String?) {
                val idx = tabs.indexOfFirst { it.web === view }
                if (idx >= 0 && !title.isNullOrBlank()) {
                    tabs[idx].title = title.take(32)
                    rebuildTabStrip()
                }
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                val target = request.url.toString()
                if (DownloadLinkTools.likelyMediaOrFile(target)) {
                    confirmDownload(target, DownloadLinkTools.fileNameFromUrl(target))
                    return true
                }
                return !(target.startsWith("https://", true) || target.startsWith("http://", true))
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
                val link = request.url.toString()
                if (DownloadLinkTools.likelyMediaOrFile(link)) {
                    detectedLinks.add(link)
                    if (Thread.currentThread() == mainLooper.thread) updateMediaButton()
                    else runOnUiThread { updateMediaButton() }
                }
                if (!request.isForMainFrame && isAdOrTracker(request.url.host.orEmpty())) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (view === currentTab()?.web && !url.isNullOrBlank()) address.setText(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (view === currentTab()?.web && !url.isNullOrBlank()) {
                    address.setText(url)
                    if (!currentTab().let { it?.incognito == true }) saveHistory(url, view?.title.orEmpty())
                    // Collect direct media/files found in links and HTML5 media tags. This is not a
                    // DRM bypass; only URLs the page exposes to its own DOM or requests are listed.
                    val js = """(function(){try{var a=Array.from(document.querySelectorAll('a[href],video[src],video source[src],audio[src],audio source[src],source[src]')).map(function(x){return x.href||x.src||'';});var p=Array.from(document.querySelectorAll('video,audio')).flatMap(function(x){return [x.src].concat(Array.from(x.querySelectorAll('source')).map(function(s){return s.src;}));});var u=Array.from(new Set(a.concat(p))).filter(function(x){return /^https?:/i.test(x)&&/\.(apk|zip|rar|7z|gz|pdf|mp4|m4v|mkv|mov|webm|mp3|m4a|aac|flac|wav|ogg|jpg|jpeg|png|webp|m3u8?|mpd|ts)(?:$|[?#])/i.test(x);});if(window.TurboFetch){window.TurboFetch.foundMany(u.join('\n'));}}catch(e){}})();"""
                    view?.evaluateJavascript(js, null)
                }
            }
        }
        tabs.add(BrowserTab(web, "New tab", incognito))
        activeIndex = tabs.lastIndex
        rebuildTabStrip()
        showTab(activeIndex)
        web.loadUrl(url)
    }

    private fun showTab(index: Int) {
        if (index !in tabs.indices) return
        activeIndex = index
        val tab = tabs[index]
        webContainer.removeAllViews()
        (tab.web.parent as? ViewGroup)?.removeView(tab.web)
        webContainer.addView(tab.web, FrameLayout.LayoutParams(-1, -1))
        address.setText(tab.web.url.orEmpty())
        rebuildTabStrip()
    }

    private fun rebuildTabStrip() {
        if (!::tabStrip.isInitialized) return
        tabStrip.removeAllViews()
        tabs.forEachIndexed { index, tab ->
            val label = (if (tab.incognito) "◐ " else "") + (index + 1).toString() + " · " + tab.title
            val chip = makeButton(label.take(18)) { showTab(index) }
            chip.maxWidth = dp(78)
            chip.alpha = if (index == activeIndex) 1f else 0.65f
            chip.maxLines = 2
            chip.setOnLongClickListener {
                closeTab(index)
                true
            }
            tabStrip.addView(chip)
        }
    }

    private fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val old = tabs.removeAt(index)
        (old.web.parent as? ViewGroup)?.removeView(old.web)
        old.web.stopLoading()
        old.web.destroy()
        if (tabs.isEmpty()) {
            addTab("https://www.google.com", privateMode)
            return
        }
        showTab(index.coerceAtMost(tabs.lastIndex))
    }

    private fun currentTab(): BrowserTab? = tabs.getOrNull(activeIndex)

    private fun navigateFromAddress() {
        val target = normalizeAddress(address.text.toString())
        currentTab()?.web?.loadUrl(target)
        address.clearFocus()
    }

    private fun findInPage() {
        val field = EditText(this).apply {
            hint = "Find text on this page"
            singleLine = true
        }
        AlertDialog.Builder(this)
            .setTitle("Find in page")
            .setView(field)
            .setPositiveButton("Find") { _, _ ->
                val query = field.text.toString()
                if (query.isNotBlank()) currentTab()?.web?.findAllAsync(query)
            }
            .setNeutralButton("Clear highlights") { _, _ ->
                currentTab()?.web?.clearMatches()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sharePage() {
        val tab = currentTab() ?: return
        val url = tab.web.url ?: return
        val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, url)
            putExtra(android.content.Intent.EXTRA_SUBJECT, tab.web.title.orEmpty().ifBlank { url })
        }
        startActivity(android.content.Intent.createChooser(share, "Share page"))
    }

    private fun confirmDownload(url: String, proposedTitle: String) {
        if (!DownloadLinkTools.isHttpUrl(url)) return
        AlertDialog.Builder(this)
            .setTitle("Download file?")
            .setMessage(proposedTitle + "\n\n" + url)
            .setPositiveButton("Download") { _, _ -> enqueue(url, proposedTitle) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun enqueue(url: String, title: String) {
        if (!DownloadLinkTools.isHttpUrl(url)) { toast("Only HTTP/HTTPS links are supported"); return }
        DownloadQueueScheduler.add(this, url, title.ifBlank { DownloadLinkTools.fileNameFromUrl(url) }, "Captured by TurboFetch Browser")
        CustomDownloadService.start(this)
        toast("Added to download queue")
    }

    private fun capturePageLinks() {
        val web = currentTab()?.web ?: return
        val js = """(function(){try{var u=Array.from(new Set(Array.from(document.querySelectorAll('a[href],video[src],video source[src],audio[src],audio source[src],source[src]')).map(function(x){return x.href||x.src||'';}).filter(function(x){return /^https?:/i.test(x)&&/\.(apk|zip|rar|7z|gz|pdf|mp4|m4v|mkv|mov|webm|mp3|m4a|aac|flac|wav|ogg|jpg|jpeg|png|webp|m3u8?|mpd|ts)(?:$|[?#])/i.test(x);})));if(window.TurboFetch){window.TurboFetch.foundMany(u.join('\n'));}}catch(e){}})();"""
        web.evaluateJavascript(js) {
            showDetectedLinks()
        }
        // shouldInterceptRequest and the injected JS populate the list asynchronously.
        web.postDelayed({ showDetectedLinks() }, 350)
    }

    private fun showDetectedLinks() {
        val candidates = detectedLinks.toList().filter { DownloadLinkTools.isHttpUrl(it) }.take(120)
        if (candidates.isEmpty()) {
            toast("No direct downloadable files detected on this page yet")
            return
        }
        val selected = BooleanArray(candidates.size)
        AlertDialog.Builder(this)
            .setTitle("Detected links · choose files")
            .setMultiChoiceItems(candidates.map { DownloadLinkTools.fileNameFromUrl(it) }.toTypedArray(), selected) { _, which, checked -> selected[which] = checked }
            .setPositiveButton("Queue selected") { _, _ ->
                val chosen = candidates.filterIndexed { index, _ -> selected[index] }
                chosen.forEach { enqueue(it, DownloadLinkTools.fileNameFromUrl(it)) }
            }
            .setNeutralButton("Queue all") { _, _ -> candidates.forEach { enqueue(it, DownloadLinkTools.fileNameFromUrl(it)) } }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun addBookmark() {
        val tab = currentTab() ?: return
        val url = tab.web.url ?: return
        val saved = JSONArray(prefs.getString("bookmarks", "[]") ?: "[]")
        if ((0 until saved.length()).any { saved.optJSONObject(it)?.optString("url") == url }) {
            toast("Already bookmarked")
            return
        }
        saved.put(JSONObject().put("url", url).put("title", tab.web.title.orEmpty().ifBlank { url }))
        prefs.edit().putString("bookmarks", saved.toString()).apply()
        toast("Bookmark saved")
    }

    private fun saveHistory(url: String, title: String) {
        if (!DownloadLinkTools.isHttpUrl(url)) return
        val old = JSONArray(prefs.getString("browser_history", "[]") ?: "[]")
        val next = JSONArray()
        next.put(JSONObject().put("url", url).put("title", title.ifBlank { url }).put("at", System.currentTimeMillis()))
        for (i in 0 until old.length()) {
            val item = old.optJSONObject(i) ?: continue
            if (item.optString("url") != url && next.length() < 200) next.put(item)
        }
        prefs.edit().putString("browser_history", next.toString()).apply()
    }

    private fun showSavedItems(key: String, title: String) {
        val items = JSONArray(prefs.getString(key, "[]") ?: "[]")
        val saved = (0 until items.length()).mapNotNull { items.optJSONObject(it) }
            .map { it.optString("title", it.optString("url")) to it.optString("url") }
        if (saved.isEmpty()) { toast("No saved items"); return }
        AlertDialog.Builder(this).setTitle(title)
            .setItems(saved.map { it.first }.toTypedArray()) { _, which -> currentTab()?.web?.loadUrl(saved[which].second) }
            .setNegativeButton("Close", null).show()
    }

    private fun updateMediaButton() {
        if (::mediaButton.isInitialized) mediaButton.text = "Media " + detectedLinks.size
    }

    private fun normalizeAddress(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return "https://www.google.com"
        if (DownloadLinkTools.isHttpUrl(trimmed)) return trimmed
        if (trimmed.contains(' ') || !trimmed.contains('.')) {
            return "https://www.google.com/search?q=" + android.net.Uri.encode(trimmed)
        }
        return "https://" + trimmed
    }

    private fun isAdOrTracker(hostValue: String): Boolean {
        val host = hostValue.lowercase()
        val blocked = listOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.",
            "googletagmanager.com", "googletagservices.com", "adnxs.com", "adform.net",
            "scorecardresearch.com", "taboola.com", "outbrain.com", "tracking.", "analytics.",
            "pixel.", "branch.io", "appsflyer.com"
        )
        return blocked.any { host == it || host.endsWith("." + it) || host.contains(it) }
    }

    override fun onBackPressed() {
        val web = currentTab()?.web
        if (web?.canGoBack() == true) web.goBack()
        else if (tabs.size > 1) closeTab(activeIndex)
        else super.onBackPressed()
    }

    override fun onDestroy() {
        tabs.forEach { tab ->
            runCatching { tab.web.stopLoading(); tab.web.destroy() }
        }
        tabs.clear()
        super.onDestroy()
    }

    private fun makeButton(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 12f
            minHeight = dp(40)
            minimumHeight = dp(40)
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { action() }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()
}
