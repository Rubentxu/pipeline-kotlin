package dev.rubentxu.pipeline.v2.events.identity

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.saga.InterventionReason
import dev.rubentxu.pipeline.v2.domain.saga.RunPresentation
import dev.rubentxu.pipeline.v2.domain.saga.SagaOutcome
import dev.rubentxu.pipeline.v2.domain.saga.SagaTerminalState
import dev.rubentxu.pipeline.v2.domain.saga.mergeCompensationOutcome
import dev.rubentxu.pipeline.v2.events.CompensationArmed
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.SagaTerminal
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted

/**
 * The run's verdict, READ FROM the Event Plane.
 *
 * ## Why this is a published projection and not a helper in the engine
 *
 * The engine already knows how the run ended — it holds the typed [RunOutcome] in memory while
 * it runs. But the engine is gone by the time anybody asks: the question "how did that run end,
 * and did it leave anything for a human?" is asked AFTERWARDS, by a consumer that has the
 * history and nothing else. Until now, that consumer had to re-derive the verdict from raw
 * words — which is exactly the duplication this projection removes.
 *
 * So the reading lives next to the plane it reads, in the module that PUBLISHES the plane, and
 * the words it understands are the words the domain declares.
 *
 * ## What it reads
 *
 * Four facts, and only four:
 *
 * - [RunFinished] — the run's own outcome.
 * - [StepFailed] — the typed detail of a failure, which [RunFinished] does not carry.
 * - [SagaTerminal] — what each Saga left behind.
 * - [CompensationArmed] — that a Saga owed something. This one is the interesting one: it is
 *   how the reader learns that a Saga exists at all when that Saga never said how it ended.
 *
 * ## The three ways this reading FAILS CLOSED
 *
 * A reader that cannot understand the plane has three honest options: say nothing, guess, or
 * say the worst thing that is true. This one says the worst thing that is true, because the
 * alternative is a green run that nobody can explain.
 *
 * 1. **No terminal at all** ⇒ [RunOutcome.Aborted]. The run never announced an ending, so it
 *    did not have one. It is NOT a success: a missing event is not evidence of an effect that
 *    did not happen, and it is certainly not evidence of a run that worked.
 * 2. **An outcome word this build does not know** ⇒ [RunOutcome.Failure] with a synthetic
 *    engine failure. The run might have succeeded, and nobody can say so, so it is reported as
 *    the failure it cannot rule out.
 * 3. **A Saga terminal this build cannot read** ⇒ [SagaOutcome.NeedsIntervention] with
 *    [InterventionReason.TERMINAL_UNREADABLE]. A Saga that cannot be read is NOT a Saga that
 *    settled; reading it as settled would turn a broken Saga into a finished one.
 *
 * ## And the fourth, which is the absence of an announcement
 *
 * A Saga that armed an obligation and never announced a terminal is a Saga that is still open.
 * The plane says it owes something and says nothing about how it ended, and the honest reading
 * of that is "a human has to decide" — never "nothing is owed".
 *
 * ## The resume
 *
 * A resumed run REUSES its run id, so a plane can hold more than one terminal announcement
 * for the same Saga, and more than one [RunFinished] for the same run. The LAST announcement is
 * the current state: a Saga that needed a human and later reconciled is settled, and a run
 * that failed and was later resumed to success has succeeded.
 *
 * Pure, total, no effects: it reads the plane and returns the verdict. It never writes, and
 * it never decides — the engine decided; this says the decision out loud, in a type.
 */
object RunPresentationProjection {

    /**
     * The presentation of the run [runId], read from [events].
     *
     * @param runId The run to present. Events of other runs are ignored: every event carries
     *   its own run id, so a caller that hands over a wider plane still gets an answer about
     *   THIS run and nothing else.
     * @param events The plane, in the order the store returned it (by sequence).
     * @return The verdict, never a guess: what the run was, and what each Saga left behind.
     */
    fun read(runId: String, events: Iterable<DomainEvent>): RunPresentation {
        val plane = events.filter { it.runId == runId }.toList()
        return mergeCompensationOutcome(readOutcome(plane), readSagas(plane))
    }

    /**
     * The run's own outcome, read from the terminal announcement.
     *
     * The LAST [RunFinished] wins, because a resumed run reuses the run id and announces its
     * ending again: the newest ending is the current one.
     */
    private fun readOutcome(plane: List<DomainEvent>): RunOutcome {
        val finished = plane.filterIsInstance<RunFinished>().lastOrNull() ?: return RunOutcome.Aborted
        return when (finished.outcome) {
            RunOutcome.SUCCESS_TOKEN -> RunOutcome.Success
            RunOutcome.UNSTABLE_TOKEN -> RunOutcome.Unstable
            RunOutcome.ABORTED_TOKEN -> RunOutcome.Aborted
            RunOutcome.FAILURE_TOKEN -> RunOutcome.Failure(readFailure(plane))
            else -> RunOutcome.Failure(
                PipelineFailure(
                    kind = FailureKind.ENGINE,
                    message = "the run's outcome word '${finished.outcome}' is not one this build reads " +
                        "(it reads ${RunOutcome.supportedTokens.joinToString()})",
                ),
            )
        }
    }

    /**
     * The typed detail of the failure, read from the plane.
     *
     * [RunFinished] carries the word "failure" and not the failure itself, so the detail has to
     * come from the plane.
     *
     * ## Why the OUTERMOST failure, and not the first one
     *
     * Measured, not assumed. A failing plugin step announces TWO failures: the failure of the
     * body it ran (`shell exited with code 1`) and its own (`the action ship failed: shell
     * exited with code 1`). The run's outcome is the OUTER one — that is the failure the
     * reducer saw, because the inner one belongs to a step nested inside the plugin's body,
     * not to a step of the run.
     *
     * So the failure that is read is the first one whose step is not nested inside another
     * step of the plane. Nesting is read from the plane's own addressing: a nested step is
     * named after its container (`parent/child`), so a step whose name begins with another
     * step's name and a slash is inside it.
     *
     * @see dev.rubentxu.pipeline.v2.domain.RunOutcomeReducer the reducer that decided the outcome
     */
    private fun readFailure(plane: List<DomainEvent>): PipelineFailure {
        val failures = plane.filterIsInstance<StepFailed>()
        val stepNames = plane.filterIsInstance<StepStarted>().map { it.stepName } +
            plane.filterIsInstance<StepFinished>().map { it.stepName } +
            failures.map { it.stepName }
        val failed = failures.firstOrNull { failure ->
            stepNames.none { other -> other != failure.stepName && failure.stepName.startsWith("$other/") }
        } ?: failures.firstOrNull()
            ?: return PipelineFailure(
                kind = FailureKind.ENGINE,
                message = "the run failed but the plane records no failed step to explain why",
            )
        val message = failed.message.takeIf { it.isNotBlank() }
            ?: "step '${failed.stepName}' failed (${failed.failureKind})"
        return PipelineFailure(kind = failed.failureKind, message = message)
    }

    /**
     * What each Saga of the run left behind.
     *
     * One entry per Saga, ordered by the moment the plane last spoke about it, so the list
     * reads in the order the Sagas settled.
     */
    private fun readSagas(plane: List<DomainEvent>): List<SagaOutcome> {
        val terminalBySaga = LinkedHashMap<String, SagaTerminal>()
        val lastSeen = LinkedHashMap<String, Int>()
        plane.forEachIndexed { index, event ->
            when (event) {
                is SagaTerminal -> {
                    terminalBySaga[event.sagaId] = event
                    lastSeen[event.sagaId] = index
                }
                is CompensationArmed -> lastSeen[event.sagaId] = index
                else -> Unit
            }
        }
        return lastSeen.entries
            .sortedBy { it.value }
            .map { (sagaId, _) ->
                val terminal = terminalBySaga[sagaId]
                if (terminal == null) {
                    // Armed and never announced: the Saga is open, and the absence is the evidence.
                    SagaOutcome.NeedsIntervention(sagaId, InterventionReason.TERMINAL_UNREADABLE)
                } else {
                    readTerminal(terminal)
                }
            }
    }

    /**
     * One terminal announcement, read as a situation.
     *
     * A terminal that contradicts itself, or that carries a word this build does not know, is
     * not read in the direction it points: a Saga that cannot be read is never read as settled.
     */
    private fun readTerminal(terminal: SagaTerminal): SagaOutcome = when (SagaTerminalState.parse(terminal.state)) {
        SagaTerminalState.SETTLED ->
            if (terminal.reason == null) {
                SagaOutcome.Settled(terminal.sagaId)
            } else {
                SagaOutcome.NeedsIntervention(terminal.sagaId, InterventionReason.TERMINAL_UNREADABLE)
            }
        SagaTerminalState.NEEDS_INTERVENTION -> {
            val reason = terminal.reason?.let { InterventionReason.parse(it) }
            SagaOutcome.NeedsIntervention(terminal.sagaId, reason ?: InterventionReason.TERMINAL_UNREADABLE)
        }
        null -> SagaOutcome.NeedsIntervention(terminal.sagaId, InterventionReason.TERMINAL_UNREADABLE)
    }
}
