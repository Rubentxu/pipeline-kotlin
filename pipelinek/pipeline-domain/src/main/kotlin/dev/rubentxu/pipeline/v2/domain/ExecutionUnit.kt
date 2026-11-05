package dev.rubentxu.pipeline.v2.domain

/**
 * One schedulable unit of an [ExecutionPlan]: either a single step or a
 * wave of steps that may run concurrently.
 * ## Semantics
 * - [Single]: exactly one step; dispatched through the run's
 *   [StepDispatcher] in isolation.
 * - [Concurrent]: two or more steps with no ordering constraint between
 *   them (same topological wave). Every step in the wave is dispatched
 *   through the **same** [StepDispatcher] instance as single steps —
 *   concurrency lives in the dispatch mechanism, never in a second
 * "parallel usa dispatcher principal").
 * The steps of a [Concurrent] unit are kept in declaration order; the
 * outcome list produced by a concurrent wave MUST preserve that order so
 * that [RunOutcomeReducer] folds deterministically ("first failure wins"
 * is a property of declaration order, not completion order).
*/
sealed interface ExecutionUnit {
    val steps: List<StepDescriptor>

    /**
     * A wave that holds exactly one step.
     *
     * The step runs alone: it has no sibling to be ordered against, so the
     * fold over the wave's outcomes has nothing to choose between.
     */
    data class Single(val step: StepDescriptor) : ExecutionUnit {
        override val steps: List<StepDescriptor> = listOf(step)
    }

    /**
     * A wave of two or more steps that may run at the same time.
     *
     * The steps carry no ordering constraint between them, and the list
     * keeps the order they were declared in, so that the outcomes of the
     * wave fold the same way whatever order they finish in.
     *
     * A wave of fewer than two steps is refused: a lone step has no sibling
     * to run beside it and belongs in [Single].
     */
    data class Concurrent(override val steps: List<StepDescriptor>) : ExecutionUnit {
        init {
            require(steps.size >= 2) {
                "ExecutionUnit.Concurrent requires at least two steps; use Single for one step"
            }
        }
    }
}

/**
 * The pure execution plan for a [PipelineDefinition]: an ordered list of
 * [ExecutionUnit]s (waves). Produced exclusively by [ExecutionPlanner].
 * @property units waves in execution order; within each
 *                [ExecutionUnit.Concurrent], steps in declaration order.
*/
data class ExecutionPlan(val units: List<ExecutionUnit>) {

    /** Flattened steps in deterministic execution order (declaration order inside waves). */
    val linearSteps: List<StepDescriptor>
        get() = units.flatMap { it.steps }
}
