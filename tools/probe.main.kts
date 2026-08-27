#!/usr/bin/env kotlin
val task = if (args.contains("ci")) "probeCi" else "probeLocal"
val extra = if (args.contains("--fast")) listOf("-PprobeFast=true") else emptyList()
val proc = ProcessBuilder("./gradlew", task, "--no-configuration-cache")
    .inheritIO()
    .start()
kotlin.system.exitProcess(proc.waitFor())
