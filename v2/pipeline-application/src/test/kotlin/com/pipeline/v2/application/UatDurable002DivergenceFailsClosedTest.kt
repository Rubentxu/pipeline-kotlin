package com.pipeline.v2.application

import com.pipeline.v2.application.durable.PipelineOrchestrator
import com.pipeline.v2.domain.durable.DivergenceDetector
import com.pipeline.v2.domain.durable.DivergenceException
import com.pipeline.v2.domain.durable.StrictFingerprintDivergenceDetector
import com.pipeline.v2.domain.durable.Clock
import com.pipeline.v2.events.SqliteEventStore
import com.pipeline.v2.events.durable.OperationJournal
import com.pipeline.v2.events.durable.SqliteOperationJournalImpl
import kotlinx.serialization.json.Json
import com.pipeline.v2.events.durable.SqliteReplayCursorStoreImpl
import com.pipeline.v2.events.durable.ReplayCursorStore
import com.pipeline.v2.sdk.runtime.durable.EffectReplayPolicy
import com.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import com.pipeline.v2.dsl.PipelineSpec
import com.pipeline.v2.dsl.StageSpec
import com.pipeline.v2.dsl.StepSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * UAT-DURABLE-002: Divergence fails closed
 *
 * Verifies that modifying the sh command between runs triggers DivergenceException
 * and the run aborts with StepFailed.
 *
 * Pattern: run spec once with sh("echo original"), journal the operation,
 * then run the same spec but with sh("echo mutated") — same runId, same opId,
 * but different fingerprint → DivergenceException → ABORT.
 *
 * @see <a href="design.md §4.6">Design §4.6 Step 15</a>
 */
class UatDurable002DivergenceFailsClosedTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `divergence is detected when sh command changes between runs`() {
        val dbPath = tempDir.resolve("uat-002.db").toString()
        val sharedRunId = deriveSharedRunId()

        // First run: sh with "echo original"
        runOrchestrated(dbPath, shSpec("echo original"), sharedRunId, startFromCursor = false)

        // Second run: same runId, SAME opId, but DIFFERENT command → divergent fingerprint
        val secondResult = runOrchestratedExpectingDivergence(
            dbPath,
            shSpec("echo mutated"),
            sharedRunId,
            startFromCursor = false,
        )

        assertTrue(secondResult.isFailure, "Second run must fail due to divergence")
        val exception = secondResult.exceptionOrNull()
        assertTrue(exception is DivergenceException) {
            "Expected DivergenceException but got: ${exception?.javaClass?.name}"
        }
        val divEx = exception as DivergenceException
        assertEquals(sharedRunId, divEx.runId, "DivergenceException must carry the runId")
    }

    @Test
    fun `divergence throws with both fingerprints in exception`() {
        val dbPath = tempDir.resolve("uat-002b.db").toString()
        val sharedRunId = deriveSharedRunId()

        // First run
        runOrchestrated(dbPath, shSpec("echo first"), sharedRunId, startFromCursor = false)

        // Second run: different command
        val secondResult = runOrchestratedExpectingDivergence(
            dbPath,
            shSpec("echo second"),
            sharedRunId,
            startFromCursor = false,
        )

        val divEx = secondResult.exceptionOrNull() as DivergenceException
        // Both expected and actual fingerprints must be present and different
        assertTrue(divEx.expected.hex.isNotEmpty(), "Expected fingerprint must be set")
        assertTrue(divEx.actual.hex.isNotEmpty(), "Actual fingerprint must be set")
        assertTrue(divEx.expected != divEx.actual) {
            "Fingerprints must differ when divergence is detected"
        }
    }

    private fun runOrchestrated(
        dbPath: String,
        spec: PipelineSpec,
        runId: String,
        startFromCursor: Boolean,
    ): String {
        val eventStore = SqliteEventStore(dbPath)
        val factory = eventStore.underlyingConnectionFactory()
        val clock: Clock = SystemClock()
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

        val result = orchestrator.run(spec, runId, startFromCursor)
        return result.getOrElse {
            throw it
        }
    }

    private fun runOrchestratedExpectingDivergence(
        dbPath: String,
        spec: PipelineSpec,
        runId: String,
        startFromCursor: Boolean,
    ): Result<String> {
        val eventStore = SqliteEventStore(dbPath)
        val factory = eventStore.underlyingConnectionFactory()
        val clock: Clock = SystemClock()
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

        return orchestrator.run(spec, runId, startFromCursor)
    }

    private fun deriveSharedRunId(): String {
        // Deterministic runId so that both runs share the same journal namespace.
        val input = "divergence-test-pipeline"
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
