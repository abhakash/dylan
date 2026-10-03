# Dylan — Fuzzy / System Test Strategy (SQLite-level verification)

**Status:** design only. Nothing in this document has been implemented.
**Scope:** `shared` (Kotlin Multiplatform core) on the **JVM target**; Android platform layer only
for the device tier. iOS/Swift is out of scope (Xcode is not installed in this environment).
**Every claim below is cited as `file:line`. Claims I could not verify by reading are marked
`UNVERIFIED` and are stated as hypotheses the suite is designed to settle.**

---

## 1. Goals / non-goals

### 1.1 What this suite is for

The unit suite is good at parts. What it cannot do is drive the four components whose correctness is
only defined by their *interaction with real SQLite and a real filesystem over a long horizon*:

| # | Property that lives nowhere but the interaction | Where it lives today |
|---|---|---|
| G1 | The trigger-maintained totals in `cache_totals` agree with the rows they summarise, after every interleaving. | `dylan.sq:106-113` (table), `:142-164` (triggers) |
| G2 | `library` ↔ `media_objects` stay in lockstep under insert/update/delete/cascade. | `dylan.sq:169-209` |
| G3 | The bytes-on-disk ↔ bytes-on-wire contract (`Breakpoint`) survives a real HTTP body that is cut off, reset, resized or answered with the wrong status. | `Breakpoint.kt:36-75`, `Transfer.kt:141-219` |
| G4 | The LRU eviction policy never destroys the file the user is listening to, and never destroys the whole pool instead of the excess. | `CacheManager.kt:291-318`, `:417-423` |
| G5 | Nothing leaks: no orphan rows, no `.part` with no record, no `protected_keys` entry with no row, no file with no row. | `Reconciler.kt:44-64` |
| G6 | A migration from a store the current code **cannot produce** is still total and still non-destructive. | `1.sqm` |

**Goal:** a suite that drives real interleavings against real SQLite (a real `JdbcSqliteDriver` over
a real file — never an in-memory fake), a faked network, and a fake audio engine, then asserts on
the **persisted database contents and the filesystem**, not on return values or `PlayerState`.

**Explicitly the goal is to make invariants and safety properties the oracle** (§5.7), because with
concurrency more than one interleaving is legal at any instant and an exact-sequence oracle would be
both wrong and flaky.

### 1.2 Non-goals — what this suite will NOT prove

* **It does not prove the audio path works.** No real decoder, no real `ExoPlayer`, no real audio
  route. `FakePlayerEngine` (`support/FakePlayerEngine.kt`) is a transcription of the rules, and
  `EngineContractTest` (`support/EngineContractTest.kt:31-33`) is the compile-time obligation that
  the transcription has not drifted. Whether the *real* engine honours that contract is a device
  question (§3, tier D).
* **It does not prove the Android lifecycle.** `MediaSessionService`, the FGS notification, the
  `HandlerThread("dylan-media")` looper and `Activity` recreation are exercised only by the
  instrumented tier (§3 D2), and even there only against a fake engine unless a device lane lands.
* **It does not prove anything about the live JioSaavn API.** No network egress. The hostile origin
  is `MockEngine` (§5.4). Live gates stay nightly (`ci.yml:261-301`).
* **It does not prove performance.** It may *bound* things (no unbounded retry loop, no unbounded
  map growth) but it does not assert on latency. A wall-clock assertion in a fuzz suite is a flake
  generator; where a bound matters (§7 `S-DL-11`) the assertion is on a *counter*, not on elapsed ms.
* **It does not replace the existing suites.** `QueueStatePropertyTest`, `CacheManagerTest`,
  `DownloadEngineTest`, `IntentMatrixTest`, `OrchestratorEdgeTest`, `CacheMigrationTest`,
  `SingleFlightStressTest` stay as they are. This plan adds a layer above them; where a scenario
  duplicates an existing test, the existing test is cited and the scenario is dropped (§7 preamble).
* **It does not run on iOS.** `commonTest` already carries the pure generators
  (`commonTest/kotlin/dylan/common/QueueStateGenerator.kt`) and those keep running on
  `iosSimulatorArm64Test` (`ci.yml:213`). Everything new here is `jvmTest` because `okio.FileSystem`
  decoration and `java.nio` faulting need the JVM.

### 1.3 Why now — the evidence this is needed

From `docs/feedback-ledger.md` §5a, verified against the tree:

* **SF-1** (`feedback-ledger.md:92`): the single-flight duplicate-load bug "passed locally every
  time and failed on CI ~1 run in 3" because the virtual-time lanes close the interleaving the bug
  needs. `ResilientClient.kt:222` (`inflight[key]?.let { return Decision.Join(it) }`) vs
  `:224` (`inflight[key] = fresh`) is the window. Verified: the fix is present
  (`ResilientClient.kt:186-197`) and `SingleFlightStressTest` exists
  (`SingleFlightStressTest.kt:43-44`, production lanes, 256 openers / 8 keys).
* **SF-2** (`feedback-ledger.md:93`): `kotlin.assert` for the lane invariant. Verified fixed —
  `LaneViolation` is thrown in every build (`util/AppDispatchers.kt:20-22`, `:144-147`).
* Nine "tests that cannot fail" and a harness that was *more permissive than production* are claimed
  at `docs/codebase-audit.md:392-395`. **I re-verified all four harness claims and they are now
  false** — see §12.3. The *class* of problem is real and the plan must be able to prove each new
  test can fail (§5.8).

### 1.4 Two findings this plan is already going to surface (read before scheduling the work)

Both are verified by reading code. Both are in the invariant catalogue, so both will be caught
mechanically. **Neither is fixed by this plan** — the plan's job is to make them red and provable.

**F-1 — `1.sqm` is applied as 33 non-transactional statements, and `user_version` is written
afterwards.** `Dylan.Schema.migrate` runs `migrateInternal` (`DylanImpl.kt:501-521`, generated,
in `shared/build/generated/sqldelight/code/Dylan/commonMain/dylan/db/shared/`), which issues
**33 separate `driver.execute` calls** (`DylanImpl.kt:276-497`) with **no enclosing transaction**
(verified: no `driver.transaction` / `BEGIN` outside trigger bodies in that file). The JVM factory
calls it directly and sets the version only on return (`DriverFactory.jvm.kt:84-88`):
`Dylan.Schema.migrate(driver, from, target)` then `setUserVersion(driver, target)`.
`Schema.version` is **2** (`DylanImpl.kt:25-26`), and `migrateInternal` applies the chain for any
`oldVersion <= 1 && newVersion > 1` (`DylanImpl.kt:276`).

*Consequence:* a process death between statement *k* and *k+1* leaves a store at `user_version = 1`
whose `cached_files` may already have been `DROP`ped (`1.sqm:55`). The next open takes the
`from < target` branch (`DriverFactory.jvm.kt:84`) and re-runs the chain from statement 1, whose
first statement is `CREATE TABLE library` (`1.sqm:14`) — which now exists. The migration aborts; the
version is never advanced; every subsequent open fails identically. `allowWipeOnCorruption` defaults
to `false` on both platforms (`DriverFactory.jvm.kt:22`, `DriverFactory.android.kt:37`), so the file
is **not** deleted — the install is permanently unopenable with the user's data intact but
unreachable. On Android the open happens in `Application.onCreate`
(`DriverFactory.android.kt:41-50`), i.e. before the first frame.

*Recommendation (owner's call, not part of this plan): route `applyMigrations` through
`Dylan(driver).transaction { … }` so migrate + version-set are atomic. `Dylan` extends
`TransacterImpl` and `db.transaction { }` is already used at `Orchestrator.kt:1167` and
`LibraryCommitter.kt:48`.

**F-2 — `cache_totals.part_*` has no writer during a session.** `setPartTotals` is
`UPDATE cache_totals SET part_bytes = ?, part_count = ? WHERE id = 0` (`dylan.sq:493-494`) — an
`UPDATE`, so it **cannot create the singleton row** if it is absent, and `partTotals()` reads
`COALESCE(…, 0)` (`dylan.sq:496-498`). The only production caller of the walking form is the boot /
weekly sweep (`Reconciler.kt:55` → `CacheManager.refreshPartTotals`, `CacheManager.kt:214-222`); the
O(1) form documented as "for a caller that already tracks its own parts (the download engine)"
(`CacheManager.kt:224-228`) has **zero callers** — verified by grep across
`shared/src/{commonMain,androidMain,iosMain,jvmMain}` and `androidApp`. So
`claimLruVictims` reads a `part_bytes` that only the reconciler ever refreshes
(`CacheManager.kt:303-304`). Impact: the byte budget is computed against a stale `.part` figure
between boots — over- or under-evicting by the size of the in-flight parts. Scenarios
`S-CACHE-06` / `S-CACHE-07` and invariant `INV-06` make this visible.

*(Not verified, and stated as such: I have not confirmed whether Android's `SQLiteOpenHelper`
wraps `onUpgrade` in a transaction, which would bound F-1 on Android to "not at all". The JVM path
is definitively non-transactional. `S-MIG-06` settles it empirically.)*

---

## 2. System under test

### 2.1 Component map

```
                      ┌──────────────────────────────────────────────────────────────┐
   UI / notification   │ androidApp  MainActivity, AppRoot, LibraryScreen, …          │
   / media-browser     │ DylanMediaService (Media3 MediaSessionService, FGS notif.)  │
                      │   ForwardingPlayer → orchestrator.submit(Intent)             │
                      │      DylanMediaService.kt:107-140, :165-177, :331-346          │
                      └───────────────┬──────────────────────────────────────────────┘
                                      │  Intent
                      ┌───────────────▼──────────────────────────────────────────────┐
   lane-confined       │ Orchestrator                                                  │
   state machine       │  inbox: Channel<Msg>(UNLIMITED)   Orchestrator.kt:79       │
                      │  Msg = I | E | Attach | Detach | Background | Restore  :161   │
                      │  one message, one guard                    :252-264, :266    │
                      │  Phase = Idle|Resolving|Downloading|Ready|Playing|Paused|Error│
                      │  playGeneration gates every async effect          :103, :1392 │
                      │  QueueStateMachine (pure "what plays next")  QueueStateMachine │
                      │  WindowPreparer (cachedRow/sniffOk)        WindowPreparer.kt │
                      └──────┬───────────────────────────────────┬───────────────────┘
                             │ Intent/enqueue                    │ cacheManager.*
        ┌────────────────────▼───────────────┐   ┌───────────────▼──────────────────┐
        │ DownloadEngine                    │   │ CacheManager                      │
        │  JobQueue(256) DownloadQueue.kt:49│   │  protection set is a TABLE       │
        │  WORKER_COUNT = 2   :780          │   │  claimLruVictims      :291-318    │
        │  Step = HYDRATE QUALITY DEDUPE    │   │  demotePins           :345-368    │
        │        SIZE RESOLVE REQUEST       │   │  unlinkAll/finishReap :374-398    │
        │        VERIFY COMMIT     :836     │   │  reapEvicting         :248-267    │
        │  Transfer (body copy + watchdog)  │   │  evictOne             :182-201    │
        │  PartStore (.part + .part.meta)   │   │  clearCacheExcluding  :149-175    │
        │  LibraryCommitter (3 statements)  │   │  refreshPartTotals    :214-222    │
        │  Breakers (per-host)               │   └───────────────┬──────────────────┘
        └──────┬────────────────────────────┘                   │
               │ provider.resolveStream + bulk HttpClient       │
        ┌──────▼────────────────────────────────────────────────▼──────────────────┐
        │ MusicProvider (SaavnProvider) · ResilientClient (JSON/negative cache/    │
        │ single-flight) · Reconciler (boot + weekly sweep) · Repos · SettingsStore│
        └──────┬──────────────────────────────────────────────────────────────────┘
               │  SQLDelight
        ┌──────▼──────────────────────────────────────────────────────────────────┐
        │ SQLite: songs · library · media_objects · cache_totals · protected_keys  │
        │         favorites · play_history · search_history · settings ·          │
        │         home_cache · download_intents · VIEW cached_files                │
        │  triggers: trg_library_total_{ins,del,upd} · trg_library_asset_{ins,upd} │
        │            trg_library_drop_assets · trg_media_drop_library ·           │
        │            trg_intent_priority_{ins,upd}                                │
        │  dylan.sq (v-current) · migrations/1.sqm (0→current content, applied    │
        │  when user_version ≤ 1)  DriverFactory.jvm.kt:73-91                      │
        └─────────────────────────────────────────────────────────────────────────┘

   Four lanes (AppDispatchers.kt:105-176), all single-permit except IO:
     STATE — the inbox and every lane-confined mutable field in the Orchestrator
     DB    — the single SQLDelight driver; every query and every transaction
     IO    — unlink, sniff, part sweep, transfer body copy (multi-permit)
     MAIN  — bridge delivery
   Lane confinement is enforced at runtime by LaneViolation (AppDispatchers.kt:20-22, :144-147)
   and lexically by tools/lane-check.sh (ci.yml:61).
```

### 2.2 The state machines

| Machine | States / alphabet | Owner file | Guard |
|---|---|---|---|
| **Playback phase** | `Idle → Resolving → Downloading → Ready → Playing ⇄ Paused`, plus `Error` | `model/Models.kt:150-176`; assigned at `Orchestrator.kt:359, 603, 639, 1015, 463, 451, 765, 783, 899` | `QueueInvariantViolation` from `PlayerState.validate` (`Models.kt:439-481`) on the **state** lane |
| **Queue algebra** | `(queue, index, shuffleOrder, shuffleOn, repeat) → nextIndex` | `Models.kt:364-392`; `QueueStateMachine.kt:14-23` | pure + total; `validate` at `:439-481` |
| **Download job** | `HYDRATE → QUALITY → DEDUPE → SIZE → RESOLVE → REQUEST → VERIFY → COMMIT`, loops on retry | `DownloadEngine.kt:452-591`, enum `:836` | `runCatching` → terminal `JobState.Failed` (`:596-606`); `CancellationException` → `onCancelled` (`:593-595`) |
| **Body copy** | `Ok / Truncated / Network / Storage / Stall` | `Transfer.kt:61`, verdict `:615-624` | stall watchdog `:626-645`; `Breakpoint.wrote` is the only mutator of `partBytes` |
| **Rendition lifecycle** | `WRITING / READY / VERIFY_FAILED / EVICTING` | `dylan.sq:86-101` | two-phase claim→unlink→drop (`CacheManager.kt:374-398`); boot reap `:248-267` |
| **Circuit breaker** | `CLOSED / OPEN / HALF_OPEN` per host | `Breaker.kt:8, 67-83, 124-158` | monotonic clock `Breaker.kt:184` |
| **Single-flight catalog** | `Hit / Join / Lead` per key | `ResilientClient.kt:132-144, 202-226` | `Mutex` + identity-checked cleanup `:186-197` |
| **Container lifecycle** | `IDLE / STARTING / RUNNING / STOPPING` | `AppContainer.kt:60-65, 338-400` | one `AtomicReference` CAS |

**Note on `WRITING` / `VERIFY_FAILED`:** `beginWrite`, `promoteObject` and `failObject`
(`dylan.sq:441-454`) have **zero callers in the entire tree** — production or test (verified by
grep). So in a store written by the current code, `media_objects.state` is only ever `READY` or
`EVICTING`, `reapableObjects` (`dylan.sq:424-431`) only ever returns `EVICTING` rows, and
`Reconciler.reapInterruptedWrites` (`Reconciler.kt:71-95`) is only reachable from a store written by
an older build or corrupted externally. Two consequences for this plan:

1. The scenarios in journey 10 must **seed those states directly** (the brief asks for "v0 states
   the current code cannot produce"), because no amount of fuzzing will produce them.
2. `INV-16` is written as a **canary in both directions**: in a healthy store there must be no
   `WRITING` row (so if a future writer starts using `beginWrite`, the suite says so), *and* when
   one is seeded, the reconciler must reap it.

### 2.3 The persistence model, in one paragraph

There is exactly one filesystem index, the name grammar `<provider>_<songId>_<bitrate>.<ext>`
(`cache/CachePath.kt:41-52`), so the on-disk name is a pure function of the row and no `rel_path`
is persisted (`dylan.sq:24-27`). `library` is *one row per song* and is simultaneously the LRU
record, the pin record and a copy of the active rendition's descriptor (`dylan.sq:53-73`).
`media_objects` is *one row per rendition on disk* and is derived from `library` by triggers
(`dylan.sq:169-209`) — never a second truth. `cache_totals` is a materialised singleton so budget
checks are O(1) and `.part` bytes never need a directory walk (`dylan.sq:15-16`). `protected_keys`
is the live protection set **as a table** so victim selection and the guard share a snapshot
(`dylan.sq:115-120`, `CacheManager.kt:29-46`). The reader surface `cached_files` survives as a VIEW
(`dylan.sq:127-130`) so no caller had to change.

**What that means for an oracle:** a post-condition is a statement about *four* things at once —
`library`, `media_objects`, `cache_totals`, and the audio directory — plus optionally
`download_intents`, `protected_keys` and the `.part.meta` sidecars. A check that only looks at
`PlayerState` or at a return value cannot see most of the defects this codebase has actually had.

---

## 3. Test taxonomy

Six tiers. Each states what it may assert and what it must not.

| Tier | Name | Runner | Real SQLite? | Real FS? | Real threads? | Hermetic? | Budget |
|---|---|---|---|---|---|---|---|
| **P0** | Pure algebra property fuzz | `jvmTest` + `commonTest` | no | no | no | yes | <20 s |
| **P1** | Schema / SQL invariant unit | `jvmTest` | **yes** (file) | no | no | yes | <30 s |
| **P2** | Pipeline fuzz (download + cache) | `jvmTest` | **yes** | **yes** | **yes** | yes | <90 s |
| **P3** | Graph/session fuzz (orchestrator + container) | `jvmTest` | **yes** | **yes** | **yes** | yes | <120 s |
| **N1** | Long soak / multi-seed / mutation gate | `jvmTest` (own task) | yes | yes | yes | yes | nightly, ≤20 min |
| **D** | Device: Robolectric + instrumented | `androidApp` | yes (on device) | yes | yes | mostly | nightly |

### 3.1 Journey → tier map

| Journey | Primary tier | Secondary |
|---|---|---|
| 1. Long queue, skip fwd/back, aggressive seek, repeat/shuffle mid-track | **P0** (algebra) + **P3** (window/phase) | D2 |
| 2. Many downloads over a hostile network | **P2** (+ **P1** for `Breakpoint`/size-band tables) | N1 |
| 3. Same track repeatedly, 128 vs 320, while playing / cached / being evicted | **P2** | P3 (protection) |
| 4. Favourite/unfavourite, queue ordering, `ClearUpNext`, `PlayNext` | **P1** (pin invariants) + **P3** | — |
| 5. Kill the app 9 ways | **P2** (kill mid-transfer) + **P3** (kill mid-transaction) + **D2** (adb) | N1 |
| 6. Storage pressure, files deleted/corrupted underneath, corrupt DB | **P2** | P1 (reparability) |
| 7. Clock jumps, positive/negative cache TTL | **P1** + **P2** | — |
| 8. Concurrent multi-window intents | **P3** | N1 |
| 9. Reorder/rotate, Activity recreate with playback live | **P3** (state survives) | D2 (real recreate) |
| 10. v0 → `1.sqm` migration incl. unreachable v0 states | **P1** | D2 for the Android driver |

### 3.2 What each tier may and may not assert

* **P0** asserts pure-function equalities. It may not touch a driver, a file or a clock. It must
  live in `commonTest` so iOS runs it too (precedent:
  `commonTest/kotlin/dylan/common/QueueStateGenerator.kt`).
* **P1** asserts **SQL-level post-conditions only**. It is allowed to write hostile rows directly
  with raw SQL, because that is the only way to reach states no current writer can produce. It must
  not depend on the download engine.
* **P2** asserts post-conditions on a quiesced engine: no in-flight job, no claim in progress.
  Every scenario must end in a `quiesce()` call that proves quiescence rather than sleeping.
* **P3** asserts invariants of `PlayerState` **and** the post-conditions of P1/P2, never an exact
  emission sequence (§5.7).
* **N1** is P2+P3 with many seeds, repeated, plus the mutation gate (§5.8).
* **D** may assert on notification presence, FGS type, session command sets, and real Activity
  recreation. It is the only tier that may assert anything about `ExoPlayerEngine` or
  `DylanMediaService`. Out of PR scope entirely.

---

## 4. HLD — harness architecture

```
                                   ┌──────────────────────────────────────────┐
                                   │  Scenario (a @Test function)             │
                                   │  seed, config, fault script, journey    │
                                   └───────────────────┬──────────────────────┘
                                                       │ build
                                   ┌───────────────────▼──────────────────────┐
                                   │  FuzzHarness                             │
                                   │  · one temp root, one real SQLite file   │
                                   │  · TestLanes.production()  (real lanes)  │
                                   │    or TestLanes.virtual(scheduler)       │
                                   │  · MutableClock (wall) + real monotonic  │
                                   │  · FakePlayerEngine (real or virtual looper)
                                   │  · GraphHarness wiring reused verbatim   │
                                   │  · EnvironmentLedger (what we broke)     │
                                   └──┬──────┬──────┬──────┬──────┬──────┬────┘
                                      │      │      │      │      │      │
        ┌─────────────────────────────┘      │      │      │      │      └──────────────┐
        │                        ┌───────────▼─┐  ┌─▼────────────┐ ┌▼──────────────┐  │
        │                        │ OriginScript│  │ FaultyFileSys│ │ ProcessDeath  │  │
        │                        │  scripted   │  │ ENOSPC/EACCES│ │  abort scope  │  │
        │                        │  HTTP + WS  │  │ /EIO/delete  │ │  cut the drv  │  │
        │                        │  (MockEngine│  │  fsRename    │ │  at stmt k    │  │
        │                        │   + WS)     │  │  (FS/SQLite) │ │  clock jumps  │  │
        │                        └─────────────┘  └──────────────┘ └───────────────┘  │
        │                                                                          │
        │   ┌──────────────────────────────────────────────────────────────────┐    │
        └──►│  SYSTEM: AppContainer / Orchestrator / DownloadEngine /         │◄───┘
            │          CacheManager / Reconciler / ResilientClient             │
            │          over  real Dylan.db  +  real audio dir                  │
            └───────────────────────┬──────────────────────────────────────────┘
                                    │ after every step, and at the end
            ┌───────────────────────▼──────────────────────────────────────────┐
            │  DbOracle                                                     │
            │   1. ledger.scope()      — which post-conditions are suspended   │
            │   2. CONSTRAINT group   — negative writes must throw             │
            │   3. TOTAL group        — cache_totals vs SUM/COUNT              │
            │   4. REFERENTIAL group  — orphans, cascades, lockstep            │
            │   5. DISK group         — rows ↔ files ↔ .part ↔ sidecar         │
            │   6. BEHAVIOUR group    — intents, pins, states, EVICTING, …     │
            │   7. PLAN group         — EXPLAIN QUERY PLAN has no TEMP B-TREE  │
            │  →  List<Violation> with a human-readable counterexample         │
            └────────────────────────────────────────────────────────────────┘
```

### 4.1 Component boundaries (one sentence each)

* **`OriginScript`** — owns *what the network does*. Knows nothing about the app.
* **`FaultyFileSystem`** — owns *what the disk does*. A delegating `okio.FileSystem` decorator.
* **`EnvironmentLedger`** — owns *what we did on purpose*, so the oracle can distinguish
  "the app broke the invariant" from "the test broke the world and the app must repair it".
* **`DbOracle`** — owns *what correct means*. Pure reads + a violation list. No writes except the
  `CONSTRAINT` group, which is write-and-expect-throw inside a rolled-back transaction.
* **`FuzzHarness`** — owns *the graph*, reusing `GraphHarness` verbatim where possible.
* **`ProcessDeath`** — owns *the only thing no other component can fake*: a real mid-flight abort.

### 4.2 Data flow, per scenario step

```
submit intent / inject fault
        ↓
   [ production lanes, real threads ]        ← 30 % of scenarios (see §9.2)
        ↓
  quiesce():  inbox drained (settleIntents)  AND  downloads: no in-flight key
             AND  no non-READY media_objects row
        ↓
  oracle.check(scope = ledger)  →  violations
        ↓
  on first violation:  write build/fuzz-repro/<scenario>-seed-<n>.json
                       (seed, config, fault script, step index, violations)
```

---

## 5. LLD — components

All paths are under `shared/src/jvmTest/kotlin/dylan/fuzz/` (package `dylan.fuzz`) unless stated.
All are **NEW** unless marked EXISTING.

### 5.1 `FuzzHarness` — `fuzz/FuzzHarness.kt` (NEW)

Composes the existing harness rather than forking it. `GraphHarness` (`support/GraphHarness.kt:60-155`)
already wires the real `Dylan` over a real `DriverFactory` and a real temp dir, and already uses
`TestLanes()` = production lanes (`GraphHarness.kt:63`, `TestLanes.kt:97`) and
`FakePlayerEngine.onRealLooper` (`GraphHarness.kt:152`). The gap is that `GraphHarness` hard-codes
`FileSystem.SYSTEM` (`GraphHarness.kt:69`) and a single fixed `responseBody`/`responseStatus`
(`:98-99`), and exposes no lifecycle-restart hook.

```kotlin
class FuzzHarness(
    val seed: Long,
    val cfg: AppConfig = AppConfig(clock = MutableClock()),
    /** PRODUCTION lanes unless the scenario is a timer scenario (§9.2). */
    val lanes: TestLanes = TestLanes.production(),
    val fs: FileSystem = FileSystem.SYSTEM,          // often a FaultyFileSystem(fs)
    val parent: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) : AutoCloseable {
    val root: Path
    val db: Dylan
    val paths: Paths
    val cacheManager: CacheManager
    val downloads: DownloadEngine
    val reconciler: Reconciler
    val orchestrator: Orchestrator?
    val engine: FakePlayerEngine?
    val origin: OriginScript
    val clock: MutableClock
    val net: FakeNetMonitor
    val ledger: EnvironmentLedger
    val oracle: DbOracle

    /** Deterministic PRNG, one instance per scenario, never shared. */
    val rnd: Random

    fun cfg(block: AppConfig.() -> AppConfig): FuzzHarness      // re-derive budgets
    fun withOrchestrator(): GraphHarness                        // only when the journey needs it
    fun submit(intent: Intent)
    fun enqueue(key: SongKey, bits: Int = 128, reason: Priority = Priority.PREFETCH_NEXT): EnqueueResult

    /** Blocks until: inbox drained, no in-flight key, no non-READY media_objects row. */
    suspend fun quiesce(ceilingMs: Long = 15_000)

    /** Close, cancel, join, then delete the tree — the GraphHarness.close() discipline (:261-266). */
    override fun close()
}
```

**Why `quiesce()` and not a sleep.** The download engine's terminal publish releases the key *before*
publishing (`DownloadEngine.kt:763-771` deliberately releases guards first), so a waiter woken by
`JobState.Done` can briefly still see the key owned. Sleeping would either be too short (flaky) or
too long (slow). The predicate is three conditions:

```kotlin
suspend fun quiesce(ceilingMs: Long = 15_000) = wall {
    withTimeout(ceilingMs) {
        // 1. inbox drained: a full CycleRepeat round-trip is a 3-emission mutation
        repeat(3) { val before = state.repeat; submit(Intent.CycleRepeat)
                    state.first { it.repeat != before } }
        // 2. no in-flight key
        downloads.inFlightKeys().isEmpty() && downloads.attemptStates.isEmpty()
        // 3. no claim in progress
        db.dylanQueries.evictingObjects().executeAsList().isEmpty()
    }
}
```

`submit`/`state.first` reuse `GraphHarness.settleIntents` (`support/GraphHarness.kt:187-195`)
verbatim — the `CycleRepeat`-is-a-3-cycle trick is already there and already correct.

**`downloads.inFlightKeys()` is NEW and must be exposed**: `JobQueue.inFlightKeys()`
(`DownloadQueue.kt:172`) exists but `DownloadEngine` does not forward it. One defaulted getter.

### 5.2 `OriginScript` — `fuzz/OriginScript.kt` (NEW)

The single largest new piece. It replaces the harness's fixed body/status with a **scripted,
stateful origin** that can be told to do everything journey 2 asks for, and that records what it
was asked so a scenario can assert the *protocol* (did the resume send `Range`? did it send
`If-Range`?) and not just the outcome.

```kotlin
/** One response the origin will give, consumed in order; the last entry repeats. */
sealed interface OriginStep {
    data class Ok(val body: ByteArray, val headers: Map<String, String> = emptyMap()) : OriginStep
    data class Status(val code: Int, val retryAfter: String? = null, val body: ByteArray = ByteArray(0)) : OriginStep
    data class Range(val from: Long, val to: Long, val total: Long?, val etag: String? = null) : OriginStep
    object Reset : OriginStep            // throw IOException from the body mid-stream
    object Truncate : OriginStep         // send k of n declared bytes, then clean EOF
    object Stall : OriginStep            // send headers, then never another byte
    object Timeout : OriginStep          // throw a *Timeout-named IOException
    data class WrongContentLength(val declared: Long) : OriginStep
    data class FlakyHtml(val status: Int = 200) : OriginStep   // bot-wall body with audio content-type
}

class OriginScript(private val seed: Long) {
    var steps: List<OriginStep>
    val requests: MutableList<RecordedRequest>   // url, Range, If-Range, Accept-Encoding, User-Agent
    fun http(): HttpClient                        // MockEngine wired to `steps`
    fun resolveScript(vararg s: OriginStep)       // for provider.resolveStream (a suspend seam, not HTTP)
    /** Coverage-guided: pick the next step that has produced the fewest distinct Request shapes. */
    fun nextStep(): OriginStep
}
```

Design rules, each load-bearing:

* `MockEngine` can only `respond(...)` with a complete body; it cannot "reset mid-stream". So the
  body is produced through a custom `ByteReadChannel` (ktor's `respond(..., ByteReadChannel)` /
  a `MockRequestHandlerScope` that returns a channel built from a `Channel<ByteArray>`), which is
  what makes `Reset` / `Truncate` / `Stall` expressible. **UNVERIFIED**: ktor 3's `MockEngine`
  accepts a `ByteReadChannel` body — the plan's M0 spike is exactly to confirm it and, if it does
  not, to substitute a tiny `HttpClientEngine` that wraps `io.ktor.client.engine.mock` for the body
  and can fail a channel mid-stream. Do not discover this mid-M3.
* `Accept-Encoding: identity` and `If-Range` must be *asserted present*, because they are the whole
  resume contract (`Transfer.kt:175-180`).
* `nextStep()` is the coverage-guided element: it buckets requests by
  `(method, hasRange, hasIfRange, status)` and prefers a bucket that has not been seen, so a
  long run explores the protocol space rather than re-running the happy path. The bucket counts are
  printed in the repro file.

### 5.3 `FaultyFileSystem` — `fuzz/FaultyFileSystem.kt` (NEW)

okio 3.18.1's `FileSystem` is `expect open class` with a public no-arg constructor and abstract
`metadataOrNull / list / openReadOnly / openReadWrite / source / sink / appendingSink /
createDirectory(s) / atomicMove / delete` (verified with `javap` against
`okio-jvm-3.18.1.jar` from the Gradle cache). The final `write(Path, Boolean, Function1)` is final but
delegates to the abstract `sink`, so a decorator controls every write path the app uses
(`PartMeta.write` → `fs.write` at `Breakpoint.kt:297-305`; `dylan.util.fsRename` goes through
`java.nio` and is **not** interceptable this way — see below).

```kotlin
class FaultyFileSystem(
    private val delegate: FileSystem = FileSystem.SYSTEM,
    private val clock: Clock,
) : FileSystem() {
    /** Byte budget for the audio dir. Exceeding it makes openReadWrite/sink throw ENOSPC. */
    var quotaBytes: Long = Long.MAX_VALUE
    /** Paths (by suffix match) whose unlink must fail with EACCES/EBUSY. */
    val undeletable: CopyOnWriteArrayList<String>
    /** Paths whose *reads* must fail with EIO — the reconciler's `metadataOrNull` returning null. */
    val unreadable: CopyOnWriteArrayList<String>
    /** Armed: the next N openReadWrite calls on a matching path throw. */
    fun failNextWrites(suffix: String, times: Int)
    /** Simulate "the user deleted files from outside the app". */
    fun deleteOutOfBand(suffix: String)
    fun truncateOutOfBand(suffix: String, toBytes: Long)
    fun corruptOutOfBand(suffix: String)     // overwrite the head so sniffContainer() fails
    override fun metadataOrNull(path: Path): FileMetadata?
    override fun openReadWrite(path: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle
    override fun sink(path: Path, mustCreate: Boolean): Sink
    override fun delete(path: Path, mustCheck: Boolean)
    // … every other member delegates
}
```

* `unreadable` is how a *missing* file is simulated without actually deleting it, so the file still
  exists for the post-repair assertion ("the row was dropped AND the bytes are gone").
* `delete` failing is the `unlinkAll` / `Reconciler.checkOne` failure path
  (`CacheManager.kt:379`, `Reconciler.kt:181`) — the "row kept for retry" contract.
* **`fsRename` cannot be decorated.** `dylan.util.fsRename` is an `expect fun` whose JVM actual
  calls `java.nio.file.Files.move` directly (`jvmMain/.../Rename.jvm.kt:7-18`). So "the rename fails"
  (the `commit()` → `STORAGE` path at `DownloadEngine.kt:678-691`) cannot be injected from a test
  without a production seam. **Options, in order of preference:** (a) make the rename failure
  reachable by making the *destination* undeletable — `Files.move` with `REPLACE_EXISTING` over a
  non-empty directory throws `DirectoryNotEmptyException`; (b) declare the destination path a
  directory via the `FaultyFileSystem` (okio `createDirectory`) — `Files.move(file → existing
  non-empty dir)` throws; (c) add a defaulted `rename: (String, String) -> Unit =
  dylan.util::fsRename` parameter to `DownloadEngine`. **(c) is the only clean one and is one line;
  it is listed in §5.9 as the second (and last) production change this plan needs.**

### 5.4 `DbOracle` — `fuzz/DbOracle.kt` + `fuzz/Invariants.kt` (NEW)

```kotlin
data class Violation(
    val id: String,            // "INV-04"
    val title: String,
    val detail: String,        // the counterexample, rendered
    val sql: String? = null,   // the query that produced it
)

sealed interface PostCondition {
    /** Runs on every check, unless the ledger suspended it. */
    fun check(ctx: CheckContext): Violation?
}

class DbOracle(
    private val driver: SqlDriver,          // the real driver, same connection the app uses
    private val fs: FileSystem,
    private val paths: Paths,
    private val ledger: EnvironmentLedger,
) {
    fun check(step: String): List<Violation>          // all conditions in scope
    fun checkOnly(ids: List<String>): List<Violation> // targeted, for hot paths
    fun suspendIds(): Set<String>                     // from the ledger
}

data class CheckContext(
    val step: String,
    val quiesced: Boolean,          // false ⇒ quiescence-only conditions are skipped
    val fsSnapshot: FsSnapshot?,    // rows→files view, computed once
)

/** Everything we deliberately broke, and what the app must therefore repair. */
class EnvironmentLedger {
    data class Entry(val what: String, val suspends: Set<String>, val expectRepair: Boolean)
    fun broke(what: String, suspends: Set<String>, expectRepair: Boolean = true): Entry
    fun clear()
    fun active(): List<Entry>
}
```

The ledger is the difference between a useful oracle and a red-herring generator. Journey 6
*deliberately* deletes a file a `library` row describes; `INV-21` (no row references a missing file)
must be **suspended** for that step and re-asserted with `expectRepair = true` — i.e. the suite
asserts that the *next reconcile* fixes it, which is the actual contract
(`Reconciler.kt:98-103`, `:155-178`).

### 5.5 Raw SQL escape hatch — `fuzz/Sql.kt` (NEW, extracted from EXISTING)

`CacheMigrationTest` already has exactly this, but private to that class
(`CacheMigrationTest.kt:325-361`, `:420-463`: `rows`, `scalarLong`, `queryPlan`). Promote it to
`dylan.fuzz.Sql` and have `CacheMigrationTest` delegate to it — **no behaviour change, pure
extraction**, and it removes the duplication the moment the migration scenarios need the same
primitives.

```kotlin
class Sql(private val driver: SqlDriver) {
    fun exec(sql: String)
    fun scalarLong(sql: String): Long        // -1 for "no row", 0 for a real zero (see the note at CacheMigrationTest.kt:435)
    fun rows(sql: String): List<String>      // first column of each row
    fun col0(sql: String): List<Long>
    fun plan(sql: String): List<String>      // EXPLAIN QUERY PLAN, reading the LAST column
    fun pragma(name: String): String
    fun userVersion(): Long
    fun tableNames(): List<String>
    fun indexNames(): List<String>
    fun triggerNames(): List<String>
    fun transaction(body: () -> Unit)        // for write-and-expect-throw
}
```

**Keep the two traps this code already documents**, or the new suite inherits them:
(a) `scalarLong` must distinguish "no row" from 0 (`CacheMigrationTest.kt:435`); (b) the plan's
*text* is the **last** column, so reading column 0 makes "must not use a TEMP B-TREE" vacuously
true (`CacheMigrationTest.kt:445-462`).

### 5.6 `ModelOracle` — `fuzz/OrchestratorModel.kt` (NEW)

A deliberately dumb reference implementation of the *observable* half of the Orchestrator, ~120
lines, no coroutines, no DB:

```kotlin
/** The queue algebra, transcribed independently. Total; returns null for "no successor". */
object RefQueue {
    fun nextIndex(q: List<SongKey>, index: Int, order: List<Int>?, shuffleOn: Boolean,
                  repeat: Repeat, dir: Int): Int?
    fun successorsOf(state: PlayerState): Set<SongKey>   // transitive closure, ≤ queue size
}
/** The reference "what must be true" for a settled state. */
object RefExpect {
    fun legalStates(queue: List<SongKey>, index: Int, repeat: Repeat, shuffle: Boolean): Set<Int>
}
object OrchestratorModel {
    fun apply(state: PlayerState, intent: Intent): RefOutcome   // RefOutcome = State | NoChange | Toast
    fun step(m: RefState, ev: EngineEvent): RefOutcome
}
data class RefState(val queue: List<SongKey>, var index: Int, var phase: Phase, var posMs: Long,
                    var shuffleOn: Boolean, var order: List<Int>?, var repeat: Repeat)
```

**The model is compared on the settled state, never on the intermediate emissions**, and only
for the subset of intents whose outcome is *not* racy: `PlayNext`, `AddLast`, `RemoveAt`,
`MoveWithinQueue`, `ClearUpNext`, `ToggleShuffle`, `CycleRepeat`. Navigation (`Next`/`Previous`)
and seek are compared as **relations** ("`current` moved to a legal successor slot"), because
`skipSettleMs` + a racing download make the exact landing slot timing-dependent
(`Orchestrator.kt:571-575`, `cfg.skipSettleMs = 350`).

`Orchestrator` is private about almost everything, so the harness observes it exactly the way
production does: `orchestrator.state` (a `StateFlow`), plus `FakePlayerEngine`'s recorded
`preparedWindows` / `seeks` / `upNextHistory` (`FakePlayerEngine.kt:87-92`). No reflection, no
`internal` access. Where the model needs a fact the state does not carry (the generation, the
`pushedUpNextId`), assert on the *itemId prefix* the engine saw, which is generation-stamped by
construction (`model/Models.kt:17-20`, `Orchestrator.kt:1137-1141`).

### 5.7 Oracle doctrine — safety, not sequences

Three tiers of assertion, in decreasing strictness and increasing use:

1. **Exact equality** — only where the outcome is a pure function of settled inputs: after
   `quiesce()`, the queue, the index, the shuffle permutation and the repeat mode must equal the
   model's. This is ~15 % of assertions.
2. **Relation / closure** — the set of songs that *can* legally play from slot *i* under the
   current `(shuffleOrder, repeat)`; `index ∈ queue.indices`; `queue[index] == current` unless the
   playing row was removed (the documented contract at `Orchestrator.kt:396-403`).
3. **Safety / liveness invariants** — everything in §6, plus: no `Phase.Error` for a track whose
   file is present and sniffed; every `JobState` published for a key is reachable from
   `attemptStates`; no in-flight key without a `.part` or a `partBytes`; the download engine's
   terminal retention maps are bounded (`DownloadEngine.kt:819-834`).

**Forbidden oracles** (and why): "the emissions in order were X" (races); "wall-clock elapsed <
N ms" (load-dependent); "exactly one HTTP request was made" (legal for a *specific* scenario, illegal
as a suite-wide rule — a preemption plus a resume is two requests and is correct).

**Quiescence is part of the oracle.** Any assertion that reads `PlayerState` must first prove the
inbox is drained, or it is reading a torn view. `FuzzHarness.quiesce()` is that proof.

### 5.8 Mutation contract — "how we will know the test can fail"

This repo has a documented history of gates that could not fail (`feedback-ledger.md:92-94`; the
audit's claim of nine such tests, `codebase-audit.md:395`). Therefore **every component above ships
with a named mutation that must turn it red**, and the mutation is executed mechanically, not by
review.

**Mechanism (NEW, `tools/mutation-gate.sh`):** copy the working tree to a scratch dir, apply one
`perl -pi -e` mutation, run `:shared:jvmTest --tests <the named test> --rerun`, assert **non-zero
exit**, restore. Twelve mutations, each pinned to one test:

| # | Mutation (single-line, mechanical) | Test that MUST go red | Proves |
|---|---|---|---|
| G-01 | `dylan.sq:146` `final_count + 1` → `final_count + 0` | `S-P1-02 totalsFollowEveryLibraryWrite` | INV-02/03 are load-bearing |
| G-02 | `dylan.sq:154` `MAX(0, final_bytes - old.bytes)` → `final_bytes - old.bytes` | `S-P1-03 totalsNeverGoNegative` | INV-04 observes the `MAX` |
| G-03 | `CacheManager.kt:418` delete `clearProtectedKeys()` | `S-P2-11 protectionTableIsRepublishedInsideTheClaimTransaction` | INV-26 (stale table ⇒ wrong victims) |
| G-04 | `CacheManager.kt:315` delete `claimedBytes += …` | `S-CACHE-04 lruEvictsTheExcessNotTheWholePool` | the documented "whole pool destroyed" defect (`CacheManager.kt:274-290`) |
| G-05 | `PartStore.kt:126` delete the `if (!fs.exists(part)) return` guard | `S-DL-08 aCompletedDownloadLeavesNoSidecar` | INV-31 (phantom resumable part) |
| G-06 | `Transfer.kt:576` replace `live.store(live.load().wrote(written.at))` with a no-op | `S-DL-03 aTruncatedBodyResumesFromTheBytesThatActuallyLanded` | DL-1 (`Breakpoint.kt:19-31`) |
| G-07 | `DownloadEngine.kt:715` swap the `dropIntent` / `publishTerminal` order | `S-DL-09 noTerminalStateCoexistsWithAnIntentRow` | INV-27 |
| G-08 | `Orchestrator.kt:1378` `navMark.elapsedNow()` → `clock.nowMs()` | `S-CLK-02 aBackwardClockJumpDoesNotUnblockNavigation` | the monotonic requirement (`Orchestrator.kt:1368-1383`) |
| G-09 | `FakePlayerEngine.prepare` emit `TrackChanged` before `Prepared` | `S-P3-04 autoplaySurvivesAStaggeredEngineEventOrder` | `autoplayReady` is order-independent (`Orchestrator.kt:998-1017`) |
| G-10 | `dylan.sq:72` delete the pin CHECK | `S-P1-05 aPinWithoutATimestampIsRejected` | INV-22 is a *real* constraint |
| G-11 | `1.sqm:46` delete the orphan `DELETE` guard | `S-MIG-03 aV0StoreWithAnOrphanRowStillMigrates` | the totality argument (`1.sqm:36-47`) |
| G-12 | `DriverFactory.jvm.kt:54` drop the `foreign_keys` property | `S-P1-07 cascadesStillFireOnAFreshConnection` | the pool-wide pragma argument (`DriverFactory.jvm.kt:38-56`) |

The gate runs **nightly** (it is ~12 × a Gradle invocation, ≈8 min), never on PR. A mutation that
does **not** turn its test red is a hard failure of the gate, not a warning: it means the test named
in the table is vacuous, which is precisely the failure mode this repo keeps hitting.

**Per-component summary** (the short form to keep in each component's file header):

| Component | Must be able to fail because… |
|---|---|
| `DbOracle` | G-01, G-02, G-10, G-12 go red |
| `OriginScript` | a scenario that asserts a *protocol* fact (`Range` / `If-Range` / status) fails when the step list says otherwise — if no scenario can distinguish a 200-for-Range from a 206, the script is a decoration |
| `FaultyFileSystem` | `S-STG-04` fails if the decorator is bypassed — asserted by a **control scenario** that counts delegate calls: `FaultyFileSystem.calls` must be > 0 for every path the app touches, or the fault is provably not being injected |
| `EnvironmentLedger` | a scenario that deliberately deletes a file must **pass** the suspend and **fail** if the suspend list is wrong — i.e. every `expectRepair` entry is asserted twice, once with the suspend and once without |
| `ModelOracle` | G-09 plus: a deliberately wrong model answer must fail the comparison (a one-line `expect(modelAnswer)` sanity assertion in the test itself) |
| `ProcessDeath` | §5.10 |

### 5.9 The two production seams this plan needs (and no more)

Both are one defaulted parameter, behaviour-preserving, and both are justified by "a test that
cannot reach the branch is not a test":

1. **`DownloadEngine(..., freeDisk: (String) -> Long = ::dylan.util.freeDiskBytes)`** — journey 6
   requires "fill the disk near `diskFloorBytes`", and the check is
   `free < max(cfg.diskFloorBytes, 2 * need)` → `STORAGE`
   (`DownloadEngine.kt:494-497`). `freeDiskBytes` is a top-level `expect fun`
   (`util/NetClass.kt:39`) with a JVM actual reading `File.usableSpace`
   (`jvmMain/.../Util.jvm.kt:43-48`); a test cannot shadow it. Without the seam, the `diskFloorBytes`
   branch is untestable and `cfg.diskFloorBytes = 500 MB` is untested config.
2. **`DownloadEngine(..., rename: (String, String) -> Unit = dylan.util::fsRename)`** — see §5.3.
   Optional; §5.3 option (b) is a workaround.

Everything else is test-side. If a third seam is ever proposed, that is a signal the scenario is
trying to assert something the app does not actually do — put it in §13 instead.

### 5.10 `ProcessDeath` — `fuzz/ProcessDeath.kt` (NEW)

Journey 5 is the one thing a fake cannot approximate, and the plan must be explicit about which of
its nine kill modes are *simulated* and which are *real*.

| Kill mode | How it is produced | Real or simulated |
|---|---|---|
| Swipe from recents | `orchestrator.detachEngine()` + `engine.shutdown()` (mirrors `DylanMediaService.onTaskRemoved` → `stopWhenPlaybackEnds`, `:466-472`) | simulated (the OS half is device-tier) |
| Force-stop / `adb kill` / OOM / reboot | **close the driver mid-flight + cancel every scope without cleanup** | *half real*: the SQLite file and its WAL are real and are then reopened by a **new** `Dylan` over a **new** driver. The process is not really gone, so page-cache and fd state are not really lost. Stated as a limitation in §12.1. |
| Network drop mid-download | `OriginScript` step `Reset` / `Stall`, then `net.online = false` | real HTTP semantics, real watchdog |
| Battery saver | no production branch exists to test — it only changes `NetMonitor.current()`. `FakeNetMonitor` already covers the METERED path (`support/FakeNetMonitor.kt`; asserted by `meteredNetworkPinsTheDownloadToTheMeteredQuality`). **Scenario is a re-assert, not new work.** |
| Process death during a DB transaction | **the interesting one.** `Dylan` is a `TransacterImpl`; a transaction is real on a real connection. To die *inside* one, the harness opens a **second raw JDBC connection** to the same file and issues a statement that must block/abort the first: e.g. `BEGIN IMMEDIATE;` from the harness, then start a graph write (it blocks on `busy_timeout=5000`, `DriverFactory.jvm.kt:55`), then `close()` the harness connection without commit — which on xerial rolls back. This is a real, deterministic simulation of "the process died holding the write lock". |

```kotlin
class ProcessDeath(private val dbPath: String, private val fs: FileSystem) {
    /** Hold a write lock, let a graph write queue behind it, then vanish. */
    fun dieHoldingTheWriteLock(block: suspend () -> Unit)
    /** Abandon every scope and the driver exactly as a `SIGKILL` would; reopen from the file. */
    fun hardRestart(h: FuzzHarness): FuzzHarness
    /** Kill between statement k and k+1 of the migration. See §7 S-MIG-06. */
    fun dieDuringMigration(k: Int, h: FuzzHarness)
}
```

`hardRestart` reopens through the **real** `DriverFactory` (`DriverFactory.jvm.kt:25-34`), which is
what makes `user_version` and `PRAGMA foreign_keys` re-checks meaningful: a store left in WAL mode
by a killed writer must still open (INV-01) and still be at the schema version (INV-02).

---

## 6. The SQLite invariant catalogue

38 numbered, individually checkable post-conditions. Each has the SQL (or the exact procedure, where
the check cannot be pure SQL), the file that makes it true, and a note on what a violation means.
`Dylan.Schema` owns all of these; nothing here is checked by SQLDelight itself.

**Legend** — `SQL` = one statement returning 0 rows / 0; `PROC` = needs `fs` or a driver open;
`NEG` = a write that must be *rejected* (run inside a rolled-back transaction).

### A. Open and schema identity

**INV-01 — `user_version` equals `Schema.version` after every open.**
`SQL: SELECT (SELECT * FROM pragma_user_version) - 2;` → 0. (`Schema.version == 2` per
`DylanImpl.kt:25-26`; hard-coded here deliberately, so a version bump without a suite update is
itself a red — the alternative, comparing to `Dylan.Schema.version`, is a tautology.)
Enforced today at `DriverFactory.jvm.kt:94-98` / `DriverFactory.android.kt:70-74`.

**INV-02 — foreign keys are ON on a *fresh* connection from the pool.**
`PROC`: close the driver, open a brand-new one through `DriverFactory`, then
`SQL: SELECT * FROM pragma_foreign_keys;` → `1`.
Not a tautology because the JVM factory supplies the pragma as a *connection property* precisely
because statements do not survive the pool (`DriverFactory.jvm.kt:38-56`). A pooled connection that
missed it turns every `ON DELETE CASCADE` into a no-op.

**INV-03 — every schema object the app reads exists.**
`SQL: SELECT name FROM sqlite_master WHERE name NOT IN ('songs','library','media_objects',
'cache_totals','protected_keys','favorites','play_history','search_history','settings',
'home_cache','download_intents');` → empty. Plus the exact index set
(`dylan.sq:49,51,78,83,104,218,231,232,239,248,264`) asserted as an equality, which is how
`CacheMigrationTest` proves the *whole* `1.sqm` chain ran rather than most of it
(`CacheMigrationTest.kt:218-231`).

### B. `cache_totals` — the materialised totals

**INV-04 — `final_bytes` equals `SUM(library.bytes)`.**
`SQL: SELECT COALESCE((SELECT final_bytes FROM cache_totals WHERE id=0),-1)
         - COALESCE((SELECT SUM(bytes) FROM library),0);` → 0.
Maintained by `trg_library_total_{ins,del,upd}` (`dylan.sq:142-164`).

**INV-05 — `final_count` equals `COUNT(library)`.**
`SQL: SELECT COALESCE((SELECT final_count FROM cache_totals WHERE id=0),-1)
         - (SELECT COUNT(*) FROM library);` → 0.

**INV-06 — the totals singleton exists, is unique, and is `id = 0`.**
`SQL: SELECT (SELECT COUNT(*) FROM cache_totals) - 1
         + (SELECT COUNT(*) FROM cache_totals WHERE id <> 0);` → 0.
`NEG` + `PROC`: `INSERT INTO cache_totals(id, …) VALUES (1, 0,0,0,0)` must be rejected by
`CHECK (id = 0)` (`dylan.sq:107`).
**This is the invariant that finds F-2**: a fresh install with `.part` files and no `library` row has
no singleton (nothing has written `library` yet, and `setPartTotals` is an `UPDATE`, `dylan.sq:493`),
so `part_bytes` can never be recorded at all.

**INV-07 — no total is ever negative.**
`SQL: SELECT COUNT(*) FROM cache_totals
        WHERE final_bytes<0 OR final_count<0 OR part_bytes<0 OR part_count<0;` → 0.
The `CHECK` (`dylan.sq:112`) makes this unrepresentable *through SQL*, which is the point: it
means a violation can only come from a direct file edit, so this check doubles as a
corruption-detector for the F-1 half-migrated store.

**INV-08 — `part_bytes` / `part_count` match the `.part` files actually on disk.**
`PROC` (needs `fs`): walk `paths.audioDir`, keep `CachePath.isPart(name)`
(`CachePath.kt:54`), sum `metadataOrNull(p)?.size`; compare with
`SELECT part_bytes, part_count FROM cache_totals WHERE id=0`.
**Scope:** only assertable at a *quiesced* point and only after
`CacheManager.refreshPartTotals()` has run. The honest framing is two-tier:
`INV-08a` "after `Reconciler.run()` they match" (always true, and the real contract), and
`INV-08b` "during a session they match" (**expected to be false today — F-2**). 8b ships as a
`@Ignore`d canary with the reason inline, and its un-ignoring is a milestone exit criterion
(§11 M5).

**INV-09 — the budget query is served by the index, not a temp b-tree.**
`SQL(EXPLAIN QUERY PLAN):` the `lruVictims` body (`dylan.sq:375-389`) and `intentsOrdered`
(`dylan.sq:640-641`) must not report `TEMP B-TREE`. The `CROSS JOIN` is load-bearing and the comment
says so (`dylan.sq:371-374`). **Control required:** a query that *does* sort
(`SELECT * FROM library ORDER BY ext`) must report one, or this check is vacuous
(the same trap `CacheMigrationTest.kt:445-462` documents).

### C. Referential integrity

**INV-10 — no orphan `library` row.**
`SQL: SELECT COUNT(*) FROM library l WHERE NOT EXISTS
        (SELECT 1 FROM songs s WHERE s.provider=l.provider AND s.song_id=l.song_id);` → 0.
Equivalent to `deleteOrphanLibrary` finding nothing (`dylan.sq:557-559`); assert the SELECT, and
assert the *mutating* query affects 0 rows only in the dedicated repair scenarios.

**INV-11 — no orphan `media_objects` row.** `dylan.sq:561-563` shape.
**INV-12 — no orphan `favorites` / `play_history` / `download_intents` row.** `dylan.sq:548-552` shape
for history; FK otherwise.

**INV-13 — `ON DELETE CASCADE` really fires.**
`NEG`+`PROC`: with a `library` row present, `DELETE FROM songs WHERE …` must leave 0 rows in
`library`, `media_objects` and (for a favourite) `favorites`. Requires INV-02; this is the pair.

**INV-14 — every `READY` `media_objects` row is a `library` row's active rendition.**
`SQL: SELECT COUNT(*) FROM media_objects m WHERE m.state='READY' AND NOT EXISTS
        (SELECT 1 FROM library l WHERE l.provider=m.provider AND l.song_id=m.song_id
           AND l.bitrate=m.bitrate AND l.ext=m.ext);` → 0.
This is the `trg_library_asset_{ins,upd}` / `trg_media_drop_library` contract
(`dylan.sq:169-209`). It is deliberately *not* extended to `EVICTING` rows: an interrupted claim
outlives its library row by one pass, which is the design (`CacheManager.kt:39-46`).

**INV-15 — a `library` row and its active `media_objects` row agree on `bytes`.**
`SQL: SELECT COUNT(*) FROM library l JOIN media_objects m
        ON m.provider=l.provider AND m.song_id=l.song_id AND m.bitrate=l.bitrate AND m.ext=l.ext
        WHERE m.bytes <> l.bytes;` → 0.

**INV-16 — canary, both directions.**
(a) In a store the current code wrote: `SELECT COUNT(*) FROM media_objects
WHERE state IN ('WRITING','VERIFY_FAILED');` → **0** (nothing calls `beginWrite`/`failObject`).
(b) When such a row is *seeded*, `Reconciler.run()` must unlink the file and drop the row
(`Reconciler.kt:71-95`) and the row must be gone afterwards.
(a) is a tripwire on the *absence* of a feature; (b) is the only coverage that
`reapInterruptedWrites` can have.

**INV-17 — no `EVICTING` row survives a completed reconcile on a quiesced engine.**
`SQL: SELECT COUNT(*) FROM media_objects WHERE state='EVICTING';` → 0 after `quiesce()` + a
`Reconciler.run()` (`CacheManager.kt:248-267`).
**Caveat, and it is a real one:** `reapEvicting` *restores a protected claim to `READY`*
(`CacheManager.kt:255-260`), so a legitimately-protected `EVICTING` row is repaired to `READY`, not
dropped. Both outcomes are correct; the assertion is "no row is left in `EVICTING`".

### D. Pins

**INV-18 — `pinned = 1` ⟺ `pinned_at_ms IS NOT NULL`.**
`SQL: SELECT COUNT(*) FROM library WHERE (pinned=0) <> (pinned_at_ms IS NULL);` → 0.
The schema `CHECK`s it (`dylan.sq:72`) so this is only reachable via a direct file edit — which is
exactly why INV-19 matters.

**INV-19 — the pin CHECK exists and rejects the phantom-pin shape.** `NEG`:
`UPDATE library SET pinned=1, pinned_at_ms=NULL WHERE …` must throw; and the mirror
`UPDATE library SET pinned=0, pinned_at_ms=<ms> WHERE …` must throw. The phantom pin is the
documented §3.9 defect (`1.sqm:6-8`, `Repos.kt:103-105`).

**INV-20 — a favourite's pin is coherent with `favorites`.**
`SQL: SELECT COUNT(*) FROM favorites f JOIN library l USING(provider,song_id)
        WHERE l.pinned=0;` → 0 — *except* for the documented direction: `LibraryCommitter.commit`
re-pins on `favorited || prev.pinned` (`LibraryCommitter.kt:59`), and `Repos.remove` demotes
(`Repos.kt:113-119`), so the only legal divergence is *transient*. Scope: quiesced only.
And the converse is legal (`Repos.add` pins a `library` row that may not exist yet), so it is not
asserted.

### E. Disk ↔ rows

**INV-21 — no row references a missing file (post-reconcile).**
`PROC`: for each `library` row, `fs.exists(paths.final(key, bitrate, ext))` and
`metadataOrNull(p)?.size == bytes`. Suspended while the ledger has an
`expectRepair` entry of kind `DELETED_FILE`.

**INV-22 — no untracked file (post-reconcile).**
`PROC`: for each entry of `fs.list(audioDir)` that `paths.parseFileName(name) != null`
(`CachePath.kt:57-64`, the allowlist), there is a `library` row for the same
(provider, songId, bitrate, ext) — **or** it is inside the `.part` grace window
(`cfg.partGraceHours`, `Reconciler.kt:124-125`).

**INV-23 — a file the app did not write is never deleted.**
`PROC`: seed `cover.jpg`, `notes.txt`, and a file whose name parses but whose ext is not a media
ext; run the full sweep; assert all three survive. This is the `parseFileName == null → continue`
contract (`Reconciler.kt:122`) and a real user-data-loss risk if it regresses.

**INV-24 — no orphan file.** (a file in `knownPaths`-complement past the 60 s orphan grace is gone)
— `Reconciler.kt:128-129, 260`. Assert with an aged mtime and an advanced `MutableClock`
(the technique `ReconcilerClockTest.kt:121-138` already uses).

### F. `.part` and resume records — the pairing invariant

**INV-25 — `|{`.part` files}| == `cache_totals.part_count` after `refreshPartTotals()`.**
`PROC`. Always true immediately after a sweep by construction (`CacheManager.kt:214-222`); the
*interesting* half is 8b during a session.

**INV-26 — every `.part` with bytes > 0 has a `.part.meta` sidecar that decodes.**
`PROC` + `PartMeta.decode` (`Breakpoint.kt:269-285`). This is what makes a `.part` *resumable*:
`loadFromDisk` reads sidecars and nothing else (`PartStore.kt:72-73`). A `.part` without one is
**not** a broken invariant — it is unreachable bytes, which is why:

**INV-27 — no `.part` bytes are unreachable.** `PROC`: for every `.part` on disk, either a sidecar
exists, or a `download_intents` row names the key, or the ledger records a `PART_ORPHANED`
injection. After `Reconciler.run()` + `enforcePartCap(force = true)`
(`DownloadEngine.kt:372-374`, `PartStore.kt:158-204`) no `.part` may be older than
`partGraceHours` and unprotected.

**INV-28 — no resumable record without a `.part`.**
`PROC`: for every sidecar `X.part.meta`, `X.part` must exist. `PartStore.persist` already guards
this (`PartStore.kt:126`, added because a `finally` re-inserted a key whose file was gone) — this
invariant is the regression test for that guard, mutation G-05.

**INV-29 — the number of `.part` files never exceeds `cfg.maxConcurrentParts` after a forced
sweep.** `PROC`. Counts the one being written too (`PartStore.kt:33-43`).

**INV-30 — the sidecar's `bytes` field is never trusted over the file.**
`PROC`: set a sidecar claiming 10× the file's size, run a resume, assert the resume's `Range:` header
equals the *file* size. This is `PartStore.note` correcting from the filesystem
(`PartStore.kt:93-104`, the DL-3 rule).

### G. Intents and the protection table

**INV-31 — no terminal `JobState` coexists with an intent row for that key.**
`PROC`: for every key in `downloads.attemptStates` whose value is `Done`/`Failed`/`Cancelled`, no
`download_intents` row. The contract is stated at `DownloadEngine.kt:712-717` ("a `Done` that still
has an intent row is a download the reconciler will re-enqueue on the next boot") — mutation G-07.

**INV-32 — `download_intents.priority` equals the canonical `CASE` over `reason`.**
`SQL: SELECT COUNT(*) FROM download_intents
        WHERE priority <> (CASE reason WHEN 'USER_NOW' THEN 0 WHEN 'USER_BULK' THEN 1
                            WHEN 'PREFETCH_NEXT' THEN 2 ELSE 3 END);` → 0.
The `CASE` exists exactly once, in the triggers (`dylan.sq:268-282`).

**INV-33 — an intent's `priority` never rises without a matching `reason` change.** Covered by 32
plus the `upsertIntent` `WHERE` guard (`dylan.sq:621-629`), asserted behaviourally in
`S-P1-08 aLowerPriorityIntentNeverReplacesAHigherOne`.

**INV-34 — `protected_keys.reason` is one of the four legal values.** `NEG`:
`INSERT INTO protected_keys(…,'NOPE')` must be rejected (`dylan.sq:118`).

**INV-35 — CORRECTION to the brief, stated explicitly.** The brief asks for "`protected_keys` never
contains a key with no row". **That is false by design and is not checkable as stated**: a key
being *prefetched* has no `library` row and no `media_objects` row, yet is legitimately protected
(`CacheManager.kt:410`, `AppContainer.kt:474-484`). The two real invariants are:

* **INV-35a** — every `protected_keys` row names a key that is *live in at least one of the four
  published sources*: a `library`/`media_objects` row, a `download_intents` row, or an in-flight
  job. `PROC`.
* **INV-35b** — **the table is not stale.** After any mutating transaction returns, the table equals
  the union of `protectedKeys.value ∪ inFlightJobKeys.value ∪ upgradeSourceKeys.value ∪ exempt`
  (`CacheManager.kt:417-423`). If it is a *superset* left over from an earlier transaction, a
  later eviction is silently over-protected. `PROC` — assert **set equality**, not subset. This is
  the assertion that mutation G-03 breaks.

**INV-36 — `upgradeSourceKeys` is non-empty exactly while a `QUALITY_UPGRADE` attempt is in
flight.** Verified in code: added at `DownloadEngine.kt:436`, released *before* the terminal publish
at `:763-764`. Assertion: while an upgrade is running, its key is `protected_keys` with reason
`UPGRADE_SOURCE`; immediately after the terminal state, it is not. (The audit's "six reads and zero
writes" claim, `codebase-audit.md:410`, is **false** — verified. This invariant is the regression
test that keeps it false.)

### H. Engagement and history

**INV-37 — an upgrade preserves engagement.**
`SQL: SELECT COUNT(*) FROM library WHERE play_count < 0;` → 0, plus a scenario assertion that
`upsertCached` from a `PrevRow` keeps `last_used_ms`, `play_count` and `pinned`
(`LibraryCommitter.kt:36-67`).

**INV-38 — `play_history` is trimmed to `cfg.historyLimit` and has no orphan.**
`SQL: SELECT (SELECT COUNT(*) FROM play_history) - <the newest N by played_at_ms>;` → ≤ 0 after a
`recordHistory` (`Orchestrator.kt:1162-1172`). Plus the orphan check (INV-12). Note the tie rule
(`dylan.sq:539-546`): all rows at the cut millisecond are kept, so the bound is
`historyLimit + (ties at the cut)`.

### I. Query-plan and shape guards (the "gate that cannot fail" family)

**INV-39 — the three hot plans use their index.** `lruVictims` (`dylan.sq:375-389`),
`intentsOrdered` (`:641`), `trimHistory` (`:545-546`) → no `TEMP B-TREE`; with a control query that
must produce one.

**INV-40 — the legal-`library` CHECKs are present and reject.** `NEG` × 4: `bytes < 0`,
`bitrate <= 0`, `ext = ''`, `play_count < 0` each must be rejected (`dylan.sq:66-69`). These four
are exactly the columns `1.sqm` copies verbatim from a v0 table that had **no** such checks — see
§7 S-MIG-04.

**INV-41 — the `media_objects.state` CHECK rejects an unknown state.** `NEG` (`dylan.sq:92-93`).

**INV-42 — the engine's retention maps stay bounded.** `PROC`:
`downloads.states.size ≤ 128`, `attemptStates.size ≤ 128`, `progress.size ≤ 128`
(`DownloadEngine.kt:774-795`, `:819-834`). Assert after a burst of 300 enqueues+settles.

### Queue/state-lane invariants (asserted in the P3 tier, listed here so the catalogue is complete)

**INV-43 — `PlayerState.validate` holds for every emission, not just settled ones.**
Subscribe to `orchestrator.state` and re-run `PlayerState.validate` on each emission
(`Models.kt:439-481`) inside a `runCatching` that fails the test. This is the only place a *torn*
state is observable, and `QueueInvariantViolation` is already thrown for 6 shapes.

**INV-44 — the state lane admits at most one task.** `lanesOverlap` / `peakInLaneConcurrency`
(`support/TestLanes.kt:137-192`) against `TestLanes.production()`, with the existing control that a
multi-permit dispatcher is detected (`TestLanesContractTest.kt:58-67`).

---

## 7. Scenario catalogue

47 named scenarios across the 10 required journeys. Each entry: **preconditions · fuzz dimensions ·
oracle · invariants**. Names are the test method names, so §5.8's mutation table can point at them.

**Precondition notation** — `lib(n)` = seed *n* `library` rows with real files; `pin(k)` = pin the
first *k*; `part(k,b)` = a `.part` for key *k* with *b* bytes and a valid sidecar; `v0[...]` = a
hostile v0 store (§7.10); `budget(files,bytes)` = `AppConfig(cacheMaxFiles=…, cacheMaxBytes=…)`.

### 7.0 Journey 1 — long queue, navigation, seek, repeat/shuffle

| # | Scenario | Preconditions | Fuzz dimensions | Oracle | Invariants |
|---|---|---|---|---|---|
| S-P0-01 | `queueAlgebraIsTotalOverTenThousandGeneratedStates` | none (pure) | `QueueStateGenerator(seed, maxQueueSize ≤ 64)` × {shuffleOn} × 3 repeats × ±1 | `QueueInvariants.total` | — |
| S-P0-02 | `maintainedNextIndexAgreesWithTheStateMachine` | none | same, plus `withQueueMutation` on every state | `QueueInvariants.nextIndexMatchesNextUp` | INV-43 |
| S-P0-03 | `repeatAllWalkIsABijectionOverTheWholeShuffleMatrix` | none | each generated `order`, every `index` | `QueueInvariants.repeatAllIsBijection` | — |
| S-P0-04 | `moveWithinQueueIsAnInvolution` | none | all `(from,to)` pairs, n ≤ 8 | `QueueInvariants.moveIsInvolution` | — |
| S-P0-05 | `snapshotRoundTripIsAlwaysNavigable` | none | random snapshots × resolvable-fraction 0.0/0.5/1.0 | `QueueInvariants.snapshotRoundTrip`, `restoredShuffleIsNavigable` | — |
| S-P3-01 | `aLongQueueSurvivesTwoHundredRandomIntents` | `lib(0)`, `PlayNow(40 songs)` | 200 intents drawn from `{Next,Previous,Seek,PlayNext,AddLast,RemoveAt,MoveWithinQueue,ClearUpNext,ToggleShuffle,CycleRepeat}` with weights 3:3:2:2:2:2:1:1:1:1; each followed by `quiesce()` | model equality on `(queue, index, order, repeat)`; relation-only for `current` when the intent was `Next`/`Previous` | INV-43, 01–05 |
| S-P3-02 | `aggressiveSeeksAreClampedAndNeverLeaveTheItem` | `lib(20)`, Playing | 200 seeks: `0`, `−1`, `dur`, `dur+1e6`, `Long.MAX/2`, random | exact: `posMs ∈ [0, durMs]`; `engine.seeks` non-decreasing and each ≤ dur | INV-43 |
| S-P3-03 | `togglingShuffleMidTrackPreservesTheAnchor` | `lib(12)`, Playing at index *i* | toggle at random points; also `ClearUpNext` between toggles | model equality; `index ∈ order` | INV-43 |
| S-P3-04 | `autoplaySurvivesAStaggeredEngineEventOrder` | 1 cached track | script `TrackChanged` **before** `Prepared`, and the reverse, N=50 | exactly one `engine.play()` per prepare; phase `Playing` | INV-43 — **G-09** |
| S-P3-05 | `previousRestartsPastThreeSecondsOtherwiseStepsBack` | Playing, position `1s` / `3.1s` | both sides of the 3 s boundary | `seeks` contains `0` (restart) vs. index moves | — |
| S-P3-06 | `anExhaustedQueueWithRepeatOffIdlesAndToasts` | 3 tracks, repeat OFF, play to the end | end via `endOfItem()` and via `ItemEnded` with no follow-up | phase `Idle`, one "End of queue" toast, `snapshot` written | — |
| S-P3-07 | `aRemovedPlayingRowKeepsTheAudibleTrack` | 5 tracks, Playing at 2 | `RemoveAt(2)` then `Next` | `current.key` unchanged after the removal; `Next` lands on the *next* song, not a repeat | INV-43 |
| S-P3-08 | `clearUpNextAlsoEmptiesTheEngineWindow` | 5 tracks, cached | `ClearUpNext` at every index | `engine.windowSize() == 1` and `upNextHistory.last() == null` | — |

> S-P3-04 duplicates no existing test: `IntentMatrixTest.autoplaySurvivesATrackChangedThatArrivesBeforePrepared`
> covers the one order; the fuzz version is the *distribution over both orders × 50 seeds*.

### 7.1 Journey 2 — many downloads over a hostile network

| # | Scenario | Preconditions | Fuzz dimensions | Oracle | Invariants |
|---|---|---|---|---|---|
| S-DL-01 | `everyOriginReplyShapeReachesATerminalState` | `lib(0)`, 1 song | 24 `OriginStep`s × 2 priorities × 3 qualities = 144 cases | exactly one terminal `JobState` per attempt; no uncaught exception; `states[key]` terminal | INV-31, 42 |
| S-DL-02 | `aTruncatedBodyIsResumableAndTheResumeSendsTheRealRange` | `.part` seeded at *b* | truncate at 10/50/90 % of the body | request #2 carries `Range: bytes=b-` and the same `If-Range`; final file size == origin's declared total | INV-26, 30 — **G-06** |
| S-DL-03 | `aResetMidTransferNeverLosesBytesAlreadyOnDisk` | `.part` at *b* | `Reset` at chunk *k* ∈ {1,2,last} | `.part` size ≥ *b*; `Breakpoint.partBytes == fileSize`; next `Range` == file size | INV-30 |
| S-DL-04 | `aWrongContentLengthIsCaughtByTheBandNotTheEstimate` | 128 vs 320, `durationS` ∈ {0, 1, 100, 3600} | declared = exact / 90 % / 110 % / 0 | `CORRUPT_SIZE` only when the origin's total is known and mismatched; a chunked 128 kbps m4a **must** commit | — |
| S-DL-05 | `twoHundredForRangeIsRestartedWithoutSplicing` | `.part` at *b* > 0 | `200` answering a `Range`; and `206` with a *wrong* `Content-Range` start | `resumeDecision` is `Restart`/`Refetch` (`Breakpoint.kt:145-178`); committed file == origin bytes byte-for-byte | INV-22 |
| S-DL-06 | `aRangeNotSatisfiableWithAPartRefetchesAndWithoutOneFails` | `.part` present / absent | `416` | present ⇒ eventually commits; absent ⇒ `NETWORK`, no infinite loop | — |
| S-DL-07 | `aStallIsTrippedByTheWatchdogAndASlowFlowIsNot` | 2 MB body | tick rates: 0 B/s, 1 KB/s, 16 KB/s, 1 MB/s; `stallTimeoutMs`/`stallWallFloorMs` shrunk in cfg | `Stall` ⇒ `NETWORK_TIMEOUT` + part kept; a flowing 16 KB/s transfer survives | INV-26 |
| S-DL-08 | `rateLimitingHonoursRetryAfterInBothFormsAndNeverSpins` | — | `429` with `Retry-After: 5`, `3600`, RFC-1123 past, absent; × `USER_NOW`/`PREFETCH_NEXT` | `USER_NOW` fails fast (`Transfer.kt:416`); background defers with the attempt budget carried; attempts ≤ `dlRetries` | — |
| S-DL-09 | `aBreakerOpensAfterThreeFailuresAndHalfOpenAdmitsExactlyOne` | — | 3× 5xx, then a success; and 3× 5xx then a `USER_NOW` | `Breaker.state` sequence `CLOSED→OPEN→HALF_OPEN→CLOSED`; the inline-wait/defer/fail split matches `Transfer.kt:433-446` | — |
| S-DL-10 | `coverageGuidedOriginExploresTheProtocolSpace` | — | 400 requests, `nextStep()` selection | the set of `(hasRange, hasIfRange, status)` buckets seen must be ≥ 6 of the 8 possible, or the generator is not exploring | — |
| S-DL-11 | `aBurstOfFiveHundredEnqueuesTerminatesAndBoundsItsMaps` | `lib(0)` | 500 enqueues: 400 unique + 100 duplicates, mixed priorities | every attempt terminal; INV-42 holds; `JobQueue.size ≤ 256` | INV-42, 31 |
| S-DL-12 | `aCrashingJobStillPublishesATerminalState` | — | throw from `resolveStream`, from `fsRename`, from a SQLDelight call | `JobState.Failed` published, `states[key]` terminal, no `Downloading` left behind (`DownloadEngine.kt:596-606`) | INV-31 |

> Existing coverage that S-DL-02/05/08/09 deliberately do **not** duplicate: `DownloadEngineTest`
> already has one case each for these (`truncatedBodyResumesFromTheBytesThatActuallyLanded`,
> `originAnsweringRangeWithTwoHundredRestartsWithoutAWastedRequest`,
> `retryAfterInHttpDateFormIsHonoured`, `breakerCountsThenOpensThenProbesOnce`). The fuzz versions
> exist because a *single* case cannot find the interaction. If M4 shows the fuzz version never
> fails where the single case passes, delete the fuzz version.

### 7.2 Journey 3 — same track repeatedly, two qualities, while playing / cached / evicted

| # | Scenario | Preconditions | Fuzz dimensions | Oracle | Invariants |
|---|---|---|---|---|---|
| S-QL-01 | `anUpgradeCommitsTheNewRenditionAndDropsTheOldOne` | `lib(1)` @128, pinned, **currently playing** | 128→320→128→320 × {playing, paused, not cached} | exactly one `media_objects` row for the song, `state='READY'`, `bytes == file size`; the 128 file is gone; the pin survived | INV-14, 15, 21, 22, 19 |
| S-QL-02 | `anInterruptedUpgradeNeverDestroysTheSource` | `lib(1)` @128 playing; upgrade fails at VERIFY | `Reset`, `truncated`, `5xx`, `STORAGE` | the 128 file still exists and the row still says 128 — the `upgradeSourceKeys` guard (`DownloadEngine.kt:436, 763`) | INV-14, 21 — regression for `codebase-audit.md:410` (disproved) |
| S-QL-03 | `aDedupeHitConsumesNoBandwidth` | `lib(1)` @320, request 128 | metered / unmetered | `JobState.Done` with **0** bulk requests (`DownloadEngine.kt:471-486`) | — |
| S-QL-04 | `evictingTheTrackBeingPlayedIsImpossible` | `lib(6)`, `budget(6, tight)`, current = *k* | evict-one, clear-cache, and a budget pass, each × 10 seeds × {k = 0..5} | *k*'s file exists after every pass | INV-35b |
| S-QL-05 | `reEnqueueingTheSameKeyTenTimesRunsItOnce` | — | 10 `USER_NOW` + 10 `PREFETCH` + 10 `QUALITY_UPGRADE` interleavings | one attempt per key in flight; the winner is the strictly-better one (`DownloadQueue.kt:73-104`); `readmit` never resurrects a retired attempt (`:123-131`) | INV-31 |
| S-QL-06 | `thePartCapSacrificesPrefetchBeforeUserParts` | `.part` × 5, `maxConcurrentParts=3` | which 3 survive, over 20 seeds | survivors are the `USER_NOW`/`USER_BULK` ones, newest first (`PartStore.kt:186-203`); never the in-flight one | INV-29 |

### 7.3 Journey 4 — favourite / queue ordering / `ClearUpNext` / `PlayNext`

| # | Scenario | Preconditions | Fuzz dimensions | Oracle | Invariants |
|---|---|---|---|---|---|
| S-LB-01 | `favouriteThenUnfavouriteLeavesNoPhantomPin` | `lib(3)` | 200 add/remove pairs, interleaved with downloads and evictions | after quiesce: `favorites` ⟺ no `library.pinned` divergence (INV-20); `pinned_at_ms` non-NULL iff pinned | INV-18, 19, 20 |
| S-LB-02 | `favouritingACapSizedCacheDemotesTheOldestPinAndKeepsOne` | `lib(30)`, `budget(6, …)`, 10 pinned | pin 10 more, × 5 seeds | ≥ 1 pin survives (`CacheManager.kt:352-355`); demotions are `pinned_at_ms` ascending (`dylan.sq:412-417`) | INV-18, 20 |
| S-LB-03 | `everyQueueMutationShapeRoundTrips` | 8 songs | all 28 `(intent, position)` combos: `PlayNext`/`AddLast` × 8 positions, `RemoveAt` × 8, `MoveWithinQueue` × 56, `ClearUpNext` × 8 | model equality; `queue.size` arithmetic exact | INV-43 |
| S-LB-04 | `playNextIntoAnEmptyQueueTakes()` | `lib(0)` | `PlayNext` as the very first intent | one song in the queue, `index=0`, phase still `Idle` (`Orchestrator.kt:373-382`) | INV-43 |
| S-LB-05 | `addLastOnAColdStartDoesNotClaimTheTransport` | `lib(0)` | `AddLast` first, then `PlayNow` | no `engine.play()` before the user's play | — |

### 7.4 Journey 5 — nine kill modes

| # | Scenario | Preconditions | Fuzz dimensions | Oracle | Invariants |
|---|---|---|---|---|---|
| S-KL-01 | `hardRestartMidTransferResumesFromThePart` | `.part` at *b*, `STALL` step | kill at 5 points across the copy | after restart: the reconciler re-enqueues, `Range: bytes=b-` + `If-Range` are sent, the file completes | INV-26, 27, 28, 30 |
| S-KL-02 | `hardRestartBetweenClaimAndUnlinkIsRepaired` | claim forced via a `delete`-failing `FaultyFileSystem`, then `dieHoldingTheWriteLock()` | protected / unprotected victim | no `EVICTING` row survives a reconcile (INV-17); a *protected* victim is restored to `READY` and its file survives | INV-17, 21 |
| S-KL-03 | `hardRestartAfterCommitBeforeTheIntentIsDropped` | kill at the `finish()` boundary | ×10 seeds | INV-31 holds: no terminal state with an intent row | INV-31 — **G-07** |
| S-KL-04 | `hardRestartDuringAnUpgrade` | `lib(1)` @128 playing, 320 in flight | kill at 4 points | source rendition intact (S-QL-02 contract) | INV-14 |
| S-KL-05 | `detachingTheEnginePausesAndSavesTheResumeSnapshot` | Playing at 30 s | `detachEngine()` | phase `Paused` at the engine's position; `settings["resume"]` parses and has `index` in range | INV-43 |
| S-KL-06 | `restoringFromASnapshotAfterRestartLandsOnTheSameTrack` | snapshot from S-KL-05, then `hardRestart` + `restoreFromSnapshot()` | snapshot age ∈ {fresh, > `resumeMaxAgeMs`} | fresh ⇒ position honoured; stale ⇒ position 0, same track (`Orchestrator.kt:1331-1334`) | — |
| S-KL-07 | `aNetworkDropMidDownloadParksRatherThanFails` | `Stall` + `net.online=false` | 3 drop points | `OFFLINE` fast path, no 120 s wait (`Orchestrator.kt:632-635`); part kept | INV-26 |
| S-KL-08 | `aLowMemoryKillIsEquivalentToHardRestartForTheDatabase` | 40 cached rows, 12 intents | kill, reopen, reconcile | all 40 rows still resolve to files; 12 intents re-enqueue and drop; `cache_totals` unchanged | all of §6 |
| S-KL-09 | `aKillDuringTheFullSweepIsIdempotent` | orphan files + stale `.part` + size-mismatched rows | kill at 5 points in `fullSweep` | a second `run()` completes the job; no file deleted twice; no row dropped over a surviving file | INV-21, 22, 24 |
| S-KL-10 | `batterySaverOnlyChangesTheNetClass` | `lib(0)` | `FakeNetMonitor` METERED, `online=true` | 128 requested, no upgrade enqueued (`JobSupport.kt:35`) | — |

### 7.5 Journey 6 — storage pressure, external mutation, corruption

| # | Scenario | Preconditions | Fuzz dimensions | Oracle | Invariants |
|---|---|---|---|---|---|
| S-STG-01 | `fillingTheDiskToTheFloorFailsWithStorage` | `freeDisk` seam → `0` (NEW, §5.9) | `free ∈ {0, floor−1, floor, floor+1, 2×need}` | `free < max(diskFloorBytes, 2*need)` ⇒ `STORAGE`; else proceeds (`DownloadEngine.kt:494-497`) | INV-27 |
| S-STG-02 | `aQuotaExhaustedPartIsNotCommitted` | `FaultyFileSystem.quotaBytes = b` | `ENOSPC` at open and at write | `StreamErr.Storage` ⇒ `STORAGE`; no truncated file committed; the part is kept | INV-21, 22 |
| S-STG-03 | `deletingCachedFilesOutOfBandIsDetectedAndReaped` | `lib(5)`, delete 2 files externally | mtime fresh / aged; sweep due / not due | INV-21/22 suspended during the break; after `run()` the rows for the missing files are gone and the others untouched | INV-21, 22 |
| S-STG-04 | `theFaultInjectorIsActuallyReached` | control | — | `FaultyFileSystem.counters` shows every op the app performs (`openReadWrite`, `sink`, `delete`, `metadataOrNull`) was intercepted; **a counter of 0 fails the test** | — (see §5.8) |
| S-STG-05 | `aCorruptPartIsRefetchedNotResumed` | `.part` with a valid sidecar but corrupt bytes | ETag same / different | same ETag ⇒ the bytes are *appended* (that's correct — the transport is the authority); different ⇒ `Refetch`; the committed file passes `sniffContainer` | INV-22 |
| S-STG-06 | `aCorruptCachedFileIsDroppedNotPlayed` | `lib(3)`, corrupt the head of 1 file | `unreadable` metadata / real corruption | the row is dropped; `sniffOk` was already refusing it (`WindowPreparer.kt:78-94`) | INV-21 |
| S-STG-07 | `aCorruptDatabaseIsWipedOnlyWithTheOptIn` | overwrite the header | `allowWipeOnCorruption` ∈ {false, true} | false ⇒ throw, file byte-identical; true ⇒ wiped and recreated at v2 (`DriverFactory.jvm.kt:125-159`; precedent `CacheMigrationTest.kt:156-179`) | INV-01 |
| S-STG-08 | `aFailedUnlinkKeepsTheRowForTheNextSweep` | `undeletable = [target]` | 1 pass, then clear, then another pass | after pass 1 the row is `READY` and the file exists; after pass 2 both are gone | INV-14, 17, 21 |
| S-STG-09 | `partMetaThatCannotBeWrittenIsNotFatal` | make the sidecar write throw | 1 attempt | the transfer still commits; a `log.w` is emitted; the part is resumable-without-validator | INV-26 |

### 7.6 Journey 7 — clock jumps and TTL expiry

| # | Scenario | Preconditions | Fuzz dimensions | Oracle | Invariants |
|---|---|---|---|---|---|
| S-CLK-01 | `everyGraceWindowIsExactlyTheConfiguredWidth` | aged mtimes | each window ±1 ms: orphan 60 s, part `partGraceHours`, resume `resumeMaxAgeMs`, snapshot age | boundary-1 survives, boundary+1 is swept | INV-24 |
| S-CLK-02 | `aBackwardClockJumpDoesNotUnblockNavigation` | Playing, `navDebounceMs = 300` | clock −1 h / +1 h / ±1 s between two `Next`s | monotonic debounce still drops the second tap (`Orchestrator.kt:1368-1383`) — **G-08** | — |
| S-CLK-03 | `aForwardClockJumpAgesEveryPartAtOnce` | `.part` with future mtime | mtime = now, now+1 s, now+1 h, now−1 h | future mtime counts as garbage (`Reconciler.kt:205-213`, `FUTURE_MTIME_TOLERANCE_MS = 5 h`) | INV-24, 25 |
| S-CLK-04 | `thePositiveCacheTTLReloadsAndTheNegativeOneReplays` | `ResilientClient` with a counting origin | TTL boundary ±1 ms; `Retry-After: 2` vs `3600`; a 429 then a success | hit/reload counts exactly; the negative TTL is `max(floor, Retry-After)` capped at 120 s (`ResilientClient.kt:315-321, 461-467`) | — |
| S-CLK-05 | `aNegativeCacheEntryIsNotSharedAcrossQueriesOnTheSameEndpoint` | — | `content.getAlbumDetails` 404 on album A, then request album B | B is **not** answered `NOT_FOUND` (`ResilientClient.kt:287-302`, `isEndpointScoped`) | — |
| S-CLK-06 | `clockJumpsDuringADownloadDoNotCorruptTheBreakpoint` | `.part` at *b* | jump at RESOLVE, mid-copy, at VERIFY | `Breakpoint.originResolvedAtMs` is the resolve time; the resume decision is unaffected | INV-30 |

### 7.7 Journey 8 — concurrent multi-window intents

| # | Scenario | Preconditions | Fuzz dimensions | Oracle | Invariants |
|---|---|---|---|---|---|
| S-MW-01 | `threeIssueRiversNeverLoseOrCorruptAnIntent` | `lib(10)`, engine attached | 3 threads × 60 intents, all via `orchestrator.submit`, N=20 seeds | every intent is either applied or is a documented no-op; no lost intent (the inbox is `UNLIMITED`, `Orchestrator.kt:79`); `QueueInvariantViolation` never escapes `guard` (`:252-264`) | INV-43, 44 |
| S-MW-02 | `notificationTransportUiAndBrowserIntentsInterleaveWithoutTearing` | engine attached, 2 "controllers" | 4 threads: transport (`TogglePlayPause`), UI (`PlayNow`, `RemoveAt`), browser (`Next`, `Seek`), settings (`SetQuality`) × 100 ops | settled state equals the model under *some* serialisation; INV-43 holds on **every** emission | INV-43, 44 |
| S-MW-03 | `aTornStateIsNeverObservable` | as S-MW-01 | subscribe to `state` and validate every emission | zero `QueueInvariantViolation`; zero emission with `maintained = false` after the first | INV-43 |
| S-MW-04 | `anIntentRacingAnEngineEventCannotReviveAnAbandonedGeneration` | Playing, prepare in flight | `TrackChanged` with the old generation's itemId × 50 seeds | `resyncFault` strikes, never an adoption; after 3 strikes it skips (`Orchestrator.kt:1118-1131`) | INV-43 |

### 7.8 Journey 9 — configuration change / rotation

| # | Scenario | Preconditions | Fuzz dimensions | Oracle | Invariants |
|---|---|---|---|---|---|
| S-CF-01 | `reattachingAnEngineReprimesTheWindowAndDoesNotDoublePlay` | Paused mid-track, 20 cached | detach/attach × 10 | `engine.playCount` increments by exactly 1 on a `Ready`/`Playing` attach, 0 on a `Paused` one (`Orchestrator.kt:282-296`) | — |
| S-CF-02 | `rotationDoesNotDisturbTheQueueOrTheSnapshot` | Playing, 30 s in | simulate 1–3 recreations (detach + attach, snapshot + restore) | queue, index, shuffle, repeat and position all preserved; snapshot written once per recreate | — |
| S-CF-03 | `aRestoreThatRacesAPlayNowDoesNotOverwriteTheNewQueue` | — | 50 seeds: `restoreFromSnapshot()` vs `PlayNow` | the *last user intent* wins; the abandoned prepare is cancelled by `bumpGeneration` (`Orchestrator.kt:1292-1324`) | INV-43 |
| S-CF-04 | `aRestoreWithUnresolvableItemsSaysSoAndChangesNothing` | snapshot naming 3 deleted songs | 0/1/2/3 resolvable | a queue that is a navigable prefix; never a `shuffleOn` with a null order | INV-43 |

### 7.9 Journey 10 — schema migration, including unreachable v0 states

`V0StoreBuilder` (§5, `Seeds.kt`) constructs a v0 database **from the real pre-split DDL**, which is
already transcribed verbatim at `CacheMigrationTest.kt:272-303`. It is parameterised so a scenario
can say exactly: *"a v0 store with 3 cached rows, one orphaned, one pinned with a NULL timestamp,
one with `bytes = -1`, one with `bitrate = 0`, one with `ext = ''`, one `play_count = -3`, and one
`download_intents` row with an unknown `reason`."*

| # | Scenario | Preconditions | Fuzz dimensions | Oracle | Invariants |
|---|---|---|---|---|---|
| S-MIG-01 | `aFreshStoreLandsOnTheSchemaVersionWithForeignKeysOn` | empty dir | — | v2, FK=1, all objects present | INV-01, 02, 03 |
| S-MIG-02 | `aCleanV0StoreMigratesFieldForField` | `v0[3 rows, 3 pins]` | 10 shapes | exact per-column equality of `library`; the `cached_files` VIEW still answers | INV-01, 03, 40 |
| S-MIG-03 | `aV0StoreWithAnOrphanRowStillMigrates` | `v0[orphan]` | FK on/off at seed time | the orphan is dropped, the rest survives, the *whole* chain ran (index-set equality) | INV-10, 11 — **G-11** |
| S-MIG-04 | **`v0HostileRowsNeverAbortTheMigration`** | `v0[bytes=-1, bitrate=0, ext='', play_count=-3]` | each column alone, then all together | **the migration must not throw**, must not wipe, and the surviving rows must satisfy the v1 CHECKs | INV-40, 01, 10 |
| S-MIG-05 | `aV0StoreWithAPartFileAndNoRowResumesOrIsSwept` | `v0` + a `.part` + sidecar on disk, no intent row | part age 0 / > `partGraceHours` | fresh ⇒ survives; aged ⇒ swept; and the row-level side holds throughout | INV-24, 25, 27 |
| S-MIG-06 | `aKillBetweenMigrationStatementsIsRecoverable` (**F-1**) | `v0` | kill after statement *k*, k ∈ 1..33 | **the next open must succeed** | INV-01, 02 |
| S-MIG-07 | `aStoreStampedAtTheWrongVersionIsRefusedNotWiped` | `user_version ∈ {1, 3, 7}` | each | v1+v0-content ⇒ migrates; v3/v7 ⇒ `IllegalStateException`, file intact (precedent `CacheMigrationTest.kt:239-250`) | INV-01 |
| S-MIG-08 | `anUnknownIntentReasonIsNormalisedNotRejected` | `v0[reason='WEIRD']` | — | `reason = 'QUALITY_UPGRADE'`, `priority = 3` (`1.sqm:205-211`) | INV-32 |
| S-MIG-09 | `migratingTwiceIsANoOp` | migrate, then re-open 5× | — | byte-identical contents; no index rebuilt; no re-run of the chain | INV-03, 11 |

**S-MIG-04 is expected to be RED on the current tree.** `1.sqm:49-53` copies `bytes`, `bitrate`,
`ext` and `play_count` verbatim from a v0 table that had **no CHECKs at all**
(`CacheMigrationTest.kt:272-282`) into `library`, which has four (`1.sqm:26-33`). The header claims
"Every COPY is therefore total: no filter, no CASE that can violate the new CHECK"
(`1.sqm:11-12`) — which is true of the *COPY* and irrelevant, because the constraint is on the
*destination row*. The only hazard the chain guards is the orphan row, and it guards it by deleting
(`1.sqm:45-47`) with the reasoning "an orphan row is unreachable garbage, so it is dropped here
rather than allowed to wedge the database". The same argument applies verbatim to a negative
`bytes` or a `bitrate` of 0. *Not verified against code I could not read:* whether any shipped v0
build could write those values — `insertCached` was fed `fileSize(...) ≥ 0` and `Quality.bits > 0`,
so the honest statement is "not producible by the current engine, producible by a truncated or
hand-edited store, and the chain's own totality claim is unqualified."

**Recommended fix (owner's call):** extend the guard at `1.sqm:45-47` to
`… OR bytes < 0 OR bitrate <= 0 OR ext = '' OR play_count < 0`, and log the dropped count. Until
then S-MIG-04 ships as an `@Ignore`d canary with the reason inline, and un-ignoring it is M5's exit
criterion — the same treatment as INV-08b.

### 7.10 The seeding/fixture DSL

Cheap and exact, so a scenario reads as a sentence:

```kotlin
h.seed {
    songs("s1", "s2", "s3", "s4", "s5", "s6", "s7")            // songs rows, resolveRef set
    cached("s1", bytes = 1_000, bits = 128)                     // writes the file AND the row
    cached("s2", bytes = 2_000, bits = 320, pinned = true)
    orphanLibraryRow("ghost", bytes = 500)                      // FK off: row with no song
    futureC("s3", pinned = true, pinnedAtMs = null)             // unreachable by any writer
    part("s4", bytes = 700, etag = "\"e1\"", total = 9_000)     // .part + valid sidecar
    partWithoutSidecar("s5", bytes = 12)                        // unreachable bytes
    intent("s6", reason = "PREFETCH_NEXT", bits = 128)          // resumable after a restart
    futureMtime("s7", deltaMs = +7L * 24 * 3600_000)            // aged *into the future*
    uncached("s8")                                             // songs row, no file
}
assert(h.describe()) == "7 songs · 6 cached (2 pinned) · 1 orphan · 2 parts · 1 intent"
```

Every primitive is one or two `db.dylanQueries.*` calls plus one `fs.write`, and every one of them
routes through the same `FaultyFileSystem` the app uses, so a scenario can be seeded *through* a
fault-injecting disk. `describe()` is a one-line human summary used in every failure message — the
"7 cached tracks, 2 pinned, 1 orphan" phrasing from the brief, as an assertion-observable value.

---

## 8. Fault-injection catalogue

34 injection points. **"Must survive"** is the property that must hold after the injection; **"detected by"** names the scenario. Mechanism `FS` = `FaultyFileSystem`, `NET` = `OriginScript`, `PROC` = `ProcessDeath`, `CLK` = `MutableClock`, `GRAPH` = harness control, `SEAM` = §5.9 production seam.

### 8.1 Network (the hostile origin)

| # | Injection | Mechanism | Must survive | Detected by |
|---|---|---|---|---|
| FI-01 | `429` + `Retry-After: <seconds>` | NET | bounded wait; `USER_NOW` fails fast, background defers with the budget carried | S-DL-08 |
| FI-02 | `429` + RFC-1123 date (past / future) | NET | same; past ⇒ 0 ms not negative | S-DL-08 |
| FI-03 | `429` with no header | NET | bounded by `dlRetries`; no 5 s loop | S-DL-08 |
| FI-04 | `503` | NET | same as 401/403 → `EXPIRED` → re-resolve, then `FORBIDDEN_REGION` | S-DL-01 |
| FI-05 | `500`/`502` ×N | NET | breaker opens after `failureThreshold`; cooldown doubles and caps | S-DL-09 |
| FI-06 | Connection reset **before the first byte** | NET (`IOException`) | `NETWORK`, retry, part untouched | S-DL-01 |
| FI-07 | Connection reset **mid-body** | NET (channel fail) | `StreamErr.Network`; `partBytes` reconciled to the file's real size | S-DL-03 |
| FI-08 | Truncated body (clean EOF early) | NET | `Truncated` → **resumable**; part kept | S-DL-02 |
| FI-09 | Truncated body with **no** `Content-Length` | NET | not committed (no `expectedEnd`, no `total`) | S-DL-04 |
| FI-10 | `Content-Length` too small / too large | NET | `Short`/`Oversize` verdict; oversize truncates the part | S-DL-04 |
| FI-11 | `200` answered to a `Range` | NET | `Restart`; the body is streamed from 0; **no splice** | S-DL-05 |
| FI-12 | `206` with a wrong `Content-Range` start | NET | `Refetch`; the part is truncated, not appended to | S-DL-05 |
| FI-13 | `206` with a changed `ETag` | NET | `Refetch` (`Breakpoint.contradicts`) | S-DL-05 |
| FI-14 | `416` with / without a `.part` | NET | refetch / terminal `NETWORK` | S-DL-06 |
| FI-15 | Stall (headers, then silence) | NET | watchdog trips on `stallTimeoutMs`; part kept | S-DL-07 |
| FI-16 | Trickle below 8 KB/s | NET | **survives** — the wall cap is rate-based (`DownloadEngine.kt:842-847`) | S-DL-07 |
| FI-17 | Timeout (class name contains `Timeout`) | NET | `NETWORK_TIMEOUT`, not `NETWORK` (`ResilientClient.kt:347`) | S-DL-01 |
| FI-18 | Bot-wall HTML body as `200 audio/mpeg` | NET | `UNSUPPORTED`/`CORRUPT_CONTAINER`; never committed | S-DL-01 |
| FI-19 | Unparseable signed URL | GRAPH | terminal `NETWORK`, not an `IllegalArgumentException` | S-DL-12 |
| FI-20 | Airplane mode / `isOnline() == false` | GRAPH | `OFFLINE` fast path with no 120 s wait | S-KL-07 |
| FI-21 | Metered mid-session | GRAPH | 128 ceiling; never an upgrade on cellular | S-KL-10 |

### 8.2 Disk and database

| # | Injection | Mechanism | Must survive | Detected by |
|---|---|---|---|---|
| FI-22 | `ENOSPC` on `openReadWrite` | FS quota | `StreamErr.Storage` → `STORAGE`; no truncated commit | S-STG-02 |
| FI-23 | `ENOSPC` on `fs.write` (the sidecar) | FS quota | non-fatal; transfer still commits | S-STG-09 |
| FI-24 | `freeDiskBytes < diskFloorBytes` | **SEAM** | `STORAGE` before any bytes are written | S-STG-01 |
| FI-25 | `delete` fails (`EACCES`/`EBUSY`) on a victim | FS | the row is kept and restored to `READY`; retried next sweep | S-STG-08 |
| FI-26 | `delete` fails on the reconciler's reap | FS | row kept, file kept, next boot retries | S-KL-09 |
| FI-27 | `metadataOrNull` returns null for a live file | FS | treated as missing; row dropped **only after** a successful unlink | S-STG-06 |
| FI-28 | File deleted out of band | FS | reconciled away; the other rows untouched | S-STG-03 |
| FI-29 | File truncated out of band (size mismatch) | FS | `checkOne` drops the row after a successful unlink | S-STG-06 |
| FI-30 | File head corrupted (`sniffContainer` fails) | FS | never played; row dropped; **not** `CORRUPT_CONTAINER` blamed on the network | S-STG-06 |
| FI-31 | `.part` deleted with its row present | FS | the resume restarts from 0; no offset into nothing | S-DL-02 |
| FI-32 | `.part.meta` deleted (`.part` kept) | FS | the part is unreachable bytes and is swept after the grace window | S-STG-05 |
| FI-33 | DB file corrupted (bad header) | GRAPH | throw with the file intact, unless `allowWipeOnCorruption` | S-STG-07 |
| FI-34 | Process death inside a `db.transaction` | PROC | the transaction rolls back; the store reopens at v2 with FK on | S-KL-08 |

### 8.3 Clock, lifecycle, platform seams

| # | Injection | Mechanism | Must survive | Detected by |
|---|---|---|---|---|
| FI-35 | Clock ±1 s / ±1 h | CLK | monotonic-only logic unaffected; grace windows exact | S-CLK-02/03 |
| FI-36 | `user_version` forced to 1/3/7 | GRAPH | refuse, never wipe | S-MIG-07 |
| FI-37 | Migration killed after statement *k* | PROC | the next open succeeds (**F-1**) | S-MIG-06 |
| FI-38 | `attachEngine` / `detachEngine` at any point | GRAPH | no double `play()`, no lost queue | S-CF-01/02 |
| FI-39 | Engine events out of order / duplicated / stale-generation | GRAPH | order-independent autoplay; a stale itemId is a fault, not a track change | S-P3-04, S-MW-04 |
| FI-40 | `stop()` then `start()` on a live container | GRAPH | the engine restarts; `stop()` publishes no `Cancelled` (`DownloadEngine.kt:322`) | reuse `GraphLifecycleTest.stopThenStartRestartsTheBackgroundWork` + INV-42 |
| FI-41 | `shutdown()` while the inbox is mid-message | GRAPH | nothing outlives it; the state lane stops | reuse `GraphLifecycleTest.shutdownStopsTheStateLaneInboxAndNotJustTheBackgroundJobs` |

> 41 rows, 34 of them new: FI-40 and FI-41 are re-assertions of existing coverage and are listed so
> the catalogue is complete, not because they need new scenarios.

---

## 9. Determinism & reproducibility

### 9.1 The three clocks, and which one a test may use

There are three distinct notions of time in this codebase, and conflating them is the exact defect
that produced SF-1:

1. **Wall clock** — `cfg.clock: Clock` (`config/AppConfig.kt:175`, `util/Clock.kt:12-18`). Drives
   LRU ordering, reconciler grace windows, cache TTLs, intent `enqueued_at_ms`, snapshot age.
   → **`MutableClock` in every scenario** (`support/MutableClock.kt:13-32`). Never the real clock.
2. **Coroutine delay** — `delay()` on a lane. Real timers under `TestLanes.production()`, virtual
   under `TestLanes.virtual(scheduler)` (`TestLanes.kt:79-112`).
3. **Monotonic elapsed** — `TimeSource.Monotonic` for *measured* durations: nav debounce
   (`Orchestrator.kt:110, 1377-1383`), the stall watchdog's `lastMark`
   (`Transfer.kt:154, 636-637`), `Breakers.nowMs` (`Breaker.kt:184`), the session listen heuristic
   (`Orchestrator.kt:1186-1193`). **Not injectable, and must not be** (`util/Clock.kt:7-10`).
   Therefore a test may never *simulate* a monotonic window; it must *wait* it out or reconfigure
   `cfg` to shrink it.

### 9.2 The virtual-vs-real rule — stated as a policy, with the incident that produced it

> **Policy.** Virtual time is for tests whose subject is a *timer*. Real dispatchers are mandatory
> for every test whose subject is an *interleaving*. A test that asserts a concurrency property and
> runs on virtual time does not count as covering it, and must be named so the lexical gate can see
> it.

The incident, in this repo's own words (`feedback-ledger.md:92`, verified):

> `SingleFlightStressTest` … That test could not catch it locally because it runs on virtual time,
> where the leader's continuation is resumed deterministically and the gap closes before anyone else
> is planned. (`SingleFlightStressTest.kt:38-41`)

Concretely: the SF-1 window is between `ResilientClient.kt:222` (a caller finding no `inflight`
entry) and `:224` (the leader registering one). Under a `StandardTestDispatcher` the leader is
resumed deterministically at the moment it stores, so the window has no reachable interleaving. On
`Dispatchers.Default.limitedParallelism(1)` it is reachable, and the suite caught it only when CI ran
on real threads.

**The design consequence, which is the load-bearing part of this section:**

* A **virtual-time** lane profile makes four lanes share one `TestCoroutineScheduler` with one
  driver thread, so *two lanes can never be occupied at the same instant* (`TestLanes.kt:65-70`).
  That is **stricter** than production and therefore **hides** every cross-lane race. The virtual
  profile is a *weaker system* for concurrency, exactly as the pre-wave `Dispatchers.Default` profile
  was a *stronger* one. Neither profile is sufficient alone.
* Therefore: **every** scenario that asserts an interleaving property runs on
  `TestLanes.production()` and is *additionally* run R times (R = 5 on PR, 50 nightly) with
  different seeds, so a 1-in-3 CI flake becomes a deterministic 1-in-3 × 5 failure.
* And for the specific classes where "probably interleaves" is not good enough, a **latch-directed
  variant** forces the exact schedule. The repo already has the technique: a `Clock` whose `nowMs()`
  blocks while `ResilientClient`'s private mutex is held (`SingleFlightStressTest.kt:124-154`),
  turning "the mutex happens to be contended at the wrong instant" into a schedule. That is
  generalised in §5.10 and used by S-MW-01/02/03, S-DL-03, S-KL-02/03.
* `FuzzHarness` defaults to `production()`; a scenario opts into `virtual()` **only** by naming
  itself `*Timers*`, and the lexical gate (`tools/lane-check.sh`, already a PR gate at `ci.yml:61`)
  gains one rule: a file whose name matches `*Race*` / `*Concurrent*` / `*Interleav*` may not
  construct `TestLanes.virtual`. Cheap, mechanical, and it stops the mistake being re-made.

### 9.3 Seeds

* One `kotlin.random.Random(seed)` per scenario (`FuzzHarness.rnd`). Never `Random.Default`, never a
  shared instance. Note `PlayerState.newShuffleOrder` defaults to `Random.Default`
  (`Models.kt:328-346`), which is **not** seedable through the public API — a gap: the harness
  must therefore assert shuffle *relations*, not the exact permutation, for any path that builds a
  fresh order. Listed in §13.
* `seed` comes from, in order: the `@Test` method's declared constant → `DYLAN_FUZZ_SEED` (a single
  integer that makes *every* scenario use one seed, for bisecting a CI failure) →
  `abs(seed xor (testName.hashCode()))`. The effective seed is printed in the first line of every
  failure message.
* Default seed corpus: `{0x0D71A11 (the existing QueueStateGenerator default, `QueueStateGenerator.kt:101`), 1, 2, 42, 1337, 0x5EED, 0xC0FFEE, 31337}` on PR; **plus** 32 `System.nanoTime()`-derived seeds nightly, with the seeds *recorded* in the build output so a nightly-only failure is reproducible.

### 9.4 Reproducing a failure from CI

1. The failure message's first line: `SEED=<n> SCENARIO=<name> STEP=<k>`.
2. A repro file `shared/build/fuzz-repro/<scenario>-seed-<n>.json` is written on **first**
   violation, containing: the seed, the full `AppConfig` (serialised field by field), the
   `OriginScript` step list, the `FaultyFileSystem` mutation log, the ledger, the step index, the
   `violations` list, and the seeded fixture description.
3. A nightly job uploads `fuzz-repro/` as an artifact (`ci.yml` already has an
   `if: always()` upload pattern at `:104-113`).
4. Reproduce: `DYLAN_FUZZ_SEED=<n> ./gradlew :shared:jvmTest --tests 'dylan.fuzz.S<NN>*'`.
5. **Shrinking.** The first milestone does not include a shrinker; it includes a *bounded* one: each
   scenario's `OriginScript` and fixture are lists, so "drop the last 3 steps and the last 2
   fixtures and re-run" is a 20-line greedy shrinker over lists, and it is the only shrinker this
   suite needs. Recorded in §11 M6 as a stretch.

### 9.5 Hermeticity

* No network egress: `HttpClient(MockEngine)` for both `bulk` and `api`; WS clients get a
  `MockEngine` that fails the handshake. The suite is offline-safe by construction, and
  `:shared:jvmTest` is already the hermetic job (`ci.yml:97-103`).
* No fixed ports, no `System.nanoTime()` used as a *time* assertion (only as a uniqueness seed —
  the discipline `GraphLifecycleTest.kt:57-60` already documents).
* Temp roots under `FileSystem.SYSTEM_TEMPORARY_DIRECTORY` with a `nanoTime` prefix; always removed
  in `close()` *after* the scope is cancelled and joined (`GraphHarness.kt:255-266`).
* No shared mutable statics. `MutableClock` exists precisely so a real time source can never be
  substituted by accident (`MutableClock.kt:9-12`).
* Locale/timezone: `.sqm` uses `kotlinx.datetime`'s RFC-1123 parser in UTC
  (`Breakpoint.kt:228-239`); a timezone-dependent test is a bug — assert only in UTC.

---

## 10. CI strategy & runtime budget

### 10.1 Layers

| Job | Trigger | Contents | Est. runtime | Notes |
|---|---|---|---|---|
| **`test/jvm` (existing)** | every PR | `:shared:jvmTest --rerun` **including P0/P1** | **+50 s** on the current 20-min ceiling | P0 is commonTest and already runs on iOS too |
| **`test/jvm-fuzz` (new)** | every PR | `:shared:jvmTest --tests 'dylan.fuzz.*' --tests 'dylan.fuzz.p0.*' --rerun`, `--max-workers=4`, `-Dkotlinx.coroutines.debug` off | **≤ 150 s** | The PR budget. Fails the PR. |
| **`nightly/fuzz` (new)** | schedule + dispatch | full seed corpus, R=50, soak scenarios, `-Xmx2g` | **≤ 20 min** | Records every seed used |
| **`nightly/mutation` (new)** | schedule + dispatch | `tools/mutation-gate.sh` — 12 mutations | **≈ 8 min** | A mutation that does not redden its test **fails the job** |
| **`nightly/soak` (new)** | weekly | S-DL-11 × 2000 enqueues, S-P3-01 × 500 intents, S-MW-01 × 200 seeds | **≤ 15 min** | Bounded-growth assertions only |
| **`android/robolectric` (new, M6)** | nightly | the P1/P2 suite re-run on the Android driver | **≈ 6 min** | Catches the Android `DriverFactory`/pragma difference |
| **`android/instrumented` (new, M6)** | nightly, emulator | S-CF-01/02 with a real `ExoPlayerEngine`, a real `MediaSessionService`, a real Activity recreate | **≈ 12 min** | The only coverage of `DylanMediaService` and `ExoPlayerEngine` |
| **device/on-device (stretch)** | manual | the full P2/P3 corpus against a real JioSaavn origin | ~1 h | Not in scope; §13 |

### 10.2 Keeping PR time under a few minutes

The total added PR cost is **P0 + P1 + P2 + P3 at 5 seeds** ≈ **3.5 minutes** on a 4-core runner,
against a CI budget I would set at **6 minutes** for the whole `test/jvm` + `test/jvm-fuzz` pair.
The levers, in the order I would pull them:

1. **Tight `ceiling` values, never sleeps.** Every wait is an event-driven predicate with a ceiling
   (`GraphHarness.CEILING_MS = 20_000` is a *failure* bound; a passing test returns in
   microseconds — `GraphHarness.kt:169-172, 303`). The single biggest cost in this suite will be
   *failures*, and a failure costs the ceiling. Budget ceilings at 1.5× the observed p99.
2. **Seed count, not scenario count, on PR.** 5 seeds for the fuzz properties; 1 seed (a fixed,
   known-good corpus) for the 20 `*-Race*` scenarios, which are R=5-loopable *inside* one test.
3. **`quiesce()` over any sleep**, everywhere (S-KL-09's five kill points are five latches, not five
   seconds).
4. **One SQLite file per scenario class, not per test method**, where the class's scenarios are
   read-mostly; each *scenario* still gets its own root (a shared DB would make `cache_totals`
   assertions order-dependent, which is exactly the kind of coupling that makes a suite lie).
5. **`--max-workers=4`.** The `production` lane profile is `limitedParallelism(1)` per lane *within
   a scenario*; scenarios are independent and parallelise cleanly. Do **not** raise it past the
   runner's core count — the point of the profile is that lanes are single-permit, and CPU
   starvation shows up as a wall-clock flake in a `quiesce` ceiling, not as a real defect.
6. **Scenario-level timeouts** (`@Timeout(30)` from `kotlin.test`) so a hang is a failure with a
   stack, not a job-level 20-minute timeout with nothing.

### 10.3 What does **not** go on PR

Live-network anything (`ci.yml:261-301` — the existing rule, unchanged); the mutation gate (it
*intentionally* breaks the build, so it cannot share a job with anything else); the soak tier; and
the device tiers. **The PR suite must be green on a laptop with the network unplugged** — that is
the test.

---

## 11. Phased implementation plan

Ordered by value-per-effort. Each milestone ends with a *runnable, green, meaningful* addition — no
"write the harness first, tests later" phase, because a harness nobody has run is a harness nobody
knows works.

### M0 — Prove the seams exist (½ day)

* Spike the two things that could invalidate the design: (a) ktor `MockEngine` accepting a
  `ByteReadChannel` body (FI-06/07/08/15 depend on it); (b) `FaultyFileSystem` intercepting
  `PartMeta.write` and `PartStore`'s `openReadWrite` (FI-22/23).
* Add the two production seams of §5.9.
* Promote `CacheMigrationTest`'s private `Store`/`Sql` helpers to `dylan.fuzz.Sql`
  (`CacheMigrationTest.kt:325-361, 420-463`) and point `CacheMigrationTest` at it.

**Exit criteria:** a throwaway test proves (a) mid-stream failure and (b) ENOSPC injection, both
against the *real* `DownloadEngine`. If (a) is impossible with `MockEngine`, the fallback
`HttpClientEngine` wrapper is written and working. No production behaviour changed.

### M1 — The oracle (`DbOracle` + §6 catalogue) (2 days)

* `Sql`, `EnvironmentLedger`, `DbOracle`, all 42 invariants with their SQL.
* `S-P1-01` … `S-P1-08`: the invariant suite over a healthy store, plus the `NEG` constraint
  existence checks and the plan-shape control.

**Exit criteria:** **all invariants green on a healthy store**, the `NEG` checks are *proven*
load-bearing by G-01/G-02/G-10/G-12, and the `PLAN` control query is proven able to report a
`TEMP B-TREE`. Two invariants (`INV-08b`, S-MIG-04) ship `@Ignore`d with the finding inline.

### M2 — Seeding + migration (2 days)

* `Seeds` DSL (§7.10) and `V0StoreBuilder`.
* `S-MIG-01` … `S-MIG-09`, including **S-MIG-06**, which settles F-1 empirically.

**Exit criteria:** S-MIG-06 is green **or** F-1 is confirmed and filed with a reproducer. S-MIG-04's
verdict is recorded either way. G-11 reddens the tree.

### M3 — Pipeline fuzz (3 days)

* `OriginScript` + coverage guidance; `FuzzHarness` (no orchestrator yet).
* `S-DL-01` … `S-DL-12`.

**Exit criteria:** S-DL-10 proves the generator reaches ≥ 6 of 8 protocol buckets; S-DL-01 covers
all 144 reply×priority×quality combinations with exactly one terminal state each; G-06 reddens.

### M4 — Cache, storage and clock (2 days)

* `FaultyFileSystem`; `S-CACHE-*` (folded into S-QL/S-STG), `S-QL-01..06`, `S-STG-01..09`,
  `S-CLK-01..06`, `S-LB-01/02`.

**Exit criteria:** **S-STG-04 passes** (the injector is provably reached — otherwise every other
storage scenario is decoration); S-STG-01 is red-able via the new seam; INV-08b's verdict is
recorded (F-2).

### M5 — Graph/session fuzz (3 days)

* `FuzzHarness.withOrchestrator()`, `ModelOracle`, `FuzzHarness.quiesce()`.
* `S-P3-01..08`, `S-MW-01..04`, `S-CF-01..04`, `S-KL-01..10`.

**Exit criteria:** `S-MW-03` (no torn state) is green over 5 seeds × 100 ops × 3 threads;
`S-MW-01` loses no intent over 20 seeds; S-P3-01 matches the model on the settled state for all
200-intent traces; G-08/G-09 redden. This is where the **PR budget** is finally spent — measure it
and, if over 3 minutes, move S-MW-* to nightly.

### M6 — Encapsulate, gate, and the canaries (2 days)

* `tools/mutation-gate.sh` with all 12 mutations, nightly.
* The lexical rule: a `*Race*`/`*Concurrent*`/`*Interleav*` file may not use `TestLanes.virtual`.
* Un-ignore `INV-08b` **iff** the F-2 fix has landed; otherwise leave it `@Ignore`d with a
  `FOLLOW-UP` comment naming the owner.
* `Repro.kt` (repro files, `DYLAN_FUZZ_SEED`).
* `nightly/fuzz`, `nightly/mutation` jobs.

**Exit criteria:** the mutation gate is green (i.e. all 12 mutations redden their test) and a
deliberately *harmless* mutation (a comment change) is correctly reported as **not** reddening —
otherwise the gate is a "fails on everything" detector and proves nothing.

### M7 — Android lanes (stretch, 2–3 days)

* Robolectric re-run of P1/P2 on the Android `DriverFactory` — the pragma and the
  `AndroidSqliteDriver`-managed migration path differ (`DriverFactory.android.kt:52-67`).
* Instrumented: S-CF-01/02 with a real `ExoPlayerEngine` and a real `MediaSessionService`; one
  scenario that connects a `MediaController` and issues intents while the UI does (journey 8 on the
  real seam, `DylanMediaService.kt:107-140`).

**Exit criteria:** the instrumented job is green on the pinned emulator image and is *not* required
for PR merge.

**Total: ~15 engineer-days.** M0–M1 (2.5 days) buy the majority of the value — the invariant
catalogue is the thing that keeps paying after the suite stops being interesting.

---

## 12. Risks & limitations

### 12.1 What this suite fundamentally cannot catch

1. **Real audio.** No decoder, no `ExoPlayer`, no audio route, no A2DP/noisy events
   (`ExoPlayerEngine.kt:160-167` is where "audio became noisy" is reported). A file that passes
   `sniffContainer` (`download/Container.kt:51-62`) is not thereby playable.
2. **A real process death.** `ProcessDeath.hardRestart` closes the driver and cancels scopes, which
   is close but not identical to `SIGKILL`: the page cache survives, fds close in a controlled order,
   and no `OutOfMemoryError` is raised. It cannot produce a torn WAL the way a power loss can, which
   is exactly the state F-1 is about. §7's `S-MIG-06` is the honest limit.
3. **The real `MediaSessionService` lifecycle.** `onTaskRemoved`, `stopWhenPlaybackEnds`, the
   resumption table, the FGS promotion window (`DylanMediaService.kt:261-288`) are Android-only and
   untestable on the JVM. Device tier or nothing.
4. **The live API.** Contract drift is a separate nightly gate (`ci.yml:284-301`); a scenario built
   on a *mocked* origin can only prove the app is correct about the origin we invented.
5. **Wall-clock/latency regressions.** Every timing assertion in the suite is a band or a counter
   (cf. `EngineContractTest.clockBand`, `:272-280`). A 30 % slowdown passes.
6. **Multi-process / multi-device.** One process, one store.
7. **The `library` row's `bytes` being *right*.** INV-15 proves row↔file agreement; nothing proves
   the file is the *right song's audio*. Only a decoder could.

### 12.2 Cost honesty

| Item | Cheap? | Note |
|---|---|---|
| §6 invariant catalogue (42 checks) | **very cheap** | ~600 lines of SQL, zero new infrastructure beyond `Sql`. Highest value per line in the plan. |
| M2 migration scenarios | **cheap** | `CacheMigrationTest` already has the v0 DDL; `V0StoreBuilder` is parameterisation. |
| P0 queue fuzz | **cheap** | The generator and predicates already exist (`QueueStateGenerator.kt`). Mostly new *drivers*. |
| `FaultyFileSystem` | **moderate** | okio's `FileSystem` is open with a public ctor (verified), so it is delegation, not reimplementation. `FileHandle` is open but its `write`/`flush` are `final`, so a *mid-write* failure needs the quota-on-open trick rather than a write override. |
| `OriginScript` | **expensive** | The mid-stream failure modes are the whole point of journey 2 and the trickiest thing in the plan. M0 de-risks it. |
| `ModelOracle` | **moderate** | ~120 lines, but keeping it *honest* (not silently re-implementing the same bug) needs discipline: it must be written from `Models.kt:364-392`, not from `Orchestrator`. |
| M5 graph fuzz | **expensive in wall time, not in code** | The scenarios are short; the budget is the problem. Levers in §10.2. |
| Mutation gate | **cheap to write, expensive to keep honest** | 12 one-line mutations + a loop. The discipline is the cost: a mutation that stops reddening is a real failure, not noise. |
| Device tiers | **expensive** | Emulator flakiness, Media3 session timing. Explicitly not PR-gated. |

### 12.3 Prior findings I re-verified (and the audit's stale claims)

`docs/codebase-audit.md:392-395` claims the harness is broken. **All four claims are now false, and
this plan does not repeat them:**

| Audit claim | Verified state |
|---|---|
| "`dbLane`/`state` run on `Dispatchers.Default`, not `limitedParallelism(1)`" | **False.** `TestLanes.kt:80-81`; audited by `TestLanesContractTest.kt:36-57` with a working control at `:58-67`. |
| "`FakeEngine.prepare()` emits synchronously; no `ItemEnded`, no `SEEK`, frozen `positionFlow`, `seekTo` = Unit" | **False.** `FakePlayerEngine.kt:144-157` queues events through a looper; `:329-350` `endOfItem`; `:213-227` `seekTo` emits `TrackChanged(SEEK)`; `:366-390` the poll advances position; `:229` `currentTimeMs` overridden. The class KDoc at `:41-66` enumerates each old defect. |
| "`shared/src/commonTest` does not exist" | **False.** It exists and holds `QueueStateGenerator.kt` / `FlowContractTest.kt`; it runs on iOS (`ci.yml:213`). |
| "`detekt` … `buildUponDefaultConfig` **unset**" | **False.** `shared/build.gradle.kts:20` sets `buildUponDefaultConfig = true`. |
| "`upgradeSourceKeys` has six reads and zero writes" | **False** (`feedback-ledger.md:113-116`). Re-verified: written at `DownloadEngine.kt:436`, released at `:763-764`. **INV-36** keeps it that way. |
| "`Orchestrator.publishProtected()` still writes a strict subset" | **False** (`feedback-ledger.md:105-111`); no such function exists. **INV-35b** is the assertion that would catch it if it were reintroduced. |
| "`tools/gate.sh` is the one entry point" | **False.** `tools/check.sh` is the local entry point; CI has six jobs. |
| "`shared/src/commonTest` … `Intent.Seek` has zero references" | **False.** `IntentMatrixTest.seekReachesTheEngineClampedToTheTrackDuration` and four siblings. |

Two audit claims that **are** still true and that this plan is built around:

* **SE-3 / RB-2** ("gates that structurally cannot fail", `codebase-audit.md:111, 146`) — the
  response was to fix the individual gates (`ContractDrift.kt:763` now `exitProcess`s; the
  resolveRef coverage line at `ProbeMain.kt:200` is honestly labelled *non-blocking* per
  `feedback-ledger.md:41`), not to make gates unfalsifiable by construction. §5.8 is the structural
  answer and goes further: it makes falsifiability a **tested property of the suite**.
* **`verifyMigrations` is off on purpose** (`shared/build.gradle.kts:108-112`) with `CacheMigrationTest`
  named as the real gate. Verified: the claim "a gate that cannot fail is worse than no gate" is
  right, and the substitute gate is *itself* incomplete (it only ever seeds `user_version = 0`,
  `CacheMigrationTest.kt:304`) — which is F-1's home turf and M2's job.

### 12.4 Known-flaky tests this suite must not be confused with

If one of these goes red, it is **not** a fuzz-suite finding, and a failure of one must not be
attributed to the other's code:

* `TestLanesContractTest` timing-based rows (`RENDEZVOUS_TIMEOUT_MS = 250`,
  `PARALLEL_RENDEZVOUS_TIMEOUT_MS = 2_000`, `TestLanes.kt:26-35`) — a loaded CI runner can push a
  barrier past its timeout and produce a *false* "lane admits overlap".
* `SingleFlightStressTest.concurrentOpenersCollapseToOneLoadPerKey` — the original defect was
  ~1-in-3; the test is now deterministic (G-fixed) but it is a **real-threads** test with a
  `Thread.sleep(20)` gate release (`SingleFlightStressTest.kt:86-89`) and will be the first thing to
  flake on a saturated runner. The seeded latch variant
  (`aCancelledLeaderStillReleasesTheKey`, `:180`) is the deterministic one; prefer it.
* `GraphLifecycleTest` cold-start timing rows (`median` over samples, `:115-119`) — a
  measurement, not a gate.
* `LogSinkPerfTest` / `LogSinkPerfHarness` — throughput, deliberately machine-dependent.
* `QueueStatePropertyTest` / `IntentMatrixTest` — deterministic, but they use a *fixed* seed
  (`SEED_A`…`SEED_H`); a change to `QueueStateGenerator` changes what they cover without changing
  their names. Any fuzz addition there must not silently re-point them.
* The **`@Ignore`d canaries this plan adds** (`INV-08b`, `S-MIG-04`) are *known* red. They must be
  reported separately from real failures — a CI summary that mixes them is a CI summary nobody
  reads.

### 12.5 Design risks

* **Oracle fatigue.** 42 invariants that all run on every `quiesce()` will be slow and will make
  failures hard to read. Mitigation: `checkOnly(ids)` on hot paths, and group the report by
  invariant *group* so a failure names a family, not a line.
* **Over-assertion.** The temptation, once the oracle exists, is to assert exact sequences. §5.7
  is the countermeasure, and it must be enforced in review — the three tiers are a *ceiling*, not a
  starting point.
* **A harness more permissive than the app.** That was the pre-wave defect (`codebase-audit.md:393`).
  The defence is `TestLanes.production()` as the **default** (not an option), the
  `*Race*`-may-not-be-virtual lexical rule, and INV-44 in the same suite.
* **The seed corpus rotting.** 8 fixed seeds find the same 8 traces forever. Hence the 32 nightly
  `nanoTime` seeds and the coverage-guided origin (S-DL-10).

---

## 13. Open questions for the owner

1. **F-1 (migration atomicity) — do you want it fixed as part of this work?** The fix is to wrap
   `applyMigrations` in `Dylan(driver).transaction { }` (`DriverFactory.jvm.kt:73-91`). It is one
   function, but it is a *production* change to the boot path, and M2 will otherwise ship a red
   canary. My recommendation: yes, and land it before M2's exit criterion is judged.
2. **F-2 (`part_*` totals) — is publishing from `PartStore` in scope?** `CacheManager.setPartTotals`
   (`CacheManager.kt:225-228`) has no caller; the natural fix is for `PartStore.persist` to publish
   the tracked total. That is a *performance-motivated* write on the io lane and needs a decision
   about frequency. Until then INV-08b stays `@Ignore`d.
3. **`1.sqm` totality for hostile v0 rows (S-MIG-04).** Should the orphan guard at `1.sqm:45-47`
   be extended to `bytes < 0 / bitrate <= 0 / ext = '' / play_count < 0`? It is the same reasoning
   already in the file, and the same consequence if omitted (a permanently unopenable install) —
   but it *deletes user rows*, so it is your call, not mine.
4. **`Schema.version == 2` while the schema header says "version 1"** (`dylan.sq:1`,
   `DylanImpl.kt:25-26`). Is the intent that the current schema is "v1" (and the file should be
   `0.sqm`), or "v2"? It changes which stores are upgradable and is worth a one-line comment in
   `dylan.sq` at minimum. My reading is that the naming is off by one relative to the author's
   intent but *behaves* correctly for the two reachable stored states (v0@0 and v0@1 → migrate;
   current@2 → no-op), which M2's S-MIG-07 will confirm.
5. **`PlayerState.newShuffleOrder` uses `Random.Default`** (`Models.kt:328-346`), so the harness
   cannot seed the fresh-order path. Do you want a seeded overload (a defaulted `random: Random`
   parameter already exists internally — is it reachable?), or shall the suite keep asserting
   relations only? I lean towards keeping it relation-only and **not** touching production.
6. **The `dispose()` path.** `Orchestrator.dispose()` (`Orchestrator.kt:217-231`) has no production
   caller on Android (`AppContainer.shutdown()` is iOS-terminate-only per `feedback-ledger.md:175`).
   Should journey 5's "kill" scenarios include a `dispose()`-based mode, or is that a path we would
   rather delete than test?
7. **Nightly wall-clock budget.** §10 assumes 20 min for `nightly/fuzz` + 8 min for
   `nightly/mutation` on a 2-core runner. If the budget is 10 minutes total, the seed corpus has to
   shrink and the mutation gate becomes weekly.
8. **Who owns the emulator lane (M7)?** It needs a pinned system image and someone who can debug
   Media3 session flakiness. If nobody owns it, the honest thing is to drop M7 and say so in the
   docs rather than ship a job that is permanently skipped.
9. **Do you want the `@Ignore`d canaries at all?** They are the only mechanism I know for shipping a
   *known-red* assertion without a red CI. The alternative is a `knownFailures.txt` the reporter
   filters. I would rather have the `@Ignore` with a named owner in the reason string — your call.
