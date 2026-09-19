package dev.rubentxu.pipeline.v2.application.support

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Shared `appBin` discovery for UAT harnesses.
 *
 * Extracted from UatDsl001JenkinsFamiliarityTest.kt to avoid duplication
 * across the corpus + compatibility UAT classes.
 */
object AppBinSupport {
    fun discover(): Path {
        val userDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        val moduleDir = if (userDir.fileName?.toString() == "pipeline-application") {
            userDir
        } else {
            userDir.resolve("v2").resolve("pipeline-application")
        }
        // WU-LPR-070: the distribution applicationName is "pipelinek". The legacy
        // "pipeline-application" install name is accepted for stale checkouts but the
        // canonical bin is install/pipelinek/bin/pipelinek.
        val installRoot = moduleDir.resolve("build").resolve("install")
        val bin = listOf("pipelinek", "pipeline-application")
            .map { installRoot.resolve(it).resolve("bin").resolve(it) }
            .firstOrNull { it.toFile().exists() }
        if (bin == null) {
            throw IllegalStateException(
                "Application binary not found under $installRoot. " +
                "Run ./gradlew :pipeline-application:installDist first."
            )
        }
        return bin
    }
}
