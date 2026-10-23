# TurboFetch

TurboFetch is an ad-free, privacy-first Android download manager built with Kotlin and Jetpack Compose.

## Current development build

- Redesigned Material 3 interface with Home, Downloads, Files, and Settings destinations.
- Light and dark themes plus adjustable in-app text size.
- Add direct HTTP/HTTPS download URLs.
- Queue downloads through Android's system DownloadManager.
- Download cards show status, progress, transferred bytes, and file-type labels.
- Search download history and filter by active, completed, or problematic downloads.
- Completed downloads offer Open and Share actions.
- Dismissal removes the in-app history entry; it does not intentionally delete the downloaded file.
- Filename sanitization and collision avoidance against tracked history.
- Download history metadata and UI preferences are stored locally.
- No advertising SDKs or analytics SDKs are included.
- CI runs unit tests and assembles a debug APK on pushes and pull requests to \`main\`.

Downloads are saved to the public Downloads directory. The current implementation supports direct HTTP/HTTPS file URLs and relies on Android's DownloadManager for transfer and background behavior. It does not yet include a built-in browser, segmented/multi-connection engine, explicit pause/resume, torrent support, or media extraction.

## Build and test

The GitHub Actions workflow uses JDK 17 and Gradle 8.9:

\`\`\`sh
gradle --no-daemon testDebugUnitTest
gradle --no-daemon assembleDebug
\`\`\`

After a successful build, the workflow uploads \`TurboFetch-debug-apk\` as an artifact.

## Roadmap

1. Improve download history, UI usability, and basic automated tests.
2. Add a custom download engine with explicit pause/resume, robust retry, and HTTP Range support when the server supports it.
3. Add stronger file browsing, destination selection, and richer queue controls.
4. Evaluate optional browser integration and link capture after core reliability improves.
5. Evaluate torrent support separately.

## Privacy and limitations

TurboFetch stores its queue metadata and appearance preferences locally. No account, ad network, or analytics integration is configured. This remains an early development build and has not been security-audited or validated on a physical device as part of every change.
