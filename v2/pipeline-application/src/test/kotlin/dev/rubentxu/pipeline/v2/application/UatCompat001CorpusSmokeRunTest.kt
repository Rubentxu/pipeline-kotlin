package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.support.AppBinSupport
import dev.rubentxu.pipeline.v2.events.JsonEventLog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * UAT-COMPAT-001: Compatibility corpus smoke-run.
 * Verifies that all corpus fixtures compile and run successfully.
 * Closes E2-06 + M2 exit criterion.
 *
 * Each fixture exercises the public compatibility DSL and produces events.
 */
@Timeout(120)
class UatCompat001CorpusSmokeRunTest {

    // Fixtures that currently fail at runtime (exit non-zero).
    // After v0.33.1 (corpus-closure cycle): fixtures 02 (withEnv canonical), 13 (pwd/isUnix/timestamps
    // synchronous + canonical), 14 (withCredentials pluginId fix) now PASS. Fixture 10 exercises
    // archiveArtifacts with no real build output — it intentionally fails with exit 1 and emits
    // ArtifactArchiveFailed, which is correct canonical behaviour. Fixture 15 exercises the
    // canonical `error()` step which by design fails the run with exit 1. All other fixtures
    // exit 0.
    private val brokenFixtures = setOf(
        "10-smoke-e2e.pipeline.kts", // archiveArtifacts: no files matched → exit 1 (correct canonical behaviour)
        "15-error.pipeline.kts",    // `error("test")` step is a deliberate failure path
    )

    private fun discoverFixtures(): List<Path> {
        val userDir = File(System.getProperty("user.dir"))
        val candidate = generateSequence(userDir) { it.parentFile }
            .map { File(it, "v2/compatibility") }
            .firstOrNull { it.isDirectory }
            ?: error("Cannot locate v2/compatibility/ via directory walk from $userDir")
        return candidate.listFiles { f -> f.name.endsWith(".pipeline.kts") }?.toList().orEmpty().sortedBy { it.name }.map { it.toPath() }
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    fun `corpus smoke-runs green and satisfies M2 exit criterion`() {
        AppBinSupport.discover()

        val fixtures = discoverFixtures()
        assertEquals(17, fixtures.size, "Corpus must have 17 valid fixtures (07-writeFile-readFile moved to UAT-owned test resources; 99-broken-compilation moved to broken resources; v0.33.1 added fixtures 15-error, 16-sleep, 17-writeFile, 18-cleanWs and renamed 09-archive-artefacts → 09-sh-then-echo)")

        val appBin = AppBinSupport.discover()
        val failures = mutableListOf<String>()

        fixtures.forEach { fixture ->
            val pb = ProcessBuilder(appBin.toString(), "run", fixture.toString())
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)

            val process = pb.start()
            val exitCode = process.waitFor()
            val stdout = process.inputStream.bufferedReader().readText().trim()

            val isBroken = brokenFixtures.contains(fixture.fileName.toString())

            if (isBroken) {
                if (exitCode == 0) {
                    failures.add("${fixture.fileName}: expected non-zero exit but got 0")
                }
            } else {
                // Other fixtures must exit 0
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
        }

        assertTrue(failures.isEmpty(), "Corpus must have zero failures: $failures")
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    fun `each corpus fixture produces non-empty event stream`() {
        AppBinSupport.discover()

        val fixtures = discoverFixtures()
        assertEquals(17, fixtures.size, "Corpus must have 17 valid fixtures (07-writeFile-readFile moved to UAT-owned test resources; 99-broken-compilation moved to broken resources; v0.33.1 added fixtures 15-error, 16-sleep, 17-writeFile, 18-cleanWs and renamed 09-archive-artefacts → 09-sh-then-echo)")
        val appBin = AppBinSupport.discover()

        fixtures.forEach { fixture ->
            val pb = ProcessBuilder(appBin.toString(), "run", fixture.toString())
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)

            val process = pb.start()
            process.waitFor()
            val stdout = process.inputStream.bufferedReader().readText().trim()

            val isBroken = brokenFixtures.contains(fixture.fileName.toString())

            if (isBroken) {
            } else {
                val events = JsonEventLog.decode(stdout)
                assertTrue(events.isNotEmpty(), "${fixture.fileName} must produce events")
            }
        }
    }
}
