package dev.rubentxu.pipeline.v2.harness.model

import kotlinx.serialization.Serializable

/**
 * EVT-3 fundamental law: [PipelineOutcome] (what the run reported) is distinct from
 * [AcceptanceOutcome] (what the observer concluded about the observable protocol).
 * A run may finish SUCCESS and still fail acceptance. The harness never mutates
 * PipelineOutcome and never persists AcceptanceOutcome as a DomainEvent.
 */
enum class PipelineOutcome { SUCCESS, UNSTABLE, FAILURE }

enum class AcceptanceOutcome { PASSED, FAILED }

/** Terminal outcome expected by a contract (lowercase wire form, matching RunFinished.outcome). */
enum class ExpectedRunOutcome { SUCCESS, UNSTABLE, FAILURE;

    companion object {
        fun fromWire(s: String): ExpectedRunOutcome? =
            entries.firstOrNull { it.name.equals(s, ignoreCase = true) }
    }
}

/** Scope of a Before relation: within which group first must precede second. */
sealed interface RelationScope {
    /** Whole history: any occurrence of first before any occurrence of second. */
    data object Global : RelationScope

    /** Same ResourceRef subject (canonical text). */
    data object SameSubject : RelationScope

    /** Same typed composite key (e.g. parallel branch key "stage:branch"). */
    data class SameKey(val key: KeyKind) : RelationScope
}

enum class KeyKind { BRANCH, STAGE, STEP }

/**
 * Typed payload field match. Closed ADT over fields the current contracts actually
 * need; resolving a match decodes the DomainEvent and reads a named domain field.
 * No Map<String, Any>, no dynamic paths.
 */
@Serializable
sealed interface FieldMatch {
    @Serializable data class CatchBuildResult(val value: String) : FieldMatch
    @Serializable data class RetryOutcome(val value: String) : FieldMatch
    @Serializable data class AttemptNumber(val value: Int) : FieldMatch
    @Serializable data class BranchIndex(val value: Int) : FieldMatch
    @Serializable data class Outcome(val value: String) : FieldMatch
    @Serializable data class MessageContains(val fragment: String) : FieldMatch
    @Serializable data class StageIndex(val value: Int) : FieldMatch
    @Serializable data class StepIndex(val value: Int) : FieldMatch
}

/**
 * Structured, typed selector: event kind plus closed payload field matches.
 */
@Serializable
data class EventSelector(
    val kind: String,
    val where: List<FieldMatch> = emptyList(),
)

/**
 * Closed constraint ADT. Deliberately minimal (P4-EX 07-10 + universal laws).
 * `After` is intentionally absent: it is the inverse of [Before]; authority is not duplicated.
 */
sealed interface EventConstraint {
    data class Exactly(val selector: EventSelector, val count: Int) : EventConstraint
    data class Never(val selector: EventSelector) : EventConstraint
    data class Before(val first: EventSelector, val second: EventSelector, val scope: RelationScope) : EventConstraint
    data class TerminalOutcome(val expected: ExpectedRunOutcome) : EventConstraint
}

/**
 * A versioned, named protocol contract over one run's observable event history.
 */
data class EventContract(
    val version: Int,
    val name: String,
    val expect: ExpectedRunOutcome,
    val constraints: List<EventConstraint>,
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}
