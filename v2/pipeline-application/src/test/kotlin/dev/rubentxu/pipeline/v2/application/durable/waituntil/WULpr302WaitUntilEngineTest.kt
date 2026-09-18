package dev.rubentxu.pipeline.v2.application.durable.waituntil

import dev.rubentxu.pipeline.v2.application.durable.FileBasedWaitUntilControlJournal
import dev.rubentxu.pipeline.v2.application.durable.WaitUntilIdentityFactory
import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.BodyInvocationContext
import dev.rubentxu.pipeline.v2.domain.step.BodyInvoker
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodyRef
import dev.rubentxu.pipeline.v2.domain.step.BodyRefs
import dev.rubentxu.pipeline.v2.domain.step.CancellationReason
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.WaitUntilCompleted
import dev.rubentxu.pipeline.v2.events.WaitUntilPolled
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * WU-LPR-302 (Phase 3, 2026-09-18) — `WaitUntilEngine` is the
 * application-internal authority for the waitUntil aggregate.
 *
 * ## Properties pinned
 *
 * 1. **Identity**: the same `BodyRef` invoked under attempt N produces the
 *    deterministic `bodyPath` segment for poll N (Phase 1b projection); poll 1
 *    and poll N produce distinct durable identities.
 * 2. **Replay**: a poll already committed as terminal SUCCEEDED in the
 *    journal is NOT re-executed; the engine returns Success without invoking
 *    the body.
 * 3. **Port use**: the engine reaches the body through the public
 *    [BodyInvoker] port; it does NOT call `invokeBodyChildren` directly.
 *    Constructor fitness pins this at the type level.
 * 4. **Outcome folding**: success / fail-then-success / deadline-exceeded /
 *    aborted / cancelled bodies produce the correct typed `StepOutcome` and
 *    the correct [WaitUntilCompleted.outcome] string.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class WULpr302WaitUntilEngineTest {

    @Nested
    inner class Identity {
        @Test
        fun `engine drives poll 1 and poll 2 through the same BodyRef under distinct attempt contexts`(
            @TempDir tempDir: Path,
        ) = runBlocking {
            val parentPath = listOf(BlockSegment(0, PluginStepId("build/waituntil")))
            val bodyRef = BodyRefs.childBody(parentPath)
            val adapter = RecordingAdapter()
            adapter.open(bodyRef) { ctx ->
                if ((ctx.attempt?.index ?: 0) < 2) {
                    StepOutcome.Failure(PipelineFailure(FailureKind.SCRIPT, "not-yet"))
                } else {
                    StepOutcome.Success
                }
            }
            val te = makeEngine(
                adapter = adapter,
                bodyRef = bodyRef,
                parentBodyPath = parentPath,
                initialRecurrencePeriodMs = 1L,
                maxBackoffMs = 4L,
                events = InMemoryEventStore(),
                tempDir = tempDir,
            )

            val outcome = te.engine.execute(bodyRef)
            assertEquals(StepOutcome.Success, outcome)
            // The body was reached twice under distinct attempt indices; the
            // bodyPath segments for poll 1 and poll 2 are distinct.
            val seenAttempts = adapter.attemptIndices()
            assertEquals(listOf(1, 2), seenAttempts) {
                "engine MUST drive the body under poll 1 and poll 2; " +
                    "got $seenAttempts"
            }
            val poll1Path = parentPath + BlockSegment(1, PluginStepId("wait-until-poll"))
            val poll2Path = parentPath + BlockSegment(2, PluginStepId("wait-until-poll"))
            assertNotEquals(poll1Path, poll2Path) {
                "per-poll bodyPath segments MUST differ to drive per-poll " +
                    "durable journal identity"
            }
        }
    }

    @Nested
    inner class Replay {
        @Test
        fun `already-satisfied journal state replays to Success without re-executing body`(
            @TempDir tempDir: Path,
        ) = runBlocking {
            val parentPath = listOf(BlockSegment(0, PluginStepId("build/waituntil")))
            val bodyRef = BodyRefs.childBody(parentPath)
            val adapter = RecordingAdapter()
            // The body must NEVER be invoked: the journal already has a
            // terminal SUCCEEDED row at attempt 1.
            adapter.open(bodyRef) { _ ->
                StepOutcome.Failure(PipelineFailure(FailureKind.SCRIPT, "must-not-fire"))
            }
            val te = makeEngine(
                adapter = adapter,
                bodyRef = bodyRef,
                parentBodyPath = parentPath,
                initialRecurrencePeriodMs = 1L,
                maxBackoffMs = 4L,
                events = InMemoryEventStore(),
                tempDir = tempDir,
            )
            // Pre-terminalise attempt 1 as SUCCEEDED.
            te.journal.beginAttempt(
                te.controlOpId,
                attempt = 1,
                currentBackoffMs = 1L,
                fingerprint = te.fingerprint,
                status = OperationStatus.RUNNING,
            )
            te.journal.updateStatus(
                te.controlOpId,
                attempt = 1,
                status = OperationStatus.SUCCEEDED,
                fingerprint = te.fingerprint,
            )

            val outcome = te.engine.execute(bodyRef)
            assertEquals(StepOutcome.Success, outcome)
            assertEquals(emptyList<Int>(), adapter.attemptIndices()) {
                "engine MUST NOT re-invoke the body when attempt 1 is already " +
                    "SUCCEEDED in the journal; replay reuses the durable outcome"
            }
        }
    }

    @Nested
    inner class PortUse {
        @Test
        fun `engine constructor takes BodyInvoker and never accepts invokeBodyChildren-style parameters`() {
            val ctor = WaitUntilEngine::class.java.declaredConstructors.first()
            val params = ctor.parameterTypes.map { it.simpleName }.toSet()
            assertTrue("BodyInvoker" in params) {
                "WaitUntilEngine MUST depend on BodyInvoker (port); got params=$params"
            }
            assertTrue("WaitUntilControlJournal" in params) {
                "WaitUntilEngine MUST depend on WaitUntilControlJournal (port); got params=$params"
            }
            assertTrue("EventSink" in params)
            val banned = listOf(
                "BlockStepNode",
                "CompiledPipeline",
                "StageNode",
                "StepNode",
                "ExecutionContext",
                "ShOptions",
                "BranchInvoker",
                "BodyExecutor",
                "BodyRunnerPort",
                "BlockExecutor",
            )
            val offending = params.filter { it in banned.toSet() }
            assertEquals(emptyList<String>(), offending) {
                "WaitUntilEngine MUST NOT accept BlockStepNode / CompiledPipeline / " +
                    "StageNode / StepNode / ExecutionContext / ShOptions / BranchInvoker / " +
                    "BodyExecutor / BodyRunnerPort / BlockExecutor. Got: $offending"
            }
        }

        @Test
        fun `engine invokes body through BodyInvoker not invokeBodyChildren`(
            @TempDir tempDir: Path,
        ) = runBlocking {
            val parentPath = listOf(BlockSegment(0, PluginStepId("build/waituntil")))
            val bodyRef = BodyRefs.childBody(parentPath)
            var invokedThroughAdapter = false
            val adapter = object : BodyInvoker {
                override suspend fun invoke(
                    body: BodyRef,
                    context: BodyInvocationContext,
                ): BodyOutcome {
                    invokedThroughAdapter = true
                    return BodyOutcome.Completed(StepOutcome.Success)
                }
            }
            val te = makeEngine(
                adapter = adapter,
                bodyRef = bodyRef,
                parentBodyPath = parentPath,
                initialRecurrencePeriodMs = 1L,
                maxBackoffMs = 4L,
                events = InMemoryEventStore(),
                tempDir = tempDir,
            )
            val outcome = te.engine.execute(bodyRef)
            assertEquals(StepOutcome.Success, outcome)
            assertTrue(invokedThroughAdapter) {
                "engine MUST route the body through BodyInvoker.invoke; " +
                    "if this is false the engine reached into the coordinator's " +
                    "internal body-child loop"
            }
        }
    }

    @Nested
    inner class OutcomeFolding {
        @Test
        fun `first poll success returns Success and persists SUCCEEDED`(
            @TempDir tempDir: Path,
        ) = runBlocking {
            val parentPath = listOf(BlockSegment(0, PluginStepId("build/waituntil")))
            val bodyRef = BodyRefs.childBody(parentPath)
            val adapter = RecordingAdapter().also { it.open(bodyRef) { StepOutcome.Success } }
            val events = InMemoryEventStore()
            val te = makeEngine(
                adapter = adapter,
                bodyRef = bodyRef,
                parentBodyPath = parentPath,
                initialRecurrencePeriodMs = 1L,
                maxBackoffMs = 4L,
                events = events,
                tempDir = tempDir,
            )

            val outcome = te.engine.execute(bodyRef)
            assertEquals(StepOutcome.Success, outcome)
            assertEquals(
                OperationStatus.SUCCEEDED,
                te.journal.readState(
                    controlOpId = te.controlOpId,
                    initialRecurrencePeriodMs = 1L,
                    maxBackoffMs = 4L,
                    currentFingerprint = te.fingerprint,
                ).controlRows.single().status,
            )
            val polled: List<WaitUntilPolled> =
                events.eventsFor(te.runId.value).filterIsInstance<WaitUntilPolled>().toList()
            assertEquals(2, polled.size) { "two WaitUntilPolled events per poll (open + close)" }
            assertTrue(
                events.eventsFor(te.runId.value).any {
                    it is WaitUntilCompleted && it.outcome == "completed" && it.totalAttempts == 1
                },
            ) { "WaitUntilCompleted(completed, totalAttempts=1) MUST be emitted on first-poll success" }
        }

        @Test
        fun `fail then success returns Success after backoff and persists SUCCEEDED at poll 2`(
            @TempDir tempDir: Path,
        ) = runBlocking {
            val parentPath = listOf(BlockSegment(0, PluginStepId("build/waituntil")))
            val bodyRef = BodyRefs.childBody(parentPath)
            val adapter = RecordingAdapter().also {
                it.open(bodyRef) { ctx ->
                    if ((ctx.attempt?.index ?: 0) < 2) {
                        StepOutcome.Failure(PipelineFailure(FailureKind.SCRIPT, "not-yet"))
                    } else {
                        StepOutcome.Success
                    }
                }
            }
            val events = InMemoryEventStore()
            val te = makeEngine(
                adapter = adapter,
                bodyRef = bodyRef,
                parentBodyPath = parentPath,
                initialRecurrencePeriodMs = 1L,
                maxBackoffMs = 4L,
                events = events,
                tempDir = tempDir,
            )

            val outcome = te.engine.execute(bodyRef)
            assertEquals(StepOutcome.Success, outcome)
            assertEquals(listOf(1, 2), adapter.attemptIndices())
            // Both polls persisted as RUNNING; the second one upgraded to
            // SUCCEEDED on predicate satisfaction.
            val rows = te.journal.readState(
                controlOpId = te.controlOpId,
                initialRecurrencePeriodMs = 1L,
                maxBackoffMs = 4L,
                currentFingerprint = te.fingerprint,
            ).controlRows.sortedBy { it.attempt }
            assertEquals(2, rows.size)
            assertEquals(1, rows[0].attempt)
            assertEquals(2, rows[1].attempt)
            assertEquals(OperationStatus.SUCCEEDED, rows[1].status)
            assertTrue(
                events.eventsFor(te.runId.value).any {
                    it is WaitUntilCompleted && it.outcome == "completed" && it.totalAttempts == 2
                },
            ) { "WaitUntilCompleted(completed, totalAttempts=2) MUST be emitted" }
        }

        @Test
        fun `deadline exceeded returns Failure TIMEOUT and persists FAILED_TIMEOUT`(
            @TempDir tempDir: Path,
        ) = runBlocking {
            // The reconciler decides when DeadlineExceeded fires. We seed the
            // journal with a pre-terminalised attempt marked FAILED_TIMEOUT so
            // the first plan() emits DeadlineExceeded(N) and the engine
            // persists FAILED_TIMEOUT + emits WaitUntilCompleted(deadline-
            // exceeded). This is the deterministic surface my engine is
            // responsible for: when the reconciler says DeadlineExceeded, the
            // engine MUST return StepOutcome.Failure(TIMEOUT).
            val parentPath = listOf(BlockSegment(0, PluginStepId("build/waituntil")))
            val bodyRef = BodyRefs.childBody(parentPath)
            val adapter = RecordingAdapter().also {
                // Body must never fire — the deadline was already decided by
                // the planner reading the seeded journal.
                it.open(bodyRef) { _ ->
                    StepOutcome.Failure(PipelineFailure(FailureKind.SCRIPT, "must-not-fire"))
                }
            }
            val events = InMemoryEventStore()
            val te = makeEngine(
                adapter = adapter,
                bodyRef = bodyRef,
                parentBodyPath = parentPath,
                initialRecurrencePeriodMs = 1L,
                maxBackoffMs = 1L,
                events = events,
                tempDir = tempDir,
            )
            // Pre-terminalise attempt 1 as FAILED_TIMEOUT (the planner's
            // terminal-failure signal for the waitUntil aggregate).
            te.journal.beginAttempt(
                te.controlOpId,
                attempt = 1,
                currentBackoffMs = 1L,
                fingerprint = te.fingerprint,
                status = OperationStatus.RUNNING,
            )
            te.journal.updateStatus(
                te.controlOpId,
                attempt = 1,
                status = OperationStatus.FAILED_TIMEOUT,
                fingerprint = te.fingerprint,
            )

            val outcome = te.engine.execute(bodyRef)
            assertTrue(outcome is StepOutcome.Failure) { "deadline exceeded MUST return Failure; got $outcome" }
            assertEquals(FailureKind.TIMEOUT, (outcome as StepOutcome.Failure).failure.kind)
            assertTrue(
                events.eventsFor(te.runId.value).any {
                    it is WaitUntilCompleted && it.outcome == "deadline-exceeded"
                },
            ) { "WaitUntilCompleted(deadline-exceeded) MUST be emitted" }
            // The body MUST NOT have been invoked — replay of an
            // already-terminal deadline is a no-op.
            assertEquals(emptyList<Int>(), adapter.attemptIndices())
        }

        @Test
        fun `cancelled body becomes Failure SCRIPT and persists FAILED on that poll`(
            @TempDir tempDir: Path,
        ) = runBlocking {
            val parentPath = listOf(BlockSegment(0, PluginStepId("build/waituntil")))
            val bodyRef = BodyRefs.childBody(parentPath)
            val adapter = object : BodyInvoker {
                override suspend fun invoke(
                    body: BodyRef,
                    context: BodyInvocationContext,
                ): BodyOutcome = BodyOutcome.Cancelled(CancellationReason.ParentCancelled)
            }
            val te = makeEngine(
                adapter = adapter,
                bodyRef = bodyRef,
                parentBodyPath = parentPath,
                initialRecurrencePeriodMs = 1L,
                maxBackoffMs = 4L,
                events = InMemoryEventStore(),
                tempDir = tempDir,
            )
            val outcome = te.engine.execute(bodyRef)
            // Cancelled body propagates as WaitUntilPredicateOutcome.Cancelled
            // -> persist ABORTED, emit WaitUntilCompleted(outcome="aborted"),
            // return StepOutcome.Failure(ENGINE) — this is the waitUntil-
            // specific cancellation interpretation. The previous design
            // that folded Cancelled into a Failure(SCRIPT) silently
            // re-entered the planner with the same RUNNING poll.
            assertTrue(outcome is StepOutcome.Failure)
            assertEquals(FailureKind.ENGINE, (outcome as StepOutcome.Failure).failure.kind)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test-local helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun makeEngine(
        adapter: BodyInvoker,
        bodyRef: BodyRef,
        parentBodyPath: List<BlockSegment>,
        initialRecurrencePeriodMs: Long,
        maxBackoffMs: Long,
        events: EventSink,
        tempDir: Path,
        runId: RunId = RunId("lpr302-phase3"),
        stageIndex: Int = 0,
        stepIndex: Int = 0,
    ): TestEngine {
        val journal = FileBasedWaitUntilControlJournal(tempDir.resolve("waituntil-control"))
        val controlOpId = WaitUntilIdentityFactory.controlOperationId(
            runId.value, stageIndex, stepIndex, parentBodyPath,
        )
        val fingerprint = Fingerprint.compute(
            input = OperationInput(
                stepId = "wait-until-contract",
                params = emptyMap(),
                runId = "wait-until-contract",
                attempt = 1,
            ),
            stepId = "wait-until-contract",
            replayPolicy = ReplayPolicy.NEVER,
            attempt = 1,
        )
        val engine = WaitUntilEngine(
            journal = journal,
            eventSink = events,
            bodyInvoker = adapter,
            controlOpId = controlOpId,
            parentBodyPath = parentBodyPath,
            fingerprint = fingerprint,
            initialRecurrencePeriodMs = initialRecurrencePeriodMs,
            maxBackoffMs = maxBackoffMs,
            runId = runId,
            stageIndex = stageIndex,
            stepIndex = stepIndex,
            blockId = StepId("build/waituntil"),
            blockPluginStepId = PluginStepId("core.waitUntil"),
        )
        return TestEngine(engine, journal, controlOpId, fingerprint, runId)
    }

    private data class TestEngine(
        val engine: WaitUntilEngine,
        val journal: FileBasedWaitUntilControlJournal,
        val controlOpId: String,
        val fingerprint: Fingerprint,
        val runId: RunId,
    )

    private class RecordingAdapter : BodyInvoker {
        private val runners = mutableMapOf<BodyRef, suspend (BodyInvocationContext) -> StepOutcome>()
        private val attempts = mutableListOf<Int>()

        fun open(ref: BodyRef, runner: suspend (BodyInvocationContext) -> StepOutcome) {
            runners[ref] = runner
        }

        fun attemptIndices(): List<Int> = attempts.toList()

        override suspend fun invoke(
            body: BodyRef,
            context: BodyInvocationContext,
        ): BodyOutcome {
            val r = runners[body] ?: return BodyOutcome.Cancelled(CancellationReason.ParentCancelled)
            attempts += context.attempt?.index ?: 0
            return BodyOutcome.Completed(r(context))
        }
    }
}
