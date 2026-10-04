package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.support.CoordinatorFixture
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.SourceDescriptor
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageDirective
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveKey
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveRegistry
import dev.rubentxu.pipeline.v2.events.DirectiveAdmitted
import dev.rubentxu.pipeline.v2.events.DirectiveDenied
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * S1-C — typed directive observability. The coordinator's directive seam is
 * the ONLY legitimate emitter of DirectiveAdmitted/DirectiveDenied:
 *
 * L1: admitted events are emitted per declared directive, AFTER RunStarted and
 *     BEFORE StageStarted (admission precedes every stage effect), carrying the
 *     registry phase and the closed policy KIND.
 * L2: a denied stage emits DirectiveDenied BEFORE the failure aborts the run,
 *     and before ANY stage effect (no StageStarted, no step dispatch, no
 *     workspace creation observable before it).
 *
 * These are observability events of decisions already taken: they are NOT a
 * second decision channel, so no behaviour is derived from their presence.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class S1C_DirectiveEventObservabilityTest {

    private fun pipeline(vararg directives: StageDirective): CompiledPipeline = CompiledPipeline(
        id = DefinitionId("s1c-directive-events"),
        source = SourceDescriptor("S1C.pipeline.kts", Digest("s1c")),
        pluginLockDigest = Digest("s1c-lock"),
        stages = listOf(
            StageNode(
                id = StageId("build"),
                name = "build",
                body = StageBody.Steps(
                    listOf(
                        OpaqueStepNode(
                            id = StepId("build/sh"),
                            pluginStepId = dev.rubentxu.pipeline.v2.domain.PluginStepId("core.sh"),
                            payload = VersionedStepPayload(
                                "dsl-v1",
                                """{"kind":"sh","command":"echo directive-ran","isScriptBlock":false,"returnStdout":false}""",
                            ),
                        ),
                    ),
                ),
                directives = directives.toList(),
            ),
        ),
    )

    private data class Wired(val coordinator: CanonicalDurableRunCoordinator, val events: InMemoryEventStore)

    private fun wired(directiveRegistry: DirectiveRegistry?): Wired {
        val clock = dev.rubentxu.pipeline.v2.application.SystemClock()
        val controlRoot = Files.createTempDirectory("s1c-control")
        val events = InMemoryEventStore()
        val coordinator = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = InMemoryOperationJournal(clock),
            cursorStore = InMemoryReplayCursorStore(clock),
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = events,
            credentialScopePort = CoordinatorFixture.noOpCredentialScopePort(),
            controlDirRoot = controlRoot,
            stepRegistry = CoreStepRegistryFactory.registry(),
            directiveRegistry = directiveRegistry,
        )
        return Wired(coordinator, events)
    }

    private fun registryWithEcho(): DirectiveRegistry = DirectiveRegistry.Builder()
        .addAll(listOf(echoDefinition()))
        .build()

    /** Minimal directive definition whose decode accepts anything. */
    private fun echoDefinition() = dev.rubentxu.pipeline.v2.domain.directive.ErasedDirectiveDefinition(
        object : dev.rubentxu.pipeline.v2.domain.directive.DirectiveDefinition<String, Unit> {
            override val key = DirectiveKey("acme.echo")
            override val phase = dev.rubentxu.pipeline.v2.domain.directive.DirectivePhase.BEFORE_STAGE
            override val policy = dev.rubentxu.pipeline.v2.domain.directive.DirectiveExecutionPolicy.Evaluate
            override fun decode(encodedArguments: String) =
                dev.rubentxu.pipeline.v2.domain.directive.DirectiveDecodeResult.Decoded(encodedArguments)
        },
    )

    @Test
    fun `an admitted directive emits one typed event before StageStarted with phase and policy kind`() {
        val (coordinator, events) = wired(registryWithEcho())
        val runId = RunId("s1c-admitted")

        val outcome = runBlocking {
            coordinator.run(pipeline(StageDirective("acme.echo", """{"message":"hi"}""")), runId)
        }

        assertEquals(RunOutcome.Success, outcome, "registered directive admits; body runs")

        val stream = events.eventsFor(runId.value).toList()
        val admitted = stream.filterIsInstance<DirectiveAdmitted>()
        assertEquals(1, admitted.size, "exactly one admitted event per declared directive: $admitted")

        val event = admitted.single()
        assertEquals("acme.echo", event.directiveKey)
        assertEquals("BEFORE_STAGE", event.phase, "phase comes from registry metadata, not inference")
        assertEquals("evaluate", event.policy, "policy is the closed policy KIND")
        assertEquals(0, event.stageIndex)
        assertEquals("build", event.stageName)

        // Ordering: after RunStarted, strictly before StageStarted (admission
        // precedes every stage effect).
        val admittedIdx = stream.indexOfFirst { it is DirectiveAdmitted }
        val runStartedIdx = stream.indexOfFirst { it is RunStarted }
        val stageStartedIdx = stream.indexOfFirst { it is StageStarted }
        assertTrue(admittedIdx > runStartedIdx, "admission is observed after RunStarted")
        assertTrue(
            admittedIdx < stageStartedIdx,
            "admission MUST be observed BEFORE StageStarted (stream: ${stream.map { it.kind }})",
        )
        assertTrue(
            stageStartedIdx >= 0,
            "an admitted stage still starts normally (stream: ${stream.map { it.kind }})",
        )
    }

    @Test
    fun `a denied stage emits DirectiveDenied before the run aborts and no stage effect follows`() {
        val (coordinator, events) = wired(registryWithNoDirectives())
        val runId = RunId("s1c-denied")

        val outcome = runBlocking {
            coordinator.run(pipeline(StageDirective("acme.missing")), runId)
        }

        assertTrue(outcome is RunOutcome.Failure, "unknown directive denies the run: $outcome")

        val stream = events.eventsFor(runId.value).toList()
        val denied = stream.filterIsInstance<DirectiveDenied>()
        assertEquals(1, denied.size, "exactly one denied event for the offending key: $denied")

        val event = denied.single()
        assertEquals("acme.missing", event.directiveKey)
        assertEquals(0, event.stageIndex)
        assertEquals("build", event.stageName)
        assertTrue(
            event.reason.contains("acme.missing"),
            "the denial reason names the offending key, got '${event.reason}'",
        )

        // Fail-closed ordering: DirectiveDenied is the LAST meaningful event —
        // no StageStarted/StepStarted may follow it (no stage effect ran).
        val deniedIdx = stream.indexOfFirst { it is DirectiveDenied }
        assertTrue(deniedIdx >= 0)
        val after = stream.drop(deniedIdx + 1)
        assertTrue(
            after.none { it is StageStarted || it is dev.rubentxu.pipeline.v2.events.StepStarted },
            "FAIL-CLOSED: no stage effect may be observed after DirectiveDenied (stream: ${stream.map { it.kind }})",
        )
        assertTrue(
            stream.none { it is DirectiveAdmitted },
            "a denied directive MUST NOT also emit an admitted event",
        )
    }

    @Test
    fun `a stage without directives emits no directive events`() {
        val (coordinator, events) = wired(null)
        val runId = RunId("s1c-quiet")

        val outcome = runBlocking { coordinator.run(pipeline(), runId) }

        assertEquals(RunOutcome.Success, outcome)
        val directiveEvents = events.eventsFor(runId.value)
            .filter { it is DirectiveAdmitted || it is DirectiveDenied }
            .toList()
        assertTrue(
            directiveEvents.isEmpty(),
            "no directive vocabulary without declared directives: $directiveEvents",
        )
    }

    private fun registryWithNoDirectives(): DirectiveRegistry = DirectiveRegistry.Builder().build()
}
