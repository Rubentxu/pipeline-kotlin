package dev.rubentxu.pipeline.v2.harness.verify

import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.saga.SagaTerminalState
import dev.rubentxu.pipeline.v2.events.CompensationArmed
import dev.rubentxu.pipeline.v2.events.ParallelBranchFinished
import dev.rubentxu.pipeline.v2.events.ParallelBranchStarted
import dev.rubentxu.pipeline.v2.events.RetryAttemptFinished
import dev.rubentxu.pipeline.v2.events.RetryAttemptStarted
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.SagaTerminal
import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.harness.model.EventViolation
import dev.rubentxu.pipeline.v2.harness.model.TraceEntry
import dev.rubentxu.pipeline.v2.harness.model.VerificationResult
import dev.rubentxu.pipeline.v2.harness.model.ViolationRule

/**
 * Universal protocol laws checked for EVERY contract verification. Partial order
 * only: events group by typed key; NO global scheduler order is imposed.
 */
object ProtocolGrammar {

    private const val CONTEXT = 4 // bounded window around the violating event

    fun check(history: List<TypedEvent>): List<EventViolation> {
        val violations = mutableListOf<EventViolation>()
        lifecyclePairs(history, "RunStarted", "RunFinished") { started, finished, _ ->
            if (started.isEmpty() && finished.isNotEmpty()) {
                violations += missingStart(finished.first(), "RunStarted")
            }
        }
        // Stage law, grouped by stage key.
        pairLaw(history, ViolationRule.LAW_FINISHED_WITHOUT_STARTED, "StageStarted", "StageFinished", Key.STAGE, violations)
        pairLaw(history, ViolationRule.LAW_FINISHED_WITHOUT_STARTED, "ParallelBranchStarted", "ParallelBranchFinished", Key.BRANCH, violations)
        pairLaw(history, ViolationRule.LAW_FINISHED_WITHOUT_STARTED, "RetryAttemptStarted", "RetryAttemptFinished", Key.STEP, violations)
        pairLaw(history, ViolationRule.LAW_FINISHED_WITHOUT_STARTED, "StepStarted", "StepFinished", Key.STEP, violations)

        // Parent structural completion cannot precede required child terminals:
        // StageFinished(S) must not appear before any ParallelBranchFinished under S.
        stageFinishedBeforeBranchFinished(history, violations)

        // Contradictory terminal outcomes for the same step attempt:
        // StepFinished(outcome=success/completed) and StepFailed for the same step key.
        contradictoryStepTerminal(history, violations)

        // A run that leaves a Saga pending cannot report success.
        sagaPendingOnSuccess(history, violations)
        return violations
    }

    private enum class Key { STAGE, BRANCH, STEP }

    private fun keyOf(e: TypedEvent, key: Key): String? = EventPayloadAccessor.scopeKey(e, when (key) {
        Key.STAGE -> dev.rubentxu.pipeline.v2.harness.model.KeyKind.STAGE
        Key.BRANCH -> dev.rubentxu.pipeline.v2.harness.model.KeyKind.BRANCH
        Key.STEP -> dev.rubentxu.pipeline.v2.harness.model.KeyKind.STEP
    })

    /**
     * For each key, every terminal must have a prior start. Keys with terminals
     * but no start are violations. Extra starts without terminals are tolerated
     * only for kinds that legitimately abort (StepFailed replaces StepFinished).
     */
    private fun pairLaw(
        history: List<TypedEvent>,
        rule: ViolationRule,
        startKind: String,
        endKind: String,
        key: Key,
        out: MutableList<EventViolation>,
    ) {
        val starts = history.filter { it.kind == startKind }
        val ends = history.filter { it.kind == endKind }
        for (end in ends) {
            val k = keyOf(end, key) ?: continue
            val hasStart = starts.any { keyOf(it, key) == k && it.sequence < end.sequence }
            if (!hasStart) {
                out += EventViolation(
                    rule = rule,
                    message = "law: $endKind requires prior $startKind for key '$k'",
                    relevantTrace = boundedWindow(history, end.sequence),
                    missing = "$startKind(key=$k) before seq=${end.sequence}",
                )
            }
        }
    }

    private fun lifecyclePairs(
        history: List<TypedEvent>, startKind: String, endKind: String,
        block: (List<TypedEvent>, List<TypedEvent>, List<TypedEvent>) -> Unit,
    ) {
        block(history.filter { it.kind == startKind }, history.filter { it.kind == endKind }, history)
    }

    private fun stageFinishedBeforeBranchFinished(history: List<TypedEvent>, out: MutableList<EventViolation>) {
        val stageFinish = history.filter { it.kind == "StageFinished" }
        val branchFinish = history.filter { it.kind == "ParallelBranchFinished" }
        for (sf in stageFinish) {
            val stageIdx = (sf.event as? StageFinished)?.stageIndex ?: continue
            val late = branchFinish.filter {
                (it.event as? ParallelBranchFinished)?.parentStageIndex == stageIdx && it.sequence > sf.sequence
            }
            if (late.isNotEmpty()) {
                out += EventViolation(
                    rule = ViolationRule.LAW_PARENT_COMPLETED_BEFORE_CHILD,
                    message = "law: StageFinished($stageIdx) must not precede ParallelBranchFinished under it",
                    relevantTrace = (boundedWindow(history, sf.sequence) + late.take(2).map {
                        TraceEntry(it.sequence, it.kind, EventPayloadAccessor.summary(it))
                    }).sortedBy { it.sequence }.take(8),
                    unexpected = "ParallelBranchFinished(stage=$stageIdx) after seq=${sf.sequence}",
                )
            }
        }
    }

    private fun contradictoryStepTerminal(history: List<TypedEvent>, out: MutableList<EventViolation>) {
        // RetryAttemptFinished duplicated for same attempt key with different outcome
        val retryFins = history.filter { it.kind == "RetryAttemptFinished" }
        for (i in retryFins.indices) {
            for (j in i + 1 until retryFins.size) {
                val a = retryFins[i].event as? RetryAttemptFinished ?: continue
                val b = retryFins[j].event as? RetryAttemptFinished ?: continue
                if (a.attemptNumber == b.attemptNumber && a.outcome != b.outcome) {
                    out += EventViolation(
                        rule = ViolationRule.LAW_CONTRADICTORY_TERMINAL,
                        message = "law: retry attempt ${a.attemptNumber} has contradictory terminals (${a.outcome} then ${b.outcome})",
                        relevantTrace = (boundedWindow(history, retryFins[i].sequence) +
                            TraceEntry(retryFins[j].sequence, retryFins[j].kind, EventPayloadAccessor.summary(retryFins[j])))
                            .sortedBy { it.sequence }.take(8),
                    )
                }
            }
        }
    }

    /**
     * A run that reports success while a Saga is still pending is a broken plane.
     *
     * ## Why this is a law and not a comment
     *
     * A Saga that needs a human is work that nobody has done. A run that says "success" while
     * such a Saga is open tells the operator that everything is fine, and everything is NOT fine:
     * a resource may be left applied, and the person who has to decide is not told.
     *
     * So the two facts cannot be told apart in a plane: a green run and an open Saga are a
     * contradiction, and a contradiction in the history is what this module exists to catch.
     *
     * ## What counts as pending
     *
     * Two shapes, and both are the same fact seen from two sides:
     *
     * - The Saga's last terminal says it did not settle.
     * - The Saga armed an obligation and never announced a terminal at all. The absence is the
     *   evidence: a Saga that owes something and never said how it ended is still open.
     *
     * ## Why the LAST terminal decides
     *
     * A resumed run reuses the run id, so a Saga may be announced more than once. The newest
     * announcement is where the Saga stands now: a Saga that needed a human and later
     * reconciled is settled, and the plane must not keep the older word.
     */
    private fun sagaPendingOnSuccess(history: List<TypedEvent>, out: MutableList<EventViolation>) {
        val finished = history.lastOrNull { it.kind == "RunFinished" } ?: return
        val outcome = (finished.event as? RunFinished)?.outcome ?: return
        if (outcome != RunOutcome.SUCCESS_TOKEN) return
        val pending = pendingSagas(history)
        if (pending.isEmpty()) return
        out += EventViolation(
            rule = ViolationRule.LAW_SAGA_PENDING_ON_SUCCESS,
            message = "law: the run reported success while saga(s) ${pending.joinToString()} were still pending",
            relevantTrace = boundedWindow(history, finished.sequence),
            unexpected = "pending saga(s): ${pending.joinToString()}",
        )
    }

    /**
     * The Sagas the plane leaves open, by id.
     *
     * A Saga that settled is not open. A Saga whose last terminal says it did not settle is.
     * A Saga that armed an obligation and never announced a terminal is.
     */
    private fun pendingSagas(history: List<TypedEvent>): List<String> {
        val terminalBySaga = LinkedHashMap<String, SagaTerminal>()
        val armed = LinkedHashSet<String>()
        history.forEach { typed ->
            when (val event = typed.event) {
                is SagaTerminal -> terminalBySaga[event.sagaId] = event
                is CompensationArmed -> armed += event.sagaId
                else -> Unit
            }
        }
        val sagaIds = LinkedHashSet<String>().apply {
            addAll(terminalBySaga.keys)
            addAll(armed)
        }
        return sagaIds.filter { sagaId ->
            val terminal = terminalBySaga[sagaId]
            terminal == null || terminal.state != SagaTerminalState.SETTLED.name
        }
    }

    private fun missingStart(finish: TypedEvent, startKind: String) = EventViolation(
        rule = ViolationRule.LAW_FINISHED_WITHOUT_STARTED,
        message = "law: ${finish.kind} requires prior $startKind",
        relevantTrace = boundedWindow(emptyList(), finish.sequence),
        missing = startKind,
    )

    /** Bounded context: the violating event +- CONTEXT neighbours, capped. */
    private fun boundedWindow(history: List<TypedEvent>, sequence: Long): List<TraceEntry> {
        val idx = history.indexOfFirst { it.sequence == sequence }
        if (idx < 0) return listOf(TraceEntry(sequence, "?", ""))
        val from = maxOf(0, idx - 2)
        val to = minOf(history.size - 1, idx + 2)
        return (from..to).map {
            TraceEntry(history[it].sequence, history[it].kind, EventPayloadAccessor.summary(history[it]))
        }
    }
}
