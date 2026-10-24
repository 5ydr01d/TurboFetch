# TurboFetch

TurboFetch is an ad-free, privacy-first Android download manager built with Kotlin and Jetpack Compose.

## Current development build

- Redesigned Material 3 interface with Home, Downloads, Files, and Settings destinations.
- Light/dark themes and adjustable in-app text size.
- Built-in multi-tab WebView browser with ad/tracker filtering, private tabs, bookmarks, local history, exposed media/file-link capture, and a page-link grabber.
- Batch queueing from pasted text, clipboard or TXT files; URL-list export; optional request headers for compatible hosts, encrypted at rest with Android Keystore.
- Download scheduler with reboot restoration, Wi-Fi-only gating, global speed cap, HTTP proxy, configurable parallel-part count, and 1–30 concurrent downloads.
- MD5 and SHA-256 file checksum tool, pause/resume all, retry failed jobs, and clear-completed-history without deleting saved files.
- Persistent FIFO queue with configurable concurrency from 1–5.
- New downloads use TurboFetch's custom HTTP engine and foreground service rather than Android DownloadManager.
- Safe partial-file resume using HTTP Range and If-Range when the server provides a usable ETag or Last-Modified validator.
- Pause, resume, cancel, retry, queue recovery, and persisted transfer progress.
- Compact download cards show status, percentage, transferred/total bytes, live speed, ETA when calculable, and file-type labels.
- Completed files are published to the public Downloads/TurboFetch folder and offer Open/Share actions.
- Dismissing history does not intentionally delete the downloaded file.
- Legacy DownloadManager history remains readable; queued-but-not-started entries are migrated when the custom service starts.
- Search and filter download history; no advertising or analytics SDKs.
- CI runs unit tests and assembles a debug APK on pushes and pull requests to `main`. Main-branch APKs use the persistent signing key configured through GitHub Actions secrets; see [the signing setup guide](docs/CI_DEBUG_SIGNING.md).

**Important limitations:** resumability depends on server support and validators; some URLs will have to restart from zero. File-size detection depends on server support for HEAD/Content-Length. Embedded stream detection can discover exposed HLS/DASH manifests and direct media URLs, but cannot bypass DRM or guarantee access to signed URLs or browser-only cookies. Parallel ranges are opt-in-by-capability and only used for large files on compatible servers; segmented pause/recovery and server-change edge cases still require focused validation. The custom foreground service, background recovery, and OEM battery-management behavior still need physical-device testing. Android places execution limits on long-running data-sync foreground services, so very long transfers need specific validation on Android 14/15+.


## Build and test

The GitHub Actions workflow uses JDK 17 and Gradle 8.14.3:

```sh
gradle --no-daemon testDebugUnitTest
gradle --no-daemon assembleDebug
```

After a successful build, GitHub Actions publishes separate ABI APK artifacts (`TurboFetch-arm64-v8a-*`, `TurboFetch-armeabi-v7a-*`, `TurboFetch-x86_64-*`, and `TurboFetch-x86-*`) for both pull requests and `main`. Pull-request APKs use GitHub's temporary debug signing key; main-branch APKs use the persistent signing key. Choose the arm64 artifact for most modern Android phones. Artifacts are retained for 14 days.

## Roadmap

1. Verify pause/resume, process death, reboot recovery, duplicate filenames, and large files on physical devices. Duplicate in-progress transfer names are now isolated internally by queue-item ID.
2. Expand destination controls, safer filename/MIME detection, and diagnostics with HTTP response details.
3. Validate segmented resume, server changes during transfer, and crash recovery on physical devices.
4. Validate browser compatibility, media capture, scheduler delivery and proxy behavior on physical Android devices.
5. Evaluate advanced streaming playlist support separately. Torrent downloads remain out of scope.

## Privacy and limitations

TurboFetch stores queue metadata and appearance preferences locally. Per-download request headers and the optional Advanced Tools User-Agent, Referer, and Cookie overrides are encrypted at rest with AES-GCM using a non-exportable Android Keystore key. Encrypted queue headers are authenticated against each download ID and source URL. Older plaintext header preferences and queue rows are migrated when possible; if the device Keystore cannot decrypt saved credentials (for example after restoring app data to another device), affected downloads must be re-added and global headers re-entered. No account, ad network, or analytics integration is configured. This remains an early development build. CI verifies compilation and unit tests, but cannot replace physical-device testing across Android versions and OEM background-process policies.
