# Subagent Feedback Ledger — addressed vs pending

Source: 5 parallel Staff-Engineer audits (playback / data / UI-session /
infra-docs / hacks-sweep). Each finding below carries its audit ID, the
verdict, and file:line evidence. Verified 2026-09-04 against `main`.
Legend: **Done** · **Partial** · **Pending** (with reason) · **Superseded**.

## 1. Playback pipeline (album-stall audit)

| ID | Finding | Verdict | Evidence |
|----|---------|---------|----------|
| P0-1 | `ensureReady` parked 120s, failures terminal | **Done** | `Orchestrator.kt` permanent-skip + transient retry w/ backoff (~`skipToNextOrError`), 15s log watchdog (`ensureReadyWatchdogMs`, `AppConfig.kt:46`); `readyTimeoutMs` stays 120s by design |
| P0-2 | `Phase.Downloading` never assigned; window joins gated on `transportable` | **Done** | `Downloading` assigned after enqueue (`Orchestrator.kt:~553`); `refreshUpNext`/`syncPendingNext` return only on `Idle`/`Error` (`:659,661,674`) |
| P0-3 | `ItemEnded` never advances; single-item window stalls | **Done** | `advanceOnNaturalEnd()` shared by `QueueExhausted` + 2s `ItemEnded` fallback (`:731-751,787`) |
| P0-4 | Preempted waiter sleeps (Cancelled ≠ Done/Failed) | **Done** | `PreemptSignal` (`DownloadEngine.kt:55`), victim re-queued, `poke()` wake checks (`:195,264,298,777`) |
| P1-1 | `playGeneration` races, side-channel launches | **Partial** | Gen re-check before every `_state` write + gen-tagged logs done; `refreshUpNext`/`prefetchHook`/`resyncFault` still launch outside the inbox loop (full reducer/Effect refactor deferred — large, risky without sim E2E) |
| P1-2 | `Previous` dropped; `Next` branches misleading | **Done** | Uniform every-phase `Previous` (`Orchestrator.kt:332-340`) + 300ms `debounceNav`; transportable path cancels both jobs |
| P1-3 | Peek-gate starves `USER_NOW` | **Done** | Same-key preempt wakes waiter (P0-4 machinery); 429 still fails fast by design |
| P1-4 | `Repeat.ONE`/shuffle exhaustion | **Done** | ONE pins index (`QueueStateMachine.kt:15-16`); `remapShuffleOrder` preserves permutation |
| P1-5 | Stale `Previous` position source | **Done** | `engine.currentTimeMs()` preferred (`Orchestrator.kt:968`), `lastPosMs` synced on pause |
| P2 | No gen/index correlation in logs | **Done** | All play/dl logs carry gen/idx/key |

## 2. Data layer (`cacheable` removal fallout)

| ID | Finding | Verdict | Evidence |
|----|---------|---------|----------|
| D-2a | `OR REPLACE` + CASCADE footgun, non-atomic admit | **Done** | `INSERT OR IGNORE` + `updateSongMetadata` (`dylan.sq:92,95`), `admitSongs` in one `db.transaction` (`Repos.kt:30`) |
| D-2b | Stale `resolve_ref` (insert-only, dead `updateSongSource`) | **Done** | Metadata refresh on admit; `updateSongSource` exercised by tests |
| D-2c | 4 divergent `toSong` copies | **Partial** | Single `SongMapper.kt` for Repos/IosGraph/LibraryScreen; Orchestrator's private copy left (owner-isolation during parallel work) |
| D-2d | Join/GC staleness, orphan history | **Done** | `ON DELETE CASCADE` everywhere, `deleteOrphanHistory` wired into weeklyGc (`Repos.kt:217`) |
| D-2e | LRU vs playing file, dual `protectedKeys` writers | **Done** | Victim re-check inside transaction; single-writer comment |
| D-migration | 14-col on-device DB vs 13-col schema, no version bump | **Deferred by owner** | Exact 3-step `1.sqm` plan staged in `dylan.sq` header; destructive wipe stays the only path until prod/beta |
| D-mapper | `has320` coercion, permaToken shape, art fallback | **Done** | `coerceHas320`/`normalizePermaToken` (`Mapper.kt:52,67`); `song_not_cacheable.json` kept as positive test |
| D-probe | Gutted P5 gate | **Done** | resolveRef-coverage gate (`ProbeMain.kt:147-149`) |

## 3. UI + media session

All **Done** (single `Sheet` enum `AppRoot.kt:81`; `Playing`-only glyphs; `isPlaying`+`showsPause` `DylanApp.swift:151`; song-keyed sliders; `canPlay` offline gating; `ForwardingPlayer` native Next/Prev; artwork-by-token + edge `setActive`; iOS single `.sheet(item:)` `RootView.swift:53`), except:

| ID | Finding | Verdict | Evidence |
|----|---------|---------|----------|
| U-showsPause | Shared `playPauseShowsPause` = Playing\|\|Ready | **Done (follow-up)** | Playing-only (`IosGraph.kt:186`); lock-screen rate follows truthfully |
| U-conflate | `FlowAdapter` conflates PlayerState | **Done (follow-up)** | `conflate=false` for state, kept for position (`FlowAdapter.kt`) |

## 4. Infra / dylan-sign / logging / docs

| ID | Finding | Verdict | Evidence |
|----|---------|---------|----------|
| I-flush | Async log loss, no flush hook | **Done** | `flush()/close()` + stop/background/terminate hooks, session-stamped boot line |
| I-anisette | Swapped `X-Apple-I-MD*` headers | **Done** | Canonical mapping + round-trip test |
| I-sign-honesty | False-positive "signed" IPA | **Done** | `--strict`, `.app` verify, placeholder bundle suffix, `DYLAN_KEYCHAIN`, symlink/`embedded.mobileprovision` handling |
| I-keyring | Double Set/Get, untyped NotFound | **Done** | Single calls, typed errors |
| I-ci-gosign/drift/cache | No Go CI, presubmit live tests, `shared/build` cache | **Done** | `gosign` matrix, nightly-only drift, cache dropped |
| I-ci-release | Silent debug-signed release | **Superseded** | Old hard-fail gate replaced by `builds.yml` split (debug presubmit + secrets-gated prod with apksigner guard) |
| I-ios-release | xcpretty double-archive, literal `$(DEVELOPMENT_TEAM)`, `altool`, `-allowProvisioningUpdates` | **Done (follow-up)** | Templated plist + teamID guard, tee logs, pilot, no provisioning-updates (`.github/workflows/ios-release.yml:61-92`) |
| I-provision-stub | Fixtures presented as live; `refresh` overclaims | **Done (follow-up)** | `[offline fixture]` label, honest `refresh` help text |
| I-docs/version | No arch docs, stale versions, `1.0` fallback | **Done** | `docs/{architecture,triage,signing}.md`, MARKETING sync, `0.0.0` sentinel |
| I-pinning/config-cache | SHA pinning, CC flag contradiction | **Done (follow-up)** — all 68 `uses:` SHA-pinned with tag comments; `configuration-cache=true` removed and all `--no-configuration-cache` flags stripped (CC off = de-facto behavior, zero behavior change) |

## 5. Hacks sweep (P0/P1/P2 catalog)

| # | Hack | Verdict |
|---|------|---------|
| 1 | `runBlocking` resumption | **Done (follow-up)** — pre-warmed `ResolvableFuture` + 1.5s budget (`DylanMediaService.kt`) |
| 2 | 120s-as-control-flow | **Partial** — watchdog + skip; timeout value kept by design |
| 3 | `!!` chain in DownloadEngine | **Done** — early `NO_SOURCE` guards |
| 4 | Stringly preempt | **Done** — `PreemptSignal` |
| 5 | `streamInto` shared-var race + `delay(10)` spin | **Done (follow-up)** — `supervisorScope` + typed `StallSignal` + `awaitContent` suspend (verified `awaitContent(min)` is an interface member in ktor 3.5.2, not an extension); 19/19 DownloadEngineTest green |
| 6 | `tryEmit` drops | **Done** — 256 buffer + checked emit + `log.w` |
| 7 | State `conflate` | **Done** (see U-conflate) |
| 8 | 18× silent bridge catches | **Done (follow-up)** — 10 user-initiated calls toast; 13 background reads stay silent-log by design |
| 9 | `as? … ?? []` erasure | **Done (follow-up, code-only)** — debug-only `assertionFailure` contract guards at all 3 sites; release behavior unchanged |
| 10 | Swallowed PRAGMAs | **Done** — logged via LogBuffer param |
| 11 | Unconditional `removeAllItems` | **Done (follow-up, code-only)** — window diff keeps audible head, shared `replaceTail` helper; needs sim E2E to confirm audibly |
| 12-26 | Search/HTTP timeouts, FileLogSink caps, debounce constants, `consecutiveErrors`, AppContainer swallows, part-cap/backoff, probe `!!`, rename `check` | **Pending (deferred tech-debt)** — tuned constants with comments; replacing them needs device-measured data, not guesses |

## Pending work list (all of it)

1. `streamInto` structured-concurrency rewrite (needs sim E2E safety net)
2. `resyncFault` escalation bound — **Done (follow-up)**: 3 strikes per itemId → skip + toast, reset on mapped TrackChanged
3. `removeAllItems` window diff on iOS
4. `as?` contract tests + debug asserts
5. Versioned `.sqm` migration before prod/beta (destructive wipe is dev-only)
6. P1/P2 constant tuning with device measurements
7. SHA-pinning actions, configuration-cache resolution
8. iOS `xcodebuild` verification — **Done**: sim Debug `BUILD SUCCEEDED` (fixed a stray `}` in Stores.swift found by the compiler)
