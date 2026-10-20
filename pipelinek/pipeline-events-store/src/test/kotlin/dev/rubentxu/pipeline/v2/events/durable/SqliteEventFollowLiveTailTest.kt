package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.events.EventRecordRead
import dev.rubentxu.pipeline.v2.events.EventRecordSlice
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.follow.EventFollowEvent
import dev.rubentxu.pipeline.v2.events.follow.EventFollowOptions
import dev.rubentxu.pipeline.v2.events.follow.EventFollowState
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.events.identity.EventQuery
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadPort
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadResult
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The live tail of a follow: a quiet page is not the end of the run.
 *
 * ## The guarantee
 * A follow over a run that is still alive stays open when the store has nothing new to say. The
 * consumer that reads a quiet page learns "no new data right now" and asks again; it is NOT told
 * "the run is over". Only two things end a follow: the run itself finishes, or the consumer's own
 * bound is reached.
 *
 * ## Why this needed a guardian
 * The follow used to emit [EventFollowEvent.Completed] on the first quiet page. That conflated a
 * snapshot with a live follow: a consumer watching a running pipeline would be told the run ended
 * at the first moment the pipeline had not yet produced an event, and would stop watching it. The
 * code was corrected, but the falsifiers that pinned the correction were deleted, so the state
 * machine could regress to the exact defect its own KDoc describes while the whole executable suite
 * stayed green. This file is that missing falsifier.
 *
 * ## What is faked, and why
 * The PORT, and only the port. The follow is built over
 * [dev.rubentxu.pipeline.v2.events.identity.EventRecordReadPort], which is the seam the product
 * already uses, so a fake port needs no disk, no SQLite and no store double. The assertions read
 * the follow's EVENT SEQUENCE — what the consumer receives — and never the handle's internals.
 *
 * The poll interval is 1 ms, the smallest value the published options accept. It is the
 * production code's own cadence at its minimum, and no assertion here depends on elapsed time: the
 * waits below are bounds that let a broken follow fail, never thresholds a correct follow has to
 * beat.
 */
class SqliteEventFollowLiveTailTest {

    /**
     * A port that serves a prepared sequence of pages and counts how many times it was asked.
     *
     * It exists to answer one question per test: "what did the store say on the Nth poll?".
     */
    private class CountingPort(
        private val pages: List<EventRecordSlice>,
        pollsToSignal: Int = 2,
    ) : EventRecordReadPort {
        private var calls = 0
        private val polled = CountDownLatch(pollsToSignal)

        override fun readRecords(
            runId: String,
            after: EventCursor?,
            query: EventQuery,
            limit: Int,
        ): EventRecordReadResult {
            val slice = pages.getOrElse(calls) { pages.last() }
            calls++
            polled.countDown()
            return EventRecordReadResult.Page(slice)
        }

        /**
         * Wait until the follow has polled the store [pollsToSignal] times.
         *
         * Returns `false` when the follow stopped asking, which is the defect this file guards.
         */
        fun awaitPolls(): Boolean = polled.await(2, TimeUnit.SECONDS)
    }

    private fun quietPage(runId: String, sequence: Long = 0L): EventRecordSlice = EventRecordSlice(
        records = emptyList(),
        nextCursor = EventCursor(runId, sequence),
        hasMore = false,
    )

    private fun finishedPage(runId: String, sequence: Long): EventRecordSlice = EventRecordSlice(
        records = listOf(
            EventRecordRead.Decoded(
                RunFinished(
                    eventId = "evt-1",
                    runId = runId,
                    sequence = sequence,
                    occurredAt = Instant.EPOCH,
                    outcome = "succeeded",
                    diagnostics = emptyList(),
                ),
            ),
        ),
        nextCursor = EventCursor(runId, sequence),
        hasMore = false,
    )

    @Test
    @DisplayName("a quiet page does not end the follow: the consumer keeps waiting for the run")
    fun aQuietPageDoesNotEndTheFollow() {
        val port = CountingPort(listOf(quietPage("run-1")))
        val seen = CopyOnWriteArrayList<EventFollowEvent>()
        val follower = SqliteEventFollower(port = port, runExists = { true })
        val handle = follower.open("run-1", EventFollowOptions(pollIntervalMs = 1L))

        val consumer = Thread {
            val events = handle.iterator()
            while (events.hasNext()) {
                seen.add(events.next())
            }
        }
        consumer.isDaemon = true
        consumer.start()

        try {
            // The follow must poll again after the quiet page. A follow that ended on the quiet
            // page asks the store exactly once and never comes back.
            assertTrue(
                port.awaitPolls(),
                "the follow stopped polling after a quiet page; it asked the store once and the " +
                    "consumer saw: ${seen.toList()}",
            )

            // And it must never have told the consumer the run was over.
            assertTrue(
                seen.none { it is EventFollowEvent.Completed },
                "a quiet page must not end the follow; the consumer saw: ${seen.toList()}",
            )
        } finally {
            handle.close()
            consumer.join(TimeUnit.SECONDS.toMillis(5))
        }
    }

    @Test
    @DisplayName("the follow ends when the run finishes, not before")
    fun theFollowEndsWhenTheRunFinishes() {
        val port = CountingPort(listOf(quietPage("run-1"), finishedPage("run-1", 7L)))
        val seen = CopyOnWriteArrayList<EventFollowEvent>()
        val follower = SqliteEventFollower(port = port, runExists = { true })

        follower.open("run-1", EventFollowOptions(pollIntervalMs = 1L)).use { handle ->
            val events = handle.iterator()
            while (events.hasNext()) {
                seen.add(events.next())
            }
        }

        assertEquals(
            listOf("StateChanged", "Page", "StateChanged", "Completed"),
            seen.map { it::class.simpleName },
            "the follow must open Live, deliver the finished page, declare RunFinished and only " +
                "then complete",
        )
        assertEquals(
            EventFollowState.RunFinished,
            seen.filterIsInstance<EventFollowEvent.StateChanged>().last().state,
            "the terminal state must name the finished run",
        )
    }
}
