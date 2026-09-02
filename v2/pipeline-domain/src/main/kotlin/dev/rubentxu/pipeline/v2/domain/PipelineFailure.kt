package dev.rubentxu.pipeline.v2.domain

/**
 * Domain-typed failure carrier. Every typed outcome that represents failure
 * (see [StepOutcome.Failure], [RunOutcome.Failure]) carries one of these
 * instead of an unstructured string, a stack-trace blob, or a raw
 * exception.
 *
 * The [kind] uses [FailureKind] as the public classification; [message] is a
 * human-readable, log-safe description (no secret bytes); [cause] keeps the
 * original throwable for diagnostics but never substitutes for [kind] and
 * [message] when crossing semantic boundaries.
 *
 * This type is the single authority for failure representation in domain.
 * Callers must compose it at the point where the failure is first observed,
 * not invent ad-hoc string pairs at the call site.
 *
 * @see FailureKind
 * @see StepOutcome.Failure
 * @see RunOutcome.Failure
 */
data class PipelineFailure(
    val kind: FailureKind,
    val message: String,
    val cause: Throwable? = null,
) {
    init {
        require(message.isNotBlank()) { "PipelineFailure.message must not be blank" }
    }
}
