# TurboFetch

TurboFetch is an ad-free, privacy-first Android download manager. The project is being built as an independent alternative inspired by common download-manager workflows.

## Current MVP

- Native Android app built with Kotlin and Jetpack Compose.
- Add direct HTTP/HTTPS download URLs.
- Queue downloads through Android's system DownloadManager, which can continue work in the background.
- View queue status, downloaded bytes, progress percentage, and failure/pause reason codes.
- Cancel active downloads, dismiss tracked entries, and retry failed downloads.
- No advertising SDKs or analytics SDKs are included.
- GitHub Actions builds a debug APK on pushes and pull requests to `main`.

Downloads are stored in the app-specific external Downloads directory. This first version handles **direct file URLs**; it is not yet a browser-integrated downloader and does not yet implement segmented/multi-connection downloads, a built-in browser, media sniffing, or torrents. Pause/resume controls are not exposed in this MVP; Android's DownloadManager owns transfer and retry behavior.

## Build

The GitHub Actions workflow uses JDK 17 and Gradle 8.9:

```sh
gradle --no-daemon assembleDebug
```

The APK is uploaded as the `TurboFetch-debug-apk` workflow artifact after a successful build.

## Roadmap

1. Establish a reliable, tested direct-download MVP.
2. Add a custom download engine with explicit pause/resume, robust retry, and HTTP Range support when the server supports it.
3. Improve file naming, duplicate handling, queue controls, and download history.
4. Explore optional browser integration and media detection.
5. Evaluate torrent support separately.

## Privacy and scope

TurboFetch currently relies on Android's built-in download service and stores its queue metadata locally in app preferences. No account, ad network, or analytics integration is configured. This is an early development build, not a release-ready or security-audited app.
