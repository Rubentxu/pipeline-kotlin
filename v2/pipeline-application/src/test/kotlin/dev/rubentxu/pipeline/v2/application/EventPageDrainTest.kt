package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventRecordRead
import dev.rubentxu.pipeline.v2.events.EventRecordSlice
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.UndecodableReason
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.events.identity.EventHistoryReader
import dev.rubentxu.pipeline.v2.events.identity.EventQuery
import dev.rubentxu.pipeline.v2.scripting.ScriptingDiagnostic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * S5.4 — the law on the observer side: an external consumer that filters must still SEE the refusal,
 * and must still get a continuation that can get past it.
 *
 * ## The production entry point this crosses
 *
 * [EventPageDrain.drain]. That is the function the events CLI calls, and behind it this test runs the
 * production [EventHistoryReader.readAfter] too — so the chain under test is the real reader over a
 * doubled STORE, not a doubled reader.
 *
 * ## What is doubled, and what that costs
 *
 * Only [RowPagingSink], which stands in for `SqliteEventStore`. It reproduces the store's own
 * documented rule — the page bound counts ROWS, `nextCursor` is the last row's sequence whether or not
 * that row decoded, and `hasMore` is "rows remain" — because a double that paged differently would
 * let a wrong drain look right. SQLite is not usable from this module and must not be: what the real
 * store's refusal behaviour is proved by is R1's 40/41/42 in `pipeline-events-store`, and the real
 * reader's propagation is proved by `EventHistoryReaderRefusalPropagationTest`.
 *
 * [FrozenCursorSink] is a second double that DELIBERATELY misbehaves, to reach the stall guard. No
 * real store behaves that way; saying so here is the point, because otherwise the guard looks like
 * dead code.
 *
 * ## The mutation that must kill this
 *
 * Reverting the CLI to `history(...) + take(limit)` is design falsifier D-M5. The equivalent defect
 * inside this file is dropping [EventPage.refusals] from the accumulated answer, and that is what
 * test 1 pins.
 */
@DisplayName("S5.4 — un observador filtrado ve el rechazo y puede pasar por el")
class EventPageDrainTest {

    private val runId = "run-drain"
    private val run: ResourceRef = ResourceRefs.run(runId)

    /** The store's rule, verbatim: rows in, rows counted, cursor by last row. */
    private class RowPagingSink(private val records: List<EventRecordRead>) : EventSink {
        override fun append(event: DomainEvent) = Unit

        override fun eventsFor(runId: String): Sequence<DomainEvent> =
            records.mapNotNull { (it as? EventRecordRead.Decoded)?.event }.asSequence()

        override fun readRecords(runId: String, after: EventCursor?, limit: Int): EventRecordSlice {
            require(limit > 0) { "limit must be positive, got $limit" }
            val afterSequence = after?.lastSequence ?: 0L
            val remaining = records.filter { it.sequence > afterSequence }
            val page = remaining.take(limit)
            return EventRecordSlice(
                records = page,
                nextCursor = EventCursor(runId, page.lastOrNull()?.sequence ?: afterSequence),
                hasMore = remaining.size > page.size,
            )
        }
    }

    /** Lies on purpose: always claims more rows, never moves the cursor. */
    private class FrozenCursorSink(private val page: List<EventRecordRead>) : EventSink {
        override fun append(event: DomainEvent) = Unit

        override fun eventsFor(runId: String): Sequence<DomainEvent> = emptySequence()

        override fun readRecords(runId: String, after: EventCursor?, limit: Int): EventRecordSlice =
            EventRecordSlice(
                records = page,
                nextCursor = after ?: EventCursor(runId, 0L),
                hasMore = true,
            )
    }

    private fun started(sequence: Long) =
        EventRecordRead.Decoded(
            RunStarted(
                eventId = "evt-$sequence",
                runId = runId,
                sequence = sequence,
                occurredAt = Instant.parse("2026-10-06T12:00:00Z"),
                scriptPath = "/tmp/run-$sequence.pipeline.kts",
            ),
        )

    private fun finished(sequence: Long) =
        EventRecordRead.Decoded(
            RunFinished(
                eventId = "evt-$sequence",
                runId = runId,
                sequence = sequence,
                occurredAt = Instant.parse("2026-10-06T12:00:01Z"),
                outcome = "SUCCESS",
                diagnostics = emptyList<ScriptingDiagnostic>(),
            ),
        )

    private fun refused(sequence: Long, kind: String?, reason: UndecodableReason) =
        EventRecordRead.Undecodable(
            sequence = sequence,
            kind = kind,
            eventId = "evt-$sequence",
            reason = reason,
        )

    private val malformed41 = refused(41L, "some.Kind", UndecodableReason.MalformedPayload("no json here"))

    private fun drainOver(
        records: List<EventRecordRead>,
        query: EventQuery = EventQuery.All,
        cursor: EventCursor? = null,
        limit: Int = 10,
    ): EventPageDrain.Outcome =
        EventPageDrain.drain(EventHistoryReader(RowPagingSink(records)), run, query, cursor, limit)

    @Test
    fun `el rechazo llega a la respuesta filtrada y no se convierte en envelope`() {
        val outcome = drainOver(listOf(started(40L), malformed41, finished(42L)))

        val page = assertInstanceOf(EventPageDrain.Outcome.Answered::class.java, outcome).page
        assertEquals(listOf(40L, 42L), page.envelopes.map { it.sequence }, "40 and 42 decode; 41 must not be invented")
        assertEquals(listOf(41L), page.refusals.map { it.sequence }, "the refusal must be in the answer, not lost")
        assertEquals(
            UndecodableReason.MalformedPayload("no json here"),
            page.refusals.single().reason,
            "the store's own classification must survive, not be flattened to a string",
        )
    }

    @Test
    fun `una pagina que TERMINA en rechazo deja el cursor despues de el`() {
        // The case D-M3 could not distinguish: when the last row of a page refuses, a continuation
        // computed from the DECODED rows would stop before it and the next read would return the same
        // page for ever.
        val outcome = drainOver(listOf(started(40L), malformed41), query = EventQuery.ByKind("RunStarted"))

        val page = assertInstanceOf(EventPageDrain.Outcome.Answered::class.java, outcome).page
        assertEquals(listOf(40L), page.envelopes.map { it.sequence })
        assertEquals(41L, page.refusals.single().sequence)
        assertEquals(
            41L,
            page.nextCursor!!.lastSequence,
            "the continuation must be the store's row position, past the refusal, not the last decoded one",
        )
        assertFalse(page.hasMore)
    }

    @Test
    fun `un filtro que no casa en la primera pagina sigue pidiendo paginas`() {
        // A row-bounded page can legitimately contain no matches. That is not the end of history, and
        // answering it as such is the fail-open this drain exists to prevent.
        val records = (1L..10L).map { started(it) } + listOf(finished(11L), finished(12L))

        val outcome = drainOver(records, query = EventQuery.ByKind("RunFinished"), limit = 10)

        val page = assertInstanceOf(EventPageDrain.Outcome.Answered::class.java, outcome).page
        assertEquals(listOf(11L, 12L), page.envelopes.map { it.sequence })
        assertFalse(page.hasMore, "the store was read to its end, so there is nothing more")
    }

    @Test
    fun `el limite cuenta los envelopes que casan y no las filas`() {
        val records = (1L..10L).map { started(it) } + listOf(finished(11L), finished(12L))

        val outcome = drainOver(records, query = EventQuery.ByKind("RunFinished"), limit = 1)

        val page = assertInstanceOf(EventPageDrain.Outcome.Answered::class.java, outcome).page
        assertEquals(listOf(11L), page.envelopes.map { it.sequence }, "one match asked for, one match delivered")
        assertTrue(page.hasMore, "row 12 was never read, and saying otherwise would be a lie")
        assertEquals(11L, page.nextCursor!!.lastSequence)
    }

    @Test
    fun `los rechazos de paginas sucesivas se acumulan`() {
        val records = listOf(
            started(40L),
            refused(41L, "future.Kind", UndecodableReason.UnknownKind("future.Kind")),
            started(42L),
            refused(43L, "future.Kind", UndecodableReason.UnknownKind("future.Kind")),
            started(44L),
        )

        val page = assertInstanceOf(
            EventPageDrain.Outcome.Answered::class.java,
            drainOver(records, limit = 2),
        ).page

        assertEquals(listOf(40L, 42L), page.envelopes.map { it.sequence })
        assertEquals(
            listOf(41L, 43L),
            page.refusals.map { it.sequence },
            "a refusal in the second page must survive too, or paging hides rejections one page at a time",
        )
        assertEquals(
            43L,
            page.nextCursor!!.lastSequence,
            "the continuation is the last row the STORE read, which is 43 — not 44, which was never read",
        )
        assertTrue(page.hasMore, "row 44 exists and was not read, so the end must not be claimed")
    }

    @Test
    fun `un cursor que no avanza con la pagina sin mas se declara y no se disimula`() {
        val fromSeven = EventPageDrain.drain(
            EventHistoryReader(FrozenCursorSink(listOf(started(40L)))),
            run,
            EventQuery.ByKind("RunStarted"),
            cursor = EventCursor(runId, 7L),
            limit = 10,
        )

        val stalled = assertInstanceOf(EventPageDrain.Outcome.Stalled::class.java, fromSeven)
        assertEquals(7L, stalled.stuckAfter!!.lastSequence)
        assertTrue(stalled.page.hasMore, "the store did claim more rows, so claiming completeness would be false")
        assertEquals(listOf(40L), stalled.page.envelopes.map { it.sequence })

        val fromStart = EventPageDrain.drain(
            EventHistoryReader(FrozenCursorSink(listOf(started(40L)))),
            run,
            EventQuery.ByKind("RunStarted"),
            cursor = null,
            limit = 10,
        )
        assertInstanceOf(
            EventPageDrain.Outcome.Stalled::class.java,
            fromStart,
            "a frozen cursor must terminate from the start of history too, not only from a resume",
        )
    }
}
