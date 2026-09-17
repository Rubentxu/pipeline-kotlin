package dev.rubentxu.pipeline.v2.events.evidence

import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.SqliteEventStore
import dev.rubentxu.pipeline.v2.events.durable.SqliteOperationJournalImpl
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File

/**
 * XCA-2B.2b — reader against the REAL SqliteOperationJournalImpl from the XCA-2B run.
 *
 * This test reads the real SQLite journal produced by the installed CLI during XCA-2B:
 *   RunId  006df865-a1d4-4bcf-ac8c-895c0864ea60
 *   run.db /tmp/xca2b/run.db
 *   rows: core.echo (SUCCEEDED) + core.sh (SUCCEEDED)
 *
 * The run.db file is produced by the pipeline CLI (not a test), proving end-to-end that:
 *   real pipeline execution -> SQLite operation_journal -> reader -> evidence
 *
 * Using [SqliteEventStore] on an existing database is safe: the init block runs idempotent
 * schema migrations and the test only reads, never writes.
 *
 * ## What this does NOT prove
 * - B.2b does NOT exercise the reader against a fresh run (that is B.4/B.5 workstream B).
 * - It proves the reader works on a real database, not that every run produces journal rows.
 */
@Timeout(30)
class JournalRunExecutionEvidenceReaderRealDbTest {

    private val systemClock: Clock = object : Clock {
        override fun now() = java.time.Clock.systemUTC().instant()
    }

    /**
     * The real run.db from XCA-2B's installed-CLI execution.
     * Verified by XCA2B_FIRST_RUNTIME_VERTICAL.md: 2 rows (core.echo + core.sh, both SUCCEEDED).
     */
    private val realDbPath = "/tmp/xca2b/run.db"

    @Test
    fun `real SQLite journal from installed CLI produces Found result`() {
        val dbFile = File(realDbPath)
        if (!dbFile.exists()) {
            // Graceful skip — the test artifact may have been garbage-collected.
            // DO NOT hard-code this path in the reader; the reader is generic.
            org.junit.jupiter.api.Assumptions.assumeTrue(
                dbFile.exists(),
                "Real run.db not available at $realDbPath — run XCA-2B first to produce it",
            )
        }

        val eventStore = SqliteEventStore(realDbPath)
        val journal = SqliteOperationJournalImpl(
            eventStore.underlyingConnectionFactory(),
            systemClock,
            Json { ignoreUnknownKeys = true; encodeDefaults = true },
            eventStore.databasePath(),
        )

        val realRunId = RunId("006df865-a1d4-4bcf-ac8c-895c0864ea60")
        val reader = JournalRunExecutionEvidenceReader(journal)
        val result = reader.read(realRunId)

        assertInstanceOf(
            dev.rubentxu.pipeline.v2.domain.durable.RunEvidenceReadResult.Found::class.java,
            result,
            "reader must return Found for the real run (not RunNotFound)",
        )
    }

    @Test
    fun `real SQLite journal observed StepKeys are core-echo and core-sh`() {
        val dbFile = File(realDbPath)
        if (!dbFile.exists()) {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                dbFile.exists(),
                "Real run.db not available at $realDbPath",
            )
        }

        val eventStore = SqliteEventStore(realDbPath)
        val journal = SqliteOperationJournalImpl(
            eventStore.underlyingConnectionFactory(),
            systemClock,
            Json { ignoreUnknownKeys = true; encodeDefaults = true },
            eventStore.databasePath(),
        )

        val realRunId = RunId("006df865-a1d4-4bcf-ac8c-895c0864ea60")
        val reader = JournalRunExecutionEvidenceReader(journal)
        val result = reader.read(realRunId) as dev.rubentxu.pipeline.v2.domain.durable.RunEvidenceReadResult.Found

        val observed = result.invocations.filter { it.isObserved }

        assertEquals(
            setOf(
                dev.rubentxu.pipeline.v2.domain.PluginStepId("core.echo"),
                dev.rubentxu.pipeline.v2.domain.PluginStepId("core.sh"),
            ),
            observed.map { it.stepKey }.toSet(),
            "observed StepKeys must match the two Steps that XCA-2B executed",
        )
    }

    @Test
    fun `all real SQLite invocations are observed`() {
        val dbFile = File(realDbPath)
        if (!dbFile.exists()) {
            org.junit.jupiter.api.Assumptions.assumeTrue(dbFile.exists(), "Real run.db not available")
        }

        val eventStore = SqliteEventStore(realDbPath)
        val journal = SqliteOperationJournalImpl(
            eventStore.underlyingConnectionFactory(),
            systemClock,
            Json { ignoreUnknownKeys = true; encodeDefaults = true },
            eventStore.databasePath(),
        )

        val realRunId = RunId("006df865-a1d4-4bcf-ac8c-895c0864ea60")
        val reader = JournalRunExecutionEvidenceReader(journal)
        val result = reader.read(realRunId) as dev.rubentxu.pipeline.v2.domain.durable.RunEvidenceReadResult.Found

        assertTrue(
            result.invocations.all { it.isObserved },
            "all rows in the real run are non-PENDING (SUCCEEDED = observed)",
        )
    }
}
