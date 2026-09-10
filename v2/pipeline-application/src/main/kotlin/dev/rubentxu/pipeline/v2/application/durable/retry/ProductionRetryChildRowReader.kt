package dev.rubentxu.pipeline.v2.application.durable.retry

import dev.rubentxu.pipeline.v2.application.durable.ChildRowReader
import dev.rubentxu.pipeline.v2.application.durable.RetryIdentityFactory
import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.BlockStepNode
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.RetryChildRowSnapshot
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal

/**
 * RETRY-D D2.6 — production adapter for [ChildRowReader].
 *
 * Bridges [OperationJournal] to [dev.rubentxu.pipeline.v2.domain.durable.RetryReconciler]
 * via canonical child identities derived from [RetryIdentityFactory] (D2.2).
 *
 * Laws (ADR-0075 §7):
 *   - The reader is a *read-only port*. It does not mutate [OperationJournal].
 *   - The reader does NOT walk StepSpec / DSL IR. It consumes the canonical
 *     [BlockStepNode] body description provided at construction.
 *   - The reader does NOT branch on concrete StepKey — it is fully agnostic to
 *     the child Step semantics (no `if (child.pluginStepId == "core.sh") ...`).
 *   - Point lookups only. For each (attempt, childIndex) the reader derives the
 *     canonical child OpId and queries the journal. No journal scans.
 *   - The retry body is captured at construction time so the reader is reusable
 *     across multiple `childrenForControlOpId` calls (one per journal refresh).
 *
 * Status semantics (ADR-0075 §6):
 *   - journal.get returns null → snapshot.status = PENDING (Missing)
 *   - journal.get returns RUNNING → snapshot.status = RUNNING (InFlight)
 *   - journal.get returns a terminal success (SUCCEEDED) → snapshot.status = SUCCEEDED
 *   - journal.get returns a terminal failure (FAILED / ABORTED) → snapshot.status = FAILED
 *   - journal.get returns DIVERGENT → snapshot.status = DIVERGENT (terminal — surfaces divergence)
 *
 * The reader never collapses Missing into any other state — that is the W1/W2/W3/W4
 * decision boundary.
 */
class ProductionRetryChildRowReader(
    private val operationJournal: OperationJournal,
    private val identityFactory: RetryIdentityFactory,
    private val retryBody: BlockStepNode,
) : ChildRowReader {

    override fun childrenForControlOpId(
        controlOpId: String,
        runId: String,
        stageIndex: Int,
        stepIndex: Int,
        parentBodyPath: List<BlockSegment>,
        maxAttempts: Int,
    ): List<RetryChildRowSnapshot> {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1, got $maxAttempts" }
        val snapshots = mutableListOf<RetryChildRowSnapshot>()
        for (attempt in 1..maxAttempts) {
            for ((childIndex, child) in retryBody.body.withIndex()) {
                val childOpId = identityFactory.childOperationId(
                    runId = runId,
                    stageIndex = stageIndex,
                    stepIndex = stepIndex,
                    parentBodyPath = parentBodyPath,
                    attempt = attempt,
                    childIndex = childIndex,
                    childPluginStepId = child.pluginStepId,
                )
                val durable = operationJournal.get(childOpId)
                val (status, capturedFingerprint) = when {
                    durable == null -> OperationStatus.PENDING to null
                    else -> durable.status to durable.fingerprint
                }
                snapshots += RetryChildRowSnapshot(
                    attempt = attempt,
                    childIndex = childIndex,
                    status = status,
                    fingerprint = capturedFingerprint,
                )
            }
        }
        return snapshots
    }
}
