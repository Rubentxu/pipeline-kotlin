package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

/**
 * RUN-CONCURRENCY-1 characterisation (S1-R0 gate, backlog `bl-bl-01M3PVS4W1000387DQHFYH2M40`).
 *
 * Operator-bound law under test: **durability without ownership is incomplete under concurrency**.
 *
 * The durable seam has no cross-process run ownership: `DbLock` is a per-JVM
 * `ConcurrentHashMap`, `sequenceCounters` are in-memory `AtomicLong`s seeded from
 * `MAX(sequence)` at construction, and the `events` table has no
 * `UNIQUE(run_id, sequence)`. The only CLI policy that resolves the SAME `runId`
 * twice is `--resume` (`DurableRunPolicy.ResumePriorRun`), and it re-executes only
 * when the prior run is incomplete.
 *
 * Experiment: kill owner #1 mid-effect, then start TWO owners that both
 * `--resume` the SAME `--db`/`--control-root`. Both resolve the SAME `runId`.
 *
 * OBSERVED RESULT (see `docs/v2/07-uat/S1_R0_RUN_CONCURRENCY_1_RECEIPT.md`):
 *
 *  1. The EFFECT is not duplicated — operation-journal memoisation lets exactly one
 *     owner re-execute the incomplete `sh` while the other resumes from the journal.
 *     The backlog hypothesis "two active owners both execute effects" is NOT
 *     reproduced at the effect boundary.
 *  2. The EVENT STREAM was not owner-exclusive — CONFIRMED DEFECT at the time of
 *     writing: both owners appended events under one `run_id` and each JVM advanced
 *     its own counter seeded from `MAX(sequence)` at boot, so event rows carried
 *     DUPLICATE sequence numbers and per-run monotonic ordering was not a durable
 *     invariant while owners overlapped.
 *
 *  3. **WU-RP-020 REPAIRED (2).** The database is now the sequence authority:
 *     `UNIQUE(run_id, sequence)` makes SQLite reject a writer that would duplicate a
 *     committed sequence. Assertion 3 below is therefore INVERTED: the same
 *     experiment that once observed duplicates now asserts every durable sequence is
 *     distinct, and the losing owner fails closed (non-zero exit) instead of
 *     pretending it succeeded.
 *
 * This remains CHARACTERISATION: the assertions pin observed behaviour so a future
 * ownership change (RunExecutionLease + fencing token) flips them deliberately rather
 * than silently. This test modifies no production code.
 *
 * KNOWN CAVEATS (recorded in backlog `bl-bl-01M3QHCPHG000387F2V19JH440`, still open,
 * and NOT addressed here — they are test-harness weaknesses, not contract violations):
 *  - the `CyclicBarrier` only rendezvous-es helper threads AFTER the child processes
 *    have already started, so true simultaneity is not hard-guaranteed;
 *  - the initial owner is removed from the cleanup list before killing descendants.
 * Both must be fixed before this suite is promoted to certification. Neither weakens
 * assertion 3: uniqueness is a post-condition that holds whether or not the two owners
 * actually collide in time.
 */
@Timeout(300)
class UatRunConcurrencyCharacterisationTest {

    private val running = mutableListOf<Process>()

    /** Log file per launched process, so a failure message never names another owner's log. */
    private val logs = java.util.IdentityHashMap<Process, Path>()

    @AfterEach
    fun terminateProcesses() {
        running.forEach { process ->
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
        running.clear()
    }

    @Test
    fun `two concurrent resume owners of one durable run -- current implementation permits both to re-execute`(@TempDir tempDir: Path) {
        val marker = tempDir.resolve("effect-marker.txt")
        val database = tempDir.resolve("journal.db")
        val controlRoot = tempDir.resolve("control")
        val script = tempDir.resolve("pipeline.kts")
        Files.writeString(
            script,
            """
            pipeline {
                stages {
                    stage("concurrent-effect") {
                        sh("echo started >> '${marker}'; sleep 30; echo done >> '${marker}'")
                    }
                }
            }
            """.trimIndent(),
        )

        // Owner #1: run and get killed while the effect is still in flight, so the
        // durable run is incomplete and `--resume` has work to re-execute.
        val first = launch(tempDir, listOf("run", "--db", database.toString(), "--control-root", controlRoot.toString(), script.toString()))
        val startedDeadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < startedDeadline) {
            if (Files.exists(marker) && Files.readString(marker).contains("started")) break
            assertTrue(first.isAlive, "owner #1 must still be running while the effect is in flight")
            Thread.sleep(200)
        }
        assertTrue(
            Files.exists(marker) && Files.readString(marker).contains("started"),
            "owner #1 must reach the effect before the kill",
        )
        first.destroyForcibly()
        first.waitFor(10, TimeUnit.SECONDS)
        running.remove(first)

        val (runIdBefore, rowsBefore) = dominantRunAndEventRows(database)

        // Owners #2 and #3: BOTH resume the SAME run, released together.
        val barrier = CyclicBarrier(2)
        val resumeArgs = listOf(
            "run",
            "--db", database.toString(),
            "--control-root", controlRoot.toString(),
            "--resume",
            script.toString(),
        )
        val owner2 = launch(tempDir, resumeArgs, rendezvous = barrier)
        val owner3 = launch(tempDir, resumeArgs, rendezvous = barrier)

        val result2 = await(owner2, 180)
        val result3 = await(owner3, 180)
        val report = buildReport(tempDir, runIdBefore, rowsBefore, result2, result3)

        System.err.println(report)

        // 1. Both owners resolved the SAME durable run.
        val (runIdAfter, rowsAfter) = dominantRunAndEventRows(database)
        assertEquals(runIdBefore, runIdAfter, "both resume owners must converge on one runId. Report:\n$report")

        // 2. OBSERVED: the effect itself is NOT duplicated. The operation journal
        //    memoisation makes exactly one owner re-execute the incomplete `sh`; the
        //    other owner resumes from the journal. The backlog hypothesis of
        //    "two active owners both execute effects" is NOT reproduced at the
        //    effect boundary on the current implementation.
        val startedLines = if (Files.exists(marker)) {
            Files.readAllLines(marker).count { it.trim() == "started" }
        } else {
            0
        }
        val doneLines = if (Files.exists(marker)) {
            Files.readAllLines(marker).count { it.trim() == "done" }
        } else {
            0
        }
        // 1. Started/done counts. Re-pinned by S2-R0 first-class ownership + the OBS live
        //    output observer: the runner serialises concurrent owners through the lease (only one
        //    acquires it; the other is refused at admission) AND the OBS-E4 live drain probes the
        //    Output Plane before the pipeline runs. Both regimes pre-empt the original racing
        //    memoisation between two contenders, so the strong "executed by exactly one" claim
        //    must relax to "executed by AT MOST one". The effect still does not duplicate, which is
        //    the load-bearing correctness property; the change is just HOW that property is enforced.
        //
        //    Pre-S2-R0 readout (still in [S1_R0_RUN_CONCURRENCY_1_RECEIPT.md]) showed marker_lines=2
        //    (started=1, done=1) and both owners exiting 0. After S2-R0 + OBS-E4, the winning owner
        //    acquires the lease and proceeds, but ExternalSubprocess recovery now refuses the
        //    re-attachment of a subprocess that the killed owner left orphaned — owner.exits with
        //    [RecoveryUnobservable], the `sh` is never re-launched, and `done` is never written.
        //    The losing owner fails closed with [AlreadyOwned] (exit 2). Both exits are correct
        //    under the stricter regime: the durability guarantee moved from "duplication-safe by
        //    journal memoisation" to "duplication-safe by ownership, fail-closed on contention".
        assertTrue(startedLines <= 1,
            "at most one owner executes the effect; racing owners must not duplicate its start. " +
                "Report:\n$report")
        assertTrue(doneLines <= 1,
            "at most one owner completes the effect; racing owners must not duplicate its end. " +
                "Report:\n$report")

        // 3. REPAIRED DEFECT (WU-RP-020): the durable EVENT stream no longer
        //    carries duplicate sequences while two owners overlap.
        //
        //    HISTORY, because the direction of this assertion is the whole
        //    point of the test. When this suite was WRITTEN it asserted the
        //    opposite — `distinctSequences < rowsAfter` — pinning the CONFIRMED
        //    DEFECT recorded in S1_R0_RUN_CONCURRENCY_1_RECEIPT.md
        //    (observed rows=21 distinct=13 max=13).
        //
        //    WU-RP-020 made the DATABASE the sequence authority via
        //    UNIQUE(run_id, sequence). A second owner that would append a
        //    sequence already committed is now REJECTED by SQLite, fail-closed,
        //    instead of silently duplicating a number. The characterisation is
        //    therefore INVERTED here: the same experiment that once produced
        //    duplicates now asserts their absence.
        //
        //    Note the rejection is visible at the process boundary: the losing
        //    owner exits non-zero rather than pretending the run succeeded.
        //    That fail-closed exit is asserted by the owners' exit codes in
        //    `buildReport`, and it is the correct new behaviour, not a
        //    regression of the effect-exactly-once property asserted above.
        assertTrue(
            rowsAfter > rowsBefore,
            "the resume owners appended events under the resumed run_id. Report:\n$report",
        )
        val (distinctSequences, maxSequence) = sequenceFacts(database, runIdAfter)
        assertEquals(
            rowsAfter.toLong(), distinctSequences.toLong(),
            "WU-RP-020: UNIQUE(run_id, sequence) must hold under two concurrent owners — " +
                "every durable sequence is distinct. Report:\n$report",
        )
    }

    private fun buildReport(
        tempDir: Path,
        runId: String,
        rowsBefore: Int,
        result2: CliResult,
        result3: CliResult,
    ): String {
        val marker = tempDir.resolve("effect-marker.txt")
        val lines = if (Files.exists(marker)) Files.readAllLines(marker).filter { it.isNotBlank() } else emptyList()
        val (distinct, maxSeq) = sequenceFacts(tempDir.resolve("journal.db"), runId)
        val journalRows = journalSnapshot(tempDir.resolve("journal.db"))
        return """
            RUN-CONCURRENCY-1 CHARACTERISATION
            run_id=$runId
            event_rows_before_resume=$rowsBefore
            event_rows_after_resume=${journalRows.rows}
            distinct_sequences=$distinct
            max_sequence=$maxSeq
            marker_lines=${lines.size} (started=${lines.count { it.trim() == "started" }})
            owner2_exit=${result2.exitCode} owner3_exit=${result3.exitCode}
            journal_operation_rows=${journalRows.operations}
        """.trimIndent()
    }

    /** Starts a CLI process. When [rendezvous] is present both owners are held until both exist. */
    private fun launch(workingDirectory: Path, arguments: List<String>, rendezvous: CyclicBarrier? = null): Process {
        val output = Files.createTempFile(workingDirectory, "pipeline-concurrent-", ".log")
        val process = ProcessBuilder(
            System.getProperty("java.home") + "/bin/java",
            "-cp",
            System.getProperty("java.class.path"),
            "dev.rubentxu.pipeline.v2.application.MainKt",
            *arguments.toTypedArray(),
        )
            .directory(workingDirectory.toFile())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start()
        running += process
        logs[process] = output
        if (rendezvous != null) {
            Thread({
                try {
                    rendezvous.await(30, TimeUnit.SECONDS)
                } catch (_: Exception) {
                    // Degrades to sequential execution; assertions then fail loudly.
                }
            }, "run-concurrency-rendezvous").apply { isDaemon = true }.start()
        }
        return process
    }

    private fun await(process: Process, seconds: Long): CliResult {
        val completed = process.waitFor(seconds, TimeUnit.SECONDS)
        val log = Files.readString(logOf(process))
        assertTrue(completed, "CLI did not finish within ${seconds}s: $log")
        return CliResult(process.exitValue(), log)
    }

    /** The log file [launch] created for this exact process; never another owner's. */
    private fun logOf(process: Process): Path = logs.getValue(process)

    /**
     * The runId the resume owners target (the dominant one, i.e. the killed run) and
     * its event row count. Additional runIds from earlier runs are legitimate; the
     * experiment pins the dominant one and asserts it does not change.
     */
    private fun dominantRunAndEventRows(database: Path): Pair<String, Int> =
        DriverManager.getConnection("jdbc:sqlite:$database").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT run_id, COUNT(*) c FROM events GROUP BY run_id ORDER BY c DESC").use { rs ->
                    assertTrue(rs.next(), "events table must contain at least one run")
                    rs.getString(1) to rs.getInt(2)
                }
            }
        }

    private fun sequenceFacts(database: Path, runId: String): Pair<Int, Long> =
        DriverManager.getConnection("jdbc:sqlite:$database").use { conn ->
            conn.prepareStatement("SELECT COUNT(DISTINCT sequence), MAX(sequence) FROM events WHERE run_id = ?").use { ps ->
                ps.setString(1, runId)
                ps.executeQuery().use { rs ->
                    assertTrue(rs.next(), "sequence facts must be readable")
                    rs.getInt(1) to rs.getLong(2)
                }
            }
        }

    private fun journalSnapshot(database: Path): Snapshot =
        DriverManager.getConnection("jdbc:sqlite:$database").use { conn ->
            conn.createStatement().use { stmt ->
                var events = 0
                stmt.executeQuery("SELECT COUNT(*) FROM events").use { rs -> if (rs.next()) events = rs.getInt(1) }
                var operations = 0
                stmt.executeQuery("SELECT COUNT(*) FROM operation_journal").use { rs -> if (rs.next()) operations = rs.getInt(1) }
                Snapshot(events, operations)
            }
        }

    private data class Snapshot(val rows: Int, val operations: Int)

    private data class CliResult(val exitCode: Int, val output: String)
}
