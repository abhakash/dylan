# Subagent Feedback Ledger — addressed vs pending

Source: 5 parallel Staff-Engineer audits (playback / data / UI-session /
infra-docs / hacks-sweep). Each finding below carries its audit ID, the
verdict, and file:line evidence. Originally verified 2026-09-04; **spot-re-verified
2026-10-01** against the working tree, sample only (rows carrying a `✓` were
re-read against code; the rest carry forward unre-checked and their line numbers
should be treated as stale).
Legend: **Done** · **Partial** · **Pending** (with reason) · **Superseded**.

The 2026-10-01 pass changed four verdicts and disproved two findings that had been
proposed as false "Done" rows. Those are called out in §6 so a future reader does not
have to re-derive them.

## 1. Playback pipeline (album-stall audit)

| ID | Finding | Verdict | Evidence |
|----|---------|---------|----------|
| P0-1 ✓ | `ensureReady` parked 120s, failures terminal | **Done** | `skipToNextOrError` (`Orchestrator.kt:745`) after a permanent-skip check; log-only watchdog `ensureReadyWatchdogMs = 15_000` (`AppConfig.kt:156`, armed at `Orchestrator.kt:649-661`) — it warns and never fails playback. `readyTimeoutMs` still owns failure and is 120 s by design (`AppConfig.kt:143`, shared literal with `stallWallFloorMs` at `:185`) |
| P0-2 ✓ | `Phase.Downloading` never assigned; window joins gated on `transportable` | **Done** | `Phase.Downloading(song.key)` assigned at `Orchestrator.kt:611`; `refreshUpNext` (`:797`) and `syncPendingNext` (`:829`) are the two join paths |
| P0-3 ✓ | `ItemEnded` never advances; single-item window stalls | **Done** | `onItemEnded` (`:982`) with an explicit no-follow-up fallback to `advanceOnNaturalEnd` (`:1017`, logged at `:999`). A 1-item window structurally cannot emit `ItemEnded` (`ExoPlayerEngine.kt:169` guards on a predecessor existing), which is asserted in the engine contract suite (`EngineContractTest.kt:123-135`) |
| P0-4 ✓ | Preempted waiter sleeps (Cancelled ≠ Done/Failed) | **Done** | `PreemptSignal : CancellationException` (`DownloadEngine.kt:827`), raised at `:409` so the victim re-queues instead of reporting a failure |
| P1-1 | `playGeneration` races, side-channel launches | **Partial** | Gen re-check before every `_state` write + gen-tagged logs done. Carried forward unre-verified since 2026-09-04 |
| P1-2 ✓ | `Previous` dropped; `Next` branches misleading | **Done** | Uniform every-phase `Previous` in the intent handler; 300 ms `navDebounceMs` (`AppConfig.kt:146`) enforced by `debounceNav` (`Orchestrator.kt:491,1284`) |
| P1-3 | Peek-gate starves `USER_NOW` | **Done** | Same-key preempt wakes the waiter (the P0-4 machinery); 429 still fails fast by design |
| P1-4 ✓ | `Repeat.ONE`/shuffle exhaustion | **Done, and stronger than the original fix** | `Repeat.ONE` *pins* the index while `Repeat.ALL` over a one-element queue *wraps* — the two are deliberately different and the distinction is documented and tested (`Models.kt:342-357`); `validate` rejects `next == index` outside those two cases (`Models.kt:448-460`). Shuffle anchoring is `PlayerState.newShuffleOrder` / `anchoredOrder` / `carriedOrder` (`Models.kt:226-233,322-340`) — the old `remapShuffleOrder` no longer exists and **is now covered**: property tests in `commonTest` (`QueueStatePropertyTest.kt`, run on JVM *and* iOS) plus unit tests for the anchor-preserves-position and stale-order-rebuild cases (`PlaybackLogicTest.kt:242-275`) |
| P1-5 | Stale `Previous` position source | **Done** | `engine.currentTimeMs()` preferred; `lastPosMs` synced on pause |
| P2 | No gen/index correlation in logs | **Done** | All play/dl logs carry gen/idx/key (e.g. `Orchestrator.kt:659`) |

## 2. Data layer (`cacheable` removal fallout)

| ID | Finding | Verdict | Evidence |
|----|---------|---------|----------|
| D-2a ✓ | `OR REPLACE` + CASCADE footgun, non-atomic admit | **Done** | `insertCached` is a plain INSERT and the admit loop runs inside one `db.transaction` (`Repos.kt:81`); metadata refresh via `updateSongMetadata` (`Repos.kt:51`) |
| D-2b ✓ | Stale `resolve_ref` (insert-only, dead `updateSongSource`) | **Done** | Metadata refresh on admit; `updateSongSource` is now reachable and exercised (`DownloadEngineTest.kt:1451-1452`) |
| D-2c ✓ | 4 divergent `toSong` copies | **Partial** | One `SongMapper.kt` for Repos / IosGraph / LibraryScreen, with the split from Orchestrator's private copy documented rather than accidental (`SongMapper.kt:15-18`; the private copy is at `Orchestrator.kt:1372`) |
| D-2d ✓ | Join/GC staleness, orphan history | **Done** | `ON DELETE CASCADE` on the FKs (`dylan.sq:97` and siblings), partial indexes instead of full scans for the hot sorts (`dylan.sq:76-84`); `deleteOrphanHistory` is called from **two** places — the weekly GC (`Repos.kt:241`) and the Reconciler (`Reconciler.kt:128`) |
| D-2e ✓ | LRU vs playing file, dual `protectedKeys` writers | **Done** | The protection set is a **table**, not a read: every mutation republishes into `protected_keys` inside its own transaction and every victim selection is one `UPDATE … WHERE … NOT IN (protected_keys) RETURNING …` (`CacheManager.kt:32-37`). `AppContainer.publishProtectedKeys` is the single writer (`AppContainer.kt:399-416`) and `CacheManager` holds a read-only `StateFlow` (`CacheManager.kt:55`). **The second writer this row was filed against no longer exists** — see §6 |
| D-migration | 14-col on-device DB vs 13-col schema, no version bump | **Done (owner-deferred, now unblocked)** | The `.sqm` chain exists: `src/commonMain/sqldelight/migrations/1.sqm`, with `migrationOutputDirectory` pinned to the one directory the plugin both reads and writes (`shared/build.gradle.kts:107`) and `verifyMigrations` deliberately off (`:108-112`). The real gate is `CacheMigrationTest`, which builds a genuine v0 database and opens it through the real `DriverFactory` |
| D-mapper ✓ | `has320` coercion, permaToken shape, art fallback | **Done** | `coerceHas320` / `normalizePermaToken` live in `Identity.kt:29,44` (called from `Mapper.kt:98,100`); `song_not_cacheable.json` is kept as a positive test |
| D-probe ✓ | Gutted P5 gate | **Done — but it is warn-only, not a gate** | The resolveRef-coverage check is `ProbeMain.kt:195-202` and it is tagged `M1`, emitting `"… (non-blocking)"`; only `M0` rows gate (`ProbeMain.kt:470`). The earlier framing of P5 as a "gate" overstated what it can do: it reports coverage and never fails the probe |

## 3. UI + media session

Single `Sheet` enum; `Playing`-only glyphs; `isPlaying` + `showsPause`; song-keyed sliders;
`canPlay` offline gating; `ForwardingPlayer` native Next/Prev; artwork-by-token; iOS single
`.sheet(item:)` (`RootView.swift:53`). Verified rows:

| ID | Finding | Verdict | Evidence |
|----|---------|---------|----------|
| U-showsPause ✓ | Shared `playPauseShowsPause` = Playing‖Ready | **Done (follow-up)** | `state?.phase is Phase.Playing` only (`IosGraph.kt:257`), so the lock-screen rate follows truthfully |
| U-conflate ✓ | `FlowAdapter` conflates `PlayerState` | **Done (follow-up)** | Conflation is a per-subscription `Conflation` decision, not a global one (`FlowAdapter.kt:27-34,70,93`): state and progress subscribe eager, position keeps `LATEST_ONLY` (`Orchestrator.kt:83`) |

## 4. Infra / dylan-sign / logging / docs

| ID | Finding | Verdict | Evidence |
|----|---------|---------|----------|
| I-flush ✓ | Async log loss, no flush hook | **Done** | `flush(timeoutMs)` / `close()` exist (`FileLogSink.kt:73,86`) and are reached from `onBackground()` (fire-and-forget, io lane), `MainActivity.onDestroy`, and `stop()` (suspending flush **then** close). The single production caller of `close()` is the iOS terminate path — see §6 |
| I-anisette ✓ | Swapped `X-Apple-I-MD*` headers | **Done** | Canonical mapping (`anisette.go:12`) with a round-trip test asserting all four headers (`anisette_test.go:25-35`) |
| I-sign-honesty ✓ | False-positive "signed" IPA | **Done** | `--strict` (`main.go:216,256-258,365`), placeholder-bundle refusal, `DYLAN_KEYCHAIN` fallback, and an explicit `[offline fixture …]` label when the portal is not wired (`main.go:307`) |
| I-keyring ✓ | Double Set/Get, untyped NotFound | **Done** | Single `Set`/`Get`/`Delete` per operation, no write-then-read-back; not-found is typed as `*store.NotFoundError` whose `Is` matches both `ErrNotFound` and `os.ErrNotExist` (`store.go:14-32`), with the darwin `keyring.ErrNotFound` normalized to it (`keyring_darwin.go:52-59`) |
| I-ci-gosign ✓ | No Go CI, presubmit live tests, `shared/build` cache | **Done** | `gosign` matrix on macOS-14 + ubuntu-latest running `go build/vet/test` (`ci.yml:225-244`); the live gates are nightly-only; `shared/build` is uncached with the reason inline (`ci.yml:185-186`) |
| I-ci-release | Silent debug-signed release | **Superseded** | Accurate as written, and the audit's counter-claim that `release.yml` "still does exactly this" is **false** — see §6. Both release paths now fail closed: `release.yml:63-67` exits 1 when either keystore secret is missing, and `:109-114` runs `apksigner verify --print-certs` on every APK and fails if the chain says `Android Debug` |
| I-ios-release ✓ | xcpretty double-archive, literal `$(DEVELOPMENT_TEAM)`, `altool`, `-allowProvisioningUpdates` | **Done (follow-up)** | Templated `exportOptions.plist` with a `sed` substitution and an unset-`APPLE_TEAM_ID` guard (`ios-release.yml:64-72`), `DEVELOPMENT_TEAM` passed on the `xcodebuild` line (`:81`), tee'd logs, `fastlane pilot` |
| I-provision-stub ✓ | Fixtures presented as live; `refresh` overclaims | **Done (follow-up)** | `[offline fixture — …]` label in the output (`main.go:307`) |
| I-docs/version ✓ | No arch docs, stale versions, `1.0` fallback | **Partial** | `docs/{architecture,triage,signing,build-and-gates}.md` exist. The sentinel is now `0.1.0`, not `0.0.0` (`androidApp/build.gradle.kts:48`), and the pbxproj is machine-synced from `VERSION` (`sync-ios-version.sh`). **`AppContainer.APP_VERSION` is a hand-maintained literal and has drifted** — it reads `0.1.0` while `VERSION` is `1.0.0` (`AppContainer.kt:532`), and `bump-version.sh` writes neither, so the boot line under-reports the shipped version. See §7 |
| I-pinning/config-cache ✓ | SHA pinning, CC flag contradiction | **Done, with one exception** | All **71** `uses:` in the tree are SHA-pinned (not the 68 the row claimed) and `gradle.properties` sets no `configuration-cache`, so CC is off as the de-facto behaviour. Exactly **one** `--no-configuration-cache` survives, and it is the shipped iOS build phase (`project.pbxproj:213`) — see §6 |

## 5. Hacks sweep (P0/P1/P2 catalog)

| # | Hack | Verdict |
|---|------|---------|
| 1 ✓ | `runBlocking` resumption | **Done** — pre-warmed `ResolvableFuture` with a bounded timeout. No `runBlocking` remains in production `commonMain`/`androidMain`/`iosMain`; the only surviving call is `ProbeMain.kt:513`, a JVM CLI `main` where blocking is the whole contract |
| 2 ✓ | 120 s-as-control-flow | **Partial** — watchdog + skip, timeout value kept by design. The value is now one shared literal (`AppConfig.kt:185`) used as both the attempt ceiling floor and the user-visible wait, precisely so the two roles cannot drift |
| 3 ✓ | `!!` chain in DownloadEngine | **Done** — early `NO_SOURCE` guards |
| 4 ✓ | Stringly preempt | **Done** — `PreemptSignal` |
| 5 ✓ | `streamInto` shared-var race + `delay(10)` spin | **Done (follow-up)** — `supervisorScope` + typed `StallSignal` + `awaitContent` suspend |
| 6 ✓ | `tryEmit` drops | **Done** — 256 buffer + checked emit + `log.w` |
| 7 ✓ | State `conflate` | **Done** (see U-conflate) |
| 8 ✓ | 18× silent bridge catches | **Done (follow-up)** — user-initiated calls toast; background reads stay silent-log by design |
| 9 | `as? … ?? []` erasure | **Partial** — the contract guard exists at the queue site (`Stores.swift:58`, which type-checks every element before the cast) and at the home-sections site (`Stores.swift:336`), but the suggestions cast (`Stores.swift:148`) and the four `compactMap { $0 as? KSong }` sites in `DylanBridge.swift` (`:179,248,285`) still drop mismatches silently. The earlier claim of "guards at all 3 sites" is **false as written** |
| 10 ✓ | Swallowed PRAGMAs | **Done** — logged via the `LogBuffer` param |
| 11 ✓ | Unconditional `removeAllItems` | **Done, in Swift and stronger than described** — the window diff lives in `NativeAudioOutputImpl.prepareOnMain` (`:113-135`): a same-head re-prepare keeps the audible item and only calls `replaceTail` (`:156-166`), and the head re-arms its own `Prepared` (`:141-153`) because an `AVPlayerItem` has no second `.readyToPlay` transition. The `replaceTail` helper the ledger named is a **Swift** function, not a Kotlin one |
| 12-26 | Search/HTTP timeouts, FileLogSink caps, debounce constants, `consecutiveErrors`, AppContainer swallows, part-cap/backoff, probe `!!`, rename `check` | **Pending (deferred tech-debt)** — tuned constants with comments. One has since been fixed for a different reason: the bulk client no longer sets `requestTimeoutMillis` (`Clients.kt:57-71`), because a whole-request cap was capping the streaming GET and making both `stallWallFloorMs` and the 8 KB/s rate-wall policy dead. The remaining ones still need device-measured data, not guesses |

## 6. Two audit claims that were DISPROVED on re-verification

Recorded because both were proposed as corrections to this file, and acting on either would
have introduced a false row.

**"D-2e is a false Done — the comment is the fix, `Orchestrator.publishProtected()` still
writes a strict subset."** False. `publishProtected` does not exist anywhere in the tree;
the single writer is `AppContainer.publishProtectedKeys` (`AppContainer.kt:399-416`), and
`CacheManager` is handed a read-only `StateFlow` with a comment that matches the code
(`CacheManager.kt:51-55`). The audit also cited `CacheManager.kt:17-21` for that comment;
those lines are now the class KDoc for the protection-table design. The row's Done verdict
stands.

**"`upgradeSourceKeys` has six reads and zero writes."** False, and it was the audit's own
top-priority five-line fix. It is written at both ends now — added when a `QUALITY_UPGRADE`
attempt starts (`DownloadEngine.kt:436`) and released on a terminal state (`:752`) — with a
regression test named for the bug (`DownloadEngineTest.kt:1297-1313`).

**Two more, for completeness:**

- **"three `--no-configuration-cache` flags remain, one in the shipped iOS build phase."**
  One remains, and it *is* the shipped one (`project.pbxproj:213`). The other two hits for
  that string in the repo are prose in `iosApp/BUILD-NOTES.md`, not invocations.
- **"`release.yml` still does exactly this [silently debug-signs a release]."** False.
  `release.yml` fails closed on a missing secret (`:63-67`) and refuses a debug-signed APK
  by cert chain (`:109-114`). The I-ci-release row's "Superseded" verdict is accurate.

And one claim that was **half** right, now split in the table above: the audit said `stop()`
and `FileLogSink.close()` have *zero* call sites. They do not — `IosGraph.dispose()` →
`container.shutdown()` → `stop()` → `closeLogTrail()` → `close()`, driven from
`applicationWillTerminate`. What is true is narrower and is now stated in the docs:
**Android never calls `stop()` or `shutdown()` at all**, so that path is iOS-terminate-only
and the reusable stop/start contract has no production caller on either platform's normal
lifecycle.

## 7. Open items

1. `streamInto` structured-concurrency rewrite (needs a device/sim E2E safety net)
2. `resyncFault` escalation bound — **Done**: 3 strikes per itemId → skip, reset on a mapped
   TrackChanged (`Orchestrator.kt:1041-1052`)
3. `removeAllItems` window diff on iOS — **Done** (see §5 #11)
4. `as?` contract tests + debug asserts — **Partial** (see §5 #9)
5. Versioned `.sqm` migration before prod — **Done** (`1.sqm`; see D-migration)
6. P1/P2 constant tuning with device measurements
7. SHA-pinning — **Done** (71/71). `configuration-cache` — off by omission, not by decision;
   the one surviving `--no-configuration-cache` is in the shipped iOS build phase and is
   load-bearing there (the embed task reads Xcode env vars that CC would not replay)
8. iOS `xcodebuild` verification — **Done** in CI for the simulator (`ci.yml:200-212`) plus
   `:shared:iosSimulatorArm64Test` (`:196-199`). Still outstanding: a **device** archive, and
   the `Embed Frameworks` question in `iosApp/BUILD-NOTES.md` §3.3
9. `AppContainer.APP_VERSION` drift (see I-docs/version) — a real, unfixed defect
10. `iosApp/Tools/check_pbxproj.py` is not in any workflow and asserts nothing about
    `DEVELOPMENT_TEAM` or the missing app icon
