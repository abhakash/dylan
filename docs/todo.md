# TODO — open findings

Two sources feed this list:

- **Device pass** (2026-10-03) — vivo V2130, Android 14 / API 34, arm64-v8a. Everything marked
  *device-verified* was observed in `adb logcat` or on screen, not inferred.
- **Carried forward** — work that was open before the device arrived and still is.

Legend for state: **Open** · **Blocked** (waiting on something named) · **Fixed-uncommitted** ·
**Withdrawn** (was believed, disproved — kept so it is not rediscovered).

`docs/feedback-ledger.md` remains the addressed-vs-pending ledger for the audit findings. This file
is the working queue. `docs/issues.md` is the ranked index and is the file to read first.

> **Last integrity pass: 2026-10-08.** This document had drifted badly: several sections described
> code that no longer exists, one (§12.7) recorded a defect that had been measured *not* a
> defect, and the "needs a device" framing on three rows was wrong. Every section below now carries
> a **Current state** line derived from the working tree. Sections marked *historical* are kept
> because the reasoning is worth not re-deriving; they do not describe current behaviour.
>
> No build, no test run and no device were used in this pass. "verified by reading" means the code
> was read; it does not mean anything was compiled or executed.

> **Verification blocked (2026-10-03).** The device dropped off USB part-way through the session, so
> everything in §3 and §5 is code-complete but **unverified on hardware**. A verification script is
> written and armed: it waits for the device, then installs a baseline `05243c2` APK and the new one,
> measures each, samples screenshots *during* a continuous multi-second `input swipe` in both
> directions (not `input motionevent`, which is not one gesture), exercises back/queue/tabs, and
> dumps logcat. Until it runs, treat §3 as unproven.
>
> **Addendum (2026-10-08).** The untracked `.drill/` directory holds `uiautomator` hierarchy dumps
> from `app.dylan.player` at 1080×2400, screenshots (`mini_bar.png`, `full_player.png`,
> `np_open.png`, `flash_1..8.png`, `after_swipe.png`) and `.bak` copies of `AppRoot.kt` and
> `NpPlayerSurface.kt`, all timestamped 2026-10-04 01:19–01:48 — *after* this note. So something ran
> on hardware after the note was written, and no verdict from it was recorded. The honest statement
> is therefore "no verdict recorded", not "never run".

---

## 1. SQLite: `PRAGMA` statements silently failing on Android

**Current state: FIXED, device-verified by an earlier session, re-verified by reading on
2026-10-08.** *(This section was already accurate; the ordering of its "Fix direction (not yet
applied)" and "RESOLVED" subsections was misleading and has been corrected.)*

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

- All pragmas routed through `executeQuery` (`DriverFactory.android.kt:268-274`).
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

**Fix direction, applied as `WalOpenHelperFactory`** (`DriverFactory.android.kt:129-154`): the
factory that `AndroidSqliteDriver`'s convenience constructor builds internally is wrapped, and
`setWriteAheadLoggingEnabled(true)` is called on the `SupportSQLiteDatabase` before the file opens.
`androidx.sqlite:sqlite-framework` was promoted from transitive-runtime to `implementation` so the
factory can be named.

Deliberately **not** done by replacing the `SupportSQLiteOpenHelper.Callback`: that callback is
SQLDelight's own and owns `Schema.create`/`migrate` (confirmed by disassembling the constructor
overload), so swapping it would silently disable the migration chain.

*Device-verified:* `sqlite3 dylan.db "PRAGMA journal_mode;"` -> `wal`, with `-wal` (8272 B) and
`-shm` (32768 B) present; `user_version` still 2 (migration chain intact); 11 library rows preserved;
no assertion errors in logcat.

**Follow-on defect this exposed, also fixed.** Enabling WAL turns on Android's connection *pool* and a
pragma is per connection, so `PRAGMA foreign_keys=ON` armed enforcement on one connection and left
`ON DELETE CASCADE` a no-op on the rest (device read back `0`). Foreign keys now go through
`setForeignKeyConstraintsEnabled(true)` (`:86`), a dbconfig Android applies to every connection.
`busy_timeout` is asserted only as a **floor** (`> 0`) because a per-connection pragma cannot be
made uniform from here — Android supplies its own default, measured at 2500 ms. Asserting exactly
5000 would assert something the code cannot deliver.

---

## 2. Search: duplicate songs, and artists missing entirely

**Current state: §2a FIXED (uncommitted). §2b OPEN — cause unproven. §2c OPEN — not started.**

Searching `Eminem` in the installed build showed 1 album, 0 artists, and `MOCKINGBIRD` ×3.

### 2a. Duplicate songs — **FIXED**

`SearchScreen` deduped on `SongKey`, i.e. `(provider, songId)`. Saavn returns a *different songId*
per album rendition, so the same track from three albums was three distinct keys and survived dedup.
That also wasted the page budget, which is what made "Show more" look like it loaded nothing new.

Fixed at the page boundary, in `provider/saavn/RenditionIdentity.kt`: `trackIdentity` is normalised
title + primary artist + duration in whole seconds (NUL-separated), `decodeSongPage` folds through
`foldRenditions` and records every collapse as `RENDITION_DUPLICATE` drift, and the search list
folds across pages through `dedupeRenditions`. A card with no usable identity is **never**
collapsed — a song that disappears is worse than a duplicate row. Documented limitation: a
rendition whose *title* the origin rewrote per album does not collapse.

### 2b. No artists — **OPEN, cause unproven**

`Mapper.mapMini` drops any artist card whose `permaUrl` does not yield a token:

```kotlin
TYPE_ARTIST -> permaArtistToken(d.permaUrl)?.let { mapMiniOf(type, d, it) }
// miniDropReason: TYPE_ARTIST -> if (permaArtistToken(d.permaUrl) == null) NO_PERMA_TOKEN else ""
```

`permaArtistToken` requires the literal substring `/artist/` in the URL (`Identity.kt:64-69`). If
Saavn's artist cards carry a different (or absent) `perma_url` shape, **every artist is silently
dropped** and the section renders empty with no error.

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

**What changed since (2026-10-08), which is progress and not a fix:**

- `PermaArtistTokenTest` pins the current extraction for every plausible `perma_url` shape, pins
  four shapes that yield a **wrong** token rather than none, and pins what the search path does
  with a dropped card. The assumption is now written down and falsifiable rather than folklore.
- `decodeMiniPage` emits `ORIGIN_ERROR` drift for an error envelope (`Mapper.kt:247`), so "the
  origin refused" is no longer byte-identical to "no matches". Before that, an `{"error":{…}}` body
  decoded as an empty page with zero drift and zero rows — which is the state §2 was reported in and
  the reason its cause could not be established from the app at all.

**Next step to close it:** capture the real response on-device. The drift channel already records
`NO_PERMA_TOKEN` drops (`Drift(endpoint, why, …)`), so the cheapest proof is to surface artist-search
drift into the `Dylan:db`/provider logcat mirror, rebuild, search `Eminem`, and read what was
dropped. Do **not** conclude from the `INPUT_INVALID` responses above.

### 2c. Search results: one merged list instead of sectioned — **OPEN, not started**

**Current state: Open. Requested 2026-10-03. Largely a presentation change — the navigation already
exists.** *Nothing has been built; the code below is the evidence that the merged list is still the
merged list.*

Search still renders **one relevance-ranked list** mixing songs, albums and artists:

```kotlin
// SearchScreen.kt:595-597
songs.forEach  { all += Triple(dylan.search.relevanceBand(submitted, it.title), order++, Hit.SongHit(it)) }
albums.forEach { all += Triple(dylan.search.relevanceBand(submitted, it.title), order++, Hit.AlbumHit(it)) }
artists.forEach { all += Triple(dylan.search.relevanceBand(submitted, it.title), order++, Hit.ArtistHit(it)) }
```

Because everything competes on one relevance axis, albums and artists end up interleaved among songs
and usually trailing, which is what makes the screen read as *"Show more only loads Album"* — the
non-song rows all look like one thing at the bottom. Both album and artist rows still render through
the same `MiniRow` with a text badge (`"Album"` / `"Artist"`, `SearchScreen.kt:458-465`). **The
"Show more" button itself is gone** — paging is now scroll-driven per section (§2d) — so the
complaint's mechanism has changed even though its perception has not.

**Already working — do not rebuild this:**

- Album tap → `onOpenAlbum(id)` → `Screen.Album` → `AlbumScreen`.
- Artist tap → `onOpenArtist(mini)` → `Screen.Artist` → `ArtistScreen`.
- Playing a song row hands `songsOnly` to a play action, so an album/artist page already has the
  `onPlaySongs = playNow` hook to load into Now Playing.

So both destination screens, the routes, and the play-into-Now-Playing entry point are in place.
What is missing is that results are grouped and that the two tap targets *look* like two different
intents.

#### What to build

1. **Section the results** — Songs, then Artists, then Albums, each under its own header. Paging is
   already per-section (§2d), so the sections have their own budgets to show; what is missing is
   the visual grouping.
2. **Encode the affordance in the shape**, following Spotify: artists render in **circular** frames,
   albums and songs in **square** ones. The rationale worth keeping is that shape tells the user
   whether tapping explores or plays, without reading a label:
   > "Spotify always places Artists in circular frames while Albums and Songs are displayed in square
   > frames. That means when I search for something I can easily distinguish between clicking on
   > something to see more versus clicking on something to play a song or album."
   > — <https://uxdesign.cc/ux-ui-analysis-spotify-31f3855a1740>
3. **Per-section counts/emptiness** — a section that legitimately returned nothing should either be
   absent or say so. Today "no artists" is STILL indistinguishable from "artists failed to load"
   in the UI: the `*Exhausted` flags are the code's answer to that question, but the merged list
   never shows them. That is exactly the ambiguity that made §2b hard to spot.
4. **Keep play in Now Playing as the page's job, not the row's** — tapping a song plays it; tapping
   an artist or album navigates, and *that* page owns Play/Shuffle.

#### Entity-page polish (lower priority)

Spotify's artist page leads with a large header image and name, then **Popular** (a short song list)
then **Albums**. `ArtistScreen` and `AlbumScreen` exist and are reachable, so this is refinement
rather than new navigation — check them against that shape once search routes users there.

#### Caveat — do not copy Spotify's shuffle

Worth knowing before implementing a Play/Shuffle affordance on these pages: Spotify's artist/album
"Shuffle Play" is a long-standing rough edge — users report it shuffling a curated mix rather than
the artist's catalogue.

- <https://community.spotify.com/t5/Live-Ideas/Artist-Shuffle/idi-p/5881030>
- <https://www.reddit.com/r/truespotify/comments/1pqvywq/frustrating_not-being_able_to_shuffle_an_entire>

For DYLAN the unambiguous behaviour is to shuffle **the songs this page actually loaded**, and to
label it that way.

#### Dependencies and acceptance

- **Blocked by §2b.** Until the artist-drop cause is fixed, the new Artists section renders empty
  and the redesign will look broken on first run. Fix §2b first, or ship both together.
- Interacts with §2a, which is now **fixed** — so the "cross-rendition duplicates make the Songs
  section look padded" objection is gone. §2a no longer blocks §2c.
- Acceptance: search `Eminem` shows three headed sections; the artist row is circular and opens
  `ArtistScreen`; the album row is square and opens `AlbumScreen`; playing from either page loads
  those songs into Now Playing.

### 2d. Search paging: per-section exhaustion, no "Show more" button — **FIXED (uncommitted), unverified on device**

**Current state: FIXED — code verified by reading 2026-10-08. Never seen paging.**

Replacing the earlier single `hasMore`, which provably could not work: the origin's endpoints return
a **mixed** envelope whose `total` counts songs, albums *and* artists, so reporting it while
delivering only the wanted-type subset made `hasMore` true forever. Worse, `deliveredTotal` decided
"there may be more" by comparing the *kept* rows against the requested page size, which is only
valid when the page contained nothing but the wanted type. A 20-row page holding 3 albums and 17
songs scores `kept (3) >= pageSize (20)` as false, so the album section declared itself exhausted
after one page and paging silently stopped — while songs kept paging, because cross-rendition
duplicates counted as distinct rows against the budget.

Now (`SearchScreen.kt`):

- `ResultState` holds three independent `*Page` counters and three `*Exhausted` flags (`:624-638`).
- `extend` fetches only the sections that are not yet exhausted, concurrently (`:749-775`).
- `foldPage` treats "a page that added nothing new" as the origin's way of saying it has no more of
  this type, whatever its `total` claimed — exhaustion is **observed**, not predicted (`:823-833`).
- A page that never arrived returns null, and the caller leaves its counter and its flag alone: a
  failed request is not evidence of anything.
- `loadingFirst` gates the scroll listener, so a page-2 request cannot arrive before page 1 exists
  and overwrite it (`:648-661`).
- The scroll listener reads the *live* `layoutInfo` rather than a captured item count, and
  `canExtend()` is read at call time rather than captured (`:321-332`).
- The "Show more" button is gone.

**Not verified on device.** No scroll has been observed to fetch a second page.

---

## 3. Mini-player / Now Playing sheet does not follow the finger

**Current state: REWRITTEN — structure verified by reading 2026-10-08. Gesture behaviour still
unverified on hardware.**

Reported as: swiping the bottom "Now Playing" bar up jumps rather than tracking the finger, and
swipe-down is equally wrong. It should track the finger 1:1.

*The four subsections below are **historical**: every defect they name has been removed from the
tree. They are kept because the diagnosis was correct and is worth not re-deriving. The
"Fix direction" at the end of this section is also historical, and it does **not** match what
shipped — see "What actually shipped".*

### 3a. The pull-up gesture moves nothing at all — primary cause *(historical)*

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

This was a threshold detector, not a drag. `dragAmount` was summed and discarded — **no
`translationY` was ever driven by the finger**, so the bar sat still and the full-screen sheet popped
in only after 64dp of travel. That is precisely the reported "jumps up".

Secondary defects in the same block:

- The threshold was on **cumulative** travel, not instantaneous movement or velocity, so a slow
  deliberate drag triggered it as readily as a flick — it felt sticky and arbitrary.
- **There was no downward branch at all.** `accum` was only ever tested against `< -64.dp`, so the
  bar could not be dismissed by swiping down.

**Gone.** `AppRoot.kt` no longer contains `detectVerticalDragGestures`, an `accum` variable, or a
`64.dp` threshold. The bar is drawn by the surface itself as its own top strip
(`AppRoot.kt:194-197`, `NpPlayerSurface.kt:311-317`), and the gesture is `anchoredDraggable`.

### 3b. The sheet's anchors mix normalised and pixel units *(historical)*

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

`anchoredDraggable` anchors are **pixels**. The initial `Closed at 1f` therefore meant "1 pixel from
the top" — i.e. *fully open*, not hidden. Until `onSizeChanged` fired and `updateAnchors` ran, the
sheet was laid out essentially on-screen. The `graphicsLayer` fallback

```kotlin
translationY = if (npState.offset.isFinite()) npState.offset else heightPx
```

never rescued it, because `npState.offset` **is** finite (~0), so `else heightPx` was not taken. Net
effect: the sheet was briefly at the wrong offset, then snapped to `Closed at heightPx` and
animated up.

**Gone.** Anchors are pixels derived from the measured surface height, built *with* the state rather
than patched into it afterwards, so `offset` is a real number from the first read
(`NpPlayerSurface.kt:218-233`). There is no `Open`/`Closed` vocabulary and no `updateAnchors` call.
`translationY` uses `requireOffset()`, which throws in the draw phase rather than silently drawing
the surface somewhere arbitrary (`:287`).

### 3c. Entrance is a scripted tween that races the gesture *(historical)*

```kotlin
LaunchedEffect(Unit) {
    snapshotFlow { heightPx }.firstOrNull { it > 0f }
    npState.animateAnchor(NpAnchor.Open)   // 280ms tween
}
```

Because the mini bar fired `onExpand()` *mid-gesture* (3a), the sheet mounted while the finger was
still down and then animated up over 280ms — two motions at once, which reads as the jump.

**Gone.** There is no entrance tween, because there is no mount to animate: the surface is mounted
for as long as a track exists, so opening the player is a change of anchor on an already-visible
layer (`AppRoot.kt:270-279`).

### 3d. No `nestedScroll` connection *(historical)*

The sheet content is scrollable and there was no `BottomSheetNestedScrollConnection` on the sheet.
Without it the drag and the inner list competed for the same delta, which is the documented cause of
"sheet moves oddly / overshoots on a vigorous fling".

**Gone.** `SurfaceNestedScroll` (`NpPlayerSurface.kt:343-401`) is attached only while expanded, so
the app's own lists underneath keep their scroll behaviour untouched. It follows the same split
Material's own `ConsumeSwipeWithinBottomSheetBoundsNestedScrollConnection` uses.

### Fix direction (historical) vs. what actually shipped

The plan written at the time was:

> Stop swapping composables. Hoist **one** `AnchoredDraggableState` into the parent with anchors
> `{ Hidden, Peek, Expanded }`, render the mini bar as the sheet's *peek* face, and let a single drag
> move one surface continuously from mini → full and back.

**What shipped is close but not that.** The shipped anchor vocabulary is **`{Full, Mini}`**
(`ui/NpAnchor.kt:13-16`), not `{Hidden, Peek, Expanded}`. The one-surface structure, the
pixel-derived anchors, the disappearance of the entrance tween and the nested-scroll connection all
match the plan. Anyone reading this section as a to-do list should read `NpPlayerSurface.kt`
instead; the remaining work is verification, not construction.

Two further deliberate choices in the shipped code, recorded here because they are easy to mistake
for defects:

- **Commit threshold.** Foundation's default is 50% of the trip, which for a ~2100 px travel means
  the finger has to move more than a thousand pixels before a slow release opens the player — the
  "it jumps" complaint in a different costume. The shipped threshold is 28% of the trip, capped at
  200 dp (`NpPlayerSurface.kt:88-99`).
- **Settle spec.** `dampingRatio = 1f` (`:110-111`) because Foundation's own settle allows the
  spring to overshoot past an anchor on purpose, which for a full-screen player means the surface
  travels off the top of the window and leaves a gap at the bottom before coming back.

**Risk, unchanged:** this touches the app's primary navigation gesture, so it needs device
verification of all four transitions (mini→full, full→mini, scroll-then-drag, and
fling-overshoot) rather than a compile check. **That has not happened.**

---

## 4. Unescaped HTML entity in track titles

**Current state: FIXED (uncommitted) for new fetches.**

Home screen, "Jump back in":

> RAINY DAY WOMEN #12 **&AMP;** 35

The origin ships `&amp;` and the title reached the UI un-decoded (and inconsistently cased-upper).

Fixed in the identity/title normalisation layer next to `artUrlOf` (`provider/saavn/Identity.kt`),
not in a UI composable, so every surface benefits: `decodeEntities` (`:135`) behind `displayTitle`
(`:197`) and `displaySubtitle` (`:200`), called from three mapper sites (`Mapper.kt:105`, `:157`,
`:326`). Deliberately a fixed set rather than a general decoder: a full table would accept
`&nbsp;` and friends and quietly change string identity for titles that legitimately contain an
ampersand-shaped run of text. Numeric references are included because they are unambiguous.

**Caveat, unchanged and worth repeating:** rows already persisted in the DB are **not** re-decoded.
A user who upgraded keeps the raw markup on every row that is not refetched.

---

## 5. Player screen: 442px dead zone at the top, 311px at the bottom

**Current state: structure changed; the specific measurements are stale. Needs re-measurement on
device.** *(The numbers below were measured on the 2026-10-03 device pass and describe a build that
no longer exists.)*

Correcting an earlier eyeball claim of mine: **the horizontal alignment is fine.** Every element's
centre is within 1.5px of the 1080px screen centre (artwork 539.5, progress track 538.5, queue row
539.5). The apparent left-shift was an artefact of a downscaled screenshot.

Measured content bands on a 1080×2400 capture *(historical — 2026-10-03 build)*:

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

So the real complaint was **vertical**: the player block was not centred in the viewport, leaving a
442px void above the artwork and 311px below the last control. Also visible: the subtitle
`LUDWIG GORANSSON - THE ODYSSEY (ORIGINAL` was clipped at the content edge with no ellipsis.

**What has changed since (2026-10-08, verified by reading):**

- The layout is now a `verticalScroll` column whose interstitial space is a weighted `Spacer`
  (`NowPlayingSheet.kt:161`), so slack is absorbed instead of left as a void at the top.
- The artwork side is a function of the measured content height and a `MIN_ART_SIDE`/`MAX_ART_SIDE`
  clamp (`:137-145`), so a tall viewport produces larger art rather than a larger void.
- The title carries `basicMarquee()` (`:251`) and the subtitle is clickable through to the artist.

**What has not changed:** the subtitle still declares `maxLines = 1` with **no** `overflow` and no
marquee of its own (`:253-263`), so it clips rather than ellipsises. That half of the §5 complaint
is still open, and it is still a render-time fact rather than a measurement.

**Do not re-quote the table above as current.** Re-measure on device: the artwork band, the
transport band, and whether the subtitle still clips.

---

## 6. M0 seam-1a flake — CI red, cause FOUND and fixed

**Current state: FIXED and re-verified by reading 2026-10-08. Not re-run.**

```
M0SeamsTest > aMidStreamResetKeepsTheBytesThatLandedAndTheResumeAsksForThem
  AssertionError: resume from the bytes that landed, not from zero
  expected:<bytes=1080-> but was:<null>
```

### Cause

In `ktor-io` 3.5.2, `io.ktor.utils.io.ByteChannel`:

```
isClosedForRead = (closedCause != null) || (...)
```

The `closedCause` disjunct has **no `_readBuffer` conjunct**. `awaitContent()` returns true,
the writer then cancels with a cause, and `readAvailable()` converts that to `-1` *before*
consulting the buffer — so a channel still holding bytes reports end-of-stream. Those bytes
are unrecoverable: `ByteChannel.readBuffer`'s getter throws.

`close()` flushes before recording the cause; `cancel()` does not. That ordering difference is
why the engine path recovers and the wrapper path does not.

### Fix

`Transfer.engineBody()` returns `r.rawContent`, bypassing the wrapping
(`shared/src/commonMain/kotlin/dylan/download/Transfer.kt:729`). Costs one narrow
`@OptIn(InternalAPI::class)`, isolated to that single function as a deletion point.

Measured, 150 runs per arm:
- `bodyAsChannel()` — **142/150 lost every byte, silently, with no error**
- `rawContent` — **0/150 lost**, and surfaced the error 150/150

Not a ktor version problem. 3.6.0's changelog covers Android streaming and ByteReadChannel work
(KTOR-9762, -9561, -9640, -9702) but nothing for this defect. KTOR-8682 and KTOR-9500 — the
two candidates — are unrelated and already fixed in 3.2.3 and 3.5.0.

`bodyAsChannel()` **is** `body<ByteReadChannel>()` (bytecode-verified), so my earlier switch to
`body<ByteReadChannel>()` was a no-op.

### Withdrawn — do not re-derive these

- That `readChunk`'s `awaitContent()`-before-`readAvailable()` ordering was *the* cause. An
  attempted reorder did **not** fix it; reverted.
- okio read-ahead discarding (3,000/3,000 clean).
- A fixture feeder-channel race (20,000/20,000 clean).

### Guards that outlive the fix

- `DownloadBodyChannelIdentityTest` — fails immediately if the body is routed back through the
  wrapper. *(Verified present, `jvmTest/fuzz/probe/DownloadBodyChannelIdentityTest.kt`.)*
- `ReadAfterAwaitContentContractTest` — holds any reader to "`awaitContent` true implies bytes or
  a terminal error, never a clean EOF". *(Verified present, same directory.)*

The characterisation test that asserted the buggy shape was **removed**, not inverted: a test
asserting a bug exists is only meaningful while that bug exists.

### Still unexplained

Why end-to-end loss is ~3% (5/150) when the isolated harness loses 146/150. Also unestablished:
whether `cancel` vs `close` is the *only* divergence, and whether `readAvailable` returning 8192
rather than 65536 is itself a defect. KTOR-8682 notes `awaitContent` does not support
cancellation — a possible separate defect in our stall watchdog, never investigated.

### Not filed

The ktor issue needs an account; it cannot be filed from here. (`docs/issues.md` #28.)

---

## 7. Previously parked, still open

Unblocked by the device unless noted.

**7a · H1 `LazyDatabase.cold` reachability — still open, and the line reference is dead.**
The class is `di/LazyDatabase.kt:36`, `cold` is `:48`, `open()` is `:70`. `AppRoot.kt:150` now holds
a `when` over the nav stack, not a gate. Needs an `androidApp` gate, not fixable from `shared/`.
Mechanism fixed; reachability unproven.

**7b · M2/M9 in `DylanMediaService.kt:421-447`, `:483-500` — still open, and the lines no longer
say what they named.** `:421-447` now holds `buildMediaButtons` and `:483-500` holds
`computeResumptionItems`. What M2 and M9 named is not identifiable from them. Unowned, unfixed.

**7c · F-1 non-transactional `1.sqm` — still open, and "needed a device" was wrong.**
Measured on the JVM by `jvmTest/fuzz/M2MigrationTest.kt:321-371`: every kill point except k=0
wedges the store permanently, because `user_version` is written only after `migrate` returns and the
generated `DylanImpl.kt` has no transaction wrapper. The plan's one-line fix is demonstrated in a
test (`aRolledBackMigrationLeavesAStoreThatReopensCleanly`) and is **not applied in production**.
The chain is now **47** statements, not the 33 the notes quote.

**7d · Constant tuning (download backoff, timeouts) — unchanged.** Still needs real network timing.

**7e · P3.1 `wipe()` removal — unchanged.** Still parked.

**7f · Repo-cleanup agent — never dispatched.** Candidates: stale rows in `docs/codebase-audit.md`,
`tools/probe-results.md`, `docs/build-and-gates.md` (lines 19 and 138-140 still describe the detekt
baseline as the sanctioned mechanism), dead `DylanTokens`/`SearchStore.reset`.

**7g · Network/provider review agent — timed out twice, never delivered.** Note that §2b sits
exactly in its area.

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

**Current state: FIXED (uncommitted). Config and derivation re-verified by arithmetic on
2026-10-08. Not device-verified.**

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

**Re-verified by arithmetic (2026-10-08):** `CACHE_TARGET_BYTES = 2 × 1024³` (`AppConfig.kt:217`);
`CACHE_HEADROOM_DIVISOR = 8` → `cacheMaxBytes = 2,147,483,648 + 268,435,456 = 2,415,919,104`
(`:95`, `:225`); `cacheMaxFiles = 2,415,919,104 / 1,000,000 = 2415` (`:106`, `:209`); the pinned row
budget is `maxOf(1, (2415 × 0.75).toInt()) = 1811` (`cache/CacheManager.kt:88`); `diskFloorBytes =
500 MiB` (`AppConfig.kt:120`, `:251`). Both UIs divide by `cacheMaxBytes` (`LibraryScreen.kt:345`,
`SettingsScreen.kt:179-188`), so the number on screen and the number the LRU pass enforces are the
same value by construction.

**453 shared tests green is a historical run result and was not reproduced in that pass.**

**Open risk, not addressed:** 2.25 GiB of audio on a device with 8.1 GB free is a large fraction of
the volume. `diskFloorBytes` (500 MB) is the only guard, and it is checked per download rather than
against total device headroom. Worth deciding whether the target should scale with available space.

---

## 10. detekt baseline: deleted, 313 findings fixed in the open

**Current state: baselines EMPTIED (not deleted) and the wiring unwired. The finding count is
unverifiable and internally inconsistent — see the correction below.**

The `lint` gate on PR #28 was going red, and I reported it as ~29 findings. That was
wrong by an order of magnitude, and the reason matters: **detekt had been reporting almost
nothing because two baseline files were suppressing 245 findings.**

| | before | after |
|---|---|---|
| `config/detekt-baseline.xml` (shared) | 132 entries | emptied |
| `config/detekt-baseline-androidApp.xml` | 113 entries | emptied |
| live findings | 29 (what I first reported) | **claimed 2, unverified** |

By rule: `MagicNumber` 140, `MaxLineLength` 103, `LongMethod` 14, `SwallowedException` 13,
`MayBeConst` 10, `TooGenericExceptionCaught` 10, `LoopWithTooManyJumpStatements` 7,
`CyclomaticComplexMethod` 5, then 2 each of `NestedBlockDepth`, `MatchingDeclarationName`,
`LargeClass`, `ReturnCount`, and 1 each of `UnusedParameter`, `TooManyFunctions`,
`LongParameterList`. Across 44 files.

**Why the baseline was worse than the debt it hid.** A baseline entry cannot record *why*
something was accepted, so "we examined this and accepted it" and "nobody ever ran the
linter over this file" are indistinguishable in the file. It also leaked: detekt's
`MaxLineLength` baseline id is `MaxLineLength:<file>.kt$` with an **empty signature**, so a
*new* over-long line added to an already-baselined file was never reported. A baseline is
therefore not a record of accepted debt; it is a hole with a comment.

Both `baseline = ...` lines are removed from the two `build.gradle.kts` files, so
`detektBaseline` is no longer reachable from a normal build and the list cannot regrow
unnoticed. `config/detekt.yml` and `docs/build-and-gates.md` were corrected — both still
described the baselines as the sanctioned way to handle debt.

**Stale entries, for the record:** a large share of the 245 were already dead. The
baselines had not been regenerated as the code moved, so entries no longer matched anything.
That is the expected decay of an unmaintained suppression list and is the strongest argument
for not having one.

### Correction (2026-10-08)

**"Deleted" is not what the tree holds.** Both files still exist as 105-byte shells:

```xml
<?xml version="1.0" ?>
<SmellBaseline>
  <ManuallySuppressedIssues/>
  <CurrentIssues/>
</SmellBaseline>
```

They were *emptied*, not deleted. While nothing points at them they are inert, but the wording in
this section, in `docs/issues.md` #22 and in `docs/build-and-gates.md:150-153` should all be
corrected to "emptied".

**The "313 → 2" count cannot be true as stated.** `config/detekt.yml` sets `maxIssues: 0` and
declares no `excludes` for anything but `**/android/ui/Dyl.kt`; neither `build.gradle.kts` sets a
baseline; and there is no `@Suppress` or `@file:Suppress` on `Orchestrator.kt`. Yet `Orchestrator.kt`
is 1,617 lines declaring 83 functions, which is a live `LargeClass` (default threshold 600) and a
live `TooManyFunctions` (`thresholdInClasses: 25`). Two findings with no suppression mechanism and
`maxIssues: 0` means a red gate. Either something outside this repo's source silences them, or the
gate is red. **Resolve with `./gradlew detekt` before repeating the count.**

---

## 11. Android Lint baseline: separate, also stale — NOT yet touched

**Current state: open, and every line reference in it has moved. The entry count in this section
was wrong (44, not 43).**

Left alone deliberately; it is a different mechanism from detekt and out of scope for this pass.
Flagged here because the same argument applies.

By rule: `NewerVersionAvailable` 23, `GradleDependency` 8, `RestrictedApi` 3,
`AutoboxingStateCreation` 3, `UnknownIssueId` 2, `AndroidGradlePluginVersion` 2,
`OldTargetApi` 1, `UnsafeOptInUsageError` 1. **Total 44 `<issue` entries, not 43.**

**Verified stale on 2026-10-08, by reading rather than by trusting the file:**

- **The `RestrictedApi` entries are stale.** Filed at `DylanMediaService.kt:195,196,196`, which are
  now *comments*. The live `ResolvableFuture.create` is at 250-251 and the live
  `pending.set(computeResumptionItems())` is at 257. So the baseline is suppressing findings at
  lines that no longer contain the call — it is not currently protecting the code it names.
- **`AutoboxingStateCreation` ×3** names `mutableStateOf` for things that should be
  primitive-state-backed, filed at `SearchScreen.kt:76,79,82`. The live
  `mutableStateOf(1)` calls are at **626, 631, 636**. Also stale.
- **`UnsafeOptInUsageError` at `ExoPlayerEngine.kt:124` is stale too** — this section previously
  listed it among the ones that "most plausibly still correspond to live code". Line 124 is
  `when (reason) {`; the call it names, `.setLooper(thread.looper)`, is at `:94`.

`abortOnError = true` now (`androidApp/build.gradle.kts:120-125`), so this baseline is the only
thing keeping `:androidApp:lintDebug` quiet. The dependency-version entries are Dependabot's
business and are refreshed on every bump anyway.

**Action, one or the other:** regenerate it with `./gradlew :androidApp:updateLintBaseline`, or
delete it. A baseline that suppresses nothing while looking like it suppresses something is worse
than no baseline, for the reason §10 gives.

---

## 12. Real defects the lint cleanup surfaced (all verified against code, 2026-10-03)

**Current state (2026-10-08): 12.1-12.6 and 12.8 FIXED. 12.7 WITHDRAWN — it was not a bug.**
*None of these is a lint problem; they are reasons the lint work was worth doing.*

### 12.1 Light mode does not exist, and the token local hands out dark tokens anyway
`androidApp/src/main/kotlin/dylan/android/ui/DylanTokens.kt` *(renamed from `Tokens.kt`)*

`LightTokens` and `DarkTokens` were **field-for-field identical** (verified: same 10 keys, same
values). `toScheme(t)` branched on `if (t == DarkTokens)`, and `DylanTokens` is a `data class`,
so value equality makes that condition true for `LightTokens` too. `DylanTheme(darkTheme = false)`
therefore builds a **dark** `ColorScheme`.

Worse than "light mode looks like dark mode": `LocalDylanTokens` defaulted to `LightTokens`, so
**all `LocalDylanTokens.current` readers** got dark-scheme values irrespective of the theme
flag — not just Material's own colours but the app's custom tokens.

**FIXED — code.** `LightTokens` is now an alias of `DarkTokens` (`:76`), so drift is
unrepresentable; `LocalDylanTokens` defaults to `DarkTokens` (`:83`); `toScheme` has no equality
branch (`:95-116`) and the selection is flag-driven (`:148`). **What remains is a design decision,
not a code defect:** real light values for the ~88 un-overridden `ColorScheme` roles, eleven of
which flip with the Material baseline (`:88-94`).

### 12.2 Black-on-black system bar icons
`androidApp/src/main/kotlin/dylan/android/MainActivity.kt:46`

`enableEdgeToEdge()` was called bare, so `SystemBarStyle.auto` picked **light (dark) icons**
whenever `isSystemInDarkTheme()` was true — while the app background is hard-coded `#FF000000`
in *both* token sets. On a device in light mode the clock, battery and signal were
drawn black on black.

**FIXED — code.** Both bars pinned with `SystemBarStyle.dark(Color.TRANSPARENT)` at
`MainActivity.kt:64-67`.

### 12.3 `Copy.kt` is a dead duplicate of the real copy source
`androidApp/src/main/kotlin/dylan/android/ui/Copy.kt`

Only `Copy.OFFLINE` (8 refs) and `Copy.BUSY` (1 ref) were referenced anywhere. The other
**9 constants plus `Copy.forCode` had zero references**. All ten strings were byte-identical to
`DylanFailure.message()` in `shared/src/commonMain/kotlin/dylan/model/Models.kt:140-153`, which
is documented as the "Single user-facing copy source" and is what toasts and the NP sheet
actually use. So `Copy` was a copy that could silently drift from the messages users see, and
nothing would notice.

**FIXED — code.** The 9 dead constants are gone; `OFFLINE` and `forCode` now delegate to
`DylanFailure.message()`; `BUSY` stays one literal with the reason recorded (there is no
`ErrorCode.BUSY`, confirmed against the enum at `Models.kt:114-131`). Live references: `OFFLINE`
×8, `BUSY` ×1 (`LibraryScreen.kt:323`), `forCode` ×1 (`NowPlayingSheet.kt:432`).

### 12.4 `wipe()` never verifies that it deleted anything
`shared/src/androidMain/kotlin/dylan/db/DriverFactory.android.kt:249`

```kotlin
private fun wipe() {
    runCatching { ctx.deleteDatabase(DB_NAME) }
    ...
        runCatching { File(base.path + suffix).delete() }
}
```

Both the `Boolean` from `deleteDatabase`/`delete` and any exception were discarded, with no log.
The line immediately above asserts *"integrity_check FAILED - wiping dylan.db. This deletes
all user data"* — so the one irreversible operation in the class was **claimed in the audit trail
and never confirmed**. A failure did eventually resurface as an `open()` exception, so this was
not silently destructive, but the log made a claim the code did not check.

Note `TooGenericExceptionCaught` cannot see this: it is a `runCatching` lambda, not a `catch`.

**FIXED — code + test.** `wipe()` now logs a `false` return and a throw, and `exists()`-checks each
sidecar first so a missing one is not misreported (`DriverFactory.android.kt:289-303`); the JVM
factory is the same shape (`DriverFactory.jvm.kt:239-252`). Covered by
`jvmTest/DriverFactoryWipeGateTest.kt`.

### 12.5 `WalOpenHelperFactory` pins the database open after close
`shared/src/androidMain/kotlin/dylan/db/DriverFactory.android.kt`

The factory was held for the process lifetime and was the object through which foreign keys got
armed pool-wide. It pinned the last `SupportSQLiteOpenHelper`, hence the `SQLiteDatabase`, the file
handle and its `-wal`/`-shm`, **after the driver was closed**. It mattered most on the wipe path:
`wipe()` unlinked the file while this process still held it open, so the space was not reclaimed
until process death and the stale connection could keep writing to the unlinked inode. Also a
latent staleness hazard — an `open()` that reached the FK line without the factory having run
would arm foreign keys on a stale helper.

**FIXED — code.** The helper reference is read once inside `open()` and cleared by `release()` in
the `finally` (`:94-100`, `:151-153`). Clearing rather than closing is deliberate: the driver's
own `close()` closes the helper, and closing it twice would throw.

### 12.6 iOS `verify()` does not verify what its comment claims
`shared/src/iosMain/kotlin/dylan/db/DriverFactory.ios.kt`

The comment above the pragmas said they are "issued outside one and then read back", but
`verify()` read back only `user_version` and `foreign_keys` — `journal_mode=WAL` and
`busy_timeout=5000` were set and never checked. The Android file documents a *measured* instance
of exactly this failure mode (a pragma that silently did nothing for the life of the app).

**FIXED — code. Uncompiled: no Xcode in this environment.** All four are read back, `busy_timeout`
as a floor `> 0` because it is connection-local (`:63-80`).

### 12.7 `fsRename` fallback cannot cross filesystems — **WITHDRAWN, THIS WAS NOT A BUG**
`shared/src/{androidMain,jvmMain}/kotlin/dylan/util/Rename.*.kt`

*This row was written as an open defect and then disproved by measurement. It is kept because it
was an audit's top-priority "fix" and re-deriving it wastes the same afternoon.*

`Files.move(..., ATOMIC_MOVE)` across filesystems throws `AtomicMoveNotSupportedException`
(caught), but the fallback `Files.move(src, dst, REPLACE_EXISTING)` then throws
`FileSystemException` — `java.nio` does no implicit copy+delete. A `.part` → final rename with
source and destination on different volumes (external vs app-private storage) still fails hard.
Lower confidence: with `ATOMIC_MOVE` all other options are ignored, so destination-exists
handling is platform-dependent, i.e. the two paths do not agree.

**Every part of that is false.** `sun.nio.fs.UnixCopyFile.move` *does* fall back to
`copyFile(...)` on `EXDEV`; `WindowsFileCopy.move` uses `MoveFileEx(..., MOVEFILE_COPY_ALLOWED)`.
Measured on two real device IDs (`hdiutil` ram disk against the boot volume): `ATOMIC_MOVE`
cross-device throws and the shipped `REPLACE_EXISTING` retry then **succeeds**, leaving the complete
file at the destination. The hand-rolled copy that was tried as a "fix" is strictly worse:
`Files.move`'s copy path rolls the target back if it fails, whereas writing straight to the final
name truncates the *previous complete* file and leaves a partial one at the final name.

**Deliberately not changed.** The evidence is recorded in `jvmTest/util/RenameTest.kt:14-38` and
in the KDoc on both `actual` implementations (`Rename.android.kt:11-22`, `Rename.jvm.kt:11-22`).
What remains genuinely unproven by CI is the cross-device case itself, which needs a second
filesystem. **Do not re-file this.**

### 12.8 A stale KDoc now contradicts measured reality
`shared/src/androidMain/kotlin/dylan/db/DriverFactory.android.kt:237-239`

*"WAL itself was never actually at risk: `AndroidSqliteDriver` enables it while opening, so the
failing PRAGMA was redundant rather than load-bearing."* That was written before the on-device
measurement, which found `journal_mode=rollback` with **no `-wal`/`-shm` files present** — so
WAL genuinely was not enabled, and the fix had to go through
`setWriteAheadLoggingEnabled(true)`. The comment asserted the opposite of what was measured.

**FIXED — code.** The comment now states the measurement and why WAL goes through the factory
instead of a pragma (`DriverFactory.android.kt:261-266`).

---

## 13. Measurement tooling that reports conclusions it did not measure

**Current state (2026-10-08): both findings FIXED.**

Surfaced by the probe/test half of the lint cleanup. Both are cases where the tool printed a
verdict that was not derived from what it measured — the worst failure mode for a harness,
because it looks like evidence.

### 13.1 `DefectLineProbe` prints a hard-coded verdict
`shared/src/jvmTest/kotlin/dylan/fuzz/probe/DefectLineProbe.kt:141`

```kotlin
"runsThatSurfacedAnError=$sawThrow verdict=StreamErr.Truncated(written=0) " +
```

The `verdict=...` was a **literal**, printed next to the real measurements
(`bytesWrittenTotal`, `sawThrow`). If a future change made `written > 0`, or made
`awaitContent` surface the error, this probe would keep printing the old conclusion. The
verdict must be derived from the counters beside it.

**FIXED — code.** `resumable`, `verdict` and the Range wording are all derived from the live
counters (`:152-158`); the literal is gone. "Output byte-identical to before" and "mutation-tested
both arms" are run-level claims and were not re-verified.

### 13.2 `ProbeMain` P3 can report a false "truthful-length violated"
`shared/src/jvmMain/kotlin/dylan/probe/ProbeMain.kt:159-178, 371-373`

`bodyBytes` silently stopped reading once the buffer exceeded `maxBytes` (default 8 MB):

```kotlin
if (out.size > maxBytes) return@let out
```

P3 then asserted `require(actual == cl)`. At 128 kbps, 8 MB is ~8.4 minutes, so **any track
longer than that reported drift that was not there.** P3 deliberately picked the *shortest* song
of the sample to stay small, which made this unlikely rather than impossible — a 9-minute
shortest-track is entirely plausible.

Note the cap was silent: there was no log line saying the body was truncated, so a false
positive was indistinguishable from a real finding in the output.

Two things to fix, not one: the truncation must be *reported* rather than silent, and the
caller must not compare a truncated read against a `Content-Length`. Also worth noting this
helper read via `bodyAsChannel()` — the same accessor whose ktor defect is the subject of
`Transfer.engineBody()`. It is a diagnostic harness so the risk is a wrong probe verdict
rather than user data, but the same read shape is now known to be unsound.

**FIXED — code.** `bodyBytes` returns `BodyRead(bytes, truncated)` (`:66-68`, `:208-226`), so the
cap is a returned fact; `truthfulLengthFailure` is a pure function that refuses to compare a
capped read against a `Content-Length` and instead reports that the length was not measurable
(`:84-93`); P3 uses it (`:419-426`). Both halves of the finding are closed. The line references
above (`:159-178`, `:371-373`) are stale.

### 13.3 Dead code in `ProbeMain` worth deleting
- P4 declared a local `data class R(token, raw)` that was never used. It implied frames are
  parsed; nothing read it. Misleading.
- `var etagP2` was declared outside the P2 lambda but written and read only inside it. The
  outer `var` was pointless.

**FIXED — code.** `data class R` is deleted (HEAD had it at `:334`); `etagP2` is a local `val`
inside the P2 lambda (`:400`).

---

## 14. Suppressions introduced, and the one refusal worth reading

**Current state (2026-10-08): the `@Suppress` claim is still true; the two "needs a product
decision" rows are stale — both have been resolved, one of them by doing the thing the row said
could not be done.**

**One `@Suppress` was added during this pass**, on `DownloadEngine`'s primary constructor
(`LongParameterList`, 12 collaborators) — still present at `DownloadEngine.kt:168`. Justified in
the class KDoc: a bag object hides the dependency list, defaulting `log`/`rename`/`freeDisk` would
let a mis-wired graph build silently with the wrong logger, and **the signature is a contract
because the test files construct it by name**. Flagged for a decision rather than silently accepted.

> **Correction (2026-10-08):** the call-site count is **8**, not nine — `OrchestratorEdgeTest`,
> `ReconcilerClockTest`, `support/GraphHarness`, `ReconcilerTest`, `fuzz/V0StoreBuilder`,
> `fuzz/HealthyStore`, `fuzz/M0SeamsTest`, `DownloadEngineTest`. The argument (the signature is a
> contract) is unaffected.

**`Orchestrator.kt` `LargeClass` + `TooManyFunctions` was refused, and the refusal is correct.**
81 functions against a threshold of 25; clearing it means moving 56. Every candidate cluster
reads 8+ `private` members of the class owning the single-permit state lane
(`pushedUpNextId`, `doneJoinedKey`, `resumePendingMs`, `engine`, `playGeneration`,
`snapshotFingerprint`, `windowPreparer`, `_state`). Kotlin `private` members are invisible
outside the class, so a file split necessarily widens shared mutable playback state from
`private` to `internal` — a visibility-semantics change to the most behaviour-critical class
in the app, with no compile gate. Needs a scheduled multi-pass change, not a lint sweep.

> **Correction (2026-10-08):** the arithmetic has drifted. The class (`:87`) now declares **83**
> functions inside lines 87-1606 plus one top-level (`:1608`), so the gap to the threshold is **58**,
> not 56. The refusal and its reason are unchanged.

**Also left, with reasons — both now stale:**

- ~~`AppConfig.kt:16` (138-char user-agent string, unwrappable without concatenation)~~ —
  **Resolved, and by the very thing the row said could not be done.** The UA is now split with `+`
  at the `Mozilla/5.0` / product boundary (`AppConfig.kt:16-22`), with the reason and the
  no-space-lost guarantee recorded inline. It is also 148 chars, not 138.
- ~~`SaavnSearchChannel.kt:537` (118-char debug format, 130 chars even on its own line)~~ —
  **Resolved, and the path has moved.** The file is now `provider/saavn/SaavnSearchChannel.kt`;
  `debugState()` is a three-line concatenation at `:544-548` and the longest line in the file is
  258 characters of prose comment, well under the limit. Nothing here needs a product decision.
