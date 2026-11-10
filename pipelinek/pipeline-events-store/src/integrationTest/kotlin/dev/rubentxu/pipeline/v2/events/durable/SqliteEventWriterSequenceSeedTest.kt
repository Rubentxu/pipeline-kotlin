package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.events.RunStarted
import java.nio.file.Path
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The sequence of a run that appears AFTER the store was opened.
 *
 * ## The guarantee under trial
 *
 * That the sequence of a run continues the run's DURABLE history, whatever
 * the order in which the writers of that history met the database. A store
 * that was open before the run existed seeds the counter of that run from the
 * durable truth when it first writes for it, exactly as it seeds the counters
 * of the runs that already existed when it opened.
 *
 * ## The defect this row chases
 *
 * The counters were seeded from the durable truth AT CONSTRUCTION, and a
 * counter that was created later started at zero. So a store that opened
 * before a run existed — the service that opens the substrate before it
 * starts anything, and the child that writes the run's own history in
 * another process — stamped the run's first fact with sequence 1, while the
 * facts the other writer had already committed carried 1, 2 and 3. The
 * database refused the append with a UNIQUE constraint, and the run's
 * terminal fact was lost: the cancel had closed the run and the run had no
 * end that anybody could read.
 *
 * ## Why this is an INTEGRATION test
 *
 * Because the truth is the DATABASE's: the seed reads the committed
 * MAX(sequence) of a run out of SQLite, and the collision this row avoids
 * happened inside the engine. A fake would have to re-implement the
 * constraint to be worth anything.
 */
class SqliteEventWriterSequenceSeedTest {

    @TempDir
    lateinit var tempDir: Path

    private val runId = "v2:run:seeded-later"
    private val startedAt = Instant.parse("2026-01-01T00:00:00Z")

    private fun started(eventId: String) = RunStarted(
        eventId = eventId,
        runId = runId,
        sequence = 0L,
        occurredAt = startedAt,
        scriptPath = "pipeline.kts",
    )

    @Test
    fun `a store that opened before the run wrote continues the run's history`() {
        val file = tempDir.resolve("pipeline.db").toString()
        // The first store opens the substrate BEFORE the run exists: it has
        // no counter for that run, and it will have to seed one.
        val early = SqliteEventStore(file, EventStoreMode.Writable)
        try {
            // Another writer — the child of a run — writes the run's history.
            val child = SqliteEventStore(file, EventStoreMode.Writable)
            try {
                child.append(started("event-1"))
                child.append(started("event-2"))
                child.append(started("event-3"))
                child.flush()
            } finally {
                child.close()
            }

            val assigned = early.appendAssigned(started("event-4"))

            assertEquals(
                4L,
                assigned.sequence,
                "the sequence continues the run's durable history instead of restarting it at 1: " +
                    "a fact stamped with a sequence that is already taken is a fact the database refuses",
            )
            early.flush()
        } finally {
            early.close()
        }
    }
}
