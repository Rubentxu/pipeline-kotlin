package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.application.CoreEchoStep
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * CDE.3-d2: freezes that [SeamedExecutionRouter] selects an executor by the PreparedExecution strategy
 * family (legacy-compatible vs registry), NEVER by step key and never by a concrete plugin step. Each
 * structural family reaches exactly its own executor once; a registry payload is never handed to the
 * legacy executor and vice versa.
 */
@Timeout(10)
class SeamedExecutionRouterTest {

    private class RecordingBoundary : CommonExecutionBoundary {
        var calls: Int = 0
            private set
        override suspend fun execute(prepared: PreparedExecution, context: CanonicalRuntimeContext): StepOutcome {
            calls++
            return StepOutcome.Success
        }
    }

    private fun runtime(): CanonicalRuntimeContext = CanonicalRuntimeContext(
        opId = OpId("router", 0, 0),
        runId = "router",
        stageName = "build",
        stageIndex = 0,
        stepIndex = 0,
        shOptions = ShOptions.EMPTY,
        controlDirRoot = null,
        eventSink = InMemoryEventStore(),
    )

    private fun registryPrepared(): PreparedRegistryExecution = PreparedRegistryExecution(
        key = CoreEchoStep.KEY,
        definition = CoreEchoStep.definition,
        decodedInput = "decoded-input",
    )

    @Test
    fun `a legacy family prepared execution reaches only the legacy executor once`() = runBlocking {
        val legacy = RecordingBoundary()
        val registry = RecordingBoundary()
        val routed = SeamedExecutionRouter.route(legacy, registry)

        routed.execute(PreparedLegacyExecution(CanonicalCoreStepCommand.Sleep(1)), runtime())

        assertEquals(1, legacy.calls, "legacy family must reach the legacy executor exactly once")
        assertEquals(0, registry.calls, "legacy family must never reach the registry executor")
    }

    @Test
    fun `a registry family prepared execution reaches only the registry executor once`() = runBlocking {
        val legacy = RecordingBoundary()
        val registry = RecordingBoundary()
        val routed = SeamedExecutionRouter.route(legacy, registry)

        routed.execute(registryPrepared(), runtime())

        assertEquals(0, legacy.calls, "registry family must never reach the legacy executor")
        assertEquals(1, registry.calls, "registry family must reach the registry executor exactly once")
    }
}
