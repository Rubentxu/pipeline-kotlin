package dev.rubentxu.pipeline.v2.events.evidence

import dev.rubentxu.pipeline.v2.domain.OperationId
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.durable.ExecutedInvocationEvidence
import dev.rubentxu.pipeline.v2.domain.durable.RunEvidenceReadResult
import dev.rubentxu.pipeline.v2.domain.durable.RunExecutionEvidenceReader
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal

/**
 * XCA-2A/A5 — adapter over the canonical durable authority.
 *
 * This is the ONLY place that knows `OperationJournal.listForRun`. Upper XCA layers depend
 * on [RunExecutionEvidenceReader] and never on the journal backend (LAW-001).
 *
 * Deliberately mechanical. It reads evidence and does NOT classify it:
 *  - it does NOT filter PENDING;
 *  - it does NOT decide what counts as certification;
 *  - it does NOT know expected/observed, verdicts, coverage or provenance.
 *
 * Semantics of "executed" live on
 * [ExecutedInvocationEvidence.isObserved] in the domain, precisely so that consumers
 * cannot re-derive them (and cannot regress to `status.isTerminal`).
 *
 * Returning ALL persisted operations preserves information the upper layer needs to
 * distinguish:
 *   - persisted but never entered execution  (a PENDING row, isObserved = false)
 *   - no durable row at all                  (absent from the returned list)
 * Filtering to `O` is XCA-2C's job: `invocations.filter { it.isObserved }`.
 *
 * Ordering is the canonical durable execution order, inherited from
 * `listForRun` (`created_at` ASC). The adapter does not re-sort.
 */
class JournalRunExecutionEvidenceReader(
    private val journal: OperationJournal,
) : RunExecutionEvidenceReader {

    override fun read(runId: RunId): RunEvidenceReadResult {
        val operations = journal.listForRun(runId.value)
        return RunEvidenceReadResult.Found(
            runId = runId,
            invocations = operations.map { operation ->
                ExecutedInvocationEvidence(
                    invocationId = OperationId(operation.id),
                    stepKey = PluginStepId(operation.input.stepId),
                    status = operation.status,
                )
            },
        )
    }
}
