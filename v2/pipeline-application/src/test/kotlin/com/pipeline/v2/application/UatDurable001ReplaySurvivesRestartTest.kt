package com.pipeline.v2.application

import com.pipeline.v2.application.durable.PipelineOrchestrator
import com.pipeline.v2.domain.durable.DivergenceDetector
import com.pipeline.v2.domain.durable.StrictFingerprintDivergenceDetector
import com.pipeline.v2.domain.durable.Clock
import com.pipeline.v2.events.EchoOutputCaptured
import com.pipeline.v2.events.EventSink
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

/**
 * UAT-DURABLE-001: Replay survives worker restart
 *
 * Verifies that killing the worker after echo (MEMOIZED + READ_ONLY) completes
 * and resuming does NOT re-execute the echo step.
 *
 * Pattern: use real SqliteEventStore + OperationJournal + PipelineOrchestrator.
 * First run: journal the echo step. Second run (fresh orchestrator, same DB):
 * the echo must be SKIPped (MEMOIZED + READ_ONLY + SUCCEEDED journal entry → SKIP).
 *
 * @see <a href="design.md §4.6">Design §4.6 Step 15</a>
 */
class UatDurable001ReplaySurvivesRestartTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `replay of echo step is skipped after restart`() {
        val dbPath = tempDir.resolve("uat-001.db").toString()

        // First run: execute pipeline with echo step
        val (run1Outcome, run1EventCount) = runOrchestrated(
            dbPath = dbPath,
            spec = echoSpec(),
            startFromCursor = false,
        )
        assertEquals("success", run1Outcome, "First run must succeed")
        val echoOutputRun1 = run1EventCount(EchoOutputCaptured::class.java)
        assertTrue(echoOutputRun1 > 0, "First run must emit EchoOutputCaptured events")

        // Simulate restart: create a NEW orchestrator pointing at the same DB.
        // The journal now contains the echo step entry.
        val (run2Outcome, run2EventCount) = runOrchestrated(
            dbPath = dbPath,
            spec = echoSpec(),
            startFromCursor = false,
        )

        // Second run must also succeed
        assertEquals("success", run2Outcome, "Second run must succeed (SKIP rather than re-execute)")

        // The key assertion: echo must NOT have been re-executed.
        // SKIP means bypass executor, so there should be no NEW EchoOutputCaptured
        // events on the second run (or at most the same count as run1 — no duplicates).
        val echoOutputRun2 = run2EventCount(EchoOutputCaptured::class.java)
        assertEquals(
            echoOutputRun1,
            echoOutputRun2,
            "EchoOutputCaptured count must not increase on second run — step should be SKIPped"
        )
    }

    private fun runOrchestrated(
        dbPath: String,
        spec: PipelineSpec,
        startFromCursor: Boolean,
    ): Pair<String, (Class<*>) -> Int> {
        val eventStore = SqliteEventStore(dbPath)
        val factory = eventStore.underlyingConnectionFactory()
        val clock: Clock = SystemClock()
        val journal: OperationJournal = SqliteOperationJournalImpl(factory, clock)
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

        val runId = deriveRunId(spec)
        val result = orchestrator.run(spec, runId, startFromCursor)
        val outcome = result.getOrElse {
            fail<Nothing>("Orchestrator run failed: ${it.message}")
        }

        val eventSink = eventStore as EventSink
        val allEvents = eventStore.eventsFor(runId).toList()

        val eventCounter: (Class<*>) -> Int = { cls ->
            allEvents.count { cls.isInstance(it) }
        }

        return outcome to eventCounter
    }

    private fun deriveRunId(spec: PipelineSpec): String {
        // Use a deterministic runId derived from the spec content.
        // Since we use echoSpec() twice, this gives the same runId for both runs.
        val input = "echo-test-pipeline-v1"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }.take(36)
    }

    private fun echoSpec(): PipelineSpec {
        return PipelineSpec(
            stages = listOf(
                StageSpec(
                    name = "EchoStage",
                    steps = listOf(
                        StepSpec.Echo(text = "hello durability"),
                    ),
                )
            ),
        )
    }
}
