package dev.rubentxu.pipeline.v2.domain

/**
 * Deterministic, test-friendly [StepDispatcher]: records every dispatched
 * step id (in dispatch order) and returns a configurable outcome per step
 * id, defaulting to [StepOutcome.Success].
 *
 * This is the moral equivalent of [MapPipelineCompiler] and
 * [MapRuntimeConfig]: the test adapter for its port. It performs no I/O,
 * touches no clock, and is fully deterministic — which makes assertions
 * like "the coordinator did NOT dispatch skipped steps on resume" trivial
 * to write.
 *
 * ## Configuration
 *
 * Outcomes are configured per step id at construction. A step id without
 * a configured outcome yields [StepOutcome.Success]. A configured
 * outcome may be a single [StepOutcome] (returned on every dispatch) or,
 * for retry scenarios, a list consumed in order with the last element
 * repeating.
 *
 * The recorded call log is exposed read-only via [dispatchedSteps] and
 * [dispatchCount]. The dispatcher is NOT thread-safe (tests are
 * sequential); concurrent use will corrupt the call log.
 *
 * @see StepDispatcher
 * @see InMemoryRunCoordinator
 */
class RecordingStepDispatcher(
    outcomes: Map<String, List<StepOutcome>> = emptyMap(),
) : StepDispatcher {

    private val outcomesView: Map<String, List<StepOutcome>> =
        outcomes.mapValues { (_, list) -> list.toList() }

    private val calls = mutableListOf<Pair<String, Int>>() // stepId to attempt

    /** Step ids dispatched so far, in dispatch order, with their attempt numbers. */
    val dispatchedSteps: List<Pair<String, Int>>
        get() = calls.toList()

    /** Step ids dispatched so far, in dispatch order, without attempt numbers. */
    val dispatchedStepIds: List<String>
        get() = calls.map { it.first }

    /** Number of times the given step id has been dispatched. */
    fun dispatchCount(stepId: String): Int = calls.count { it.first == stepId }

    override fun dispatch(step: StepDescriptor, context: StepExecutionContext): StepOutcome {
        calls += step.id to context.attempt
        val configured = outcomesView[step.id] ?: return StepOutcome.Success
        val index = (calls.count { it.first == step.id } - 1).coerceAtMost(configured.size - 1)
        return configured[index]
    }

    /** Returns a dispatcher that records calls but yields [StepOutcome.Success] for every step. */
    companion object {
        fun successOnly(): RecordingStepDispatcher = RecordingStepDispatcher()
    }
}
