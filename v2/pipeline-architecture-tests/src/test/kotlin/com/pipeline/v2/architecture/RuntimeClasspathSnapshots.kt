package com.pipeline.v2.architecture

import java.nio.file.Files
import java.nio.file.Path

object RuntimeClasspathSnapshots {
    // Module names (as Gradle knows them) and their corresponding snapshot file base names
    // For nested subprojects, Gradle uses the leaf name only in the file path
    private val moduleToSnapshotName = mapOf(
        "pipeline-domain" to "pipeline-domain",
        "pipeline-application" to "pipeline-application",
        "pipeline-scripting-api" to "pipeline-scripting-api",
        "pipeline-scripting-kotlin24" to "pipeline-scripting-kotlin24",
        "pipeline-testkit" to "pipeline-testkit",
        "pipeline-events" to "pipeline-events",
        "pipeline-step-sdk:api" to "api",
        "pipeline-step-sdk:processor" to "processor",
        "pipeline-step-sdk:runtime" to "runtime",
    )

    fun load(root: Path): Map<String, List<String>> {
        val result = mutableMapOf<String, List<String>>()
        for ((module, snapshotName) in moduleToSnapshotName) {
            // For nested subprojects like pipeline-step-sdk:api, the file is at pipeline-step-sdk/api/
            // Convert module name to path: pipeline-step-sdk:api -> pipeline-step-sdk/api
            val modulePath = module.replace(":", "/")
            val snapshotFile = root.resolve("$modulePath/build/fitness/${snapshotName}-runtime-classpath.txt")
            // Only load modules that have snapshot files (graceful handling for fixtures)
            if (Files.exists(snapshotFile)) {
                result[module] = Files.readAllLines(snapshotFile)
            }
        }
        return result
    }
}
