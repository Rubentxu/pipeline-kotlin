package dev.rubentxu.pipeline.v2.application.durable.retry

import dev.rubentxu.pipeline.v2.application.durable.FileBasedRetryControlJournal
import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.RetryControlIdentity
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationInput
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciler
import dev.rubentxu.pipeline.v2.domain.step.AttemptSegment
import dev.rubentxu.pipeline.v2.domain.step.BodyInvocationContext
import dev.rubentxu.pipeline.v2.domain.step.BodyInvoker
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.RetryAttemptFinished
import dev.rubentxu.pipeline.v2.events.RetryAttemptStarted
import java.time.Instant
import java.util.UUID

/**
 * Application-internal authority for the retry aggregate (WU-LPR-302 Phase 2,
 * 2026-09-18; ADR-0075).
 *
 * ## What this owns
 *
 * `RetryEngine` owns the **loop**: reconcile -> should-invoke -> build attempt
 * context -> `BodyInvoker.invoke(...)` -> fold outcome -> persist transition.
 * It does NOT iterate body children; it does NOT call the canonical
 * `invokeBodyChildren` directly. The body-child traversal lives in the
 * canonical coordinator's shared loop; the engine reaches that loop through the
 * `BodyInvoker` port (B11 / W2) so the canonical "one body-child traversal
 * authority" law is preserved.
 *
 * ## What it does NOT own
 *
 * - The `BodyRef` lifecycle (`open` / `close`): the canonical coordinator
 *   remains the owner; the engine only invokes the registered body through
 *   `BodyInvoker.invoke`.
 * - Event shape beyond what the retry aggregate itself owns
 *   ([RetryAttemptStarted]). The canonical run / stage / step events live in
 *   the coordinator.
 * - The legacy compat retry loop (no `retryControlJournal`): the canonical
 *   coordinator keeps its inline legacy loop bit-equivalent for callers that
 *   have not yet opted into the durable retry aggregate.
 *
 * ## Plan-only invariant
 *
 * `plan()` is the pure read path and is unchanged in semantics from
 * [RetryReconciliationDriver]: read the journal, build the input, delegate to
 * [RetryReconciler]. The engine mutates ONLY through `journal.beginAttempt`
 * and `journal.updateStatus`, and only after a plan() emits a mutation
 * decision. The single-writer law (ADR-0075 §11) is preserved: this engine is
 * the only place that mutates [FileBasedRetryControlJournal] for the retry
 * aggregate.
 *
 * ## Body invocation law
 *
 * The engine invokes the body through `bodyInvoker.invoke(bodyRef,
 * BodyInvocationContext(attempt = AttemptSegment(N, key)))`. The runner
 * registered by the canonical coordinator (Phase 1b) projects `attempt` onto
 * the per-attempt deterministic `bodyPath` segment, so each attempt owns a
 * distinct journal OpId. The engine is `StepKey`-blind: it does not branch on
 * concrete `PluginStepId` and does not call `invokeBodyChildren` directly.
 *
 * ## Re-entry port discipline
 *
 * `RetryEngine` depends only on the public [BodyInvoker] port. It does NOT
 * declare or require any secondary `BodyExecutor`, `BranchInvoker`,
 * `BodyRunnerPort`, or `BlockExecutor` collaborator. The port count for body
 * re-entry is exactly ONE across the application (B10 / W1d-W2 fitness law).
 */
class RetryEngine(
    private val journal: FileBasedRetryControlJournal,
    private val eventSink: EventSink,
    private val bodyInvoker: BodyInvoker,
    private val identity: RetryControlIdentity,
    private val controlOpId: String,
    private val parentBodyPath: List<BlockSegment>,
    private val fingerprint: Fingerprint,
    private val maxAttempts: Int,
    private val runId: RunId,
    private val stageIndex: Int,
    private val stepIndex: Int,
    private val blockId: StepId,
    private val blockPluginStepId: PluginStepId,
) {

    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1, got $maxAttempts" }
        require(runId.value.isNotBlank()) { "runId must not be blank" }
    }

    /**
     * Project a retry attempt's terminal durable transition.
     *
     * Persists the terminal status via the control journal and emits
     * [RetryAttemptFinished] only when the transition actually occurred
     * (RUNNING -> FAILED / RUNNING -> SUCCEEDED). Re-terminaling an attempt
     * that is already in the requested terminal state is a no-op: no event.
     *
     * Replay paths (ReuseSuccess / ReuseFailure / CloseSuccessFromChild) do
     * not route through here, so replay never fabricates extra events.
     *
     * This helper is part of the retry aggregate's authority and is the
     * single place that translates durable status transitions into the
     * [RetryAttemptFinished] event. The canonical coordinator's previous
     * `persistAttemptTerminalTransition` has been replaced by this engine.
     */
    private fun persistTerminalTransition(attempt: Int, status: OperationStatus) {
        val priorStatus = journal.readState(
            controlOpId = controlOpId,
            runId = runId.value,
            stageIndex = stageIndex,
            stepIndex = stepIndex,
            parentBodyPath = parentBodyPath,
            maxAttempts = maxAttempts,
            currentFingerprint = fingerprint,
        ).controlRows.firstOrNull { it.attempt == attempt }?.status
        journal.updateStatus(controlOpId, attempt, status, fingerprint)
        val transitioned = priorStatus == null || priorStatus != status
        if (!transitioned) return
        val outcomeText = when (status) {
            OperationStatus.SUCCEEDED -> "succeeded"
            OperationStatus.FAILED -> "failed"
            else -> error("persistTerminalTransition requires a terminal status, got $status")
        }
        eventSink.append(
            RetryAttemptFinished(
                eventId = UUID.randomUUID().toString(),
                runId = runId.value,
                sequence = 0L,
                occurredAt = Instant.now(),
                attemptNumber = attempt,
                maxAttempts = maxAttempts,
                stepName = blockId.value,
                stepType = blockPluginStepId.value,
                stageIndex = stageIndex,
                stepIndex = stepIndex,
                outcome = outcomeText,
            ),
        )
    }

    /**
     * Pure plan-only read of durable state. Equivalent to
     * [RetryReconciliationDriver.plan]. Kept on the engine so the dispatch
     * loop below is one collaborator; the planner remains pure.
     */
    fun plan(): RetryReconciliationDecision {
        val state = journal.readState(
            controlOpId = controlOpId,
            runId = runId.value,
            stageIndex = stageIndex,
            stepIndex = stepIndex,
            parentBodyPath = parentBodyPath,
            maxAttempts = maxAttempts,
            currentFingerprint = fingerprint,
        )
        return RetryReconciler.reconcile(
            RetryReconciliationInput(
                controlIdentity = identity,
                maxAttempts = maxAttempts,
                currentFingerprint = fingerprint,
                controlRows = state.controlRows,
                childrenByAttempt = state.childrenByAttempt,
                preControlChildren = state.preControlChildren,
            ),
        )
    }

    /**
     * Execute the retry aggregate against [bodyRef].
     *
     * The loop is bounded by [maxAttempts] + 1 ticks to guarantee progress.
     * The engine never reaches the canonical `invokeBodyChildren` directly;
     * it re-enters the body through the [BodyInvoker] port the coordinator
     * already opened under [bodyRef], passing the per-attempt
     * [BodyInvocationContext] so the runner projects the attempt onto a
     * distinct bodyPath segment.
     *
     * Outcomes are folded into [StepOutcome]:
     *  - `ReuseSuccess` / `CloseSuccessFromChild` -> [StepOutcome.Success]
     *  - `ReuseFailure` -> [StepOutcome.Failure] with [FailureKind.SCRIPT]
     *  - `RejectDivergence` -> [StepOutcome.Failure] with
     *    [FailureKind.REPLAY_COMPATIBILITY]
     *  - `ScheduleAttempt` / `ResumeAttempt` -> invoke, fold, persist
     *    transition, advance or return
     *  - `AdvanceAfterFailure` -> persist next attempt RUNNING, loop
     *
     * The body's typed outcome (Success / Failure / Unstable) decides the
     * engine's fold; the engine itself is `StepKey`-blind (no `when(stepKey)`,
     * no `if (pluginStepId == ...)`).
     */
    suspend fun execute(bodyRef: dev.rubentxu.pipeline.v2.domain.step.BodyRef): StepOutcome {
        // The loop is bounded by `2 * maxAttempts + 1` ticks to guarantee
        // progress. Each failed attempt consumes two ticks (an
        // `AdvanceAfterFailure` plan + the subsequent `ScheduleAttempt` plan);
        // each successful attempt consumes one. Without this margin, retry
        // aggregates with N >= 3 attempts would hit the budget ceiling and
        // surface an `ENGINE` failure instead of the typed `SCRIPT` failure
        // the planner emits through `ReuseFailure`.
        var budget = 2 * maxAttempts + 1
        while (budget-- > 0) {
            val decision = plan()
            when (decision) {
                is RetryReconciliationDecision.ReuseSuccess -> return StepOutcome.Success
                is RetryReconciliationDecision.ReuseFailure -> return StepOutcome.Failure(
                    PipelineFailure(
                        FailureKind.SCRIPT,
                        "retry aggregate terminal failure at attempt ${decision.attempt}",
                    ),
                )
                is RetryReconciliationDecision.CloseSuccessFromChild -> {
                    persistTerminalTransition(decision.attempt, OperationStatus.SUCCEEDED)
                    return StepOutcome.Success
                }
                is RetryReconciliationDecision.RejectDivergence -> return StepOutcome.Failure(
                    PipelineFailure(
                        FailureKind.REPLAY_COMPATIBILITY,
                        "retry control fingerprint divergence: ${decision.reason}",
                    ),
                )
                is RetryReconciliationDecision.ScheduleAttempt,
                is RetryReconciliationDecision.ResumeAttempt -> {
                    val attempt = when (decision) {
                        is RetryReconciliationDecision.ScheduleAttempt -> decision.attempt
                        is RetryReconciliationDecision.ResumeAttempt -> decision.attempt
                        else -> error("unreachable: handled by outer when")
                    }
                    journal.beginAttempt(controlOpId, attempt, fingerprint, OperationStatus.RUNNING)
                    eventSink.append(
                        RetryAttemptStarted(
                            eventId = UUID.randomUUID().toString(),
                            runId = runId.value,
                            sequence = 0L,
                            occurredAt = Instant.now(),
                            attemptNumber = attempt,
                            maxAttempts = maxAttempts,
                            stepName = blockId.value,
                            stepType = blockPluginStepId.value,
                            stageIndex = stageIndex,
                            stepIndex = stepIndex,
                        ),
                    )

                    // Body re-entry through the public BodyInvoker port. The
                    // canonical coordinator already opened the runner under
                    // bodyRef; the engine only passes the per-attempt
                    // BodyInvocationContext. The runner projects `attempt`
                    // onto the per-attempt deterministic bodyPath segment.
                    val outcome = bodyInvoker.invoke(
                        bodyRef,
                        BodyInvocationContext(
                            attempt = AttemptSegment(
                                index = attempt,
                                key = PluginStepId("retry-attempt"),
                            ),
                        ),
                    )

                    val attemptOutcome = when (outcome) {
                        is dev.rubentxu.pipeline.v2.domain.step.BodyOutcome.Completed ->
                            outcome.outcome

                        is dev.rubentxu.pipeline.v2.domain.step.BodyOutcome.Cancelled ->
                            // Cancelled is a terminal absolute for the retry
                            // aggregate: a cancelled body MUST NOT be retried,
                            // because the cancellation reason (parent /
                            // shutdown) is a structural decision the parent
                            // already made. The engine persists the terminal
                            // transition and returns without advancing.
                            return run {
                                persistTerminalTransition(attempt, OperationStatus.FAILED)
                                StepOutcome.Failure(
                                    PipelineFailure(
                                        FailureKind.SCRIPT,
                                        "retry body cancelled (reason=${outcome.reason})",
                                    ),
                                )
                            }
                    }

                    when (attemptOutcome) {
                        is StepOutcome.Success -> {
                            persistTerminalTransition(attempt, OperationStatus.SUCCEEDED)
                            return StepOutcome.Success
                        }
                        is StepOutcome.Unstable -> {
                            persistTerminalTransition(attempt, OperationStatus.FAILED)
                            return attemptOutcome
                        }
                        is StepOutcome.Failure -> {
                            persistTerminalTransition(attempt, OperationStatus.FAILED)
                            if (attempt >= maxAttempts) return attemptOutcome
                            // Loop again: the planner will emit
                            // AdvanceAfterFailure or ReuseFailure.
                        }
                    }
                }
                is RetryReconciliationDecision.AdvanceAfterFailure -> {
                    journal.beginAttempt(
                        controlOpId,
                        decision.to,
                        fingerprint,
                        OperationStatus.RUNNING,
                    )
                    // Loop again: the planner will emit ScheduleAttempt(decision.to).
                }
            }
        }
        return StepOutcome.Failure(
            PipelineFailure(
                FailureKind.ENGINE,
                "retry reconciliation loop exceeded budget ($maxAttempts)",
            ),
        )
    }
}
