package com.pipeline.v2.application

import com.pipeline.v2.application.durable.PipelineOrchestrator
import com.pipeline.v2.domain.durable.DivergenceDetector
import com.pipeline.v2.domain.durable.DivergenceException
import com.pipeline.v2.domain.durable.StrictFingerprintDivergenceDetector
import com.pipeline.v2.domain.durable.Clock
import com.pipeline.v2.events.SqliteEventStore
import com.pipeline.v2.events.durable.OperationJournal
import com.pipeline.v2.events.durable.SqliteOperationJournalImpl
import com.pipeline.v2.events.durable.SqliteReplayCursorStoreImpl
import com.pipeline.v2.events.durable.ReplayCursorStore
import com.pipeline.v2.sdk.runtime.durable.EffectReplayPolicy
import com.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import com.pipeline.v2.dsl.PipelineSpec
import com.pipeline.v2.dsl.StageSpec
import com.pipeline.v2.dsl.StepSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * UAT-DURABLE-005: Timeout persists across restart (C-024)
 *
 * Verifies:
 * - C-024.1: step with timeout=100ms completing in 50ms on first run
 *            journals deadline_ms = firstRunStart + 100ms
 * - C-024.2: worker resumed after deadline elapsed fails closed with
 *            DivergenceException citing expired deadline; NO new journal
 *            entries are written after the deadline-expired resume
 *
 * Uses TestClock (via MutableClock) to control wall-clock time injected
 * into the orchestrator, allowing deadline expiry to be simulated
 * without real time passing.
 *
 * Pattern: mirrors UatDurable001ReplaySurvivesRestartTest setup style
 * (in-memory SqliteEventStore + PipelineOrchestrator wired with all durable deps).
 */
class UatDurable005TimeoutPersistsAcrossRestartTest {

    @TempDir
    lateinit var tempDir: Path

    /**
     * C-024.1: timeout=100ms completing in 50ms journals correct deadline_ms
     *
     * Set up a step that takes ~50ms, configure timeout=100ms.
     * Run with TestClock at T0. After run, query the journal for deadline_ms.
     * Assert deadline_ms = T0 + 100ms.
     */
    @Test
    fun `step with timeout 100ms completing in 50ms journals deadline_ms correctly`() {
        val dbPath = tempDir.resolve("uat-005-deadline.db").toString()
        val T0 = java.time.Instant.parse("2025-03-01T12:00:00Z")

        // TestClock starts at T0
        val clock = MutableClock(T0)

        // Run pipeline: step takes ~50ms, timeout=100ms
        val spec = sleepSpec(seconds = 0.05, timeoutMs = 100)
        val runId = deriveRunId(spec)

        val outcome = runOrchestrated(dbPath, spec, runId, startFromCursor = false, clock = clock)
        assertEquals(
            "success",
            outcome,
            "Pipeline with 50ms step and 100ms timeout must succeed"
        )

        // Query journal for deadline_ms
        val deadlineMs = queryJournalDeadlineMs(dbPath, runId)
        val expectedDeadline = T0.toEpochMilli() + 100

        assertTrue(
            deadlineMs != null,
            "Journal must contain a deadline_ms entry for a step with timeout configured"
        )
        assertEquals(
            expectedDeadline,
            deadlineMs,
            "deadline_ms must be T0 + 100ms, got: $deadlineMs (T0=$T0)"
        )
    }

    /**
     * C-024.2: resume after deadline elapsed fails closed with DivergenceException
     *
     * First run: TestClock=T0, step takes ~50ms, timeout=100ms, deadline=T0+100ms.
     * After first run succeeds: advance clock to T0 + 250ms (past deadline).
     * Resume with same DB and clock: deadline check sees now > deadline.
     * Must throw DivergenceException (FAIL-CLOSED).
     * Additionally: verify NO new journal entry was written after deadline expiry.
     */
    @Test
    fun `resume after deadline elapsed fails closed with DivergenceException and no new journal entry`() {
        val dbPath = tempDir.resolve("uat-005-expired.db").toString()
        val T0 = java.time.Instant.parse("2025-03-01T12:00:00Z")

        val clock = MutableClock(T0)

        // First run: step takes 50ms, timeout=100ms
        val spec = sleepSpec(seconds = 0.05, timeoutMs = 100)
        val runId = deriveRunId(spec)

        // First run succeeds
        val firstOutcome = runOrchestrated(dbPath, spec, runId, startFromCursor = false, clock = clock)
        assertEquals(
            "success",
            firstOutcome,
            "First run with 50ms step and 100ms timeout must succeed"
        )

        // Count journal entries after first run
        val entriesBeforeResume = countJournalEntries(dbPath, runId)

        // Advance clock past deadline: T0 + 250ms
        clock.advanceTo(java.time.Instant.parse("2025-03-01T12:00:00.250Z"))

        // Resume: should throw DivergenceException due to deadline expiry
        val exceptionThrown = try {
            runOrchestrated(dbPath, spec, runId, startFromCursor = true, clock = clock)
            false
        } catch (ex: DivergenceException) {
            true
        } catch (ex: Exception) {
            // Could be wrapped in Result.failure
            val cause = ex.cause
            if (cause is DivergenceException) true else false
        }

        assertTrue(
            exceptionThrown,
            "Resume after deadline expiry must throw DivergenceException (FAIL-CLOSED)"
        )

        // Verify NO new journal entries were written after deadline expiry
        val entriesAfterResume = countJournalEntries(dbPath, runId)
        assertEquals(
            entriesBeforeResume,
            entriesAfterResume,
            "No new journal entries must be written after deadline-expired resume (FAIL-CLOSED)"
        )
    }

    /**
     * Creates a PipelineSpec with a Sleep step that takes `seconds` and has `timeoutMs`.
     */
    private fun sleepSpec(seconds: Double, timeoutMs: Long): PipelineSpec {
        return PipelineSpec(
            stages = listOf(
                StageSpec(
                    name = "TimeoutStage",
                    steps = listOf(
                        StepSpec.Sleep(
                            seconds = seconds.toLong(),
                            timeoutMillis = timeoutMs,
                        ),
                    ),
                )
            ),
        )
    }

    private fun runOrchestrated(
        dbPath: String,
        spec: PipelineSpec,
        runId: String,
        startFromCursor: Boolean,
        clock: MutableClock,
    ): String {
        val eventStore = SqliteEventStore(dbPath)
        val factory = eventStore.underlyingConnectionFactory()
        val journal: OperationJournal = SqliteOperationJournalImpl(factory)
        val cursorStore: ReplayCursorStore = SqliteReplayCursorStoreImpl(factory)
        val divergenceDetector: DivergenceDetector = StrictFingerprintDivergenceDetector()
        val effectPolicy: EffectReplayPolicy = DefaultEffectReplayPolicy()
        val orchestrator = PipelineOrchestrator(
            journal = journal,
            cursorStore = cursorStore,
            divergenceDetector = divergenceDetector,
            effectReplayPolicy = effectPolicy,
            eventSink = eventStore,
            clock = clock,
        )

        val result = orchestrator.run(spec, runId, startFromCursor)
        return result.getOrElse {
            fail<Nothing>("Orchestrator run failed: ${it.message}")
        }
    }

    private fun deriveRunId(spec: PipelineSpec): String {
        val input = "timeout-test-pipeline-v1"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }.take(36)
    }

    /**
     * Queries the journal for the deadline_ms associated with the step.
     */
    private fun queryJournalDeadlineMs(dbPath: String, runId: String): Long? {
        val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        return try {
            val opId = "$runId-s0-0"
            conn.prepareStatement(
                "SELECT deadline_ms FROM operation_journal WHERE op_id = ? ORDER BY attempt DESC LIMIT 1"
            ).use { ps ->
                ps.setString(1, opId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        val colIndex = rs.findColumn("deadline_ms")
                        if (rs.getObject(colIndex) != null) {
                            rs.getLong(colIndex)
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                }
            }
        } finally {
            conn.close()
        }
    }

    /**
     * Counts the number of journal entries for the step.
     */
    private fun countJournalEntries(dbPath: String, runId: String): Int {
        val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        return try {
            val opId = "$runId-s0-0"
            conn.prepareStatement(
                "SELECT COUNT(*) FROM operation_journal WHERE op_id = ?"
            ).use { ps ->
                ps.setString(1, opId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt(1) else 0
                }
            }
        } finally {
            conn.close()
        }
    }

    /**
     * MutableClock: a Clock implementation whose current instant can be
     * advanced programmatically. Used to simulate time passage for
     * deadline expiry testing without real time delays.
     *
     * NOT thread-safe. Not for production use.
     */
    class MutableClock(private var currentInstant: java.time.Instant) : Clock {
        override fun now(): java.time.Instant = currentInstant

        fun advanceTo(newInstant: java.time.Instant) {
            require(!newInstant.isBefore(currentInstant)) {
                "advanceTo must not move time backwards: was $currentInstant, attempted $newInstant"
            }
            currentInstant = newInstant
        }
    }
}
