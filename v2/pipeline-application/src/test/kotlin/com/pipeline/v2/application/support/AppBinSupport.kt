package com.pipeline.v2.application.support

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
        val bin = moduleDir
            .resolve("build")
            .resolve("install")
            .resolve("pipeline-application")
            .resolve("bin")
            .resolve("pipeline-application")
        if (!bin.toFile().exists()) {
            throw IllegalStateException(
                "Application binary not found at $bin. " +
                "Run ./gradlew :pipeline-application:installDist first."
            )
        }
        return bin
    }
}
