# W4 status — verified by hand, not taken on agent report

Date: 2026-10-01. Branch `refactor/structure-and-performance`.
Baseline commit for this wave: `0cb7f11`.

## Done and independently verified

### 1. Cache eviction loop (`0cb7f11`, committed)
`CacheManager.claimLruVictims` re-read `cache_totals` every iteration, but the totals only
move when a row is *dropped*, which happens in `finishReap` — after the unlink, once
`claimLruVictims` has already returned. A row claimed this pass (READY -> EVICTING) was
therefore still counted by the next iteration, `need` never shrank, and the loop claimed
until `lruVictims` ran dry: the whole eligible pool was destroyed instead of the excess.

Fixed in the loop's own accounting. The SQL was never wrong — the totals faithfully describe
a state one step behind the loop. No test or `.sq` file was modified.
309/309 green across 3 forced `--rerun-tasks` runs.

### 2. Cache budget derivation (agent work, verified)
`cacheMaxBytes` was independent 2 GB, enforced as `min(cacheMaxBytes, cacheMaxFiles x 1 MB)`.
At 300 files that `min` could only pick the derived term, so `cacheMaxBytes` was dead config
that *looked* live — both platform UIs print and divide by it. Now derived as
`cacheMaxFiles * CACHE_MEAN_TRACK_BYTES`. Enforcement value unchanged at 300 MB.

**The audit's stated consequence was wrong and I am recording the correction:**
`docs/codebase-audit.md` says "300 x 320 kbps tracks is ~255 MB ... the entire pin-demotion
path is dead". It divides by 8 twice. 320 kbps = 40 kB/s; the repo's own `Clients.kt` calls a
6-minute 320 kbps track ~14.5 MB, so 300 of them is ~4.35 GB — the *byte* cap binds first, not
the file cap. Pin demotion is live: `pinnedByteBudget` trips at ~38 six-megabyte renditions,
well inside the 225-row budget, and `CacheManagerTest.pinnedDemotionRespectsThePinnedCapAndDemotesOldestPinFirst`
proves the path executes.

### 3. `readyTimeoutMs` leak (Orchestrator `awaitDownload`, fixed by me)
`downloads.cancelAttempt` existed with **zero production callers** — only one test. So when
`withTimeoutOrNull(cfg.readyTimeoutMs)` fired, the transfer kept streaming to its own ceiling
`max(stallWallFloorMs, expectedBytes/8)`: up to ~30 min for a 14.5 MB track, on cellular, after
the user was told the download failed. The retry made it worse, not better: `onPrepareFailed`
re-enqueues the same key at the same USER_NOW rank, which the live attempt out-ranks, so
`JobQueue.offer` returns `SupersededByHigherPriority` and the incumbent simply continues.

Now cancelled on timeout (`cancelAttempt(id, keepPart = true)` on the id path so a newer attempt
for the same key is untouched; `cancel(key, …)` on the key-keyed fallback). `keepPart` so the
retry resumes from the `.part`.

Verified: `OrchestratorEdgeTest` 17/17, `OrchestratorRecoveryTest` 8/8, `PlaybackLogicTest`,
`CacheManagerTest` 17/17, `CacheMigrationTest` 8/8 — 50 tests, 0 failures.

## Open

### Rate contract tests: 2 failures in the engine-seam agent's files
`FakeEngineContractTest.aRateOutsideTheSupportedRangeIsClampedToIt` (expected 6000, got 6200)
and `.setRateChangesHowFastTheMediaClockMoves` (expected 6000, got 6150).

**Not a rate bug.** The expected arithmetic is correct: 2450 ms of wall time = 24 whole
100 ms ticks, so 2400 ms of ground at 1.0x, then 24 more ticks at 1.5 x 150 ms = 3600, total
6000. The fake produces 25 ticks where 24 are due.

Mechanism: `startPolling` loops `delay(pollIntervalMs)`, and the harness's
`advanceTimeBy(2450)` is followed by `runCurrent()` — which also releases the tick scheduled at
exactly t=2450. `OFF_TICK_QUARTER_TRACK_MS` is documented as chosen precisely so the tick count
is stable "under either reading", but the inclusive `runCurrent()` defeats that intent. The fix
belongs in the harness or the constant, not in `setRate`. Left to the owning agent rather than
edited from under it.

## Known flakes (pre-existing, unrelated to W4)
- `DownloadEngineTest.networkErrorMidTransferIsNotReportedAsStorage`
- `SaavnProviderTest.theSnapshotLruSurvivesConcurrentWritersThatTheLinkedHashMapDoesNot`

Both ~1 in 3 full runs. Real concurrency bugs, seeded into the review wave.

## Deferred to the review wave
- Versioned `.sqm` migration before prod/beta (destructive wipe is dev-only)
- `check_pbxproj.py` not in CI; missing iOS icon set; no splash gate
- CI lexical lane check
- Constant tuning with device measurements (needs a real device on `adb`)