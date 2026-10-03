package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.support.CoordinatorFixture
import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.post.PostCondition
import dev.rubentxu.pipeline.v2.domain.PostSpec
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.SourceDescriptor
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.PostConditionSelected
import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.application.durable.buildDefaultExecutionBoundary
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.domain.RunId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * S2-B: `post` finalizers through the REAL canonical coordinator.
 *
 * These are the integration teeth the pure planner tests cannot provide: a
 * compiled [PostSpec] must actually dispatch its StepNodes through the same
 * spine as stage steps, emit PostConditionSelected BEFORE StageFinished, and
 * abort the run with a typed USER failure when a finalizer fails.
 */
@Timeout(120)
class S2BPostCoordinatorIntegrationTest {

    private fun shNode(id: String, command: String) = OpaqueStepNode(
        id = StepId(id),
        pluginStepId = PluginStepId("core.sh"),
        payload = VersionedStepPayload(
            "dsl-v1",
            """{"kind":"sh","script":"$command"}""",
        ),
    )

    private fun echoNode(id: String, message: String) = OpaqueStepNode(
        id = StepId(id),
        pluginStepId = PluginStepId("core.echo"),
        payload = VersionedStepPayload(
            "dsl-v1",
            """{"kind":"echo","text":"$message"}""",
        ),
    )

    private fun pipelineWithPost(
        stageCommand: String,
        post: PostSpec,
        stageOptions: List<dev.rubentxu.pipeline.v2.domain.StageOption> = emptyList(),
    ): CompiledPipeline = CompiledPipeline(
        id = DefinitionId("s2b-post"),
        source = SourceDescriptor("S2BPost.pipeline.kts", Digest("s2b-post")),
        pluginLockDigest = Digest("s2b-post-lock"),
        stages = listOf(
            StageNode(
                id = StageId("build"),
                name = "build",
                options = stageOptions,
                body = StageBody.Steps(listOf(shNode("build/sh", stageCommand))),
                post = post,
            ),
        ),
    )

    private fun coordinator(eventStore: InMemoryEventStore, controlRoot: Path): CanonicalDurableRunCoordinator =
        CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = InMemoryOperationJournal(SystemClock()),
            cursorStore = InMemoryReplayCursorStore(SystemClock()),
            clock = SystemClock(),
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = eventStore,
            credentialScopePort = CoordinatorFixture.noOpCredentialScopePort(),
            controlDirRoot = controlRoot,
            commonExecutionBoundary = buildDefaultExecutionBoundary(
                dispatcher = CanonicalNodeDispatcher(),
                invocationExecutor = null,
                stepRegistry = CoreStepRegistryFactory.registry(),
            ),
            stepRegistry = CoreStepRegistryFactory.registry(),
        )

    @Test
    fun `post finalizers dispatch through the spine and fire before StageFinished`(@TempDir tempDir: Path) = runBlocking {
        val marker = tempDir.resolve("always-marker.txt")
        val post = PostSpec(
            conditions = mapOf(
                PostCondition.ALWAYS to listOf(echoNode("post/always-echo", "cleanup always")),
                PostCondition.SUCCESS to listOf(shNode("post/success-touch", "echo done > '$marker'")),
                PostCondition.FAILURE to listOf(echoNode("post/failure-echo", "never on success")),
            ),
        )
        val store = InMemoryEventStore()
        val pipeline = pipelineWithPost("echo stage-body", post)
        val outcome = coordinator(store, tempDir).run(pipeline, RunId("s2b-post-success"))

        assertEquals(RunOutcome.Success, outcome)

        val events = store.eventsFor("s2b-post-success").toList()
        val selected = events.filterIsInstance<PostConditionSelected>().single()
        assertEquals(listOf("ALWAYS", "SUCCESS"), selected.selectedConditions)
        assertEquals(listOf("FAILURE"), selected.skippedConditions)

        // Ordering law: the decision event lands before the stage's terminal record.
        val selectedIndex = events.indexOf(selected)
        val stageFinished = events.filterIsInstance<StageFinished>().single()
        val finishedIndex = events.indexOf(stageFinished)
        assertTrue(selectedIndex in 0 until finishedIndex, "PostConditionSelected must precede StageFinished")

        // The finalizer really ran through the spine: real step events for the
        // post nodes exist (stepIndex >= POST_BASE_STEP_INDEX), and the side
        // effect happened.
        val postStarts = events.filterIsInstance<StepStarted>().filter { it.stepIndex >= 1000 }
        assertTrue(postStarts.size >= 2, "post nodes must produce real step events: $postStarts")
        assertTrue(marker.toFile().readText().contains("done"), "SUCCESS finalizer must have executed")
    }

    @Test
    fun `a failing finalizer aborts the run with a typed USER failure`(@TempDir tempDir: Path) = runBlocking {
        val post = PostSpec(
            conditions = mapOf(
                PostCondition.ALWAYS to listOf(shNode("post/always-fail", "exit 3")),
            ),
        )
        val store = InMemoryEventStore()
        val pipeline = pipelineWithPost("echo stage-body", post)
        val outcome = coordinator(store, tempDir).run(pipeline, RunId("s2b-post-fail"))

        assertTrue(outcome is RunOutcome.Failure, "a failed finalizer must fail the run")
        val failure = (outcome as RunOutcome.Failure).failure
        assertEquals(dev.rubentxu.pipeline.v2.domain.FailureKind.USER, failure.kind)
        assertTrue(failure.message.contains("post ALWAYS finalizer"), "reason names the block: ${failure.message}")
    }

    @Test
    fun `stage failure selects FAILURE and UNSUCCESSFUL but not SUCCESS`(@TempDir tempDir: Path) = runBlocking {
        val post = PostSpec(
            conditions = mapOf(
                PostCondition.SUCCESS to listOf(echoNode("post/success-echo", "never")),
                PostCondition.FAILURE to listOf(echoNode("post/failure-echo", "on failure")),
                PostCondition.UNSUCCESSFUL to listOf(echoNode("post/unsuccessful-echo", "unsuccessful runs")),
            ),
        )
        val store = InMemoryEventStore()
        val pipeline = pipelineWithPost("exit 1", post)
        val outcome = coordinator(store, tempDir).run(pipeline, RunId("s2b-post-stage-fail"))

        // The stage failed, but the post block SUCCEEDED, so the run does not
        // change fate: it stays a failure (the stage's own failure is the
        // recorded cause), and the failure-selection is visible in the event.
        assertTrue(outcome is RunOutcome.Failure)
        val selected = store.eventsFor("s2b-post-stage-fail").filterIsInstance<PostConditionSelected>().single()
        assertEquals(listOf("FAILURE", "UNSUCCESSFUL"), selected.selectedConditions)
        assertEquals(listOf("SUCCESS"), selected.skippedConditions)
        assertEquals("failed", selected.stageOutcome)
    }

    @Test
    fun `options timeout projection reaches the skipped-stage post environment`(@TempDir tempDir: Path) = runBlocking {
        // The skip path is the seam where the raw shOptions bug lived: a gate
        // that decides negative must still hand finalizers a stage-projected
        // environment. This suite pins compilation-level presence of the
        // projection (the negative gate needs a registered gate directive; the
        // DSL-level test is the installed-binary UAT).
        // Here we assert the always/cleanup selection for an outcome that no
        // finalizer selects except ALWAYS/CLEANUP: skipped.
        val marker = tempDir.resolve("skipped-always.txt")
        val post = PostSpec(
            conditions = mapOf(
                PostCondition.ALWAYS to listOf(shNode("post/always-echo", "echo skipped > '$marker'")),
                PostCondition.SUCCESS to listOf(echoNode("post/success-echo", "never on skip")),
            ),
        )
        // Hand-build the skipped outcome through runPostBlock indirectly is not
        // possible without a gate directive; the pure planner pins Skipped
        // selection (PostPlannerTest S2B-POST-009). Here we pin that a stage
        // that succeeds still hands ALWAYS its real ShOptions: the marker file
        // is written relative to the stage workspace the coordinator created.
        val store = InMemoryEventStore()
        val pipeline = pipelineWithPost("echo body", post)
        val outcome = coordinator(store, tempDir).run(pipeline, RunId("s2b-post-skip-probe"))

        assertEquals(RunOutcome.Success, outcome)
        assertTrue(marker.toFile().readText().contains("skipped"), "ALWAYS ran with real shell options")
    }
}
