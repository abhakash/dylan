# Triage — extracting logs per platform

File trail: `<baseDir>/logs/dylan.log.0` (live), `.1`, `.2` (rotated archives).
Line format: `<UTC ISO ms> <LEVEL_INITIAL>/<tag>: <msg> [metaJson]`, e.g.

```
2025-08-24T01:46:40.000Z I/dl: enqueue saavn:s1 bits=128
2025-08-24T01:46:41.003Z W/play: stall watchdog fired
2025-08-24T01:46:42.120Z I/boot: container up v=0.1.0 session=aaaabbbb-… dir=/…
```

## Android

```bash
# file trail (debuggable builds)
adb shell run-as app.dylan.player cat files/logs/dylan.log.0
adb shell run-as app.dylan.player ls files/logs/
# older rotations
adb shell run-as app.dylan.player cat files/logs/dylan.log.1
# live console mirror
adb logcat -s "Dylan:*"
```

## iOS simulator

```bash
# container path for the booted sim (UDID changes per device — see warning)
xcrun simctl get_app_container booted app.dylan.player.ios data
cat "$(xcrun simctl get_app_container booted app.dylan.player.ios data)/Application Support/dylan/logs/dylan.log.0"
# list sims when `booted` is ambiguous
xcrun simctl list devices
```

> **Sim UUID warning:** the simulator UDID and the DerivedData `.app` path change
> across devices/builds — never hardcode them. Resolve via
> `xcrun simctl get_app_container booted <bundle>` and
> `find ~/Library/Developer/Xcode/DerivedData -name "iosApp.app"`.

## iOS device

Download the app container from Xcode → Devices, then open
`Application Support/dylan/logs/dylan.log.*`. No filesharing entitlement is
declared, so container download is the path.

## Useful greps (real tags/levels)

```bash
grep "I/boot" dylan.log.*    # session + version per run (correlate here first)
grep "I/dl:" dylan.log.*     # download enqueue/complete
grep "W/play:" dylan.log.*   # playback warnings (stalls, preemptions)
grep "E/\|C/" dylan.log.*    # errors + criticals
grep "W/dl:" dylan.log.*     # download retries / breaker opens
grep "I/reconciler" dylan.log.*  # boot sweep
```

Level initials: `D/I/W/E/C` = DEBUG/INFO/WARN/ERROR/CRITICAL (see
`FileLogSink.format`: `${level.name.first()}/${tag}:`).

## DB vs audio vs logs split

| Data | Android | iOS |
|------|---------|-----|
| Audio cache | `filesDir/audio/` (`<baseDir>/audio`) | Application Support `dylan/audio/` |
| Logs | `filesDir/logs/` | Application Support `dylan/logs/` |
| SQLite (`dylan.db`, WAL) | `databases/dylan.db` (`run-as … cat databases/dylan.db` to pull) | Application Support `dylan.db` — **sibling** of the `dylan/` dir, not inside it (Native driver forbids path separators) |

Audio eviction (`CacheManager`/reconciler) never touches logs or the DB file —
a "missing file" report needs the audio path + DB row, not the log trail.
