package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.TypedStepOutput
import kotlin.ConsistentCopyVisibility

/**
 * LB-02 / G3-A4.3 — typed output carrier for `core.error` handlers.
 *
 * S2-A1 / G3-A4.3 amendment: the handler does NOT throw to signal a typed failure (handler
 * MUST NOT throw to signal a step failure per the Step Constitution); it returns this carrier,
 * which:
 *  - carries the canonical [PipelineFailure] (kind + message) — the typed failure authority,
 *  - carries the canonical [StepOutcome] (its single [StepOutcome.Failure] variant), which is
 *    `derived from` [failure] in a single, dedicated factory [CoreErrorOutput.from]; there is
 *    exactly one classifier.
 *  - implements [TypedStepOutput] so `CommonExecutionBoundary` projects `outcome` via the
 *    Step-agnostic `produced as? TypedStepOutput` check. The coordinator never branches on
 *    `"core.error"`.
 *
 * Invariant (preserved at construction): `outcome == StepOutcome.Failure(failure)`. The data
 * class field is `val`, not computed, but every construction path uses [CoreErrorOutput.from]
 * which enforces the invariant. Hand-rolled `CoreErrorOutput(...)` would NOT be authoritative;
 * callers must go through the factory.
 */
@ConsistentCopyVisibility
data class CoreErrorOutput internal constructor(
    val failure: PipelineFailure,
    override val outcome: StepOutcome,
) : TypedStepOutput {
    init {
        // Single invariant assertion: `outcome == StepOutcome.Failure(failure)`.
        // The handler MUST NOT construct a Success outcome here; an error Step's typed output
        // is always a Failure. This check is the ONE place this invariant is enforced.
        val expected = StepOutcome.Failure(failure)
        require(outcome == expected) {
            "CoreErrorOutput invariant violated: outcome($outcome) != StepOutcome.Failure(failure)"
        }
    }

    companion object {
        /**
         * The single authority for `core.error` outcome construction.
         *
         * Given the canonical [PipelineFailure], produces the typed carrier with the
         * `outcome == StepOutcome.Failure(failure)` invariant. The handler MUST go through
         * this factory. The classifier is in ONE place; no parallel authorities.
         */
        fun from(failure: PipelineFailure): CoreErrorOutput =
            CoreErrorOutput(failure, StepOutcome.Failure(failure))

        /**
         * Convenience factory from raw kind + message. Equivalent to:
         * ```
         * from(PipelineFailure(kind, message))
         * ```
         * Construction in ONE place; downstream code reads `output.failure` and `output.outcome`
         * without re-classifying.
         */
        fun of(kind: FailureKind, message: String): CoreErrorOutput =
            from(PipelineFailure(kind, message))
    }
}
