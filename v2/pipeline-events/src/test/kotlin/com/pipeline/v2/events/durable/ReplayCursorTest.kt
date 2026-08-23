package com.pipeline.v2.events.durable

import com.pipeline.v2.events.SqliteEventStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ReplayCursorTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `load after close reopen returns persisted cursor`() {
        val dbPath = tempDir.resolve("cursor-test.db").toString()
        val eventStore1 = SqliteEventStore(dbPath)
        val factory1 = eventStore1.underlyingConnectionFactory()
        val store1 = ReplayCursorStore(factory1)
        store1.advance("run-1", "op-5", 2)

        // Simulate restart.
        val eventStore2 = SqliteEventStore(dbPath)
        val factory2 = eventStore2.underlyingConnectionFactory()
        val store2 = ReplayCursorStore(factory2)
        val cursor = store2.load("run-1")

        assertNotNull(cursor)
        assertEquals("run-1", cursor!!.runId)
        assertEquals("op-5", cursor.lastOpId)
        assertEquals(2, cursor.stageIndex)
    }

    @Test
    fun `advance overwrites previous cursor`() {
        val dbPath = tempDir.resolve("cursor-overwrite.db").toString()
        val eventStore = SqliteEventStore(dbPath)
        val factory = eventStore.underlyingConnectionFactory()
        val store = ReplayCursorStore(factory)

        store.advance("run-1", "op-first", 0)
        store.advance("run-1", "op-second", 1)

        val cursor = store.load("run-1")
        assertNotNull(cursor)
        assertEquals("op-second", cursor!!.lastOpId)
        assertEquals(1, cursor.stageIndex)
    }

    @Test
    fun `unknown runId returns null`() {
        val dbPath = tempDir.resolve("cursor-unknown.db").toString()
        val eventStore = SqliteEventStore(dbPath)
        val factory = eventStore.underlyingConnectionFactory()
        val store = ReplayCursorStore(factory)

        val cursor = store.load("nonexistent-run")
        assertNull(cursor)
    }

    @Test
    fun `negative stageIndex is rejected`() {
        val dbPath = tempDir.resolve("cursor-negative.db").toString()
        val eventStore = SqliteEventStore(dbPath)
        val factory = eventStore.underlyingConnectionFactory()
        val store = ReplayCursorStore(factory)

        assertThrows(IllegalArgumentException::class.java) {
            store.advance("run-1", "op-1", -1)
        }
    }
}
