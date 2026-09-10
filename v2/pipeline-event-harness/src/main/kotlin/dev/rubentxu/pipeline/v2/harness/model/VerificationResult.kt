package dev.rubentxu.pipeline.v2.harness.model

/**
 * Result of verifying one contract against one history. Exceptions are NOT used
 * for contract failure: they are reserved for corrupt contracts, codec incompatibility
 * and internal verifier invariants.
 */
sealed interface VerificationResult {
    data object Valid : VerificationResult

    data class Invalid(val violations: List<EventViolation>) : VerificationResult
}

/**
 * Bounded counterexample: violated rule + minimal relevant window, never the full
 * 200-event dump. 80/20 slicing: violated selectors + nearest causally related
 * events + small bounded context.
 */
data class EventViolation(
    val rule: ViolationRule,
    val message: String,
    val relevantTrace: List<TraceEntry>,
    val missing: String? = null,
    val unexpected: String? = null,
)

enum class ViolationRule {
    EXACTLY_NOT_SATISFIED,
    NEVER_VIOLATED,
    BEFORE_VIOLATED,
    TERMINAL_OUTCOME_MISMATCH,
    LAW_FINISHED_WITHOUT_STARTED,
    LAW_PARENT_COMPLETED_BEFORE_CHILD,
    LAW_CONTRADICTORY_TERMINAL,
}

/** One line of a counterexample trace: position in the history + short typed summary. */
data class TraceEntry(
    val sequence: Long,
    val kind: String,
    val summary: String,
)
