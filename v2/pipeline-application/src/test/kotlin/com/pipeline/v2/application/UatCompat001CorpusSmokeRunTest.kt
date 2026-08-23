package com.pipeline.v2.application

import com.pipeline.v2.application.support.AppBinSupport
import com.pipeline.v2.events.JsonEventLog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * UAT-COMPAT-001: Compatibility corpus smoke-run.
 * Verifies that all 6 corpus fixtures compile and run successfully.
 * Closes E2-06 + M2 exit criterion.
 */
class UatCompat001CorpusSmokeRunTest {

    private val compatibilityDir: Path by lazy {
        val userDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        if (userDir.fileName?.toString() == "pipeline-application") {
            userDir.resolve("v2").resolve("compatibility")
        } else {
            userDir.resolve("compatibility")
        }
    }

    private fun discoverFixtures(): List<Path> {
        val dir = compatibilityDir.toFile()
        if (!dir.exists()) {
            return emptyList()
        }
        return dir.listFiles()
            ?.filter { it.name.endsWith(".pipeline.kts") }
            ?.sortedBy { it.name }
            ?.map { it.toPath() }
            ?: emptyList()
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `corpus smoke-runs green and satisfies M2 exit criterion`() {
        AppBinSupport.discover()

        val fixtures = discoverFixtures()
        assertEquals(6, fixtures.size, "Corpus must have 6 fixtures")

        val appBin = AppBinSupport.discover()
        val failures = mutableListOf<String>()

        fixtures.forEach { fixture ->
            val pb = ProcessBuilder(appBin.toString(), "run", fixture.toString())
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)

            val process = pb.start()
            val exitCode = process.waitFor()
            val stdout = process.inputStream.bufferedReader().readText().trim()

            if (exitCode != 0) {
                val stderr = process.errorStream.bufferedReader().readText()
                failures.add("${fixture.fileName}: exit $exitCode, stderr: $stderr")
            } else {
                val events = JsonEventLog.decode(stdout)
                if (events.isEmpty()) {
                    failures.add("${fixture.fileName}: no events produced")
                }
            }
        }

        assertTrue(failures.isEmpty(), "Corpus must have zero failures: $failures")
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `each corpus fixture produces non-empty event stream`() {
        AppBinSupport.discover()

        val fixtures = discoverFixtures()
        val appBin = AppBinSupport.discover()

        fixtures.forEach { fixture ->
            val pb = ProcessBuilder(appBin.toString(), "run", fixture.toString())
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)

            val process = pb.start()
            process.waitFor()
            val stdout = process.inputStream.bufferedReader().readText().trim()

            val events = JsonEventLog.decode(stdout)
            assertTrue(events.isNotEmpty(), "${fixture.fileName} must produce events")
        }
    }
}
