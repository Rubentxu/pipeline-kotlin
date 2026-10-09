package dev.rubentxu.pipeline.v2.application.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

import dev.rubentxu.pipeline.v2.application.support.AppBinSupport

/**
 * **UAT-R1-01** — `sh("true")`, sin stdout/stderr, termina y su follower conoce el fin.
 *
 * Source of the row: OBS-R1 mandate §1.5.
 *
 * ## Why this class exists when `ObservationWakeupTest.FOLLOW-5` already asserts the decision
 *
 * FOLLOW-5 is HF0: it drives `followDecision` directly. It proves the FUNCTION reaches the right
 * verdict for the right inputs. It cannot prove that a real pipeline reaches that function — that
 * the installed binary wires the run plane's `RunFinished` into it, that the Output Plane really is
 * empty for a silent step, and that the process actually exits. Every one of those is a separate way
 * to hang, and all three were broken or unproven before this class.
 *
 * HF2 — Forked Real Distribution. The production entry point crossed is the installed binary:
 * `pipelinek run` writes the plane, then `pipelinek observe … --follow` reads it and must RETURN.
 * Nothing here reimplements the decision, the store, or the seal; a harness that did would certify
 * itself (HARNESS FIDELITY §2).
 *
 * ## The property, stated as a discrete observation
 *
 * The failure mode is a HANG, so the observation is whether the process EXITED. That is discrete and
 * it is what the product promises. Nothing here asserts on a duration, on a byte count, or on how
 * many polls it took: those are properties of the machine.
 *
 * The bounds below are an ESCAPE HATCH, not a threshold being asserted on. The follower returns in
 * well under a second when it works, and row B must additionally outlive the fixture's `sleep 12`, so
 * 120 s and 180 s are two orders of magnitude of headroom over the observed behaviour. They exist so
 * that a hang FAILS and names itself instead of stalling the suite. The verdict is "exited" or
 * "hung", never "took too long".
 *
 * ## Three harness defects this class paid for, recorded because each gave a WRONG verdict
 *
 * The first version read the pipes after `destroyForcibly` had closed them, so every hang surfaced as
 * `IOException: Stream closed` — pointing the reader at the harness instead of at the follower. The
 * repair read them BEFORE the kill, which was worse: `readText()` waits for EOF and a process that
 * never exits never sends one, so a reported hang became a real one and froze the measurement. What
 * is left drains from the start on daemon threads and kills first. Each step was found by running it,
 * not by reading it.
 *
 * ## Rows
 *
 *  * `R1-01-A` — the run has already finished when the follower starts. Proves a finished silent run
 *    terminates. This is the row the old `tailStates.isEmpty() -> ReadAgain` arm made hang.
 *  * `R1-01-B` — the follower starts while the run is STILL EXECUTING a silent step, and terminates
 *    when the run ends. Proves the follower WAITS rather than exiting early, which A cannot: A
 *    cannot distinguish "waited then finished" from "exited immediately".
 *
 * ## Mutations
 *
 *  * `M-R1-01-A` — restore the unconditional `tailStates.isEmpty() -> ReadAgain` arm. Both rows hang.
 *  * `M-R1-01-B` — answer `Finished` from an empty tail list while ignoring `runFinished`. Row A still
 *    passes; row B fails, because the follower exits immediately on a run that is still going. This is
 *    the pair that makes the two rows non-redundant.
 */
@Timeout(15, unit = TimeUnit.MINUTES)
class ObsR1SilentFollowInstalledUatTest {

    private val binary: File = AppBinSupport.discover().toFile()

    /**
     * A finished process, or the fact that it was still running.
     *
     * [hung] is carried rather than thrown so a caller can report WHICH kind of failure it was —
     * a hang and a wrong exit code are different defects and a merged assertion hides which.
     */
    private data class CliResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val hung: Boolean,
    )

    /**
     * Runs the binary and always returns a result, including when it hangs.
 *
     * Two harness defects were paid for here, both recorded because both produced a WRONG verdict
     * rather than a missing one:
 *
     *  1. Reading the streams after `destroyForcibly` closed them, so every hang surfaced as
     *     `IOException: Stream closed` — indistinguishable from a broken harness, pointing the reader
     *     at the test instead of at the follower.
     *  2. The first fix read them BEFORE the kill, which is worse: `readText()` blocks until EOF, and
     *     a process that never exits never sends one. That turned a reported hang into a real one and
     *     froze the measurement.
     *
     * The only arrangement that is correct on both counts is to drain from the START on background
     * threads and kill first. Draining from the start also removes the classic deadlock where a chatty
     * child fills the pipe buffer and blocks on a write nobody is reading — which would have made this
     * harness fail for large output and blamed the follower for it.
     */
    private fun cli(vararg args: String, timeoutMinutes: Long = 5): CliResult {
        val proc = ProcessBuilder(binary.absolutePath, *args).start()
        val out = StringBuilder()
        val err = StringBuilder()
        val drainOut = drain(proc, proc.inputStream, out)
        val drainErr = drain(proc, proc.errorStream, err)

        val finished = proc.waitFor(timeoutMinutes, TimeUnit.MINUTES)
        if (finished) {
            drainOut.join(DRAIN_JOIN_MILLIS)
            drainErr.join(DRAIN_JOIN_MILLIS)
            return CliResult(
                exitCode = proc.exitValue(),
                stdout = out.toString(),
                stderr = err.toString(),
                hung = false,
            )
        }
        proc.destroyForcibly()
        proc.waitFor(30, TimeUnit.SECONDS)
        drainOut.join(DRAIN_JOIN_MILLIS)
        drainErr.join(DRAIN_JOIN_MILLIS)
        // The kill is what closes the pipes, so whatever the drain collected by then is kept and the
        // rest is named as missing rather than reported as an empty stream the follower never wrote.
        return CliResult(
            exitCode = -1,
            stdout = out.toString().ifEmpty { "<no stdout collected before the kill>" },
            stderr = err.toString().ifEmpty { "<no stderr collected before the kill>" },
            hung = true,
        )
    }

    private fun drain(proc: Process, stream: java.io.InputStream, into: StringBuilder): Thread =
        Thread {
            try {
                stream.bufferedReader().use { reader ->
                    val chunk = CharArray(4096)
                    while (true) {
                        val n = reader.read(chunk)
                        if (n < 0) break
                        into.appendRange(chunk, 0, n)
                    }
                }
            } catch (_: java.io.IOException) {
                // The pipe closed because the process was killed. That is the expected end here.
            }
        }.apply {
            isDaemon = true
            name = "obs-r1-drain-${proc.pid()}"
            start()
        }

    private companion object {
        /** Bounded, so a drain thread that never sees EOF cannot hold the harness open either. */
        const val DRAIN_JOIN_MILLIS = 5_000L

        /** Escape hatch for a finished silent run. Observed behaviour: under a second. */
        const val FOLLOW_BOUND_ROW_A_MINUTES = 2L

        /** Row B must additionally outlive the fixture's `sleep 12`. */
        const val FOLLOW_BOUND_ROW_B_MINUTES = 3L
    }

    /**
     * A pipeline whose only step writes NOTHING to stdout or stderr.
     *
     * `true` is the smallest command that exits 0 silently. `sleep` is the smallest command that
     * stays silent AND stays alive, which is what row B needs: it gives the follower a window in
     * which the run is provably still going and provably has no stream to seal.
     */
    private fun silentScript(dir: Path, command: String): File =
        File(dir.toFile(), "silent-${command.hashCode().toUInt().toString(16)}.pipeline.kts").apply {
            writeText(
                """
                pipeline {
                    stages {
                        stage("silent") {
                            sh("$command")
                        }
                    }
                }
                """.trimIndent(),
            )
        }

    /**
     * The run id of a run that has already committed `RunStarted`, read straight from the journal.
     *
     * The harness needs it WHILE the run is alive, and neither `run`'s stdout (collected to the end)
     * nor any `observe` verb exposes a run enumeration yet — OBS-R6 §6.2 lists "enumerar runs" as
     * capability to be BUILT, so there is nothing to call. Reading the one row the run itself wrote
     * is not a reimplementation of the product: it is the same durable fact, and a different journal
     * would make the whole UAT vacuous.
     */
    private fun liveRunId(db: File, deadlineSeconds: Long): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(deadlineSeconds)
        var lastSeen: String? = null
        while (System.nanoTime() < deadline) {
            lastSeen = runCatching {
                dev.rubentxu.pipeline.v2.events.durable.SqliteConnectionFactory
                    .open(db.absolutePath)
                    .use { conn ->
                        conn.createStatement().use { st ->
                            st.executeQuery("SELECT run_id FROM events ORDER BY rowid ASC LIMIT 1")
                                .use { rs -> if (rs.next()) rs.getString(1) else null }
                        }
                    }
            }.getOrNull()
            if (lastSeen != null) return lastSeen
            Thread.sleep(100)
        }
        error("no run was journalled within ${deadlineSeconds}s; the run may not have started")
    }

    @Test
    fun `R1-01-A a silent finished run terminates its follower`( @TempDir dir: Path) {
        val db = File(dir.toFile(), "a.sqlite")
        val ctl = File(dir.toFile(), "a-control").absolutePath
        val script = silentScript(dir, "true")

        val run = cli("run", "--db", db.absolutePath, "--control-root", ctl, script.absolutePath)
        assertEquals(0, run.exitCode, "the silent pipeline must succeed; stderr:\n${run.stderr.takeLast(400)}")

        // The run id comes from the journal, not from `run`'s stdout. Without `--format json` that
        // stdout is human-readable console text and carries no envelope to parse, which is a
        // dependency on presentation the property under test has nothing to do with.
        val follow = cli(
            "observe", liveRunId(db, deadlineSeconds = 30),
            "--control-root", ctl,
            "--db", db.absolutePath,
            "--view", "console",
            "--follow",
            timeoutMinutes = FOLLOW_BOUND_ROW_A_MINUTES,
        )
        assertTrue(
            !follow.hung,
            "UAT-R1-01 FAILS AS A HANG: the follower did not return for a finished run that wrote " +
                "no byte. That is the defect this row exists for — the output plane has nothing, and " +
                "termination must come from the run plane. stderr:\n${follow.stderr.takeLast(400)}",
        )
        assertEquals(
            0,
            follow.exitCode,
            "a follower that reaches the end is a success; a refusal is a different outcome. " +
                "stderr:\n${follow.stderr.takeLast(400)}",
        )
        assertEquals(
            "",
            follow.stdout,
            "a silent step must print nothing. Output here would mean a stream was fabricated to " +
                "give the follower something to seal, which invents an observation to buy a " +
                "termination",
        )
    }

    @Test
    fun `R1-01-B the follower waits for a running silent run and ends with it`( @TempDir dir: Path) {
        val db = File(dir.toFile(), "b.sqlite")
        val ctl = File(dir.toFile(), "b-control").absolutePath
        val script = silentScript(dir, "sleep 12")

        // Started, not waited on. The follower below has to attach to a run that is still going.
        val runProc = ProcessBuilder(
            binary.absolutePath, "run",
            "--db", db.absolutePath, "--control-root", ctl, script.absolutePath,
        ).start()

        try {
            val runId = liveRunId(db, deadlineSeconds = 90)
            val follow = cli(
                "observe", runId,
                "--control-root", ctl,
                "--db", db.absolutePath,
                "--view", "console",
                "--follow",
                timeoutMinutes = FOLLOW_BOUND_ROW_B_MINUTES,
            )
            assertTrue(
                !follow.hung,
                "UAT-R1-01 FAILS AS A HANG: the follower started while the run was still executing " +
                    "and never returned. It has no stream to seal and no terminal fact yet, so it " +
                    "must keep reading — not conclude, and not hang after the run ends. " +
                    "stderr:\n${follow.stderr.takeLast(400)}",
            )
            assertEquals(
                0,
                follow.exitCode,
                "reaching the end of a silent run is a success. stderr:\n${follow.stderr.takeLast(400)}",
            )
            assertEquals(
                "",
                follow.stdout,
                "the step wrote nothing, so the follower has nothing to print",
            )
        } finally {
            runProc.destroyForcibly()
            runProc.waitFor(30, TimeUnit.SECONDS)
        }

        assertTrue(
            runProc.waitFor(60, TimeUnit.SECONDS),
            "the run itself must also finish; a run that outlives its own sleep is a different defect",
        )
        assertEquals(0, runProc.exitValue(), "the silent pipeline must succeed")
    }
}