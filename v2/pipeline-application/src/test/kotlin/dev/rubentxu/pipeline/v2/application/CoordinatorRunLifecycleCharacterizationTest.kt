package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeFailure
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeOutcome
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
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * WU-PR-017 slice 0: CHARACTERIZATION BASELINE of the run lifecycle, pinned
 * BEFORE any extraction moves it into a RunLifecycleEngine.
 *
 * The extraction contract is bit-equivalence of everything observable:
 *
 *  - the exact EVENT KIND SEQUENCE for a happy path and for a failing stage;
 *  - the stage/step indexes carried by those events;
 *  - the terminal [RunOutcome] (including the failure kind and message);
 *  - the deterministic journal operation ids;
 *  - the correlation invariant: RunFinished exists iff RunStarted was emitted,
 *    exactly once, even when a stage fails mid-run.
 *
 * These assertions pin today's behavior, including its quirks (lifecycle
 * events always carry sequence 0; ordering, not monotony, is the observable
 * contract). The extracted `RunLifecycleEngine` must satisfy them UNCHANGED.
 */
@Timeout(60)
class CoordinatorRunLifecycleCharacterizationTest {

    private val echoKey = PluginStepId("core.echo")
    private val errorKey = PluginStepId("core.error")

    private fun harness(): Triple<CanonicalDurableRunCoordinator, InMemoryOperationJournal, InMemoryEventStore> {
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        val events = InMemoryEventStore()
        val coordinator = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = journal,
            cursorStore = InMemoryReplayCursorStore(clock),
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = events,
            credentialScopePort = { _, _ ->
                CredentialScopeOutcome.Unavailable(
                    CredentialScopeFailure.StoreUnavailable("lifecycle characterization stub"),
                )
            },
            controlDirRoot = Files.createTempDirectory("lifecycle-char-").resolve("control"),
            shOptions = ShOptions.EMPTY,
            stepRegistry = InMemoryStepRegistry().apply {
                CoreEchoStep.registerInto(this)
                CoreErrorStep.registerInto(this)
            },
        )
        return Triple(coordinator, journal, events)
    }

    private fun echo(stage: String, index: Int, text: String) = OpaqueStepNode(
        id = StepId("$stage/echo-$index"),
        pluginStepId = echoKey,
        payload = VersionedStepPayload("dsl-v1", "{\"kind\":\"echo\",\"text\":\"$text\"}"),
    )

    private fun userError(stage: String, index: Int, message: String) = OpaqueStepNode(
        id = StepId("$stage/error-$index"),
        pluginStepId = errorKey,
        payload = VersionedStepPayload(
            "dsl-v1",
            "{\"kind\":\"error\",\"message\":\"$message\",\"failureKind\":\"USER\"}",
        ),
    )

    private fun stage(name: String, vararg steps: StepNode) = StageNode(
        id = StageId(name),
        name = name,
        body = StageBody.Steps(steps.toList()),
    )

    private fun pipelineOf(vararg stages: StageNode) = CompiledPipeline(
        id = DefinitionId("lifecycle-characterization"),
        source = SourceDescriptor("Pipeline.kts", Digest("source")),
        pluginLockDigest = Digest("lock"),
        stages = stages.toList(),
    )

    private fun eventKinds(events: InMemoryEventStore, runId: String) =
        events.eventsFor(runId).map { it.kind }.toList()

    @Test
    fun `happy path - the lifecycle event sequence is pinned`() = runBlocking {
        val (coordinator, journal, events) = harness()

        val outcome = coordinator.run(
            pipelineOf(
                stage("S1", echo("s1", 0, "one")),
                stage("S2", echo("s2", 0, "two")),
            ),
            RunId("lc1"),
        )

        assertEquals(RunOutcome.Success, outcome)
        assertEquals(
            listOf(
                "RunStarted",
                "StageStarted", "StepStarted", "EchoOutputCaptured", "StepFinished", "StageFinished",
                "StageStarted", "StepStarted", "EchoOutputCaptured", "StepFinished", "StageFinished",
                "RunFinished",
            ),
            eventKinds(events, "lc1"),
            "the lifecycle event ordering IS the extraction contract",
        )
        assertEquals(
            listOf("lc1-s0-0", "lc1-s1-0"),
            journal.listForRun("lc1").map { it.id },
            "journal operation ids are deterministic per stage/step",
        )
        val runFinished = events.eventsFor("lc1").last() as dev.rubentxu.pipeline.v2.events.RunFinished
        assertEquals("success", runFinished.outcome)
    }

    @Test
    fun `core error child - typed failure folds into the run outcome`() = runBlocking {
        val (coordinator, journal, events) = harness()

        val outcome = coordinator.run(
            pipelineOf(stage("ERR", userError("err", 0, "injected failure"), echo("err", 1, "never"))),
            RunId("lc3"),
        )

        assertTrue(outcome is RunOutcome.Failure, "a core.error child must fail the run")
        assertTrue(
            (outcome as RunOutcome.Failure).failure.message.contains("injected failure"),
            "the typed failure message must surface verbatim, got ${outcome.failure}",
        )
        assertEquals(
            listOf(
                "RunStarted",
                "StageStarted", "StepStarted", "StepFailed", "StepFinished",
                "RunFinished",
            ),
            eventKinds(events, "lc3"),
            "a failing child emits StepFailed then StepFinished and the run closes WITHOUT a " +
                "StageFinished (today's shape); the echo AFTER it never runs",
        )
        val last = events.eventsFor("lc3").last()
        assertEquals("failure", (last as dev.rubentxu.pipeline.v2.events.RunFinished).outcome)
        assertEquals(
            listOf("lc3-s0-0"),
            journal.listForRun("lc3").map { it.id },
            "the step after the failure must have no journal row: lc3-s0-1 never executed",
        )
    }

    @Test
    fun `mid-run failure - RunStarted and RunFinished stay exactly once`() = runBlocking {
        val (coordinator, _, events) = harness()

        val outcome = coordinator.run(
            pipelineOf(
                stage("OK", echo("ok", 0, "fine")),
                stage("BAD", userError("bad", 0, "mid-run failure")),
                stage("NEVER", echo("never", 0, "unreachable")),
            ),
            RunId("lc4"),
        )

        assertTrue(outcome is RunOutcome.Failure)
        val kinds = eventKinds(events, "lc4")
        assertEquals("RunStarted", kinds.first(), "the run opens with RunStarted")
        assertEquals("RunFinished", kinds.last(), "RunFinished exists iff RunStarted was emitted")
        assertEquals(
            1,
            kinds.count { it == "RunFinished" },
            "exactly one RunFinished per run",
        )
        // Three stages DECLARED; only two StageStarted may appear (NEVER never starts).
        assertEquals(
            2,
            kinds.count { it == "StageStarted" },
            "a stage after a failed one must never start",
        )
    }
}
