package dev.rubentxu.pipeline.v2.application.durable.waituntil

import dev.rubentxu.pipeline.v2.application.durable.WaitUntilControlJournal
import dev.rubentxu.pipeline.v2.application.durable.WaitUntilControlState
import dev.rubentxu.pipeline.v2.application.observation.HumanConsoleRenderer
import dev.rubentxu.pipeline.v2.application.observation.ObservationView
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.WaitUntilControlRowSnapshot
import dev.rubentxu.pipeline.v2.domain.step.BodyInvocationContext
import dev.rubentxu.pipeline.v2.domain.step.BodyInvoker
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodyRef
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.WaitUntilPolled
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The guarantee: a poll of `waitUntil` publishes ONE `WaitUntilPolled`, and that event carries
 * the poll's own answer.
 *
 * The defect this file guards was a MIRROR: the engine published a `WaitUntilPolled` before
 * evaluating the predicate — `durationMs = 0L`, `conditionResult = false` — and then published
 * the real one after the body ran. Every attempt therefore published TWO events, and the first
 * one answered for a poll that had not happened yet: it claimed a duration of zero and a verdict
 * of "not satisfied" for work that was still to be done. The console projects one line per
 * event, so the reader saw two lines per attempt, the first of which was a lie about the poll
 * that followed it.
 *
 * The row below asserts the count AND the console text in the SAME row, because the console is
 * where the double line reached the user: a fix that corrected the event but left the projection
 * alone would still show the reader two attempts' worth of lines for one attempt.
 *
 * ## Why the row enters through the RESUME path
 *
 * A fresh `waitUntil` sleeps for its initial interval before its first poll, and a unit test may
 * not sleep. The row therefore starts from a journal that already holds an in-flight attempt,
 * which the reconciler answers with `ResumeAttempt` — the same attempt, resumed, without paying
 * the interval again. Both arrivals reach the SAME emission site (the engine emits inside the
 * `ScheduleAttempt | ResumeAttempt` branch), so the row covers the site, not one of its two
 * doors.
 *
 * ## What this row deliberately does NOT assert
 *
 * `durationMs` and `occurredAt` are wall-clock measures taken with `System.currentTimeMillis()`
 * and `Instant.now()`. A unit row that cannot sleep and may not reach for a real clock cannot
 * pin them, and an assertion that cannot fail is not an assertion. The row therefore says
 * nothing about them rather than pretending to; what it does say is the count and the verdict,
 * which are the facts the defect falsified.
 */
class WaitUntilPollEventTest {

    @Test
    fun `un intento de waitUntil publica un solo WaitUntilPolled con el veredicto evaluado`() {
        val fingerprint = Fingerprint("a".repeat(64))
        val sink = RecordingEventSink()
        val engine = WaitUntilEngine(
            journal = InFlightAttemptJournal(
                WaitUntilControlRowSnapshot(
                    attempt = 1,
                    status = OperationStatus.RUNNING,
                    currentBackoffMs = 1_000L,
                    fingerprint = fingerprint,
                ),
            ),
            eventSink = sink,
            bodyInvoker = SatisfyingBodyInvoker(),
            controlOpId = "wait-until-op",
            parentBodyPath = emptyList(),
            fingerprint = fingerprint,
            initialRecurrencePeriodMs = 1_000L,
            maxBackoffMs = 60_000L,
            runId = RunId("run-1"),
            stageIndex = 0,
            stepIndex = 0,
            blockId = StepId("step-1"),
            blockPluginStepId = PluginStepId("core.waitUntil"),
        )

        val outcome = runBlocking { engine.execute(BodyRef("body/wait-until")) }

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

        // The console is where the double line reached the user: one line per attempt.
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

    /**
     * A journal that holds one in-flight attempt and records what the engine wrote.
     *
     * It reads back the SAME row it was given, so the planner keeps answering `ResumeAttempt`
     * and the row under test never waits for an interval. The writes are recorded because a fake
     * that silently dropped them would make the engine's persistence invisible; nothing here
     * asserts on them, because the guarantee under test is the EVENT, not the journal.
     */
    private class InFlightAttemptJournal(
        private val row: WaitUntilControlRowSnapshot,
    ) : WaitUntilControlJournal {

        val writes = mutableListOf<String>()

        override fun beginAttempt(
            controlOpId: String,
            attempt: Int,
            currentBackoffMs: Long,
            fingerprint: Fingerprint,
            status: OperationStatus,
        ) {
            writes += "beginAttempt(attempt=$attempt, status=$status)"
        }

        override fun updateStatus(
            controlOpId: String,
            attempt: Int,
            status: OperationStatus,
            fingerprint: Fingerprint,
        ) {
            writes += "updateStatus(attempt=$attempt, status=$status)"
        }

        override fun readState(
            controlOpId: String,
            initialRecurrencePeriodMs: Long,
            maxBackoffMs: Long,
            currentFingerprint: Fingerprint,
        ): WaitUntilControlState = WaitUntilControlState(controlRows = listOf(row))
    }

    /** A body whose predicate holds. */
    private class SatisfyingBodyInvoker : BodyInvoker {
        override suspend fun invoke(body: BodyRef, context: BodyInvocationContext): BodyOutcome =
            BodyOutcome.Completed(StepOutcome.Success)
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
