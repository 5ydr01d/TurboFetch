# TurboFetch architecture

This note records the active boundaries in the current app so new features do not reintroduce duplicate implementations or unsynchronized queue writes.

## Runtime boundaries

| Component | Responsibility |
| --- | --- |
| `MainActivity` | App entry point, screen navigation, Android intents, user-facing actions, and lifecycle integration. |
| `DownloadUiComponents` | Stateless Compose presentation components; queue state and side effects are supplied through parameters/callbacks. |
| `DownloadModels` / `DownloadRowLoader` | Immutable UI row model and reconciliation of persisted metadata with legacy Android DownloadManager records. |
| `DownloadQueueStore` | The single synchronized boundary for the JSON-backed `downloads` preference. Read-modify-write operations must be transactions, not separate unguarded reads and writes. |
| `DownloadQueueScheduler` | FIFO admission, concurrency accounting, queue mutation policy, and compatibility with legacy DownloadManager items. |
| `CustomDownloadService` | Foreground lifecycle, pause/resume/cancel, interrupted-job recovery, transfer orchestration, publication and notifications. |
| `ResumableHttpEngine` / `AdaptiveDownloadEngine` | HTTP validation/resume and optional segmented transfers with fallback to one stream. Neither owns UI state. |
| `DownloadHeaderVault` | Encrypts saved request headers with an Android Keystore-backed AES-GCM key; do not persist raw Cookie/Referer/User-Agent values. |
| `feather_browser` submodule + Gradle patch tasks | Browser implementation and narrowly scoped build-time integration changes. Patch tasks must fail loudly if their upstream anchors disappear. |

## Queue persistence rules

1. Do not read or write the `downloads` preference directly from app code. Use `DownloadQueueStore.snapshot`, `replace`, or `transaction`.
2. A transaction holds the same monitor used by the scheduler and foreground service. Do not launch asynchronous work from a transaction callback.
3. A snapshot is a detached `JSONArray`; mutating it does not affect persisted state until a transaction or replace operation commits it.
4. Store a stable queue ID in internal transfer filenames so same-named downloads never share partial or segmented scratch data.
5. Keep request headers out of logs, exported queue data, long-lived launch intents, and unencrypted preferences. Remove credentials when they are no longer required.

## Validation

The unit suite should cover pure queue policy, cancellation/retry state, URL and filename handling, encrypted-header integrity, and concurrent queue updates. CI must run `testDebugUnitTest` and assemble all configured ABI APKs.

A green build does not replace device testing. Before a release, test large files, HTTP 416/changed validators, a server that stops honoring ranges, pause/cancel during a write, process death, reboot recovery, saved cookies, and private-tab isolation on a physical Android device.
