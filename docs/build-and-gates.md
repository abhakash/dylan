# Build & Gates

Everything that can turn a build red, and what specifically makes it red.

`tools/check.sh` is the local equivalent of the hermetic presubmit set, plus
`assembleDebug` + `assembleRelease` and an assertion that both APKs exist and are
non-empty. It is the shortest way to reproduce presubmit before opening a PR:

```bash
./tools/check.sh                    # hermetic gates + both APK flavors
DYLAN_LIVE_GATES=1 ./tools/check.sh # also runs probeCi + contractDrift (live API)
```

## Presubmit (every push / PR) — hermetic, no network

| Gate | Task | Fails on |
| --- | --- | --- |
| supply-chain | `wrapper-validation` | tampered Gradle wrapper jar |
| lint | `ktlintCheck detekt :androidApp:lintDebug` | style, any detekt finding, any Android Lint finding outside the lint baseline (there is no detekt baseline) |
| test | `:shared:jvmTest --rerun` | any test failure (the test task is force-re-executed, so results are never stale) |
| android/debug | `:androidApp:assembleDebug` | compile/R8 failure, or no APK produced |
| ios/klib | `:shared:compileKotlinIosSimulatorArm64 :shared:compileKotlinIosArm64` | Kotlin/Native compile failure |
| ios/simulator | `:shared:assemble`, `:shared:iosSimulatorArm64Test`, `xcodebuild` | native link failure, `commonTest` failure on iOS, Swift build failure |
| gosign | `go build/vet/test` | any Go failure |

`--rerun` (task-scoped) is used instead of `--rerun-tasks`: it re-executes
`:shared:jvmTest` so a cached/UP-TO-DATE result can never be reported as green,
while leaving `compileKotlinJvm` / `compileTestKotlinJvm` incremental and
build-cacheable. `--rerun-tasks` forces a full main + test recompile on every
run for no correctness gain. It appears in `ci.yml:89`, `builds.yml:63` and
`tools/check.sh:17`; `release.yml:52` uses it too, though that workflow's gate
is the same trio minus Android lint.

### the presubmit graph is a fan, not a chain

`wrapper-validation` gates everything; then four jobs run as **siblings**
(`lint`, `test/jvm`, `ios/klib`, `gosign` — `ci.yml:39,71,142,227`), and only
`android/debug` and `ios/simulator` wait for `lint` + `test/jvm`
(`ci.yml:106,166`). So `ios/klib` and `gosign` can be green while `lint` is red:
they are not downstream of the linters, and a klib compile will not catch a
ktlint violation. Branch protection that requires only the five `ios/*`/`android/*`
job names leaves `lint` unrequired.

### one flag, one place

`--no-configuration-cache` survives in exactly one invocation in the tree: the
shipped iOS build phase (`iosApp/iosApp.xcodeproj/project.pbxproj:213`). It is
load-bearing there — the Kotlin embed task reads `CONFIGURATION` / `SDK_NAME` /
`ARCHS` / `TARGET_BUILD_DIR` / `FRAMEWORKS_FOLDER_PATH` from the Xcode
environment, which a configuration-cache replay would not reproduce. There is no
`configuration-cache` setting in `gradle.properties`, so CC is off everywhere
else by omission rather than by decision.

## Nightly / manual (schedule + `workflow_dispatch`) — hits the live JioSaavn API

| Job | Task | Fails on |
| --- | --- | --- |
| nightly/probe | `:shared:probeLocal -PprobeFast`, `:shared:probeCi` | any `M0`-gated check that **FAILs or TIMEOUTs**. `probeCi` runs S1 (api.php JSON + live search mapping), S2 (plain-HTTP autocomplete), S3 (WS handshake); all three are tagged `gate = "M0"` and `gates = true`, so a failure exits 1. A hang counts: `withTimeoutOrNull` records it as `TIMEOUT` and a blackholed origin (captive portal, firewall DROP) is the most common way a live gate breaks, so gating only on `FAIL` reported the total absence of an answer as success. |
| nightly/contract-drift | `:shared:contractDrift` | any `FAIL`-severity drift row, or any unreachable endpoint. |

These are deliberately **not** presubmit: they depend on a third-party API being
up, so a Saavn outage must not block a PR.

### proving the probe gate can go red

`probeCi` reaches the live API, so "it exits 0" is not evidence that it can exit
1. `DYLAN_PROBE_API_BASE` / `DYLAN_PROBE_WS_URL` repoint it, and
`DYLAN_PROBE_TIMEOUT_MS` shortens the per-check window so the proof does not take
45 s per check. `10.255.255.1` is RFC1918 and unrouted, so packets are *dropped*
rather than refused — which is what produces `TIMEOUT` rather than `FAIL`:

```bash
DYLAN_PROBE_API_BASE=https://10.255.255.1/api.php \
DYLAN_PROBE_WS_URL=wss://10.255.255.1/ \
DYLAN_PROBE_TIMEOUT_MS=4000 ./gradlew :shared:probeCi; echo "exit=$?"
```

Expect three `[!] … (M0) … TIMEOUT` rows, `GATING FAILURES: S1(TIMEOUT) …` and
`exit=1`.

Report output (which embeds live API response bodies) is written to
`build/reports/probe/`, which is gitignored. `tools/probe-results.md` is
untracked for the same reason.

### contract-drift severities

`FAIL` (exit 1): `NEW_FIELD`, `TYPE_DRIFT`, `MISSING_FIELD`, `MAPPING_LOSS`,
plus the structural failures `UNREACHABLE`, `NOT_JSON`, `DECODE_FAIL`,
`ENDPOINT_EMPTY`, `EMPTY_MAPPING`, `EMPTY_TRACKLIST`, `MAPPED_NULL`,
`TOKEN_DERIVATION_FAILED`, `TOKEN_MISMATCH`, `SIGNING_FAILED`, `REPORT_EMPTY`.

`WARN` (reported, exit 0): `PARTIAL_PRESENT`, `NULLABILITY_DRIFT`,
`DUPLICATE_CARDS`, `DEDUPE_DROPPED`, `PARSE_FAIL`, `KEY_MISSING`,
`FALLBACK_NUMERIC_ID`, `SKIP_NO_TOKEN`.

Escape hatch for triage: `DYLAN_DRIFT_ADVISORY=1 ./gradlew :shared:contractDrift`
reports every finding but always exits 0.

Endpoints covered (was 3, now 8): `search.getResults`,
`content.getTopSearches`, `content.getTrending`, `search.getAlbumResults`,
`search.getArtistResults`, `webapi.get?type=album`, `webapi.get?type=artist`,
`song.generateAuthToken`, plus the `ws autocomplete.get` text frame.

A missing or unparseable file in `fixtures/` is a hard error — the tool aborts
instead of comparing against an empty expectation set. `fixtures/` is resolved
from `-Ddylan.fixturesDir` (set by the Gradle task), else the nearest ancestor
of the working directory, else the nearest ancestor of the loaded class, so it
no longer depends on `workingDir`.

## Release / build lanes

`builds.yml` has a `gate` job (`ktlintCheck detekt :shared:jvmTest --rerun`,
`builds.yml:63`) that every build job `needs:` (`builds.yml:67,105,178,223`) — no
APK, IPA or `.aab` is produced from a tree that has not been linted and tested in
that same run. It is needed because the release commit pushed by `release.yml` carries
`[skip ci]`, so `ci.yml` never runs for it.

**The gate is narrower than presubmit on purpose and narrower by omission:** it omits
`:androidApp:lintDebug`, which `ci.yml:54` and `tools/check.sh:13` both run. A release can
therefore be cut from a tree that has never been Android-linted. Separately, the
`platform: android/ios/all` input on `builds.yml` is declared but never read — all four
`if:` conditions test `inputs.flavor` only (`builds.yml:68,106,179,224`), so a
`platform: android` dispatch still builds the iOS lanes.

`release.yml` materialises `keystore.properties` + `dylan-release.keystore` from
secrets when they are present, and runs `apksigner verify --print-certs` on every
produced APK, failing if the chain says `Android Debug`. When the secrets are
**absent** it does not skip and does not silently fall back to the debug key: it
generates a disposable keystore in the job and the release body and artifact name
both say *devsigned*, because an unsigned APK cannot be installed by Android at
all and a canary nobody can open is not a canary.

`release.yml` refuses to create a tag that already exists rather than
force-creating it. That collision is exactly what a history rewrite produces — the
tag points at a commit that is no longer published, so re-cutting would silently
repoint something published. It aggregates every conventional commit since the
previous tag to pick the bump (`major` on `!:`/`BREAKING CHANGE`, `minor` on
`feat:`, else `patch`), so a squash merge of three `feat:` commits is still a minor
bump, and it skips entirely when the range contains nothing but release
bookkeeping.

## Detekt

`buildUponDefaultConfig = true`: `config/detekt.yml` is a **delta** on the
packaged detekt defaults, not a replacement. Adding a rule there as
`active: false` to silence a finding is not the mechanism. There is no detekt baseline any more either: both were deleted and the `baseline = ...` wiring removed from the two `build.gradle.kts` files, so `detektBaseline` cannot regrow one.

Sources are declared explicitly because the KMP layout does not match detekt's
`src/main/kotlin` default (this was why `:shared:detekt` was `NO-SOURCE`):

```
src/commonMain/kotlin  src/jvmMain/kotlin  src/androidMain/kotlin
src/iosMain/kotlin     src/commonTest/kotlin  src/jvmTest/kotlin
```

**There is no baseline.** There used to be two — `config/detekt-baseline.xml`
(shared, 132 entries) and `config/detekt-baseline-androidApp.xml` (113) — and both
have been deleted along with the `baseline = ...` wiring in the two
`build.gradle.kts` files. `detektBaseline` is therefore not reachable from a
normal build, so the suppression list cannot regrow unnoticed.

The reason is that the baselines had stopped functioning as a record. A baseline
entry cannot say *why* something was accepted, so "we looked at this and accepted
it" and "nobody ever ran the linter on this file" are indistinguishable in the
file. It also leaked: detekt's baseline id for `MaxLineLength` is
`MaxLineLength:<file>.kt$` with an empty signature, so a *new* over-long line
added to an already-baselined file was silently not reported. Between them the two
files were suppressing 245 findings, which is why the gate looked healthy while
`detekt` had little to say about most of the UI.

If a rule is genuinely wrong for this codebase, change it in `config/detekt.yml`,
in the open, with the reasoning in a comment. Do not re-add a baseline.

Rules deliberately left off: `TopLevelPropertyNaming` (project policy). Rules
deliberately kept ON: `LongMethod` (80), `CyclomaticComplexMethod` (20),
`LongParameterList` (12), `TooManyFunctions` (25), `MagicNumber`, `ReturnCount` (4),
`TooGenericExceptionCaught`.

## Android Lint

`abortOnError = true`, `checkDependencies = true` (so `:shared` is linted as a
dependency too), baseline `androidApp/lint-baseline.xml` regenerated with
`./gradlew :androidApp:updateLintBaseline`. The orphaned `config/lint.xml` — a
file nothing referenced, so its `NewApi=error` policy never took effect — was
deleted; `NewApi` keeps its default `error` severity and `MissingPreview` is
disabled in the Gradle DSL instead.

`DylanMediaService` declares `android:permission="android.permission.MEDIA_CONTENT_CONTROL"`,
which cleared the `ExportedService` finding outright rather than baselining it.
