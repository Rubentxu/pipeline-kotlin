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
 * WU-RP-031 E4: effective-execution block extracted from [CanonicalDurableRunCoordinator]
 * (the Execute branch of the dispatch reconciliation). This is the ONLY code that invokes
 * the effective executor: beginOperation, the [StepExecutionBoundary]-wrapped
 * [CommonExecutionBoundary] call, the terminal journal write and the cursor advance
 * decision live here. Reuse/divergence/recover never reach this class.
 *
 * ## ADR-0103 D7 — this class is FRONTEND-NEUTRAL and does not own the replay cursor
 *
 * It once took a [dev.rubentxu.pipeline.v2.events.durable.ReplayCursorStore] and advanced
 * it, which made it un-enterable by any caller that does not own a canonical stage
 * position — and that is every frontend except the canonical traversal. The scripted
 * frontend was the concrete instance: `MainScriptedSupport` does not pass a
 * `ReplayCursorStore` at all, and `ScriptedFrontendRunner` already fixes `stageIndex = 0`
 * for scripted calls, so entering this class as it stood would have moved the run's
 * resume point to stage 0 on every scripted call.
 *
 * `ReplayCursor(runId, lastOpId, stageIndex, savedAt)` models **where the RUN resumes**,
 * not a property of an operation. A scripted call's identity is
 * `entryPoint / callSite / dynamicScope / ordinal`, which is not a stage position, so a
 * cursor for it could only be fabricated. The cursor therefore moved up to the canonical
 * traversal that owns stage position, and this class keeps only the durable fold of ONE
 * operation:
 *
 * ```text
 * beginOperation → execute through the boundary → terminal journal append → return
 * ```
 *
 * The consequence is deliberate: a frontend may execute and journal a durable operation
 * without owning a canonical stage position, and it MUST NOT move the run cursor.
 *
 * ## Why it returns [CommonExecutionResult] and not [StepOutcome]
 *
 * `encodedOutput` used to be persisted and then dropped: the journal kept it and the
 * caller received only the outcome, so any consumer that needed the typed value had to
 * re-derive it. Returning the carrier preserves the single execution→durability channel
 * this repository already has. Consumers that only need the outcome read
 * `execution.outcome`.
 *
 * Note the ordering law, unchanged: the terminal journal append happens BEFORE the
 * caller may advance the cursor (ReplayCursorStore's R-C mitigation).
 *
 * @see docs/v2/04-adrs/ADR-0103-one-replay-authority.md (D5, D7)
 */
internal class DurableStepExecutor(
    private val eventSink: EventSink,
    private val executionBoundary: CommonExecutionBoundary,
    private val journal: dev.rubentxu.pipeline.v2.events.durable.OperationJournal,
) {

    /**
     * Executes one admitted invocation and folds the result into the durable protocol.
     *
     * Returns the whole [CommonExecutionResult] — outcome AND encoded output — so the
     * caller keeps control of context and continuation (it receives data, not a side
     * effect) without the typed output being lost in the middle.
     */
    suspend fun executeAndJournal(
        operationId: String,
        fingerprint: Fingerprint,
        input: OperationInput,
        journaled: dev.rubentxu.pipeline.v2.domain.durable.DurableOperation?,
        prepared: PreparedExecution,
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

/**
 * Whether this terminal outcome counts as progress for the canonical traversal.
 *
 * Extracted from the executor when the cursor moved up (ADR-0103 D7) so that the
 * predicate is named, tested in one place, and cannot drift between the Execute and the
 * RecoverRunning advancement sites.
 *
 * ## This predicate is deliberately ASYMMETRIC with recovery — do not "fix" it here
 *
 * Historical behaviour, characterized before the cursor was relocated:
 *
 * ```text
 * Execute        → advance when  outcome !is StepOutcome.Failure   (Unstable ADVANCES)
 * RecoverRunning → advance when  outcome  is StepOutcome.Success    (Unstable does NOT)
 * ```
 *
 * That asymmetry is pre-existing and is NOT resolved by D7, which only changed who owns
 * the cursor. Execute keeps its `!is Failure` rule verbatim; recovery keeps its `is
 * Success` rule verbatim. Whether the two SHOULD agree is a separate question tracked on
 * its own work item; until then, changing either predicate would be an unevidenced
 * behaviour change disguised as a refactor.
 */
internal fun StepOutcome.advancesCanonicalCursor(): Boolean = this !is StepOutcome.Failure
