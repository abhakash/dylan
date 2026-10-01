# Dylan architecture — seams, lanes, logging contract

## Seams

| Seam | Prod | Second impl |
|------|------|-------------|
| `PlayerEngine` | `ExoPlayerEngine` (androidApp) / `IosPlayerEngine` (iosMain) | `FakePlayerEngine`, checked against the shared `EngineContractTest` |
| `MusicProvider` | `SaavnProvider` (`provider/saavn/SaavnProvider.kt:52`) | test fakes only — `GraphHarness.StubProvider`, `GatedProvider`, `DownloadEngineTest` |
| `SearchChannel` | `SaavnSearchChannel` — **one** class; WS is a latency fast path, HTTP is the authority | **none.** `HttpSuggest` (`SaavnSearchChannel.kt:553`) is a private collaborator inside that class, not a second implementation |
| `NetMonitor` | Android `NetworkCapabilities` (`Util.android.kt:80`), Darwin `NWPathMonitor` (`Util.ios.kt:45`), JVM fixed stub (`Util.jvm.kt:26`) | `FakeNetMonitor` |
| `NativeAudioOutput` | Swift `NativeAudioOutputImpl` over `AVQueuePlayer` | — (iOS-only seam) |

`SearchChannel` is the one place where the interface is aspirational: `container.net.searchChannel`
is typed `SaavnSearchChannel`, not `SearchChannel` (`AppContainer.kt:153`, `Graphs.kt:52`), so
production cannot be substituted behind it today. `MusicProvider` is the same
(`AppContainer.kt:152`). Both are still worth the indirection — the test fakes are real
consumers — but neither is a swap point a product change could use.

The reliability ordering inside `SaavnSearchChannel` is deliberate and inverted from the
obvious one: the WS frame carries no query echo and no request id
(`SaavnSearchChannel.kt:76-81`), so FIFO position is strictly *weaker* correlation than
HTTP's implicit correlation by construction. Every doubt — offline, cooldown, timeout, socket
error, undecodable frame, a mispaired demand — falls back to HTTP rather than rendering a
plausible answer for the wrong query.

`IosGraph` reuses `AppContainer` — startup reconciler, restore-from-snapshot,
weekly GC, and download engine are shared, not mirrored.

## Composition root — `AppContainer`

The container is a **graph you receive**, not a locator you navigate. It is the only
place that constructs components, and it constructs four narrow facades:

| Facade | Members | Lanes |
|---|---|---|
| `container.data` | `db`, `settings`, `favorites`, `history`, `searchHistory`, `homeCache` | db |
| `container.files` | `fs`, `paths`, `fileLog` | io |
| `container.net` | `api`, `bulk`, `ws`, `provider`, `searchChannel`, `netMonitor` | io |
| `container.playback` | `orchestrator`, `downloads`, `reconciler`, `breakers`, `cacheManager` | state + db + io |

`cacheManager` is the one documented inversion: it writes rows *and* unlinks files, so it is
not database-only, and `data.favorites` is its one consumer.

**Lifecycle.** The constructor does no I/O — no directory is created, no driver is
opened, no Ktor client is built, no component is instantiated.

- `suspend fun open()` — creates `audio/` and `logs/`, opens SQLite (schema probe,
  first-run `Schema.create`, four PRAGMAs) and builds the three Ktor clients, all on
  the io lane. Idempotent. `start()` calls it if nobody has, so omitting it degrades to
  the old eager behaviour rather than to a broken graph.
- `fun start()` — idempotent and re-entry safe (one `compareAndSet`); publishes the four
  background jobs.
- `suspend fun stop()` — `cancelAndJoin`s every coroutine the container launched, stops the
  `DownloadEngine`, closes the three `HttpClient`s plus the shared `HttpClientEngine`, flushes
  **and closes** the log trail, and resets `protectedKeys` (`AppContainer.kt:324-337`). The
  container **takes ownership** of the engine it is given. `start()` after `stop()` relaunches
  the background work; the HTTP layer is not rebuilt, so a container that stopped and must
  serve again is replaced.
- `suspend fun shutdown()` — `stop()` plus every scope handed out by `componentScope()`.
  Terminal.

**Who actually calls them.** Exactly one production path reaches `stop()`:
`IosGraph.dispose()` → `container.shutdown()` → `stop()` (`IosGraph.kt:197`), driven from
Swift by `AppEnvironment.teardown()` on `applicationWillTerminate` (`DylanApp.swift:238-240`
→ `:143` → `DylanBridge.swift:413`). **Android never calls either** — `MainActivity.onDestroy`
only fire-and-forget flushes the log sink (`MainActivity.kt:64-68`) and
`DylanMediaService.onDestroy` releases the session and engine
(`DylanMediaService.kt:420-447`); the container's Ktor clients, HTTP engine, state-lane
`SupervisorJob` and file-log handle are left to process death. So `stop()` is a real,
exercised lifecycle step on iOS and an unclaimed one on Android, and
`GraphLifecycleTest.kt:172,219,234` is currently the only thing covering the Android-shaped
usage.

**Job ownership.** The container creates the `SupervisorJob`; components receive a
`CoroutineScope` from `container.componentScope(name)` and own no `Job`. `shutdown()`
reaches all of them. `DownloadEngine` is the one remaining site that still builds its own
`SupervisorJob` — but it recreates it on every `start()` rather than reusing a cancelled one
(`DownloadEngine.kt:174`), which is the fix for the old stop/start brick; ownership still
belongs in the container.

## Lanes (dispatchers)

`AppDispatchers(main, io, dbLane, state)`. The four properties are the **raw**
dispatchers — they are not wrapped. A lane is entered with `disp.on(Lane.X)`, which is
`dispatcher + LaneTag + the thread-scoped publication`, so `disp.assertInContext(Lane.X)`
checks the lane from a coroutine that entered through `on` (portable, and the check
production code uses) and `disp.assert(Lane.X)` / `disp.current()` check it from ordinary
non-suspending code on the JVM and Android. Both asserts compile out in release.

Wrapping each dispatcher in a `LaneDispatcher` was tried and removed: it published the
lane from `dispatch()` by allocating a wrapper `Runnable` per dispatch, and because the
wrapper is not a `Delay`, every lane `delay()` fell back to the global real-time
`DefaultDelay` — an extra dispatch hop per delay and no virtual time in tests. `on`
composes with any dispatcher, `TestDispatcher` included.

- `state` — single-threaded (`limitedParallelism(1)`); owns queue/orchestrator state.
  **No `runBlocking` on this lane.** Every `scope` handed to a component is
  `SupervisorJob + state + CEH` so one crashed coroutine never cancels siblings.
- `dbLane` — single-threaded; all SQLDelight access (WAL, `busy_timeout=5000`).
- `io` — network + filesystem.
- `main` — UI only.

No `synchronized` in `commonMain` (JVM-only) — shared code uses lock-free
copy-on-write rings (`AtomicReference`) and `Mutex` where mutual exclusion is needed.

**The Swift bridge hops to `main` explicitly.** `FlowAdapter` takes its lanes and its
`LogBuffer` from the graph (`BridgeLanes`), collects with `disp.on(Lane.MAIN)`, and
asserts the lane on every delivery; `MainThread<T>` names the hop in the signature. There
is no `flowOn` — the upstream flows are all `StateFlow`s, so it only added a second
dispatch per emission onto a pool iOS shares with `state`/`dbLane`/`io`.

## Logging contract

- `LogBuffer`: in-memory ring (default 512 entries, `minLevel` INFO release / DEBUG
  debug builds), lock-free COW ring + additive sinks. Every entry: `ts/level/tag/msg/metaJson`.
- `FileLogSink`: persistent trail at `<baseDir>/logs/dylan.log.{0,1,2}` —
  `FILE_BYTES_DEFAULT = 512_000` B per file, `filesToKeep = 2` archives **+ live file =
  3 files max** (~1.5 MB cap; the figure is 512 000, not 512 KiB). Async `DROP_OLDEST`
  channel (1024); single writer drains in batches, flushing after each idle window (50 ms)
  — crash window ≈ 50 ms of tail.
- Line format: `2025-08-24T01:46:40.000Z I/dl: enqueue saavn:s1 bits=128`
  (`<UTC ms> <LEVEL_INITIAL>/<tag>: <msg> [meta]`). Query tags like `dl`, `play`,
  `boot`; see `docs/triage.md` for greps.
- Byte accounting is UTF-8, via a reused `okio.Buffer` staging write rather than a per-line
  `encodeToByteArray()` allocation — okio's fluent `writeUtf8` returns the sink, not the
  byte count, so the byte count has to come from `staging.size` (`FileLogSink.kt:45-48,106-125`).
- Lifecycle, and who reaches it:
  - `AppContainer.onBackground()` → `flushLogAsync()`: a fire-and-forget
    `flush(timeoutMs = 2000)` on the io lane (`AppContainer.kt:505-515`). Never blocks the
    background budget. Reached from `MainActivity.onStop` (Android) and
    `IosGraph.onBackground()` (iOS).
  - `MainActivity.onDestroy` adds its own fire-and-forget flush, because Android never calls
    `stop()` (`MainActivity.kt:61-69`).
  - `stop()` → `closeLogTrail()`: a **suspending** `flush(2000)` then `close()`
    (`AppContainer.kt:517-521`, reached at `:331`). `close()` drops the file handle and is
    reopened lazily by the next write (`FileLogSink.kt:86-89,106-113`), so it loses no bytes —
    it only trades a held descriptor for a reopen. It is the **only** production caller of
    `FileLogSink.close()`, and it is iOS-terminate-only, as above.
  - `shutdown()` adds no flush of its own: it calls `stop()` first (`AppContainer.kt:347`),
    so the last flush is the one inside `stop()`.
- Boot line carries version + session for correlation without touching playback:
  `I/boot: container up v=<APP_VERSION> session=<uuid> dir=<baseDir> openMs=<n>`
  (`AppContainer.kt:396`, emitted at the end of `open()` so it is the first line in the trail).
  `APP_VERSION` is a hand-maintained literal (`AppContainer.kt:532`) and has **drifted**:
  it reads `0.1.0` while root `VERSION` is `1.0.0`. `bump-version.sh` writes `VERSION` and
  the pbxproj, never this constant, so the boot line under-reports the shipped version.
- Platform mirrors: Android binds a logcat sink (`Dylan:<tag>`); iOS logs via
  `NSLog` on scope failures. Console mirrors are additive — the file is the record.
