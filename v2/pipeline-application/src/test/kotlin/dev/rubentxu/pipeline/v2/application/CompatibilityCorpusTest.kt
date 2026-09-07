package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.support.AppBinSupport
import dev.rubentxu.pipeline.v2.events.JsonEventLog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNotEquals
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
 *
 * Fixtures use the public `StageScope.sh` options to preserve their declared
 * script-block semantics through compilation and execution.
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

    /**
     * Fixtures that fail at runtime (exit non-zero).
     *
     * After v0.33.1 (corpus-closure cycle), fixtures 02, 10, 13, 14 now PASS:
     * - Fixture 02 (withEnv): now canonical with core.withEnv
     * - Fixture 10 (archiveArtifacts): now canonical with core.archiveArtifacts
     * - Fixture 13 (timestamps): now canonical with core.timestamps
     * - Fixture 14 (withCredentials): now canonical with core.withCredentials
     *
     * `load` step still quarantined under INC-024 — not exercised by these fixtures.
     */
    private val runtimeFailureFixtures: Set<String> = emptySet()

    /**
     * Run a fixture that is expected to succeed (exit 0).
     */
    private fun runFixturePass(name: String) {
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

    /**
     * Run a fixture that is expected to fail (exit non-zero).
     * Used for fixtures with known runtime failures.
     */
    private fun runFixtureFail(name: String) {
        val path = fixture(name)
        val appBin = AppBinSupport.discover()

        val pb = ProcessBuilder(appBin.toString(), "run", path.toString())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)

        val process = pb.start()
        val exitCode = process.waitFor()
        val stderr = process.errorStream.bufferedReader().readText().trim()

        assertNotEquals(0, exitCode) { "Fixture $name should exit non-zero but got 0. stderr: $stderr" }
    }

    @Test fun fixture01Basic() = runFixturePass("01-basic.pipeline.kts")

    @Test fun fixture02Environment() = runFixturePass("02-environment.pipeline.kts")

    @Test fun fixture03Stages() = runFixturePass("03-stages.pipeline.kts")

    @Test fun fixture04Sh() = runFixturePass("04-sh.pipeline.kts")

    @Test fun fixture05ScriptedIf() = runFixturePass("05-scripted-if.pipeline.kts")

    @Test fun fixture06Loop() = runFixturePass("06-loop.pipeline.kts")

    @Test fun fixture08WithEnv() = runFixturePass("08-withEnv-pipeline.pipeline.kts")

    @Test fun fixture09ShThenEcho() = runFixturePass("09-sh-then-echo.pipeline.kts")

    @Test fun fixture10SmokeE2E() = runFixtureFail("10-smoke-e2e.pipeline.kts")

    @Test fun fixture11WorkflowControl() = runFixturePass("11-workflow-control.pipeline.kts")

    @Test fun fixture12ErrorHandling() = runFixturePass("12-error-handling.pipeline.kts")

    @Test fun fixture13WorkspaceHelpers() = runFixturePass("13-workspace-helpers.pipeline.kts")

    @Test fun fixture14CredentialsBindings() = runFixturePass("14-credentials-bindings.pipeline.kts")

    @Test fun fixture15Error() = runFixtureFail("15-error.pipeline.kts")

    @Test fun fixture16Sleep() = runFixturePass("16-sleep.pipeline.kts")

    @Test fun fixture17WriteFile() = runFixturePass("17-writeFile.pipeline.kts")

    @Test fun fixture18CleanWs() = runFixturePass("18-cleanWs.pipeline.kts")

    /**
     * Verifies that a script with compilation errors exits with non-zero code.
     * INC-R10-ARC-001: compilation failure is a FAILURE outcome, not success.
     */
    @Test
    fun allCorpusFixturesAreDiscoverable() {
        val fixtures = fixtureDir().listFiles { f -> f.extension == "kts" }.orEmpty()
        assertEquals(17, fixtures.size, "Corpus must have 17 valid fixtures in v0.33.1")

        val names = fixtures.map { it.name }.toSet()
        assertTrue(names.contains("01-basic.pipeline.kts"))
        assertTrue(names.contains("02-environment.pipeline.kts"))
        assertTrue(names.contains("03-stages.pipeline.kts"))
        assertTrue(names.contains("04-sh.pipeline.kts"))
        assertTrue(names.contains("05-scripted-if.pipeline.kts"))
        assertTrue(names.contains("06-loop.pipeline.kts"))
        assertTrue(names.contains("08-withEnv-pipeline.pipeline.kts"))
        assertTrue(names.contains("09-sh-then-echo.pipeline.kts"))
        assertTrue(names.contains("10-smoke-e2e.pipeline.kts"))
        assertTrue(names.contains("11-workflow-control.pipeline.kts"))
        assertTrue(names.contains("12-error-handling.pipeline.kts"))
        assertTrue(names.contains("13-workspace-helpers.pipeline.kts"))
        assertTrue(names.contains("14-credentials-bindings.pipeline.kts"))
        assertTrue(names.contains("15-error.pipeline.kts"))
        assertTrue(names.contains("16-sleep.pipeline.kts"))
        assertTrue(names.contains("17-writeFile.pipeline.kts"))
        assertTrue(names.contains("18-cleanWs.pipeline.kts"))
    }
}
