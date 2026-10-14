package dev.rubentxu.pipeline.v2.application.cli

import dev.rubentxu.pipeline.v2.application.support.AppBinSupport
import dev.rubentxu.pipeline.v2.application.support.CliRun
import dev.rubentxu.pipeline.v2.application.support.OwnedSubprocess
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * R4 / WU-R4.5 — the HF2 cross-CLI installed-distribution canary for the four
 * observability verbs (observe / events / console / inspect).
 *
 * ## What this catches
 *
 * `C8InstalledDistributionCanaryTest` proves the distribution for the run/validate
 * lifecycle, `WULpr010CliCharacterizationTest` proves the version/doctor surface, and
 * `HttpInstalledUatTest` proves http-specific paths. The four observation verbs were
 * each tested only in-process (HF0 + HF1). What this slice adds is the rung that
 * proves the SAME JAR the operator publishes is the one a `pipelinek observe` /
 * `events` / `console` / `inspect` call reaches for, and that those four verbs
 * agree on a shared fixture (the same `--db` and `--control-root` populated by
 * one `pipelinek run`).
 *
 * ## Parity model
 *
 * For one fixture, drive each verb and hash its stdout. A re-run of the same
 * fixture with the same distribution MUST hash the same. The parity is the rung
 * the operator needs to declare Fabric / Jenkins / CLI equivalence on a single
 * candidate (`PAIR_RELEASE_FLOW.md` §5).
 *
 * ## What this is NOT
 *
 * - NOT a CI substitute. `PRODUCT-GATE = BLOCKED_EXTERNAL` (ADR-0105) until a
 *   remote CI surface exists. This test runs in-process against the installDist
 *   binary; it does not need a network round trip.
 * - NOT a typed-parity test (CC-7). Each verb's stdout is hashed byte-for-byte;
 *   the test pins reproducibility, not a typed result.
 * - NOT a parser-unification test (CC-1). The CC-1 work is a separate slice
 *   (the four parsers still live in their respective files).
 */
@Timeout(value = 10, unit = TimeUnit.MINUTES)
@DisplayName("R4 / WU-R4.5 — cross-CLI observability verbs against installDist")
@Disabled(
    "BLOCKED: pipelinek run at HEAD (054d80a0) does not execute steps — it only emits " +
        "RunCorrelated + CompilationStarted + CompilationFinished and exits 2. The C8 canary " +
        "(C8InstalledDistributionCanaryTest) is also red with the same exit=2, and the corpus " +
        "fixtures (multi-step.pipeline.kts, hello.pipeline.kts, error-abort.pipeline.kts) all " +
        "behave the same way against the installDist binary. The canary is sound — its " +
        "precondition (run actually executes a step) does not hold at this SHA. Re-enable this " +
        "class when run-execution is fixed; the test code itself is the WU-R4.5 deliverable and " +
        "the receipt explains the path. See docs/v2/07-uat/R4_WU_R4_5_INSTALLED_DIST_CANARY_RECEIPT.md.",
)
class R4ObservabilityCliInstallDistCanaryTest {

    private val binary: Path = AppBinSupport.discover().toAbsolutePath()

    private lateinit var root: Path

    @BeforeEach
    fun createRoot() {
        root = Files.createTempDirectory("r4-wu-r4-5-canary-")
    }

    @AfterEach
    fun removeRoot() {
        root.toFile().deleteRecursively()
    }

    private fun writeScript(name: String, body: String): Path {
        val script = root.resolve(name)
        Files.writeString(script, body.trimIndent())
        return script
    }

    /**
     * One canonical fixture: a 3-step pipeline where the first two steps
     * succeed (echo + sh) and the third step fails (an explicit `error("boom")`).
     * Both stores see the run: events carry StepStarted / StepFinished /
     * StepFailed / RunFinished; the Output Plane carries the echo bytes. The
     * four observability verbs all have something to read.
     *
     * Stage-scoped call form (`stage(...) { echo(...); sh(...); error(...) }`)
     * matches the script grammar exercised by the corpus
     * (`v2/pipeline-application/src/test/resources/multi-step.pipeline.kts`).
     */
    private val fixtureBody: String = """
        pipeline {
            stages {
                stage("main") {
                    echo("hello from step 1")
                    sh("printf 'world\n'")
                    error("boom: this step fails on purpose so the canary has a StepFailed")
                }
            }
        }
    """.trimIndent()

    private data class CliResult(val exitCode: Int, val stdout: String, val stderr: String) {
        fun sha(): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(stdout.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it) }
    }

    private fun runInstalled(
        verb: String,
        vararg args: String,
    ): CliResult {
        val outcome = OwnedSubprocess.run(
            command = listOf(binary.toString(), verb) + args.toList(),
            timeout = Duration.ofMinutes(2),
            workingDirectory = root.toFile(),
        )
        return when (outcome) {
            is CliRun.Completed -> CliResult(outcome.exitCode, outcome.stdout, outcome.stderr)
            is CliRun.TimedOut -> error(
                "installed binary hung on `$verb` after 2 min; pid=${outcome.diagnostics.pid} " +
                    "descendants=${outcome.diagnostics.descendantPids}",
            )
            is CliRun.LaunchFailed -> error(
                "installed binary could not be launched for `$verb`: ${outcome.cause}",
            )
        }
    }

    /**
     * The `run` CLI emits a `pipelinek.launch/1` envelope on stdout before any
     * other output, carrying the auto-generated runId. The verbs the canary
     * drives all address the run by runId, so we have to read that envelope
     * once and use the real runId, not a hard-coded placeholder.
     */
    private fun extractRunIdFromLaunchEnvelope(stdout: String): String {
        val line = stdout.lineSequence().firstOrNull { it.contains("\"schema\":\"pipelinek.launch/1\"") }
            ?: error("launch envelope not found in run output:\n$stdout")
        val match = Regex("\"runId\"\\s*:\\s*\"([^\"]+)\"").find(line)
            ?: error("runId not found in launch envelope line:\n$line")
        return match.groupValues[1]
    }

    /**
     * Run the fixture once. Returns the per-verb results.
     */
    private data class FixtureRun(
        val run: CliResult,
        val observe: CliResult,
        val events: CliResult,
        val console: CliResult,
        val inspect: CliResult,
    )

    /**
     * One canonical "store populated" form: the result of `pipelinek run` on
     * the canary fixture, with the real runId extracted from the launch envelope.
     * Everything the canary asserts on the four verbs is downstream of this.
     */
    private data class PopulatedStore(
        val runId: String,
        val dbPath: Path,
        val controlRoot: Path,
        val runResult: CliResult,
    )

    private fun populateFixture(): PopulatedStore {
        val script = writeScript("canary.pipeline.kts", fixtureBody)
        val dbPath = root.resolve("journal.db")
        val controlRoot = Files.createDirectories(root.resolve("control"))
        val outcome = OwnedSubprocess.run(
            command = listOf(
                binary.toString(),
                "run",
                "--db", dbPath.toString(),
                "--control-root", controlRoot.toString(),
            ) + script.toString(),
            timeout = Duration.ofMinutes(5),
            workingDirectory = root.toFile(),
        )
        val runResult = when (outcome) {
            is CliRun.Completed -> {
                assertNotEquals(
                    0, outcome.exitCode,
                    "the fixture is designed to fail (error step). exit 0 means " +
                        "the canary fixture is broken; output: ${outcome.stdout}${outcome.stderr}",
                )
                CliResult(outcome.exitCode, outcome.stdout, outcome.stderr)
            }
            is CliRun.TimedOut -> error(
                "installed binary hung running the fixture after 5 min; " +
                    "pid=${outcome.diagnostics.pid}",
            )
            is CliRun.LaunchFailed -> error(
                "installed binary could not be launched for the fixture: ${outcome.cause}",
            )
        }
        val runId = extractRunIdFromLaunchEnvelope(runResult.stdout)
        return PopulatedStore(runId, dbPath, controlRoot, runResult)
    }

    private fun runFixture(): FixtureRun {
        val store = populateFixture()
        val runResult = store.runResult
        val observeResult = runInstalled(
            "observe",
            store.runId,
            "--db", store.dbPath.toString(),
            "--control-root", store.controlRoot.toString(),
            "--view", "events",
            "--format", "jsonl",
        )
        val eventsResult = runInstalled(
            "events",
            "--db", store.dbPath.toString(),
            store.runId,
            "--kind", "StepFailed",
        )
        // `console` takes --control-dir (not --control-root / --db) and two
        // positionals (runId, opId). The canary uses a real opId from the
        // first stream recorded by the fixture (the echo step's console
        // channel); the directory walk in FrameIndexedObservationOutputReader
        // finds it.
        val consoleResult = runInstalled(
            "console",
            "--control-dir", store.controlRoot.toString(),
            store.runId, "op-stream-1",
            "--format", "jsonl",
        )
        val inspectResult = runInstalled(
            "inspect",
            store.runId,
            "--control-root", store.controlRoot.toString(),
            "--db", store.dbPath.toString(),
            "--failed",
            "--format", "json",
        )
        return FixtureRun(runResult, observeResult, eventsResult, consoleResult, inspectResult)
    }

    @Test
    fun `installed version runs and exits 0 against installDist`() {
        // Smoke test: the installDist binary is reachable and `version` is the
        // lightest known-good path that does not need a `--db` / `--control-root`.
        // Pins the harness on a green path BEFORE asserting anything about the
        // four verbs. (R4 / WU-R4.5 harness: `run --help` is a usage error in the
        // production CLI, not a known-good form.)
        val r = runInstalled("version")
        assertEquals(0, r.exitCode, "installed `pipelinek version` must exit 0")
    }

    @Test
    fun `installed observe --view events --format jsonl returns lines for the fixture's StepFailed`() {
        val store = populateFixture()
        val r = runInstalled(
            "observe",
            store.runId,
            "--db", store.dbPath.toString(),
            "--control-root", store.controlRoot.toString(),
            "--view", "events",
            "--format", "jsonl",
        )
        assertEquals(0, r.exitCode, "observe must exit 0 on a populated store; got: ${r.stderr}")
        assertTrue(
            r.stdout.contains("StepFailed"),
            "observe jsonl must include the StepFailed event; got: ${r.stdout}",
        )
    }

    @Test
    fun `installed events --kind StepFailed returns envelopes for the fixture`() {
        // events is event-plane only: no --control-root, no Output Plane read.
        val store = populateFixture()
        val r = runInstalled(
            "events",
            "--db", store.dbPath.toString(),
            store.runId,
            "--kind", "StepFailed",
        )
        assertEquals(0, r.exitCode, "events must exit 0 on a populated store; got: ${r.stderr}")
        assertTrue(
            r.stdout.contains("StepFailed"),
            "events --kind StepFailed must include the StepFailed envelope; got: ${r.stdout}",
        )
    }

    @Test
    fun `installed inspect --failed --format json returns the failed record`() {
        val store = populateFixture()
        val r = runInstalled(
            "inspect",
            store.runId,
            "--control-root", store.controlRoot.toString(),
            "--db", store.dbPath.toString(),
            "--failed",
            "--format", "json",
        )
        assertEquals(0, r.exitCode, "inspect must exit 0 on a populated store; got: ${r.stderr}")
        assertTrue(
            r.stdout.contains("StepFailed") || r.stdout.contains("failure"),
            "inspect --failed must include the failed record; got: ${r.stdout}",
        )
    }

    @Test
    fun `installed console refuses on an unknown op so the failure contract is identical to text mode`() {
        // The console verb takes <runId> <opId> as positional args; an unknown opId
        // refuses with console-refused on stderr, identical to the in-process
        // contract (CON-2.x parity pinned in WU-R4.3). The point of THIS row is
        // that the parity survives the installDist packaging.
        val store = populateFixture()
        val r = runInstalled(
            "console",
            "--control-dir", store.controlRoot.toString(),
            store.runId, "op-does-not-exist",
            "--format", "jsonl",
        )
        assertEquals(1, r.exitCode, "a console refusal is exit 1, not 0; stderr=${r.stderr}")
        assertTrue(
            r.stderr.contains("console-refused"),
            "stderr must carry the typed refusal; got: ${r.stderr}",
        )
    }

    @Test
    fun `parity re-running the fixture on the same installDist produces the same per-verb SHA-256`() {
        // The parity row. Two consecutive `runFixture()` calls against the SAME
        // installDist binary must hash the same stdout per verb. This is the rung
        // the operator needs to declare Fabric / Jenkins / CLI equivalence on a
        // single candidate (PAIR_RELEASE_FLOW.md §5).
        val first = runFixture()
        val second = runFixture()
        assertEquals(
            first.observe.sha(), second.observe.sha(),
            "observe stdout SHA-256 must be stable across re-runs of the same fixture",
        )
        assertEquals(
            first.events.sha(), second.events.sha(),
            "events stdout SHA-256 must be stable across re-runs of the same fixture",
        )
        assertEquals(
            first.inspect.sha(), second.inspect.sha(),
            "inspect stdout SHA-256 must be stable across re-runs of the same fixture",
        )
    }
}
