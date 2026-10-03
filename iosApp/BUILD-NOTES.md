# iOS BUILD NOTES — Xcode-day checklist

**Scope of this document:** what the Swift/Kotlin-ios side still needs a human to confirm.
The klib gate (`compileKotlinIosSimulatorArm64` / `compileKotlinIosArm64`) covers no Swift,
but the Swift is no longer only statically audited: `ci` runs
`xcodebuild -sdk iphonesimulator CODE_SIGNING_ALLOWED=NO` and
`:shared:iosSimulatorArm64Test` on every PR (`ci.yml:195-212`). What remains unverified is
everything a **simulator** build cannot answer — device signing, the `Embed Frameworks`
question in §3.3, and the runtime list in §3.9.

**Status after the readiness pass (2026-08-22), re-verified 2026-10-01:** the pbxproj gap
this list originally tracked is **closed differently than §3.3 describes**. The `Embed
Frameworks` copy phase exists, but it is **redundant with the Kotlin Gradle plugin** — KGP
already copies *and* signs `shared.framework` into `iosApp.app/Frameworks` (evidence in §3.3).
Read §3.3 before touching that phase. The bogus `-Pdevice` Gradle flag is gone. All Swift
was statically audited (§3A fixes / §3B deferrals) and every risky Kotlin-symbol mapping is
centralized in `Bridge/DylanBridge.swift` (inventory in §3C). Two §3B deferrals have since
been closed in code and are marked as such below: **D3** and the §3A **F8** parity claim.

---

## 1. Files created (all under `iosApp/iosApp/`, exactly matching pbxproj refs)

| File | Role | Bound shared seams |
|---|---|---|
| `App/DylanApp.swift` | `@main`, composition root: URLCache singleton once (R6-3), audio-session `.playback` at launch (C.8), `SharedIosGraph.companion.create(baseDir:)`, `attachAudio(output)`, NWPathMonitor→`pushMetered` (D14), memory-warning flush, toast wiring | IosGraph.create, attachAudio, pushMetered, onToast, cfg |
| `App/RootView.swift` | Tabs Home/Search/Library · mini player above tab bar · full-screen NP cover · Queue + Settings sheets · album detail pushed cover · `.onChange(of: scenePhase)` → `graph.onBackground()` on **`.background` only** (`RootView.swift:82`, see D3) | subscribePlayerState (via PlayerStore), onBackground |
| `Audio/NativeAudioOutputImpl.swift` | AVQueuePlayer window engine (§9.4). **D8 mirror: emits `Prepared(itemId)` only when the CURRENT item's KVO status hits `.readyToPlay`; failed items emit `Error(.source)`.** `prepare` runs a window diff — a same-head re-prepare keeps the audible item and only rebuilds the tail (`:113-135`, `replaceTail` at `:156-166`), and re-arms its own `Prepared` because an `AVPlayerItem` has no second `.readyToPlay` transition (`:141-153`). replaceUpNext removes beyond index 0 only; KVO currentItem → TrackChanged(AUTO); natural end → ItemEnded / QueueExhausted; route-change oldDeviceUnavailable → pause+RouteLost; interruptions → Interrupted(shouldResume); setActive(true) inside play() with SessionActivation error mapping | NativeAudioOutput protocol incl. `bindEvents(sink)` |
| `Audio/NowPlayingController.swift` | MPRemoteCommandCenter → intent bus (Law 4); MPNowPlayingInfoCenter state + ~2 Hz throttled position + 500 px artwork via Thumbnailer | subscribePlayerState, subscribePosition, submit |
| `Bridge/DylanBridge.swift` | THE ONLY file naming raw Kotlin symbols (typealiases + Events/Intents factories + async wrappers over suspend funs with safe defaults) | provider/repos/settings/graph suspend API |
| `Imaging/Thumbnailer.swift` | ImageIO downsample decoder (§11.9): rows 150 px, NP/lockscreen 500 px; NSCache memory + URLSession.shared → app-wide URLCache disk | — |
| `Stores/Stores.swift` | `@Observable @MainActor` stores: PlayerStore (state+position+derived phase/repeat/status strings via graph helpers), SearchStore (WS render-on-arrival + D7 dedupe + submit), HomeStore, LibraryStore, PrefsStore, ToastStore. Handles cancelled in deinit (M7) | subscribePlayerState/Position/Suggestions, FlowAdapter closures |
| `Views/Tokens.swift` | §11.2 palette light/dark adaptive + type scale + spacing/radii | — (mirrors Tokens.kt; parity goldens = M4) |
| `Views/Components.swift` | SongRowView (§11.5 states incl. per-row DownloadRing subscribing `subscribeProgress(key:)` alone — R7-P1), MiniRowView, ThumbImage, EqBars (Reduce-Motion gated), PlayPauseCircle, chips/banner, formatBytes | subscribeProgress |
| `Views/Screens.swift` | HomeScreen / SearchScreen / LibraryScreen / AlbumScreen / SettingsPanel — structure & copy follow the Android screens, with the counted divergences listed in §4 (Jump Back In count, the iOS-only prefetch row) | search bridge, favorites, downloads library, storage stats, clear-cache, bulk enqueue |
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
   `Build Kotlin framework` → `Sources` → `Frameworks` → **`Embed Frameworks`**
   (`project.pbxproj:156-161`).
3. **The embed phase is redundant, and the original justification for it was wrong.**
   `E104… Embed Frameworks` = PBXCopyFilesBuildPhase, `dstSubfolderSpec = 10` (Frameworks),
   embedding `shared.framework` from `BUILT_PRODUCTS_DIR` with
   `ATTRIBUTES = (CodeSignOnCopy, RemoveHeadersOnCopy)` (`project.pbxproj:21,25-35`).
   KGP 2.4.10's `embedAndSignAppleFrameworkForXcode` **already does the whole job**, and this
   was confirmed by reading the plugin's bytecode, not inferred:
   - `XcodeEnvironment.getBuiltProductsDir` reads `BUILT_PRODUCTS_DIR`;
   - `XcodeEnvironment.getEmbeddedFrameworksDir` reads `TARGET_BUILD_DIR` +
     `FRAMEWORKS_FOLDER_PATH` — i.e. the **app bundle's** `Frameworks` dir;
   - `XcodeEnvironment.getSign` reads `EXPANDED_CODE_SIGN_IDENTITY`;
   - `registerEmbedTask` builds an `EmbedAndSignTask` (a `FrameworkCopy` subclass) whose
     destination is that embedded dir and whose `@TaskAction` shells out to copy it there.
   So the framework is copied and codesigned into `iosApp.app/Frameworks` by the Gradle
   phase, and the copy phase then copies it a second time and signs it again.

   Two consequences, and they are the reason this is documented rather than deleted:
   - **"idempotent and intentional belt-and-braces" is not accurate.** `RemoveHeadersOnCopy`
     is in the Xcode phase's attribute list and is *not* something KGP's copy does, so the
     two operations are not the same operation applied twice — the second one changes the
     artifact. The ordering (`Build Kotlin framework` first) is what makes the second copy
     win, and the framework Kotlin emits does ship a `Headers/` dir, so this is live, not
     theoretical.
   - **The F1 rationale ("no embed phase ⇒ dyld crash risk") does not hold for KGP
     2.4.10**, because the Gradle phase embeds into the bundle whether or not a copy phase
     exists. The crash the phase was added to prevent was already prevented upstream.

   The safe resolution is to delete the `E104` phase and its `D711` build file, then confirm
   the simulator link and the device archive. It is left in place pending a run of
   `xcodebuild` on a device build, because removing it untested is the riskier of the two
   errors and the current state does link.
4. **Build-phase script hygiene (FIXED):** the stray `-Pdevice=$PLATFORM_NAME` Gradle flag
   was removed — that property never existed; the task auto-detects everything from env
   vars. Script remains (`project.pbxproj:213`):
   `./gradlew :shared:embedAndSignAppleFrameworkForXcode --no-configuration-cache || exit 1`
   Debug vs Release selection is automatic per configuration. Fail-fast on Gradle failure.
   The `--no-configuration-cache` flag is the **last** one in the tree and is the only
   *shipped* one (it is in the pbxproj, not just in this document).
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
8. **pbxproj sanity gate:** re-run after ANY hand edit of project.pbxproj. **It must be run
   from `iosApp/`, not from the repo root** — the checker resolves `PATH` as the relative
   string `iosApp.xcodeproj/project.pbxproj` (`Tools/check_pbxproj.py:130`), so the
   invocation previously written here crashed with `FileNotFoundError` on every run from
   the root. Correct:
   ```bash
   (cd iosApp && python3 Tools/check_pbxproj.py)
   ```
   Current output: `OK — 49 objects, graph consistent.` (object graph, ID uniqueness, phase
   ordering, embed attributes, Sources↔disk parity; mutation-tested). It is **not in any
   workflow** — nothing in `.github/workflows/` invokes it, so a bad hand edit that still
   parses will not be caught by CI. It also asserts nothing about `DEVELOPMENT_TEAM` or the
   missing icon, which is why both can drift without a red check.
9. **Runtime smoke list** (M1.5-style, single screen acceptable): play cached file end-to-end
   through orchestrator; confirm exactly ONE `Prepared` per prepare (D8 mirror) in logs;
   lock-screen controls; interruption/headphone-unplug pause; kill/resume snapshot;
   metered push flips prefetch quality (Network Link Conditioner or airplane-mode drill).

### 3A. Swift staff-review ledger — FIXED this round (static audit, no compiler)

| # | File | Finding | Fix |
|---|---|---|---|
| F1 | pbxproj | No embed phase ⇒ dyld crash risk | PBXCopyFilesBuildPhase added + ordered last (see §3.3). **Rationale later disproved:** KGP already embeds *and* signs into `Frameworks/`, so the phase is redundant, and its `RemoveHeadersOnCopy` is not idempotent with the Gradle copy. Checker written & mutation-tested, but **not wired into CI** |
| F2 | pbxproj | `-Pdevice=$PLATFORM_NAME` unknown Gradle property | Removed; task needs only Xcode env vars (KGP 2.4.10 bytecode verified) |
| F3 | DylanBridge (+NowPlayingController, DylanApp) | `shared.KotlinSubscription`, `shared.EngineEvent*` unprefixed module-qualified names — guaranteed compile errors; raw `SharedIntent*`/`SharedIosGraph.companion` spellings leaked outside bridge | All aliases now `Shared…`-prefixed; `KEngineEvent` alias added for `emit()`; NowPlayingController routes through `Intents.*`; `DylanGraph.create(baseDir:)` wrapper added; single-file invariant holds (grep-verified) |
| F4 | Tokens.swift | `DylanTokens.s6` used 6× but undefined — compile error | Added `s6 = 6` |
| F5 | Thumbnailer.swift | `inflight[key]` passed NSString key to `[String: Task]` dict / `removeValue(forKey: key as NSString)` — type errors | Key kept as String; explicit `as NSString` only at NSCache boundary |
| F6 | NativeAudioOutputImpl.swift | Block-based notification observers not removed if dealloc'd without `release()`; `CMTimeGetSeconds` NaN/∞ → UB Int64 conversion poisoning position lane | Idempotent `deinit { release() }`; non-finite/negative guard returning 0 |
| F7 | Screens.swift AlbumScreen | Shuffle button restarted album at track 0 mid-playback — pre-E2 Android behavior | E2 parity ported: anchor-current reshuffles upcoming only; else shuffle ON + random start index |
| F8 | Stores.swift HomeStore | Jump Back In used `historyRecent(10)` — pre-E5 Android behavior | "Fix" applied: `historyRecent(5)`. **Still not parity, and the cited Android behaviour is wrong** — Android's *Home* screen reads `history.recent(20)` (`HomeScreen.kt:81`); `recent(5)` is Android's *Library* screen (`LibraryScreen.kt:69`). So iOS Home now shows 5 where Android Home shows 20, and the code comment at `Stores.swift:331` names the wrong counterpart. Pick one number and make both Home screens read it. |

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
| D3 | ~~RootView fires `graph.onBackground()` on `.inactive` too~~ | **RESOLVED — and it was a bug, not a behavioral choice.** `RootView.swift:82` is now `if phase == .background`, with the reason recorded inline: `.inactive` also fires on a Control-Center pull, an incoming-call banner, a permission dialog and the app switcher, several times a session, and firing there closed the search WebSocket under the user's fingers and wrote a resume snapshot mid-gesture. It is now in parity with Android, whose only background trigger is `onStop` (`MainActivity.kt:56-59`). The `.inactive` arm must not be re-added. |
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
`(cd iosApp && python3 Tools/check_pbxproj.py)` (unchanged pbxproj should stay green).

## 4. Known deviations / accepted v1 gaps (documented, not bugs)

Real, verified divergence between the two UIs. Each row states which side is the reference,
because "parity" is not currently the answer for any of them.

- **Album detail opens as a full-screen cover** instead of Android's inline tab-content swap.
  (Deliberate and unchanged.)
- **Jump Back In count: iOS Home shows 5, Android Home shows 20** (`Stores.swift:332` vs
  `HomeScreen.kt:81`). The §3A F8 row records this as a "fix" toward parity; it is a
  divergence. See F8.
- **Downloads-tab empty state lacks the live "Downloading…" variant** (Android reads the
  whole progress map; the iOS store subscribes per-key only, R7-P1-faithful). Add a count
  subscription to IosGraph if wanted later.
- **Prefetch toggle exists only on iOS, and persists nothing on either side.** iOS reads
  `cfg.prefetchEnabled` through a computed property (`DylanApp.swift:38`, rendered at
  `Screens.swift:630`) and has no way to write it. Android's Settings screen has **no
  prefetch row at all** and never references `prefetchEnabled` anywhere in the module. So
  the toggle is an iOS-only, read-only row over a constant — the earlier claim that it was
  "visual parity with Android" was wrong on both halves.
- **No marquee for long NP titles** (Android uses basicMarquee; SwiftUI has no built-in — M4 polish).
- **Queue reordering uses move-up/down buttons** (same as current Android), not drag handles.
- **NowPlayingController keeps its own subscriptions** (decoupled from UI stores, like the Android
  service observing independently of Compose).
- **No app icon and no `Assets.xcassets`.** The pbxproj sets no
  `ASSETCATALOG_COMPILER_APPICON_NAME`, so a device build ships the default empty icon. There
  is a `bobdylan.svg.png` at the repo root that is not wired into any target.
- **`DEVELOPMENT_TEAM` is `""` in all three target configurations**
  (`project.pbxproj:419,459,500`). Simulator builds are unaffected; any device build or
  archive needs a team, and `ios-release.yml` supplies one via `APPLE_TEAM_ID` at the
  `xcodebuild` invocation rather than through the project.

## 5. Verification commands

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
cd /Users/abhakash/PersonalWS/dylan
./gradlew :shared:compileKotlinIosSimulatorArm64 :shared:compileKotlinIosArm64 --no-configuration-cache
```
