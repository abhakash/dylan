# Open issues, ranked

Single ranked view. Detail, evidence and withdrawn theories live in `docs/todo.md`; this file is
the index.

**Last updated: 2026-10-08 — documentation-integrity pass.** Every status cell below was
re-derived by reading the working tree, not by trusting the prose that was already here. This
ledger has been wrong more than once; the standing rule is that a row must say exactly one of the
verdicts below and nothing softer.

**Statuses (one per row, no other spellings):**

| Status | What it asserts |
|---|---|
| **FIXED — code** | the fix is present in the working tree and was read at the cited lines |
| **FIXED — code + test** | the fix is present **and** a test pins it. The test was read, **not run** |
| **FIXED — device** | an earlier session measured it on hardware; the mechanism is now readable in code |
| **PART-FIXED** | part of the claim is resolved; the rest is named explicitly |
| **NOT A BUG** | the claim was withdrawn, with the evidence that withdrew it |
| **OPEN — cause established** | the defect is real and its mechanism is known |
| **OPEN — cause unproven** | the symptom is real, the cause is not |
| **UNVERIFIED — needs device** | nothing in the code can settle it either way |

**Nothing in this file is green because a gate, a test suite or an app was run during this pass.**
No build, no test run, no device. "code + test" means the assertion exists in a file that was
read; it does not mean the assertion passed. Every count that came out of a run (`313 findings`,
`453 tests green`, `10/10`, `142/150`) is carried forward as a historical measurement and marked
as such. See "What this document cannot tell you" at the end.

Working-tree caveat: `git status --porcelain` is the source of truth for this pass. ~70 files are
modified and **nothing is committed beyond HEAD `607477a`**, so every "FIXED" here is a
working-tree fact that a `git stash` or a hard checkout would silently erase.

---

## Tier 1 — data loss and wrong behaviour on the user's device

| # | Issue | Where | Status |
|---|---|---|---|
| 1 | One download attempt can publish two terminal states | `download/DownloadEngine.kt` | FIXED — code + test |
| 2 | `wipe()` never checks that it deleted anything | `db/DriverFactory.{android,jvm}.kt` | FIXED — code + test |
| 3 | Foreign keys were never enforced on Android | `db/DriverFactory.android.kt` | FIXED — device + code |
| 4 | Every `PRAGMA` was failing silently | `db/DriverFactory.android.kt` | FIXED — device + code |
| 5 | Mid-stream resets silently discarded every downloaded byte | `download/Transfer.kt:729` | FIXED — code |

### Row 1 — double terminal state

`onSizeMismatch` did `scope.launch { fail(STORAGE) }` on a *sibling* coroutine nothing joins, then
returned `Step.VERIFY`; `onVerify` read that same sentinel as "budget spent" and called
`fail(CORRUPT_SIZE)` itself. Two `fail()`, two `dropIntent`, two `publishTerminal`.

**Correction, recorded.** My earlier framing — that a race decided whether the `.part` was
deleted — was wrong. Neither `STORAGE` nor `CORRUPT_SIZE` is in `NON_RESUMABLE_CODES`
(`DownloadEngine.kt:974-981`, the set is `{NOT_CACHEABLE, NO_SOURCE, NOT_FOUND, UNSUPPORTED,
CORRUPT_CONTAINER}`), so the bytes survived either way. The real damage was the racy error code,
the double `dropIntent`, and a detached `publishTerminal` able to land after `runJob`'s `finally`.

Verified present: `onSizeMismatch` is `suspend` (`:772`); the `fail(STORAGE)` is awaited and
followed by `return null` for "terminal, already published" (`:794-795`); `onSizeMismatchStep`
keeps `null` and `Step.VERIFY` as two different answers (`:737-741`). Pinned by
`jvmTest/DownloadEngineTerminalStateTest.kt:99-171`, which asserts exactly one `failed …` line for
the song, one terminal state, code `STORAGE`, and that the part survives. The `tools/lane-check.sh`
ALLOWED entry for the detached launch was removed in the same pass.

### Row 2 — `wipe()` never confirmed deletion

The `Boolean` from `deleteDatabase`/`delete` and any exception were both discarded, silently, on
the line below a log asserting *"This deletes all user data"*. `TooGenericExceptionCaught` cannot
see it — it is a `runCatching` lambda, not a `catch`.

Verified present: Android `wipe()` logs a `false` return and a throw (`DriverFactory.android.kt:
289-303`) and `exists()`-checks each sidecar before deleting it, so a missing one is not
misreported; the JVM factory is the same shape (`DriverFactory.jvm.kt:239-252`). HEAD discarded
both (`git show HEAD:shared/src/androidMain/kotlin/dylan/db/DriverFactory.android.kt`, lines
239-244). Covered by `jvmTest/DriverFactoryWipeGateTest.kt`.

### Row 3 — foreign keys never enforced on Android

Enabling WAL turned on Android's connection *pool*, and `PRAGMA foreign_keys=ON` is per-connection,
so it armed on one connection and left `ON DELETE CASCADE` a no-op on the rest. Device read back
`0`.

Verified present: the platform call `setForeignKeyConstraintsEnabled(true)` at `DriverFactory.
android.kt:86`; `verify()` reads `PRAGMA foreign_keys` back and logs loudly if it is not 1
(`:162-165`). The device measurement itself (`foreign_keys` = 0 before, enforced after) was taken
by an earlier session and is **not re-derivable from code here**.

### Row 4 — every `PRAGMA` was failing silently

`driver.execute` bottoms out in `execSQL`, which rejects row-producing statements; each pragma
threw and was swallowed by the `runCatching` around setup. The DB ran in rollback-journal mode with
no busy timeout and no FK enforcement for its entire life.

Verified present: every pragma now goes through `driver.executeQuery` (`DriverFactory.android.kt:
272`), and `verify()` reads `journal_mode` and `busy_timeout` back rather than trusting them
(`:169-184`). Note `busy_timeout` is asserted as a floor `> 0`, not as `== 5000`; the code says
why and the reason is sound (a per-connection pragma cannot be made uniform from here).

### Row 5 — mid-stream resets discarded every downloaded byte

ktor-io's `ByteChannel.isClosedForRead` has a `closedCause` disjunct with no `_readBuffer`
conjunct, so `readAvailable()` returns `-1` with bytes still buffered — unrecoverably, the getter
throws. Fixed by reading the engine's own `rawContent`.

Verified present: `HttpResponse.engineBody()` is `rawContent` (`Transfer.kt:729`) and is the only
call site (`:469`). The two guard tests exist and were read: `jvmTest/fuzz/probe/
DownloadBodyChannelIdentityTest.kt` and `ReadAfterAwaitContentContractTest.kt`. The 150-run
measurement (`bodyAsChannel()` lost all bytes in 142/150 with no error; `rawContent` 0/150) is a
historical lab number and was **not** reproduced here.

---

## Tier 2 — user-visible wrong rendering

| # | Issue | Where | Status |
|---|---|---|---|
| 6 | Light mode does not exist, and the token local hands out dark tokens | `ui/DylanTokens.kt` | PART-FIXED |
| 7 | Black-on-black system bar icons | `android/MainActivity.kt:64-67` | FIXED — code |
| 8 | `MOCKINGBIRD` ×3 in one search | `saavn/RenditionIdentity.kt` | FIXED — code + test |
| 9 | Search shows 0 artists | `saavn/Identity.kt:64-69` | OPEN — cause unproven |
| 10 | Titles render as `&AMP;` | `saavn/Identity.kt:135` | FIXED — code + test (new fetches only) |

### Row 6 — light mode

`LightTokens` and `DarkTokens` were field-for-field identical; `toScheme` branched on
`t == DarkTokens` and `DylanTokens` is a `data class`, so value equality made it true for
`LightTokens` too; `LocalDylanTokens` *defaulted* to `LightTokens`, so all readers got dark values
whatever the theme flag said.

**The accidental half is gone.** `LightTokens` is an alias of `DarkTokens` — the same object, so
drift is unrepresentable (`DylanTokens.kt:76`); `LocalDylanTokens` defaults to `DarkTokens`
(`:83`); `toScheme` has no equality branch at all (`:95-116`) and the selection is flag-driven
(`:148`). "No user-visible change" is code-verifiable without running anything: both `darkTheme`
values hand the *same object* to the same pure function.

**What remains is a design decision, not a code defect:** the light palette itself — real values
for the ~88 un-overridden `ColorScheme` roles, eleven of which flip with the Material baseline
(`:88-94`). Do not read this row as "light mode works".

### Row 7 — black-on-black system bar icons

Bare `enableEdgeToEdge()` picks light (dark) icons when `isSystemInDarkTheme()` is true, while the
app background is hard-coded `#FF000000` in both token sets. On a light-mode device the clock,
battery and signal were black on black.

Verified present: both bars pinned with `SystemBarStyle.dark(Color.TRANSPARENT)` at
`MainActivity.kt:64-67`, with the reasoning recorded inline (`:48-63`): `TRANSPARENT` is what the
defaults already resolved to because the nav-bar scrim pair is only read below API 29 and `minSdk`
is 34. The surrounding claim that these APIs are deprecated only *after* the pinned activity
1.12.4 is a statement about a library artifact and is **not verifiable from this repo's source**.

### Row 8 — `MOCKINGBIRD` ×3 in one search

Dedup is on `SongKey` = `(provider, songId)`, but Saavn returns a different `songId` per album
rendition, so the same track from three albums is three keys — and three rows of page budget.

Verified present: `trackIdentity` = normalised title + artist + duration, with NUL as the
separator (`RenditionIdentity.kt:46-56`); `decodeSongPage` folds at the page boundary and records
every collapse as `RENDITION_DUPLICATE` drift (`Mapper.kt:216`, `RenditionIdentity.kt:91-99`,
`:128`); the cross-page fold is `dedupeRenditions` (`:108`), which is what the search list uses.
`TrackIdentityTest` exists and was read; its pass count (`10/10`) is a historical number, not a
result of this pass. **Known limitation, documented in code:** a rendition whose *title* the origin
rewrote per album does not collapse — no title-based key sees through a rewritten title.

### Row 9 — search shows 0 artists

`Mapper.mapMini` drops any artist card whose `permaUrl` lacks the literal substring `/artist/`. If
Saavn's artist cards differ, *every* artist is dropped and the section renders empty with no error.

**Cause still unproven.** `permaArtistToken` still requires `/artist/` (`Identity.kt:64-69`) and
the drop path is unchanged (`Mapper.kt:140`, `:177`). `PermaArtistTokenTest` pins the current
behaviour for every plausible `perma_url` shape and pins four shapes that yield a *wrong* token
rather than none, so the assumption is now written down and falsifiable instead of folklore.

**Progress that is not the fix:** an error envelope now produces `ORIGIN_ERROR` drift
(`Mapper.kt:247`, `PageEnvelope.kt:54`), so "the origin refused" is no longer byte-identical to
"no matches". The cause of the empty artist section is still not established, and nothing in the
code can establish it — that needs a real response, which needs a device or a proxy.

### Row 10 — titles render as `&AMP;`

Saavn returns HTML-escaped names. **Caveat: rows already persisted in the DB are not re-decoded.**

Verified present: `decodeEntities` (`Identity.kt:135`) behind `displayTitle`/`displaySubtitle`
(`:197`, `:200`), called from three mapper sites (`Mapper.kt:105`, `:157`, `:326`).
`EntityDecodeTest` now holds 16 tests, including the `&#X41;` case that motivated widening the hex
branch. The persisted-rows caveat still holds: nothing re-decodes rows already in the DB, so a
user who upgraded keeps the raw markup until the row is refetched.

---

## Tier 3 — measurement and tooling that can lie

| # | Issue | Where | Status |
|---|---|---|---|
| 11 | `DefectLineProbe` prints a hard-coded verdict | `fuzz/probe/DefectLineProbe.kt:152` | FIXED — code |
| 12 | `ProbeMain` P3 can report a false "truthful-length violated" | `probe/ProbeMain.kt:84-93` | FIXED — code |
| 13 | iOS `verify()` does not verify what its comment claims | `DriverFactory.ios.kt:63` | FIXED — code, no Xcode |

### Row 11 — `DefectLineProbe`'s hard-coded verdict

`verdict=StreamErr.Truncated(written=0)` was a string literal printed next to the real counters.
If a change made `written > 0`, the probe would keep printing the old conclusion — on the one
harness guarding row 5.

Verified present: `resumable`, `verdict` and the Range wording are all derived from the live
counters (`DefectLineProbe.kt:152-158`); the literal is gone. "Output byte-identical to before"
and "mutation-tested both arms" are claims about a run and are **not verifiable from the code**.
Line reference in the previous version of this row (`:141`) was stale.

### Row 12 — `ProbeMain` P3's false truthful-length failure

`bodyBytes` silently stopped at 8 MB, then P3 asserted `require(actual == cl)`. At 128 kbps that is
~8.4 minutes, so any longer track reported drift that was not there — and silently, so a false
positive was indistinguishable from a real one.

Verified present: `bodyBytes` returns `BodyRead(bytes, truncated)` (`ProbeMain.kt:66-68`,
`:208-226`), so the cap is a returned fact; `truthfulLengthFailure` is a pure function that refuses
to compare a capped read against a `Content-Length` and instead says the length was not measurable
(`:84-93`); P3 uses it (`:419-426`). The previous row's references (`:159-178`, `:371-373`) were
stale.

### Row 13 — iOS `verify()`

Said the pragmas are "read back", but read only `user_version` and `foreign_keys`;
`journal_mode=WAL` and `busy_timeout=5000` were set and never checked — the gap that let Android
run its whole life in `journal_mode=rollback`.

Verified present: all four are read back, `busy_timeout` as a floor `> 0` because it is
connection-local (`DriverFactory.ios.kt:63-80`). **Uncompiled: no Xcode in this environment.**
Whether this file still compiles is a question only a Kotlin/Native or Xcode toolchain can answer.

---

## Tier 4 — resource lifetime, portability, and hygiene

| # | Issue | Where | Status |
|---|---|---|---|
| 14 | `WalOpenHelperFactory` pins the database open after close | `DriverFactory.android.kt:129-154` | FIXED — code |
| 15 | `fsRename` cannot cross filesystems | `util/Rename.{android,jvm}.kt` | NOT A BUG |
| 16 | `Copy.kt` is a dead duplicate of the real copy source | `ui/Copy.kt` | FIXED — code |
| 17 | `ENTITY_RE` cannot match uppercase `&#X41;` | `saavn/Identity.kt:126` | FIXED — code + test |
| 18 | `Clients.kt` KDoc contradicts its own retry comment | `net/Clients.kt:14-27` | FIXED — code |
| 19 | A stale KDoc asserts the opposite of what was measured | `DriverFactory.android.kt:261` | FIXED — code |
| 20 | `FlowAdapter` swallows `Error`s; `Orchestrator` does not | `bridge/FlowAdapter.kt:148` | FIXED — code + test |
| 21 | Dead code in `ProbeMain` | `probe/ProbeMain.kt:400` | FIXED — code |

### Row 14 — `WalOpenHelperFactory` pins the DB open

Process-lifetime reference to the last `SupportSQLiteOpenHelper`, hence the file handle and its
`-wal`/`-shm`. Worst on the wipe path: `wipe()` unlinks the file while the process still holds it,
so space is not reclaimed until process death.

Verified present: the helper reference is read once inside `open()` and cleared by `release()` in
the `finally` (`DriverFactory.android.kt:94-100`, `:151-153`); clearing rather than closing is
deliberate and the reason is recorded (the driver's own `close()` closes the helper, and closing it
twice would throw). HEAD held the reference for the process lifetime.

### Row 15 — `fsRename` cannot cross filesystems — **NOT A BUG**

**The claim was false, measured, and deliberately not "fixed".** `sun.nio.fs.UnixCopyFile.move`
*does* fall back to copy+delete on `EXDEV`; `WindowsFileCopy.move` uses
`MoveFileEx(..., MOVEFILE_COPY_ALLOWED)`. Measured on two real device IDs (`hdiutil` ram disk vs
boot volume): `ATOMIC_MOVE` cross-device throws and the shipped `REPLACE_EXISTING` retry then
succeeds, leaving the complete file at the destination. A hand-rolled copy was measured too and is
strictly worse — it truncates the previous complete file and leaves a partial one at the final
name.

The evidence is recorded in `jvmTest/util/RenameTest.kt:14-38` and in the KDoc on both `actual`
implementations (`Rename.android.kt:11-22`, `Rename.jvm.kt:11-22`), precisely so it cannot drift
back into being a defect. **What is still unproven by CI is the cross-device case itself**,
because reproducing it needs a second filesystem. The shipped code is unchanged.

### Row 16 — `Copy.kt` is a dead duplicate

9 of 11 constants had **zero** references; all ten strings were byte-identical to
`DylanFailure.message()`, documented as the single source and actually used by toasts. A copy that
can silently drift.

Verified present: the 9 dead constants are gone; `OFFLINE` and `forCode` delegate to
`DylanFailure.message()`; `BUSY` stays a literal with the reason recorded (no `ErrorCode.BUSY`
exists — confirmed, the enum is `Models.kt:114-131`). Live references now: `OFFLINE` ×8, `BUSY` ×1,
`forCode` ×1 (`NowPlayingSheet.kt:432`). Nothing in the file is dead any more.

### Row 17 — `ENTITY_RE` cannot match uppercase `&#X41;`

The first branch was `#x`, lowercase only, so `&#X41;` matched no branch and stayed raw markup —
while the branch body carried `startsWith("#x", ignoreCase = true)`, making that `ignoreCase`
unreachable.

Verified present: the hex branch is widened to `#[xX]` (`Identity.kt:126`), which makes the
existing `ignoreCase` live. `EntityDecodeTest` grew 12 → 16 tests. "Pre-fix pattern fails 4 of
them" is a claim about a mutation run and was not reproduced here. The named-entity set and the
surrogate refusal are unchanged.

### Row 18 — `Clients.kt` KDoc contradicts its own retry comment

Claimed a 20 s ceiling is "well under the WS search budget's 2.5 s", but `maxRetries = 1` makes
the real worst case ~40.4 s — off by ~16×. Anything reasoning from that paragraph was reasoning
from a number that does not exist.

Verified present: the KDoc states the real worst case and why the WS budget cannot bound this
client (`Clients.kt:14-27`); the values are unchanged and only hoisted to named constants
(`:105-110`). The arithmetic — `20 s + 400 ms backoff + 20 s = 40.4 s` — is checkable from those
constants and the `maxRetries = 1` at `:57`.

### Row 19 — a stale KDoc asserted the opposite of the measurement

Said "WAL was never actually at risk… the failing PRAGMA was redundant", but the device showed
`journal_mode=rollback` with no `-wal` file.

Verified present: the comment now states the measurement (`journal_mode` read back `rollback`, no
`-wal`/`-shm` present) and why WAL goes through the factory instead of a pragma
(`DriverFactory.android.kt:261-266`).

### Row 20 — `FlowAdapter` swallows `Error`s

`FlowAdapter.subscribe` caught `Throwable`, so an `OutOfMemoryError` was routed to `onError` and
logged as "collect failed". The sibling's stated policy is that an OOM is "not a handler bug to be
logged and shrugged off", and the bridge is the boundary to a `@MainActor` Swift store — the
*worse* place for it.

Verified present: the catch is `Exception` (`FlowAdapter.kt:148`), below a rethrowing
`CancellationException` clause (`:146`); `onError` keeps its `Throwable` parameter but nothing in
the class sends an `Error` to it. `jvmTest/bridge/FlowAdapterErrorPolicyTest.kt` (5 tests) covers
both directions; the tests were read, not run.

### Row 21 — dead code in `ProbeMain`

P4 declared an unused `data class R(token, raw)` implying frames are parsed; `var etagP2` was
declared outside the lambda that owns it.

Verified present: `data class R` is deleted (HEAD had it at `:334`); `etagP2` is a local `val`
inside the P2 lambda (`:400`). "Check order verified identical to HEAD" is a run-level claim; the
two edits are individually visible in the diff.

---

## Tier 5 — quality gate was not reporting

| # | Issue | Status |
|---|---|---|
| 22 | detekt suppressed 245 of 313 findings via two baselines | PART-FIXED — and the count does not add up |
| 23 | `androidApp/lint-baseline.xml` is stale | OPEN — every line reference has moved |
| 24 | `Orchestrator.kt` `LargeClass` + `TooManyFunctions` refused | Refused, correctly — arithmetic drifted |

### Row 22 — the detekt baselines

**The `baseline = ...` wiring is gone** from both `build.gradle.kts` files (`shared/build.gradle.
kts:17-32`, `androidApp/build.gradle.kts:15-30`), so `detektBaseline` cannot regrow a suppression
list. The leak is also real: detekt's `MaxLineLength` baseline id carries an empty signature, so a
new over-long line in a baselined file was never reported.

**Two corrections to this row.**

1. **"Both baselines deleted" is not what the tree holds.** `config/detekt-baseline.xml` and
   `config/detekt-baseline-androidApp.xml` still exist as 105-byte shells containing only
   `<ManuallySuppressedIssues/>` and `<CurrentIssues/>`. They were *emptied*, not deleted. They
   are inert while nothing points at them, but `docs/build-and-gates.md:150-153` repeats
   "deleted" and should be fixed alongside this row.
2. **The count is unverifiable and internally inconsistent.** `maxIssues: 0`, no baseline, no
   `@Suppress`, no `excludes` — yet `Orchestrator.kt` is 1,617 lines declaring 83 functions, which
   is a live `LargeClass` *and* a live `TooManyFunctions` (threshold 25) with no suppression
   mechanism anywhere in `config/` or the build files. So either detekt is red, or something
   outside this repo's source silences those two. Resolve with `./gradlew detekt` before anyone
   repeats "313 → 2".

### Row 23 — the Android lint baseline

`abortOnError = true` now (`androidApp/build.gradle.kts:120-125`), so the baseline is the only
thing keeping `:androidApp:lintDebug` quiet. It is stale in every reference, not just the ones this
row used to name:

- **44 `<issue` entries, not 43.**
- `RestrictedApi` ×3 filed at `DylanMediaService.kt:195,196,196` — lines 195-196 are comments. The
  live calls are `ResolvableFuture.create` at 250-251 and `pending.set(…)` at 257.
- `AutoboxingStateCreation` ×3 filed at `SearchScreen.kt:76,79,82` — the live
  `mutableStateOf(1)` calls are at 626, 631, 636.
- **`UnsafeOptInUsageError` at `ExoPlayerEngine.kt:124`, which this row previously listed as
  still live, is also stale.** Line 124 is `when (reason) {`; the call it names, `.setLooper`, is
  at `:94`.

The dependency-version entries (`NewerVersionAvailable` ×23, `GradleDependency` ×8,
`AndroidGradlePluginVersion` ×2, `OldTargetApi` ×1) are Dependabot's business and are refreshed on
every bump. So is this baseline: regenerate it with `./gradlew :androidApp:updateLintBaseline` or
delete it, but do not leave a file that suppresses nothing while looking like it suppresses
something.

### Row 24 — the `Orchestrator` refusal

**Refused, correctly — but the arithmetic has drifted.** The class (`Orchestrator.kt:87`) now
declares **83** functions inside lines 87-1606, plus one top-level (`:1608`), so the gap to the
threshold of 25 is **58**, not 56.

The refusal itself still holds and the reason is unchanged: every candidate cluster reads 8+
`private` members of the class owning the single-permit state lane, and Kotlin `private` members
are invisible outside the class, so a file split necessarily widens shared mutable playback state
from `private` to `internal`. That is a visibility-semantics change to the most behaviour-critical
class in the app and it needs a scheduled multi-pass change with a compile gate, not a lint sweep.

---

## Blocked

| # | Item | Status |
|---|---|---|
| 25 | Player sheet overhaul | UNVERIFIED — needs device |
| 26 | Search infinite scroll | UNVERIFIED — needs device |
| 27 | Cache budget | UNVERIFIED — needs device |
| 28 | ktor issue not filed | OPEN — blocked on a human |

### Row 25 — the player sheet

Rewritten to one `AnchoredDraggableState`; the ghost surface was a measurement deadlock
(`measured = heightPx > 0f` while `heightPx` was assigned *below* the `if (!measured) return`).

Structure verified by reading. Anchors are pixels only and are built *with* the state rather than
patched in afterwards (`ui/NpPlayerSurface.kt:218-233`), so `offset` is a real number from the
first read. The entrance tween and the `Closed at 1f` normalised fallback described in
`docs/todo.md` §3 are both gone. There is a `SurfaceNestedScroll` connection (`:343-401`), a
`BackHandler` (`:259-261`), and a commit threshold tuned off Foundation's 50%-of-trip default to
28% capped at 200 dp (`:98`). The anchor vocabulary is `{Full, Mini}` (`ui/NpAnchor.kt:13-16`),
**not** the `{Hidden, Peek, Expanded}` that `todo.md` §3 proposes — that section needs rewriting.

**Nothing here tells you whether the sheet tracks the finger.** The four transitions that need
hardware are mini→full, full→mini, scroll-then-drag, and fling-overshoot. See the `.drill` note
under "What this document cannot tell you".

### Row 26 — search infinite scroll

Paging logic rewritten to per-section exhaustion after proving `hasMore` could not work (mixed
envelopes make `deliveredTotal` report "no more" after one page: a 20-row page holding 3 albums and
17 songs scores `kept (3) >= pageSize (20)` as false).

Verified by reading: three independent `*Exhausted` flags and page counters
(`ui/screens/SearchScreen.kt:624-638`); exhaustion *observed* rather than predicted by `foldPage`,
which asks only whether a page brought back rows the screen did not already hold (`:823-833`);
scroll-driven prefetch with a live `canExtend()` guard read at call time rather than captured
(`:321-332`); and the "Show more" button is gone. Compiles-by-reading only — never seen paging.

### Row 27 — the cache budget

Inverted to bytes-primary: 2 GiB target / 2.25 GiB ceiling / 2415 derived rows.

Verified by arithmetic: `CACHE_TARGET_BYTES = 2 GiB` (`AppConfig.kt:217`);
`CACHE_HEADROOM_DIVISOR = 8` gives `cacheMaxBytes = 2,415,919,104` (`:95`, `:225`);
`cacheMaxFiles = cacheMaxBytes / 1_000_000 = 2415` (`:106`, `:209`); the pinned row budget is
`2415 × 0.75 = 1811` (`cache/CacheManager.kt:88`); `diskFloorBytes = 500 MiB` (`AppConfig.kt:251`).
Both UIs divide by `cacheMaxBytes` (`LibraryScreen.kt:345`, `SettingsScreen.kt:179-188`), so the
number on screen and the number the LRU pass enforces are the same value by construction.

**453 tests green is a historical run result, not reproduced here.** The open question stands:
2.25 GiB on a device with 8.1 GB free is a large fraction of the volume, and `diskFloorBytes` is
checked per download rather than against total device headroom.

### Row 28 — the ktor issue

Needs an account. No account here; it cannot be filed from this environment. Blocked on a human,
unchanged.

---

## Also still parked

Stale references corrected; the items themselves are unchanged.

- ~~`H1` `LazyDatabase.cold` reachability~~ — **still open, but the line reference is dead.** The
  class is `di/LazyDatabase.kt:36`, `cold` is `:48`, `open()` is `:70`; `AppRoot.kt:150` now holds
  a `when` over the nav stack, not a gate. The caller-side gate is still absent, so the mechanism
  is fixed and reachability is unproven.
- `M2`/`M9` in `DylanMediaService` — the cited lines (`:421-447`, `:483-500`) now hold
  `buildMediaButtons` and `computeResumptionItems`. What M2 and M9 named is no longer
  identifiable from them. Unowned and unmoved.
- ~~`F-1` non-transactional `1.sqm`~~ — **the reason recorded here was wrong.** It does not need a
  device: it was measured on the JVM by `jvmTest/fuzz/M2MigrationTest.kt:321-371`, which walks
  every kill point through the real `Dylan.Schema.migrate` and finds every one but k=0 wedges the
  store permanently (`user_version` is written only after `migrate` returns, and the generated
  `DylanImpl.kt` has no transaction wrapper). The plan's one-line fix is demonstrated in a test
  (`aRolledBackMigrationLeavesAStoreThatReopensCleanly`) and is **not applied in production**. The
  chain is now 47 statements, not the 33 the notes quote.
- Constant tuning (download backoff, timeouts) — still needs real network timing.
- `P3.1` `wipe()` removal — still parked.
- Repo cleanup: stale rows in `docs/codebase-audit.md` and `tools/probe-results.md`, plus
  `docs/build-and-gates.md`, whose lines 19 and 138-140 still describe the detekt baseline as the
  sanctioned mechanism for accepted debt — which the tree contradicts.
- Network/provider review agent — never delivered; row 9 sits in its area.

---

## What this document cannot tell you

Listed so the next reader does not mistake silence for a pass.

1. **Whether anything compiles.** No build was run. Every "FIXED" is a reading of source.
2. **Whether any test passes.** No test was run. Where a row says "code + test", the test exists
   and was read; its greenness is a claim carried over from an earlier session. That covers
   `TrackIdentityTest` (row 8), `EntityDecodeTest` (rows 10, 17), `DownloadEngineTerminalStateTest`
   (row 1), `DriverFactoryWipeGateTest` (row 2), the two `Transfer` channel guards (row 5),
   `FlowAdapterErrorPolicyTest` (row 20), `RenameTest` (row 15) and `M2MigrationTest` (F-1).
3. **Any count that came out of a run.** "313 findings → 2" (row 22), "453 shared tests green"
   (row 27), "10/10" (row 8), "12 mutation-proven tests" (row 10), "142/150 vs 0/150" (row 5),
   "output byte-identical to before" (row 11) and "mutation-tested both arms" (rows 11, 12) are
   all historical. Row 22 is worse than historical: it is contradicted by the code.
4. **Anything requiring a device or an emulator.** Rows 25, 26 and 27 in full; the device
   measurements behind rows 3, 4, 5, 9 and 15; and specifically whether the player sheet tracks
   the finger, whether scroll paging actually fetches, and whether the cache actually evicts on a
   real volume.
5. **Anything requiring Xcode or a Kotlin/Native toolchain.** Row 13 is marked *uncompiled*. iOS
   `verify()`, `IosGraph`, and every Swift-side claim in `docs/feedback-ledger.md` are in the same
   position.
6. **Anything about library artifacts.** The deprecation timing asserted for row 7
   (`enableEdgeToEdge`/`SystemBarStyle` deprecated after activity 1.12.4) is a claim about an AAR,
   not about this repo's source.
7. **Whether the app currently works end to end.** No install, no launch, no network call.

**One ambiguity, recorded rather than resolved.** The untracked `.drill/` directory holds
`uiautomator` hierarchy dumps from `app.dylan.player` at 1080×2400, screenshots named
`mini_bar.png`, `full_player.png`, `np_open.png`, `flash_1..8.png`, `after_swipe.png`, and `.bak`
copies of `AppRoot.kt` and `NpPlayerSurface.kt`, all timestamped **2026-10-04 01:19–01:48** —
after this document's previous "last updated" of 2026-10-03 and after the "phone dropped off USB"
note in `docs/todo.md`. So *something* ran on hardware after that note was written, and no verdict
from it was recorded anywhere. Rows 25-27 are therefore marked "UNVERIFIED — needs device" **in
this document**, which is not the same claim as "never run". Whoever has the device should either
record what `.drill` showed or delete it.
