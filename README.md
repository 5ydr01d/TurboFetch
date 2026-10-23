# TurboFetch

TurboFetch is an ad-free, privacy-first Android download manager built with Kotlin and Jetpack Compose.

## Current development build

- Redesigned Material 3 interface with Home, Downloads, Files, and Settings destinations.
- Light and dark themes plus adjustable in-app text size.
- Add direct HTTP/HTTPS download URLs.
- Persistent FIFO intake queue with a configurable limit of 1–5 simultaneous app-managed downloads.
- Queue entries have a visible waiting state and can be cancelled before they start.
- Retries are returned to the app-managed queue instead of bypassing its concurrency limit.
- A DownloadManager completion receiver advances queued items without requiring the main screen to be visible.
- Download cards show status, progress, transferred bytes, and file-type labels.
- Search download history and filter by active, completed, or problematic downloads.
- Completed downloads offer Open and Share actions.
- Dismissal removes the in-app history entry; it does not intentionally delete the downloaded file.
- Filename sanitization and collision avoidance against tracked history.
- Download history metadata and UI preferences are stored locally.
- No advertising SDKs or analytics SDKs are included.
- CI runs unit tests and assembles a debug APK on pushes and pull requests to \`main\`.

Downloads are saved to the public Downloads directory. The current implementation supports direct HTTP/HTTPS file URLs and relies on Android's DownloadManager for the actual transfer. The app-managed concurrency limit controls how many entries TurboFetch submits to DownloadManager at once; lowering the limit does not interrupt transfers already running. Android remains responsible for retries and transfer behavior. Explicit pause/resume, segmented/multi-connection downloads, a built-in browser, torrent support, and media extraction are not implemented yet. As with any Android broadcast-based background behavior, the completion-triggered queue advance still needs real-device validation across Android versions and OEM battery-management settings.

## Build and test

The GitHub Actions workflow uses JDK 17 and Gradle 8.9:

\`\`\`sh
gradle --no-daemon testDebugUnitTest
gradle --no-daemon assembleDebug
\`\`\`

After a successful build, the workflow uploads \`TurboFetch-debug-apk\` as an artifact.

## Roadmap

1. Add power-user queue controls and automated queue-policy tests.
2. Evaluate a custom HTTP engine for genuine pause/resume, robust retry, safe HTTP Range resume, and optional multi-connection transfers.
3. Improve file browsing, destination selection, and filename/MIME detection.
4. Evaluate browser integration and link capture after core transfer behavior is dependable.
5. Evaluate torrent support separately.

## Privacy and limitations

TurboFetch stores its queue metadata and appearance preferences locally. No account, ad network, or analytics integration is configured. This remains an early development build and has not been security-audited or validated on a physical device as part of every change.
