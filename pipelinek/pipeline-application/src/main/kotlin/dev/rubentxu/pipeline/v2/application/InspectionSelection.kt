package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.runtime.durable.OpId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted

/**
 * The SELECTION of an inspection: which rows the document shows, and which operation each row
 * belongs to.
 *
 * ## What this owns, and what it does not
 *
 * Everything here is PURE: no clock, no filesystem, no store, no reader. The inputs are the run's
 * events, the operation each event belongs to, the console tails the read produced and the parsed
 * query; the output is the list of rows the document renders. That boundary is the point of the
 * split: the selection is the part of the verb whose behaviour can be pinned by a unit test, and
 * the reads it sits on cannot.
 *
 * ## One authority, not two
 *
 * The decision "which records does this query show" lives HERE and only here. It used to live in
 * the verb, mixed with the reads and the rendering; it was MOVED, not copied, so there is still one
 * place that decides it and no second place free to disagree.
*/
internal object InspectionSelection {

    /**
     * Resolve each event to the operation whose console tail it may report, in event order.
     *
     * ## The defect this removes
     *
     * `--log-tail` used to be answered ONCE for the whole run and the same value stamped onto
     * every row. Measured in an earlier build against distribution 0.62.0, a two-stage run whose first
     * stage printed `DESPUES` and whose second stage exited 7:
     *
     * ```text
     * STAGE          OUTCOME   CONSOLETAIL
     *  build/sh-0    StepStarted  DESPUES
     *  verify/sh-0   failure     DESPUES     <- a FAILURE carrying the other step's transcript
     * ```
     *
     * Thirteen rows, thirteen copies, and the failure's row quoted a step that SUCCEEDED. Same
     * defect class as the `ok` verdict, in the opposite direction: a projection rendering a fact
     * its event does not carry. [MainInspectCli.InspectField.ConsoleTail] had always promised "the
     * Output Plane for THE FAILED STEP'S OP"; this function is what makes the promise true.
     *
     * ## Why a fold, and what it cannot do
     *
     * `StepFailed` carries `stepIndex` but NOT `stageIndex`, and an [OpId] needs both. The fold
     * therefore keeps the op of the most recent [StepStarted] — a step runs inside its stage, so
     * the open step's op is the right one — while every event that carries its own indices
     * prefers them.
     *
     * Both stage boundaries CLEAR the open op: a stage-level row belongs to a stage, and a stage
     * is not an operation. `StageFinished` was measured inheriting the last step's op and printing
     * that step's transcript on the row that closes its stage — the same defect as the failure row,
     * one level up, and found by reading the rendered document rather than by a test.
     * `StageSkipped` needs no case: a skipped stage never opened a step, so `open` is already null.
     *
     * What the fold cannot decide: a `StepFailed` from a PARALLEL branch that reports after a
     * sibling started, when the failure was written by an OLDER build — that event carries
     * nothing to resolve it with, so it inherits the sibling. Stated rather than hidden, because
     * wrong-but-present attribution is precisely what the measurement above removes.
     *
     * A step of a parallel branch now carries its own operation identity, so the fold does not
     * have to decide that case any more: the event says which operation it is about.
     *
     * Pure: no clock, no filesystem, no reader. [OpId] is the typed authority for the format —
     * this never re-templates the string.
    */
    fun operationIdPerEvent(events: List<DomainEvent>, runId: String): List<OpId?> {
        var open: OpId? = null
        return events.map { event ->
            open = when (event) {
                // A stage boundary closes every operation the stage opened. Both ends, not one:
                // closing only the opening end leaves the stage's own summary row holding the last
                // step's transcript.
                is StageStarted, is StageFinished -> null
                is StepStarted -> operationOf(event.opId, runId, event.stageIndex, event.stepIndex)
                is StepFinished -> operationOf(event.opId, runId, event.stageIndex, event.stepIndex)
                // A failure of a step of a parallel branch carries the operation it happened in,
                // and that is the failure's OWN operation: the step that failed is not the step
                // the fold happens to have open, and in a parallel stage the open one is a
                // sibling's. A failure without an identity keeps inheriting the open step, the
                // way it always did.
                is StepFailed -> {
                    val identity = event.opId
                    if (identity == null) open else OpId.parse(identity)
                }
                else -> open
            }
            open
        }
    }

    /**
     * The operation a step's event belongs to.
     *
     * A step that runs inside a parallel branch carries its own operation identity, and that
     * identity IS the operation the event belongs to: the sibling branch runs a step with the
     * same stage index and the same step index, so an operation derived from the indices would
     * name the sibling's bytes.
     *
     * A step outside a branch carries no identity, and its operation is derived from the indices:
     * the same operation it always was.
     *
     * An identity this build cannot read names NO operation. The row then says which step it is
     * about and says nothing about which bytes, and that is better than a row that borrows the
     * bytes of the sibling branch.
     */
    private fun operationOf(opId: String?, runId: String, stageIndex: Int, stepIndex: Int): OpId? =
        if (opId == null) OpId(runId, stageIndex, stepIndex) else OpId.parse(opId)

    /**
     * Translate one [DomainEvent] into the row shape that [MainInspectCli.InspectField] extractors
     * read from. The mapping is intentionally narrow: the five [MainInspectCli.InspectField] cases
     * are the contract, not the union of every DomainEvent subtype.
     *
     * [opId] is this row's operation, or null when the event belongs to no step. A row with no
     * operation has no transcript, and says nothing rather than borrowing one.
    */
    fun toRecord(
        event: DomainEvent,
        opId: OpId?,
        consoleTails: Map<String, InspectConsoleTail>,
    ): InspectionRecord {
        val stepName: String? = (event as? StepStarted)?.stepName
            ?: (event as? StepFinished)?.stepName
            ?: (event as? StepFailed)?.stepName
        val stepOrdinal: Long? = (event as? StepStarted)?.stepIndex?.toLong()
            ?: (event as? StepFinished)?.stepIndex?.toLong()
            ?: (event as? StepFailed)?.stepIndex?.toLong()
        val outcome: String = when (event) {
            // The result word is the domain's, and the column speaks the words the operator
            // already reads: success, unstable and failure. A word the domain does not know is
            // shown as it was written, because this column reports what the run carries and
            // guessing would report something else.
            is RunFinished -> when (RunOutcome.parse(event.outcome)) {
                RunOutcome.Success -> "success"
                RunOutcome.Unstable -> "unstable"
                is RunOutcome.Failure -> "failure"
                RunOutcome.Aborted -> "failure"
                null -> event.outcome
            }
            is StepFailed -> "failure"
            // A step's terminal event carries the step's verdict, so this row can say it. It used to
            // fall through to the event's kind, which made the column answer "StepFinished" to a
            // question about the outcome — the kind, not the result. A row written before the
            // verdict was published carries none, and falls back to the kind it always showed.
            is StepFinished -> event.outcome ?: event.kind
            else -> event.kind
        }
        val message: String? = (event as? StepFailed)?.message
        return InspectionRecord(
            stage = stepName,
            stepOrdinal = stepOrdinal,
            outcome = outcome,
            message = message,
            consoleTail = opId?.let { consoleTails[it.format()] } ?: InspectConsoleTail.NotRequested,
        )
    }

    /**
     * The rows the query's filters keep.
     *
     * No filter asked for means every row is kept: the query has one answer for the whole run.
     * With filters, a row is kept when it satisfies ANY of them — the filters narrow by different
     * dimensions of the same row, so demanding all of them at once would answer a question nobody
     * asked.
    */
    fun applyFilters(
        records: List<InspectionRecord>,
        q: CompiledInspectionQuery,
    ): List<InspectionRecord> {
        if (!q.failed && q.kind == null && q.stage == null && q.step == null) return records
        return records.filter { rec ->
            (q.failed && rec.outcome == "failure") ||
                (q.kind != null && kindOf(rec) == q.kind) ||
                (q.stage != null && rec.stage == q.stage) ||
                (q.step != null && rec.stepOrdinal == q.step)
        }
    }

    /**
     * The rows the context window keeps: every match, plus the rows around it.
     *
     * The window is measured in ROWS of the unfiltered run, so a match in the middle of a stage
     * brings the neighbouring rows with it — the rows are what the operator reads around the
     * failure, and they are the ones the filters had removed.
    */
    fun applyContextWindow(
        all: List<InspectionRecord>,
        matched: List<InspectionRecord>,
        q: CompiledInspectionQuery,
    ): List<InspectionRecord> {
        if (matched.isEmpty() || q.context == 0) return matched
        val selected = mutableSetOf<Int>()
        val matchedIndices = matched.mapNotNull { rec ->
            all.indexOfFirst { it === rec }
        }.toSet()
        matchedIndices.forEach { idx ->
            val from = (idx - q.context).coerceAtLeast(0)
            val to = (idx + q.context).coerceAtMost(all.size - 1)
            for (i in from..to) selected.add(i)
        }
        return selected.sorted().map { all[it] }
    }

    /** The dimension `--kind` filters on: the row's own outcome. */
    private fun kindOf(rec: InspectionRecord): String = rec.outcome
}
