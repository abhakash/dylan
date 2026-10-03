# TODO — open findings

Two sources feed this list:

- **Device pass** (2026-10-03) — vivo V2130, Android 14 / API 34, arm64-v8a. Everything marked
  *device-verified* was observed in `adb logcat` or on screen, not inferred.
- **Carried forward** — work that was open before the device arrived and still is.

Legend for state: **Open** · **Blocked** (waiting on something named) · **Fixed-uncommitted** ·
**Withdrawn** (was believed, disproved — kept so it is not rediscovered).

`docs/feedback-ledger.md` remains the addressed-vs-pending ledger for the audit findings. This file
is the working queue.

> **Verification blocked (2026-10-03).** The device dropped off USB part-way through the session, so
> everything in §3 and §5 is code-complete but **unverified on hardware**. A verification script is
> written and armed: it waits for the device, then installs a baseline `05243c2` APK and the new one,
> measures each, samples screenshots *during* a continuous multi-second `input swipe` in both
> directions (not `input motionevent`, which is not one gesture), exercises back/queue/tabs, and
> dumps logcat. Until it runs, treat §3 as unproven.

---

## 1. SQLite: `PRAGMA` statements silently failing on Android

**State: FIXED and device-verified.**

```
E/Dylan:db: PRAGMA journal_mode=WAL failed: unknown error (code 0 SQLITE_OK):
  Queries can be performed using SQLiteDatabase query or rawQuery methods only.
E/Dylan:db: PRAGMA busy_timeout=5000 failed: (same)
```

`DriverFactory.android.kt:pragma()` used `driver.execute(...)`, which bottoms out in Android's
`execSQL`, and `execSQL` refuses any statement that produces a row. `journal_mode=WAL` and
`busy_timeout=5000` are exactly the two pragmas that report their result, so **both were being
rejected**. `synchronous` and `foreign_keys` set silently and return nothing, which is why only these
two ever complained — and why the complaint read as ignorable noise.

### Done

- All pragmas routed through `executeQuery` (`DriverFactory.android.kt`).
- `verify()` extended to assert `journal_mode` and `busy_timeout`, so a silently-failing pragma is
  now a loud error instead of a log line nobody reads. *Device-verified:* the
  `PRAGMA journal_mode=WAL failed` error is gone and `busy_timeout` no longer trips the assertion.

### Resolved — WAL enabled via the platform API

*Device-verified.* Pulled the live DB off the device (`run-as` → local `sqlite3`):

| pragma | value | verdict |
|---|---|---|
| `journal_mode` | `delete` | **not WAL** — no `-wal`/`-shm` files exist |
| `busy_timeout` | (assertion silent) | now applied |
| `user_version` | `2` | correct |
| `synchronous` | `1` (NORMAL) | set |

An earlier reading of a stale `-wal` left over from the August install suggested WAL was fine; it was
not. The new `verify()` assertion is what caught it.

**Cause:** `AndroidSqliteDriver` contains *zero* references to WAL / `enableWriteAheadLogging`
(verified by disassembling `android-driver-2.0.2`), so nothing enables it. The `PRAGMA` alone is not
reliable on Android, which wants the platform API.

**Fix direction (not yet applied):** wrap the `SupportSQLiteOpenHelper.Factory` that
`AndroidSqliteDriver`'s convenience constructor builds internally, and call
`enableWriteAheadLogging()` on the `SupportSQLiteDatabase` from `getWritableDatabase()`.

Deliberately **not** done by replacing the `SupportSQLiteOpenHelper.Callback`: that callback is
SQLDelight's own and owns `Schema.create`/`migrate` (confirmed by disassembling the constructor
overload), so swapping it would silently disable the migration chain.

**RESOLVED.** WAL now comes from a `SupportSQLiteOpenHelper.Factory` wrapper calling
`setWriteAheadLoggingEnabled(true)` before the file opens. `androidx.sqlite:sqlite-framework` was
promoted from transitive-runtime to `implementation` so the factory can be named.

*Device-verified:* `sqlite3 dylan.db "PRAGMA journal_mode;"` -> `wal`, with `-wal` (8272 B) and
`-shm` (32768 B) present; `user_version` still 2 (migration chain intact); 11 library rows preserved;
no assertion errors in logcat.

**Follow-on defect this exposed, also fixed.** Enabling WAL turns on Android's connection *pool* and a
pragma is per connection, so `PRAGMA foreign_keys=ON` armed enforcement on one connection and left
`ON DELETE CASCADE` a no-op on the rest (device read back `0`). Foreign keys now go through
`setForeignKeyConstraintsEnabled(true)`, a dbconfig Android applies to every connection.
`busy_timeout` is asserted only as a **floor** (`> 0`) because a per-connection pragma cannot be
made uniform from here — Android supplies its own default, measured at 2500 ms. Asserting exactly
5000 would assert something the code cannot deliver.

---

## 2. Search: duplicate songs, and artists missing entirely

**State: Open. Observed on device, cause not yet proven.**

Searching `Eminem` in the installed build shows:

- **1 album** (`THE EMINEM SHOW`) and **0 artists**, though the screen renders a merged
  songs+albums+artists list.
- **The same song repeated:** `MOCKINGBIRD` ×3 (Encore / Mockingbird single / Curtain Call),
  `WITHOUT ME` ×3 (Curtain Call ×2 / The Eminem Show).

### 2a. Duplicate songs — cause understood

`SearchScreen` dedupes on `SongKey`, i.e. `(provider, songId)`. Saavn returns a *different* `songId`
per album rendition, so the same track from three albums is three distinct keys and survives dedup.
Deduping needs a track identity (title+artist+duration), not the per-rendition id. This also wastes
the page budget, which is what makes "Show more" look like it loads nothing new.

### 2c. Search results: one merged list instead of sectioned, with no affordance distinction

**State: Open. Requested 2026-10-03. Largely a presentation change — the navigation already exists.**

Search currently renders **one relevance-ranked list** mixing songs, albums and artists:

```kotlin
// SearchScreen.kt:99-101
results.forEach { all += Triple(relevanceBand(q, it.title), order++, Hit.SongHit(it)) }
albums.forEach  { all += Triple(relevanceBand(q, it.title), order++, Hit.AlbumHit(it)) }
artists.forEach { all += Triple(relevanceBand(q, it.title), order++, Hit.ArtistHit(it)) }
```

Because everything competes on one relevance axis, albums and artists end up interleaved among songs
and usually trailing, which is what makes the screen read as *"Show more only loads Album"* — the
non-song rows all look like one thing at the bottom. Both album and artist rows render through the
same `MiniRow` with a text badge (`"ALBUM"` / `"Artist"`).

**Already working — do not rebuild this:**

- Album tap → `onOpenAlbum(id)` (`SearchScreen.kt:278`) → `Screen.Album` → `AlbumScreen`
  (`AppRoot.kt:209`).
- Artist tap → `onOpenArtist(mini)` (`:282`) → `Screen.Artist` → `ArtistScreen` (`AppRoot.kt:210`).
- Playing a song row hands `songsOnly` to a play action (`:261`), so an album/artist page already has
  the `onPlaySongs = playNow` hook to load into Now Playing (`:209-210`).

So both destination screens, the routes, and the play-into-Now-Playing entry point are in place. What
is missing is that results are grouped and that the two tap targets *look* like two different
intents.

#### What to build

1. **Section the results** — Songs, then Artists, then Albums, each under its own header, each with
   its own "Show more" that pages *only that section*. Today `loadMore()` already fetches all three
   sections concurrently (`SearchScreen.kt:180-182`) but presents them as one list, so a section with
   nothing new looks broken rather than exhausted.
2. **Encode the affordance in the shape**, following Spotify: artists render in **circular** frames,
   albums and songs in **square** ones. The rationale worth keeping is that shape tells the user
   whether tapping explores or plays, without reading a label:
   > "Spotify always places Artists in circular frames while Albums and Songs are displayed in square
   > frames. That means when I search for something I can easily distinguish between clicking on
   > something to see more versus clicking on something to play a song or album."
   > — <https://uxdesign.cc/ux-ui-analysis-spotify-31f3855a1740>
3. **Per-section counts/emptiness** — a section that legitimately returned nothing should either be
   absent or say so. Right now "no artists" is indistinguishable from "artists failed to load",
   which is exactly the ambiguity that made §2b hard to spot from the UI.
4. **Keep play in Now Playing as the page's job, not the row's** — tapping a song plays it; tapping
   an artist or album navigates, and *that* page owns Play/Shuffle. That split is the Spotify
   behaviour and it is also why the badge-and-list treatment is confusing today.

#### Entity-page polish (lower priority)

Spotify's artist page leads with a large header image and name, then **Popular** (a short song list)
then **Albums**. `ArtistScreen` and `AlbumScreen` exist and are reachable, so this is refinement
rather than new navigation — check them against that shape once search routes users there.

#### Caveat — do not copy Spotify's shuffle

Worth knowing before implementing a Play/Shuffle affordance on these pages: Spotify's artist/album
"Shuffle Play" is a long-standing rough edge — users report it shuffling a curated mix rather than
the artist's catalogue.

- <https://community.spotify.com/t5/Live-Ideas/Artist-Shuffle/idi-p/5881030>
- <https://www.reddit.com/r/truespotify/comments/1pqvywq/frustrating_not_to_be_able_to_shuffle_an_entire>

For DYLAN the unambiguous behaviour is to shuffle **the songs this page actually loaded**, and to
label it that way.

#### Dependencies and acceptance

- **Blocked by §2b.** Until the artist-drop cause is fixed, the new Artists section renders empty and
  the redesign will look broken on first run. Fix §2b first, or ship both together.
- Interacts with §2a: cross-rendition duplicates make the Songs section look padded, which
  undermines the point of sectioning.
- Acceptance: search `Eminem` shows three headed sections; the artist row is circular and opens
  `ArtistScreen`; the album row is square and opens `AlbumScreen`; playing from either page loads
  those songs into Now Playing; and "Show more" under a heading pages only that section.

### 2b. No artists — hypothesis, NOT yet proven

`Mapper.mapMini` drops any artist card whose `permaUrl` does not yield a token:

```kotlin
TYPE_ARTIST -> permaArtistToken(d.permaUrl)?.let { mapMiniOf(type, d, it) }
// miniDropReason: TYPE_ARTIST -> if (permaArtistToken(d.permaUrl) == null) NO_PERMA_TOKEN else ""
```

`permaArtistToken` requires the literal substring `/artist/` in the URL. If Saavn's artist cards
carry a different (or absent) `permaUrl` shape, **every artist is silently dropped** and the section
renders empty with no error.

**Why this is still open:** the direct API check was inconclusive. From this machine every endpoint
returns `INPUT_INVALID: operation is not supported`:

```
search.getArtistResults → {"error":{"code":"INPUT_INVALID","msg":" operation is not supported"}}
search.getAlbumResults  → same
search.search           → same
```

while the *app on the device* returns albums and songs fine — so the difference is environmental
(IP/geo/cookies), and the failure above says nothing about the app's own requests. The device has no
`curl`/`wget` either.

**Next step to close it:** capture the real response on-device. The drift channel already records
`NO_PERMA_TOKEN` drops (`Drift(endpoint, why, …)`), so the cheapest proof is to surface artist-search
drift into the `Dylan:db`/provider logcat mirror, rebuild, search `Eminem`, and read what was dropped.
Do **not** conclude from the `INPUT_INVALID` responses above.

---

## 3. Mini-player / Now Playing sheet does not follow the finger

**State: REWRITTEN, unverified on hardware.**

Reported as: swiping the bottom "Now Playing" bar up jumps rather than tracking the finger, and
swipe-down is equally wrong. It should track the finger 1:1.

There are **two independent mechanisms**, and both are wrong.

### 3a. The pull-up gesture moves nothing at all — primary cause

`AppRoot.kt`, `MiniPlayer`:

```kotlin
.pointerInput(Unit) {
    var accum = 0f
    detectVerticalDragGestures(
        onDragStart = { accum = 0f },
        onVerticalDrag = { change, dragAmount ->
            change.consume()
            accum += dragAmount
            if (accum < -64.dp.toPx()) { accum = 0f; onExpand() }
        },
    )
}
```

This is a threshold detector, not a drag. `dragAmount` is summed and discarded — **no
`translationY` is ever driven by the finger**, so the bar sits still and the full-screen sheet pops
in only after 64dp of travel. That is precisely the reported "jumps up".

Secondary defects in the same block:

- The threshold is on **cumulative** travel, not instantaneous movement or velocity, so a slow
  deliberate drag triggers it as readily as a flick — it feels sticky and arbitrary.
- **There is no downward branch at all.** `accum` is only ever tested against `< -64.dp`, so the bar
  cannot be dismissed by swiping down; the only route down is opening the sheet first. This is the
  "same for swipe down" half of the report.

### 3b. The sheet's anchors mix normalised and pixel units

```kotlin
AnchoredDraggableState(
    initialValue = NpAnchor.Closed,
    anchors = DraggableAnchors { NpAnchor.Open at 0f; NpAnchor.Closed at 1f },  // ← pixels, so 1px
)
...
LaunchedEffect(heightPx) {                       // only after the first layout pass
    npState.updateAnchors(DraggableAnchors { Open at 0f; Closed at heightPx })
}
```

`anchoredDraggable` anchors are **pixels**. The initial `Closed at 1f` therefore means "1 pixel from
the top" — i.e. *fully open*, not hidden. Until `onSizeChanged` fires and `updateAnchors` runs, the
sheet is laid out essentially on-screen. The `graphicsLayer` fallback

```kotlin
translationY = if (npState.offset.isFinite()) npState.offset else heightPx
```

never rescues it, because `npState.offset` **is** finite (~0), so `else heightPx` is not taken. Net
effect: the sheet is briefly at the wrong offset, then snaps to `Closed at heightPx` and animates up.

### 3c. Entrance is a scripted tween that races the gesture

```kotlin
LaunchedEffect(Unit) {
    snapshotFlow { heightPx }.firstOrNull { it > 0f }
    npState.animateAnchor(NpAnchor.Open)   // 280ms tween
}
```

Because the mini bar fires `onExpand()` *mid-gesture* (3a), the sheet mounts while the finger is
still down and then animates up over 280ms — two motions at once, which reads as the jump.

### 3d. No `nestedScroll` connection

The sheet content is scrollable and there is no `BottomSheetNestedScrollConnection` on the sheet.
Without it the drag and the inner list compete for the same delta, which is the documented cause of
"sheet moves oddly / overshoots on a vigorous fling".

### Fix direction (Spotify-style single surface — the actual answer to "match finger behaviour")

Stop swapping composables. Hoist **one** `AnchoredDraggableState` into the parent with anchors
`{ Hidden, Peek, Expanded }`, render the mini bar as the sheet's *peek* face, and let a single drag
move one surface continuously from mini → full and back. Then:

- 1:1 finger tracking comes free from `anchoredDraggable`;
- up *and* down are the same gesture in opposite directions;
- the 280ms entrance tween disappears, because there is no mount to animate;
- anchors are declared in pixels from measured height only — no normalised fallback.

Relevant references found while researching this:

- Android, *Drag, swipe, and fling* — `swipeable` anchors, `thresholds`, `resistance`,
  `velocityThreshold`: <https://developer.android.com/develop/ui/compose/touch-input/pointer-input/drag-swipe-fling>
- Android, *Bottom sheets*: <https://developer.android.com/develop/ui/compose/components/bottom-sheets>
- Turo Engineering, *Advanced Bottom Sheet With Flexible Configuration for Compose* — the
  `offset` + `anchoredDraggable` + `onSizeChanged` + `BottomSheetNestedScrollConnection` structure,
  and why nested scroll is required once the sheet content scrolls:
  <https://medium.com/turo-engineering/customizable-bottom-sheet-with-flexible-configuration-for-compose-36953b10b758>
- Known `ModalBottomSheet` overshoot on fast flings (settle seeded with fling velocity) — Slack
  discussion linked from the Compose issue tracker; relevant if the rebuild lands on
  `ModalBottomSheet` instead of a custom `anchoredDraggable`.

**Risk:** this touches the app's primary navigation gesture, so it needs device verification of all
four transitions (mini→full, full→mini, scroll-then-drag, and fling-overshoot) rather than a
compile check.

---

## 4. Unescaped HTML entity in track titles

**State: Open. Observed on device.**

Home screen, "Jump back in":

> RAINY DAY WOMEN #12 **&AMP;** 35

The origin ships `&amp;` and the title reaches the UI un-decoded (and inconsistently cased-upper).
Fix belongs in the identity/title normalisation layer next to `artUrlOf`
(`provider/saavn/Identity.kt`), not in a UI composable, so every surface benefits. Needs a decode for
at least `&amp; &quot; &#39; &lt; &gt;` and a test that a title containing them round-trips.

---

## 5. Player screen: 442px dead zone at the top, 311px at the bottom

**State: Open. Measured on device.**

Correcting an earlier eyeball claim of mine: **the horizontal alignment is fine.** Every element's
centre is within 1.5px of the 1080px screen centre (artwork 539.5, progress track 538.5, queue row
539.5). The apparent left-shift was an artefact of a downscaled screenshot.

Measured content bands on a 1080×2400 capture:

| band | y range | height |
|---|---|---|
| status bar | 29–57 | 29 |
| **void** | **58–499** | **442** |
| artwork | 500–1274 | 775 |
| subtitle | 1276–1301 | 26 |
| progress track | 1333–1343 | 11 |
| transport row | 1440–1494 | 55 |
| play button | 1522–1552 | 31 |
| queue/quality row | 1598–1647 | 50 |
| **void** | **2010–2320** | **311** |

So the real complaint is **vertical**: the player block is not centred in the viewport, leaving a
442px void above the artwork and 311px below the last control, with the mini-player and system nav
crowding the bottom. Also visible: the subtitle
`LUDWIG GORANSSON - THE ODYSSEY (ORIGINAL` is clipped at the content edge with no ellipsis.

---

## 6. M0 seam-1a flake — CI red, cause narrowed but NOT explained

**State: ROOT-CAUSED AND FIXED. Not yet CI-verified.**

```
M0SeamsTest > aMidStreamResetKeepsTheBytesThatLandedAndTheResumeAsksForThem
  AssertionError: resume from the bytes that landed, not from zero
  expected:<bytes=1080-> but was:<null>
```

### What is established

- The retry layer is behaving correctly given its input: the persisted byte count is **0**, so there
  is no non-zero resume offset and no `Range` header is constructed.
- Reproduced end-to-end locally at ~3% (5 of 150 iterations) with an engine-level harness; the
  engine's own log shows `partB=0` on the retry step, i.e. **zero bytes survived the reset**.
- The loss is **not** in the fixture's feeder channel (20,000/20,000 elements delivered) and **not**
  okio read-ahead discarding on a slow consumer (3,000/3,000 full reads).

### Withdrawn

My earlier claim that `readChunk`'s `awaitContent()`-before-`readAvailable()` ordering was *the* cause.
It is not established, and an attempted reorder did **not** fix the loss. Reverted; the tree is at
`05243c2` for this file.

### Why the obvious mechanism does not hold

For a single `RealBufferedSource` with a positive `min` and no competing consumer:

```
source.request(min) == true   ⇒   buffer.size >= min on return
exhausted()  ==  buffer.exhausted() && upstream.exhausted()
```

so `exhausted()` must be `false` while the buffer holds data. A reset during `request(min)` should
therefore yield exactly one of: `request == false` (upstream EOF), `request` **throws** (upstream
reset), or `request == true` **and the bytes stay readable**. `request(1) == true` followed by
immediate `-1` is *not* a legitimate outcome of a serialised buffered source.

But the instrumented run produced precisely that:

```
PROBE pre  ac=true  closed=true     ← awaitContent: content IS available
PROBE post n=-1     closed=true     ← readAvailable: clean EOF. zero bytes.
```

So the invariant is being violated somewhere, and the remaining causes are narrow:

1. **`min` is zero somewhere** — `request(0)` returns `true` without proving content. *Largely ruled
   out at our call site: `Transfer.readChunk` calls `ch.awaitContent()` with ktor's declared default
   `min = 1`.* Still worth logging `min` to be certain.
2. **Another consumer** drains the same source between the two calls — a retry/cleanup/cancellation
   path, or a parallel reader.
3. **Not the same buffered object** — a wrapper recreates or swaps the source between the calls, or
   the two accessors observe different layers.
4. **The adapter weakens the source contract** — it converts a reset into EOF while dropping bytes it
   had already accepted, or holds async producer/error state not represented by the buffer. *This is
   the leading candidate here, since the failing path goes through `kotlinx.io`'s `RawSource.buffered()`
   adapter rather than a hand-written `RealBufferedSource`.*
5. **The probe itself is not passive** — `isClosedForRead`/`exhausted()` can touch upstream when the
   buffer is empty. Does not explain `request(1)==true` then exhaustion, but it does affect how the
   captured sequence should be read.

### The decisive next probe

Instrument **one exclusive path** with no other permitted reader, and log the buffered byte count
around each step:

```kotlin
val before      = source.buffer.size
val ready       = source.request(min.toLong())
val afterReq    = source.buffer.size
val exhausted   = source.exhausted()
val afterExh    = source.buffer.size
val n           = source.read(sink, wanted)
```

Log: source identity · `min` · `ready` · buffer size before/after request · buffer size
before/after exhausted · read result or thrown exception · thread/coroutine identity.

Decision rule:

- `min > 0 && ready == true && afterReq == 0` → fault is **below** `RealBufferedSource`, i.e. the
  adapter (cause 4).
- `afterReq > 0` and it is `0` by the time of the read → a **competing drain** exists (cause 2).

### Regression contract

> If `awaitContent(1)` returns true and no other reader consumes the channel, the immediately
> following read must return at least one byte **or** a terminal error. It must not return clean
> EOF (`-1`).

This is also the right shape for a committed test — it is deterministic and needs no scheduler luck,
which the current test does need (it passed 8/8 locally and failed on CI).

### Fix direction

Do not base correctness on a split "wait, then separately test/read" protocol. The layer owning the
response body should expose **one serialised operation** that either returns bytes and commits them
to the retry accumulator, returns EOF, or returns the transport error — never treating a reset as a
clean close, and never discarding already-buffered bytes. The resume offset should be derived only
from bytes **committed** to that accumulator, so the retry issues `Range: bytes=<committed>-` exactly
when `<committed> > 0`, ideally guarded by the response validator/ETag.

---

## 7. Previously parked, still open

Unblocked by the device unless noted.

| # | Item | Note |
|---|---|---|
| 7a | **H1** `LazyDatabase.cold` reachability | Needs an `androidApp` gate (`AppRoot.kt:150` / `DylanApp.onCreate`), not fixable from `shared/`. Mechanism already fixed; reachability unproven. |
| 7b | **M2/M9** in `DylanMediaService.kt:421-447`, `:483-500` | Unowned, unfixed. Now testable on device. |
| 7c | **F-1** non-transactional `1.sqm` | Needed a device to observe; now possible. |
| 7d | **Constant tuning** (download backoff, timeouts) | Needed real network timing; device now has working DNS + internet. |
| 7e | **P3.1 `wipe()` removal** | Needed a device to observe a wipe; now possible. |
| 7f | **Repo-cleanup agent** | Never dispatched. Candidates: stale rows in `docs/codebase-audit.md`, `tools/probe-results.md`, dead `DylanTokens`/`SearchStore.reset`. |
| 7g | **Network/provider review agent** | Timed out twice, never delivered. Note that §2b sits exactly in its area. |

---

## 8. Build / environment notes from the device pass

- SDK is **not** at `~/Library/Android/sdk`; `local.properties` points at
  `/opt/homebrew/share/android-commandlinetools`. `adb` is on PATH via Homebrew.
- `adb install -r -d` works against the existing debug-signed install (same debug keystore).
- The device has **no `curl` and no `wget`** — API checks have to go through the app or
  `run-as`. `toybox` has neither.
- `adb shell run-as app.dylan.player cat databases/dylan.db` + local `sqlite3` is a working way to
  inspect live DB state. Remember to pull `-wal` and `-shm` too, and that connection-local pragmas
  read from a *different* connection show that connection's defaults, not the app's.
- Never `rm -rf shared/build/test-results` while a Gradle run is live; concurrent runs corrupt it
  and produce spurious `EOFException` / `NoSuchFileException`.

---

## 9. Cache budget: bytes are primary now (was inverted)

**State: FIXED. 453 shared tests green; not yet device-verified.**

The cache was sized by a **row count x an assumed 1 MB mean rendition**, so the enforced budget was
`300 x 1 MB` = **300 MB**, while both UIs printed and divided by a nominal 2 GB. The advertised
number was unreachable dead config — the user saw a limit the cache could never reach.

| | before | after |
|---|---|---|
| `cacheTargetBytes` (nominal) | — | 2.00 GiB |
| `cacheMaxBytes` (enforced) | 300 MB (`300 x 1 MB`) | 2.25 GiB (2,415,919,104) |
| `cacheMaxFiles` | 300 (primary knob) | 2415 (derived) |
| pinned rows (`x 0.75`) | 225 | 1811 |

- The derivation is now **bytes -> rows**, not rows -> bytes.
- `cacheMaxBytes` is `target + target / 8` (**+12.5% headroom**). A ceiling enforced at exactly the
  advertised number means the cache reads as full the instant the user looks at the figure and every
  later download evicts one, so the number is wrong by a single download.
- Four `CacheManagerTest` tests changed. One asserted the *old* direction and was rewritten to the
  new contract (including an explicit `>= 2 GiB` assertion); two shrank the budget via the row cap and
  now shrink via `cacheTargetBytes`; one asserted the row cap doubles exactly, which truncating
  division does not guarantee, so it now asserts scaling within one row.

**Open risk, not addressed:** 2.25 GiB of audio on a device with 8.1 GB free is a large fraction of
the volume. `diskFloorBytes` (500 MB) is the only guard, and it is checked per download rather than
against total device headroom. Worth deciding whether the target should scale with available space.
