package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.events.SqliteEventStore
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Contract tests for [ReplayCursorStore] interface.
 * Tests the interface contract per M3-R1 design.md §8 and C-014.
 */
class ReplayCursorStoreContractTest {

    @TempDir
    lateinit var tempDir: Path

    private fun freshStore(clock: Clock): ReplayCursorStore {
        val dbPath = tempDir.resolve("cursor-contract-test.db").toString()
        val eventStore = SqliteEventStore(dbPath)
        return SqliteReplayCursorStoreImpl(eventStore.underlyingConnectionFactory(), clock)
    }

    private val systemClock: Clock = object : Clock {
        override fun now() = java.time.Clock.systemUTC().instant()
    }

    @Test
    fun `load returns null for unknown runId`() {
        val store = freshStore(systemClock)
        assertNull(store.load("nonexistent-run"))
    }

    @Test
    fun `advance then load returns the cursor`() {
        val store = freshStore(systemClock)
        store.advance("run-1", "op-5", 2)
        val cursor = store.load("run-1")
        assertNotNull(cursor)
        assertEquals("run-1", cursor!!.runId)
        assertEquals("op-5", cursor.lastOpId)
        assertEquals(2, cursor.stageIndex)
    }

    @Test
    fun `advance is idempotent - later advance wins`() {
        val store = freshStore(systemClock)
        store.advance("run-1", "op-first", 0)
        store.advance("run-1", "op-second", 1)
        val cursor = store.load("run-1")
        assertNotNull(cursor)
        assertEquals("op-second", cursor!!.lastOpId)
        assertEquals(1, cursor.stageIndex)
    }
}
