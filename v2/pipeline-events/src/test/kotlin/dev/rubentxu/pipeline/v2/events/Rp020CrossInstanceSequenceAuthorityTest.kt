package dev.rubentxu.pipeline.v2.events

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * WU-RP-020 — the durable sequence authority must survive TWO WRITERS, not one.
 *
 * ## Why this test exists
 *
 * `Lpr041DurableSequenceRepairTest."concurrent appends produce gapless unique
 * sequences across instances"` appears to cover this, but it does not: both of
 * its threads share ONE `SqliteEventStore` instance (one in-memory `AtomicLong`).
 * It exercises intra-JVM concurrency, never the inter-process case.
 *
 * The inter-process case is the real one. `SqliteEventStore` seeds its per-run
 * counter ONCE in the constructor (`seedSequenceCounters()`) from
 * `MAX(sequence)`. Two stores over the same `--db` therefore start from the same
 * number, each increments independently, and both INSERTs succeed because the
 * `events` table has no `UNIQUE(run_id, sequence)`.
 *
 * That is the P1 `bl-bl-01M3QD197Q000387ET2D4MFKR0`, reproduced at
 * `rows=21, COUNT(DISTINCT sequence)=13, MAX(sequence)=13`.
 *
 * ## What this test pins
 *
 * With two independent store instances over the same database, the durable
 * (run_id, sequence) pair MUST stay unique. Either both stores agree on a
 * sequence, or the store that would duplicate it fails closed. Silence is not
 * an option: a duplicate is a corrupted monotonic invariant that replay and
 * every external observer depend on.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class Rp020CrossInstanceSequenceAuthorityTest {

    private fun tempDb(): Pair<Path, String> {
        val tmp = Files.createTempDirectory("rp020-authority-")
        return tmp.resolve("events.db") to tmp.toString()
    }

    private fun newStageStarted(runId: String, name: String): StageStarted =
        StageStarted(
            eventId = UUID.randomUUID().toString(),
            runId = runId,
            sequence = 0L,
            occurredAt = Instant.now(),
            stageIndex = 0,
            stageName = name,
        )

    private fun cleanup(parent: String) {
        java.io.File(parent).deleteRecursively()
    }

    /**
     * Counts rows and distinct sequences for a run directly from SQLite,
     * bypassing the read model: the invariant under test is a property of the
     * DURABLE state, so it must be read from the durable state.
     */
    private fun durableCounts(dbPath: Path, runId: String): Pair<Long, Long> {
        java.sql.DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*), COUNT(DISTINCT sequence) FROM events WHERE run_id = ?"
            ).use { ps ->
                ps.setString(1, runId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getLong(1) to rs.getLong(2)
                }
            }
        }
    }

    @Test
    fun `two store instances over one database never produce duplicate sequences`() {
        val (dbPath, parent) = tempDb()
        val runId = "rp020-" + UUID.randomUUID()

        // TWO INDEPENDENT INSTANCES over the same --db. This is the shape the
        // P1 reproduced across two processes; two instances in one JVM model
        // the same per-instance counter seeding without the cost of forking.
        val storeA = SqliteEventStore(dbPath.toString())
        val storeB = SqliteEventStore(dbPath.toString())

        val n = 150
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)

        val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())

        val t1 = Thread {
            ready.countDown(); go.await()
            runCatching { repeat(n) { i -> storeA.append(newStageStarted(runId, "a$i")) } }
                .onFailure { failures.add(it) }
        }
        val t2 = Thread {
            ready.countDown(); go.await()
            runCatching { repeat(n) { i -> storeB.append(newStageStarted(runId, "b$i")) } }
                .onFailure { failures.add(it) }
        }
        t1.start(); t2.start()
        ready.await(); go.countDown()
        t1.join(); t2.join()

        // After UNIQUE(run_id, sequence) the losing writer cannot corrupt the
        // log; its batch is rejected by SQLite. That surfaces as a failure at
        // flush/close time, which is the current (typed-later) behaviour: the
        // invariant is protected, the error is not yet a typed rejection.
        val closeFailure = runCatching { storeA.close(); storeB.close() }.exceptionOrNull()

        val (rows, distinct) = durableCounts(dbPath, runId)

        // THE invariant under test: the durable (run_id, sequence) pair is
        // unique. Read from SQLite, because a property of the durable state
        // must be observed in the durable state.
        check(rows == distinct) {
            "DUPLICATE DURABLE SEQUENCES: rows=$rows distinct=$distinct " +
                "(expected equal; ${rows - distinct} duplicated)"
        }

        // A rejected batch is a legitimate outcome of contention; losing
        // events are not silent corruption. Record whether the rejection
        // actually happened, so the test documents real behaviour instead of
        // asserting a story the code does not tell.
        check(rows in 1..2L * n) {
            "unexpected durable row count: rows=$rows"
        }
        if (closeFailure != null) {
            // Fail-closed is intact: the store refuses to pretend the run
            // succeeded. The message must name the constraint, not a raw driver
            // stack, so operators can act on it.
            val chain = generateSequence(closeFailure) { it.cause }.joinToString(" | ")
            check(chain.contains("UNIQUE")) {
                "expected a UNIQUE-constraint rejection, got: $chain"
            }
        }

        cleanup(parent)
    }
}
