package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.application.CoreEchoStep
import dev.rubentxu.pipeline.v2.application.EVENT_SINK_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * CDE.3-b2: freezes that the new additive [CommonExecutionBoundary], reached through
 * [LegacyExecutionAdapter], routes an already-prepared legacy execution to the old
 * [CanonicalInvocationExecutor] exactly once and forwards the decoded command unchanged.
 *
 * This is the migration foundation: while legacy sits behind the adapter, both seams are
 * observationally equivalent for the effective-execution law (a call through the boundary is exactly
 * one call to the old executor). It does NOT rewire production, so there is zero observable change.
 */
@Timeout(10)
class LegacyExecutionAdapterTest {

    /** Stub old seam: records calls and the command; returns Success (no real side effects here). */
    private class RecordingLegacyExecutor : CanonicalInvocationExecutor {
        var calls: Int = 0
            private set
        var lastCommand: CanonicalCoreStepCommand? = null
            private set

        override suspend fun invoke(
            command: CanonicalCoreStepCommand,
            context: CanonicalRuntimeContext,
        ): StepOutcome {
            calls++
            lastCommand = command
            return StepOutcome.Success
        }
    }

    private fun runtime(store: InMemoryEventStore): CanonicalRuntimeContext = CanonicalRuntimeContext(
        opId = OpId("adapter-test", 0, 0),
        runId = "adapter-test",
        stageName = "build",
        stageIndex = 0,
        stepIndex = 0,
        shOptions = ShOptions.EMPTY,
        controlDirRoot = null,
        eventSink = store,
    )

    @Test
    fun `a prepared legacy execution through the adapter invokes the old executor exactly once`() = runBlocking {
        val legacy = RecordingLegacyExecutor()
        val boundary = LegacyExecutionAdapter.adapt(legacy)
        val store = InMemoryEventStore()
        val command = CanonicalCoreStepCommand.Sleep(1)

        val outcome = boundary.execute(PreparedLegacyExecution(command), runtime(store))

        assertEquals(StepOutcome.Success, outcome)
        assertEquals(1, legacy.calls, "a single CommonExecutionBoundary.execute must reach the old executor exactly once")
        assertSame(command, legacy.lastCommand, "the adapter must forward the decoded command unchanged")
    }

    @Test
    fun `a non-legacy prepared execution fails closed in the legacy adapter`() = runBlocking {
        val legacy = RecordingLegacyExecutor()
        val boundary = LegacyExecutionAdapter.adapt(legacy)
        val store = InMemoryEventStore()
        // A registry-family payload must never reach the legacy old executor: CDE.3-d2 families are
        // distinct structural strategy kinds and the legacy adapter only routes legacy ones.
        val registry = InMemoryStepRegistry().apply { CoreEchoStep.registerInto(this) }
        val ready = RegistryExecutionPreparation.prepare(
            registry = registry,
            key = CoreEchoStep.KEY,
            encodedInput = CoreEchoStep.definition.contract.inputCodec.encode(dev.rubentxu.pipeline.v2.application.EchoInput("x")),
            availableCapabilities = setOf(EVENT_SINK_CAPABILITY),
        )
        val registryPrepared = (ready as ExecutionPreparation.Ready).prepared

        org.junit.jupiter.api.Assertions.assertThrows(dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation::class.java) {
            kotlinx.coroutines.runBlocking { boundary.execute(registryPrepared, runtime(store)) }
        }
        assertEquals(0, legacy.calls, "a registry-family payload must never be routed to the old executor")
    }
}
