package dev.rubentxu.pipeline.v2.events.durable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Whether a reader of a run's journal can change it.
 *
 * The guarantee issue #145 asks for has two halves. This file holds the one that can be decided
 * without a disk: a verb that READS a run opens the store in the reader's lane, and the reader's
 * lane cannot write.
 *
 * The mapping is the whole of that guarantee, and it is a mapping somebody can break by accident:
 * a follow that "needs to repair something" is exactly the kind of change that would hand the
 * reader a writer's permissions again, and nothing else in the tree would notice. The symptom
 * only shows when a reader and a producer touch the same file at the same time, and the end-to-end
 * scenario cannot make that happen on demand — so the mapping is pinned here, per purpose.
 *
 * The behavioural half — an observer killed in mid-flight leaves the producer running — is pinned
 * by the end-to-end scenario `examples/events/06-observer-death-does-not-kill-the-producer.pipeline.kts`,
 * which runs the real distribution. Nothing here touches a database.
 */
class EventStoreModeTest {

    @Test
    @DisplayName("a reader of a run's journal opens it in the lane that cannot write")
    fun aReaderOpensTheStoreInTheLaneThatCannotWrite() {
        // The reader is a VIEW over a journal the producer owns. A view that writes is not a
        // view: it changes the producer's file, and it can hold a write lock on it.
        assertEquals(EventStoreMode.ReadOnly, EventStorePurpose.Read.storeMode())
        assertFalse(EventStoreMode.ReadOnly.mayWrite())
    }

    @Test
    @DisplayName("the producer owns the file and may write it")
    fun theProducerOwnsTheFile() {
        assertEquals(EventStoreMode.Writable, EventStorePurpose.Write.storeMode())
        assertTrue(EventStoreMode.Writable.mayWrite())
    }

    @Test
    @DisplayName("every purpose lands in one of the two lanes")
    fun everyPurposeLandsInOneOfTheTwoLanes() {
        // The mapping is TOTAL over the closed set of purposes: the compiler refuses a new
        // purpose that nobody decided a lane for, and this states the reachable lanes so that a
        // third one cannot appear unnoticed.
        val purposes = listOf(EventStorePurpose.Read, EventStorePurpose.Write)
        val lanes = purposes.map { it.storeMode() }.toSet()

        assertEquals(setOf(EventStoreMode.ReadOnly, EventStoreMode.Writable), lanes)
    }
}
