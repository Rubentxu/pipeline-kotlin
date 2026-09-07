package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.MilestoneAborted
import dev.rubentxu.pipeline.v2.events.MilestoneReached
import java.time.Instant
import java.util.UUID

/** Runtime dependencies required by the canonical milestone dispatcher. */
data class CanonicalMilestoneDispatchContext(
    val runId: String,
    val eventSink: EventSink,
)

/**
 * Dispatches canonical [CanonicalCoreStepCommand.Milestone] nodes.
 *
 * ML-R9 T-09 local single-run semantics (ADR-0046 §ML): there is no cross-build
 * coordination, so a milestone never aborts anything. A strictly increasing
 * ordinal emits the typed [MilestoneReached] event and succeeds. A non-monotonic
 * ordinal emits the typed [MilestoneAborted] event and returns [StepOutcome.Unstable]
 * (record-only, never a typed failure): the pipeline continues so downstream steps still
 * observe the pipeline state — per the SC-013-02 acceptance criterion and AGENTS.md
 * STEP SEMANTICS (Jenkins familiarity + per-step typed events).
 */
class CanonicalMilestoneNodeDispatcher {
    private var lastReachedOrdinal: Int? = null

    fun dispatch(command: CanonicalCoreStepCommand.Milestone, ctx: CanonicalMilestoneDispatchContext): StepOutcome {
        val previous = lastReachedOrdinal
        if (previous != null && command.ordinal <= previous) {
            ctx.eventSink.append(
                MilestoneAborted(
                    eventId = UUID.randomUUID().toString(),
                    runId = ctx.runId,
                    sequence = 0L,
                    occurredAt = Instant.now(),
                    ordinal = command.ordinal,
                    reason = "ordinal-already-reached (previous=$previous)",
                ),
            )
            // Record-only — local single-run semantics: never abort the run.
            return StepOutcome.Unstable
        }
        lastReachedOrdinal = command.ordinal
        ctx.eventSink.append(
            MilestoneReached(
                eventId = UUID.randomUUID().toString(),
                runId = ctx.runId,
                sequence = 0L,
                occurredAt = Instant.now(),
                ordinal = command.ordinal,
                label = command.label,
            ),
        )
        return StepOutcome.Success
    }
}
