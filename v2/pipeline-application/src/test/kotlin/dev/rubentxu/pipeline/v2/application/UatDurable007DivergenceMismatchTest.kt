package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.PipelineOrchestrator
import dev.rubentxu.pipeline.v2.domain.durable.DivergenceDetector
import dev.rubentxu.pipeline.v2.domain.durable.DivergenceException
import dev.rubentxu.pipeline.v2.domain.durable.StrictFingerprintDivergenceDetector
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.SqliteEventStore
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.events.durable.SqliteOperationJournalImpl
import kotlinx.serialization.json.Json
import dev.rubentxu.pipeline.v2.events.durable.SqliteReplayCursorStoreImpl
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.EffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.StageSpec
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Timeout
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

/**
 * UAT-DURABLE-007: Reconciliation divergence detection (C-027.2)
 *
 * Verifies:
 * - C-027.2: mismatch between journaled fingerprint and current fingerprint
 *            during reconciliation → DivergenceException is thrown.
 *
 * This test simulates a scenario where:
 * 1. First run starts with fingerprint A (command "echo original")
 * 2. Kill happens after beginOperation but before append
 * 3. Second run attempts with SAME runId but DIFFERENT command (fingerprint B)
 * 4. Reconciliation detects fingerprint mismatch → DivergenceException
 */
@Timeout(120)
class UatDurable007DivergenceMismatchTest {

    @TempDir
    lateinit var tempDir: Path

    /**
     * MutableClock: a Clock implementation whose current instant can be
     * advanced programmatically. Used to simulate time passage for
     * deadline expiry testing without real time delays.
     *
     * NOT thread-safe. Not for production use.
     */
@Timeout(120)
    class MutableClock(private var currentInstant: java.time.Instant) : Clock {
        override fun now(): java.time.Instant = currentInstant

        fun advanceTo(newInstant: java.time.Instant) {
            require(!newInstant.isBefore(currentInstant)) {
                "advanceTo must not move time backwards: was $currentInstant, attempted $newInstant"
            }
            currentInstant = newInstant
        }
    }

    @Test
    fun `C-027-2 reconciliation mismatch throws DivergenceException`() {
        val dbPath = tempDir.resolve("uat-007.db").toString()
        val sharedRunId = deriveSharedRunId()

        // First run: sh with "echo original"
        val clock = MutableClock(java.time.Instant.parse("2025-01-01T12:00:00Z"))
        runOrchestrated(dbPath, shSpec("echo original"), sharedRunId, startFromCursor = false, clock = clock)

        // Simulate kill: manually revert the journal entry to RUNNING state
        simulateKillAfterCompletion(dbPath, clock)

        // Second run: SAME runId, but DIFFERENT command → divergent fingerprint
        // This should trigger DivergenceException during reconciliation
        val secondResult = runOrchestratedExpectingDivergence(
            dbPath,
            shSpec("echo mutated"),
            sharedRunId,
            startFromCursor = true,
            clock = clock,
        )

        assertTrue(secondResult.isFailure, "Second run must fail due to divergence")
        val exception = secondResult.exceptionOrNull()
        assertTrue(exception is DivergenceException) {
            "Expected DivergenceException but got: ${exception?.javaClass?.name}"
        }
        val divEx = exception as DivergenceException
        assertEquals(sharedRunId, divEx.runId, "DivergenceException must carry the runId")
    }

    /**
     * Simulates a kill that happened AFTER the subprocess completed but BEFORE append ran.
     * Reverts the journal entry to RUNNING state with ended_at set to clock.now().
     */
    private fun simulateKillAfterCompletion(
        dbPath: String,
        clock: MutableClock,
    ) {
        val eventStore = SqliteEventStore(dbPath)
        val journal: OperationJournal = SqliteOperationJournalImpl(
            eventStore.underlyingConnectionFactory(),
            clock,
            Json { ignoreUnknownKeys = true; encodeDefaults = true },
            eventStore.databasePath(),
        )

        val runId = deriveSharedRunId()
        val opId = "$runId-s0-0"

        // Get the current entry
        val existingOp = journal.get(opId, 1)
        if (existingOp != null) {
            // Revert to RUNNING with ended_at = now (simulating completed subprocess)
            val nowMs = clock.now().toEpochMilli()
            val conn = eventStore.underlyingConnectionFactory()()
            try {
                conn.prepareStatement(
                    """
                    UPDATE operation_journal
                    SET status = 'RUNNING', ended_at = ?
                    WHERE op_id = ? AND attempt = 1
                    """.trimIndent()
                ).use { ps ->
                    ps.setLong(1, nowMs)
                    ps.setString(2, opId)
                    ps.executeUpdate()
                }
            } finally {
                conn.close()
            }
        }
    }

    private fun runOrchestrated(
        dbPath: String,
        spec: PipelineSpec,
        runId: String,
        startFromCursor: Boolean,
        clock: Clock,
    ): String {
        val eventStore = SqliteEventStore(dbPath)
        val factory = eventStore.underlyingConnectionFactory()
        val journal: OperationJournal = SqliteOperationJournalImpl(factory, clock, Json { ignoreUnknownKeys = true; encodeDefaults = true }, dbPath)
        val cursorStore: ReplayCursorStore = SqliteReplayCursorStoreImpl(factory, clock)
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

        val result = runBlocking { orchestrator.run(spec, runId, startFromCursor) }
        return result.getOrElse {
            throw it
        }
    }

    private fun runOrchestratedExpectingDivergence(
        dbPath: String,
        spec: PipelineSpec,
        runId: String,
        startFromCursor: Boolean,
        clock: Clock,
    ): Result<String> {
        val eventStore = SqliteEventStore(dbPath)
        val factory = eventStore.underlyingConnectionFactory()
        val journal: OperationJournal = SqliteOperationJournalImpl(factory, clock, Json { ignoreUnknownKeys = true; encodeDefaults = true }, dbPath)
        val cursorStore: ReplayCursorStore = SqliteReplayCursorStoreImpl(factory, clock)
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

        return runBlocking { orchestrator.run(spec, runId, startFromCursor) }
    }

    private fun deriveSharedRunId(): String {
        // Deterministic runId so that both runs share the same journal namespace.
        val input = "divergence-mismatch-test-pipeline"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }.take(36)
    }

    private fun shSpec(command: String): PipelineSpec {
        return PipelineSpec(
            stages = listOf(
                StageSpec(
                    name = "ShStage",
                    steps = listOf(
                        StepSpec.Shell(command = command),
                    ),
                )
            ),
        )
    }
}
