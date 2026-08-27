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
    config.setFrom(rootProject.files("config/detekt.yml"))
}

val keystoreProperties = Properties()
val keystorePropsFile = rootProject.file("keystore.properties")
if (keystorePropsFile.exists()) {
    keystorePropsFile.inputStream().use { keystoreProperties.load(it) }
}

// ── Semver: source of truth root/VERSION (e.g. 0.1.0) ─────────────────────
// Every commit auto-bumps versionCode; VERSION file bump = semantic release.
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
        abortOnError = false
        checkDependencies = false
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
    debugImplementation(libs.compose.ui.tooling)
}
