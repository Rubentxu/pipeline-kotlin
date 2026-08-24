package com.pipeline.v2.application

import com.pipeline.v2.application.durable.PipelineOrchestrator
import com.pipeline.v2.domain.durable.DivergenceDetector
import com.pipeline.v2.domain.durable.DivergenceException
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
 * UAT-DURABLE-003: Script block replay (C-022)
 *
 * Verifies:
 * - C-022.1: script {} block survives restart via SKIP (script doesn't re-execute)
 * - C-022.2: script body mutation → DivergenceException (FAIL-CLOSED)
 *
 * Uses StepSpec.Shell with isScriptBlock=true to represent script {} DSL.
 */
class UatDurable003ScriptBlockReplayTest {

    @TempDir
    lateinit var tempDir: Path

    /**
     * C-022.1: script {} block survives restart via SKIP
     *
     * First run: script executes and is journaled.
     * Restart: same script body → fingerprint matches → MEMOIZED + SUCCEEDED → SKIP.
     * Script does NOT re-execute on second run.
     */
    @Test
    fun `script block survives restart via SKIP`() {
        val dbPath = tempDir.resolve("uat-003-skip.db").toString()
        val scriptBody = "echo 'hello from script'"

        // First run: execute script block
        val (run1Outcome, run1EventCounter) = runOrchestrated(
            dbPath = dbPath,
            spec = scriptSpec(scriptBody),
            startFromCursor = false,
        )
        assertEquals("success", run1Outcome, "First run must succeed")
        val echoOutputRun1 = run1EventCounter(EchoOutputCaptured::class.java)
        assertTrue(echoOutputRun1 > 0, "First run must emit EchoOutputCaptured events")

        // Simulate restart: new orchestrator pointing at same DB
        // Script body unchanged → fingerprint matches → SKIP
        val (run2Outcome, run2EventCounter) = runOrchestrated(
            dbPath = dbPath,
            spec = scriptSpec(scriptBody),
            startFromCursor = false,
        )
        assertEquals("success", run2Outcome, "Second run must succeed (SKIP rather than re-execute)")

        // Script must NOT have re-executed
        val echoOutputRun2 = run2EventCounter(EchoOutputCaptured::class.java)
        assertEquals(
            echoOutputRun1,
            echoOutputRun2,
            "EchoOutputCaptured count must not increase on second run — script should be SKIPped"
        )
    }

    /**
     * C-022.2: script body mutation → DivergenceException (FAIL-CLOSED per RECOVERY_DURABILITY §5)
     *
     * First run: script executes and is journaled.
     * Restart with MUTATED script body: fingerprint differs → FAIL-CLOSED → DivergenceException.
     */
    @Test
    fun `script body mutation triggers DivergenceException`() {
        val dbPath = tempDir.resolve("uat-003-divergence.db").toString()
        val originalScript = "echo 'original script'"
        val mutatedScript = "echo 'MUTATED script'"

        // First run: execute original script
        val (run1Outcome, _) = runOrchestrated(
            dbPath = dbPath,
            spec = scriptSpec(originalScript),
            startFromCursor = false,
        )
        assertEquals("success", run1Outcome, "First run must succeed")

        // Restart with MUTATED script body
        val exceptionThrown = try {
            runOrchestratedExpectingDivergence(
                dbPath = dbPath,
                spec = scriptSpec(mutatedScript),
                startFromCursor = false,
            )
            false
        } catch (ex: DivergenceException) {
            true
        } catch (ex: Exception) {
            // DivergenceException wraps in Result.failure
            val cause = ex.cause
            if (cause is DivergenceException) true else false
        }

        assertTrue(exceptionThrown, "Mutation must trigger DivergenceException (FAIL-CLOSED)")
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

        val runId = deriveSharedRunId()
        val result = orchestrator.run(spec, runId, startFromCursor)
        val outcome = result.getOrElse {
            fail<Nothing>("Orchestrator run failed: ${it.message}")
        }

        val allEvents = eventStore.eventsFor(runId).toList()
        val eventCounter: (Class<*>) -> Int = { cls ->
            allEvents.count { cls.isInstance(it) }
        }

        return outcome to eventCounter
    }

    private fun runOrchestratedExpectingDivergence(
        dbPath: String,
        spec: PipelineSpec,
        startFromCursor: Boolean,
    ) {
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

        val runId = deriveSharedRunId()
        // Unwrap Result; DivergenceException is wrapped in Result.failure by orchestrator
        orchestrator.run(spec, runId, startFromCursor).getOrElse { throw it }
    }

    private fun deriveSharedRunId(): String {
        val input = "script-block-test-pipeline"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }.take(36)
    }

    /**
     * Creates a PipelineSpec with a script {} block.
     * Uses StepSpec.Shell with isScriptBlock=true to represent script {} DSL.
     */
    private fun scriptSpec(scriptBody: String): PipelineSpec {
        return PipelineSpec(
            stages = listOf(
                StageSpec(
                    name = "ScriptStage",
                    steps = listOf(
                        // isScriptBlock=true represents script {} DSL; command is the script body
                        StepSpec.Shell(command = scriptBody, isScriptBlock = true),
                    ),
                )
            ),
        )
    }
}
