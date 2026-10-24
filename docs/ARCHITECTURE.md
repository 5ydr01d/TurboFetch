# TurboFetch architecture

TurboFetch is a download-only Android app. Do not add a built-in browser, browser tabs, an embedded WebView, or general-purpose browser navigation to this project.

## Runtime boundaries

| Component | Responsibility |
| --- | --- |
| `MainActivity` | Download queue, history, settings, bulk URL intake, file actions, checksums, scheduler controls, request headers and the video-page quality picker. |
| `DownloadUiComponents` / `DownloadModels` / `DownloadRowLoader` | Stateless queue presentation, immutable row models, and reconciliation of persisted records with legacy Android DownloadManager records. |
| `DownloadQueueStore` | The single synchronized boundary for the JSON-backed `downloads` preference. Read-modify-write operations must be transactions, not separate unguarded reads and writes. |
| `DownloadQueueScheduler` | FIFO admission, concurrency accounting, queue mutation policy, and compatibility with legacy Android DownloadManager items. |
| `CustomDownloadService` | Foreground lifecycle, pause/resume/cancel, interrupted-job recovery, transient retry/backoff, transfer orchestration, publication and notifications. |
| `ResumableHttpEngine` / `AdaptiveDownloadEngine` | Validator-aware HTTP resume, segmented byte ranges, and dispatcher for HLS/DASH manifest downloads. |
| `HlsDownloadEngine` | Finite HLS VOD: master/variant selection, validated segment ranges, retries, AES-128, segment checkpoints and assembly. |
| `DashDownloadEngine` | Static DASH VOD parsing and transfer: BaseURL, SegmentTemplate/SegmentTimeline, SegmentList and separate clear audio/video tracks. Live/dynamic MPDs, SegmentBase/SIDX and multi-period stitching are intentionally out of scope. |
| `MediaTrackMuxer` | Muxes compatible clear audio/video files into MP4 using Android media APIs. It does not decrypt or bypass DRM. |
| `DownloadHeaderVault` | Encrypts saved request headers with an Android Keystore-backed AES-GCM key; do not persist raw Cookie/Referer/User-Agent values. |
| `VideoSiteExtractor` | Inspects supported video pages and returns direct queueable formats. This is a downloader tool, not a browser; it must not load arbitrary web pages in an embedded WebView. |

## Queue persistence rules

1. Do not read or write the `downloads` preference directly from app code. Use `DownloadQueueStore.snapshot`, `replace`, or `transaction`.
2. A transaction holds the same monitor used by the scheduler and foreground service. Do not launch asynchronous work from a transaction callback.
3. A snapshot is a detached `JSONArray`; mutating it does not affect persisted state until a transaction or replace operation commits it.
4. Store a stable queue ID in internal transfer filenames so same-named downloads never share partial or segmented scratch data.
5. Keep credentials out of logs, exported queue data, long-lived launch intents and unencrypted preferences. Remove credentials when they are no longer required.
6. Temporary segment files and checkpoints must remain private until assembly is complete and the output is validated; cancellation and queue removal must clean up engine-owned scratch data without racing active writers.
7. Do not trust manifest URLs, segment paths, XML attributes, or server range responses blindly. Resolve relative URLs against the final manifest URL, cap manifest/segment counts, validate range boundaries, and disable XML external entities.

## Validation

The unit suite should cover queue policy, cancellation/retry state, URL and filename handling, encrypted-header integrity, concurrent queue updates, range fallback, HLS master/segment assembly, DASH template expansion, and protected/live manifest rejection. Before a release, test large files, HTTP 416 and changed validators, servers that stop honoring ranges, pause/cancel during a write, process death, reboot recovery, saved cookies, HLS/DASH variants and byte ranges, muxed audio/video playback, and foreground-service behavior on a physical Android device.

A green build does not prove physical-device behavior or guarantee every host, codec or manifest is supported.
