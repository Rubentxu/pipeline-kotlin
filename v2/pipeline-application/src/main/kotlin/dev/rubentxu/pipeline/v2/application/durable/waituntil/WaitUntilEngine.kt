package dev.rubentxu.pipeline.v2.application.durable.waituntil

import dev.rubentxu.pipeline.v2.application.durable.WaitUntilControlJournal
import dev.rubentxu.pipeline.v2.application.durable.WaitUntilReconciliationDriver
import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.WaitUntilControlIdentity
import dev.rubentxu.pipeline.v2.domain.durable.WaitUntilReconciliationDecision
import dev.rubentxu.pipeline.v2.domain.durable.WaitUntilReconciler
import dev.rubentxu.pipeline.v2.domain.step.WaitUntilPredicateOutcome
import dev.rubentxu.pipeline.v2.domain.step.AttemptSegment
import dev.rubentxu.pipeline.v2.domain.step.BodyInvocationContext
import dev.rubentxu.pipeline.v2.domain.step.BodyInvoker
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodyRef
import dev.rubentxu.pipeline.v2.domain.step.BodyRefs
import dev.rubentxu.pipeline.v2.domain.step.CancellationReason
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.WaitUntilCompleted
import dev.rubentxu.pipeline.v2.events.WaitUntilPolled
import java.time.Instant
import java.util.UUID
import kotlin.math.min

/**
 * Application-internal authority for the waitUntil aggregate (WU-LPR-302
 * Phase 3, 2026-09-18).
 *
 * ## What this owns
 *
 * `WaitUntilEngine` owns the **polling loop**: reconcile -> should-poll ->
 * build attempt context -> `BodyInvoker.invoke(...)` -> fold outcome ->
 * persist transition -> sleep / advance. It does NOT iterate body children;
 * it does NOT call the canonical `invokeBodyChildren` directly. The body-child
 * traversal lives in the canonical coordinator's shared loop; the engine
 * reaches that loop through the `BodyInvoker` port (B11 / W2).
 *
 * ## What it does NOT own
 *
 * - The `BodyRef` lifecycle (`open` / `close`): the canonical coordinator
 *   remains the owner.
 * - Event shape beyond what the waitUntil aggregate itself owns
 *   ([WaitUntilPolled] / [WaitUntilCompleted]).
 * - The legacy compat polling loop (no `waitUntilControlJournal`): the
 *   canonical coordinator keeps its inline legacy loop bit-equivalent for
 *   callers that have not yet opted into the durable waitUntil aggregate.
 *
 * ## Plan-only invariant
 *
 * `plan()` is the pure read path and is unchanged in semantics from
 * [WaitUntilReconciliationDriver]: read the journal, build the input,
 * delegate to [WaitUntilReconciler]. The engine mutates ONLY through
 * `journal.beginAttempt` and `journal.updateStatus`, and only after a
 * plan() emits a mutation decision. The single-writer law is preserved:
 * this engine is the only place that mutates [WaitUntilControlJournal]
 * for the waitUntil aggregate.
 *
 * ## Body invocation law
 *
 * The engine invokes the body through `bodyInvoker.invoke(bodyRef,
 * BodyInvocationContext(attempt = AttemptSegment(N, wait-until-poll)))`.
 * The runner registered by the canonical coordinator (Phase 1b) projects
 * `attempt` onto the per-attempt deterministic `bodyPath` segment, so
 * each poll owns a distinct journal OpId. The engine is `StepKey`-blind:
 * it does not branch on concrete `PluginStepId`.
 */
class WaitUntilEngine(
    private val journal: WaitUntilControlJournal,
    private val eventSink: EventSink,
    private val bodyInvoker: BodyInvoker,
    private val controlOpId: String,
    private val parentBodyPath: List<BlockSegment>,
    private val fingerprint: Fingerprint,
    private val initialRecurrencePeriodMs: Long,
    private val maxBackoffMs: Long,
    private val runId: RunId,
    private val stageIndex: Int,
    private val stepIndex: Int,
    private val blockId: StepId,
    private val blockPluginStepId: PluginStepId,
) {

    init {
        require(initialRecurrencePeriodMs >= 1) {
            "initialRecurrencePeriodMs must be >= 1, got $initialRecurrencePeriodMs"
        }
        require(maxBackoffMs >= initialRecurrencePeriodMs) {
            "maxBackoffMs must be >= initialRecurrencePeriodMs, got $maxBackoffMs < $initialRecurrencePeriodMs"
        }
        require(runId.value.isNotBlank()) { "runId must not be blank" }
    }

    /**
     * Pure plan-only read of durable state. Equivalent to
     * [WaitUntilReconciliationDriver.plan]. Kept on the engine so the
     * dispatch loop below is one collaborator; the planner remains pure.
     */
    fun plan(): WaitUntilReconciliationDecision {
        val driver = WaitUntilReconciliationDriver(
            journal = journal,
            controlOpId = controlOpId,
            fingerprint = fingerprint,
            initialRecurrencePeriodMs = initialRecurrencePeriodMs,
            maxBackoffMs = maxBackoffMs,
        )
        return driver.plan()
    }

    /**
     * Execute the waitUntil aggregate against [bodyRef] until the predicate
     * body succeeds, the deadline is exceeded, or the aggregate is aborted.
     *
     * The engine reaches the body through the [BodyInvoker] port the
     * coordinator already opened; it does NOT call `invokeBodyChildren`
     * directly. The per-poll bodyPath segment
     * (`BlockSegment(N, wait-until-poll)`) drives per-poll journal identity.
     *
     * Outcomes fold into [StepOutcome]:
     *  - `Satisfied` -> [StepOutcome.Success], persist SUCCEEDED, emit
     *    [WaitUntilCompleted].
     *  - `Failed(...)` -> advance (or fail at deadline); persist next poll.
     *  - `DeadlineExceeded` -> persist FAILED_TIMEOUT, emit
     *    [WaitUntilCompleted], return [StepOutcome.Failure]
     *    ([FailureKind.TIMEOUT]).
     *  - `Aborted` -> persist ABORTED, emit [WaitUntilCompleted], return
     *    [StepOutcome.Failure] ([FailureKind.ENGINE]).
     *  - `RejectDivergence` -> [StepOutcome.Failure]
     *    ([FailureKind.REPLAY_COMPATIBILITY]).
     */
    suspend fun execute(bodyRef: BodyRef): StepOutcome {
        val overallStartMs = System.currentTimeMillis()
        while (true) {
            val decision = plan()
            when (decision) {
                is WaitUntilReconciliationDecision.ScheduleAttempt,
                is WaitUntilReconciliationDecision.ResumeAttempt -> {
                    val attempt = when (decision) {
                        is WaitUntilReconciliationDecision.ScheduleAttempt -> decision.attempt
                        is WaitUntilReconciliationDecision.ResumeAttempt -> decision.attempt
                        else -> error("unreachable: handled by outer when")
                    }

                    journal.beginAttempt(
                        controlOpId = controlOpId,
                        attempt = attempt,
                        currentBackoffMs = initialRecurrencePeriodMs,
                        fingerprint = fingerprint,
                        status = OperationStatus.RUNNING,
                    )

                    val pollStartMs = System.currentTimeMillis()
                    // Emit WaitUntilPolled BEFORE the predicate evaluation
                    // (observability mirror).
                    eventSink.append(
                        WaitUntilPolled(
                            eventId = UUID.randomUUID().toString(),
                            runId = runId.value,
                            sequence = 0L,
                            occurredAt = Instant.now(),
                            attempt = attempt,
                            durationMs = 0L,
                            conditionResult = false,
                        ),
                    )

                    val pollOutcome: BodyOutcome = invokeBodyOnce(bodyRef, attempt)

                    val pollDurationMs = System.currentTimeMillis() - pollStartMs
                    val completedOutcome: StepOutcome? = when (pollOutcome) {
                        is BodyOutcome.Completed -> pollOutcome.outcome
                        is BodyOutcome.Cancelled -> null
                    }
                    val predicateSatisfied = completedOutcome is StepOutcome.Success

                    // Emit updated WaitUntilPolled with actual result (AFTER journal write).
                    eventSink.append(
                        WaitUntilPolled(
                            eventId = UUID.randomUUID().toString(),
                            runId = runId.value,
                            sequence = 0L,
                            occurredAt = Instant.now(),
                            attempt = attempt,
                            durationMs = pollDurationMs,
                            conditionResult = predicateSatisfied,
                        ),
                    )

                    val predicateOutcome: WaitUntilPredicateOutcome = when {
                        pollOutcome is BodyOutcome.Cancelled -> WaitUntilPredicateOutcome.Cancelled(
                            reason = pollOutcome.reason,
                        )
                        completedOutcome is StepOutcome.Success -> WaitUntilPredicateOutcome.Satisfied
                        completedOutcome is StepOutcome.Failure -> WaitUntilPredicateOutcome.Failed(completedOutcome.failure)
                        completedOutcome is StepOutcome.Unstable -> WaitUntilPredicateOutcome.Failed(
                            PipelineFailure(
                                FailureKind.SCRIPT,
                                "waitUntil body marked unstable",
                            ),
                        )
                        else -> WaitUntilPredicateOutcome.Failed(
                            PipelineFailure(
                                FailureKind.SCRIPT,
                                "waitUntil body returned an unhandled outcome",
                            ),
                        )
                    }

                    when (predicateOutcome) {
                        is WaitUntilPredicateOutcome.Satisfied -> {
                            journal.updateStatus(
                                controlOpId = controlOpId,
                                attempt = attempt,
                                status = OperationStatus.SUCCEEDED,
                                fingerprint = fingerprint,
                            )
                            emitCompleted(overallStartMs, attempt, "completed")
                            return StepOutcome.Success
                        }
                        is WaitUntilPredicateOutcome.Failed -> {
                            // Predicate failed: persist FAILED on this poll so
                            // the next plan() emits
                            // AdvanceAfterPredicateUnsatisfied (or
                            // DeadlineExceeded), not ResumeAttempt. Without
                            // this persistence the loop would re-execute the
                            // same RUNNING poll indefinitely.
                            journal.updateStatus(
                                controlOpId = controlOpId,
                                attempt = attempt,
                                status = OperationStatus.FAILED,
                                fingerprint = fingerprint,
                            )
                            // Fall through to the next plan iteration.
                        }
                        is WaitUntilPredicateOutcome.Unsatisfied -> {
                            // Same persistence discipline as Failed: mark the
                            // current poll terminal so the planner advances.
                            journal.updateStatus(
                                controlOpId = controlOpId,
                                attempt = attempt,
                                status = OperationStatus.FAILED,
                                fingerprint = fingerprint,
                            )
                            // Fall through to advance.
                        }
                        is WaitUntilPredicateOutcome.Cancelled -> {
                            // Body returned BodyOutcome.Cancelled: persist
                            // ABORTED on this poll so the planner emits
                            // Aborted (or the durable journal shows a
                            // terminal state), not ResumeAttempt.
                            journal.updateStatus(
                                controlOpId = controlOpId,
                                attempt = attempt,
                                status = OperationStatus.ABORTED,
                                fingerprint = fingerprint,
                            )
                            emitCompleted(overallStartMs, attempt, "aborted")
                            return StepOutcome.Failure(
                                PipelineFailure(
                                    FailureKind.ENGINE,
                                    "waitUntil cancelled: ${predicateOutcome.reason}",
                                ),
                            )
                        }
                    }
                }

                is WaitUntilReconciliationDecision.AdvanceAfterPredicateSatisfied -> {
                    journal.updateStatus(
                        controlOpId = controlOpId,
                        attempt = decision.attempt,
                        status = OperationStatus.SUCCEEDED,
                        fingerprint = fingerprint,
                    )
                    emitCompleted(overallStartMs, decision.attempt, "completed")
                    return StepOutcome.Success
                }

                is WaitUntilReconciliationDecision.AdvanceAfterPredicateUnsatisfied -> {
                    journal.beginAttempt(
                        controlOpId = controlOpId,
                        attempt = decision.attempt,
                        currentBackoffMs = decision.nextBackoffMs,
                        fingerprint = fingerprint,
                        status = OperationStatus.RUNNING,
                    )
                    kotlinx.coroutines.delay(decision.nextBackoffMs)
                }

                is WaitUntilReconciliationDecision.DeadlineExceeded -> {
                    journal.updateStatus(
                        controlOpId = controlOpId,
                        attempt = decision.attempt,
                        status = OperationStatus.FAILED_TIMEOUT,
                        fingerprint = fingerprint,
                    )
                    emitCompleted(overallStartMs, decision.attempt, "deadline-exceeded")
                    return StepOutcome.Failure(
                        PipelineFailure(
                            FailureKind.TIMEOUT,
                            "waitUntil deadline exceeded at poll ${decision.attempt} (${maxBackoffMs}ms backoff ceiling)",
                        ),
                    )
                }

                is WaitUntilReconciliationDecision.Aborted -> {
                    journal.updateStatus(
                        controlOpId = controlOpId,
                        attempt = decision.operationId.hashCode(),
                        status = OperationStatus.ABORTED,
                        fingerprint = fingerprint,
                    )
                    emitCompleted(overallStartMs, decision.operationId.hashCode(), "aborted")
                    return StepOutcome.Failure(
                        PipelineFailure(
                            FailureKind.ENGINE,
                            "waitUntil aborted: ${decision.reason}",
                        ),
                    )
                }

                is WaitUntilReconciliationDecision.RejectDivergence -> {
                    return StepOutcome.Failure(
                        PipelineFailure(
                            FailureKind.REPLAY_COMPATIBILITY,
                            "waitUntil control fingerprint divergence: ${decision.reason}",
                        ),
                    )
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        return StepOutcome.Failure(
            PipelineFailure(
                FailureKind.ENGINE,
                "waitUntil loop terminated without a terminal decision (unreachable)",
            ),
        )
    }

    /**
     * Invoke the predicate body through the [BodyInvoker] port and fold the
     * typed outcome.
     *
     * Cancellation propagates as [WaitUntilPredicateOutcome.Cancelled] so the
     * main loop persists ABORTED and emits
     * [dev.rubentxu.pipeline.v2.events.WaitUntilCompleted] with
     * `outcome = "aborted"`. A typed [StepOutcome.Failure] from a completed
     * body is folded as [WaitUntilPredicateOutcome.Failed].
     */
    private suspend fun invokeBodyOnce(
        bodyRef: BodyRef,
        attempt: Int,
    ): BodyOutcome {
        return bodyInvoker.invoke(
            bodyRef,
            BodyInvocationContext(
                attempt = AttemptSegment(
                    index = attempt,
                    key = PluginStepId("wait-until-poll"),
                ),
            ),
        )
    }

    private fun emitCompleted(overallStartMs: Long, totalAttempts: Int, outcome: String) {
        val totalDurationMs = System.currentTimeMillis() - overallStartMs
        eventSink.append(
            WaitUntilCompleted(
                eventId = UUID.randomUUID().toString(),
                runId = runId.value,
                sequence = 0L,
                occurredAt = Instant.now(),
                totalAttempts = totalAttempts,
                totalDurationMs = totalDurationMs,
                outcome = outcome,
            ),
        )
    }
}
