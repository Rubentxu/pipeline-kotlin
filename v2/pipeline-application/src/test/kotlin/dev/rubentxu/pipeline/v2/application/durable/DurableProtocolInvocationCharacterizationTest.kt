package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.application.SystemClock
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
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeFailure
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeOutcome
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopePort
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * B1.2c2-a1.2: freezes the durable protocol's effective-invocation signal using a
 * [RecordingInvocationExecutor]. The signal means "the concrete Step will actually execute and may
 * produce side effects" (distinct from StepExecutionBoundary/lifecycle and replay/journal machinery).
 *
 * C1: fresh execution -> executor.calls == 1.
 */
@Timeout(10)
class DurableProtocolInvocationCharacterizationTest {

    /** Counts effective step invocations; delegates to the real production executor. */
    private class RecordingInvocationExecutor(
        private val delegate: CanonicalInvocationExecutor,
    ) : CanonicalInvocationExecutor {
        var calls: Int = 0
            private set

        override suspend fun invoke(
            command: CanonicalCoreStepCommand,
            context: CanonicalRuntimeContext,
        ): StepOutcome {
            calls++
            return delegate.invoke(command, context)
        }
    }

    private fun echoPipeline(text: String, stepId: String = "build/echo"): CompiledPipeline =
        CompiledPipeline(
            id = DefinitionId("a1-2-echo-$stepId"),
            source = SourceDescriptor("Pipeline.kts", Digest("source")),
            pluginLockDigest = Digest("lock"),
            stages = listOf(
                StageNode(
                    StageId("build"),
                    "build",
                    body = StageBody.Steps(
                        listOf(
                            OpaqueStepNode(
                                id = StepId(stepId),
                                pluginStepId = PluginStepId("core.echo"),
                                payload = VersionedStepPayload("dsl-v1", """{"kind":"echo","text":"$text"}"""),
                            ),
                        ),
                    ),
                ),
            ),
        )

    private fun noOpCredentialScopePort(): CredentialScopePort = CredentialScopePort { _, _ ->
        CredentialScopeOutcome.Unavailable(
            CredentialScopeFailure.StoreUnavailable("no credential store in characterization test"),
        )
    }

    @Test
    fun `C1 fresh execution invokes the effective executor exactly once`() = runBlocking {
        val clock = SystemClock()
        val eventStore = InMemoryEventStore()
        val runId = RunId("a1-2-c1-fresh")
        val recorder = RecordingInvocationExecutor(
            CanonicalInvocationExecutor { command, ctx -> CanonicalNodeDispatcher().dispatch(command, ctx) },
        )
        val coordinator = CanonicalDurableRunCoordinator(
            CanonicalNodeDispatcher(),
            InMemoryOperationJournal(clock),
            InMemoryReplayCursorStore(clock),
            clock,
            DefaultEffectReplayPolicy(),
            eventStore,
            credentialScopePort = noOpCredentialScopePort(),
            invocationExecutor = recorder,
        )

        val outcome = coordinator.run(echoPipeline("hola"), runId)

        assertEquals(RunOutcome.Success, outcome)
        assertEquals(1, recorder.calls, "fresh execution must invoke the effective executor exactly once")
        assertEquals(
            "hola\n",
            eventStore.eventsFor(runId.value).filterIsInstance<EchoOutputCaptured>().single().content,
        )
        assertTrue(recorder.calls >= 1, "the executor signal is located on the real execution path")
    }
}
