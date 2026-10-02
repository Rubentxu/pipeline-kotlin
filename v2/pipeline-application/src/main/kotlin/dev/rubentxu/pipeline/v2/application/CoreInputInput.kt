package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.TypedStepOutput

/**
 * Typed input of `core.input` (RP6-B / WU-092).
 *
 * The WHOLE payload is encoded; the engine does not probe individual fields, and
 * the codec is not required to round-trip only some of them. Not annotated
 * `@Serializable` on purpose: `pipeline-application` does not apply the
 * kotlinx-serialization compiler plugin, so the codec in [CoreInputStep] is
 * written out explicitly, exactly as `CoreLockInput` is.
 *
 * ## Surface and its Jenkins source
 *
 * Derived from the official `input` step reference
 * (<https://www.jenkins.io/doc/pipeline/steps/workflow-durable-task-step/>).
 * `withId` and the permission/role parameters are deliberately absent; the reasons
 * are in `docs/v2/07-uat/SPEC_WU092_INPUT.md` §1. `timeoutSeconds` is an addition
 * and not a Jenkins parameter: Jenkins bounds `input` by wrapping it in `timeout`,
 * and this runner already bounds a suspending wait with the enclosing scope budget
 * (SPEC_WU092_INPUT.md §3.4), so an author-level bound here is the same fact stated
 * in the place the author looks.
 */
data class CoreInputInput(
    val message: String,
    val ok: String = "Proceed",
    val submitter: String? = null,
    val id: String? = null,
    val timeoutSeconds: Int? = null,
)

/**
 * A human answer. Closed: there are exactly two possible answers, and the pipeline
 * author cannot express a third one. [message] is the answer's own free text
 * (Jenkins returns `input`'s value to the caller), not the question.
 */
sealed interface InputDecision {
    data class Proceed(val submitter: String?, val message: String?) : InputDecision
    data class Abort(val submitter: String?, val message: String?) : InputDecision
}

/**
 * Why there is no answer. Closed, and every case carries the evidence needed to
 * act on it: how long we waited, whether we were cancelled, or what made the
 * question unaskable.
 *
 * A malformed response and a lost race are deliberately NOT cases here. A
 * malformed file is ignored and the wait continues; a lost race is a filesystem
 * fact on the answering side. Neither can be an outcome of the Step
 * (SPEC_WU092_INPUT.md §2, D4).
 */
sealed interface InputDenialReason {
    data class TimedOut(val waitedMillis: Long) : InputDenialReason
    data object Cancelled : InputDenialReason
    data class Unanswerable(val diagnostic: String) : InputDenialReason
}

/**
 * The Step's result. [decision] and [denial] are mutually exclusive by
 * construction, and [bodyRan] is true if and only if a `Proceed` was obtained.
 *
 * [outcome] carries what the body returned, so a body failure is the run's failure
 * and a body success is the run's success. It is null exactly when the body did
 * not run, which is the same condition [bodyRan] states — a second fact about the
 * same event, kept because the journal reader needs the outcome and the flag
 * answers a different question (did we run at all).
 */
data class CoreInputOutput(
    val requested: String,
    val decision: InputDecision? = null,
    val denial: InputDenialReason? = null,
    val bodyRan: Boolean = false,
    val bodyOutcome: StepOutcome? = null,
) : TypedStepOutput {
    /**
     * The canonical Step outcome, projected by `RegistryExecutionBoundary` through the
     * Step-agnostic `(produced as? TypedStepOutput)?.outcome`.
     *
     * This is NOT a convenience alias: it is the only channel by which an unanswered or
     * refused question can reach the run's outcome. `core.input` can end without ever
     * running a body, so the body engine has no outcome to propagate and the handler's
     * return value alone would default to Success — a run whose permission was DENIED,
     * TIMED OUT or ABORTED would report success and exit 0. Measured on the real CLI
     * before this carrier existed (WU-092 G4).
     */
    override val outcome: StepOutcome get() = runOutcome()

    /**
     * The run outcome this Step produces. Pure interpretation of the facts above, so
     * no caller can disagree about what an Abort or a denial means.
     */
    fun runOutcome(): StepOutcome = when {
        bodyOutcome != null -> bodyOutcome
        decision is InputDecision.Abort -> StepOutcome.Failure(
            PipelineFailure(
                kind = FailureKind.USER,
                message = "core.input: the run was not continued ($requested)",
            ),
        )
        denial != null -> StepOutcome.Failure(
            PipelineFailure(
                kind = when (denial) {
                    is InputDenialReason.TimedOut -> FailureKind.TIMEOUT
                    is InputDenialReason.Cancelled -> FailureKind.INFRASTRUCTURE
                    is InputDenialReason.Unanswerable -> FailureKind.USER
                },
                message = "core.input: no answer for '$requested' (${denialDiscriminant(denial!!)})",
            ),
        )
        else -> StepOutcome.Failure(
            PipelineFailure(
                kind = FailureKind.UNKNOWN,
                message = "core.input: neither a decision nor a denial for '$requested'",
            ),
        )
    }
}

/** The single discriminant used by both the wire and the run outcome. */
internal fun denialDiscriminant(reason: InputDenialReason): String = when (reason) {
    is InputDenialReason.TimedOut -> "TIMED_OUT"
    is InputDenialReason.Cancelled -> "CANCELLED"
    is InputDenialReason.Unanswerable -> "UNANSWERABLE"
}

/** A declaration this Step refuses to ask, with the reason it refuses. */
sealed interface InputInputError {
    /** The operator-facing text, on the interface so every caller reads one thing. */
    val diagnostic: String

    /** The question is empty, so it asks nothing. */
    data object BlankMessage : InputInputError {
        override val diagnostic: String get() = "message must not be blank"
    }

    /** The affirmative label is empty, so nobody could recognise it. */
    data object BlankOk : InputInputError {
        override val diagnostic: String get() = "ok must not be blank"
    }

    data class NegativeTimeout(val seconds: Int) : InputInputError {
        override val diagnostic: String get() = "timeoutSeconds must not be negative, got $seconds"
    }
}

/** The pure decision: what will be asked, and for how long this invocation may wait. */
sealed interface InputIntentResolution {
    /** [waitMillis] is the tighter of the author timeout and the scope budget; null means unbounded. */
    data class Resolved(val waitMillis: Long?) : InputIntentResolution
    data class Rejected(val error: InputInputError) : InputIntentResolution
}

/**
 * Decides the question and its bound, in one pure place, once.
 *
 * The author declares; the scope budget bounds. Nothing here performs I/O, reads a
 * clock or inspects the environment, so the rule cannot be applied twice with
 * different answers, and it is testable without a coordinator or a filesystem.
 */
fun inputIntentOf(
    input: CoreInputInput,
    budget: ExecutionBudget,
): InputIntentResolution = when {
    input.message.isBlank() -> InputIntentResolution.Rejected(InputInputError.BlankMessage)
    input.ok.isBlank() -> InputIntentResolution.Rejected(InputInputError.BlankOk)
    input.timeoutSeconds != null && input.timeoutSeconds < 0 ->
        InputIntentResolution.Rejected(InputInputError.NegativeTimeout(input.timeoutSeconds))
    else -> InputIntentResolution.Resolved(
        budget.bound(input.timeoutSeconds?.toLong()?.times(1_000L)),
    )
}
