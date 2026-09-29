# Build & Gates

Everything that can turn a build red, and what specifically makes it red.

## Presubmit (every push / PR) — hermetic, no network

| Gate | Task | Fails on |
| --- | --- | --- |
| supply-chain | `wrapper-validation` | tampered Gradle wrapper jar |
| lint | `ktlintCheck detekt :androidApp:lintDebug` | style, any detekt finding outside the baselines, any Android Lint finding outside the baseline |
| test | `:shared:jvmTest --rerun` | any test failure (the test task is force-re-executed, so results are never stale) |
| android/debug | `:androidApp:assembleDebug` | compile/R8 failure, or no APK produced |
| ios/klib | `:shared:compileKotlinIosSimulatorArm64 :shared:compileKotlinIosArm64` | Kotlin/Native compile failure |
| ios/simulator | `:shared:assemble`, `:shared:iosSimulatorArm64Test`, `xcodebuild` | native link failure, `commonTest` failure on iOS, Swift build failure |
| gosign | `go build/vet/test` | any Go failure |

`--rerun` (task-scoped) is used instead of `--rerun-tasks`: it re-executes
`:shared:jvmTest` so a cached/UP-TO-DATE result can never be reported as green,
while leaving `compileKotlinJvm` / `compileTestKotlinJvm` incremental and
build-cacheable. `--rerun-tasks` forces a full main + test recompile on every
run for no correctness gain.

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

`builds.yml` has a `gate` job (`ktlintCheck detekt :shared:jvmTest --rerun`)
that every build job `needs:` — no APK, IPA or `.aab` is produced from a tree
that has not been linted and tested in that same run.

`release.yml` materialises `keystore.properties` + `dylan-release.keystore`
from secrets and **exits 1 when either secret is missing** (an earlier version
skipped gracefully, which meant the debug signing config was used and a
debug-signed APK was uploaded as "release"). It then runs `apksigner verify
--print-certs` on every produced APK and fails if the cert chain says
`Android Debug`.

`version.yml`'s push-retry loop sets an `ok` flag and `exit 1` if all three
attempts fail, so a version bump that is committed locally but never published
no longer reports success.

## Detekt

`buildUponDefaultConfig = true`: `config/detekt.yml` is a **delta** on the
packaged detekt defaults, not a replacement. Adding a rule there as
`active: false` to silence a finding is not the mechanism — the baseline is.

Sources are declared explicitly because the KMP layout does not match detekt's
`src/main/kotlin` default (this was why `:shared:detekt` was `NO-SOURCE`):

```
src/commonMain/kotlin  src/jvmMain/kotlin  src/androidMain/kotlin
src/iosMain/kotlin     src/commonTest/kotlin  src/jvmTest/kotlin
```

Baselines (detekt's `detektBaseline` task *rewrites* the file it is pointed at
rather than merging, so the two modules cannot share one):

* `config/detekt-baseline.xml` — shared
* `config/detekt-baseline-androidApp.xml` — androidApp

Rules deliberately left off: `TopLevelPropertyNaming` (project policy). Rules
deliberately kept ON despite pre-existing debt: `LongMethod` (80),
`CyclomaticComplexMethod` (20), `LongParameterList` (12), `TooManyFunctions`
(25), `MagicNumber`, `ReturnCount` (4), `TooGenericExceptionCaught` — see the
per-rule counts in the commit that introduced the baselines.

Caveat: detekt's baseline id for `MaxLineLength` is `MaxLineLength:<file>.kt$`
with an empty signature, so a *new* over-long line added to an already-baselined
file is not reported. `MagicNumber`/`LongMethod`/etc. carry real signatures and
do catch new code.

## Android Lint

`abortOnError = true`, `checkDependencies = true` (so `:shared` is linted as a
dependency too), baseline `androidApp/lint-baseline.xml` regenerated with
`./gradlew :androidApp:updateLintBaseline`. The orphaned `config/lint.xml` — a
file nothing referenced, so its `NewApi=error` policy never took effect — was
deleted; `NewApi` keeps its default `error` severity and `MissingPreview` is
disabled in the Gradle DSL instead.

`DylanMediaService` declares `android:permission="android.permission.MEDIA_CONTENT_CONTROL"`,
which cleared the `ExportedService` finding outright rather than baselining it.
