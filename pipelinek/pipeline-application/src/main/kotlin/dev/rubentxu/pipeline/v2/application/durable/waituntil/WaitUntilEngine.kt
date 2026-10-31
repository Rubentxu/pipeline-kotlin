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
import dev.rubentxu.pipeline.v2.domain.durable.WaitUntilAbortCause
import dev.rubentxu.pipeline.v2.domain.durable.WaitUntilCompletion
import dev.rubentxu.pipeline.v2.domain.durable.WaitUntilReconciliationDecision
import dev.rubentxu.pipeline.v2.domain.durable.WaitUntilReconciler
import dev.rubentxu.pipeline.v2.domain.step.WaitUntilPredicateOutcome
import dev.rubentxu.pipeline.v2.domain.step.AttemptSegment
import dev.rubentxu.pipeline.v2.domain.step.BodyInvocationContext
import dev.rubentxu.pipeline.v2.domain.step.BodyInvoker
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodyRef
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.WaitUntilCompleted
import dev.rubentxu.pipeline.v2.events.WaitUntilPolled
import java.time.Instant
import java.util.UUID

/**
 * Application-internal authority for the waitUntil aggregate (
 * ## What this owns
 * `WaitUntilEngine` owns the **polling loop**: reconcile -> should-poll ->
 * build attempt context -> `BodyInvoker.invoke(...)` -> fold outcome ->
 * persist transition -> sleep / advance. It does NOT iterate body children;
 * it does NOT call the canonical `invokeBodyChildren` directly. The body-child
 * traversal lives in the canonical coordinator's shared loop; the engine
 * Reaches that loop through the `BodyInvoker` port
 * ## What it does NOT own
 * - The `BodyRef` lifecycle (`open` / `close`): the canonical coordinator
 *   remains the owner.
 * - Event shape beyond what the waitUntil aggregate itself owns
 *   ([WaitUntilPolled] / [WaitUntilCompleted]).
 * - The legacy compat polling loop (no `waitUntilControlJournal`): the
 *   canonical coordinator keeps its inline legacy loop for callers that have
 *   not yet opted into the durable waitUntil aggregate. It applies the SAME
 *   rule — the backoff ceiling IS the deadline, and the poll whose interval
 *   reached it is the last poll — and it projects its terminal from the same
 *   [WaitUntilCompletion] algebra, so the two branches cannot disagree about
 *   when a wait ends or about how it says so.
 * ## Plan-only invariant
 * `plan()` is the pure read path and is unchanged in semantics from
 * [WaitUntilReconciliationDriver]: read the journal, build the input,
 * delegate to [WaitUntilReconciler]. The engine mutates ONLY through
 * `journal.beginAttempt` and `journal.updateStatus`, and only after a
 * plan emits a mutation decision. The single-writer law is preserved:
 * this engine is the only place that mutates [WaitUntilControlJournal]
 * for the waitUntil aggregate.
 * ## Body invocation law
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
     * The engine reaches the body through the [BodyInvoker] port the
     * coordinator already opened; it does NOT call `invokeBodyChildren`
     * directly. The per-poll bodyPath segment
     * (`BlockSegment(N, wait-until-poll)`) drives per-poll journal identity.
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

                    // A FRESH start pays the initial interval before its first
                    // poll, exactly as the inline loop does
                    // (`BodyExecutionEngine.executeWaitUntilInline` delays
                    // `initialRecurrencePeriod` before poll 1). Without it the
                    // durable path polled at once and then waited 2x, 4x, ...:
                    // the same ladder of intervals, but a different schedule,
                    // so `initialRecurrencePeriod` meant two different things
                    // in the two branches. A RESUMED attempt does not pay it
                    // again: that attempt's interval was already waited for
                    // before the interruption.
                    if (decision is WaitUntilReconciliationDecision.ScheduleAttempt && attempt == 1) {
                        kotlinx.coroutines.delay(initialRecurrencePeriodMs)
                    }

                    val pollStartMs = System.currentTimeMillis()

                    val pollOutcome: BodyOutcome = invokeBodyOnce(bodyRef, attempt)

                    val pollDurationMs = System.currentTimeMillis() - pollStartMs
                    // The poll's answer is read ONCE, by the fold that owns the reading. The
                    // engine never inspects the body outcome field by field, so there is one
                    // place where a body's answer becomes the predicate's verdict, and no
                    // carrier that can hold "nothing" in the middle of the translation.
                    val predicateOutcome: WaitUntilPredicateOutcome =
                        waitUntilPredicateOutcome(pollOutcome)
                    val predicateSatisfied = predicateOutcome is WaitUntilPredicateOutcome.Satisfied

                    // ONE event per attempt. The poll's own record: the duration
                    // it took and the verdict the predicate actually evaluated to.
                    // There is no mirror emitted before the evaluation — a
                    // mirror published a second `WaitUntilPolled` per attempt,
                    // with `durationMs = 0L` and `conditionResult = false`,
                    // i.e. a poll that had not happened yet, answering for one
                    // that had.
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

                    when (predicateOutcome) {
                        is WaitUntilPredicateOutcome.Satisfied -> {
                            // The terminal is decided ONCE, as a
                            // `WaitUntilCompletion`, and the durable status, the
                            // typed result and the wire token are all projections
                            // of it. This site used to write all three by hand.
                            val completion = WaitUntilCompletion.Satisfied
                            journal.updateStatus(
                                controlOpId = controlOpId,
                                attempt = attempt,
                                status = completion.durableStatus,
                                fingerprint = fingerprint,
                            )
                            emitCompleted(overallStartMs, attempt, completion.wireOutcome)
                            return completion.toStepOutcome()
                        }
                        is WaitUntilPredicateOutcome.Failed -> {
                            // Predicate failed: persist FAILED on this poll so
                            // the next plan emits
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
                            // Same terminal as the reconciler's
                            // `Aborted`, and here it DOES persist, because the
                            // fresh path owns a row that is still RUNNING. The
                            // terminal does not decide that; the branch does.
                            val completion = WaitUntilCompletion.Aborted(
                                WaitUntilAbortCause.BodyCancelled(predicateOutcome.reason),
                            )
                            journal.updateStatus(
                                controlOpId = controlOpId,
                                attempt = attempt,
                                status = completion.durableStatus,
                                fingerprint = fingerprint,
                            )
                            emitCompleted(overallStartMs, attempt, completion.wireOutcome)
                            return completion.toStepOutcome()
                        }
                    }
                }

                is WaitUntilReconciliationDecision.AdvanceAfterPredicateSatisfied -> {
                    // The same `Satisfied` terminal the fresh loop
                    // reaches at line ~216. One case, two arrival paths, which is
                    // the point: fresh and restart are not two vocabularies.
                    val completion = WaitUntilCompletion.Satisfied
                    journal.updateStatus(
                        controlOpId = controlOpId,
                        attempt = decision.attempt,
                        status = completion.durableStatus,
                        fingerprint = fingerprint,
                    )
                    emitCompleted(overallStartMs, decision.attempt, completion.wireOutcome)
                    return completion.toStepOutcome()
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
                    val completion = WaitUntilCompletion.DeadlineExceeded(
                        attempt = decision.attempt,
                        ceilingMs = maxBackoffMs,
                    )
                    journal.updateStatus(
                        controlOpId = controlOpId,
                        attempt = decision.attempt,
                        status = completion.durableStatus,
                        fingerprint = fingerprint,
                    )
                    emitCompleted(overallStartMs, decision.attempt, completion.wireOutcome)
                    return completion.toStepOutcome()
                }

                is WaitUntilReconciliationDecision.Aborted -> {
                    // READ-ONLY. The reconciler returns `Aborted` precisely
                    // BECAUSE it read a durable row whose status already is ABORTED, so
                    // there is no transition left to persist. Writing it back could only
                    // ever be a no-op, and it was: `updateStatus` requires an existing
                    // control row for the attempt it is given and throws otherwise, so the
                    // write below used to throw `IllegalStateException` on an attempt that
                    // existed nowhere — the branch never emitted its event and never
                    // returned its typed failure, and the abort escaped as an untyped crash.
                    // The terminal is still the same `Aborted` the fresh
                    // cancellation produces. What differs is the persistence, and that
                    // stays here: this branch has nothing to write.
                    val completion = WaitUntilCompletion.Aborted(
                        WaitUntilAbortCause.DurableRowAlreadyAborted,
                    )
                    emitCompleted(overallStartMs, decision.attempt, completion.wireOutcome)
                    return completion.toStepOutcome()
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

