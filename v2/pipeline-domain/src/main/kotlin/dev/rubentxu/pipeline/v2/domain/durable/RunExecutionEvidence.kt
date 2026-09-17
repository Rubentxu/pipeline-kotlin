package dev.rubentxu.pipeline.v2.domain.durable

import dev.rubentxu.pipeline.v2.domain.OperationId
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId

/**
 * XCA-2A — execution evidence contracts (INNER layer).
 *
 * These types express a general execution-domain need: *which Step invocations of a run
 * were actually executed, according to the durable authority*. They deliberately contain
 * NO certification concepts: no expected/observed, no verdict, no coverage, no provenance,
 * no `examples/` knowledge, no journal or persistence type.
 *
 * Hexagonal placement (see docs/v2/07-uat/XCA2A_JOURNAL_CHARACTERIZATION.md):
 *   - this file lives in `pipeline-domain`, which has NO project dependencies;
 *   - it MUST NOT import `dev.rubentxu.pipeline.v2.events.*` (the OperationJournal port)
 *     nor any concrete journal adapter, SQL/JDBC or schema API;
 *   - the adapter that knows `OperationJournal.listForRun` lives outside this module
 *     (`JournalRunExecutionEvidenceReader`).
 *
 * Reused identity types (NOT parallel Strings): [OperationId], [PluginStepId], [RunId].
 *
 * @param invocationId the logical invocation identity of the executed operation
 * @param stepKey the Step that was invoked
 * @param status the durable lifecycle status observed for that invocation
 */
data class ExecutedInvocationEvidence(
    val invocationId: OperationId,
    val stepKey: PluginStepId,
    val status: OperationStatus,
) {
    /**
     * Whether this invocation crossed the execution boundary.
     *
     * `observed <=> status != PENDING`, and it is sound because
     * [OperationStatus.transition] makes `PENDING` write-once: no transition returns an
     * operation to it after it has left (`RUNNING -> PENDING` and `terminal -> PENDING`
     * both fail by construction).
     *
     * Deliberately NOT `status.isTerminal`: `LOST` is terminal but `RUNNING` is not, and
     * BOTH prove execution started without terminal success. A terminal-only criterion
     * would silently drop `RUNNING` and every downstream coverage number would become
     * quietly optimistic.
     */
    val isObserved: Boolean get() = status.isTerminal
}

/**
 * Outcome of reading execution evidence for a run.
 *
 * [Found] with an empty list means "a real run that executed nothing observable".
 * [RunNotFound] means "no such run". They are distinct: collapsing them would let an
 * unknown run masquerade as a legitimately empty execution.
 */
sealed interface RunEvidenceReadResult {
    val runId: RunId

    data class Found(
        override val runId: RunId,
        val invocations: List<ExecutedInvocationEvidence>,
    ) : RunEvidenceReadResult

    data class RunNotFound(
        override val runId: RunId,
    ) : RunEvidenceReadResult
}

/**
 * Port: read the executed Step invocations of a run from the canonical durable authority.
 *
 * Contract:
 *  1. a [RunId] identifies exactly one execution;
 *  2. only invocations whose execution is evidenced by the durable authority are returned;
 *  3. the result is deterministic, in canonical durable execution order;
 *  4. it does NOT interpret certification and does NOT read `step-certification.yaml`;
 *  5. it does NOT read `examples/`;
 *  6. it does NOT use events as authority (events are observability);
 *  7. it does NOT re-execute anything;
 *  8. it does NOT modify the journal;
 *  9. it does NOT know XCA classification.
 *
 * Returns invocations, never a premature `Set<PluginStepId>`; the projection to a set is
 * the upper layer's business and would discard replay and identity information.
 */
interface RunExecutionEvidenceReader {
    fun read(runId: RunId): RunEvidenceReadResult
}
