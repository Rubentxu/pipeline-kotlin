package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.FailureKind
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
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(10)
class CanonicalDurableRunCoordinatorTest {
    @Test
    fun `identifies the linear core subset eligible for canonical execution`() {
        assertTrue(echoPipeline("eligible").supportsCanonicalDurableExecution())

        val unsupported = echoPipeline("unsupported").copy(
            stages = listOf(
                StageNode(
                    id = StageId("build"),
                    name = "build",
                    body = StageBody.Steps(
                        listOf(
                            OpaqueStepNode(
                                id = StepId("build/custom"),
                                pluginStepId = PluginStepId("custom.step"),
                                payload = VersionedStepPayload("dsl-v1", "{}"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertFalse(unsupported.supportsCanonicalDurableExecution())
    }

    @Test
    fun `fails closed when a resumed canonical node diverges from its journal`() = runBlocking {
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        val cursorStore = InMemoryReplayCursorStore(clock)
        val runId = RunId("canonical-divergence-run")
        val coordinator = CanonicalDurableRunCoordinator(
            CanonicalNodeDispatcher(), journal, cursorStore, clock,
            DefaultEffectReplayPolicy(), InMemoryEventStore(),
        )

        coordinator.run(echoPipeline("original"), runId)
        val outcome = coordinator.run(echoPipeline("changed"), runId)

        assertTrue(outcome is RunOutcome.Failure)
        assertEquals(FailureKind.INFRASTRUCTURE, (outcome as RunOutcome.Failure).failure.kind)
    }

    @Test
    fun `records a failing canonical shell run as a typed script failure`() = runBlocking {
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        val runId = RunId("canonical-failing-shell-run")
        val pipeline = CompiledPipeline(
            id = DefinitionId("canonical-failing-shell-pipeline"),
            source = SourceDescriptor("Pipeline.kts", Digest("source")),
            pluginLockDigest = Digest("lock"),
            stages = listOf(StageNode(StageId("build"), "build", body = StageBody.Steps(listOf(
                OpaqueStepNode(
                    id = StepId("build/fail"),
                    pluginStepId = PluginStepId("core.sh"),
                    payload = VersionedStepPayload("dsl-v1", """{"kind":"sh","command":"exit 7","isScriptBlock":false,"returnStdout":false}"""),
                ),
            )))),
        )
        val outcome = CanonicalDurableRunCoordinator(
            CanonicalNodeDispatcher(), journal, InMemoryReplayCursorStore(clock), clock,
            DefaultEffectReplayPolicy(), InMemoryEventStore(),
        ).run(pipeline, runId)

        assertTrue(outcome is RunOutcome.Failure)
        assertEquals(FailureKind.SCRIPT, (outcome as RunOutcome.Failure).failure.kind)
        assertEquals(OperationStatus.FAILED, journal.listForRun(runId.value).single().status)
    }

    @Test
    fun `journals and checkpoints a linear canonical echo run`() = runBlocking {
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        val cursorStore = InMemoryReplayCursorStore(clock)
        val eventStore = InMemoryEventStore()
        val runId = RunId("canonical-durable-run")
        val pipeline = CompiledPipeline(
            id = DefinitionId("canonical-durable-pipeline"),
            source = SourceDescriptor("Pipeline.kts", Digest("source")),
            pluginLockDigest = Digest("lock"),
            stages = listOf(
                StageNode(
                    id = StageId("build"),
                    name = "build",
                    body = StageBody.Steps(
                        listOf(
                            OpaqueStepNode(
                                id = StepId("build/echo"),
                                pluginStepId = PluginStepId("core.echo"),
                                payload = VersionedStepPayload("dsl-v1", """{"kind":"echo","text":"durable"}"""),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val coordinator = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = journal,
            cursorStore = cursorStore,
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = eventStore,
        )
        val outcome = coordinator.run(pipeline, runId)
        val resumedOutcome = coordinator.run(pipeline, runId)

        assertEquals(RunOutcome.Success, outcome)
        assertEquals(RunOutcome.Success, resumedOutcome)
        assertEquals(1, journal.listForRun(runId.value).size)
        assertEquals("${runId.value}-s0-0", cursorStore.load(runId.value)?.lastOpId)
        assertEquals(1, eventStore.eventsFor(runId.value).count())
    }

    private fun echoPipeline(text: String) = CompiledPipeline(
        id = DefinitionId("canonical-echo-pipeline"),
        source = SourceDescriptor("Pipeline.kts", Digest("source")),
        pluginLockDigest = Digest("lock"),
        stages = listOf(StageNode(StageId("build"), "build", body = StageBody.Steps(listOf(
            OpaqueStepNode(
                id = StepId("build/echo"),
                pluginStepId = PluginStepId("core.echo"),
                payload = VersionedStepPayload("dsl-v1", """{"kind":"echo","text":"$text"}"""),
            ),
        )))),
    )
}
