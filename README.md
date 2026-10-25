# TurboFetch

TurboFetch is an **advanced Android download manager**. It does not include a built-in web browser.

## Download features

- Custom HTTP/HTTPS transfer service with persistent queueing, pause, resume, cancel, recovery after interruption, notifications, and actionable error diagnostics.
- Configurable automatic retries with exponential backoff for temporary network failures and retryable HTTP responses. Certificate failures and permanent errors are not retried.
- Parallel byte-range transfers for large files when the server provides compatible ranges and a stable ETag or Last-Modified validator. Unsupported or changing range responses fall back to the single-stream resume engine.
- Static HLS VOD downloads (M3U8) with master-playlist selection, segment retries, segment checkpoints for resume, byte-range validation, fMP4 initialization segments, and HLS AES-128 decryption.
- Static MPEG-DASH VOD downloads (MPD) using SegmentTemplate/SegmentTimeline, SegmentList, and direct BaseURL media files. Separate clear audio/video tracks can be muxed into MP4 with Android's media APIs when the container/codecs are compatible.
- yt-dlp-based video-page inspection for supported hosts, with direct audio-bearing video formats and audio-only formats selectable for queueing.
- Concurrent download limits, configurable connections/parts, global speed limits, Wi-Fi-only downloads, HTTP proxy support, and date/time scheduling.
- Paste one link or a list of links; import from clipboard or text files; share a URL from another Android app into TurboFetch; export queued URLs.
- Optional User-Agent, Referer, Cookie and other supported request headers. Saved request headers are encrypted with Android Keystore-backed AES-GCM.
- Download history search and status filters; open/share completed files; remove history without deleting saved files.
- MD5 and SHA-256 checksum calculation, light/dark themes, and adjustable in-app text size.

## Architecture

See [the architecture guide](docs/ARCHITECTURE.md) for queue-storage invariants, transfer responsibilities, and release validation. The app keeps the download queue, transfer engines, media processing, settings, diagnostics and UI in one download-focused codebase. There is no Feather submodule, browser activity, embedded WebView, browser media player, or browser source-patching task.

## Build and validation

The GitHub Actions workflow uses JDK 17 and Gradle 8.14.3. Pull requests run unit tests and build a debug APK. A green CI build does not substitute for physical-device validation: test large files, stale/expired links, HTTP 416 and changed validators, servers that stop honoring byte ranges, HLS/DASH variants and manifests, audio/video mux output, pause/cancel during writes, process death, reboot recovery, saved request headers, and Android foreground-service limits.

## Important limitations

- Resume depends on server support and usable validators; some links must restart from zero.
- Parallel downloads can be slower or rejected by servers that limit connections. Host-specific behavior still needs testing.
- HLS support is for finite VOD playlists. Live HLS and masters requiring separate audio renditions are not currently supported. HLS AES-128 is supported; DRM and SAMPLE-AES are not bypassed.
- DASH support is for static VOD manifests with supported SegmentTemplate/SegmentTimeline, SegmentList, or direct BaseURL resources. Dynamic/live MPDs, SegmentBase/SIDX parsing, multi-period stitching and DRM ContentProtection are not supported.
- Separate DASH audio/video muxing depends on Android's media codecs and compatible MP4 track formats. A successful byte transfer does not guarantee every downloaded file is playable.
- Video-page extraction depends on yt-dlp support and may return expiring signed URLs. Queue them promptly.
- This app does not bypass DRM and is not a torrent client or cloud-to-cloud transfer service.
