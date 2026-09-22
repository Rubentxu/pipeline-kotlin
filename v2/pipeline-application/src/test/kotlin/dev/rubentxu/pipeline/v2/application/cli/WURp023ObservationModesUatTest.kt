package dev.rubentxu.pipeline.v2.application.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

import dev.rubentxu.pipeline.v2.application.support.AppBinSupport

/**
 * WU-RP-023 — UAT-RP-017 (Observation) installed-distribution UAT.
 *
 * Observation surfaces characterised against the REAL binary (HF2):
 *
 *   1. Run stdout is ONE JSON ARRAY of event envelopes (JsonEventLog shape);
 *      the terminal outcome line goes to stderr. This is the characterised
 *      surface: array-on-stdout for `run`, jsonl envelopes for `events`.
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
 */
@Timeout(10, unit = TimeUnit.MINUTES)
class WURp023ObservationModesUatTest {

    private val binary: File = AppBinSupport.discover().toFile()

    private data class CliResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun run(vararg args: String): CliResult {
        val pb = ProcessBuilder(binary.absolutePath, *args)
        val proc = pb.start()
        val finished = proc.waitFor(5, TimeUnit.MINUTES)
        assertTrue(finished) { "binary hung on ${args.toList()}" }
        val stdout = proc.inputStream.bufferedReader().readText()
        val stderr = proc.errorStream.bufferedReader().readText()
        return CliResult(proc.exitValue(), stdout, stderr)
    }

    private fun writeText(prefix: String, content: String): File {
        val file = Files.createTempFile(prefix, ".tmp").toFile()
        file.writeText(content)
        file.deleteOnExit()
        return file
    }

    private fun kindsOfJsonArray(payload: String): List<String> {
        // Parse the JSON array stdout of `run` without a JSON dependency:
        // each envelope carries "kind":"X" exactly once per object.
        return Regex("\"kind\":\"([^\"]+)\"").findAll(payload).map { it.groupValues[1] }.toList()
    }

    private fun kindsOfJsonl(payload: String): List<String> =
        payload.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                // Envelope kind follows eventRefId in the events CLI jsonl;
                // earlier "kind" occurrences belong to subject/source refs.
                Regex("\"eventRefId\":\"[^\"]*\",\"kind\":\"([^\"]+)\"").find(line)?.groupValues?.get(1)
            }
            .toList()

    @Test
    fun `observation surfaces — run array stdout, events jsonl replay, cursor reconnect, contract verify`() {
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

            // 1. Run: stdout is a JSON array of envelopes; outcome on stderr.
            val run1 = run("run", "--db", db, script.absolutePath)
            assertEquals(0, run1.exitCode, "run must succeed; stderr:\n${run1.stderr.takeLast(300)}")
            assertTrue(run1.stderr.contains("Pipeline finished with SUCCESS"))
            assertTrue(run1.stdout.trimStart().startsWith("["), "run stdout must be a JSON array of envelopes")
            val runKinds = kindsOfJsonArray(run1.stdout)
            assertTrue(runKinds.contains("CompilationStarted"))
            assertTrue(runKinds.contains("RunStarted"))
            assertTrue(runKinds.contains("EchoOutputCaptured"))
            assertTrue(runKinds.contains("RunFinished"))

            // Discover runId from the run stdout envelopes.
            val runId = Regex("\"runId\":\"([^\"]+)\"").find(run1.stdout)!!.groupValues.get(1)

            // 2. Events CLI: re-read WITHOUT execution; jsonl envelopes; kind
            //    multiset equal to the run's own stream.
            val ev1 = run("events", "--db", db, runId)
            assertEquals(0, ev1.exitCode, "events read must succeed; stderr:\n${ev1.stderr.takeLast(300)}")
            val replayKinds = kindsOfJsonl(ev1.stdout)
            assertEquals(runKinds.sorted(), replayKinds.sorted(), "events replay must match the run's own event stream")

            // 3. Cursor reconnect: token on stderr; reconnect after a full
            //    read yields nothing further, exit 0.
            val cursorMatch = Regex("evt-cursor-v1:([^:]+):(\\d+)").find(ev1.stderr)
            assertTrue(cursorMatch != null, "events read must print an evt-cursor-v1 token; stderr:\n${ev1.stderr.takeLast(300)}")
            val cursorToken = "evt-cursor-v1:${cursorMatch!!.groupValues[1]}:${cursorMatch.groupValues[2]}"
            val ev2 = run("events", "--db", db, "--after-cursor", cursorToken, runId)
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
            val verifyOk = run("events", "verify", "--db", db, "--run", runId, "--contract", contractOk.absolutePath)
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
            val verifyWrong = run("events", "verify", "--db", db, "--run", runId, "--contract", contractWrong.absolutePath)
            assertEquals(1, verifyWrong.exitCode, "verify must FAIL a wrong expectation")
            assertTrue(verifyWrong.stdout.contains("acceptance: FAILED"))

            val contractMalformed = writeText("rp023-contract-bad", "expect: success\n")
            val verifyMalformed = run("events", "verify", "--db", db, "--run", runId, "--contract", contractMalformed.absolutePath)
            assertEquals(2, verifyMalformed.exitCode, "malformed contract must exit 2 with a typed decode error")

            // 5. Unknown runId: read-only empty observation, exit 0.
            val evUnknown = run("events", "--db", db, "no-such-run")
            assertEquals(0, evUnknown.exitCode, "events on unknown run must stay read-only")
            assertTrue(kindsOfJsonl(evUnknown.stdout).isEmpty())
        } finally {
            dbDir.deleteRecursively()
        }
    }
}
