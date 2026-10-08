# Dylan — Offline-First Music Player

[![CI](https://github.com/abhakash/dylan/actions/workflows/ci.yml/badge.svg)](https://github.com/abhakash/dylan/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-7f52ff)](https://kotlinlang.org)
[![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20iOS-lightgrey)](https://github.com/abhakash/dylan)

**Dylan** is an offline-first personal music player. Kotlin Multiplatform core with native UIs (Jetpack Compose on Android, SwiftUI on iOS). Every track is downloaded, verified, and played from local storage — no streaming in v1.

> **Status:** Incubating · **License:** Apache 2.0 · **Personal use only** — catalog access relies on undocumented endpoints with no redistribution grant. Do not distribute APKs or fetched content.

---

## Features

- **Search** — WebSocket type-ahead (120 ms debounce) + HTTP full results, deduped across catalog buckets, recent-search chips
- **Home** — Trending albums, top-search chips, *Jump Back In* (last 20), *Recently played albums* (SQL `GROUP BY album_id`), favorites
- **Albums & Artists** — detail pages via `content.getAlbumDetails` / `webapi.get` perma-token routing, play & shuffle end-to-end
- **Download-first playback** — single-slot engine `USER_NOW > USER_BULK > PREFETCH_NEXT` with preemption, stall watchdog (no fresh bytes for 20 s **or** whole-transfer wall cap `max(120 s, expectedBytes / 8 B/ms)`), `Range`/`If-Range` resume, `Content-Length` + `ftyp`/`ID3` verification, signed-URL resolve per dequeue attempt (capped at 3 per job, no TTL cache)
- **Offline cache** — LRU ≤ 300 files / 300 MB **derived** (`cacheMaxFiles × 1 MB`, so the two caps cannot disagree), `.part` accounting, favourites auto-pin into a 75% sub-pool (rows *and* bytes) with oldest-first demotion, `clear-cache` protects what's playing
- **Quality** — 128 / 320 toggle, metered forces 128, never downgrades, upgrades only unmetered + earned
- **Queue** — shuffle anchors the current track, repeat off/all/one, next-track auto-advance with late-join on download
- **Platform** — Media3 `MediaSession` notification, lock-screen controls, process-death `ResumeSnapshot` (max age 15 min), Coil memory (48 MB) + disk (150 MB) artwork caches
- **Diagnostics** — `LogBuffer` (512 ring) + `FileLogSink` (512 000 B × 3 files = ~1.5 MB, `DROP_OLDEST`) at `files/logs/dylan.log.*`, flushed on background/terminate, week-later triage (see `docs/triage.md`)

---

## Architecture

```
androidApp (Compose, minSdk 34)          iosApp (SwiftUI, iOS 17+)
  Screens ← StateFlows                     Screens ← @Observable Stores
  DylanMediaService                        NativeAudioOutputImpl (AVQueuePlayer)
    └ ExoPlayerEngine                        └ IosPlayerEngine
                         shared Kotlin core
  Orchestrator  ·  QueueStateMachine  ·  WindowPreparer  ·  ResumeSnapshot
  DownloadEngine  ·  JobQueue  ·  Transfer + container sniffing  ·  Breakers
  SaavnProvider + Mapper  ·  SaavnSearchChannel (WS fast path, HTTP authority)  ·  CacheManager/Reconciler
  SQLDelight (WAL, single dbLane)  ·  okio fs  ·  AppContainer (lazy open / start / stop / shutdown)
  FlowAdapter → Swift
```

**Seams.** `PlayerEngine` is the only seam with two *production* implementations.
`MusicProvider` and `SearchChannel` each have exactly one, and `NetMonitor` has one per
platform — so the "every interface needs a nameable second impl today" rule
(see [Contributing](#contributing)) holds for `PlayerEngine` and `NetMonitor` and not yet
for the other two.

| Seam | Prod | Second impl |
|------|------|-------------|
| `PlayerEngine` | `ExoPlayerEngine` (Android) / `IosPlayerEngine` (iOS) | `FakePlayerEngine`, run against the shared `EngineContractTest` |
| `MusicProvider` | `SaavnProvider` | test fakes only (`GraphHarness.StubProvider`, `GatedProvider`) |
| `SearchChannel` | `SaavnSearchChannel` — WS is a *latency* fast path, HTTP is the **authority**; every doubt (offline, cooldown, timeout, socket error, undecodable frame, mispaired demand) falls back to HTTP | none. `HttpSuggest` is a private collaborator *inside* that one class, not a second `SearchChannel` |
| `NetMonitor` | Android `NetworkCapabilities` / Darwin `NWPathMonitor` / JVM fixed stub | `FakeNetMonitor` in tests |

Both `container.net.provider` and `container.net.searchChannel` are held as **concrete**
types (`AppContainer.kt:153`, `Graphs.kt:52`), so no production call site is substitutable
behind the `MusicProvider` / `SearchChannel` interfaces today. Unknown connectivity fails
closed to `METERED` (`NetClass.kt:17,34`), which is why the two catalog seams are not yet a
swap point and why the quality badge and the download bitrate can never disagree.

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

# presubmit gates — the exact tasks ci.yml runs, minus the iOS/Xcode jobs
# (`--rerun`, not `--rerun-tasks`: re-executes the test task only, leaves
#  compilation cached. See docs/build-and-gates.md.)
./gradlew ktlintCheck detekt :androidApp:lintDebug
./gradlew :shared:jvmTest --rerun

# or, everything local presubmit runs plus the build/artifact assertions:
./tools/check.sh                 # add DYLAN_LIVE_GATES=1 for probeCi + contractDrift

# Android debug (sideload)
./gradlew :androidApp:assembleDebug
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
# appId app.dylan.player, minSdk 34 / targetSdk 36

# release (R8, mapping)
./gradlew :androidApp:assembleRelease

# live probe (real network, manual gate)
./gradlew :shared:probeLocal -PprobeFast

# iOS klibs only (no Xcode)
./gradlew :shared:compileKotlinIosSimulatorArm64 :shared:compileKotlinIosArm64
```

### iOS without local Xcode

Push to GitHub — `ci` builds the XCFramework + `xcodebuild -sdk iphonesimulator` on `macos-14` (Xcode 16.2). For device IPA: `Actions → ios-release → Run workflow → testflight` (needs `APPLE_TEAM_ID` + cert/profile secrets). `IPHONEOS_DEPLOYMENT_TARGET = 17.0` is set in all six build configurations. There is no app icon and no asset catalog yet, and `DEVELOPMENT_TEAM = ""` in all three target configurations — a device run or archive needs a team set first; simulator builds do not.

---

## Testing

```bash
./gradlew :shared:jvmTest --rerun
./gradlew :shared:iosSimulatorArm64Test   # runs commonTest on the simulator (presubmit)
./gradlew :shared:probeCi                # live structural contract, S1-S3, M0-gated
./gradlew :shared:contractDrift          # nightly-only: live vs fixtures (not presubmit)
```

Fixtures in `fixtures/` are sanitized real responses backing `Mapper` tests. A missing or
unparseable fixture is a hard error in `contractDrift`, not an empty expectation set.

---

## Project Structure

```
dylan/
├── androidApp/          # Compose UI, DylanMediaService, ExoPlayerEngine
├── iosApp/              # SwiftUI, NativeAudioOutputImpl, Bridge
├── shared/
│   ├── commonMain/      # core: provider/mapper, playback, download, cache, db, util
│   ├── androidMain/     # DriverFactory, Util (NetMonitor over NetworkCapabilities)
│   ├── jvmMain/         # DriverFactory, Util (JVM NetMonitor), ProbeMain (probe/contractDrift)
│   ├── iosMain/         # DriverFactory, IosGraph, IosPlayerEngine
│   ├── commonTest/      # pure queue-algebra + property tests (run on JVM *and* iOS)
│   └── jvmTest/         # engine contract suite, download matrix, graph lifecycle
├── tools/               # check.sh, bump-version.sh, dylan-sign (Go)
├── fixtures/            # sanitized API responses
├── gradle/              # libs.versions.toml (single version source)
├── .github/workflows/   # ci.yml (presubmit) + builds.yml (gate + debug/prod lanes) + version/release/ios-*
├── config/              # detekt.yml (delta on packaged defaults) + per-module detekt baselines
└── VERSION              # single version source (bump-version.sh writes it)
```

---

## CI/CD — FAANG Style

`ci.yml` (presubmit, `concurrency: cancel-in-progress`):

- `wrapper-validation` gates everything. The rest is a **fan, not a chain**: `lint`,
  `test/jvm`, `ios/klib` and `gosign` are four siblings that depend only on
  `wrapper-validation`, and `android/debug` + `ios/simulator` are the two that wait for
  `test/jvm` *and* `android/debug` + `ios/simulator` are the two that wait for
  `lint` and `test/jvm` (`ci.yml:39,71,142,227` vs `106,166`). So `ios/klib` and `gosign`
  can be green while `lint` is still red — they are not downstream of the linters.
- `lint` runs `ktlintCheck detekt :androidApp:lintDebug` (`ci.yml:54`); `test/jvm` runs
  `:shared:jvmTest --rerun` (`ci.yml:89`) and is **not** the probe — `probeCi` is nightly
  only. `ios/simulator` runs `:shared:assemble`, `:shared:iosSimulatorArm64Test`
  (the `commonTest` lane) and `xcodebuild … CODE_SIGNING_ALLOWED=NO`.
- Cache: `gradle/actions/setup-gradle` (`cache-read-only` on non-`main`; `GRADLE_ENCRYPTION_KEY`
  is set on the `lint` job only) + `~/.konan`/DerivedData (`shared/build` intentionally uncached)
- Artifacts: `apks-debug` + `lint-reports` + `xcode-logs` (14d)
- Nightly (`schedule` / manual only): `nightly/probe` (probeLocal + probeCi) +
  `nightly/contract-drift` (live vs fixtures). Both hit the live API, so neither is presubmit.

`builds.yml` runs a `gate` job (`ktlintCheck detekt :shared:jvmTest --rerun`, no Android
lint) that all four build jobs `need:`, then `android/debug` (debug APK, no secrets) +
`android/prod` (R8 + signed APK + AAB, keystore secrets, graceful skip when absent, apksigner
guard refuses debug-signed output) + `ios/debug` (sim Debug) + `ios/prod-unsigned` (Release
unsigned IPA with version stamp + sha256, asserted unsigned — sign via `tools/dylan-sign`).
The manual `platform: android/ios/all` input is **declared but never read** — all four
`if:` conditions test `inputs.flavor` only, so a `platform: android` dispatch still runs the
iOS jobs. A `v*` tag push runs **all four** lanes, not just prod. Prod artifacts kept 30d.

`version.yml` (each `main` push, except bot pushes): bumps VERSION from the HEAD message
(`feat:` → minor, `!:`/`BREAKING CHANGE` → major, else patch) via `tools/bump-version.sh`,
syncs iOS `MARKETING_VERSION`/`CURRENT_PROJECT_VERSION`, commits
`chore(version): vX.Y.Z [skip ci]`, and pushes tag `vX.Y.Z` — which triggers `builds.yml`.
Loop-safe four ways (actor guard at `version.yml:36`, `[skip ci]` on the commit, a
`chore(version):` re-bump skip, and a 3-attempt rebase-retry that `exit 1`s if none
succeeds — `version.yml:81-95`), serialized via a `cancel-in-progress: false` concurrency
group. Note `AppContainer.APP_VERSION` is a hand-maintained literal and has drifted from
`VERSION`; only the pbxproj is machine-synced.

`release.yml` (manual `workflow_dispatch`) is a separate path from `builds.yml`: bump →
gates → materialise the keystore (**fails closed**, `release.yml:63-67`) → commit/tag/push
directly to `main` → `assembleRelease` → `apksigner verify --print-certs` on every APK,
failing if the chain says `Android Debug` (`release.yml:109-114`) → upload APK + mapping.
It pushes straight to `main`, which branch protection rejects without a bypass allowance
(`release.yml:78-80`).

`ios-free.yml` (manual unsigned Release IPA, same as `builds.yml: ios/prod-unsigned` standalone) and `ios-release.yml` (`workflow_dispatch: testflight/adhoc/simulator`) → archive → templated `exportOptions.plist` (teamID from `APPLE_TEAM_ID` secret) → TestFlight via `fastlane pilot` (fails clearly without API-key secrets); signing material trap-cleaned.

Branch protection on `main` is a repository setting this repo does not own, so the required
-check names are not verifiable from the tree. What the tree *does* fix is the graph: the six
-presubmit job names above are the ones that exist, and `release.yml`/`version.yml` both push
to `main` directly, so a bypass allowance for `github-actions[bot]` is a precondition for
both, not just for releases.

Dependabot weekly groups `ktor/compose/kotlin/sqldelight`.

---

## Contributing

PRs must be green on the six presubmit jobs above — concretely
`ktlintCheck detekt :androidApp:lintDebug` + `:shared:jvmTest` + `:androidApp:assembleDebug`
+ the iOS klib/simulator pair. `./tools/check.sh` runs the hermetic subset locally.

Keep seams minimal: an interface earns its keep by having a *nameable second
implementation today* — and today only `PlayerEngine` (two prod impls plus a contract suite)
and `NetMonitor` (one impl per platform) clear that bar. `MusicProvider` and `SearchChannel`
are single-impl interfaces kept because the test fakes are real consumers, not because the
product can swap them. No `runBlocking` on the `state` lane, no `synchronized` in `commonMain`.

---

## License

Apache 2.0 — see [LICENSE](LICENSE). Copyright 2026 Dylan Contributors.

---

## Acknowledgments

Coil, ExoPlayer/Media3, SQLDelight, Ktor, okio. Icon derived from a 1965 publicity still (public domain).
