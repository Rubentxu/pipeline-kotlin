package dev.rubentxu.pipeline.v2.output.store

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The guarantee: an exclusion lives exactly as long as the work that needs it.
 *
 * The capacity this serves: a control root that serves a thousand runs in one process does not
 * keep a lock for every stream it ever touched. The bytes of a stream are bounded by the root's
 * retention, but the lock was not — it was a map that only ever grew, so a long-lived process
 * accumulated one entry per stream the root ever saw, and kept them for streams that no longer
 * existed.
 *
 * The entry exists while a caller is inside it or waiting for it, and no longer. The count of the
 * callers is what makes the removal safe: an entry is dropped only when nobody holds it and nobody
 * waits for it, so a caller that already took the entry and has not yet acquired it still finds
 * the exclusion it came for.
 *
 * These rows are pure: a lock and a counter. No directory, no store, no clock, no thread.
 */
class StreamLocksTest {

    @Test
    fun `the registry holds the exclusion of the stream being used`() {
        val locks = StreamLocks()

        locks.withLock("stream-a") {
            assertEquals(
                1,
                locks.heldStreams,
                "the stream being excluded is the one the registry is holding: the exclusion has " +
                    "to exist BEFORE the caller is inside it",
            )
        }
    }

    @Test
    fun `the registry holds one entry per stream, not one for all of them`() {
        val locks = StreamLocks()

        locks.withLock("stream-a") {
            locks.withLock("stream-b") {
                assertEquals(
                    2,
                    locks.heldStreams,
                    "two streams are two directories and two exclusions: one entry for both would " +
                        "serialise streams that have nothing to do with each other",
                )
            }
        }
    }

    @Test
    fun `the registry holds no entry for a stream nobody is inside`() {
        val locks = StreamLocks()

        repeat(100) { index ->
            locks.withLock("stream-$index") { }
        }

        assertEquals(
            0,
            locks.heldStreams,
            "the registry is a fact about the work in flight, not a list of every stream the root " +
                "ever touched: a hundred released streams leave nothing behind",
        )
    }
}
