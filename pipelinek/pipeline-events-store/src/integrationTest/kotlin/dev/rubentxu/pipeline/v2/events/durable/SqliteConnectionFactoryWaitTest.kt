package dev.rubentxu.pipeline.v2.events.durable

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The wait the PRODUCER's door declares, against the lock the engine actually holds.
 *
 * ## THE GUARANTEE
 *
 * A journal another writer has open does not kill the producer's door. The door waits the window
 * it declares, and when the window runs out it refuses with a message that names the lock and the
 * way out of it.
 *
 * ```text
 * waited    the door asks the engine for the lock, the engine's own handler waits, and the
 *           change of the journal mode happens with the lock in hand
 * refused   the lock did not free up inside the window: the refusal names the lock, the window
 *           and what the operator can do about it
 * ```
 *
 * ## WHY THIS INSTRUMENT
 *
 * The subject is a decision of the DATABASE ENGINE: whether the engine consults its wait when
 * the journal mode changes is a fact about the engine, not about this code. A double would answer
 * whatever it was told to answer, which is exactly the fact under test. So the rows use the
 * shipped driver, a real file on disk and a second real connection that holds the lock the way
 * another process would.
 *
 * The holder opens the file with the plain driver URL, on purpose: a holder that went through
 * the producer's own door would have put the file in WAL mode, and a file already in WAL mode is
 * not the case under test — the change of the mode only contends while the file is NOT in WAL
 * mode yet.
 *
 * ## WHY IT IS AN INTEGRATION TEST
 *
 * The file, the driver, the engine and the second connection are the real ones. Nothing in the
 * door is faked.
 *
 * ## THE ONE TIMER
 *
 * The row that proves the door WAITS needs the holder to let go of the lock while the door is
 * waiting for it, and the door is the one blocked, so the holder lets go on its own after a
 * bounded moment. The window the door declares is five seconds and the holder lets go after half
 * of one: the margin is wide enough that a loaded machine cannot turn the row into a flake, and
 * the row asserts that the door took longer than a door that did not wait would take.
 */
class SqliteConnectionFactoryWaitTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("the producer's door waits for the lock and opens when it frees up")
    fun theProducersDoorWaitsForTheLockAndOpensWhenItFreesUp() {
        val file = tempDir.resolve("journal.db").toString()
        val holder = Holder(file, releaseAfterMillis = 500)

        try {
            val started = System.nanoTime()
            SqliteConnectionFactory.open(file).use { connection ->
                assertEquals("wal", modeOf(connection))
            }
            val waited = (System.nanoTime() - started) / 1_000_000

            // A door that did not wait would have died at once, and a door that opened by luck
            // would have opened before the holder let go.
            assertTrue(
                waited >= 300,
                "the door must have waited for the lock, waited ${waited} ms",
            )
        } finally {
            holder.close()
        }
    }

    @Test
    @DisplayName("the producer's door refuses a journal that stays locked, naming the lock")
    fun theProducersDoorRefusesAJournalThatStaysLockedNamingTheLock() {
        val file = tempDir.resolve("locked.db").toString()
        val holder = Holder(file)

        try {
            val refusal = assertThrows(SQLException::class.java) {
                SqliteConnectionFactory.open(file, busyTimeoutMs = 200)
            }

            // The refusal has to be readable by whoever reads it: the lock, the window that ran
            // out and the way out of it. A raw engine error names none of that.
            assertTrue(
                refusal.message?.contains("held by another connection") == true,
                "the refusal must name the lock it could not get: ${refusal.message}",
            )
            assertTrue(
                refusal.message?.contains("200 ms") == true,
                "the refusal must name the window that ran out: ${refusal.message}",
            )
        } finally {
            holder.close()
        }
    }

    private fun modeOf(connection: Connection): String =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA journal_mode").use { rows ->
                rows.next()
                rows.getString(1)
            }
        }

    /**
     * A writer that holds the journal the way another process would.
     *
     * The lock is taken with a write transaction, which is what a writer that is doing work
     * holds, and it is given back when the row says so.
     */
    private class Holder(
        file: String,
        releaseAfterMillis: Long? = null,
    ) : AutoCloseable {

        private val connection = DriverManager.getConnection("jdbc:sqlite:$file")
        private val held = CountDownLatch(1)
        private val released = CountDownLatch(1)
        private val thread: Thread

        init {
            thread = Thread {
                connection.createStatement().use { it.execute("BEGIN IMMEDIATE") }
                held.countDown()
                if (releaseAfterMillis == null) {
                    released.await()
                } else {
                    released.await(releaseAfterMillis, TimeUnit.MILLISECONDS)
                }
                connection.createStatement().use { it.execute("COMMIT") }
            }
            thread.isDaemon = true
            thread.start()
            check(held.await(5, TimeUnit.SECONDS)) { "the holder never took the lock" }
        }

        override fun close() {
            released.countDown()
            thread.join(5_000)
            connection.close()
        }
    }
}
