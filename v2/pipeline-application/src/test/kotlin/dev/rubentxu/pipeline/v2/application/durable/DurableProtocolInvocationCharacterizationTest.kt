package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CoreStepRegistryFactory
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * B1.2c2-a1.2 / CDE.3-b4: freezes the durable protocol's effective-invocation signal on the new
 * authority seam [CommonExecutionBoundary] using a [RecordingBoundary]. The signal means "the
 * concrete Step will actually execute and may produce side effects" (distinct from
 * StepExecutionBoundary/lifecycle and replay/journal machinery). Equivalence with the old
 * [CanonicalInvocationExecutor] was proven in DualExecutionSeamCharacterizationTest before this
 * migration (CDE.3-b3); the old seam is now a compatibility detail behind the adapter.
 *
 * C1: fresh execution -> boundary.calls == 1.
 */
@Timeout(10)
class DurableProtocolInvocationCharacterizationTest {

    /** Counts effective step executions observed on the common seam, delegating to the REAL production
     * routing authority (B1.2c3-S2.5.2). Family-agnostic: it observes [CommonExecutionBoundary] and wraps
     * [buildDefaultExecutionBoundary] (legacy adapter or family router), never reimplementing routing. */
    private class RecordingBoundary : CommonExecutionBoundary {
        var calls: Int = 0
            private set
        private val delegate = buildDefaultExecutionBoundary(
            dispatcher = CanonicalNodeDispatcher(),
            invocationExecutor = null,
            stepRegistry = CoreStepRegistryFactory.registry(),
        )

        override suspend fun execute(prepared: PreparedExecution, context: CanonicalRuntimeContext): StepOutcome {
            calls++
            return delegate.execute(prepared, context)
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
        val recorder = RecordingBoundary()
        val coordinator = CanonicalDurableRunCoordinator(
            CanonicalNodeDispatcher(),
            InMemoryOperationJournal(clock),
            InMemoryReplayCursorStore(clock),
            clock,
            DefaultEffectReplayPolicy(),
            eventStore,
            credentialScopePort = noOpCredentialScopePort(),
            commonExecutionBoundary = recorder,
            stepRegistry = CoreStepRegistryFactory.registry(),
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

    @Test
    fun `C2 replay reuse of a completed echo does not invoke the executor`() = runBlocking {
        val clock = SystemClock()
        val eventStore = InMemoryEventStore()
        val journal = InMemoryOperationJournal(clock)
        val runId = RunId("a1-2-c2-reuse")
        // Seed a completed (SUCCEEDED) echo operation so the durable protocol reuses it.
        val pipeline = echoPipeline("reuse me")
        val payload = (pipeline.stages.single().body as StageBody.Steps).steps.single().payload.encoded
        val operationId = "${runId.value}-s0-0"
        val input = dev.rubentxu.pipeline.v2.domain.durable.OperationInput(
            stepId = "core.echo",
            params = mapOf("payload" to kotlinx.serialization.json.JsonPrimitive(payload)),
            runId = runId.value,
            attempt = 1,
        )
        journal.append(
            dev.rubentxu.pipeline.v2.domain.durable.RerunOperation(
                id = operationId,
                fingerprint = dev.rubentxu.pipeline.v2.domain.durable.Fingerprint.compute(
                    input,
                    "core.echo",
                    ReplayPolicy.MEMOIZED,
                    1,
                ),
                input = input,
                output = null,
                status = OperationStatus.SUCCEEDED,
                attempt = 1,
            ),
        )
        val recorder = RecordingBoundary()
        val coordinator = CanonicalDurableRunCoordinator(
            CanonicalNodeDispatcher(),
            journal,
            InMemoryReplayCursorStore(clock),
            clock,
            DefaultEffectReplayPolicy(),
            eventStore,
            credentialScopePort = noOpCredentialScopePort(),
            commonExecutionBoundary = recorder,
            stepRegistry = CoreStepRegistryFactory.registry(),
        )

        val outcome = coordinator.run(pipeline, runId)

        assertEquals(RunOutcome.Success, outcome)
        assertEquals(0, recorder.calls, "replay reuse (SKIP) must not invoke the effective executor")
    }

    @Test
    fun `C4 fingerprint divergence does not invoke the executor`() = runBlocking {
        val clock = SystemClock()
        val eventStore = InMemoryEventStore()
        val journal = InMemoryOperationJournal(clock)
        val runId = RunId("a1-2-c4-divergence")
        val pipeline = echoPipeline("real payload")
        // Seed a journal row whose fingerprint does NOT match the current invocation's, forcing
        // divergence before the executor is reached.
        val payload = (pipeline.stages.single().body as StageBody.Steps).steps.single().payload.encoded
        val operationId = "${runId.value}-s0-0"
        val staleInput = dev.rubentxu.pipeline.v2.domain.durable.OperationInput(
            stepId = "core.echo",
            params = mapOf("payload" to kotlinx.serialization.json.JsonPrimitive("a-different-payload")),
            runId = runId.value,
            attempt = 1,
        )
        journal.append(
            dev.rubentxu.pipeline.v2.domain.durable.RerunOperation(
                id = operationId,
                fingerprint = dev.rubentxu.pipeline.v2.domain.durable.Fingerprint.compute(
                    staleInput,
                    "core.echo",
                    ReplayPolicy.MEMOIZED,
                    1,
                ),
                input = staleInput,
                output = null,
                status = OperationStatus.SUCCEEDED,
                attempt = 1,
            ),
        )
        val recorder = RecordingBoundary()
        val coordinator = CanonicalDurableRunCoordinator(
            CanonicalNodeDispatcher(),
            journal,
            InMemoryReplayCursorStore(clock),
            clock,
            DefaultEffectReplayPolicy(),
            eventStore,
            credentialScopePort = noOpCredentialScopePort(),
            commonExecutionBoundary = recorder,
            stepRegistry = CoreStepRegistryFactory.registry(),
        )

        val outcome = coordinator.run(pipeline, runId)

        assertTrue(outcome is RunOutcome.Failure, "divergence must fail closed, got $outcome")
        assertEquals(0, recorder.calls, "divergence must not invoke the effective executor")
    }

    @Test
    fun `C3 decode failure does not invoke the executor`() = runBlocking {
        val clock = SystemClock()
        val eventStore = InMemoryEventStore()
        val runId = RunId("a1-2-c3-decode")
        val malformed = CompiledPipeline(
            id = DefinitionId("a1-2-c3-decode-pipeline"),
            source = SourceDescriptor("Pipeline.kts", Digest("source")),
            pluginLockDigest = Digest("lock"),
            stages = listOf(
                StageNode(
                    StageId("build"),
                    "build",
                    body = StageBody.Steps(
                        listOf(
                            OpaqueStepNode(
                                id = StepId("build/echo"),
                                pluginStepId = PluginStepId("core.echo"),
                                payload = VersionedStepPayload("dsl-v1", "{not-valid-json"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val recorder = RecordingBoundary()
        val coordinator = CanonicalDurableRunCoordinator(
            CanonicalNodeDispatcher(),
            InMemoryOperationJournal(clock),
            InMemoryReplayCursorStore(clock),
            clock,
            DefaultEffectReplayPolicy(),
            eventStore,
            credentialScopePort = noOpCredentialScopePort(),
            commonExecutionBoundary = recorder,
            stepRegistry = CoreStepRegistryFactory.registry(),
        )

        val outcome = coordinator.run(malformed, runId)

        assertTrue(outcome is RunOutcome.Failure, "decode failure must produce a typed failure, got $outcome")
        assertEquals(0, recorder.calls, "decode failure must not invoke the effective executor")
    }

    private fun shellPipeline(command: String, stepId: String = "build/sh"): CompiledPipeline =
        CompiledPipeline(
            id = DefinitionId("a1-3-shell-$stepId"),
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
                                pluginStepId = PluginStepId("core.sh"),
                                payload = VersionedStepPayload(
                                    "dsl-v1",
                                    """{"kind":"sh","command":"$command","isScriptBlock":false,"returnStdout":false}""",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

    @Test
    fun `S1 running shell recovery reuses the completed result without relaunching or invoking the executor`(@TempDir tempDir: Path) = runBlocking {
        val clock = SystemClock()
        val eventStore = InMemoryEventStore()
        val journal = InMemoryOperationJournal(clock)
        val runId = RunId("a1-3-s1-recovery")
        val command = "echo relaunched > '${tempDir.resolve("relaunched.txt")}'"
        val pipeline = shellPipeline(command)
        val operationId = "${runId.value}-s0-0"
        val payload = (pipeline.stages.single().body as StageBody.Steps).steps.single().payload.encoded
        val input = dev.rubentxu.pipeline.v2.domain.durable.OperationInput(
            stepId = "core.sh",
            params = mapOf("payload" to kotlinx.serialization.json.JsonPrimitive(payload)),
            runId = runId.value,
            attempt = 1,
        )
        journal.append(
            dev.rubentxu.pipeline.v2.domain.durable.RerunOperation(
                id = operationId,
                fingerprint = dev.rubentxu.pipeline.v2.domain.durable.Fingerprint.compute(
                    input,
                    "core.sh",
                    ReplayPolicy.RERUN,
                    1,
                ),
                input = input,
                output = null,
                status = OperationStatus.RUNNING,
                attempt = 1,
            ),
        )
        val controlRoot = tempDir.resolve("control")
        Files.createDirectories(controlRoot.resolve(operationId))
        Files.writeString(controlRoot.resolve(operationId).resolve("result.txt"), "0")

        val recorder = RecordingBoundary()
        val coordinator = CanonicalDurableRunCoordinator(
            CanonicalNodeDispatcher(),
            journal,
            InMemoryReplayCursorStore(clock),
            clock,
            DefaultEffectReplayPolicy(),
            eventStore,
            credentialScopePort = noOpCredentialScopePort(),
            controlDirRoot = controlRoot,
            commonExecutionBoundary = recorder,
            stepRegistry = CoreStepRegistryFactory.registry(),
        )

        val outcome = coordinator.run(pipeline, runId)

        assertEquals(RunOutcome.Success, outcome)
        assertFalse(Files.exists(tempDir.resolve("relaunched.txt")), "A reconciled result must not relaunch the shell")
        assertEquals(0, recorder.calls, "shell recovery must not invoke the effective executor (no fresh relaunch)")
        assertEquals(OperationStatus.SUCCEEDED, journal.get(operationId)?.status)
    }

    @Test
    fun `C5 malformed payload with a matching journaled success preserves decode-first precedence`() = runBlocking {
        val clock = SystemClock()
        val eventStore = InMemoryEventStore()
        val journal = InMemoryOperationJournal(clock)
        val runId = RunId("a1-2-c5-malformed-journaled")
        // A malformed (schema-invalid) echo payload whose fingerprint DOES match a pre-seeded SUCCEEDED
        // journal row: decode-first would reject as SCHEMA; reuse-first would skip to Success. This
        // freezes which precedence the durable protocol has today (decode failure vs replay reuse).
        val malformedPayload = "{not-valid-json"
        val pipeline = CompiledPipeline(
            id = DefinitionId("a1-2-c5-malformed-pipeline"),
            source = SourceDescriptor("Pipeline.kts", Digest("source")),
            pluginLockDigest = Digest("lock"),
            stages = listOf(
                StageNode(
                    StageId("build"),
                    "build",
                    body = StageBody.Steps(
                        listOf(
                            OpaqueStepNode(
                                id = StepId("build/echo"),
                                pluginStepId = PluginStepId("core.echo"),
                                payload = VersionedStepPayload("dsl-v1", malformedPayload),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val operationId = "${runId.value}-s0-0"
        val input = dev.rubentxu.pipeline.v2.domain.durable.OperationInput(
            stepId = "core.echo",
            params = mapOf("payload" to kotlinx.serialization.json.JsonPrimitive(malformedPayload)),
            runId = runId.value,
            attempt = 1,
        )
        journal.append(
            dev.rubentxu.pipeline.v2.domain.durable.RerunOperation(
                id = operationId,
                fingerprint = dev.rubentxu.pipeline.v2.domain.durable.Fingerprint.compute(
                    input,
                    "core.echo",
                    ReplayPolicy.MEMOIZED,
                    1,
                ),
                input = input,
                output = null,
                status = OperationStatus.SUCCEEDED,
                attempt = 1,
            ),
        )
        val recorder = RecordingBoundary()
        val coordinator = CanonicalDurableRunCoordinator(
            CanonicalNodeDispatcher(),
            journal,
            InMemoryReplayCursorStore(clock),
            clock,
            DefaultEffectReplayPolicy(),
            eventStore,
            credentialScopePort = noOpCredentialScopePort(),
            commonExecutionBoundary = recorder,
            stepRegistry = CoreStepRegistryFactory.registry(),
        )

        val outcome = coordinator.run(pipeline, runId)

        // Freeze: the durable protocol is DECODE-FIRST. A malformed payload is rejected as SCHEMA even
        // when a matching SUCCEEDED journal row exists (reuse-eligible): schema validation currently
        // precedes replay/reuse and must not be skipped by a reuse. executor stays 0.
        assertTrue(
            outcome is RunOutcome.Failure && outcome.failure.kind == dev.rubentxu.pipeline.v2.domain.FailureKind.SCHEMA,
            "malformed payload must reject as SCHEMA (decode-first), got: $outcome",
        )
        assertEquals(0, recorder.calls, "a malformed payload must never reach the effective executor")
    }

    @Test
    fun `C6 EmitEvent CatchErrorEntered overlay is applied on replay reuse`() = runBlocking {
        val clock = SystemClock()
        val eventStore = InMemoryEventStore()
        val journal = InMemoryOperationJournal(clock)
        val runId = RunId("a1-2-c6-overlay-reuse")
        // [0] CatchErrorEntered(UNSTABLE) will be REUSED (journaled SUCCESS); [1] sh "exit 1" runs fresh.
        // If the overlay is still pushed when the CatchErrorEntered step is reused, the sh failure is
        // downgraded to Unstable. If the overlay is NOT pushed on reuse, the sh failure propagates as
        // Failure. This freezes whether EmitEvent overlay resolution precedes durable reconciliation.
        val enterPayload = """{"kind":"CatchErrorEntered","buildResult":"UNSTABLE","stageResult":"UNSTABLE","enteredAt":"${System.currentTimeMillis()}"}"""
        val enterOp = "${runId.value}-s0-0"
        val enterInput = dev.rubentxu.pipeline.v2.domain.durable.OperationInput(
            stepId = "core.emit.event",
            params = mapOf("payload" to kotlinx.serialization.json.JsonPrimitive(enterPayload)),
            runId = runId.value,
            attempt = 1,
        )
        journal.append(
            dev.rubentxu.pipeline.v2.domain.durable.RerunOperation(
                id = enterOp,
                fingerprint = dev.rubentxu.pipeline.v2.domain.durable.Fingerprint.compute(
                    enterInput,
                    "core.emit.event",
                    ReplayPolicy.MEMOIZED,
                    1,
                ),
                input = enterInput,
                output = null,
                status = OperationStatus.SUCCEEDED,
                attempt = 1,
            ),
        )
        val pipeline = CompiledPipeline(
            id = DefinitionId("a1-2-c6-overlay-pipeline"),
            source = SourceDescriptor("Pipeline.kts", Digest("source")),
            pluginLockDigest = Digest("lock"),
            stages = listOf(
                StageNode(
                    StageId("build"),
                    "build",
                    body = StageBody.Steps(
                        listOf(
                            OpaqueStepNode(
                                id = StepId("build/catch-enter"),
                                pluginStepId = PluginStepId("core.emit.event"),
                                payload = VersionedStepPayload("dsl-v1", enterPayload),
                            ),
                            OpaqueStepNode(
                                id = StepId("build/sh"),
                                pluginStepId = PluginStepId("core.sh"),
                                payload = VersionedStepPayload(
                                    "dsl-v1",
                                    """{"kind":"sh","command":"exit 1","isScriptBlock":false,"returnStdout":false}""",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val recorder = RecordingBoundary()
        val coordinator = CanonicalDurableRunCoordinator(
            CanonicalNodeDispatcher(),
            journal,
            InMemoryReplayCursorStore(clock),
            clock,
            DefaultEffectReplayPolicy(),
            eventStore,
            credentialScopePort = noOpCredentialScopePort(),
            commonExecutionBoundary = recorder,
            stepRegistry = CoreStepRegistryFactory.registry(),
        )

        val outcome = coordinator.run(pipeline, runId)

        // The reused CatchErrorEntered (executor 0) must still push the overlay, so the fresh failing sh
        // (executor 1) is downgraded to Unstable rather than propagating as a Failure.
        assertEquals(1, recorder.calls, "reused CatchErrorEntered executor=0; fresh failing sh executor=1")
        assertEquals(RunOutcome.Unstable, outcome, "CatchErrorEntered overlay must be applied even on reuse")
    }

    @Test
    fun `a1-4 lifecycle spine owns StepStarted and StepFinished around the semantic event`() = runBlocking {
        val clock = SystemClock()
        val eventStore = InMemoryEventStore()
        val runId = RunId("a1-4-lifecycle-order")
        val coordinator = CanonicalDurableRunCoordinator(
            CanonicalNodeDispatcher(),
            InMemoryOperationJournal(clock),
            InMemoryReplayCursorStore(clock),
            clock,
            DefaultEffectReplayPolicy(),
            eventStore,
            credentialScopePort = noOpCredentialScopePort(),
        )
        val outcome = coordinator.run(echoPipeline("ordered"), runId)
        assertEquals(RunOutcome.Success, outcome)

        val events = eventStore.eventsFor(runId.value).toList()
        val started = events.indexOfFirst { it is dev.rubentxu.pipeline.v2.events.StepStarted && it.stepName == "build/echo" }
        val semantic = events.indexOfFirst { it is EchoOutputCaptured && it.content == "ordered\n" }
        val finished = events.indexOfFirst { it is dev.rubentxu.pipeline.v2.events.StepFinished && it.stepName == "build/echo" }
        assertTrue(started >= 0, "StepStarted must be emitted")
        assertTrue(semantic >= 0, "semantic EchoOutputCaptured must be emitted")
        assertTrue(finished >= 0, "StepFinished must be emitted")
        assertTrue(started < semantic && semantic < finished, "expected StepStarted < semantic event < StepFinished")
    }
}
