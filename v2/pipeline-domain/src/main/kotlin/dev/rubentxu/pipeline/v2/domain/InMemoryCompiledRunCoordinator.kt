package dev.rubentxu.pipeline.v2.domain

/** One invocation of the canonical compiled pipeline. */
data class CompiledRunRequest(
    val pipeline: CompiledPipeline,
    val runId: RunId,
)

/** Dispatches the definition-local [StepNode] that the compiled IR contains. */
fun interface CompiledStepDispatcher {
    fun dispatch(step: StepNode, context: StepExecutionContext): StepOutcome
}

/**
 * Deterministic reference adapter for direct execution of [CompiledPipeline].
 *
 * It deliberately has no dependency on `PipelineDefinition`, `StepDescriptor`,
 * or a registry. Parallel execution is flattened in declaration order until
 * the canonical runtime declares its concurrent-dispatch contract.
 */
class InMemoryCompiledRunCoordinator(
    private val dispatcher: CompiledStepDispatcher,
) {
    fun run(request: CompiledRunRequest): RunOutcome {
        val plan = CompiledExecutionPlanner.plan(request.pipeline)
        val outcomes = plan.units.flatMap { unit ->
            unit.steps.map { step ->
                dispatcher.dispatch(step, StepExecutionContext(runId = request.runId))
            }
        }
        return RunOutcomeReducer.reduce(outcomes)
    }
}
