package dev.rubentxu.pipeline.v2.application.cli

import dev.rubentxu.pipeline.v2.application.support.AppBinSupport
import dev.rubentxu.pipeline.v2.application.support.CliRun
import dev.rubentxu.pipeline.v2.application.support.OwnedSubprocess
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
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
 *
 * ## Status at v0.60.0-rc7
 *
 * Re-enabled at `6af6e45c` (R4 / WU-R4.0.0 merge). The pre-fix precondition
 * (pipelinek run does not execute steps) was the pre-fix example-plugin
 * `apiRange=[0.57.0, 0.58.0)` against the host 0.60.0 — the same root cause
 * that broke `C8InstalledDistributionCanaryTest`. With the apiRange fix
 * shipped, the run body executes, step events reach the journal, and the
 * canary populates its fixture and the four verbs read from it. The first
 * re-enable attempt read the runId from `[PipelineK] Run correlated: <uuid>`,
 * which is the **correlationId**, not the journal runId. That was the false-green
 * that let 3/6 rows pass while the 3 rows that actually query the journal saw
 * `records: []`. This slice reads runId from the `pipelinek.launch/1` JSON
 * envelope (the authoritative journal key) and asserts the text line carries
 * the same uuid as the envelope's `correlationId` field — a drift between the
 * two surfaces is exactly the kind of bug the canary exists to catch. Receipt
 * at `docs/v2/07-uat/R4_WU_R4_5_INSTALLED_DIST_CANARY_RECEIPT.md` documents
 * the original BLOCKED state and the re-enable evidence.
 */
@Tag("installed-contract")
@Timeout(value = 10, unit = TimeUnit.MINUTES)
@DisplayName("R4 / WU-R4.5 — cross-CLI observability verbs against installDist")
class R4ObservabilityCliInstallDistCanaryTest {

    private val binary: Path = AppBinSupport.discover().toAbsolutePath()

    private lateinit var root: Path

    /**
     * The canary's root is a child of a JUnit-owned `@TempDir`, not a top-level system temp.
     *
     * `Files.createTempDirectory(prefix)` with no parent lands in `java.io.tmpdir` and outlives this
     * class unless something deletes it — which is why this carried an explicit `deleteRecursively`
     * in `@AfterEach`, and why a killed run left a directory per test behind (HARNESS FIDELITY §4).
     * Nesting under the injected temp dir makes the ownership structural: JUnit removes the parent,
     * the child goes with it, and there is no cleanup step that can be skipped by a failing
     * assertion.
     */
    @BeforeEach
    fun createRoot(@TempDir parent: Path) {
        root = Files.createDirectories(parent.resolve("r4-wu-r4-5-canary"))
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
     * The runId is the journal key. It is the value the verbs all address by,
     * and the only way to read back what `pipelinek run` wrote.
     *
     * The current binary (post-`6af6e45c`) prints a `pipelinek.launch/1` JSON
     * envelope on **stderr** for every `pipelinek run`, in addition to the human
     * `[PipelineK] Run correlated: <correlationId>` line on **stdout**. The JSON
     * envelope is the authoritative runId surface — the correlationId is a
     * separate identity used to thread a single run across control-plane +
     * data-plane events, but it is NOT the journal key. Reading "Run
     * correlated" as a runId is the false-green that originally let the canary
     * pass: the parity and refusal rows never query the journal, so a wrong
     * identity there is silent. Reading from stdout only is a second false-green:
     * the JSON envelope is on stderr (a [OutputProtocol] design choice so the
     * runId is observable without polluting the data plane).
     *
     * The text line is kept as a secondary probe: the canary reads runId from
     * the JSON envelope (authoritative), and asserts the text line carries the
     * SAME uuid the envelope's `correlationId` field has. If the two diverge,
     * the canary fails loud — that drift is exactly the bug class
     * `extractRunIdFromLaunchEnvelope` was patched for.
     */
    private fun extractRunIdFromLaunchEnvelope(stdout: String, stderr: String): String {
        val jsonLine = stderr.lineSequence()
            .firstOrNull { it.contains("\"schema\":\"pipelinek.launch/1\"") }
            ?: error("pipelinek.launch/1 envelope not found in stderr:\nstderr=$stderr\nstdout=$stdout")
        val runId = Regex("\"runId\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"")
            .find(jsonLine)?.groupValues?.get(1)
            ?: error("runId not found in launch envelope line:\n$jsonLine")
        val correlationId = Regex("\"correlationId\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"")
            .find(jsonLine)?.groupValues?.get(1)
            ?: error("correlationId not found in launch envelope line:\n$jsonLine")
        // Cross-check the human-readable line. If a future CL changes which
        // identity the runtime line carries, this row fails loud rather than
        // silently letting the canary query a different journal key.
        val textLine = stdout.lineSequence()
            .firstOrNull { it.contains("Run correlated:") }
            ?: error("Run correlated: line not found in stdout:\nstdout=$stdout\nstderr=$stderr")
        val textUuid = Regex("Run correlated:\\s*([0-9a-fA-F-]{36})")
            .find(textLine)?.groupValues?.get(1)
            ?: error("uuid not parsed from `Run correlated:` line:\n$textLine")
        require(textUuid == correlationId) {
            "Run correlated: line carries '$textUuid' but the envelope's correlationId is " +
                "'$correlationId' — the human-readable line and the authoritative " +
                "envelope have drifted. The canary refuses to continue with a mismatched identity."
        }
        return runId
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
        val runId = extractRunIdFromLaunchEnvelope(runResult.stdout, runResult.stderr)
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
        // The parity row. The fixture populates the journal once; the verbs
        // are then called TWICE each against the SAME runId, and the SHA-256
        // of each verb's stdout must match. This is the rung the operator
        // needs to declare Fabric / Jenkins / CLI equivalence on a single
        // candidate (PAIR_RELEASE_FLOW.md §5).
        //
        // Why per-verb re-call, not re-run of the fixture: a `pipelinek run`
        // produces a fresh runId every invocation. Verb output is keyed by
        // runId, so two SEPARATE runs would compare verb output for two
        // different runIds, which is not a stable statement about the verb —
        // the SHA difference would be a property of the runId, not the verb.
        // The parity that matters for CI/release equivalence is "the same
        // verb, the same journal, the same runId, twice in a row, same
        // SHA-256" — that proves the verb is a pure function of the journal
        // (and the runId, which the test holds constant).
        val store = populateFixture()
        val observeFirst = runInstalled(
            "observe",
            store.runId,
            "--db", store.dbPath.toString(),
            "--control-root", store.controlRoot.toString(),
            "--view", "events",
            "--format", "jsonl",
        )
        val observeSecond = runInstalled(
            "observe",
            store.runId,
            "--db", store.dbPath.toString(),
            "--control-root", store.controlRoot.toString(),
            "--view", "events",
            "--format", "jsonl",
        )
        val eventsFirst = runInstalled(
            "events",
            "--db", store.dbPath.toString(),
            store.runId,
            "--kind", "StepFailed",
        )
        val eventsSecond = runInstalled(
            "events",
            "--db", store.dbPath.toString(),
            store.runId,
            "--kind", "StepFailed",
        )
        val inspectFirst = runInstalled(
            "inspect",
            store.runId,
            "--control-root", store.controlRoot.toString(),
            "--db", store.dbPath.toString(),
            "--failed",
            "--format", "json",
        )
        val inspectSecond = runInstalled(
            "inspect",
            store.runId,
            "--control-root", store.controlRoot.toString(),
            "--db", store.dbPath.toString(),
            "--failed",
            "--format", "json",
        )
        assertEquals(
            observeFirst.sha(), observeSecond.sha(),
            "observe stdout SHA-256 must be stable across re-calls on the same runId",
        )
        assertEquals(
            eventsFirst.sha(), eventsSecond.sha(),
            "events stdout SHA-256 must be stable across re-calls on the same runId",
        )
        assertEquals(
            inspectFirst.sha(), inspectSecond.sha(),
            "inspect stdout SHA-256 must be stable across re-calls on the same runId",
        )
    }
}
