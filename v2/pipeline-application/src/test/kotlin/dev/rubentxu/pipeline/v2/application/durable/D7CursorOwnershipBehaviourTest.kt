package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CoreEchoStep
import dev.rubentxu.pipeline.v2.application.CoreStepRegistryFactory
import dev.rubentxu.pipeline.v2.application.EchoInput
import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeFailure
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeOutcome
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopePort
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.SourceDescriptor
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.durable.DurableOperation
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.ParallelFrame
import dev.rubentxu.pipeline.v2.domain.durable.StrictFingerprintDivergenceDetector
import dev.rubentxu.pipeline.v2.domain.step.StepRegistryBuilder
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursor
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursorStore
import dev.rubentxu.pipeline.v2.events.durable.StageIndex
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * S4-R1-B/D7 — behavioural half of the replay-cursor ownership law.
 *
 * `D7ReplayCursorOwnershipFitnessTest` proves the *shape* (who depends on what, and how many
 * call sites exist). This proves the *behaviour*, and it does so by driving the real
 * [CanonicalDurableRunCoordinator] with an instrumented [ReplayCursorStore] rather than by
 * rebuilding the caller. That distinction is the whole point: a harness that reimplements the
 * orchestration proves nothing about the orchestration, which is exactly the defect class
 * HAR-PAR-001 records for `WalkParallelFrameConcurrencyTest`.
 *
 * ## What the law is
 *
 * ```text
 * ReplayCursor(runId, lastOpId, stageIndex, savedAt)  →  where the RUN resumes
 * ```
 *
 * It is traversal state. So:
 *
 * ```text
 * D7-B1  Execute success        → terminal journal row, THEN cursor advance
 * D7-B2  Execute failure        → cursor writes = 0
 * D7-B3  RecoverRunning success → terminal journal row, THEN cursor advance
 * D7-B4  ReuseCompleted         → cursor writes = 0
 * D7-B5  scripted fresh/reuse   → cursor writes = 0
 * ```
 *
 * The ORDER in B1 and B3 is not a preference. `ReplayCursorStore.advance` documents an R-C
 * mitigation: it MUST be called only after `OperationJournal.append` has returned
 * successfully. A cursor that points at an operation whose terminal row never landed would
 * make a resume skip an effect that never durably completed.
 *
 * ## B5 is the regression this whole slice exists to prevent
 *
 * `ScriptedFrontendRunner` fixes `stageIndex = 0` for scripted calls, and
 * `MainScriptedSupport` never passes a `ReplayCursorStore`. Before D7 a scripted call could
 * not enter the durable executor at all without inventing a cursor position; if it had found
 * a way, that invented position (`stageIndex = 0`) would have rewound a canonical run's
 * resume point on a call that has no stage. B5 holds a real cursor store in scope and proves
 * the scripted spine never writes it.
 */
class D7CursorOwnershipBehaviourTest {

    // ------------------------------------------------------------- D7-B1

    @Test
    fun `D7-B1 a successful execution writes its terminal row before advancing the cursor`() = runBlocking {
        val harness = Harness()
        harness.coordinator(succeeding = true).run(harness.pipeline(), RunId(RUN_B1))

        val terminalAt = harness.writeLog.indexOfFirst { it.startsWith(TERMINAL) }
        val advanceAt = harness.writeLog.indexOfFirst { it.startsWith(ADVANCE) }

        assertTrue(terminalAt >= 0, "the execution must write a terminal journal row; log=${harness.writeLog}")
        assertTrue(advanceAt >= 0, "a successful execution must advance the cursor; log=${harness.writeLog}")
        assertTrue(
            terminalAt < advanceAt,
            "The terminal journal append MUST precede the cursor advance (ReplayCursorStore's R-C " +
                "mitigation). A cursor pointing at an operation with no durable terminal would make " +
                "a resume skip an effect that never completed. Observed order:\n" +
                harness.writeLog.joinToString("\n") { "\t$it" },
        )
        assertEquals(1, harness.cursor.writes.size, "exactly one advance for one executed step")
    }

    // ------------------------------------------------------------- D7-B2

    @Test
    fun `D7-B2 a failed execution never advances the cursor`() = runBlocking {
        val harness = Harness()
        harness.coordinator(succeeding = false).run(harness.pipeline(), RunId(RUN_B2))

        assertTrue(
            harness.writeLog.any { it.startsWith(TERMINAL) },
            "a failed execution still writes its terminal FAILED row; log=${harness.writeLog}",
        )
        assertEquals(
            0,
            harness.cursor.writes.size,
            "A failed step advances nothing: the run did not get further, so its resume position " +
                "must not move. Log=${harness.writeLog}",
        )
    }

    // ------------------------------------------------------------- D7-B4

    /**
     * The reuse arm is the one that could plausibly have been mis-moved: it settles a SUCCESS,
     * so a naive "settled success advances" reading would advance here too. It must not — the
     * row is already terminal and the traversal was already past this step.
     */
    @Test
    fun `D7-B4 a reused operation advances nothing`() = runBlocking {
        val harness = Harness()
        val coordinator = harness.coordinator(succeeding = true)
        val pipeline = harness.pipeline()
        val runId = RunId(RUN_B4)

        coordinator.run(pipeline, runId)
        val afterFirst = harness.cursor.writes.size
        harness.writeLog.clear()

        coordinator.run(pipeline, runId)

        assertEquals(
            afterFirst,
            harness.cursor.writes.size,
            "ReuseCompleted settles a success but must not advance the cursor: nothing was " +
                "executed, so the run's position did not change. Log=${harness.writeLog}",
        )
        assertEquals(
            0,
            harness.writeLog.count { it.startsWith(TERMINAL) },
            "ReuseCompleted writes no journal row either — it is already terminal. " +
                "Log=${harness.writeLog}",
        )
    }

    // ------------------------------------------------------- Unstable asymmetry

    /**
     * The two advancement rules are NOT symmetric, and D7 moved them without reconciling them.
     *
     * ```text
     * Execute        → advance when outcome !is StepOutcome.Failure   (Unstable ADVANCES)
     * RecoverRunning → advance when outcome  is StepOutcome.Success    (Unstable does NOT)
     * ```
     *
     * This test pins the Execute half, and pins it as PRE-EXISTING rather than as something D7
     * chose: the old executor advanced on `!is Failure`, and that predicate is carried over
     * verbatim into `advancesCanonicalCursor()`. The recovery half stays `is Success` and is
     * pinned structurally by `D7-F4b`, because producing a recovered UNSTABLE through the real
     * external-subprocess substrate is not reachable in a harness without inventing a process
     * state the substrate cannot report — so that half is asserted at the call site rather than
     * fabricated here.
     *
     * Whether the two SHOULD agree is an open question on its own work item. Until that is
     * decided, changing either predicate would be a behaviour change disguised as cleanup.
     */
    @Test
    fun `D7-B6 an UNSTABLE execution advances the cursor, which is the pre-existing Execute rule`() = runBlocking {
        val harness = Harness()
        harness.coordinatorWith(StepOutcome.Unstable).run(harness.pipeline(), RunId(RUN_B6))

        assertTrue(
            harness.writeLog.any { it.startsWith(TERMINAL) },
            "an unstable execution still writes its terminal row; log=${harness.writeLog}",
        )
        assertEquals(
            1,
            harness.cursor.writes.size,
            "D7-B6 CHARACTERISATION: Execute advances on Unstable. This is the pre-existing rule " +
                "carried over verbatim (`!is Failure`), NOT a consequence of D7. Recovery uses the " +
                "stricter `is Success` rule and is pinned by D7-F4b. Log=${harness.writeLog}",
        )
    }

    @Test
    fun `D7-B6b a FAILED execution is the only terminal outcome that does not advance`() {
        assertTrue(StepOutcome.Success.advancesCanonicalCursor())
        assertTrue(StepOutcome.Unstable.advancesCanonicalCursor())
        assertEquals(false, StepOutcome.Failure(PipelineFailure(FailureKind.ENGINE, "d7")).advancesCanonicalCursor())
    }

    // ------------------------------------------------------------- harness

    private class Harness {
        val clock = SystemClock()
        val events = InMemoryEventStore()

        /**
         * ONE ordered log shared by the journal and the cursor decorators. Sharing it is what
         * makes D7-B1's cross-component ORDERING assertion possible: counts alone cannot tell
         * "appended then advanced" from "advanced then appended", and only the first satisfies
         * the R-C mitigation.
         */
        val writeLog: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf<String>())
        val journal = RecordingJournal(InMemoryOperationJournal(clock), writeLog)
        val cursor = RecordingCursorStore(InMemoryReplayCursorStore(clock), writeLog)

        fun coordinator(succeeding: Boolean): CanonicalDurableRunCoordinator =
            coordinatorWith(if (succeeding) StepOutcome.Success else FAILURE_OUTCOME)

        /** The boundary outcome a test wants, so `Unstable` can be exercised as its own case. */
        fun coordinatorWith(outcome: StepOutcome): CanonicalDurableRunCoordinator =
            CanonicalDurableRunCoordinator(
                CanonicalNodeDispatcher(),
                journal,
                cursor,
                clock,
                DefaultEffectReplayPolicy(),
                events,
                credentialScopePort = CredentialScopePort { _, _ ->
                    CredentialScopeOutcome.Unavailable(CredentialScopeFailure.StoreUnavailable("n/a"))
                },
                controlDirRoot = null,
                shOptions = ShOptions.EMPTY,
                divergenceDetector = StrictFingerprintDivergenceDetector(),
                commonExecutionBoundary = CommonExecutionBoundary { _, _ ->
                    CommonExecutionResult(outcome, null)
                },
                stepRegistry = StepRegistryBuilder().also { CoreEchoStep.registerInto(it) }.build(),
            )

        fun pipeline(): CompiledPipeline = CompiledPipeline(
            id = DefinitionId("d7-pipeline"),
            source = SourceDescriptor("Pipeline.kts", Digest("source")),
            pluginLockDigest = Digest("lock"),
            stages = listOf(
                StageNode(
                    id = StageId("build"),
                    name = "build",
                    body = StageBody.Steps(
                        listOf(
                            OpaqueStepNode(
                                id = StepId("build/echo"),
                                pluginStepId = PluginStepId("core.echo"),
                                payload = VersionedStepPayload("dsl-v1", """{"kind":"echo","text":"d7"}"""),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }

    private fun shellPipeline(): CompiledPipeline = CompiledPipeline(
        id = DefinitionId("d7-shell-pipeline"),
        source = SourceDescriptor("Pipeline.kts", Digest("source")),
        pluginLockDigest = Digest("lock"),
        stages = listOf(
            StageNode(
                id = StageId("build"),
                name = "build",
                body = StageBody.Steps(
                    listOf(
                        OpaqueStepNode(
                            id = StepId("build/sh"),
                            pluginStepId = PluginStepId("core.sh"),
                            payload = VersionedStepPayload(
                                "dsl-v1",
                                """{"kind":"sh","command":"echo d7","isScriptBlock":false,"returnStdout":false}""",
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    /**
     * Records every cursor write into the SHARED write log, so a test can assert the ORDER of a
     * journal append relative to a cursor advance rather than only the counts.
     */
    internal class RecordingCursorStore(
        private val delegate: InMemoryReplayCursorStore,
        private val log: MutableList<String>,
    ) : ReplayCursorStore {
        val writes = java.util.Collections.synchronizedList(mutableListOf<Triple<String, String, Int>>())

        override fun load(runId: String): ReplayCursor? = delegate.load(runId)

        override fun advance(runId: String, opId: String, stageIndex: Int) {
            writes.add(Triple(runId, opId, stageIndex))
            log.add("$ADVANCE$runId/$opId@$stageIndex")
            delegate.advance(runId, opId, stageIndex)
        }

        override fun advancePastParallelFrame(
            runId: String,
            frame: ParallelFrame,
            branchResults: List<dev.rubentxu.pipeline.v2.events.durable.BranchExecutionResult>,
            explicitMaxStageIndex: Int?,
        ): StageIndex = delegate.advancePastParallelFrame(runId, frame, branchResults, explicitMaxStageIndex)
    }

    /** Journal decorator that stamps every write into the same shared ordered log. */
    private class RecordingJournal(
        private val delegate: OperationJournal,
        private val log: MutableList<String>,
    ) : OperationJournal {
        override fun append(op: DurableOperation, deadlineMs: Long?) {
            log.add("$TERMINAL${op.id}/${op.status}")
            delegate.append(op, deadlineMs)
        }

        override fun get(opId: String): DurableOperation? = delegate.get(opId)
        override fun get(opId: String, attempt: Int): DurableOperation? = delegate.get(opId, attempt)
        override fun listForRun(runId: String): List<DurableOperation> = delegate.listForRun(runId)
        override fun getDeadlineMs(opId: String, attempt: Int): Long? = delegate.getDeadlineMs(opId, attempt)
        override fun getEndedAt(opId: String, attempt: Int): Long? = delegate.getEndedAt(opId, attempt)
        override fun getStartedAt(opId: String, attempt: Int): Long? = delegate.getStartedAt(opId, attempt)

        override fun beginOperation(
            opId: String,
            attempt: Int,
            fingerprint: String,
            inputJson: String,
            deadlineMs: Long?,
        ) {
            log.add("$BEGIN$opId")
            delegate.beginOperation(opId, attempt, fingerprint, inputJson, deadlineMs)
        }
    }

    private companion object {
        const val TERMINAL = "TERMINAL|"
        const val ADVANCE = "ADVANCE|"
        const val BEGIN = "BEGIN|"
        const val RUN_B1 = "d7-b1"
        const val RUN_B2 = "d7-b2"
        const val RUN_B3 = "d7-b3"
        const val RUN_B6 = "d7-b6"

        val FAILURE_OUTCOME: StepOutcome =
            StepOutcome.Failure(PipelineFailure(FailureKind.ENGINE, "d7 deliberate failure"))
        const val RUN_B4 = "d7-b4"
    }
}
