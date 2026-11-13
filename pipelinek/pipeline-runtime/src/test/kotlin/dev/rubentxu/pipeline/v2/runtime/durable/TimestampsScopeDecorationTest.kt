package dev.rubentxu.pipeline.v2.runtime.durable

import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.BlockStepNode
import dev.rubentxu.pipeline.v2.domain.ExecutionContext
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.runtime.LiveSpanParent
import dev.rubentxu.pipeline.v2.runtime.NoopPipelineTelemetry
import dev.rubentxu.pipeline.v2.runtime.SequentialIdGenerator
import dev.rubentxu.pipeline.v2.runtime.SpanScope
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The `timestamps` scope, and what it does to the output of the body it wraps.
 *
 * ## The guarantee under trial
 *
 * That the scope ANNOUNCES itself and DECORATES nothing: it publishes the
 * pair of bookend events of the scope, and the body it runs produces exactly
 * the output it would produce outside the scope.
 *
 * ## Why the guarantee is worth measuring
 *
 * Because the name of the scope and its KDoc promised a decoration that this
 * product does not implement: Jenkins prefixes each line of the output with
 * the time it was produced, and the shape that carries the promise here —the
 * `StepSpec.Timestamps` of the plan and the `TimestampsScope` of the engine—
 * has nowhere to put one. The options the child runs under carry no
 * decoration, and the child receives the very options the scope received.
 *
 * The row measures that fact at the seam where a decoration would have to
 * appear: the projection of the scope onto the child's execution surface.
 * A decoration would have to travel in those options, and there is no field
 * of [ShOptions] that could carry it.
 *
 * ## What this file does NOT affirm
 *
 * It does not affirm that the scope is useless: the bookends it publishes are
 * the observation the run keeps of the scope, and they are what a reader of the
 * trace uses. It affirms that the observation is the WHOLE of what the scope
 * does to the output.
 */
class TimestampsScopeDecorationTest {

    @Test
    fun `the timestamps scope announces itself and decorates nothing`() {
        val sink = RecordingEventSink()
        val engine = engine(sink)
        val options = options()

        // The projection of the scope: this is where a decoration would have to
        // enter the surface the child runs under.
        val scoped = engine.projectScope(
            scope = BlockShellScope.TimestampsScope,
            block = block(),
            runId = RUN_ID,
            stageIndex = 0,
            stepIndex = 0,
            stageShOptions = options,
            context = ExecutionContext.EMPTY,
        )

        assertEquals(
            options,
            scoped.shOptions,
            "the scope changed the surface the body runs under: a scope that decorates has to " +
                "carry the decoration in it, and the surface it projected does not carry one",
        )

        // The execution of the body under that scope: the bookend of the exit
        // is published here, and the child is dispatched with the options.
        val recorder = RecordingDispatcher()
        val outcome = runBlocking {
            engine.executeScope3b(
                scope = BlockShellScope.TimestampsScope,
                block = block(),
                runId = RUN_ID,
                stageName = "stage",
                stageIndex = 0,
                stepIndex = 0,
                childShOptions = options,
                parentBodyPath = listOf(BlockSegment(0, PluginStepId("body"))),
                spanScope = SpanScope(ExecutionContext.EMPTY, LiveSpanParent.Root),
                dispatcher = recorder,
                bodyInvokerAdapter = BodyInvokerAdapter(),
            )
        }

        assertEquals(StepOutcome.Success, outcome)
        assertEquals(
            options,
            recorder.single().shOptions,
            "the child did not run under the options of the scope: the surface the output is " +
                "produced under is not the one the scope published",
        )
        assertEquals(
            listOf("TimestampsEntered", "TimestampsExited"),
            sink.events.map { event -> event::class.simpleName },
            "the scope announces itself with the pair of bookends and nothing else: those are " +
                "the events the run keeps of the scope. The events it published are " +
                "${sink.events.map { event -> event::class.simpleName }}",
        )
    }

    // --- the fixtures ------------------------------------------------------

    private fun engine(sink: EventSink): BodyExecutionEngine = BodyExecutionEngine(
        eventSink = sink,
        clock = FixedClock,
        idGenerator = SequentialIdGenerator(),
        telemetry = NoopPipelineTelemetry,
    )

    private fun options(): ShOptions = ShOptions(
        // The seam never touches the filesystem; the path is a value, and the
        // scenario needs no workspace.
        workspaceRoot = Path.of("/nonexistent/pipelinek-workspace"),
        captureStdout = true,
        timeoutMs = null,
        env = emptyMap(),
    )

    private fun block(): BlockStepNode = BlockStepNode(
        id = StepId("body"),
        pluginStepId = PluginStepId("core.block"),
        payload = VersionedStepPayload(schemaVersion = "1", encoded = "{}"),
        body = listOf(
            OpaqueStepNode(
                id = StepId("child-0"),
                pluginStepId = PluginStepId("sh"),
                payload = VersionedStepPayload(schemaVersion = "1", encoded = "{}"),
            ),
        ),
    )

    /**
     * The seam, replaced by a recorder.
     *
     * It keeps the request it was handed, so a row can say what the child
     * RECEIVED: the options are where a decoration would have to arrive.
     */
    private class RecordingDispatcher : BodyChildDispatcher {

        val requests: MutableList<StepDispatchRequest> = mutableListOf()

        override suspend fun dispatchChild(request: StepDispatchRequest): StepOutcome {
            requests += request
            return StepOutcome.Success
        }

        /** The one request the row expects; a second one is a fact the row must see. */
        fun single(): StepDispatchRequest = requests.single()
    }

    /** An event sink that keeps whatever it is given. */
    private class RecordingEventSink : EventSink {

        val events: MutableList<DomainEvent> = mutableListOf()

        override fun append(event: DomainEvent) {
            events += event
        }

        override fun eventsFor(runId: String): Sequence<DomainEvent> = events.asSequence()
    }

    /** A clock that never moves; the rows assert on the surface, not on instants. */
    private object FixedClock : Clock {
        override fun now(): Instant = Instant.EPOCH
    }

    private companion object {
        val RUN_ID = RunId("run-1")
    }
}
