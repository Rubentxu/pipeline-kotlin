package dev.rubentxu.pipeline.v2.harness.verify

import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.harness.model.EventViolation
import dev.rubentxu.pipeline.v2.harness.model.KeyKind
import dev.rubentxu.pipeline.v2.harness.model.TraceEntry
import dev.rubentxu.pipeline.v2.harness.model.ViolationRule

/**
 * A step cannot have failed and succeeded at the same time.
 *
 * ## What the law says
 *
 * Within one attempt of one step, the plane must not say both "this step failed" and "this
 * step succeeded". The failure carries the evidence — the kind and the message; the terminal
 * carries the verdict. When both are there for the same attempt, the plane contradicts itself,
 * and a contradiction in the history is what this module exists to catch.
 *
 * ## How a step is identified, and why it takes three parts
 *
 * A step is its stage, its index inside that stage, and its name. Losing any of the three
 * makes the law compare two different steps:
 *
 * - **stage**: the step index is the index *inside a stage*, so the same index means a
 *   different step in each stage of the run. Without the stage, the failure of a step in
 *   one stage and the success of a step in another stage would be one and the same step.
 * - **step index**: the position of the step inside its stage.
 * - **name**: a nested step shares the index of the step that contains it — the container
 *   and the nested one are one index, and only the name tells them apart. Without the name,
 *   the failure of a nested step would be read as the failure of its container.
 *
 * The stage and the index come from the harness's own scope key, so a failure that names no
 * stage has NO identity at all: it belongs to no stage, and the law does not put it in the
 * first one just to make it comparable. A failure in the first stage and a failure whose
 * stage nobody recorded are two different facts.
 *
 * ## How an attempt is delimited, and why
 *
 * An attempt runs from the `StepStarted` of that step to the next `StepStarted` of the same
 * step. A retry runs the step again, and the second attempt says so by starting again: the
 * failure of the first attempt and the success of the second are two facts about two
 * attempts, so the plane is not contradicting itself. This is not a supposition — it is what
 * the product's own plane shows, measured on a run whose first attempt failed and whose
 * second one succeeded.
 */
internal object StepTerminalContradiction {

    fun check(history: List<TypedEvent>): List<EventViolation> {
        val violations = mutableListOf<EventViolation>()
        val attemptOf = mutableMapOf<String, Int>()
        val failureIn = mutableMapOf<Pair<String, Int>, TypedEvent>()
        val successIn = mutableMapOf<Pair<String, Int>, TypedEvent>()

        for (event in history.sortedBy { it.sequence }) {
            val identity = identity(event) ?: continue
            if (event.kind == "StepStarted") {
                attemptOf[identity] = (attemptOf[identity] ?: 0) + 1
                continue
            }
            val attempt = attemptOf[identity] ?: 0
            when (event.kind) {
                "StepFailed" -> failureIn.putIfAbsent(identity to attempt, event)
                "StepFinished" -> {
                    val terminal = event.event as? StepFinished ?: continue
                    if (terminal.outcome == StepOutcome.SUCCESS_TOKEN) {
                        successIn.putIfAbsent(identity to attempt, event)
                    }
                }
            }
        }

        for ((key, failure) in failureIn) {
            val success = successIn[key] ?: continue
            violations += EventViolation(
                rule = ViolationRule.LAW_CONTRADICTORY_TERMINAL,
                message = "law: step '${key.first}' failed and succeeded in the same attempt",
                relevantTrace = (ProtocolGrammar.boundedWindow(history, failure.sequence) +
                    TraceEntry(success.sequence, success.kind, EventPayloadAccessor.summary(success)))
                    .sortedBy { it.sequence }.take(8),
                unexpected = "StepFailed and StepFinished(success) for the same step attempt",
            )
        }
        return violations
    }

    /** The identity of a step: its scope key — the stage and the index — and its name. */
    private fun identity(e: TypedEvent): String? {
        val scope = EventPayloadAccessor.scopeKey(e, KeyKind.STEP) ?: return null
        val name = when (val event = e.event) {
            is StepStarted -> event.stepName
            is StepFinished -> event.stepName
            is StepFailed -> event.stepName
            else -> return null
        }
        return "$scope:$name"
    }
}
