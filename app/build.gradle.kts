plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val ciDebugKeystorePath = providers.environmentVariable("TURBOFETCH_DEBUG_KEYSTORE_PATH").orNull
val ciDebugStorePassword = providers.environmentVariable("TURBOFETCH_DEBUG_STORE_PASSWORD").orNull
val ciDebugKeyAlias = providers.environmentVariable("TURBOFETCH_DEBUG_KEY_ALIAS").orNull
val ciDebugKeyPassword = providers.environmentVariable("TURBOFETCH_DEBUG_KEY_PASSWORD").orNull

android {
    namespace = "com.example"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fiveydr.turbofetch"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.4.0"
    }

    signingConfigs {
        getByName("debug") {
            if (!ciDebugKeystorePath.isNullOrBlank()) {
                storeFile = file(ciDebugKeystorePath)
                storePassword = requireNotNull(ciDebugStorePassword)
                keyAlias = requireNotNull(ciDebugKeyAlias)
                keyPassword = requireNotNull(ciDebugKeyPassword)
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    sourceSets.getByName("main").java.srcDir(layout.buildDirectory.dir("generated/feather/java"))
    sourceSets.getByName("main").res.srcDir(layout.buildDirectory.dir("generated/feather/res"))
}

val syncFeatherBrowser = tasks.register<Sync>("syncFeatherBrowser") {
    from(rootProject.file("feather_browser/app/src/main/java")) { into("java") }
    from(rootProject.file("feather_browser/app/src/main/res")) { into("res") }
    into(layout.buildDirectory.dir("generated/feather"))
}

val patchFeatherPopupBlocker = tasks.register("patchFeatherPopupBlocker") {
    dependsOn(syncFeatherBrowser)
    doLast {
        val settingsFile = layout.buildDirectory.file("generated/feather/java/com/example/ui/components/SettingsDialog.kt").get().asFile
        check(settingsFile.exists()) { "Feather SettingsDialog.kt was not copied" }
        var settingsSource = settingsFile.readText()
        val contextAnchor = "    val context = LocalContext.current"
        if (!settingsSource.contains("var popupBlockerEnabled by remember")) {
            check(settingsSource.contains(contextAnchor)) { "Could not find SettingsDialog context anchor" }
            settingsSource = settingsSource.replace(
                contextAnchor,
                contextAnchor + "\n\n    var popupBlockerEnabled by remember { mutableStateOf(context.getSharedPreferences(\"feather_browser_preferences\", 0).getBoolean(\"pref_popup_blocker_enabled\", true)) }"
            )
        }
        val privacyAnchor = "                    // 3rd-Party Cookies"
        if (!settingsSource.contains("Pop-up Blocker")) {
            check(settingsSource.contains(privacyAnchor)) { "Could not find Privacy & Security insertion point" }
            val popupSection = listOf(
                "                    // Pop-up blocker",
                "                    HorizontalDivider(",
                "                        modifier = Modifier.padding(vertical = 12.dp),",
                "                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)",
                "                    )",
                "                    Row(",
                "                        verticalAlignment = Alignment.CenterVertically,",
                "                        modifier = Modifier.fillMaxWidth()",
                "                    ) {",
                "                        Column(modifier = Modifier.weight(1f)) {",
                "                            Text(",
                "                                text = \"Pop-up Blocker\",",
                "                                fontSize = 14.sp,",
                "                                fontWeight = FontWeight.Medium,",
                "                                color = MaterialTheme.colorScheme.onSurface",
                "                            )",
                "                            Text(",
                "                                text = \"Blocks new windows opened by websites, including advertising pop-ups\",",
                "                                fontSize = 11.5.sp,",
                "                                color = MaterialTheme.colorScheme.onSurfaceVariant",
                "                            )",
                "                        }",
                "                        Switch(",
                "                            checked = popupBlockerEnabled,",
                "                            onCheckedChange = { enabled ->",
                "                                popupBlockerEnabled = enabled",
                "                                context.getSharedPreferences(\"feather_browser_preferences\", 0)",
                "                                    .edit().putBoolean(\"pref_popup_blocker_enabled\", enabled).apply()",
                "                            }",
                "                        )",
                "                    }",
                "",
                "                    HorizontalDivider(",
                "                        modifier = Modifier.padding(vertical = 12.dp),",
                "                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)",
                "                    )",
                ""
            ).joinToString("\n")
            settingsSource = settingsSource.replace(privacyAnchor, popupSection + privacyAnchor)
        }
        settingsFile.writeText(settingsSource)

        // Put the Universal Artifact Downloader entry point directly in Feather's address bar.
        val addressBarFile = layout.buildDirectory.file("generated/feather/java/com/example/ui/components/AddressBar.kt").get().asFile
        check(addressBarFile.exists()) { "Feather AddressBar.kt was not copied" }
        var addressBarSource = addressBarFile.readText()
        val addressSignature = """    onGoHome: (() -> Unit)? = null,
    modifier: Modifier = Modifier
)"""
        if (!addressBarSource.contains("onOpenUniversalArtifactFinder")) {
            check(addressBarSource.contains(addressSignature)) { "Could not find Feather AddressBar signature insertion point" }
            addressBarSource = addressBarSource.replace(
                addressSignature,
                """    onGoHome: (() -> Unit)? = null,
    onOpenUniversalArtifactFinder: (() -> Unit)? = null,
    modifier: Modifier = Modifier
)"""
            )
        }
        val addressActionAnchor = "                    // Quick Reload or Bookmark on Top Bar"
        if (!addressBarSource.contains("Universal Artifact Downloader")) {
            check(addressBarSource.contains(addressActionAnchor)) { "Could not find Feather address-bar action insertion point" }
            val finderButton = listOf(
                "                    // Universal Artifact Downloader stays inside Feather Browser.",
                "                    if (hasValidUrl && onOpenUniversalArtifactFinder != null) {",
                "                        IconButton(",
                "                            onClick = onOpenUniversalArtifactFinder,",
                "                            modifier = Modifier.size(36.dp).focusProperties { canFocus = false }.testTag(\"universal_artifact_finder_button\")",
                "                        ) {",
                "                            Icon(",
                "                                imageVector = Icons.Default.Download,",
                "                                contentDescription = \"Universal Artifact Downloader\",",
                "                                tint = MaterialTheme.colorScheme.primary,",
                "                                modifier = Modifier.size(20.dp)",
                "                            )",
                "                        }",
                "                    }",
                ""
            ).joinToString("\n")
            addressBarSource = addressBarSource.replace(addressActionAnchor, finderButton + addressActionAnchor)
        }
        addressBarFile.writeText(addressBarSource)

        val browserScreenFile = layout.buildDirectory.file("generated/feather/java/com/example/ui/BrowserScreen.kt").get().asFile
        check(browserScreenFile.exists()) { "Feather BrowserScreen.kt was not copied" }
        var browserScreenSource = browserScreenFile.readText()
        val addressStateAnchor = "    var isAddressBarEditing by remember { mutableStateOf(false) }"
        if (!browserScreenSource.contains("showUniversalArtifactFinder")) {
            check(browserScreenSource.contains(addressStateAnchor)) { "Could not find Feather BrowserScreen state insertion point" }
            browserScreenSource = browserScreenSource.replace(
                addressStateAnchor,
                addressStateAnchor + "\n    var showUniversalArtifactFinder by remember { mutableStateOf(false) }"
            )
        }
        val addressCallbackAnchor = "                                        onGoHome = { dismissAddressBar(); viewModel.goHome() }"
        if (!browserScreenSource.contains("onOpenUniversalArtifactFinder =")) {
            check(browserScreenSource.contains(addressCallbackAnchor)) { "Could not find Feather AddressBar callback insertion point" }
            browserScreenSource = browserScreenSource.replace(
                addressCallbackAnchor,
                addressCallbackAnchor + ",\n                                        onOpenUniversalArtifactFinder = { dismissAddressBar(); showUniversalArtifactFinder = true }"
            )
        }
        val browserBoxAnchor = "    Box(modifier = modifier.fillMaxSize()) {"
        if (!browserScreenSource.contains("UniversalArtifactFinderDialog(")) {
            check(browserScreenSource.contains(browserBoxAnchor)) { "Could not find Feather BrowserScreen overlay insertion point" }
            val finderDialog = listOf(
                "    Box(modifier = modifier.fillMaxSize()) {",
                "        if (showUniversalArtifactFinder) {",
                "            UniversalArtifactFinderDialog(",
                "                pageUrl = currentUrl,",
                "                onDismiss = { showUniversalArtifactFinder = false },",
                "                onDownload = { url, userAgent, referer, cookie ->",
                "                    val handoff = android.content.Intent(context, com.fiveydr.turbofetch.MainActivity::class.java).apply {",
                "                        action = \"com.fiveydr.turbofetch.action.BROWSER_DOWNLOAD\"",
                "                        putExtra(\"com.fiveydr.turbofetch.extra.DOWNLOAD_URL\", url)",
                "                        putExtra(\"com.fiveydr.turbofetch.extra.USER_AGENT\", userAgent.ifBlank { android.webkit.WebSettings.getDefaultUserAgent(context) })",
                "                        putExtra(\"com.fiveydr.turbofetch.extra.REFERER\", referer)",
                "                        putExtra(\"com.fiveydr.turbofetch.extra.COOKIE\", cookie)",
                "                    }",
                "                    showUniversalArtifactFinder = false",
                "                    context.startActivity(handoff)",
                "                }",
                "            )",
                "        }"
            ).joinToString("\n")
            browserScreenSource = browserScreenSource.replace(browserBoxAnchor, finderDialog)
        }
        browserScreenFile.writeText(browserScreenSource)

        val webViewFile = layout.buildDirectory.file("generated/feather/java/com/example/ui/components/WebViewContainer.kt").get().asFile
        check(webViewFile.exists()) { "Feather WebViewContainer.kt was not copied" }
        var webViewSource = webViewFile.readText()
        val popupAnchor = "                                // User-initiated target=_blank links should become real"
        if (!webViewSource.contains("val popupBlockerEnabled = ctx.getSharedPreferences")) {
            check(webViewSource.contains(popupAnchor)) { "Could not find WebView popup handler insertion point" }
            val popupCheck = listOf(
                "                                val popupBlockerEnabled = ctx.getSharedPreferences(\"feather_browser_preferences\", Context.MODE_PRIVATE)",
                "                                    .getBoolean(\"pref_popup_blocker_enabled\", true)",
                "                                if (popupBlockerEnabled) return false",
                popupAnchor
            ).joinToString("\n")
            webViewSource = webViewSource.replace(popupAnchor, popupCheck)
        }
        // Automatically scan every real Feather Browser page and persist the latest page's assets for TurboFetch Finder.
        val pageFinishedAnchor = "                                view?.title?.let {\n                                    viewModel.onTitleChanged(tabId, it)\n                                }"
        if (!webViewSource.contains("turbofetch_artifact_scan_json")) {
            check(webViewSource.contains(pageFinishedAnchor)) { "Could not find Feather onPageFinished insertion point for automatic artifact scan" }
            val autoScan = listOf(
                "                                // TurboFetch: automatically collect exposed assets after every browser navigation.",
                "                                if (url != null && (url.startsWith(\"https://\") || url.startsWith(\"http://\"))) {",
                "                                    val scanScript = com.fiveydr.turbofetch.MediaArtifactTools.PAGE_SCAN_SCRIPT",
                "                                    view?.evaluateJavascript(scanScript) { rawResult ->",
                "                                        runCatching {",
                "                                            val decoded = org.json.JSONTokener(rawResult ?: \"null\").nextValue() as? String ?: return@runCatching",
                "                                            val parsed = org.json.JSONObject(decoded)",
                "                                            val prefs = view?.context?.getSharedPreferences(\"turbofetch_browser_artifacts\", android.content.Context.MODE_PRIVATE) ?: return@runCatching",
                "                                            prefs.edit()",
                "                                                .putString(\"turbofetch_artifact_scan_url\", parsed.optString(\"page\", url))",
                "                                                .putString(\"turbofetch_artifact_scan_json\", decoded)",
                "                                                .putLong(\"turbofetch_artifact_scan_time\", System.currentTimeMillis())",
                "                                                .apply()",
                "                                        }",
                "                                    }",
                "                                }"
            ).joinToString("\n")
            webViewSource = webViewSource.replace(pageFinishedAnchor, pageFinishedAnchor + "\n" + autoScan)
        }
        val downloadListenerAnchor = "                            viewModel.handleDownloadRequest(url, userAgent, contentDisposition, mimetype, contentLength)"
        if (!webViewSource.contains("com.fiveydr.turbofetch.action.BROWSER_DOWNLOAD")) {
            check(webViewSource.contains(downloadListenerAnchor)) { "Could not find Feather download listener insertion point" }
            val downloadHandoff = listOf(
                "                            // Hand browser downloads directly to TurboFetch.",
                "                            val handoff = Intent(context, com.fiveydr.turbofetch.MainActivity::class.java).apply {",
                "                                action = \"com.fiveydr.turbofetch.action.BROWSER_DOWNLOAD\"",
                "                                putExtra(\"com.fiveydr.turbofetch.extra.DOWNLOAD_URL\", url)",
                "                                putExtra(\"com.fiveydr.turbofetch.extra.USER_AGENT\", userAgent.orEmpty())",
                "                                putExtra(\"com.fiveydr.turbofetch.extra.CONTENT_DISPOSITION\", contentDisposition.orEmpty())",
                "                                putExtra(\"com.fiveydr.turbofetch.extra.MIME_TYPE\", mimetype.orEmpty())",
                "                                putExtra(\"com.fiveydr.turbofetch.extra.REFERER\", \"\")",
                "                                putExtra(\"com.fiveydr.turbofetch.extra.COOKIE\", CookieManager.getInstance().getCookie(url).orEmpty())",
                "                            }",
                "                            handoff.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)",
                "                            context.startActivity(handoff)"
            ).joinToString("\n")
            webViewSource = webViewSource.replace(downloadListenerAnchor, downloadHandoff)
        }
        webViewFile.writeText(webViewSource)
    }
}

tasks.named("preBuild").configure { dependsOn(patchFeatherPopupBlocker) }

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.media:media:1.7.0")
    // yt-dlp-backed extractor for supported video hosting sites.
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    ksp("androidx.room:room-compiler:2.7.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
}
