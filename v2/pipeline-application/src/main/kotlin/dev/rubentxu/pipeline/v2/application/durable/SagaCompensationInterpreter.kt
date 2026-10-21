package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.saga.CompensationDirective
import dev.rubentxu.pipeline.v2.domain.saga.CompensationKey
import dev.rubentxu.pipeline.v2.domain.saga.CompensationObligation
import dev.rubentxu.pipeline.v2.domain.saga.CompensationObligationStatus
import dev.rubentxu.pipeline.v2.domain.saga.CompensationObservation
import dev.rubentxu.pipeline.v2.domain.saga.CompensationPolicy
import dev.rubentxu.pipeline.v2.domain.saga.EffectEvidence
import dev.rubentxu.pipeline.v2.domain.saga.EffectReceiptRef
import dev.rubentxu.pipeline.v2.domain.saga.InterventionReason
import dev.rubentxu.pipeline.v2.domain.saga.SagaActionId
import dev.rubentxu.pipeline.v2.domain.saga.SagaBodyPromise
import dev.rubentxu.pipeline.v2.domain.saga.SagaBodyVerdict
import dev.rubentxu.pipeline.v2.domain.saga.SagaCompensationReport
import dev.rubentxu.pipeline.v2.domain.saga.SagaCompletionReport
import dev.rubentxu.pipeline.v2.domain.saga.SagaLifecycle
import dev.rubentxu.pipeline.v2.domain.saga.SagaOperationIdentity
import dev.rubentxu.pipeline.v2.domain.saga.SagaOperationPhase
import dev.rubentxu.pipeline.v2.domain.saga.SagaPlan
import dev.rubentxu.pipeline.v2.domain.saga.SagaPlanOperations
import dev.rubentxu.pipeline.v2.domain.saga.SagaRecordOutcome
import dev.rubentxu.pipeline.v2.domain.saga.SagaTerminalState
import dev.rubentxu.pipeline.v2.domain.saga.UncertaintyReason
import dev.rubentxu.pipeline.v2.domain.step.AttemptSegment
import dev.rubentxu.pipeline.v2.domain.step.BodyInvocationContext
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodySlotInvoker
import dev.rubentxu.pipeline.v2.domain.step.ExecutionContextPatch
import dev.rubentxu.pipeline.v2.events.CompensationFinished
import dev.rubentxu.pipeline.v2.events.CompensationStarted
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.SagaTerminal
import java.util.UUID

/**
 * The engine's compensation loop.
 *
 * ## The loop
 *
 * Ask the durable state what is owed → dispatch the body that owes it → record what happened →
 * ask again. Four steps, and the state is durable at every turn, so the loop can be re-entered
 * by a LATER PROCESS without inventing anything: the second process reads the same obligations
 * and reaches the same decision.
 *
 * ## The order is not decided here
 *
 * The loop never picks which obligation to compensate. That is
 * [dev.rubentxu.pipeline.v2.domain.saga.SagaDecider.nextCompensation], a pure function over the
 * durable state, and this class only obeys it. An engine that decided the order itself would
 * be a second, divergent copy of the rule.
 *
 * ## The write-ahead
 *
 * The obligation is moved to "the compensation is about to be dispatched" BEFORE the body
 * runs. If the process dies while the body is in flight, the durable state says so, and the
 * next process does NOT run the body again on a guess: it stops, because the compensation may
 * already have been applied.
 *
 * ## A refusal is a STOP, not a warning
 *
 * Both writes of the loop are asked for an answer, and the answer is obeyed. If the durable
 * state refuses the write-ahead, the body is not dispatched at all: a compensation that leaves
 * no "in flight" marker is a compensation nobody can fence, and the next process would apply it
 * a second time. If the durable state refuses the record, the Saga cannot claim the compensation
 * happened, and the loop stops instead of compensating an older obligation against a state that
 * was never proven to be restored.
 *
 * In both cases the report says what the durable state says, so the process that reads the
 * rows later reaches the same conclusion this one reached.
 *
 * ## The bodies run through the SAME dispatcher
 *
 * The compensation body is a child of the Saga Step's own body, dispatched through the slot
 * seam, so its journal rows are the rows that child would have had. There is no second
 * dispatcher and no second exactly-once law.
 *
 * ## What the event plane sees
 *
 * The loop announces three facts and nothing else: a compensation STARTED, a compensation
 * FINISHED, and the Saga reached a TERMINAL state. The durable row says what is owed; the
 * events say what the engine did about it, and they are emitted AFTER the durable write that
 * makes them true — so an event never announces something the store does not know.
 */
class SagaCompensationInterpreter(
    private val runId: String,
    private val planOperations: SagaPlanOperations,
    private val completion: SagaCompletionOperations,
    private val slotInvoker: BodySlotInvoker,
    private val eventSink: EventSink,
    private val clock: Clock,
    /**
     * The bodies this step owns, so the interpreter can tell which body is about to run.
     */
    private val bodies: SagaBodyResolver?,
    /**
     * The body each promise is paid with, read from the durable row.
     */
    private val promisedBodies: SagaPromisedBodyReader,
) : SagaLifecycle {

    override suspend fun compensate(plan: SagaPlan): SagaCompensationReport {
        val compensated = mutableListOf<SagaActionId>()

        // What the reconciliation has already tried, and how many times. Both live in THIS
        // loop, not in the class: a second loop is a second chance, not a continuation of a
        // previous one's bookkeeping.
        val reconciled = mutableSetOf<CompensationKey>()
        val attempts = mutableMapOf<CompensationKey, Int>()

        while (true) {
            when (val directive = planOperations.next(plan.sagaId, plan.policy)) {
                is CompensationDirective.Settled ->
                    return terminal(plan, compensated, directive)

                // The engine has already decided that a human is needed. The remaining
                // obligations stay pending and durable; the loop stops here.
                is CompensationDirective.NeedsIntervention ->
                    return terminal(plan, compensated, directive)

                // The outcome is not established and the provider CAN be asked, because the
                // plugin declared its bodies safe to ask twice. So ask: the same body, the
                // same operation key, and therefore the same answer from a provider that
                // recognises its own keys. A `null` result means the answer is now durable
                // and the loop goes on to the next obligation.
                is CompensationDirective.Reconcile -> {
                    val stop = reconcile(plan, directive.obligation, reconciled, attempts)
                    if (stop != null) {
                        return terminal(plan, compensated, stop)
                    }
                }

                is CompensationDirective.Compensate -> {
                    val obligation = directive.obligation
                    val action = plan.action(obligation.actionId)
                        ?: return terminal(
                            plan,
                            compensated,
                            CompensationDirective.NeedsIntervention(
                                obligation,
                                // The engine cannot invent the plan that would have compensated
                                // it, and guessing a compensation is how a Saga does more damage
                                // than the failure it was recovering from.
                                InterventionReason.OBLIGATION_NOT_ARMED,
                            ),
                        )

                    // The body that is about to run must be the body the promise named.
                    //
                    // A promise says which body will undo the effect. If the body that is about
                    // to run is a different one, running it would undo the effect with a body
                    // nobody promised — and the effect would be undone by something that may
                    // not even be the body that made it. So the body does not run, the promise
                    // stays owed, and a human decides which body was meant.
                    //
                    // The check comes BEFORE the write-ahead: a compensation that is refused
                    // must not have left a row saying "in flight", because nothing ran.
                    val divergence = bodyDivergence(obligation, action.compensateSlot)
                    if (divergence != null) {
                        return terminal(
                            plan,
                            compensated,
                            CompensationDirective.NeedsIntervention(
                                obligation,
                                InterventionReason.COMPENSATION_BODY_DIVERGED,
                                divergence,
                            ),
                        )
                    }

                    // The write-ahead: BEFORE the body leaves the process.
                    //
                    // A refusal is not a warning: it means the durable state does not know that
                    // a compensation is about to run, so the body is NOT dispatched. A body that
                    // runs without that marker is a compensation nobody can fence — after a crash
                    // the row would still say "owed", and the next process would compensate a
                    // second time.
                    when (planOperations.beginCompensation(obligation.key)) {
                        is SagaRecordOutcome.Recorded -> Unit

                        is SagaRecordOutcome.Refused -> return terminal(
                            plan,
                            compensated,
                            CompensationDirective.NeedsIntervention(
                                obligation,
                                InterventionReason.COMPENSATION_NOT_DISPATCHED,
                            ),
                        )
                    }

                    // The row already says "in flight", so the announcement is a projection of
                    // something durable — not a promise about the future.
                    announceStarted(obligation, action.compensateSlot.value)

                    val observation = observe(
                        slotInvoker.invoke(
                            action.compensateSlot,
                            // The body gets the operation's key, so that a body which is asked
                            // again — by a reconciliation, or by a resumed run — can recognise
                            // the operation and answer "already done" instead of doing it twice.
                            operationContext(obligation, SagaOperationPhase.COMPENSATE, attempt = null),
                        ),
                    )

                    // The answer has to become durable too. If the store refuses the record, the
                    // row keeps saying "the compensation is in flight", and the Saga cannot
                    // claim it is settled: a later process reads that same row and reaches the
                    // same conservative conclusion. So the loop stops here, and the reason is
                    // the one the row itself implies.
                    when (planOperations.recordCompensation(obligation.key, observation)) {
                        is SagaRecordOutcome.Recorded -> {
                            announceFinished(obligation, action.compensateSlot.value, observation)
                            compensated += obligation.actionId
                        }

                        is SagaRecordOutcome.Refused -> {
                            // The body DID run, so the fact is announced even though the row
                            // could not learn it. The event is the only witness that it happened,
                            // and the disagreement with the row is the point: it is what makes a
                            // refused record visible instead of silent.
                            announceFinished(obligation, action.compensateSlot.value, observation)
                            return terminal(
                                plan,
                                compensated,
                                CompensationDirective.NeedsIntervention(
                                    obligation,
                                    InterventionReason.COMPENSATION_UNCERTAIN,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Closes the Saga by KEEPING its effects.
     *
     * ## What it does
     *
     * The completion port does the durable work — it discharges the obligations whose effect was
     * confirmed, in one statement, and reads the rows back — and this method does the rest:
     * announce the terminal, and hand the plugin the report.
     *
     * ## The order
     *
     * The terminal is announced AFTER the durable write, like every other fact the loop
     * announces: an event never says something the store does not know. A completion that the
     * durable state refused is announced as a terminal that needs a human, with the reason that
     * says the completion did not happen.
     *
     * ## What is NOT here
     *
     * No body runs. A completion keeps the effects; it does not undo them, so there is nothing
     * to dispatch and no compensation to write ahead of.
     */
    override suspend fun complete(plan: SagaPlan): SagaCompletionReport {
        val report = completion.complete(plan.sagaId)
        announceTerminal(plan, compensated = emptyList(), directive = report.directive)
        return report
    }

    /**
     * Asks the provider again about an obligation whose answer was lost.
     *
     * ## What it does
     *
     * It dispatches the SAME body, with the SAME operation key, and records what the body says
     * now. The body is asked because the plugin declared [CompensationPolicy.Reconcilable]:
     * its bodies are safe to ask twice, and the key is what makes them safe — a body that
     * recognises the key can answer "already done" instead of doing it again.
     *
     * ## Why the attempt changes
     *
     * The re-ask is a NEW attempt of the same operation, so it carries an attempt segment and
     * therefore a new identity in the operation journal. Without it the second dispatch would
     * answer to the first one's journal row, and a run that reads its own history would find
     * the first attempt's result instead of the second one's.
     *
     * ## Why it happens ONCE
     *
     * One re-ask per obligation. A second re-ask that still does not establish the outcome
     * means the provider cannot answer, and a provider that cannot answer is a human's job.
     * The loop must not spin on an obligation that will never settle.
     *
     * @return `null` when the answer is now durable and the loop may go on, or the directive
     * that ends the loop.
     */
    private suspend fun reconcile(
        plan: SagaPlan,
        obligation: CompensationObligation,
        reconciled: MutableSet<CompensationKey>,
        attempts: MutableMap<CompensationKey, Int>,
    ): CompensationDirective? {
        val action = plan.action(obligation.actionId)
            ?: return CompensationDirective.NeedsIntervention(
                obligation,
                InterventionReason.OBLIGATION_NOT_ARMED,
            )

        if (!reconciled.add(obligation.key)) {
            // The re-ask did not settle it either. Nobody can, so a human decides.
            return CompensationDirective.NeedsIntervention(
                obligation,
                unresolvedReason(obligation.status),
            )
        }
        val attempt = (attempts[obligation.key] ?: 1) + 1
        attempts[obligation.key] = attempt

        return when (obligation.status) {
            CompensationObligationStatus.EFFECT_PENDING,
            CompensationObligationStatus.EFFECT_UNKNOWN,
            -> {
                // The FORWARD's answer was lost. Ask the body that performs the effect, with
                // the forward's own key: a provider that recognises it answers "already done"
                // or does it now, and both answers are safe.
                val evidence = reconcileEvidence(
                    operationKey(obligation, SagaOperationPhase.FORWARD),
                    slotInvoker.invoke(
                        action.forwardSlot,
                        operationContext(obligation, SagaOperationPhase.FORWARD, attempt),
                    ),
                )
                when (planOperations.recordForward(obligation.key, evidence)) {
                    is SagaRecordOutcome.Recorded -> null

                    is SagaRecordOutcome.Refused -> CompensationDirective.NeedsIntervention(
                        obligation,
                        InterventionReason.UNKNOWN_EFFECT,
                    )
                }
            }

            CompensationObligationStatus.COMPENSATION_PENDING -> {
                // The COMPENSATION's answer was lost. Ask the body that undoes the effect,
                // with the compensation's own key — the same key the first attempt used.
                //
                // The body asked again must still be the body the promise named: the ANSWER
                // was lost, but the promise did not change. Asking a different body would be
                // a second guess wearing the first attempt's key.
                val divergence = bodyDivergence(obligation, action.compensateSlot)
                if (divergence != null) {
                    return CompensationDirective.NeedsIntervention(
                        obligation,
                        InterventionReason.COMPENSATION_BODY_DIVERGED,
                        divergence,
                    )
                }
                val observation = observe(
                    slotInvoker.invoke(
                        action.compensateSlot,
                        operationContext(obligation, SagaOperationPhase.COMPENSATE, attempt),
                    ),
                )
                when (planOperations.recordCompensation(obligation.key, observation)) {
                    is SagaRecordOutcome.Recorded -> {
                        announceFinished(obligation, action.compensateSlot.value, observation)
                        null
                    }

                    is SagaRecordOutcome.Refused -> CompensationDirective.NeedsIntervention(
                        obligation,
                        InterventionReason.COMPENSATION_UNCERTAIN,
                    )
                }
            }

            else -> CompensationDirective.NeedsIntervention(
                obligation,
                unresolvedReason(obligation.status),
            )
        }
    }

    /**
     * The divergence between the body the promise is paid with and the body that is about to
     * run, or `null` when there is none.
     *
     * ## What it compares
     *
     * The body the PROMISE named — read from the durable row, because that is where the
     * promise lives — against the body that is about to RUN, resolved from the slot the plan
     * says to dispatch. Both are measured the same way, by the engine, so the comparison means
     * something.
     *
     * ## What it does NOT compare
     *
     * The plan is not compared here. The plan says which SLOT to dispatch; the slot is a local
     * name, and the same slot name in two plugins means two different bodies. The body is what
     * tells them apart.
     */
    private fun bodyDivergence(
        obligation: CompensationObligation,
        slot: PluginStepId,
    ): SagaBodyVerdict.Diverged? {
        val promised = promisedBodies.digestOf(obligation.key)
        val current = bodies?.digestOf(slot)
        val verdict = SagaBodyPromise.verdict(promised = promised, current = current)
        return verdict as? SagaBodyVerdict.Diverged
    }

    /**
     * The context a Saga body is dispatched with: its operation key, and the attempt it is.
     *
     * The key is the OPERATION's, so it does not change between the first attempt and a
     * reconciliation of the same operation. The attempt is the ATTEMPT's, so the two dispatches
     * are two rows in the operation journal instead of one row read twice.
     */
    private fun operationContext(
        obligation: CompensationObligation,
        phase: SagaOperationPhase,
        attempt: Int?,
    ): BodyInvocationContext = BodyInvocationContext(
        attempt = attempt?.let { AttemptSegment(index = it) },
        patch = ExecutionContextPatch.Environment(
            mapOf(SagaOperationIdentity.OPERATION_KEY_ENV to operationKey(obligation, phase)),
        ),
    )

    /** The key that identifies one Saga operation, for the body that performs it. */
    private fun operationKey(obligation: CompensationObligation, phase: SagaOperationPhase): String =
        SagaOperationIdentity.derive(obligation.forwardOpId, phase, obligation.compensationId)

    /**
     * What the re-ask established about a forward effect.
     *
     * A failure here means "it is NOT done": the body was asked with the same key, and a body
     * that recognises its own keys does not apply an operation twice — so a failure now is a
     * failure of the FIRST attempt too, and the honest record is "no effect", not "nobody
     * knows". That is the difference the [CompensationPolicy.Reconcilable] declaration buys.
     *
     * It is the DECLARATION that makes the failure readable: the body answered about the
     * OPERATION ("already done" or "not done"), not about the attempt that asked. A body that
     * could only say "it failed this time" would not be answering about the operation, and
     * reading that as "not done" would leave an applied effect standing for ever.
     *
     * The receipt of a confirmed re-ask is the operation's own key: the body did not hand one
     * over, and the key IS the reference that says which operation the provider recognised.
     */
    private fun reconcileEvidence(operationKey: String, outcome: BodyOutcome): EffectEvidence =
        when (outcome) {
            is BodyOutcome.Completed -> when (outcome.outcome) {
                is StepOutcome.Success -> EffectEvidence.Confirmed(EffectReceiptRef.of(operationKey))

                is StepOutcome.Failure -> EffectEvidence.Absent

                is StepOutcome.Unstable -> EffectEvidence.Unknown(UncertaintyReason.ACK_LOST)
            }

            is BodyOutcome.Cancelled -> EffectEvidence.Unknown(UncertaintyReason.CALL_CANCELLED)
        }

    /**
     * Closes the loop and announces it.
     *
     * One place, so the terminal event cannot be emitted twice for one loop, nor forgotten for
     * one of the ways the loop can end.
     */
    private fun terminal(
        plan: SagaPlan,
        compensated: List<SagaActionId>,
        directive: CompensationDirective,
    ): SagaCompensationReport {
        announceTerminal(plan, compensated, directive)
        return SagaCompensationReport(plan.sagaId, compensated, directive)
    }

    /**
     * Announces the Saga's terminal state.
     *
     * One place for BOTH closings — the one that undoes the effects and the one that keeps them
     * — so the terminal event cannot be emitted twice for one closing, nor forgotten for one of
     * the ways a Saga can end.
     *
     * The event is a projection of the durable state: the write that makes it true has already
     * happened, so the plane never announces something the store does not know.
     */
    private fun announceTerminal(
        plan: SagaPlan,
        compensated: List<SagaActionId>,
        directive: CompensationDirective,
    ) {
        val intervention = directive as? CompensationDirective.NeedsIntervention
        eventSink.append(
            SagaTerminal(
                eventId = UUID.randomUUID().toString(),
                runId = runId,
                sequence = 0L,
                occurredAt = clock.now(),
                sagaId = plan.sagaId.value,
                state = if (intervention == null) {
                    SagaTerminalState.SETTLED.name
                } else {
                    SagaTerminalState.NEEDS_INTERVENTION.name
                },
                reason = intervention?.reason?.name,
                compensatedActions = compensated.map { it.value },
            ),
        )
    }

    /** Announces that the body of a compensation is about to be dispatched. */
    private fun announceStarted(obligation: CompensationObligation, slot: String) {
        eventSink.append(
            CompensationStarted(
                eventId = UUID.randomUUID().toString(),
                runId = runId,
                sequence = 0L,
                occurredAt = clock.now(),
                sagaId = obligation.sagaId.value,
                actionId = obligation.actionId.value,
                compensationId = obligation.compensationId.value,
                resourceRef = obligation.resourceRef.value,
                slot = slot,
            ),
        )
    }

    /** Announces what the body of a compensation produced. */
    private fun announceFinished(
        obligation: CompensationObligation,
        slot: String,
        observation: CompensationObservation,
    ) {
        eventSink.append(
            CompensationFinished(
                eventId = UUID.randomUUID().toString(),
                runId = runId,
                sequence = 0L,
                occurredAt = clock.now(),
                sagaId = obligation.sagaId.value,
                actionId = obligation.actionId.value,
                compensationId = obligation.compensationId.value,
                slot = slot,
                outcome = outcomeName(observation),
                detail = detailOf(observation),
            ),
        )
    }

    /**
     * Why an obligation that cannot be settled automatically needs a human.
     *
     * The two cases are not the same fact: an outcome that was never established is about
     * something that MAY NOT have happened, while an indeterminate compensation is about
     * something that MAY ALREADY have been undone.
     */
    private fun unresolvedReason(status: CompensationObligationStatus): InterventionReason =
        when (status) {
            CompensationObligationStatus.COMPENSATION_PENDING,
            CompensationObligationStatus.COMPENSATION_UNCERTAIN,
            -> InterventionReason.COMPENSATION_UNCERTAIN

            else -> InterventionReason.UNKNOWN_EFFECT
        }

    /**
     * What a compensation body produced, as the durable vocabulary sees it.
     *
     * The mapping is deliberately conservative: only a body that COMPLETED SUCCESSFULLY is a
     * confirmed compensation. A body that was cancelled or that came back unstable did not
     * establish that the resource was put back, and the difference between "not back" and
     * "nobody knows" decides whether the Saga may continue compensating.
     */
    private fun observe(outcome: BodyOutcome): CompensationObservation = when (outcome) {
        is BodyOutcome.Cancelled -> CompensationObservation.Uncertain(
            dev.rubentxu.pipeline.v2.domain.saga.UncertaintyReason.ACK_LOST,
        )

        is BodyOutcome.Completed -> when (val result = outcome.outcome) {
            is StepOutcome.Success -> CompensationObservation.Confirmed(receipt = null)

            is StepOutcome.Failure -> CompensationObservation.Failed(result.failure.message)

            is StepOutcome.Unstable -> CompensationObservation.Uncertain(
                dev.rubentxu.pipeline.v2.domain.saga.UncertaintyReason.ACK_LOST,
            )
        }
    }

    private companion object {
        /** The three names the domain's closed vocabulary projects to. */
        fun outcomeName(observation: CompensationObservation): String = when (observation) {
            is CompensationObservation.Confirmed -> "CONFIRMED"
            is CompensationObservation.Failed -> "FAILED"
            is CompensationObservation.Uncertain -> "UNCERTAIN"
        }

        /** The provider's message for a failure, the reason for an uncertainty. */
        fun detailOf(observation: CompensationObservation): String? = when (observation) {
            is CompensationObservation.Confirmed -> null
            is CompensationObservation.Failed -> observation.reason
            is CompensationObservation.Uncertain -> observation.reason.name
        }
    }
}
