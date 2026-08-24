# DYLAN — Complete Design & Implementation Plan

**Version 1.3.2** · Supersedes v1.3.1 and earlier; `PLAN1.md` / `PLAN2.md` / `PLAN3.md` obsolete
**Platforms:** Android (API 34+, Compose) · iOS (26+, SwiftUI)
**Core:** Kotlin Multiplatform · **Product mode:** Personal use only (sideloaded APK / private TestFlight)

---

## Changelog v1.3.1 → v1.3.2 (seventh & eighth review rounds)

| # | Change | Source |
|---|---|---|
| 1 | §7.3 step 12: `ftyp` check was **inverted** (`==` deleted every valid file), **5 bytes wide** (`4..8`), and ran **before ext derivation** (would false-fail MP3). Now: non-inverted, `4 until 8`, gated on `ext == "m4a"`, after `extFor` | R7a-Bug1 / R7b-B1 |
| 2 | §7.3 step 12: verify total is the **authoritative entity size** (`total`: CL for 200, Content-Range TOTAL for 206) — `segStart+segLen` made exact-equality vacuous (range-capped responses self-verified truncation); dead undefined `contentRangeTotal` arm removed | R7a-Bug2 |
| 3 | §7.3 step 10/11: classify arms completed — cap-exhausted 200-ignore-Range now sets stream state and enters STREAM; **416 and stall/network retry the GET (`goto 9`) without consuming resolveCount**; two independent budgets (`resolveCount` = signatures, `attempts` = transport) | R7a-Bug3 |
| 4 | §7.3 step 13: COMMIT **preserves** `play_count`/`last_used_ms`/`pinned_at_ms` from `prev` (v1.3.1 reset all three on every upgrade — front-loaded LRU, inverted pin demotion); first-insert favorite pins with `now`; identical-refetch fast path | R7a-Bug4 |
| 5 | §9.1: `nextUp` handles Repeat.ONE (⇒ current) and Repeat.ALL wrap; `indexOf < 0` ⇒ null (not shuffle slot 0); shuffle-order rebuild mandated on every queue mutation, stale permutation = resync fault | R7a-Bug5 / R7b-B4 |
| 6 | §5.3: GC tuple-NOT-IN replaced with temp-table anti-join; `home_cache` self-referencing DELETE replaced with Kotlin-computed keep-set + bound IN list | R7a-Bug6 |
| 7 | §8.2: filenames carry `<provider>_` prefix (PK collision across catalogs); songId charset validated at adapter boundary, else SHA-256 | R7a-Bug7 / R7b-F1 |
| 8 | §5.1/§7.1: intent writes are a **priority-preserving upsert** — PREFETCH can no longer silently replace USER_BULK/USER_NOW | R7b-B3 |
| 9 | §8.6: clear-cache protects `upgradeSourceKeys`; test added | R7b-B5 |
| 10 | §7.3 step 13: same-path-overwrite crash window **documented as accepted** (worst case one transparent re-download; reconciler size-check catches it deterministically); journaling rejected (orphan-row class C.1 banned) | R7b-B2 |
| 11 | §9.3: transition table rows added for `TrackChanged(SEEK)` and `(EXPLICIT)`; QueueExhausted→repeat-ALL wrap specifies orchestrator re-primes the engine | R7b-M1 |
| 12 | §6.1: apiClient retry excludes 503 (breaker owns it); WS client confirmed defaultRequest-free | R7b-M2 / F5 |
| 13 | §8.3: file I/O moved OFF `dbLane` (function on io, only tx hops lanes); deterministic eviction tiebreakers (`cached_at_ms, rowid`); just-committed key exempt from its own enforcement pass | R7b-M3/M5/P6 |
| 14 | §7.3 step 7: `netNew` clamped ≥ 0 | R7b-M4 |
| 15 | §8.4: 12-byte magic+size sniff at cache-hit handoff before engine prepare | R7b-footgun |
| 16 | §4.1: state-lane discipline — no runBlocking, no engine joins, events drive readiness | R7b-footgun |
| 17 | §6.4: HTTP-fallback 429 never counts as a WS strike; §13.3 P13 media-HTML DRIFT sentinel; P1 HEAD→GET fallback; P7 calibration replaces ×125 | R7b-footguns |
| 18 | §9.1: `progressFor(key)` derived flow — rows never collect the raw map; §10.2: `Song`/`Album`/`SearchSection` in stability metrics gate | R7b-P1/P2 |
| 19 | §9.7: snapshot `order[]` sanitized against filtered queue; `onPlaybackResumption` clarified as the post-death Play path; session accumulator pause/seek semantics (§5.2); resolveCount cross-restart semantics documented | R7b-clarifications |
| 20 | §14: export ships `resolveRef`/`permaToken` — risk documented as deliberate personal-use trade-off | R7b-F2 |

### Rejected recommendations (with rationale)

| Claim | Verdict |
|---|---|
| B2 full commit redesign (rename-after-commit journaling) | Rejected — trades a one-file orphan window for an orphan-*row* window (C.1's banned class); worst case today is one transparent re-download, zero user data at risk. Documented instead |
| F3 move `resume_snapshot` out of `settings` | Rejected — the exception is deliberate and documented twice (§5.1 comment, §5.4 policy); a dedicated table for one blob is churn |
| F6 prominent "wait for download" UX | Rejected — v1 limitation already surfaced via sheet state + copy (§9.10) |
| F7 replace 10 s snapshot heartbeat with event-driven-only | Rejected — single-row WAL upsert at 0.1 Hz is negligible; event-only loses the last minute of position per crash |
| P4 byte-cap on `home_cache` | Rejected — 200 rows × KB-scale JSON ≤ few MB against a 2 GB audio budget; count+TTL suffices at personal scale |

---

## Changelog v1.3 → v1.3.1 (fifth & sixth review rounds)

| # | Change | Source |
|---|---|---|
| 1 | §7.3: sticky `retriedAfterFreshSign` flag **eliminated** — a fresh signature earns a re-sign retry iff `resolveCount < cap`. One hard bound, no reset semantics to get wrong across network-retry interleavings | R5-Bug1 |
| 2 | §7.4: reconciler re-enqueues **every intent whose final file is absent** — interrupted downloads (`.part` present, final absent) now actually resume; the old "lacking `.part`/final" wording excluded exactly those jobs. Worker `dropIntent` on success consumes intents; reconciler sweep is the belt | R5-Bug2 |
| 3 | §9.1: `nextUp` shuffle coordinate fix — locate `index` *inside* `shuffleOrder` (`indexOf`), not `shuffleOrder[index + 1]`; declared the single source of truth for all next-computation (prefetch, replaceUpNext, Queue UI) | R5-Bug3 |
| 4 | §8.3: budget accounting counts **all** `.part` bytes (v1.3 computed `partBytes` but never added it — resumed jobs contributed negative usage); callers pass incremental net-new only | R5-Bug4 |
| 5 | §7.3 step 7: free-disk check uses `2×netNew` (a 90 %-complete resume needs room for the remainder, not twice the track) | R5-M-v |
| 6 | §10.1: cold-start SLO split by platform — iOS first-launch-after-install accepted at < 1.5 s (Kotlin/Native init), warm < 800 ms | R5-M-i |
| 7 | §12.1: edge-to-edge inset handling stated as a **global rule** for every screen, not just NP sheet + mini-player | R5-M-ii |
| 8 | §9.9: thread-marshalling requirement explicit — `ExoPlayerEngine` posts all calls to the media HandlerThread; proven in M1.5 | R5-M-iii |
| 9 | §9.10: process-death invariant documented — `appScope` is per-process, transient flows re-init empty on cold start, never hydrated from `settings` | R6-1 |
| 10 | §8.3 Phase 3: TOCTOU guard — protect-set re-checked before each `fs.delete`, row restored if the victim was promoted mid-flight; file-first ordering rejected (reintroduces C.1's worse orphan class) | R6-2 |
| 11 | §12.2/§11.9: `URLCache` singleton discipline (once in `App.init`, injected into thumbnailer, views never construct one) + memory-warning flush | R6-3 |

---

## Changelog v1.2 → v1.3 (fourth review round)



| # | Change | Source |
|---|---|---|
| 1 | **Engine window contract**: `TrackChanged(itemId, reason)` / `ItemEnded` / `QueueExhausted` events; opaque `itemId` addressing (`provider:songId:bitrate`); engine plays a 1–2 item window while the orchestrator owns THE queue; `preloadNext` → `replaceUpNext`. Kills Kotlin↔native desync (wrong lock-screen/history/prefetch after native advance) | R4-B1 |
| 2 | **Toolchain pinning**: exact Kotlin version pinned to installed Xcode major (no ranges); framework-link task added to M0 exit criteria; CI Xcode version pinned | R4-B2 |
| 3 | **Probe split**: `probe:local` (all checks, milestone-gating, real network, results stamped date+region) vs `probe:ci` (structural only, nightly). Geo-blocking would have made a hosted nightly meaningless — P5 would measure the wrong catalog | R4-B3 |
| 4 | **WS correlation decided by P4**: single-flight is incompatible with the <300 ms suggestion SLO under bursts (serialized RTT math). Strategy now data-driven: echo ⇒ match-on-echo · ordered ⇒ FIFO deque pairing with divergence fallback · unordered ⇒ single-flight + SLO relaxed to <700 ms p95. Strikes consecutive-only; timeout ≠ socket-error | R4-B4 |
| 5 | **Scope & ownership**: `appScope` = SupervisorJob + state lane + logging exception handler; engine attachable via holder (service onCreate/onDestroy); intents while detached buffered; detach ⇒ PAUSED preserving snapshot; base class reverted to **MediaSessionService** (Library callbacks unjustified for a non-goal) | R4-B5 |
| 6 | §7.3: 429/503 branch **terminates** (was falling through and appending the error body into `.part`); status classification made exhaustive | R4-M1 |
| 7 | §7.3: same-bitrate delete guard (`old.bitrate != q`) — was deleting the freshly renamed file on corrupt-recovery re-downloads | R4-M2 |
| 8 | §7.3: stall detector moved into a **concurrent watchdog** (the post-`copyTo` check was unreachable dead code) | R4-M3 |
| 9 | §7.3: single `resolveCount` capped at the call site (`resignCount` never actually bounded resolve calls across retries/restarts) | R4-M4 |
| 10 | §7.3: corrupt paths clean up their artifacts; `ftyp` verified on `.part` **pre-rename** (resumed files still carry bytes 4–8) | R4-M5 |
| 11 | §7.3: missing Content-Length ⇒ Content-Range-total fallback, else ≥0.90×raw-estimate floor — silent-truncation hole closed | R4-M6 |
| 12 | Bridge sample fixed: `[weak self]`, `deinit { cancel }`, `.conflate()` before `flowOn` (BUFFERED channel was queuing stale states), errors logged, `Cancellable`→`KotlinSubscription` (Combine name collision) | R4-M7 |
| 13 | Pre-flight eviction counts **net-new** bytes only (existing `.part` no longer double-counted on resume — was over-evicting on flaky networks) | R4-M8 |
| 14 | Post-download pass is `enforceBudget(0)` (row already inserted; passing `finalSize` double-counted it) | R4-M9 |
| 15 | GC rewritten with per-table `NOT EXISTS` (+ `download_intents` + resume-queue exclusions); concatenation-collision and index-defeating `\|\|` removed; survival test added | R4-M10, R5-patch2 |
| 16 | `shuffleOrder: PersistentList<Int>?` (one unstable field poisons the whole class); stability-config restored; Compose-compiler-metrics CI assertion added | R4-M11 |
| 17 | `ACCESS_NETWORK_STATE` permission added — metered detection would have thrown `SecurityException` on first cellular download | R4-F1 |
| 18 | Pinned demotion ordered by `pinned_at_ms ASC` (NULL `last_used_ms` sorts first in ASC ⇒ brand-new favorites were demoted first — inverted intent) | R4-F2 |
| 19 | Pinned budget enforced on favorite-add too, not only at download time | R4-F3 |
| 20 | Protect-set injected as `StateFlow<Set<SongKey>>` from orchestrator (breaks the CacheManager→Orchestrator dependency cycle) | R4-F4 |
| 21 | Source row of an in-flight quality upgrade joins the protect set (upgrade failure no longer loses the track entirely) | R4-F5 |
| 22 | `home_cache` eviction added (30 d age + 200-row LRU cap) — TTL governed freshness, never deletion | R4-F6 |
| 23 | Per-host circuit breakers (`www.jiosaavn.com` vs `web.saavncdn.com`) — a throttled CDN download no longer blanks type-ahead search | R4-F7 |
| 24 | USER_NOW enqueued during a breaker pause fails fast with RATE_LIMITED (no stranded DOWNLOADING spinner for minutes) | R4-F8 |
| 25 | File extension derived from response `type`/Content-Type; unexpected types rejected UNSUPPORTED (AVPlayer is extension-sensitive) | R4-F9 |
| 26 | `preferredForwardBufferDuration` deleted — no-op for local files; false gapless confidence removed | R4-F10 |
| 27 | Kotlin-side uncaught-exception hook added (`NSSetUncaughtExceptionHandler` misses Kotlin `abort()` crashes) | R4-F11 |
| 28 | MockK confined to `androidUnitTest`; commonTest suites use hand-written fakes exclusively | R4-F12 |
| 29 | Coil memory cache absolute 48 MB cap (25 %-of-heap broke the 200 MB memory SLO arithmetic) | R4-F13 |
| 30 | iOS bridging accessor `queueAsList()` (`PersistentList` doesn't bridge to Swift collections; copy cost measured in M1.5) | R4-F14 |
| 31 | DB access unified on one `dbLane` including reads (conservatism/simplicity — corrected rationale); explicit DB paths; `PRAGMA wal_checkpoint(TRUNCATE)` named; iOS Data Protection class explicit | R5-1.1 + smaller items |
| 32 | Bulk client sends `Accept-Encoding: identity` (gzip would break exact Content-Length verification); apiClient retries 5xx-only so it never re-fires a 429 past the breaker | R5-3.3, R4-smaller |
| 33 | `play_count` counts sessions with ≥30 s listened OR completion (repeat-loops now qualify tracks for quality upgrades; skips don't inflate) | R5-2.2 refined |
| 34 | Unfavorite ⇒ unpin mechanism + test; resume snapshot bumped to `"v":2` with composite keys, restore skips missing songs and clamps index | R5-2.1, R5-2.3, R4-smaller |
| 35 | HTTP 416 handled as guarded restart-clean; settle-thrash test pinned (5 Next taps within 1 s ⇒ ≤1 download started) | R5-2.5, R5-1.6 test-half |
| 36 | Eviction drops `bytes DESC` tiebreak (systematically evicted 320-files first — quality drift); two-step victim select (mobile SQLite lacks UPDATE..ORDER BY/LIMIT); tx scoping fixed (file I/O outside the write transaction); notifications emitted after commit | R5-patch4, R4-smaller |
| 37 | Progress percent ×100 (integer division always yielded 0); favorites export carries both art URLs; `DylanFailure(code, key, detail)` replaces enum+StringRes; sealed `Phase.Error(failure)` makes illegal states unrepresentable | R4-smaller |
| 38 | Edge-to-edge + predictive-back handling noted for targetSdk 36; goldens reduced to 8 states × 2 themes + representative 200 % subset; SubsonicProvider made an optional M4 stretch goal | R4-smaller, R4-closing |

---

## Changelog v1.1 → v1.2 (third review round)

| # | Change | Source |
|---|---|---|
| 1 | **Bridge rewritten**: Kotlin-owned `FlowAdapter` (real cancellation, real generics, `flowOn(Default)`, Main-delivery, bounded buffering); Swift side is a thin sink; store uses `@Observable`. Old snippet deleted — it leaked collectors, force-cast, collected on main, buffered unbounded | R1-B.1, R2-1.3 |
| 2 | **Engine split**: Swift implements only imperative `NativeAudioOutput`; Kotlin owns `events`/`positionFlow`; `prepare` non-suspend + `Prepared` event | R1-B.2 |
| 3 | **Two HttpClients** (`api` / `bulk`); `defaultRequest` no longer leaks params onto signed CDN URLs; transport-level retry removed from downloads (§7.3 owns retry) | R1-B.3 |
| 4 | **Pinned pool bounded** at 75 % of cache budget with LRU demotion (files stay, become evictable; favorites survive); pin-on-first-download bug fixed (`pinned = favorited OR wasPinnedBefore`); new `USER_BULK` priority for album-favorites | R1-B.4, R2-1.5, R3-C |
| 5 | **Non-cacheable tracks are unplayable in v1** (greyed rows, badge before tap, error copy); contradiction with §1.2/Law 1 resolved; probe measures the actual % | R1-B.5 |
| 6 | **Quality dedupe by sufficiency** — never downgrade, never spend cellular upgrading; `QUALITY_UPGRADE` trigger finally defined | R1-B.6 |
| 7 | **Preemption**: a new `USER_NOW` cancels a stale executing `USER_NOW` (keeps `.part`, demotes to prefetch if still queued). Two-slot design considered and rejected | R1-B.7, R2-2.2 |
| 8 | **`downloadProgress` removed from `PlayerState`** into its own keyed throttled flow; `nextUp` derived, not stored; `PersistentList` proves Compose stability | R1-B.8 |
| 9 | **Eviction ordering fixed**: `last_used_ms` nullable (+ `play_count`), prefetched-but-unplayed rows evict first; dead `IS NULL` clause resurrected; dedicated unit test | R1-B.9, R3-A |
| 10 | **WS correlation redesigned**: mandatory single-flight (order-independent), protocol-level ping (`pingIntervalMillis`), reconnect on field-focus not keystroke, 800 ms per-request timeout feeding the 3-strike HTTP fallback | R1-B.10 |
| 11 | Eviction deletes **row before file**, runs on the write lane inside a transaction reading SUM/COUNT | R1-C.1 |
| 12 | Schema: composite `(provider, song_id)` keys, nullable `resolve_ref`, `perma_token` recovery path; export format gains `provider`; hard resolve failure re-fetches via token once | R1-C.2 |
| 13 | **`songs` admission rule + weekly GC** — search results live in memory; only played/favorited/cached/queued/album-opened songs persist | R1-C.3 |
| 14 | Verification by exact **Content-Length**; `ftyp` checked post-rename; pre-flight estimate padded ×1.25; eviction re-run when real size exceeds estimate >20 % | R1-C.4, R3-E3/E4 |
| 15 | Range resume hardened: **`If-Range` + ETag**, `rangeRestarts ≤ 1` guard, per-attempt re-resolve with global cap 2; `resumed_after_resign` diagnostic tag | R1-C.5, R3-E3 |
| 16 | Engine-level **429 circuit breaker** shared by downloads and search; honors `Retry-After` | R1-C.6 |
| 17 | **Stall detector** (20 s no-progress or total-deadline) | R1-C.7 |
| 18 | iOS audio session split: `setCategory` at launch (non-interrupting), `setActive(true)` at first play, throw handled | R1-C.8 |
| 19 | `.part` bytes counted in budget; concurrent partials capped at 3 | R1-C.9 |
| 20 | Clear-cache excludes protected set `{current, next, in-flight}`; dialog says so | R1-C.10 |
| 21 | `download_intents` table replaces settings blob; resume blob versioned + tolerant parse | R1-C.11 |
| 22 | Album detail cached via `home_cache["album:<id>"]` TTL 7 d SWR — offline album pages work | R1-C.12 |
| 23 | Notification-denied degradation card + deep-link to settings | R1-C.13 |
| 24 | Position polling truth: Android polls on the player's application looper; "zero polling" claim reworded | R1-C.14 |
| 25 | SLO table completed: search latency, uncached transition, battery/hr, DB-open, p95 cold launch, 500-item p99 scroll | R1-D |
| 26 | Prefetch on cellular **enabled by default for exactly one track at 128 kbps**, triggered at track start (not 50 %) | R1-D |
| 27 | Now Playing blur pipelined (64 px downscale → blur once → cache per song); marquee gated on Reduce Motion ∧ visibility | R1-D |
| 28 | Golden/a11y test suites added; CI runs `xcodebuild test` (tokens parity actually executes) | R1-D |
| 29 | Error taxonomy collapsed to one `DylanError`; `GEO_BLOCKED` added | R1-D |
| 30 | Probe expanded to 12 checks incl. **Range/206 support, WS ordering/echo, non-cacheable %, HTTP-autocomplete existence, re-sign stability, bitrate calibration**; TTL compared against response `Date` header | R1-F |
| 31 | **M0 exit criteria rewritten** (probe gates + leak-verified bridge spike + real-audio-through-orchestrator proof); **M1.5 inserted** ("one song end-to-end, both platforms") | R1-G |
| 32 | Metered-network detection via OS capabilities (`NOT_METERED` / `isExpensive`), never transport type | R3-E1 |
| 33 | `SearchChannelTest` mandated (fake WS: out-of-order, silence, refusals, mid-typing backgrounding) | R3-E2 |
| 34 | Crash-safe diagnostics: uncaught-exception handlers flush the ring buffer synchronously | R3-E6 |
| 35 | iOS image decoding: hand-rolled ImageIO thumbnailer (no new deps) | R3-E5 |
| 36 | Misc: normalized search-history keys, pagination id-dedupe, reco-section hide-on-error, gapless encoder-metadata caveat, DB-dir backup intent stated, drills marked human-checklist, 4 h soak test, Android background-download limitation documented, transition-table default rule, import fallback re-search, scrubbing local-state note, ducking note, log redaction of `resolve_ref` | R2, R3 |

### Rejected recommendations (with rationale)

| Recommendation | Verdict |
|---|---|
| Two download slots (user/prefetch) — R2-2.2 | **Rejected.** The 350 ms settle timer caps the USER_NOW queue at depth ≈1; preemption handles the stale-job case. A second slot doubles bandwidth contention on LTE for no UX gain. Single slot + preemption is simpler and strictly ordered. |
| `kotlin-inject` DI — R2-5.1 | **Rejected.** ~50 classes; manual graph stays readable and test-overridable via constructor parameters. Revisit only if the graph exceeds one screen. |
| Split `SuggestionsProvider` / `SearchProvider` — R2-5.2 | **Rejected.** Law 3: every seam needs a nameable second implementation *today*. Suggestions has two (WS, HTTP-autocomplete); fullResults has one. Splitting would create a single-impl seam. One `SearchChannel` with two implementations satisfies the law exactly. |
| Build watermark server in M2 — R2-7.1 | **Rejected for scope.** User explicitly phased watermark streaming to v2 (D2). Design stays in §19.1. |
| `provider_version` hash column — R2-7.5 | **Rejected.** Fixture tests + probe detect drift; a stored hash adds bookkeeping without adding capability. |
| Drop WebSocket typing search — R3-F | **Rejected by mandate.** User decision D8 explicitly requires WS-while-typing. Mitigated instead: single-flight removes the ordering hazard class entirely, protocol ping replaces text-heartbeat, focus-reconnect, per-request timeout, 3-strike fallback, mandatory `SearchChannelTest`. Honest cost note recorded in §6.4. |
| Decouple favorite-from-pin entirely — R3-C option 2 | **Partially rejected.** D4 semantics (favorite ⇒ offline pin) retained as product intent; the *safety problem* is solved by the bounded pinned pool + demotion. Revisit trigger recorded: if demotion fires regularly in use, promote "Keep offline" to an explicit separate action. |
| "`last_used_ms IS NULL` ordering is backwards — use ASC" — R5-1.2 | **Rejected — reviewer arithmetic inverted.** `(x IS NULL)` evaluates to 1 for NULL rows, so `DESC` puts never-played rows *first*, exactly as intended (SQLite also sorts bare NULLs first under ASC). The mandated unit test pins the direction so this stays settled empirically. |
| Poll `currentPosition` off the player looper — R5-3.1 | **Rejected as written** — off-looper reads throw `IllegalStateException`. The underlying concern (stall blocking UI) is accepted; fix is a dedicated media `HandlerThread` looper owning both player and sampler (§9.9), keeping Main untouched by construction. |
| `WeakReference` the bridge Job — R5-3.2 | **Rejected.** A weakly-referenced Job can be collected while still running — nondeterministic cancellation is worse than the leak it pretends to fix. Real protection: the `onTermination` contract plus the M0 Instruments leak gate. |
| Debounce USER_NOW preemption by 2 s — R5-1.6 | **Rejected.** The 350 ms settle timer already collapses rapid Next taps into ≤1 download start (test pins this). An extra debounce only adds lag to deliberate sequential navigation. |
| Flip prefetch-on-cellular back to opt-in — R5-1.5 | **Rejected.** Strict download-first otherwise makes every mobile-data transition a multi-second gap. Default stays ONE track @128 kbps with visible usage copy ("~3 MB/track") and a settings toggle; trivially flippable in AppConfig for personal use. |
| Relax Content-Length check to ≥95 % unconditionally — R5-1.4 | **Partially rejected.** Exact equality stays primary: gzip is disabled at the client (removing the main lie-source) and CloudFront static objects carry truthful lengths. If probe P3 ever shows lying lengths, relaxation becomes evidence-backed. Absent-length truncation is closed separately (v1.3 #11). |
| Force-320 hidden settings toggle — R5-5.2 | **Rejected for v1 UI.** An AppConfig edit achieves the same on a personal device without settings sprawl. |
| Build SubsonicProvider in M4 (required) — R4-closing | **Deferred to optional stretch.** Test fakes satisfy Law 3's nameable-second-implementation requirement today; a full second provider is product work for a catalog not yet in use. Spike remains available if the seam shows strain. |

---

## Evidence annotation policy

Every load-bearing claim in this document is tagged:

- `[verified: HAR-n]` — observed directly in captured traffic.
- `[verified: probe]` — asserted by `tools/probe.main.kts` (must pass before M0 exits).
- `[verified: spike]` — proven by an M0/M1.5 code spike on device.
- `[assumed → probe Pn]` — inference today; the named probe check converts it to fact.

M0's job is to turn every `[assumed]` into one of the other two. Claims that fail their probe trigger plan revision, not workaround code.

---

# Part 0 — Decision Log (immutable record)

| # | Question | Decision |
|---|---|---|
| D1 | Cross-platform strategy | KMP shared core + native UI (Jetpack Compose / SwiftUI) |
| D2 | Playback model v1 | Strict download-first (play only from verified local file). Watermark streaming + 128-first ladder = v2 (§19.1) |
| D3 | Audio quality | In-app 128/320 toggle. Metered network forces 128. One cached quality per song; sufficiency-dedupe (§7.3); upgrades only on unmetered + earned (favorited ∨ plays ≥ 2); replacement atomic replace-after-verify |
| D4 | Cache budget | ≤ 300 files ∧ ≤ 2 GB audio. LRU eviction, pre-flight. Favorites auto-pin into a **bounded pool (≤ 75 % of budget)** with LRU demotion — favorites list survives demotion, files become evictable |
| D5 | MVP scope | Core scope per original Q&A + additions adopted through reviews (downloads sheet, export/import, diagnostics, degradation cards) |
| D6 | Product mode | Personal use only. Not publicly distributed. Stance §1.2 |
| D7 | OS floors | minSdk 34 / targetSdk latest stable · iOS 26.0. Honest rationale: developer's own devices. Relaxing later costs nothing |
| D8 | Search transport | **Both**: persistent WS drives as-you-type results; Enter calls HTTP `search.getResults`. WS hardened per §6.4; user-mandated despite complexity tradeoff (recorded) |
| D9 | Auth | None. Cookie-less verified across all captures `[verified: HAR-1..7]` |
| D10 | DI / deps | Manual `AppContainer`. No Hilt/Koin/DataStore/WorkManager/Rx/Room/Coil-iOS/image libs. Platform navigation used (Navigation-Compose / NavigationStack) — amended: "no third-party navigation abstraction", not "no navigation library" |
| D11 | Crypto | None required today. Media-source fields treated as opaque; wording avoids "no crypto ever" |
| D12 | Pinned pool | 75 % sub-budget, LRU demotion (unpin oldest, keep files until naturally evicted), one-time user notice, Settings visibility line |
| D13 | Non-cacheable content | Unplayable in v1: greyed row, "Not available offline" badge pre-tap, error copy on tap. Probe P5 measures catalog %; revisit if > 5 % |
| D14 | Network layer | Two clients (api/bulk). Metered detection: Android `NET_CAPABILITY_NOT_METERED`, iOS `NWPath.isExpensive/isConstrained` — never transport type |
| D15 | Download concurrency | Single execution slot; USER_NOW > USER_BULK > PREFETCH_NEXT; USER_NOW preempts stale USER_NOW; settle timer prevents thrash |
| D16 | Quality transitions | Dedupe on sufficiency (cached ≥ requested ⇒ done); downgrade never; upgrade only unmetered ∧ (favorited ∨ plays ≥ 2) ∧ idle |
| D17 | WS lifecycle | Protocol ping 25 s; connect on Search-tab entry/field focus; close on background; 800 ms per-request timeout ⇒ serve that query over HTTP + strike; strikes consecutive-only, tracked per kind; 3 in a row ⇒ HTTP for session. Correlation/concurrency mode defers to D22 (P4-decided) |
| D18 | Catalog persistence | Admission rule + GC (§5.3); composite provider keys; nullable `resolve_ref` + `perma_token` recovery |
| D19 | Evidence discipline | Annotation policy above; M0 converts assumptions to facts; failing probe ⇒ revise plan, don't code around it |
| D20 | Engine seam contract | Opaque `itemId` addressing (`provider:songId:bitrate`); engine plays a 1–2 item window; `TrackChanged`/`ItemEnded`/`QueueExhausted` events; orchestrator owns THE queue and refills via `replaceUpNext` |
| D21 | Probe execution split | `probe:local` gates milestones (real network, stamped date+region results); `probe:ci` runs structural checks only, nightly. P4/P5 outputs are recorded decisions, not trivia |
| D22 | WS correlation | Strategy chosen by probe P4: echo ⇒ match-on-echo; ordered-no-echo ⇒ FIFO deque pairing (divergence ≥3 ⇒ single-flight); unordered ⇒ single-flight with suggestion SLO relaxed to <700 ms p95 |
| D23 | Scope & ownership | `appScope` = SupervisorJob + state lane + logging exception handler (never rethrow); engine attachable/detachable via holder; media service attaches in onCreate, detaches in onDestroy; detach ⇒ PAUSED + snapshot written |
| D24 | DB discipline | ALL operations (reads included) on one `dbLane` — conservatism and simplicity, not corruption paranoia (workload is tiny; read concurrency buys nothing measurable); explicit DB paths; named WAL checkpoint; Data Protection class explicit |
| D25 | Download integrity | Exact Content-Length equality primary; gzip disabled on bulk client; any relaxation requires probe evidence first; absent-length truncation closed by Content-Range-total / raw-floor fallback |
| D26 | Cellular prefetch | Stays ON by default: exactly one next track @128 kbps, "~3 MB/track" usage copy in Settings, user-toggleable |

---

# Part 1 — Product Definition & Constraints

## 1.1 What DYLAN is
A two-platform, one-core personal music client: instant search (WS type-ahead + HTTP full results), browse trending/albums, download-and-play with a bounded offline cache, favorites, history — minimal cute-professional UI.

## 1.2 Legal / operational stance
- Catalog served by JioSaavn's **unofficial web endpoints**. No ToS grant, no license to redistribute.
- Personal mode consequences: sideload APK / personal-team TestFlight; no sharing surfaces; no lyrics; artwork cached in-app only.
- Per-track `rights.cacheable`: **v1 treats non-cacheable tracks as unplayable** (D13). Probe P5 quantifies how much catalog this affects; if > 5 %, this becomes a product-defining constraint requiring the v2 streaming path earlier than planned.
- Exit ramp: `MusicProvider` seam → licensed/owned catalog (Subsonic/Navidrome/local) without touching domain/UI (§19.3).

## 1.3 Non-goals (v1)
Accounts/auth · analytics/crash SDKs · feature flags · CarPlay/Android Auto · Chromecast/AirPlay routing UI · podcasts/shows · playlists CRUD · lyrics · EQ · crossfade · tablet layouts · widgets/watch · public distribution.

---

# Part 2 — Design Philosophy: The Nine Laws

1. **Playback consumes a verified local file.** A signed URL exists solely to create/refresh that file. Exactly one code path owns cache writes (the download worker). *(v2 watermark keeps the law — only the writer changes.)*
2. **The server is the catalog; SQLite holds user-owned projections only.** Persisted forever: admitted `songs` refs, `favorites`, `play_history`, `search_history`, `settings`, `home_cache` (TTL'd). Never mirrored: raw search results, unadmitted catalog rows.
3. **Swappable ⇒ interface — and a seam must have a nameable second implementation today.** Seams: `MusicProvider` (Saavn ↔ Subsonic ↔ test fake), `SearchChannel` (WS ↔ HTTP-autocomplete — both ship in v1), `PlayerEngine` (ExoPlayer ↔ AVPlayer ↔ test fake). Everything else concrete until proven otherwise.
4. **One logical playback state machine.** UI dispatches intents to `PlaybackOrchestrator`; platforms render its `StateFlow`. Lock-screen/remote controls route through the same intents. Physical engines native and platform-owned; reactive surface Kotlin-owned (§9.4).
5. **Smallest lifecycle-correct primitive wins.** Coroutines own scheduling while the process owns lifetime; OS facilities own survival. Durable-execution frameworks enter only on their named triggers (§19.2).
6. **No main-thread I/O — enforced by construction.** Dispatchers injected (`AppDispatchers`); repositories cannot block; shared flows collect off-main and deliver on main (§9.11). Writes funnel through a single-writer lane.
7. **Provider quirks die at the adapter boundary.** String numbers, `data_N` buckets, image rewriting, encrypted blobs: quarantined in `provider/saavn/`. Drift breaks one file and trips fixtures + probe.
8. **Performance is written down or it doesn't exist.** SLOs (§10) include unhappy paths; p95, not p50.
9. **Readable beats clever.** Linear coroutines, immutable data, exhaustive switches, no reflection tricks. Complexity must buy measured value — WS transport is retained under protest of this law, by explicit user mandate (D17 note).

---

# Part 3 — Verified API Contract

All requests: `GET https://www.jiosaavn.com/api.php?<common>` where
`common = api_version=4&_format=json&ctx=web6dot0&_marker=0` plus browser-like `User-Agent`.

## 3.1 Endpoint inventory

| Purpose | Call | Shape |
|---|---|---|
| Trending (home) | `content.getTrending&entity_type=album&entity_language=hindi` | Array[24] mini-album cards |
| Top searches | `content.getTopSearches` | Array mini cards (`mini_obj:true`) |
| Related albums | `reco.getAlbumReco&albumid=<id>` | Array[16] mini albums |
| Album detail | `webapi.get&token=<albumToken>&type=album` | `{…, list:[fullSong…]}` |
| Full song search | `search.getResults&q=<q>&p=<page>&n=20` | `{total,start,results:[fullSong…]}` offset pagination |
| Autocomplete | **WS** send `{"url":"/api.php?__call=autocomplete.get&query=<q>&…common…"}` | recv `{"action":"search","resp":"<json-string>"}` → `{modules:[{title,position,source:"data_N"}], data_0..data_6}` |
| Sign media URL | `song.generateAuthToken&url=<encrypted_media_url>&bitrate=128\|320` | `{"auth_url":"https://web.saavncdn.com/…_160.mp4?Expires=…&Signature=…&Key-Pair-Id=…","type":"mp4","status":"success"}` |

Full-song consumed fields: `id,title,subtitle,type="song",perma_url,image(-150x150.jpg),language,year,play_count,explicit_content, more_info{album_id,album,duration,"320kbps",encrypted_media_url,rights{cacheable},artistMap.primary_artists[{name}]}`.

## 3.2 Hard-won facts
1. **Zero cookies anywhere** — api.php sends/receives none; WS handshake carries none `[verified: HAR-1..7]`.
2. **Signed-URL TTL = 5 minutes** (`Expires − response Date`) `[verified: HAR-2]`. Resolve inside the worker at dequeue; never persist `auth_url`; 401/403 ⇒ re-sign once per attempt, global cap 2 (§7.3).
3. `bitrate=128` serves `_160.mp4` (AAC ~128–160 kbps VBR); `320` → `_320.mp4` gated by `"320kbps":"true"` `[verified: HAR-2]`. Actual average bitrates calibrated by probe P7.
4. Autocomplete songs are mini-objects (no media URL) — typing-path taps jump through full-results (§9.6).
5. Images: `-150x150.jpg` → `-500x500.jpg` rewrite regex-guarded in adapter, original URL fallback `[assumed → probe P10]`.
6. Radio endpoints returned empty/errors in captures — excluded.
7. **CDN Range support is NOT yet verified** — the entire resume design depends on it `[assumed → probe P1, highest-value check]`.
8. **CDN object stability across re-signs** (same bytes under two signatures) `[assumed → probe P6]`; `If-Range` guards the risk regardless (§7.5).
9. **WS response ordering/echo behavior** `[assumed → probe P4, decision gate]` — the outcome selects the correlation strategy (§6.4): echo ⇒ match-on-echo; ordered ⇒ FIFO pairing; unordered ⇒ single-flight with relaxed SLO.
10. **HTTP autocomplete availability** (`__call=autocomplete.get` as plain GET) `[assumed → probe P8]` — required for the fallback path to exist.
11. **CDN `If-Range` semantics** validated alongside Range support by probe P1 (bogus etag ⇒ 200-full; matching etag ⇒ 206) `[assumed → probe P1]`.
12. **Geo-targeting**: catalog and CDN behavior differ off Indian networks — every gating probe result must come from `probe:local` on the real usage network; hosted CI runs structural checks only (D21).

---

# Part 4 — Architecture

## 4.1 System diagram

```
┌───────────────────────────────┐      ┌───────────────────────────────┐
│ androidApp (Compose, API 34+) │      │ iosApp (SwiftUI, iOS 26+)     │
│ Screens ← ViewModels          │      │ Screens ← @Observable Store   │
│ DylanMediaService             │      │ AudioEngine.swift             │
│  └ ExoPlayerEngine            │      │  └ AvAudioOutput:             │
│    (MediaSessionService,      │      │    NativeAudioOutput          │
│     own media HandlerThread)  │      │                               │
└──────────────┬────────────────┘      └──────────────┬────────────────┘
               │   shared Kotlin core (`shared/`)      │
┌──────────────┴───────────────────────────────────────┴────────────────┐
│ ui-shared: Tokens.kt · Strings.kt                                     │
│ PlaybackOrchestrator (intents → StateFlow<PlayerState>; queue/shuffle/ │
│   repeat policy; prefetch triggers; resume snapshot; history)         │
│   + positionMs: Flow<Long>  + downloadProgress: Flow<Map<SongKey,Int>>│
│   + protectedKeys: StateFlow<Set<SongKey>>  (feeds CacheManager, F4)  │
│ UseCases: ObserveHome · Suggestions · FullSearch · GetAlbum           │
│ DownloadEngine (single slot; priorities; settle; stall watchdog;       │
│   per-host breakers; resolve-at-dequeue; If-Range resume; verify)     │
│ CacheManager (pre-flight LRU; pinned pool; budgets; sweep)            │
│ LocalSearch (LIKE over admitted songs) · FavoritesRepo · HistoryRepo  │
├──────── SEAMS (the only interfaces) ─────────────────────────────────┤
│ MusicProvider ◄── saavn/SaavnProvider        [alt: Subsonic, fakes]   │
│ SearchChannel ◄── saavn/SaavnSearchChannel   [WS impl + HTTP impl]    │
│ PlayerEngine  ◄── ExoPlayerEngine · IosPlayerEngine(out:NativeAudioOutput) │
│                                    └ NativeAudioOutput ◄── Swift impl │
├───────────────────────────────────────────────────────────────────────┤
│ Db (SQLDelight WAL — ALL ops on one dbLane) · okio fs · AppConfig     │
│ apiClient / bulkClient (Ktor; OkHttp/Darwin engines)                  │
│ AppDispatchers(main · io · dbLane=io.limitedParallelism(1) ·          │
│                state=Default.limitedParallelism(1))                   │
│ appScope = SupervisorJob + state lane + logging exception handler     │
└───────────────────────────────────────────────────────────────────────┘
```

**State-lane discipline (R7-footgun):** the orchestrator, the exception handler, and every intent
transition share `state` (parallelism 1) — one blocking call deadlocks the whole app. Hard rules:
**no `runBlocking` on the state lane; no waiting on engine calls** (`prepare` is non-suspend by
contract §9.4; readiness arrives as a `Prepared` *event*, never as a state-lane join); file and
network I/O stay on `io`. Engine calls are marshalled to their own threads (§9.9/§9.10).

## 4.2 Module tree

```
dylan/
├── settings.gradle.kts · build.gradle.kts · gradle/libs.versions.toml
├── shared/
│   └── src/
│       ├── commonMain/kotlin/dylan/
│       │   ├── config/AppConfig.kt
│       │   ├── model/          # Song, SongKey(provider,id), Album, HomeFeed,
│       │   │                   # SearchSections, PlayerState(+sealed Phase), Repeat,
│       │   │                   # DylanFailure(code,key,detail), Quality…
│       │   ├── provider/MusicProvider.kt
│       │   ├── provider/saavn/ # SaavnProvider, SaavnSearchChannel(ws+http),
│       │   │                   # dto/*, Mapper.kt, ImageUrl.kt  ← quarantine zone
│       │   ├── net/Clients.kt  # apiClient + bulkClient builders
│       │   ├── search/SearchChannel.kt
│       │   ├── playback/Orchestrator.kt, Intents.kt, Shuffle.kt, ResumeSnapshot.kt
│       │   ├── download/DownloadEngine.kt, Jobs.kt, Breaker.kt
│       │   ├── cache/CacheManager.kt, Paths.kt, Reconciler.kt
│       │   ├── db/*.sq, Database.kt, DriverFactory expect/actual
│       │   ├── repo/Favorites.kt, History.kt, SettingsStore.kt, HomeCache.kt
│       │   ├── diag/LogBuffer.kt
│       │   ├── util/AppDispatchers.kt, NetClass.kt (expect/actual metered detect)
│       │   ├── bridge/FlowAdapter.kt     # see §9.11 — the whole Swift bridge
│       │   └── di/AppContainer.kt
│       ├── androidMain/  # DriverFactory, NetClass(NOT_METERED), FreeSpace
│       └── iosMain/      # DriverFactory, NetClass(isExpensive), FreeSpace,
│                         # IosPlayerEngine (owns flows; delegates to NativeAudioOutput)
├── androidApp/           # Compose UI + DylanMediaService + ExoPlayerEngine
├── iosApp/
│   ├── Views/ …          # SwiftUI (@Observable stores)
│   ├── Audio/NativeAudioOutputImpl.swift   # AVQueuePlayer wrapper (imperative only)
│   ├── Bridge/DylanBridge.swift            # asyncStream<T>(adapter) helper
│   └── Imaging/Thumbnailer.swift           # ImageIO downsample decoder (§11.9)
├── tools/probe.main.kts
└── fixtures/             # sanitized real responses (§13.1)
```

## 4.3 Ownership rules

```kotlin
class AppContainer(...) {
    // B5a: one scope, supervised, exceptions logged — a prefetch crash must never cancel playback.
    val appScope = CoroutineScope(SupervisorJob() + dispatchers.state +
        CoroutineExceptionHandler { _, t -> logBuffer.error("scope", t) })

    // B5b: the engine is ATTACHABLE, not owned-by-everyone.
    private val _engine = MutableStateFlow<PlayerEngine?>(null)
    val engine: StateFlow<PlayerEngine?> = _engine.asStateFlow()
    fun attachEngine(e: PlayerEngine) { _engine.value = e }
    fun detachEngine() { _engine.value?.release(); _engine.value = null }

    val protectedKeys = MutableStateFlow<Set<SongKey>>(emptySet())   // F4: feeds CacheManager
}
```

- Process-scoped singletons in `AppContainer`, created once at app entry. `DylanMediaService` **attaches** its engine in `onCreate` and **detaches** in `onDestroy` — it never constructs a second graph, and the container never holds a released player (no leaked `AudioTrack`/WakeLock, no dangling reference).
- Intents arriving while detached: `PlayNow`/`TogglePlayPause` are buffered (one slot each) and drained on attach; everything else drops with a debug log. Detach transitions state to **PAUSED preserving queue/position** and writes the snapshot.
- Orchestrator mutates state on the `state` lane (serialized); UI collects on Main. It publishes `protectedKeys` (`{current, next, in-flight, upgrade-sources}`) for the CacheManager — injection direction only, no cycle (F4).
- **All** DB operations — reads included — run on `dbLane`. Rationale (D24): conservatism and simplicity, not corruption paranoia; the workload is tiny so read-concurrency buys nothing measurable, and one lane deletes an entire class of platform-specific doubt. Eviction is serialized with inserts by construction.

---

# Part 5 — Data Layer (SQLite / SQLDelight)

## 5.1 Schema (`dylan.sq`)

```sql
-- DURABLE. Admitted references only (see admission rule §5.3). Never auto-evicted.
CREATE TABLE songs (
  provider      TEXT NOT NULL DEFAULT 'saavn',
  song_id       TEXT NOT NULL,
  title         TEXT NOT NULL,
  subtitle      TEXT NOT NULL,
  album_id      TEXT,
  album_name    TEXT,
  art_url_150   TEXT NOT NULL,
  art_url_500   TEXT NOT NULL,
  duration_s    INTEGER NOT NULL,
  has_320       INTEGER NOT NULL,
  cacheable     INTEGER NOT NULL DEFAULT 1,
  resolve_ref   TEXT,                -- nullable: opaque encrypted_media_url (may be absent/withdrawn)
  perma_token   TEXT,                -- recovery path: webapi.get&type=song re-fetch
  updated_at_ms INTEGER NOT NULL,
  PRIMARY KEY (provider, song_id)
);

-- DISPOSABLE index over the audio directory. Rebuildable from disk alone.
CREATE TABLE cached_files (
  provider     TEXT NOT NULL,
  song_id      TEXT NOT NULL,
  bitrate      INTEGER NOT NULL,     -- 128|320
  ext          TEXT NOT NULL DEFAULT 'm4a',  -- derived from response type (F9); AVPlayer is extension-sensitive
  bytes        INTEGER NOT NULL,
  cached_at_ms INTEGER NOT NULL,
  last_used_ms INTEGER,              -- NULL = downloaded, never played (drives eviction!)
  play_count   INTEGER NOT NULL DEFAULT 0,
  pinned       INTEGER NOT NULL DEFAULT 0,
  pinned_at_ms INTEGER,              -- set at pin time; demotion orders by this (F2)
  PRIMARY KEY (provider, song_id),
  FOREIGN KEY (provider, song_id) REFERENCES songs(provider, song_id) ON DELETE CASCADE
);
CREATE INDEX idx_cached_lru_unpinned ON cached_files(last_used_ms, bytes DESC) WHERE pinned = 0;
-- NOTE: the LRU query leads with (play_count=0)/(last_used_ms IS NULL) predicates, so this index is
-- advisory at best; at ≤300 rows it is irrelevant — kept for the pinned=0 partial filter only.
CREATE INDEX idx_cached_pinned       ON cached_files(pinned_at_ms)             WHERE pinned = 1;

CREATE TABLE favorites (
  provider    TEXT NOT NULL,
  song_id     TEXT NOT NULL,
  added_at_ms INTEGER NOT NULL,
  PRIMARY KEY (provider, song_id),
  FOREIGN KEY (provider, song_id) REFERENCES songs(provider, song_id)
);
CREATE INDEX idx_favorites ON favorites(added_at_ms DESC);

CREATE TABLE play_history (
  id           INTEGER PRIMARY KEY,   -- rowid alias; AUTOINCREMENT unnecessary (no sqlite_sequence table)
  provider     TEXT NOT NULL,
  song_id      TEXT NOT NULL,
  played_at_ms INTEGER NOT NULL,
  FOREIGN KEY (provider, song_id) REFERENCES songs(provider, song_id)
);
CREATE INDEX idx_history ON play_history(played_at_ms DESC);

CREATE TABLE search_history (
  query_key  TEXT PRIMARY KEY NOT NULL,   -- trimmed lowercase (dedupe key)
  display    TEXT NOT NULL,               -- original casing for chips
  used_at_ms INTEGER NOT NULL
);
CREATE INDEX idx_search_history ON search_history(used_at_ms DESC);

CREATE TABLE settings (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL);
-- Only stable values live here. Volatile blobs are banned (see §5.4):
--   allowed: quality, theme, prefetch_enabled, resume_snapshot (versioned JSON, tolerant parse)
--   banned:  pending downloads (they have their own table below)

CREATE TABLE home_cache (
  key           TEXT PRIMARY KEY NOT NULL,  -- "trending"|"top_searches"|"reco:<albumId>"|"album:<id>"
  json          TEXT NOT NULL,
  fetched_at_ms INTEGER NOT NULL
);

-- DISPOSABLE durable-download intents (drop/rebuild freely per §5.4 policy).
CREATE TABLE download_intents (
  provider      TEXT NOT NULL,
  song_id       TEXT NOT NULL,
  reason        TEXT NOT NULL,   -- USER_NOW|USER_BULK|PREFETCH_NEXT|QUALITY_UPGRADE
  bitrate       INTEGER NOT NULL,
  enqueued_at_ms INTEGER NOT NULL,
  PRIMARY KEY (provider, song_id)
);
-- R7-B3: plain INSERT OR REPLACE lets a PREFETCH_NEXT silently DOWNGRADE a pending USER_BULK/
-- USER_NOW for the same song (album bulk enqueue → user plays one of its tracks → prefetch
-- overwrites the bulk intent → the rest of the album may never download). Writes go through a
-- PRIORITY-PRESERVING upsert — never downgrade, equal priority refreshes timestamp:
--   ON CONFLICT(provider,song_id) DO UPDATE SET
--     reason         = excluded.reason,
--     bitrate        = excluded.bitrate,
--     enqueued_at_ms = excluded.enqueued_at_ms
--   WHERE excluded.priority > priority            -- lower/equal ⇒ no-op (equal: touch timestamp only)
```

## 5.2 Timestamps & trimming
- Time columns `_ms` UTC epoch millis. `duration_s` stays provider-native, converted at edges.
- `last_used_ms` semantics: **NULL until first real playback event**; set only at playback start, crossing 30 s listened, completion, explicit selection. Never at download time, never per tick.
- `play_count` counts **playback sessions**: a session qualifies when it reaches ≥30 s listened OR completes. Repeat-ONE loops therefore accumulate (tracks become upgrade-eligible); sub-30 s skips never inflate the count. (R5-2.2, refined — increment-on-play-start alone would let taps inflate upgrade eligibility.) **Session accumulation pauses while paused and survives seeks within the item** (seeking neither resets nor adds); a restore-seek into a resumed session continues the same accumulator — no double-count (R7-clarify#5).
- Trim-in-transaction with insert: history newest 500; search_history newest 20.

## 5.3 `songs` admission rule & GC
Admit a row only when the song is: **played ∨ favorited ∨ cached ∨ currently queued ∨ in an opened album's tracklist**. Search/home results stay in memory for the session; hydration happens on tap/play/favorite/cache.
Weekly GC (on foreground after 7-day marker) — per-table `NOT EXISTS` (index-friendly, no `||` concatenation collisions), protecting pending intents and the saved queue:

```sql
-- R7-Bug6: (provider,song_id) NOT IN :keys is a TUPLE bind — SQLDelight can't express it and
-- SQLite can't take it. Materialize the saved queue into a temp table first, then anti-join:
CREATE TEMP TABLE IF NOT EXISTS gc_protect(provider TEXT, song_id TEXT);  -- filled from snapshot
DELETE FROM songs
 WHERE updated_at_ms < :cutoff
   AND NOT EXISTS (SELECT 1 FROM favorites f        WHERE f.provider = songs.provider AND f.song_id = songs.song_id)
   AND NOT EXISTS (SELECT 1 FROM play_history h     WHERE h.provider = songs.provider AND h.song_id = songs.song_id)
   AND NOT EXISTS (SELECT 1 FROM cached_files c     WHERE c.provider = songs.provider AND c.song_id = songs.song_id)
   AND NOT EXISTS (SELECT 1 FROM download_intents d WHERE d.provider = songs.provider AND d.song_id = songs.song_id)
   AND NOT EXISTS (SELECT 1 FROM gc_protect g       WHERE g.provider = songs.provider AND g.song_id = songs.song_id);
DROP TABLE gc_protect;

-- home_cache eviction rides the same weekly pass (F6 — TTL governed freshness, never deletion).
-- R7-Bug6: the v1.3.1 cap-delete was a self-referencing subquery on the table being deleted —
-- a known SQLite footgun. At ≤200 kilobyte-scale rows, compute the keep-set in Kotlin
-- (SELECT key … ORDER BY fetched_at_ms DESC LIMIT 200) and bind it as a plain IN list:
DELETE FROM home_cache WHERE fetched_at_ms < :now - 30d;
DELETE FROM home_cache WHERE key NOT IN :keepKeys;      -- Kotlin-bound list, no self-reference
```

Engine-side complement: a dequeued intent whose song row is gone is dropped with an `intent_orphaned` log — never a crash, never a silent retry loop. Test: *"a queued-but-unreferenced song survives the cutoff."*
Local LIKE search therefore searches *your library*, not query residue.

## 5.4 Migration & volatility policy
- Disposable tables (`cached_files`, `download_intents`, `home_cache`) may be dropped and rebuilt anytime.
- Durable tables (`songs`, `favorites`, `play_history`, `search_history`, `settings`) require numbered `.sqm` migrations, ever. No evolving JSON blobs in `settings` — volatile state got its own table precisely so this sentence stays true.
- `resume_snapshot` JSON carries `"v":2` with composite `(provider,songId)` keys; unparseable ⇒ treated as absent, never a crash.

## 5.5 Driver & concurrency
- WAL + `synchronous=NORMAL` + `foreign_keys=ON` + `busy_timeout=5000` on both drivers.
- **All** operations — reads and writes — run on the single `dbLane` (D24). Download progress lives in RAM (`MutableStateFlow`), only terminal states touch the DB.
- `DELETE … RETURNING` avoided entirely (portability across bundled SQLite versions): read-then-write in one transaction instead.

## 5.6 Backup posture
- `audio/` excluded from backup on both platforms (§12).
- The **DB directory is intentionally backed up** (favorites/history/settings survive reinstall). Explicit locations: Android `filesDir/databases/dylan.db`; iOS `Application Support/databases/dylan.db` (the native driver's default path is NOT Application Support — set it explicitly so this sentence is true). iOS WAL sidecars checkpointed via `PRAGMA wal_checkpoint(TRUNCATE)` on `dbLane` in the background handler, so the main file is self-consistent for backup.
- iOS Data Protection: `NSFileProtectionCompleteUntilFirstUserAuthentication` set explicitly on both the audio directory and DB directory (locked-device background playback keeps working after reboot+unlock).

---

# Part 6 — Network Layer

## 6.1 Two clients (R1-B.3)

```kotlin
// JSON API client — defaults OK here
val apiClient = HttpClient(engine) {
    expectSuccess = false
    install(ContentNegotiation) { json(jsonCfg) }
    install(HttpTimeout) { connectTimeoutMillis = 5_000; socketTimeoutMillis = 15_000 }
    install(HttpRequestRetry) {
        maxRetries = 1; exponentialDelay(millis = 400)
        retryIf { _, response ->
            // R7-M2: 503 EXCLUDED — the per-host breaker owns it (Retry-After-aware); a blind
            // 400 ms plugin retry would re-fire into a throttled host before the breaker pauses.
            response.status.value in 500..599 && response.status.value != 503   // NEVER re-fire 429/503 past the breaker
        }
    }
    defaultRequest { url(cfg.apiBaseUrl); cfg.commonParams.forEach { (k, v) -> parameter(k, v) } }
    headers { append(HttpHeaders.UserAgent, cfg.userAgent) }
}

// Bulk/download client — NO defaultRequest (would poison signed URLs),
// NO HttpRequestRetry (would fight §7.3's Range-aware loop), effectively infinite request timeout,
// NO content compression (gzip would break exact Content-Length verification and could
// hand us encoded bytes for a binary sink — audio is already compressed; gzip is pure loss)
val bulkClient = HttpClient(engine) {
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 30_000          // per-read guard; §7.3's watchdog is the real stall detector
        // requestTimeoutMillis deliberately unset
    }
    headers { append(HttpHeaders.AcceptEncoding, "identity") }
}
```

Guard: any 200 whose body fails JSON parse or looks like HTML ⇒ `DylanFailure(DRIFT)` → diagnostics capture → fixture gets a new case. **Same DRIFT check on signed media** (R7-footgun): a CDN 200 with `text/html` is a bot-wall, not a song — the worker's `extFor` already fails UNSUPPORTED on it; the probe asserts it explicitly (§13.3). The WS client is separate — `defaultRequest` is never installed on it (R7-F5), so API params can't leak onto `wss://ws.jiosaavn.com/`.

## 6.2 DTO rules (`provider/saavn/dto`)
String→number coercion once in mappers (`toLongSafe()`, drift logged). `@SerialName` for quoted keys (`"320kbps"`). Image rewrite lives here (regex-guarded, fallback preserved). Mapper never throws on missing optional fields — absent `encrypted_media_url` yields `resolve_ref = null` and the song renders greyed/unplayable (D13).

## 6.3 Submit-search (Enter path)
`search.getResults&q&p&n=20` → `Paged<Song>`; infinite scroll increments `p`; monotonic request token discards stale pages; **accumulator dedupes by `SongKey`** (server may overlap pages). Results render from memory; admission to `songs` happens only per §5.3 triggers.

## 6.4 WebSocket typing-search — v1.3 protocol

Connection: `wss://ws.jiosaavn.com/` (no cookies) `[verified: HAR-7]`.

**Correlation: decided by probe P4, not assumed (D22).** Single-flight is *safe* under any server behavior but serializes bursts — with ~150 ms/keystroke typing, 120 ms debounce and 200 ms RTT, a two-character burst renders at +370 ms and p95 lands near the 800 ms timeout, blowing the <300 ms suggestion SLO. So the strategy is data-driven:

| P4 outcome | Strategy | SLO |
|---|---|---|
| `resp` **echoes the query** | Match response to request on echo; fully pipelined | <300 ms p95 stands |
| **Ordered**, no echo (single WS/TCP connection processed sequentially) | FIFO deque pairing: each send stamps a seq; a received frame pops the head; render iff popped seq == latestSentSeq. Divergence counter ≥3 ⇒ fall back to single-flight | <300 ms p95 stands |
| **Unordered** | Mandatory single-flight (correctness independent of server) | Relaxed to <700 ms p95, documented here and in §10.1 |

Reordering under FIFO pairing causes *dropped* frames (mismatched seq ⇒ discarded), never wrong-query renders — degradation is graceful and observable.

| Concern | Rule |
|---|---|
| Debounce | 120 ms idle-gap before send |
| Per-request timeout | 800 ms ⇒ abandon response, **serve that query over HTTP autocomplete immediately**, count one strike. **Strike attribution (R7-footgun): only WS-side failures count** — a 429/503 on the HTTP *fallback* is an api-host breaker event, never a WS strike; conflating them lets a throttled API host kill the healthy WS path for the session |
| Strikes | Tracked **separately for timeouts vs socket errors**, consecutive-only: 3 in a row of either kind ⇒ HTTP impl serves suggestions for the rest of the foreground session; any healthy response resets both counters. WS retried next session |
| Heartbeat | Ktor `WebSockets { pingIntervalMillis = 25_000 }` — protocol-level ping/pong; **never text-frame "ping"** |
| Connect trigger | Search-tab entry / field focus (not keystroke — TLS handshake must not sit on the first keystroke) |
| Background | Close socket on background (both platforms); reconnect lazily on next focus |
| Backoff | 1 s → 16 s ±50 % jitter; reset after 5 min healthy |
| Parse | Double-parse (outer frame, then inner `resp` string); drift-guarded |

Honest cost note (Law 9 tension, D17): this transport is retained because the product owner mandated type-ahead over WS (D8). Its complexity is contained behind `SearchChannel`, covered by `SearchChannelTest` (§13.2), and degradable to plain HTTP at any time without UI changes.

---

# Part 7 — Download Engine

## 7.1 Job model & priorities
```kotlin
enum class Priority { USER_NOW, USER_BULK, PREFETCH_NEXT, QUALITY_UPGRADE }  // strict order
data class DownloadJob(val key: SongKey, val reason: Priority, val enqueuedAtMs: Long)
sealed interface JobState {
    data object Queued : JobState
    data object Resolving : JobState
    data class Downloading(val loadedB: Long, val totalB: Long?) : JobState
    data object Verifying : JobState
    data class Done(val bytes: Long, val bitrate: Int) : JobState
    data class Failed(val err: DylanFailure, val willRetry: Boolean) : JobState
    data object Cancelled : JobState
}
```
- Intents persist in `download_intents` (priority-preserving upsert per §5.1 — R7-B3: a lower-priority enqueue must never replace a higher one); process death ⇒ re-enqueue at launch; `.part` files make it cheap. **Intent writes are coalesced (~250 ms)** so rapid skipping doesn't produce a DB write per settle (R4-smaller).
- **Partial cap enforced at enqueue too** (R4-smaller): `maxConcurrentParts=3` — a long skip-heavy session accumulates `.part` files via preemption's keep-part rule; exceeding the cap deletes the oldest partial at enqueue time, not only in the startup sweep. **Victim order among partials: PREFETCH parts first, then oldest** (R7-footgun) — the just-preempted USER_NOW part you're most likely to resume must not be the first thing sacrificed.
- **Single execution slot.** Queue depth for USER_NOW ≈1 thanks to the settle timer.
- **Settle timer:** `Next/Previous` restart a 350 ms timer; the resolve+download for playback fires on expiry. Direct taps bypass it. UI flips optimistically regardless.
- **Preemption (D15):**
```
onEnqueue(USER_NOW, B):
  if executing?.priority == USER_NOW && executing.key != B.key:
      cancel(executing, keepPart = true)                    // resumable, probably still wanted
      if executing.key ∈ desiredQueue: requeueAs(PREFETCH_NEXT)
  start(B)
```
USER_NOW also preempts USER_BULK/PREFETCH_NEXT executing jobs identically.

- **QUALITY_UPGRADE trigger (D16):** scanner on idle + unmetered finds entries where `entry.bitrate < preferredQuality && (favorited || play_count ≥ 2)` ⇒ enqueue lowest-priority upgrade job.

## 7.2 Effective quality & metered detection
```
fun effectiveQuality(pref, song, net): Int =
  when {
    !song.has320                        -> 128
    pref == Q320 && net == UNMETERED    -> 320
    else                                -> 128
  }
```
`NetClass` (expect/actual): Android `NetworkCapabilities.NET_CAPABILITY_NOT_METERED` (+ `RESTRICTED_BACKGROUND` check); iOS `NWPathMonitor` `isExpensive`/`isConstrained`. **Never transport-type heuristics** — hotspots and car Wi-Fi lie.

## 7.3 Worker loop (canonical algorithm — v1.3)

> Implementation note: this is written as an **exhaustive classification over response outcomes** on purpose. In Kotlin it becomes an exhaustive `when` over a sealed `HttpOutcome`, so a branch without a terminator is a *compile error*, not a runtime surprise — the pseudocode-drift bug class (v1.2's M1–M6) becomes unrepresentable.

```
loop:  // single worker coroutine on AppDispatchers.io; one execution slot
  1. GATE     if breakerFor(host).pausedUntil > now → delay(remaining); continue
  2. TAKE     job = queue.takeHighestPriority()      // USER_NOW > USER_BULK > PREFETCH_NEXT > QUALITY_UPGRADE
  3. HYDRATE  song = songs[job.key]
                 ?: run { dropIntent(job); log("intent_orphaned"); continue }   // GC raced an intent: drop, never crash (M10 complement)
  4. QUALITY  q = effectiveQuality(pref, song, net)
  5. DEDUPE   entry = cache.entry(job.key)                                  // sufficiency (D16)
                 entry != null && entry.bitrate >= q      ⇒ touch(key); emit Done; continue
                 entry != null && entry.bitrate <  q && net.metered ⇒ emit Done; continue   // never spend cellular upgrading
  6. RIGHTS   !song.cacheable        ⇒ fail(NOT_CACHEABLE)                  // D13
              song.resolve_ref == null ⇒ fail(NO_SOURCE)
  7. SIZE     expectedB = ceil(duration_s × q × 125)          // raw unpadded estimate, bytes
              partB     = sizeOrNull(partPath(key,q)) ?: 0
              netNew    = max(0, expectedB×1.25 − partB)      // INCREMENTAL (R5-M-v); CLAMPED ≥ 0 (R7-M4):
                                                              // a stale/larger .part must not REDUCE usage
              cacheManager.enforceBudget(netNewBytes = netNew)  // M8 semantics: see §8.3 accounting
              freeDisk < max(diskFloor, 2×netNew) ⇒ fail(STORAGE)  // R5-M-v: a 90 %-complete resume needs
                                                                    // room for the remainder, not 2× the track
  8. RESOLVE  resolveCount++; resolveCount > cfg.resolveCapPerJob ⇒ fail(RESOLVE_LIMIT)   // M4/R5-Bug1: THE bound.
              signed = provider.resolveStream(song.resolve_ref, q)             // TTL starts NOW
                                                                            // R7-clarify#6: resolveCount is
                                                                            // IN-MEMORY per execution attempt — process
                                                                            // death resets it, by design: the reconciler
                                                                            // re-enqueues each intent once per boot, so a
                                                                            // poison job costs ≤ cap signatures per run,
                                                                            // and cross-restart loops have no driver.
  9. REQUEST  resp = bulkClient.get(signed.url) {
                  if (partB > 0) { header("Range", "bytes=$partB-"); etag?.let { header("If-Range", it) } }
              }                                                                // gzip impossible: identity encoding (§6.1)

 10. CLASSIFY exhaustive when (resp.status) {                     // EVERY branch terminates (M1)
      429, 503 →  breakerFor(hostOf(signed.url)).pause(retryAfterOr(resp, base=5s))   // per-host breaker (F7)
                  job.priority == USER_NOW ⇒ fail(RATE_LIMITED)                    // F8: fast-fail, no stranded spinner
                  else                     ⇒ requeue(job, backoff); continue
      401, 403 →  // signature was minted fresh at step 8 ⇒ refusal, not expiry.
                  // R5-Bug1: NO sticky flag — a fresh signature earns a re-sign retry iff the cap allows.
                  if (resolveCount < cfg.resolveCapPerJob) goto 8
                  else ⇒ fail(if (otherEndpointsHealthy) FORBIDDEN_REGION else EXPIRED)
      404      →  fail(NOT_FOUND)
      416      →  // R7-Bug3: a Range problem is NOT a signature problem — restarting the GET from zero
                  // fixes it; re-signing burns resolve budget on a still-valid URL.
                  rangeRestarts++ ≤ cfg.rangeRestartsCap ⇒ { truncate(tmp); partB=0; etag=null; goto 9 }  // R5-2.5
                  else                                   ⇒ { truncate(tmp); partB=0; etag=null; goto 9 }  // clean full attempt either way
      200 && partB>0 →  // server ignored Range / If-Range mismatch ⇒ full body incoming
                  rangeRestarts++ ≤ cfg.rangeRestartsCap ⇒ { truncate(tmp); partB=0; goto 9 }
                  else ⇒ { truncate(tmp); partB=0; segStart=0; segLen=contentLength; total=contentLength;
                           etag=resp.etag ?: etag }        // R7-Bug3: cap-exhausted path now SETS the stream
                                                           // state and falls into STREAM — v1.3.1 fell through
                                                           // with stale segStart/partB (undefined behavior)
      200      →  segStart=0; segLen=contentLength; total=contentLength; etag=resp.etag ?: etag
      206      →  segStart=partB; segLen=contentLength; total=parseContentRangeTotal(resp)   // "bytes a-b/TOTAL"
                   etag=resp.etag ?: etag                                                       // may be null (M6)
      else     →  attempts++ ≤ cfg.dlRetries ⇒ { backoff(attempts); goto 9 } : fail(NETWORK)
                   // R7-Bug3: transport failures retry the SAME url (goto 9), bounded by `attempts` —
                   // they no longer consume resolveCount. Two independent budgets:
                   //   resolveCount — signatures minted per job (cap 3): only 401/403 reach it
                   //   attempts     — transport retries per job  (cap dlRetries): stall/5xx/IO here
                   // A flaky CDN can no longer exhaust RESOLVE_LIMIT while its URL is still valid.
    }

 11. STREAM   coroutineScope {
                val copy = launch { channel.copyTo(tmp.append()) { chunk →
                                lastProgressAt = now
                                progress[key] = ((segStart+written) × 100 / (total ?: estimate)).coerceIn(0,99)
                                // total is ENTITY length on both paths: CL for 200, Content-Range TOTAL
                                // for 206; segStart+written is the absolute object offset — correct ratio
                            } }
                val watchdog = launch {                                          // M3: CONCURRENT — the old post-copy
                  while (isActive) { delay(2_000)                                // check was unreachable dead code
                    if (now − lastProgressAt > cfg.stallTimeoutMs ||
                        now − startedAt   > max(60_000, expectedB / 20_000))
                       copy.cancel(StallException) } }
                try { copy.join() }
                catch (Stall) { attempts++ ≤ cfg.dlRetries ⇒ goto 9 : fail(NETWORK_TIMEOUT) }   // R7-Bug3: goto 9, not 8
                finally { watchdog.cancel() } }
              IOException during append ⇒ fail(STORAGE)                          // ENOSPC & friends — StatFs lies (R5-3.4)

 12. VERIFY   finalSize = tmp.size()
              // R7-Bug2: expectedTotal is the AUTHORITATIVE entity size — never computed from what we
              // just received. v1.3.1 used `segStart + segLen`, which makes exact-equality VACUOUS for
              // 206: a range-capped response self-verifies a truncated file. (`contentRangeTotal` was
              // also an undefined variable — that fallback arm was dead code.)
              expectedTotal = when {
                  total != null     -> total                                    // D25 primary: CL (200) / CR-TOTAL (206)
                  segStart > 0      -> segStart + segLen                        // 206 sans CR-total (P1-gated): open-ended assumption
                  else              -> ceil(0.90 × duration_s×q×125)            // M6 fallback: floor vs raw estimate
              }
              finalSize != expectedTotal && total != null ⇒ { delete(tmp); fail(CORRUPT_SIZE) }   // M5: delete — never resume
              finalSize <  expectedTotal && total == null ⇒ { delete(tmp); fail(CORRUPT_SIZE) }   // a poisoned offset forever
              ext = extFor(resp.contentType, signed.type)   // mp4/m4a ⇒ "m4a"; audio/mpeg ⇒ "mp3";    // F9
                                                            // unexpected ⇒ fail(UNSUPPORTED)
              if (ext == "m4a") {                           // R7-Bug1: container check AFTER ext derivation,
                  bytes(tmp, 4 until 8) != "ftyp"           // ISO-BMFF ONLY (an MP3 has no ftyp box), and
                      ⇒ { delete(tmp); fail(CORRUPT_CONTAINER) }   // NON-INVERTED at 4-byte width.
              }                                             // v1.3.1 checked `== "ftyp" ||` over `4..8` (5 bytes)
                                                            // BEFORE ext was known — it deleted every valid file.

 13. COMMIT   finalPath = pathFor(key, q, ext)
              prev = SELECT cached_files WHERE key               // plain read BEFORE the tx (no RETURNING)
              if (prev != null && prev.bitrate == q && prev.ext == ext && prev.bytes == finalSize) {
                  atomicRename(tmp, finalPath); dropIntent(job); emit Done(job); continue   // R7-B2 fast path:
              }                                                        // identical re-fetch ⇒ pure rename, no tx churn
              atomicRename(tmp, finalPath)
              ok = tx(dbLane) {
                  DELETE FROM cached_files WHERE key
                  INSERT cached_files(key, q, ext, finalSize, now,
                                      last_used_ms = prev?.last_used_ms,   // R7-Bug4: PRESERVE engagement state on
                                      play_count   = prev?.play_count ?: 0,// upgrade/recovery re-fetch — v1.3.1 reset
                                      pinned       = favorited(key) OR prev?.pinned == 1,  // all three, front-loading
                                      pinned_at_ms = prev?.pinned_at_ms                    // LRU and inverting pin-demotion order
                                          ?: if (favorited(key)) now else NULL)   // first-insert favorite pins NOW
                                      // NULL last_used_ms on true first insert = downloaded-never-played (eviction intent)
              }
              !ok ⇒ { delete(finalPath); fail(DB) }              // no orphan row
              prev != null && (prev.bitrate != q || prev.ext != ext) ⇒                   // M2 GUARD: same-quality recovery
                  fs.delete(pathFor(key, prev.bitrate, prev.ext))                        // must NOT delete the new file
              cacheManager.enforceBudget(netNewBytes = 0, exemptKeys = setOf(key))  // M9: row already inserted & counted —
                                                                                    // pass ZERO; R7-P6: never evict the just-
                                                                                    // committed file in its own enforcement pass
              dropIntent(job)                                    // R5-Bug2: success CONSUMES the intent row
                                                                 // (reconciler sweeps any stragglers)
              emit Done(job)
 ```

**Accepted crash window (R7-B2, documented not redesigned):** `atomicRename` lands before the tx, so a same-path overwrite (upgrade to a bitrate whose path already exists) killed between rename and commit leaves row=`old bytes` vs file=`new size`. The startup reconciler's size check (§7.4 step 4) catches this deterministically and drops both — worst case is **one cache entry re-downloaded on next play**; favorites/history/`songs` are untouched, so no user data is ever lost. A rename-after-commit journaling scheme would trade this for an orphan-*row* window — the exact failure class C.1 rejected — for a personal-use app where the blast radius is one transparent re-download. Law 9: documented window beats speculative journaling.

`fail(x)` semantics: terminal `JobState.Failed(DylanFailure(code, key))` recorded, artifacts cleaned as specified, intent row deleted on terminal failure (requeued failures keep it), LogBuffer tag per path (`resigned_midjob`, `resumed_after_resign`, `breaker_pause`, `intent_orphaned`).

Failure-path invariants: crash between tx-commit and file-delete leaves an orphan *file* — reconciler step 2 sweeps it. Crash between rename and tx leaves an orphan final file — swept likewise. Always leave the recoverable orphan.

## 7.4 Startup reconciler (runs once on IO, < 300 ms typical)
1. Delete `*.part` older than 1 h (younger belong to re-enqueued intents).
2. `dirFiles − dbRows` ⇒ delete orphan files.
3. `dbRows − dirFiles` ⇒ delete orphan rows.
4. Rows where `file.size != row.bytes` ⇒ delete both.
5. Cap stray partials: if > 3 `.part` files, delete oldest beyond 3.
6. Recompute aggregates if anything changed; run LRU pass.
7. **Re-enqueue EVERY persisted intent whose FINAL file is absent** (R5-Bug2 — the v1.2 wording
   "lacking `.part`/final" excluded exactly the interrupted downloads the reconciler exists for):
   jobs with a `.part` resume from it via Range (§7.5); jobs with neither start fresh; jobs whose
   final exists get their intent row dropped (success consumes intents — worker `dropIntent` is
   the primary mechanism, this sweep is the belt).

## 7.5 Resumability & integrity
- `Range: bytes=<offset>-` + **`If-Range: <etag>`** captured from the first partial response. Changed resource ⇒ server returns 200-full ⇒ existing ignore-Range branch truncates and restarts cleanly (spec-compliant guarantee, not heuristic).
- `rangeRestarts ≤ 1` prevents infinite loops against servers that never honor Range.
- Cross-re-sign byte identity is assumed `[assumed → probe P6]`; `If-Range` bounds the damage; `resumed_after_resign` diagnostic tag correlates any downstream CORRUPT spike to this exact path (R3-E3).
- **Probe gates:** P1 asserts `Accept-Ranges: bytes` + a real 206 with exact byte count, **and** validates `If-Range` semantics (bogus etag ⇒ expect 200-full; matching etag ⇒ expect 206). If P1 fails, §7.5 is redesign work, not runtime handling — discovered in M0, not M3.

## 7.6 Circuit breakers — per host (F7)
On 429/503: `breakerFor(url.host).pauseUntil = now + Retry-After` (else exponential from 5 s, cap 5 min). Two independent instances: `www.jiosaavn.com` (api/search) and `web.saavncdn.com` (bulk media) — a throttled CDN download must never blank foreground type-ahead. UI shows "Slow down a moment…" only if a USER_NOW job is affected; such jobs fail fast per §7.3 step 10 instead of spinning.

---

# Part 8 — Cache Manager

## 8.1 Budgets
| Pool | Limit |
|---|---|
| Audio total | 300 files ∧ 2 GB |
| ↳ pinned sub-pool | ≤ 1.5 GB (75 %) — overflow demotes, never blocks favorites |
| Android images | 150 MB disk + **48 MB memory (absolute cap** — %-of-heap broke the 200 MB SLO arithmetic, F13) |
| iOS images | 150 MB `URLCache` + decode-time downsampling (§11.9) |

## 8.2 Layout
```
filesDir/audio/                       (Android, backup-excluded)
Application Support/audio/            (iOS, isExcludedFromBackup)
  <provider>_<songId>_<bitrate>.<ext>     ext from cached_files row (F9; default m4a)
  <provider>_<songId>_<bitrate>.part      in-progress
```
Filenames derived in `Paths.kt` from the DB row (**provider** + id + bitrate + ext — R7-Bug7: the PK is `(provider, song_id)`, so a provider-less filename lets two catalogs holding the same numeric id clobber each other's audio the moment the Subsonic stretch lands); DB stores no paths; DB rebuildable from directory scan. **Sanitization (R7-F1):** the provider adapter validates `songId ∈ [A-Za-z0-9_-]+` at the mapper boundary (Saavn ids are alphanumeric `[verified: HAR]`); anything else ⇒ SHA-256 hex of the raw id — path traversal becomes unrepresentable.

## 8.3 Budget enforcement (deterministic; DB work serialized on `dbLane`, file I/O off it)

> R7-M3: the v1.3.1 header claimed the whole function is "serialized on `dbLane`" while Phase 0
> scans the audio directory and Phase 3 unlinks files — that would stall every DB read behind a
> directory walk. Correct split: **the function runs on `AppDispatchers.io`; ONLY the `tx` blocks
> hop onto `dbLane`.** `partBytes` is computed before entering any DB work; deletions happen
> outside both the transaction and the lane.

```
enforceBudget(netNewBytes: Long = 0, exemptKeys: Set<SongKey> = empty):
  // Phase 0 — inputs, no locks held
  partBytes = Σ size(audioDir/*.part)                // ALL partials on disk — preempted jobs included
  usage     = SUM(cached_files.bytes) + partBytes    // R5-Bug4: FULL disk presence. v1.3 computed
                                                     // partBytes but never added it — the budget was
                                                     // blind to every kept .part file.
  usage    += netNewBytes                            // caller passes INCREMENTAL bytes (padded estimate −
                                                     // own .part, §7.3 step 7), so the current job's existing
                                                     // partial is counted exactly once — M8 semantics preserved
  protect   = protectedKeys.value                    // F4: {current, next} from orchestrator StateFlow —
                                                     // published as PersistentSet (copy-on-write); the
                                                     // flow NEVER exposes a MutableSet a reader could
                                                     // mutate mid-iteration (R7-F4)
            ∪ inFlightJobKeys                        // files being written right now
            ∪ upgradeSourceKeys                      // F5: old-quality row of an executing QUALITY_UPGRADE —
                                                     // a failed upgrade must not have eaten the original
  victims: MutableList<Victim> = []                // Victim = Delete(key,file) | Demote(key)

  // Phase 1 — pinned pool (D12/F2/F3): demote OLDEST-PINNED first.
  // (Demoting by last_used_ms was inverted: NULL sorts first in ASC, so brand-new never-played
  //  favorites were evicted first — exactly backwards. pinned_at_ms is unambiguous.)
  while pinnedUsage > cfg.pinnedMaxBytes:
     v = SELECT provider,song_id FROM cached_files
         WHERE pinned=1 AND key NOT IN :protect
         ORDER BY pinned_at_ms ASC LIMIT 1        // two-step select→update: mobile SQLite has no
     v ?: break                                   // UPDATE..ORDER BY/LIMIT (R5-patch4)
     victims += Demote(v); pinnedUsage -= v.bytes

  // Phase 2 — general LRU
  while usage > cacheMaxBytes || count+1 > cacheMaxFiles:
     v = SELECT * FROM cached_files
         WHERE key NOT IN :protect AND pinned = 0
         ORDER BY (play_count = 0) DESC,          -- never played first
                  (last_used_ms IS NULL) DESC,    -- then oldest-unknown
                  last_used_ms ASC,               -- bytes DESC deliberately DROPPED: it systematically
                                                  -- evicted 320-files first (quiet quality drift)
                  cached_at_ms ASC, rowid ASC LIMIT 1   -- R7-M5: deterministic tiebreakers — equal
                                                        -- last_used_ms (many NULLs) must not make
                                                        -- eviction order arbitrary / tests flaky
     v ?: break                                   // protected tail may exceed budget temporarily
     victims += Delete(v); usage -= v.bytes; count--

  // Phase 3 — commit rows atomically, THEN delete files OUTSIDE the transaction
  // (no file I/O while holding the SQLite write lock; crash leaves a recoverable orphan file.
  //  R6-2 proposed file-first ordering — REJECTED: a crash mid-sequence would then leave an
  //  orphan ROW claiming bytes that no longer exist, the exact play-path failure class C.1 rejected)
  tx(dbLane) { victims.forEach { apply row DELETE / pinned=0 UPDATE } }
  for (v in victims.filterIsInstance<Delete>()) {
      if (v.key ∈ protectedKeys.value) {          // TOCTOU guard (R6-2): victim became protected
          tx(dbLane) { re-INSERT v.row }          // between selection and unlink — undo cleanly;
          continue                                // file untouched, DB↔disk stay consistent
      }
      fs.delete(v.file)                           // crash here ⇒ orphan file ⇒ reconciler step 2 sweeps
  }                                               // (engine never prepares unprotected tracks, so the only
                                                  //  exposure was this select→delete window — now guarded)
  if any Demote: notifyOnce("Favorites exceed the offline budget — oldest moved out of guaranteed storage.")
  // notification emitted AFTER commit — a rolled-back tx must not have told the user anything
```

Invocation points: pre-download (`netNewBytes` = clamped padded estimate − existing `.part`), post-download `enforceBudget(0, exemptKeys = {just-committed key})` (the row is already inserted and counted — passing `finalSize` again double-counts, M9; the exemption stops a bulk/prefetch job from being evicted by its own completion pass before anyone presses play, R7-P6), **favorite-add** (F3 — favoriting 400 already-cached songs must trigger enforcement immediately, not wait for a download), and protection-set changes.

Unit tests pin: *"unplayed-but-downloaded track evicts before a track played two weeks ago"* · the SQL NULL-truth direction explicitly · newest-favorite survives the longest under pool pressure · upgrade-source protection.

## 8.4 Validation policy
Trust the row on the play path (no stat-per-read). **One cheap exception (R7-footgun): the cache-hit handoff sniffs 12 bytes** — magic (`ftyp`/ID3) + `file.size == row.bytes` — before constructing the `LocalTrack`; a truncated-but-row-matching file fails here and takes the transparent-redownload path *before* the engine surfaces a decoder error mid-lock-screen-update. Cost: one 12-byte read per prepare. Lazy validation on engine load failure remains the backstop ⇒ targeted reconciler pass ⇒ transparent fall-through to download. Startup sweep catches the rest.

## 8.5 Pinned favorites
Favorite ⇒ pin on existing row (`pinned=1, pinned_at_ms=now`) + `enforceBudget()` runs immediately (F3); download completion pins if favorited. **Unfavorite ⇒ unpin** (`UPDATE pinned=0` on the cached row if present) — the flag can never outlive the intent that created it (R5-2.1; test: favorite→pin→unfavorite⇒`pinned==0`). **A single favorite does NOT enqueue a download** (R7-M7, product decision): pinning is a *storage guarantee*, not a download trigger — D4/D12 promise favorites survive eviction once cached, not that tapping ♥ spends bandwidth. Uncached favorites reach disk through normal playback (USER_NOW), album bulk-download (USER_BULK), or prefetch. Settings Storage section shows three lines: `Cached audio: X of 2 GB` · `Guaranteed favorites: Y of 1.5 GB` · `Artwork: platform-managed (~150 MB)`. Swipe-remove works even on pinned (explicit intent overrides pin).

## 8.6 Clear cache (C.10)
Excludes protected set `{current, next, in-flight} ∪ upgradeSourceKeys` (R7-B5 — clearing cache mid-upgrade must not eat the old-quality file the running upgrade falls back to on failure; F5 invariant holds under every entry point) — dialog copy: *"Keeps the song you're playing."* Wipes everything else including pinned files (rows deleted; favorites rows survive; Library shows cloud glyphs afterwards). Test: *"clear cache during an active upgrade does not delete the source file."*

---

# Part 9 — Playback System

## 9.1 State (shared, immutable — hot values excluded; illegal states unrepresentable)
```kotlin
enum class ErrorCode { OFFLINE, NOT_FOUND, NO_SOURCE, NOT_CACHEABLE, EXPIRED, FORBIDDEN_REGION,
    NETWORK, NETWORK_TIMEOUT, STORAGE, CORRUPT_SIZE, CORRUPT_CONTAINER, UNSUPPORTED,
    RATE_LIMITED, RESOLVE_LIMIT, TOO_MANY_FAILURES, DRIFT }

// R4-smaller: an enum can't carry context (which song? retry-after?) and StringRes doesn't exist
// in commonMain. Codes map to copy at the UI edge via Strings.kt.
data class DylanFailure(val code: ErrorCode, val songKey: SongKey? = null, val detail: String? = null)

sealed interface Phase {                       // sealed: phase/error can never disagree (R4-smaller)
    data object Idle : Phase
    data class Resolving(val key: SongKey) : Phase
    data class Downloading(val key: SongKey) : Phase
    data class Ready(val key: SongKey) : Phase
    data class Playing(val key: SongKey) : Phase
    data class Paused(val key: SongKey) : Phase
    data class Error(val failure: DylanFailure) : Phase
}

data class PlayerState(
  val phase: Phase,
  val current: Song?,                                   // convenience mirror of phase.key — invariant-tested
  val queue: PersistentList<Song>,                      // kotlinx.collections.immutable — PROVES stability
  val index: Int,
  val shuffleOn: Boolean,
  val shuffleOrder: PersistentList<Int>?,               // M11: plain List<Int> here would make the WHOLE
                                                        // class unstable to the Compose compiler
  val repeat: Repeat,
) {
  // F-safe derivation: total — never throws on empty queues, last track, or shuffle bounds (R5-3.5).
  // R5-Bug3: coordinate systems — `index` is a QUEUE position; `shuffleOrder` is a list of
  // queue-positions in PLAYBACK order. Locating "next" requires finding index INSIDE shuffleOrder;
  // `shuffleOrder[index + 1]` mixes the two and answers a different question entirely.
  val nextUp: Song? get() = when {
      queue.isEmpty() || repeat == Repeat.ONE -> current   // R7-Bug5: repeat-ONE ⇒ next IS current;
                                                           // prefetch/replaceUpNext become no-ops
      shuffleOn -> shuffleOrder?.let { order ->
          val pos = order.indexOf(index)                   // O(n), n ≤ queue length — negligible.
          if (pos < 0) null else order.getOrNull(pos + 1)?.let { queue.getOrNull(it) }
          // R7-Bug4: pos < 0 (queue edited without remap / inconsistent restore) must yield NULL,
          // not `order[0]` — indexOf(-1)+1 == 0 silently crowned the first shuffle slot as "next".
      }
      else -> when {
          index + 1 < queue.size        -> queue[index + 1]
          repeat     == Repeat.ALL      -> queue.firstOrNull()   // R7-Bug5: wrap for prefetch/gapless
          else                          -> null
      }
  }
}
```
**Shuffle-order invariant (R7-Bug5):** every queue mutation that shifts positions — `RemoveAt`, `MoveWithinQueue`, `PlayNow` re-indexing, snapshot restore filtering — **rebuilds or remaps `shuffleOrder` in the same state update**. A stale permutation is treated like an unknown `itemId`: `indexOf == -1` is a resync fault (log DRIFT, rebuild from authoritative state), never a silent wrong-next. Test added: *"remove the current track's successor under shuffle ⇒ nextUp is the following shuffle slot, not slot 0."*

val positionMs: Flow<Long>                     // 10 Hz NP visible · 4 Hz mini · 0 Hz hidden
val downloadProgress: Flow<Map<SongKey, Int>>  // keyed, ~4 Hz coalesced; SongListItem subscribes alone
fun progressFor(key: SongKey): Flow<Int> =     // R7-P1: rows collect THIS, never the raw map —
    downloadProgress.map { it[key] }.distinctUntilChanged()   // one row's ring must not re-emit
                                              // every other row (Home collects nothing)
```
No hot value rides `PlayerState`; no observer re-evaluates because a different song's ring advanced.

**Single source of truth:** prefetch (§9.5), `replaceUpNext` (§9.4), and the Queue UI all compute "next" through THIS property — no private reimplementations anywhere (the shuffle-bug class lives exactly in duplicated next-logic).

## 9.2 Intents
```kotlin
sealed interface Intent {
  data class PlayNow(val songs: List<Song>, val startIndex: Int) : Intent
  data class PlayNext(val song: Song) : Intent
  data class AddLast(val song: Song) : Intent
  data object TogglePlayPause : Intent
  data class Seek(val ms: Long) : Intent
  data object Next : Intent
  data object Previous : Intent
  data object ToggleShuffle : Intent
  data object CycleRepeat : Intent
  data class RemoveAt(val queuePos: Int) : Intent
  data class MoveWithinQueue(val from: Int, val to: Int) : Intent
  data object ClearUpNext : Intent
  data class SetQuality(val q: Quality) : Intent
}
```
Default rule for unspecified (phase × intent) cells (G1): *transport intents (Play/Seek/Next/Prev) no-op outside PLAYING/PAUSED/READY; mutation intents (queue edits, quality, shuffle) valid in any phase.* Full matrix generated as a test table.

Side-effects owned by orchestrator: history record once per play-start (guard: same song within 30 min ⇒ skip — kills repeat-ONE spam), LRU touch + `play_count`, resume snapshot (pause/background/every 10 s), prefetch triggering, download-progress publication.

## 9.3 Transition table (core paths)

| From | Event | To | Actions |
|---|---|---|---|
| IDLE/any | PlayNow | RESOLVING | snapshot; ensure admitted; kick pipeline |
| RESOLVING/DOWNLOADING | cache hit / Done | READY→PLAYING | `engine.prepare(window)`; on `Prepared` → `play()` |
| DOWNLOADING | Failed(user) | ERROR(failure) | copy per §11.8; retry affordance |
| DOWNLOADING | Failed(prefetch/bulk) | stay | silent; toast only if it becomes current |
| PLAYING | **TrackChanged(itemId, AUTO)** | PLAYING(next) | map itemId→queue index; record history; LRU touch; prefetch new next; `replaceUpNext(newNext)` — native advance is now *visible* to the state machine (B1) |
| PLAYING | **TrackChanged(itemId, SEEK)** | PLAYING(same-index) | R7-M1: user seeked across an item boundary — update index/current mirrors, **no queue advance**, no history double-count (30-min guard covers), prefetch new next, `replaceUpNext(newNext)` |
| PLAYING | **TrackChanged(itemId, EXPLICIT)** | PLAYING(mapped) | R7-M1: orchestrator-driven rebuild/resync (unknown-id recovery, window re-prime) — set mirrors from authoritative state; history guard prevents double-record; re-run prefetch |
| PLAYING | **QueueExhausted** | IDLE / wrap | repeat-ALL ⇒ wrap to index 0 (**orchestrator calls `prepare(window)` for index 0 — the engine is re-primed by the orchestrator, it never wraps itself**, R7-clarify#4); else stop |
| PLAYING | ItemEnded(itemId) | unchanged | informational only — successor existed; no state change |
| PLAYING | TrackError(song) | PLAYING(next) | transient-bad mark; <3 consecutive ⇒ skip+toast; ≥3 ⇒ ERROR(TOO_MANY_FAILURES) |
| PLAYING | RouteLost | PAUSED | headphone unplug |
| PAUSED | InterruptionEnded(shouldResume) | PLAYING | call ended |
| any | **EngineDetached** | PAUSED | preserve queue/position; write snapshot (service destroyed — B5b) |
| any | Seek | unchanged | clamp |

## 9.4 Engine seam — window contract (v1.3, closes the desync class)

The v1.2 shape had two queues and no sync channel: ExoPlayer/AVQueuePlayer advanced natively while Kotlin's `index` stood still — wrong Now Playing, wrong lock screen, wrong history, wrong prefetch, and `Ended` never fired because the player hadn't ended. Fixed by contract:

```kotlin
data class LocalTrack(val itemId: String, val path: String, val durationHintMs: Long?)
// itemId = "$provider:$songId:$bitrate" — OPAQUE to engines; orchestrator maps itemId ⇄ queue position.
// Under download-first most of the queue has no file — so indices NEVER cross this boundary; ids do.

enum class TransitionReason { AUTO, SEEK, EXPLICIT }

sealed interface EngineEvent {
    data class Prepared(val itemId: String) : EngineEvent
    data class TrackChanged(val itemId: String, val reason: TransitionReason) : EngineEvent
    data class ItemEnded(val itemId: String) : EngineEvent      // informational: successor existed
    data object QueueExhausted : EngineEvent                    // nothing left to advance into
    data class Error(val itemId: String?, val kind: EngineErr) : EngineEvent
    data object RouteLost : EngineEvent
    data class Interrupted(val shouldResume: Boolean) : EngineEvent
}

interface PlayerEngine {                        // Kotlin-owned reactive surface
    fun prepare(window: List<LocalTrack>)       // 1–2 items: current (+ next iff its file exists)
    fun replaceUpNext(track: LocalTrack?)       // swap ONLY the upcoming slot; current untouched
    fun play(); fun pause(); fun seekTo(ms: Long)
    val events: SharedFlow<EngineEvent>
    val positionFlow: Flow<Long>
    fun release()
}

// Swift implements ONLY this — imperative, no flows, no suspend:
interface NativeAudioOutput {
    fun prepare(items: List<LocalTrack>)        // itemId travels through as a stable handle
    fun replaceUpNext(item: LocalTrack?)
    fun play(); fun pause(); fun seekTo(ms: Long)
    fun currentTimeMs(): Long
    fun onEvent(e: EngineEvent)                 // Swift → Kotlin event pump
    fun release()
}
```

**Window contract:** the engine holds *current + at most next*; the orchestrator owns THE queue and refills the window. Refill triggers: `prepare()`, every `TrackChanged` (the new current's full duration is available to insert the new next — gapless preserved), any queue edit/shuffle toggle/prefetch completion that changes `nextUp` ⇒ `replaceUpNext(nextUp.windowTrack())`, `null` clears.

Platform mapping:
- **Android** (`ExoPlayerEngine`): `MediaItem.mediaId = itemId`; `onMediaItemTransition(reason)` maps directly to `TrackChanged` (`MEDIA_ITEM_TRANSITION_AUTO`→AUTO, `SEEK`→SEEK, `PLAYLIST_CHANGED`→EXPLICIT); `STATE_ENDED` ⇒ `QueueExhausted`. `replaceUpNext` = `replaceMediaItem(1, …)` **when `mediaItemCount ≥ 2`; otherwise `addMediaItem(1, …)`** (R7-clarify#3 — calling `replaceMediaItem` on a nonexistent index 1 throws).
- **iOS** (`NativeAudioOutputImpl` + `IosPlayerEngine`): `[itemId: AVPlayerItem]` dictionary; KVO on `\AVPlayer.currentItem` ⇒ `TrackChanged(AUTO)`; currentItem nil after final advance ⇒ `QueueExhausted`. `replaceUpNext` removes queued items **beyond index 0 only** (never `removeAllItems()` — that kills playback), then inserts.
- Unknown `itemId` in any event ⇒ resync fault: log DRIFT, rebuild window from authoritative state, re-emit current as `TrackChanged(EXPLICIT)`.
- **Event contract (R7-clarify#2):** `ItemEnded(itemId)` fires exactly once per *natural* end of the CURRENT item when a successor existed in the window; it is never emitted for a non-current item, and is always followed by either `TrackChanged(AUTO)` or `QueueExhausted`.
- **Golden test (R7-footgun):** any sequence of `replaceUpNext` calls must never emit `TrackChanged`, `QueueExhausted`, or interrupt the audible current item — pinned for both engines (`BridgeGoldenTest` companion).

Gapless honesty: Android gets device-native gapless from the playlist; iOS relies on early insertion + preroll, and additionally on source-side encoder gapless metadata (`iTunSMPB`) which we don't control `[assumed → M5 device measurement]` (G2). No gapless claim ships before that measurement.

## 9.5 Prefetch
Trigger: **track start** (not 50 % — an 8 s fetch misses a 50 % window on LTE) OR queue/index change. Desired-set reconciliation keeps eligible `.part` files. Cellular policy: enabled by default for **exactly one** next track at forced 128 kbps; Settings toggle disables. Justification: strict download-first otherwise makes every mobile-data transition a multi-second gap; 3 MB/track is a defensible default (R1-D).

## 9.6 Search-to-play wiring
Enter-results & album tracks: direct `PlayNow`. Typing-path song tap (mini-object): fire `fullResults(currentQuery)`, locate id, `PlayNow` at index (session-cached per query). Offline taps on favorites/history: cached ⇒ instant; uncached ⇒ ERROR(OFFLINE) with "Available online" affordance. Non-cacheable rows: greyed + badge, tap shows NOT_CACHEABLE copy (D13).

## 9.7 Queue snapshot / restore
`settings["resume"] = {"v":2, items:[{provider,songId}…], index, posMs, shuffleOn, order[]}` on pause/background/every 10 s (R7-F7 reviewed and kept: a single-row WAL upsert at 0.1 Hz is negligible; event-driven-only writes would lose the last minute of position on every crash). Composite keys per D18 (v1's bare `songIds[]` predates them). **Restore is tolerant**: `mapNotNull` through the songs repo (GC/clear-cache may have removed rows), clamp `index` into range, empty result ⇒ IDLE — a stale snapshot degrades to a clean start, never a crash or broken queue (R5-2.3; test: 3-song queue, 1 row deleted ⇒ restores 2). **`order[]` is sanitized against the FILTERED queue** (R7-Bug4): drop out-of-range entries, remap to post-filter positions, discard the permutation entirely if any entry is unresolvable — a stale `order[]` pointing past the filtered queue is exactly the `indexOf == -1` resync-fault class §9.1 bans. Launch hydrates to PAUSED(current,posMs); engine prepare deferred to first user play. Android killed-process resume via `MediaSessionService.onPlaybackResumption` — the notification's Play after process death flows through **`onPlaybackResumption`, not the detached-engine intent buffer** (the buffer dies with the process; R7-footgun doc note).

## 9.8 Shuffle
Fisher-Yates over indices → `shuffleOrder`; original order untouched; toggling off realigns at current. `Next` walks the order — no random-per-press repeats.

## 9.9 Android integration specifics
- **Player + sampler live on a dedicated `HandlerThread("dylan-media")` looper** (R5-3.1, corrected fix): the player is *built* on that looper, so all `ExoPlayer` calls — including the position poll — execute there natively. A stalled decoder can never touch Main; off-looper reads (the reviewer's suggested fix) would throw `IllegalStateException`. UI receives positions only via Flow.
- **Thread marshalling is the engine's job** (R5-M-iii): `PlayerEngine` methods are non-suspend, and orchestrator calls arrive on the `state` lane — so `ExoPlayerEngine` internally posts every call to the media HandlerThread (`Handler.post`). Proven in M1.5: wrong marshalling = `IllegalStateException` or deadlock on first play.
- `ExoPlayer.Builder.setAudioAttributes(attrs, handleAudioFocus = true)` — Media3 auto-handles loss/pause and **transient-can-duck volume ducking**; `.setHandleAudioBecomingNoisy(true)`; `.setWakeMode(WAKE_MODE_LOCAL)`.
- `DylanMediaService : MediaSessionService` (reverted from MediaLibraryService — its browser callbacks are unjustified for a non-goal and a half-implemented library service misbehaves with Assistant/Wear clients; upgrading later is a superclass swap plus callbacks written anyway). Manifest `foregroundServiceType="mediaPlayback"`. Service attaches/detaches the engine per §4.3.
- POST_NOTIFICATIONS requested at **first play**; denial ⇒ one-time inline card in Now Playing ("Turn on notifications for lock-screen controls") deep-linking to app settings; opportunistic re-request in a later session (C.13).

## 9.10 iOS integration specifics
- `setCategory(.playback, mode: .default)` **at launch** — cheap, interrupts nothing (C.8). `setActive(true)` **immediately before first `play()`**; a thrown `setActive` error maps to `EngineErr.SessionActivation` (real error, not silent no-audio).
- Interruptions: began ⇒ pause; ended + `.shouldResume` ⇒ play. Route change `.oldDeviceUnavailable` ⇒ pause → `RouteLost`.
- `MPRemoteCommandCenter` → intent bus. `MPNowPlayingInfoCenter` collector observes state + throttled position; artwork fetched at 500 px, cached.
- ScenePhase.background ⇒ snapshot, WS close. Audio session keeps process alive during playback ⇒ prefetch continues naturally.
- **Known v1 limitation (documented symmetrically, R3-D):** downloads initiated without playback stall when the app backgrounds on BOTH platforms — Android via process suspension (no FGS justification), iOS via NSURLSession suspension. Prefetch-during-active-playback is unaffected (session/FGS hold the process). Downloads sheet shows a "Paused in background" state rather than a spinner pretending progress. Upgrade path §19.2.
- **Process death is normal, not exceptional** (R6-1): iOS may `SIGKILL` after the background grace period — untrappable, and irrelevant *by design*: `appScope` is per-process, its transient flows (`positionMs`, `downloadProgress`) simply cease and re-initialize empty on cold start; durability lives in the resume snapshot + `download_intents` + startup reconciler. **Never "fix" cold start by hydrating transient RAM state into `settings`.**

## 9.11 Flow→Swift bridge (Kotlin-owned — v1.3 hardening)
```kotlin
// commonMain/bridge/FlowAdapter.kt — the entire bridge
class KotlinSubscription internal constructor(private val job: Job) { fun cancel() { job.cancel() } }
// renamed from `Cancellable` — collided with Combine.Cancellable in Swift (M7)

class FlowAdapter<T : Any>(
    private val flow: Flow<T>,
    private val scope: CoroutineScope,          // container-owned, NEVER Main
) {
    fun subscribe(
        onEach: (T) -> Unit,
        onError: (Throwable) -> Unit = { logBuffer.error("bridge", it) },   // M7: never silent
        onComplete: () -> Unit = {},
    ): KotlinSubscription = KotlinSubscription(
        scope.launch(Dispatchers.Main.immediate) {      // Main.immediate availability verified in M0 spike
            try {
                flow.conflate()                         // M7: BEFORE flowOn — otherwise flowOn's BUFFERED
                    .flowOn(Dispatchers.Default)        // channel queues up to 64 stale states at 10 Hz
                    .collect { onEach(it) }             // heavy work off-main (Law 6)
                onComplete()
            } catch (c: CancellationException) { throw c }
            catch (t: Throwable) { onError(t) }
        }
    )
}
// M0 decision point: if the ObjC bridge degrades `(T) -> Unit` closures to `(Any) -> Void`,
// swap in hand-written concrete adapters (~8 lines each, fully typed):
//   PlayerStateAdapter(scope) · PositionAdapter(scope)   // PositionAdapter also unboxes KotlinLong,
//                                                        // killing 10 Hz allocation churn
```
```swift
// Bridge/DylanBridge.swift — thin sink; buffering explicit; cancellation REAL
func stream<T>(_ adapter: FlowAdapter<T>,
               buffering: AsyncStream<T>.Continuation.BufferingPolicy = .bufferingNewest(1)) -> AsyncStream<T> {
    AsyncStream(T.self, bufferingPolicy: buffering) { cont in
        let handle = adapter.subscribe(onEach: { cont.yield($0) },
                                       onError: { _ in cont.finish() },
                                       onComplete: { cont.finish() })
        cont.onTermination = { _ in handle.cancel() }   // cancels the KOTLIN job (scope-owned)
    }
}

@Observable @MainActor final class PlayerStore {          // @Observable: per-property invalidation
    var state: PlayerState = PlayerState.idle()
    var queueItems: [Song] { state.queue.map(\.self) }    // F14: PersistentList doesn't bridge to Swift
                                                          // collections — accessor copies; cost measured M1.5
    private var handle: KotlinSubscription?
    func bind(_ orch: PlaybackOrchestrator, scope: CoroutineScope) {
        handle = FlowAdapter(flow: orch.state, scope: scope)
            .subscribe(onEach: { [weak self] in self?.state = $0 })   // M7: break store→job retain cycle
    }
    deinit { handle?.cancel() }                           // M7: guaranteed teardown even without stream()
}
```
Why this shape: Swift `Task.cancel()` does not cross the bridge (the v1.1 defect) — cancellation flows through the Kotlin `Job` owned by the container scope; generics survive (`FlowAdapter<PlayerState>` exports as a real generic — no `as!`); collection runs on Default with delivery hopped to Main (Law 6 holds on iOS); `.conflate()` + `bufferingNewest(1)` bound the 10 Hz case together. Leak gate: Instruments must show **zero retained collectors after 20 present/dismiss cycles** — an M0 exit criterion, not a hope (the v1.2 sample would have failed its own gate via the strong-self cycle). SKIE remains the escape hatch if the bridged-flow count grows past ~10.

---

# Part 10 — Performance Engineering

## 10.1 SLOs (p95 unless noted)

| Metric | Target |
|---|---|
| Cold launch → interactive shell | Android < 800 ms · iOS < 800 ms warm, **< 1.5 s on first-launch-after-install** (Kotlin/Native framework init; documented acceptance — first launch is rare, R5-M-i) |
| DB open + reconciler complete | < 300 ms |
| Keystroke → suggestions rendered | < 300 ms p95 **if P4 shows echo/ordered** (FIFO or echo correlation); relaxed to < 700 ms p95 under mandatory single-flight (D22) — the honest number either way |
| Cached tap → audio (warm engine) | < 150 ms |
| Cached tap → audio (cold restore) | < 500 ms |
| Uncached tap → audio, Wi-Fi ≥ 50 Mbps @128 | < 4 s |
| Uncached tap → audio, LTE 10 Mbps @128 | < 8 s |
| Transition, next cached | < 250 ms perceived |
| Transition, next uncached, LTE | < 8 s |
| Scroll, 500-item list | p99 frame < 16 ms equivalent; no sustained jank |
| Memory browsing + playing | < 200 MB; flat trend over 4 h soak |
| Battery, screen-off playback | < 3 %/hour (Pixel 8 / iPhone 15 class) |
| APK / IPA | < 25 MB / < 35 MB |

## 10.2 Enforcement
- Lazy init: app-entry touches only `AppContainer`; DB opens lazily; WS connects on Search focus; reconciler posted to IO.
- Compose stability (M11 follow-through): `PersistentList` in models **and** `stability-config.conf` as belt-and-braces; CI runs the Compose-compiler metrics task asserting `PlayerState` **and the UI models (`Song`, `Album`, `SearchSection`)** report *stable* — a regression fails the build rather than surfacing as scroll jank later (R7-P2: an unstable `Song` silently degrades every LazyColumn row). Keyed lazy items; Coil sized requests (150 rows / 500 NP).
- Polling truthfulness (C.14): *"no polling except the rate-gated position sampler (0 Hz when hidden) and the WS heartbeat."* Android sampler runs on the player's application looper; iOS uses `addPeriodicTimeObserver` with scrub-gating.
- Debug spans in `LogBuffer`: tap→audio, resolve latency, download duration/bytes/rate.
- Baseline profiles + Macrobenchmark kept in M5 but **non-gating** for M6 unless they show regressions (personal-device fleet; R3-F proportionality).

---

# Part 11 — UI/UX Specification

*(Adopts the provided "Dylan Minimal Music App" guideline; deltas called out.)*

## 11.1 Principles
Content is king · 8 dp breathing room · bold readable type · soft rounded edges · purposeful motion ≤ 300 ms · adaptive light/dark.

## 11.2 Tokens (`shared/design/Tokens.kt` — single source)

| Token | Light | Dark |
|---|---|---|
| primary | `#FF6B6B` | `#FF8A80` |
| onPrimary | `#FFFFFF` | `#1C1C1E` |
| background | `#F9F9F9` | `#121212` |
| surface | `#FFFFFF` | `#1C1C1E` |
| surfaceVariant | `#F0F0F0` | `#2C2C2E` |
| textPrimary | `#1C1C1E` | `#FFFFFF` |
| textSecondary | `#6C6C70` | `#A1A1A6` |
| divider | `#E5E5E5` | `#2C2C2E` |
| error | `#FF3B30` | `#FF453A` |
| gradientStop | `#FF9F45` (hero moments only) | |

Spacing 4/8/12/16/24 · radii sm8 md12 lg16 xl20 pill · durations fast150 normal250 spring300. Build emits `tokens.json`; iOS `Tokens.swift` mirrored constants + golden unit test asserting parity — and CI actually runs it (`xcodebuild test`, tokens.json bundled as resource).

## 11.3 Type & icons
System fonts. displayLarge32/Bold · displayMedium24/Bold · titleLarge20/SemiBold · titleMedium16/SemiBold · bodyLarge16 · bodyMedium14 · labelSmall11/Medium; letter-spacing 0/0.25 small. Material Symbols Rounded / SF Symbols; 24 std · 32 transport · 48 main play; tint textPrimary or primary-active only.

## 11.4 Screens (tabs: Home · Search · Library)

**Home** — greeting + gear. Jump back in (local history, offline) · Trending albums (`home_cache`, SWR, offline dot on stale) · Top searches chips · Because you listened to ⟨X⟩ (`reco:<lastAlbumId>`; **section hides silently on error** — R2-4.3). Pull-to-refresh bypasses cache.

**Search** — sticky rounded field. Empty: recent chips (normalized dedupe, original casing displayed) + top searches. Typing: WS sections rendered generically from `modules[]` (Top Result hero, Albums rail, Songs preview, Artists rail). Submitted: paginated Songs list, id-deduped accumulator, 320 badges, non-cacheable rows greyed with "Not available offline" badge (D13).

**Library** — segmented Downloads | Favorites. Downloads: sort menu, swipe-remove (confirm >100 MB), footer `Cached audio X of 2 GB · N songs`. Favorites: **cached/cloud glyph derives from actual `cached_files` presence, never from the pinned flag** (R7-M6 — demotion only clears the pin; a demoted-but-still-on-disk favorite shows the *cached* glyph until LRU actually evicts it, then cloud). Uncached (tap ⇒ download if online). **Downloads sheet** (M4): active/queued/failed states, "Paused in background" honesty state (§9.10).

**Album detail** — blurred-art header wash + gradient scrim, 500 px art lg16, title/artist/year/runtime, Play (gradient pill) + Shuffle, favorite-album heart (favorites all tracks ⇒ `USER_BULK` downloads within pinned budget). Tracklist rows numbered with per-item state glyph. Detail page itself served from `home_cache["album:<id>"]` TTL 7 d SWR ⇒ **fully offline album pages** (C.12).

**Now Playing** — drag handle · art xl20 soft shadow · title displayLarge (**marquee gated on Reduce Motion ∧ visible**) · artist · slider (surfaceVariant 4→6 dp active, primary fill, invisible thumb 24 dp hit, floating scrub label driven by local gesture state, committed on release) · times labelSmall · shuffle/prev/**64 dp primary circle**/next/repeat(badge) · secondary: queue, heart, quality chip. Background: **blur pipeline** — downscale artwork to 64×64 → blur once → cache per songId → bilinear upscale (no real-time blur during the spring transition). Gestures: swipe-down collapse; horizontal swipe on art = next/prev. No lyrics/device buttons.

**Queue** — Up Next + Clear; reorderable (drag handles → `MoveWithinQueue`); current highlighted with eq-bars; swipe-remove; prefetch desired-set reconciles around edits.

**Settings** — grouped: Audio (quality radio + "Metered networks always use 128"; prefetch toggle) · Storage (three-line usage §8.5; Clear cache w/ protected-set copy) · Appearance (system/light/dark) · Data (Export favorites / Import / Export diagnostics) · About (version, provider, personal-use notice).

**Mini player** — above tabs when `current ≠ null`: 40 px art, single-line title/artist, 32 dp play/pause, 2 dp primary progress line (throttled flow). Tap expands. Long-press ⇒ Stop & clear.

## 11.5 SongListItem states
Default · Playing (primary title + 3-bar eq, 1 s loop) · Downloading (per-key ring from `downloadProgress` map — item subscribes individually) · Cached (filled glyph) · Favorited (heart in menu) · NonCacheable (greyed + badge). Long-press: Play next / Add to queue / Favorite / Download now / Go to album.

## 11.6 Motion
Fade-through 200 ms nav · NP spring (damping 0.85) 300 ms · press ripple/scale 0.97 · slider fill animated 250 ms on track change · shimmer 1.2 s — shimmer and eq-bars disabled under Reduce Motion.

## 11.7 Accessibility
48×48 targets · labels on every icon-button · heading traits · Dynamic Type 200 % (wrap, ≤2-line titles) · never color-only state · AA contrast pairs verified for both palettes. **Automated:** Roborazzi/Paparazzi goldens — 8 component states × light/dark, plus 200 % type on 3 representative components (full-matrix goldens = 32 images to review per UI change; the reduced set + a11y assertions catches more per review-minute, R4-smaller); Android `AccessibilityChecks` instrumented pass; XCTest snapshots.

## 11.8 Copy (Strings.kt)
OFFLINE "You're offline — saved music still plays." · NOT_FOUND "This track seems unavailable." · EXPIRED (silent auto-retry; double-fail) "Couldn't refresh this track. Try again." · NETWORK/TIMEOUT "Check your connection and try again." · STORAGE "Not enough space. Free up storage or clear cache." · CORRUPT "That file didn't download cleanly. Retrying…" · NOT_CACHEABLE "This track can't be saved for offline play." · GEO_BLOCKED "Not available in your region." · RATE_LIMITED "Slow down a moment…" · TOO_MANY_FAILURES "Several tracks failed to load. Check your connection." · Empty Downloads/Favorites/Search per v1.1.

## 11.9 iOS image decoding (R3-E5)
`AsyncImage` decodes at native pixel size — a scrolling grid of 500 px arts blows the memory SLO. Hand-rolled `Thumbnailer.swift` via `CGImageSourceCreateThumbnailAtIndex(kCGImageSourceThumbnailMaxPixelSize:)` (~30 lines, dependency-free, Law 9-compliant): rows decode at 150, NP at 500. Disk/memory caching stays the shared injected `URLCache` (§12.2) — never a view-local instance.

---

# Part 12 — Platform Integration Checklists

## 12.1 Android (API 34+)
Permissions: `INTERNET`, **`ACCESS_NETWORK_STATE`** (F1 — metered detection throws `SecurityException` without it, gating quality selection on the first cellular download), `POST_NOTIFICATIONS` (first play), `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `WAKELOCK`. Service declaration with `mediaPlayback` type + MediaSessionService intent-filter. `dataExtractionRules.xml` + legacy `fullBackupContent` exclude `file/audio/`. `reportFullyDrawn()` after first frame. R8 full + resource shrink; arm64 release split.
**targetSdk 36 behavior notes:** edge-to-edge is *enforced* — **every screen** uses explicit inset handling (`WindowInsets` / safe-area padding): Home greeting, Search sticky field, Library segments, Album header, Settings lists, Queue modal — with the Now Playing sheet and mini-player-above-tab-bar as the trickiest cases (R5-M-ii). Predictive back is *on by default* — the NP sheet and Queue modal register back handlers that collapse instead of exiting.

## 12.2 iOS (26+)
`UIBackgroundModes: [audio]`. `Application Support/audio/` + `isExcludedFromBackup`; Data Protection class `CompleteUntilFirstUserAuthentication` explicit on audio + DB directories (§5.6). DB directory intentionally backed up (§5.6). **`URLCache` is instantiated exactly once in `App.init`** (`memoryCapacity ≈ 32 MB`, `diskCapacity = 150 MB`) and injected into the thumbnailer — views never construct a cache (R6-3); `didReceiveMemoryWarning` ⇒ `removeAllCachedResponses()` on the shared instance (blunt, but the only lever `URLCache` offers — disk repopulates) to keep the 200 MB soak SLO honest. Container constructs `AppContainer`, injects `IosPlayerEngine(out: NativeAudioOutputImpl())`. Swift 5 language mode / strict-concurrency minimal initially; `@MainActor` confinement for Kotlin objects; deliberate migration to Swift 6 later. Archive via personal team.

---

# Part 13 — Testing Strategy

## 13.1 Fixture corpus (`fixtures/`, sanitized)
`top_searches.json` · `trending.json` · `album_detail_full.json` · `search_getresults_p1.json` · `search_empty.json` · `autocomplete_ws_frame.json` · `generate_auth_token_128.json` · `song_no320.json` · `song_not_cacheable.json` · `song_no_resolve_ref.json` · `malformed_fields.json` · `html_error_page.txt` · `expired_signature_403.txt` · `rate_limited_429.json`. CI fails if any mapper drops a fixture.

## 13.2 Unit suites
`CacheManagerTest` (eviction math incl. the NULL-ordering direction test; pinned demotion by `pinned_at_ms` with newest-favorite-survives-longest assertion; protected tails incl. upgrade-source rows (F5); `.part` accounting + net-new sizing (M8) **incl. clamped-negative netNew (R7-M4)**; deterministic tiebreaker order (R7-M5)) · `DownloadEngineTest` (fake provider+transport: TTL re-sign, Range resume, ignore-Range restart guard, **416 restart-clean**, Content-Length mismatch ⇒ artifact deleted, **absent-length floor (M6)**, stall watchdog fires mid-copy (M3), ENOSPC IOException⇒STORAGE, preemption, sufficiency dedupe, per-host breaker isolation, breaker fast-fail for USER_NOW, old-file delete on upgrade **and same-bitrate no-delete guard (M2)**, resolve-count cap (M4), extension derivation (F9), **settle-thrash: 5 Next taps within 1 s ⇒ ≤1 download started**, **ftyp asserted on m4a only + MP3 path skips it (R7-Bug1)**, **206 verify against CR-TOTAL catches a range-capped truncated body (R7-Bug2)**, **transport retries never consume resolveCount (R7-Bug3)**, **COMMIT preserves play_count/last_used_ms/pinned_at_ms across upgrade (R7-Bug4)**, **intent upsert never downgrades priority (R7-B3)**) · `OrchestratorTest` (Turbine: transition matrix incl. default rule; **TrackChanged(itemId) mapping incl. unknown-id resync fault**; **SEEK/EXPLICIT reason rows (R7-M1)**; shuffle walk; repeat-ONE history-once; skip-on-error; snapshot writes; **concurrent-intent races**; detached-engine buffering; **replaceUpNext never emits TrackChanged/QueueExhausted (R7 golden)**; **nextUp: repeat-ONE ⇒ current, repeat-ALL wrap, remove-successor-under-shuffle ⇒ correct slot not slot 0 (R7-Bug4/5)**) · `MapperTest` (all fixtures) · `ReconcilerTest` (four orphan classes + partial-cap PREFETCH-first victim order + **GC survival: queued-but-unreferenced song survives cutoff**) · `SearchChannelTest` (**fake WS: out-of-order frames, silence past heartbeat, refusals, mid-typing backgrounding, 800 ms timeout⇒HTTP fallback, consecutive-strike semantics per kind, all three P4 correlation strategies**) · `SnapshotTest` (restore skips missing songs, clamps index, empty⇒Idle, **stale `order[]` sanitized/remapped against filtered queue (R7-Bug4)**) · `BridgeGoldenTest` (tokens.json parity).
**MockK is JVM-only — confined to `androidUnitTest`.** All `commonTest` suites use hand-written fakes exclusively (R4-F12).

## 13.3 Probe — split execution (D21, R4-B3)

JioSaavn is geo-targeted; GitHub-hosted runners sit in US/EU datacenter ranges where responses degrade or block — a hosted nightly would either cry wolf or measure the wrong catalog (P5 gates a *product decision*). So:

- **`probe:local`** — all checks below, run manually from the dev machine on the real usage network **before M0 exit and before each milestone**. Output stamped with date + network + region; P5's measured value is recorded in §1.2 once taken.
- **`probe:ci`** — structural only (api.php returns JSON not HTML; mapper parses one live search shape; WS handshake reachable), nightly GitHub Actions, secrets-free.

| ID | Check | Gates |
|---|---|---|
| P1 | `auth_url`: HEAD `Accept-Ranges: bytes` (**on HEAD failure, retry via GET-Range before declaring failure** — some CDNs 403 HEAD but serve GET ranges, R7-nit); GET `Range: bytes=100-199` ⇒ **206** + exactly 100 bytes; **If-Range variant**: bogus etag ⇒ expect 200-full, matching etag ⇒ expect 206 | **M0** |
| P2 | `ETag` present on media response (enables If-Range) | M0 |
| P3 | `Content-Length` present **and truthful** on sampled objects (enables exact verify; lying lengths ⇒ D25 relaxation becomes evidence-backed) | M0 |
| P4 | WS: 3 rapid queries on one socket — **decision gate**: outputs ECHO / ORDERED / UNORDERED, selecting the correlation strategy (§6.4) | **M0** |
| P5 | Sample 50 songs (trending + 3 search pages): report `% rights.cacheable == false` — **stamped with date+region**, feeds D13's >5 % product decision | **M0** |
| P6 | Re-sign stability: two `generateAuthToken` calls, same path? | M1 |
| P7 | Actual bitrate calibration: `Content-Length ÷ duration` for 128/320 — the measured bytes-per-second **REPLACES the ×125 constant** in §7.3 step 7 (not merely logged; R7-perf#7) | M1 |
| P8 | `autocomplete.get` works as plain HTTP GET (fallback viability) | **M0** |
| P9 | Geo sanity: log resolved CDN edge into diagnostics | M1 |
| P10 | `-500x500` variant exists for 3 sampled images | M0 |
| P11 | Signed-URL TTL ∈ [60 s, 10 min] **vs response `Date` header** (not device clock) | M0 |
| P12 | WS handshake + one round-trip < 2 s | M0 |
| P13 | Signed-media DRIFT sentinel (R7-footgun): GET a signed URL asserting `Content-Type` is audio/* and body is non-HTML — a 200-HTML bot-wall must surface as DRIFT, not as a corrupt-download loop | **M0** |

Any gating check failing ⇒ plan revision, not workaround code (D19).

## 13.4 Device drill matrix — **human checklist, not CI-automatable** (G5)
Kill during download · kill during playback · evict-while-playing · airplane-mode tap · expired-URL replay · corrupt injection · low-storage (< floor) · call/Siri interruption · headphone unplug (both) · Bluetooth meta buttons · Doze/battery-saver prefetch · slow-3G first play · **download-while-playing navigation jank** (R2-6.3) · **4-hour soak: memory sampled every 15 min, assert flat trend** (G6).

---

# Part 14 — Diagnostics & Data Portability

- **LogBuffer**: 512-entry structured ring (ts, level, tag, msg, metaJson). Redaction: URLs stripped after `?`; `resolve_ref` values never logged (songKey only); special tags `resigned_midjob`, `resumed_after_resign`, `breaker_pause`, `intent_orphaned`. Flush on background **and** via crash hooks on BOTH runtimes: `Thread.setDefaultUncaughtExceptionHandler` (Android), `NSSetUncaughtExceptionHandler` **plus** `kotlin.native.runtime.setUnhandledExceptionHook` (iOS — the ObjC handler alone misses Kotlin `abort()` crashes, R4-F11; neither catches SIGSEGV — accepted). Rotating 256 KB ×3 local files; Export via share sheet.
- **Favorites export** `dylan_favorites_v1.json`: `{version, exportedAt, favorites:[{provider, songId, title, subtitle, albumId, albumName, artUrl150, artUrl500, durationSec, has320, resolveRef?, permaToken?}]}` — both art URLs exported (schema requires both NOT NULL; a single field would force fabrication on import, R4-smaller). Import = upsert merge; **dead `resolveRef` on later play ⇒ re-fetch via `permaToken`; both dead ⇒ offer re-search by title+artist** (G3). **Known trade-off (R7-F2, accepted):** `resolveRef`/`permaToken` are opaque source handles and DO ship inside the export file — deliberate, because import must restore offline playability without a network re-search. Fine for a personal sideloaded app; this format must not be reused if distribution ever changes.
- Resume snapshot versioned/tolerant (§5.4, §9.7).
- **Debug screen (M4)**: current `PlayerState` JSON · active download jobs · cache stats · last 50 LogBuffer entries — on-device debugging without ADB/Console (R5-5.1).

---

# Part 15 — Configuration (`AppConfig`)

```kotlin
data class AppConfig(
  // API
  val apiBaseUrl: String = "https://www.jiosaavn.com/api.php",
  val commonParams: Map<String,String> = mapOf("api_version" to "4","_format" to "json","ctx" to "web6dot0","_marker" to "0"),
  val userAgent: String = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36",
  val wsSearchUrl: String = "wss://ws.jiosaavn.com/",
  val wsTypingDebounceMs: Int = 120,
  val wsRequestTimeoutMs: Int = 800,
  val wsPingIntervalMs: Int = 25_000,
  val wsBackoffBaseMs: Long = 1_000, val wsBackoffCapMs: Long = 16_000,
  val submitPageSize: Int = 20,
  // cache
  val cacheMaxFiles: Int = 300,
  val cacheMaxBytes: Long = 2L * 1024 * 1024 * 1024,
  val pinnedMaxFraction: Double = 0.75,
  val imageCacheBytes: Long = 150L * 1024 * 1024,
  val diskFloorBytes: Long = 500L * 1024 * 1024,
  val partGraceHours: Int = 1, val maxConcurrentParts: Int = 3,
  val estimatePadding: Double = 1.25,
  // downloads
  val dlRetries: Int = 2, val dlBackoffBaseMs: Long = 800,
  val resolveCapPerJob: Int = 3,              // M4: caps RESOLVE calls (renamed from resignCapPerJob —
                                              // the old name never bounded what it claimed)
  val rangeRestartsCap: Int = 1,
  val stallTimeoutMs: Long = 20_000, val stallWatchdogTickMs: Long = 2_000,
  val skipSettleMs: Int = 350,
  val prefetchEnabled: Boolean = true,
  val prefetchCellularTracks: Int = 1,        // 0 disables; forced 128 on metered (D26)
  // playback
  val defaultQuality: Quality = Quality.BITRATE_320,
  val meteredQuality: Quality = Quality.BITRATE_128,
  val posHzFull: Int = 10, val posHzMini: Int = 4,
  // misc
  val homeCacheTtlMs: Long = 6L * 60 * 60 * 1000,
  val albumCacheTtlMs: Long = 7L * 24 * 60 * 60 * 1000,
  val homeCacheRowCap: Int = 200,             // F6
  val imageMemoryCacheBytes: Long = 48L * 1024 * 1024,   // F13: ABSOLUTE cap — %-of-heap broke the SLO math
  val historyLimit: Int = 500, val searchHistoryLimit: Int = 20,
  val songsGcAgeDays: Int = 60,
)
```
Platform overrides via expect/actual `PlatformDefaults` merged at construction. User-facing prefs persist in `settings` and override at composition.

---

# Part 16 — Scaffold & Build

Version catalog — **exact pins, no ranges** (R4-B2): `kotlin = "<latest stable whose release notes list support for the installed Xcode major>"` (resolve at M0 hour one; Kotlin/Native breaks on new Xcode majors with regularity) · `agp = "8.x.y"` pinned · `coroutines`, `serialization-json`, `ktor` (+okhttp/darwin/websockets), `sqldelight 2.0.2`, `okio`, `coil3` (Android only), `media3` (exoplayer, session), `navigation-compose`, `lifecycle`, `kotlinx.collections.immutable`, `junit/turbine/robolectric` + MockK (**androidUnitTest only**, F12) · Roborazzi.
iOS: single Xcode project; framework via `embedAndSignAppleFrameworkForXcode`; deployment 26.0; Swift 5 mode; no SPM deps. **CI pins the Xcode version** (`maxim-lobanov/setup-xcode`) so local and CI never drift.
CI: `ktlint+detekt → shared:allTests → androidApp:assembleRelease → xcodebuild **test** -destination 'platform=iOS Simulator,…'` (tokens parity executes) + nightly `probe:ci` job. `probe:local` is a documented manual gate (D21).

---

# Part 17 — Roadmap

| Milestone | Deliverables | Exit criteria |
|---|---|---|
| **M0 Scaffold & Proof** | Workspace; both apps launch empty; **hour one: record Xcode version, pin exact Kotlin, `linkDebugFrameworkIosArm64` green + framework loads on a physical iPhone** (B2); `probe:local` green **incl. P1 (Range + If-Range), P4 (correlation decision), P5 (stamped %), P8, P10–P12**; bridge spike: `FlowAdapter` streams `PlayerState` into SwiftUI `@Observable`, **20 present/dismiss cycles ⇒ zero retained collectors (Instruments)**; spike decisions recorded: `Main.immediate` availability on Native, generic-closure bridging fidelity (else concrete adapters), WS correlation mode per P4; `NativeAudioOutput` shape proven: Swift impl + Kotlin-owned flows, one hardcoded `.m4a` plays **through the shared orchestrator** on a real iPhone | Every `[assumed]` in §3.2 resolved to verified or plan-revised |
| **M1 Core logic** | Schema v1.3 (composite keys, nullable last_used + pinned_at_ms + ext, intents table); SaavnProvider + dual SearchChannel (correlation mode per P4); CacheManager (pinned pool, demotion, protectedKeys); DownloadEngine (full §7.3 as exhaustive sealed-when Kotlin); Orchestrator (appScope, engine holder); repos; GC — full unit suites vs fixtures incl. `SearchChannelTest` | All suites green |
| **M1.5 One song, end-to-end, both platforms** | Deliberately ugly single screen: hardcoded query → result → tap → resolve → download w/ visible progress → play → lock-screen control → kill app → resume. Exercises preemption, dedupe, Range resume, bridge, audio session, pinned accounting, Content-Length verify **against reality before any real UI exists**. **Desync assertion: 3-track queue — after each natural transition, `PlayerState.current` must match the audible track** (B1 proof). Measure `queueAsList()` copy cost (F14). **Prove `ExoPlayerEngine` thread-marshalling** — every engine call from the state lane lands on the media looper with no `IllegalStateException`/deadlock on first play (R5-M-iii) | Works on one Android + one iPhone device; desync assertion passes |
| **M2 Android E2E** | All screens; service/notification/lock-screen; POST_NOTIFICATIONS flow + denied-card; background audio; mini↔NP; queue; album cache; edge-to-edge insets verified | Offline playback; kill-resume; drill subset passes |
| **M3 iOS parity** | SwiftUI screens; NativeAudioOutputImpl complete; remote commands; interruptions/routes; backup exclusion verified; thumbnailer | Same criteria on device |
| **M4 Polish** | Prefetch (cellular 1-track), downloads sheet (+retry-all-failed, R5-5.4), debug screen (R5-5.1), clear-history button (R5-5.3), home/album SWR, favorites export/import, diagnostics export, settings, empty/error copy, Reduce-Motion, goldens + a11y suites. *Optional stretch:* SubsonicProvider read-only spike (seam honesty check) | Feature grid D5 complete |
| **M5 Perf & hardening** | SLO instrumentation; full drill matrix §13.4; baseline profile (non-gating); R8 sizes | §10.1 met & recorded |
| **M6 Release** | Sideload scripts (release keystore doc; iOS archive guide); README | Installable artifacts on both devices |

Sequencing rule: M≥1 blocked on M0 probe gates; M2 blocked on M1.5.

---

# Part 18 — Risks & Mitigations

| Risk | L | Mitigation |
|---|---|---|
| Provider contract drift / UA blocking | Med-High | Quarantine + probe alarm + fixtures; diagnostics pinpoint in minutes; provider seam swap |
| CDN lacks Range support / lies about If-Range | Low-Med | **Probe P1 gates M0** (both Range and If-Range variants); if absent, resume design revisited before any engine code ships |
| Geo-blocking degrades hosted probes | High (for CI) | D21 split: `probe:local` gates milestones from the real network; `probe:ci` structural-only nightly; P5 stamped with date+region |
| Kotlin/Native vs Xcode 26 toolchain breakage | Med | B2: exact version pinning procedure, framework-link task in M0 hour one, CI Xcode pinned |
| WS endpoint instability | Med | Hardened lifecycle + P4-selected correlation + per-request timeout + consecutive-strike HTTP fallback; degrades gracefully |
| Rate limiting unknown thresholds | Low-Med | Single-flight downloads, no polling, **per-host** breakers honoring Retry-After, USER_NOW fast-fail, 429 fixture |
| Signed-URL semantics tighten | Low | Resolve-at-dequeue minimizes exposure; resolve-count capped; P11 range-checked in `probe:local` |
| Cross-re-sign byte mutation | Low | `If-Range` bounds it; P6 measures; `resumed_after_resign` tag correlates any corruption |
| iOS/Android background download stalls (no playback) | Certain | Documented v1 limitation (§9.10); intents persist; Range resumes; §19.2 trigger-gated upgrade |
| Swift/Kotlin concurrency friction | Med | Swift 5 mode + MainActor confinement + M0 leak-verified bridge spike + concrete-adapter fallback ready |
| Non-cacheable catalog share large | Unknown | Probe P5 quantifies in M0 (stamped); >5 % triggers early v2 streaming prioritization (D13) |
| Legal posture (personal use) | — | D6 constraints enforced in-product; exit ramp §19.3 |

---

# Part 19 — Future Enhancements

## 19.1 Watermark streaming + bitrate ladder (v2 headline)
Embedded Ktor CIO server on `127.0.0.1:<ephemeral>`: route `/t/<jobId>` serves Range requests off the growing `.part` (or final file). Engine URL is localhost from byte one; playback starts at `bytes ≥ max(512 KB, 8 s × bitrate)`. Identical mechanism both platforms; Law 1 intact (still one cache writer). Ladder: first-ever play resolves 128; silent 320 re-fetch when favorited/second-play/unmetered-idle. Cold-LTE SLO moves to < 2 s p95. Also dissolves the non-cacheable limitation (D13) if P5 demands it early.

## 19.2 Durable background execution (trigger-gated)
Adopt when data shows backgrounded-paused downloads matter: Android ⇒ WorkManager (expedited, network-constrained) consuming the same `download_intents`; iOS ⇒ URLSession-background executor behind the same internal interface. Policy layers already isolate this to one executor class per platform.

## 19.3 Alternative providers (seam proof)
`SubsonicProvider : MusicProvider` (Navidrome/Jellyfin): stable IDs, plain-URL `resolve_ref`, no signing. Validates Law 3 without touching UI/domain/cache. Composite `(provider, song_id)` keys make coexistence trivial.

---

*End of plan v1.3.2. Implementation begins at M0 — and M0 exists to prove this document wrong in the cheapest possible place.*
