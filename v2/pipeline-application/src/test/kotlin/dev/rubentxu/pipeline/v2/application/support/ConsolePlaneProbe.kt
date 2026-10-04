package dev.rubentxu.pipeline.v2.application.support

import dev.rubentxu.pipeline.v2.application.ConsoleReadService
import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.output.OutputCursor
import dev.rubentxu.pipeline.v2.output.OutputPage
import java.nio.file.Path

/**
 * S4/M1 integration — reads a Step's process transcript from the OUTPUT PLANE.
 *
 * ## Why this exists
 *
 * `EchoOutputCaptured` used to carry process output, and a large number of tests read it from
 * there. M1 moved those bytes into the Output Plane and left events carrying semantic facts
 * only, so those tests went RED with an EMPTY observed string — not because the process printed
 * nothing, but because they were looking at a channel that no longer carries output.
 *
 * Re-pointing them here is the whole point. An assertion like "stage a must see its own
 * environment value" did not become less true; the reader changed to the authority that owns
 * the bytes. [ConsoleReadService] is that authority, and it is deliberately NOT built on
 * `EventHistoryReader`: the event plane continues over a sequence, the output plane over a
 * committed byte offset, and conflating them is what `ADR-M1 D3` forbids.
 *
 * ## The rule that makes this helper safe
 *
 * A read that **cannot be answered** throws. It never returns `""`.
 *
 * That distinction is the whole ballgame. An empty page is a legitimate answer — the process
 * really printed nothing — and a refusal means the reader asked about a stream that does not
 * exist. A helper that collapses both into `""` reproduces, one level up, the exact defect this
 * migration exists to fix: a test that cannot tell "the product did nothing" from "I am reading
 * the wrong place" passes when it should fail. So the failure mode here is a thrown error with
 * the refusal reason, not a quiet empty string.
 */
object ConsolePlaneProbe {

    /**
     * The whole committed transcript of one operation, read as a consumer would.
     *
     * Pages until the plane stops advancing, so the result is independent of
     * [ConsoleReadService.DEFAULT_PAGE_BYTES]. A test that reads only the first page and asserts
     * on it would be asserting on an arbitrary cut point.
     *
     * @throws AssertionError if the plane refuses the read. A refusal is a fact about the
     *   question, and hiding it is what makes a broken reader look like a quiet process.
     */
    fun transcript(
        controlDirRoot: Path,
        runId: String,
        stageIndex: Int,
        stepIndex: Int,
        branchIndex: Int? = null,
    ): String {
        val opId = OpId(runId, stageIndex, stepIndex, branchIndex).format()
        val sink = StringBuilder()
        var cursor: OutputCursor? = null
        do {
            val result = ConsoleReadService.read(controlDirRoot, runId, opId, cursor)
            val page: OutputPage = when (result) {
                is ConsoleReadService.Result.Page -> result.page
                is ConsoleReadService.Result.Refused -> throw AssertionError(
                    "the Output Plane refused to answer for run '$runId' op '$opId' at " +
                        "stage=$stageIndex step=$stepIndex: " +
                        ConsoleReadService.renderRefusal(result.reason) +
                        ". This is NOT 'the process printed nothing' — it means the reader is " +
                        "pointing at the wrong stream, and a test that read '' here would pass " +
                        "for the wrong reason.",
                )
            }
            sink.append(String(page.bytes))
            cursor = page.next
        } while (cursor != null)
        return sink.toString()
    }

    /**
     * The transcript of the single `sh` step a one-step test pipeline contains.
     *
     * Named for what it is rather than made to look general: every current caller runs a
     * one-stage, one-step pipeline, and a helper that pretended to handle arbitrary shapes would
     * be asserting positions it never verified.
     */
    fun singleStepTranscript(controlDirRoot: Path, runId: String): String =
        transcript(controlDirRoot, runId, stageIndex = 0, stepIndex = 0)

    /** The run the log is about, as the engine recorded it. Never guessed from the file name. */
    fun runIdOf(events: List<DomainEvent>): String =
        (events.firstOrNull { it is RunStarted } as? RunStarted)?.runId
            ?: throw AssertionError(
                "the run log carries no RunStarted event, so there is no run identity to read " +
                    "transcripts for. A probe that invented one would point at a stream that " +
                    "does not exist and report the process as silent.",
            )

    /**
     * Every process transcript this run produced for steps of [stepType], joined in event order.
     *
     * ## Which streams exist is a question of fact, not of guesswork
     *
     * Which `(stageIndex, stepIndex)` pairs ran is recorded by the engine in [StepStarted], so
     * this resolves stream identity from the log instead of asking each test to recount its own
     * pipeline by hand. That is a lookup, not a second authority: the BYTES still come from the
     * Output Plane and the cursor still comes from the plane's own page. It is the same
     * resolution [dev.rubentxu.pipeline.v2.application.MainConsoleCli] performs for a human
     * consumer — a name is resolved, never invented.
     *
     * Filtering by [stepType] matters for the same reason. A `core.echo` step has no process and
     * therefore no transcript, and asking the plane for one is a refusal, not an empty page.
     * Treating that refusal as silence would reintroduce, one level up, the exact conflation this
     * helper exists to undo.
     *
     * A step that never started has no [StepStarted] and contributes nothing. When a test asserts
     * that a body did NOT run, the proof is that event plus the absence of a transcript — not the
     * absence of a transcript alone, which a mis-aimed reader would also produce.
     */
    fun transcriptsOfSteps(
        controlDirRoot: Path,
        events: List<DomainEvent>,
        stepType: String,
    ): String {
        val runId = runIdOf(events)
        val sink = StringBuilder()
        events.filterIsInstance<StepStarted>()
            .filter { it.stepType == stepType }
            .forEach { started ->
                sink.append(
                    transcript(
                        controlDirRoot = controlDirRoot,
                        runId = runId,
                        stageIndex = started.stageIndex,
                        stepIndex = started.stepIndex,
                    ),
                )
            }
        return sink.toString()
    }
}
