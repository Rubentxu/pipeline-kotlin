package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue

/**
 * Generic atomic execution result for the closed execution seam (LB-02 / G3-A3).
 *
 * This is the single shape returned by [CommonExecutionBoundary.execute]; it carries BOTH
 * responsibilities produced by an effective step execution in one value, so the durable
 * coordinator never needs a second channel, a side-cache, or a temporal-coupling call to
 * retrieve a typed output.
 *
 * Two responsibilities, kept separate:
 *
 *  - [outcome]: the closed [StepOutcome] algebra (Success | Failure). What the durable
 *    coordinator maps to `OperationStatus` for the journal.
 *  - [encodedOutput]: the typed `O` produced by the Step handler, encoded through the
 *    Step's declared `outputCodec.encode(O)` and held under an [EncodedStepValue]
 *    (a value class over `String`; no `Any` crosses the seam). `null` when the step
 *    produces no durable-visible output (legacy paths and `Unit`-returning handlers).
 *
 * The seam NEVER persists either of these into a journal row. Per the durable spine:
 * **execution produces outcome + encoded output atomically; durability decides whether
 * and how to persist them.**
 *
 * Legacy execution paths return `CommonExecutionResult(outcome, encodedOutput = null)`
 * — backwards-behaviour-preserving. Registry-routed paths MAY populate [encodedOutput]
 * with a non-null value when the handler returns a non-`Unit` typed value.
 *
 * @see docs/v2/00-context/LB02_TYPED_OUTPUT_DECISION.md
 * @see <a href="AGENTS.md STEP CONSTITUTION">AGENTS.md STEP CONSTITUTION</a>
 */
data class CommonExecutionResult(
    val outcome: StepOutcome,
    val encodedOutput: EncodedStepValue? = null,
)
