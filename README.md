# Dylan — Offline-First Music Player

[![CI](https://github.com/abhakash/dylan/actions/workflows/ci.yml/badge.svg)](https://github.com/abhakash/dylan/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-7f52ff)](https://kotlinlang.org)
[![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20iOS-lightgrey)](https://github.com/abhakash/dylan)

**Dylan** is an offline-first personal music player. Kotlin Multiplatform core with native UIs (Jetpack Compose on Android, SwiftUI on iOS). Every track is downloaded, verified, and played from local storage — no streaming in v1.

> **Status:** Incubating · **License:** Apache 2.0 · **Personal use only** — catalog access relies on undocumented endpoints with no redistribution grant. Do not distribute APKs or fetched content.

---

## Features

- **Search** — WebSocket type-ahead (800 ms) + HTTP full results, deduped across catalog buckets, recent-search chips
- **Home** — Trending albums, top-search chips, *Jump Back In* (last 5), *Recently played albums* (SQL `GROUP BY album_id`), favorites
- **Albums & Artists** — detail pages via `content.getAlbumDetails` / `webapi.get` perma-token routing, play & shuffle end-to-end
- **Download-first playback** — single-slot engine `USER_NOW > USER_BULK > PREFETCH_NEXT` with preemption, stall watchdog (20 s / rate wall 8 KB/s), `Range`/`If-Range` resume, `Content-Length` + `ftyp`/`ID3` verification, signed-URL resolve at dequeue (5 min TTL)
- **Offline cache** — LRU ≤ 300 files / 2 GB, `.part` accounting, favorites auto-pin into 75% sub-pool with LRU demotion, `clear-cache` protects what's playing
- **Quality** — 128 / 320 toggle, metered forces 128, never downgrades, upgrades only unmetered + earned
- **Queue** — shuffle anchors current track, repeat off/all/one, next-track auto-advance with late-join on download
- **Platform** — Media3 `MediaSession` notification, lock-screen controls, process-death `ResumeSnapshot`, Coil memory (48 MB) + disk (150 MB) artwork caches
- **Diagnostics** — `LogBuffer` (512 ring) + `FileLogSink` (512 KB × 2, `DROP_OLDEST`) at `files/logs/dylan.log.*`, week-later triage

---

## Architecture

```
androidApp (Compose, minSdk 34)          iosApp (SwiftUI, iOS 17+)
  Screens ← StateFlows                     Screens ← @Observable Stores
  DylanMediaService                        AudioEngine (AVQueuePlayer)
    └ ExoPlayerEngine                        └ NativeAudioOutput
                         shared Kotlin core
  PlaybackOrchestrator  ·  QueueStateMachine  ·  WindowPreparer  ·  SnapshotStore
  DownloadEngine  ·  DownloadQueue  ·  Fetcher  ·  Verifier  ·  Breaker
  CatalogProvider + Mapper  ·  SearchChannel (WS+HTTP)  ·  CacheManager/Reconciler
  SQLDelight (WAL, single dbLane)  ·  okio fs  ·  AppContainer (pure ctor start/stop)
  FlowAdapter → Swift
```

**Seams (only interfaces, each with a second impl today):**

| Seam | Prod | Alt |
|------|------|-----|
| `MusicProvider` | `CatalogProvider` | test fakes |
| `SearchChannel` | WS + HTTP | HTTP-only fallback |
| `PlayerEngine` | `ExoPlayerEngine` / `IosPlayerEngine` | `FakeEngine` in tests |

---

## Getting Started

### Prerequisites

- JDK 17 (`/opt/homebrew/opt/openjdk@17` on macOS)
- Android SDK (`ANDROID_HOME` or `platform-tools` on PATH)
- For iOS: Xcode 16.2+ *or* use cloud CI (no local Xcode needed, see below)

### Clone & Build

```bash
git clone git@github.com:abhakash/dylan.git
cd dylan
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home

# gates — must be green before any PR
./gradlew ktlintCheck detekt :shared:jvmTest --rerun-tasks --no-configuration-cache

# Android debug (sideload)
./gradlew :androidApp:assembleDebug --no-configuration-cache
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
# appId app.dylan.player, minSdk 34 / targetSdk 36

# release (R8, mapping)
./gradlew :androidApp:assembleRelease --no-configuration-cache

# live probe (real network, manual gate)
./gradlew :shared:probeLocal -PprobeFast --no-configuration-cache

# iOS klibs only (no Xcode)
./gradlew :shared:compileKotlinIosSimulatorArm64 :shared:compileKotlinIosArm64 --no-configuration-cache
```

### iOS without local Xcode

Push to GitHub — `ci` builds the XCFramework + `xcodebuild -sdk iphonesimulator` on `macos-14` (Xcode 16.2). For device IPA: `Actions → ios-release → Run workflow → testflight` (needs `APPLE_TEAM_ID` + cert/profile secrets). `IPHONEOS_DEPLOYMENT_TARGET = 17.0` runs on iOS 26 devices.

---

## Testing

```bash
./gradlew :shared:jvmTest --rerun-tasks --no-configuration-cache  # 19 tests
./gradlew :shared:probeCi --no-configuration-cache                # structural contract
./gradlew :shared:contractDrift --no-configuration-cache          # live vs fixtures
```

Fixtures in `fixtures/` are sanitized real responses backing `Mapper` tests.

---

## Project Structure

```
dylan/
├── androidApp/          # Compose UI, DylanMediaService, ExoPlayerEngine
├── iosApp/              # SwiftUI, NativeAudioOutputImpl, Bridge
├── shared/
│   ├── commonMain/      # core: provider/mapper, playback, download, cache, db
│   ├── androidMain/     # DriverFactory, NetClass, Util
│   └── iosMain/         # DriverFactory, IosGraph, IosPlayerEngine
├── fixtures/            # sanitized API responses
├── gradle/              # libs.versions.toml (single version source)
├── .github/workflows/   # ci.yml (lint/test/android/ios) + ios-release.yml
└── config/detekt.yml
```

---

## CI/CD — FAANG Style

`ci.yml` (presubmit, `concurrency: cancel-in-progress`):

- `wrapper-validation` → `lint` (ktlint+detekt+Android lint) → `test/jvm` (probeCi+contractDrift) → `android` (debug+release R8) + `ios/klib` + `ios/simulator` (`assembleXCFramework` + `xcodebuild CODE_SIGNING_ALLOWED=NO`)
- Cache: `gradle/actions/setup-gradle` + `GRADLE_ENCRYPTION_KEY` (hermetic, `cache-read-only` on PRs), `~/.konan/DerivedData`
- Artifacts: `apks` + `mapping.txt` + `lint-reports` + `xcode-logs` (14d)

`ios-release.yml` (`workflow_dispatch: testflight/adhoc/simulator`) → archive → `exportOptions.plist` → TestFlight via `altool`.

Branch protection on `main`: require `lint,test,android,ios/klib,ios/simulator` + CODEOWNERS review.

Dependabot weekly groups `ktor/compose/kotlin/sqldelight`.

---

## Contributing

PRs must be green (`ktlintCheck detekt jvmTest` + `android` + `ios`). Keep seams minimal (Law 3: every interface needs a nameable second impl today). No `runBlocking` on `state` lane, no `synchronized` in `commonMain`.

---

## License

Apache 2.0 — see [LICENSE](LICENSE). Copyright 2026 Dylan Contributors.

---

## Acknowledgments

Coil, ExoPlayer/Media3, SQLDelight, Ktor, okio. Icon derived from a 1965 publicity still (public domain).
