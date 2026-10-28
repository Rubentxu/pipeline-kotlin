package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.OperationOutput
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.events.EventSink
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * Effective-execution block extracted from [CanonicalDurableRunCoordinator]
 * (the Execute branch of the dispatch reconciliation). This is the ONLY code that invokes
 * the effective executor: beginOperation, the [StepExecutionBoundary]-wrapped
 * [CommonExecutionBoundary] call and the terminal journal write live here. Reuse/divergence/recover
 * never reach this class.
 * The cursor advance decision does NOT live here: it belongs to [CanonicalCursorAdvance],
 * which is the single authority for what advances and for the write that advances it.
 * This class is FRONTEND-NEUTRAL and does not own the replay cursor
 * It once took a [dev.rubentxu.pipeline.v2.events.durable.ReplayCursorStore] and advanced
 * it, which made it un-enterable by any caller that does not own a canonical stage
 * position — and that is every frontend except the canonical traversal. The scripted
 * frontend was the concrete instance: `MainScriptedSupport` does not pass a
 * `ReplayCursorStore` at all, and `ScriptedFrontendRunner` already fixes `stageIndex = 0`
 * for scripted calls, so entering this class as it stood would have moved the run's
 * resume point to stage 0 on every scripted call.
 * `ReplayCursor(runId, lastOpId, stageIndex, savedAt)` models **where the RUN resumes**,
 * not a property of an operation. A scripted call's identity is
 * `entryPoint / callSite / dynamicScope / ordinal`, which is not a stage position, so a
 * cursor for it could only be fabricated. The cursor therefore moved up to the canonical
 * traversal that owns stage position, and this class keeps only the durable fold of ONE
 * operation:
 * ```text
 * beginOperation → execute through the boundary → terminal journal append → return
 * ```
 * The consequence is deliberate: a frontend may execute and journal a durable operation
 * without owning a canonical stage position, and it MUST NOT move the run cursor.
 * ## Why it returns [CommonExecutionResult] and not [StepOutcome]
 * `encodedOutput` used to be persisted and then dropped: the journal kept it and the
 * caller received only the outcome, so any consumer that needed the typed value had to
 * re-derive it. Returning the carrier preserves the single execution→durability channel
 * this repository already has. Consumers that only need the outcome read
 * `execution.outcome`.
 * Note the ordering law, unchanged: the terminal journal append happens BEFORE the
 * caller may advance the cursor (ReplayCursorStore's mitigation).
*/
internal class DurableStepExecutor(
    private val eventSink: EventSink,
    private val executionBoundary: CommonExecutionBoundary,
    private val journal: dev.rubentxu.pipeline.v2.events.durable.OperationJournal,
) {

    /**
     * Executes one admitted invocation and folds the result into the durable protocol.
     * Returns the whole [CommonExecutionResult] — outcome AND encoded output — so the
     * caller keeps control of context and continuation (it receives data, not a side
     * effect) without the typed output being lost in the middle.
*/
    suspend fun executeAndJournal(
        operationId: String,
        fingerprint: Fingerprint,
        input: OperationInput,
        journaled: dev.rubentxu.pipeline.v2.domain.durable.DurableOperation?,
        prepared: PreparedRegistryExecution,
        runtime: CanonicalRuntimeContext,
        lifecycleContext: StepLifecycleContext,
    ): CommonExecutionResult {
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
        return executionResult
    }
}
