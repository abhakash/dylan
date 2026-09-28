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
