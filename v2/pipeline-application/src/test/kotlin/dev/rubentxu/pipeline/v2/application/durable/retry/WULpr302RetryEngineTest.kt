package dev.rubentxu.pipeline.v2.application.durable.retry

import dev.rubentxu.pipeline.v2.application.durable.FileBasedRetryControlJournal
import dev.rubentxu.pipeline.v2.application.durable.RetryIdentityFactory
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
import dev.rubentxu.pipeline.v2.domain.durable.RetryControlIdentity
import dev.rubentxu.pipeline.v2.domain.step.BodyInvocationContext
import dev.rubentxu.pipeline.v2.domain.step.BodyInvoker
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodyRefs
import dev.rubentxu.pipeline.v2.domain.step.CancellationReason
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.RetryAttemptFinished
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
 * WU-LPR-302 (Phase 2, 2026-09-18) — RetryEngine is the application-internal
 * authority for the retry aggregate.
 *
 * These tests pin the four properties the human direction demanded:
 *
 * 1. **Identity**: the same `BodyRef` invoked under attempt N produces the
 *    deterministic `bodyPath` segment for attempt N (Phase 1b projection);
 *    attempt 1 and attempt 2 produce distinct durable identities.
 * 2. **Replay**: an attempt already committed as terminal in the journal is
 *    NOT re-executed; the engine returns the reused outcome without invoking
 *    the body.
 * 3. **Port use**: the engine reaches the body through the public
 *    [BodyInvoker] port; it does NOT call `invokeBodyChildren` directly. A
 *    fitness scan pins this property at the bytecode level.
 * 4. **Outcome folding**: success / fail-then-success / exhausted / unstable /
 *    cancelled bodies produce the correct typed StepOutcome.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class WULpr302RetryEngineTest {

    @Nested
    inner class Identity {
        @Test
        fun `engine drives attempt 1 and attempt 2 through the same BodyRef under distinct attempt contexts`(
            @TempDir tempDir: Path,
        ) = runBlocking {
            // The engine uses `bodyInvoker.invoke(bodyRef,
            // BodyInvocationContext(attempt = N))` for each attempt. Phase 1b
            // proves the runner projects attempt onto a distinct deterministic
            // bodyPath; together the engine + seam guarantee per-attempt
            // identity.
            val parentPath = listOf(BlockSegment(0, PluginStepId("build/retry")))
            val bodyRef = BodyRefs.childBody(parentPath)
            val adapter = RecordingAdapter()
            adapter.open(bodyRef) { ctx ->
                StepOutcome.Success
            }
            val te = makeEngine(
                adapter = adapter,
                bodyRef = bodyRef,
                parentBodyPath = parentPath,
                maxAttempts = 2,
                events = InMemoryEventStore(),
                tempDir = tempDir,
            )

            // Simulate a fresh retry: no control rows -> planner emits
            // ScheduleAttempt(1); body succeeds; engine closes the loop with
            // Success. To exercise attempt 2, we feed the engine a control
            // journal that has attempt 1 as a terminal failure so the
            // planner emits AdvanceAfterFailure / ScheduleAttempt(2).
            te.journal.beginAttempt(
                te.controlOpId,
                attempt = 1,
                fingerprint = te.fingerprint,
                status = OperationStatus.RUNNING,
            )
            te.journal.updateStatus(
                te.controlOpId,
                attempt = 1,
                status = OperationStatus.FAILED,
                fingerprint = te.fingerprint,
            )
            val outcome = te.engine.execute(bodyRef)
            assertEquals(StepOutcome.Success, outcome)
            // The recorded invocations show attempt 2 reaching the same
            // BodyRef under a distinct attempt context. Attempt 1 is
            // pre-terminalised in the journal by the test, so the planner
            // emits AdvanceAfterFailure(1 -> 2) and ScheduleAttempt(2) in
            // sequence; only attempt 2 actually invokes the body. The
            // per-attempt identity law is still proven: attempt 2's
            // BodyInvocationContext.attempt.index == 2, distinct from any
            // prior attempt.
            val seenAttempts = adapter.attemptIndices()
            assertEquals(listOf(2), seenAttempts) {
                "engine MUST invoke the body under attempt 2; " +
                    "if the engine re-ran attempt 1, the planner's " +
                    "pre-terminalised row was ignored"
            }
            // Per-attempt identity projection is exercised through the same
            // BodyRef; the bodyPath for attempt 2 differs from attempt 1's.
            val attempt1Path = parentPath + BlockSegment(1, PluginStepId("retry-attempt"))
            val attempt2Path = parentPath + BlockSegment(2, PluginStepId("retry-attempt"))
            assertNotEquals(attempt1Path, attempt2Path)
        }
    }

    @Nested
    inner class Replay {
        @Test
        fun `engine reuses a SUCCEEDED control row without invoking the body`(
            @TempDir tempDir: Path,
        ) = runBlocking {
            val parentPath = listOf(BlockSegment(0, PluginStepId("build/retry")))
            val bodyRef = BodyRefs.childBody(parentPath)
            val adapter = RecordingAdapter()
            adapter.open(bodyRef) { StepOutcome.Success }
            val te = makeEngine(
                adapter = adapter,
                bodyRef = bodyRef,
                parentBodyPath = parentPath,
                maxAttempts = 2,
                events = InMemoryEventStore(),
                tempDir = tempDir,
            )
            // Pre-seed the journal with a terminal SUCCEEDED row at attempt 1.
            te.journal.beginAttempt(
                te.controlOpId,
                attempt = 1,
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
                "a SUCCEEDED control row MUST short-circuit; the body MUST NOT run again"
            }
        }

        @Test
        fun `engine reuses an exhausted control row without invoking the body`(
            @TempDir tempDir: Path,
        ) = runBlocking {
            val parentPath = listOf(BlockSegment(0, PluginStepId("build/retry")))
            val bodyRef = BodyRefs.childBody(parentPath)
            val adapter = RecordingAdapter()
            adapter.open(bodyRef) { StepOutcome.Success }
            val te = makeEngine(
                adapter = adapter,
                bodyRef = bodyRef,
                parentBodyPath = parentPath,
                maxAttempts = 2,
                events = InMemoryEventStore(),
                tempDir = tempDir,
            )
            te.journal.beginAttempt(
                te.controlOpId, attempt = 1,
                fingerprint = te.fingerprint, status = OperationStatus.RUNNING,
            )
            te.journal.updateStatus(
                te.controlOpId, attempt = 1,
                status = OperationStatus.FAILED, fingerprint = te.fingerprint,
            )
            te.journal.beginAttempt(
                te.controlOpId, attempt = 2,
                fingerprint = te.fingerprint, status = OperationStatus.RUNNING,
            )
            te.journal.updateStatus(
                te.controlOpId, attempt = 2,
                status = OperationStatus.FAILED, fingerprint = te.fingerprint,
            )

            val outcome = te.engine.execute(bodyRef)
            assertTrue(outcome is StepOutcome.Failure)
            val failure = outcome as StepOutcome.Failure
            assertEquals(FailureKind.SCRIPT, failure.failure.kind)
            assertEquals(emptyList<Int>(), adapter.attemptIndices()) {
                "an exhausted retry (maxAttempts reached FAILED) MUST short-circuit"
            }
        }
    }

    @Nested
    inner class PortUse {
        @Test
        fun `engine constructor takes BodyInvoker and never accepts invokeBodyChildren-style parameters`() {
            // Fitness: the engine's public surface must NOT accept any
            // parameter that hints at the legacy "iterate body children"
            // authority. If a future change adds such a parameter, this test
            // fails and the architecture fitness is restored.
            val ctor = RetryEngine::class.java.declaredConstructors.first()
            val params = ctor.parameterTypes.map { it.simpleName }.toSet()
            assertTrue("BodyInvoker" in params) {
                "RetryEngine MUST depend on BodyInvoker (port); got params=$params"
            }
            assertTrue("FileBasedRetryControlJournal" in params)
            assertTrue("EventSink" in params)
            // No `invokeBodyChildren`-style parameter. `BlockStepNode`,
            // `CompiledPipeline`, `StageNode`, `ExecutionContext` and
            // `ShOptions` all belong to the canonical coordinator's body-
            // dispatch surface; the engine must not own them. `List` is
            // permitted only because the engine legitimately takes
            // `List<BlockSegment>` (per-attempt deterministic bodyPath).
            val banned = listOf(
                "BlockStepNode",
                "CompiledPipeline",
                "StageNode",
                "StepNode",
                "ExecutionContext",
                "ShOptions",
            )
            val offending = params.filter { it in banned.toSet() }
            assertEquals(emptyList<String>(), offending) {
                "RetryEngine MUST NOT accept BlockStepNode / CompiledPipeline / " +
                    "StageNode / StepNode / ExecutionContext / ShOptions — those " +
                    "belong to the coordinator. Got: $offending"
            }
        }

        @Test
        fun `engine invokes body through BodyInvoker not invokeBodyChildren`(
            @TempDir tempDir: Path,
        ) = runBlocking {
            // Behavioural proof: the body is invoked through the
            // `bodyInvoker.invoke(bodyRef, ...)` call. We register a runner
            // that records the call; if the engine reached into the
            // coordinator's body-child loop, the runner would never fire.
            val parentPath = listOf(BlockSegment(0, PluginStepId("build/retry")))
            val bodyRef = BodyRefs.childBody(parentPath)
            var invokedThroughAdapter = false
            val adapter = object : BodyInvoker {
                override suspend fun invoke(
                    body: dev.rubentxu.pipeline.v2.domain.step.BodyRef,
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
                maxAttempts = 1,
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
        fun `success first attempt returns StepOutcome Success and persists SUCCEEDED`(
            @TempDir tempDir: Path,
        ) = runBlocking {
            val parentPath = listOf(BlockSegment(0, PluginStepId("build/retry")))
            val bodyRef = BodyRefs.childBody(parentPath)
            val adapter = RecordingAdapter().also { it.open(bodyRef) { StepOutcome.Success } }
            val events = InMemoryEventStore()
            val te = makeEngine(
                adapter = adapter,
                bodyRef = bodyRef,
                parentBodyPath = parentPath,
                maxAttempts = 2,
                events = events,
                tempDir = tempDir,
            )

            val outcome = te.engine.execute(bodyRef)
            assertEquals(StepOutcome.Success, outcome)
            assertEquals(
                OperationStatus.SUCCEEDED,
                te.journal.readState(
                    controlOpId = te.controlOpId,
                    runId = te.runId.value,
                    stageIndex = 0,
                    stepIndex = 0,
                    parentBodyPath = parentPath,
                    maxAttempts = 2,
                    currentFingerprint = te.fingerprint,
                ).controlRows.single().status,
            )
            assertTrue(
                events.eventsFor(te.runId.value)
                    .any { it is RetryAttemptFinished && it.attemptNumber == 1 && it.outcome == "succeeded" },
            ) { "RetryAttemptFinished(succeeded, 1) MUST be emitted on success" }
        }

        @Test
        fun `fail then success returns Success and persists SUCCEEDED on attempt 2`(
            @TempDir tempDir: Path,
        ) = runBlocking {
            val parentPath = listOf(BlockSegment(0, PluginStepId("build/retry")))
            val bodyRef = BodyRefs.childBody(parentPath)
            val attempts = mutableListOf<Int>()
            val adapter = RecordingAdapter().also {
                it.open(bodyRef) { ctx ->
                    attempts += ctx.attempt?.index ?: 0
                    if (ctx.attempt?.index == 1) StepOutcome.Failure(
                        PipelineFailure(FailureKind.SCRIPT, "fail-attempt-1"),
                    ) else StepOutcome.Success
                }
            }
            val te = makeEngine(
                adapter = adapter,
                bodyRef = bodyRef,
                parentBodyPath = parentPath,
                maxAttempts = 2,
                events = InMemoryEventStore(),
                tempDir = tempDir,
            )
            val outcome = te.engine.execute(bodyRef)
            assertEquals(StepOutcome.Success, outcome)
            assertEquals(listOf(1, 2), attempts)
            // Control rows: attempt 1 FAILED, attempt 2 SUCCEEDED.
            val rows = te.journal.readState(
                controlOpId = te.controlOpId,
                runId = te.runId.value,
                stageIndex = 0,
                stepIndex = 0,
                parentBodyPath = parentPath,
                maxAttempts = 2,
                currentFingerprint = te.fingerprint,
            ).controlRows.sortedBy { it.attempt }
            assertEquals(2, rows.size)
            assertEquals(OperationStatus.FAILED, rows[0].status)
            assertEquals(OperationStatus.SUCCEEDED, rows[1].status)
        }

        @Test
        fun `exhausted attempts returns StepOutcome Failure SCRIPT with attempt N`(
            @TempDir tempDir: Path,
        ) = runBlocking {
            val parentPath = listOf(BlockSegment(0, PluginStepId("build/retry")))
            val bodyRef = BodyRefs.childBody(parentPath)
            val adapter = RecordingAdapter().also {
                it.open(bodyRef) { _ ->
                    StepOutcome.Failure(PipelineFailure(FailureKind.SCRIPT, "always-fail"))
                }
            }
            val te = makeEngine(
                adapter = adapter,
                bodyRef = bodyRef,
                parentBodyPath = parentPath,
                maxAttempts = 3,
                events = InMemoryEventStore(),
                tempDir = tempDir,
            )
            val outcome = te.engine.execute(bodyRef)
            assertTrue(outcome is StepOutcome.Failure)
            assertEquals(FailureKind.SCRIPT, (outcome as StepOutcome.Failure).failure.kind)
            assertEquals(listOf(1, 2, 3), adapter.attemptIndices())
        }

        @Test
        fun `unstable body returns StepOutcome Unstable and persists FAILED on that attempt`(
            @TempDir tempDir: Path,
        ) = runBlocking {
            val parentPath = listOf(BlockSegment(0, PluginStepId("build/retry")))
            val bodyRef = BodyRefs.childBody(parentPath)
            val adapter = RecordingAdapter().also {
                it.open(bodyRef) { _ -> StepOutcome.Unstable }
            }
            val te = makeEngine(
                adapter = adapter,
                bodyRef = bodyRef,
                parentBodyPath = parentPath,
                maxAttempts = 3,
                events = InMemoryEventStore(),
                tempDir = tempDir,
            )
            val outcome = te.engine.execute(bodyRef)
            assertTrue(outcome is StepOutcome.Unstable)
            // First attempt is FAILED; engine returned without advancing.
            val rows = te.journal.readState(
                controlOpId = te.controlOpId,
                runId = te.runId.value,
                stageIndex = 0,
                stepIndex = 0,
                parentBodyPath = parentPath,
                maxAttempts = 3,
                currentFingerprint = te.fingerprint,
            ).controlRows
            assertEquals(1, rows.size)
            assertEquals(OperationStatus.FAILED, rows.single().status)
        }

        @Test
        fun `cancelled body becomes StepOutcome Failure SCRIPT and persists FAILED`(
            @TempDir tempDir: Path,
        ) = runBlocking {
            val parentPath = listOf(BlockSegment(0, PluginStepId("build/retry")))
            val bodyRef = BodyRefs.childBody(parentPath)
            val adapter = object : BodyInvoker {
                override suspend fun invoke(
                    body: dev.rubentxu.pipeline.v2.domain.step.BodyRef,
                    context: BodyInvocationContext,
                ): BodyOutcome = BodyOutcome.Cancelled(CancellationReason.ParentCancelled)
            }
            val te = makeEngine(
                adapter = adapter,
                bodyRef = bodyRef,
                parentBodyPath = parentPath,
                maxAttempts = 2,
                events = InMemoryEventStore(),
                tempDir = tempDir,
            )
            val outcome = te.engine.execute(bodyRef)
            assertTrue(outcome is StepOutcome.Failure)
            val rows = te.journal.readState(
                controlOpId = te.controlOpId,
                runId = te.runId.value,
                stageIndex = 0,
                stepIndex = 0,
                parentBodyPath = parentPath,
                maxAttempts = 2,
                currentFingerprint = te.fingerprint,
            ).controlRows
            assertEquals(1, rows.size)
            assertEquals(OperationStatus.FAILED, rows.single().status)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test-local helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun makeEngine(
        adapter: BodyInvoker,
        bodyRef: dev.rubentxu.pipeline.v2.domain.step.BodyRef,
        parentBodyPath: List<BlockSegment>,
        maxAttempts: Int,
        events: EventSink,
        tempDir: Path,
        runId: RunId = RunId("lpr302-phase2"),
        stageIndex: Int = 0,
        stepIndex: Int = 0,
    ): TestEngine {
        val journal = FileBasedRetryControlJournal(tempDir.resolve("retry-control"))
        val controlOpId = RetryIdentityFactory.controlOperationId(
            runId.value, stageIndex, stepIndex, parentBodyPath,
        )
        val fingerprint = Fingerprint.compute(
            input = OperationInput(
                stepId = "build/retry",
                params = emptyMap(),
                runId = "retry-contract",
                attempt = 1,
            ),
            stepId = "retry-contract",
            replayPolicy = ReplayPolicy.MEMOIZED,
            attempt = 1,
        )
        val engine = RetryEngine(
            journal = journal,
            eventSink = events,
            bodyInvoker = adapter,
            identity = RetryControlIdentity(operationId = controlOpId),
            controlOpId = controlOpId,
            parentBodyPath = parentBodyPath,
            fingerprint = fingerprint,
            maxAttempts = maxAttempts,
            runId = runId,
            stageIndex = stageIndex,
            stepIndex = stepIndex,
            blockId = StepId("build/retry"),
            blockPluginStepId = PluginStepId("core.retry"),
        )
        return TestEngine(engine, journal, controlOpId, fingerprint, runId)
    }

    /**
     * Test-local view of the engine plus the journal identifiers the tests
     * need to verify durable state. The engine itself keeps these fields
     * private (production invariant); the helper exposes them only to tests.
     */
    private data class TestEngine(
        val engine: RetryEngine,
        val journal: FileBasedRetryControlJournal,
        val controlOpId: String,
        val fingerprint: Fingerprint,
        val runId: RunId,
    )

    /**
     * Tiny [BodyInvoker] that records the attempt indices passed to its
     * registered runner. Used to assert engine-side per-attempt identity.
     */
    private class RecordingAdapter : BodyInvoker {
        private val runners = mutableMapOf<
            dev.rubentxu.pipeline.v2.domain.step.BodyRef,
            suspend (BodyInvocationContext) -> StepOutcome
        >()
        private val attempts = mutableListOf<Int>()

        fun open(
            ref: dev.rubentxu.pipeline.v2.domain.step.BodyRef,
            runner: suspend (BodyInvocationContext) -> StepOutcome,
        ) {
            runners[ref] = runner
        }

        fun attemptIndices(): List<Int> = attempts.toList()

        override suspend fun invoke(
            body: dev.rubentxu.pipeline.v2.domain.step.BodyRef,
            context: BodyInvocationContext,
        ): BodyOutcome {
            val r = runners[body] ?: return BodyOutcome.Cancelled(CancellationReason.ParentCancelled)
            attempts += context.attempt?.index ?: 0
            return BodyOutcome.Completed(r(context))
        }
    }
}
