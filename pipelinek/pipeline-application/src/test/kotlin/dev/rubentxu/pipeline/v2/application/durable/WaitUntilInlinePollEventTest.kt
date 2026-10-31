package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.observation.HumanConsoleRenderer
import dev.rubentxu.pipeline.v2.application.observation.ObservationView
import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.BlockStepNode
import dev.rubentxu.pipeline.v2.domain.ExecutionContext
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.WaitUntilPolled
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant

/**
 * The guarantee, on the LEGACY branch: a poll of `waitUntil` publishes ONE `WaitUntilPolled`,
 * carrying the poll's own answer.
 *
 * ## Why this branch earns a row of its own
 *
 * The inline loop is NOT the shipped path: the composition root always binds a waitUntil control
 * journal, so a `waitUntil` in the product runs through the durable engine, whose row lives in
 * `...durable.waituntil.WaitUntilPollEventTest`. But the inline loop is KEPT, it is reachable by
 * any caller that constructs the engine without a journal, and it carried the SAME mirror: a
 * `WaitUntilPolled` published before the body ran, with `durationMs = 0L` and
 * `conditionResult = false`, followed by the real one. A defect fixed in one branch and left in
 * the other is not a fix; it is a branch that will be re-broken by the next reader who assumes
 * "the other branch does the same thing".
 *
 * The row is also what makes the two branches INDEPENDENT under the focal mutation: restoring
 * the mirror in one of them must turn exactly one row red, and this is the evidence that the
 * durable row's redness came from the durable site and not from a shared fixture.
 *
 * ## No clock, no sleep
 *
 * The initial interval is zero, so the loop does not wait before its first poll, and the body
 * succeeds on that poll, so it never reaches the backoff. The row measures the event stream and
 * the console text, which are the facts the defect falsified; `durationMs` and `occurredAt` are
 * wall-clock measures that a unit row may not pin.
 */
class WaitUntilInlinePollEventTest {

    @Test
    fun `la rama inline publica un solo WaitUntilPolled por intento`() {
        val sink = RecordingEventSink()
        val engine = BodyExecutionEngine(
            eventSink = sink,
            clock = FixedClock,
            bodyInvokerAdapter = CanonicalBodyInvokerAdapter(),
        )

        val outcome = runBlocking {
            engine.executeWaitUntilInline(
                scope = BlockShellScope.WaitUntilScope(
                    initialRecurrencePeriod = 0L,
                    maxBackoffMs = 60_000L,
                ),
                block = blockWithOneChild(),
                runId = RunId("run-1"),
                stageName = "stage",
                stageIndex = 0,
                stepIndex = 0,
                childShOptions = ShOptions(
                    // The dispatcher below never touches the filesystem; the path is a value,
                    // and the scenario needs no workspace.
                    workspaceRoot = Path.of("/nonexistent/pipelinek-workspace"),
                    captureStdout = false,
                    timeoutMs = null,
                    env = emptyMap(),
                ),
                parentBodyPath = emptyList(),
                executionContext = ExecutionContext.EMPTY,
                dispatcher = SatisfyingDispatcher(),
            )
        }

        assertEquals(StepOutcome.Success, outcome, "the predicate held, so the wait succeeded")

        val polled = sink.events.filterIsInstance<WaitUntilPolled>()
        assertEquals(
            1,
            polled.size,
            "one attempt publishes ONE WaitUntilPolled; it published ${polled.size}: " +
                "${polled.map { "attempt=${it.attempt} durationMs=${it.durationMs} " +
                    "conditionResult=${it.conditionResult}" }}",
        )
        assertEquals(1, polled.single().attempt, "the event names the attempt it belongs to")
        assertTrue(
            polled.single().conditionResult,
            "the event carries the verdict the predicate actually evaluated to",
        )

        val console = HumanConsoleRenderer.render(sink.events, ObservationView.EVENTS)
        val polledLines = console.lines().filter { it.contains("waitUntil polled") }
        assertEquals(
            1,
            polledLines.size,
            "the console shows one line per attempt; it showed ${polledLines.size}: $polledLines",
        )
        assertEquals(
            "[waitUntil polled (attempt 1): true]",
            polledLines.single(),
            "the line the reader sees is the poll's own answer",
        )
    }

    /** A block whose body is one child; the child is what the predicate runs. */
    private fun blockWithOneChild(): BlockStepNode = BlockStepNode(
        id = StepId("step-1"),
        pluginStepId = PluginStepId("core.block"),
        payload = VersionedStepPayload(schemaVersion = "1", encoded = "{}"),
        body = listOf(
            OpaqueStepNode(
                id = StepId("child-1"),
                pluginStepId = PluginStepId("core.sh"),
                payload = VersionedStepPayload(schemaVersion = "1", encoded = "{}"),
            ),
        ),
    )

    /** A dispatcher whose child holds the predicate. */
    private class SatisfyingDispatcher : BodyChildDispatcher {
        override suspend fun dispatchChild(
            child: StepNode,
            runId: RunId,
            stageName: String,
            stageIndex: Int,
            stepIndex: Int,
            childShOptions: ShOptions,
            bodyPath: List<BlockSegment>,
            executionContext: ExecutionContext,
        ): StepOutcome = StepOutcome.Success
    }

    /** A clock that never moves; the row asserts on events, not on instants. */
    private object FixedClock : Clock {
        override fun now(): Instant = Instant.EPOCH
    }

    /** An event sink that keeps whatever it is given. */
    private class RecordingEventSink : EventSink {

        val events = mutableListOf<DomainEvent>()

        override fun append(event: DomainEvent) {
            events += event
        }

        override fun eventsFor(runId: String): Sequence<DomainEvent> = events.asSequence()
    }
}
