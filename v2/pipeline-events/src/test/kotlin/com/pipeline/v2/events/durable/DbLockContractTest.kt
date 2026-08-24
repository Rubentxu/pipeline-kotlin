package com.pipeline.v2.events.durable

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Contract tests for [DbLock] (C-033).
 *
 * Tests the F13 HIGH finding closure: DbLock replaces the per-instance
 * synchronized(this) with a cross-instance static ConcurrentHashMap,
 * ensuring serialized writes across multiple instances.
 */
class DbLockContractTest {

    @TempDir
    lateinit var tempDir: Path

    /**
     * C-033.1: Single instance retains pre-fix behavior (synchronized replacement).
     *
     * Verifies that a single OperationJournal with DbLock still works correctly —
     * the lock is transparent to correct single-threaded usage.
     */
    @Test
    fun `single instance serial appends`() {
        val dbPath = tempDir.resolve("single-lock-test.db").toString()
        val eventStore = com.pipeline.v2.events.SqliteEventStore(dbPath)
        val journal: OperationJournal = SqliteOperationJournalImpl(
            eventStore.underlyingConnectionFactory(),
            object : com.pipeline.v2.domain.durable.Clock {
                override fun now() = java.time.Clock.systemUTC().instant()
            },
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true },
            eventStore.databasePath(),
        )

        // Append should succeed without deadlocks or race conditions
        journal.append(
            com.pipeline.v2.domain.durable.RerunOperation(
                id = "op-single-1",
                fingerprint = com.pipeline.v2.domain.durable.Fingerprint("a".repeat(64)),
                input = com.pipeline.v2.domain.durable.OperationInput("step", mapOf(), "run-1", 1),
                output = null,
                status = com.pipeline.v2.domain.durable.OperationStatus.SUCCEEDED,
                attempt = 1,
            )
        )

        val retrieved = journal.get("op-single-1")
        assertNotNull(retrieved, "append should persist the operation")
        assertEquals("op-single-1", retrieved!!.id)
    }

    /**
     * C-033.2: Two instances over same DB serialize writes across N threads.
     *
     * Verifies that two OperationJournal instances writing to the same DB file
     * serialize correctly — no SQLITE_BUSY and no torn writes.
     */
    @Test
    fun `two instances parallel threads serialize`() {
        val dbPath = tempDir.resolve("parallel-lock-test.db").toString()
        val eventStore = com.pipeline.v2.events.SqliteEventStore(dbPath)
        val clock = object : com.pipeline.v2.domain.durable.Clock {
            override fun now() = java.time.Clock.systemUTC().instant()
        }
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

        val journal1: OperationJournal = SqliteOperationJournalImpl(
            eventStore.underlyingConnectionFactory(),
            clock,
            json,
            eventStore.databasePath(),
        )
        val journal2: OperationJournal = SqliteOperationJournalImpl(
            eventStore.underlyingConnectionFactory(),
            clock,
            json,
            eventStore.databasePath(),
        )

        // Simulate concurrent writes from two "threads" (runnables)
        val errors = mutableListOf<Throwable>()
        val thread1 = Thread {
            try {
                for (i in 1..10) {
                    journal1.append(
                        com.pipeline.v2.domain.durable.RerunOperation(
                            id = "op-parallel-1-$i",
                            fingerprint = com.pipeline.v2.domain.durable.Fingerprint("a".repeat(64)),
                            input = com.pipeline.v2.domain.durable.OperationInput("step", mapOf(), "run-1", 1),
                            output = null,
                            status = com.pipeline.v2.domain.durable.OperationStatus.SUCCEEDED,
                            attempt = 1,
                        )
                    )
                }
            } catch (e: Throwable) {
                errors.add(e)
            }
        }
        val thread2 = Thread {
            try {
                for (i in 1..10) {
                    journal2.append(
                        com.pipeline.v2.domain.durable.RerunOperation(
                            id = "op-parallel-2-$i",
                            fingerprint = com.pipeline.v2.domain.durable.Fingerprint("b".repeat(64)),
                            input = com.pipeline.v2.domain.durable.OperationInput("step", mapOf(), "run-2", 1),
                            output = null,
                            status = com.pipeline.v2.domain.durable.OperationStatus.SUCCEEDED,
                            attempt = 1,
                        )
                    )
                }
            } catch (e: Throwable) {
                errors.add(e)
            }
        }

        thread1.start()
        thread2.start()
        thread1.join()
        thread2.join()

        assertTrue(errors.isEmpty(), "No errors should occur: $errors")
        // All 20 operations should be persisted
        for (i in 1..10) {
            assertNotNull(journal1.get("op-parallel-1-$i"), "journal1 op $i should exist")
            assertNotNull(journal2.get("op-parallel-2-$i"), "journal2 op $i should exist")
        }

        // Cleanup: clear locks after parallel test
        DbLock.clearForTest()
    }

    /**
     * C-033.3: busy_timeout pragma is set on every connection.
     *
     * Verifies that SqliteConnectionFactory sets PRAGMA busy_timeout = 5000
     * on every new connection, so SQLite waits up to 5s before returning BUSY.
     */
    @Test
    fun `busy_timeout pragma set on every connection`() {
        val dbPath = tempDir.resolve("busy-timeout-test.db").toString()
        val factory = com.pipeline.v2.events.SqliteEventStore(dbPath)
        val conn = factory.underlyingConnectionFactory()()

        try {
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("PRAGMA busy_timeout")
                rs.next()
                val busyTimeout = rs.getInt(1)
                assertEquals(5000, busyTimeout, "busy_timeout should be 5000ms")
            }
        } finally {
            conn.close()
        }
    }
}
