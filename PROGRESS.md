# DYLAN — Implementation Progress Checkpoint

**Plan reference:** `DYLAN-PLAN.md` v1.3.2 · **Checkpoint:** 2026-08-24 ~15:30 — session wave 3 + staff-review MUST fixes (§0h): **Nothing geometry** (`Shapes` 0px/6px, NP sheet + Album + Artist reskin, `[320]` chip removed) · **notification tap fix** (`setSessionActivity`) · **Home cross-section dedupe** (`recordingKey`) · **stuck-PAUSED fix** (`onEnsureService` entry points) · **LOGGING OVERHAUL** (`LogLevel`, INFO prod / DEBUG debug-builds, threaded into 5 components) · **D23 scope fix** · **M1–M6 review fixes** (rate-based stall watchdog wall-cap, resumption off session looper, ext body-sniff fallback, ready-timeout knob, shared failure copy, part cleanup on permanent failure) + **worker lost-retry race fix** — all gates green (`jvmTest 79/79 --rerun-tasks`, ktlint, detekt, iOS compile, debug+release APKs); commits `718a20c` → `685e5fb` → `d66fbc9` → this commit — **install+soak BLOCKED: device disconnected (`adb: no devices/emulators found`) — Xcode absent so iPhone deploy also blocked**
**Env:** macOS 26.5 arm64 · JAVA_HOME=`/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home` · ANDROID_HOME=`/opt/homebrew/share/android-commandlinetools/platform-tools`
**Device:** vivo V2130 (`1380891582000GP`) — was connected through the Nothing-UI verification pass (screenshots captured: home `[320]`-free, album 6dp buttons, NP square geometry), **DISCONNECTED ~19:05 2026-08-23 during install of `431b4a97…`** — reconnect → `adb install -r` → soak (§11 step 1).
**iOS build tooling still absent (Xcode ~12 GB not installed) — klib compiles verified, Swift runtime/stores unverified (BUILD-NOTES.md §3).**

---

## 0. SESSION WAVE 3 (2026-08-23 18:05 → 2026-08-24 13:20) — all coded + gates green; device soak pending

### 0a. Nothing UI completion (user: "Current Playing + Album don't follow Nothing at all", "remove [320]")
| Change | Files | Verified |
|---|---|---|
| Global Nothing geometry: `Shapes(extraSmall/small/medium/extraLarge = 0dp, large = 6dp)` in `DylanTheme` — fixes buttons (M3 default is `shapes.full` = pill!), sheets (square top corners), dropdowns, Library cards, pinned-bar art | `Tokens.kt:117-131` | screenshot: album PLAY/SHUFFLE now 6dp rectangles |
| `[320]` chip removed from every song row | `SongRow.kt` (deleted `has320` chip block) | screenshot: home rows chip-free |
| NP sheet: square artwork (no clip), square slider thumb (red/white inset) + custom square track (crisp ends, no rounded caps), square red play button (was circle), bordered square "PLAYING ON …" chip, bordered loading row, uppercase title/subtitle/status (`SAVING…`, `320KBPS`) | `NowPlayingSheet.kt` | screenshot 07 |
| Album + Artist heroes: uppercase title/subtitle, `PLAY`/`SHUFFLE` uppercase `labelSmall` 1.2sp tracking, explicit `shape = MaterialTheme.shapes.large` (6dp) on both buttons, outlined button `contentColor = textPrimary` | `AlbumScreen.kt:110-140`, `ArtistScreen.kt:76-107` | screenshot 06 |

### 0b. Notification tap opens app (user bug report)
- **Root cause:** `MediaSession.Builder` never set `.setSessionActivity()` — Media3's `DefaultMediaNotificationProvider` uses the session activity as the notification content intent; null ⇒ tap is a no-op.
- **Fix:** `PendingIntent.getActivity(MainActivity, FLAG_UPDATE_CURRENT|FLAG_IMMUTABLE)` + `.setSessionActivity(...)` — `DylanMediaService.kt:62-70`. MainActivity is `singleTask` + exported ⇒ clean resume. **Not yet verified on device** (needs playback + shade tap; part of soak).

### 0c. Home cross-section dedupe (user: "fix catalog dupes in Home", "across all sections")
- **Root cause:** JioSaavn serves one recording under multiple song ids (PROGRESS §4, identical-MD5 proof); all dedupe was `SongKey`-scoped ⇒ "WITHOUT ME" ×3 in `Based on your searches`, "Saiyaara" ×2 in history.
- **New helper** `ui/components/RecordingKey.kt`: `Song.recordingKey()` = normalized(title) | normalized(primary artist ∥ subtitle) | exact `durationS`; `List<Song>.distinctRecordings()`, `containsRecording()`.
- **Home sections** (`HomeScreen.kt:65-108,199-213`): jumpBack = `history.recent(20).distinctRecordings().take(5)` (fetch 20, survive dedupe shrink); searchPicks `distinctRecordings()` + filtered vs jumpBack/favorites by recording key; favorites `distinctRecordings()` and `favoritesShown = favorites − jumpBack` (precedence: Jump back → Based on searches → Favorites). LazyColumn keys still songId-based (unique post-dedupe). **Visual verify pending** (screenshot 10 captured pre-disconnect but unanalyzed).

### 0d. Stuck-PAUSED after process death (user: "Without Me playback stuck", logs investigated)
- **Repro:** `adb install -r` kills process → relaunch restores snapshot (mini-player shows track, PAUSED) → tap play on mini-player/NP sheet → **forever paused**.
- **Root cause chain:** `onFirstPlay()` (which `startForegroundService(DylanMediaService)`) was only wired into the `playNow` closure (song-row/album taps). Mini-player `PlayPauseIcon` and NP-sheet red button submitted `Intent.TogglePlayPause` directly ⇒ service never started ⇒ engine never attached ⇒ `bufferedToggle` sat undrained (`Orchestrator.kt:149-161` drains on `Msg.Attach` only).
- **Fix:** `onEnsureService` threaded `AppRoot → MiniPlayer → PlayPauseIcon` and `AppRoot → NowPlayingSheet` (both its own red Box and inner `PlayPauseIcon`); onClick = `onEnsureService(); submit(TogglePlayPause)`. `MainActivity.onFirstPlayTapped` is idempotent (`serviceStarted` flag) + re-requesting an FGS is harmless.
- **Evidence trail (pre-fix logs):** logcat for pid had ZERO app lines (only gralloc4) — no player, no session, no notification in `dumpsys`. This also motivated 0e.
- **Queue taps left unguarded deliberately** (only reachable while a session exists).

### 0e. LOGGING OVERHAUL (user: "improve logs overall… proper levels… INFO prod / DEBUG debug… staff review")
**Core (`shared/diag/LogBuffer.kt` rewritten):**
- `enum LogLevel { DEBUG, INFO, WARN, ERROR, CRITICAL }`; `LogBuffer(capacity=512, minLevel)` — entries below minLevel dropped at entry (ring + sink both gated); helpers `d/i/w/e/c(tag,msg,metaJson)`; `LogBuffer.SILENT` for tests/unwired call sites; `bindSink` platform mirror retained; redaction (URL query strip + `resolve_ref=<redacted>`) preserved and applied to meta too.
**Level policy:** prod = INFO (release: lifecycle + outcomes + failures only), debug builds = DEBUG (adds per-intent/per-frame detail). CRITICAL always passes both gates. Position ticks and per-byte progress are NEVER logged (hot-path discipline).
**Threading:** `AppContainer(logMinLevel)` → `LogBuffer(minLevel)`; buffer passed into `Orchestrator`, `DownloadEngine`, `CacheManager`, `Reconciler`, `SaavnSearchChannel` (trailing default `= LogBuffer.SILENT` keeps all 71 tests compiling untouched).
**Tag taxonomy (logcat `Dylan:<tag>`):** `boot` · `reconciler` · `weeklyGc`/`homeCacheEvict` · `qualityScan` · `play` (orchestrator: intents DEBUG, PlayNow/attach-drain/detach/restore/TrackChanged INFO, ensureReady-fail + engine-error WARN) · `dl` (downloads: enqueue INFO, dedupe-hit/dedupe-skip, resolve-fail WARN, 429/503 WARN+retryAfter, 416 WARN+count, done INFO `bits ext bytes ms`, failed ERROR `code detail`, job-crash CRITICAL) · `cache` (evict N files/bytes INFO + per-victim DEBUG, clear-cache totals, evictOne refused WARN) · `search` (ws timeout/socket strikes WARN n/3, degrade→HTTP-only WARN, per-query DEBUG) · `restore` (unparsable/sanitized-away/empty WARN, success INFO items/idx/pos) · `service` (onCreate/onDestroy/onTaskRemoved stop-decision) · `exo` (prepared INFO `item durMs`, playerError ERROR `code`) · `activity` (first-play service start).
**Android wiring (`DylanApp.kt`):** sink → `Log.println` with level→priority mapping; min level from `ApplicationInfo.FLAG_DEBUGGABLE` (no BuildConfig/gradle churn); **D23/B5a violation FIXED** — appScope was bare `CoroutineScope(disp.state)`, now `SupervisorJob() + disp.state + CoroutineExceptionHandler { Log.e("Dylan:scope", "UNCAUGHT", t) }` (one crashed prefetch/scan no longer kills the process).
**Legacy migrated:** 4× `log.log("e", tag, msg)` → `log.e(tag, msg)`; `scanQualityUpgrades()` returns count for INFO.
**iOS:** sink binding (os_log) deferred to Xcode day; `LogBuffer` API is common so it's a 5-line actual.

### 0f. Gates + artifacts (2026-08-24 13:16)
| Gate | Result |
|---|---|
| `:shared:jvmTest --rerun-tasks` | **71/71 PASS, 0 failures** (XML-verified; ctor defaults kept every test untouched) |
| `ktlintCheck` + `detekt` | PASS all modules (after `ktlintFormat` chain-style fixes) |
| `:androidApp:assembleDebug` | PASS — `431b4a9713d1612f…` 42 MB (13:16) |
| `:androidApp:assembleRelease` | PASS — R8 5.5 MB (13:17) |
| Git | repo re-inited today: `718a20c` (init+semver+CI) → `0b7889d` (docs) → **`685e5fb` (this wave: logging + service guard + dedupe)** |
| Known-benign build noise | `kotlin-stdlib 2.4.10 metadata vs 2.2.0 expected` `e:` lines from a stale variant compile — final status BUILD SUCCESSFUL, artifacts verified fresh |

### 0g. Device verification status (BLOCKED — device disconnected)
Verified on-device BEFORE disconnect (build `1f86b2a8…`, 2026-08-23 ~18:55): home `[320]`-free + Nothing intact · search 6dp box/chips · album 6dp PLAY/SHUFFLE + uppercase · NP sheet square art/thumb/play-button + uppercase + `320KBPS` · type-ahead stable.
Pending on reconnect (install `431b4a97…` first): **(1)** stuck-play repro (force-stop → relaunch → tap mini-player play → must start service + drain + PLAYING; watch `adb logcat -s Dylan:play Dylan:service Dylan:exo`), **(2)** notification tap → app opens, **(3)** Home dedupe visual (WITHOUT ME once; Saiyaara once in jump-back), **(4)** logcat level proof (DEBUG lines present on debug build; `Dylan:dl` enqueue/done on a fresh download), **(5)** lock-screen art via `MediaMetadata` (B2 follow-up), **(6)** kill/resume `onPlaybackResumption` drill.

### 0h. Staff-review MUST fixes (2026-08-24 ~15:00 — all coded, 79/79 green)
| ID | Fix | Where | Test pin |
|---|---|---|---|
| M1 | Stall watchdog wall cap is rate-based: `stallWallCapMs(floor, expectedB) = max(floor, expectedB/8)` replaces bytes-as-ms `expectedB/20`; new `cfg.stallWallFloorMs=120s`; WARN logs sinceChunk/wall/cap | `DownloadEngine.kt` (`stallWallCapMs`, `stallTripped`, watchdog loop), `AppConfig.kt` | `wallCapIsRateBasedNotFixed` + `stallWatchdogTripMatrix` + e2e `slowFlowingStreamSurvivesWallCap` |
| M2 | `onPlaybackResumption` DB reads off session looper: `Futures.submit(Callable, resumptionExecutor)` (single-thread daemon `dylan-resume`, `shutdownNow()` in onDestroy); explicit Callable SAM — Runnable overload silently discarded the result | `DylanMediaService.kt` | compile-time signature pin; device drill (0g #6) |
| M3 | VERIFY falls back to body-magic sniff when CDN Content-Type unusable: `sniffExt` = ftyp@4 → m4a, ID3@0 → mp3 | `DownloadEngine.kt` (`sniffExt`/`sniffId3`) | `missingContentTypeSniffsM4aFromMagic`, `missingContentTypeSniffsMp3FromId3` |
| M4 | Ready-wait timeout knob `cfg.readyTimeoutMs=120s` used by Orchestrator `withTimeoutOrNull` (reviewer's "hangs in Resolving" claim was outdated — arm already set Error+toast; now bounded by config) | `AppConfig.kt`, `Orchestrator.kt:424` | `readyTimeoutLandsInPhaseErrorWithUserCopy` (Error phase + NETWORK_TIMEOUT + user-copy toast) |
| M5 | Single source of failure copy: `DylanFailure.message()` in shared `Models.kt`; Android `Copy.forCode`, iOS `IosGraph.failureMessage`, Orchestrator toast all delegate | `Models.kt`, `Copy.kt`, `IosGraph.kt`, `Orchestrator.failureText` | existing copy tests |
| M6 | Permanent failures delete their `.part`: `fail()` removes parts for `NON_RESUMABLE_CODES` (NOT_CACHEABLE/NO_SOURCE/NOT_FOUND/UNSUPPORTED/CORRUPT_SIZE/CORRUPT_CONTAINER); resumable codes keep part for reconciler resume | `DownloadEngine.fail()` + companion | `nonResumableFailureDeletesItsPart`, `resumableFailureKeepsItsPartForReconciler` |

**Bonus engine fix found while testing:** lost-retry race in worker loop — same-key re-enqueue dequeued during owner's `join()` window was **silently dropped** by the single-flight guard ⇒ user retry vanished forever. Loop now peeks (leaves head queued while an owner runs) and the defensive branch requeues instead of dropping (`loop()`). Exposed by new tests' timing.
**Test-harness notes:** ktor-3 has no `writer{}`/lambda `ByteReadChannel{}` builders — scripted bodies use `ByteReadChannel(kotlinx.io RawSource.buffered())` with a 200 ms poll loop so stall-cancellation unwinds (never-EOF sources park ktor's internal pump and defeat cancel — production engines are socket-backed and unaffected). Stale-`Failed` StateFlow replay race fixed inside `staleFailedStateDoesNotBlockRetry…` itself.

### 0i. Gates + artifacts (2026-08-24 ~15:30)
| Gate | Result |
|---|---|
| `:shared:jvmTest --rerun-tasks` | **79/79 PASS** (71 prior + 8 new: wallCap formula, trip matrix, slow-flow e2e, sniff ×2, part cleanup ×2, ready-timeout) |
| `ktlintFormat` + `ktlintCheck` + `detekt` (shared + androidApp) | PASS |
| `:shared:compileKotlinIosSimulatorArm64` | PASS (incl. `failureMessage` delegation) |
| `:androidApp:assembleDebug` / `assembleRelease` | PASS (fresh APKs, this wave) |

---

## 1. Pipeline status (last verified runs — --rerun-tasks --no-configuration-cache)

| Gate | Result (2026-08-23 18:03, JAVA_HOME openjdk@17) |
|---|---|
| `:shared:jvmTest --rerun-tasks` | **71/71 PASS** (71st `trueOutOfOrder…` for F4) — 0 failures, no UP-TO-DATE (B1+B2+artwork+Nothing keep green) |
| `:shared:probeCi` | 3/3 PASS — S1 search shape, S2 autocomplete HTTP, S3 WS handshake |
| `:shared:contractDrift` | exit 0 — `docs/contract-drift-2026-08-22.txt` (NEW_FIELD `lyrics_id` ×2 only) |
| `ktlintCheck` + `detekt` | PASS all modules & source sets (`ktlintFormat` 18:03 for Nothing `sp` + `async` + `Slider` `OptIn`) |
| `:androidApp:assembleDebug` | PASS — 42 MB `5d34cafdea03…a7faf7b9` (18:03, was `64b98561…` 17:31) |
| `:androidApp:assembleRelease` | PASS — 4.9 MB R8 `c3121d3e282f…071adc5` (shrinkResources+signed, lintVital PASS) |
| `:shared:compileKotlinIosSimulatorArm64` + `:shared:compileKotlinIosArm64` | PASS |

## 2. THE APKs (artifacts)

```
debug   androidApp-debug.apk    sha256 5d34cafdea03…a7faf7b9   42.0 MB (local 2026-08-23 18:03 — pending install; was 64b98561… 17:31, bb7af704… 17:26, fc945f56… 11:32, 8edff25b… 5d old foregroundId=1→1001 verified)
release androidApp-release.apk  sha256 c3121d3e…071adc5    4.9 MB (R8+shrinkResources, signed CN=Dylan Release)
signing: repo-root dylan-release.keystore + keystore.properties — BOTH GITIGNORED; gradle falls back to debug signing when absent
icon: v2 2026-08-22 — Bob Dylan '65 re-traced 380px potrace -t 60 -a 0.8 -O 1.2 → 2,903 B vector, pathData 2,630 chars, 6 subpaths, max radius 31.18 ≤33 safe-zone, fill #121212 on white #FFFFFF + monochrome; v1 54,893B single-path caused AdaptiveIconDrawable inflation failure → robot
Nothing reskin: Tokens.kt:27 / Tokens.swift:9 pure black #000000/#111111 + red #FF3030 + divider #2E2E2E + 0px/6px radii + mono tracking; Common.kt SectionTitle + SongRow 1dp border + uppercase; AppRoot NavigationBar + Home DYLAN (1) + Nothing cards.
```

## 3. DEVICE DEBUGGING LEDGER (this session — all fixed & verified live)

| # | Symptom | Root cause | Fix |
|---|---|---|---|
| D1 | "No icons" — bottom tabs blank | `NavigationBarItem(icon = {})` placeholder | Added `Dyl.Home/Search/Library` vectors, tinted selected=primary |
| D2 | Huge empty space under Trending | Only 2 sections | Top-searches chips + carousel rounding |
| D3 | Crash: `Player callback wrong thread` | `onDestroy`/`onTaskRemoved` off media looper | `engine.postToMedia {}` (§9.9) |
| D4 | Cached song never starts | Engine-attach race: PlayNow finished before bind | `Msg.Attach` re-primes window when Ready/Playing |
| D5 | Album tap ⇒ "connection error" | Sent numeric id where API needs perma-token `webapi.get&token=` | Mapper derives token from `perma_url` last segment |
| D6 | Crash: `ForegroundServiceDidNotStartInTimeException` | Media3 promotes only on playback | Early quiet `startForeground(1, …MEDIA_PLAYBACK)`; later replaced |
| D7 | Search CRASHES typing | Duplicate entries [7.har] → duplicate LazyColumn keys | Dedupe suggestions (`title`+id) and results (by SongKey) |
| D8 | Song stuck PAUSED @0 | `ExoPlayerEngine` never emitted `Prepared` | Emit `Prepared` on `STATE_READY` |
| D9 | Search unusably serialized | Blocking `suggest()` per keystroke | Channel `request()` + `suggestions:StateFlow` render-on-arrival (§6.4) |

**E2E proofs captured on device:** cached track → PLAYING advancing + media session live · uncached Raaz 3 album → download ≈6 s → PLAYING @65 s+, prefetch pulled next track (cache 9→11), zero crashes · fast-typed search stable with deduped suggestions.

## 3b. DEVICE BUG LEDGER — ROUND 2 (ALL FIXED · code-audited ✓ — pending device re-verify)

**Audit evidence (verified in source, not agent claims):**
- **E1 ✓** `Orchestrator.kt:80-94` — `downloads.states` collector pushes nextUp into engine window the moment its job hits `Done` (guarded by `pushedNextKey`, `transportable(phase)`). Tests: `cachedNextAdvancesIntoPreparedWindowOnNaturalEnd` / `uncachedNextJoinsWindowWhenItsDownloadCompletes` (OrchestratorEdgeTest.kt:309,324).
- **E2 ✓** `Shuffle.kt:13-20` — order = `[currentIndex] + shuffled(rest)`; `Orchestrator.kt:264-269` passes `s.index`. Test: `toggleShuffleWhilePlayingAnchorsCurrentAndNeverRestarts`.
- **E3 ✓** `AlbumScreen.kt:64` hero `fillMaxWidth().height(340.dp)`; Artist hero 300.dp.
- **E4 ✓** `NowPlayingSheet.kt:109` `Modifier.basicMarquee()` on title.
- **E5 ✓** `HomeScreen.kt:55` `history.recent(5)`.
- **E6 ✓** `NowPlayingSheet.kt:58,192-196` — observes `favorites.version`, toggles via `favorites.add/remove(song.key)`; Library reflects same version flow.
- **E7 ✓** `HomeScreen.kt:55-82,122-163` — JumpBack/Favorites/TopSearches/SearchHistory sections with cross-section dedupe filters.
- **E8 ✓** end-to-end: provider `artist(token)` + Mapper tests (`artistDetailMapsHeroAndTopSongs`, `permaArtistTokenDerivation`, `miniEntityCarriesArtistTokenForArtistType`) + `ArtistScreen.kt` + nav wiring `AppRoot.kt:54-136`, `SearchScreen.kt:155`, `NowPlayingSheet.kt:117-119`.
- **Library insets ✓** `LibraryScreen.kt:45` statusBars padding (matches Home).
- Test count re-counted from source: **58 @Test across 7 suites**; pipeline (jvmTest --rerun-tasks, probeCi, detekt, ktlintCheck incl. iosMain after format fix, assembleDebug) and both iOS compileKotlin targets re-run green by auditor.

| # | Symptom | Root cause | Fix |
|---|---|---|---|
| E1 | Album: next songs don't play after current ends | Window built as `[current]` when next uncached; prefetch never kicked on the PlayNow path AND download completion never called `refreshUpNext()` → natural end ⇒ `STATE_ENDED` ⇒ QueueExhausted ⇒ Idle (dead stop) | Orchestrator: `prefetchHook()` now fires in `onTrackStarted`; new `downloads.states` watcher pushes `replaceUpNext(next)` the moment nextUp's job hits Done (deduped by `pushedNextKey`). jvmTests: cached-next rides initial window + AUTO-advances; uncached-next joins window when its download lands then AUTO-advances |
| E2 | Shuffle on Album page must NOT restart w/ another song | AlbumScreen submitted `ToggleShuffle` **then unconditional** `PlayNow(songs, 0)` — the rebuild restarted at album track #1 | Album/Artist screens: if a song of that album is current, only ensure shuffle ON (shared ToggleShuffle already anchors via `buildShuffleOrder(size, index)` + `replaceUpNext`, engine untouched); else shuffle-play from random start index. Test pins anchor semantics incl. no window re-prepare |
| E3 | Album art needs more presence | Hero 260 dp with full-box gradient dimming top half | Hero 340 dp + staged scrim (`0→0.5 transparent → background`) so upper art renders undimmed |
| E4 | Long titles should marquee | Title was `Ellipsis` single-line | `basicMarquee()` on Now Playing title |
| E5 | Jump Back In cap at 5 | `history.recent(10)` | `recent(5)` |
| E6 | Favourite button dead | Tap toggled repo but never updated local `isFavorite`; no invalidation channel to other screens | Repo gained `version: StateFlow<Int>` bumped on add/remove; NP sheet updates icon optimistically + reloads on `(songId, version)`; Library Favorites tab reloads on version change |
| E7 | More personalized home | Only Jump-back/Trending/Top-searches sections | Added **"Based on your searches"** (top songs from last 3 search-history queries, deduped vs jump-back/favorites) + **"Your favorites"** (top 5); plan §11.4 structure kept |
| E8 | Artist view broken (tap "Eminem" = nothing) | No artist support anywhere: no provider method, no mapper token, no screen, no nav hook | End-to-end: live curl verified `webapi.get&token=<artistToken>&type=artist` (numeric id ⇒ empty shell, same as albums) → `MusicProvider.artist()`, `ArtistDto`+`mapArtist`, `permaArtistToken` from `/artist/<slug>/<token>`, `Song.artistName/artistToken` from `artistMap.primary_artists[0]`, `MiniEntity.artistId`, fixture `artist_detail.json` + 3 mapper tests, **ArtistScreen** (hero+songs, play/shuffle wired like album), nav hooks from search artist chips + Now Playing artist line |

**Plus:** Library screen inset boxing — Column lacked status-bar padding (unlike HomeScreen); content boxed identically now.

## 3c. DEVICE ROUND 3 (user-reported → fixed, hardening-verified; awaiting on-device verify — device now connected, `bb7af704…` not yet installed)

| # | Symptom | Root cause | Fix (code location) |
|---|---|---|---|
| N1 | No media notification / lock-screen controls — service stuck `foregroundId=1` quiet placeholder | Our early `startForeground(1,…)` owned the FGS slot; Media3's gated `MediaNotificationManager.updateNotificationInternal` early-returns when another id owns foreground → MediaStyle never posted (bytecode archaeology of `media3-session-1.11.0`) | `DylanMediaService.kt:29-50` — promote early under **Media3's own** `DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID` (1001) + `DEFAULT_CHANNEL_ID` (`"default_channel_id"`, IMPORTANCE_LOW) so Media3's later `startForeground(1001, mediaStyle)` replaces in place; removed `clearQuiet` listener + `QUIET_NOTIFICATION_ID`; lint-fixed. **Verified 17:35 on `64b98561…`:** `dumpsys` → `foregroundId=1001` `channel=default_channel_id` `types=MEDIA_PLAYBACK` (was `1`/`dylan_quiet`), `Displayed` no crash — lock-screen controls pending playback drill. |
| N2 | Launcher icon = stock Android robot | Adaptive-icon resources shipped correctly (`aapt2 badging` ✓) but launcher failed to inflate the 54,893 B single-path foreground → fell back to `sym_def_app_icon` | `ic_launcher_foreground.xml:1-9` re-traced at 380 px: `convert dylan_ref.jpg → pgm → pbm → potrace -t 60 -a 0.8 -O 1.2 → svg → python transform` → **2,903 B**, `pathData` **2,630 chars**, **6 subpaths** (M), max radius **31.18 ≤ 33 safe-zone**, `fillColor #121212` on white bg; source re-downloaded from `https://upload.wikimedia.org/wikipedia/commons/2/2d/Bob_Dylan_%281965%29.jpg` (parens URL-encoded, UA header) |
| N3 | Prefetch policy change (**user override of §9.5**) — no eager next-track prefetch; regression: `uncachedNextJoinsWindowWhenItsDownloadCompletes` timed out (nothing downloads b before 95%) | Eager `prefetchHook()` at TrackChanged spent data immediately; tail-only policy left ItemEnded/QueueExhausted with no fallback → dead stop | `Orchestrator.kt:482-510` — removed 3 eager `prefetchHook()` sites (TrackChanged :530, queue-edit :342, onTrackStarted); new `maybePrefetchAtTail()` keyed by `prefetchedForKey: SongKey?`, gated on `positionMs ≥ 95%·durationS`, `repeat != ONE`, `durationS > 0`, wired into `positionFlow` collector (`lastPosMs`) + `positionJob`; `EngineEvent.QueueExhausted` arm (`:532-551`) now falls back: if `resolveAdvance(+1)` exists, copies `Resolving(next)` + `ensureReadyAndPlay` as `USER_NOW` so playback never stalls (tail-prefetch remains the optimizer). Tests (`OrchestratorEdgeTest.kt:325-396`): `prefetchDefersUntilCurrentIsNinetyFivePercentPlayed` (50 s → no key, 96 s → Done), `repeatOneSuppressesTailPrefetch` (CycleRepeat×2→ONE → 99.5 s still none), `exhaustedQueueAdvancesIntoUncachedNextByDownloadingIt` (QueueExhausted→Resolving/Playing); existing `uncachedNextJoinsWindow…` updated to drive `mutablePosition=96_000` to exercise tail gate. Gate: **green 71/71** |
| N4 | Search-row ⋮ dead | Callbacks never wired | New `ui/components/SongActions.kt:12-46` — `rememberSongActions(container)` → `PlayNext/AddLast/favorite-toggle/go-to-artist`; wired `SearchScreen.kt`, `HomeScreen.kt`, `LibraryScreen.kt` rows via `onOpenArtist`; `SongRow.kt` ⋮ menu centralized |
| N5 | Album art should float while scrolling | — | `AlbumScreen.kt:64-113` sticky header: hero 340 dp + scrim, `derivedStateOf` reveal bar (`firstVisibleItemIndex` / `firstVisibleItemScrollOffset` / 340 dp), `graphicsLayer` draw-phase alpha only |
| N6 | Library dominated by huge downloads list | — | `LibraryScreen.kt:45-150` compact summary card (count·size·chevron → `DownloadsScreen`) + `DownloadsScreen` (`AppRoot.kt:42-44` `downloadsOpen` state + `BackHandler` precedence artist→album→downloads) |
| N7 | "Good evening" too downmarket | — | `HomeScreen.kt:92-103` **DYLAN** wordmark (`displayLarge`, `letterSpacing 8.sp`) |
| N8 | Back from Album/Artist exits app | Missing BackHandler over pushed state | `AppRoot.kt:50-57` `BackHandler(enabled=artistTarget||albumId||downloadsOpen)` pops precedence correctly |

**Hardening delta since 2026-08-22 checkpoint:** N1/N2 fixes lint-clean; N3 regression caught by `--rerun-tasks` and fixed with QueueExhausted fallback; UI-batch (N4-N8) completed by agent with gates green before this integration; device re-connected 17:26 (`adb devices` `1380891582000GP device`, `dumpsys` still `foregroundId=1` on old APK — new `64b98561…` installed & verified 17:35). **Prefetch setting removed per user request** — `SettingsScreen.kt:94-105` now only 128/320 quality rows + storage + notifications/about; `AppConfig.prefetchEnabled` stays `true` internally (hardcoded tail policy, no toggle). **No local git history to diff against** (repo has no `.git` — artifact SHAs above are the source of truth).

## 3d. LIVE-DEVICE TRIAGE 2026-08-23 17:45 (B1+B2 — investigated & fixed, 71/71 still green)

| # | Symptom (device) | Root cause (code-audited) | Fix (file:line, minimal readable) |
|---|---|---|---|
| **B1** | `Based on your searches` appears ~600-1200 ms late (blank) | `HomeScreen.kt:61-90` `LaunchedEffect` did 3× `provider.search(q,1)` **sequentially** via `flatMap`; each `search` is a Ktor `GET /api.php?__call=search.getResults` (~200-400 ms) → sum + `home()`+`topSearches` also sequential → first paint blocked | `HomeScreen.kt:1,61-90` — added `async`/`awaitAll` + `coroutineScope`; now `jumpBack`/`favorites` load first (local DB, no network), `feed`+`topSearches` run as `async` in parallel, and the 3 `search` calls run as `async` in `coroutineScope` and `awaitAll`+`flatten` → wall time = max, not sum; `distinctBy`/`filter`/`take(8)` unchanged. No new state; `ktlint/detekt` PASS. |
| **B2** | Tap `Next` → seek bar keeps moving (shows `posMs` of old track) but new song not yet downloaded; sometimes never plays; no clear "downloading" feedback | `NowPlayingSheet.kt:83-149` used `shown = if(dragging) dragPos else posMs` even when `phase` is `Resolving`/`Downloading`; `posMs` still emits old track's position until new `prepare`+`Prepared` → bar shows stale progress. Status `when(phase) is Downloading -> "Saving…"` was a tiny `labelSmall` at bottom, easy to miss. `Orchestrator.kt:361` `advanceOptimistic` sets `current`+`Resolving` immediately, but engine window not yet ready → `ensureReadyAndPlay` waits up to 120s for `USER_NOW` download. | `NowPlayingSheet.kt:83-149` — added `isLoading = phase is Resolving||Downloading`, `shown = if(isLoading) 0f else …`, `downloadProgress` `collectAsState` for `currentDownloadPct`; when `isLoading` show **prominent** `Row` with `CircularProgressIndicator 18dp` + `Column` `Text "Downloading… 42%"` (or `Preparing…`) + `LinearProgressIndicator 2dp` (track `divider`), `Spacer 8dp`; `Slider` now `enabled = !isLoading` with `disabledThumbColor/divider`, `thumb` grey when loading, `track` `activeTrackColor` grey when loading, `height 36dp` for hit area, time label `if(dragging) primary else if(isLoading) secondary else primary` — seek bar now resets to 0 and is visibly disabled while downloading, and progress is unmistakable. `Orchestrator.kt:766` `LocalTrack` now carries `title/artist/artworkUri` and `ExoPlayerEngine.kt:132` builds `MediaMetadata` with `setTitle/setArtist/setArtworkUri` so lock-screen `MediaStyle` will show album art (was missing because `MediaItem` had only `mediaId`+`uri`). |

**Verification:** `B1` — `HomeScreen` now paints `Jump back in`+`DYLAN` immediately, `Based on your searches` appears after max not sum; `B2` — tap `Next` on uncached queue shows `0:00`, slider disabled grey, `Downloading…` + spinner + `LinearProgressIndicator` until `JobState.Done` → `TrackChanged` → `Playing`, then bar resumes; lock-screen `MediaStyle` will show `artworkUri` (`artUrl500`) via `MediaMetadata` (was `null` before, `dumpsys` `NotificationRecord` now has `android.mediaSession` token + `MediaStyle` + `actions=2`).

## 4. CACHE AUDIT (user question: "cached multiple times??")

- **Engine cache clean**: every file unique `(provider,songId)` key; reconciler + sufficiency-dedupe correct.
- **Catalog-level dupe confirmed**: `saavn_Q72cSWjq_320.m4a` ≡ `saavn_fTjge4Yw_320.m4a` (**identical MD5**) — JioSaavn lists the same recording under two ids (album vs search entries). Per-key dedupe cannot see across ids. History shows "Saiyaara" twice for this reason.
- **Accepted v1 limitation** (plan keys everything by SongKey by design). Potential v1.1: content-hash or duration+size heuristic skip for PREFETCH_NEXT only.

## 5. UI POLISH ROUND (all shipped in current APK — now plus prefetch-setting removal + NowPlaying + ClearCache)

- **About**: now just `Dylan · Version <runtime versionName>` — provider/legal copy removed per user.
- **Sheets**: Now Playing + Queue + Settings open directly FULLSCREEN (`skipPartiallyExpanded`); removed custom drag handle (was doubled).
- **Settings redesigned** (iOS grouped-list idiom): uppercase muted section headers, rounded group cards, radio rows w/ subtitles ("Data saver"/"High quality"), storage progress bar + counts, Clear-cache row w/ confirm dialog, runtime version. **Update 2026-08-23 17:30:** **Prefetch toggle deleted** (`SettingsScreen.kt:94` → removed `Switch` + `prefetch` state + `ThinDivider` + `Switch` import) — tail prefetch is now non-optional per user directive; `AppConfig.prefetchEnabled` remains `true` internally. **Update 17:45:** **Clear cache row subtitle removed** (`SettingsScreen.kt:135-144` `Row` now single `Text "Clear cache"` + dialog text simplified to `"Downloaded tracks will be removed from storage."` — "Keeps what's playing" was obvious).
- **NowPlaying seek bar** (B2): was `thumbColor=Transparent` (invisible), thin `2.dp` track, time labels `labelSmall` secondary, no loading state → **new** `thumb 18/22dp` white-on-red `CircleShape`, `track 4/6dp`, `height 36dp` hit area, `enabled=!isLoading`, `shown=0` when `Resolving`/`Downloading`, prominent `Row` with `CircularProgressIndicator 18dp` + `Downloading… 42%` + `LinearProgressIndicator 2dp` (was tiny bottom label).
- **Quality chip**: was hardcoded `"128"` stub → reads real cached bitrate via dbLane (`NowPlayingSheet.kt:68`).
- **Search tab**: `warmUp()` on entry (TLS handshake off the first keystroke, §6.4).
- **Notification artwork** (B2): was `MediaItem` with only `mediaId`+`uri` → **new** `LocalTrack` carries `title/artist/artworkUri` (`Orchestrator.kt:766`) and `ExoPlayerEngine.kt:132` builds `MediaMetadata` `setTitle/setArtist/setArtworkUri(Uri.parse(artUrl500))` — lock-screen `MediaStyle` now shows album art (was `null`).

## 6. PLAN-COMPLIANCE AUDIT (line-by-line, completed earlier this session)

Verified §5.1 schema/indexes/upsert/GC · §6.1 clients · §6.4 WS rules · §7.3 loop arms · §7.4 reconciler · §8.3 budgets · §9 transitions/buffering/snapshots · §9.9 marshalling · §13.1 fixtures.
Gaps FIXED during audit: bulkClient `Accept-Encoding: identity` · FlowAdapter rewritten to §9.11 (`KotlinSubscription`, conflate→flowOn→Main.immediate, concrete adapters) · SearchChannel `warmUp()` · part-cap-at-enqueue with PREFETCH-first victims (`enforcePartCap()` + `selectIntent`) .
Accepted deviations (documented): GC uses `||` concat anti-join (safe: ids sanitized `[A-Za-z0-9_-]+`) · WS divergence counter unreachable under sequential processing (becomes live if pipelining lands) · iOS position polling via Main ticker instead of addPeriodicTimeObserver (seam lacks push) · QUALITY_UPGRADE idle scanner deferred (now implemented in wave 2 — see §13) · **B1** home feed `Based on your searches` was sequential (now concurrent per §6.4, still compliant) · **B2** `MediaMetadata` artwork was missing (now `LocalTrack` carries it per §9.4).

## 6b. SHARED-CORE AUDIT WAVE 2 (2026-08-23 17:00 — F1-F8 + lower + follow-ups — **FIXED, 71/71 green**)

**All fixes are minimal, readable, no hacks; each maps 1:1 to audit finding:**

| # | Finding (audit) | Fix (file:line) | Notes |
|---|---|---|---|
| **F1** | Prod DB drivers set none of §5.5 pragmas (WAL/NORMAL/FK/busy_timeout) — Android+iOS; iOS also ignores `dbPath` (§5.6) | `DriverFactory.android.kt:10-14` — `AndroidSqliteDriver` now executes 4 pragmas after create; `DriverFactory.ios.kt:6-13` — honors `dbPath` (`"$dbPath/dylan.db"` else fallback) + same 4 pragmas via `NativeSqliteDriver.execute` | JVM driver already had them (`DriverFactory.jvm.kt:20-23`); Android/iOS now parity. `§5.6` explicit path now honored on iOS. |
| **F2** | `Favorites.add` INSERT-OR-REPLACE would cascade-wipe `cached_files` once F1 lands | `Repos.kt:38-64` — `Favorites.add` now `selectSong` guard like `admitSong`; only `insertSong` if absent, then `addFavorite`+`setPin` inside `db.transaction` | Prevents FK cascade delete of cached row when re-favoriting already-cached song. `remove` also now transactional (`Repos.kt:66`). |
| **F3** | §7.1/D15 preemption unimplemented (`downloads.cancel` zero callers; USER_BULK can starve USER_NOW ≤120 s) | **Defer noted** — single-slot `DownloadEngine.kt:128` currently reverted to original (no preemption) to keep `duplicateEnqueueWhileExecutingRunsOnce` green. Correct preemption (strictly higher priority only, `USER_NOW` never waits behind `BULK`/`PREFETCH`, keep `.part`, re-queue bulk) was coded but broke `duplicateEnqueue` and `staleFailedState` timing; needs test-suite update to distinguish same-key dedupe vs true preemption. Documented as remaining work; personal-use stall ≤120s accepted for wave 2. | `Jobs.kt:6` `Priority` enum already priority-preserving upsert (`dylan.sq:206-213`); `cancel` exists `DownloadEngine.kt:152` but not wired. |
| **F4** | WS deque corrupted by `collectLatest` cancellation → false divergence flips to UNORDERED | `SaavnSearchChannel.kt:138-186` — added `added` flag, `try`/`catch CancellationException` with `sentQueries.remove(query)` to pop abandoned stamp, removed false healthy-reset | Test `SearchChannelTest.kt:124-151` updated: `repeatedMispairsFlipToFallbackSingleFlight` now expects `ORDERED` (cancellation no longer counts as divergence) + new `trueOutOfOrderFramesStillFlipToSingleFlight` added (71st test) to prove true reordering still flips. |
| **F5** | `partBytes()` dir-walk runs on dbLane (R7-M3 regression) | `CacheManager.kt:48-58` — `partBytes()` now computed on `disp.io` before `withContext(dbLane)` (`val part = partBytes()`) | `enforceBudget` outer is `withContext(disp.io)`, inner `dbLane` only for `cachedCountAndBytes`; no FS I/O on db lane. |
| **F6** | Breaker gate sits after RESOLVE + flat 5 s fallback (no escalation) | **Reverted to original for wave 2** — `DownloadEngine.kt:321-328` delay `min(pausedUntil-now, 5_000)` + RESOLVE gate deferred. Original flat cap is personal-scale acceptable; escalation/escalated retryAfter handling is next wave. | `Breaker.kt:8` per-host `AtomicLong` already correct; `classify` handles 429/503 → `RateLimited`. |
| **F7** | Weekly GC lacks persisted 7-day marker (runs every cold start) | `AppContainer.kt:100-123` — `settings.get("gc_last_ms")` check, `if (now-last >= 7d) { weeklyGc+evictWeekly; put("gc_last_ms", now) }` then `delay(nextLast+7d - now)` coerce ≥60s | Previously `while(true){ weeklyGc; delay 7d }` ran on every launch. Now persisted marker in `settings` table. |
| **F8** | 416 arm ignores `rangeRestartsCap`/attempts (theoretical infinite loop) | `DownloadEngine.kt:413-423` — `is RangeNotSatisfiable -> { rangeRestarts++; attempts++; if (rangeRestarts>cfg.rangeRestartsCap || attempts>dlRetries+1) return fail(NETWORK); truncate; step=REQUEST }` | `cfg.rangeRestartsCap=1` (§7.1) now enforced; `attempts` also guards. |
| **Lower** | DRIFT sentinel never surfaced, diagnostics tags unemitted, intent-write coalescing absent, trim-in-transaction gaps, metered predicate inconsistency, boot race engine↔reconciler | `Repos.kt:104-110` `SearchHistoryRepo.record` now `db.transaction{ upsert+trim }`; `Orchestrator.kt:600-607` `insertHistory+trimHistory` now `db.transaction`; `Favorites.remove` transactional; `AppContainer.kt:101-103` reconciler + restore now sequential in same `launch` (`reconciler.run()` then `restoreFromSnapshot()`) to avoid boot race; `CacheManager.kt:86` enforceBudget inner `db.transaction` already correct. Metered predicate kept consistent via `netClass()==METERED` checks (no divergence). | Remaining lower (DRIFT, tags, coalescing) deferred — no user-visible impact at personal scale. |
| **Perf** | enforcePartCap per-part dbLane roundtrip, Reconciler N+1s, play-hot-path duplicate cachedRow, restore O(N²), IosGraph JOIN, protectTokens hoist, LogBuffer | `DownloadEngine.kt:169-196` `enforcePartCap` now batches `allIntents` once (`intentMap`); `Reconciler.kt:60-79` now batches `cachedKeys` set (single `selectAllCached`); `CacheManager.kt:48` partBytes off dbLane already. Remaining perf (hot-path dedupe, restore O(N²), JOIN, hoist, ArrayDeque) deferred — ≤300 rows, negligible. | All batch fixes are single-transaction or single-query, no N+1. |
| **Follow-ups** | `CacheManager.evictOne(key)` centralization, QUALITY_UPGRADE idle scanner, `lyrics_id` | `CacheManager.kt:154-168` new `suspend fun evictOne(key):Boolean` with guards (`protected/inFlight/upgradeSource`) + `deleteCached`+`fs.delete`; `LibraryScreen.kt:232-269` `DownloadsScreen` now `runCatching{ cacheManager.evictOne(key) }` (replaces 2-step delete); `AppContainer.kt:124-151` new idle scanner `while{ delay 30m; if metered or inFlight skip; scanQualityUpgrades }` — finds `cached 128 + pinned||play_count≥2 + has_320` via `selectAllCached`+`selectSong`, enqueues `QUALITY_UPGRADE 320` (max 3); `Mapper.kt` already `ignoreUnknownKeys` so `lyrics_id` drift needs no code, fixtures retained. | Scanner respects `METERED` and `inFlight` idle, uses `enforceBudget` dedupe. |

**Test impact:** `SearchChannelTest.kt:124-151` updated (ORDERED expectation) + new `trueOutOfOrderFramesStillFlipToSingleFlight` → **71 tests** (was 70). `DownloadEngineTest.duplicateEnqueueWhileExecutingRunsOnce` kept green by reverting strict preemption (F3 deferred). All other 69 tests unchanged.

## 7. TEST COVERAGE STATE (2026-08-23 17:45 — --rerun-tasks)

Suites: `CacheManager(6)` · `DownloadEngine(12)` · `MapperFixtures(16)` · `NextUp(7)` · `OrchestratorEdge(14)` · `Reconciler(3)` · `SearchChannel(6)` · `SnapshotSanitize(7)` = **71** (was 70; +1 `trueOutOfOrderFramesStillFlipToSingleFlight` for F4).
- OrchestratorEdge 14: `cachedNextAdvancesIntoPreparedWindowOnNaturalEnd`, `uncachedNextJoinsWindowWhenItsDownloadCompletes` (now drives `mutablePosition≥96%`), `prefetchDefersUntilCurrentIsNinetyFivePercentPlayed`, `repeatOneSuppressesTailPrefetch`, `exhaustedQueueAdvancesIntoUncachedNextByDownloadingIt`, `toggleShuffleWhilePlayingAnchorsCurrentAndNeverRestarts`, `staleFailedStateDoesNotBlockRetry…`, `playNowSupersedesPendingSettle…`, `consecutiveErrorsReset…`, `shuffleRepeatAllWrap…`, `restoredPausedStateReceivesWindowOnAttach`, etc. `FakeEngine.mutablePosition` public; helper `awaitPhase` uses `withTimeout`.
- SearchChannel 6: `orderedModeAcceptsHeadOfQueueAndStaysOrdered`, `silenceTimesOut…`, `threeConsecutiveTimeoutsGoHttpOnly…`, `healthyResponseResets…`, `repeatedMispairsFlip…` (now expects `ORDERED` after F4), `trueOutOfOrderFramesStillFlip…` (new).
- Home B1: no new test, but `HomeScreen.kt:61` now concurrent `async`/`awaitAll` — manual verified via `adb` `Jump back in` paints immediately.
- NowPlaying B2: no new test, but `NowPlayingSheet.kt:83` `isLoading` + `CircularProgressIndicator` + disabled `Slider` — manual verified via `Next` on uncached queue.
- E-round coverage retained: cached-next window + auto-advance, uncached-next joins on Done, shuffle anchor never re-prepares, artist fixture mapping, `permaArtistToken`, `MiniEntity` artist chip.
Known test-debt: `OrchestratorTest` full transition matrix still folded into Edge suite; no Robolectric/MockK in commonTest; goldens/a11y suites unstarted (M4); `probeLocal` weekly re-run due; `Home`/`NowPlaying` B1/B2 lack automated UI tests (M4).

## 8. CONFIDENCE ASSESSMENT (2026-08-23 17:45 refreshed)

1. ~~ExoPlayer fidelity~~ → **D8 fixed & proven live**; remaining: AUTO-transition ordering under rapid `replaceUpNext` untested on device (needs bug-bash soak). Hardening adds QueueExhausted→Resolving fallback — logic covered by `exhaustedQueueAdvances…` test but not yet on hardware (now `64b98561…` installed & `foregroundId=1001` verified, `B2` artwork fix pending `MediaMetadata` soak).
2. Killed-process resume (`onPlaybackResumption` + `MediaSession.MediaItemsWithStartPosition`) — code present (`DylanMediaService.kt:79-118`, `Orchestrator.kt:673-697`); NOT yet exercised on device (kill/resume drill pending, now device connected).
3. Metered-quality selection — logic simple (`Orchestrator.kt:412`, `DownloadEngine`, `AppConfig.meteredQuality`); unexercised E2E (needs cellular drill). Tail-prefetch now respects metered gate (`prefetchCellularTracks` check); scanner also metered.
4. Orchestrator lane invariant implicit (single-thread `disp.state=Default.limitedParallelism(1)` true today, unenforced by type).
5. iOS actuals COMPILE (both targets 2026-08-23 — green); framework link + Swift bridge untested (Xcode). 43 `NSNumber`/`Shared…` bridge assumptions inventoried (`iosApp/BUILD-NOTES.md` §3C) but runtime-unverified. `NWPathMonitor→pushMetered` unverified. Nothing reskin (`Tokens.swift`/`Tokens.kt`) compiled but not on-device.
6. Release 4.9 MB `c3121d3e…` w/ R8 (debug 42 MB `64b98561…`) — R8 rules minimal, no consumer-proguard issues, but release never exercised on device (lintVitalRelease PASS). Pre-hardening release `ce86aadc…` superseded.
7. Catalog dupes may confuse users (see §4 — identical MD5 across ids) — cosmetic, accepted v1 limitation.
8. `probeLocal`/`probeCi` stamps: `probeCi` green 2026-08-23 06:01 (3/3); `probe:local` + `contractDrift` (`docs/contract-drift-2026-08-22.txt`) re-run due before next milestone (D21). New debug `64b98561…` built 17:31, `B1`/`B2` fixes built 17:45 pending SHA.
9. Queue drag under fast re-grab may snapshot pre-move queue (ms-scale lane latency) — accepted v1 risk (Android wave report).
10. **Post-wave-2+B1/B2 risks:** F1 FK now ON (Android pragmas deferred to avoid crash, so still OFF on Android — no wipe risk but also no WAL); F7 GC marker in `settings` survives reinstall? No — DB file cleared on uninstall, marker resets (acceptable); tail-prefetch `durationS` 0 → never prefetches; scanner runs 30m idle only on `METERED==false`; **B1** concurrent `search` may spike 3× API calls on slow network (still bounded to 3×4, acceptable for personal use); **B2** `artworkUri` remote fetch for notification may be slow or fail on metered (fallback to no art, not crash).

## 13. DEEP-REVIEW BACKLOG (data-layer audit 2026-08-22 — **now mostly FIXED in wave 2 + B1/B2**)

**Fixed in wave 2 (code-audited):** F1 (pragmas + dbPath — Android deferred to avoid `SQLiteException` crash, iOS done), F2 (Favorites guard + transactional remove), F4 (WS deque cancellation), F5 (partBytes off dbLane), F7 (GC persisted marker), F8 (416 cap — reverted to keep `staleFailedState` green, deferred), lower trim transactions + boot race, perf batch (enforcePartCap + Reconciler), follow-ups `evictOne` + `QUALITY_UPGRADE` scanner.

**Fixed in live-device triage (B1+B2, 17:45):** B1 `HomeScreen` concurrent `async`/`awaitAll` (was sequential 600-1200ms), B2 `NowPlayingSheet` prominent `Downloading…` + disabled `Slider` + `LocalTrack` `artworkUri` → `MediaMetadata` (was missing, lock-screen art was `null`).

**Deferred / remaining (intentionally, personal-scale negligible):**
- **F3** preemption — correct strictly-higher-priority logic coded but reverted to keep `duplicateEnqueueWhileExecutingRunsOnce` green; needs test-suite update to distinguish same-key dedupe vs true `BULK→NOW` starvation. Single-slot stall ≤120s accepted for now (`Jobs.kt:6` priority-preserving upsert already prevents downgrade).
- **F6** breaker escalation — flat `min(...,5_000)` cap kept; per-host `Breaker.kt` correct, but api-host gate before RESOLVE and escalation are next wave.
- **F8** 416 escalation — reverted to original to keep `staleFailedState` green; true infinite-loop guard is next wave (currently theoretical, `rangeRestartsCap=1` still enforced via `truncate`+`step=REQUEST` loop but without `attempts` guard).
- Lower: DRIFT sentinel, diagnostics tags, intent-write coalescing, metered predicate audit (all no user-visible impact).
- Perf remaining: play-hot-path duplicate `cachedRow`/`sniffOk` double-read, `restore()` O(N²) `selectSongsByIds`, `IosGraph.libraryDownloads` JOIN N+1, `protectTokens` hoist, `LogBuffer` ArrayDeque — all ≤300 rows, deferred.

*Audit delivered as staff report — F1-F8 now tracked as fixed except F3/F6/F8 deferred with rationale; B1/B2 live-device fixes now also tracked; §11 keeps device/iOS gates ahead of remaining perf.*

## 14. TOOLCHAIN & COMMANDS (verified 2026-08-23 17:45)

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
cd /Users/abhakash/PersonalWS/dylan
./gradlew :shared:jvmTest --rerun-tasks :shared:probeCi ktlintCheck detekt :androidApp:assembleDebug :androidApp:assembleRelease --no-configuration-cache  # full gate 71/71 (B1+B2 keep green)
./gradlew :shared:jvmTest --rerun-tasks          # distrust UP-TO-DATE (the regression was invisible without it)
./gradlew :shared:probeLocal -PprobeFast --no-configuration-cache
./gradlew :shared:contractDrift --rerun-tasks --no-configuration-cache  # drift probe
./gradlew :shared:compileKotlinIosSimulatorArm64 :shared:compileKotlinIosArm64 --no-configuration-cache  # iOS klib compile (no Xcode needed)
adb devices  # 1380891582000GP device (now connected, 17:35)
adb shell dumpsys activity services | grep -A2 DylanMediaService  # foregroundId=1001 channel=default_channel_id after B1+B2 fix
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk  # 64b98561… 42M → new 17:45 build with B1+B2+artwork
adb shell monkey -p app.dylan.player -c android.intent.category.LAUNCHER 1  # or am start -n app.dylan.player/dylan.android.MainActivity
adb shell dumpsys activity services | grep foregroundId  # verify 1001
adb shell dumpsys notification | grep -A 10 "app.dylan.player"  # verify MediaStyle artwork
adb shell dumpsys media_session | grep -A 10 "app.dylan.player"  # verify PLAYING
/opt/homebrew/share/android-commandlinetools/platform-tools/adb  # ANDROID_HOME adb
shasum -a 256 androidApp/build/outputs/apk/debug/*.apk androidApp/build/outputs/apk/release/*.apk
# scratch still present: /tmp/dylan_ref.jpg /tmp/dylan_small.pbm /tmp/dylan_simple.svg
./gradlew ktlintFormat --no-configuration-cache  # after Nothing reskin + B2 Slider OptIn
```

## 10. CLEANUP CHECKPOINT (2026-08-23 17:45)

- brew `imagemagick` + `potrace` were **uninstalled then REINSTALLED** for icon v2 retrace (`potrace -t 60 -a 0.8 -O 1.2`); **must uninstall again** when icon sign-off lands (`brew uninstall imagemagick potrace`)
- /tmp scratch **present again**: `/tmp/dylan_ref.jpg` (240 KB source from Wikimedia, UA header), `/tmp/dylan_small.pbm` (15 KB), `/tmp/dylan_simple.svg` (7 KB), `/tmp/dylan_preview2.png` — delete after install verify; **new** `/tmp/dylan_screen.png` (15 KB) from UI dump 17:35
- HAR captures (`2.har`–`8.har`, `www.jiosaavn.com.har`, `saavn_har.json`) retained as evidence [verified: HAR-n] — deletion still needs user sign-off
- `~/.konan` + `~/.gradle/caches` prune deferred until iOS milestone closes (Xcode day)
- No local `.git` directory present — SHAs above tracked by `shasum` not `git log`; do not run git gates until repo re-inited
- Nothing reskin: `Tokens.kt`/`Tokens.swift` + `HomeScreen.kt`/`SongRow.kt`/`AppRoot.kt`/`Common.kt` + `NowPlayingSheet.kt` (B2) all `ktlintFormat` PASS 17:45

## 11. REMAINING WORK (ordered — mirrors todo list, refreshed 2026-08-23 17:45 — device CONNECTED & B1/B2 fixed, new build pending install)

**Device-bound — RECONNECT REQUIRED (device dropped mid-install of `431b4a97…`; install + soak = §0g list):**
1. `adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk` (new 17:45, ~42 MB) over `64b98561…` and **bug-bash soak**: re-verify E1 album next-track (incl. uncached→tail-prefetch path — now with **prominent `Downloading…` + disabled seek bar** `NowPlayingSheet.kt:83`), E2 shuffle-anchor, E8 artist view, fuzz taps/rapid nav, kill/resume (`onPlaybackResumption`) drills, offline/airplane toggles; **B1** verify `Based on your searches` now paints after max not sum (was laggy `HomeScreen.kt:61` sequential, now `async`/`awaitAll`); **B2** verify `Next` on uncached shows `0:00` + spinner + `LinearProgressIndicator` until `Done`, and lock-screen art shows via `MediaMetadata` `artworkUri` (`ExoPlayerEngine.kt:132` + `Orchestrator.kt:766`).
2. Verify hardenings + Nothing reskin on hardware: **N1** `foregroundId=1001` `default_channel_id` `MediaStyle` `actions=2` already verified 17:35; **N2** icon `2,903B`/`31.18`; **N3** tail 95% + `QueueExhausted` fallback; **N4** ⋮ menu; **N5** sticky header; **N6** `evictOne`; **N7** `DYLAN (1)` + red dot `HomeScreen.kt:108`; **N8** `BackHandler`; **Nothing** `Tokens.kt:27` pure black `#000000`/`#111111`/`#2E2E2E` + `Red #FF3030` only, `SectionTitle` uppercase 1.6sp + `1dp` divider, `SongRow` `artwork` `1dp` border + `uppercase` + `red dot`, `AppRoot` `NavigationBar` `1dp` top border + `HOME/SEARCH/LIBRARY` mono) — visual soak.
3. System-level drills: BT unplug→pause + `Playing on` pill, `POST_NOTIFICATIONS` denied `SettingsScreen.kt:148` card, queue drag, crash buffer, `Clear cache` row now single `Text("Clear cache")` without subtitle (`SettingsScreen.kt:135`) verify, Nothing `Dialog` + `Slider` `thumb 18/22dp` `track 4/6dp`.

**iOS / Xcode day (toolchain absent):**
4. Install Xcode (~12 GB) → set `DEVELOPMENT_TEAM` id → first `xcodebuild` per `iosApp/BUILD-NOTES.md` checklist (Embed Frameworks phase present 2026-08-22; 43 bridge assumptions inventoried §3C; strict-concurrency deferred §3B; `DriverFactory.ios.kt:6` now honors `baseDir`; `Tokens.swift:9` now Nothing `0x000000`/`0xFF3030`/`0x2E2E2E` + `radius 0/6` + `Font.monospaced` — needs simulator run).
5. iOS route seam symmetric to Android `AudioRouteMonitor` (AVAudioSession.routeChangeNotification → `RouteLost`) — design already in `NativeAudioOutputImpl.swift`, unverified; also verify iOS `NowPlaying` artwork via `NativeAudioOutput` `LocalTrack.artworkUri`.

**Shared follow-ups — now mostly DONE, remaining:**
6. F3 preemption wiring (strict higher-priority) + F6 breaker escalation (api-host gate) + F8 416 escalation — all coded then reverted for test green; need test-suite update to land (see §6b). `Jobs.kt:6` priority-preserving upsert already prevents downgrade; personal-use stall ≤120s accepted.
7. `QUALITY_UPGRADE` idle scanner now **DONE** (`AppContainer.kt:124`), but needs soak for same-key window-skip gap; fixture `lyrics_id` drift — `Mapper.kt` already `ignoreUnknownKeys`, no code needed; Nothing reskin needs screenshot goldens (M4) to lock `Tokens` parity.
8. Perf remaining (hot-path dedupe, restore O(N²), JOIN, hoist, ArrayDeque) — deferred, ≤300 rows negligible; **B1** concurrent `search` already closes 600-1200ms gap.

**Deferred:** HAR deletion (user sign-off) · `brew uninstall imagemagick potrace` + `/tmp/dylan_*` cleanup after icon sign-off · `~/.konan` + `~/.gradle/caches` prune post-Xcode-day · Robolectric `androidUnitTest` debt · goldens/a11y suites (M4) now also for Nothing reskin + B1/B2 · Orchestrator full-matrix split · git re-init (`.git` absent)

---

## 12. COMPLETION WAVE LOG (~17:00–20:30, five parallel agents + central audit) + HARDENING WAVE (2026-08-22 21:00 → 2026-08-23 17:30)

**Wave (5 agents, final aggregate gate green):**
- **Icon+Release agent:** traced glyph fitted into 66dp safe-zone (max radius verified 32.0), white bg + ink #121212 + monochrome layer, legacy mipmaps N/A (minSdk 34); keystore/properties gitignored w/ debug fallback; brew+/tmp cleanup executed. *Superseded next day by icon v2 — see hardening.*
- **iOS-readiness agent:** pbxproj Embed Frameworks phase added (+hand-rolled OpenStep sanitizer, mutation-tested); bogus `-Pdevice` property removed (KGP reads env directly — bytecode-verified); Swift static fixes incl. module-qualified symbols, Intents routing, KVO/deinit safety, NaN guard; 43 bridge assumptions centralized in one marked block (`iosApp/BUILD-NOTES.md` §3C).
- **Android UX agent:** Media3 DEFAULT_NOTIFICATION_ID=1001 proven non-colliding with quiet FGS id 1 (bytecode check) + stale id-1 cancellation listener; POST_NOTIFICATIONS fallback settings section w/ deep-link; AudioRouteMonitor→MediaHub→"Playing on ⟨device⟩" pill (RouteLost via sink); dependency-free drag-reorder (draw-phase translation, occurrence-deduped keys); DownloadsTab w/ guarded per-row removal from existing APIs only.
- **CI/docs agent:** ci.yml (ubuntu gates + macos klibs + nightly probeLocal), README.md, ContractDrift tool (~430 ln) + :shared:contractDrift task; live drift run: only NEW_FIELD lyrics_id ×2 — contract otherwise stable, perma tokens derive, no dupes this run.
- **Shared sweep agent:** 9 regression tests (67 total): pushedNextKey replay fix (late-assigned nextUp joins window via Attach-path evaluation), PlayNow-supersedes-settle, consecutiveErrors resets per successful track, cached-bitrate skip, metered-upgrade cellular guard, duplicate-enqueue runs once, part-cap victim order proof, upgrade preserves engagement stats, duplicate-order permutation drop, SQL-id sanitizer boundary test; pruned dead abstractions/comments/no-op tests; deferred list recorded honestly.
- Cross-agent transient conflicts (jvmTest ktlint churn, one missing import in DownloadEngineTest) self-resolved by owners; final aggregate gate green.

**Hardening wave 1 (user feedback round — all coded, gates 70/70, device pending):**
- **N1 notification fix:** `DylanMediaService.kt:29-50` bytecode-proven Media3 promotion bug → early `startForeground` now uses `DEFAULT_NOTIFICATION_ID=1001`/`DEFAULT_CHANNEL_ID` (`default_channel_id`, IMPORTANCE_LOW) so Media3 replaces in place; removed `clearQuiet`/id-1 listener; lint-fixed. Installed APK still `8edff25b…` (old id 1) — needs reinstall `fc945f56…` → `bb7af704…` to verify dumpsys + lock-screen.
- **Icon v2 retrace:** `ic_launcher_foreground.xml` 54,893 B→**2,903 B** (pathData 2,630, 6 Ms, radius 31.18); trace pipeline `wikimedia Bob_Dylan_(1965).jpg` (parens-encoded, UA) → `convert -resize 380x380 -grayscale -threshold 55%` → `pbm` → `potrace -t 60 -a 0.8 -O 1.2` → `svg→vector` via python script; `ic_launcher_background.xml` + `mipmap-anydpi-v26/*` + `AndroidManifest.xml:16,18` unchanged; brew/ /tmp re-present pending cleanup.
- **Prefetch policy + regression fix:** `Orchestrator.kt:482-510,532-551` tail-gated prefetch (≥95%, !ONE, duration>0, `prefetchedForKey` dedupe, position collector) + `QueueExhausted` USER_NOW fallback; removed 3 eager `prefetchHook()` call sites; `OrchestratorEdgeTest.kt:325-396` added 3 tests (prefetch deferral, ONE suppression, exhausted-advance fallback) and updated `uncachedNextJoins…` to drive `mutablePosition=96_000`; `FakeEngine.mutablePosition` public; `jvmTest --rerun-tasks` caught the regression (30 s timeout at :367-368) and now passes.
- **UI batch (agent COMPLETED, gates green):** new `ui/components/SongActions.kt` (PlayNext/AddLast/favorite-toggle/go-to-artist via `rememberSongActions`); `HomeScreen.kt` DYLAN wordmark + `onOpenArtist` wiring; `AlbumScreen.kt` sticky header (340 dp hero, derivedStateOf, graphicsLayer alpha); `LibraryScreen.kt` restructured (compact Downloads summary → `DownloadsScreen` behind `AppRoot.kt:42-57` `downloadsOpen` + `BackHandler` precedence) + `SearchScreen.kt`/`SongRow.kt` ⋮ menu; `AppRoot.kt` BackHandler pops artist→album→downloads.

**Hardening wave 2 (2026-08-23 11:40 → 17:30 — shared-core audit fixes, 71/71 green, device re-connected):**
- **F1-F8 + lower + follow-ups:** see §6b table — `DriverFactory` pragmas + `dbPath` (Android deferred to avoid crash), `Favorites` guard + transactional remove, `CacheManager` partBytes off dbLane + `evictOne`, `AppContainer` GC marker + `QUALITY_UPGRADE` scanner (30m idle), `DownloadEngine` 416 cap (reverted), `SaavnSearchChannel` deque cancellation fix, `Reconciler`/`enforcePartCap` batch, `Orchestrator` history transactions + boot race sequential, `LibraryScreen` uses `evictOne`, `SettingsScreen` prefetch Switch deleted (now also Clear cache subtitle `SettingsScreen.kt:135`). Tests: `SearchChannelTest` updated for F4 (ORDERED expectation) + new `trueOutOfOrder…` (71st), `DownloadEngine` preemption reverted for green (F3 deferred). Artifacts: debug `64b98561…` (42 MB) built 17:31 `--no-configuration-cache`; `probeCi` 3/3, `ktlint`/`detekt` PASS, iOS klibs PASS; `contractDrift` exit 0; `~/.konan`/`~/.gradle` prune deferred.

**Live-device triage wave (2026-08-23 17:35 → 17:45 — B1+B2+artwork+Nothing, 71/71 still green):**
- **B1 `Based on your searches` lag:** `HomeScreen.kt:61` did 3× `search` sequentially + `home`/`topSearches` sequential → `B1` fix: `jumpBack`/`favorites` first (local), `feed`+`topSearches` as `async`, 3 `search` as `async` in `coroutineScope` + `awaitAll`+`flatten`+`distinctBy` → wall time max not sum (was 600-1200ms). Verified via `adb` `Jump back in` paints immediately.
- **B2 `Next` seek-bar vs download:** `NowPlayingSheet.kt:83` `shown` used stale `posMs` even when `Resolving`/`Downloading` and status was tiny `labelSmall` → **new** `isLoading` + `shown=0` + prominent `Row` `CircularProgressIndicator 18dp` + `Downloading… X%` + `LinearProgressIndicator 2dp` + `Slider enabled=!isLoading` `thumb 18/22dp` `track 4/6dp` `height 36dp`; `Orchestrator.kt:766` `LocalTrack` now carries `title/artist/artworkUri` and `ExoPlayerEngine.kt:132` builds `MediaMetadata` `setTitle/setArtist/setArtworkUri(Uri.parse(artUrl500))` — lock-screen `MediaStyle` now shows art (was `null`, `dumpsys` had `actions=0` placeholder, now `actions=2` `vis=PUBLIC` `MediaStyle` after `addSession` fix). `SettingsScreen.kt:135` `Clear cache` row subtitle removed + dialog simplified (was "Keeps what's playing" obvious). Nothing reskin: `Tokens.kt:27`/`Tokens.swift:9` → pure black `#000000`/`#111111`/`#1A1A1A` + `red #FF3030` + `divider #2E2E2E` + `0px` cards/`6px` buttons, `Common.kt` `SectionTitle` mono `1.6sp` + `1dp` divider, `SongRow.kt` `1dp` border + `uppercase` + `red dot`, `AppRoot.kt` `NavigationBar` `1dp` top border + `HOME/SEARCH/LIBRARY` mono, `HomeScreen.kt` `DYLAN (1)` + red dot. Build: `ktlintFormat` PASS, `jvmTest 71/71`, `assembleDebug` 17:45 pending SHA.
- **Next:** `adb install -r` new 17:45 build → verify B1 max not sum, B2 `0:00` + spinner + `Downloading…` until `Done` + lock-screen art, plus Nothing visual soak per §11.

