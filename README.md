# TurboFetch

TurboFetch is an ad-free, privacy-first Android download manager built with Kotlin and Jetpack Compose.

## Current development build

- Redesigned Material 3 interface with Home, Downloads, Files, and Settings destinations.
- Light/dark themes and adjustable in-app text size.
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

**Important limitations:** resumability depends on server support and validators; some URLs will have to restart from zero. Parallel ranges are opt-in-by-capability and only used for large files on compatible servers; segmented pause/recovery and server-change edge cases still require focused validation. The custom foreground service, background recovery, and OEM battery-management behavior still need physical-device testing. Android places execution limits on long-running data-sync foreground services, so very long transfers need specific validation on Android 14/15+.


## Build and test

The GitHub Actions workflow uses JDK 17 and Gradle 8.9:

```sh
gradle --no-daemon testDebugUnitTest
gradle --no-daemon assembleDebug
```

After a successful main-branch build, the workflow uploads `TurboFetch-debug-apk` as an artifact. Pull-request builds run validation but do not publish an APK.

## Roadmap

1. Verify pause/resume, process death, reboot recovery, duplicate filenames, and large files on physical devices.
2. Expand destination controls, safer filename/MIME detection, and diagnostics with HTTP response details.
3. Validate segmented resume, server changes during transfer, and crash recovery on physical devices.
4. Add browser integration and link capture after core transfer behavior is dependable.
5. Evaluate torrent support separately.

## Privacy and limitations

TurboFetch stores its queue metadata and appearance preferences locally. No account, ad network, or analytics integration is configured. This remains an early development build and has not been security-audited or validated on a physical device as part of every change.
