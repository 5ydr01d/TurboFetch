# TurboFetch

TurboFetch is an **advanced Android download manager**. It does not include a built-in web browser.

## Download features

- Custom HTTP/HTTPS transfer service with persistent queueing, pause, resume, cancel, retry, recovery after interruption, and file integrity checks.
- Parallel byte-range transfers for large files when the server provides compatible ranges and a stable ETag or Last-Modified validator. Unsupported or changing range responses fall back to the single-stream resume engine.
- Concurrent download limits, global speed limits, Wi-Fi-only downloads, HTTP proxy support, and date/time scheduling.
- Paste one link or a list of links; import from clipboard or text files; share a URL from another Android app into TurboFetch; export queued URLs.
- Optional User-Agent, Referer, Cookie and other supported request headers. Saved request headers are encrypted with Android Keystore-backed AES-GCM.
- yt-dlp-based video page inspection for supported hosts, with direct audio-bearing video formats and audio-only formats selectable for queueing. DRM-protected streams and separate video-only formats requiring muxing are not supported by this picker.
- Download history search and status filters; open/share completed files; remove history without deleting saved files.
- MD5 and SHA-256 checksum calculation, dark/light themes, and adjustable in-app text size.

## Architecture

See [the architecture guide](docs/ARCHITECTURE.md) for queue-storage invariants, transfer responsibilities, and release validation. The main app keeps the download queue, transfer engine, UI, and diagnostics in one download-focused codebase. No Feather browser submodule, browser activity, browser media-player service, or browser source-patching tasks are part of this build.

## Build and validation

The GitHub Actions workflow uses JDK 17 and Gradle 8.14.3. Pull requests run unit tests and build a debug APK. A green CI build does not substitute for physical-device validation: test large files, stale/expired links, HTTP 416 and changed validators, servers that stop honoring byte ranges, pause/cancel during writes, process death, reboot recovery, saved request headers, and Android 14/15+ foreground-service limits.

## Important limitations

- Resume depends on the server supporting byte ranges and providing a usable validator; some links must restart from zero.
- Parallel downloads can be slower or rejected by servers that limit connections. The engine should fall back safely, but host-specific behavior still needs testing.
- Video-page extraction depends on yt-dlp support and may return expiring signed URLs. Queue them promptly.
- This app does not bypass DRM and is not a torrent client or cloud-to-cloud transfer service.
