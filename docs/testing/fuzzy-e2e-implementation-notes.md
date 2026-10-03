# Fuzzy / system test strategy — M0, M1, M2 implementation notes

**Author:** the agent that owned `shared/src/jvmTest/kotlin/dylan/fuzz/**` for M0–M2.
**Plan:** [`fuzzy-e2e-plan.md`](./fuzzy-e2e-plan.md) (1 677 lines; §1–§13).
**Status:** M0 ✅, M1 ✅, M2 ✅. M3+ not started — see [Handover](#handover).
**Never committed.** The user commits. No `git add` / `commit` / `checkout` / `stash` / `restore` was
run at any point in this work; mutations were applied in an rsync'd scratch copy of the tree (see
`tools/fuzz-mutation-gate.sh`), so no file another agent owns was ever modified in place.

---

## 1. What landed

```
shared/src/jvmTest/kotlin/dylan/fuzz/
  M0SeamsTest.kt        6 tests   the two seams, proven against the real DownloadEngine
  M1InvariantsTest.kt  14 tests   the oracle over a healthy store (+1 @Ignore'd canary)
  M2MigrationTest.kt   10 tests   v0 → current migration, and finding F-1
  DbOracle.kt                     43 post-conditions, the catalogue
  PostCondition.kt                CheckContext, FsSnapshot, the four condition shapes
  EnvironmentLedger.kt            (existing) + `suspendedBy()` so a skip names its break
  HealthyStore.kt                 the reference store, derived in one place
  FaultyFileSystem.kt             (existing) + `failAllWrites` / `disarmWrites`, KDoc corrected
  OriginScript.kt                 (existing) + two bugs fixed, knobs moved off system properties
  Sql.kt                          (existing) + real transactions, public NO_ROW
  V0StoreBuilder.kt               the pre-split DDL, transcribed
tools/fuzz-mutation-gate.sh        12 mutations; a non-reddening mutation fails the gate
```

`:shared:jvmTest --tests "dylan.fuzz.*"` → **30 tests, 0 failures, 1 skipped** (the F-2 canary).

---

## 2. M0 — go / no-go: **both seams work, and neither needed a production change**

### Seam 1 — a hostile origin that fails *mid-stream*: **works**

`ktor`'s `MockEngine` `respond` overload takes a `ByteReadChannel`, so a body can be produced through
a `RawSource` over a `Channel<ByteArray>` that the feeder closes cleanly, with an `IOException`, or
not at all. That is what makes FI-06/07/08/15 expressible. **No `HttpClientEngine` wrapper was
needed**, so the plan's §5.2 hedge is resolved.

Evidence: `M0SeamsTest.aMidStreamResetKeepsTheBytesThatLandedAndTheResumeAsksForThem` — a reset after
1 080 bytes under a 12 000-byte `Content-Length`, `dlRetries = 1`; the job completes, exactly two
requests are made, request #2 carries `Range: bytes=1080-` and `Accept-Encoding: identity`, and the
committed row says 12 000 bytes. The control is that assertion on the request log: if the channel had
never failed, request #2 would be `Range: bytes=0-`.

`aTruncatedBodyIsResumableRatherThanCommitted` is the other half — the *same* two request indices with
a clean early close, and `bytes=500-`. The two are the distinction the scenario catalogue exists to
make (`StreamErr.Truncated` vs `StreamErr.Network`), so they are kept as a pair.

### Seam 2 — an okio decorator that intercepts every write path: **works, with one honest limit**

`FileSystem.write` is `final` and delegates to the abstract `sink`, and okio ships
`ForwardingFileSystem`, so overriding `sink` is delegation rather than reimplementation and it *does*
reach `PartMeta.write`. Proven by `M0SeamsTest.theSidecarWriteGoesThroughTheDecoratorSoItCanBeFaulted`,
which arms the sidecar unconditionally, asserts the decorator's `sink` counter moved, and asserts the
sidecar is absent while the `.part` is untouched.

**The limit, which the plan got wrong.** `okio.FileHandle.write` is *also* `final`
(`javap`: `public final void write(long, byte[], int, int)` with `protectedWrite` abstract). The
download engine writes the audio body through `openReadWrite(...)` → `FileHandle.write(pos, …)`
(`Transfer.kt:501`, `:608`). So **no byte quota can be enforced on the audio body from a `FileSystem`
decorator** — the only reachable ENOSPC there is at the open. `aQuotaOnTheAudioDirectoryIsReportedAsStorage`
was therefore renamed to `aFaultAtThePartOpenIsReportedAsStorage` and now uses `failAllWrites(".part")`,
which is what the plan's own §12.2 cost table says ("a *mid-write* failure needs the quota-on-open trick
rather than a write override"). The assertion is unchanged and stronger: `STORAGE`, exactly one
request (a full disk is not retried), the open-for-write counter is non-zero, no row committed, and no
`.m4a` appears in the audio directory. Keeping the old name would have been a test that asserted
`STORAGE` for a reason that had nothing to do with the disk.

### Production seams: **both already exist; nothing was added**

`DownloadEngine` already carries the two defaulted parameters §5.9 asks for —
`freeDisk: (String) -> Long = ::freeDiskBytes` and `rename: (String, String) -> Unit = ::fsRename`
(`DownloadEngine.kt:177`, `:183`). Nothing in `commonMain` was touched by this work. `Sql` was
promoted from `CacheMigrationTest`'s private helpers (the previous agent's `fuzz/Sql.kt`), but
`CacheMigrationTest` was **not** repointed at it — see [Known duplication](#known-duplication).

---

## 3. The five previously-failing tests: root cause and current state

All five are **green**. Four of the five were fixture bugs; one was a harness bug that had been
masked by another harness bug.

| Test | Root cause | Fix |
|---|---|---|
| `aQuotaOnTheAudioDirectoryIsReportedAsStorage` | **Two, independent.** (a) `OriginScript.SliceSource.readAtMostTo` called `sink.write(bytes, off, n)` — `kotlinx.io.Buffer.write(ByteArray, startIndex, endIndex)`'s third argument is an **end index**, not a length, so any body larger than one 8 KiB channel segment threw `IllegalArgumentException: startIndex (8192) > endIndex (3808)` from inside the fixture. (b) The byte quota could never have fired anyway: `quotaBytes` is only consulted in `openReadWrite`/`sink`, and the *body* goes through `FileHandle.write`. | `off + n`; and the injection moved to `failAllWrites(".part")` with the test renamed to say what it injects |
| `theSidecarWriteGoesThroughTheDecoratorSoItCanBeFaulted` | **Fixture contradicted production.** `cfg = AppConfig(dlRetries = 0)`, and `Transfer.retryOrGiveUp` gives up when `attempts + 1 > dlRetries` — so with a zero budget a reset is terminal *by construction*, and "the job must complete" could never hold. | `dlRetries = 1`, `dlBackoffBaseMs = 5`; plus the test now drives a job that *keeps* its part, because `PartMeta.write` is only reachable from `PartStore.persist`, which `runJob` calls from its `finally` |
| `aMidStreamResetKeepsTheBytesThatLandedAndTheResumeAsksForThem` | Same `dlRetries = 0` contradiction. Also masked: the `Range` step built its slice with `copyOfRange(askedOffset, to + 1)`, which throws whenever the request's offset disagrees with the script's — a self-contradictory *fixture* reported as a `NETWORK` app failure. That is the `startIndex > endIndex` shape in the original report. | `dlRetries = 1`; `Range` now clamps its slice and declares the script's `from` separately, so a deliberately wrong `Content-Range` (FI-12) is expressible |
| `aTruncatedBodyIsResumableRatherThanCommitted` | **Harness bug.** `FeedSource.readAtMostTo` turned *every* throw from `Channel.receive` into an exception, so a **clean close** — which `receive` also reports as a throw — became a transport failure instead of EOF. It surfaced as `ClosedByteChannelException` → `NETWORK`. This is precisely the truncation-equals-ambiguity the `StreamErr` taxonomy exists to remove, reproduced inside the harness. | `Feed.Eof` sentinel: a clean close is `-1`, a close *with a cause* is that cause as an `IOException`, and silence past the ceiling is a failure. Same `dlRetries` fix as above |
| `aDeleteThatFailsIsInjectedWithoutBreakingTheReconciler` | **Fixture did not describe anything the reconciler acts on.** `insertCached` stamps `verified_at_ms` via `trg_library_asset_ins`, so the row was never "unstamped"; and the seeded file *matched* its row, so `checkOne` stat'ed it, saw a match and returned. Nothing was ever deleted. | The row now claims **more bytes than the file holds** (a truncated file — the shape `checkOne` exists to detect) and its `verified_at_ms` is cleared so the *boot* sweep re-examines it. The test also now asserts the **retry half**: a second sweep with the fault lifted drops both |

**Nothing was weakened.** Every assertion is either unchanged or strengthened; the two that changed
shape changed because the *subject* changed (see the rows above), and both are argued in the KDoc at
the assertion.

### Two further production facts M0 turned up, which the plan does not state

1. **The repair cadence is the weekly sweep, not the boot.** `fullSweepDue` writes
   `reconcile_full_ms` on the way past, so two `Reconciler.run()` calls in a row are not two sweeps,
   and the boot path only re-stats rows with `verified_at_ms IS NULL`. `M1InvariantsTest` therefore
   asserts *both* halves — "not due ⇒ still broken **and still reported**" and "past the interval ⇒
   repaired" — rather than assuming the second run repairs.
2. **FI-23's stated shape is unreachable.** `PartMeta.write` is called from `PartStore.persist`, which
   `DownloadEngine.runJob` invokes from its `finally` — *after* COMMIT has renamed the part away. So a
   sidecar write can only ever happen on a job that **kept** its part, and it can never affect whether a
   transfer commits. "The sidecar write fails, the transfer still commits" is not a state the current
   production code can be in. The test asserts what *is* reachable; see [Handover] for the implication
   for mutation **G-05**.

---

## 4. M1 — the oracle

**43 post-conditions land green on a healthy store** (42 from the plan's §6 catalogue plus one extra
schema-identity check). Which plan ids are in, and which are not, and why:

**Landed:** INV-01, 02, 03 (+`03i` index-set equality, `03t` trigger set, `03v` the `cached_files`
view), 04, 05, 06 (+`06n` the rejection), 07, 08 (= the plan's *08a*), 10, 11, 12 (+`12i`), 13, 14, 15,
16 (= 16a), 18, 19a, 19b, 20, 21, 22, 25, 26, 27, 28, 32, 34, 37, 38, 39 (+`39l`), 40a–d, 41.

**Not in the oracle, and deliberately:** INV-08b, 17, 23, 24, 29, 30, 31, 33, 35a/b, 36, 42 (and the
queue-tier 43/44). Every one of them is a statement about a **transition** — a live `DownloadEngine`, an
eviction, a `Reconciler.run()` *between* two reads, or a resume across the wire. An oracle invoked
inside a transition could only ever see one side of it, and putting them here would mean an oracle that
had to be told when to lie. They are M3/M4 scenarios; the ids are listed here so the omission is a
decision on the record rather than a gap.

Three design decisions that carry the weight:

* **Scope, not skipping.** A condition the ledger suspends is not evaluated *and is reported* in
  `oracle.lastSkipped` with the break that caused it. "Held", "not asked" and "held because the test
  broke the world" are therefore three distinguishable answers, and
  `M1InvariantsTest.outOfBandDamageIsDetected…` asserts all three.
* **Procedural conditions are conditions.** Fifteen of the post-conditions are about rows *and* files
  *and* sidecars at once. They are first-class catalogue members, run at the same points, skipped under
  the same rules — so there is no way to assert only the SQL half of a two-part property.
* **Controls travel with the check.** `PlanCondition` carries a query that *must* sort; if it does not,
  the guard reports "the control did not sort either, so 'no sort' proves nothing" and fails.
  `RejectionCondition` reads a probe before and after to prove the rollback landed, so a typo in a
  column name cannot masquerade as a CHECK firing.

### The plan's SQL, corrected where it could not have worked

* **INV-03** as written (`SELECT name FROM sqlite_master WHERE name NOT IN (…)`) can never be green:
  `sqlite_master` also holds every index and trigger. Restricted to `type IN ('table','view')`.
* **INV-26** as written ("every `.part` with bytes has a decodable sidecar") contradicts INV-27, which
  the plan itself says grades a sidecar-less part against the grace window. Restated as the pair that
  *is* an invariant: every `.part` a `download_intents` row names must have a decodable sidecar,
  because the reconciler re-enqueues that part on the next boot and `loadFromDisk` reads sidecars and
  nothing else.
* **INV-22** needed the `.part` grace-window exemption for the same reason, or it would grade a
  mid-flight transfer as untracked in the same report that grades it as legal.
* **INV-01** compares against `SCHEMA_VERSION`, a literal in `DbOracle.kt`, **not** `Dylan.Schema.version`
  — the factory writes that number, so comparing against it is "the code equals itself". A version bump
  without a suite update is therefore a red. `SCHEMA_VERSION_MIGRATED_FROM` sits beside it so the two
  move together.

### The known-red canary

`partTotalsArePublishedDuringASessionAndNotOnlyAtBoot` (INV-08b, finding **F-2**) ships `@Ignore`d with
the finding inline, per the plan. It is *compilable and runnable*, so un-ignoring it is a one-word edit
rather than a repair job, and adding a `setPartTotals` call in `PartStore.persist` turns it green with
no other change — which is what makes it a canary rather than a comment.

---

## 5. M2 — migration, and finding F-1: **F-1 is REAL**

### The verdict

`M2MigrationTest.aKillDuringTheMigrationLeavesAStoreThatCannotBeReopened` walks **every** kill point
*k* = 0…33 through the **real** `Dylan.Schema.migrate`, the **real** JDBC driver and a **real** file, and
measures the result:

```
k = 0                              → next open SUCCEEDS (the migration completed or never started)
k = 1 .. 33                        → next open THROWS, and keeps throwing, forever
```

and for every wedged *k*:

* `user_version` is still 0, because `DriverFactory` sets it *after* `migrate` returns and `migrate`
  never returned;
* the next open therefore takes the `from < target` branch and re-runs the chain from statement 1,
  `CREATE TABLE library`, which now exists;
* the file is **not** wiped — not even with `allowWipeOnCorruption = true`, because
  `PRAGMA integrity_check` answers "ok": this is a *healthy* file at a half-applied schema. The user's
  favourites, history and cache are intact and permanently unreachable.

The statement count is **33**, measured by `CountingDriver` rather than quoted — and there is no
`BEGIN TRANSACTION` / `COMMIT` anywhere in `DylanImpl.kt` (checked in the generated file). Both of the
plan's claims in §1.4 are confirmed.

**How the death is produced, and why it is faithful.** `DyingDriver` delegates to the real driver and
throws a plain `RuntimeException` on execute *k+1*. Each statement is its own autocommit transaction,
so the *k* before it are committed; the throw is not a `SQLException`, because a real process death is
not an error the driver can report or `DriverFactory` can classify — it is the code simply stopping.
Anything the driver or factory would translate it into would make the simulation softer than the thing
it stands for.

**This is a characterisation test.** It asserts the *defect*, so that wrapping `applyMigrations` in a
transaction turns it red and says "this finding is stale" instead of leaving a suite that has quietly
stopped describing reality. `aRolledBackMigrationLeavesAStoreThatReopensCleanly` is the other half: the
same real chain inside `Sql.rolledBack`, for every *k* = 1…33, each followed by a successful open.
**The fix is executed and green today.**

The fix itself is one line in `DriverFactory.applyMigrations` — `Dylan(driver).transaction { … }` around
`migrate` + `setUserVersion` — and it is a **production change on the boot path**, so it is the owner's
call (plan §13 Q1). Nothing in this work touched it.

### Where the plan is stale

* **§7.9's S-MIG-04 prediction is wrong.** The plan says `1.sqm:49-53` copies `bytes`, `bitrate`, `ext`
  and `play_count` "verbatim" and that S-MIG-04 "is expected to be RED on the current tree". The file in
  the tree already clamps all four (`MAX(1, bitrate)`, `CASE WHEN ext = '' THEN 'm4a'`, `MAX(0, bytes)`,
  `MAX(0, play_count)` — `1.sqm:71-79`), and `CacheMigrationTest.aV0StoreWithCheckViolatingRowsStillMigratesAndRepairsTheValues`
  already covers it. Finding **H4** is fixed; `v0HostileRowsAreClampedRatherThanAbortingTheMigration`
  is a green regression guard, not a canary. The discrepancy is recorded in the test rather than quietly
  dropped, because "the plan says red" and "the suite says green" is a disagreement somebody has to
  resolve.
* **§7.9's S-MIG-04 recommended fix is therefore already in.** Same for plan §13 Q3.
* **§6 INV-35's correction is correct** and is in the handover, not the oracle: "`protected_keys` never
  contains a key with no row" is false by design, because a prefetched key legitimately has neither a
  `library` nor a `media_objects` row.

### M2 scenarios landed

S-MIG-01 (fresh store, FK on, every object and index present), S-MIG-02 (field-for-field), S-MIG-03
(orphan row, with the index set as the "the whole chain ran" evidence), S-MIG-04 (hostile rows clamped),
S-MIG-05 (a `.part` + sidecar with no row survives the migration and is swept only when stale),
S-MIG-06 (**F-1**, above), S-MIG-07 (v1 migrates; a newer stamp is refused and not wiped, even with the
opt-in), S-MIG-08 (unknown reason normalised, and INV-32 holds after a migration, not only on a fresh
store), S-MIG-09 (idempotent across five re-opens, with the index set as the evidence that nothing was
rebuilt).

---

## 6. Falsifiability — the mutation gate

`tools/fuzz-mutation-gate.sh` applies 12 single-line mutations, each pinned to the test that must go
red, and **fails if any of them does not**. It rsyncs the sources into a private scratch directory and
mutates *there*: every mutation targets a file another agent owns, and mutating the shared tree would
put a broken build in front of everyone for the length of the run.

Run it with `FUZZ_MUT_KEEP=1 tools/fuzz-mutation-gate.sh` to keep the scratch copy.

See [`mutation-results.md`](./mutation-results.md) for the executed before/after table.

---

## 7. Determinism

* **No virtual time anywhere in this suite.** Every scenario that has an engine uses
  `TestLanes.production()` (§9.2: virtual time is for tests whose subject is a *timer*), and the M1/M2
  scenarios have **no running engine at all** — `HealthyStore.newEngine()` builds a `DownloadEngine` and
  deliberately does **not** call `start()`, because a started engine would let `Reconciler.resumeIntents`
  begin a real transfer for the fixture's intent row while the scenario inspects the result.
  `startWorkers()` is the explicit opt-in M3 needs.
* **No `delay()`-based waiting.** Every wait is an event predicate with a ceiling
  (`states.first { … isTerminal }`), and every grace window is exercised by ageing an mtime against
  `MutableClock` — the technique `ReconcilerClockTest` already uses.
* **No system properties.** `OriginScript`'s `chunkBytes` / `chunkDelayMs` were read from
  `dylan.fuzz.*` system properties, which meant a scenario's behaviour could be changed from outside the
  process running it. They are constructor parameters now.
* **Seeded / enumerated.** No `Random` is used at all in M0–M2: every dimension is an explicit loop
  (`k = 0..33`, the whole catalogue, five re-opens), which is stronger than a seed for a suite this
  size.
* **`Sql.expectRejected` does not use statement-level `BEGIN`/`ROLLBACK`.** SQLDelight's JDBC driver
  uses a `ThreadedConnectionManager` for a file URL (verified: `JdbcSqliteDriverKt.connectionManager`
  picks it for anything that is not `:memory:`), so a `BEGIN` and a `ROLLBACK` are two independent
  hand-outs of the connection and do not have to be the same one. That fails as `cannot rollback — no
  transaction is active`, and in the worst case commits a write the caller believed it had discarded.
  It is the driver's own transaction object, via `Sql.inTransaction`.

---

## 8. Known duplication

`V0StoreBuilder` transcribes the same pre-split DDL that `CacheMigrationTest.seedV0()` holds. That is
duplication, and it is deliberate: `CacheMigrationTest.kt` is owned by a change in flight, so it was not
edited. **When that owner is idle, `seedV0()` should become a call into `V0StoreBuilder` so there is one
transcription.** The construction is the same in both (create at the current schema, then *downgrade*),
and the reason is stated in both files: tables `1.sqm` does not touch are byte-identical to their v0
definitions, so building the store from scratch would let a change to an untouched table silently make a
migration scenario pass.

---

## 9. Handover — M3 and beyond

### M3 — pipeline fuzz (highest value next)

* **The G-05 gap is M3's to close.** Mutation G-05 (`PartStore.persist`'s `if (!fs.exists(part)) return`)
  currently has **no load-bearing witness** in this suite, because the sidecar write is only reachable
  after a job that *kept* its part (§3, finding 2). `S-DL-08` — a completed download leaves no sidecar —
  is the witness, and it needs a *successful* job against a real origin.
* `S-DL-01`…`S-DL-12` as written in §7.1. The pieces are in place: `OriginScript` supports
  `Ok / Status / Range / Reset / Truncate / Stall / WrongContentLength` and records every request's
  `Range` / `If-Range` / `Accept-Encoding` for protocol assertions.
* **Known limitation of `OriginStep.Stall`, stated so M4 does not rediscover it.**
  `RawSource.readAtMostTo` is not a `suspend` function, so the receive is bridged with `runBlocking`,
  which does **not** observe the outer coroutine's cancellation. A stall ends the copy when the watchdog
  cancels the copy coroutine, but the blocked `readAtMostTo` only unwinds when the receive itself returns
  or `OriginScript.receiveCeilingMs` fires. **A stall scenario must set `receiveCeilingMs` comfortably
  above `cfg.stallTimeoutMs`**, or the test measures that constant instead of the watchdog. Making the
  read cancellable (a `runBlocking` that polls an `AtomicBoolean` set by the watchdog) is the real fix.
* `nextStep()` coverage guidance (§5.2) is **not** implemented; `OriginScript.buckets` already counts the
  `(hasRange, hasIfRange, status)` protocol buckets, so S-DL-10 only needs the selection policy.

### M4 — cache, storage, clock

* `FaultyFileSystem` is complete for the FS catalogue (ENOSPC at the open, `undeletable`, `unreadable`,
  armed writes, per-op counters). Two additions worth making: `truncateOutOfBand` / `corruptOutOfBand`
  (FI-29/FI-30) are not implemented.
* Mutations **G-03** (`clearProtectedKeys`) and **G-04** (`claimedBytes +=`) need an eviction scenario.
* F-2's verdict is already recorded by the canary; the decision on publish frequency is plan §13 Q2.

### M5 — graph/session fuzz

* The `*Race*` / `*Concurrent*` / `*Interleav*` lexical rule against `TestLanes.virtual` (plan §9.2) is
  **not** in `tools/lane-check.sh`. It is cheap and mechanical and should land with M5, whose scenarios
  are the ones it protects.
* `FuzzHarness.quiesce()` is **not** built. `HealthyStore` deliberately has no engine running, so there
  is nothing to quiesce yet; M5 builds both together.

### M6 — the mutation gate and the nightly jobs

* `tools/fuzz-mutation-gate.sh` exists and works. `tools/mutation-gate.sh` (the plan's name) does not;
  the gate is 6 production + 6 harness mutations, not the plan's 12 — the other 6 (G-05, G-07, G-08, G-09,
  and the two `CacheManager` ones) have no witness yet and are listed per-milestone above.
* The plan's §11 M6 exit criterion — "a deliberately *harmless* mutation (a comment change) is correctly
  reported as **not** reddening, otherwise the gate is a 'fails on everything' detector" — is
  **deliberately not** implemented as a gate failure, because under the current design that would make
  the gate permanently red. The honesty check is the *baseline* run at the end of the script: if the
  unmutated suite is not green, the gate is measuring nothing. A no-op mutation is the natural next
  addition, and it belongs in the nightly job where a deliberate red is affordable.
* `Repro.kt` / `DYLAN_FUZZ_SEED` / the repro JSON are not started. Nothing landed in M0–M2 needs them:
  every dimension is enumerated, not sampled.

### M7 — Android

Not started, and **not started is the right answer** until someone owns the emulator image. A pinned
job nobody debugs is a job that is permanently skipped.

### Things needing another owner (not edited here)

| # | What | Where | Why not here |
|---|---|---|---|
| 1 | Wrap `applyMigrations` in a transaction | `DriverFactory.applyMigrations` (all three actuals) | production boot path; plan §13 Q1. **The fix is executed and green in `M2MigrationTest.aRolledBackMigrationLeavesAStoreThatReopensCleanly`.** |
| 2 | Publish `part_bytes` from `PartStore` | `PartStore.persist` | needs a decision on io-lane write frequency; plan §13 Q2. Un-ignores `M1InvariantsTest`'s canary. |
| 3 | `PlayerState.newShuffleOrder` uses `Random.Default` | `model/Models.kt` | plan §13 Q5 — my recommendation is still *don't*; assert relations. |
| 4 | `CacheMigrationTest.seedV0()` → `V0StoreBuilder` | `CacheMigrationTest.kt` | owned by a change in flight; §8 above. |

### CI

Nothing wired yet, on purpose. The whole suite is **6 s** of `:shared:jvmTest` and is already inside
the existing `test/jvm` job (`:103`), so it is running on every PR at zero added cost. A separate
`test/jvm-fuzz` job is only worth adding once M3/M5 land and the suite stops being free; at that point
`--tests 'dylan.fuzz.*'` + `--max-workers=4` is the whole change. The mutation gate belongs on the
**nightly** lane (≈12 × a Gradle invocation, ~10 min measured) and must not share a job with anything
else, because it intentionally produces failures.

---

## 10. Plan references that were wrong, in one place

| Plan claim | Reality |
|---|---|
| §6 INV-03 SQL | Cannot be green on a healthy store; `sqlite_master` also lists indexes and triggers |
| §6 INV-26 | Contradicts the plan's own INV-27 grace window; restated as "intent-named parts are resumable" |
| §6 INV-35 ("never a key with no row") | Already corrected by the plan itself — kept out of the oracle for the stated reason |
| §7.5 S-STG-02 / §8 FI-22 "ENOSPC on the body" | Not reachable from a `FileSystem` decorator: `okio.FileHandle.write` is `final`. Only the *open* is reachable. |
| §7.5 FI-23 "the sidecar write fails, the transfer still commits" | Unreachable: the sidecar is written from `runJob`'s `finally`, after COMMIT renamed the part away |
| §7.9 S-MIG-04 "expected to be RED on the current tree" | Green. `1.sqm:71-79` already clamps all four values; `CacheMigrationTest` already covers it (finding H4) |
| §7.9 S-MIG-05 "fresh ⇒ survives; aged ⇒ swept" | Also needs the *sweep interval* to elapse between two `run()` calls; `fullSweepDue` stamps its key on the first pass |
| §12.2 "a mid-write failure needs the quota-on-open trick" | Correct, and this is why the quota test was renamed rather than "fixed" |
| §11 M6 "a harmless mutation is correctly reported as not reddening" | Under the current design that makes the gate permanently red; the baseline run is the substitute |