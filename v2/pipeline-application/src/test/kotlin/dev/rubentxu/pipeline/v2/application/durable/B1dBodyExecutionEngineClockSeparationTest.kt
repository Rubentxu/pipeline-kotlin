package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.BlockStepNode
import dev.rubentxu.pipeline.v2.domain.ExecutionContext
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.DirEntered
import dev.rubentxu.pipeline.v2.events.DirExited
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.TimestampsEntered
import dev.rubentxu.pipeline.v2.events.TimestampsExited
import dev.rubentxu.pipeline.v2.events.TimeoutScheduled
import dev.rubentxu.pipeline.v2.events.TimeoutTriggered
import dev.rubentxu.pipeline.v2.events.RetryAttemptStarted
import dev.rubentxu.pipeline.v2.events.WaitUntilPolled
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * B1d — AUD-06: the scope bookends take their `occurredAt` from the injected durable
 * [Clock], the measurement sites keep the wall clock, and neither choice moves durable
 * replay semantics.
 *
 * ## What AUD-06 measured, and what this class pins
 *
 * `BodyExecutionEngine` read the current instant from two sources: the injected durable
 * `Clock` (CredentialUsed, TimeoutScheduled) and `Instant.now()` (ten `occurredAt`
 * sites, plus five `System.currentTimeMillis()` duration computations). AUD-06's own
 * reading endorses the separation "business/reproducible clock vs measurement wall
 * clock" and asks for it to be executed, not re-decided.
 *
 * This class pins the executable part of that separation, and only that part:
 *
 * - `DirEntered`/`DirExited`, `TimestampsEntered`/`TimestampsExited`, `TimeoutTriggered`
 *   carry the injected instant (their event kinds have no other producer in the
 *   codebase, and the sibling `TimeoutScheduled` already read the durable clock);
 * - `RetryAttemptStarted` and the `WaitUntil*` events stay on the wall clock because
 *   `RetryEngine` / `WaitUntilEngine` emit the SAME event kinds with `Instant.now()`;
 *   moving one side alone would put the two execution paths of a single Step on
 *   different time sources. That boundary is deferred, and the guard row below states
 *   it as CHARACTERISATION so any future family-wide unification flips it explicitly
 *   instead of silently.
 *
 * ## Why this is not a durable-semantics change (the AUD-06 hard limit)
 *
 * `occurredAt` is a non-deterministic identity field: it is excluded from semantic
 * parity (`S2_A4_...:57`), stripped as non-deterministic by `CorpusNormalizer`, and
 * absent from every `Fingerprint` payload. No journal decision, no fingerprint and no
 * replay comparison reads it. Under the production `SystemClock`,
 * `java.time.Clock.systemUTC().instant()` and `Instant.now()` are the same value, so
 * the change is a production null-op that removes an ambient wall-clock read.
 *
 * ## Fidelity (HARNESS FIDELITY LAW §1)
 *
 * **HF1 — in-process, through the production authority, named:** the scopes are
 * projected and executed through `BodyExecutionEngine.projectScope` /
 * `executeScope3b` — the same functions the canonical coordinator calls — with a real
 * `CanonicalBodyInvokerAdapter` and a real event sink. Only the child dispatcher is a
 * stub, and it is not the subject. Assertions are discrete values (the exact instant
 * and the exact event kinds), never durations, sizes or real-clock magnitudes.
 */
@Timeout(60)
class B1dBodyExecutionEngineClockSeparationTest {

    /** A clock whose reading is a constant: the property under test is "the event carries it". */
    private class FixedClock(private val instant: Instant) : Clock {
        override fun now(): Instant = instant
    }

    @Test
    fun `dir scope bookends carry the injected durable clock instant`(@TempDir root: Path) {
        val sink = RecordingSink()
        val engine = engine(sink)
        val target = Files.createDirectories(root.resolve("projected"))
        val block = block()
        val scope = BlockShellScope.Directory(target, root)

        engine.projectScope(scope, block, RUN_ID, 0, 0, shOptions(root), ExecutionContext.EMPTY)
        runBlocking {
            engine.executeScope3b(
                scope = scope,
                block = block,
                runId = RUN_ID,
                stageName = "S",
                stageIndex = 0,
                stepIndex = 0,
                childShOptions = shOptions(root),
                parentBodyPath = emptyList(),
                context = ExecutionContext.EMPTY,
                dispatcher = successDispatcher(),
                bodyInvokerAdapter = CanonicalBodyInvokerAdapter(),
            )
        }

        val entered = sink.events.filterIsInstance<DirEntered>().single()
        val exited = sink.events.filterIsInstance<DirExited>().single()
        assertEquals(
            FIXED_INSTANT,
            entered.occurredAt,
            "AUD-06 (B1d): with a fixed clock injected, DirEntered.occurredAt must be exactly that " +
                "instant (before the fix it was java.time.Instant.now()).",
        )
        assertEquals(
            FIXED_INSTANT,
            exited.occurredAt,
            "AUD-06 (B1d): with a fixed clock injected, DirExited.occurredAt must be exactly that " +
                "instant; the dir bookends must share one reproducible time source.",
        )
    }

    @Test
    fun `timestamps scope bookends carry the injected durable clock instant`(@TempDir root: Path) {
        val sink = RecordingSink()
        val engine = engine(sink)
        val block = block()
        val scope = BlockShellScope.TimestampsScope(RUN_ID.value)

        engine.projectScope(scope, block, RUN_ID, 0, 0, shOptions(root), ExecutionContext.EMPTY)
        runBlocking {
            engine.executeScope3b(
                scope = scope,
                block = block,
                runId = RUN_ID,
                stageName = "S",
                stageIndex = 0,
                stepIndex = 0,
                childShOptions = shOptions(root),
                parentBodyPath = emptyList(),
                context = ExecutionContext.EMPTY,
                dispatcher = successDispatcher(),
                bodyInvokerAdapter = CanonicalBodyInvokerAdapter(),
            )
        }

        val entered = sink.events.filterIsInstance<TimestampsEntered>().single()
        val exited = sink.events.filterIsInstance<TimestampsExited>().single()
        assertEquals(
            FIXED_INSTANT,
            entered.occurredAt,
            "AUD-06 (B1d): TimestampsEntered.occurredAt must be the injected instant.",
        )
        assertEquals(
            FIXED_INSTANT,
            exited.occurredAt,
            "AUD-06 (B1d): TimestampsExited.occurredAt must be the injected instant.",
        )
    }

    @Test
    fun `timeout admission and breach pair on the injected durable clock instant`(@TempDir root: Path) {
        val sink = RecordingSink()
        val engine = engine(sink)
        val block = block()
        val scope = BlockShellScope.Timeout(budgetMs = 1_000L)

        engine.projectScope(scope, block, RUN_ID, 0, 0, shOptions(root), ExecutionContext.EMPTY)
        val outcome = runBlocking {
            engine.executeScope3b(
                scope = scope,
                block = block,
                runId = RUN_ID,
                stageName = "S",
                stageIndex = 0,
                stepIndex = 0,
                childShOptions = shOptions(root),
                parentBodyPath = emptyList(),
                context = ExecutionContext.EMPTY,
                dispatcher = BodyChildDispatcher { _, _, _, _, _, _, _, _ ->
                    StepOutcome.Failure(PipelineFailure(FailureKind.TIMEOUT, "budget exceeded"))
                },
                bodyInvokerAdapter = CanonicalBodyInvokerAdapter(),
            )
        }
        assertTrue(outcome is StepOutcome.Failure, "the stub child must fold its TIMEOUT failure")

        val scheduled = sink.events.filterIsInstance<TimeoutScheduled>().single()
        val triggered = sink.events.filterIsInstance<TimeoutTriggered>().single()
        assertEquals(
            FIXED_INSTANT,
            scheduled.occurredAt,
            "AUD-06 (B1d): TimeoutScheduled already read the durable clock; keep it pinned.",
        )
        assertEquals(
            FIXED_INSTANT,
            triggered.occurredAt,
            "AUD-06 (B1d): TimeoutTriggered must read the same seam as its admission, so an " +
                "observer can subtract the two reproducibly under an injected clock.",
        )
    }

    @Test
    fun `a body re-executed with the same clock reproduces the same event timestamps`(@TempDir root: Path) {
        val first = runFourScopeScenario(root)
        val second = runFourScopeScenario(root)

        val firstTimeline = first.map { it::class.simpleName to it.occurredAt }
        val secondTimeline = second.map { it::class.simpleName to it.occurredAt }

        assertEquals(
            firstTimeline,
            secondTimeline,
            "AUD-06 (B1d): with the same clock injected, the same scopes must produce the same " +
                "(kind, occurredAt) timeline; a wall-clock read would make occurrences differ.",
        )
        assertTrue(
            first.any { it is DirEntered } && first.any { it is TimeoutTriggered },
            "the scenario must actually exercise the fixed-clock scopes; got $firstTimeline",
        )
    }

    /**
     * CHARACTERISATION of the deferred group, NOT a claim of correctness.
     *
     * `WaitUntilPolled` and `RetryAttemptStarted` are emitted by `WaitUntilEngine` and
     * `RetryEngine` with `Instant.now()`. Unifying the family is a separate, wider gate;
     * until then this row states the boundary explicitly. If a future WU unifies the
     * family onto the durable clock, this row flips and the transition is deliberate.
     */
    @Test
    fun `CHARACTERISATION - waitUntil and retry attempt events still read the wall clock`(@TempDir root: Path) {
        val sink = RecordingSink()
        val engine = engine(sink)

        runBlocking {
            engine.executeScope3b(
                scope = BlockShellScope.Retry(maxAttempts = 1),
                block = block(),
                runId = RUN_ID,
                stageName = "S",
                stageIndex = 0,
                stepIndex = 0,
                childShOptions = shOptions(root),
                parentBodyPath = emptyList(),
                context = ExecutionContext.EMPTY,
                dispatcher = successDispatcher(),
                bodyInvokerAdapter = CanonicalBodyInvokerAdapter(),
            )
            engine.executeScope3b(
                scope = BlockShellScope.WaitUntilScope(initialRecurrencePeriod = 1L, quiet = true, maxBackoffMs = 2L),
                block = block(),
                runId = RUN_ID,
                stageName = "S",
                stageIndex = 0,
                stepIndex = 0,
                childShOptions = shOptions(root),
                parentBodyPath = emptyList(),
                context = ExecutionContext.EMPTY,
                dispatcher = BodyChildDispatcher { _, _, _, _, _, _, _, _ ->
                    StepOutcome.Failure(PipelineFailure(FailureKind.SCRIPT, "condition never holds"))
                },
                bodyInvokerAdapter = CanonicalBodyInvokerAdapter(),
            )
        }

        val retry = sink.events.filterIsInstance<RetryAttemptStarted>()
        val polled = sink.events.filterIsInstance<WaitUntilPolled>()
        assertTrue(retry.isNotEmpty(), "the retry inline loop must emit RetryAttemptStarted")
        assertTrue(polled.isNotEmpty(), "the waitUntil inline loop must emit WaitUntilPolled")
        assertFalse(
            (retry.map { it.occurredAt } + polled.map { it.occurredAt }).contains(FIXED_INSTANT),
            "DEFERRED GROUP: RetryAttemptStarted/WaitUntilPolled are shared with RetryEngine/" +
                "WaitUntilEngine, which use Instant.now(); they must NOT silently pick up the " +
                "injected clock here. Flip this row only as part of a family-wide unification.",
        )
    }

    // ------------------------------------------------------------------ fixtures

    private fun engine(sink: EventSink): BodyExecutionEngine = BodyExecutionEngine(
        eventSink = sink,
        clock = FixedClock(FIXED_INSTANT),
        bodyInvokerAdapter = CanonicalBodyInvokerAdapter(),
    )

    private fun runFourScopeScenario(root: Path): List<DomainEvent> {
        val sink = RecordingSink()
        val engine = engine(sink)
        val target = runCatching { Files.createDirectories(root.resolve("projected")) }.getOrThrow()

        engine.projectScope(
            BlockShellScope.Directory(target, root), block(), RUN_ID, 0, 0, shOptions(root), ExecutionContext.EMPTY,
        )
        engine.projectScope(
            BlockShellScope.Timeout(1_000L), block(), RUN_ID, 0, 0, shOptions(root), ExecutionContext.EMPTY,
        )
        runBlocking {
            engine.executeScope3b(
                scope = BlockShellScope.Directory(target, root),
                block = block(),
                runId = RUN_ID,
                stageName = "S",
                stageIndex = 0,
                stepIndex = 0,
                childShOptions = shOptions(root),
                parentBodyPath = emptyList(),
                context = ExecutionContext.EMPTY,
                dispatcher = successDispatcher(),
                bodyInvokerAdapter = CanonicalBodyInvokerAdapter(),
            )
            engine.executeScope3b(
                scope = BlockShellScope.Timeout(1_000L),
                block = block(),
                runId = RUN_ID,
                stageName = "S",
                stageIndex = 0,
                stepIndex = 0,
                childShOptions = shOptions(root),
                parentBodyPath = emptyList(),
                context = ExecutionContext.EMPTY,
                dispatcher = BodyChildDispatcher { _, _, _, _, _, _, _, _ ->
                    StepOutcome.Failure(PipelineFailure(FailureKind.TIMEOUT, "budget exceeded"))
                },
                bodyInvokerAdapter = CanonicalBodyInvokerAdapter(),
            )
        }
        return sink.events
    }

    private fun successDispatcher(): BodyChildDispatcher =
        BodyChildDispatcher { _, _, _, _, _, _, _, _ -> StepOutcome.Success }

    private fun shOptions(root: Path) = ShOptions(
        workspaceRoot = root,
        captureStdout = false,
        timeoutMs = null,
        env = emptyMap(),
    )

    private fun block(): BlockStepNode = BlockStepNode(
        id = StepId("b1d-block"),
        pluginStepId = PluginStepId("core.dir"),
        payload = VersionedStepPayload("dsl-v1", "{}"),
        body = listOf<StepNode>(
            OpaqueStepNode(
                id = StepId("child"),
                pluginStepId = PluginStepId("core.echo"),
                payload = VersionedStepPayload("dsl-v1", "{}"),
            ),
        ),
    )

    private class RecordingSink : EventSink {
        val events = mutableListOf<DomainEvent>()

        override fun append(event: DomainEvent) {
            events += event
        }

        override fun eventsFor(runId: String): Sequence<DomainEvent> = events.asSequence()
    }

    private companion object {
        val FIXED_INSTANT: Instant = Instant.parse("2024-01-01T00:00:00Z")
        val RUN_ID = RunId("b1d-clock-separation")
    }
}
