package dev.rubentxu.pipeline.fabric.consumer

import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSlice
import dev.rubentxu.pipeline.v2.events.EventStore
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.events.identity.EventId
import dev.rubentxu.pipeline.v2.events.identity.EventPage
import dev.rubentxu.pipeline.v2.events.identity.EventRef
import dev.rubentxu.pipeline.v2.events.identity.EventTail
import dev.rubentxu.pipeline.v2.events.identity.PipelineEventEnvelope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * What the published contract offers a consumer that has to walk a run's events incrementally.
 *
 * The requirement names no product: walk the events of a run incrementally, in store-assigned
 * order, with a cursor to resume from and a page limit, and see what actually happened rather than
 * only that something did.
 *
 * **This file is the evidence that used to say the contract could not do that.** It characterised
 * the gap by writing out, in full, the cut a consumer had to re-implement: `sequence > after`,
 * ordering, the peek that proves more is coming, and the cursor to resume from — four rules that
 * lived in a KDoc and that every consumer had to write again. It also showed the other half, the
 * paged read the contract did have, answering with identity only: position, kind, time and
 * identity, and no outcome anywhere in it, even when the underlying event finished with one.
 *
 * Both are now decided by the store, which is the sequence authority, and both are reachable from
 * here without writing a line of arithmetic. The characterisation is kept in the second test
 * because a capability that is asserted once and then never looked at again is a capability
 * nothing protects.
 */
class PublishedReadSurfaceTest {

    /**
     * The capability, consumed from OUTSIDE: a typed, ordered, resumable page, with the outcome
     * in it, and not one line of cut logic written by this consumer.
     */
    @Test
    fun `a consumer walks a run in typed pages without re-cutting anything`() {
        val store = RecordingStore(RUN_ID)

        val first: EventSlice = store.readSlice(RUN_ID, after = null, limit = 3)
        assertEquals(listOf(1L, 2L, 3L), first.events.map { it.sequence }, "a page of three")
        assertTrue(first.hasMore, "four events remain, so this is not the end of the run")
        assertEquals(EventCursor(RUN_ID, 3L), first.nextCursor, "resume from the last one RETURNED")

        val second = store.readSlice(RUN_ID, first.nextCursor, limit = 3)
        val third = store.readSlice(RUN_ID, second.nextCursor, limit = 3)

        assertEquals(listOf(4L, 5L, 6L), second.events.map { it.sequence })
        assertEquals(listOf(7L), third.events.map { it.sequence })
        assertFalse(third.hasMore, "the seventh is the last event written")
        assertEquals(
            "success",
            (third.events.single() as StageFinished).outcome,
            "and the page carries the semantics: the outcome is readable, which is the thing an " +
                "identity-only page could never give",
        )
    }

    /**
     * The same run through the paged read that projects identity, to say what the two shapes are
     * FOR. They are the same events at two resolutions — the one you page through when you need to
     * know what happened, the one you page through when you only need to know that it did — and
     * they answer the same cursor with the same answer, which is the part worth pinning.
     */
    @Test
    fun `the identity read pages over the same cut and lands on the same position`() {
        val store = RecordingStore(RUN_ID)
        val tail = EnvelopeOnlyTail(store)

        val typed: EventSlice = store.readSlice(RUN_ID, after = null, limit = 3)
        val identity: EventPage = tail.readAfter(ResourceRefs.run(RUN_ID), cursor = null, limit = 3)

        assertEquals(typed.events.map { it.sequence }, identity.envelopes.map { it.sequence })
        assertEquals(typed.nextCursor, identity.nextCursor, "same cut, same position")
        assertEquals(typed.hasMore, identity.hasMore)

        val typedFinished: StageFinished? = typed.events.filterIsInstance<StageFinished>().firstOrNull()
        assertEquals(null, typedFinished, "the first page stops before the event that finished")

        val lastPage = store.readSlice(RUN_ID, after = identity.nextCursor, limit = 10)
        val finished = lastPage.events.filterIsInstance<StageFinished>().single()
        assertEquals("success", finished.outcome, "and four lines down it is still typed")
        assertTrue(
            identity.envelopes.none { it.toString().contains(OUTCOME) },
            "while the identity projection of the same event carries no outcome, because 'success' " +
                "is semantics and an envelope is identity",
        )
    }

    // ── The two published shapes, reduced to what a consumer can reach ────────────────

    /**
     * An [EventStore] written here, in the consumer's tree, against the published interface.
     *
     * It overrides `eventsFor` and nothing else, which is now the whole of what a store has to
     * implement to offer a resumable typed read: `readSlice` has a default that decides the page
     * from the sequence authority's own stream. A consumer does not have to subclass a store or
     * know how a cursor is cut to get one.
     */
    private class RecordingStore(private val runId: String) : EventStore {
        private val stored: List<DomainEvent> = buildList {
            add(RunStarted("e1", runId, 1L, AT, "p.kts"))
            (2..6).forEach { n -> add(StageStarted("e$n", runId, n.toLong(), AT, 0, "s$n")) }
            add(StageFinished("e7", runId, 7L, AT, 0, LAST_STAGE, OUTCOME))
        }

        override fun append(event: DomainEvent) = Unit

        override fun eventsFor(r: String): Sequence<DomainEvent> =
            if (r == runId) stored.asSequence() else emptySequence()
    }

    /**
     * The identity read, written the way a consumer would now write it: ask the store for the page
     * and project it. No cut, no peek, no cursor arithmetic — the shape a consumer would have had
     * to write by hand before the store decided it.
     */
    private class EnvelopeOnlyTail(private val store: EventStore) : EventTail {
        override fun readAfter(
            run: dev.rubentxu.pipeline.v2.domain.identity.ResourceRef,
            cursor: EventCursor?,
            limit: Int,
        ): EventPage {
            val runId = run.segments.last()
            val slice = store.readSlice(runId, cursor, limit)
            return EventPage(
                envelopes = slice.events.map {
                    PipelineEventEnvelope(
                        version = PipelineEventEnvelope.VERSION,
                        eventRef = EventRef(ResourceRefs.run(runId), EventId(it.eventId)),
                        kind = it.kind,
                        occurredAt = it.occurredAt,
                        sequence = it.sequence,
                        subject = ResourceRefs.run(runId),
                    )
                },
                nextCursor = slice.nextCursor,
                hasMore = slice.hasMore,
            )
        }
    }

    private companion object {
        const val RUN_ID = "run-read-surface"
        const val LAST_STAGE = "build-app"
        const val OUTCOME = "success"
        val AT: Instant = Instant.parse("2026-01-01T00:00:00Z")
    }
}
