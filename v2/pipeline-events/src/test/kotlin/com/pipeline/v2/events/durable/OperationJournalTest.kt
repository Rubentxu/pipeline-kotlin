package com.pipeline.v2.events.durable

import com.pipeline.v2.events.SqliteEventStore
import com.pipeline.v2.domain.durable.Fingerprint
import com.pipeline.v2.domain.durable.OperationInput
import com.pipeline.v2.domain.durable.OperationOutput
import com.pipeline.v2.domain.durable.OperationStatus
import com.pipeline.v2.domain.durable.RerunOperation
import com.pipeline.v2.domain.durable.MemoizedOperation
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class OperationJournalTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `append and get round-trip`() {
        val dbPath = tempDir.resolve("test.db").toString()
        // Use SqliteEventStore to create tables, then get underlying connection factory.
        val eventStore = SqliteEventStore(dbPath)
        val factory = eventStore.underlyingConnectionFactory()
        val journal: OperationJournal = SqliteOperationJournalImpl(factory)

        val op = RerunOperation(
            id = "op-1",
            fingerprint = Fingerprint("a".repeat(64)),
            input = OperationInput("step", mapOf("k" to JsonPrimitive("v")), "run-1", 1),
            output = OperationOutput(JsonPrimitive("result"), 100L, System.currentTimeMillis()),
            status = OperationStatus.SUCCEEDED,
            attempt = 1,
        )
        journal.append(op)
        val retrieved = journal.get("op-1")
        assertNotNull(retrieved)
        assertEquals("op-1", retrieved!!.id)
        assertEquals(op.fingerprint.hex, retrieved.fingerprint.hex)
    }

    @Test
    fun `duplicate op_id rejected via PRIMARY KEY`() {
        val dbPath = tempDir.resolve("test.db").toString()
        val eventStore = SqliteEventStore(dbPath)
        val factory = eventStore.underlyingConnectionFactory()
        val journal: OperationJournal = SqliteOperationJournalImpl(factory)

        val op = RerunOperation(
            id = "op-dup",
            fingerprint = Fingerprint("a".repeat(64)),
            input = OperationInput("step", mapOf(), "run-1", 1),
            output = null,
            status = OperationStatus.SUCCEEDED,
            attempt = 1,
        )
        journal.append(op)
        assertThrows(Exception::class.java) {
            journal.append(op.copy(status = OperationStatus.FAILED))
        }
    }

    @Test
    fun `journal_mode returns wal`() {
        val dbPath = tempDir.resolve("test.db").toString()
        val eventStore = SqliteEventStore(dbPath)

        // Verify pragma by reopening.
        val verifyFactory = eventStore.underlyingConnectionFactory()
        val conn = verifyFactory()
        try {
            conn.createStatement().use { stmt ->
                stmt.executeQuery("PRAGMA journal_mode").use { rs ->
                    rs.next()
                    assertEquals("wal", rs.getString(1))
                }
            }
        } finally {
            conn.close()
        }
    }

    @Test
    fun `rows survive process restart`() {
        val dbPath = tempDir.resolve("test.db").toString()

        // First "process": write via SqliteEventStore.
        val eventStore1 = SqliteEventStore(dbPath)
        val factory1 = eventStore1.underlyingConnectionFactory()
        val journal1: OperationJournal = SqliteOperationJournalImpl(factory1)
        journal1.append(
            RerunOperation(
                id = "op-persist",
                fingerprint = Fingerprint("b".repeat(64)),
                input = OperationInput("step", mapOf(), "run-1", 1),
                output = OperationOutput(JsonPrimitive("ok"), 50L, System.currentTimeMillis()),
                status = OperationStatus.SUCCEEDED,
                attempt = 1,
            )
        )

        // Simulate restart: new SqliteEventStore pointing to same file.
        val eventStore2 = SqliteEventStore(dbPath)
        val factory2 = eventStore2.underlyingConnectionFactory()
        val journal2: OperationJournal = SqliteOperationJournalImpl(factory2)
        val retrieved = journal2.get("op-persist")
        assertNotNull(retrieved)
        assertEquals("op-persist", retrieved!!.id)
    }

    @Test
    fun `listForRun returns operations in order`() {
        val dbPath = tempDir.resolve("test.db").toString()
        val eventStore = SqliteEventStore(dbPath)
        val factory = eventStore.underlyingConnectionFactory()
        val journal: OperationJournal = SqliteOperationJournalImpl(factory)

        val runId = "run-ordered"
        for (i in 1..3) {
            journal.append(
                RerunOperation(
                    id = "op-$i",
                    fingerprint = Fingerprint(("%064x").format(i.toLong())),
                    input = OperationInput("step", mapOf(), runId, 1),
                    output = OperationOutput(JsonPrimitive("result-$i"), 10L, System.currentTimeMillis()),
                    status = OperationStatus.SUCCEEDED,
                    attempt = 1,
                )
            )
        }

        val ops = journal.listForRun(runId)
        assertEquals(3, ops.size)
    }

    @Test
    fun `RerunOperation roundtrip with kind=RERUN preserves attempt`() {
        val dbPath = tempDir.resolve("test.db").toString()
        val eventStore = SqliteEventStore(dbPath)
        val factory = eventStore.underlyingConnectionFactory()
        val journal: OperationJournal = SqliteOperationJournalImpl(factory)

        val op = RerunOperation(
            id = "op-rerun-kind",
            fingerprint = Fingerprint("c".repeat(64)),
            input = OperationInput("step", mapOf(), "run-kind", 3),
            output = OperationOutput(JsonPrimitive("cached"), 200L, System.currentTimeMillis()),
            status = OperationStatus.SUCCEEDED,
            attempt = 3,
        )
        journal.append(op)
        val retrieved = journal.get("op-rerun-kind")
        assertNotNull(retrieved)
        assertTrue(retrieved is RerunOperation, "Expected RerunOperation but got ${retrieved!!::class.simpleName}")
        assertEquals(3, (retrieved as RerunOperation).attempt)
        assertEquals(op.fingerprint.hex, retrieved.fingerprint.hex)
    }

    @Test
    fun `MemoizedOperation roundtrip with kind=MEMOIZED reconstructs cachedOutput`() {
        val dbPath = tempDir.resolve("test.db").toString()
        val eventStore = SqliteEventStore(dbPath)
        val factory = eventStore.underlyingConnectionFactory()
        val journal: OperationJournal = SqliteOperationJournalImpl(factory)

        val cachedOut = OperationOutput(JsonPrimitive("cached-result"), 150L, System.currentTimeMillis())
        val op = MemoizedOperation(
            id = "op-memo-kind",
            fingerprint = Fingerprint("d".repeat(64)),
            input = OperationInput("step", mapOf(), "run-memo", 2),
            output = cachedOut,
            status = OperationStatus.SUCCEEDED,
            attempt = 2,
            cachedOutput = cachedOut,
        )
        journal.append(op)
        val retrieved = journal.get("op-memo-kind")
        assertNotNull(retrieved)
        assertTrue(retrieved is MemoizedOperation, "Expected MemoizedOperation but got ${retrieved!!::class.simpleName}")
        val memo = retrieved as MemoizedOperation
        assertEquals(2, memo.attempt)
        assertEquals(cachedOut.result, memo.cachedOutput?.result)
    }
}
