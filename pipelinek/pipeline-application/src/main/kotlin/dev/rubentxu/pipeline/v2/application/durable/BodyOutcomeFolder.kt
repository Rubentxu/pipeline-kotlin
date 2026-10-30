package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CoreWorkflowControlInput
import dev.rubentxu.pipeline.v2.domain.CatchErrorBuildResult
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcomeFold

/**
 * What a fold says the scope OBSERVED, as a value the dispatch publishes.
 *
 * The emission carries the PAYLOAD of the event and nothing else: no event id,
 * no instant, no run id. Those belong to the publisher, because only the
 * publisher owns the clock and the id source. A fold that carried them would
 * have to read a clock, and a rule that reads a clock is not a rule.
 *
 * The two cases map one-to-one onto the two durable events the legacy
 * projection published:
 *  - [CaughtFailure] is published as `events.CatchErrorTriggered`.
 *  - [StageDegraded] is published as `events.StageMarkedUnstable`.
 *
 * The vocabulary is CLOSED. The legacy published those two events from two
 * different places (the stage-continuation walk and the marker node), and a
 * third place would have been a third authority on what a control scope does.
 */
internal sealed interface BodyFoldEmission {

    /**
     * The scope's body failed and the scope caught it.
     *
     * [buildResult] is what the scope does with the caught failure and
     * [stageResult] is what the enclosing stage sees afterwards. Both are the
     * DECLARED values: the legacy published them from the overlay the author
     * had declared, and the declaration is the only authority for them.
     */
    data class CaughtFailure(
        val buildResult: CatchErrorBuildResult,
        val stageResult: CatchErrorBuildResult,
        val message: String?,
    ) : BodyFoldEmission

    /**
     * The scope degraded its stage.
     *
     * The message is never absent: the event carries a non-nullable text, and
     * a scope that declared no message publishes the empty string rather than
     * a `null` the event cannot hold. That is the legacy's own spelling, and
     * the one the durable reader already expects.
     */
    data class StageDegraded(val message: String) : BodyFoldEmission
}

/**
 * The outcome of one fold: what the scope's outcome BECOMES, and what the scope
 * publishes about it.
 *
 * Both halves travel together because they are one fact. A scope that changes
 * the outcome without saying why produces an unexplained outcome, and a scope
 * that says something without changing the outcome produces an announcement.
 */
internal data class BodyFoldResult(
    /** The scope's outcome, after the fold. */
    val outcome: StepOutcome,
    /** What the scope publishes, in publication order. */
    val emissions: List<BodyFoldEmission>,
)

/**
 * The fold: how a control scope turns its body's typed outcome into the
 * scope's own outcome.
 *
 * ## Why this exists
 * A control scope (`catchError` / `warnError`) had NO owner for its semantics.
 * The compiler translated it into a shell script, the runtime read that
 * script, and the two effects the scope owned — absorbing a failure and
 * decorating the output — were implemented as markers scattered through the
 * translated artifact and a walk over a context overlay. The scope's meaning
 * lived in three places and in none of them as a rule.
 *
 * This is that rule, in one place, as a pure function.
 *
 * ## The two folds
 * [BodyOutcomeFold.CONTAINMENT] absorbs the body's failure and the scope ends
 * with the outcome the author DECLARED. [BodyOutcomeFold.OUTPUT_DECORATION]
 * always ends UNSTABLE and publishes a warning about what it saw.
 *
 * ## What the legacy did, and what this preserves
 * The legacy decided the continuation by walking the overlay chain when a
 * failure reached the stage, and it published `CatchErrorTriggered` there.
 * The observable facts that walk produced are preserved here:
 *  - a scope whose body SUCCEEDED publishes nothing;
 *  - a scope whose body FAILED publishes `CatchErrorTriggered` with the
 *    DECLARED `buildResult` and `stageResult`;
 *  - the DECLARED `buildResult` decides the continuation, and `stageResult`
 *    travels into the published event without changing that decision.
 *
 * The difference is WHERE the decision happens: it happens inside the scope,
 * as the scope's own outcome, instead of at the stage boundary over a context
 * overlay that the scope had to push and pop with marker nodes.
 */
internal object BodyOutcomeFolder {

    /**
     * Folds one body's outcome into the scope's outcome.
     *
     * Pure: no clock, no events, no I/O. The publications are RETURNED, not
     * performed, so the rule can be stated as a rule.
     *
     * @param fold which declared effect this scope owns.
     * @param contract the scope's declared contract (the results it declared).
     * @param bodyOutcome the outcome the scope's body produced.
     */
    fun fold(
        fold: BodyOutcomeFold,
        contract: CoreWorkflowControlInput,
        bodyOutcome: StepOutcome,
    ): BodyFoldResult = when (fold) {
        BodyOutcomeFold.CONTAINMENT -> contain(contract, bodyOutcome)
        BodyOutcomeFold.OUTPUT_DECORATION -> decorate(contract, bodyOutcome)
    }

    /**
     * CONTAINMENT: the scope absorbs the body's failure and ends with the
     * outcome the author declared.
     *
     * The body's own SUCCESS and UNSTABLE pass through untouched and publish
     * nothing: a scope that caught nothing has nothing to announce. That is
     * the legacy's own rule — the legacy published `CatchErrorTriggered` only
     * from the failure walk — and it is what makes a `catchError` that catches
     * nothing leave the run STABLE.
     *
     * A failure is re-thrown outward when the declared `buildResult` is
     * `FAILURE`: the scope did not absorb it, so the SAME failure continues
     * outward, unchanged, to the next enclosing scope or to the run.
     */
    private fun contain(
        contract: CoreWorkflowControlInput,
        bodyOutcome: StepOutcome,
    ): BodyFoldResult = when (bodyOutcome) {
        StepOutcome.Success -> BodyFoldResult(StepOutcome.Success, emptyList())
        StepOutcome.Unstable -> BodyFoldResult(StepOutcome.Unstable, emptyList())
        is StepOutcome.Failure -> {
            val emission = BodyFoldEmission.CaughtFailure(
                buildResult = contract.buildResult,
                stageResult = contract.stageResult,
                message = contract.message,
            )
            val outcome = when (contract.buildResult) {
                CatchErrorBuildResult.Success -> StepOutcome.Success
                CatchErrorBuildResult.Unstable -> StepOutcome.Unstable
                // Not absorbed: the SAME failure continues outward.
                CatchErrorBuildResult.Failure -> bodyOutcome
            }
            BodyFoldResult(outcome, listOf(emission))
        }
    }

    /**
     * OUTPUT_DECORATION: the scope always ends UNSTABLE and publishes a
     * warning about what it saw.
     *
     * The scope's body may have succeeded, degraded, or failed: the
     * decoration does not depend on which. That is the legacy's rule too — the
     * `StageMarkedUnstable` marker ran after the scope whatever the scope had
     * done — and it is why a `warnError` degrades its stage even when every
     * step inside it succeeded.
     *
     * A body that FAILED publishes BOTH: the caught failure, because the scope
     * saw one, and the degradation, because the scope always degrades. The
     * failure is absorbed: an output decoration does not re-throw, which is
     * what separates it from containment.
     */
    private fun decorate(
        contract: CoreWorkflowControlInput,
        bodyOutcome: StepOutcome,
    ): BodyFoldResult {
        val degraded = BodyFoldEmission.StageDegraded(contract.message.orEmpty())
        return when (bodyOutcome) {
            StepOutcome.Success -> BodyFoldResult(StepOutcome.Unstable, listOf(degraded))
            StepOutcome.Unstable -> BodyFoldResult(StepOutcome.Unstable, listOf(degraded))
            is StepOutcome.Failure -> BodyFoldResult(
                StepOutcome.Unstable,
                listOf(
                    BodyFoldEmission.CaughtFailure(
                        buildResult = contract.buildResult,
                        stageResult = contract.stageResult,
                        message = contract.message,
                    ),
                    degraded,
                ),
            )
        }
    }
}
