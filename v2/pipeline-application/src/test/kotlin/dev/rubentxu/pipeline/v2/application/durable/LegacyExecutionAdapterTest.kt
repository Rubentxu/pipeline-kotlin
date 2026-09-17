package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.application.CoreEchoStep
import dev.rubentxu.pipeline.v2.application.EVENT_SINK_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
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

    // CORE-LOAD-REJECTED (2026-09-17): CanonicalCoreStepCommand.Load subtype
    // physically removed (REJECTED, FIRST ZERO LEGACY RESIDUAL). The historical test
    // "a prepared legacy execution through the adapter invokes the old executor
    // exactly once" depended on `Load(path = "...")` as the canonical vehicle for
    // a legacy command. With `Load` removed and `LEGACY_PLUGIN_IDS = emptySet()`,
    // there is NO remaining legacy subtype to instantiate, and `PreparedLegacyExecution(...)`
    // cannot be constructed with a non-existent subtype. The "adapter routes legacy
    // commands" invariant is now a structural fact (sealed hierarchy has no legacy
    // constructors) rather than a runtime testable property; it is asserted by
    // LegacyResidualSnapshot / Lfc2ZeroLegacyResidualFitnessTest at the architecture
    // fitness level.
    //
    // The companion test "a non-legacy prepared execution fails closed in the legacy
    // adapter" is preserved below because it asserts the cross-family fail-closed
    // invariant (registry-family → legacy adapter → EngineInvariantViolation), which
    // remains semantically meaningful even after the legacy hierarchy is empty.

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
