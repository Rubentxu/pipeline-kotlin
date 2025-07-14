package dev.rubentxu.pipeline.steps.testing

import dev.rubentxu.pipeline.context.PipelineContext
import dev.rubentxu.pipeline.steps.IStepExecutor
import kotlin.reflect.KCallable

/**
 * A step executor for testing purposes that uses a mock registry to execute steps.
 * It also records the call history for verification.
 */
class TestStepExecutor(
    private val mockRegistry: StepMockRegistry,
    private val callHistory: MutableList<KCallable<*>>
) : IStepExecutor {

    override suspend fun <T> execute(context: PipelineContext, step: KCallable<*>, parameters: Map<String, Any>): T {
        callHistory.add(step)
        val mock = mockRegistry.getMock(step.name)
            ?: throw IllegalStateException("No mock found for step '${step.name}'. Use mockStep() to define a mock.")

        @Suppress("UNCHECKED_CAST")
        return mock(parameters) as T
    }
}
