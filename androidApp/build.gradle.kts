import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

ktlint {
    version = libs.versions.ktlint.get()
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(rootProject.files("config/detekt.yml"))
    source.setFrom(
        "src/main/kotlin",
        "src/main/java",
        "src/commonMain/kotlin",
        "src/jvmMain/kotlin",
        "src/androidMain/kotlin",
        "src/test/kotlin",
        "src/commonTest/kotlin",
    )
    // No baseline, for the same reason as `shared`: see the note there. The
    // module previously carried 113 suppressed findings, so the gate had been
    // reporting on almost nothing in the UI.
}

val keystoreProperties = Properties()
val keystorePropsFile = rootProject.file("keystore.properties")
if (keystorePropsFile.exists()) {
    keystorePropsFile.inputStream().use { keystoreProperties.load(it) }
}

// ── Semver: source of truth root/VERSION (e.g. 1.0.0) ──────────────────────
// Release model (FAANG-style channels):
//   main                    -> canary   versionName 1.0.0-canary.<n>  (GitHub prerelease)
//   release/x.y             -> stable   versionName 1.0.0            (GitHub release, latest)
//   local dev tree          ->          versionName 1.0.0+<n>.<sha>
// VERSION is bumped once per stable cut (release.yml, workflow_dispatch only), never per
// commit. When a cut happens the bump is DERIVED by aggregating every conventional commit
// since the previous tag (minor on `feat:`, patch on `fix:`/`chore:`/`docs:`, else patch);
// a major bump is a deliberate act, since it needs a `!:`/`BREAKING CHANGE` commit to reach
// the release branch. main never has its VERSION rewritten — the canary is identified by the
// `-canary.<n>` suffix on the tag, not by a bumped base version.
//
// versionCode = MAJOR*10_000_000 + MINOR*100_000 + PATCH*1_000 + (BUILD % 1_000)
// (Play limit 2.1B, so MAJOR ≤ 210, MINOR/PATCH ≤ 99, BUILD ≤ 999 — plenty of room)
//
// BUILD must NOT come from `git rev-list --count HEAD`: that is a count of reachable
// commits, so a history squash silently resets it (this repo was squashed to a single
// commit and versionCode regressed ~24 -> 1, which would make an overwrite-install roll
// backwards). GITHUB_RUN_NUMBER is monotonic per repo across any rewrite, so it is
// preferred when present.
//
// The BUILD field is capped at 1000, not unbounded: packing an unbounded run number
// into the same integer as the semver fields would let a large run number bleed into
// PATCH. 1000 canaries per release line is far more headroom than this repo needs, and
// a stricter cap would wrap backwards every 100 pushes (the old formula's bug).
fun semver(): Triple<String, Int, String> {
    val vf = rootProject.file("VERSION")
    val base = if (vf.exists()) vf.readText().trim() else "0.1.0"
    val (maj, min, pat) = base.split(".").map { it.toInt() }
    val hash =
        runCatching {
            providers
                .exec { commandLine("git", "rev-parse", "--short", "HEAD") }
                .standardOutput.asText
                .get()
                .trim()
        }.getOrDefault("dev")
    val isCI = providers.environmentVariable("CI").isPresent || providers.environmentVariable("GITHUB_ACTIONS").isPresent

    // BUILD: monotonic across history rewrites. GITHUB_RUN_NUMBER wins; commit count is the
    // local fallback. See the comment above for why the count alone is not safe.
    val buildNum =
        providers
            .environmentVariable("GITHUB_RUN_NUMBER")
            .orNull
            ?.trim()
            ?.toIntOrNull()
            ?: runCatching {
                providers
                    .exec { commandLine("git", "rev-list", "--count", "HEAD") }
                    .standardOutput.asText
                    .get()
                    .trim()
                    .toInt()
            }.getOrDefault(0)

    // Channel, from the ref being built rather than from a convention anyone can forget:
    // a tag push is a stable release, main is the canary train, release/* is stabilized.
    val refType = providers.environmentVariable("GITHUB_REF_TYPE").orNull?.trim()
    val refName = providers.environmentVariable("GITHUB_REF_NAME").orNull?.trim()
    val code = maj * 10_000_000 + min * 100_000 + pat * 1_000 + (buildNum % 1_000)
    val name =
        when {
            refType == "tag" -> base
            !isCI -> "$base+$buildNum.$hash"
            refName == "main" -> "$base-canary.$buildNum"
            refName != null && refName.startsWith("release/") -> base
            buildNum == 0 -> base
            else -> "$base+$buildNum"
        }
    return Triple(base, code, name)
}
val (dylanBase, dylanCode, dylanName) = semver()
extra["dylanBaseVersion"] = dylanBase
extra["dylanVersionCode"] = dylanCode
extra["dylanVersionName"] = dylanName

android {
    namespace = "dylan.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.dylan.player"
        minSdk = 34
        targetSdk = 36
        versionCode = dylanCode
        versionName = dylanName
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = true
        checkDependencies = true
        // Compose screens legitimately omit @Preview previews; everything else
        // (NewApi included) keeps its default severity and can fail the build.
        disable += "MissingPreview"
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.serialization.json)
    implementation(libs.collections.immutable)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coil)
    implementation(libs.coil.network)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    // Media3-documented resumption pattern: suspend resume-table bridged via
    // ResolvableFuture (no runBlocking on the session thread, bounded timeout).
    implementation(libs.concurrent.futures.ktx)
    debugImplementation(libs.compose.ui.tooling)
}
