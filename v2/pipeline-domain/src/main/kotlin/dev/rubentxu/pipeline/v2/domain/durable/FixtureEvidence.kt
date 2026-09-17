package dev.rubentxu.pipeline.v2.domain.durable

import dev.rubentxu.pipeline.v2.domain.PluginStepId

/**
 * XCA-2C — fixture execution state: what happened when the fixture ran.
 *
 * This is the RAW axis: did the fixture produce canonical evidence, or not?
 * It does NOT classify the relationship to expectations — that is the second axis
 * (see [StepExpectationRelation]).
 *
 * These states are mutually exclusive and exhaustive.
 */
sealed interface FixtureExecutionState {
    /** The fixture ran and produced canonical durable evidence (operation_journal rows). */
    data object HasEvidence : FixtureExecutionState

    /**
     * The fixture was declared but produced NO durable evidence.
     *
     * Distinguish from [HasEvidence] with an empty list: [HasEvidence] means the run
     * produced rows that were read; an empty list from `listForRun` is still [HasEvidence]
     * (a real run with zero observable operations, per the `Found(empty)` reader result).
     *
     * [NoEvidence] means the reader returned `RunNotFound` — no run was produced at all,
     * or the durable state was inaccessible.
     */
    data object NoEvidence : FixtureExecutionState

    /**
     * The fixture could not be executed (compilation error, infrastructure failure, etc.).
     * This is distinct from [NoEvidence]: something was attempted but did not complete.
     */
    data class ExecutionFailed(
        val reason: String,
    ) : FixtureExecutionState
}

/**
 * XCA-2C — expectation relation: how an EXPECTED StepKey relates to what was OBSERVED.
 *
 * This is the RELATIONSHIP axis. It is orthogonal to [FixtureExecutionState]:
 * a fixture can have evidence AND still have expected-but-not-executed steps (e.g., a step
 * under an unreachable branch).
 *
 * These states are with respect to a SINGLE expected StepKey.
 */
sealed interface StepExpectationRelation {
    val stepKey: PluginStepId

    /** The expected StepKey was executed (observed) in the run. */
    data class ExpectedAndExecuted(
        override val stepKey: PluginStepId,
        val status: OperationStatus,
    ) : StepExpectationRelation

    /** The expected StepKey was NOT executed (not observed) in the run. */
    data class ExpectedButNotExecuted(
        override val stepKey: PluginStepId,
    ) : StepExpectationRelation

    /**
     * A StepKey was executed (observed) but was NOT in the expectation set.
     *
     * This is not an error — it may be supporting evidence (e.g., a control step
     * the fixture author didn't list). But it should be visible.
     */
    data class ExecutedSupporting(
        override val stepKey: PluginStepId,
        val status: OperationStatus,
    ) : StepExpectationRelation

    /**
     * A StepKey was executed but its relationship to expectations cannot be determined.
     *
     * For example: the fixture produced evidence but the expectation set is unknown
     * because it wasn't declared.
     */
    data class ExecutedUnknown(
        override val stepKey: PluginStepId,
        val status: OperationStatus,
    ) : StepExpectationRelation
}

/**
 * XCA-2D — structured evidence provenance for a fixture's execution.
 *
 * Captures three dimensions:
 *   SOURCE     — where the evidence comes from (kind + path)
 *   EXPECTATION — what the fixture declares as its expected StepKeys
 *   VERIFICATION — the verification status (STATIC_CANDIDATE until executed)
 *
 * ## Provenance kinds
 *
 * - `PRODUCT_EXAMPLE`      — fixture in `examples/` directory
 * - `REGRESSION_CORPUS`   — fixture in `v2/compatibility/`
 * - `STRUCTURAL_CONTRACT` — synthetic test fixture (not from the corpus)
 *
 * ## Verification status lifecycle
 *
 * ```
 * STATIC_CANDIDATE   --[real execution]--> EXECUTED
 * STATIC_CANDIDATE   --[fixture removed]--> REMOVED
 * STATIC_CANDIDATE   --[fixture not executable]--> NOT_APPLICABLE
 * EXECUTED           --[stale journal]--> STALE_EVIDENCE (must not credit current run)
 * ```
 *
 * Per XCA-2D: "Every migrated claim starts as `STATIC_CANDIDATE`. Only real execution
 * may promote to `EXECUTED`."
 */
data class StructuredFixtureEvidence(
    val stepKey: PluginStepId,
    val provenance: FixtureProvenance,
    val expectation: FixtureExpectation,
    val verification: VerificationStatus,
)

data class FixtureProvenance(
    val kind: ProvenanceKind,
    val path: String,
)

enum class ProvenanceKind {
    /** Fixture in `examples/` directory — real-world usage example. */
    PRODUCT_EXAMPLE,
    /** Fixture in `v2/compatibility/` — regression corpus member. */
    REGRESSION_CORPUS,
    /** Synthetic fixture produced by the test harness — not from the corpus. */
    STRUCTURAL_CONTRACT,
}

data class FixtureExpectation(
    /** StepKeys the fixture author declared as expected. */
    val expectedStepKeys: Set<PluginStepId>,
    /** Whether this expectation was verified by real execution. */
    val expectationVerified: Boolean = false,
)

sealed interface VerificationStatus {
    /**
     * Static declaration only — not yet verified by real execution.
     * Per XCA-2D: "Every migrated claim starts as STATIC_CANDIDATE."
     */
    data object StaticCandidate : VerificationStatus

    /**
     * Verified by real execution against the installed CLI.
     */
    data class Executed(
        val runId: String,
        val observedStatus: OperationStatus,
    ) : VerificationStatus

    /**
     * The fixture was declared but could not be executed (not applicable).
     */
    data object NotApplicable : VerificationStatus

    /**
     * The fixture was removed from the corpus.
     */
    data object Removed : VerificationStatus

    /**
     * Evidence exists but is stale (belongs to a different run).
     */
    data class StaleEvidence(
        val evidenceRunId: String,
    ) : VerificationStatus
}

/**
 * XCA-2C — full reconciliation result for a fixture.
 *
 * Combines the two axes:
 *   1. Fixture execution state (did the fixture run?)
 *   2. Step expectation relations (how do observed/expected relate?)
 *
 * Use [reconcile] to produce this from observed StepKeys + expected StepKeys + execution state.
 */
data class FixtureReconciliation(
    val fixturePath: String,
    val executionState: FixtureExecutionState,
    val stepRelations: List<StepExpectationRelation>,
    val observedStepKeys: Set<PluginStepId>,
    val expectedStepKeys: Set<PluginStepId>,
) {
    /**
     * Computed sets for convenience.
     */
    val matched: Set<PluginStepId>
        get() = stepRelations.filterIsInstance<StepExpectationRelation.ExpectedAndExecuted>()
            .map { it.stepKey }.toSet()

    val expectedButNotExecuted: Set<PluginStepId>
        get() = stepRelations.filterIsInstance<StepExpectationRelation.ExpectedButNotExecuted>()
            .map { it.stepKey }.toSet()

    val extraObserved: Set<PluginStepId>
        get() = stepRelations.filterIsInstance<StepExpectationRelation.ExecutedSupporting>()
            .map { it.stepKey }.toSet()

    /**
     * True when E ⊆ O (every expected StepKey was observed).
     * This is the COVERAGE gate: a fixture with expected-but-not-executed steps fails this.
     */
    val isFullyCovered: Boolean
        get() = expectedButNotExecuted.isEmpty()
}

/**
 * XCA-2C — reconcile observed StepKeys with expected StepKeys.
 *
 * This is the pure reconciliation function. It takes:
 *   - the set of StepKeys the fixture declared as expected (E)
 *   - the set of StepKeys the reader observed in the run (O)
 *   - the fixture execution state
 *
 * And produces:
 *   - [FixtureReconciliation] with the two axes resolved
 *
 * ## Mathematics
 *
 * ```
 * E ∩ O  -> ExpectedAndExecuted    (matched)
 * E − O  -> ExpectedButNotExecuted (missing)
 * O − E  -> ExecutedSupporting     (extra)
 * O only -> ExecutedUnknown        (when E is unknown)
 * ```
 */
fun reconcile(
    fixturePath: String,
    executionState: FixtureExecutionState,
    expectedStepKeys: Set<PluginStepId>,
    observedStepKeys: Set<PluginStepId>,
    observedByStatus: Map<PluginStepId, OperationStatus>,
): FixtureReconciliation {
    val matched = expectedStepKeys intersect observedStepKeys
    val missing = expectedStepKeys - observedStepKeys
    val extra = observedStepKeys - expectedStepKeys

    val relations = buildList {
        matched.forEach { key ->
            add(StepExpectationRelation.ExpectedAndExecuted(key, observedByStatus[key]!!))
        }
        missing.forEach { key ->
            add(StepExpectationRelation.ExpectedButNotExecuted(key))
        }
        extra.forEach { key ->
            add(StepExpectationRelation.ExecutedSupporting(key, observedByStatus[key]!!))
        }
    }

    return FixtureReconciliation(
        fixturePath = fixturePath,
        executionState = executionState,
        stepRelations = relations,
        observedStepKeys = observedStepKeys,
        expectedStepKeys = expectedStepKeys,
    )
}

/**
 * XCA-2D — promote a [StructuredFixtureEvidence] from STATIC_CANDIDATE to EXECUTED.
 *
 * Only call this after confirmed real execution of the fixture through the installed CLI.
 * The [runId] and [status] come from the [JournalRunExecutionEvidenceReader].
 */
fun StructuredFixtureEvidence.promoteToExecuted(
    runId: String,
    status: OperationStatus,
): StructuredFixtureEvidence {
    check(verification is VerificationStatus.StaticCandidate) {
        "Only StaticCandidate evidence can be promoted to Executed. Current: $verification"
    }
    return copy(
        verification = VerificationStatus.Executed(runId = runId, observedStatus = status),
        expectation = expectation.copy(expectationVerified = true),
    )
}
