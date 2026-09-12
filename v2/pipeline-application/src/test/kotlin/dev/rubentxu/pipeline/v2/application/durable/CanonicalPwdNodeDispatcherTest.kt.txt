package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.PwdResolved
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Paths

@Timeout(10)
class CanonicalPwdNodeDispatcherTest {
    @Test
    fun `dispatches a canonical pwd node and emits PwdResolved event`() {
        val eventStore = InMemoryEventStore()
        val runId = "canonical-pwd-run"
        val workspaceRoot = Paths.get("/tmp/test-workspace")
        val command = CanonicalCoreStepCommand.Pwd(tmp = false)

        val outcome = CanonicalPwdNodeDispatcher().dispatch(
            command,
            CanonicalPwdDispatchContext(
                runId = runId,
                stepIndex = 0,
                eventSink = eventStore,
                workspaceRoot = workspaceRoot,
            ),
        )

        assertEquals(StepOutcome.Success, outcome)
        val pwdEvents = eventStore.eventsFor(runId).filterIsInstance<PwdResolved>().toList()
        assertEquals(1, pwdEvents.size)
        val event = pwdEvents.first()
        assertEquals(workspaceRoot.toAbsolutePath().toString(), event.path)
        assertEquals(workspaceRoot.toAbsolutePath().toString(), event.workspaceRoot)
        assertNotNull(event.sha256)
        assertTrue(event.sha256.isNotEmpty())
    }

    @Test
    fun `pwd with tmp=true creates temp subdirectory`() {
        val eventStore = InMemoryEventStore()
        val runId = "canonical-pwd-tmp-run"
        val workspaceRoot = Paths.get("/tmp/test-workspace")
        val command = CanonicalCoreStepCommand.Pwd(tmp = true)

        val outcome = CanonicalPwdNodeDispatcher().dispatch(
            command,
            CanonicalPwdDispatchContext(
                runId = runId,
                stepIndex = 0,
                eventSink = eventStore,
                workspaceRoot = workspaceRoot,
            ),
        )

        assertEquals(StepOutcome.Success, outcome)
        val pwdEvents = eventStore.eventsFor(runId).filterIsInstance<PwdResolved>().toList()
        assertEquals(1, pwdEvents.size)
        val event = pwdEvents.first()
        // tmp=true path should be under workspaceRoot
        assertTrue(event.path.startsWith(workspaceRoot.toAbsolutePath().toString()))
        assertTrue(event.path.contains("tmp-pwd-"))
    }
}
