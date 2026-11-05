package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.StepFinished
import java.nio.file.Path
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A journal written by the PREVIOUS version, resumed by this one.
 *
 * ## The guarantee
 *
 * A run whose durable history was written before this codec existed is not a run that has to be
 * thrown away: it is read, it is continued, and the event this runtime appends lands after the
 * events it inherited.
 *
 * ## Why the rows are written by hand
 *
 * The rows below are the bytes the previous version wrote, and they are written into the file
 * directly, without this codec anywhere near them. A journal produced by the codec under test
 * would prove that the codec agrees with itself, and the journal that matters is the one that was
 * already on the disk when this version started.
 *
 * The second row is a row written BEFORE the step's verdict existed: it does not carry the field,
 * and it reads back as a step whose verdict nobody recorded.
 *
 * ## What is real here
 *
 * The store is the real one, over a real database file, and the sequence it assigns comes from the
 * durable truth it reads at construction. Only the world outside the store is held still: the file
 * is in a temporary directory and the clock is a fixed instant.
 */
class PreviousVersionJournalResumeTest {

    @TempDir
    lateinit var directory: Path

    @Test
    fun `a journal written by the previous version is read and continued`() {
        val file = directory.resolve("events.db").toString()
        writeThePreviousVersionJournal(file)

        SqliteEventStore(file).use { store ->
            val appended = store.appendAssigned(finished())
            store.flush()

            assertEquals(
                3L,
                appended.sequence,
                "the journal continues where the previous version left it; the store counts from " +
                    "the durable truth, not from zero",
            )

            val journal = store.eventsFor(RUN_ID).toList()

            assertEquals(
                listOf(1L, 2L, 3L),
                journal.map { it.sequence },
                "the journal is read in order, and the inherited rows keep their numbers",
            )
            assertEquals("RunStarted", journal[0].kind)
            assertEquals("StepFinished", journal[1].kind)
            assertNull(
                (journal[1] as StepFinished).outcome,
                "a row written before the verdict existed carries no verdict, and reading it as " +
                    "one would be reading something nobody wrote",
            )
            assertEquals("RunFinished", journal[2].kind)
        }
    }

    /**
     * The journal as the previous version left it.
     *
     * Two rows, in the wire format it wrote: the payload is a JSON array holding the one event,
     * and the columns carry the identity the store needs to classify the row.
     */
    private fun writeThePreviousVersionJournal(file: String) {
        SqliteEventSchema.prepare { SqliteConnectionFactory.open(file) }
        SqliteConnectionFactory.open(file).use { connection ->
            connection.prepareStatement(
                "INSERT INTO events (event_id, run_id, sequence, kind, occurred_at, payload) " +
                    "VALUES (?, ?, ?, ?, ?, ?)",
            ).use { insert ->
                listOf(
                    LegacyRow(
                        eventId = "e-1",
                        sequence = 1L,
                        kind = "RunStarted",
                        payload = """[{"eventId":"e-1","runId":"$RUN_ID","sequence":1,""" +
                            """"kind":"RunStarted","occurredAt":"2026-01-01T00:00:00Z",""" +
                            """"scriptPath":"/pipeline.kts"}]""",
                    ),
                    LegacyRow(
                        eventId = "e-2",
                        sequence = 2L,
                        kind = "StepFinished",
                        // A row written before the verdict was published: it does not carry it.
                        payload = """[{"eventId":"e-2","runId":"$RUN_ID","sequence":2,""" +
                            """"kind":"StepFinished","occurredAt":"2026-01-01T00:00:05Z",""" +
                            """"stageIndex":1,"stepIndex":1,"stepName":"build/sh-1",""" +
            """"stepType":"sh"}]""",
                    ),
                ).forEach { row ->
                    insert.setString(1, row.eventId)
                    insert.setString(2, RUN_ID)
                    insert.setLong(3, row.sequence)
                    insert.setString(4, row.kind)
                    insert.setString(5, "2026-01-01T00:00:00Z")
                    insert.setString(6, row.payload)
                    insert.addBatch()
                }
                insert.executeBatch()
            }
        }
    }

    /** One row of the inherited journal. */
    private data class LegacyRow(
        val eventId: String,
        val sequence: Long,
        val kind: String,
        val payload: String,
    )

    private fun finished() = RunFinished(
        eventId = "e-3",
        runId = RUN_ID,
        sequence = 0L,
        occurredAt = Instant.parse("2026-01-01T00:00:10Z"),
        outcome = "succeeded",
        diagnostics = emptyList(),
    )

    private companion object {
        const val RUN_ID = "run-42"
    }
}
