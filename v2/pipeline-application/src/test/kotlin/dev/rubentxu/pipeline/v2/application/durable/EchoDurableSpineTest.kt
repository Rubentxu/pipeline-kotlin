package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.application.support.CoordinatorFixture
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.SourceDescriptor
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * B1.2c3-S2.4: after the flip, `core.echo` (with a registry injected) is classified as the Registry
 * structural family and executes durably through the registry handler + EVENT_SINK capability, NOT the
 * legacy decode. Replay/divergence never run the codec or handler.
 *
 * Durable-compat (the highest-value proof): because slice-1 made echo's envelope byte-identical to the
 * legacy compiled payload, an echo invocation PERSISTED under the old path replays/reuses under the new
 * registry architecture with the SAME durable identity (fingerprint), so the migration is continuous.
 */
@Timeout(10)
class EchoDurableSpineTest {

    private val ECHO_KEY = PluginStepId("core.echo")

    private fun echoNode(text: String) = OpaqueStepNode(
        id = StepId("build/echo"),
        pluginStepId = ECHO_KEY,
        payload = VersionedStepPayload("dsl-v1", """{"kind":"echo","text":"$text"}"""),
    )

    private fun pipeline(node: OpaqueStepNode) = CompiledPipeline(
        id = DefinitionId("echo-registry-spine-pipeline"),
        source = SourceDescriptor("Pipeline.kts", Digest("source")),
        pluginLockDigest = Digest("lock"),
        stages = listOf(
            StageNode(
                id = StageId("build"),
                name = "build",
                body = StageBody.Steps(listOf(node)),
            ),
        ),
    )

    private fun echoInput(runIdValue: String, text: String): OperationInput = OperationInput(
        stepId = ECHO_KEY.value,
        params = mapOf("payload" to JsonPrimitive("""{"kind":"echo","text":"$text"}""")),
        runId = runIdValue,
        attempt = 1,
    )

    @Test
    fun `flipped echo runs via the registry family emitting EchoOutputCaptured`() = runBlocking {
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        val eventSink = InMemoryEventStore()
        val coord = CoordinatorFixture.default(clock, journal, eventSink)

        val outcome = coord.run(pipeline(echoNode("hello flipped")), RunId("echo-registry-fresh"))

        assertEquals(RunOutcome.Success, outcome)
        val captured = eventSink.eventsFor("echo-registry-fresh").filterIsInstance<EchoOutputCaptured>()
        assertEquals(1, captured.count(), "registry-routed echo must emit exactly one EchoOutputCaptured")
        assertEquals("hello flipped\n", captured.single().content)
        assertEquals(OperationStatus.SUCCEEDED, journal.listForRun("echo-registry-fresh").single().status)
    }

    @Test
    fun `echo persisted under the old path is reused under the new registry architecture`() = runBlocking {
        // Durable-compat: seed a SUCCEEDED echo op with the EXACT envelope fingerprint the legacy path
        // would have written. Under the migrated registry architecture the envelope is byte-identical, so
        // the same durable identity is recognized and the outcome is reused WITHOUT re-running the handler.
        val clock = SystemClock()
        val runId = RunId("echo-registry-reuse")
        val journal = InMemoryOperationJournal(clock)
        val eventSink = InMemoryEventStore()
        val input = echoInput(runId.value, "persisted-under-old-path")
        journal.append(
            RerunOperation(
                id = "${runId.value}-s0-0",
                fingerprint = Fingerprint.compute(input, ECHO_KEY.value, ReplayPolicy.MEMOIZED, 1),
                input = input,
                output = null,
                status = OperationStatus.SUCCEEDED,
                attempt = 1,
            ),
        )
        // Pre-registry executions used runId with matching op id "echo-registry-reuse-s0-0".
        val coord = CoordinatorFixture.default(clock, journal, eventSink)

        val outcome = coord.run(pipeline(echoNode("persisted-under-old-path")), runId)

        assertEquals(RunOutcome.Success, outcome)
        assertEquals(
            0,
            eventSink.eventsFor(runId.value).filterIsInstance<EchoOutputCaptured>().count(),
            "a reused echo must NOT re-emit EchoOutputCaptured (handler did not run)",
        )
    }

    @Test
    fun `echo still executes via legacy when no registry is injected during the dual phase`() = runBlocking {
        // S2.4 dual: without a registry the coordinator still resolves echo as a legacy core command,
        // so behaviour is continuous during the migration before legacy echo removal.
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        val eventSink = InMemoryEventStore()
        val coord = CoordinatorFixture.negativeNoRegistry(clock, journal, eventSink)

        val outcome = coord.run(pipeline(echoNode("legacy dual")), RunId("echo-legacy-dual"))

        assertEquals(RunOutcome.Success, outcome)
        val captured = eventSink.eventsFor("echo-legacy-dual").filterIsInstance<EchoOutputCaptured>()
        assertEquals(1, captured.count(), "dual-phase echo (no registry) must still execute")
    }
}
