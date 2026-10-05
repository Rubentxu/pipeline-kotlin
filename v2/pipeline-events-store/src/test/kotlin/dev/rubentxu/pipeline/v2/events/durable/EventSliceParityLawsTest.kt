package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSlice
import dev.rubentxu.pipeline.v2.events.EventStore
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Overriding [EventStore.readSlice] is an optimisation, never a change of meaning.
 *
 * The method has a default, so every store answers it even the ones that cannot cut inside their
 * own storage. Two of them can — a list and an indexed table — and both override it with a
 * different algorithm: the in-memory one walks the list under the write monitor, the SQL one adds
 * `WHERE sequence > ? ORDER BY sequence LIMIT ? + 1` and never materialises the rest of the run.
 * Three implementations of one rule is exactly the arrangement in which the rule quietly forks.
 *
 * So the rule is not left to be re-derived by each of them and agreed on by inspection. This
 * asserts the agreement: the same run, the same cursor and the same limit must produce the same
 * sequences, the same continuation and the same `hasMore` whichever store answered. A store that
 * starts interpreting the cut differently fails here rather than in a consumer that resumed from a
 * cursor the store did not honour.
 *
 * The reference is the INHERITED default, not the SQL one. That direction matters: the default is
 * the plain statement of the rule, and the specialisations have to match it. The reverse would
 * make the most efficient implementation the definition, which is how a fast path becomes the
 * specification without anybody deciding it was one.
 */
class EventSliceParityLawsTest {

    @TempDir
    lateinit var tempDir: Path

    private val runId = "run-parity"

    /** Seven events: 1 RunStarted, 5 StageStarted, 1 StageFinished carrying the outcome. */
    private fun events(): List<DomainEvent> = buildList {
        add(RunStarted("e1", runId, 1L, AT, "p.kts"))
        (2..6).forEach { n -> add(StageStarted("e$n", runId, n.toLong(), AT, 0, "s$n")) }
        add(StageFinished("e7", runId, 7L, AT, 0, "s6", "success"))
    }

    /** A store that keeps the INHERITED `readSlice`, so it states the rule in its plainest form. */
    private class DefaultCutStore(
        private val runId: String,
        private val stored: List<DomainEvent>,
    ) : EventStore {
        override fun append(event: DomainEvent) = Unit
        override fun eventsFor(r: String): Sequence<DomainEvent> =
            if (r == runId) stored.asSequence() else emptySequence()
    }

    private fun default(): EventStore = DefaultCutStore(runId, events())

    private fun inMemory(): EventStore = InMemoryEventStore().also { store ->
        events().forEach { store.appendAssigned(it) }
    }

    private fun sqlite(): EventStore = SqliteEventStore(tempDir.resolve("parity.db").toString()).also { store ->
        events().forEach { store.appendAssigned(it) }
        store.flush()
    }

    /**
     * Every boundary a resuming reader can land on, in one table.
     *
     * The interesting cases are the ones a hand-written cut usually gets wrong, which is why they
     * are here rather than one comfortable middle page: a page that exactly fills the limit and
     * is still not the end, a short page that still has more, an empty page at the end, a limit of
     * one, a cursor already past the last sequence, and a run that was never written.
     */
    private val boundaries: List<Triple<String, EventCursor?, Int>> = listOf(
        Triple("first page, limit 3", null, 3),
        Triple("middle page, limit 3", EventCursor(runId, 3L), 3),
        Triple("last partial page", EventCursor(runId, 6L), 3),
        Triple("page exactly filling the limit, and not the end", EventCursor(runId, 3L), 4),
        Triple("limit 1", null, 1),
        Triple("limit larger than the run", null, 99),
        Triple("cursor already past the end", EventCursor(runId, 7L), 3),
        Triple("cursor past the end with limit 1", EventCursor(runId, 12L), 1),
    )

    @Test
    fun `the SQL store answers exactly what the inherited rule answers`() {
        val reference = default()
        val subject = sqlite()
        try {
            forEachBoundary { what, cursor, limit ->
                assertSamePage(
                    reference.readSlice(runId, cursor, limit),
                    subject.readSlice(runId, cursor, limit),
                    what,
                    "SqliteEventStore.readSlice",
                )
            }
        } finally {
            (subject as SqliteEventStore).close()
        }
    }

    @Test
    fun `the in-memory store answers exactly what the inherited rule answers`() {
        val reference = default()
        val subject = inMemory()
        forEachBoundary { what, cursor, limit ->
            assertSamePage(
                reference.readSlice(runId, cursor, limit),
                subject.readSlice(runId, cursor, limit),
                what,
                "InMemoryEventStore.readSlice",
            )
        }
    }

    @Test
    fun `a run that was never written is an empty page and not an error`() {
        val ausente = "run-que-nunca-se-escribio"
        forEachBoundary { what, cursor, limit ->
            for (store in listOf(default(), inMemory())) {
                val slice = store.readSlice(ausente, cursor, limit)
                assertEquals(emptyList<DomainEvent>(), slice.events, what)
                assertEquals(false, slice.hasMore, what)
                // Not null. A null cursor already means "start at the beginning", so a null
                // continuation here would make the end of a run indistinguishable from its start.
                assertEquals(
                    EventCursor(ausente, cursor?.lastSequence ?: 0L),
                    slice.nextCursor,
                    "$what: an empty page still has a position, and it is the one the read started from",
                )
            }
        }
    }

    @Test
    fun `the outcome survives the page, which is the whole reason the page is typed`() {
        forEachBoundary { what, cursor, limit ->
            for (slice in listOf(default().readSlice(runId, cursor, limit), inMemory().readSlice(runId, cursor, limit))) {
                for (event in slice.events) {
                    if (event is StageFinished) {
                        assertEquals(
                            "success",
                            event.outcome,
                            "$what: a paged typed read has to carry what the event says, or a " +
                                "consumer is back to inferring it from a kind",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `a non-positive limit is refused rather than silently treated as one`() {
        for (limit in listOf(0, -1)) {
            assertThrows(IllegalArgumentException::class.java) {
                default().readSlice(runId, null, limit)
            }
        }
    }

    private fun forEachBoundary(body: (String, EventCursor?, Int) -> Unit) {
        for ((what, cursor, limit) in boundaries) body(what, cursor, limit)
    }

    private fun assertSamePage(expected: EventSlice, actual: EventSlice, what: String, who: String) {
        assertEquals(expected.events.map { it.sequence }, actual.events.map { it.sequence }, "$who, $what: sequences")
        assertEquals(expected.events.map { it.eventId }, actual.events.map { it.eventId }, "$who, $what: identities")
        assertEquals(expected.nextCursor, actual.nextCursor, "$who, $what: continuation")
        assertEquals(expected.hasMore, actual.hasMore, "$who, $what: hasMore")
    }

    private companion object {
        val AT: java.time.Instant = java.time.Instant.parse("2026-01-01T00:00:00Z")
    }
}
