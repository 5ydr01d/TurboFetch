# TurboFetch

An ad-free, privacy-first Android download manager.

## Direction

TurboFetch is being built Android-first with Kotlin and Jetpack Compose. The first milestone is a reliable direct HTTP/HTTPS download queue; browser integration, media detection and torrent support are later milestones.

## Planned MVP

- Add direct HTTP/HTTPS URLs
- Persistent download queue
- Progress, pause, resume, retry and cancel
- HTTP Range-based continuation where supported by the server
- Background downloads with an ongoing notification
- App-specific download storage
- No ad SDKs, analytics SDKs, account requirement or tracking endpoint

## Development

Open the project in Android Studio with JDK 17 and the Android SDK installed. The Android Gradle project files and automated build workflow will be added in the next commits.

## Scope and safety

This app is intended for direct links the user is authorized to download. It will not bypass DRM, paywalls, authentication, or site access controls. Resume support depends on server byte-range support; when unavailable, downloads must restart safely.

## Privacy

No advertising or analytics SDKs are planned. Network requests should only go to URLs the user submits.

## License

No license has been selected yet.
