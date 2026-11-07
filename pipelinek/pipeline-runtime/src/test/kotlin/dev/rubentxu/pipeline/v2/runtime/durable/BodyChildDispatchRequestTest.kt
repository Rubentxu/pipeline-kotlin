package dev.rubentxu.pipeline.v2.runtime.durable

import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.BlockStepNode
import dev.rubentxu.pipeline.v2.domain.ExecutionContext
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.NullEventSink
import dev.rubentxu.pipeline.v2.runtime.LiveSpanParent
import dev.rubentxu.pipeline.v2.runtime.NoopPipelineTelemetry
import dev.rubentxu.pipeline.v2.runtime.SequentialIdGenerator
import dev.rubentxu.pipeline.v2.runtime.SpanScope
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * The coordinates a body child travels with when it comes back to the spine.
 *
 * ## THE GUARANTEE
 *
 * A body child re-enters the canonical spine through ONE seam, and the seam
 * carries the child's coordinates as ONE value:
 *
 * ```text
 * carried   the request the child was dispatched with names the step, the run,
 *           the stage and its index, the step's index, the options the child
 *           runs under, the durable path that tells it apart from its
 *           siblings, and the span parent it hangs under
 * ```
 *
 * The value is the one a parallel branch is dispatched with too, so a reader of
 * a dispatch reads ONE shape and not two.
 *
 * ## WHY THIS INSTRUMENT
 *
 * The seam is replaced by a recorder, because the question is not "did the
 * child work?" but "what did the child RECEIVE?". The recorder answers that,
 * and the four scopes are what put the coordinates under pressure: a nested
 * body, a retry attempt, a wait until poll and a child that fails. Each of
 * them appends a segment to the durable path or stops the body, and a
 * coordinate that arrived in the wrong slot would show up here as a path no
 * other child could claim.
 *
 * Nothing is booted: the event sink discards, the clock is fixed, and the
 * workspace points at a path that DOES NOT EXIST, because the seam never
 * touches the filesystem and the scenario needs no workspace.
 */
class BodyChildDispatchRequestTest {

    @Test
    fun `the child request carries every coordinate of the dispatch`() {
        val recorder = RecordingDispatcher()

        val outcome = runBlocking {
            engine().invokeBodyChildren(
                block = blockWithChildren("sh"),
                runId = RUN_ID,
                stageName = "build",
                stageIndex = 2,
                stepIndex = 4,
                childShOptions = options(),
                parentBodyPath = parentPath(),
                spanScope = spanScope(),
                dispatcher = recorder,
            )
        }

        assertEquals(StepOutcome.Success, outcome)
        val request = recorder.single()
        assertEquals(StepId("child-0"), request.step.id)
        assertEquals(RUN_ID, request.runId)
        assertEquals("build", request.stageName)
        assertEquals(2, request.stageIndex)
        assertEquals(4, request.stepIndex)
        assertEquals(options(), request.shOptions)
        assertEquals(
            listOf(BlockSegment(0, PluginStepId("body")), BlockSegment(0, PluginStepId("sh"))),
            request.bodyPath,
        )
        assertEquals(spanScope(), request.spanScope)
    }

    @Test
    fun `a nested body's child carries the parent's path plus its own segment`() {
        val recorder = RecordingDispatcher()

        runBlocking {
            engine().invokeBodyChildren(
                block = blockWithChildren("sh"),
                runId = RUN_ID,
                stageName = "build",
                stageIndex = 2,
                stepIndex = 4,
                childShOptions = options(),
                parentBodyPath = parentPath() + listOf(BlockSegment(1, PluginStepId("inner"))),
                spanScope = spanScope(),
                dispatcher = recorder,
            )
        }

        // The path is the child's durable identity: it tells this child apart from its
        // siblings AND from the same child of another body. A child that lost a segment
        // of the nesting would claim journal rows another child owns.
        assertEquals(
            listOf(
                BlockSegment(0, PluginStepId("body")),
                BlockSegment(1, PluginStepId("inner")),
                BlockSegment(0, PluginStepId("sh")),
            ),
            recorder.single().bodyPath,
        )
    }

    @Test
    fun `a retry attempt's child carries the attempt segment`() {
        val recorder = RecordingDispatcher()

        val outcome = runBlocking {
            engine().executeScope3b(
                scope = BlockShellScope.Retry(maxAttempts = 2),
                block = blockWithChildren("sh"),
                runId = RUN_ID,
                stageName = "build",
                stageIndex = 2,
                stepIndex = 4,
                childShOptions = options(),
                parentBodyPath = parentPath(),
                spanScope = spanScope(),
                dispatcher = recorder,
                bodyInvokerAdapter = BodyInvokerAdapter(),
            )
        }

        assertEquals(StepOutcome.Success, outcome)
        // The attempt travels between the parent's path and the child's own segment, so
        // every attempt owns its own rows under exactly-once semantics.
        assertEquals(
            listOf(
                BlockSegment(0, PluginStepId("body")),
                BlockSegment(1, PluginStepId("retry-attempt")),
                BlockSegment(0, PluginStepId("sh")),
            ),
            recorder.single().bodyPath,
        )
    }

    @Test
    fun `a wait until poll's child carries the poll segment`() {
        val recorder = RecordingDispatcher()

        val outcome = runBlocking {
            engine().executeWaitUntilInline(
                scope = BlockShellScope.WaitUntilScope(
                    initialRecurrencePeriod = 0L,
                    maxBackoffMs = 60_000L,
                ),
                block = blockWithChildren("sh"),
                runId = RUN_ID,
                stageName = "build",
                stageIndex = 2,
                stepIndex = 4,
                childShOptions = options(),
                parentBodyPath = parentPath(),
                spanScope = spanScope(),
                dispatcher = recorder,
            )
        }

        assertEquals(StepOutcome.Success, outcome)
        // The poll is an attempt of its own, and it owns its own rows: the segment is
        // the poll's number and the poll's own kind.
        assertEquals(
            listOf(
                BlockSegment(0, PluginStepId("body")),
                BlockSegment(1, PluginStepId("wait-until-poll")),
                BlockSegment(0, PluginStepId("sh")),
            ),
            recorder.single().bodyPath,
        )
    }

    @Test
    fun `a child that fails ends the body and the next child is never dispatched`() {
        val recorder = RecordingDispatcher(
            result = StepOutcome.Failure(
                PipelineFailure(FailureKind.SCRIPT, "the child refused"),
            ),
        )

        val outcome = runBlocking {
            engine().invokeBodyChildren(
                block = blockWithChildren("sh", "sh"),
                runId = RUN_ID,
                stageName = "build",
                stageIndex = 2,
                stepIndex = 4,
                childShOptions = options(),
                parentBodyPath = parentPath(),
                spanScope = spanScope(),
                dispatcher = recorder,
            )
        }

        // A cancellation reaches the seam as a failure of the child, and the body stops
        // on it: the second child is never dispatched, and the failure travels back.
        val failure = assertInstanceOf(StepOutcome.Failure::class.java, outcome).failure
        assertEquals(FailureKind.SCRIPT, failure.kind)
        assertEquals("the child refused", failure.message)
        assertEquals(1, recorder.requests.size, "the body stopped on the first failure")
    }

    // --- the fixtures ------------------------------------------------------

    private fun engine(): BodyExecutionEngine = BodyExecutionEngine(
        eventSink = NullEventSink,
        clock = FixedClock,
        idGenerator = SequentialIdGenerator(),
        telemetry = NoopPipelineTelemetry,
    )

    private fun options(): ShOptions = ShOptions(
        // The seam never touches the filesystem; the path is a value, and the
        // scenario needs no workspace.
        workspaceRoot = Path.of("/nonexistent/pipelinek-workspace"),
        captureStdout = false,
        timeoutMs = null,
        env = emptyMap(),
    )

    private fun spanScope(): SpanScope = SpanScope(ExecutionContext.EMPTY, LiveSpanParent.Root)

    private fun parentPath(): List<BlockSegment> = listOf(BlockSegment(0, PluginStepId("body")))

    /** A block whose body holds the given children, in declaration order. */
    private fun blockWithChildren(vararg kinds: String): BlockStepNode = BlockStepNode(
        id = StepId("body"),
        pluginStepId = PluginStepId("core.block"),
        payload = VersionedStepPayload(schemaVersion = "1", encoded = "{}"),
        body = kinds.mapIndexed { index, kind ->
            OpaqueStepNode(
                id = StepId("child-$index"),
                pluginStepId = PluginStepId(kind),
                payload = VersionedStepPayload(schemaVersion = "1", encoded = "{}"),
            )
        },
    )

    /**
     * The seam, replaced by a recorder.
     *
     * It keeps every request it was handed, so a row can say how many children the
     * body dispatched and what each of them received.
     */
    private class RecordingDispatcher(
        private val result: StepOutcome = StepOutcome.Success,
    ) : BodyChildDispatcher {

        val requests: MutableList<StepDispatchRequest> = mutableListOf()

        override suspend fun dispatchChild(request: StepDispatchRequest): StepOutcome {
            requests += request
            return result
        }

        /** The one request the row expects; a second one is a fact the row must see. */
        fun single(): StepDispatchRequest = requests.single()
    }

    /** A clock that never moves; the rows assert on coordinates, not on instants. */
    private object FixedClock : Clock {
        override fun now(): Instant = Instant.EPOCH
    }

    private companion object {
        val RUN_ID = RunId("run-1")
    }
}
