# Download engine evaluation

## Decision for this milestone

Keep Android `DownloadManager` as the transfer backend while adding an app-managed FIFO queue and concurrency cap. Do not advertise pause/resume or segmented downloading yet. This keeps current background transfer, connectivity retry, and reboot behavior while making the queue policy independent and testable.

## Current backend: Android DownloadManager

**Good fit for:** direct HTTP/HTTPS downloads, platform-managed background work, notifications, connectivity changes, and basic retries.

**Limitations:** TurboFetch cannot reliably expose a true pause/resume button, independently manage byte ranges, limit HTTP connection count inside a single file, or control the detailed retry policy. A TurboFetch-level queue can cap how many items it submits, but it cannot interrupt transfers already submitted when the setting is lowered.

## Custom HTTP backend: candidate for the next milestone

A custom engine should sit behind a small backend interface rather than replacing the UI and local history at the same time.

Required proof-of-concept order:

1. **Single-stream transfer:** explicit states, cancellation, speed reporting, durable progress, temporary files, and atomic completion.
2. **Safe resume:** persist URL, destination, byte count, ETag/Last-Modified and relevant response metadata. Resume only when the server confirms byte ranges and validators still match. If the server ignores `Range`, changes validators, or returns an invalid `Content-Range`, restart safely instead of silently corrupting the file.
3. **Background lifecycle:** define the notification, foreground/background execution, process-death recovery, and reboot behavior before shipping the backend.
4. **Segmented transfer:** only after single-stream resume passes integration tests. Require valid range support and a known compatible resource; use bounded connections, separate temporary segments, and verify the final length before publishing the file.
5. **Benchmark:** compare success rate, resume correctness, speed, battery/data overhead, and server compatibility against the current backend on several real Android devices and networks.

## Architecture guardrails

- Keep the queue policy independent from the network implementation.
- Store transfer state durably; do not use Compose state as the source of truth.
- Never claim an HTTP server supports resume based only on a URL or file extension.
- Never append new bytes to an old partial file without validating the response.
- Preserve a non-segmented fallback for servers that do not support range requests.
- Keep browser integration, media extraction, and torrent support separate from the core engine decision.

## Release gate

The custom backend is not ready for general use until automated tests cover normal completion, cancellation, timeouts, redirects, HTTP errors, interrupted transfer, process death, resume validation, insufficient storage, duplicate filenames, and corrupted/incomplete segments. Automated CI is necessary but not a substitute for device testing.
