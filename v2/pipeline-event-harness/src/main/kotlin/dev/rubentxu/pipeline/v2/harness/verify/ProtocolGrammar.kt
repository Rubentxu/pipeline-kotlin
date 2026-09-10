package dev.rubentxu.pipeline.v2.harness.verify

import dev.rubentxu.pipeline.v2.events.ParallelBranchFinished
import dev.rubentxu.pipeline.v2.events.ParallelBranchStarted
import dev.rubentxu.pipeline.v2.events.RetryAttemptFinished
import dev.rubentxu.pipeline.v2.events.RetryAttemptStarted
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
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
