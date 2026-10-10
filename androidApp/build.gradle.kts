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

// ── Semver: source of truth root/VERSION (e.g. 0.1.0) ─────────────────────
// version.yml auto-bumps VERSION on every main push (conventional commits:
// feat: → minor, "!:"/BREAKING CHANGE → major, else patch) and tags v<ver>.
// versionCode advances every commit (count-based); versionName is unique per
// commit, so each main push yields distinct, installable versioned artifacts.
// versionCode = MAJOR*1_000_000 + MINOR*10_000 + PATCH*100 + (commitCount %100)  (Play limit 2.1B)
// versionName = 0.1.0 (tag) / 0.1.0+42 (CI) / 0.1.0-dev.42+abc123 (local)
fun semver(): Triple<String, Int, String> {
    val vf = rootProject.file("VERSION")
    val base = if (vf.exists()) vf.readText().trim() else "0.1.0"
    val (maj, min, pat) = base.split(".").map { it.toInt() }
    val cnt =
        runCatching {
            providers
                .exec { commandLine("git", "rev-list", "--count", "HEAD") }
                .standardOutput.asText
                .get()
                .trim()
                .toInt()
        }.getOrDefault(0)
    val hash =
        runCatching {
            providers
                .exec { commandLine("git", "rev-parse", "--short", "HEAD") }
                .standardOutput.asText
                .get()
                .trim()
        }.getOrDefault("dev")
    val isCI = providers.environmentVariable("CI").isPresent || providers.environmentVariable("GITHUB_ACTIONS").isPresent
    val code = maj * 1_000_000 + min * 10_000 + pat * 100 + (cnt % 100)
    val name =
        when {
            isCI && cnt == 0 -> base
            isCI -> "$base+$cnt"
            else -> "$base-dev.$cnt+$hash"
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
