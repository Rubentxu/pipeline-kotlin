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
 * CDE.3-b3: temporary DUAL characterization of the effective-execution seam during migration.
 *
 * It observes BOTH the new [CommonExecutionBoundary] (authority) and the old
 * [CanonicalInvocationExecutor] (legacy compatibility behind the adapter) on the SAME run, and
 * asserts the migration equivalence law:
 *
 *   commonExecutionCalls == legacyInvocationExecutorCalls
 *
 * for every legacy resolution family. If that equality ever fails, one of the seams represents
 * something different from effective side-effect execution and the frontier must be revisited.
 *
 * These two counters are MIGRATION instrumentation, not a permanent contract: they are retired once
 * the a1 laws are migrated to observe [CommonExecutionBoundary] alone (CDE.3-b4/b5).
 */
@Timeout(20)
class DualExecutionSeamCharacterizationTest {

    /** Old seam recorder: counts effective calls, then runs the real legacy dispatcher path. */
    private class RecordingLegacyExecutor : CanonicalInvocationExecutor {
        var calls: Int = 0
            private set
        override suspend fun invoke(
            command: CanonicalCoreStepCommand,
            context: CanonicalRuntimeContext,
        ): StepOutcome {
            calls++
            return CanonicalNodeDispatcher().dispatch(command, context)
        }
    }

    /** New seam recorder: counts effective calls, delegates through the legacy adapter. */
    private class RecordingBoundary(private val delegate: CommonExecutionBoundary) : CommonExecutionBoundary {
        var calls: Int = 0
            private set
        override suspend fun execute(prepared: PreparedExecution, context: CanonicalRuntimeContext): StepOutcome {
            calls++
            return delegate.execute(prepared, context)
        }
    }

    /** Dual-recording coordinator: asserts the equivalence law holds on this run. */
    private class DualRecorderCoordinator {
        val legacyExecutor = RecordingLegacyExecutor()
        val boundary: RecordingBoundary =
            RecordingBoundary(LegacyExecutionAdapter.adapt(legacyExecutor))

        fun build(
            journal: InMemoryOperationJournal,
            cursor: InMemoryReplayCursorStore,
            eventStore: InMemoryEventStore,
            controlDirRoot: Path? = null,
        ): CanonicalDurableRunCoordinator = CanonicalDurableRunCoordinator(
            CanonicalNodeDispatcher(),
            journal,
            cursor,
            SystemClock(),
            DefaultEffectReplayPolicy(),
            eventStore,
            credentialScopePort = CredentialScopePort { _, _ ->
                CredentialScopeOutcome.Unavailable(
                    CredentialScopeFailure.StoreUnavailable("no credential store in dual characterization"),
                )
            },
            controlDirRoot = controlDirRoot,
            commonExecutionBoundary = boundary,
        )

        /** The migration equivalence law: both seams count the SAME effective executions. */
        fun assertEquivalent(expected: Int) {
            assertEquals(expected, legacyExecutor.calls, "legacy executor seam must observe $expected effective executions")
            assertEquals(expected, boundary.calls, "common boundary seam must observe $expected effective executions")
            assertEquals(
                legacyExecutor.calls,
                boundary.calls,
                "EQUIVALENCE LAW: commonExecutionCalls must equal legacyInvocationExecutorCalls; if this fails, the seams diverge",
            )
        }
    }

    private fun echoPipeline(text: String, stepId: String = "build/echo"): CompiledPipeline =
        CompiledPipeline(
            id = DefinitionId("dual-$stepId"),
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

    @Test
    fun `fresh valid legacy executes exactly once through both seams`() = runBlocking {
        val store = InMemoryEventStore()
        val recorder = DualRecorderCoordinator()
        val coordinator = recorder.build(InMemoryOperationJournal(SystemClock()), InMemoryReplayCursorStore(SystemClock()), store)

        val outcome = coordinator.run(echoPipeline("dual"), RunId("dual-fresh"))

        assertEquals(RunOutcome.Success, outcome)
        recorder.assertEquivalent(expected = 1)
    }

    @Test
    fun `replay reuse never reaches either seam`() = runBlocking {
        val clock = SystemClock()
        val store = InMemoryEventStore()
        val journal = InMemoryOperationJournal(clock)
        val runId = RunId("dual-reuse")
        val pipeline = echoPipeline("reuse me")
        val payload = (pipeline.stages.single().body as StageBody.Steps).steps.single().payload.encoded
        journal.append(
            RerunOperation(
                id = "${runId.value}-s0-0",
                fingerprint = dev.rubentxu.pipeline.v2.domain.durable.Fingerprint.compute(
                    dev.rubentxu.pipeline.v2.domain.durable.OperationInput(
                        stepId = "core.echo",
                        params = mapOf("payload" to kotlinx.serialization.json.JsonPrimitive(payload)),
                        runId = runId.value,
                        attempt = 1,
                    ),
                    "core.echo",
                    ReplayPolicy.MEMOIZED,
                    1,
                ),
                input = dev.rubentxu.pipeline.v2.domain.durable.OperationInput(
                    stepId = "core.echo",
                    params = mapOf("payload" to kotlinx.serialization.json.JsonPrimitive(payload)),
                    runId = runId.value,
                    attempt = 1,
                ),
                output = null,
                status = OperationStatus.SUCCEEDED,
                attempt = 1,
            ),
        )
        val recorder = DualRecorderCoordinator()
        val coordinator = recorder.build(journal, InMemoryReplayCursorStore(clock), store)

        val outcome = coordinator.run(pipeline, runId)

        assertEquals(RunOutcome.Success, outcome)
        recorder.assertEquivalent(expected = 0)
    }

    @Test
    fun `fingerprint divergence never reaches either seam`() = runBlocking {
        val clock = SystemClock()
        val store = InMemoryEventStore()
        val journal = InMemoryOperationJournal(clock)
        val runId = RunId("dual-divergence")
        val pipeline = echoPipeline("real payload")
        val staleInput = dev.rubentxu.pipeline.v2.domain.durable.OperationInput(
            stepId = "core.echo",
            params = mapOf("payload" to kotlinx.serialization.json.JsonPrimitive("a-different-payload")),
            runId = runId.value,
            attempt = 1,
        )
        journal.append(
            RerunOperation(
                id = "${runId.value}-s0-0",
                fingerprint = dev.rubentxu.pipeline.v2.domain.durable.Fingerprint.compute(staleInput, "core.echo", ReplayPolicy.MEMOIZED, 1),
                input = staleInput,
                output = null,
                status = OperationStatus.SUCCEEDED,
                attempt = 1,
            ),
        )
        val recorder = DualRecorderCoordinator()
        val coordinator = recorder.build(journal, InMemoryReplayCursorStore(clock), store)

        val outcome = coordinator.run(pipeline, runId)

        assertTrue(outcome is RunOutcome.Failure, "divergence must fail closed, got $outcome")
        recorder.assertEquivalent(expected = 0)
    }

    @Test
    fun `decode schema rejection never reaches either seam`() = runBlocking {
        val clock = SystemClock()
        val store = InMemoryEventStore()
        val malformed = CompiledPipeline(
            id = DefinitionId("dual-malformed"),
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
        val recorder = DualRecorderCoordinator()
        val coordinator = recorder.build(InMemoryOperationJournal(clock), InMemoryReplayCursorStore(clock), store)

        val outcome = coordinator.run(malformed, RunId("dual-schema"))

        assertTrue(
            outcome is RunOutcome.Failure && outcome.failure.kind == dev.rubentxu.pipeline.v2.domain.FailureKind.SCHEMA,
            "malformed payload must reject as SCHEMA, got $outcome",
        )
        recorder.assertEquivalent(expected = 0)
    }

    private fun shellPipeline(command: String): CompiledPipeline =
        CompiledPipeline(
            id = DefinitionId("dual-shell"),
            source = SourceDescriptor("Pipeline.kts", Digest("source")),
            pluginLockDigest = Digest("lock"),
            stages = listOf(
                StageNode(
                    StageId("build"),
                    "build",
                    body = StageBody.Steps(
                        listOf(
                            OpaqueStepNode(
                                id = StepId("build/sh"),
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
    fun `running shell recovery never reaches either seam`(@TempDir tempDir: Path) = runBlocking {
        val clock = SystemClock()
        val store = InMemoryEventStore()
        val journal = InMemoryOperationJournal(clock)
        val runId = RunId("dual-recovery")
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
            RerunOperation(
                id = operationId,
                fingerprint = dev.rubentxu.pipeline.v2.domain.durable.Fingerprint.compute(input, "core.sh", ReplayPolicy.RERUN, 1),
                input = input,
                output = null,
                status = OperationStatus.RUNNING,
                attempt = 1,
            ),
        )
        val controlRoot = tempDir.resolve("control")
        Files.createDirectories(controlRoot.resolve(operationId))
        Files.writeString(controlRoot.resolve(operationId).resolve("result.txt"), "0")

        val recorder = DualRecorderCoordinator()
        val coordinator = recorder.build(journal, InMemoryReplayCursorStore(clock), store, controlDirRoot = controlRoot)

        val outcome = coordinator.run(pipeline, runId)

        assertEquals(RunOutcome.Success, outcome)
        assertFalse(Files.exists(tempDir.resolve("relaunched.txt")), "a reconciled result must not relaunch the shell")
        recorder.assertEquivalent(expected = 0)
        assertEquals(OperationStatus.SUCCEEDED, journal.get(operationId)?.status)
    }
}
