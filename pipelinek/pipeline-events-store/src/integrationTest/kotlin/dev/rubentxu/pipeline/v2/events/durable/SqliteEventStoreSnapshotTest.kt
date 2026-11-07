package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.events.EventRecordRead
import dev.rubentxu.pipeline.v2.events.EventRecordSlice
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.events.identity.EventQuery
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The store's snapshot, against the real database.
 *
 * ## The guarantee
 *
 * A read of a run through the port answers from ONE instant of the durable history, and it costs
 * ONE connection.
 *
 * The instant matters because the tail and the page are two facts about the same run: an append
 * that lands between two reads of it produces a pair that cannot both be true — a page newer than
 * the tail it was cut against, or a run whose first event arrived after the tail was read and was
 * therefore refused as a cursor past a tail that did not exist. The connection matters because a
 * read that opens a connection per fact is a read whose cost is the number of facts it asks
 * about, and that cost is paid on every poll of every follower.
 *
 * ## Why this test is an INTEGRATION test and not a unit test
 *
 * The guarantee is about a DURABLE store: its snapshot is a transaction the database engine holds
 * for the reader, and that behaviour belongs to SQLite and not to this repository's code. A double
 * would answer with whatever the double was told to answer, which is exactly the fact under test.
 * The file is the substrate of the module under test — the store IS a file — so it is not faked
 * here; what the test owns is the store's DOOR, the factory every connection is opened through.
 *
 * ## What the fakes buy
 *
 * The door is the seam the test owns: it counts the connections a read opens, and it lets a fact
 * land in the middle of a read. Nothing else is faked — the store, the writer, the schema and the
 * transactions are the real ones.
 */
class SqliteEventStoreSnapshotTest {

    @TempDir
    lateinit var tempDir: Path

    private val runId = "v2:run:snapshot"
    private val startedAt = Instant.parse("2026-01-01T00:00:00Z")

    private fun started(eventId: String) = RunStarted(
        eventId = eventId,
        runId = runId,
        sequence = 0L,
        occurredAt = startedAt,
        scriptPath = "pipeline.kts",
    )

    private fun dbPath(): String = tempDir.resolve("pipeline.db").toString()

    private fun eventIds(slice: EventRecordSlice): List<String?> = slice.records.map {
        when (it) {
            is EventRecordRead.Decoded -> it.event.eventId
            is EventRecordRead.Undecodable -> it.eventId
        }
    }

    /**
     * A connection that lets the test land a fact in the middle of a read.
     *
     * The hook fires when the PAGE query is prepared — after the tail has been read and before the
     * page is read — which is the exact window in which two separate reads would disagree.
     */
    private class InterleavedConnection(
        private val real: Connection,
        private val onPageQuery: () -> Unit,
    ) : Connection by real {
        override fun prepareStatement(sql: String): PreparedStatement {
            if (sql.contains("ORDER BY sequence ASC LIMIT")) onPageQuery()
            return real.prepareStatement(sql)
        }
    }

    @Test
    @DisplayName("a port read opens ONE connection, with a cursor or without one")
    fun aPortReadOpensOneConnection() {
        val file = dbPath()
        var opens = 0
        val store = SqliteEventStore(
            file,
            EventStoreMode.Writable,
            readerConnections = {
                opens++
                openEventStoreReaderConnection(file)
            },
        )
        store.append(started("evt-1"))
        store.flush()
        val port = EventRecordReadPortStoreAdapter(reader = store, runDeclared = { false })

        opens = 0
        port.readRecords(runId, null, EventQuery.All, 10)
        assertEquals(
            1,
            opens,
            "a read without a cursor must open ONE connection, not one per fact it asks about",
        )

        opens = 0
        port.readRecords(runId, EventCursor(runId, 0L), EventQuery.All, 10)
        assertEquals(
            1,
            opens,
            "a read with a cursor must open ONE connection, not one per fact it asks about",
        )
        store.close()
    }

    @Test
    @DisplayName("the tail and the page come from the same instant of the store")
    fun theTailAndThePageComeFromTheSameInstant() {
        val file = dbPath()
        var landed = false
        lateinit var store: SqliteEventStore
        val connections = {
            InterleavedConnection(openEventStoreReaderConnection(file)) {
                if (!landed) {
                    landed = true
                    // The fact lands BETWEEN the tail and the page of the read below.
                    store.append(started("evt-2"))
                    store.flush()
                }
            }
        }
        store = SqliteEventStore(file, EventStoreMode.Writable, readerConnections = connections)
        store.append(started("evt-1"))
        store.flush()

        val snapshot = store.snapshotOf(runId, null, 10)

        assertTrue(landed, "the fact must have landed during the read, or this proves nothing")
        assertEquals(
            1L,
            snapshot.tail,
            "the tail must be the one the page was cut against",
        )
        assertEquals(
            listOf("evt-1"),
            eventIds(snapshot.page),
            "the page must not show a fact that landed after the instant the tail was read",
        )

        val later = store.snapshotOf(runId, null, 10)
        assertEquals(2L, later.tail, "the landed fact must be visible to a LATER read")
        assertEquals(
            listOf("evt-1", "evt-2"),
            eventIds(later.page),
            "the landed fact must be visible to a LATER read",
        )
        store.close()
    }
}
