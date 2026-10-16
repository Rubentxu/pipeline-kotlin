package dev.rubentxu.pipeline.v2.application.cli

import dev.rubentxu.pipeline.v2.application.support.AppBinSupport
import dev.rubentxu.pipeline.v2.application.support.CliRun
import dev.rubentxu.pipeline.v2.application.support.OwnedSubprocess
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Timeout

/**
 * R4 / WU-R4.0.0 — installed distribution executes a pipeline end-to-end.
 *
 * ## The bug this file was added to catch
 *
 * At `main@5c845ceb` (v0.60.0-rc6), the three example plugins
 * (`example-block-plugin-0.1.0.jar`, `example-uppercase-plugin-0.1.0.jar`,
 * `example-directive-plugin-0.1.0.jar`) shipped in the installDist
 * distribution with `apiRange=[0.57.0, 0.58.0)`. The host runtime is
 * `0.60.0`, so `PluginCompositionAdmitter` refused every one of them
 * for `IncompatibleApiRange`, `admitThenComposeOrExit` called
 * `System.exit(2)`, and every `pipelinek run` against that installDist
 * exited 2 BEFORE the run body even compiled. The events journal saw
 * only `RunCorrelated` + `CompilationStarted` + `CompilationFinished`,
 * and no step ran.
 *
 * The root cause was a Gradle build-cache invalidation gap: the
 * `compute*Release` tasks in the example plugins and the
 * `buildExampleBlockPlugin` / `buildExamplePlugin` /
 * `buildExternalDirectivePlugin` wrapper tasks in v2/build.gradle.kts
 * did not declare `-PsdkVersion` (and its outer `rootProject.version`
 * source) as an input. So when `version = "0.60.0"` was set, the
 * `apiRange` derived at the previous build (0.57.0) was reused. This
 * file pins the bug from the runtime side: a real `pipelinek run`
 * against the installDist MUST execute the steps and reach
 * `StepFinished` in the events journal.
 *
 * ## Why this is a single test
 *
 * It exercises the full chain the example plugins live in:
 * `pipelinek run` → admission → compose → compile → orchestrator
 * → step handler → event store. A regression in any of those that
 * causes the run to bail before the step executes surfaces here as
 * an empty (or near-empty) journal or a non-zero exit, with a
 * message that names the layer.
 *
 * ## What it does NOT cover
 *
 * - The example plugins' own step semantics (covered by their own
 *   characterisation tests in this package).
 * - C8's `deleteDir` / `cleanWs` semantics (covered by
 *   C8InstalledDistributionCanaryTest).
 * - Per-verb observability (covered by R4ObservabilityCliInstallDistCanaryTest).
 */
@Tag("installed-contract")
@Timeout(value = 5, unit = TimeUnit.MINUTES)
@DisplayName("R4 / WU-R4.0.0 — installed distribution runs a pipeline end-to-end")
class RunExecutesStepsInstalledUatTest {

    private val binary: File = AppBinSupport.discover().toFile()

    private data class CliResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    private data class PopulatedStore(
        val runId: String,
        val dbPath: Path,
        val controlRoot: Path,
    )

    /**
     * Run a 3-step pipeline fixture against the installDist binary. Returns the
     * populated store (runId + paths) so the test can drive `inspect` against
     * the same runId afterwards.
     *
     * Uses [OwnedSubprocess] per HF2 / HF3 discipline: a hung child is the
     * test's fault, not the binary's, and a 2-min deadline per call is the
     * agreed ceiling.
     */
    private fun populateFixture(root: Path): PopulatedStore {
        val script = root.resolve("canary.pipeline.kts")
        Files.writeString(
            script,
            """
            pipeline {
                stages {
                    stage("main") {
                        echo("hello from r4.0.0 canary")
                        sh("printf 'world\n'")
                        error("boom: intentional failure so the run exits non-zero")
                    }
                }
            }
            """.trimIndent(),
        )
        val dbPath = root.resolve("journal.db")
        val controlRoot = Files.createDirectories(root.resolve("control"))
        val outcome = OwnedSubprocess.run(
            command = listOf(
                binary.absolutePath, "run",
                "--db", dbPath.toString(),
                "--control-root", controlRoot.toString(),
            ) + script.toString(),
            timeout = Duration.ofMinutes(2),
            workingDirectory = root.toFile(),
        )
        val result = when (outcome) {
            is CliRun.Completed -> CliResult(outcome.exitCode, outcome.stdout, outcome.stderr)
            is CliRun.TimedOut -> error(
                "installed binary hung after 2 min; pid=${outcome.diagnostics.pid} " +
                    "descendants=${outcome.diagnostics.descendantPids}",
            )
            is CliRun.LaunchFailed -> error(
                "installed binary could not be launched: ${outcome.cause}",
            )
        }
        // The fixture is designed to fail (the error step). exit 0 means the
        // canary fixture itself is broken. exit 2 with the pre-fix binary was
        // the symptom of the run-execution defect; we want a non-zero exit
        // AND step events in the journal (checked in the test body).
        assertNotEquals(
            0, result.exitCode,
            "the fixture is designed to fail (the error step); exit 0 means the " +
                "canary fixture itself is broken. output: stdout=${result.stdout} stderr=${result.stderr}",
        )
        // The runId is on the launch envelope; both stdout and stderr are
        // possible surfaces (PK-1/ADR-PK1-IDENTITY moved it to stderr on
        // durable runs but the launch envelope is still printed on stdout
        // by the in-memory branch).
        val runId = RUN_ID_REGEX.find(result.stdout)?.groupValues?.get(1)
            ?: RUN_ID_REGEX.find(result.stderr)?.groupValues?.get(1)
            ?: error(
                "the launch envelope must carry a runId on stdout or stderr; " +
                    "stdout=${result.stdout} stderr=${result.stderr}",
            )
        return PopulatedStore(runId, dbPath, controlRoot)
    }

    private fun inspectJson(store: PopulatedStore): String {
        val outcome = OwnedSubprocess.run(
            command = listOf(
                binary.absolutePath, "inspect",
                store.runId, // positional first
                "--control-root", store.controlRoot.toString(),
                "--db", store.dbPath.toString(),
                "--format", "json",
            ),
            timeout = Duration.ofMinutes(1),
            workingDirectory = store.controlRoot.parent.toFile(),
        )
        val result = when (outcome) {
            is CliRun.Completed -> CliResult(outcome.exitCode, outcome.stdout, outcome.stderr)
            is CliRun.TimedOut -> error("inspect hung after 1 min; pid=${outcome.diagnostics.pid}")
            is CliRun.LaunchFailed -> error("inspect could not launch: ${outcome.cause}")
        }
        assertEquals(
            0, result.exitCode,
            "inspect must exit 0 against a populated store; got ${result.exitCode}; " +
                "stdout=${result.stdout} stderr=${result.stderr}",
        )
        return result.stdout
    }

    @Test
    fun `installed distribution runs a 3-step pipeline and emits Step events in the journal`(
        @TempDir parent: Path,
    ) {
        // Under the JUnit-owned temp dir rather than at the top of java.io.tmpdir. The old form
        // created the directory with no parent and relied on a `finally { deleteRecursively() }`,
        // which is a cleanup step that a killed run skips — leaving a directory per test behind
        // (HARNESS FIDELITY §4). Nesting makes the ownership structural.
        val root = Files.createDirectories(parent.resolve("r4-wu-r4-0-0-canary"))
        try {
            val store = populateFixture(root)
            val json = inspectJson(store)

            // The pre-fix run had only the 3 lifecycle events (RunCorrelated,
            // CompilationStarted, CompilationFinished) and ZERO step events.
            // Each of these assertions fails pre-fix; all three pass post-fix.
            assertTrue(
                json.contains("\"StepStarted\""),
                "the journal must contain StepStarted events; the pre-fix run produced none. " +
                    "Got: $json",
            )
            assertTrue(
                json.contains("\"StepFinished\""),
                "the journal must contain StepFinished events; the pre-fix run produced none. " +
                    "Got: $json",
            )
            assertTrue(
                json.contains("\"main/error-0\""),
                "the journal must contain the error step's records (proves the orchestrator " +
                    "reached the third step and recorded its failure outcome). Pre-fix: the run " +
                    "never reached the orchestrator. Got: $json",
            )
            // The error step records its failure as the typed StepFinished event
            // with outcome="failure" + the failure message in `message`, not as a
            // separate `StepFailed` event. Pinned by name so a future shape change
            // shows up in the assertion message, not as a silent regression.
            assertTrue(
                Regex("\"stage\"\\s*:\\s*\"main/error-0\"[^}]*\"outcome\"\\s*:\\s*\"failure\"").containsMatchIn(json),
                "the error step's StepFinished record must carry outcome=failure with the " +
                    "configured message. Got: $json",
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private companion object {
        // The `pipelinek.launch/1` envelope carries the runId:
        //   {"schema":"pipelinek.launch/1","runId":"<uuid>",...}
        val RUN_ID_REGEX: Regex = Regex("\"runId\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"")
    }
}
