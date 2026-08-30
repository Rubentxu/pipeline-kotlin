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
 * Verifies that all 6 corpus fixtures compile and run successfully.
 * Closes E2-06 + M2 exit criterion.
 */
@Timeout(120)
class UatCompat001CorpusSmokeRunTest {

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
        assertEquals(13, fixtures.size, "Corpus must have 13 valid fixtures (07-writeFile-readFile moved to UAT-owned test resources; 99-broken-compilation moved to broken resources)")

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
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    fun `each corpus fixture produces non-empty event stream`() {
        AppBinSupport.discover()

        val fixtures = discoverFixtures()
        assertEquals(13, fixtures.size, "Corpus must have 13 valid fixtures (07-writeFile-readFile moved to UAT-owned test resources; 99-broken-compilation moved to broken resources)")
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
