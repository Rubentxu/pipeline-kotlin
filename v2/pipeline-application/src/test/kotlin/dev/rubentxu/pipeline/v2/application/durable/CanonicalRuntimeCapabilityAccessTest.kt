package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.EVENT_SINK_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * CDE.3-d1: freezes the small, explicit capability bridge from a [CanonicalRuntimeContext] to a
 * [dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess]. The bridge exposes ONLY the capabilities
 * the canonical runtime declares and provides (EVENT_SINK today), never the raw runtime context, and
 * fails closed on any other lookup.
 */
@Timeout(10)
class CanonicalRuntimeCapabilityAccessTest {

    private fun runtime(eventSink: EventSink): CanonicalRuntimeContext = CanonicalRuntimeContext(
        opId = OpId("cap-bridge", 0, 0),
        runId = "cap-bridge",
        stageName = "build",
        stageIndex = 0,
        stepIndex = 0,
        shOptions = ShOptions.EMPTY,
        controlDirRoot = null,
        eventSink = eventSink,
    )

    private val unknownCapability = StepCapability("some.other.capability")

    @Test
    fun `bridge exposes the canonical capability set (event sink + shell + workspace ops + identities)`() {
        // CDE.3-d1 originally pinned EVENT_SINK as the single exposed capability. Since
        // then the bridge has accreted the canonical runtime capability set:
        //   - EVENT_SINK_CAPABILITY
        //   - SHELL_OPERATIONS_CAPABILITY  (LB-02 / A4)
        //   - WORKSPACE_OPERATIONS_CAPABILITY  (S2-A3 / G1)
        //   - STAGE_IDENTITY_CAPABILITY    (S2-A4 / G1)
        //   - PLATFORM_IDENTITY_CAPABILITY  (S2-A5 / G1)
        //   - WORKSPACE_IDENTITY_CAPABILITY (S2-A6 / G1)
        //   - TEMPORARY_WORKSPACE_OPERATIONS_CAPABILITY (S2-A6 / G3T post-correction)
        //
        // The bridge must expose EXACTLY this set — adding/removing a capability requires
        // updating both this test and the bridge together. The set must NEVER silently
        // grow or shrink across cycles.
        val expected = setOf(
            EVENT_SINK_CAPABILITY,
            dev.rubentxu.pipeline.v2.application.SHELL_OPERATIONS_CAPABILITY,
            dev.rubentxu.pipeline.v2.application.WORKSPACE_OPERATIONS_CAPABILITY,
            dev.rubentxu.pipeline.v2.application.STAGE_IDENTITY_CAPABILITY,
            dev.rubentxu.pipeline.v2.application.PLATFORM_IDENTITY_CAPABILITY,
            dev.rubentxu.pipeline.v2.application.WORKSPACE_IDENTITY_CAPABILITY,
            dev.rubentxu.pipeline.v2.application.TEMPORARY_WORKSPACE_OPERATIONS_CAPABILITY,
        )
        val access = CanonicalRuntimeCapabilityAccess(runtime(InMemoryEventStore()))
        assertEquals(expected, access.available())
    }

    @Test
    fun `event sink capability lookup returns the runtime event sink`() {
        val store = InMemoryEventStore()
        val access = CanonicalRuntimeCapabilityAccess(runtime(store))
        val sink: EventSink = access.get(EVENT_SINK_CAPABILITY)
        assertSame(store, sink, "the bridge must hand back the runtime event sink, typed")
    }

    @Test
    fun `lookup of a capability the runtime does not supply fails closed`() {
        val access = CanonicalRuntimeCapabilityAccess(runtime(InMemoryEventStore()))
        assertThrows(IllegalArgumentException::class.java) {
            access.get<String>(unknownCapability)
        }
    }

    @Test
    fun `admission passes when required capabilities are supplied and fails otherwise`() {
        val access = CanonicalRuntimeCapabilityAccess(runtime(InMemoryEventStore()))
        val available = access.available()

        // Mirrors RegistryStepInvoker admission: handler must not start when required - available != empty.
        assertEquals(emptySet<StepCapability>(), setOf(EVENT_SINK_CAPABILITY) - available)
        assertEquals(setOf(unknownCapability), setOf(unknownCapability) - available)
    }
}
