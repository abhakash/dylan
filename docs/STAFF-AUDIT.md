# Staff audit — `refactor/structure-and-performance`

**Date:** 2026-10-08 · **HEAD:** `607477a` · **Tree:** ~70 files modified, nothing pushed
**Method:** primary-source reading of `shared/**` and `androidApp/**`; `git status --porcelain` as the
source of truth; `./gradlew :shared:detekt :androidApp:detekt` and `./gradlew :shared:jvmTest
--rerun` executed locally (JDK 17, `openjdk@17`); ktor 3.5.2 / kotlinx.coroutines 1.11.0 behaviour
confirmed against upstream source, not from memory.

> **Environment note (not a code finding).** `./gradlew` fails with "Unable to locate a Java Runtime"
> under this machine's default `/usr/bin/java`; the JBR in IntelliJ makes `detekt` fail with
> `Invalid value (25) passed to --jvm-target`. I used Homebrew `openjdk@17`
> (`/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`). Anyone reproducing the numbers
> below needs a JDK 17 on `JAVA_HOME`.

---

## 0. Gate results, measured

| Gate | Command | Result |
|---|---|---|
| detekt | `./gradlew :shared:detekt :androidApp:detekt` | **red, 2 findings** — `LargeClass` + `TooManyFunctions(82)` on `Orchestrator.kt:87`. No third finding anywhere. |
| jvmTest | `./gradlew :shared:jvmTest --rerun` | **491 tests, 1 skipped, 0 failures** in a clean run — but **see F-02**: one test is flaky at roughly 1 failure per 4–15 runs. |

This settles `docs/issues.md` #22's open question ("*either detekt is red, or something outside
this repo silences those two*"): detekt is red, and red with exactly the two accepted findings. The
count is **2, not 0 and not 313**. `TooManyFunctions` reports **82** functions, not the 83 the row
quotes — the arithmetic drift the row already predicted has happened again.

---

## 1. Summary table

Severity: **S1** ship-blocker / user-visible wrongness · **S2** real defect, bounded blast radius ·
**S3** correctness-adjacent, readability or documentation defect · **S4** hygiene.

| ID | Area | Severity | Confidence | One line |
|---|---|---|---|---|
| F-01 | data / perf | S1 | **CERTAIN** | `upgradeCandidates` is dead code; the live quality scan still runs the N+1 it was written to delete |
| F-02 | tests / download | S1 | **CERTAIN** | The guard test on the mid-reset byte-loss seam is flaky — `readAvailable` **throws**, and the test's model has no case for that |
| F-03 | perf / UI | S2 | **CERTAIN** | `recordingKey()` compiles 4 `Regex` objects per call and is invoked O(n·m) times |
| F-04 | correctness / UI | S2 | **CERTAIN** | `recordingKey()` strips all non-ASCII, so two unrelated non-Latin songs with equal durations collapse to one identity |
| F-05 | concurrency | S2 | **CERTAIN** | A blocking `stat` still runs on the single-permit state lane, two calls deep in the hottest path in playback |
| F-06 | dead code | S3 | **CERTAIN** | `AppConfig.posHzFull` / `posHzMini` have exactly one reference each: their own declaration |
| F-07 | comments | S3 | **CERTAIN** | `MAX_COOLDOWN_DOUBLINGS`'s KDoc asserts 32 min is "well inside the 10 min cap" |
| F-08 | comments | S3 | **CERTAIN** | `FlowAdapter.subscribe`'s KDoc is duplicated verbatim, two identical paragraphs |
| F-09 | concurrency / comments | S3 | **CERTAIN** | `SaavnSearchChannel`'s class KDoc claims all mutable state is behind `engineLock`; seven fields are not |
| F-10 | perf / cache | S2 | **CERTAIN** | `Reconciler.fullSweep` re-queries every row it already holds in memory, plus one `stat` per row |
| F-11 | diagnostics | S4 | **CERTAIN** | `verifyUnstampedObjects` returns the row count it *read*, not the count it *verified*, and logs it as the latter |
| F-12 | dead code | S3 | **CERTAIN** | `ExoPlayerEngine.Companion.keyOf` has zero readers, and its KDoc describes an item-id format the code no longer produces |
| F-13 | architecture | S4 | **CERTAIN** | `PlaybackGraph.breakers` and `NetGraph.ws` are graph members with no readers |
| F-14 | architecture | S4 | **CERTAIN** | `Reconciler` still gets its own `SettingsStore`; the one-argument fix described in its KDoc was never applied |
| F-15 | download / comments | S3 | **CERTAIN** | `Transfer`'s mechanism KDoc states `readAvailable` turns a recorded cause into `-1`; it does so only when the cause predates the call |
| F-16 | concurrency / gates | S3 | **CERTAIN** | `DylanMediaService`'s state collector launches lane-less on the platform scope — and `tools/lane-check.sh` cannot see `androidApp` |
| F-17 | perf / UI | S3 | **CERTAIN** | `SeekBar` allocates a fresh `SliderColors`, two lambdas and two `String`s per 10 Hz tick |
| F-18 | perf / UI | S3 | **LIKELY** | `AppRoot`'s "remember the lambdas" fix is applied to two of five lambdas, so the screens still cannot skip |
| F-19 | correctness / UI | S3 | **LIKELY** | `rememberCachedKeys` never invalidates on eviction, so an offline gate can go stale |
| F-20 | concurrency | S3 | **LIKELY** | `DownloadEngine.scope` is an unsynchronised `var` written from `start()`/`stop()` |
| F-21 | comments | S4 | **CERTAIN** | `WindowPreparer.cachedRows`'s KDoc calls an N-statement loop "the N+1-free form" |
| F-22 | architecture | S3 | **LIKELY** | `Orchestrator` is genuinely too large, but for a different reason than `LargeClass` says — and there *is* a safe first split |
| F-23 | correctness | S2 | **SPECULATIVE** | `SongKey.itemId` embeds raw ids in a `:`-joined string; `queueKeyOf` splits on `:` |

---

## 2. Findings

### F-01 · `upgradeCandidates` is dead code, and the live quality scan still runs the N+1 it deleted

**S1 · CERTAIN · `shared/src/commonMain/kotlin/dylan/repo/Repos.kt:217`, `shared/src/commonMain/kotlin/dylan/di/AppContainer.kt:594-621`**

`Dylan.upgradeCandidates` is a purpose-built wrapper over a hand-written JOIN. Its own KDoc records
the defect it fixes:

> "The call site used to `selectAllCached()` and then `selectSong()` per row *before* `.take(n)`,
> so the limit bounded nothing and a 300-row cache cost 301 round trips through the single
> dbLane every 30 minutes, blocking playback DB work for the duration."
> — `Repos.kt:212-216`

The SQL is real and correct (`shared/src/commonMain/sqldelight/dylan/db/dylan.sq:361-366`):

```sql
selectUpgradeCandidates:
SELECT c.provider AS provider, c.song_id AS song_id
  FROM library c JOIN songs s ON s.provider = c.provider AND s.song_id = c.song_id
 WHERE c.bitrate = ? AND s.has_320 = 1 AND (c.pinned = 1 OR c.play_count >= 2)
 ORDER BY c.last_used_ms DESC, c.provider, c.song_id
 LIMIT ?;
```

**Evidence** — repo-wide grep for the wrapper's name:

```
$ grep -rn "upgradeCandidates" --include="*.kt" --include="*.sq" . | grep -v "/build/"
./shared/src/commonMain/kotlin/dylan/repo/Repos.kt:217:suspend fun Dylan.upgradeCandidates(
```

One hit: its own declaration. Zero callers, in production or test.

Meanwhile the only production caller — the 30-minute quality scan — does exactly what the KDoc says
was fixed (`AppContainer.kt:594-621`):

```kotlin
private suspend fun scanQualityUpgrades(): Int {
    val candidates =
        withContext(disp.on(Lane.DB)) {
            data.db.dylanQueries
                .selectAllCached()
                .executeAsList()
                .filter { it.bitrate == UPGRADE_FROM_BITRATE && (it.pinned == 1L || it.play_count >= 2) }
                .mapNotNull { row ->
                    data.db.dylanQueries.selectSong(row.provider, row.song_id).executeAsOneOrNull()
                    ...
```

One `selectAllCached()` (every cached row, all columns) plus one `selectSong()` per surviving row,
inside a single `dbLane` block. It is one *lane hop* but N *statements*, and they are serialised
against every download step's queries and every `settings.get()` on the same lane.

**Fix.** Replace the body of `scanQualityUpgrades` with
`data.db.upgradeCandidates(disp, UPGRADE_FROM_BITRATE, MAX_UPGRADE_CANDIDATES)` and delete the
three-line dedupe that follows it (`DownloadEngine` already no-ops a duplicate). `MAX_UPGRADE_
CANDIDATES` then bounds the row count in SQL rather than after the joins.

**Risk.** Low. The only behavioural difference is the `ORDER BY`: the current code orders by
`selectAllCached`'s order and takes the first 3; the new one orders by `last_used_ms DESC`. That is
strictly better (most-recently-used tracks upgraded first) but it *is* a behaviour change, so it
needs `MAX_UPGRADE_CANDIDATES` asserted over a seeded store rather than assumed.

---

### F-02 · The guard test for the mid-reset byte-loss seam is flaky, because `readAvailable` throws

**S1 · CERTAIN · `shared/src/jvmTest/kotlin/dylan/fuzz/probe/ReadAfterAwaitContentContractTest.kt:119`, `:245-288`, `:298-313`; `shared/src/jvmTest/kotlin/dylan/fuzz/probe/OneShotResetSource.kt:79`**

This is the only test standing between the tree and the "a mid-stream reset silently discards every
downloaded byte" defect that `docs/issues.md` #5 records as fixed. It is **not** a reliable gate.

**Measured.** 8 isolated runs of the class, then 20, then 15 more. Failure rate **1 in 8 → 1 in 4 →
1 in 15**. The full suite passed on the clean run I eventually got, so this is a flake, not a
deterministic break — which is worse than a deterministic break, because it trains readers to
re-run rather than to read.

```
ReadAfterAwaitContentContractTest[jvm] > a contract-satisfying reader is never told
  clean EOF through the real ktor stack[jvm] FAILED
    io.ktor.utils.io.ClosedReadChannelException at ReadAfterAwaitContentContractTest.kt:119
        Caused by: java.io.IOException at ReadAfterAwaitContentContractTest.kt:198
491 tests completed, 1 failed, 1 skipped
```

**Mechanism, confirmed against upstream ktor 3.5.2** (`ktor-io/common/src/io/ktor/utils/io/
ByteReadChannelOperations.kt`):

```kotlin
public suspend fun ByteReadChannel.readAvailable(
    buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset
): Int {
    if (isClosedForRead) return -1
    if (readBuffer.exhausted()) awaitContent()   // ← readBuffer getter THROWS here
    if (isClosedForRead) return -1
    return readBuffer.readAvailable(buffer, offset, length)
}
```

and (`ByteChannel.kt`):

```kotlin
override val readBuffer: Source
    get() {
        _closedCause.value?.throwOrNull(::ClosedReadChannelException)
        if (_readBuffer.exhausted()) moveFlushToReadBuffer()
        return _readBuffer
    }
```

The two `isClosedForRead` checks and the `readBuffer` access are **not atomic**. The MockEngine's
`defaultTransformers` writer coroutine calls `channel.cancel(OneShotResetSource.RESET)` on another
thread; if the cause lands between the first `isClosedForRead` check and the `readBuffer` getter,
the getter throws `ClosedReadChannelException` wrapping the reset. That window is tiny, which is why
the test fails roughly a quarter of the time rather than always.

**The bug is in the test's model, not only its timing.** `Outcome` (`:89-99`) has three cases —
`Bytes`, `Terminal`, `Eof` — and `readContractCompliant` (`:113-125`) assumes `readAvailable` returns
an `Int`. It does not always. So the `Observation` type the whole class is built around cannot
represent the outcome that actually occurs, and `assertContractHolds` (`:144-155`) is never reached.

**Production consequence.** `Transfer.readChunk` (`Transfer.kt:649-656`) has the identical shape:

```kotlin
if (!ch.awaitContent()) return -1
val n = ch.readAvailable(buf, 0, buf.size)
return if (n == 0) null else n
```

On the same interleaving it throws instead of returning. `pump` (`:525-550`) catches **only**
`StallSignal`, so the exception escapes `pump` → `drain` → `Transfer.run` → `runJob`'s
`catch (expected: Throwable)` (`DownloadEngine.kt:500-509`) and is published as
`ErrorCode.NETWORK` with no retry classification. The `.part` survives (it is resumable), so this is
not data loss — but it is a *worse* answer than `StreamErr.Network`, and it is the exact path this
test exists to protect.

**Fix.** Two changes, in this order.

1. Test first: add `data class Threw(val cause: String) : Outcome` and wrap
   `ch.readAvailable(...)` in `try/catch (t: Throwable)`. A throw where the contract demands `Bytes`
   or `Terminal` is a contract violation; assert that too, so the flake becomes a *specific*
   assertion rather than an exception.
2. Production: make `readChunk` classify it. `Transfer.readChunk` should return a `StreamErr` (or
   `null`-able result) distinguishing a recorded cause from EOF, and `copyChunks` should treat it as
   `StreamErr.Network` — the same retryable path `verdict()` already produces for a truncation.

**Risk.** Low for (1). Medium for (2): it changes the retry classification for a case that today
becomes `NETWORK` anyway, so the observable change is only that the `.part` resumes earlier and the
breaker is charged once rather than per crash. `DownloadEngineTerminalStateTest` and the two
`Transfer` channel guards should both stay green; run them.

---

### F-03 · `recordingKey()` compiles four `Regex` objects per call, and is invoked O(n·m) times

**S2 · CERTAIN (allocation is certain; the magnitude is a code-level argument, not a device measurement) · `androidApp/src/main/kotlin/dylan/android/ui/components/RecordingKey.kt:8-22`, `androidApp/src/main/kotlin/dylan/android/ui/screens/HomeScreen.kt:90`**

```kotlin
fun Song.recordingKey(): String {
    val title = title.lowercase().replace(Regex("[^a-z0-9 ]"), "").replace(Regex("\\s+"), " ").trim()
    val artist = (artistName ?: subtitle).lowercase().replace(Regex("[^a-z0-9 ]"), "").replace(Regex("\\s+"), " ").trim()
    return "$title|$artist|$durationS"
}
```

**Four `Regex` compilations** per `recordingKey()` call — two per field, inline. Every other `Regex`
in production is a top-level `private val`, which is the pattern this one should follow:

```
$ grep -rn "Regex(" --include="*.kt" androidApp/src shared/src/commonMain shared/src/androidMain
androidApp/.../RecordingKey.kt:12:  .replace(Regex("[^a-z0-9 ]"), "")
androidApp/.../RecordingKey.kt:13:  .replace(Regex("\\s+"), " ")
androidApp/.../RecordingKey.kt:18:  .replace(Regex("[^a-z0-9 ]"), "")
androidApp/.../RecordingKey.kt:19:  .replace(Regex("\\s+"), " ")
shared/.../LogBuffer.kt:132,137      private val ... RE = Regex(...)   ← hoisted
shared/.../CachePath.kt:35           private val SEGMENT_RE = Regex(...)
shared/.../Identity.kt:91,127        private val ... = Regex(...)
shared/.../Breakpoint.kt:326         private val CONTENT_RANGE = Regex(...)
```

The call shape makes this quadratic. `HomeScreen.kt:90`:

```kotlin
val favoritesShown = remember(favorites, jumpBack) { favorites.filter { f -> !jumpBack.containsRecording(f) } }
```

and `RecordingKey.kt:26`:

```kotlin
fun List<Song>.containsRecording(song: Song): Boolean = any { it.recordingKey() == song.recordingKey() }
```

For F favourites and J jump-back rows that is **F × (J + 1)** `recordingKey()` calls, i.e. 4·F·(J+1)
regex compilations plus two `lowercase()` allocations and four intermediate strings each. At 100 × 20
that is 8,440 `Regex` compiles inside one `remember`. `distinctRecordings()` (`RecordingKey.kt:24`,
used at `HomeScreen.kt:174,176`) is the same cost in the linear case.

**Fix.** Hoist the two patterns to `private val` consts in the file (trivial, no behaviour change),
then make `containsRecording` build the key set once:

```kotlin
private val NON_ALNUM = Regex("[^a-z0-9 ]")
private val WHITESPACE = Regex("\\s+")
private val WHITESPACE_RUN = Regex("\\s+")

fun List<Song>.containsRecording(song: Song) = asSequence().map { it.recordingKey() }.contains(song.recordingKey())
```

and better still, memoise `recordingKey` on `Song` — it is a pure function of four immutable fields.
A `song.recordingKey` computed at construction removes every call site's cost.

**Risk.** None for hoisting. Low for memoisation: the only way it goes wrong is if `Song` were
mutable, which it is not (`data class`). Prefer the set-based `containsRecording` over hoisting
alone — hoisting removes 75% of the allocations but leaves the O(n·m) string building.

---

### F-04 · `recordingKey()` strips every non-ASCII character, so non-Latin songs collide

**S2 · CERTAIN · `androidApp/src/main/kotlin/dylan/android/ui/components/RecordingKey.kt:12,18`**

`Regex("[^a-z0-9 ]")` deletes everything outside ASCII lowercase letters, digits and space. For a
Devanagari, Arabic, Cyrillic or CJK title *and* artist, both normalised fields are `""`, so the key
degenerates to `"|" + durationS`. Two unrelated Hindi songs of the same duration have the **same
`recordingKey`**.

`containsRecording` then reports a match, and `HomeScreen.kt:90` filters the favourite out of *Your
favourites* entirely; `distinctRecordings()` (`RecordingKey.kt:24`) drops one of them from Jump back
in. The file's own comment states the intent — "identical MD5 across ids, so lists dedupe on this
instead of `SongKey` alone" — which is right for Latin titles and wrong for everything else.

`docs/codebase-audit.md` §3.6 recorded this as a live defect on 2026-09-27 and the tree has not
moved.

**Fix.** Widen the keep-set rather than deleting: keep every character that is a letter or digit,
which is what "same recording" means in any script.

```kotlin
private val NON_WORD = Regex("[^\\p{L}\\p{N} ]")
```

`java.util.regex` and `kotlin.text.Regex` both support `\p{L}`/`\p{N}` on the JVM and on Kotlin/Native
(`MatchResult`/`Regex` in `kotlin.text` is implemented over `java.util.regex` on JVM and a custom
engine on Native — both support Unicode categories). **Verify on the iOS target**, since this is the
platform I could not compile here.

**Risk.** Medium. Widening the key changes identity for *every* non-ASCII title, so previously
colliding rows separate and previously-distinct rows might now match if two renditions of the same
track differ only in punctuation. Add a test asserting a Devanagari pair with equal durations does
**not** collapse, and a Latin pair with differing punctuation does.

---

### F-05 · A blocking `stat` still runs on the single-permit state lane

**S2 · CERTAIN · `shared/src/commonMain/kotlin/dylan/playback/WindowPreparer.kt:110-118`, called from `Orchestrator.kt:766`, `:1041`, `:817`**

`WindowPreparer.sniffOk` correctly moves the expensive part off the state lane:

```kotlin
val ok = withContext(disp.on(Lane.IO)) { sniffContainer(fs, path) != null }   // WindowPreparer.kt:91
```

but `stampOf` — called *before* that, on every `sniffOk`, memoised or not — runs in `sniffOk`'s own
context, which is the caller's:

```kotlin
private fun stampOf(path: Path, expectedBytes: Long): Stamp? {
    val meta = runCatching { fs.metadataOrNull(path) }.getOrNull() ?: return null   // ← :114, blocking stat
    ...
}
```

`sniffOk` is a `suspend fun` with no lane wrapper. Its three production callers all run on
`Lane.STATE`: `awaitPlayable` (`Orchestrator.kt:765-767`, reached from `ensureReadyAndPlay` which
is launched via `scope.launch(disp.on(Lane.STATE))`), `trackFor` (`:1041`, reached from
`prepareWindow`/`refreshUpNext`), and `playableAfterDownload` (`:817`).

So on the lane that owns the entire playback inbox, every `sniffOk` performs one `stat` — two to
four per track change, plus one per queue edit. The class's own KDoc describes the intended layout:

> "Blocking `stat`/`open`/`read`/`close` — four syscalls per call, 2-4 calls per track change — so
> it runs on the io lane, never on the single state lane." — `WindowPreparer.kt:66-67`

The comment describes the *intent* correctly and the code delivers three of the four. The `stat` was
left behind. `docs/codebase-audit.md` DI-3 measured this on the old code; the read moved, the stat
did not.

**Fix.** Hoist the whole of `stampOf` onto io, or better, hoist all of `sniffOk`'s pre-work:

```kotlin
suspend fun sniffOk(row: Cached_files): Boolean {
    if (row.bytes <= 0L) { verified.remove(key); return false }
    val stamp = withContext(disp.on(Lane.IO)) { stampOf(paths.final(key, row.bitrate.toInt(), row.ext), row.bytes) }
    ...
}
```

**Risk.** Low. One extra lane hop per `sniffOk` call, which is exactly the trade the file already
made for the read. The `verified` map stays on the state lane (it is only touched inside `sniffOk`,
which is state-lane), so no new sharing. `ReconcilerTest` and `OrchestratorEdgeTest` pin the
memoisation behaviour; both should stay green.

---

### F-06 · `posHzFull` and `posHzMini` are dead config

**S3 · CERTAIN · `shared/src/commonMain/kotlin/dylan/config/AppConfig.kt:175-176`**

```kotlin
val posHzFull: Int = 10,
val posHzMini: Int = 4,
```

Repo-wide grep:

```
$ grep -rn "posHzFull" --include="*.kt" . | grep -v "/build/"
./shared/src/commonMain/kotlin/dylan/config/AppConfig.kt:175:    val posHzFull: Int = 10,
$ grep -rn "posHzMini" --include="*.kt" . | grep -v "/build/"
./shared/src/commonMain/kotlin/dylan/config/AppConfig.kt:176:    val posHzMini: Int = 4,
```

One hit each: the declaration. `docs/codebase-audit.md` §3.6 listed these under "Move to shared …
(already in `AppConfig`, referenced nowhere)" on 2026-09-27. Confirmed still dead 11 days later.

The live rates are hardcoded elsewhere: `ExoPlayerEngine.POSITION_POLL_INTERVAL_MS = 100L`
(`ExoPlayerEngine.kt:299`) is the real 10 Hz, and `IosPlayerEngine` has its own. These two knobs
therefore *look* like the way to change the ticker and are not.

**Fix.** Either wire them (`PositionThrottle` at the engine seam, or at minimum use them as the
assertion in `EngineContractTest`) or delete them. Deleting is safer than wiring, because wiring
changes device behaviour in a pass that has no device.

**Risk.** None either way. Deleting them removes the false affordance.

---

### F-07 · `MAX_COOLDOWN_DOUBLINGS`'s comment gets its own arithmetic wrong by 3.2×

**S3 · CERTAIN · `shared/src/commonMain/kotlin/dylan/search/SaavnSearchChannel.kt:553-554`**

```kotlin
/** 2^6 × the 30 s base is 32 min, well inside the 10 min cap. */
const val MAX_COOLDOWN_DOUBLINGS = 6
```

`repeat(6) { widened *= 2 }` starting from `cfg.wsCooldownBaseMs = 30_000` gives
30 s × 64 = **1 920 s = 32 minutes**. `cfg.wsCooldownCapMs = 600_000` = **10 minutes**. 32 minutes
is 3.2× *outside* the cap, not "well inside" it. The code is right (`coerceAtMost` clamps) and the
comment is wrong.

The consequence is small but real: `degrade` computes `widened`, clamps it, and the clamp fires for
every degradation from the second onward, so `MAX_COOLDOWN_DOUBLINGS = 6` is doing nothing. If the
intent was "the doubling ladder reaches the cap in a bounded number of steps", the right value is
**5** (30 s × 32 = 16 min, still clamped) — or simply `1 + log2(cap/base) = 1 + 4.32 → 5` doublings
then the clamp.

**Fix.** Either correct the comment ("2^6 × 30 s = 32 min; the 10 min cap clamps it, so the ladder
is fully consumed after the second doubling") or reduce the constant to 5 and say so.

**Risk.** None for the comment. Changing the constant to 5 shortens the ladder by one step, which
is unobservable because the cap already binds — but that is an argument for changing the comment
rather than the constant.

---

### F-08 · `FlowAdapter.subscribe`'s KDoc is duplicated verbatim

**S3 · CERTAIN · `shared/src/commonMain/kotlin/dylan/bridge/FlowAdapter.kt:73-95`**

Lines 73-84 and lines 85-95 are the same paragraph, byte for byte, including both blanks and the
sentence "Every flow wired to this adapter is a `StateFlow`." It reads as a half-completed edit where
the first copy should have been replaced by the `### What is routed and what is rethrown` section
that follows at `:97`.

**Fix.** Delete one copy. Keep the one that still reads correctly — the surviving text is accurate.

**Risk.** None. This is 13 lines of pure noise in the file iOS maintainers read first.

---

### F-09 · `SaavnSearchChannel`'s class KDoc makes a safety claim the code does not deliver

**S3 · CERTAIN (the claim is false) / LIKELY (the resulting race) · `shared/src/commonMain/kotlin/dylan/search/SaavnSearchChannel.kt:96-97`**

> "All of the engine's mutable state is behind [engineLock], and the socket behind [socketOwner],
> so nothing here needs lane affinity any more and no `onBackground()` can race a running read."

**What is actually behind `engineLock`:** `outstanding` — at `:232` (`removeAll`), `:314`
(`addLast`), `:379` (`removeFirstOrNull`), `:467` (`removeAll`), `:448` (`clear`), and `:540`
(`stampsForTest`).

**What is not:**

| Field | Declared | Written | Read |
|---|---|---|---|
| `timeoutStrikes` | `:132` | `:463`, `:478`, `:505` | `:496`, `:518`, `:547` |
| `socketStrikes` | `:133` | `:473`, `:479`, `:506` | `:496`, `:519`, `:547` |
| `divergence` | `:134` | `:381`, `:449` | `:516`, `:547` |
| `consecutiveDegradations` | `:140` | `:500`, `:483` | `:500` |
| `backoffMs` | `:141` | `:417`, `:482`, `:506` | `:421` |
| `cooldownUntilMs` / `cooldownNetClass` / `cooldownMs` | `:137-139` | `:481-484`, `:501-503` | `:457-459`, `:547`, `:550` |

And `stateForTest()` (`:524-534`) — whose KDoc claims "A consistent read of the engine's mutable
state, so a test never asserts a torn view" — reads `outstanding.size` at `:528` **without** the
lock, which is the one thing in the class whose read genuinely can tear.

**Is this a live race?** Partly. `engine()` is one coroutine, so the strike/cooldown counters are
single-coroutine in production. But `dropSocket()` is launched from `onBackground()` (`:178`), from
`publish()` on a blank query (`:220`), and from `tryWs`'s catch clauses — all `scope.launch(disp.on
(Lane.IO))` on a multi-threaded `Dispatchers.IO`. `dropSocket` takes `socketOwner` for `session`
and then `engineLock` for `outstanding`/`divergence`, so the two fields it *does* guard are fine.
The counters are reached only from the engine coroutine, so today they are safe *by construction, not
by the lock the comment names*. A second entry point — which the KDoc explicitly invites by saying
"nothing here needs lane affinity any more" — would break them silently.

There is also a real, if bounded, delay: `session()` holds `socketOwner` across `connectBlock()`
(up to `wsHandshakeTimeoutMs = 5 s`) **and** `delay(backoffMs)` (up to 16 s) and up to two attempts.
`dropSocket()` from `onBackground()` therefore blocks for up to ~26 s before it can close the socket
it was asked to close. It suspends rather than deadlocks, so this is a latency finding, not a hang.

**Fix.** Narrow the KDoc to what is true: "`outstanding` is behind `engineLock`; `session` is behind
`socketOwner`; the strike and cooldown counters are confined to the `engine()` coroutine and must
stay that way." If the counters ever need a second entry point, put them behind `engineLock` first.
Take `stampsForTest`'s lock for `stateForTest`'s `outstanding.size` read.

**Risk.** None for the comment. Low for the lock — `engineLock` is held for non-suspending
operations only, so widening it is safe.

---

### F-10 · `Reconciler.fullSweep` re-queries every row it already holds

**S2 · CERTAIN · `shared/src/commonMain/kotlin/dylan/cache/Reconciler.kt:122-151`, `:166-189`, `:211-223`**

`fullSweep` reads every cached row at `:123`:

```kotlin
val rows = withContext(disp.on(Lane.DB)) { db.dylanQueries.selectAllCached().executeAsList() }
```

`rows` carries `bytes`. 20 lines later it discards that value and re-reads it per row:

```kotlin
known.forEach { (key, bits, ext) -> checkOne(key.provider, key.songId, bits, ext, 0, now, stampOnly = true) }
```

and `checkOne` (`:166-189`) with `stampOnly = true` re-derives it:

```kotlin
val recorded = if (stampOnly) currentBytes(provider, songId, bitrate, ext) else expectedBytes
```

`currentBytes` (`:211-223`) is a `selectCached(provider, song_id)` **plus** a
`takeIf { it.bitrate == bitrate && it.ext == ext }` guard, wrapped in its own
`withContext(disp.on(Lane.DB))`.

So the weekly sweep issues **1 + N** `dbLane` hops for a cache of N files — one `selectAllCached`,
then N `selectCached`s — on top of N `fs.metadataOrNull` stats. At the shipped 2 415-row cap that
is ~2 400 lane handoffs and ~2 400 stats, each of which contends with every download step's queries.
The class KDoc at `:19-23` claims the boot path was made cheap; it does not mention that the weekly
path is not.

`currentBytes`'s guard is also subtly wrong for its use here: `selectCached(provider, songId)`
returns *one* row, and if a song has renditions at two bitrates the row returned may be the other
one, in which case `takeIf` returns `null`, `recorded` is `null`, `size != null && size == recorded`
is false, and `checkOne` unlinks the file and drops the row — a healthy rendition destroyed because
a sibling rendition was read first.

**Fix.** `checkOne` already takes `expectedBytes: Long`. `fullSweep` holds it in `rows`. Pass
`it.bytes` and drop the `stampOnly` branch entirely; then delete `currentBytes`. That removes
`currentBytes`'s double-read hazard and the N extra lane hops in one edit.

**Risk.** Low and mechanical. `ReconcilerTest`'s weekly-sweep cases exercise this path; they should
stay green. Confirm no other caller relies on `stampOnly` — grep shows `checkOne` is called only at
`:112` and `:143`.

---

### F-11 · `verifyUnstampedObjects` reports the wrong number

**S4 · CERTAIN · `shared/src/commonMain/kotlin/dylan/cache/Reconciler.kt:109-114`, `:70-74`**

```kotlin
private suspend fun verifyUnstampedObjects(now: Long): Int {
    val rows = withContext(disp.on(Lane.DB)) { db.dylanQueries.unstampedObjects(REAP_BATCH.toLong()).executeAsList() }
    rows.forEach { row -> checkOne(row.provider, ...) }
    return rows.size
}
```

`rows.size` is the number of rows *read*, not the number verified — and `checkOne` may unlink and
drop some of them, or find them already correct. `run()` logs it as `unstamped=$unstamped` inside a
line whose other fields (`claimed`, `reaped`) are all counts of things that *happened*. One of five
numbers on the line means something different from the other four.

**Fix.** Return the count `checkOne` actually acted on, or rename the field to `read=`.

**Risk.** None. Diagnostic only.

---

### F-12 · `ExoPlayerEngine.Companion.keyOf` is dead, and its comment describes a format that no longer exists

**S3 · CERTAIN · `androidApp/src/main/kotlin/dylan/android/media/ExoPlayerEngine.kt:299-306`**

```kotlin
/** A media item id is `provider:trackId:<variant>`; anything else is not a [SongKey]. */
private const val SONG_KEY_SEGMENT_COUNT = 3

fun keyOf(itemId: String): SongKey? {
    val parts = itemId.split(":")
    return parts.takeIf { it.size == SONG_KEY_SEGMENT_COUNT }?.let { SongKey(it[0], it[1]) }
}
```

Repo-wide grep:

```
$ grep -rn "keyOf" --include="*.kt" . | grep -v "/build/"
./androidApp/.../media/ExoPlayerEngine.kt:303:    fun keyOf(itemId: String): SongKey? {
./androidApp/.../ui/screens/SearchScreen.kt:828:    keyOf: (T) -> K,        ← an unrelated *parameter*
./androidApp/.../ui/screens/SearchScreen.kt:831:    val seen = held.mapTo(HashSet<K>()) { keyOf(it) }
./androidApp/.../ui/screens/SearchScreen.kt:832:    val fresh = page.items.filter { seen.add(keyOf(it)) }
```

Zero readers. `docs/codebase-audit.md` §3.6 listed it under "Delete" on 2026-09-27. Still present.

Worse than dead: the documented format is stale by one generation-prefix. `SongKey.itemId`
(`shared/src/commonMain/kotlin/dylan/model/Models.kt:17-20`) is
`"$GEN_PREFIX$generation:$provider:$songId:$bits"` — **four** colon-separated segments. Had
`keyOf` been wired up after the generation stamp landed, it would have returned
`null` for every real id.

**Fix.** Delete the function and `SONG_KEY_SEGMENT_COUNT`.

**Risk.** None.

---

### F-13 · Two graph members have no readers

**S4 · CERTAIN · `shared/src/commonMain/kotlin/dylan/di/Graphs.kt:53`, `:73`**

```
$ grep -rnE "\b(net|graph|playback|ng)\.ws\b" --include="*.kt" . | grep -v "/build/"
(none)
$ grep -rnE "\.breakers\b" --include="*.kt" . | grep -v "/build/" | grep -v "Breakers()"
(none)
```

`NetGraph.ws` is written by `buildNet()` and read only by `NetGraph.close()` (`Graphs.kt:60`) — it
is an *owned handle for teardown*, not a handle a consumer is allowed. That contradicts the file's
own contract comment ("Each one is the *complete set of handles a consumer of that concern* is
allowed").

`PlaybackGraph.breakers` is written and never read at all. The live `Breakers` instance reaches
`Transfer` through `DownloadEngine`'s constructor, which is the correct path.

**Fix.** Keep `NetGraph.ws` (it is load-bearing for `close()`) but re-document it as a
teardown-owned handle. Delete `PlaybackGraph.breakers` — or keep it if the iOS graph needs it; grep
says it does not.

**Risk.** None.

---

### F-14 · `Reconciler` still gets its own `SettingsStore`

**S4 · CERTAIN · `shared/src/commonMain/kotlin/dylan/cache/Reconciler.kt:38-51`, `shared/src/commonMain/kotlin/dylan/di/AppContainer.kt:322`**

`Reconciler`'s constructor documents the defect and the one-line fix and then declines to make it:

> "The real fix is one argument at that call site (`Reconciler(…, data.settings)`) and it is NOT
> made here because `AppContainer.kt` is owned by another change in flight."
> — `Reconciler.kt:41-43`

`AppContainer.kt` is **in the working tree, modified, and uncommitted** — nobody owns it. The call
site still omits the argument (`AppContainer.kt:322`), so the KDoc's own analysis of why this is
currently harmless ("this instance touches exactly one key … so it can never be the one holding a
stale copy") is what stands between the tree and a second cache.

That analysis is still *correct today*, and I verified it: `FULL_SWEEP_KEY` is touched only at
`Reconciler.kt:273` and `:149`, both through `this.settings`. So this is not a live bug. It is a
landmine with a fuse already lit, in a tree whose stated failure mode is "stale, confidently-written
comments".

**Fix.** Pass `settings` at `AppContainer.kt:322` and delete the defaulted parameter plus the
45-line KDoc defending it.

**Risk.** Low. One constructor argument. `GraphHarness`, `ReconcilerTest` and `ReconcilerClockTest`
all construct `Reconciler` — check whether they rely on the default (they construct it directly, so
they pass their own `db`/`disp`; a new required parameter will surface there).

---

### F-15 · `Transfer`'s mechanism KDoc is incomplete, and the unclassified throw it hides costs a retry

**S3 · CERTAIN (the comment) / LIKELY (the cost) · `shared/src/commonMain/kotlin/dylan/download/Transfer.kt:693-697`, `:649-656`, `:539-549`**

> "`readAvailable` turns that into `-1` *before* consulting the buffer. `close()` also flushes
> before recording the cause … while `cancel()` does not — which is why a clean EOF delivers its
> bytes and a reset does not."

Both halves are true **only when the cause is already recorded at the first `isClosedForRead`
check**. When the `writer` coroutine's `cancel(cause)` lands between that check and the
`readBuffer` getter, `readAvailable` throws `ClosedReadChannelException` instead — see F-02 for the
upstream source and the measured failure rate. The comment states the *mechanism* as unconditional
where it is conditional.

The consequence is in `pump` (`:539-549`): the `try` catches `StallSignal` and nothing else, so
this exception escapes the whole copy path, past `verdict()`'s three-way classification, into
`runJob`'s generic `catch (expected: Throwable)` at `DownloadEngine.kt:500-509` and out as
`ErrorCode.NETWORK` with `willRetry = false`. The bytes that *were* written survive (the `.part` is
resumable and `live.load().partBytes` was folded per chunk), so no data is lost — but the attempt is
not retried as a transport failure, and the error code does not describe what happened.

**Fix.** Classify it in `readChunk` (see F-02) rather than widening `pump`'s catch: a recorded cause
on the body channel *is* a transport failure, and `StreamErr` already has a home for it.

**Risk.** Medium. This is the seam with the most documented history in the repo. Any change here
should be made together with F-02 and validated by the three existing channel guards.

---

### F-16 · `DylanMediaService`'s state collector launches lane-less, and `tools/lane-check.sh` cannot see `androidApp`

**S3 · CERTAIN (the gate gap) / LIKELY (the hazard) · `androidApp/src/main/kotlin/dylan/android/media/DylanMediaService.kt:220`; `tools/lane-check.sh:49`, `:113`**

```kotlin
stateJob =
    container.scope.launch {
        container.orchestrator.state.collect { state -> ... }
    }
```

No `disp.on(Lane.X)`. The behaviour is *currently* correct, because `DylanApp` builds the platform
scope with `disp.state` as its dispatcher (`MainActivity`/`DylanApp.kt:38-40`: `SupervisorJob() +
disp.state + CEH`). So the collector inherits the state lane by dispatcher. But it publishes no
`LaneTag`, so `assertInContext(Lane.STATE)` anywhere inside it would throw `LaneViolation` — and the
dependency on `appScope`'s dispatcher is invisible at the call site.

The gate cannot see it, by construction:

```
SRC="$ROOT/shared/src/commonMain/kotlin/dylan"      # tools/lane-check.sh:49
... grep -rn "scope\.launch {" . --include="*.kt"   # :113 — rooted at $SRC
```

Every pattern in `lane-check.sh` is anchored inside `shared/src/commonMain`. Nothing in
`androidApp/`, `shared/src/androidMain/`, `shared/src/iosMain/` or `iosApp/` is scanned. I verified
the gate passes on the tree (`lane-check: OK`), and that `grep -rn "scope.launch {"` over
`commonMain` returns exactly the 12 sites the script's `ALLOWED` list accounts for — so the gate is
*wrong* on nothing and *blind* to everything outside `commonMain`.

**Fix.** Two options, in order of value:

1. Add `androidApp/src/main/kotlin` to a second scan root in `lane-check.sh`, with the same
   fingerprint-based allowlist. `container.scope.launch {` would then need an entry or a lane.
2. Cheaper and local: launch on `container.componentScope("media-state", container.disp.state)` or
   `container.scope.launch(container.disp.on(Lane.STATE))`. The latter publishes the tag and keeps
   the collector under `containerJob` so `shutdown()` reaches it — today it is parented to the
   *platform* scope, which `shutdown()` cancels only via `containerJob` and which `stop()` does not
   touch.

**Risk.** Low for (2) — a lifetime change only, the same argument `AppContainer.buildPlayback`
(`:296-309`) already made for the orchestrator. Medium for (1): it may surface existing
`androidApp` launches that were never reviewed.

---

### F-17 · `SeekBar` reallocates its `SliderColors`, two lambdas and two `String`s on every 10 Hz tick

**S3 · CERTAIN · `androidApp/src/main/kotlin/dylan/android/ui/screens/NowPlayingSheet.kt:598`, `:604-654`**

```kotlin
val posMs by container.orchestrator.positionMs.collectAsStateWithLifecycle(0L)   // :598
...
Slider(
    value = shown.coerceIn(0f, durMs.toFloat()),                                 // :605
    ...
    colors = SliderDefaults.colors(                                             // :616-626 — new object per tick
        thumbColor = accent, activeTrackColor = accent, ...
    ),
    thumb = { Box(Modifier.size(if (dragging) 22.dp else 18.dp)...) },           // :627-635 — fresh lambda
    track = { sliderState -> ... },                                             // :636-652 — fresh lambda
    modifier = Modifier.fillMaxWidth().height(36.dp).padding(top = 8.dp),        // :653 — fresh Modifier
)
Text(formatTime(shown.toLong()), ...)                                           // :660 — new String per tick
Text(formatTime(durMs), ...)                                                    // :668 — new String per tick
```

The subtree *is* correctly isolated — the KDoc at `:582-585` is accurate about the 10 Hz invalidating
only this subtree and not the artwork, marquee or transport row. `MiniProgress`
(`NpPlayerSurface.kt:540-557`) is the model of the better pattern: it reads `pos.value` **inside**
the `graphicsLayer` block, so the position never recomposes anything at all.

`Slider` takes a plain `value: Float`, so the composable must recompose to move the thumb — that is
unavoidable for a Material3 `Slider`. What is avoidable is everything allocated *per tick* to do
it. `SliderDefaults.colors(...)` builds a new `SliderColors` instance (a non-stable holder of ten
`Color`s); the two lambdas defeat skippability; `formatTime` allocates via `"%d:%02d".format`.

**Fix.** Hoist three of the four:

```kotlin
val sliderColors = remember(dim, accent) { SliderDefaults.colors(thumbColor = accent, ...) }
val thumbSlot: @Composable () -> Unit = remember { { Box(...) } }
```

`formatTime(durMs)` can be `remember(durMs) { formatTime(durMs) }`. This is roughly 5 allocations
per tick down to 1.

**Risk.** None measurable. The `thumb` slot reads `dragging`, which is why it cannot simply be
`remember`ed as-is — either keep `dragging` out of the slot (drive the size from the state passed
in) or accept that one lambda. Do not "simplify" by removing the custom `track`/`thumb`; they
exist to get the 22/18 dp and 6/4 dp behaviour `SliderDefaults` does not express.

---

### F-18 · `AppRoot`'s "remember the lambdas" fix is applied to two of five lambdas

**S3 · LIKELY · `androidApp/src/main/kotlin/dylan/android/ui/AppRoot.kt:329-344`, `:374`, `:394`, `:397-398`**

The comment at `:329-331` names the exact defect and fixes it for two lambdas:

```kotlin
// Stable across recompositions: a fresh lambda instance here is a changed parameter for all
// six screens, so none of them could skip.
val playNow = remember(container, onFirstPlay) { { songs, idx -> ... } }
val openArtist = remember(backStack) { { m -> ... } }
```

Three fresh lambdas remain in the same body:

```kotlin
NavStackContent(..., onOpenSettings = { sheet = Sheet.Settings })          // :374
AppOverlays(
    ...
    onSheet = { sheet = it },                                             // :394
    onOpenArtist = { name, token ->                                        // :397-397
        if (token.isNotBlank()) backStack.add(Screen.Artist(name, token))
    },
    onEnsureService = onFirstPlay,                                        // :398 — this one is fine
)
```

`AppRoot` reads `container.orchestrator.state` (`:322`), so it recomposes on every `PlayerState`
change — every phase transition, every queue mutation, every seek. `NavStackContent` and
`AppOverlays` both receive fresh lambdas on each of those and cannot skip, so the whole screen body
runs. It is not a 10 Hz storm (`PlayerState.posMs` is written by seeks, not by the ticker — see
`MediaHub`'s KDoc for the correct argument), but it is avoidable work on the composition that owns
the navigation stack.

**Fix.** `remember { (s: Sheet) -> sheet = s }` etc., keyed on nothing — they close over
`mutableStateOf` setters, which are stable. `onOpenSettings` and `onSheet` can share one
`remember`ed lambda because `Sheet.Settings` is just a value.

**Risk.** Low. This is a skippability change, and Compose's ability to skip here is a property of
the runtime I could not measure. Grade LIKELY rather than CERTAIN for that reason; the lambda
identity argument itself is certain.

---

### F-19 · `rememberCachedKeys` never invalidates on eviction

**S3 · LIKELY · `androidApp/src/main/kotlin/dylan/android/ui/components/Common.kt:87-113`**

```kotlin
@Composable
fun rememberCachedKeys(container: AppContainer): Set<SongKey> {
    var keys by remember { mutableStateOf(emptySet<SongKey>()) }
    val inflight = rememberDownloadingKeys(container)
    LaunchedEffect(inflight) {                       // ← only these two state changes re-run the query
        keys = withContext(container.disp.dbLane) { selectAllCached() ... }
    }
    return keys
}
```

The effect key is the in-flight key set. It therefore re-runs when a download starts or ends — which
is *almost* every time the cached set changes, because the completion of the last in-flight download
turns `{k}` into `emptySet()`. But a change that touches neither the in-flight set nor an
eviction that happens with nothing in flight (a budget sweep that fires from `Favorites.add`, from
`IosGraph.enqueueBulkDownloads`, or from `Reconciler.run()`'s boot `enforceBudget`) leaves the key
set stale.

`canPlay(isOnline, cachedKeys, key)` (`Common.kt:137-141`) then answers "playable offline" for a row
whose file the LRU pass just deleted, and the song is greyed-in until the next download starts.

**Fix.** Key on `cacheManager.revision` too — it exists precisely as "bumped by every mutation this
class makes" (`CacheManager.kt:64-69`). It is `private`, so expose it as an `internal`/public
`StateFlow` and `combine` it with the in-flight set.

**Risk.** Low. One extra full-`selectAllCached()` per cache mutation, on the io/db lanes, from at
most three live screens. That is the same cost the fix is trying to avoid — so consider deriving
the key set reactively from `cacheManager.downloads` (already a JOIN, already reactive) instead of
adding a query.

---

### F-20 · `DownloadEngine.scope` is an unsynchronised `var`

**S3 · LIKELY · `shared/src/commonMain/kotlin/dylan/download/DownloadEngine.kt:208`, `:273-287`, `:286`**

```kotlin
private var scope: CoroutineScope = CoroutineScope(SupervisorJob() + disp.on(Lane.IO) + engineFailure)

fun stop() {
    started.store(false)
    scope.cancel()                       // ← read of `scope` from whatever thread called stop()
    ...
}

fun start() {
    if (!scope.isActive) {
        scope = CoroutineScope(...)      // ← write of `scope`
    }
    ...
    repeat(WORKER_COUNT) { index -> scope.launch { worker(index) } }   // :286 — read of `scope`
}
```

`start()` is called from `AppContainer.bootComponents` on the **io** lane; `stop()` from
`AppContainer.stop()`, reachable from any thread. The workers themselves read `scope` on io lanes.
`scope` is a plain `var` with no `@Volatile` and no atomic. In the JVM memory model a worker can
observe the stale `scope` and launch into a cancelled one; worse, a `stop()` racing a `start()` can
cancel the *new* scope, silently bricking downloads — the class's own comment (`:196-198`) records
that this exact failure was fixed once before.

There are currently **zero** callers of `AppContainer.stop()` in the tree, so this is latent rather
than live. But the fix for the previous instance of this bug was to make the scope re-creatable,
which introduced the unsynchronised write that will bite whoever first calls `stop()` and then
`start()`.

**Fix.** Make it an `AtomicReference<CoroutineScope>` (the file already uses `kotlin.concurrent
.atomics`, so the import is there), or guard the transition with a `Mutex`.

**Risk.** Low. The transition is already rare and the CAS is not load-bearing for throughput.

---

### F-21 · `WindowPreparer.cachedRows`'s KDoc calls an N-statement loop "the N+1-free form"

**S4 · CERTAIN · `shared/src/commonMain/kotlin/dylan/playback/WindowPreparer.kt:49-63`**

> "Every cached row for [keys] in a single `dbLane` pass — the N+1-free form of [cachedRow]."

It *is* one lane pass, so the description is defensible if you read "N+1" as "N+1 lane round trips".
It is not N statements:

```kotlin
return withContext(disp.on(Lane.DB)) {
    val out = LinkedHashMap<SongKey, Cached_files>(wanted.size)
    for (k in wanted) {
        db.dylanQueries.selectCached(k.provider, k.songId).executeAsOneOrNull()?.let { out[k] = it }
    }
    out
}
```

`Orchestrator` already has the batched primitive this should use — `selectSongsByIds(chunk)` with
`DB_IN_CHUNK = 400` (`Orchestrator.kt:197`, `:1497`, `:1595`). With a 2-key window the cost is
irrelevant; the cost of the *comment* is that it names a property the N+1 vocabulary promises.

**Fix.** Either add a `selectCachedByIds` query and use it, or soften the KDoc to "one lane pass —
the per-key statements remain, which is fine for a two-item window".

**Risk.** None for the comment. Low for the query, but it is pure optimisation on a 2-element set;
not worth the schema surface.

---

### F-22 · `Orchestrator` is genuinely too large — but not for the reason `LargeClass` names, and there is a safe first split

**S3 · LIKELY · `shared/src/commonMain/kotlin/dylan/playback/Orchestrator.kt:87-1606`**

detekt reports `LargeClass` + `TooManyFunctions(82)`. `docs/issues.md` #24 refuses the split, on the
ground that "every candidate cluster reads 8+ `private` members of the class owning the
single-permit state lane, and Kotlin `private` members are invisible outside the class, so a file
split necessarily widens shared mutable playback state from `private` to `internal`".

I read the class looking for a cluster that does **not** need the shared state, and there are three.
The refusal is right about the class as a whole and wrong about these three:

**Pass 1 — `ResumeStore`** (`saveSnapshot`, `saveSnapshotAsync`, `startTicking`,
`snapshotFingerprintOf`, `resumeCandidateMs`, `freshPosition`, `snapshotJob`,
`snapshotFingerprint`, and the `lastPosMs` that `attachEngine`'s position collector writes).
Owns ~90 lines and 6 of the 82 functions. Its only outward reads are `_state.value` and
`settings`; everything it writes is its own. Seam: `suspend fun tick(state: PlayerState)`,
`fun notePosition(ms: Long)`, `fun positionMs(): Long`. Zero `internal` widening — nothing outside
the cluster currently reads those fields.

**Pass 2 — `SessionLogger`** (`onTrackStarted`, `recordHistory`, `bumpPlayCount`, `watchSession`,
`countedThisSession`, `lastHistoryKey`, `lastHistoryAt`, `sessionJob`). Needs `db`, `disp`,
`cfg`, a position `Flow`, and a `Launch` callback. Also self-contained.

**Pass 3 — `WindowOwner`** (`prepareWindow`, `refreshUpNext`, `joinableUpNext`, `trackFor`,
`syncPendingNext`, `pushedUpNextId`, `doneJoinedKey`, `keyToSlot`, `slotIndexOf`, plus `engine`
and `WindowPreparer`). This is the one that widens the surface: `engine` is read by
`togglePlayPause`, `seek`, `navigate`, `holdPosition`, `onItemEnded`, `onEngineDetached`,
`disposeOnState` and `attachEngine`. Pass 3 is where the `private`→`internal` argument applies, and
it can be deferred without blocking passes 1 and 2.

Two passes would take the class to roughly 1 050 lines and ~68 functions — still over detekt's 25,
but with the residual being the irreducible routing core (`process`/`handleIntent`/`handleEvent`
and the phase transitions), which is what the file is *for*. That is a much more defensible refusal
than "the class is one unit".

**Risk of doing it now.** Real, and the refusal is right about one thing: `Orchestrator` is the
single most behaviour-critical class in the app and every extracted state field is a field whose
lifetime changes from "the class" to "the collaborator". Pass 1 is the safest because the snapshot
ticker is already causally isolated — nothing else in the class reads `snapshotFingerprint` or
`lastPosMs` except through the two functions being moved. Do pass 1 alone, behind
`OrchestratorEdgeTest` + `SnapshotSanitizeTest`, before considering pass 2.

---

### F-23 · `SongKey.itemId` embeds raw ids in a `:`-joined string

**S2 · SPECULATIVE · `shared/src/commonMain/kotlin/dylan/model/Models.kt:17-24`, `shared/src/commonMain/kotlin/dylan/playback/Orchestrator.kt:1279-1288`**

```kotlin
fun itemId(generation: Long, provider: String, songId: String, bits: Int) =
    "$GEN_PREFIX$generation:$provider:$songId:$bits"          // Models.kt:20
```

and the parser:

```kotlin
private fun queueKeyOf(itemId: String): SongKey {
    val parts = itemId.split(SongKey.GEN_SEP)
    return SongKey(parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" })
}
```

This is correct as long as neither `provider` nor `songId` contains `:`. I have no evidence that
JioSaavn's song ids do — `CachePath.SEGMENT_RE = Regex("[A-Za-z0-9-]+")` is the *filename*
allowlist and a non-matching id is SHA-256'd for the filename but left raw for `itemId`, so the
filename path is already defensive where the itemId path is not.

If a colon ever appears, `queueKeyOf` returns a truncated `songId`, `keyToSlot` misses,
`onTrackChanged` takes the `resyncFault` branch, and the user loses the current track every
3 strikes — a loud failure, not a silent one, which is the good outcome.

Graded **SPECULATIVE** because it is asserted without a counterexample. If you want it settled, the
cheap proof is a `require(':' !in provider && ':' !in songId)` in `itemId` for one release and a
look at the crash logs; the cheap fix is to base64/hex the key or length-prefix it.

**Fix.** Prefer the `require` over the re-encoding: a `require` turns an unknown into a loud
programmer error, which is this codebase's stated preference (`QueueInvariantViolation`'s KDoc).

---

## 3. Performance audit

### 3.1 What is already right, and must not be "cleaned"

These are the patterns most likely to be broken by a well-meaning refactor.

**Draw-phase reads instead of recomposition.** `MiniProgress` (`NpPlayerSurface.kt:540-557`) reads
`pos.value` *inside* the `graphicsLayer` block, so the 10 Hz position ticker invalidates a draw
layer and nothing else — no measure, no layout, no recomposition anywhere above it. The KDoc is
accurate. Do not hoist the read into the composable body.

**Correct modifier order.** Both `SurfaceScrim` (`NpPlayerSurface.kt:448-450`) and `MiniProgress`
(`:553-556`) put `graphicsLayer { alpha/scaleX }` **before** `background(...)`. `graphicsLayer` is the
outer node, so the background is composited into the layer and the alpha applies to it. Reversing
this — which reads as tidier — makes the alpha apply to nothing. `docs/issues.md` lists
"`graphicsLayer` ordering after `Modifier.background`" as already-reported; it is **fixed** here, and
the current order is the fix.

**The dead-tap fix is structural, not a guard.** `SurfaceScrim` gates the whole `Modifier.pointerInput`
on `expanded` (`:450`) rather than putting `if (expanded)` inside the block. That is the right shape:
the hit node does not exist when it must not consume. Do not refactor it into a conditional body.

**Per-row progress keying.** `rememberDownloadPct` (`Common.kt:120-127`),
`rememberAnyDownloading` (`:130-134`) and `rememberDownloadingKeys` (`:107-113`) each `map` the shared
`progress` map to a *per-row* value before collecting, with the comment explaining that reading the
map at screen level made every visible row recompose 4×/s. Correct, and three years of Compose
bug reports back it up.

**`Conflation.KEEP_EVERY` vs `LATEST_ONLY`.** `PlayerStateAdapter` keeps every emission because
`PlayerState` is a data class whose `equals` already dedupes at the `MutableStateFlow`;
`PositionAdapter` conflates because 10 Hz intermediate values are worthless (`FlowAdapter.kt:26-40`).
The two-way enum exists precisely so a call site cannot say `conflate = true` without saying which
stream. Leave it.

### 3.2 Hot paths, cost and fix

| # | Hot path | Cost | Fix | ID |
|---|---|---|---|---|
| P-1 | `AppContainer.scanQualityUpgrades` every 30 min | 1 + N `dbLane` statements, N = every 128 kbps pinned/twice-played row; serialised against every download step and every `settings.get()` | one `JOIN … LIMIT 3` already written and unused | F-01 |
| P-2 | `Reconciler.fullSweep` weekly | 1 + N `dbLane` hops + N `stat`s, where N is every cached row; the per-row re-read also risks unlinking a healthy rendition | pass `it.bytes` from the query already held; delete `currentBytes` | F-10 |
| P-3 | `HomeScreen.kt:90` on every favourites/jump-back change | 4·F·(J+1) `Regex` compiles + ~8·F·(J+1) string allocations in one `remember` | hoist + set-based `containsRecording` | F-03 |
| P-4 | `sniffOk` → `stampOf`, per track change and per queue edit | 1 blocking `stat` on the single-permit state lane, memoised or not | `withContext(disp.on(Lane.IO))` around `stampOf` | F-05 |
| P-5 | `SeekBar` body, per 10 Hz tick | 1 `SliderColors` + 2 lambdas + 1 `Modifier` chain + 2 `String`s per tick while the NP sheet is open | `remember` the colors and the thumb slot | F-17 |
| P-6 | `AppRoot` body, per `PlayerState` emission | whole screen body recomposes because 3 of 5 lambdas are fresh | `remember` them | F-18 |

**Not a problem, checked explicitly:**

- **`foldPage` / `mergeHits` in search paging** (`SearchScreen.kt:825-834`, `:587-599`).
  `foldPage` is O(held) per page, so a k-page section costs O(k·n) — 20 pages of 20 rows is 400
  hash inserts plus 400 copies, i.e. microseconds, once per page fetch rather than per frame.
  `mergeHits` is inside `remember(results.songs, results.albums, results.artists, submitted)`
  (`:287-289`) so it re-runs on a fold, not on a recomposition. This is the *right* shape; the O(k·n)
  is the honest cost of "a page that adds nothing new means exhausted", which is the correct
  exhaustion rule the docs record as the fix for the mixed-envelope bug.
- **`cacheManager.downloads`** (`CacheManager.kt:101-103`): `combine(...).map { readDownloads() }`
  runs one JOIN per mutation, not per emission or per row. `removableKeys` (`:106-107`) re-derives a
  set from that list — one O(n) per emission, acceptable.
- **`MutableStateFlow.update` on large maps.** The retention code (`DownloadEngine.kt:992-1007`,
  `TERMINAL_RETENTION`/`PROGRESS_RETENTION = 128`) is the documented mitigation and the constant is
  justified in a comment that is accurate. I looked for the pattern repeating elsewhere: the only
  other large maps are `AppContainer.protectedKeys` (a `Set<SongKey>` bounded by
  `current + nextUp + inflight + upgrade`, ≤ ~5 entries) and `CacheManager.revision` (a `Long`).
  Nothing else repeats it.
- **The 10 Hz position ticker's blast radius.** Traced end to end: `Orchestrator.positionMs` →
  `flatMapLatest` over `engineFlow`. Two collectors in `androidApp`: `MiniProgress` (draw-phase,
  free) and `SeekBar` (P-5). `watchSession`'s collector (`Orchestrator.kt:1324-1344`) reads
  `_state.value` and does arithmetic — cheap, and on the state lane. `maybePrefetchAtTail`
  (`:1363-1373`) is a handful of comparisons. No per-tick DB write: `startTicking` ticks at
  `snapshotIntervalMs = 30_000` and `saveSnapshot` short-circuits on a fingerprint (`:1400-1416`).
- **Coil artwork.** The singleton `ImageLoader` is configured once in `DylanApp.onCreate` with
  `imageMemoryCacheBytes` and `imageCacheBytes` from `AppConfig`, and `ExoPlayerEngine`'s
  `artworkLoader` deliberately routes through it rather than building a per-load `ImageLoader`
  (`ExoPlayerEngine.kt:44-59` — a comment that explains exactly the failure that avoids). All
  `AsyncImage` calls pass a URL string, so decoding is already on Coil's own workers.
- **`clampSeekTargetMs` / `clampPlaybackRate`** — pure, allocation-free, and both KDoc blocks
  correctly describe why `NaN` and `0` must be treated as unknown rather than as values.

### 3.3 The one thing I could not measure

The Compose recomposition claims in P-5 and P-6 are arguments about lambda identity and
`remember` scoping, not measurements. The allocation counts are certain; the frame-time effect is
not. Grading them LIKELY/CERTAIN for the allocation and unstated for the frame cost is deliberate —
this codebase has already lost time to confident wrong diagnoses, and the honest statement is that
a Layout Inspector trace on a device would settle both in about ten minutes.

---

## 4. What I could not verify

1. **iOS compiles.** No Xcode, no Kotlin/Native toolchain. `shared/src/iosMain/**`,
   `iosApp/**` and the pbxproj were read by eye only. `F-04`'s proposed `\p{L}` fix in particular
   must be checked on the Native target before it ships — Kotlin/Native's `Regex` is not
   `java.util.regex`.
2. **Anything requiring the device.** I did not run `adb`, install, or screenshot. Rows 25-27 of
   `docs/issues.md` (player-sheet tracking, scroll paging, cache eviction on a real volume) remain
   unverifiable here. So does F-19's staleness, which needs a real eviction to observe.
3. **The flake rate in F-02** is measured on this machine's JBR/JDK-17 and thread scheduling,
   with no other load. The rate itself will differ on CI; the existence of the interleaving will
   not, because it is a race inside `readAvailable` and not a property of this machine.
4. **`ExoPlayerEngine`'s HandlerThread and Media3 thread-confinement claims.** The comments in
   `DylanMediaService` about `verifyApplicationThread()` and `onTaskRemoved` are load-bearing and
   specific; I accepted them as written because they are checkable only against a device or a
   debugger.
5. **Whether `libs.versions.toml`'s pinned Compose/Material3 versions match the behaviour I
   assumed for `SliderDefaults.colors`.** The API shape is stable across 1.x; the exact allocation
   behaviour of `SliderDefaults.colors` I did not disassemble.
6. **F-23.** No counterexample found; no proof of absence either.

---

## 5. Deliberately fine — do not "clean these up"

Recorded so the next pass does not spend a day reverting correct work.

1. **`SurfaceScrim`'s `expanded`-gated modifier** (`NpPlayerSurface.kt:450`). Gating the modifier
   rather than the block body is the whole fix. An `if (expanded)` inside `detectTapGestures` is
   the bug.
2. **`MiniProgress`'s draw-phase position read** (`NpPlayerSurface.kt:546`, `:553-556`). The 10 Hz
   flow deliberately never recomposes anything above it.
3. **`graphicsLayer` before `background`** in both `SurfaceScrim` and `MiniProgress`. The order is
   the fix, not the defect.
4. **`MediaHub`'s refusal to put `AudioRoute` in `PlayerState`** (`MediaHub.kt:11-27`). The
   four-bullet argument — no shared consumer, a platform-shaped projection, nothing to publish on
   iOS, and the churn premise being false — is checked against the tree and correct. So is the
   matching `PlayerState` KDoc (`Models.kt:197-201`).
5. **`IosGraph.create`'s `Dispatchers.IO` comment** (`IosGraph.kt:370-375`). Verified against
   kotlinx.coroutines 1.11.0 `kotlinx-coroutines-core/native/src/Dispatchers.kt`: `internal val IO`
   on `Dispatchers`, and `@Suppress("EXTENSION_SHADOWED_BY_MEMBER") public actual val
   Dispatchers.IO` — the member does shadow the public extension. **`docs/codebase-audit.md`
   §3.6 Phase 0 #10 ("the 'does not exist on Native' comment is false for coroutines 1.11.0") is
   wrong**; do not act on it.
6. **`fsRename`'s cross-device behaviour.** Documented as NOT A BUG in `docs/issues.md` #15 with a
   two-device measurement. `sun.nio.fs.UnixCopyFile.move` does fall back to copy+delete on `EXDEV`.
   Do not hand-roll a copy.
7. **`ExoPlayerEngine.release()` being called** (`DylanMediaService.kt:565`). `docs/codebase-
   audit.md` AN-1 ("called from nowhere ⇒ HandlerThread, coroutine scope and AudioManager leak per
   cycle") is **FIXED**. Do not re-file.
8. **`Orchestrator.guard` rethrowing `QueueInvariantViolation` and `LaneViolation`**
   (`Orchestrator.kt:345-363`). A loud dead lane on a shipped build is the stated, deliberate
   preference. Do not soften it into a log line.
9. **`dispose()`/`shutdown()` non-reentrancy.** The one-shot CASes on `shutDown` and `phase` are
   the entire mutual exclusion; `cancelAndJoin`'s self-check (`:661-670`) exists specifically so
   joining from inside a container job cannot hang. Do not "make it idempotent" by adding a second
   guard.
10. **`navDebounceMs = 300` and the `null`-not-`INFINITE` sentinel** (`Orchestrator.kt:1519-1525`).
    The KDoc explains why `Duration.INFINITE` made the *first* tap always drop and every later tap
    with it. It is correct and subtle; leave it alone.
11. **`stallWallFloorMs` and `readyTimeoutMs` sharing one literal** (`AppConfig.kt:199`). The shared
    `READY_TIMEOUT_MS` is intentional, with the reasoning about what a constant *can* and *cannot*
    guarantee written out correctly (`AppConfig.kt:143-157`).
12. **`AppConfig.cacheMaxBytes` at 2.25 GiB and the derived row cap.** The inversion
    `docs/codebase-audit.md` §3.3/§4 calls for is implemented and arithmetically consistent
    (`AppConfig.kt:95`, `:106`; `CacheManager.kt:82-94`). 2 GiB target + 12.5% headroom =
    2 415 919 104 B; /1 000 000 = 2 415 rows; × 0.75 = 1 811 pinned rows. **Still open, and worth a
    device conversation:** 2.25 GiB is a large fraction of a typical free volume and
    `diskFloorBytes` (500 MiB) is checked per download against *current free space*
    (`DownloadEngine.kt:608`) rather than against total device headroom. That is a product
    decision, not a defect.
13. **`RecordingKey`'s existence.** The `distinctRecordings` idea is right; only its normalisation
    (F-03, F-04) is wrong.

---

## 6. Reading list for whoever acts on this

Ordered by (impact × certainty) ÷ risk:

1. **F-01** — dead fix, live N+1, one query already written. S1, mechanical.
2. **F-02** — the only gate on the byte-loss seam is flaky. Fix the test's model *first*, then
   classify the throw in `Transfer`.
3. **F-05** — one `withContext`, removes blocking I/O from the lane that owns playback.
4. **F-10** — delete `currentBytes`, pass `it.bytes`; also removes a healthy-file-deletion hazard.
5. **F-03 + F-04** — one file, `RecordingKey.kt`. Do them together; F-04 changes identity for every
   non-ASCII title and needs its own test.
6. **F-17 + F-18** — Compose allocation churn. Cheap, low-risk, and the honest payoff is unmeasured.
7. **F-06, F-08, F-12, F-13, F-21** — pure deletion and comment fixes. No behaviour change.
8. **F-07, F-09, F-15** — comments that assert things the code does not do. Fix the wording, not
   the code.
9. **F-14** — one constructor argument, and it removes a 45-line KDoc defending a duplication.
10. **F-19, F-20, F-23** — latent, no caller today. Verify the caller exists before fixing.
11. **F-22** — the Orchestrator split. Pass 1 only, behind the existing snapshot tests.
