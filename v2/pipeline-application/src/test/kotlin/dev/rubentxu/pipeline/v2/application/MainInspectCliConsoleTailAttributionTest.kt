package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.application.durable.OutputPlaneProvider
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.durable.SqliteEventStore
import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.time.Instant

/**
 * D2 — `inspect --log-tail` stamped ONE run-scoped transcript onto EVERY row.
 *
 * ## The defect, measured
 *
 * Distribution 0.62.0, two stages: stage 0 prints `DESPUES`, stage 1 exits 7.
 *
 * ```text
 * $ pipelinek inspect <run> --log-tail 200 --fields STAGE,OUTCOME,CONSOLETAIL
 * inspect run=<run>
 *   STAGE            OUTCOME     CONSOLETAIL
 *    build/sh-0      StepStarted  DESPUES
 *    build/sh-0      StepFinished DESPUES
 *    verify/sh-0     failure      DESPUES      <- the FAILURE quoting the step that SUCCEEDED
 * ```
 *
 * Thirteen rows, thirteen copies. A reader asking "what did the failing step print" was handed
 * the transcript of the step that succeeded, on the row that says the step failed. `InspectField
 * .ConsoleTail`'s own KDoc promised "the Output Plane for THE FAILED STEP'S OP" the whole time.
 *
 * This is the same class as the `ok` verdict fixed in cycle 1, in the opposite direction: a
 * projection rendering a fact its event does not carry. Inventing a verdict and borrowing a
 * transcript are the same failure with different vocabulary.
 *
 * ## The production entry point this crosses
 *
 * ```text
 * MainInspectCli.main(argv)                             real stdout/stderr
 *   -> CliParser.parseInspect
 *   -> SqliteEventStore.eventsFor(runId)                 REAL SQLite, REAL events
 *   -> operationIdPerEvent(events, runId)                pure fold, no I/O
 *   -> OutputPlaneProvider.storeForReading(controlRoot)  REAL SegmentOutputStore
 *   -> FrameIndexedObservationOutputReader.readTail(runId, opId, n)   per OPERATION
 *   -> toInspectionRecord(event, opId, tails)
 *   -> InspectField.ConsoleTail.extract -> emitDocument
 * ```
 *
 * **HF1, in-process.** Real store over a real directory, real SQLite, frames published through the
 * production write order (`declareStream -> reserve -> write -> commit -> append`). Nothing is
 * stubbed and no reader is reimplemented.
 *
 * `TAIL-OP-0` is HF0: the pure fold, with no filesystem at all. It comes first because it is the
 * decision the other rows depend on — if the fold attributes a `StepFailed` to the wrong op, every
 * other row is measuring the wrong thing, and they would all be green.
 *
 * ## The stream shape is measured, not assumed
 *
 * The fixture writes `"$runId/$runId-s0-0/stdout"`, read out of the frame index of a real run:
 *
 * ```text
 * $ strings <controlRoot>/output-plane/frames/<runId>.streams
 * <runId>/<runId>-s0-0/stdout/stdout
 * ```
 *
 * `MainInspectCliConsoleTailRefusalTest` writes `"$runId/build/sh-0/stdout"`, which no engine
 * ever mints. It passed because the run-scoped read matched on `runId` alone and ignored the
 * middle segments — a fixture whose shape the product does not produce, green for the wrong reason.
 *
 * ## The mutation that must kill this
 *
 * ```text
 * M-1  toInspectionRecord:
 *        consoleTail = opId?.let { consoleTails[it.format()] } ?: InspectConsoleTail.NotRequested
 *      reverted to the pre-fix line, which received ONE tail for the whole run:
 *        consoleTail = <the single run-scoped InspectConsoleTail>
 * ```
 *
 * Attribution MEASURED on this class after the mutation, not predicted: kills `TAIL-OP-1` AND
 * `TAIL-OP-2`. Both carry the same subject — a row must not report a transcript that is not its
 * own — so both are expected to die, and a mutation that killed only one of them would mean one
 * of the two rows was not measuring attribution at all.
 * `TAIL-OP-0` and `TAIL-OP-3` stay green: the fold is not the mutation, and the fail-closed
 * validation of an unknown opId is not the mutation.
 *
 * This attribution was PREDICTED as "kills TAIL-OP-1 only" and the measurement corrected it.
 * Recorded because a predicted attribution that is never measured is the same class as a predicted
 * test result — and the correction is the useful part: it named two rows, and had the mutation
 * killed only one, the other would have been quietly measuring something else.
 */
@Timeout(60)
class MainInspectCliConsoleTailAttributionTest {

    @TempDir
    lateinit var root: Path

    private val runId = "run-inspect-attribution"
    private val transcript = "step-0 said something important\n"

    /** The op that prints, and the op that does not. Measured shape: `<runId>/<opId>/<channel>`. */
    private val printingOp = OpId(runId, stageIndex = 0, stepIndex = 0)
    private val silentOp = OpId(runId, stageIndex = 1, stepIndex = 0)

    private val controlRoot: Path get() = root.resolve("control")
    private val db: Path get() = root.resolve("events.db")

    @AfterEach
    fun dropCachedStores() = OutputPlaneProvider.forgetAll()

    // ------------------------------------------------------------------ HF0: the pure decision

    /**
     * The fold, with no filesystem: `StepFailed` carries no `stageIndex`, so it must INHERIT the
     * open step's operation rather than guess, and a stage boundary must close the previous one.
     *
     * This row is first because it is the decision the other three rows observe. A fold that gave
     * `StepFailed` the wrong op would make every row above green while answering a different
     * question.
     */
    @Test
    fun TAIL_OP_0_step_failed_inherits_the_open_step_operation() {
        val events: List<DomainEvent> = listOf(
            StageStarted("e1", runId, 0L, AT, stageIndex = 0, stageName = "build"),
            StepStarted("e2", runId, 1L, AT, stageIndex = 0, stepIndex = 0, stepName = "build/sh-0", stepType = "core.sh"),
            StepFinished("e3", runId, 2L, AT, stageIndex = 0, stepIndex = 0, stepName = "build/sh-0", stepType = "core.sh"),
            // Found by reading the RENDERED DOCUMENT against the installed binary, not by a test:
            // this row is a stage summary, and it was carrying the stage's last step's transcript.
            // One `is StageStarted -> null` branch closed the opening end and left this one open.
            StageFinished("e4", runId, 3L, AT, stageIndex = 0, stageName = "build", outcome = "success"),
            StageStarted("e5", runId, 4L, AT, stageIndex = 1, stageName = "verify"),
            StepStarted("e6", runId, 5L, AT, stageIndex = 1, stepIndex = 0, stepName = "verify/sh-0", stepType = "core.sh"),
            // No stageIndex on this event by construction — that is the whole reason the fold exists.
            StepFailed("e7", runId, 6L, AT, stepIndex = 0, stepName = "verify/sh-0", stepType = "core.sh",
                failureKind = FailureKind.SCRIPT, message = "shell exited with code 7"),
        )

        val ops = MainInspectCli.operationIdPerEvent(events, runId)

        // A stage-level row belongs to a stage, and a stage is not an operation. BOTH ends.
        assertNull(ops[0], "a StageStarted opens a stage, not an operation, so it has no op")
        assertNull(
            ops[3],
            "a StageFinished is a STAGE summary and must not carry the stage's last step's transcript. " +
                "This hole was measured in the rendered document and not by a test, which is why the " +
                "row exists: the fold had one stage branch where the law has two.",
        )
        assertEquals(printingOp.format(), ops[1]?.format(), "a StepStarted names its own operation")
        assertEquals(
            printingOp.format(), ops[2]?.format(),
            "a StepFinished carries both indices and must resolve to its own op",
        )
        assertEquals(
            silentOp.format(), ops[6]?.format(),
            "a StepFailed carries NO stageIndex, so it must INHERIT the op of the step that started " +
                "it — the failing one, not the earlier successful one. Getting this wrong is the " +
                "defect this whole class removes: the failure row would report the other step's " +
                "transcript, and the wrong transcript is indistinguishable from the right one.",
        )
    }

    // ------------------------------------------------------------------ HF1: the CLI boundary

    /**
     * The claim, at the boundary a consumer reads it. This is the row `M-1` kills.
     */
    @Test
    fun TAIL_OP_1_a_failing_rows_transcript_is_not_another_steps_transcript() {
        givenACommittedTranscriptForThePrintingOp()
        appendTheTwoStageRun()

        val rows = cliRows(
            "--log-tail", "1024",
            "--fields", "STAGE,OUTCOME,CONSOLETAIL",
        )

        assertTrue(rows.isNotEmpty(), "the run must still produce rows")
        val failure = rows.single { it.contains("failure") && it.contains("verify/sh-0") }
        assertFalse(
            failure.contains(transcript.trim()),
            "the FAILING step's row carried the transcript of the step that succeeded:\n$failure\n" +
                "A reader asking what the failure printed is handed the wrong answer, and the wrong " +
                "answer looks exactly like the right one.",
        )
        // The control: the transcript must EXIST somewhere in the document, or the row above would
        // pass on a fixture that published nothing — the vacuous-green shape.
        val printed = rows.single { it.contains("build/sh-0") && it.contains("StepStarted") }
        assertTrue(
            printed.contains(transcript.trim()),
            "the op that DID print must still report its own transcript:\n$printed",
        )
    }

    /**
     * `--log-tail-op` narrows to the operation the operator NAMED. The other operation's rows
     * project silence rather than the named one's transcript.
     */
    @Test
    fun TAIL_OP_2_log_tail_op_reports_the_transcript_on_the_named_operation_only() {
        givenACommittedTranscriptForThePrintingOp()
        appendTheTwoStageRun()

        val rows = cliRows(
            "--log-tail", "1024",
            "--log-tail-op", printingOp.format(),
            "--fields", "STAGE,OUTCOME,CONSOLETAIL",
        )

        assertTrue(
            rows.single { it.contains("build/sh-0") && it.contains("StepStarted") }.contains(transcript.trim()),
            "the named operation's own row must carry the transcript:\n$rows",
        )
        assertTrue(
            rows.filter { it.contains("verify/sh-0") }.none { it.contains(transcript.trim()) },
            "an operation the operator did not name must not be given the named one's transcript:\n$rows",
        )
    }

    /**
     * An opId that belongs to no step in this run is a usage error, not an empty column.
     *
     * Without this, a mistyped `--log-tail-op` renders every row empty and the operator cannot
     * tell "no such operation" from "this step printed nothing" — the one distinction the field
     * exists to make, erased by the typo.
     */
    @Test
    fun TAIL_OP_3_an_unknown_log_tail_op_is_refused_with_a_reason() {
        givenACommittedTranscriptForThePrintingOp()
        appendTheTwoStageRun()

        val result = cli(
            runId,
            "--db", db.toString(),
            "--control-root", controlRoot.toString(),
            "--log-tail", "1024",
            "--log-tail-op", "$runId-s9-9",
        )

        assertEquals(2, result.exitCode, "an unknown op is a usage error, not a document: ${result.stdout}")
        assertTrue(
            result.stderr.contains("--log-tail-op $runId-s9-9") &&
                result.stderr.contains("This run's step operations") &&
                result.stderr.contains(printingOp.format()),
            "the refusal must name what was asked for AND what the run actually has: ${result.stderr}",
        )
    }

    // ---- fixture -------------------------------------------------------------------------------

    private fun stdoutStream(op: OpId) = OutputStreamId("$runId/${op.format()}/stdout")

    /**
     * Publishes [transcript] on the PRINTING op only, through the production write order.
     *
     * The silent op deliberately gets no stream at all — that is what a step which printed nothing
     * looks like on disk, and it is the case `TAIL_OP_1` is about.
     */
    private fun givenACommittedTranscriptForThePrintingOp() {
        val store = OutputPlaneProvider.storeForWriting(controlRoot)
        val stream = stdoutStream(printingOp)
        store.frameIndex().declareStream(stream, OutputChannel.STDOUT)
        val payload = transcript.toByteArray(Charsets.UTF_8)
        val from = store.committedExtent(stream) ?: 0L
        store.open(stream).reserve(payload.size).let { reservation ->
            reservation.write(payload)
            reservation.commit()
        }
        store.frameIndex().append(stream, OutputChannel.STDOUT, from, from + payload.size)
        // The CLI opens its OWN reader store for this root; drop the cached ones so the reading
        // side is constructed against the state on disk as it stands right now.
        OutputPlaneProvider.forgetAll()
    }

    /**
     * The run's real event sequence, appended in order.
     *
     * Every event carries `sequence = 0L` on purpose: [dev.rubentxu.pipeline.v2.events.durable.
     * SqliteEventStore.appendAssigned] is the durable authority for `UNIQUE(run_id, sequence)` and
     * assigns the sequence itself, so a fixture that invents sequences either collides with the
     * counter or silently reorders. Production does not choose its sequences and neither does this.
     */
    private fun appendTheTwoStageRun() {
        SqliteEventStore(db.toString()).use { store ->
            val events: List<DomainEvent> = listOf(
                StageStarted("e1", runId, 0L, AT, stageIndex = 0, stageName = "build"),
                StepStarted("e2", runId, 0L, AT, stageIndex = 0, stepIndex = 0,
                    stepName = "build/sh-0", stepType = "core.sh"),
                StepFinished("e3", runId, 0L, AT, stageIndex = 0, stepIndex = 0,
                    stepName = "build/sh-0", stepType = "core.sh"),
                StageStarted("e4", runId, 0L, AT, stageIndex = 1, stageName = "verify"),
                StepStarted("e5", runId, 0L, AT, stageIndex = 1, stepIndex = 0,
                    stepName = "verify/sh-0", stepType = "core.sh"),
                StepFailed("e6", runId, 0L, AT, stepIndex = 0, stepName = "verify/sh-0",
                    stepType = "core.sh", failureKind = FailureKind.SCRIPT,
                    message = "shell exited with code 7"),
                StepFinished("e7", runId, 0L, AT, stageIndex = 1, stepIndex = 0,
                    stepName = "verify/sh-0", stepType = "core.sh"),
            )
            events.forEach(store::append)
        }
    }

    private data class CliResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun cli(vararg args: String): CliResult {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val savedOut = System.out
        val savedErr = System.err
        val exit = try {
            PrintStream(out, true, Charsets.UTF_8).use { stdout ->
                PrintStream(err, true, Charsets.UTF_8).use { stderr ->
                    System.setOut(stdout)
                    System.setErr(stderr)
                    MainInspectCli.main(arrayOf(*args))
                }
            }
        } finally {
            System.setOut(savedOut)
            System.setErr(savedErr)
        }
        return CliResult(exit, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    /** One document row per line, header and indentation removed. */
    private fun cliRows(vararg extraArgs: String): List<String> {
        val result = cli(
            runId,
            "--db", db.toString(),
            "--control-root", controlRoot.toString(),
            *extraArgs,
        )
        assertEquals(0, result.exitCode, "the document must be produced: ${result.stderr}")
        return result.stdout.lines()
            .drop(2)                       // "inspect run=…" and the column header
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private companion object {
        val AT: Instant = Instant.parse("2026-10-18T09:00:00Z")
    }
}
