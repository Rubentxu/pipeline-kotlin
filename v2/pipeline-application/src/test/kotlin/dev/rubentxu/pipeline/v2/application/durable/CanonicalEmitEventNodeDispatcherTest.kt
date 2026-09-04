package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.events.CatchErrorTriggered
import dev.rubentxu.pipeline.v2.events.FileWritten
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.StageMarkedUnstable
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Unit tests for [CanonicalEmitEventNodeDispatcher].
 *
 * Verifies:
 * - CatchErrorTriggered kind emits correctly
 * - StageMarkedUnstable kind emits correctly
 * - FileWritten kind emits correctly
 * - Unknown kind returns Failure(SCHEMA) and emits no event
 * - Event sink receives exactly the expected number of events
 */
class CanonicalEmitEventNodeDispatcherTest {

    private fun makeEmitContext(runId: String, eventStore: InMemoryEventStore, stageName: String = "test-stage") =
        CanonicalEmitEventDispatchContext(runId = runId, stageName = stageName, eventSink = eventStore)

    @Test
    fun `CatchErrorTriggered kind emits matching DomainEvent`() = runBlocking {
        val eventStore = InMemoryEventStore()
        val dispatcher = CanonicalEmitEventNodeDispatcher()
        val command = CanonicalCoreStepCommand.EmitEvent(
            kind = "CatchErrorTriggered",
            payload = mapOf(
                "stageName" to "build",
                "buildResult" to "FAILURE",
                "stageResult" to "FAILURE",
                "message" to "tolerated failure",
            ),
        )

        val outcome = dispatcher.dispatch(command, makeEmitContext("catch-run", eventStore))

        assertEquals(StepOutcome.Success, outcome)
        val events = eventStore.eventsFor("catch-run").toList()
        assertEquals(1, events.size, "Exactly one event must be emitted")
        val catchEvent = events.filterIsInstance<CatchErrorTriggered>().singleOrNull()
        assertNotNull(catchEvent, "Must emit CatchErrorTriggered. Events: ${events.map { it::class.simpleName }}")
        val evt = catchEvent!!
        assertEquals("catch-run", evt.runId)
        assertEquals("build", evt.stageName)
        assertEquals("FAILURE", evt.buildResult)
        assertEquals("FAILURE", evt.stageResult)
        assertEquals("tolerated failure", evt.message)
    }

    @Test
    fun `StageMarkedUnstable kind emits matching DomainEvent and returns Unstable`() = runBlocking {
        val eventStore = InMemoryEventStore()
        val dispatcher = CanonicalEmitEventNodeDispatcher()
        val command = CanonicalCoreStepCommand.EmitEvent(
            kind = "StageMarkedUnstable",
            payload = mapOf(
                "stageName" to "test",
                "message" to "flaky-network",
            ),
        )

        val outcome = dispatcher.dispatch(command, makeEmitContext("unstable-run", eventStore))

        // D6: StageMarkedUnstable returns Unstable (not Success) to signal run-level unstable
        assertEquals(StepOutcome.Unstable, outcome)
        val events = eventStore.eventsFor("unstable-run").toList()
        assertEquals(1, events.size)
        val unstableEvent = events.filterIsInstance<StageMarkedUnstable>().singleOrNull()
        assertNotNull(unstableEvent, "Must emit StageMarkedUnstable. Events: ${events.map { it::class.simpleName }}")
        val evt = unstableEvent!!
        assertEquals("test", evt.stageName)
        assertEquals("flaky-network", evt.message)
    }

    @Test
    fun `CatchErrorEntered kind validates kind and returns Success with no event`() = runBlocking {
        val eventStore = InMemoryEventStore()
        val dispatcher = CanonicalEmitEventNodeDispatcher()
        val command = CanonicalCoreStepCommand.EmitEvent(
            kind = "CatchErrorEntered",
            payload = mapOf(
                "buildResult" to "UNSTABLE",
                "stageResult" to "UNSTABLE",
                "enteredAt" to System.currentTimeMillis().toString(),
            ),
        )

        val outcome = dispatcher.dispatch(command, makeEmitContext("catch-enter-run", eventStore))

        // D1/D7: CatchErrorEntered is a scope-only signal — returns Success, emits NO event
        assertEquals(StepOutcome.Success, outcome)
        val events = eventStore.eventsFor("catch-enter-run").toList()
        assertTrue(events.isEmpty(), "CatchErrorEntered must NOT emit any event. Found: ${events.map { it::class.simpleName }}")
    }

    @Test
    fun `FileWritten kind emits matching DomainEvent`() = runBlocking {
        val eventStore = InMemoryEventStore()
        val dispatcher = CanonicalEmitEventNodeDispatcher()
        val command = CanonicalCoreStepCommand.EmitEvent(
            kind = "FileWritten",
            payload = mapOf(
                "path" to "/tmp/workspace/build-0/output.txt",
                "sha256" to "abc123def456",
                "size" to "42",
                "atomicallyMoved" to "true",
            ),
        )

        val outcome = dispatcher.dispatch(command, makeEmitContext("filewritten-run", eventStore))

        assertEquals(StepOutcome.Success, outcome)
        val events = eventStore.eventsFor("filewritten-run").toList()
        assertEquals(1, events.size)
        val fileEvent = events.filterIsInstance<FileWritten>().singleOrNull()
        assertNotNull(fileEvent, "Must emit FileWritten. Events: ${events.map { it::class.simpleName }}")
        val fe = fileEvent!!
        assertEquals(Paths.get("/tmp/workspace/build-0/output.txt"), fe.path)
        assertEquals("abc123def456", fe.sha256)
        assertEquals(42L, fe.size)
        assertTrue(fe.atomicallyMoved)
    }

    @Test
    fun `unknown kind returns Failure SCHEMA and emits no event`() = runBlocking {
        val eventStore = InMemoryEventStore()
        val dispatcher = CanonicalEmitEventNodeDispatcher()
        val command = CanonicalCoreStepCommand.EmitEvent(
            kind = "ghost-event",
            payload = mapOf("k" to "v"),
        )

        val outcome = dispatcher.dispatch(command, makeEmitContext("unknown-run", eventStore))

        assertTrue(outcome is StepOutcome.Failure, "Must return Failure")
        val failure = (outcome as StepOutcome.Failure).failure
        assertEquals(FailureKind.SCHEMA, failure.kind, "Failure kind must be SCHEMA")
        assertTrue(failure.message.contains("ghost-event"), "Error message should mention the unknown kind")
        assertTrue(
            failure.message.contains("CatchErrorTriggered") &&
                failure.message.contains("StageMarkedUnstable") &&
                failure.message.contains("FileWritten"),
            "Error message should list allowed kinds",
        )
        // NO events must be emitted for unknown kinds (fail-closed)
        val events = eventStore.eventsFor("unknown-run").toList()
        assertTrue(events.isEmpty(), "No events must be emitted for unknown kind. Found: ${events.size}")
    }
}
