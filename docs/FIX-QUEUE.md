# Fix queue — findings from `docs/STAFF-AUDIT.md`

Every item below was **re-verified against the code** before being added (the audit itself was read
sceptically, and two of its findings were re-checked and one path corrected). Ranked by severity.

Do not re-litigate these; fix them.

---

## F-01 · S1 · CERTAIN — a documented fix is dead code, and the live path still has the bug

`shared/src/commonMain/kotlin/dylan/repo/Repos.kt:217` declares
`suspend fun Dylan.upgradeCandidates(...)`. Its KDoc records the N+1 query it exists to eliminate.

Verified: `grep -rn "upgradeCandidates" shared/src androidApp/src` returns **exactly one hit — that
declaration**. It is never called.

The only production consumer of that logic is
`shared/src/commonMain/kotlin/dylan/di/AppContainer.kt:594-621`, which still does:

```kotlin
.mapNotNull { row ->
    data.db.dylanQueries
        .selectSong(row.provider, row.song_id)   // one query per row
        .executeAsOneOrNull()
    …
}
```

serially on the db lane, every 30 minutes, against the whole cached catalogue. The correct
set-based SQL already exists at `shared/src/commonMain/sqldelight/dylan.sq:361-366` — written, but
never wired up.

**Fix:** wire the existing query through `upgradeCandidates` and call it from `AppContainer`,
deleting the per-row loop. Preserve the `take(MAX_UPGRADE_CANDIDATES)` cap and the
in-flight-key filter.

---

## F-04 · S2 · CERTAIN — `recordingKey()` destroys every non-Latin title

`androidApp/src/main/kotlin/dylan/android/ui/components/RecordingKey.kt:10-19`:

```kotlin
.replace(Regex("[^a-z0-9 ]"), "")
```

`[^a-z0-9 ]` is ASCII-only, and it runs *after* `lowercase()`. A Devanagari, Arabic, CJK or accented
title **and** artist both normalise to `""`, so the key degenerates to `"|" + durationS`. Two
unrelated songs of equal duration then collide.

`List<Song>.containsRecording` (`:26`) is used by favourites; a collision makes HomeScreen filter a
genuinely-favourited track out of *Your favourites*. This app's catalogue is substantially Hindi
(`ZARA SA`, `AASHIQUI 2`, `SHRI RAM JAANKI`), so this is live.

**Fix:** keep non-ASCII letters. The intent is a normalising fold, not an ASCII filter. Prefer
`lowercase()` plus a Unicode-aware category test (or drop characters that are neither letters,
digits nor `Letter_Number`/marks) and strip whitespace runs. **The key must still collide for the
case it exists for** — the same recording under different song ids, with Latin-script titles — so
existing behaviour for Latin script must not change.

---

## F-05 · S3 · CERTAIN — `recordingKey()` compiles four `Regex` objects per call

Same file, lines 10-19: `Regex("[^a-z0-9 ]")` and `Regex("\\s+")` are constructed **inline**, twice
each (title and artist). Every other `Regex` in production is a hoisted `private val` (verified by
grep across `LogBuffer`, `CachePath`, `Identity`, `Breakpoint`).

`HomeScreen`'s favourites check calls it F×(J+1) times inside one `remember`, ~8 440 compiles at
100×20 songs.

**Fix:** hoist to file-private `val`s. Note this is a regex-*allocation* count, not just a
compile-once issue — `Regex(...)` compiles every call.

---

## F-03 · S2 · CERTAIN — a blocking `stat` still runs on the single-permit state lane

`shared/src/commonMain/kotlin/dylan/download/WindowPreparer.kt:114` calls `fs.metadataOrNull(...)`
with no lane hop. The file's own KDoc states the intended layout — open/read moved off-lane at
`:91`, but the `stat` did not. Called from `Orchestrator.kt:766`, `:1041`, `:817`, i.e. 2–4 times per
track change, all on `disp.state`.

**Fix:** move the metadata call behind the same io hop the sibling syscalls already use, matching
what the file's KDoc already claims it does.

---

## F-10 · S2 · CERTAIN — `Reconciler.fullSweep` re-queries rows it already holds, and can unlink a healthy file

`shared/src/commonMain/kotlin/dylan/cache/Reconciler.kt:123` reads `rows` — which **already carries
`bytes`**. `:143` discards it and `currentBytes` (`:211-223`) re-reads each row in its own dbLane
hop. At the 2 415-row cap that is 1 + N round trips plus N filesystem stats.

Worse: the guard `takeIf { it.bitrate == bitrate && it.ext == ext }` means a song with **two
renditions** can have a healthy file unlinked, because the sibling's row was read first.

**Fix:** derive byte totals from the row set already in hand. For the rendition hazard, make the
unlink decision consider *all* rows for a song, not the first that matches the filter.

---

## F-02 · S2 · CERTAIN — the guard test on the mid-reset byte-loss seam is flaky

`shared/src/jvmTest/kotlin/dylan/fuzz/probe/ReadAfterAwaitContentContractTest.kt:119` throws
`io.ktor.utils.io.ClosedReadChannelException`. Measured: **1 failure per 4-15 isolated runs**.

Root cause confirmed against upstream ktor 3.5.2: `readAvailable` does
`if (isClosedForRead) return -1` then reads `readBuffer` (whose getter throws on a recorded cause).
The MockEngine's `cancel(cause)` lands between the two. The test's `Outcome` enum has no case for a
throw.

The production shape at `shared/src/commonMain/kotlin/dylan/download/Transfer.kt:649-656`
(`readChunk`) is **identical**, and `pump` catches only `StallSignal` — so in production the
exception escapes to `runJob`'s generic catch and is classified `NETWORK`.

**Fix:** represent "throw" as a first-class `Outcome` rather than an exception escaping the probe.
Make the test deterministic rather than probabilistically green. Then decide, deliberately, whether
`Transfer.readChunk` should classify that throw as a retryable error instead of letting it fall
through to the generic path — and document the choice.

---

## F-22 · S3 · LIKELY — `Orchestrator` is too large, but the published refusal was too broad

`docs/issues.md` #24 argues every cluster reads 8+ private members, so any split forces
`private`→`internal`. The audit found **two clusters where that argument does not apply**:

- **`ResumeStore`** — `saveSnapshot`, `startTicking`, `snapshotFingerprintOf`, `resumeCandidateMs`,
  `freshPosition`, `snapshotJob`, `snapshotFingerprint`, `lastPosMs` (~90 lines, 6 of 82 functions).
  Nothing outside reads those fields.
- **`SessionLogger`** — `onTrackStarted`, `recordHistory`, `bumpPlayCount`, `watchSession`,
  `sessionJob`.

**Fix:** extract these two only, behind the existing snapshot tests. Do **not** attempt
`WindowPreparer`/`WindowOwner` — that is where the `private`→`internal` argument genuinely applies
and where a careless split risks playback semantics.

---

## F-07 · S3 · CERTAIN — a comment gets its own arithmetic wrong by 3.2×

`shared/src/commonMain/kotlin/dylan/search/SaavnSearchChannel.kt:553-554`:

```kotlin
/** 2^6 × the 30 s base is 32 min, well inside the 10 min cap. */
const val MAX_COOLDOWN_DOUBLINGS = 6
```

30 s × 64 = 1 920 s = 32 min. The cap is 600 s = 10 min. The code is right (a `coerceAtMost`
clamps); the comment describes the opposite, so the constant reads as a no-op.

**Fix:** correct the comment to state that the clamp engages after ~5 doublings, or make the real
limit explicit.

---

## F-09 · S3 · CERTAIN — two claims of safety the code does not deliver

1. `shared/src/commonMain/kotlin/dylan/search/SaavnSearchChannel.kt:96-97` claims *"All of the
   engine's mutable state is behind `engineLock`"*. Seven fields are outside it (`timeoutStrikes`,
   `socketStrikes`, `divergence`, `cooldown*`, `backoffMs`, `consecutiveDegradations`), and
   `stateForTest` (`:528`) reads `outstanding.size` unlocked under a KDoc promising a torn-view-free
   read.
2. `androidApp/src/main/kotlin/dylan/android/media/DylanMediaService.kt:220` launches its state
   collector **lane-less on the platform scope**. Correct today only because `appScope`'s dispatcher
   happens to be `disp.state` — and `tools/lane-check.sh:49,113` roots every scan in `commonMain`,
   so it is structurally blind to `androidApp`.

**Fix:** either bring the claimed invariant into line with the code (put the fields behind the lock)
or correct the comment. For the service, name the lane explicitly and extend the lane-check scan to
cover `androidApp`.

---

## F-23 · S1 · device-reported, root cause NOT yet established — search rows render dimmed, as if unplayable

Reported on hardware (vivo V2130, Android 14, `v1.1.1`, debug build) against the query `eminem`.
Some rows in the merged result list render visibly dimmer than their neighbours and read as disabled.

**Verified in code, and it rules out the obvious explanation.** A dimmed row is *not* an album or
artist row: `MiniRow` (`androidApp/.../components/SongRow.kt:264-274`) and `SongRow`
(`SongRow.kt:124-130`) both colour their title with `t.textPrimary`, so the two row kinds are
*identical* in title colour. Nothing passes `greyed = true` to `MiniRow`, so
`GREYED_ROW_ALPHA` (`:337`) is never applied. The dimming is `SongRow.kt:71`,
`.alpha(if (enabled) 1f else DISABLED_ROW_ALPHA)` with `DISABLED_ROW_ALPHA = 0.45f` (`:334`).

So a dimmed row is a **song** for which `SearchScreen.kt:432` computed
`can = canPlay(isOnline, cachedKeys, song.key)` as false, and `Common.kt:137-141` defines that as
`isOnline || key in cachedKeys`. The rows in question are not in the library, so on the face of it
`isOnline` was false for them.

**That is the contradiction, and it is unresolved.** Uncached neighbours in the same list render at
full alpha, and the query itself was served over the network. Either `isOnline` is being read
inconsistently within one composition, or something narrower is going on. **Not yet root-caused.**

**Fix:** establish which of the two operands of `canPlay` is false before changing anything.
Note the reporter's proposed remedy — "if you can't play them, don't return that result" — would be
wrong if the dimming turns out to be a false `isOnline`, because it would delete legitimate results
to hide a state bug. Fix the state, not the result set.

---

## F-24 · S2 · CERTAIN — infinite scroll cannot arm until the query is submitted

Also reported on hardware: while typing, the list does not page. Pressing Enter (the IME submit)
starts infinite scroll immediately.

**Confirmed in code.** The scroll listener is `LaunchedEffect(listState, submitted)`
(`SearchScreen.kt:324`), and `loadMore()` pages `results.extend(container, submitted)` — the
**submitted** query, not the live typed one (`:310-317`). `submitted` only moves in the submit
lambda (`:125-131`, `:158-175`). The typeahead feed is a separate path (`demand` at `:96`,
`rememberSuggestions` at `:100`).

So while the user types, the visible rows and the paged query are *different queries*. Paging
cannot be armed by typing, by construction. This is a design seam, not a stray guard.

**Fix:** decide which behaviour is wanted and make the two paths agree — either the visible
typeahead rows page under the typed query, or the list makes clear that only a submitted query
pages. Silently paging a query the user is not looking at is worse than not paging.

---

## F-25 · S2 · device-reported, NOT reproduced or root-caused — after paging, only Albums come back

Reported on hardware, immediately after F-24's Enter-to-page: further pages surface album rows
only; songs and artists stop appearing.

**Not investigated yet.** Recorded so it is not lost. One observation that may or may not be
related, offered as a **lead and not a conclusion**: the device log for the same session shows the
search websocket mispairing and being struck while the user typed —

```
W Dylan:search: ws mispair on 'eminem' — the frame answered a different demand
W Dylan:search: ws socket strike=1/3 fifo mispair
```

— and no `ws ok` line for the final `eminem`. Whatever the per-section paging does with a socket
that has been struck under it is unexamined. Do not treat that as the cause without evidence.

**Fix:** reproduce against `ResultState.extend` / `canExtend` and the per-section exhaustion
budgets, with a test that asserts all three sections still extend. A section silently going
exhausted would produce exactly this symptom.

---

## Explicitly out of scope

The audit cleared 13 items that look like defects but are not. See `docs/STAFF-AUDIT.md` §5. Two
worth restating because they look like the bugs fixed this session and must **not** be reverted:

- `SurfaceScrim`'s gated `pointerInput` and the `graphicsLayer`-before-`background` ordering in
  `NpPlayerSurface.kt` are **correct**. Do not reorder.
- `ExoPlayerEngine.release()` is already called from `DylanMediaService.kt:565`.
- `IosGraph.kt:370-375`'s `Dispatchers.IO` comment is **verified true** against kotlinx.coroutines
  1.11.0's Native `Dispatchers.kt` — `codebase-audit.md` §3.6 Phase 0 #10 is itself wrong; do not
  act on it.
