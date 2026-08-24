# DYLAN

Offline-first personal music player over JioSaavn's **unofficial** web API. Kotlin Multiplatform core; Android (Compose) ships today, iOS sources compile but are not yet linked. Playback consumes only verified local files: every track is downloaded, integrity-checked, then played — nothing streams in v1.

> **Personal use only.** Catalog access relies on undocumented endpoints with no ToS grant and no redistribution license. Do not distribute the APK or any fetched content. See `DYLAN-PLAN.md` §1.2 for the full legal stance.

## Features (current state)

- **Search** — WebSocket type-ahead suggestions plus HTTP full results; duplicate entries deduped (`[verified: 7.har]`); search history chips
- **Home** — Trending albums, Top-searches chips, Jump Back In (last 5), Based-on-your-searches, Your favorites
- **Albums & artists** — detail pages keyed by perma-token (numeric ids return empty shells `[verified: HAR-2]`); play/shuffle wired end-to-end
- **Download-first playback** — single-slot engine with USER_NOW > USER_BULK > PREFETCH_NEXT priorities, settle-timer preemption, stall watchdog, Range/If-Range resume, exact Content-Length verification, signed-URL resolve-at-dequeue (5-min TTL)
- **Offline cache** — LRU, ≤ 300 files / 2 GB, `.part` accounting, favorites auto-pin into a bounded 75% sub-pool with demotion; clear-cache protects what's playing
- **Quality** — 128/320 toggle, metered networks force 128, sufficiency-dedupe never downgrades, upgrades only earned + unmetered
- **Queue semantics** — shuffle anchors current track (never restarts), repeat off/all/one, next-track auto-advance incl. uncached-next join-on-download
- **Platform** — Media3/MediaSession notification + lock controls, process-death resume snapshot, artwork memory/disk caches
- **Tests** — 58 JVM unit tests across 7 suites (fixtures-based mapper suite included)

Not yet: iOS runtime (framework link blocked on Xcode install), release-size R8 audit, goldens/a11y suites (M4).

## Requirements & build

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home   # JDK 17 required
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools/platform-tools   # or your SDK path

./gradlew ktlintCheck detekt :shared:jvmTest --rerun-tasks :shared:probeCi :androidApp:assembleDebug --no-configuration-cache
```

Other useful tasks:

```bash
./gradlew :androidApp:assembleRelease                                        # signed release APK
./gradlew :shared:probeLocal -PprobeFast --no-configuration-cache            # live network probe (manual milestone gate, D21)
./gradlew :shared:contractDrift                                              # live-vs-fixture API contract report (see below)
./gradlew :shared:compileKotlinIosSimulatorArm64 :shared:compileKotlinIosArm64   # iOS klibs, no Xcode needed
```

`keystore.properties` + `dylan-release.keystore` at the repo root drive release signing; without them, release falls back to the debug key.

## Sideload (Android)

```bash
# debug
./gradlew :androidApp:assembleDebug
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk

# signed release
./gradlew :androidApp:assembleRelease
adb install -r androidApp/build/outputs/apk/release/androidApp-release.apk
```

App id: `app.dylan.player`. minSdk 34 / targetSdk 36.

## Architecture

| Module | Role |
|---|---|
| `androidApp/` | Compose UI (screens/stores), `DylanMediaService` + `ExoPlayerEngine` on a dedicated media thread |
| `shared/` commonMain | Models, `PlaybackOrchestrator` (single state machine, state lane), `SaavnProvider` + `Mapper` (adapter quarantine zone), `SearchChannel` (WS + HTTP fallback), `DownloadEngine`, `CacheManager`/Reconciler, SQLDelight schema, repos, `FlowAdapter` Swift bridge |
| `shared/` iosMain / `iosApp/` | Driver/metering actuals; SwiftUI views, `NativeAudioOutputImpl` (AVQueuePlayer), bridge, thumbnailer — compile-only until Xcode lands |
| `fixtures/` | Sanitized real responses backing `MapperFixturesTest` |
| `tools/` | `probe.main.kts`, fixture extraction, probe results log |

Seams (the only interfaces — each has a nameable second implementation):

| Seam | Impl today | Alternate |
|---|---|---|
| `MusicProvider` | `saavn/SaavnProvider` | test fakes (Subsonic optional M4) |
| `SearchChannel` | WS type-ahead + HTTP autocomplete | HTTP-only session mode |
| `PlayerEngine` | `ExoPlayerEngine`, `IosPlayerEngine` | `FakeEngine` in tests |

## Contract drift tooling

JioSaavn's endpoints are unofficial and can change silently. `./gradlew :shared:contractDrift` hits live search / album / top-searches / trending through the real clients + Mapper, compares field-by-field against expectations derived from `fixtures/` (presence, types, nullability, dedupe counts, perma-token derivation), and prints a `field | kind | sample` report. Captured reports live in `docs/contract-drift-*.txt`; run before milestones or whenever the server misbehaves. Structural checks also run hosted via `.github/workflows/ci.yml`.

## HAR captures as evidence

Repo-root captures (`2.har`–`8.har`, `www.jiosaavn.com.har`, `saavn_har.json`) back every `[verified: HAR-n]` claim in `DYLAN-PLAN.md` §3.2 and several mapper invariants (perma-token derivation, zero-cookie auth, signed-URL TTL, duplicate search rows). They are retained deliberately as evidence, not leftovers — delete only by explicit sign-off.

## Icon attribution

Launcher icon foreground traced from a Bob Dylan 1965 publicity photograph (publicity stills released without notice are public domain), sourced via Wikimedia Commons.
