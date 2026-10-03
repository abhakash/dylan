# Dylan — Codebase Audit & Rearchitecture Proposal

**Date:** 2026-09-27 · **Commit:** `749bdca` · **Method:** 8 parallel read-only audits, one per subsystem, all findings verified against source.

**Scope:** ~16,700 LOC of Kotlin/Swift/SQL + Go tooling. 3 platforms (Android/Compose, iOS/SwiftUI, JVM test).

---

## 1. Executive summary

This is a well-architected codebase with a serious reliability problem, and the two facts are not in tension. The seams are right, the module boundaries are mostly right, `docs/architecture.md` is honest about intent, and the Go tooling is the only properly-tested subsystem in the repo. What is broken is the **gap between the documented concurrency model and the actual one**, and **the fact that none of the gates can fail**.

### The three things that matter most

**1. The single-threaded `state` lane is being treated as a mutex. It is not.**

`docs/architecture.md:16-23` states the model correctly: `state` is `limitedParallelism(1)`, no `runBlocking` on it, lock-free rings + `Mutex`. But `Orchestrator.handleIntent` captures `val s = _state.value` at line 226, then *suspends* (20+ `dbLane` round-trips in `admitSongs` for one album), then writes a whole new `PlayerState` derived from the stale read. Every `suspend` call inside the inbox loop is a yield point at which the loop's seriality guarantee evaporates. The same pattern — check, suspend, act — appears in `prepareWindow`, `refreshUpNext`, `restore`, `onTrackStarted`, and `evictOne`. **This is the root cause of most of the CRITICAL findings in the playback, download, and cache layers.** No amount of dispatcher discipline fixes it; the code needs to be a reducer where the state transition is synchronous with respect to state.

**2. Three "gates" run on every push and none of them can go red.**

| Gate | Claim | Reality |
|---|---|---|
| `probeCi` | "hermetic, rerun" | `ProbeMain.kt:403` passes `gates = false`; `:420-422` therefore computes `blocking = emptyList()` and **always returns 0**. Six `S1 FAIL` rows sit archived in `tools/probe-results.md`. It also makes live network calls, so it is not hermetic. |
| `ContractDrift` | contract-drift detection | `:418-420` returns 0 if **1 of 3** endpoints answers. Drift rows are ignored entirely. It is a `fun main()` in `jvmTest`, so presubmit never runs it. |
| `lintDebug` | Android lint | `build.gradle.kts:112` sets `abortOnError = false`. `config/lint.xml` is referenced by nothing. The baseline has 2 of 3 line refs stale. |

`release.yml:73` then runs `assembleRelease` with no keystore materialization step and no `apksigner verify` — it publishes a **debug-signed APK labelled "release"** from a green-looking workflow. `tools/check.sh` compounds it with `--continue`, `|| true` on artifact checks, and an unconditional `✅ All gates passed`.

**3. ~370 defects, of which the blocker class is almost entirely "a state transition is not atomic" or "a resource is never released".**

| Severity | Count | Character |
|---|---|---|
| Blocker | 43 | permanent data loss, silent corruption, unbounded leak, guaranteed-failure path, uncaught exception killing a permanent loop |
| Critical | 87 | races, TOCTOU, wrong-thread, N+1, un-diagnosable failure, ship-blocker |
| Major | 121 | measurable perf loss, maintainability traps, contract violations, feature gaps |
| Minor | 119 | magic numbers, dead code, duplicated helpers, stale comments |

The blocker class is unusually concentrated. Verifying the top of the list against source:

- `Orchestrator.kt:100-103` — the inbox consumer is `for (m in inbox) process(m)` with **no `try`/`catch` anywhere in `process`**. `SupervisorJob` protects siblings, not the failed child. One SQLite hiccup permanently ends playback, and `inbox` is `Channel.UNLIMITED` so every subsequent intent accumulates behind a dead loop. **Verified.**
- `DownloadEngine.kt:311,371,433,504` — `partB` is written in exactly 4 places, **never** from the writer. So `hadRange` is false on every retry and `Range:` is never re-sent inside a job. **Every retry restarts from byte 0.** **Verified.**
- `DownloadEngine.kt:615-630` — the only `catch` is `CancellationException`; there is no `catch (t: Throwable)`. `Url(s.url).host`, `fsRename` (which is `check(rc==0)` on iOS), and every `db.dylanQueries` call can throw. The job dies with no terminal state, so the Orchestrator's `withTimeoutOrNull(120s)` sits there, then reports `NETWORK_TIMEOUT`. **Verified.**
- `Clients.kt:52` — `requestTimeoutMillis = 45_000` on the bulk client caps the *entire* transfer including the body read. This makes `stallWallFloorMs = 120_000` and the 8 KB/s rate-wall policy **dead code**, and fails every track over ~2 MB on any sub-3 Mbit/s link. **Verified.**
- `CacheManager.kt:82` — `while (usage > cacheMaxBytes || count + 1 > cacheMaxFiles)`. Four callers pass `netNewBytes = 0` with no pending insert (COMMIT after the row is already in, `Favorites.add`, boot sweep, bulk enqueue). At the 300-file cap, **every completed download and every favourite silently destroys one extra unrelated file.** **Verified.**
- `ExoPlayerEngine.kt:259-267` — `release()` exists and is **called from nowhere** (`grep -rn "\.release()"` returns only the session and the never-invoked body). So `thread.quitSafely()` never runs, `artScope` never cancels, and `AudioManager.registerAudioDeviceCallback` is never unregistered — **one leaked thread, one coroutine scope and one system registration per play/pause/swipe cycle.** **Verified.**
- `IosGraph.kt:260-272` — `removeDownload` deletes row + file with **no `protectedKeys` check at all**, while Android routes through `CacheManager.evictOne` which does check. Swipe-removing the row you are currently playing deletes the playing file on iOS only. **Verified.**
- `SaavnSearchChannel.kt:63,192` — `httpOnly` is assigned once, at declaration, and **nowhere else**. Three 800 ms socket timeouts permanently disable WebSocket search for the process lifetime, with no recovery path. **Verified.**
- `DownloadEngine.kt:122,143-149` — `stop()` cancels a constructor-owned `SupervisorJob`; `start()` relaunches into the dead scope. One stop/start cycle bricks all downloads with zero log output. Latent today only because `AppContainer.stop()` has no caller either. **Verified.**
- 20 × `collectAsState`, **0** × `collectAsStateWithLifecycle` — and `lifecycle-runtime-compose` is already a declared dependency. The `positionMs` flow emits at 10 Hz from a running engine, so the whole UI tree recomposes 10×/sec **while backgrounded**. **Verified.**

### What is genuinely good

- `PlayerEngine`, `MusicProvider`, `SearchChannel` are the right seams. `IosGraph` correctly reuses `AppContainer` rather than mirroring it.
- The `Step` state machine in `DownloadEngine` is the right shape; the transfer/verify/evict split is a correct instinct.
- `LogBuffer`'s COW ring is genuinely lock-free and correct (verified against the CAS loop — no ABA, capacity exact, O(log₃₂n) not O(n)); the defects are in the *sinks*, not the ring.
- `tools/dylan-sign` (`internal/anisette/anisette_test.go`) is the only fully-tested subsystem, and `go vet`/`go test` run in CI on two OSes.
- Supply-chain hygiene: every `uses:` in CI is SHA-pinned, Gradle wrapper validation is on, Dependabot is configured.

### The security findings, calibrated

| Item | Verdict |
|---|---|
| `dylan-release.keystore`, `keystore.properties` | **Not tracked in any git ref.** `.gitignore` works. But `keystore.properties` holds the live store+key password in plaintext on disk (identical for both), and `dylan-release.keystore` sits next to it. Move to a CI secret / password manager and rotate if this key ever signed a Play release. |
| `*.har` ×8 + `saavn_har.json` (6.6 MB) | **Reachable at `refs/tags/v0.1.0`.** `git clone` fetches tags. I checked the contents: no cookies, no `set-cookie`, no `__call=user.*` — so **no user credentials**. What is there is replayable `song.generateAuthToken` payloads, the public page token, the full working API surface, and the dev's UA/OS fingerprint. Given `README.md:10` says "do not distribute", this undercuts the stated posture. Fix with `git filter-repo` + force-push tags, or make the repo private. |
| `DylanMediaService` `exported="true"`, no `android:permission` | **Real.** Any installed app can bind a `MediaController` and read the full queue (ids, titles, artists, artwork URLs) and drive transport. Deliberately suppressed by a `lint-baseline.xml` entry rather than fixed. |
| `allowBackup="true"` excluding only `audio/` | **Real.** `dylan.db` (every song ever played with `resolve_ref` + `perma_token` in the clear, full listening history, favourites, the resume blob) and `files/logs/` (the 1.5 MB diagnostic trail) all go to Google cloud backup and device-to-device transfer. `domain="file"` does not cover `domain="database"`. |
| `hs_err_pid*.log` ×2 | Untracked, not in `.gitignore`, and they contain absolute paths + JVM build info. A real crash was investigated and never followed up. |
| `tools/probe-results.md` | Tracked, and `probeCi` **appends live API response bodies to it on every run** (6.6 MB of the repo is HARs; this is the debug log). Stale since 2026-08-23, dirtied constantly. Untrack it. |

---

## 2. Blockers — the complete list

Ordered by (impact × reachability) ÷ effort. Every row is verified in source.

### Playback core

| ID | Defect | Location | Cost |
|---|---|---|---|
| **PB-1** | Inbox consumer has no supervision. One throw anywhere in `process()` ends playback permanently; `inbox` is `UNLIMITED` so intents accumulate unbounded behind the dead loop. | `Orchestrator.kt:100-103`, `:167-222` | S |
| **PB-2** | `Done`/`Failed` are not correlated with the enqueue that awaited them. A failed track can never be retried in the same session; an evicted track reports `CORRUPT_SIZE`; a `Done` row whose file fails `sniffOk` becomes a permanent `Ready` deadlock (no error, no skip, UI shows a track that can never play). | `Orchestrator.kt:562-606`, `DownloadEngine.kt:151-154` | M |
| **PB-3** | `ResumeSnapshot.posMs` is written but **never consumed**; `PlayerState` has no position field. `Msg.Attach` re-prepares, which resets the engine to 0 and then `play()`s. So process-death resume = restore queue, lose position, and on Android a sticky service restart **restarts the track from the beginning and autoplays**. iOS has no MediaSession resumption at all, so iOS resume is always 0. | `Orchestrator.kt:885-897`, `:899-935`, `:171-199` | M |
| **PB-4** | `Repeat.ONE` builds a 2-item engine window containing the same track twice (`nextUp === current` under repeat-one), so every track plays twice per cycle. On iOS `replaceUpNext` then `remove()`s the currently-playing item. | `Orchestrator.kt:641-655`, `Models.kt:172-184` | S |
| **PB-5** | Media3's resumption table applies an unfiltered `index` to a **cache-filtered** item list, so any eviction before the current position resumes on the wrong song. It also hand-rolls a second parser for the same artifact, ignoring `v` and `sanitizeSnapshot`. `MediaItems` carry no metadata, so a Bluetooth resume shows a blank notification. | `DylanMediaService.kt:295-339` | M |

### Download pipeline

| ID | Defect | Location | Cost |
|---|---|---|---|
| **DL-1** | `partB` is never updated from the writer, so `Range` is never re-sent on retry and **every retry restarts from byte 0**. Resume only works across a process restart. | `DownloadEngine.kt:311,371,433,504` | S |
| **DL-2** | Premature EOF is indistinguishable from success (`StreamErr.Ok` on every loop exit). With `Content-Length` the short file then hits `CORRUPT_SIZE`, which **deletes the resumable `.part`**. Without `Content-Length` the short file is accepted and committed. There is no window in which this is correct. | `DownloadEngine.kt:659-674`, `:536-543` | S |
| **DL-3** | No persisted validator. `etag` is an in-memory `var`; no `.part` sidecar, no `If-Range` on a cold resume, no comparison of the returned ETag, and the `Content-Range` parser **discards the start offset** so a 206-from-0 is undetectable. A permissive CDN serving a different rendition produces a file that passes both the size check and the `ftyp` sniff and is committed as playable. | `DownloadEngine.kt:310,423,442,451,722-728` | M |
| **DL-4** | No `catch (t: Throwable)`. A malformed URL, a `check()` in iOS `fsRename`, or any SQL error kills the job with **no terminal state**, so playback parks for 120 s behind a dead progress bar. | `DownloadEngine.kt:323,615-630` | S |
| **NET-1** | `requestTimeoutMillis = 45_000` on the bulk client caps the whole streaming GET, contradicting the stall-watchdog design and killing `stallWallFloorMs` (120 s) as dead code. | `Clients.kt:52` | S |

### Cache / DB

| ID | Defect | Location | Cost |
|---|---|---|---|
| **CA-1** | `enforceBudget`'s `count + 1` reserves a phantom slot for a download that has already inserted its row. At the 300-file cap, every completed download and every favourite **destroys one extra unrelated file**. | `CacheManager.kt:82` + 4 call sites | S |
| **CA-2** | `clearCacheExcludingProtected` snapshots `protectedKeys` once, freezes the candidate list, and re-validates nothing before delete — while the sibling `enforceBudget` *does* re-check. Tapping "clear cache" then hitting play deletes the file that just became current. | `CacheManager.kt:149-170` | S |
| **CA-3** | iOS `removeDownload` bypasses the eviction guard entirely (Android routes through `evictOne`). Deletes the currently-playing or in-flight-upgrade source file. | `IosGraph.kt:260-272` | S |
| **CA-4** | Reconciler deletes the DB row even when `fs.delete` failed, so one `EACCES`/`EBUSY`/`EMFILE` permanently loses a track — the next launch's orphan sweep deletes the file for real. | `Reconciler.kt:49-58` | S |

### Network / search

| ID | Defect | Location | Cost |
|---|---|---|---|
| **SE-1** | WS correlation is a tautology *and* actively wrong. `tryWs` is only reachable from one `collectLatest` body, so at most one request is outstanding ⇒ `head == query` is always true and ORDERED mode does nothing. Worse, the `CancellationException` handler **deletes the abandoned query from the FIFO**, so a late frame for `a` pops `ab` and is **rendered as the suggestions for `ab`**. The server payload has no query echo, so FIFO is the only correlation signal available and the code throws it away. | `SaavnSearchChannel.kt:140-189` | M |
| **SE-2** | `httpOnly` is a one-way latch for the process. | `SaavnSearchChannel.kt:63,192` | S |
| **SE-3** | `ContractDrift` cannot fail (exit 0 if 1/3 endpoints answer) and covers 3 of 6 endpoints even though fixtures for the rest are committed. | `ContractDrift.kt:418-420` | M |

### Android platform

| ID | Defect | Location | Cost |
|---|---|---|---|
| **AN-1** | `ExoPlayerEngine.release()` is called from nowhere ⇒ HandlerThread, coroutine scope, and `AudioManager` device callback leak per service cycle. Because the old registration survives, a later BT removal fires the *dead* engine's `RouteLost` at the live orchestrator. | `ExoPlayerEngine.kt:259-267` | S |
| **AN-2** | `detachEngine()` never cancels the event/position collectors. After detach, a stale `Error` advances the queue with **no engine**, a stale `RouteLost` pauses the live engine, and a stale `positionFlow` drives phantom prefetches. | `Orchestrator.kt:200-214` | S |
| **AN-3** | Notification / lock screen / Bluetooth `play()` and `pause()` are **not** forwarded to the Orchestrator, and the engine emits nothing for them. Press Play on the lock screen and the in-app UI still shows paused; the in-app play button then *re-pauses*. Two sources of truth for the most important state. | `DylanMediaService.kt:105-122`, `ExoPlayerEngine.kt:177` | M |
| **AN-4** | The `MediaSession` is never bound to the player's looper. Session calls cross into `dylan-media`; the resulting `IllegalStateException` is swallowed by **three empty `catch (_: Exception) {}` blocks**, so the notification silently stops updating with no log line. | `DylanMediaService.kt:123-126`, `:219-253` | S |
| **AN-5** | `Msg.Restore` does not re-prime the engine window (only `Msg.Attach` does). After a sticky restart the UI reports `Playing` over a silent player. The buffering path that was supposed to cover this (`bufferedPlayNow`/`bufferedToggle`) is **read and cleared but never written**. | `Orchestrator.kt:171-216` | M |

### iOS platform

| ID | Defect | Location | Cost |
|---|---|---|---|
| **IO-1** | Same-head `prepare()` can never re-emit `Prepared` (the keep-head branch doesn't reset the latch, and `.status` KVO with `[.new]` can't re-fire on an already-ready head). The resync path therefore **can never resync** — silence for 2 strikes, then a skip. | `NativeAudioOutputImpl.swift:98-112`, `:234-239` | S |
| **IO-2** | `NativeAudioOutputImpl` is mutated from the Kotlin `state` lane while AVFoundation callbacks run on main. Two unsynchronized Swift `Dictionary`s, an `AVQueuePlayer` mutated off its thread, and a `currentItem` KVO with no dispatch (so it fires on the *state lane* — a third thread). | `NativeAudioOutputImpl.swift:19,38-81,102-111` | M |
| **IO-3** | No asset catalog / no `PBXResourcesBuildPhase` / no `CFBundleIcons` ⇒ **the app cannot be archived or submitted**, and shows a blank icon on device. | `project.pbxproj`, `Info.plist` | S |
| **IO-4** | `DEVELOPMENT_TEAM = ""` in all three configurations ⇒ device builds and any `xcodebuild archive` fail at signing. Known and documented as a TODO; still a release blocker with no gate. | `project.pbxproj:419,459,502` | S |
| **IO-5** | iOS `removeDownload` deletes the playing file (see CA-3). | `IosGraph.kt:260-272` | S |

### DI / concurrency / diagnostics

| ID | Defect | Location | Cost |
|---|---|---|---|
| **DI-1** | `DownloadEngine.stop()` cancels a constructor-owned `SupervisorJob`; `start()` relaunches into the dead scope ⇒ one stop/start cycle bricks all downloads with zero log output. | `DownloadEngine.kt:122,143-149` | S |
| **DI-2** | `SaavnSearchChannel.onBackground()` writes `session = null` on the **caller's (main) thread** while the state lane mutates a non-thread-safe `ArrayDeque`. iOS calls this on `.inactive`, so **every Control-Center pull and permission dialog** races the WS engine. | `SaavnSearchChannel.kt:83-88` | S |
| **DI-3** | `WindowPreparer.sniffOk` performs blocking `stat`/`open`/`read`/`close` **on the single state lane**, 2-4× per track change and per queue edit, contradicting the rule `CacheManager.kt:60` documents two files away. | `WindowPreparer.kt:21-43` | S |

### Build / CI / release

| ID | Defect | Location | Cost |
|---|---|---|---|
| **RB-1** | `release.yml` publishes a **debug-signed APK labelled "release"**, with no keystore step and no `apksigner verify`. Un-upgradeable, well-known key, indistinguishable from a signed release in the artifact list. `docs/feedback-ledger.md:54` claims this is "Superseded" — the workflow was never removed. | `release.yml:72-89` | S |
| **RB-2** | Two gates structurally cannot fail (see §1.2). | `ProbeMain.kt:403,420`; `ContractDrift.kt:418` | S |
| **RB-3** | Android Lint disabled three times over: `abortOnError = false`, a dead `config/lint.xml`, and a stale baseline (2 of 3 line refs wrong). | `build.gradle.kts:110-114` | S |
| **RB-4** | `DylanMediaService` exported with no permission, deliberately baselined. | `AndroidManifest.xml:33-40` | S |
| **RB-5** | 8 HAR captures (6.6 MB) with replayable media-auth tokens reachable at `refs/tags/v0.1.0`. No user credentials — see §1 for calibration. | git history | M |

---

## 3. Rearchitecture

The findings cluster into seven structural problems. Fixing individual symptoms without addressing these will just re-seed the same classes of bug.

### 3.1 The concurrency contract is documentation, not a type

Today: a comment says "state owns queue/orchestrator state", `AppDispatchers` hands out four raw `CoroutineDispatcher`s, and every consumer is free to `withContext` wherever it likes. Six lane violations exist (full table in the DI audit); three of them are blockers.

**Proposal — make the wrong lane unrepresentable and enforce, don't document.**

```kotlin
// commonMain/util/Lane.kt
enum class Lane { MAIN, IO, DB, STATE }

// The only way to leave a lane. Components receive no CoroutineDispatcher at all.
class Lanes internal constructor(private val pools: Map<Lane, CoroutineDispatcher>) {
    suspend fun <T> on(lane: Lane, block: suspend CoroutineScope.() -> T): T =
        withContext(pools.getValue(lane) + LaneTag(lane)) { block() }

    fun assert(lane: Lane) { check(coroutineContext[LaneTag] == lane) { "expected $lane" } }
}
```

Consequences, in order of value:
1. `sniffOk` **cannot** run on `state` without an explicit `on(IO)` — DI-3 becomes structurally impossible.
2. `onBackground()` **cannot** mutate lane-confined state from the caller, because it has no reference to the lane's fields — DI-2 becomes structurally impossible.
3. `assert(Lane.STATE)` at the top of `Orchestrator.process`, `CacheManager.enforceBudget`, and `DownloadEngine.loop` catches the next one in the first test run rather than the first bug report. Cost: one thread-local equivalent.

**Second: the reducer pattern.** `Orchestrator` needs to stop being a coroutine that sometimes awaits. Split it:

```kotlin
// Synchronous with respect to state. No suspend. No dispatcher knowledge.
fun reduce(state: PlayerState, msg: Msg): PlayerState
```

and push every suspension into three collaborators that return a `Result` the reducer applies:

| Extracted | Owns | Kills |
|---|---|---|
| `ResolveEngine` | `nextIndex`, `advance(dir)`, wrap policy, shuffle remap, repeat-one | PB-4, and the `nextUp` vs `resolveAdvance` divergence |
| `PrepareCoordinator` | cache probe → offline gate → enqueue → await → verify → prepare, with the generation token | PB-2, PC-cancels-on-skip |
| `PlaybackEngine` (the `PlayerEngine` adapter) | window versioning, media-looper hop, `itemId`↔`SongKey`, position, `Prepared`/`TrackChanged` dedup | the stale-window-write race (PC-1), the `Prepared`-on-rebuffer autoplay |

`Orchestrator` becomes ~350 lines of routing and phase transitions, all synchronously testable with no fake clock and no engine.

**Third: one writer, versioned writes.** The single most impactful change in this document. Today `prepare`/`replaceUpNext` are fire-and-forget posts to a looper with no ack, so the orchestrator can only *guess* whether a window write landed. Make the seam request/response:

```kotlin
data class PrepareRequest(val generation: Long, val itemIds: List<String>, val firstItemId: String)
sealed interface PrepareResult { data class Ready(val itemId: String) : PrepareResult; data class Failed(val itemId: String, val err: EngineErr) : PrepareResult }
suspend fun prepare(req: PrepareRequest): PrepareResult
```

and stamp the generation into `LocalTrack.itemId` (`"g$gen:provider:songId:bits"`) so `indexOfItemId` rejects a stale prefix. Every "the engine is playing something the state machine didn't ask for" bug becomes a checked precondition instead of a race. This is also the cheapest way to make the engine seam testable on both platforms.

### 3.2 The queue algebra exists twice and has already diverged

`PlayerState.nextUp` (`Models.kt:172-184`) and `QueueStateMachine.resolveAdvance` (`QueueStateMachine.kt:17-27`) are two implementations of "what plays next". They disagree: **`nextUp` has no repeat-ALL wrap on the shuffle branch**, so at the end of every shuffled repeat-all queue the UI shows no next track, the engine window shrinks to one item, the prefetcher stops, and the wrap target is unprotected from eviction. Two separate tests each cover one axis of the 2×3 matrix, so neither catches it.

Then: `RemoveAt` of the current row leaves `queue[index] != current` (the orchestrator's own edge test *asserts* this), which makes `nextUp` point at the wrong song — **Next skips a track**. And `restore()` keeps `shuffleOn = true` with a `shuffleOrder` that `sanitizeSnapshot` dropped, which makes the queue permanently unnavigable with a "End of queue" toast on every press.

**Proposal: one function, one invariant set, one property test.**

```kotlin
// pure, total, exhaustively testable
fun nextIndex(state: PlayerState, dir: Int): Int?

// PlayerState gains a maintained nextIndex: Int? recomputed in exactly one place
// that assigns queue/index/shuffleOrder/repeat.

// Four invariants asserted by a single validator called from that one place:
assert(shuffleOn implies shuffleOrder != null)
assert(nextIndex == null || nextIndex in queue.indices)
assert(queue.isEmpty() implies index == -1)
// and if we keep queue[index] == current: assert it, or introduce an explicit
// currentKey: SongKey? — `keepCurrent` is already pretending to be that.
```

Then, in `commonTest`, a deterministic generator over thousands of valid states asserting:
- `nextIndex(state, +1) == state.nextUp` (catches the divergence outright),
- totality: if it returns `t`, then `t in queue.indices`,
- **bijection**: for fixed `order` with `repeat == ALL`, the map `index → nextIndex(+1)` is a permutation of `order` — the wrap property that is currently broken,
- `MoveWithinQueue(from,to)` then `(to,from)` restores both `queue` and `index`.

### 3.3 The cache conflates four orthogonal facts in one table

One `cached_files` row currently means "there is a playable file at `{provider}_{sanitize(songId)}_{bitrate}.{ext}` of `bytes` bytes" — and that single row is simultaneously the filesystem index, the LRU record, the pin/priority record, and the eviction work queue. Nearly every cache defect follows from that conflation.

**Proposal: split it, and make eviction a state machine.**

```sql
-- physical asset. PK includes bitrate+ext because the FILENAME does.
media_objects(provider, song_id, bitrate, ext, rel_path, bytes, state, etag, verified_at_ms)
  -- state CHECK IN ('WRITING','READY','VERIFY_FAILED')
-- per-song LRU record; never needs bitrate/ext
library(provider, song_id, last_used_ms, play_count, pinned, pinned_at_ms, active_object)
-- exactly one row, updated in the same transaction as every insert/delete
cache_totals(id=0, final_bytes, final_count, part_bytes, part_count)
-- the StateFlow, materialised; written by the single owner that owns the flow
protected_keys(provider, song_id, reason, PRIMARY KEY(provider, song_id))
```

`cache_totals` alone removes the `COUNT(*)`/`SUM(bytes)` full scans, the `partBytes()` directory walk on every budget check, and the entire double-counting question. `protected_keys` as a real table is what makes the fix for CA-1/CA-2/CA-3/CA-12 a *class* fix rather than four instances:

```sql
-- one statement, one snapshot, cannot observe a stale protection set
DELETE FROM library WHERE (provider, song_id) IN (
  SELECT provider, song_id FROM library WHERE pinned = 0 AND (provider,song_id) NOT IN (SELECT * FROM protected_keys)
  ORDER BY (play_count = 0) DESC, (last_used_ms IS NULL) DESC, last_used_ms, cached_at_ms LIMIT ?)
RETURNING provider, song_id;
```

Then eviction becomes a two-phase, crash-safe, idempotent pipeline:

```
READY ──(budget exceeded)──> EVICTING ──unlink ok──> (row gone)
  │                             │
  │                             └──unlink failed──> READY (+ log, retry next sweep)
  └──(crash mid-unlink)─────────> READY
```

Reaping becomes an indexed query over a bounded set instead of today's `mtime` guessing over the whole directory with two magic grace windows. And `state` + `verified_at_ms` make the Reconciler **cheap and idempotent by construction** — a healthy library returns zero rows and touches zero files — so the full sweep becomes a weekly job (it already has a `gc_last_ms` setting) instead of a boot cost of ~600 blocking syscalls on every cold start.

**Measured index wins** (against the real schema, `EXPLAIN QUERY PLAN` + timing):

```sql
-- REQUIRED: play_history has no index on its FK columns. 708 ms -> 24 ms at 25k songs,
-- on the single-threaded dbLane that also serves playback writes.
CREATE INDEX idx_history_song ON play_history(provider, song_id, played_at_ms DESC);
-- REQUIRED: gcSongs drives on updated_at_ms unindexed; also makes ON DELETE CASCADE cheap.
CREATE INDEX idx_songs_gc     ON songs(updated_at_ms);
-- REQUIRED: lruVictim currently sorts into a temp b-tree on every single call (verified).
CREATE INDEX idx_cached_lru   ON cached_files
  ((play_count = 0) DESC, (last_used_ms IS NULL) DESC, last_used_ms, cached_at_ms, provider, song_id)
  WHERE pinned = 0;   -- replaces idx_cached_lru_unpinned, which serves no ORDER BY
-- Deterministic pinned demotion (bulk-hearting an album currently makes it arbitrary).
CREATE INDEX idx_cached_pinned ON cached_files(pinned_at_ms, last_used_ms, provider, song_id) WHERE pinned = 1;
-- trimHistory drives off this instead of scanning + materialising a bloom filter.
CREATE INDEX idx_history_album ON play_history(played_at_ms DESC, provider, song_id);
```

Also: **the pinned sub-pool has no row budget.** 300 favourites × 4 MB = 1.2 GB < the 1.5 GB `pinnedCap`, so the demotion loop never runs, `lruVictim` (`WHERE pinned = 0`) then matches nothing, `?: break` exits, and the 300-file cap becomes **permanently unenforceable with no user-visible signal**. Give the pool a row budget derived from the same 0.75 fraction.

And: **there is no migration strategy at all.** Zero `.sqm` files, no schema version, a `catch (e: Exception) { wipe(); open() }` fallback in all three `DriverFactory` actuals, and a drift check that is a substring sniff for exactly one historical shape (`else if ("cacheable" in ddl)`). This is the only finding that gets *worse* over time, and it is the one the ledger defers to a "PROD MIGRATION" checklist. Fix it before the first release, not after.

### 3.4 Downloads: the bytes-on-disk ↔ bytes-on-wire relationship is implicit

Six of the download blockers (DL-1, DL-2, DL-3, DL-6, DL-9, and the truncation-vs-success ambiguity) are all statements about the *relationship between the bytes on disk and the bytes on the wire*. Today that relationship is implicit in "whatever `partB` happens to say", which is exactly why `partB` was never updated on the retry path — nobody can see the write-set.

**Proposal: make it a value type.**

```kotlin
data class Breakpoint(
    val partBytes: Long,         // exactly what is on disk
    val totalBytes: Long?,       // declared by the origin; persisted, never re-derived
    val etag: String?,           // strong validator; If-Range source
    val quality: Quality,
    val originResolvedAtMs: Long, // for signed-URL TTL
)
fun resumeDecision(bp: Breakpoint?, response: HttpResponse): Resume  // Append(at) | Restart | Fail
```

`totalBytes` stored rather than recomputed kills the `duration × bitrate` estimate-as-hard-bound bug (the current 90%-of-estimate floor **rejects every legitimate 128 kbps m4a** on a chunked response, deterministically, because `BITRATE_128.bps` is already 27% padded). `partBytes` fed back from the writer kills DL-1. `etag` persisted kills DL-3. `originResolvedAtMs` is what lets the engine notice a 5-minute-TTL URL expiring mid-transfer instead of discovering it as a truncation. And the `.part` + `.meta` pair gives the Reconciler something real to GC.

Then make the job body a state machine with an immutable record threaded through it, so transitions are total functions and the whole thing is testable **with no HTTP at all** — which is precisely what the current suite cannot do (the mock engine produces one status for the whole run, so **206, 416, 429, 503 and 401 are never produced by any of the 19 tests**).

**And: the priority queue should be data, not a `MutableList` + hand-rolled sort.** Five sites independently re-derive priority; two of them disagree. `requeueLater` removes a same-key job **without a priority check**, so a `USER_NOW` enqueued between a 429 and the retry timer is silently downgraded to a background retry.

```kotlin
sealed interface EnqueueResult {
    data class Queued(val id: JobId) : EnqueueResult
    data object SupersededByHigherPriority : EnqueueResult   // must NOT touch `states`
    data class Preempted(val victim: DownloadJob) : EnqueueResult
    data object Dropped : EnqueueResult                       // queue at capacity
}
```

`Preempted` as a **return value** means `enqueue` stops reaching into `executingJob` — which removes the unlocked-triple race (CC-10) entirely, because the decision is made on a snapshot the queue owns. And the queue can be bounded at construction instead of being bounded only indirectly by `enforcePartCap` deleting `.part` files out from under jobs that are merely *queued*.

**On parallelism:** `loop()` is strictly single-flight (`j.join()`) while `maxConcurrentParts = 3` and `enforcePartCap` maintains up to 3 parts. The config promises concurrency the engine does not have, and ~45 lines of part-cap machinery is unreachable. The priority model itself is right and I'd keep it — but **one download slot is too few for a music player**. Two slots (one `USER_NOW` in flight, one queued) is the minimum that makes a track boundary not stall. If you keep one slot, the non-negotiable is: **cancel the abandoned job on every generation change** (today an abandoned `USER_NOW` cannot be preempted by the replacement's equal priority, so a 3-second skip costs a full 4 MB transfer).

### 3.5 Providers and search: errors are `null`, and the WS correlation is a guess

`Models.kt:94-111` already defines `OFFLINE / NETWORK / NETWORK_TIMEOUT / RATE_LIMITED / DRIFT / FORBIDDEN_REGION` — and **no code in the provider or search channel ever constructs one**. Everything returns `null` or an empty list, so a geo-block, a rate-limit, a bot-wall HTML page, a decode break, and a genuine "no matches" are byte-identical to the user and to the logs.

**Proposal: typed results, per-element decode, one shared `Json`.**

```kotlin
sealed interface CatalogResult<out T> {
    data class Ok<T>(val value: T, val drift: List<Drift> = emptyList()) : CatalogResult<T>
    data class Err(val code: ErrorCode, val detail: String?, val retryable: Boolean) : CatalogResult<Nothing>
}
```

Three specifics, all small:
- The provider's `Json` is **missing `coerceInputValues`** — the setting that makes a null field survivable — and it is set on the `Json` instance in `Clients.kt` that **nobody uses**. One bad card in a 20-result page currently discards all 20.
- `Content-Type` is trusted over the body (`sniffExt` only runs when the header is unusable), so an HTML error page with `Content-Type: audio/mpeg` is committed as a playable mp3. The code's own comment states the correct policy; the code inverts it.
- **The WS is not a better source than HTTP here.** The response carries no query echo and no request id, so the only correlation available is strict FIFO — which is strictly *weaker* than HTTP's implicit correlation. Today the WS is treated as authoritative and HTTP as a degraded fallback, which inverts the reliability ordering. Use the WS purely as a latency optimisation with a hard 1-frame budget and a deadline; fall back on any doubt.

Make demand a value with a monotonic epoch and never render an answer whose epoch ≠ current demand; never remove an in-flight stamp from the FIFO on cancellation, because the stamp's only job is to make a late frame pop against the wrong query. And make degradation a **cooldown, not a latch**.

Drift becomes unmissable with three mechanical additions: typed primitives that return `Either<Drift, T>` so a bad field drops a *card*, not a page; a committed expectation file that `ContractDrift` fails the build on, covering **all six endpoints** plus the WS frame (fixtures for the rest are already committed); and a rule that **a fixture no test imports is a bug** — enforceable with a 15-line test that walks `fixtures/`. Three of fifteen fixtures are currently orphans, and they are precisely the three provider *error-path* fixtures (429, HTML interstitial, expired signature). `SaavnProvider` has no test file at all.

### 3.6 The platform layers are ~40% too thick, and they disagree with each other

The Android and iOS layers each contain four *playback decision-making* components that belong in `commonMain`: a hand-rolled second parser for the resume artifact, a `canNext`/`canPrev` heuristic that duplicates and disagrees with `resolveAdvance`, a hand-rolled projection of `PlayerState` into Media3 view-model objects, and (iOS) a whole `AVQueuePlayer` cursor that duplicates the Kotlin `index`.

**Move to shared:** `AudioRoute` as a `PlayerState` field (delete the `MediaHub` global); a `TransportUi` projection; `CacheManager.removableKeys`; `recordingKey` dedupe identity (a multi-script catalogue's normalisation rules are domain logic and belong in `commonMain` with unit tests, not in a `ui/components` file); `posHzFull`/`posHzMini` (already in `AppConfig`, referenced nowhere).

**Delete:** `onPlaybackResumption` + `computeResumptionItems` (−75 lines; it hijacks the engine timeline, duplicates the parser, produces a blank-metadata notification, and has 8 silent `runCatching`s); `Copy.kt`'s 10 string constants (9 of 10 are verbatim duplicates of `DylanFailure.message()`, and `strings.xml` has exactly one entry and is otherwise unused); `Tokens.kt`'s `LightTokens` + the whole `lightColorScheme` branch (the two token objects are field-for-field identical, so `t == DarkTokens` is *always* true and 22 lines are unreachable — plus `windowLightStatusBar=true` on a black background renders the clock black-on-black); `bufferedPlayNow`/`bufferedToggle`; three abandoned "Wave 3 extract" facade classes (`SnapshotStore`, `DownloadQueue`, `Fetcher`/`Verifier`) whose doc comments describe an architecture that does not exist, two of which **duplicate live mutable state as public `var`s on a non-data class**; `ExoPlayerEngine.Companion.keyOf`.

**iOS specifically: use a bare `AVPlayer`, not `AVQueuePlayer`.** The window is 1-2 items. `AVQueuePlayer` maintains its own `currentItem` cursor, its own `actionAtItemEnd` transition, and its own `items()` array — three parallel sources of truth that must be kept in lock-step with the Kotlin `index`. Every one of the iOS audio bugs (no re-`Prepared`, the ~60 lines of cursor-reconciliation bookkeeping including the `suppressCurrentItemEvents` flag, `replaceTail` removing the live item) traces back to that duplication. With `AVPlayer`: `prepare` = `replaceCurrentItem(with:)` (does not blank the audible item) + an explicit `nextItem` property; `ItemEnded` from one observer with `object: currentItem`; and `TrackChanged(AUTO)` emitted by **the shared layer's own advance decision** rather than by a KVO on a platform-maintained cursor. That deletes `currentItemChanged` and `suppressCurrentItemEvents` entirely, and it stops every track transition from discarding the preloaded next item (today `automaticallyWaitsToMinimizeStalling` buys nothing).

**Make ownership explicit.** The iOS retain-cycle audit's verdict is worth repeating: there is **no true retain cycle in the Swift↔Kotlin bridge** — `sink` is weak, every KVO/notification block captures `[weak self]`, the stores' `graph` refs are `weak`. The three real ones are `Thumbnailer → inflight → Task → Thumbnailer` (fix: one `actor`), never-removed `MPRemoteCommandCenter` / `NotificationCenter` / `warningToken` registrations, and `IosGraph → IosPlayerEngine → NativeAudioOutputImpl → AppEnvironment.output` (fine only because both ends are singletons; a real leak the moment `attachAudio` is called twice). The deeper problem is the **inverse**: the subscription graph is rooted too high. `PlayerStore`/`SearchStore`/`NowPlayingController` bind in `AppEnvironment.init`, so 4 Kotlin collectors exist from frame 1 and are cancelled only in a `deinit` that can never run.

**Handedness to remove:** three of five iOS Kotlin→Swift callbacks mutate `@MainActor`/`@State` with no isolation marker, resting entirely on an undocumented runtime property (`FlowAdapter` collects on `Main.immediate`). `PlayerStore` is hardened with `MainActor.assumeIsolated`; the other four are not. That is a hard compile error the moment `SWIFT_STRICT_CONCURRENCY` leaves `minimal`, which the notes plan. The `nonisolated(unsafe)` escape hatches (6 of them) exist only to make `deinit` compile — a `nonisolated final class SubscriptionBag` removes all six.

**Compose/SwiftUI performance, concretely:** 20 `collectAsState` → 0 with-lifecycle (mechanical, `lifecycle-runtime-compose` already a dependency, biggest measurable win in the perf-critical UI); `playNow`/`openArtist` are `val`s in `AppRoot`'s body, not `remember`ed, so they're fresh on every phase change and make all six screens non-skippable; the mini-player and the whole Now Playing sheet recompose at 10 Hz from the position flow when they only need the progress bar repainted (hoist to the draw phase — `AppRoot.kt:297` already does exactly this for the scrim, so the pattern is in the file); `downloads.progress` is read at screen level so any key's tick recomposes every visible row; `recordingKey()` strips all non-ASCII so **two unrelated Hindi songs with the same duration collide into one key, and a favourited one is filtered out of Your Favorites entirely**; `MiniPlayer`'s play/pause is a `Button` nested inside a `Button`, so **the single most-used control in the app is unreachable** on iOS; iOS rebuilds a `Set` of every download for every row on every body pass (O(n²) on a 400-song favourites list) where Android memoizes; `ForEach(id: \.offset)` and `List(id: \.title)` cause full identity churn and duplicate-key crashes.

### 3.7 The seams the code has and doesn't need, and the two it lacks

**Two missing seams are the reason 7,732 LOC is untestable:**
- `NetMonitor` is an `expect class` with a hardcoded JVM stub returning `UNMETERED`/`true`/empty. **Every orchestrator test runs with the network baked in.** The `ErrorCode.OFFLINE` fast-path — the app's single most common real-world complaint — is structurally untestable, and the metered quality policy is untestable. Make it an interface in `commonMain` with a `FakeNetMonitor`.
- `nowMs()` is a bare `expect fun` with 35 production call sites. The only way to test the 60-second orphan window is `File.setLastModified` against the real clock — which is why `ReconcilerTest` has no grace-window test at all. Make it an injected `Clock`. (`DownloadEngine` already mixes wall clock with `TimeSource.Monotonic` in the same function, and LRU ordering is wall-clock-based, so a clock correction can permanently reorder the cache or make every `.part` look older than the grace window.)

**One seam the code has and doesn't need:** `PlayerEngine` is right, but a hand-rolled ctor container in the "pure ctor — no I/O" style is *not* achieving what a DI framework would (a DI framework would not help the lane discipline at all, and would add an annotation processor to a build that has none). The problems are scope, ownership and reach-through, not the pattern: `AppContainer` is 15 eagerly-constructed `val`s any consumer can reach any other; `DownloadEngine.supervisor` and the WS engine loop are parentless `Job`s launched from constructors and reachable by no `stop()`; and `ExoPlayerEngine.kt:29` resolves its logger by casting the `Application` — the signature of a service locator. The fix is narrow facades (`DataGraph` / `FileGraph` / `NetGraph` / `PlaybackGraph`), the rule that **a component never owns a `Job`** (the owner creates the `SupervisorJob`; `DownloadEngine` receives a scope), and `log: LogBuffer` as a **required** parameter with no default — the nine `= LogBuffer.SILENT` defaults are precisely how a total-data-loss log line ("wiping dev DB") ended up in a dead ring that no platform passes a real buffer to.

**And `AppContainer`'s ctor contract is false.** The KDoc says "Pure ctor — no I/O. Call start() after setContent to avoid blocking first frame", and the ctor does `createDirectories` ×2, a full SQLite open, a schema probe, `Schema.create` and 4 PRAGMAs — before the first frame, on main, on **both** platforms (Android's `Application.onCreate` and iOS's `@MainActor AppEnvironment.init`). The `wipe()` fallback is reachable from there, so a schema mismatch silently deletes the user's entire library *before the UI has drawn*. Delete the KDoc or make it true with a suspend `open()` on the io lane.

### 3.8 Make the gates able to fail, then fix what they find

Nothing above can be verified until the gates are real, because right now none of them can go red.

**One entry point:** `tools/gate.sh`, called by both a developer and CI, running `./gradlew ktlintCheck detekt :androidApp:lintDebug :shared:jvmTest --rerun-tasks` with **no** `--continue`, **no** `abortOnError = false`, and an explicit `test -f <apk>` assertion. Then make the branch-protection check names correspond to real jobs. There are currently four different definitions of "the gate" in one repo, three of which pass while broken.

**Split hermetic from live.** Presubmit: ktlint + detekt + lint + `jvmTest` + an offline structural probe. Nightly: the live probe and `contractDrift` with real drift thresholds. `probeCi` makes live network calls from `jvmTest`'s source set and is documented as "hermetic".

**`detekt` currently enforces almost nothing.** `config/detekt.yml` is a 32-line partial override with `buildUponDefaultConfig` **unset** — the documented footgun that means the packaged defaults are never loaded, so `maxIssues: 0` governs a near-empty rule set. And the seven rules it does declare are precisely the ones that would have flagged `Orchestrator.kt` (1,030 lines, a 14-branch `when` over 14 sealed types, 199 lines) as disabled: `LongMethod`, `CyclomaticComplexMethod`, `LongParameterList`, `TooManyFunctions`, `MagicNumber`, `ReturnCount`, `TooGenericExceptionCaught` — the last of which is what lets ~30 `runCatching { }.getOrDefault()` calls swallow every real error into a default. Set `buildUponDefaultConfig = true`, re-enable `LongMethod`/`CyclomaticComplexMethod` with a baseline, and add a ~20-line CI check for `fs.`/`File(`/`java.io`/`HttpClient` calls lexically inside a `disp.state` block — that would have caught DI-3 mechanically.

**Then fix the harness before writing the tests.** The suite's 90 green tests are much weaker than they look:
- `dbLane`/`state` run on **`Dispatchers.Default`**, not `limitedParallelism(1)`. The serialization invariant is untested by construction, and the tests are not a weaker version of production — they are a *more permissive* system, so latent missing-hop bugs are masked by scheduling luck and exposed on device.
- `FakeEngine.prepare()` emits `Prepared`+`TrackChanged` **synchronously inside `prepare()`** (no real engine does that), never emits `ItemEnded` for a 1-item window (which ExoPlayer's `if (idx > 0)` makes impossible), never emits `TransitionReason.SEEK`, has a `positionFlow` frozen at 0 with `play() = Unit`, and `seekTo(ms) = Unit` — so `enginePositionMs()` always takes the fallback branch in tests and the real branch in production. `Intent.Seek` has **zero** test references, and `FakeEngine` could not assert one.
- Nine tests cannot fail. `assertTrue(ch.debugState().contains("mode=UNORDERED") || ch.correlationMode == ORDERED)` is `A || !A` — and the comment states the opposite of what it permits. `pinnedDemotesOldestPinFirstAndNeverBlocksFavorites` asserts `all { pinned_at_ms >= 10 }` on rows inserted with `>= 10`, and `all{}` on an empty list is `true`. `partBytesCountTowardUsage` passes identically whether or not parts are counted. `playNowSupersedesPendingSettleAdvance` passes with the `Next` deleted.

Fix: a `TestLanes` factory on one `StandardTestDispatcher`; a `FakeEngine` that ticks position on virtual time, queues events instead of emitting them from `prepare`, records `seekTo`, and overrides `currentTimeMs`; a per-request scripted `MockEngine`; and an `EngineContract` abstract suite that both real engines must pass. Move the pure-logic tests into `commonTest` (the dependencies are already declared and `shared/src/commonTest` **does not exist**) so they run on every target, and enable `iosSimulatorArm64Test` and an `androidUnitTest`/Robolectric lane. That single addition closes most of the 4,469-LOC `androidApp` gap and turns the `FakeEngine`-vs-reality divergence from a comment into a compile-time-enforced contract.

### 3.9 Two things that look like debt but are the highest leverage

**`docs/feedback-ledger.md` contains at least four verifiable false "Done" claims** and one self-contradiction:
- "I-ci-release | Silent debug-signed release | **Superseded**" — `release.yml` still does exactly this.
- "D-2e | dual protectedKeys writers | **Done** | … single-writer comment" — **the comment is the fix.** `Orchestrator.publishProtected()` still writes a strict subset of what `AppContainer`'s `combine` computes, and `CacheManager.kt:17-21` still says "Do not add another writer".
- "P1-4 | Repeat.ONE/shuffle exhaustion | **Done** | … remapShuffleOrder preserves permutation" — `remapShuffleOrder` has **zero tests** and regenerates the whole permutation on every size change.
- "all `--no-configuration-cache` flags stripped" — three remain, **one of them in the shipped iOS build phase** in the pbxproj.
- Verified "2026-09-04 against main"; HEAD is three weeks later.

A ledger that is confidently wrong is worse than no ledger: it is the mechanism by which a future reader concludes these classes of bug are handled.

**`upgradeSourceKeys` has six reads and zero writes.** It is permanently `emptySet()`. The intended invariant is stated in the code itself (`CacheManager.kt:17-20`: "current + next-up + in-flight + **upgrade sources**"), and with it dead, an in-flight 128→320 upgrade's *source file* is LRU-evictable — so if the 320 transfer fails (the common case on metered), the user is left with **no copy of a track they already had offline**. A 5-line fix to a documented feature that is currently a no-op.

---

## 4. Sequenced plan

Ordered so that each phase makes the next one verifiable.

### Phase 0 — this week (security + gates; all S)

| # | Action | Ref |
|---|---|---|
| 1 | Add `android:permission` to `DylanMediaService`; delete the `ExportedService` baseline entry | RB-4 |
| 2 | Exclude `database` and `file/logs/` from `backup_rules.xml` **and** `data_extraction_rules.xml` | §1.5 |
| 3 | Delete `release.yml`; add a `gate` job all `builds.yml` jobs `needs:`; make the keystore skip `exit 1` on tag pushes; add the missing `exit 1` to `version.yml`'s retry loop | RB-1 |
| 4 | `probeCi` → `gates = true`; `ContractDrift` → non-zero on real drift; `abortOnError = true`; delete the dead `config/lint.xml`; regenerate the baseline; drop `--continue` and `|| true` from `check.sh`; assert artifacts exist | RB-2, RB-3 |
| 5 | `git filter-repo` the HARs + force-push tags (or make the repo private); move `keystore.properties` to a secret and rotate if it ever signed a release; untrack `tools/probe-results.md` and write probe output to `build/`; add `hs_err_pid*.log` to `.gitignore` | RB-5, §1.5 |
| 6 | `DylanApp.release()` → call `ExoPlayerEngine.release()`; `setSessionLooper(e.mediaLooper)`; delete the three empty `catch` blocks and log instead | AN-1, AN-4 |
| 7 | `iOS: canRemove(key)` through `CacheManager`'s three key sets; `enforceBudget(pendingRows = 1)` as an explicit param at the 5 call sites; Reconciler row-delete conditional on unlink success | CA-1, CA-2, CA-3, CA-4, IO-5 |
| 8 | `partB` fed from the writer; `catch (t: Throwable)` → terminal `Failed`; drop `requestTimeoutMillis` from `bulkClient`; split `StreamErr.Storage` from `Network` | DL-1, DL-2, DL-4, NET-1 |
| 9 | `try/catch` in the inbox consumer; same for `startTicking`/`watchSession`; cancel the collectors in `Msg.Detach`; make `DownloadEngine.supervisor` re-creatable | PB-1, AN-2, DI-1 |
| 10 | `onBackground()` → `scope.launch(disp.state)`; `sniffOk` → `suspend` + `withContext(io)`; `io = Dispatchers.IO` on iOS (the "does not exist on Native" comment is false for coroutines 1.11.0) | DI-2, DI-3, GR-5 |

### Phase 1 — 1 week (correctness of visible features)

`posMs` into `PlayerState` + one `seekTo` after attach/prepare; single-`nextIndex` algebra + the 2×3 matrix test; delete `publishProtected()`; write `upgradeSourceKeys`; `Repeat.ONE` window guard; honour `job.bitrate`; WS correlation (stop deleting the FIFO stamp on cancel) + a cooldown instead of a `httpOnly` latch + a handshake deadline; `canNext`/`canPrev` from `QueueStateMachine` and drop `COMMAND_GET_TIMELINE`; forward notification `play()`/`pause()`/`seekTo()` to intents and emit `Interrupted` on focus change; `ClearUpNext`/`PlayNext` shuffle remap fix; iOS `MiniPlayer` nested-`Button`; iOS slider-swap-on-load; iOS same-head `Prepared`; iOS `onBackground` on `.inactive` → `.background` only; `collectAsStateWithLifecycle` ×20; `remember` the `AppRoot` lambdas.

### Phase 2 — 2 weeks (make the test suite able to fail)

`buildUponDefaultConfig = true` + re-enable the complexity rules with a baseline. `TestLanes` + `FakeEngine` rewrite + `commonTest` source set + `iosSimulatorArm64Test` + `androidUnitTest`. The missing indexes (2 `CREATE INDEX` lines, **708 ms → 24 ms measured**). Batch the N+1s with one JOIN. `cache_totals` + `protected_keys` tables and the single-statement eviction. Typed `CatalogResult` + per-element decode + `coerceInputValues` + magic-before-header. The download resume/breaker matrix (206/416/200-for-Range/429/503/401) with a per-request `MockEngine`. `SaavnProviderTest` over the three orphaned error fixtures + a test that every fixture is referenced. Property tests for the queue algebra.

### Phase 3 — 3 weeks (the structural work, in this order)

1. **SQLDelight migrations** (`.sqm` chain + `user_version` + drop the `wipe()` fallback) — do this before the schema grows further, not after.
2. **Cache state machine** (`media_objects` / `library` / `cache_totals` / `protected_keys`; two-phase eviction; cheap idempotent Reconciler on a weekly cadence).
3. **`Breakpoint` + immutable `Attempt`** for downloads; typed `EnqueueResult`; bounded queue; then 2 download slots.
4. **Lane enforcement** (`Lanes`/`Lane` + `assert` + a CI lexical check) and the `Lanes`-based reducer split of `Orchestrator` into `ResolveEngine` / `PrepareCoordinator` / `PlaybackEngine`, with the generation-stamped request/response engine seam.
5. **Injected `Clock` and `NetMonitor` interface.**
6. **Platform layer thinning** (delete the four duplicated decision components, move 6 things to shared, `AVPlayer` over `AVQueuePlayer`, `AudioRoute` into `PlayerState`).
7. **Ownership**: narrow graph facades, no component-owned `Job`s, `log: LogBuffer` required everywhere, thread both CEHs into the `LogBuffer`, promote the download `Step` machine to INFO, add a `logMinLevel` param to `IosGraph.create`, make the iOS graph construction async behind a splash gate.

### Phase 4 — continuous

Re-verify `docs/feedback-ledger.md` against the code and correct the four false "Done" rows, or delete it. Correct `README.md` (six inaccuracies: the `NetClass` source set, the inert rate-wall, the missing `:androidApp:lintDebug` in the stated gate, three non-sequitur/ordering claims). Correct `docs/architecture.md` (the seam table names a `CatalogProvider` that doesn't exist and a `SearchChannel` "HTTP-only fallback" that is a mode inside one class; `stop()` and `FileLogSink.close()` are documented as lifecycle steps and have **zero** call sites). Correct `iosApp/BUILD-NOTES.md` (D3 "behavioral choice, not a bug" is a bug; F8/§4 parity claims are false; the `Embed Frameworks` phase double-signs a framework KGP already embeds). Extend `iosApp/Tools/check_pbxproj.py` (not in CI; the documented invocation always crashes; add the missing icon and `DEVELOPMENT_TEAM` assertions) and add an icon set. Add `setRate` + `skipForward`/`skipBackward` to the engine seam. Re-derive `cacheMaxBytes` from `cacheMaxFiles × expectedMeanBytes` (the 2 GB figure is unreachable — 300 × 320 kbps tracks is ~255 MB, so the file cap binds 6× earlier and the entire pin-demotion path is dead), and reconcile `stallWallCapMs` with `readyTimeoutMs` (a user is told the download failed at 2 minutes and is then billed for 18 more minutes of cellular).

---

## 5. Findings index

| Prefix | Layer | Blockers | Critical | Major | Minor |
|---|---|---|---|---|---|
| PB / PC / PM / PN | Playback core | 5 | 10 | 10 | 8 |
| DL / DC / DM / DN | Download pipeline | 5 | 4 | 13 | 17 |
| CA / CC / CM / CN | Cache / DB / repos | 4 | 10 | 11 | 9 |
| SE / SC / SM / SN | Network / provider / search | 3 | 5 | 12 | 18 |
| AN / AC / AM | Android platform | 5 | 10 | 16 | 22 |
| IO / IC / IM / IN | iOS platform | 5 | 16 | 32 | 25 |
| DI / CC / LC / GR / LG / CF / PA | DI / concurrency / diagnostics / config | 3 | 14 | 11 | 8 |
| RB / RC / RM / RN | Tests / build / CI / hygiene | 5 | 12 | 12 | 10 |
| **Total** | | **43** | **87** | **121** | **119** |

Full per-finding detail — location, quoted evidence, failure scenario, fix sketch, and cost estimate for each of the ~370 findings — is available in the eight per-layer audit reports. Ask and I'll write any layer out in full as its own document.
