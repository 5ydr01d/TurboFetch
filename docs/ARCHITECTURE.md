# TurboFetch architecture

TurboFetch is a download-only Android app. Do not add a built-in browser or browser navigation to this project.

## Runtime boundaries

| Component | Responsibility |
| --- | --- |
| `MainActivity` | Download queue, history, settings, bulk URL intake, file actions, and the video-page quality picker. |
| `DownloadUiComponents` / `DownloadModels` / `DownloadRowLoader` | Stateless queue presentation, immutable row models, and reconciliation of persisted records with legacy Android DownloadManager records. |
| `DownloadQueueStore` | The single synchronized boundary for the JSON-backed `downloads` preference. Read-modify-write operations must be transactions, not separate unguarded reads and writes. |
| `DownloadQueueScheduler` | FIFO admission, concurrency accounting, queue mutation policy, and compatibility with legacy Android DownloadManager items. |
| `CustomDownloadService` | Foreground lifecycle, pause/resume/cancel, interrupted-job recovery, transfer orchestration, publication, and notifications. |
| `ResumableHttpEngine` / `AdaptiveDownloadEngine` | HTTP validation/resume and optional segmented transfers with fallback to one stream. Neither owns UI state. |
| `DownloadHeaderVault` | Encrypts saved request headers with an Android Keystore-backed AES-GCM key; do not persist raw Cookie/Referer/User-Agent values. |
| `VideoSiteExtractor` | Inspects supported video pages and returns only direct queueable formats. It is a downloader tool, not a browser and must not load general websites in an embedded WebView. |

## Queue persistence rules

1. Do not read or write the `downloads` preference directly from app code. Use `DownloadQueueStore.snapshot`, `replace`, or `transaction`.
2. A transaction holds the same monitor used by the scheduler and foreground service. Do not launch asynchronous work from a transaction callback.
3. A snapshot is a detached `JSONArray`; mutating it does not affect persisted state until a transaction or replace operation commits it.
4. Store a stable queue ID in internal transfer filenames so same-named downloads never share partial or segmented scratch data.
5. Keep credentials out of logs, exported queue data, long-lived launch intents, and unencrypted preferences. Remove credentials when they are no longer required.

## Validation

The unit suite should cover pure queue policy, cancellation/retry state, URL and filename handling, encrypted-header integrity, concurrent queue updates, and segmented transfer fallback. Before a release, test large files, HTTP 416/changed validators, a server that stops honoring ranges, pause/cancel during a write, process death, reboot recovery, saved cookies, and foreground-service behavior on a physical Android device.

A green build does not prove physical-device behavior or guarantee all sites and servers work.
