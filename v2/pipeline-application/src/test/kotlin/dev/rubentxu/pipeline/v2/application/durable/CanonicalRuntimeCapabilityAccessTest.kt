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
    fun `bridge exposes exactly the declared event sink capability`() {
        val access = CanonicalRuntimeCapabilityAccess(runtime(InMemoryEventStore()))
        assertEquals(setOf(EVENT_SINK_CAPABILITY), access.available())
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
