package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.UnixDetected
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(10)
class CanonicalIsUnixNodeDispatcherTest {
    @Test
    fun `dispatches a canonical isUnix node and emits UnixDetected event on Linux`() {
        assumeTrue(
            System.getProperty("os.name", "").lowercase().contains("linux"),
            "This test requires Linux"
        )

        val eventStore = InMemoryEventStore()
        val runId = "canonical-isUnix-run"
        val command = CanonicalCoreStepCommand.IsUnix()

        val outcome = CanonicalIsUnixNodeDispatcher().dispatch(
            command,
            CanonicalIsUnixDispatchContext(
                runId = runId,
                stepIndex = 0,
                eventSink = eventStore,
            ),
        )

        assertEquals(StepOutcome.Success, outcome)
        val unixEvents = eventStore.eventsFor(runId).filterIsInstance<UnixDetected>().toList()
        assertEquals(1, unixEvents.size)
        val event = unixEvents.first()
        assertTrue(event.isUnix, "Should be true on Linux")
        assertNotNull(event.osName)
        assertTrue(event.osName.isNotEmpty())
        assertNotNull(event.sha256)
        assertTrue(event.sha256.isNotEmpty())
    }
}
