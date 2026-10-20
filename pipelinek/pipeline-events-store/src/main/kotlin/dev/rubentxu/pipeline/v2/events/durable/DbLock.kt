package dev.rubentxu.pipeline.v2.events.durable

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Cross-instance file-level lock for SQLite operation journal writes.
 *
 * Required because `synchronized(this)` is per-instance — two different
 * [SqliteOperationJournalImpl] instances writing to the same database file
 * would not serialize correctly (F13 HIGH finding from M3-R3 debt-report).
 *
 * Lock granularity: one lock per absolute DB path.
 *
 * ## Usage
 *
 * ```
 * DbLock.forPath(dbPath).withLock {
 *     // perform serialized write
 * }
 * ```
 *
 * ## Test cleanup
 *
 * For test isolation, [clearForTest] removes all locks. Call in `@After`
 * or `@AfterClass` to prevent lock leakage between test cases.
 */
object DbLock {
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    /**
     * Returns the lock for the given database path, creating it if absent.
     *
     * @param dbPath Absolute path to the SQLite database file.
     * @return A [ReentrantLock] for the given path.
     */
    fun forPath(dbPath: String): ReentrantLock =
        locks.computeIfAbsent(dbPath) { ReentrantLock() }

    /**
     * Clears all locks. For test cleanup only — do not call in production.
     */
    internal fun clearForTest() = locks.clear()
}
