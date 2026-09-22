package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.OperationOutput
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursorStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * WU-RP-031 E4: effective-execution block extracted from [CanonicalDurableRunCoordinator]
 * (the Execute branch of the dispatch reconciliation). This is the ONLY code that invokes
 * the effective executor: beginOperation, the [StepExecutionBoundary]-wrapped
 * [CommonExecutionBoundary] call, the terminal journal write and the cursor advance
 * decision live here. Reuse/divergence/recover never reach this class.
 */
internal class DurableStepExecutor(
    private val eventSink: EventSink,
    private val executionBoundary: CommonExecutionBoundary,
    private val journal: dev.rubentxu.pipeline.v2.events.durable.OperationJournal,
    private val cursorStore: ReplayCursorStore,
) {

    /**
     * Executes one admitted invocation and folds the result into the durable protocol.
     * Returns the terminal [StepOutcome]; the coordinator keeps control of context and
     * continuation (it receives the outcome as data, not as a side effect).
     */
    suspend fun executeAndJournal(
        operationId: String,
        fingerprint: Fingerprint,
        input: OperationInput,
        journaled: dev.rubentxu.pipeline.v2.domain.durable.DurableOperation?,
        prepared: PreparedExecution,
        runtime: CanonicalRuntimeContext,
        lifecycleContext: StepLifecycleContext,
        runIdValue: String,
        stageIndex: Int,
    ): StepOutcome {
        if (journaled == null) {
            journal.beginOperation(operationId, 1, fingerprint.hex, Json.encodeToString(input))
        }

        val executionStartMs = System.currentTimeMillis()
        val executionResult = StepExecutionBoundary(eventSink).execute(lifecycleContext) {
            executionBoundary.execute(prepared, runtime)
        }
        val outcome = executionResult.outcome
        val executionEndMs = System.currentTimeMillis()
        journal.append(
            RerunOperation(
                id = operationId,
                fingerprint = fingerprint,
                input = input,
                output = executionResult.encodedOutput?.let {
                    OperationOutput(
                        result = JsonPrimitive(it.value),
                        durationMs = executionEndMs - executionStartMs,
                        finishedAt = executionEndMs,
                    )
                },
                status = outcome.toOperationStatus(),
                attempt = 1,
            ),
        )
        if (outcome !is StepOutcome.Failure) cursorStore.advance(runIdValue, operationId, stageIndex)
        return outcome
    }
}
