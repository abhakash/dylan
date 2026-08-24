# Semver — Dylan 0.1.x

Source of truth: `VERSION` file (e.g. `0.1.0`) at repo root.
Tag: `v0.1.0` is first release. Every CI build auto-increments `versionCode` without touching `VERSION`.

## How it increments every build

`androidApp/build.gradle.kts: semver()` reads:

```
base = VERSION.trim() // 0.1.0
commitCount = git rev-list --count HEAD // 1,2,3...
versionCode = MAJOR*1_000_000 + MINOR*10_000 + PATCH*100 + (commitCount %100)
versionName = base + (CI ? "+count" : "-dev.count+hash")
iOS MARKETING_VERSION = base, CURRENT_PROJECT_VERSION = commitCount
```

Examples:
- After `git tag v0.1.0` (count 1): `versionCode 10001`, `versionName 0.1.0+1` (CI) / `0.1.0-dev.1+718a20c` (local), iOS `MARKETING 0.1.0 BUILD 1`
- Next commit (count 2): `10002`, `0.1.0+2`
- After `./tools/bump-version.sh patch` → `0.1.1` (count 2): `10102`, `0.1.1+2`

So every commit is a new build; semantic bump only on releases.

## Manual bumps

```bash
./tools/bump-version.sh show   # 0.1.0
./tools/bump-version.sh patch  # 0.1.0 -> 0.1.1 (also syncs iOS)
./tools/bump-version.sh minor  # 0.1.1 -> 0.2.0
./tools/bump-version.sh major  # 0.2.0 -> 1.0.0
git add VERSION iosApp/iosApp.xcodeproj/project.pbxproj
git commit -m "chore(release): v0.1.1"
git tag v0.1.1 && git push origin main --tags
# or via GitHub: Actions → release → Run workflow (bump patch/minor/major)
```

## CI

`release.yml` workflow_dispatch does bump+verify gates+tag+push+build release APK.
`ci.yml` just builds with count-based version — no file mutation, so no loop.

## Play Store limit

`versionCode` < 2_100_000_000. Formula caps PATCH*100 + count%100, safe to ~2100.0.0.

## Tools

- `tools/check.sh` — full local gate (ktlint, detekt, lintDebug, jvmTest --rerun-tasks, assembleDebug/Release, iOS klibs) + SHA
- `tools/sync-ios-version.sh` — syncs `MARKETING_VERSION`/`CURRENT_PROJECT_VERSION` from `VERSION`+`count`
