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
    config.setFrom(rootProject.files("config/detekt.yml"))
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
    description = "Structural-only probe for hosted nightly (D21)"
    classpath = files(layout.buildDirectory.dir("classes/kotlin/jvm/main"), jvmCompilation.runtimeDependencyFiles)
    mainClass.set("dylan.probe.ProbeMainKt")
    args("ci")
    workingDir = rootDir
    dependsOn(jvmCompilation.compileTaskProvider)
}

val jvmTestCompilation = kotlin.targets["jvm"].compilations["test"]

tasks.register<JavaExec>("contractDrift") {
    group = "probe"
    description = "Live-vs-fixture contract drift report over search/album/topSearches/trending"
    classpath =
        files(
            layout.buildDirectory.dir("classes/kotlin/jvm/main"),
            layout.buildDirectory.dir("classes/kotlin/jvm/test"),
            jvmTestCompilation.runtimeDependencyFiles,
        )
    mainClass.set("dylan.tools.ContractDriftKt")
    workingDir = rootDir
    dependsOn(jvmTestCompilation.compileTaskProvider)
}
