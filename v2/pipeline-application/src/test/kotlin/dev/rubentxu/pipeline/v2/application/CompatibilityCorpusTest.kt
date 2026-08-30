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
 * Compatibility corpus smoke tests — ONE test method per fixture.
 *
 * Per-fixture granularity is deliberate (AGENTS.md test-efficiency rules):
 * every fixture is independently selectable via
 * `--tests 'CompatibilityCorpusTest.fixture11*'`, gets its own timing
 * attribution in the JUnit XML, and a change to one fixture no longer
 * forces re-running the other twelve.
 *
 * Fixtures are functional (exit code + parseable events), NOT
 * timing-sensitive — unlike the process-kill/resume UAT classes protected
 * by AGENTS.md rule 11. Any future parallelization must be a measured,
 * explicit decision with a recorded baseline.
 */
@Timeout(value = 600, unit = TimeUnit.SECONDS)
class CompatibilityCorpusTest {

    private fun fixtureDir(): File =
        generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .map { File(it, "v2/compatibility") }
            .firstOrNull { it.isDirectory }
            ?: error("Cannot locate v2/compatibility/ via directory walk from ${System.getProperty("user.dir")}")

    private fun fixture(name: String): Path =
        fixtureDir().resolve(name).toPath().also { p ->
            assertTrue(p.toFile().isFile) { "Corpus fixture not found: $name" }
        }

    private fun runFixture(name: String) {
        val path = fixture(name)
        val appBin = AppBinSupport.discover()

        val pb = ProcessBuilder(appBin.toString(), "run", path.toString())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)

        val process = pb.start()
        val exitCode = process.waitFor()
        val stdout = process.inputStream.bufferedReader().readText().trim()

        assertEquals(0, exitCode) { "Fixture $name exited with code $exitCode. stderr: ${process.errorStream.bufferedReader().readText()}" }
        assertTrue(stdout.startsWith("[")) { "Fixture $name stdout must start with '['" }
        assertTrue(stdout.endsWith("]")) { "Fixture $name stdout must end with ']'" }

        val events = JsonEventLog.decode(stdout)
        assertTrue(events.isNotEmpty()) { "Fixture $name produced no events" }
    }

    @Test fun fixture01Basic() = runFixture("01-basic.pipeline.kts")

    @Test fun fixture02Environment() = runFixture("02-environment.pipeline.kts")

    @Test fun fixture03Stages() = runFixture("03-stages.pipeline.kts")

    @Test fun fixture04Sh() = runFixture("04-sh.pipeline.kts")

    @Test fun fixture05ScriptedIf() = runFixture("05-scripted-if.pipeline.kts")

    @Test fun fixture06Loop() = runFixture("06-loop.pipeline.kts")

    @Test fun fixture07WriteFileReadFile() = runFixture("07-writeFile-readFile.pipeline.kts")

    @Test fun fixture08WithEnv() = runFixture("08-withEnv-pipeline.pipeline.kts")

    @Test fun fixture09ArchiveArtefacts() = runFixture("09-archive-artefacts.pipeline.kts")

    @Test fun fixture10SmokeE2E() = runFixture("10-smoke-e2e.pipeline.kts")

    @Test fun fixture11WorkflowControl() = runFixture("11-workflow-control.pipeline.kts")

    @Test fun fixture12ErrorHandling() = runFixture("12-error-handling.pipeline.kts")

    @Test fun fixture13WorkspaceHelpers() = runFixture("13-workspace-helpers.pipeline.kts")

    @Test
    fun allCorpusFixturesAreDiscoverable() {
        val fixtures = fixtureDir().listFiles { f -> f.extension == "kts" }.orEmpty()
        assertEquals(13, fixtures.size)

        val names = fixtures.map { it.name }.toSet()
        assertTrue(names.contains("01-basic.pipeline.kts"))
        assertTrue(names.contains("02-environment.pipeline.kts"))
        assertTrue(names.contains("03-stages.pipeline.kts"))
        assertTrue(names.contains("04-sh.pipeline.kts"))
        assertTrue(names.contains("05-scripted-if.pipeline.kts"))
        assertTrue(names.contains("06-loop.pipeline.kts"))
        assertTrue(names.contains("07-writeFile-readFile.pipeline.kts"))
        assertTrue(names.contains("08-withEnv-pipeline.pipeline.kts"))
        assertTrue(names.contains("09-archive-artefacts.pipeline.kts"))
        assertTrue(names.contains("10-smoke-e2e.pipeline.kts"))
        assertTrue(names.contains("11-workflow-control.pipeline.kts"))
        assertTrue(names.contains("12-error-handling.pipeline.kts"))
        assertTrue(names.contains("13-workspace-helpers.pipeline.kts"))
    }
}
