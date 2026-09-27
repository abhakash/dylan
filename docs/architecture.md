# Dylan architecture — seams, lanes, logging contract

## Seams (only interfaces; each has a second impl today)

| Seam | Prod | Alt |
|------|------|-----|
| `MusicProvider` | `CatalogProvider` (Saavn) | test fakes |
| `SearchChannel` | WS + HTTP (`SaavnSearchChannel`) | HTTP-only fallback |
| `PlayerEngine` | `ExoPlayerEngine` / `IosPlayerEngine` | `FakeEngine` in tests |

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
- `suspend fun stop()` — `cancelAndJoin`s every coroutine the container launched, resets
  `protectedKeys`, flushes and closes the log trail, and closes the three `HttpClient`s
  plus the shared `HttpClientEngine`. The container **takes ownership** of the engine it
  is given. `start()` after `stop()` relaunches the background work; the HTTP layer is
  not rebuilt, so a container that stopped and must serve again is replaced.
- `suspend fun shutdown()` — `stop()` plus every scope handed out by `componentScope()`.
  Terminal. This is what a platform teardown path calls (`IosGraph.dispose()`).

**Job ownership.** The container creates the `SupervisorJob`; components receive a
`CoroutineScope` from `container.componentScope(name)` and own no `Job`. `shutdown()`
reaches all of them. `DownloadEngine` is the one remaining site that still builds its own
`SupervisorJob` (it recreates it on every `start()`, so stop/start works, but ownership
still belongs in the container).

## Lanes (dispatchers)

`AppDispatchers(main, io, dbLane, state)`. Each property is a `LaneDispatcher` that
publishes the lane to a thread-local on every dispatch, so `disp.assert(Lane.X)` checks a
lane from ordinary non-suspending code and `disp.assertInContext(Lane.X)` checks it from a
coroutine that entered through `disp.on(Lane.X)`. Both compile out in release.

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
  512 KB per file, `filesToKeep = 2` archives **+ live file = 3 files max**
  (~1.5 MB cap). Async `DROP_OLDEST` channel (1024); single writer drains in batches,
  flushing after each idle window (50 ms) — crash window ≈ 50 ms of tail.
- Line format: `2025-08-24T01:46:40.000Z I/dl: enqueue saavn:s1 bits=128`
  (`<UTC ms> <LEVEL_INITIAL>/<tag>: <msg> [meta]`). Query tags like `dl`, `play`,
  `boot`; see `docs/triage.md` for greps.
- Byte accounting uses UTF-8 bytes (`encodeToByteArray().size`), not chars.
- Lifecycle: `AppContainer.onBackground()` and `stop()` fire-and-forget
  `FileLogSink.flush(timeoutMs = 2000)` on the io lane — never blocks UI/background
  budget. `stop()` additionally `close()`s the handle (reopened lazily on the next write);
  `shutdown()` is the last flush.
- Boot line carries version + session for correlation without touching playback:
  `I/boot: container up v=0.1.0 session=<uuid> dir=<baseDir> openMs=<n>`
  (`APP_VERSION` in `AppContainer` companion, synced with root `VERSION`). It is emitted
  at the end of `open()`, so it is the first line in the file trail.
- Platform mirrors: Android binds a logcat sink (`Dylan:<tag>`); iOS logs via
  `NSLog` on scope failures. Console mirrors are additive — the file is the record.
