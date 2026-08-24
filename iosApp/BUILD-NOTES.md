# iOS BUILD NOTES — Xcode-day checklist

**Scope of this document:** everything the Swift/Kotlin-ios side needs verified the moment
Xcode is installed. Kotlin/Native klib compilation is already green for both targets
(`compileKotlinIosSimulatorArm64` / `compileKotlinIosArm64`) — that gate does NOT cover any
Swift below; treat all Swift as compile-unverified until `xcodebuild` runs.

**Status after readiness pass (2026-08-22):** the two known pbxproj gaps are FIXED —
an `Embed Frameworks` copy-files phase now exists (CodeSignOnCopy, runs after link), and the
bogus `-Pdevice` Gradle flag was removed from the build-phase script. All Swift sources were
statically audited (see §3A fixes / §3B deferrals) and every risky Kotlin-symbol mapping is
centralized in `Bridge/DylanBridge.swift` (inventory in §3C).

---

## 1. Files created (all under `iosApp/iosApp/`, exactly matching pbxproj refs)

| File | Role | Bound shared seams |
|---|---|---|
| `App/DylanApp.swift` | `@main`, composition root: URLCache singleton once (R6-3), audio-session `.playback` at launch (C.8), `SharedIosGraph.companion.create(baseDir:)`, `attachAudio(output)`, NWPathMonitor→`pushMetered` (D14), memory-warning flush, toast wiring | IosGraph.create, attachAudio, pushMetered, onToast, cfg |
| `App/RootView.swift` | Tabs Home/Search/Library · mini player above tab bar · full-screen NP cover · Queue + Settings sheets · album detail pushed cover · ScenePhase.background → `graph.onBackground()` (snapshot + WS close) | subscribePlayerState (via PlayerStore), onBackground |
| `Audio/NativeAudioOutputImpl.swift` | AVQueuePlayer window engine (§9.4). **D8 mirror: emits `Prepared(itemId)` only when the CURRENT item's KVO status hits `.readyToPlay`; failed items emit `Error(.source)`.** replaceUpNext removes beyond index 0 only; KVO currentItem → TrackChanged(AUTO); natural end → ItemEnded / QueueExhausted; route-change oldDeviceUnavailable → pause+RouteLost; interruptions → Interrupted(shouldResume); setActive(true) inside play() with SessionActivation error mapping | NativeAudioOutput protocol incl. `bindEvents(sink)` |
| `Audio/NowPlayingController.swift` | MPRemoteCommandCenter → intent bus (Law 4); MPNowPlayingInfoCenter state + ~2 Hz throttled position + 500 px artwork via Thumbnailer | subscribePlayerState, subscribePosition, submit |
| `Bridge/DylanBridge.swift` | THE ONLY file naming raw Kotlin symbols (typealiases + Events/Intents factories + async wrappers over suspend funs with safe defaults) | provider/repos/settings/graph suspend API |
| `Imaging/Thumbnailer.swift` | ImageIO downsample decoder (§11.9): rows 150 px, NP/lockscreen 500 px; NSCache memory + URLSession.shared → app-wide URLCache disk | — |
| `Stores/Stores.swift` | `@Observable @MainActor` stores: PlayerStore (state+position+derived phase/repeat/status strings via graph helpers), SearchStore (WS render-on-arrival + D7 dedupe + submit), HomeStore, LibraryStore, PrefsStore, ToastStore. Handles cancelled in deinit (M7) | subscribePlayerState/Position/Suggestions, FlowAdapter closures |
| `Views/Tokens.swift` | §11.2 palette light/dark adaptive + type scale + spacing/radii | — (mirrors Tokens.kt; parity goldens = M4) |
| `Views/Components.swift` | SongRowView (§11.5 states incl. per-row DownloadRing subscribing `subscribeProgress(key:)` alone — R7-P1), MiniRowView, ThumbImage, EqBars (Reduce-Motion gated), PlayPauseCircle, chips/banner, formatBytes | subscribeProgress |
| `Views/Screens.swift` | HomeScreen / SearchScreen / LibraryScreen / AlbumScreen / SettingsPanel — structure & copy mirror Android screens | search bridge, favorites, downloads library, storage stats, clear-cache, bulk enqueue |
| `Views/NowPlayingView.swift` | NowPlayingSheet (scrub committed-on-release, shuffle/prev/64pt-circle/next/repeat-badge, queue/heart/**real cached-bitrate chip**), QueueSheet (move up/down = MoveWithinQueue, swipe remove = RemoveAt), MiniPlayerBar | submit intents, isFavorite, cachedBitrateOf |

## 2. iosMain additions this round (`shared/src/iosMain/kotlin/dylan/di/IosGraph.kt`)

- `cachedBitrateOf(key)` — NP quality chip reads the REAL cached row bitrate (Android parity).
- `enqueueDownloadNow(song)` — SongRow "Download now" context action ⇒ USER_NOW at
  effective quality (metered⇒128 else settings pref), mirroring orchestrator selection.

Graph factory itself (`create(baseDir:)`) was already complete and mirrors Android init:
DriverFactory → DB → clients (Darwin engine) → SearchChannel → CacheManager → DownloadEngine
→ Orchestrator → Reconciler → restoreFromSnapshot → weeklyGc/home-cache-evict loop,
dispatchers main/io/dbLane=Default.limitedParallelism(1)/state=same (§5.2).

## 3. First xcodebuild session — check in this order

0. **Prereqs:** `export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`;
   open `iosApp/iosApp.xcodeproj`; select an iOS Simulator (arm64) and the `iosApp` scheme.
1. **DEVELOPMENT_TEAM — TODO (explicit):** still `""` in all three target configs
   (I101/I102/I103). We have NO Apple ID yet. Set it in Signing & Capabilities for
   Debug+Release+Profile (automatic signing) before any device run or archive. Simulator
   builds work without a team.
2. **Build-phase order is now correct** (verify visually once):
   `Build Kotlin framework` → `Sources` → `Frameworks` → **`Embed Frameworks`**.
3. **Embed phase (NEW — previously missing, would have dyld-crashed at launch).**
   `E104… Embed Frameworks` = PBXCopyFilesBuildPhase, dstSubfolderSpec=10 (Frameworks),
   embedding `shared.framework` from `BUILT_PRODUCTS_DIR` with
   `ATTRIBUTES = (CodeSignOnCopy, RemoveHeadersOnCopy)`.
   Why the source path resolves: Kotlin Gradle plugin 2.4.10's
   `embedAndSignAppleFrameworkForXcode` (verified in its bytecode) ① builds the framework
   per Xcode env vars (`CONFIGURATION`, `SDK_NAME`, `ARCHS`) into
   `shared/build/xcode-frameworks/<config>/<sdk>`, ② creates a symlink
   `$BUILT_PRODUCTS_DIR/shared.framework` → that location, ③ also copies it straight into
   `iosApp.app/Frameworks`. Our copy phase therefore finds the file; the double-embed
   (Gradle + CopyFiles) is idempotent and intentional belt-and-braces.
4. **Build-phase script hygiene (FIXED):** the stray `-Pdevice=$PLATFORM_NAME` Gradle flag
   was removed — that property never existed; the task auto-detects everything from env
   vars. Script remains:
   `./gradlew :shared:embedAndSignAppleFrameworkForXcode --no-configuration-cache || exit 1`
   Debug vs Release selection is automatic per configuration. Fail-fast on Gradle failure.
5. **Framework header names.** DylanBridge assumes ObjC prefix `Shared…` (framework baseName
   `shared`), nested classes flattened (`IntentPlayNow` style), `data object` → `.shared`,
   interfaces suffixed `-Protocol`. If the importer disagrees, fix ONLY typealiases in
   `DylanBridge.swift` (+ `KNativeAudioOutput`/`KEngineEventSink` conformances in
   NativeAudioOutputImpl.swift) — no other Swift file names a Kotlin symbol anymore.
   Full inventory: §3C below.
6. **Swift flags: NONE required.** pbxproj already pins `SWIFT_VERSION = 5.0` and
   `SWIFT_STRICT_CONCURRENCY = minimal` in all three configs — do NOT flip to Swift 6 /
   strict yet: Kotlin-delivered callbacks land via `Dispatchers.Main.immediate` and are only
   runtime-safe (not annotation-proven); strict mode would demand wrappers first.
7. **Companion access**: `SharedIosGraph.companion.create(baseDir:)` (wrapped by
   `DylanGraph.create` in the bridge) — verify spelling (older toolchains: `.metaClass`).
8. **pbxproj sanity gate:** re-run after ANY hand edit of project.pbxproj:
   `python3 iosApp/Tools/check_pbxproj.py` — validates object graph, ID uniqueness,
   phase ordering, embed attributes, Sources↔disk parity (mutation-tested).
9. **Runtime smoke list** (M1.5-style, single screen acceptable): play cached file end-to-end
   through orchestrator; confirm exactly ONE `Prepared` per prepare (D8 mirror) in logs;
   lock-screen controls; interruption/headphone-unplug pause; kill/resume snapshot;
   metered push flips prefetch quality (Network Link Conditioner or airplane-mode drill).

### 3A. Swift staff-review ledger — FIXED this round (static audit, no compiler)

| # | File | Finding | Fix |
|---|---|---|---|
| F1 | pbxproj | No embed phase ⇒ dyld crash risk | PBXCopyFilesBuildPhase added + ordered last (see §3.3); checker written & mutation-tested |
| F2 | pbxproj | `-Pdevice=$PLATFORM_NAME` unknown Gradle property | Removed; task needs only Xcode env vars (KGP 2.4.10 bytecode verified) |
| F3 | DylanBridge (+NowPlayingController, DylanApp) | `shared.KotlinSubscription`, `shared.EngineEvent*` unprefixed module-qualified names — guaranteed compile errors; raw `SharedIntent*`/`SharedIosGraph.companion` spellings leaked outside bridge | All aliases now `Shared…`-prefixed; `KEngineEvent` alias added for `emit()`; NowPlayingController routes through `Intents.*`; `DylanGraph.create(baseDir:)` wrapper added; single-file invariant holds (grep-verified) |
| F4 | Tokens.swift | `DylanTokens.s6` used 6× but undefined — compile error | Added `s6 = 6` |
| F5 | Thumbnailer.swift | `inflight[key]` passed NSString key to `[String: Task]` dict / `removeValue(forKey: key as NSString)` — type errors | Key kept as String; explicit `as NSString` only at NSCache boundary |
| F6 | NativeAudioOutputImpl.swift | Block-based notification observers not removed if dealloc'd without `release()`; `CMTimeGetSeconds` NaN/∞ → UB Int64 conversion poisoning position lane | Idempotent `deinit { release() }`; non-finite/negative guard returning 0 |
| F7 | Screens.swift AlbumScreen | Shuffle button restarted album at track 0 mid-playback — pre-E2 Android behavior | E2 parity ported: anchor-current reshuffles upcoming only; else shuffle ON + random start index |
| F8 | Stores.swift HomeStore | Jump Back In used `historyRecent(10)` — pre-E5 Android behavior | E5 parity: `recent(5)` |

Audit-clean areas (checked, no action): retain-cycle sweep (every FlowAdapter closure,
KVO token and NotificationCenter block captures `[weak self]`; sink stored weak);
AVAudioSession activation error path surfaces through `bindEvents` sink as
`Error(nil, .sessionActivation)`; URLCache set exactly once before any URLSession use;
NSCache thread-safe + inflight map lock-guarded; NowPlaying throttle correct (state-change
writes bounded by conflated StateFlow, position writes ≥500 ms apart, artwork race guarded
by song-token compare); no `try!`/`as!`; single documented IUO (`AppEnvironment.shared!`,
app-lifetime singleton).

### 3B. Swift findings DEFERRED (uncertain or by-design — revisit with compiler)

| # | Item | Rationale for deferral |
|---|---|---|
| D1 | Strict concurrency left `minimal` | Closures arrive on `Dispatchers.Main.immediate` (runtime-main but unannotated); flipping requires actor-hops around every Kotlin callback — do it WITH xcodebuild diagnostics in hand, not blind |
| D2 | `deinit` touches MainActor-isolated props (PlayerStore/SearchStore/NowPlayingController cancel) | `Job.cancel` is thread-safe; minimal mode compiles it; restructuring storage to nonisolated deferred until compiler can verify |
| D3 | RootView fires `graph.onBackground()` on `.inactive` too | As-authored intent (snapshot+WS-close early); Android triggers background-only — behavioral choice, not a bug |
| D4 | Home rails keyed `ForEach(id: \.title)` | Duplicate server titles would log SwiftUI duplicate-ID warnings (cosmetic); dedupe belongs in store later |
| D5 | `replaceUpNext` onto EMPTY window emits TrackChanged(AUTO) | Orchestrator's `transportable(phase)` guard makes post-exhaustion pushes unreachable today; changing semantics without runtime proof risks regressions |
| D6 | Remote-command handlers return `.success` even when submit guards out pre-playback | Cosmetic lock-screen edge; v1 scope |

### 3C. Bridge assumption inventory — 43 symbol checks under rules A1–A8

Single-file fix point: `iosApp/iosApp/Bridge/DylanBridge.swift` (rules A1–A8 stated in its
header block). Verify each against
`shared/build/bin/<sdk>/<config>Framework/shared.framework/Headers/shared-Swift.h`.

| # | Swift symbol (bridge line) | Expected header declaration |
|---|---|---|
| 1–2 | `SharedIosGraph` + `.companion.create(baseDir:)` | `@interface SharedIosGraph` (class IosGraph) + companion static prop |
| 3 | `SharedKotlinSubscription` | class dylan.bridge.KotlinSubscription (was wrongly unprefixed) |
| 4–11 | `SharedSong` `SharedSongKey` `SharedMiniEntity` `SharedAlbum` `SharedHomeSection` `SharedPlayerState` `SharedDylanFailure` `SharedLocalTrack` | data classes, prefix rule A1 |
| 12–13 | `SharedCachedSongInfo` `SharedCacheStats` | iosMain data classes (songCount/totalBytes/bytes: Int64) |
| 14 | `SharedPaged` | generic ERASED: `.items` arrives `[Any]` (A6), `.total` Int64 |
| 15 | `SharedNativeAudioOutputProtocol` | @protocol SharedNativeAudioOutput + "-Protocol" (A2) |
| 16 | `SharedEngineEventSinkProtocol` | @protocol SharedEngineEventSink + "-Protocol" |
| 17 | `SharedEngineEventProtocol` | sealed interface EngineEvent + "-Protocol" (was unprefixed) |
| 18 | `SharedIntentProtocol` | interface Intent + "-Protocol" |
| 19 | `SharedIntentPlayNow(songs:startIndex:Int32)` | flattened Intent.PlayNow; List→[Any], Int→Int32 |
| 20 | `SharedIntentSeek(ms:Int64)` | Long→Int64 |
| 21–22 | `SharedTransitionReason` `.auto/.seek/.explicit`; `SharedEngineErr` `.decode/.source/.sessionActivation` | enums lowerCamelCase (A5) |
| 23–27 | `SharedEngineEventPrepared(itemId:)` `TrackChanged(itemId:reason:)` `ItemEnded(itemId:)` `Error(itemId:String?,kind:)` `Interrupted(shouldResume:)` | flattened EngineEvent children (A3) |
| 28–29 | `SharedEngineEventQueueExhausted.shared` `SharedEngineEventRouteLost.shared` | data objects → `.shared` (A4) |
| 30–35 | `SharedIntentTogglePlayPause/Next/Previous/ToggleShuffle/CycleRepeat/ClearUpNext` each `.shared` | data objects → `.shared` (A4) |
| 36–37 | `SharedIntentPlayNext(song:)` `SharedIntentAddLast(song:)` | init labels preserved |
| 38–39 | `SharedIntentRemoveAt(queuePos:Int32)` `SharedIntentMoveWithinQueue(from:Int32,to:Int32)` | Int→Int32 narrowing |
| 40 | `onToast: ((String) -> Void)?` block property | `(String)->Unit?` var (A8) |
| 41 | `subscribePlayerState((SharedPlayerState)->Void)` | FlowAdapter concrete closure |
| 42 | `subscribePosition((Int64)->Void)` | Long→Int64 param |
| 43 | `subscribeProgress(key:){(Int32)->Void}` + `subscribeSuggestions((String,[Any])->Void)` | Int→Int32; List<MiniEntity>→[Any] (A6) |

If ANY row disagrees: fix the alias/factory in DylanBridge.swift ONLY, then re-run
`python3 iosApp/Tools/check_pbxproj.py` (unchanged pbxproj should stay green).

## 4. Known deviations / accepted v1 gaps (documented, not bugs)

- Album detail opens as a full-screen cover instead of Android's inline tab-content swap.
- Downloads-tab empty state lacks the live "Downloading…" variant (Android reads the whole
  progress map; iOS store subscribes per-key only, R7-P1-faithful). Add a count subscription
  to IosGraph if wanted later.
- Prefetch toggle row is visual parity with Android (both read `cfg.prefetchEnabled`; neither
  persists yet — AppConfig edit toggles it).
- No marquee for long NP titles (Android uses basicMarquee; SwiftUI has no built-in — M4 polish).
- Queue reordering uses move-up/down buttons (same as current Android), not drag handles.
- NowPlayingController keeps its own subscriptions (decoupled from UI stores, like the Android
  service observing independently of Compose).

## 5. Verification commands

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
cd /Users/abhakash/PersonalWS/dylan
./gradlew :shared:compileKotlinIosSimulatorArm64 :shared:compileKotlinIosArm64 --no-configuration-cache
```
