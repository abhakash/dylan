# Dylan architecture — seams, lanes, logging contract

## Seams (only interfaces; each has a second impl today)

| Seam | Prod | Alt |
|------|------|-----|
| `MusicProvider` | `CatalogProvider` (Saavn) | test fakes |
| `SearchChannel` | WS + HTTP (`SaavnSearchChannel`) | HTTP-only fallback |
| `PlayerEngine` | `ExoPlayerEngine` / `IosPlayerEngine` | `FakeEngine` in tests |

`IosGraph` reuses `AppContainer` verbatim — startup reconciler, restore-from-snapshot,
weekly GC, and download engine are shared, not mirrored.

## Lanes (dispatchers)

`AppDispatchers(main, io, dbLane, state)`:

- `state` — single-threaded (`limitedParallelism(1)`); owns queue/orchestrator state.
  **No `runBlocking` on this lane.** App scope is `SupervisorJob + state + CEH` so one
  crashed coroutine never cancels siblings.
- `dbLane` — single-threaded; all SQLDelight access (WAL, `busy_timeout=5000`).
- `io` — network + filesystem.
- `main` — UI only.

No `synchronized` in `commonMain` (JVM-only) — shared code uses lock-free
copy-on-write rings (`AtomicReference`) and `Mutex` where mutual exclusion is needed.

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
  budget. `close()` only closes the handle (reopened lazily).
- Boot line carries version + session for correlation without touching playback:
  `I/boot: container up v=0.1.0 session=<uuid> dir=<baseDir>`
  (`APP_VERSION` in `AppContainer` companion, synced with root `VERSION`).
- Platform mirrors: Android binds a logcat sink (`Dylan:<tag>`); iOS logs via
  `NSLog` on scope failures. Console mirrors are additive — the file is the record.
