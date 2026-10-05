package dev.rubentxu.pipeline.v2.events.durable

import java.nio.file.Path
import java.sql.DriverManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The order a store READS in is the order it SEQUENCES in. Both halves are this
 * store's own claim, and they are made in two different places.
 *
 * `appendAssigned` is the sequence authority: the store assigns the number, the
 * `UNIQUE(run_id, sequence)` index makes the database reject a second opinion
 * about it (WU-RP-020), and [EventCursor] defines continuation as
 * `sequence > lastSequence` **ordered by sequence** — which `EventHistoryReader`
 * repeats in a comment before applying it.
 *
 * The read side does not keep that promise. `eventsFor` orders by `rowid`, the
 * physical position SQLite gave the row, not by the value the store assigned.
 * Those agree only while nothing writes concurrently, and the write path is
 * built so that they can disagree: the sequence is assigned on the PRODUCT
 * thread by an atomic counter, and the event is enqueued on a separate writer
 * thread a few instructions later. Between the `incrementAndGet` and the `put`,
 * a second thread can take the next sequence and enqueue first — so the row with
 * the HIGHER sequence gets the LOWER rowid, and the two orders part company.
 *
 * The consequence is not a wrong sort, it is silent loss. A reader resuming from
 * `lastSequence = 6` asks for `sequence > 6`; the row carrying sequence 5 is
 * still unread, and no later cursor will return it, because a cursor only moves
 * forward. The event disappears from the history of every consumer that pages.
 *
 * So the test does not try to win a race with the scheduler. It writes real rows
 * through the real store, then rewrites their `rowid` so the physical order and
 * the assigned order disagree, and asserts that reading returns the assigned
 * order. That file state is exactly what a concurrent writer leaves behind, and
 * a test that cannot produce the disagreement can only test the happy path.
 */
class SqliteEventOrderAuthorityTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `eventsFor reads in assigned-sequence order, not physical row order`() {
        val db = tempDir.resolve("order.db").toString()
        val runId = "run-order-authority"

        SqliteEventStore(db).use { store ->
            store.appendAssigned(runStarted(runId, "e1"))
            store.appendAssigned(runStarted(runId, "e2"))
            store.appendAssigned(runStarted(runId, "e3"))
            store.flush()
        }

        // Reverse the PHYSICAL order without touching a single payload. Row with
        // sequence 1 moves behind rows 2 and 3, so `ORDER BY rowid` now reads 3, 2, 1
        // while the sequence each event carries is still 1, 2, 3.
        //
        // The payload is left alone on purpose: `eventsFor` selects `payload` and
        // decodes the sequence from the JSON, so rewriting the `sequence` COLUMN
        // instead would change nothing an observer can see, and the test would
        // pass against a reader that is demonstrably out of order.
        reversePhysicalOrder(db, runId)

        val leidas = SqliteEventStore(db).use { store ->
            store.eventsFor(runId).map { it.sequence }.toList()
        }

        assertEquals(
            listOf(1L, 2L, 3L),
            leidas,
            "the read order must be the sequence the store assigned, not the rowid the " +
                "database happened to give the row: a cursor cuts on sequence, so a read " +
                "ordered by anything else can step over an event and lose it permanently",
        )
    }

    @Test
    fun `the physical order really does disagree, so the row above is not vacuous`() {
        val db = tempDir.resolve("order-agrees.db").toString()
        val runId = "run-order-agrees"

        SqliteEventStore(db).use { store ->
            store.appendAssigned(runStarted(runId, "e1"))
            store.appendAssigned(runStarted(runId, "e2"))
            store.appendAssigned(runStarted(runId, "e3"))
            store.flush()
        }

        assertEquals(
            listOf(1L, 2L, 3L),
            porRowid(db, runId),
            "in the untouched file both orders agree, which is exactly why a read ordered " +
                "by rowid can look correct for as long as nobody writes concurrently",
        )

        reversePhysicalOrder(db, runId)

        assertEquals(
            listOf(3L, 2L, 1L),
            porRowid(db, runId),
            "after reversing the rowids the two orders really do disagree, so the read above " +
                "is asserting a difference rather than restating the file",
        )
    }

    /** Rewrites `rowid` so the three rows come back physically reversed. */
    private fun reversePhysicalOrder(db: String, runId: String) {
        DriverManager.getConnection("jdbc:sqlite:$db").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "UPDATE events SET rowid = -rowid WHERE run_id = '$runId'"
                )
            }
        }
    }

    /** The sequence stored in each payload, read in physical row order. */
    private fun porRowid(db: String, runId: String): List<Long> =
        DriverManager.getConnection("jdbc:sqlite:$db").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT payload FROM events WHERE run_id = '$runId' ORDER BY rowid ASC"
                ).use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                Regex("\"sequence\"\\s*:\\s*(\\d+)")
                                    .find(rs.getString(1))
                                    ?.groupValues
                                    ?.get(1)
                                    ?.toLong()
                                    ?: error("payload without a sequence: ${rs.getString(1)}")
                            )
                        }
                    }
                }
            }
        }

    private fun runStarted(runId: String, eventId: String) =
        dev.rubentxu.pipeline.v2.events.RunStarted(
            eventId = eventId,
            runId = runId,
            sequence = 0L,
            occurredAt = java.time.Instant.parse("2026-01-01T00:00:00Z"),
            scriptPath = "p.kts",
        )
}
