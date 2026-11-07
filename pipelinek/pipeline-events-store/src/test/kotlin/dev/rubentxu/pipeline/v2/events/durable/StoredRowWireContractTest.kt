package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.events.EventRecordRead
import dev.rubentxu.pipeline.v2.events.RunStarted
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * The wire BETWEEN the store and the row decoder: a stored row is a JSON array of EXACTLY ONE
 * event, and the two ends of that wire agree on it.
 *
 * ## What these rows hold
 *
 * The store writes the row and the store reads it back, and the two halves live in DIFFERENT
 * files: the writer binds the payload column and the reader hands it to the codec. A test that
 * compares the codec with itself says nothing about that seam, because both halves would have
 * to be wrong in the SAME way for the comparison to stay green.
 *
 * So the rows here cross the seam with the REAL writer and the REAL reader, over the REAL SQLite
 * engine, and they read the COLUMN the writer left — never a string the test wrote by hand.
 *
 * ## What is faked, and why
 *
 * Only the LOCATION of the storage: the database is in memory (`mode=memory&cache=shared`), so
 * the test touches no disk and leaves no file behind. The engine, the writer, the schema, the
 * reader and the codec are the product's own. A fake writer would have to re-implement the column
 * binding to be useful, and a fake that writes the same bytes the product writes is the thing
 * under test.
 *
 * ## The defect these rows hunt
 *
 * The row decoder used to CUT the brackets off the payload with `substring(1, length - 1)` and
 * never looked at the shape. A payload that was not an array reached the event decoder as
 * RESIDUE and was refused for a field it never had ("eventId is unreadable"), and a payload that
 * held two events kept the first one in silence. A row is refused because of what it HOLDS, and
 * the refusal has to name that.
 */
class StoredRowWireContractTest {

    /**
     * The row the store writes IS a unit array of one event, and the row decoder reads it.
     *
     * The pin and the roundtrip are two assertions about the SAME column and they fail for
     * different reasons, which is why both are here. The pin says what the bytes ARE, checked
     * with the serialization runtime and not with the product's own reader, so it does not move
     * together with the code it pins. The roundtrip says the decoder reads those bytes, which is
     * what makes the pin a CONTRACT and not a description.
     */
    @Test
    fun `the row the store writes is a unit array of one event, and the row decoder reads it`() {
        val database = RealDatabase()
        val writer = SqliteEventWriter(database.connections)
        try {
            val written = writer.appendAssigned(event("e-1"))
            writer.flush()

            // The COLUMN, as the writer left it. Nothing here rebuilds the payload.
            val payload = database.payloadOf("e-1")

            // THE PIN: a JSON array of EXACTLY ONE object.
            val array = assertInstanceOf(
                JsonArray::class.java,
                Json.parseToJsonElement(payload),
                "the stored payload is not a JSON array: \"$payload\"",
            )
            assertEquals(
                1,
                array.size,
                "a stored row holds EXACTLY ONE event and the column holds ${array.size}",
            )
            assertInstanceOf(
                JsonObject::class.java,
                array.single(),
                "the one event of the row is not a JSON object: \"$payload\"",
            )

            // THE ROUNDTRIP: the row decoder reads exactly what the writer wrote.
            val outcome = JsonEventLog.decodeStoredRow(payload, "RunStarted")
            val accepted = assertInstanceOf(
                StoredRowDecode.Accepted::class.java,
                outcome,
                "the row decoder did not read the row the writer wrote: $outcome",
            )
            assertEquals(written.eventId, accepted.event.eventId)
            assertEquals(written.sequence, accepted.event.sequence)
            assertEquals(RUN_ID, accepted.event.runId)
        } finally {
            runCatching { writer.close() }
            database.close()
        }
    }

    /**
     * A row the store CANNOT read is reported, not omitted.
     *
     * The corrupted column is the row written as a BARE OBJECT instead of a unit array, and it is
     * produced by the product's own one-object encoder — so the corruption is a shape the
     * product can really write, not a string this test made up.
     *
     * The row next to it is there for the same reason: a page that reports the unreadable row
     * and DROPS the readable one would also pass a test that only looked at the refusal.
     */
    @Test
    fun `a row the store cannot read is reported, not omitted`() {
        val database = RealDatabase()
        val writer = SqliteEventWriter(database.connections)
        try {
            writer.appendAssigned(event("e-1", sequence = 1))
            writer.appendAssigned(event("e-2", sequence = 2))
            writer.flush()

            database.setPayload("e-2", JsonEventLog.encodeOne(event("e-2", sequence = 2)))

            val page = SqliteEventReader(database.connections, EventStoreMode.ReadOnly)
                .readRecords(RUN_ID, null, 10)

            assertEquals(
                2,
                page.records.size,
                "a row that does not decode is still a ROW: the page counts rows, not decodable " +
                    "events, and an unreadable row that disappears is a hole in the history",
            )
            val readable = assertInstanceOf(
                EventRecordRead.Decoded::class.java,
                page.records[0],
                "the row that CAN be read was not read: ${page.records[0]}",
            )
            assertEquals("e-1", readable.event.eventId)
            val unreadable = assertInstanceOf(
                EventRecordRead.Undecodable::class.java,
                page.records[1],
                "the row that cannot be read was not reported as such: ${page.records[1]}",
            )
            assertEquals(2L, unreadable.sequence, "the refusal carries the row's OWN sequence")
            assertEquals("e-2", unreadable.eventId)
        } finally {
            runCatching { writer.close() }
            database.close()
        }
    }

    private fun event(eventId: String, sequence: Long = 0L): RunStarted = RunStarted(
        eventId = eventId,
        runId = RUN_ID,
        sequence = sequence,
        occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
        scriptPath = "pipeline.kts",
    )

    /**
     * A REAL SQLite database, in memory, over the shared cache.
     *
     * The writer holds one connection and the reader opens its own, so the database has to
     * outlive both: `mode=memory&cache=shared` keeps ONE database alive as long as a connection
     * to it is open, and this one holds it open for the whole test.
     */
    private class RealDatabase : AutoCloseable {

        private val name = "stored-row-wire-${System.nanoTime()}"

        val connections: () -> Connection = {
            DriverManager.getConnection("jdbc:sqlite:file:$name?mode=memory&cache=shared")
        }

        private val keepAlive: Connection = connections()

        /** The payload COLUMN of the row, as the store left it. */
        fun payloadOf(eventId: String): String = connections().use { connection ->
            connection.prepareStatement("SELECT payload FROM events WHERE event_id = ?").use { statement ->
                statement.setString(1, eventId)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "the row \"$eventId\" is not in the store" }
                    rows.getString(1)
                }
            }
        }

        /** Puts [payload] in the row's column, the way a damaged store would. */
        fun setPayload(eventId: String, payload: String) {
            connections().use { connection ->
                connection.prepareStatement("UPDATE events SET payload = ? WHERE event_id = ?").use { statement ->
                    statement.setString(1, payload)
                    statement.setString(2, eventId)
                    statement.executeUpdate()
                }
            }
        }

        override fun close() {
            keepAlive.close()
        }
    }

    private companion object {
        const val RUN_ID = "run-stored-row-wire"
    }
}
