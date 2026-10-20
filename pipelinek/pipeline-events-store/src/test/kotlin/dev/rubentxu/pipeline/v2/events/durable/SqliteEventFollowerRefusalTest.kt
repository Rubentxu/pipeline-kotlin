package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.events.follow.EventFollowEvent
import dev.rubentxu.pipeline.v2.events.follow.EventFollowOptions
import dev.rubentxu.pipeline.v2.events.follow.EventFollowRefusal
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.events.identity.EventQuery
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadPort
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadRefusal
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The follow's refusals, observed where they are produced.
 *
 * The follower is built over a PORT, so the whole refusal story can be told with an in-memory
 * fake and no store: no disk, no SQLite, no clock. What is under test is the promise the
 * published follow contract makes — that the refusal a consumer receives names the fact that
 * stopped the follow, and not a different one.
 *
 * The failure this guards against is silent and expensive: a consumer that is told "the run does
 * not exist" when the truth is "the store failed" will look for a run that was never there, and
 * a consumer told "the run does not exist" when the truth is "your cursor is past the tail" will
 * throw away a run it was watching.
 */
class SqliteEventFollowerRefusalTest {

    /** A port that answers with a prepared sequence of outcomes; a `null` entry means "fail". */
    private class FakePort(
        private val outcomes: List<EventRecordReadResult?>,
    ) : EventRecordReadPort {
        private var call = 0

        override fun readRecords(
            runId: String,
            after: EventCursor?,
            query: EventQuery,
            limit: Int,
        ): EventRecordReadResult {
            val outcome = outcomes.getOrElse(call) { outcomes.last() }
            call++
            return outcome ?: error("the disk went away")
        }
    }

    private fun emptyPage(runId: String): EventRecordReadResult = EventRecordReadResult.Page(
        dev.rubentxu.pipeline.v2.events.EventRecordSlice(
            records = emptyList(),
            nextCursor = EventCursor(runId, 0L),
            hasMore = false,
        ),
    )

    private fun firstEvent(
        port: EventRecordReadPort,
        runExists: Boolean = true,
    ): EventFollowEvent {
        val follower = SqliteEventFollower(port = port, runExists = { runExists })
        follower.open("run-1", EventFollowOptions(pollIntervalMs = 1L)).use { handle ->
            val events = handle.iterator()
            assertTrue(events.hasNext(), "the follow must produce a first event")
            return events.next()
        }
    }

    @Test
    @DisplayName("a run that does not exist is refused as a run that does not exist")
    fun aRunThatDoesNotExistIsRefusedAsSuch() {
        val event = firstEvent(
            FakePort(
                listOf(EventRecordReadResult.Refused(EventRecordReadRefusal.UnknownRun("run-1"))),
            ),
            runExists = false,
        )

        assertEquals(
            EventFollowEvent.Refused(EventFollowRefusal.UnknownRun("run-1")),
            event,
        )
    }

    @Test
    @DisplayName("a cursor past the tail is refused as a cursor past the tail")
    fun aCursorPastTheTailIsRefusedAsSuch() {
        val event = firstEvent(
            FakePort(
                listOf(
                    EventRecordReadResult.Refused(
                        EventRecordReadRefusal.CursorBeyondTail(
                            runId = "run-1",
                            requestedSequence = 99L,
                            tailSequence = 12L,
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            EventFollowEvent.Refused(
                EventFollowRefusal.CursorBeyondTail(
                    runId = "run-1",
                    requestedSequence = 99L,
                    tailSequence = 12L,
                ),
            ),
            event,
        )
    }

    @Test
    @DisplayName("a store that fails on the first read is refused as a storage failure")
    fun aStoreThatFailsOnTheFirstReadIsRefusedAsAStorageFailure() {
        val event = firstEvent(FakePort(listOf(null)))

        assertTrue(
            event is EventFollowEvent.Refused &&
                event.refusal is EventFollowRefusal.StorageError,
            "a storage failure must not be reported as a missing run, got: $event",
        )
    }

    @Test
    @DisplayName("a store that fails on a later poll is refused as a storage failure")
    fun aStoreThatFailsOnALaterPollIsRefusedAsAStorageFailure() {
        // The first poll succeeds and the second one fails: the refusal has to
        // survive a follow that was already under way, not only one that never
        // started.
        val port = FakePort(listOf(emptyPage("run-1"), null))
        val follower = SqliteEventFollower(port = port, runExists = { true })
        follower.open("run-1", EventFollowOptions(pollIntervalMs = 1L)).use { handle ->
            val events = handle.iterator()
            val seen = mutableListOf<EventFollowEvent>()
            while (events.hasNext()) {
                seen.add(events.next())
                if (seen.size > 4) break
            }

            val refusal = seen.filterIsInstance<EventFollowEvent.Refused>().lastOrNull()
            assertTrue(
                refusal?.refusal is EventFollowRefusal.StorageError,
                "a storage failure on a later poll must not be reported as a missing run, got: $seen",
            )
        }
    }
}
