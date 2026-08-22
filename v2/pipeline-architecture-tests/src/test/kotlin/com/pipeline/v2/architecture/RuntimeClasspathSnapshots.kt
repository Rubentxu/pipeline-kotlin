package com.pipeline.v2.architecture

import java.nio.file.Files
import java.nio.file.Path

object RuntimeClasspathSnapshots {
    fun load(root: Path): Map<String, List<String>> {
        val modules = listOf("pipeline-domain", "pipeline-application", "pipeline-scripting-api", "pipeline-testkit")
        val result = mutableMapOf<String, List<String>>()
        for (module in modules) {
            val snapshotFile = root.resolve("$module/build/fitness/${module}-runtime-classpath.txt")
            if (!Files.exists(snapshotFile)) {
                throw IllegalStateException(
                    "Missing runtime-classpath snapshot for module '$module': $snapshotFile does not exist. " +
                    "Verify that the runtimeClasspathCapture task ran for this module before the test."
                )
            }
            result[module] = Files.readAllLines(snapshotFile)
        }
        return result
    }
}
