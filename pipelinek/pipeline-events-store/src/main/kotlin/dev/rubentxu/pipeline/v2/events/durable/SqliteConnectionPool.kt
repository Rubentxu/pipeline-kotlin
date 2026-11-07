package dev.rubentxu.pipeline.v2.events.durable

import java.sql.Connection
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A bounded set of connections to ONE database, each owned by ONE operation at a time.
 *
 * ## What this owns
 *
 * The CONNECTIONS to a database file, and nothing else. It does not know what they are used for,
 * it does not decide who writes and who reads, it holds no statement and no transaction.
 *
 * - a CAPACITY: the pool never opens more connections than it was given, so the cost of the
 *   database is bounded and not a function of how many callers arrive at once;
 * - a LAZY opening: a connection is opened the first time somebody needs one, not when the pool
 *   is built, so a store that is never asked a question never touches the file;
 * - EXCLUSIVE OWNERSHIP: a connection that is lent out belongs to that ONE operation until it is
 *   given back. Two threads never use the same connection at the same time, and the pool is the
 *   thing that guarantees it.
 *
 * ## Why the ownership is transferred and not shared
 *
 * A connection is not safe to use from two threads at once, and a pool that let a connection be
 * shared would move that hazard from the caller to the pool. Here the loan TRANSFERS the
 * ownership: the operation that borrows is the only one that holds the connection until it
 * releases it. A caller that wants concurrency takes as many connections as it needs, up to the
 * capacity, and the capacity is the ceiling of that concurrency.
 *
 * ## What happens when the pool is full
 *
 * The borrower WAITS for a connection to come back. The pool is bounded and lossless: it does not
 * refuse a caller because it is busy, and it never hands the same connection to two borrowers. A
 * wait is the price of the bound, and the bound is what makes the number of file handles knowable.
 *
 * ## What happens after the close
 *
 * A closed pool lends nothing, and the refusal is loud ([IllegalStateException]). A connection
 * that was lent when the pool closed is closed when it comes back. The pool never opens a
 * connection after its close, so closing it really does end the connections to the file.
 */
internal class SqliteConnectionPool(
    private val open: () -> Connection,
    private val capacity: Int,
) : AutoCloseable {

    init {
        require(capacity >= 1) {
            "a pool of connections needs room for at least one: capacity=$capacity"
        }
    }

    private val lock = ReentrantLock()
    private val returned = lock.newCondition()

    /** How many connections this pool has opened, lent out or not. */
    private var live = 0

    /** The connections nobody is using right now. */
    private val idle = ArrayDeque<Connection>()

    /**
     * The connections lent out, by identity.
     *
     * Identity and not equality: the same connection is never lent twice, and a connection that
     * is given back twice must not go back to the idle queue.
     */
    private val lent = Collections.newSetFromMap(IdentityHashMap<Connection, Boolean>())

    private var closed = false

    /**
     * Runs [block] with a connection of this pool and gives the connection back.
     *
     * The connection belongs to [block] alone while it runs, and it comes back even when the
     * block fails — a connection that stayed lent out would shrink the pool by one every time an
     * operation failed, and the pool would starve instead of reporting the failure.
     */
    fun <T> withConnection(block: (Connection) -> T): T {
        val connection = borrow()
        try {
            return block(connection)
        } finally {
            release(connection)
        }
    }

    /**
     * Takes a connection out of the pool. The caller OWNS it until it gives it back with
     * [release].
     *
     * @throws IllegalStateException if the pool is closed.
     */
    fun borrow(): Connection {
        lock.withLock {
            var slot = false
            while (!slot) {
                check(!closed) { CLOSED_MESSAGE }
                val pooled = idle.pollFirst()
                if (pooled != null) {
                    lent.add(pooled)
                    return pooled
                }
                if (live < capacity) {
                    live++
                    slot = true
                } else {
                    returned.await()
                }
            }
        }

        // The slot is reserved and the lock is NOT held: opening a connection is the expensive
        // part of a loan, and holding the lock through it would make every other borrower wait
        // for a connection that is not theirs.
        val connection = try {
            open()
        } catch (failure: Throwable) {
            lock.withLock {
                live--
                returned.signalAll()
            }
            throw failure
        }

        return lock.withLock {
            if (closed) {
                runCatching { connection.close() }
                live--
                returned.signalAll()
                error(CLOSED_MESSAGE)
            }
            lent.add(connection)
            connection
        }
    }

    /**
     * Gives a connection back to the pool.
     *
     * A connection this pool did not lend, or one that was already given back, is LEFT ALONE:
     * putting it in the idle queue would lend it to a second borrower, and that is the one
     * accident this pool exists to prevent.
     */
    fun release(connection: Connection) {
        lock.withLock {
            if (!lent.remove(connection)) return@withLock
            if (closed) {
                live--
                runCatching { connection.close() }
            } else {
                idle.addLast(connection)
            }
            returned.signalAll()
        }
    }

    /**
     * Closes the pool: the connections that nobody is using are closed, and the ones that are
     * lent out are closed when they come back. The pool lends nothing after this.
     */
    override fun close() {
        val toClose = lock.withLock {
            if (closed) {
                emptyList()
            } else {
                closed = true
                val pooled = idle.toList()
                idle.clear()
                live -= pooled.size
                returned.signalAll()
                pooled
            }
        }
        toClose.forEach { runCatching { it.close() } }
    }

    private companion object {
        const val CLOSED_MESSAGE = "this pool of connections is closed"
    }
}
