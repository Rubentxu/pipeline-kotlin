package dev.rubentxu.pipeline.v2.events.identity

import dev.rubentxu.pipeline.v2.events.EventRecordRead
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadRefusal
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * M1-A — ADT-shape test for [EventRecordReadPort] in the published module.
 *
 * The previous version of this file held a hand-rolled [EventRecordReadPort]
 * in-memory double with a `sequence=2` collision. That test was
 * superseded on 2026-10-10 by the real-store contract test in
 * `:pipeline-events-store/src/test/.../durable/EventRecordReadPortAdapterTest.kt`,
 * which uses a real [dev.rubentxu.pipeline.v2.events.durable.SqliteEventStore]
 * with `UNIQUE(run_id, sequence)` and exercises the production
 * [dev.rubentxu.pipeline.v2.events.durable.EventRecordReadPortStoreAdapter].
 *
 * What stays here is the ADT-shape test: the closed refusal hierarchy,
 * the page wrapper, the closed result wrapper, the cursor encoding, and
 * the cross-hierarchy inequalities the compiler must enforce. These do
 * not need a store, and they are the floor that the real-store tests
 * stand on.
 */
class EventRecordReadPortTest {

    @Nested
    @DisplayName("refusal hierarchy is closed and distinct")
    inner class RefusalHierarchy {

        @Test
        fun `UnknownRun carries the runId`() {
            val r = EventRecordReadRefusal.UnknownRun("run-x")
            assertEquals("run-x", r.runId)
        }

        @Test
        fun `CursorBeyondTail carries both the requested and the tail sequence`() {
            val r = EventRecordReadRefusal.CursorBeyondTail("run-x", 99L, 3L)
            assertEquals("run-x", r.runId)
            assertEquals(99L, r.requestedSequence)
            assertEquals(3L, r.tailSequence)
        }

        @Test
        fun `StorageError carries a short diagnostic string`() {
            val r = EventRecordReadRefusal.StorageError("disk: read-only fs")
            assertTrue(r.cause.contains("disk"))
        }

        @Test
        fun `the three refusal cases are not equal to each other`() {
            val unknown = EventRecordReadRefusal.UnknownRun("run-x")
            val beyond = EventRecordReadRefusal.CursorBeyondTail("run-x", 99L, 3L)
            val storage = EventRecordReadRefusal.StorageError("io")
            assertNotEquals(unknown, beyond)
            assertNotEquals(unknown, storage)
            assertNotEquals(beyond, storage)
        }
    }

    @Nested
    @DisplayName("result wrapper")
    inner class ResultWrapper {

        @Test
        fun `Page wraps a slice and Refused wraps a refusal, and they are distinct`() {
            val runId = "run-x"
            val typed = EventRecordRead.Decoded(
                RunStarted("evt-1", runId, 1L, Instant.parse("2026-01-01T00:00:00Z"), "/ws/x.kts"),
            )
            val slice = dev.rubentxu.pipeline.v2.events.EventRecordSlice(
                records = listOf(typed),
                nextCursor = EventCursor(runId, 1L),
                hasMore = false,
            )
            val page: EventRecordReadResult = EventRecordReadResult.Page(slice)
            val refused: EventRecordReadResult =
                EventRecordReadResult.Refused(EventRecordReadRefusal.UnknownRun(runId))
            assertInstanceOf(EventRecordReadResult.Page::class.java, page)
            assertInstanceOf(EventRecordReadResult.Refused::class.java, refused)
            assertNotEquals(page, refused)
            val pageAsPage = assertInstanceOf(EventRecordReadResult.Page::class.java, page)
            assertEquals(1, pageAsPage.slice.records.size)
        }
    }

    @Nested
    @DisplayName("cursor encoding")
    inner class CursorShape {

        @Test
        fun `EventCursor round-trips through the wire token format`() {
            val cursor = EventCursor("run-x", 7L)
            val token = cursor.encode()
            assertTrue(token.startsWith("evt-cursor-v1:"))
            val decoded = EventCursor.decode(token)
            assertNotNull(decoded)
            assertEquals("run-x", decoded!!.runId)
            assertEquals(7L, decoded.lastSequence)
        }

        @Test
        fun `a malformed cursor fails closed (null, not a silent default)`() {
            // Per the KDoc on EventCursor.decode: a token that does not
            // match the wire format is refused by returning `null`,
            // not silently defaulted to a cursor at sequence 0.
            val bogus = "not-a-cursor-token"
            assertNull(EventCursor.decode(bogus))
        }
    }
}
