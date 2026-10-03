plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

ktlint {
    version = libs.versions.ktlint.get()
    filter {
        exclude("**/build/generated/**")
    }
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(rootProject.files("config/detekt.yml"))
    source.setFrom(
        "src/commonMain/kotlin",
        "src/jvmMain/kotlin",
        "src/androidMain/kotlin",
        "src/iosMain/kotlin",
        "src/commonTest/kotlin",
        "src/jvmTest/kotlin",
    )
    baseline = rootProject.file("config/detekt-baseline.xml")
}

kotlin {
    androidTarget {
        compilations.all {
            compilerOptions.configure { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 }
        }
    }
    jvm {
        compilations.all {
            compilerOptions.configure { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 }
        }
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "shared"
            isStatic = false
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
            implementation(libs.collections.immutable)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            implementation(libs.okio)
            implementation(libs.kotlinx.datetime)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqldelight.android)
            // Transitive `runtime` scope from android-driver; promoted to `implementation` because
            // DriverFactory names FrameworkSQLiteOpenHelperFactory to turn WAL on before open.
            implementation(libs.androidx.sqlite)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
            implementation(libs.turbine)
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.jvm)
            implementation(libs.ktor.client.cio)
        }
        jvmTest.dependencies {
            implementation(libs.ktor.client.mock)
            implementation(libs.sqldelight.jvm)
        }
    }
}

android {
    namespace = "dylan.shared"
    compileSdk = 34
    defaultConfig { minSdk = 34 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

sqldelight {
    databases {
        create("Dylan") {
            packageName.set("dylan.db")
            dialect("app.cash.sqldelight:sqlite-3-38-dialect:${libs.versions.sqldelight.get()}")
            // Where `./gradlew :shared:generateCommonMainDylanMigrations` writes the next .sqm.
            // It must be this directory: it is the only place the plugin both reads and writes
            // migrations from, and it is the one directory the plugin also globs for .sq files,
            // so a migration can never be committed somewhere the codegen silently ignores.
            migrationOutputDirectory.set(file("src/commonMain/sqldelight/migrations"))
            // verifyMigrations stays OFF on purpose: in 2.0.2 it needs a committed per-version
            // .db snapshot to migrate FROM, and with only the newest snapshot present the
            // verify task is green even when a migration drops an index (verified). A gate that
            // cannot fail is worse than no gate; the real gate is CacheMigrationTest, which
            // builds a genuine v0 database and opens it through the real DriverFactory.
        }
    }
}

val jvmCompilation = kotlin.targets["jvm"].compilations["main"]

tasks.register<JavaExec>("probeLocal") {
    group = "probe"
    description = "Live probe incl M0 gates on the real usage network (D21)"
    classpath = files(layout.buildDirectory.dir("classes/kotlin/jvm/main"), jvmCompilation.runtimeDependencyFiles)
    mainClass.set("dylan.probe.ProbeMainKt")
    args =
        buildList {
            add("local")
            if (project.hasProperty("probeFast")) add("--fast")
        }
    workingDir = rootDir
    dependsOn(jvmCompilation.compileTaskProvider)
}

tasks.register<JavaExec>("probeCi") {
    group = "probe"
    description = "Live-network structural probe (S1-S3) with M0 gating — nightly only, never presubmit"
    classpath = files(layout.buildDirectory.dir("classes/kotlin/jvm/main"), jvmCompilation.runtimeDependencyFiles)
    mainClass.set("dylan.probe.ProbeMainKt")
    args("ci")
    workingDir = rootDir
    dependsOn(jvmCompilation.compileTaskProvider)
}

val jvmTestCompilation = kotlin.targets["jvm"].compilations["test"]

/**
 * Generate `dylan/di/AppVersion.kt` from the root VERSION file.
 *
 * AppContainer logs the shipped version on every boot, but it is a multiplatform module and cannot
 * read androidApp's BuildConfig. This used to be a hand-maintained literal with a "keep in sync"
 * comment, and it had drifted (VERSION 1.0.0 vs APP_VERSION 0.1.0), so the boot line under-reported
 * what the user had installed. Generating it makes the drift unrepresentable, and keeps one source of
 * truth: the same VERSION that androidApp's versionName is derived from.
 */
val appVersionSrcDir = layout.buildDirectory.dir("generated/dylan/di")
val generateAppVersion by tasks.registering {
    val versionFile = rootProject.layout.projectDirectory.file("VERSION")
    val outFile = appVersionSrcDir.map { it.file("AppVersion.kt") }
    val header =
        """
        // GENERATED BY THE BUILD - DO NOT EDIT.
        //
        // Written by shared/build.gradle.kts from the root VERSION file, which is the single source of
        // truth for the shipped version. This exists because AppContainer needs the version for its
        // session-stamped boot log line, and shared is a multiplatform module that cannot read
        // androidApp's BuildConfig.
        //
        // It used to be a hand-maintained literal (`const val APP_VERSION = "0.1.0"`) carrying a "keep in
        // sync with root VERSION" comment. That had drifted to VERSION=1.0.0 vs APP_VERSION=0.1.0, so every
        // boot line under-reported the installed version. A comment cannot enforce a sync; generation can.

        package dylan.di

        /** Shipped app version, e.g. `1.0.0`. Injected at build time from the root `VERSION` file. */
        const val APP_VERSION: String =
        """.trimIndent()
    inputs.file(versionFile)
    outputs.file(outFile)
    doLast {
        val version =
            versionFile.asFile
                .readText()
                .trim()
                .ifEmpty { "0.0.0-unset" }
        val target = outFile.get().asFile
        target.parentFile.mkdirs()
        target.writeText("$header\"$version\"\n")
    }
}

kotlin.sourceSets.named("commonMain") {
    kotlin.srcDir(appVersionSrcDir)
}

// Every task that READS the generated source must depend on the task that WRITES it, and the
// dependency has to be declared for all of them — not just the Kotlin compile tasks. ktlint and
// detekt walk commonMain too, so without this Gradle reports an implicit-dependency validation
// error ("uses this output of task ':shared:generateAppVersion' without declaring a dependency")
// and fails the moment both are in the same invocation.
//
// Declared as an input rather than only `dependsOn` so the task also re-runs when VERSION changes,
// which a bare dependsOn would not do on its own. The input is the generated FILE, not its parent
// directory: Gradle rejects `inputs.file()` pointed at a directory.
tasks.matching { it.name.startsWith("runKtlint") || it.name.endsWith("ktlintSourceSetCheck") }.configureEach {
    dependsOn(generateAppVersion)
    inputs.file(generateAppVersion.map { it.outputs.files.singleFile }).withPropertyName("generatedAppVersion").optional()
}
tasks.matching { it.name.contains("detekt") }.configureEach {
    dependsOn(generateAppVersion)
    inputs.file(generateAppVersion.map { it.outputs.files.singleFile }).withPropertyName("generatedAppVersion").optional()
}
tasks.matching { it.name.startsWith("compile") && it.name.contains("Kotlin") }.configureEach {
    dependsOn(generateAppVersion)
}

tasks.register<JavaExec>("contractDrift") {
    group = "probe"
    description = "Live-vs-fixture contract drift gate over 8 endpoints — nightly/manual only, never presubmit"
    classpath =
        files(
            layout.buildDirectory.dir("classes/kotlin/jvm/main"),
            layout.buildDirectory.dir("classes/kotlin/jvm/test"),
            jvmTestCompilation.runtimeDependencyFiles,
        )
    mainClass.set("dylan.tools.ContractDriftKt")
    systemProperty("dylan.fixturesDir", rootProject.file("fixtures").absolutePath)
    workingDir = rootDir
    dependsOn(jvmTestCompilation.compileTaskProvider)
}
