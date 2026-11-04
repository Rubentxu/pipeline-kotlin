package dev.rubentxu.pipeline.v2.runtime.durable.retry

import dev.rubentxu.pipeline.v2.runtime.LiveSpanLedger
import dev.rubentxu.pipeline.v2.runtime.LiveSpanNames
import dev.rubentxu.pipeline.v2.runtime.LiveSpanParent
import dev.rubentxu.pipeline.v2.runtime.LiveSpanStatus
import dev.rubentxu.pipeline.v2.runtime.SequentialIdGenerator
import dev.rubentxu.pipeline.v2.runtime.durable.RetryControlJournal
import dev.rubentxu.pipeline.v2.runtime.durable.RetryControlState
import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.RetryControlIdentity
import dev.rubentxu.pipeline.v2.domain.durable.RetryControlRowSnapshot
import dev.rubentxu.pipeline.v2.domain.step.BodyInvocationContext
import dev.rubentxu.pipeline.v2.domain.step.BodyInvoker
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodyRef
import dev.rubentxu.pipeline.v2.domain.step.CancellationReason
import dev.rubentxu.pipeline.v2.events.NullEventSink
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The retry aggregate must not LOOK like it ran when it did not run.
 *
 * ## THE GUARANTEE
 *
 * A retry that REUSES a durable terminal and a retry that RAN an attempt are
 * two different facts, and the trace has to tell them apart:
 *
 * ```text
 * reused    the body did not run, so no attempt span was opened, and the
 *           durable state was not written again
 * ran       the body ran, so exactly one attempt span was opened, and it
 *           says how the attempt ended
 * ```
 *
 * The first half is the half that is easy to get wrong. An implementation that
 * opened the attempt span BEFORE asking the reconciler would produce a trace
 * saying "this attempt ran" for a replay that reused a terminal, and the
 * collector would receive a lie that no reader could detect on its own. The
 * reuse arm therefore opens NOTHING, and this file asserts the absence.
 *
 * ## THE CAPACITY THIS SERVES
 *
 * An operator reading a trace of a resumed run asks "did the work happen
 * again?". A trace that opens an attempt span for every replay makes that
 * question unanswerable: the trace would say the work happened on every
 * resume, however many times the durable state had already recorded it as
 * done. The trace has to be a witness of what the process actually did.
 *
 * ## WHY THIS INSTRUMENT
 *
 * The instrument is the aggregate's own collaborators, held in memory: the
 * ledger that records which span was opened, the journal that records what was
 * persisted, and a body that counts how many times it ran. The three together
 * answer the three questions the guarantee is made of — "did it run?", "did it
 * say so?", "did it write anything?" — and none of them needs a process, a
 * directory or a clock.
 *
 * ## WHAT THIS INSTRUMENT CANNOT SEE
 *
 * It does not observe a durable resume: the journal here is in memory and
 * dies with the test. It observes the aggregate's DECISIONS — whether the body
 * runs, what is persisted, and which span is opened — and those decisions are
 * what the guarantee is about.
 */
class RetryEngineSpanLineageTest {

    /**
     * A replay that reuses a durable terminal opens NO attempt span.
     *
     * The aggregate reconciles BEFORE it invokes anything, and the reconcile
     * answers "this already succeeded". A span opened before the reconcile
     * would say the attempt ran, and it did not.
     */
    @Test
    @DisplayName("a replayed retry reuses the durable terminal and opens no attempt span")
    fun aReplayedRetryReusesTheDurableTerminalAndOpensNoAttemptSpan() {
        val ledger = LiveSpanLedger()
        val journal = InMemoryRetryControlJournal(
            seeded = listOf(
                RetryControlRowSnapshot(
                    attempt = 1,
                    status = OperationStatus.SUCCEEDED,
                    fingerprint = fingerprint,
                ),
            ),
        )
        val body = CountingBodyInvoker(BodyOutcome.Completed(StepOutcome.Success))

        val outcome = runBlocking {
            engine(journal, body, ledger).execute(
                bodyRef = BodyRef("body:0:core.retry"),
                parent = LiveSpanParent.Root,
            )
        }

        assertEquals(
            StepOutcome.Success,
            outcome,
            "the replayed aggregate answers the terminal that is already durable",
        )
        assertEquals(0, body.invocations, "the body did not run again")
        assertTrue(
            journal.writes.isEmpty(),
            "the replay persisted nothing: ${journal.writes}",
        )
        assertTrue(
            ledger.opened.isEmpty(),
            "the replay opened no span at all: ${ledger.opened.map { it.name }}",
        )
    }

    /**
     * A cancelled attempt says so, and is not retried.
     *
     * A cancellation is the parent's decision, and the aggregate honours it as
     * absolute: the attempt is not tried again. The span has to agree with
     * that, and the durable state has to record exactly one attempt — a second
     * attempt row would say the aggregate kept going after the parent said
     * stop.
     */
    @Test
    @DisplayName("a cancelled attempt ends its span as failed and is not retried")
    fun aCancelledAttemptEndsItsSpanAsFailedAndIsNotRetried() {
        val ledger = LiveSpanLedger()
        val journal = InMemoryRetryControlJournal()
        val body = CountingBodyInvoker(BodyOutcome.Cancelled(CancellationReason.Deadline))

        val outcome = runBlocking {
            engine(journal, body, ledger).execute(
                bodyRef = BodyRef("body:0:core.retry"),
                parent = LiveSpanParent.Root,
            )
        }

        val failure = assertInstanceOf(
            StepOutcome.Failure::class.java,
            outcome,
            "a cancelled body fails the aggregate",
        )
        assertEquals(
            FailureKind.SCRIPT,
            failure.failure.kind,
            "the cancellation is reported as a failure of the body",
        )
        assertEquals(1, body.invocations, "the body ran exactly once")
        assertEquals(
            listOf(
                "begin:1:${OperationStatus.RUNNING}",
                "status:1:${OperationStatus.FAILED}",
            ),
            journal.writes,
            "the attempt was opened and closed exactly once, and never reopened",
        )
        val attempt = ledger.spanNamed(LiveSpanNames.RETRY_ATTEMPT, RUN_ID)
        assertEquals(
            LiveSpanStatus.Failed,
            attempt.endedWith,
            "the attempt span says the attempt did not complete",
        )
        assertEquals(
            1,
            ledger.spansOf(RUN_ID).size,
            "a cancelled attempt is not retried, so there is only one attempt span",
        )
    }

    // --- the fixtures ------------------------------------------------------

    /** The aggregate under observation, composed over the collaborators of the row. */
    private fun engine(
        journal: RetryControlJournal,
        bodyInvoker: BodyInvoker,
        ledger: LiveSpanLedger,
    ): RetryEngine = RetryEngine(
        journal = journal,
        eventSink = NullEventSink,
        bodyInvoker = bodyInvoker,
        identity = RetryControlIdentity(operationId = CONTROL_OP_ID),
        controlOpId = CONTROL_OP_ID,
        parentBodyPath = listOf(BlockSegment("0:core.retry")),
        fingerprint = fingerprint,
        maxAttempts = MAX_ATTEMPTS,
        runId = RunId(RUN_ID),
        stageIndex = 0,
        stepIndex = 0,
        blockId = StepId(STEP_ID),
        blockPluginStepId = PluginStepId(STEP_ID),
        telemetry = ledger,
        clock = FixedClock,
        idGenerator = SequentialIdGenerator(),
    )

    /**
     * The durable control journal of the aggregate, in memory.
     *
     * The rows observe the aggregate's DECISIONS, and the journal is where
     * those decisions become durable. A fake that answered the reconciler with
     * whatever the row wanted would let the row assert itself; this one holds
     * the rows and records every write, so a row can say "the terminal was not
     * written again" as a fact.
     */
    private class InMemoryRetryControlJournal(
        seeded: List<RetryControlRowSnapshot> = emptyList(),
    ) : RetryControlJournal {

        /** The control rows the aggregate can read back. */
        private val rows = CopyOnWriteArrayList<RetryControlRowSnapshot>()

        /** Every write the aggregate performed, in order. */
        val writes: MutableList<String> = CopyOnWriteArrayList()

        init {
            rows.addAll(seeded)
        }

        /** The control rows, in attempt order. */
        val controlRows: List<RetryControlRowSnapshot> get() = rows.sortedBy { it.attempt }

        override fun beginAttempt(
            controlOpId: String,
            attempt: Int,
            fingerprint: Fingerprint,
            status: OperationStatus,
        ) {
            writes += "begin:$attempt:$status"
            rows.removeIf { it.attempt == attempt }
            rows += RetryControlRowSnapshot(attempt = attempt, status = status, fingerprint = fingerprint)
        }

        override fun updateStatus(
            controlOpId: String,
            attempt: Int,
            status: OperationStatus,
            fingerprint: Fingerprint,
        ) {
            writes += "status:$attempt:$status"
            rows.removeIf { it.attempt == attempt }
            rows += RetryControlRowSnapshot(attempt = attempt, status = status, fingerprint = fingerprint)
        }

        override fun readState(
            controlOpId: String,
            runId: String,
            stageIndex: Int,
            stepIndex: Int,
            parentBodyPath: List<BlockSegment>,
            maxAttempts: Int,
            currentFingerprint: Fingerprint,
        ): RetryControlState = RetryControlState(
            controlRows = controlRows,
            childrenByAttempt = emptyMap(),
            preControlChildren = emptyList(),
        )
    }

    /** The body of the aggregate, which counts how many times it actually ran. */
    private class CountingBodyInvoker(
        private val outcome: BodyOutcome,
    ) : BodyInvoker {

        /** How many times the body was invoked. */
        var invocations: Int = 0
            private set

        override suspend fun invoke(body: BodyRef, context: BodyInvocationContext): BodyOutcome {
            invocations += 1
            return outcome
        }
    }

    private companion object {
        const val RUN_ID = "run-1"
        const val STEP_ID = "core.retry"
        const val CONTROL_OP_ID = "run-1-s0-0-retry"
        const val MAX_ATTEMPTS = 3

        /** The fingerprint the retry contract under observation carries. */
        val fingerprint: Fingerprint = Fingerprint("a".repeat(64))
    }
    /** A clock that does not move, so nothing in these rows depends on the wall clock. */
    private object FixedClock : Clock {
        override fun now(): Instant = Instant.ofEpochMilli(2_000L)
    }
}
