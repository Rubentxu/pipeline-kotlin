package dev.rubentxu.pipeline.v2.application.durable

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
        override suspend fun execute(
            prepared: PreparedExecution,
            context: CanonicalRuntimeContext,
        ): CommonExecutionResult {
            calls++
            return CommonExecutionResult(outcome = StepOutcome.Success, encodedOutput = null)
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

    // CORE-LOAD-REJECTED (2026-09-17): The "a legacy family prepared execution reaches
    // only the legacy executor once" test depended on CanonicalCoreStepCommand.Load to
    // construct a `PreparedLegacyExecution(...)`. With `Load` removed (REJECTED) and
    // `LEGACY_PLUGIN_IDS = emptySet()`, there is no legacy subtype to instantiate the
    // prepared execution with. The architectural invariant "SeamedExecutionRouter routes
    // legacy-family payloads ONLY to the legacy boundary" is now structurally true at the
    // type level (no legacy subtype exists, so no legacy-family payload can be constructed
    // in tests or production). The companion registry-family test below remains.

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
