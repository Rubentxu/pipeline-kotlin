package dev.rubentxu.pipeline.v2.application.cli

import dev.rubentxu.pipeline.v2.application.observation.ObservationFormat
import dev.rubentxu.pipeline.v2.application.support.AppBinSupport
import dev.rubentxu.pipeline.v2.application.support.CliContractTestKit
import dev.rubentxu.pipeline.v2.application.support.CliRun
import dev.rubentxu.pipeline.v2.application.support.OwnedSubprocess
import dev.rubentxu.pipeline.v2.events.DomainEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Timeout
import java.io.File
import java.nio.file.Files
import java.time.Duration

/**
 * WU-RP-023 — UAT-RP-017 (Observation) installed-distribution UAT.
 *
 * Observation surfaces characterised against the REAL binary (HF2):
 *
 *   1. `pipeline run --format json --db <path> <script>` emits a
 *      `pipelinek.launch/1` LaunchReceipt on stdout line 1, followed by
 *      one DomainEvent JSON object per line on stdout lines 2..N. The
 *      terminal outcome line goes to stderr.
 *   2. `pipeline events --db <path> <runId>` re-reads persisted history
 *      WITHOUT executing anything: one jsonl envelope per line, and the
 *      kind multiset matches the run's own event stream.
 *   3. `--after-cursor` reconnect: the cursor token `evt-cursor-v1:<runId>:<seq>`
 *      printed to stderr by the first read, when passed back, yields ONLY
 *      events after that sequence (none left after a full read).
 *   4. `pipeline events verify --db --run --contract` re-verifies persisted
 *      history against a typed contract WITHOUT re-execution (exit 0 PASSED
 *      on matching expectations; exit 1 on a wrong expected outcome;
 *      exit 2 on a malformed contract).
 *   5. Unknown runId: events read returns an empty stream with exit 0
 *      (observation is read-only).
 *
 * Oracle: stdout/stderr snapshots and kind multisets; DB is the authority.
 *
 * PK-0-57-RC1 / R2.4 (audit fix): the previous form used a raw
 * `ProcessBuilder` + `waitFor(5, TimeUnit.MINUTES)` and parsed stdout
 * with `output.trimStart().startsWith("[")` plus a regex over
 * `"kind":"..."`. That is a pre-R0.3 assumption: line 1 of stdout
 * is a `{` (the LaunchReceipt), not a `[`, and the events are line
 * 2..N. The migration preserves every original oracle while going
 * through [CliContractTestKit.decodeLaunchContract] for the `run`
 * subcommand and [OwnedSubprocess] for concurrent stdout/stderr
 * drain + tree reap.
 */
@Tag("installed-contract")
@Timeout(10, unit = java.util.concurrent.TimeUnit.MINUTES)
class WURp023ObservationModesUatTest {

    private val binary: File = AppBinSupport.discover().toFile()

    /**
     * One way this class runs the installed binary. The deadline is
     * below the class `@Timeout` watchdog so a hang is classified and
     * reaped; the watchdog exists for "this test is broken" only.
     */
    private val cliDeadline: Duration = Duration.ofMinutes(5)

    private fun run(vararg args: String): CliRun {
        return OwnedSubprocess.run(
            command = listOf(binary.absolutePath) + args.toList(),
            timeout = cliDeadline,
        )
    }

    private fun runOk(vararg args: String): OwnedOk {
        val outcome = run(*args)
        return when (outcome) {
            is CliRun.Completed -> OwnedOk(
                exitCode = outcome.exitCode,
                stdout = outcome.stdout,
                stderr = outcome.stderr,
            )
            is CliRun.TimedOut -> error(
                "binary hung on ${args.toList()} after ${cliDeadline.toSeconds()}s; " +
                    "pid=${outcome.diagnostics.pid} descendants=${outcome.diagnostics.descendantPids}",
            )
            is CliRun.LaunchFailed -> error(
                "binary could not be launched on ${args.toList()}: ${outcome.cause}",
            )
        }
    }

    private data class OwnedOk(val exitCode: Int, val stdout: String, val stderr: String)

    private fun writeText(prefix: String, content: String): File {
        val file = Files.createTempFile(prefix, ".tmp").toFile()
        file.writeText(content)
        file.deleteOnExit()
        return file
    }

    private fun kindsOf(events: List<DomainEvent>): List<String> =
        events.map { it::class.simpleName ?: it.toString() }

    /**
     * Parse the jsonl envelopes of the `events` subcommand. Each line is
     * a single JSON envelope with a `kind` field; the `run` subcommand's
     * contract does not apply here, so this test must parse the stream
     * itself rather than route through [CliContractTestKit.decodeLaunchContract].
     */
    private fun kindsOfJsonl(payload: String): List<String> =
        payload.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                Regex("\"eventRefId\":\"[^\"]*\",\"kind\":\"([^\"]+)\"").find(line)?.groupValues?.get(1)
            }
            .toList()

    @Test
    fun `observation surfaces — run launch contract, events jsonl replay, cursor reconnect, contract verify`() {
        assertTrue(binary.exists(), "installDist binary must exist; run :pipeline-application:installDist first")
        val script = writeText("rp023-obs-", """
            pipeline {
                stages {
                    stage("obs") {
                        echo("observable")
                    }
                }
            }
        """.trimIndent())
        val dbDir = Files.createTempDirectory("rp023-db-").toFile()
        try {
            val db = File(dbDir, "db.sqlite").absolutePath

            // 1. Run: stdout line 1 is the LaunchReceipt, lines 2..N are
            //    event envelopes. The testkit decodes the contract; the
            //    receipt carries the runId we need for the events CLI.
            val run1 = runOk("run", "--format", "json", "--db", db, script.absolutePath)
            assertEquals(0, run1.exitCode, "run must succeed; stderr:\n${run1.stderr.takeLast(300)}")
            assertTrue(run1.stderr.contains("Pipeline finished with SUCCESS"))
            val contract = CliContractTestKit.decodeLaunchContract(
                stdout = run1.stdout,
                stderr = run1.stderr,
                format = ObservationFormat.JSON,
            )
            val receipt = contract.receipt
            val runId = receipt.runId
            assertNotNull(runId, "the receipt must carry a runId; got: $receipt")
            val runKinds = kindsOf(contract.eventDocument.events)
            assertTrue(runKinds.contains("CompilationStarted"), "must have CompilationStarted; got $runKinds")
            assertTrue(runKinds.contains("RunStarted"), "must have RunStarted; got $runKinds")
            assertTrue("EchoOutputCaptured" in runKinds, "must have EchoOutputCaptured; got $runKinds")
            assertTrue("RunFinished" in runKinds, "must have RunFinished; got $runKinds")

            // 2. Events CLI: re-read WITHOUT execution; jsonl envelopes; kind
            //    multiset equal to the run's own stream.
            val ev1 = runOk("events", "--db", db, runId)
            assertEquals(0, ev1.exitCode, "events read must succeed; stderr:\n${ev1.stderr.takeLast(300)}")
            val replayKinds = kindsOfJsonl(ev1.stdout)
            assertEquals(runKinds.sorted(), replayKinds.sorted(), "events replay must match the run's own event stream")

            // 3. Cursor reconnect: token on stderr; reconnect after a full
            //    read yields nothing further, exit 0.
            val cursorMatch = Regex("evt-cursor-v1:([^:]+):(\\d+)").find(ev1.stderr)
            assertTrue(cursorMatch != null, "events read must print an evt-cursor-v1 token; stderr:\n${ev1.stderr.takeLast(300)}")
            val cursorToken = "evt-cursor-v1:${cursorMatch!!.groupValues[1]}:${cursorMatch.groupValues[2]}"
            val ev2 = runOk("events", "--db", db, "--after-cursor", cursorToken, runId)
            assertEquals(0, ev2.exitCode)
            assertTrue(kindsOfJsonl(ev2.stdout).isEmpty(), "reconnect after full read must yield no further events")

            // 4. events verify: typed contract, no re-execution.
            val contractOk = writeText("rp023-contract-ok", """
                version: 1
                name: rp023-ok
                expect:
                  runOutcome: SUCCESS
                constraints:
                  - exactly: { event: RunStarted, count: 1 }
                  - exactly: { event: RunFinished, count: 1 }
            """.trimIndent())
            val verifyOk = runOk("events", "verify", "--db", db, "--run", runId, "--contract", contractOk.absolutePath)
            assertEquals(0, verifyOk.exitCode, "verify must PASS recorded success; stdout:\n${verifyOk.stdout.takeLast(400)}")
            assertTrue(verifyOk.stdout.contains("acceptance: PASSED"))

            val contractWrong = writeText("rp023-contract-wrong", """
                version: 1
                name: rp023-wrong
                expect:
                  runOutcome: FAILURE
                constraints:
                  - exactly: { event: RunStarted, count: 1 }
            """.trimIndent())
            val verifyWrong = runOk("events", "verify", "--db", db, "--run", runId, "--contract", contractWrong.absolutePath)
            assertEquals(1, verifyWrong.exitCode, "verify must FAIL a wrong expectation")
            assertTrue(verifyWrong.stdout.contains("acceptance: FAILED"))

            val contractMalformed = writeText("rp023-contract-bad", "expect: success\n")
            val verifyMalformed = runOk("events", "verify", "--db", db, "--run", runId, "--contract", contractMalformed.absolutePath)
            assertEquals(2, verifyMalformed.exitCode, "malformed contract must exit 2 with a typed decode error")

            // 5. Unknown runId: read-only empty observation, exit 0.
            val evUnknown = runOk("events", "--db", db, "no-such-run")
            assertEquals(0, evUnknown.exitCode, "events on unknown run must stay read-only")
            assertTrue(kindsOfJsonl(evUnknown.stdout).isEmpty())
        } finally {
            dbDir.deleteRecursively()
        }
    }
}
