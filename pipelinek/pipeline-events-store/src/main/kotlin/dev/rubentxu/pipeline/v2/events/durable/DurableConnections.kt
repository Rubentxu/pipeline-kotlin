package dev.rubentxu.pipeline.v2.events.durable

import java.sql.Connection

/**
 * The connections the durable stores borrow: the pool they WRITE through and the pool they READ
 * through.
 *
 * ## Why there are two pools and not one
 *
 * A write and a read are not the same kind of work, and they do not deserve the same connection.
 *
 * - The WRITE pool has room for ONE connection, and it is taken inside the lock that fences the
 *   writes. The lock already says that one write happens at a time, so a second connection would
 *   not be a second writer: it would be a second writer waiting for the same lock. With one
 *   connection, a write never waits for a reader.
 * - The READ pool has room for several, because reads are concurrent: the branches of a run that
 *   execute in parallel, and the verbs that observe a run while it is alive. A reader that waits
 *   for a connection is bounded backpressure, and the bound is knowable.
 *
 * ## Why the reads go through a different door
 *
 * The two pools are not two views of the same door: the reads go through the store's READING
 * door, the one the database engine refuses to write through, in both lanes. A read that could
 * write is a read that one day does, and the door is what makes the promise true instead of
 * merely intended.
 *
 * ## What this owns
 *
 * The CONNECTIONS, and the end of them: closing this closes both pools, and a pool that is closed
 * lends nothing.
 */
internal class DurableConnections(
    /** The pool the durable stores write through. */
    val writes: SqliteConnectionPool,

    /** The pool the durable stores read through. */
    val reads: SqliteConnectionPool,
) : AutoCloseable {

    override fun close() {
        writes.close()
        reads.close()
    }

    companion object {

        /**
         * How many connections the writes may use.
         *
         * One: the writes are serialized by the lock that fences them, so a second connection
         * would wait for the same lock instead of writing.
         */
        private const val WRITE_CONNECTIONS = 1

        /**
         * How many connections the reads may use.
         *
         * Eight: the readers of a run are the branches that execute in parallel and the verbs that
         * observe a run while it is alive. The number bounds the file handles the reads cost; it
         * is not a claim about how many readers there are, and a reader beyond it waits for one to
         * come back.
         */
        private const val READ_CONNECTIONS = 8

        /**
         * The connections of the durable lane, over the two doors of the store: the producer's
         * door for the writes and the reading door for the reads.
         */
        fun over(
            writeDoor: () -> Connection,
            readDoor: () -> Connection,
        ): DurableConnections = DurableConnections(
            writes = SqliteConnectionPool(writeDoor, capacity = WRITE_CONNECTIONS),
            reads = SqliteConnectionPool(readDoor, capacity = READ_CONNECTIONS),
        )
    }
}
