package dev.rubentxu.pipeline.v2.events.durable

import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The pool of connections, and the ownership it transfers.
 *
 * ## The guarantee
 *
 * A connection belongs to ONE operation at a time. The pool lends it, the operation uses it, and
 * the operation gives it back; no two operations ever hold the same connection at the same moment.
 *
 * Around that, two bounds that make the cost of a database knowable: the pool opens no more
 * connections than its capacity, and it opens them lazily, so a pool nobody asks anything of never
 * touches the file.
 *
 * ## Why this is a UNIT test
 *
 * The subject is the POOL, and the pool only opens, lends and closes connections: it does not
 * query anything. The connections it lends can therefore be in-memory databases — real
 * connections, with no file behind them — and the test can watch the number of them opened
 * without a database, a schema or a clock.
 *
 * The behaviour that DOES need a database — that a read cannot write, that a corrupted file is
 * refused — is proved in the integration lane, where the engine is the one that answers.
 */
class SqliteConnectionPoolTest {

    /**
     * A real connection to a private in-memory database: no file, no disk.
     */
    private fun memoryConnection(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @Test
    fun `the pool opens nothing until somebody asks for a connection`() {
        val opens = AtomicInteger()
        val pool = SqliteConnectionPool({ opens.incrementAndGet(); memoryConnection() }, capacity = 2)

        assertEquals(0, opens.get(), "a pool nobody asked anything of must not open a connection")
        pool.close()
    }

    @Test
    fun `a connection that comes back is lent again instead of opened again`() {
        val opens = AtomicInteger()
        val pool = SqliteConnectionPool({ opens.incrementAndGet(); memoryConnection() }, capacity = 1)

        val first = pool.borrow()
        pool.release(first)
        val second = pool.borrow()

        assertSame(first, second, "the connection comes back to the pool and is lent again")
        assertEquals(1, opens.get(), "a connection that was given back is not opened again")
        pool.release(second)
        pool.close()
    }

    @Test
    fun `two loans at the same time are two different connections`() {
        val pool = SqliteConnectionPool({ memoryConnection() }, capacity = 2)

        val first = pool.borrow()
        val second = pool.borrow()

        assertNotSame(first, second, "a connection lent to one operation is not lent to another")
        pool.release(first)
        pool.release(second)
        pool.close()
    }

    @Test
    fun `a connection that is lent is not lent to a second borrower`() {
        val pool = SqliteConnectionPool({ memoryConnection() }, capacity = 2)

        val first = pool.borrow()
        pool.release(first)

        // The connection is idle again, and the next borrower takes it OUT of the queue. A pool
        // that left it in the queue would hand the SAME connection to the borrower after it, and
        // two operations would be holding one connection at the same moment.
        val second = pool.borrow()
        val third = pool.borrow()

        assertNotSame(
            second,
            third,
            "a connection that is lent out belongs to ONE operation at a time: the second " +
                "borrower was handed the connection the first one was still holding",
        )
        pool.release(second)
        pool.release(third)
        pool.close()
    }

    @Test
    fun `the pool never opens more connections than its capacity`() {
        val opens = AtomicInteger()
        val pool = SqliteConnectionPool({ opens.incrementAndGet(); memoryConnection() }, capacity = 2)
        val first = pool.borrow()
        val second = pool.borrow()

        // A third operation arrives while the two connections are lent. It must WAIT for one of
        // them, not open a third connection: the capacity is the ceiling of the file handles.
        val borrowed = AtomicReference<Connection>()
        val started = CountDownLatch(1)
        val borrower = Thread {
            started.countDown()
            borrowed.set(pool.borrow())
        }
        borrower.start()
        started.await()
        pool.release(first)
        borrower.join(5_000)

        assertSame(first, borrowed.get(), "the waiting operation is served with the connection that came back")
        assertEquals(2, opens.get(), "the pool opened two connections, and no more")
        pool.release(second)
        pool.release(borrowed.get())
        pool.close()
    }

    @Test
    fun `a closed pool lends nothing and says so`() {
        val pool = SqliteConnectionPool({ memoryConnection() }, capacity = 1)
        val connection = pool.borrow()
        pool.release(connection)

        pool.close()

        val refusal = assertThrows(IllegalStateException::class.java) { pool.borrow() }
        assertTrue(
            refusal.message?.contains("closed") == true,
            "the refusal must say the pool is closed, got: ${refusal.message}",
        )
        assertTrue(connection.isClosed, "closing the pool closes the connections it was holding")
    }

    @Test
    fun `a connection lent when the pool closes is closed when it comes back`() {
        val pool = SqliteConnectionPool({ memoryConnection() }, capacity = 1)
        val lent = pool.borrow()

        pool.close()
        assertTrue(!lent.isClosed, "a lent connection is closed when it comes back, not before")

        pool.release(lent)
        assertTrue(lent.isClosed, "the connection that was lent when the pool closed is closed on release")
    }
}
