package dev.rubentxu.pipeline.v2.events.identity

import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventRecordRead
import dev.rubentxu.pipeline.v2.events.EventRecordSlice
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.UndecodableReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * M1-A — contract test for [EventRecordReadPort].
 *
 * The test double is a hand-rolled [EventRecordReadPort] implementation
 * that holds an in-memory list of [DomainEvent] entries plus, optionally,
 * a list of `Undecodable` rows at chosen sequences. It does not depend
 * on `:pipeline-events-store` (the production store); that would invert
 * the boundary the new port is meant to draw.
 *
 * The contract the test pins:
 *  1. typed `DomainEvent` rows are returned inside [EventRecordSlice.decoded]
 *     in store sequence order,
 *  2. refusal rows are returned inside [EventRecordSlice.refusals] with
 *     sequence / kind / eventId / reason preserved,
 *  3. an unknown runId returns [EventRecordReadResult.Refused] carrying
 *     [EventRecordReadRefusal.UnknownRun],
 *  4. a cursor past the tail returns
 *     [EventRecordReadRefusal.CursorBeyondTail] with the requested and
 *     tail sequences,
 *  5. the page's [EventRecordSlice.hasMore] is `false` exactly when the
 *     underlying store is exhausted.
 */
class EventRecordReadPortTest {

    private val runId = "run-m1-a"
    private val run = ResourceRefs.run(runId)
    private val at = Instant.parse("2026-01-01T00:00:00Z")

    private fun id(n: Int) = "evt-$n"

    private fun threeEvents(): List<DomainEvent> = listOf(
        RunStarted(id(1), runId, 1L, at, "/ws/x.pipeline.kts"),
        StageStarted(id(2), runId, 2L, at, 0, "build"),
        RunFinished(id(3), runId, 3L, at, outcome = "success", diagnostics = emptyList()),
    )

    /**
     * A test double of the new port. Holds the run + (optionally) a
     * few undecodable rows. The page it returns is the concatenation of
     * typed rows and undecodable rows in store sequence order, capped
     * at [limit].
     */
    private class InMemoryReadPort(
        private val typed: Map<String, List<DomainEvent>>,
        private val undecodable: Map<String, List<EventRecordRead.Undecodable>> = emptyMap(),
    ) : EventRecordReadPort {
        override fun readRecords(
            runId: String,
            after: EventCursor?,
            query: EventQuery,
            limit: Int,
        ): EventRecordReadResult {
            val rows = typed[runId] ?: return EventRecordReadResult.Refused(
                EventRecordReadRefusal.UnknownRun(runId),
            )
            val tail = rows.maxOfOrNull { it.sequence } ?: 0L
            val startAfter = after?.lastSequence ?: 0L
            if (startAfter > tail) {
                return EventRecordReadResult.Refused(
                    EventRecordReadRefusal.CursorBeyondTail(runId, startAfter, tail),
                )
            }
            val typedInWindow = rows.filter { it.sequence > startAfter }
                .map { EventRecordRead.Decoded(it) as EventRecordRead }
            val undecodableInWindow = undecodable[runId].orEmpty()
                .filter { it.sequence > startAfter } as List<EventRecordRead>
            // Merge by sequence: typed + undecodable interleaved, capped at limit.
            val merged = (typedInWindow + undecodableInWindow)
                .sortedBy { it.sequence }
                .take(limit)
            val lastSeq = merged.lastOrNull()?.sequence ?: startAfter
            val totalInWindow = typedInWindow.size + undecodableInWindow.size
            val hasMore = totalInWindow > limit
            return EventRecordReadResult.Page(
                EventRecordSlice(
                    records = merged,
                    nextCursor = EventCursor(runId, lastSeq),
                    hasMore = hasMore,
                ),
            )
        }
    }

    /** A port that holds the three typed events and NO undecodable rows. */
    private val typedOnlyPort = InMemoryReadPort(typed = mapOf(runId to threeEvents()))

    /** A port that holds the three typed events and ONE undecodable row at seq=2. */
    private val mixedPort = InMemoryReadPort(
        typed = mapOf(runId to threeEvents()),
        undecodable = mapOf(
            runId to listOf(
                EventRecordRead.Undecodable(
                    sequence = 2L,
                    kind = "UnknownKind",
                    eventId = "evt-2-broken",
                    reason = UndecodableReason.UnknownKind("UnknownKind"),
                ),
            ),
        ),
    )

    @Nested
    @DisplayName("typed payload delivery")
    inner class TypedPayload {

        @Test
        fun `readRecords returns the typed DomainEvent rows in store sequence order`() {
            val result = typedOnlyPort.readRecords(runId, after = null, query = EventQuery.All, limit = 10)
            val page = assertInstanceOf(EventRecordReadResult.Page::class.java, result)
            assertEquals(3, page.slice.records.size)
            val decoded = page.slice.records.mapNotNull { (it as? EventRecordRead.Decoded)?.event }
            assertEquals(3, decoded.size)
            assertEquals("evt-1", decoded[0].eventId)
            assertEquals("evt-2", decoded[1].eventId)
            assertEquals("evt-3", decoded[2].eventId)
        }

        @Test
        fun `readRecords advances the cursor to the last sequence in the page`() {
            val result = typedOnlyPort.readRecords(runId, after = null, query = EventQuery.All, limit = 2)
            val page = assertInstanceOf(EventRecordReadResult.Page::class.java, result)
            assertEquals(2, page.slice.records.size)
            assertEquals(EventCursor(runId, 2L), page.slice.nextCursor)
            assertEquals(true, page.slice.hasMore)
        }

        @Test
        fun `readRecords with limit larger than the run reports hasMore=false`() {
            val result = typedOnlyPort.readRecords(runId, after = null, query = EventQuery.All, limit = 100)
            val page = assertInstanceOf(EventRecordReadResult.Page::class.java, result)
            assertEquals(3, page.slice.records.size)
            assertEquals(false, page.slice.hasMore, "exhausted page must report hasMore=false")
        }

        @Test
        fun `readRecords with after-cursor skips earlier sequences`() {
            val cursor = EventCursor(runId, 1L)
            val result = typedOnlyPort.readRecords(runId, after = cursor, query = EventQuery.All, limit = 10)
            val page = assertInstanceOf(EventRecordReadResult.Page::class.java, result)
            assertEquals(2, page.slice.records.size)
            val decoded = page.slice.records.mapNotNull { (it as? EventRecordRead.Decoded)?.event }
            assertEquals("evt-2", decoded[0].eventId)
            assertEquals("evt-3", decoded[1].eventId)
        }
    }

    @Nested
    @DisplayName("refusal rows preserved with identity intact")
    inner class RefusalRows {

        @Test
        fun `undecodable rows are interleaved with typed rows in sequence order`() {
            val result = mixedPort.readRecords(runId, after = null, query = EventQuery.All, limit = 10)
            val page = assertInstanceOf(EventRecordReadResult.Page::class.java, result)
            // 3 typed + 1 undecodable
            assertEquals(4, page.slice.records.size, "got ${page.slice.records}")
            // Sequence order: 1 (typed), 2 (undecodable and typed at the same seq), 3 (typed).
            val sequences = page.slice.records.map { it.sequence }
            assertEquals(listOf(1L, 2L, 2L, 3L), sequences, "rows must be in store sequence order")
        }

        @Test
        fun `undecodable row carries sequence, kind, eventId and reason through the page`() {
            val result = mixedPort.readRecords(runId, after = null, query = EventQuery.All, limit = 10)
            val page = assertInstanceOf(EventRecordReadResult.Page::class.java, result)
            val undecodable = page.slice.records.filterIsInstance<EventRecordRead.Undecodable>()
            assertEquals(1, undecodable.size)
            val u = undecodable[0]
            assertEquals(2L, u.sequence)
            assertEquals("UnknownKind", u.kind)
            assertEquals("evt-2-broken", u.eventId)
            assertInstanceOf(UndecodableReason.UnknownKind::class.java, u.reason)
        }
    }

    @Nested
    @DisplayName("refusal of the read itself")
    inner class ReadRefusal {

        @Test
        fun `unknown runId returns Refused UnknownRun`() {
            val result = typedOnlyPort.readRecords("run-unknown", after = null, query = EventQuery.All, limit = 10)
            val refused = assertInstanceOf(EventRecordReadResult.Refused::class.java, result)
            val unknown = assertInstanceOf(EventRecordReadRefusal.UnknownRun::class.java, refused.refusal)
            assertEquals("run-unknown", unknown.runId)
        }

        @Test
        fun `cursor past the tail returns Refused CursorBeyondTail with both sequences`() {
            val cursor = EventCursor(runId, 99L)
            val result = typedOnlyPort.readRecords(runId, after = cursor, query = EventQuery.All, limit = 10)
            val refused = assertInstanceOf(EventRecordReadResult.Refused::class.java, result)
            val beyond = assertInstanceOf(EventRecordReadRefusal.CursorBeyondTail::class.java, refused.refusal)
            assertEquals(runId, beyond.runId)
            assertEquals(99L, beyond.requestedSequence)
            assertEquals(3L, beyond.tailSequence)
        }

        @Test
        fun `storage error is surfaced as Refused StorageError when the store fails`() {
            val failingPort = object : EventRecordReadPort {
                override fun readRecords(
                    runId: String,
                    after: EventCursor?,
                    query: EventQuery,
                    limit: Int,
                ): EventRecordReadResult =
                    EventRecordReadResult.Refused(EventRecordReadRefusal.StorageError("io: read failed"))
            }
            val result = failingPort.readRecords(runId, after = null, query = EventQuery.All, limit = 10)
            val refused = assertInstanceOf(EventRecordReadResult.Refused::class.java, result)
            val err = assertInstanceOf(EventRecordReadRefusal.StorageError::class.java, refused.refusal)
            assertTrue(err.cause.contains("io"))
        }
    }
}
