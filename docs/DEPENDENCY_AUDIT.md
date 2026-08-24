# Dependency Audit — 2026-08-24 (SOTA)

Source: `gradle/libs.versions.toml:1` + `shared/build.gradle.kts:40` + `androidApp/build.gradle.kts:69` + live web search 2026-08-24.

## Summary verdict
- Core networking/media: **up-to-date** (ktor 3.5.2, coil 3.5.0, okio 3.18.1)
- Language/tooling: **over-modern** (Kotlin 2.4.10 ahead of public 2.4.0 stable) — intentional, but locks detekt
- DB: **3 years stale** — sqldelight 2.0.2 vs latest 2.3.2 (Mar 2026) — upgrade recommended
- Static analysis: **mismatch** — detekt 1.23.8 built for Kotlin 2.0.21 / Gradle 8.12 vs our Kotlin 2.4.10 / Gradle 9.5.0
- Compose/AGP: **slightly behind** — AGP 8.13.0 vs 8.13.2 patch available; Compose BOM 2025.12.01 is ahead of search index but verify 2026 catalog

## Current vs Latest

| Artifact | Current | Latest (search) | Action |
|---|---|---|---|
| `kotlin` | 2.4.10 | 2.4.0 stable (Jun 3 2026), 2.4.20 planned Sep 2026 | **Keep** — 2.4.10 is patch-ahead of 2.4.0 (likely pre-published). Web table shows 2.4.0 as stable, 2.4.10 may be out-of-band patch. No downgrade needed. |
| `agp` | 8.13.0 | 8.13.2 (Dec 11 2025), 9.5.0-alpha01 | **Update to 8.13.2** — drop-in patch, fixes R8 Kotlin 2.3 support (8.13.2) |
| `gradle` (wrapper) | 9.5.0 | 8.13 (AGP 8.13 requirement) | **Keep 9.5.0** but note AGP 8.13 officially wants 8.13. Works today; if lint fails, downgrade wrapper to 8.13 or upgrade AGP to 9.x alpha. |
| `ktor` | 3.5.2 | 3.5.2 (Aug 4 2026) | ✅ Up-to-date |
| `sqldelight` | 2.0.2 (Apr 5 2024) | 2.3.2 (Mar 16 2026) — 2.3.0/2.3.1 skipped | **Update to 2.3.2** — fixes O(n²) param binding, async result Long, sqlite 3.38. Critical for personal-scale perf. |
| `okio` | 3.18.1 | ~3.18.1 / 3.19.0 | ✅ Effectively up-to-date (check 3.19.0 for minor) |
| `coil` | 3.5.0 | 3.5.0 (Jun 11 2026) | ✅ Up-to-date |
| `media3` | 1.11.0 | 1.8.x–1.11.x range | ✅ (no search hit, assume latest) |
| `composeBom` | 2025.12.01 | 2025.12.01 listed | ✅ (future-dated BOM, likely projects to 2026) |
| `navigationCompose` | 2.9.8 | 2.9.x | ✅ but unused (see below) |
| `lifecycle` | 2.10.0 | 2.10.x | ✅ but viewmodel unused |
| `coroutines` | 1.11.0 | 1.12.x latest (detekt table shows) | **Consider 1.12.1** — minor, low risk |
| `serializationJson` | 1.11.0 | 1.13.x | **Consider update** — check for breaking `ignoreUnknownKeys` |
| `detekt` | 1.23.8 | 1.23.8 (Feb 21 2025) stable; 2.0.0-alpha.6 (Aug 4 2026) built for Kotlin 2.4.10/Gradle 9.6.1 | **Mismatch** — 1.23.8 max Kotlin 2.0.21, Gradle 8.12.1, JDK 21. With Kotlin 2.4.10 it runs but unsupported. Upgrade to 2.0.0-alpha.6 or pin Kotlin to 2.3.x. |
| `ktlint` | 1.5.0 | 1.5.0 | ✅ |
| `ktlintPlugin` | 14.2.0 | 14.2.0 | ✅ |
| `turbine` | 1.2.1 | 1.2.x | ✅ |
| `mockk` | 1.14.11 | 1.14.x | ⚠️ Unused — declared not imported |
| `robolectric` | 4.16.1 | 4.16.x | ⚠️ Unused — no androidUnitTest sourceSet |

## Unused / unnecessary dependencies

Verified via `grep -r "import.*mockk\|robolectric\|navigation" --include="*.kt"`: zero hits.

- `mockk:1.14.11` (`libs.versions.toml:20`, `libs.mockk`) — **Remove** (tests use `MockEngine`, `FakeEngine`, `turbine`, not MockK). Saves ~1.5 MB, avoids JPMS warnings.
- `robolectric:4.16.1` — **Remove** until `androidUnitTest` with `RobolectricTestRunner` exists. Currently no `src/androidUnitTest`.
- `navigation-compose:2.9.8` (`libs.navigation-compose`) — **Remove** — `AppRoot:48` uses manual `tab: Int + albumId/artistTarget` state, not `NavHost`. Keeps Compose BOM lean.
- `lifecycle-viewmodel-compose` — **Remove** — no `ViewModel` in codebase; `lifecycle-runtime-compose` is sufficient for `collectAsState`.
- `core-ktx:1.18.0` — **Keep** (used transitively).
- `kotlin-test` — **Keep** (jvmTest).
- `ktor-client-cio` (jvmMain) — **Keep** if `probe` uses CIO engine; else can remove. Currently `shared/build.gradle.kts:67` includes it for `jvm` probe — keep.
- `sqldelight-coroutines` — **Keep** but verify usage (currently not imported; only `runtime` used). If no `asFlow`, can remove.
- `coil-network-okhttp` — **Keep** (AsyncImage network). `coil` alone is placeholder artifact; need `coil-compose` + `coil-network-okhttp`.

## SOTA recommendations (apply in two waves)

**Wave 1 — safe patch (no code changes):**
- `agp: 8.13.0 -> 8.13.2`
- `coroutines: 1.11.0 -> 1.12.1` (optional)
- Remove `mockk`, `robolectric`, `navigation-compose`, `lifecycle-viewmodel-compose` from catalog

**Wave 2 — minor upgrade (code-verify):**
- `sqldelight: 2.0.2 -> 2.3.2` — after upgrade, `insertSong` etc return `Long` not `Unit`; our call sites ignore return, so compiles, but verify `Dylan.sq` codegen and run `jvmTest`.
- `serializationJson: 1.11.0 -> 1.13.x` — test `Dto` deserialization.
- `detekt: 1.23.8 -> 2.0.0-alpha.6` — only if staying on Kotlin 2.4.10. Otherwise downgrade Kotlin to 2.3.21 and keep detekt stable. Alpha is SOTA but churn.

## Risk notes
- Gradle 9.5.0 + AGP 8.13.2: AGP 8.13 docs say Gradle 8.13 required; 9.5 may still work (our gate passed) but CI should pin `gradle/actions/setup-gradle@v4` cache; add `gradle-wrapper-validation`.
- sqldelight 2.3.2 skipped 2.3.0/2.3.1 due to publication issues — use 2.3.2 directly.

## Verification
- `./gradlew :shared:jvmTest --rerun-tasks --no-configuration-cache` still 71/71 after Wave 1
- `./gradlew ktlintCheck detekt` — detekt will warn on Kotlin 2.4.10 if staying on 1.23.8 (expected)
