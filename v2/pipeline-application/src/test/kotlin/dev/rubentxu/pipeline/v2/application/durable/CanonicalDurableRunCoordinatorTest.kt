package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.BlockStepNode
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.OptionSpec
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
import dev.rubentxu.pipeline.v2.events.CatchErrorTriggered
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.EffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ReplayDecision
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files

@Timeout(10)
class CanonicalDurableRunCoordinatorTest {
    @Test
    fun `continues after a default catchError failure and returns unstable`() = runBlocking {
        val clock = SystemClock()
        val eventStore = InMemoryEventStore()
        val runId = RunId("canonical-catch-error-continuation")
        val pipeline = CompiledPipeline(
            id = DefinitionId("canonical-catch-error-continuation-pipeline"),
            source = SourceDescriptor("Pipeline.kts", Digest("source")),
            pluginLockDigest = Digest("lock"),
            stages = listOf(StageNode(StageId("build"), "build", body = StageBody.Steps(listOf(
                emitEventStep("build/catch-enter", "CatchErrorEntered", "buildResult" to "UNSTABLE"),
                shellStep("build/catch-fail", "exit 1"),
                emitEventStep(
                    "build/catch-trigger",
                    "CatchErrorTriggered",
                    "buildResult" to "UNSTABLE",
                    "stageResult" to "UNSTABLE",
                    "emitted" to "true",
                ),
                echoStep("build/after-catch", "after catchError"),
            )))),
        )

        val outcome = CanonicalDurableRunCoordinator(
            CanonicalNodeDispatcher(), InMemoryOperationJournal(clock), InMemoryReplayCursorStore(clock), clock,
            DefaultEffectReplayPolicy(), eventStore,
        ).run(pipeline, runId)

        assertEquals(RunOutcome.Unstable, outcome)
        assertEquals(1, eventStore.eventsFor(runId.value).filterIsInstance<CatchErrorTriggered>().count())
        assertTrue(
            eventStore.eventsFor(runId.value).filterIsInstance<StepStarted>()
                .any { it.stepName == "build/after-catch" },
            "The sibling after catchError must execute",
        )
    }

    @Test
    fun `reconciles a completed running canonical shell without relaunching it`(@TempDir tempDir: Path) = runBlocking {
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        val runId = RunId("canonical-running-shell")
        val operationId = "${runId.value}-s0-0"
        val command = "echo relaunched > '${tempDir.resolve("relaunched.txt")}'"
        val pipeline = shellPipeline(command)
        val payload = (pipeline.stages.single().body as StageBody.Steps).steps.single().payload.encoded
        val input = OperationInput(
            stepId = "core.sh",
            params = mapOf("payload" to kotlinx.serialization.json.JsonPrimitive(payload)),
            runId = runId.value,
            attempt = 1,
        )
        journal.append(
            RerunOperation(
                id = operationId,
                fingerprint = Fingerprint.compute(input, "core.sh", ReplayPolicy.RERUN, 1),
                input = input,
                output = null,
                status = OperationStatus.RUNNING,
                attempt = 1,
            ),
        )
        Files.createDirectories(tempDir.resolve("control").resolve(operationId))
        Files.writeString(tempDir.resolve("control").resolve(operationId).resolve("result.txt"), "0")

        val outcome = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = journal,
            cursorStore = InMemoryReplayCursorStore(clock),
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = InMemoryEventStore(),
            controlDirRoot = tempDir.resolve("control"),
        ).run(pipeline, runId)

        assertEquals(RunOutcome.Success, outcome)
        assertFalse(Files.exists(tempDir.resolve("relaunched.txt")), "A reconciled result must not relaunch the shell")
        assertEquals(OperationStatus.SUCCEEDED, journal.get(operationId)?.status)
    }

    @Test
    fun `projects a stage timeout into canonical shell execution`(@TempDir tempDir: Path) = runBlocking {
        val clock = SystemClock()
        val runId = RunId("canonical-stage-timeout")
        val journal = InMemoryOperationJournal(clock)
        val pipeline = CompiledPipeline(
            id = DefinitionId("canonical-stage-timeout-pipeline"),
            source = SourceDescriptor("Pipeline.kts", Digest("source")),
            pluginLockDigest = Digest("lock"),
            stages = listOf(
                StageNode(
                    id = StageId("build"),
                    name = "build",
                    options = listOf(OptionSpec("timeout", "1")),
                    body = StageBody.Steps(
                        listOf(
                            OpaqueStepNode(
                                id = StepId("build/sleep"),
                                pluginStepId = PluginStepId("core.sh"),
                                payload = VersionedStepPayload("dsl-v1", """{"kind":"sh","command":"sleep 5","isScriptBlock":false,"returnStdout":false}"""),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val outcome = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = journal,
            cursorStore = InMemoryReplayCursorStore(clock),
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = InMemoryEventStore(),
            controlDirRoot = tempDir.resolve("control"),
            shOptions = ShOptions(tempDir.resolve("workspace"), false, null, emptyMap()),
        ).run(pipeline, runId)

        assertTrue(outcome is RunOutcome.Failure)
        assertEquals(FailureKind.TIMEOUT, (outcome as RunOutcome.Failure).failure.kind)
        assertEquals(OperationStatus.FAILED_TIMEOUT, journal.listForRun(runId.value).single().status)
    }

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
    fun `accepts supported block bodies and rejects unsupported nested steps`() {
        fun block(body: List<dev.rubentxu.pipeline.v2.domain.StepNode>) = CompiledPipeline(
            id = DefinitionId("canonical-block-pipeline"),
            source = SourceDescriptor("Pipeline.kts", Digest("source")),
            pluginLockDigest = Digest("lock"),
            stages = listOf(
                StageNode(
                    id = StageId("build"),
                    name = "build",
                    body = StageBody.Steps(
                        listOf(
                            BlockStepNode(
                                id = StepId("build/dir-body-0"),
                                pluginStepId = PluginStepId("core.dir"),
                                payload = VersionedStepPayload("dsl-v1", "{}"),
                                body = body,
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertTrue(block(echoPipeline("nested").stages.single().let {
            (it.body as StageBody.Steps).steps
        }).supportsCanonicalDurableExecution())
        assertFalse(block(listOf(
            OpaqueStepNode(
                id = StepId("build/dir-body-0/custom-0"),
                pluginStepId = PluginStepId("custom.step"),
                payload = VersionedStepPayload("dsl-v1", "{}"),
            ),
        )).supportsCanonicalDurableExecution())
    }

    @Test
    fun `journals a supported block child with its body path`(@TempDir tempDir: Path) = runBlocking {
        val clock = SystemClock()
        val runId = RunId("canonical-block-run")
        val journal = InMemoryOperationJournal(clock)
        val pipeline = CompiledPipeline(
            id = DefinitionId("canonical-block-pipeline"),
            source = SourceDescriptor("Pipeline.kts", Digest("source")),
            pluginLockDigest = Digest("lock"),
            stages = listOf(
                StageNode(
                    id = StageId("build"),
                    name = "build",
                    body = StageBody.Steps(
                        listOf(
                            BlockStepNode(
                                id = StepId("build/dir-body-0"),
                                pluginStepId = PluginStepId("core.dir"),
                                payload = VersionedStepPayload("dsl-v1", """{"kind":"dir","path":"${tempDir.resolve("nested")}"}"""),
                                body = listOf(
                                    OpaqueStepNode(
                                        id = StepId("build/dir-body-0/echo-0"),
                                        pluginStepId = PluginStepId("core.echo"),
                                        payload = VersionedStepPayload("dsl-v1", """{"kind":"echo","text":"nested"}"""),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val outcome = CanonicalDurableRunCoordinator(
            CanonicalNodeDispatcher(), journal, InMemoryReplayCursorStore(clock), clock,
            DefaultEffectReplayPolicy(), InMemoryEventStore(),
        ).run(pipeline, runId)

        assertEquals(RunOutcome.Success, outcome)
        assertEquals(
            OperationStatus.SUCCEEDED,
            journal.get("${runId.value}-s0-0-bp1-0:core.echo")?.status,
        )
    }

    @Test
    fun `propagates a dir block working directory to its shell child`(@TempDir tempDir: Path) = runBlocking {
        val clock = SystemClock()
        val targetDirectory = tempDir.resolve("nested")
        val pwdOracle = tempDir.resolve("child-pwd.txt")
        val pipeline = CompiledPipeline(
            id = DefinitionId("canonical-dir-working-directory"),
            source = SourceDescriptor("Pipeline.kts", Digest("source")),
            pluginLockDigest = Digest("lock"),
            stages = listOf(
                StageNode(
                    id = StageId("build"),
                    name = "build",
                    body = StageBody.Steps(
                        listOf(
                            BlockStepNode(
                                id = StepId("build/dir-body-0"),
                                pluginStepId = PluginStepId("core.dir"),
                                payload = VersionedStepPayload("dsl-v1", """{"kind":"dir","path":"$targetDirectory"}"""),
                                body = listOf(shellStep("build/dir-body-0/sh-0", "pwd > '$pwdOracle'")),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val outcome = CanonicalDurableRunCoordinator(
            CanonicalNodeDispatcher(), InMemoryOperationJournal(clock), InMemoryReplayCursorStore(clock), clock,
            DefaultEffectReplayPolicy(), InMemoryEventStore(), controlDirRoot = tempDir.resolve("control"),
        ).run(pipeline, RunId("canonical-dir-working-directory"))

        assertEquals(RunOutcome.Success, outcome)
        assertEquals(targetDirectory.toString(), Files.readString(pwdOracle).trim())
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
        // C3: First run (RERUN) emits: RunStarted + StepStarted + EchoOutputCaptured (from echo) + StepFinished + RunFinished = 5 events
        // C3: Second run (SKIP) emits: RunStarted + RunFinished = 2 events (run completed, no step events)
        // Total: 7 events
        assertEquals(7, eventStore.eventsFor(runId.value).count())
    }

    @Test
    fun `dispatch decodes each StepNode before delegating to the typed dispatcher`() = runBlocking {
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        val cursorStore = InMemoryReplayCursorStore(clock)
        val eventStore = InMemoryEventStore()
        val runId = RunId("decoder-delegation-run")

        // Pipeline with 2 echo steps - both must succeed for this test to pass.
        // If the decoder was not called, the steps would fail to decode and we
        // would get SCHEMA failures instead of success.
        val pipeline = CompiledPipeline(
            id = DefinitionId("decoder-delegation-pipeline"),
            source = SourceDescriptor("Pipeline.kts", Digest("source")),
            pluginLockDigest = Digest("lock"),
            stages = listOf(
                StageNode(
                    id = StageId("build"),
                    name = "build",
                    body = StageBody.Steps(
                        listOf(
                            OpaqueStepNode(
                                id = StepId("build/echo1"),
                                pluginStepId = PluginStepId("core.echo"),
                                payload = VersionedStepPayload("dsl-v1", """{"kind":"echo","text":"first"}"""),
                            ),
                            OpaqueStepNode(
                                id = StepId("build/echo2"),
                                pluginStepId = PluginStepId("core.echo"),
                                payload = VersionedStepPayload("dsl-v1", """{"kind":"echo","text":"second"}"""),
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

        // Both steps must succeed - this proves the decoder was called for each step
        // because the typed dispatcher only receives properly decoded commands
        assertEquals(RunOutcome.Success, outcome, "Both echo steps must succeed, proving decoder was invoked for each")
        assertEquals(2, journal.listForRun(runId.value).size, "Both steps must be journaled")
    }

    @Test
    fun `dispatch returns Failure SCHEMA and journals FAILED when decoder throws`() = runBlocking {
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        val cursorStore = InMemoryReplayCursorStore(clock)
        val eventStore = InMemoryEventStore()
        val runId = RunId("schema-failure-run")

        // Pipeline with invalid schema version - should trigger SCHEMA failure
        val pipeline = CompiledPipeline(
            id = DefinitionId("schema-failure-pipeline"),
            source = SourceDescriptor("Pipeline.kts", Digest("source")),
            pluginLockDigest = Digest("lock"),
            stages = listOf(StageNode(StageId("build"), "build", body = StageBody.Steps(listOf(
                OpaqueStepNode(
                    id = StepId("build/invalid"),
                    pluginStepId = PluginStepId("core.sh"),
                    payload = VersionedStepPayload("dsl-v0", """{"kind":"sh","command":"exit 0"}"""),
                ),
            )))),
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

        assertTrue(outcome is RunOutcome.Failure, "Outcome must be Failure")
        assertSame(FailureKind.SCHEMA, (outcome as RunOutcome.Failure).failure.kind, "Failure kind must be SCHEMA")

        // Verify journal recorded FAILED status
        val journalEntries = journal.listForRun(runId.value)
        assertEquals(1, journalEntries.size)
        assertEquals(OperationStatus.FAILED, journalEntries.single().status)
    }

    @Test
    fun `run emits StepStarted before dispatch`() = runBlocking {
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        val cursorStore = InMemoryReplayCursorStore(clock)
        val eventStore = InMemoryEventStore()
        val runId = RunId("step-started-before-dispatch")

        val coordinator = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = journal,
            cursorStore = cursorStore,
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = eventStore,
        )
        coordinator.run(echoPipeline("test"), runId)

        val events = eventStore.eventsFor(runId.value).toList()
        val stepStartedEvents = events.filterIsInstance<StepStarted>()
        assertTrue(stepStartedEvents.isNotEmpty(), "Must have at least one StepStarted event")
        val stepStarted = stepStartedEvents.first()
        assertEquals(0, stepStarted.stageIndex)
        assertEquals(0, stepStarted.stepIndex)
        assertEquals("echo", stepStarted.stepType)
    }

    @Test
    fun `run emits StepFinished after dispatch`() = runBlocking {
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        val cursorStore = InMemoryReplayCursorStore(clock)
        val eventStore = InMemoryEventStore()
        val runId = RunId("step-finished-after-dispatch")

        val coordinator = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = journal,
            cursorStore = cursorStore,
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = eventStore,
        )
        coordinator.run(echoPipeline("test"), runId)

        val events = eventStore.eventsFor(runId.value).toList()
        val stepFinishedEvents = events.filterIsInstance<StepFinished>()
        assertTrue(stepFinishedEvents.isNotEmpty(), "Must have at least one StepFinished event")
        val stepFinished = stepFinishedEvents.first()
        assertEquals(0, stepFinished.stageIndex)
        assertEquals(0, stepFinished.stepIndex)
        assertEquals("echo", stepFinished.stepType)
    }

    @Test
    fun `StepFinished count equals 1 per step on failure path`() = runBlocking {
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        val cursorStore = InMemoryReplayCursorStore(clock)
        val eventStore = InMemoryEventStore()
        val runId = RunId("step-finished-count-on-failure")

        val pipeline = CompiledPipeline(
            id = DefinitionId("failing-shell-pipeline"),
            source = SourceDescriptor("Pipeline.kts", Digest("source")),
            pluginLockDigest = Digest("lock"),
            stages = listOf(StageNode(StageId("build"), "build", body = StageBody.Steps(listOf(
                OpaqueStepNode(
                    id = StepId("build/fail"),
                    pluginStepId = PluginStepId("core.sh"),
                    payload = VersionedStepPayload("dsl-v1", """{"kind":"sh","command":"exit 42","isScriptBlock":false,"returnStdout":false}"""),
                ),
            )))),
        )

        val coordinator = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = journal,
            cursorStore = cursorStore,
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = eventStore,
        )
        coordinator.run(pipeline, runId)

        val events = eventStore.eventsFor(runId.value).toList()
        val stepStartedEvents = events.filterIsInstance<StepStarted>()
        val stepFinishedEvents = events.filterIsInstance<StepFinished>()
        val stepFailedEvents = events.filterIsInstance<StepFailed>()

        // Exactly one StepStarted and one StepFinished for the failing step
        assertEquals(1, stepStartedEvents.size, "Must have exactly 1 StepStarted for the failing step")
        assertEquals(1, stepFinishedEvents.size, "Must have exactly 1 StepFinished for the failing step")
        assertEquals(1, stepFailedEvents.size, "Must have exactly 1 StepFailed for the failing step")
        assertEquals(0, stepFailedEvents.first().stepIndex)
    }

    @Test
    fun `No step events for ReplayDecision SKIP`() = runBlocking {
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        val cursorStore = InMemoryReplayCursorStore(clock)
        val eventStore = InMemoryEventStore()
        val runId = RunId("no-events-on-skip")

        val coordinator = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = journal,
            cursorStore = cursorStore,
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = eventStore,
        )

        // First run - executes and journals
        coordinator.run(echoPipeline("test"), runId)

        // Second run - should SKIP due to memoization (echo is MEMOIZED)
        val resumedOutcome = coordinator.run(echoPipeline("test"), runId)

        assertEquals(RunOutcome.Success, resumedOutcome)

        val events = eventStore.eventsFor(runId.value).toList()
        val stepStartedEvents = events.filterIsInstance<StepStarted>()
        val stepFinishedEvents = events.filterIsInstance<StepFinished>()

        // On SKIP, no step events should be emitted
        // But first run should have emitted them
        // The SKIP path returns before emitting events
        // So for MEMOIZED steps that are skipped, there should be only 1 pair of events
        assertTrue(stepStartedEvents.size <= 1, "At most 1 StepStarted (only from first run)")
        assertTrue(stepFinishedEvents.size <= 1, "At most 1 StepFinished (only from first run)")
    }

    @Test
    fun `ReplayDecision ABORT emits one failed lifecycle without dispatching`() = runBlocking {
        val clock = SystemClock()
        val eventStore = InMemoryEventStore()
        val runId = RunId("replay-abort-lifecycle")
        val coordinator = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = InMemoryOperationJournal(clock),
            cursorStore = InMemoryReplayCursorStore(clock),
            clock = clock,
            effectReplayPolicy = object : EffectReplayPolicy {
                override fun decide(
                    replayPolicy: ReplayPolicy,
                    effects: Set<Effect>,
                    hasJournalEntry: Boolean,
                    journaledOutcome: OperationStatus?,
                ) = ReplayDecision.ABORT
            },
            eventSink = eventStore,
        )

        val outcome = coordinator.run(echoPipeline("must-not-dispatch"), runId)

        assertTrue(outcome is RunOutcome.Failure)
        val events = eventStore.eventsFor(runId.value).toList()
        assertEquals(1, events.filterIsInstance<StepStarted>().size)
        assertEquals(1, events.filterIsInstance<StepFailed>().size)
        assertEquals(1, events.filterIsInstance<StepFinished>().size)
        assertEquals(FailureKind.INFRASTRUCTURE, events.filterIsInstance<StepFailed>().single().failureKind)
    }

    @Test
    fun `Exactly-once discipline - 3 steps emits 3 StepStarted and 3 StepFinished`() = runBlocking {
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        val cursorStore = InMemoryReplayCursorStore(clock)
        val eventStore = InMemoryEventStore()
        val runId = RunId("exactly-once-3-steps")

        val pipeline = CompiledPipeline(
            id = DefinitionId("three-step-pipeline"),
            source = SourceDescriptor("Pipeline.kts", Digest("source")),
            pluginLockDigest = Digest("lock"),
            stages = listOf(StageNode(StageId("build"), "build", body = StageBody.Steps(listOf(
                OpaqueStepNode(
                    id = StepId("build/echo1"),
                    pluginStepId = PluginStepId("core.echo"),
                    payload = VersionedStepPayload("dsl-v1", """{"kind":"echo","text":"first"}"""),
                ),
                OpaqueStepNode(
                    id = StepId("build/echo2"),
                    pluginStepId = PluginStepId("core.echo"),
                    payload = VersionedStepPayload("dsl-v1", """{"kind":"echo","text":"second"}"""),
                ),
                OpaqueStepNode(
                    id = StepId("build/echo3"),
                    pluginStepId = PluginStepId("core.echo"),
                    payload = VersionedStepPayload("dsl-v1", """{"kind":"echo","text":"third"}"""),
                ),
            )))),
        )

        val coordinator = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = journal,
            cursorStore = cursorStore,
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = eventStore,
        )
        coordinator.run(pipeline, runId)

        val events = eventStore.eventsFor(runId.value).toList()
        val stepStartedEvents = events.filterIsInstance<StepStarted>()
        val stepFinishedEvents = events.filterIsInstance<StepFinished>()

        assertEquals(3, stepStartedEvents.size, "Must have exactly 3 StepStarted events for 3 steps")
        assertEquals(3, stepFinishedEvents.size, "Must have exactly 3 StepFinished events for 3 steps")

        // Verify each stepIndex appears exactly once
        val startedStepIndices = stepStartedEvents.map { it.stepIndex }.sorted()
        val finishedStepIndices = stepFinishedEvents.map { it.stepIndex }.sorted()
        assertEquals(listOf(0, 1, 2), startedStepIndices, "StepStarted must have stepIndex 0, 1, 2")
        assertEquals(listOf(0, 1, 2), finishedStepIndices, "StepFinished must have stepIndex 0, 1, 2")
    }

    @Test
    fun `milestone dispatches MilestoneReached for strictly increasing ordinals`() = runBlocking {
        val clock = SystemClock()
        val eventStore = InMemoryEventStore()
        val runId = RunId("canonical-milestone-run")
        val pipeline = CompiledPipeline(
            id = DefinitionId("canonical-milestone-pipeline"),
            source = SourceDescriptor("Pipeline.kts", Digest("source")),
            pluginLockDigest = Digest("lock"),
            stages = listOf(StageNode(StageId("build"), "build", body = StageBody.Steps(listOf(
                milestoneStep("build/milestone-1", 1, "first"),
                milestoneStep("build/milestone-2", 2, "second"),
            )))),
        )

        val outcome = CanonicalDurableRunCoordinator(
            CanonicalNodeDispatcher(), InMemoryOperationJournal(clock), InMemoryReplayCursorStore(clock), clock,
            DefaultEffectReplayPolicy(), eventStore,
        ).run(pipeline, runId)

        assertEquals(RunOutcome.Success, outcome)
        val reached = eventStore.eventsFor(runId.value)
            .filterIsInstance<dev.rubentxu.pipeline.v2.events.MilestoneReached>()
            .toList()
        assertEquals(listOf(1, 2), reached.map { it.ordinal }, "Both milestones must be reached in order")
        assertEquals(listOf("first", "second"), reached.map { it.label })
    }

    @Test
    fun `milestone out-of-order ordinal emits MilestoneAborted and continues as Unstable (record-only per Jenkins verbatim)`() = runBlocking {
        val clock = SystemClock()
        val eventStore = InMemoryEventStore()
        val runId = RunId("canonical-milestone-nonmonotonic-run")
        val pipeline = CompiledPipeline(
            id = DefinitionId("canonical-milestone-nonmonotonic-pipeline"),
            source = SourceDescriptor("Pipeline.kts", Digest("source")),
            pluginLockDigest = Digest("lock"),
            stages = listOf(StageNode(StageId("build"), "build", body = StageBody.Steps(listOf(
                milestoneStep("build/milestone-2", 2, "newest"),
                milestoneStep("build/milestone-1", 1, "older"),
            )))),
        )

        val outcome = CanonicalDurableRunCoordinator(
            CanonicalNodeDispatcher(), InMemoryOperationJournal(clock), InMemoryReplayCursorStore(clock), clock,
            DefaultEffectReplayPolicy(), eventStore,
        ).run(pipeline, runId)

        // ML-R9 T-09 local single-run semantics: record-only, never abort the run.
        // Jenkins verbatim: in local single-run there is no cross-build coordination;
        // an out-of-order milestone emits the typed MilestoneAborted event and the
        // pipeline continues as Unstable (per AGENTS.md STEP SEMANTICS — Jenkins
        // familiarity + per-step typed events).
        assertEquals(RunOutcome.Unstable, outcome,
            "A non-monotonic milestone ordinal must produce Unstable (record-only), not a typed failure")
        val reached = eventStore.eventsFor(runId.value)
            .filterIsInstance<dev.rubentxu.pipeline.v2.events.MilestoneReached>()
            .toList()
        assertEquals(listOf(2), reached.map { it.ordinal }, "Only the monotonic milestone may emit MilestoneReached")
        val aborted = eventStore.eventsFor(runId.value)
            .filterIsInstance<dev.rubentxu.pipeline.v2.events.MilestoneAborted>()
            .toList()
        assertEquals(listOf(1), aborted.map { it.ordinal },
            "The older ordinal must produce a typed MilestoneAborted event for observability")
        assertNotNull(aborted.first().reason, "MilestoneAborted must carry a reason")
    }

    private fun milestoneStep(id: String, ordinal: Int, label: String) = OpaqueStepNode(
        id = StepId(id),
        pluginStepId = PluginStepId("core.milestone"),
        payload = VersionedStepPayload(
            "dsl-v1",
            """{"kind":"milestone","ordinal":$ordinal,"label":"$label"}""",
        ),
    )

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

    private fun shellPipeline(command: String) = CompiledPipeline(
        id = DefinitionId("canonical-shell-pipeline"),
        source = SourceDescriptor("Pipeline.kts", Digest("source")),
        pluginLockDigest = Digest("lock"),
        stages = listOf(StageNode(StageId("build"), "build", body = StageBody.Steps(listOf(
            OpaqueStepNode(
                id = StepId("build/sh"),
                pluginStepId = PluginStepId("core.sh"),
                payload = VersionedStepPayload(
                    "dsl-v1",
                    """{"kind":"sh","command":"$command","isScriptBlock":false,"returnStdout":false}""",
                ),
            ),
        )))),
    )

    private fun echoStep(id: String, text: String) = OpaqueStepNode(
        id = StepId(id),
        pluginStepId = PluginStepId("core.echo"),
        payload = VersionedStepPayload("dsl-v1", """{"kind":"echo","text":"$text"}"""),
    )

    private fun shellStep(id: String, command: String) = OpaqueStepNode(
        id = StepId(id),
        pluginStepId = PluginStepId("core.sh"),
        payload = VersionedStepPayload(
            "dsl-v1",
            """{"kind":"sh","command":"$command","isScriptBlock":false,"returnStdout":false}""",
        ),
    )

    private fun emitEventStep(id: String, kind: String, vararg fields: Pair<String, String>) = OpaqueStepNode(
        id = StepId(id),
        pluginStepId = PluginStepId("core.emit.event"),
        payload = VersionedStepPayload(
            "dsl-v1",
            buildString {
                append("{\"kind\":\"").append(kind).append('"')
                fields.forEach { (name, value) ->
                    append(",\"").append(name).append("\":\"").append(value).append('"')
                }
                append('}')
            },
        ),
    )
}
