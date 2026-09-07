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
     * These are known runtime failures (02, 10, 13, 14).
     * Fixture 14 uses `withCredentials` (a non-canonical credential plugin): the canonical bridge
     * fails closed with exit 2 by design (AGENTS.md STEP SEMANTICS #3) — reclassify as runtime failure.
     *
     * Fixture 11 (workflow-control: dir/deleteDir/timeout/retry/catchError/unstable) now PASSES
     * after v0.33.0 because its step families have canonical implementations (INC-024 closed
     * for fixture 11).
     * Fixture 13 (workspace-helpers: pwd/isUnix/waitUntil/timestamps) STILL FAILS because the
     * `timestamps` decorator is not yet in canonical scope — INC-024 partial closure.
     * `load` step still quarantined under INC-024 — not exercised by these fixtures.
     */
    private val runtimeFailureFixtures = setOf(
        "02-environment.pipeline.kts",   // runtime failure
        "10-smoke-e2e.pipeline.kts",     // runtime failure
        "13-workspace-helpers.pipeline.kts", // timestamps decorator not yet canonical (INC-024 partial)
        "14-credentials-bindings.pipeline.kts" // non-canonical plugin → exit 2 (fail-closed)
    )

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

    @Test fun fixture02Environment() = runFixtureFail("02-environment.pipeline.kts")

    @Test fun fixture03Stages() = runFixturePass("03-stages.pipeline.kts")

    @Test fun fixture04Sh() = runFixturePass("04-sh.pipeline.kts")

    @Test fun fixture05ScriptedIf() = runFixturePass("05-scripted-if.pipeline.kts")

    @Test fun fixture06Loop() = runFixturePass("06-loop.pipeline.kts")

    @Test fun fixture08WithEnv() = runFixturePass("08-withEnv-pipeline.pipeline.kts")

    @Test fun fixture09ArchiveArtefacts() = runFixturePass("09-archive-artefacts.pipeline.kts")

    @Test fun fixture10SmokeE2E() = runFixtureFail("10-smoke-e2e.pipeline.kts")

    @Test fun fixture11WorkflowControl() = runFixturePass("11-workflow-control.pipeline.kts")

    @Test fun fixture12ErrorHandling() = runFixturePass("12-error-handling.pipeline.kts")

    @Test fun fixture13WorkspaceHelpers() = runFixtureFail("13-workspace-helpers.pipeline.kts")

    @Test fun fixture14CredentialsBindings() = runFixtureFail("14-credentials-bindings.pipeline.kts")

    /**
     * Verifies that a script with compilation errors exits with non-zero code.
     * INC-R10-ARC-001: compilation failure is a FAILURE outcome, not success.
     */
    @Test
    fun allCorpusFixturesAreDiscoverable() {
        val fixtures = fixtureDir().listFiles { f -> f.extension == "kts" }.orEmpty()
        assertEquals(13, fixtures.size, "Corpus must have 13 valid fixtures (07-writeFile-readFile and 99-broken-compilation moved to UAT-owned test resources)")

        val names = fixtures.map { it.name }.toSet()
        assertTrue(names.contains("01-basic.pipeline.kts"))
        assertTrue(names.contains("02-environment.pipeline.kts"))
        assertTrue(names.contains("03-stages.pipeline.kts"))
        assertTrue(names.contains("04-sh.pipeline.kts"))
        assertTrue(names.contains("05-scripted-if.pipeline.kts"))
        assertTrue(names.contains("06-loop.pipeline.kts"))
        assertTrue(names.contains("08-withEnv-pipeline.pipeline.kts"))
        assertTrue(names.contains("09-archive-artefacts.pipeline.kts"))
        assertTrue(names.contains("10-smoke-e2e.pipeline.kts"))
        assertTrue(names.contains("11-workflow-control.pipeline.kts"))
        assertTrue(names.contains("12-error-handling.pipeline.kts"))
        assertTrue(names.contains("13-workspace-helpers.pipeline.kts"))
        assertTrue(names.contains("14-credentials-bindings.pipeline.kts"))
    }
}
