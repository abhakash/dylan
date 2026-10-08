# Review — `shared/` + `androidApp/` (Kotlin only)

Read-only Staff-Engineer audit, 2026-10-02. Scope: `shared/src/**`, `androidApp/src/**`,
root Gradle, `tools/**`, `.github/workflows/**`. `iosApp/**` ignored. The tree is
**mid-review** — 45 files carry uncommitted work from a parallel wave; that diff is
reviewed here too and is called out where it is in flight.

Every finding is **PROVEN** (the mechanism is constructible from the code as written) or
**SUSPECTED** (the mechanism is plausible but I could not close the reachability).

## Resolution log (added after the fixes landed)

| Finding | Status |
|---|---|
| **C1** worker pool retired by a preemption | **FIXED** - the handle is now the attempt's own child `Job`, launched `LAZY` and `join`ed; test `DownloadEngineMaintenanceTest.twoPreemptionsDoNotRetireTheWorkersThatAreLeftToServeThem` |
| **H4** `1.sqm` COPY not total against the CHECKs | **FIXED** - `MAX(0, bytes)`, `MAX(1, bitrate)`, `MAX(0, play_count)`, and `ext = ''` to `'m4a'`; the header's "no filter" sentence is rewritten; test `CacheMigrationTest.aV0StoreWithCheckViolatingRowsStillMigratesAndRepairsTheValues` |
| **H5** `ArtworkBitmapLoader` never settles on cancellation | **FIXED** - extracted `dylan.util.launchSettled`, which ties settlement to the job's *completion*; test `LaunchSettledTest` (4 cases; `androidApp` has no test source set, so the guarantee is pinned on the shared helper the Android file calls) |
| **H3** `DylanMediaService` TOCTOU on two unsynchronised fields | **FIXED** - extracted `dylan.util.VersionedCell`; the compare-and-set is one `AtomicReference` CAS on an immutable (generation, value) pair; test `VersionedCellTest` (6 cases) |
| **M6** `FileLogSink.close()` does not take `writeMutex` | **FIXED** - `close()` is `suspend` and takes the lock; test `FileLogSinkTest.closeWaitsForALineBeingWrittenRatherThanClosingTheHandleUnderIt` |
| **M7** `Reconciler` builds a second `SettingsStore` | **PARTIAL** - the lying comment in `Reconciler` and the lying invariant in `SettingsStore`'s KDoc are corrected to what is actually true. The real one-liner (pass `data.settings`) lives in `AppContainer.kt`, which was out of scope for this pass. |
| **M8** `SettingsStore.get` reads `cache` outside the mutex | **FIXED** - the fast path moved inside the lock; test `SettingsStoreTest.aGetDoesNotProceedWhileAPutToTheSameCacheIsUnfinished` |
| **M1** "unlink failed" and "already gone" indistinguishable | **NOT REPRODUCED - the premise is false.** okio 3.18.1's `FileSystem.delete(path, mustExist = ...)` defaults `mustExist` to **`false`**, not `true`: `delete(path)` on an absent path returns normally, and only the *explicit* `mustExist = true` throws `FileNotFoundException`. So neither `Reconciler.unlink` nor `CacheManager.unlinkAll` ever produced the phantom row the finding describes, and the `log.e` on every weekly sweep does not fire. Measured two ways: `ReconcilerTest.deleteMustExistIsWhatThrowsOnAnAbsentPath` pins the okio behaviour directly, and `aCachedRowWhoseFileIsAlreadyGoneIsDroppedRatherThanKeptForRetry` passes against the *unmodified* code. `mustExist = false` is now written out explicitly at both call sites - it is the correct semantic and the default is not part of okio's contract - and both KDocs state the measurement, so the claim cannot rot again. |
| **M3** `checkOne(stampOnly)` null-`recorded` branch | **NOT FIXED - SUSPECTED, and not constructible from here.** `fullSweep` builds its list from `selectAllCached()` (one row per song, the active rendition) and `currentBytes` re-reads `selectCached` filtered to that same rendition, so the two always agree without a concurrent write. I could not build a schedule that produces the null, and `checkOne` is private, so I could not add a seam to force one. The branch is still one `?:` from unlinking a good rendition and the fix is a three-line null guard; it is left alone rather than shipped untested. |

## Gates run (read-only, nothing fixed)

| Gate | Result |
|---|---|
| `./gradlew ktlintCheck detekt` | **GREEN** (up-to-date; tree unchanged since last run) |
| `./gradlew :shared:jvmTest` | **GREEN — 353 tests, 0 failures, 0 skipped** (first attempt hit `NoSuchFileException` on `in-progress-results-generic.bin` after I deleted `test-results`; that is the stale-artifact corruption, re-ran clean) |
| `./gradlew :androidApp:assembleDebug :androidApp:lintDebug` | **GREEN** |
| `./tools/lane-check.sh` | **GREEN** (exit 0) |

Neither known flake (`SaavnProviderTest.theSnapshotLruSurvivesConcurrentWritersThatTheLinkedHashMapDoesNot`,
`DownloadEngineTest.networkErrorMidTransferIsNotReportedAsStorage`) fired on this run.

---

# Executive summary — the 10 that matter, worst first

1. **A download preemption permanently retires a worker coroutine.** `DownloadEngine.worker`
   stores its *own* `Job` as the per-attempt handle and `cancelWorker` cancels that `Job` —
   which cancels the `while (true)` loop around it, not just the attempt. `WORKER_COUNT` is 2
   and nothing ever replaces a worker, so **two preemptions in one process brick every
   download for the rest of the session**, silently. `DownloadEngine.kt:378-397, 407-411`.
   No test asserts pool liveness. → **C1, PROVEN.**
2. **`AppContainer`'s four graphs are `lazy(PUBLICATION)` with side-effecting builders, and the
   comment claims that is safe.** The main thread (`AppRoot.kt:150`) and the io lane
   (`openOnIo`) race for `net` and `playback` on every cold start. A losing build leaks three
   Ktor clients, two download workers and a live state-lane inbox. The stated invariant
   ("publication is enough") is exactly backwards — `PUBLICATION` *permits* concurrent
   initialisation. → **C2, PROVEN.**
3. **Same root cause, worse symptom: the deferred-open design does not survive Android.** A
   cold SQLite open + schema create runs on the **main thread** on first launch, which is the
   one thing `AppContainer.open()`'s KDoc says it exists to prevent. `LazyDatabase.kt:36-43`.
   → **H1, PROVEN.**
4. **`AppContainer.open()` can hang forever.** `launchOpen` cancels the open job when
   `shutDown` is already set; a job cancelled before its body first runs never completes
   `opened`, and `open()` plus all four `bgJobs` `await` it with no timeout.
   `AppContainer.kt:328-331, 442-457`. → **H2, PROVEN.**
5. **`DylanMediaService.resumeFuture` / `resumeGeneration` are plain fields written from three
   threads with no barrier.** The new generation guard cannot deliver the guarantee its KDoc
   claims. `DylanMediaService.kt:40-44, 97-101, 186-194, 220-221`. → **H3, PROVEN** (race),
   **false-invariant comment**.
6. **`1.sqm`'s COPY is not total against the new CHECK constraints, and its header says it is.**
   The in-flight wave fixed the FK hazard and documented it in detail; the four CHECK hazards
   are untouched and abort the migration *before* `DROP TABLE cached_files`, which — with the
   new `isCorrupt()` that keeps the file — is a permanently unopenable install.
   `1.sqm:10-12, 45-53`. → **H4, PROVEN.**
7. **`ArtworkBitmapLoader` never completes its futures on cancellation, and its KDoc says it
   does.** A `loadBitmap` that races `release()` leaves Media3's `CacheBitmapLoader` holding a
   future that never settles, so notification artwork never renders again for the process.
   `ArtworkBitmapLoader.kt:41-44, 69-77`. → **H5, PROVEN** (false invariant + real leak).
8. **"Unlink failed" and "the file was already gone" are indistinguishable**, because okio's
   `delete` defaults to `mustExist = true`. A row whose file vanished is restored to `READY` /
   kept forever and re-attempted on every weekly sweep — with a *retryable* error logged.
   `Reconciler.kt:172-177`, `CacheManager.kt:374-384`. → **M1, PROVEN.**
9. **Media3 playback resumption lands on the wrong track.** `computeResumptionItems` maps the
   snapshot's *queue* index onto a *filtered* `mediaItems` list with no remap.
   `DylanMediaService.kt:421-447`. → **M2, PROVEN.**
10. **The concurrency invariant the whole state lane rests on is asserted in three places and
    enforced in none — and its only enforcement mechanism is deliberately swallowed.**
    `Orchestrator.kt:52-64` states "no handler captures a snapshot and then suspends" as the
    reason shared mutable state is safe; `guard` catches the `QueueInvariantViolation` that
    would prove it wrong, logs it, and carries on with a broken queue. The audit that invented
    the claim (`docs/codebase-audit.md` §1.1) misdiagnoses why. → **H6, PROVEN.**

The wave under review is, with two exceptions (§H4, §H5-adjacent), **good work**: the
single-flight stale-entry fix, the `NonCancellable` + identity-checked cleanup, the endpoint-scoped
negative cache, the protection-at-destruction rule, the `PartStore.persist` guard, the
`Reconciler` row-keep fix, the Media3 `BitmapLoader` migration and the two Media3
application-thread fixes are all real defects fixed with real tests. It is also **internally
inconsistent in three places** (M6, M7, H4) where a new KDoc contradicts code or another KDoc
it did not update.

---

# Findings

## Concurrency

### C1 — Critical · PROVEN · `shared/src/commonMain/kotlin/dylan/download/DownloadEngine.kt:378-397`, `:407-411`

`worker(index)` records `currentHandle()` — *its own* coroutine `Job` — as the per-attempt
handle for the job it is running (`handles.mutate { it + (job.key to handle) }`), and
`cancelWorker(key)` does `handle.cancel(PreemptSignal())`. `Job.cancel` cancels the whole job,
which includes the `while (true)` loop in `worker`. `runJob` catches `CancellationException`,
runs `onCancelled`, and **rethrows** (`DownloadEngine.kt:593-595`), so the `finally` releases the
key and then the exception leaves `worker` for good. The scope is a `SupervisorJob`, so the
sibling worker survives and the failure is only visible as one `log.c("dl", "engine job crashed")`.

Interleaving: user skips to a prefetching track → `USER_NOW` preempts the `PREFETCH_NEXT` →
worker 0 dies. Later a quality upgrade preempts a bulk download → worker 1 dies. Now
`queue.claimNext()` has no consumer, `wake.receive()` blocks forever, and every subsequent
`PlayNow` enqueues a `USER_NOW`, waits out `readyTimeoutMs` (120 s), reports
`NETWORK_TIMEOUT`, and skips. The intent rows survive in `download_intents` until the next boot's
`resumeIntents()`. Nothing recovers short of a process restart: `start()` is guarded by
`started.compareAndSet(false, true)` (`:253`) and is only ever called once, from
`AppContainer.bootComponents` (`AppContainer.kt:496`).

The `WORKER_COUNT` KDoc (`:775-780`) — "two means one in flight and one queued, which is the
minimum that makes a track boundary not stall" — is false from the first preemption onward, and
so is `handles`' "Coroutine handles of the **jobs** in flight" (`:230`): it holds workers.

**Fix.** Run each attempt in its own child job and cancel *that*:
`val attempt = scope.launch { runJob(job) }` held in `handles`; `worker` then awaits
`attempt.join()` and loops. Independently, restart any worker whose `join` returns while
`started` is true, so a pool can never shrink below `WORKER_COUNT`.

**Test gap that let it through.** `DownloadEngineTest.aDisplacedAttemptSettlesSoItsWaiterDoesNotWaitOutTheTimeout`
(`:1392`) asserts the *displaced attempt settles* and then opens the gate "for the replacer's
resolve" — the replacer is picked up by the **other** worker, so one preemption is invisible.
No test asserts the pool is still `WORKER_COUNT` after a preemption. That is the single
highest-value test to add.

### C2 — Critical · PROVEN · **FIXED** · `AppContainer.kt:646-647` (+ `:149-157`)

```kotlin
/** No lock: every graph member is immutable once built, and publication is enough. */
private inline fun <T> lazyGraph(noinline build: () -> T): Lazy<T> =
    lazy(LazyThreadSafetyMode.PUBLICATION, build)
```

`PUBLICATION` does the opposite of what the comment says: it guarantees only that the *value*
is safely published, and it **explicitly permits the initialiser to run more than once
concurrently** (`SYNCHRONIZED` is the mode that runs it once). Every one of these builders has
heavy, unrepeatable side effects:

| graph | builder | what a second execution leaks |
|---|---|---|
| `net` | `AppContainer.kt:240-264` | 3 `HttpClient`s on the shared engine; the loser's `netRef.store(it)` can publish a graph of clients that `closeNetworkLayer` already closed |
| `playback` | `AppContainer.kt:266-315` | a `DownloadEngine` with `WORKER_COUNT` worker coroutines (`DownloadEngine.kt:255`), an `Orchestrator` inbox loop **and** a `states` collector (`Orchestrator.kt:148-159`), a `componentScope` SupervisorJob |
| `data`/`cache` | `:227-238`, `:216-225` | a second `SettingsStore` cache and a second `CacheManager` over the same `StateFlow`s |

Reachability on Android is not exotic, it is the cold start: `DylanApp.onCreate` calls
`container.start()` → `launchOpen()` → `scope.launch(disp.on(Lane.IO))`; the main thread then
returns and `MainActivity.setContent` composes `AppRoot`, whose first line is
`container.orchestrator.state` (`AppRoot.kt:150`) → `buildPlayback()` → `net.provider` →
`buildNet()`. The io lane is very unlikely to have reached `net` yet (it is behind
`database.open()`), so the main thread usually wins — and then `buildPlayback()` may *also* race
the io lane's own `net` read for the third time.

**Fix.** `lazy(LazyThreadSafetyMode.SYNCHRONIZED, build)` (one line each), or — better, and it
also fixes H1 — force the graphs behind `opened.await()` so the only thread that can build one
is `openOnIo`.

**This comment is a HIGH-severity false invariant in its own right**: it is the reason a future
reader will not look, and `LazyDatabase` right beside it *does* implement the CAS the comment is
pretending to describe (`LazyDatabase.kt:45-55`).

> **FIXED.** `lazyGraph` is `SYNCHRONIZED`, and the comment now states what the mode actually
> guarantees (one build per member, readers block) instead of the opposite. `LazyDatabase` is
> single-flight for the same reason (see H1). Regression tests in `GraphConcurrencyTest`: the
> builder-count test fails `8×` against the old code, and the end-to-end storm test asserts every
> graph is still a singleton afterwards.

### H4 — High · PROVEN · `shared/src/commonMain/sqldelight/migrations/1.sqm:10-12`, `:45-53`

The header states: *"Every COPY is therefore total: no filter, no CASE that can violate the new
CHECK, no NOT NULL on a column v0 allowed to be NULL."* The in-flight wave added a filter
(`:45-47`) and correctly labelled it as "the one hazard the header's list does not name" — so that
one is honest. But the COPY at `:49-53` sanitises **only** the pin invariant
(`CASE WHEN pinned = 1 …`). It copies `bytes`, `bitrate`, `ext` and `play_count` straight from
v0, and v0's `cached_files` had none of the four new CHECKs — that is the whole reason the tables
were rebuilt (`:5-6`). A single v0 row with `bitrate = 0`, `ext = ''` or `bytes < 0` raises a
CHECK violation, which aborts the migration **before** `DROP TABLE cached_files` (`:55`). Nothing
is wiped, `setUserVersion` never runs, so every subsequent open re-runs the identical chain and
fails identically: the install is permanently unopenable. And the wave's own
`DriverFactory.isCorrupt()` change (`DriverFactory.jvm.kt:125-159`) now correctly returns `false`
for anything that is not content corruption, so there is no longer any path that would recover
it.

The FK hazard was *proven* reachable from real data, which is precisely the evidence that the
CHECK hazards are reachable too. **Fix:** clamp each copied column the way `pinned` is clamped
(`MAX(0, bytes)`, `MAX(1, bitrate)`, `CASE WHEN ext = '' THEN 'm4a' ELSE ext END`,
`MAX(0, play_count)`), and correct the header's "no filter" sentence.

### H6 — High · PROVEN (false invariant) · **FIXED (part a: delete; part b: enforce)** · `Orchestrator.kt:52-64`, `:252-264`; `AppDispatchers.kt:14-19`; `docs/codebase-audit.md` §1.1

`Orchestrator`'s class KDoc: *"No handler captures a snapshot and then suspends, so there is no
window in which a second message can interleave between a read and the write derived from it."*
Two problems.

**(a) It is not what makes the state safe, and the audit that invented it is wrong about why.**
`docs/codebase-audit.md` §1.1 claims *"Every `suspend` call inside the inbox loop is a yield
point at which the loop's seriality guarantee evaporates"* and calls this "the root cause of most
of the CRITICAL findings". It does not: the inbox is a single-consumer
`for (m in inbox) guard(...) { process(m) }` coroutine (`:149-152`), so a suspension inside
`process` cannot let a *second inbox message* be processed. What a suspension does allow is
interleaving with the **side-channel** coroutines — `prepareJob` (`:362`), `settleJob` (`:572`),
the 10 Hz ticker (`:1242`), `resyncFault`'s re-prepare (`:1130`), `onTrackChanged`'s
`refreshUpNext` (`:1056`) — which are a *different coroutine* on the same lane and *do* write
`_state`. The KDoc does not mention them. (They are all generation-checked, which is the real
protection; the ledger's P1-1 row is honest about that.)

**(b) The invariant is unenforced, and its only enforcement mechanism is inverted.**
`PlayerState.validate` throws `QueueInvariantViolation` ("A throw, not a log line: these are
programmer errors", `Models.kt:178-181`), and `AppDispatchers.LaneViolation`'s KDoc says a
violation "is a correctness bug that must stop the line that caused it". But `Orchestrator.guard`
(`:252-264`) wraps every handler in `runCatching`, logs, and continues with the inbox loop alive.
The wave's own new comment at `:365-372` documents this happening for real:
`PlayNext` from a cold start threw `QueueInvariantViolation` and *"the button silently did
nothing"*. So a comment asserting the invariant is what makes the state machine safe is, in the
one case the tree has actually exercised, exactly wrong — and it is the same sentence the next
reader will rely on.

**Fix.** Either reword both KDocs to the real contract ("one single-permit lane; every write
re-reads `_state.value`; side-channel coroutines are generation-checked") and say explicitly that
`guard` swallows a queue violation, or make `guard` rethrow `QueueInvariantViolation` (and
`LaneViolation`) after logging so the loop does die as the model claims. Do not leave the
current sentence.

> **FIXED — and the two halves got opposite answers, deliberately.**
>
> **(a) Deleted, not enforced.** "No handler captures a snapshot and then suspends" is not a
> mechanically checkable property: nothing can observe "a suspension happened between this read and
> that write" without a hook that does not exist portably, so any code enforcing it would be a
> comment with extra steps. The class KDoc now states the contract that *is* structural — a
> single-consumer inbox (so a suspension cannot admit a second *message*), generation-checked
> side-channel coroutines as the real interleaving source, and single-assignment state writes — and
> says which of those is what makes the shared state safe. `AppDispatchers.LaneViolation`'s KDoc no
> longer claims a guarantee nothing enforced; it names the place that now enforces it.
>
> **(b) Enforced.** `guard` rethrows `QueueInvariantViolation` and `LaneViolation` after logging them
> CRITICALLY, so `Models.kt:178-181` ("a throw, not a log line") and `LaneViolation`'s "must stop
> the line that caused it" are both true. Making the loop die honestly required closing the `inbox`
> in its `finally` — otherwise a dead lane plus an `UNLIMITED` channel is an unbounded queue, i.e.
> the original PB-1 bug with a different trigger — and routing all six inbox senders through one
> `post()` that `trySend`s and logs a drop, so a closed lane does not throw out of an unrelated
> coroutine. `OrchestratorRecoveryTest.aQueueInvariantViolationStopsTheStateLaneInsteadOfBeingSwallowed`
> fails on the old code (a later `CycleRepeat` still takes effect);
> `oneFailedMessageDoesNotEndPlayback` remains the control for the recoverable case, which must not
> stop the lane.
>
> `docs/codebase-audit.md` §1.1's six stale rows are **not** touched here — out of scope for this
> pass.

### H3 — High · PROVEN (race) / false-invariant comment · `androidApp/.../DylanMediaService.kt:40-44`, `:97-101`, `:186-194`, `:220-221`, `:476-477`

`resumeFuture: ListenableFuture<…>?` and `resumeGeneration: Long` are plain (non-volatile)
fields written from at least three threads: the `container.scope.launch` pre-warm (`:97`,
appScope = `disp.state` = `Dispatchers.Default.limitedParallelism(1)`), the state collector
(`:220-221`, same lane as the pre-warm but a *different coroutine*), Media3's
`onPlaybackResumption` callback (`:186-194`, on the `dylan-media` HandlerThread) and
`onDestroy` (`:476`, main thread).

The new guard's KDoc — *"Bumped on every state emission so a resumption pre-warm that was already
in flight when the state moved cannot cache the table it read before the move"* — is not
achievable as written. Interleaving: the pre-warm reads `resumeGeneration == 0`; the collector
runs, increments to 1 and stores `resumeFuture = null`; the pre-warm's re-read of
`resumeGeneration` is an unsynchronised load that can still return `0` (ARM in particular), so it
stores the pre-move table *after* the invalidation. The check-then-store is not atomic, so even
without reordering it is a TOCTOU on two unsynchronised fields.

**Fix.** `@Volatile` on `resumeFuture` and an `AtomicLong` for `resumeGeneration`, and do the
compare-and-set as one `AtomicReference.update`/`compareAndSet` on the future slot (store the
`(gen, future)` pair and let the collector clear it with one CAS). Then the KDoc is true.

### H2 — High · PROVEN · **FIXED** · `AppContainer.kt:328-331`, `:442-457`, `:471`, `:490`, `:505`, `:535`

```kotlin
openJob.store(job)
if (shutDown.load()) job.cancel()      // :456
```

A `Job` cancelled before its body first runs never executes it, so
`opened.complete(Unit)` / `opened.completeExceptionally(t)` (`:446-451`) never happen. `opened` is
then never completed, and `open()` (`:330`), `publishProtectedKeys` (`:471`),
`bootComponents` (`:490`), `weeklyGcLoop` (`:505`) and `qualityScanLoop` (`:535`) all
`await()` it with **no timeout**. `open()` is documented as "Idempotent and safe to call
concurrently" and is the documented entry point for a platform graph. A caller that loses that
race hangs forever; on the `bgJobs` side, four coroutines park on a `CompletableDeferred` for the
life of the process (and `stop()` would then have to cancel them to recover).

Interleaving is exactly the one the author documented: `shutdown()` sets `shutDown` and calls
`stop()`, which returns immediately (CAS on `RUNNING` fails because `start()` was never called);
concurrently `start()` → `launchOpen()` → `store(job)` → sees `shutDown` → cancels before
dispatch.

**Fix.** Complete `opened` in the `launchOpen` body *and* in a `job.invokeOnCompletion { if (!opened.isCompleted) opened.completeExceptionally(...) }`, or gate `launchOpen` on `if (shutDown.load()) { opened.completeExceptionally(…) ; return }` before creating the job. Independently, bound every `opened.await()` with `withTimeoutOrNull` and log.

> **FIXED, and deliberately *without* the `withTimeoutOrNull` half.** Both suggested mechanisms are
> present (a pre-check before the job exists, plus `invokeOnCompletion` as the backstop), but a
> timeout would turn a *failed* open into a silent one — the four `bgJobs` would carry on booting
> against a graph that was never built, which is the exact class of quiet failure this finding is
> about. `opened` is now settled on every path, so `await()` cannot park; `open()`'s KDoc states
> that and says it throws when the open failed or the container was shut down first.
> `GraphConcurrencyTest.openSettlesEvenWhenShutdownWonTheRace` hangs (fails at
> `open() parked forever`) against the old code, and a soak over the real `open`/`shutdown` race
> catches the window the deterministic test cannot schedule.

## Correctness

### H1 — High · PROVEN · **PARTLY FIXED** · `LazyDatabase.kt:36-43`; reached from `AppRoot.kt:150`

`AppContainer`'s KDoc is emphatic that construction is I/O-free and that `open()` exists so the
open happens "on the io lane … never … between the platform's entry point and the first frame".
`LazyDatabase.value` is the escape hatch that breaks it: any consumer that reaches it first runs
`driverFactory.createDriver()` **on its own thread**, with only a WARN. On Android the first
consumer is the main thread at first composition (`AppRoot.kt:150` → `container.orchestrator` →
`buildPlayback()` → `data.db`), and `DylanApp.onCreate` only calls `container.start()` — never
`container.open()`. On a cold start the main thread wins, so a first-run user pays a full SQLite
open (file create + schema probe + `Schema.create` + 4 PRAGMAs + `integrity`-free verify) on the
UI thread before the first frame, and the boot log line says so.

**Fix.** Build the graphs behind `opened.await()` (see C2) and have `LazyDatabase.value` throw
rather than open, keeping the WARN path only for tests. Or call `container.open()` from a
`Dispatchers.IO.launch` in `DylanApp.onCreate` and gate the UI on `opened`.

> **PARTLY FIXED, and the remainder is not fixable in `shared/`.** The *shared-side* part is done:
> the cold open is now single-flight (`LazyDatabase.cold` is `SYNCHRONIZED`, so a racing thread
> waits for the open already in flight instead of running a second `createDriver` — two
> `Schema.create`s against one path, which is what the old CAS-only shape allowed and what
> `GraphConcurrencyTest.aColdGraphReadOpensTheDriverOnceHoweverManyThreadsRaceForIt` now fails on
> with `the SQLite open ran 8× for one process`). The KDoc no longer implies the graph is free to
> open wherever you touch it: it says a cold read *is* the caller's cost, and that `open()` is the
> gate.
>
> What is **not** fixed is the reachability: a main thread that reads `container.orchestrator`
> before `open()` completes still performs the SQLite open itself, because a synchronous property
> getter cannot await. Closing that requires a change in `androidApp` (`AppRoot.kt:150` /
> `DylanApp.onCreate`), which is outside this fix's ownership. Until it is made, the WARN line is the
> honest signal and it fires exactly once per process.

### H5 — High · PROVEN (false invariant + leak) · `ArtworkBitmapLoader.kt:41-44`, `:56-67`, `:69-77`

```kotlin
/** … Cancelled with the engine, which cancels every in-flight future with it. */   // :41-44
override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
    val out = newFuture()
    scope.launch { runCatching { fetch(uri.toString()) }.onSuccess { out.set(it) } … }
    return out
}
```

Cancelling the `CoroutineScope` cancels the coroutine, **not** the `SettableFuture`. Nothing
else ever completes `out`, and nothing cancels it. `fetch` is declared `suspend` but contains no
suspension point (Coil's `execute` is blocking), so the body normally runs to completion — but
when the job is cancelled *before first dispatch* (Media3's notification path calling
`loadBitmap` after `release()` has already run `artScope.cancel()`,
`ExoPlayerEngine.kt:277-284`) the body never runs and the future never settles. Media3's
`DefaultMediaNotificationProvider` wraps this loader in `SizeLimitedBitmapLoader` +
`CacheBitmapLoader`, so the never-settling future is retained and the notification's artwork
update is permanently pending for the life of the process.

**Fix.** `val j = scope.launch { … }; j.invokeOnCompletion { if (j.isCancelled) out.cancel(false) }`
in both `loadBitmap` and `decodeBitmap`, and reword the KDoc to say the *coroutine* is cancelled.

### M1 — Medium · PROVEN · `Reconciler.kt:172-177`, `:181`; `CacheManager.kt:374-384`

`private fun unlink(path: okio.Path): Boolean = runCatching { fs.delete(path) }.isSuccess` and
`unlinkAll`'s `runCatching { fs.delete(pathOf(claim)) }.isSuccess`. okio's
`FileSystem.delete(path, mustExist = true)` **throws `FileNotFoundException` when the file is
already gone**. So a cached row whose file vanished — deleted externally, or removed by
`DownloadEngine.commit`'s `deleteQuietly(fs, finalPath)` on a failed commit
(`DownloadEngine.kt:690`) — produces `unlink == false` and the row is *kept*, with
`log.e("reconciler", "unlink failed, row kept for retry")`. `checkOne` has no "vanished" branch
at all (contrast `CacheManager.reapEvicting`, which does: `doomed.filter { fs.exists(...) }` →
`vanished`, `:257-260`). Result: the phantom row is re-checked on every weekly sweep forever,
`log.e` fires every time, and `syncTotals` counts bytes that do not exist against the budget.

**The new test cannot see this.** `ReconcilerTest.aSizeMismatchWhoseUnlinkFailsKeepsItsRowForTheNextSweep`
(`:151-158`) makes the failure by `createDirectories(path)` — a non-empty *directory* at the final
path, which is `EISDIR`, not `ENOENT`. It proves "a genuinely unlinkable file keeps its row" and
says nothing about the case that actually occurs. (It is still a worthwhile test; it is just not
the test for this.)

**Fix.** `fs.delete(path, false)` in both places, and add the `size == null → drop the row`
branch to `checkOne` that `reapEvicting` already has.

### M2 — Medium · PROVEN · `DylanMediaService.kt:421-447`

`computeResumptionItems` builds `keys` from the whole resume snapshot, filters to rows that are
`selectCached`-able **and** whose file exists, and then does
`index.coerceIn(0, mediaItems.size - 1)` (`:446`) with `index` taken from `root["index"]` — an
index into the *full queue*, not into the filtered list. Nothing remaps it.

Scenario: the queue is `[A (downloaded), B (never downloaded), C (downloaded)]` and
`index = 1`. `mediaItems` is `[A, C]`; `coerceIn(0, 1)` leaves `1`; Media3 resumes on **C** while
the user was on **B**. Any queue with an undownloaded track before the current one resumes on the
wrong song. (The shared `Orchestrator.restore` → `restoreState` gets this right, which is why this
is a divergence and not a shared bug.)

**Fix.** Build the index from the key list: find `positionOf(snap.items[index])` in the
surviving keys, or carry `(key, bitrate)` pairs and look the current one up. Better: reuse
`dylan.playback.decodeSnapshot` instead of re-parsing the JSON here, so there is one decoder.

### M3 — Medium · PROVEN · `Reconciler.kt:167-177`

`checkOne(..., stampOnly = true)` reads `recorded = currentBytes(...)` which returns `Long?`. If
`selectCached` finds no row, or finds a different rendition, `recorded` is `null`; then
`size != null && size == recorded` is false, and the code logs a *size mismatch* and unlinks a
perfectly good file. During `fullSweep` the rows come from the same `selectAllCached`, so this
needs a concurrent mutation to fire — SUSPECTED, not PROVEN, but the branch is one `?:` away
from a data-loss bug and the log message is wrong in the meantime.

### M4 — Medium · SUSPECTED · `dylan.sq:375-389` + `:202-209`; asserted falsely at `CacheManager.kt:312-316`

`lruVictims` joins `library l` to `media_objects m` on `(provider, song_id)` and `m.state='READY'`
only — it does **not** require `m.bitrate = l.bitrate AND m.ext = l.ext`. The comment at
`CacheManager.kt:312-316` asserts *"One claimed rendition == one library row == the bytes it
reports"*. That is false for a song with two `READY` renditions (the state `reapableObjects`
exists to clean up is documented as real, so such rows are expected). Dropping the non-active one
via `dropObject` fires `trg_media_drop_library`, whose `WHEN NOT EXISTS (… a library row matching
that rendition)` is then true, so it **deletes the whole `library` row** — the user's active
rendition becomes untracked bytes on disk and the next `fullSweep` deletes it as an orphan.

I could not construct a path in the current engine that leaves a second `READY` rendition
surviving `trg_library_asset_{ins,upd}`, which is why this is SUSPECTED. The fix is one
predicate (`AND m.bitrate = l.bitrate AND m.ext = l.ext`) and it makes the comment true. Also
`claimedRows += batch.size` (`:315`) over-counts if two renditions of one song are claimed in the
same batch, so the discount is wrong for that pass.

### M5 — Medium · **SUSPECTED, not PROVEN** · `Orchestrator.kt:604-605` — not fixed

`ensureReadyAndPlay` calls `prepareWindow(index)` (which suspends on the dbLane) and then
`onTrackStarted(song)` with the `song` captured before the suspension and with **no generation
re-check** — `prepareWindow` returns early at `:809` when superseded, but the caller cannot tell.
So a superseded prepare still writes a `play_history` row, `cacheManager.touch`es the key, and
starts a `watchSession` watcher for a track the user has already skipped. Low blast radius (a
history row and a session job that self-cancels on the next track), but it is exactly the
side-channel class the ledger's P1-1 row describes as "every one is gen-checked".

> **Re-classified PROVEN → SUSPECTED after attempting the interleaving, and deliberately left
> unfixed.** A regression test was written and the schedule built: a `FileSystem` that parks
> `openReadOnly` for the up-next track, so `prepareWindow`'s io-lane sniff is held open while the
> test drives the state lane. The supersession then *cannot* be delivered — and the reason is the
> interesting part:
>
>  * `PlayNow`, `advanceOptimistic` and `advanceToIndex` all reach `ensureReadyAndPlay` via
>    `bumpGeneration()`, whose first act is `cancelPrepare()`/`cancelSettle()`. A second `PlayNow`
>    therefore **cancels** the in-flight `prepareJob`; the coroutine cannot resume into
>    `onTrackStarted` at all.
>  * The only untracked caller is `advanceToIndex` from `exhausted`, which runs **inside the inbox
>    handler**. The inbox is single-consumer on a single-permit lane, so while its `prepareWindow`
>    is suspended the only other coroutines that can bump the generation from off-inbox are the two
>    that `bumpGeneration` just cancelled.
>
> So there is no in-tree path to the described symptom, and a "fix" would be an assertion the test
> cannot fail. The missing re-check is documented in `ensureReadyAndPlay`'s KDoc with the reasoning
> above, so a future change that opens a path (a new generation bumper that does not cancel, or an
> `ensureReadyAndPlay` call site outside the inbox) inherits the caveat.

## Resource & lifecycle

### M6 — Medium · PROVEN (false invariant) · **FIXED** · `FileLogSink.kt:42-43`, `:52`, `:73-83`, `:86-89`

`out` and `written` are plain fields. `flush` and `drainLoop` both take `writeMutex` (correct,
and the `flush` KDoc says so), but **`close()` does not** (`:86-89`): it closes and nulls `out`
from whatever thread called it, concurrently with `writeEntryLocked`. The `written` accounting is
a plain read-modify-write from two threads with no barrier, so a rotation boundary can be missed
or double-counted and the log grows past `maxBytesPerFile` without rolling. On Android
`close()` is never called (`stop()`/`shutdown()` have no caller — the ledger's §7 row is
accurate), so this is latent there and live on the iOS terminate path. Fix: take
`writeMutex.withLock` in `close()` (make it `suspend`) or route it through the same writer
coroutine.

> **FIXED.** `close()` is `suspend` and takes `writeMutex`, so it cannot shut the handle between
> `writeEntryLocked`'s read of `out` and its `s.write` on that sink. Its KDoc now says so, and says
> why `suspend` is required rather than incidental. Regression test:
> `FileLogSinkTest.closeWaitsForALineBeingWrittenRatherThanClosingTheHandleUnderIt` parks the writer
> inside `Sink.write` via a `ForwardingFileSystem` and asserts `close()` has *not* returned while a
> line is on the stack; against the old code it fails with `close() returned while a line was being
> written — it does not take writeMutex`, and it then asserts the blocked line lands exactly once.
> `AppContainer.closeLogTrail` is the only caller and is already suspending, so the signature change
> is local.

### M7 — Medium · PROVEN · `Reconciler.kt:38-40`; falsifies `SettingsStore.kt:12-21`

```kotlin
// Defaults to a private instance because AppContainer (owned by the DI wave) still builds this
// with the pre-settings argument list. One-line fix, in the wave's report: pass data.settings.
private val settings: SettingsStore = SettingsStore(db, disp, cfg),
```

A self-admitted workaround, permanently in the tree, four waves after the "wave" it names. It is
not cosmetic: `SettingsStore` is a **stateful cache** with a `Mutex` and a per-instance
`mutableMapOf`, and its KDoc's stated invariant is *"the invariant that makes this safe is that
`SettingsStore` is the only writer to the `settings` table for every key that is read through
here"*. There are now two instances, so that invariant is false as written. It happens not to
produce a wrong value today (the reconciler only touches `FULL_SWEEP_KEY`, and always through its
own instance) — but the comment that makes it safe is the one that is now untrue. Pass
`data.settings` and delete the default.

### M8 — Medium · PROVEN (data race) · `SettingsStore.kt:36-43`

`get()` reads `cache[key]` **outside** the mutex and writes it inside:
`cache[key]?.let { return it }` then `mutex.withLock { cache[key] ?: withContext(db) { … }.also { cache[key] = it } }`.
That is an unsynchronised read of a `LinkedHashMap` concurrent with a `put` from another thread —
in the hottest read path in the app (`qualityPref()` once per download job, `resume` on every
boot, `gc_last_ms` on every GC tick). A read during a table resize can miss a present key (benign
here, it re-reads the DB) or, on a `LinkedHashMap` mid-resize, observe a stale bucket array.
PROVEN as a race; SUSPECTED as a practical failure. Fix: read inside the lock, or make `cache` a
`ConcurrentHashMap`.

### M9 — Medium · PROVEN (ordering) · `DylanMediaService.kt:483`, `:495-498`

`onDestroy` calls `container.orchestrator.detachEngine()` (which asynchronously posts
`engine?.pause()` to the media looper via `Orchestrator.onEngineDetached` → `:302`) and *then*
`e.postToMedia { s?.release(); e.release() }`. Nothing orders the two posts, so `player.pause()`
can be delivered to an already-released `ExoPlayer` (Media3 documents post-`release()` calls as
undefined). Fix: perform the detach inside the same `postToMedia` block, before `e.release()`,
or have `detachEngine` return a `Job` and `join()` it.

### M10 — Low · PROVEN · `DylanMediaService.kt:97-101`

The pre-warm `container.scope.launch { … }` job handle is discarded and never cancelled in
`onDestroy` (`:474-500` cancels `routeJob` and `stateJob` only). It can complete after the
service is gone and write `resumeFuture` on a dead object, and it performs a settings read + a
dbLane pass nobody is waiting for. Hold the job and cancel it.

### M11 — Low · PROVEN · `DownloadQueue.kt:227-229`

`TransferGate.release()` is `tokens.trySend(Unit)` with the result discarded, while its sibling
`DownloadEngine.poke()` (`:401-404`) logs loudly for exactly the same shape and carries a comment
saying the wake "cannot drop unless closed". An over-release (a double `release` from a
cancelled `finally` plus a normal path) is silently swallowed, so the pool permanently loses a
permit and one fewer transfer ever runs. Log it, as `poke` does.

### M12 — Low · PROVEN · **FIXED** · `AppContainer.kt:188`, `Orchestrator.kt:86`

`AppContainer.onToast` and `Orchestrator.toast` are plain `var`s of function type. `onToast` is
read on whatever thread the UI calls from; `Orchestrator.toast` is **written from the io lane**
(`AppContainer.kt:492`, inside `bootComponents`) and **read from the state lane**
(`Orchestrator.kt:766, 780, 784, 1090, 1125`). A cross-lane unsynchronised publication of a
non-null value. `AtomicReference` costs nothing here.

> **FIXED.** Both are now `AtomicReference`-backed properties. A data race cannot be provoked
> deterministically from a test (x86 reorders almost nothing; the ARM window is small and
> timing-dependent), so `CrossLaneToastPublicationTest` asserts the *property* instead — that no
> toast hook is stored in a plain field — via a reflection check that names the defect directly.
> Both fail against the old code with `stores a toast hook in a plain field (toast)` /
> `(onToast)`.

## SQL & schema

Beyond **H4** (the migration) and **M4** (`lruVictims`):

* **`upsertIntent` (`dylan.sq:621-629`)** — `ON CONFLICT … DO UPDATE … WHERE (CASE excluded.reason …) <= download_intents.priority`. Correct, and the two triggers that maintain `priority` on INSERT/UPDATE OF reason are the only place the CASE exists. Clean. Note that the *caller* therefore never has to reason about priority: `LibraryCommitter.writeIntent` can write any reason and the table sorts it. Verified consistent.
* **`trimHistory` (`:544-546`)** — the comment's claim that it is a covering-index range delete off `idx_history` is right, and the tie-preservation note ("Ties at the cut millisecond are all kept") matches the SQL. Clean.
* **`recency` triggers `trg_library_total_*` (`:142-164`)** — the "no `INSERT OR IGNORE` inside a trigger" note is a real, correct SQLite subtlety and the `INSERT … SELECT … WHERE NOT EXISTS` form is the right workaround. `MAX(0, …)` clamping hides under-count rather than reporting it; `syncTotals` is the self-heal and `Reconciler.run` calls it on every boot. Clean.
* **`deleteOrphanLibrary` / `deleteOrphanObjects` (`:557-563`)** — both exist and both are called (from `Reconciler.fullSweep:133-137` **and** `Repos.weeklyGc:245-246`). The ledger's D-2d "two places" claim is verified. Clean.
* **`gcSongs` (`:312-320`)** — `provider || '#' || song_id NOT IN ?` is always called with a non-empty list (`Repos.kt:236` substitutes `"::"` when the protect list is empty), so the empty-`IN` case cannot fire. Clean, and the guard is deliberate.
* **Foreign keys on Android** — `DriverFactory.android.kt:57-60` issues `PRAGMA foreign_keys=ON` *after* `AndroidSqliteDriver` has already run `Schema.create`/`migrate` inside its own callback transaction, where the pragma is a no-op. So the `library → songs` FK is **not** enforced during the migration on the production platform. The wave's `1.sqm` `DELETE` (`:45-47`) therefore does the real work there, which is fine — but its comment's justification is explicitly scoped to the JVM factory, and `DriverFactory.android.kt`'s `verify` (`:75-78`) only *logs* a foreign_keys failure where the JVM one `error(...)`s. The two factories now enforce different policies. Low; worth one line in the doc rather than a code change.

## Hacks & workarounds

| Site | What it papers over | Real fix |
|---|---|---|
| `Reconciler.kt:38-40` | A second `SettingsStore` (M7) | pass `data.settings` |
| `FileLogSink.kt:124-132` | `staging.clear()` moved to a `finally` — the wave's own fix; the comment is honest about the reused-buffer residue it corrects | none needed; keep |
| `DylanMediaService.kt:453-465` | Not calling `super.onTaskRemoved` | The reasoning is sound and *measured* (Media3 1.11's base reaches the player from the main thread while the session's application looper is `dylan-media`). The rule is re-implemented by `stopWhenPlaybackEnds`. Legitimate. Keep, and consider a regression test that `onTaskRemoved` does not throw. |
| `DylanMediaService.kt:240-259` | `applyAvailableCommands` posts to the media looper instead of reading `connectedControllers` inline | The comment is a precise, correct account of a real Media3 1.11 constraint. Legitimate. But nothing tests it — it is a *platform* threading fact that the JVM suite structurally cannot cover, and the failure mode it fixes (the collector dying on the first emission, before the buttons or `stopWhenPlaybackEnds` ever run) is severe. Add a Robolectric/instrumentation smoke test or a comment in the ledger marking it device-only. |
| `DriverFactory.jvm.kt:167-178` | `isContentCorruption` matches SQLite error **strings** because `org.sqlite.SQLiteException` is not on the compile classpath | Add `sqlite-jdbc` as a direct `testImplementation`/api dependency and use `resultCode`. The string matching is now covered by a real test (`DriverFactoryWipeGateTest.aFileThatIsNotADatabaseIsStillTreatedAsCorrupt`), so this is a robustness note, not a live bug. |
| `DriverFactoryWipeGateTest.kt:92-95` | `if (!probeFails) { …; return }` — the test **silently passes** when run as root | Acceptable and documented, but it should be `assumeTrue`-shaped so a skipped precondition is visible in the report rather than indistinguishable from a pass. |
| `SingleFlightStressTest.kt:222`, `:241` | `Thread.sleep(250)` / `Thread.sleep(20)` in a test whose subject is real-thread interleaving | See T3. |
| `ProbeMain.kt` `!!` ×7 (`:206,266,270,271,395,397,405,411`) | All inside a JVM `main` CLI | Legitimate; the ledger's §5 #1 already excludes this file. |
| `AlbumScreen.kt:188`, `ArtistScreen.kt:158` (`errorCode!!`), `DylanMediaService.kt:197` (`session!!`) | Unchecked casts on values the surrounding `runCatching`/`if` already established | Low. `session!!` at `:197` is provably non-null (assigned 12 lines above); `errorCode!!` deserves a `?:` with a neutral message. |

## Performance

* **`AppContainer.scanQualityUpgrades` (`:546-573`) — the N+1 that was already fixed elsewhere.**
  It does `selectAllCached().executeAsList()` (every cached row), filters in Kotlin, then issues
  one `selectSong` per surviving row, **all inside one `withContext(disp.on(Lane.DB))`**, every 30
  minutes. `selectUpgradeCandidates` (`dylan.sq:361-366`) does the predicate, the `has_320`
  filter, the ORDER BY and the LIMIT in SQL in **one** round trip, and `Repos.upgradeCandidates`
  (`:217-227`) wraps it — with a KDoc naming this exact defect ("301 round trips through the
  single dbLane every 30 minutes, blocking playback DB work for the duration"). `upgradeCandidates`
  has **zero callers**. Fix: delete `scanQualityUpgrades`' body and call `data.upgradeCandidates(disp, UPGRADE_FROM_BITRATE, MAX_UPGRADE_CANDIDATES)`.
* **`WindowPreparer.cachedRows` (`:49-63`) — the comment says "N+1-free" and the body is the N+1.**
  `for (k in wanted) { db.dylanQueries.selectCached(...) }` — one query per key, just moved inside
  the `withContext` so it is one lane hop instead of N. `selectSongsByIds` (`dylan.sq:306-307`)
  and the `HashSet` pattern in `Orchestrator.loadSongs` (`:1344-1362`) are the batch form. Called
  from `prepareWindow` (2 keys) and `refreshUpNext` (1) on every window build, i.e. every track
  change. Fix: one `selectCachedByIds` query, or reuse the `loadSongs` shape.
* **`SearchScreen.kt:96-102` — the merged submit list is a fourth copy of the ordering and was
  not migrated onto the new shared comparator.** It builds `Triple(relevanceBand(q, title), order, Hit)`
  and sorts with its own `compareBy`, calling the **public** `relevanceBand`, which does
  `normTitle(query)` on every element (`SearchRank.kt:124-127`). `normTitle` is
  `trim().lowercase()` plus a `Regex("\\s+")` pass (`Identity.kt`), so a 60-hit submit costs 60
  redundant query normalisations on the main thread inside a `remember`. Meanwhile the wave's new
  `rankMerged` (`SearchRank.kt:61-69`) — built for exactly this — has **zero production callers**
  (only `SearchRankTest.rankMergedInterleavesBucketsBySharedBand`). Fix: `remember { rankMerged(q, listOf(results, albums, artists), Hit::title) }`.
* **`AppRoot.kt:154-162` — the lambda-stability comment is half true and the cost is real.**
  Only `playNow` and `openArtist` are `remember`ed. `onBack`, `onOpenAlbum`, `onOpenSettings`,
  `onOpenArtist` (in `NpOverlay`), `onExpand`, `onLongPressClear` are fresh instances on every
  recomposition, so **none** of the six screens can skip, on every `PlayerState` emission.
  Fix: `remember` the rest (they capture only `backStack`/`sheet`, both stable snapshot state).
* **`CacheManager.demotePins` (`:345-368`)** — 3 dbLane round trips per iteration, run on
  **every** `enforceBudget`, which `DownloadEngine.commit` calls after every download and
  `Favorites.add` calls on every favourite. Over budget it is O(favourites) iterations. It
  early-returns on the first read when under budget, so the steady-state cost is 2 round trips
  per download; acceptable, but the `oldestPinned` + `demotePin` pair should be one statement
  (`UPDATE … ORDER BY … LIMIT 1` via a subquery) to halve it.
* **`ResilientClient.planLocked` (`:213-220`)** — every cache hit rebuilds the whole `LinkedHashMap`
  to do an LRU reorder. At `catalogLruEntries = 24` that is 24 entries per album open. Correct
  and cheap; noted only because the wave's own comment calls the map an "Immutable snapshot
  swapped wholesale", which it is, at the cost of an O(n) copy per hit.
* **Clean:** `bounded()` (`DownloadEngine.kt:819-834`) is O(limit); `ProgressThrottle`
  (`:85-109`) is time-throttled with a no-op filter; `slotIndexOf` (`Orchestrator.kt:938-948`)
  replaced a `startsWith` per queue entry per event; `loadSongs`/`knownSongKeys` are chunked and
  set-based; `MiniProgress` (`AppRoot.kt:509-532`) defers the 10 Hz read into the draw phase via
  `graphicsLayer`; all 27 Android `collectAsState` sites use `collectAsStateWithLifecycle`
  (0 bare). Genuinely good.

## Comments & docs

Quoted, located, and corrected. The brief's rule applies: *a comment asserting an invariant the
code does not enforce is worse than no comment.*

| Severity | Site | What it says | What is true |
|---|---|---|---|
| **High** | `AppContainer.kt:646` | "**No lock**: every graph member is immutable once built, and publication is enough." | `LazyThreadSafetyMode.PUBLICATION` permits concurrent initialisation by design. The builders are not idempotent. See **C2**. — **corrected** |
| **High** | `Orchestrator.kt:52-64` + `AppDispatchers.kt:14-19` + `Models.kt:178-181` | "No handler captures a snapshot and then suspends", and a `LaneViolation` "must stop the line that caused it". | The first is unenforced and its only enforcer is swallowed by `guard`; the second is contradicted by `guard` catching it. See **H6**. — **corrected** |
| **High** | `ArtworkBitmapLoader.kt:41-44` | "Cancelled with the engine, which **cancels every in-flight future** with it." | Scope cancellation cancels the coroutine. The `SettableFuture` is never completed or cancelled. See **H5**. |
| **High** | `DylanMediaService.kt:40-43` | "Bumped on every state emission so a resumption pre-warm … **cannot** cache the table it read before the move." | Three unsynchronised writers; the check-then-store is a TOCTOU. See **H3**. |
| **High** | `1.sqm:10-12` | "Every COPY is therefore **total**: no filter, no CASE that can violate the new CHECK…" | The CHECKs are unenforced on the copy. See **H4**. |
| **Medium** | `AppConfig.kt:48-54` | "the origin's `Retry-After` is parsed **nowhere in production** (`ResilientClient.retryAfterMs` has test-only callers), so a 429 costs exactly this long regardless of what the origin asked for." | The in-flight wave wired it in: `ResilientClient.negativeTtlMs` (`:316-321`) now feeds `retryAfterMs` into the negative cache. **In-flight inconsistency** — the wave updated `retryAfterMs`'s own KDoc ("This is now wired to production") and left this one asserting the opposite. |
| **Medium** | `SettingsStore.kt:12-21` | "the invariant that makes this safe is that `SettingsStore` is the **only writer** to the `settings` table…" | There are two instances. See **M7**. |
| **Medium** | `CacheManager.kt:312-316` | "One claimed rendition == **one library row** == the bytes it reports." | `lruVictims` does not restrict to the active rendition. See **M4**. |
| **Medium** | `WindowPreparer.kt:49` | "**Every** cached row for `[keys]` in a **single** `dbLane` pass — the **N+1-free** form of `cachedRow`." | One query per key. The *lane hops* are batched; the queries are not. |
| **Medium** | `SearchRank.kt:34-41`, `:54-59` | "`relevanceOrder` … **The one ordering every surface shares.** It was written out three times — … **the merged submit list on Android** …" / "`rankMerged` … **Written once per platform**, and the two copies disagreed." | Android's merged list (`SearchScreen.kt:96-102`) is still its own copy and was not migrated. `rankMerged` has no production caller. The claim is false about the tree as it stands. |
| **Medium** | `AppRoot.kt:154-155` | "Stable across recompositions: a fresh lambda instance here is a changed parameter for **all six screens**, so **none of them could skip**." | Two of eight lambdas are `remember`ed. The claim describes the problem the fix was for, then the fix only covers a fifth of it. |
| **Medium** | `Reconciler.kt:38-39` | "Defaults to a private instance because AppContainer (**owned by the DI wave**) still builds this with the pre-settings argument list. One-line fix, **in the wave's report**." | A cross-wave TODO in a shipped source file. See **M7**. |
| **Low** | `Orchestrator.kt:977-985` and `:998-1009` | The same 8-line Media3 ordering argument is written out **twice**, in two adjacent KDocs, on two functions that do the same thing. | One of them belongs on `autoplayReady`; the other's subject (`onPrepared` is a buffering event) is a different point and should be one sentence. |
| **Low** | `Orchestrator.kt:49` | "…one which the Orchestrator now imports…" — no; the neighbouring `RESTART_PREVIOUS_MS` mirror in `DylanMediaService.kt:62-63` is a *duplicated literal* with a comment saying "Mirrors `Orchestrator.RESTART_PREVIOUS_MS`, which is private to the shared module." | A magic number duplicated across a module boundary. Fix: make the constant public in `Orchestrator`'s companion and reference it. |
| **Low** | `Orchestrator.kt:367-372` | Documents the `QueueInvariantViolation`-swallowed-by-`guard` bug at length, and the fix shipped was "handle the empty queue" — **not** "stop swallowing it". | Accurate as history, but it leaves the reader with the impression the class of bug is handled. It is not; it is one `commitQueue` call away. |
| **Low** | `Orchestrator.kt:1055` (via the new `autoplayReady`) | "…and the user removed this row from under the engine (phase already past `Ready`, so only the phase is refreshed)." | Accurate. Verified against the code. |

### `docs/codebase-audit.md` is stale in at least six checkable ways

Header says *"Commit: `749bdca`"*, 2026-09-27. Verified against the tree:

| Claim | Reality |
|---|---|
| §1.1 "The single-threaded `state` lane is being treated as a mutex. It is not." — *"the root cause of most of the CRITICAL findings"* | The inbox is single-consumer; suspension cannot admit a second message. See **H6**. |
| §1 "`Orchestrator.kt:100-103` … with **no `try`/`catch` anywhere in `process`**" | `guard` at `Orchestrator.kt:252-264` wraps every message, added since. (Though it now swallows queue violations — **H6**.) |
| §1 "`DownloadEngine.kt:311,371,433,504` — `partB` … **never** from the writer. Every retry restarts from byte 0." | `partB` does not exist. It is `Breakpoint` (`Breakpoint.kt`), written by `Transfer` and folded by `parts.note`/`parts.persist`. |
| §1 "`Clients.kt:52` — `requestTimeoutMillis = 45_000` on the bulk client" | `bulkClient` has no whole-request timeout; the removal is documented in a 12-line comment at `Clients.kt:74-84`. |
| §1 "`ExoPlayerEngine.kt:259-267` — `release()` … **called from nowhere**" | Called from `DylanMediaService.onDestroy:495-498` and `Orchestrator.disposeOnState:228`. |
| §1 "`build.gradle.kts:112` sets `abortOnError = false`" | `androidApp/build.gradle.kts:126` sets `abortOnError = true`. |
| §1 "20 × `collectAsState`, **0** × `collectAsStateWithLifecycle`" | 0 bare, 27 with-lifecycle. |
| §1 "`DownloadEngine.kt:122,143-149` — `start()` relaunches into the dead scope" | Fixed: the scope is rebuilt in `start()` (`DownloadEngine.kt:249-252`). |

The ledger's §6 already disclaims four audit rows; this adds six more. The audit's *method*
claim — "all findings verified against source" — does not survive contact with the current tree.
Nothing here says the audit was wrong in 2026-09; it says it cannot be used as a worklist now.
`docs/feedback-ledger.md` §6 remains accurate on every row I re-checked (D-2e, `upgradeSourceKeys`,
the `--no-configuration-cache` count, `release.yml` fails closed) — and §7's "Android never calls
`container.stop()`" is confirmed against `MainActivity.kt`, which has no such call.

## Tests

353 tests, 0 failures. Assessment below is about **what they can detect**, not whether they pass.

### Tests that cannot fail (or cannot detect what they name)

* **T1 — `DriverFactoryWipeGateTest.theOptOutIsNotAnAccidentOfConstruction` (`:106-112`) — cannot fail.**
  It opens a *healthy* database with a default-constructed factory, so `createDriver`'s catch is
  never entered, and asserts no "wiping" line was logged. Mutation that slips past: change
  `allowWipeOnCorruption`'s **default** to `true` (the test still passes — a healthy DB still
  opens); change any production call site to pass `true` (the test does not construct one). Its
  own KDoc says "this is the note" — correct, but it is a note wearing a test's clothes. Fix:
  make it a **lint/architecture test** that greps the tree for `allowWipeOnCorruption = true` /
  `allowWipeOnCorruption =` outside `jvmTest`, so the property it claims ("every production call
  site takes the default") is actually asserted. That property is worth having — a single flipped
  default deletes every user's library.
* **T2 — `LogSinkPerfTest.logPathCosts` (`:22-46`) — cannot fail for any realistic regression.**
  The ceiling is `PER_LINE_CEILING_NS = 100_000L` (100 µs/line) against a measured cost in the
  hundreds of ns. The KDoc names the regressions it is supposed to catch — "a ring that stopped
  being a ring, a `removeAt(0)` that went quadratic, redaction running its two regexes on every
  ordinary line" — and a 512-element `removeAt(0)` per line plus two regex passes is still
  single-digit µs, i.e. an order of magnitude *inside* the band. Better than the `assertTrue(true)`
  it replaced (and that replacement is a real improvement worth crediting), but it is a
  smoke test wearing a gate's clothes. Fix: either drop the assertion and keep the `println`, or
  tighten to a measured-multiple (assert against a baseline captured in the same run, e.g. 8× the
  median of three batches) so a *relative* regression is visible.
* **T3 — `SingleFlightStressTest.aCancelledLeaderStillReleasesTheKeySoTheNextCallerReloads` (`:181-264`) — under-detects by 250 ms.**
  The test is genuinely good — the `LatchingClock` blocking inside `storeLocked` turns a race into
  a schedule, which is exactly the right technique, and it does fail against the pre-fix code. But
  step 3 of the documented schedule depends on `Thread.sleep(250)` (`:222`) giving the
  already-cancelled leader time to reach its cleanup *while the mutex is still held*. On a loaded
  CI box where it does not, `releaseMutex()` fires first, the old code's plain `lock.withLock`
  then finds the mutex free, removes the entry anyway, and **the test passes green against the
  unfixed code**. The final poll loop (`:239-241`) then also has a 5 s budget with
  `Thread.sleep(20)`, which is fine. Fix: replace the sleep with a second `CountDownLatch` that
  the leader's cleanup counts down — but the cleanup is production code, so the honest options are
  (a) make the sleep a `withTimeout`-bounded wait on an observable (`clock.inside`-style) or
  (b) accept it and mark the test `@Flaky` with the reason, so a green run is not over-read.
  Note this is the same shape of problem as the two known flakes, in the *opposite* direction
  (under-detecting, not over-flaking).
* **T4 — `ReconcilerTest.aSizeMismatchWhoseUnlinkFailsKeepsItsRowForTheNextSweep` (`:151-168`) —
  cannot distinguish its two causes.** The final path is made undeletable with
  `createDirectories(path)` (EISDIR/ENOTEMPTY), so the test proves "a genuinely unlinkable file
  keeps its row". The case that actually occurs in the field is ENOENT (the file is already
  gone), which takes the same `false` branch and is therefore **uncovered** — see **M1**. Fix:
  add the sibling case with no file at the path at all, asserting the row is dropped.
* **T5 — `SearchRankTest.relevanceOrderIsTheSharedComparatorAndAPermutation` (`:93-113`) — the
  headline assertion is near-circular.** `expected` is computed as
  `titles.indices.sortedWith(compareBy({ relevanceBand("laado", titles[it]) }, { it }))` and
  compared to `relevanceOrder("laado", titles)` — the same band function, the same total
  comparator, differing only in `withIndex`-vs-`sortedWith`. The permutation and `take(3)`
  assertions are real; the first is a restatement. Also: the KDoc claims the shared comparator
  now serves "the merged submit list on Android" and `rankMergedInterleavesBucketsBySharedBand`
  (`:136-152`) tests `rankMerged`, **which no production code calls** — so the only coverage of
  the "one ordering" claim is of an unused function. Fix: migrate `SearchScreen.kt:96-102` onto
  `rankMerged` and then the test guards something real.
* **T6 — counters instead of behaviour.** The suite is mostly good here (the wave's
  `shutdownStopsTheStateLaneInboxAndNotJustTheBackgroundJobs` explicitly notes that
  `noCoroutineOutlivesStop` "asserts on `backgroundJobCount` … so it passed green while the one
  component that owns every lane-confined field stayed live", and replaces it with a behavioural
  check — exactly the right move). Two survivors: `CacheManagerTest`'s
  `assertEquals(1L, …song_count, "precondition, and the real assertion: …")` (the count is not the
  assertion, and the comment says so) and `aBadRequestOnOneQueryIsNotReplayedForEveryOtherQuery`'s
  `assertEquals(2, requests.size, …)` — that one is fine, because the `is Ok` assertion above it
  carries the behaviour. No action beyond the note.
* **T7 — `aRestoreDisarmsTheResolveItSupersedes` (`OrchestratorEdgeTest.kt:840-868`) — a negative
  assertion behind a 2 s sleep.** It asserts the restored track is *still* current after
  `delay(2000)`. If the superseded skip were merely *slower* than 2 s on a loaded box, the test
  passes against broken code. Fix: assert on a positive observable (e.g. that the abandoned
  attempt's `JobState` is `Cancelled` **and** that `states` contains no entry for the abandoned
  key's skip) rather than on the absence of a change after a wall-clock delay.
* **T8 — virtual time closes interleavings real concurrency exposes.** `TestLanes.virtual()`
  (`TestLanes.kt:104-110`) makes **all four** lanes single-permit, including `io`, which is
  `Dispatchers.IO` (multi-permit) in production. The KDoc states this honestly and points at
  `TestLanesContractTest` for the overlap contract — good. But the consequence is not called out
  where it matters: `CacheManagerTest` and `ReconcilerTest` are the only virtual-time suites, and
  **`DownloadEngineTest` and `OrchestratorEdgeTest` use the production profile** — so the
  two-worker pool and the `io`-lane transfer concurrency *are* exercised on real threads, which is
  why **C1** should have been caught and was not (no test looked). Worth one line in `TestLanes`'s
  KDoc naming which suites may and may not use which profile.

### Coverage gaps, by blast radius

1. **Download worker-pool liveness after a preemption** (**C1**). Nothing asserts
   `WORKER_COUNT` live workers, or that a `USER_NOW` enqueued *after* a preemption ever runs.
   One test closes the whole finding. **Highest value in this section.**
2. **`AppContainer` graph construction concurrency** (**C2**). **Closed** — `GraphConcurrencyTest`
   covers the builder-exactly-once property (deterministically, via a barrier inside the builder),
   the driver's exactly-once open (**H1**'s mechanism), and an end-to-end storm over all four graphs
   that must leave one working container. Still open: no test asserts that a cold `open()` does *not*
   run on the caller's thread, because that needs an `androidApp` change (**H1**'s reachability).
3. **The "file is already gone" unlink branch** (**M1**, **T4**).
4. **`1.sqm` totality beyond the FK** (**H4**). `CacheMigrationTest` has a genuine v0 store
   harness (`withV0Store`) and a good "the whole chain ran" index assertion
   (`aV0StoreWithAnOrphanCachedRowStillMigrates:178-240`). It is missing exactly one test:
   `withV0Store { it.exec("UPDATE cached_files SET bitrate = 0, ext = '' ") … }` and assert the
   migration still lands. That one test would have caught H4 before the wave shipped it.
5. **Resumption index remapping** (**M2**). `computeResumptionItems` is `private` and
   Android-only, so it is structurally untestable on the JVM — which is why it needs the
   extracted-decoder refactor first, not a test.
6. **`SettingsStore` under concurrent get/put** (M8). No test exercises two coroutines through
   one `SettingsStore`. `SettingsStore` is easy to test (one `withV0Store`-style driver) and this
   is the hottest read path in the app.
7. **`allowWipeOnCorruption` call-site audit** (**T1**). Needs the lint-style test described above.
8. **Android `resumeGeneration`** (**H3**). Structurally untestable without Robolectric; the
   fix (atomics) is cheaper than the test.

### The wave's tests, credited

`aDisplacedAttemptCannotEvictTheBetterRequestThatDisplacedIt` and
`aReadmitIsStillHonouredWhenNothingBetterOwnsTheKey` (both pure `JobQueue`, no scheduling) are
exactly the right shape for the `readmit` rank guard and would fail if the guard were deleted or
loosened to `>=`. `aCompletedDownloadLeavesNoSidecarForAPartThatIsGone` asserts a directory
listing, not a counter. `queueingTheFirstTrackFromAColdStartTakes` proves the inbox is still live
afterwards — guarding the `guard`-swallow failure mode directly. `clearUpNextDropsTheTrackFromTheEngineWindowToo`
is behavioural. `shutdownClosesTheEngineEvenWhenStartNeverReachedRunning` exercises the exact
CAS-failure path the wave's KDoc describes. `autoplaySurvivesATrackChangedThatArrivesBeforePrepared`
correctly drives the *reverse* of the fake's ordering, which is the finding. The negative-cache
five (`aNotFoundOnOneAlbum…`, `aBadRequestOnOneQuery…`, `anOriginFailureIsStillReplayed…`,
`aRateLimitIsParkedForAsLongAsTheOriginAsked`, `aShortRetryAfterNeverShortensTheFloor`) each
assert a **request count** *and* a **result value**, which is the right pairing: a wrong
implementation that keeps the count but changes the answer, or keeps the answer but spends the
request, is caught. `aFileThatIsNotADatabaseIsStillTreatedAsCorrupt` /
`anUnreadableFileIsNotEvidenceOfCorruption` are a genuinely good pair — both directions of the
wipe gate, with a precondition probe so neither can pass vacuously.

---

# Areas examined and found sound — do not redo this work

* **The four-lane model itself.** `AppDispatchers` (`:105-176`) is the best-documented file in the
  repo and its KDoc is *true*: the four properties really are the unwrapped dispatchers, the
  `LaneTag`/`lanePublication` split really is the only portable mechanism available (the
  kotlinx-coroutines#4208 argument is correct), and `LaneViolation` really does throw in every
  build. The claim "`limitedParallelism(1)` may be served by more than one pool thread over a
  coroutine's life" (`:98-99, 159-160`) is the right caveat and it is why plain
  `mutableMapOf` in `WindowPreparer`/`ResilientClient`-adjacent lane-confined state is safe.
  Verified: `WindowPreparer.verified`/`current` are only touched from the state lane; the io-lane
  hop in `sniffOk` is a suspension, not a parallel access.
* **`ResilientClient`'s single-flight and negative cache.** The sweep removal, the
  `NonCancellable` + identity-checked cleanup, the per-entry TTL, the endpoint-scoping predicate
  and the `Retry-After` clamp are all correct as implemented and correctly explained. The
  `entries`/`inflight`/`negative` maps are all mutated only under `lock`; I checked every site.
  The only dead code is `planLocked:210`'s non-`Ok` TTL branch (Low, listed above).
* **The protection-set-as-a-table design.** `CacheManager`'s class KDoc, the
  `publishProtection`-inside-the-claim rule, and the new "protection is consulted at the point
  of destruction" rule in `reapEvicting` all match the code. `publishProtection` is called at the
  head of **all four** claim transactions (`:331`, `:360`, `:160`, `:188`). `AppContainer.publishProtectedKeys`
  is genuinely the only writer of `protectedKeys` (verified: no other assignment), and
  `isRemovable` / `readDownloads().removable` read the same live sets the table is built from,
  so the UI's "removable" and the claim guard cannot disagree.
* **`claimLruVictims`'s discount** (`CacheManager.kt:277-317`). The reasoning — "`cache_totals`
  only moves when a row is *dropped*, and the drop happens after the unlink, outside the loop" —
  is correct, and `syncTotals` in `Reconciler.run` is the self-heal for any drift.
* **`PlayerState`'s algebra.** `nextIndexIn`, `anchoredOrder`/`carriedOrder`, the
  `Repeat.ONE`-pins-vs-`Repeat.ALL`-wraps distinction, and the 64-bit bitset/`HashSet` split at
  `>64` (the `1L shl i` wrap bug it documents is real and correctly avoided) are all sound and
  well covered by `commonTest`'s property tests. `validate` is total.
* **Duration totality.** `0 == UNKNOWN` is honoured end to end: `Mapper` records
  `DURATION_UNPARSED` drift, `durationKnown` exists and is asserted,
  `ResilientClient:72-82` states the consequence, `upperBoundMs` uses `> 0L`,
  `clampSeekTargetMs` uses `> 0L`, `WindowPreparer.trackFor` passes the hint through unchanged,
  and `chooseQuality`/`paddedEstimate` never size a transfer from a 0 without the band. The
  wave's `JsonElement?.str()` change (`Mapper.kt:29-33`) removes a real `IllegalArgumentException`
  escape from `decodeSongPage` and the new test proves both halves (artwork survives, unreadable
  duration is drift).
* **`DownloadQueue`'s snapshot CAS.** `mutate` with `next === cur` short-circuit, `withPending`'s
  insertion sort, `claimNext`'s "not owned by a worker" guard, `preemptable`'s
  `minWithOrNull(compareBy({-rank},{-seq}))` (newest-first among equals = least invested
  sacrificed) — all correct. `capacity` is only enforced when nothing is preemptable, which is
  the documented intent.
* **`TransferGate`.** Bounded on *transfers*, not jobs; the KDoc's correction of the two earlier
  "one in flight and one queued" claims is accurate (`WORKER_COUNT` is the other bound, and the
  `gate` KDoc says so at `:191-202`).
* **The Media3 platform fixes in the wave.** `applyAvailableCommands` posting to the media
  looper, `onTaskRemoved` not calling `super`, and the `BitmapLoader` migration instead of
  `replaceMediaItem` are all correctly reasoned, and the `replaceMediaItem` argument
  (`MediaItem.equals` compares `mediaMetadata` → a spurious `REASON_CHANGED` transition →
  `TrackChanged` for an unchanged track → phase forced back to `Playing` over a user pause) is
  exactly the class of bug the ledger's U-rows describe. The `ArtworkBitmapLoader` *future*
  lifecycle is the one gap (H5).
* **The gates.** `ktlintCheck` and `detekt` are clean; `lane-check.sh` is clean and its rewrite
  from a line-numbered allowlist to content fingerprints is a real improvement (the two
  self-checks — uniqueness and non-emptiness — are the right properties, and rule 2
  (`commonMain` must not call the thread-scoped `assert`) catches a genuine iOS-only crash class
  that nothing else would).
* **`AppConfig`'s derived `cacheMaxBytes`** and the `pinnedMaxFraction` row+byte budgets: the
  arithmetic in the KDoc checks out (`300 × 1 MB = 300 MB`; `0.75 × 300 MB = 225 MB`, so ~38
  6 MB renditions trip the pinned byte budget well before 225 rows — the claim is accurate).

---

# Fix ordering — highest value first

1. **C1 — `DownloadEngine` worker pool.** Make the cancelled handle a per-attempt child `Job`, not
   the worker's own, and add a liveness test (two preemptions, then a third `USER_NOW` that must
   run). *Unblocks:* every other download-layer finding, because a bricked pool makes all of them
   unreproducible.
2. **C2 + H1 — `AppContainer` graph construction and the deferred open.** `SYNCHRONIZED` on the
   four lazies, and build them only behind `opened.await()`. *Unblocks:* the main-thread SQLite
   open, the double-build leak, and makes the Android boot path match its own KDoc. One-line fix,
   disproportionate payoff. — **C2 and H1's mechanism done**; the caller-side gate for H1 is not.
3. **H2 — `opened` completion on the lost-race path.** Complete `opened` in an
   `invokeOnCompletion`, and bound every `await()` with `withTimeoutOrNull`. *Unblocks:* any
   reliable teardown test, and the `stop()`/`start()` contract the KDoc advertises. — **done**
   (without the timeout; see the finding).
4. **H4 — `1.sqm` totality.** Clamp the four copied columns and fix the header's "no filter"
   sentence. Add the `bitrate = 0 / ext = ''` v0 case to `CacheMigrationTest`. *Unblocks:* the
   migration gate; without it, any store with one bad row is a brick.
5. **H5 + H3 — the two Android false-invariant comments.** `invokeOnCompletion` →
   `out.cancel()` in `ArtworkBitmapLoader`; atomics for `resumeFuture`/`resumeGeneration`. Both
   are ~5 lines and both comments are currently *stopping the next reader from looking*.
6. **M1 (+ T4) — `fs.delete(path, false)` and the "already gone" branch** in `Reconciler.checkOne`
   and `CacheManager.unlinkAll`. *Unblocks:* the phantom-row class, and gives the weekly sweep a
   terminal state.
7. **M2 — remap the resumption index** (ideally by extracting and reusing
   `dylan.playback.decodeSnapshot`). *Unblocks:* correctness of Media3 resumption, and removes a
   second JSON decoder.
8. **H6 — fix or delete the concurrency invariant comments.** Either reword
   `Orchestrator.kt:52-64` to the real contract *and* state that `guard` swallows a
   `QueueInvariantViolation`, or make `guard` rethrow it. Also strike `docs/codebase-audit.md` §1.1
   and the six stale rows. *Unblocks:* the next reviewer; this is the comment class that caused
   the audit to be wrong in the first place. — **done** (delete the unenforceable half, enforce the
   swallow); the `codebase-audit.md` §1.1 rows are still outstanding.
9. **M7 + M8 + M6 — the three small resource fixes.** Pass `data.settings` into `Reconciler`;
   read `SettingsStore`'s cache under its lock (or make it a `ConcurrentHashMap`); make
   `FileLogSink.close()` take `writeMutex`. — **M6 done.**
10. **Performance batch, all mechanical:** `scanQualityUpgrades` → `data.upgradeCandidates` (kills
    the 301-round-trip N+1 that `Repos.upgradeCandidates` already exists to fix);
    `WindowPreparer.cachedRows` → one batched query; `SearchScreen`'s merged list →
    `rankMerged` (kills 2n `normTitle` calls on the main thread *and* makes T5's coverage real);
    `remember` the remaining `AppRoot` lambdas.
11. **Test-quality batch:** turn `theOptOutIsNotAnAccidentOfConstruction` into a call-site audit;
    add the ENOENT sibling to `aSizeMismatchWhoseUnlinkFails…`; replace
    `aRestoreDisarmsTheResolveItSupersedes`'s 2 s negative sleep with a positive observable;
    tighten or drop `LogSinkPerfTest`'s 100 µs ceiling; annotate
    `aCancelledLeaderStillReleasesTheKey…` with its 250 ms sensitivity.
12. **Small correctness/robustness:** `M9` (order the detach before the release), `M3`
    (`stampOnly` null guard), `M11` (log the dropped gate token), `L1` `AppRoot.decodeScreen`'s
    `substring(1)` before the dispatch, `L2` `onToast`/`toast` as atomics, `L4` the duplicated
    `RESTART_PREVIOUS_MS`, `L5` de-duplicate the `autoplayReady` KDoc.

**Do not touch, and here is why:** the `DylanMediaService` Media3-thread workarounds (all three
are correct and precisely explained); the `lane-check.sh` fingerprint rewrite; the
`FakePlayerEngine.stopPolling` change (it moved the oracle to match the real engine's shape, which
is the right direction, and `pausingStopsThePollSoAResumeCannotDoubleIt` is the test that
demonstrates it).
