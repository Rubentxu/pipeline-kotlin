package dev.rubentxu.pipeline.v2.runtime.durable

import dev.rubentxu.pipeline.v2.runtime.LiveSpanLedger
import dev.rubentxu.pipeline.v2.runtime.SequentialIdGenerator
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskTerminal
import dev.rubentxu.pipeline.v2.domain.durable.FailureOrigin
import dev.rubentxu.pipeline.v2.domain.durable.FailureRecord
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.events.NullEventSink
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * A REPLAY must not claim the work it skipped.
 *
 * ## The guarantee
 *
 * A durable run that resumes finds work in three states: still running, already finished, or
 * never started. The trace of that run has to say which one it was, and it may not say anything
 * about an effect the replay did not perform:
 *
 * ```text
 * already finished   the effect happened in an EARLIER run → this run emits NO span for it
 * still running      this run reconciled it → this run emits a span of its OWN
 * ```
 *
 * The first line is the one a trace is most tempted to break. A replay that reuses a completed
 * effect could emit a span saying «the step ran», and the reader would count an execution that
 * happened in another run — or in another attempt — as one that happened here. The replay would
 * then appear to have performed a side effect twice, when the whole point of the replay is that
 * it performed it ZERO times.
 *
 * The second line is the mirror: a replay that reconciles an in-flight operation DID do work —
 * it observed the substrate, materialised the value and settled the row — and that work is
 * worth a span. It is a NEW span, not the original invocation's: a replay opens a span for what
 * it actually did, and never re-uses the span id of an attempt that is not its own.
 *
 * ## The capacity this serves
 *
 * An operator reading the trace of a resumed run has to be able to tell how much of the pipeline
 * actually re-executed. Without that, a resumed run looks exactly like a fresh one, and the only
 * way to find out whether an effect was repeated is to go and read the journal by hand.
 *
 * ## Why this instrument
 *
 * The instrument is the production engine over an in-memory journal and a discarding event sink,
 * with a FAKE observer behind the product's own telemetry port. The fake is what makes the
 * ABSENCE observable: a real collector would tell us that a span arrived, and never that one did
 * NOT. The replay's whole guarantee here is an absence, so the instrument has to be able to
 * witness absences.
 *
 * Nothing here touches a disk, a process, a network or the wall clock.
 *
 * ## What this instrument cannot see
 *
 * It does not observe the DISPATCH boundary, which opens a span of its own and has to discard it
 * when this engine says the invocation was reused. That discard lives inside a method with ten
 * collaborators, and a unit row that pretended to reach it would be a row about a fiction. What
 * this file observes is the half that decides: the engine's own answer, and whether a span was
 * opened for work that was skipped.
 */
class RecoveryInterpretationEngineTest {

    /**
     * A REUSED effect leaves no trace at all.
     *
     * The effect described by this invocation happened in an earlier run. Emitting a span for it
     * here would be a fabricated execution, and the span would be indistinguishable from one that
     * reported work this run really did. The row asserts the ABSENCE, which is the whole point.
     */
    @Test
    @DisplayName("a reused effect leaves no span")
    fun aReusedEffectLeavesNoSpan() {
        val observer = LiveSpanLedger()

        val interpretation = runBlocking {
            engine(observer).interpret(
                resolution = InvocationReconciliation.ReuseCompleted,
                request = request(),
            )
        }

        assertInstanceOf(
            RecoveryInterpretationEngine.RecoveryInterpretation.Reused::class.java,
            interpretation,
            "an invocation whose effect is already complete is reused, not re-executed",
        )
        assertTrue(
            observer.opened.isEmpty(),
            "a replay that skipped the effect must leave no span of it, but opened ${observer.opened.map { it.name }}",
        )
    }

    /**
     * A RECOVERED effect opens a span of its own.
     *
     * The replay did work here: it observed the substrate, materialised the value and settled the
     * row. That work is worth a span, and the span belongs to THIS run — the coordinates of the
     * invocation travel with it, so a reader can tell which operation was reconciled.
     */
    @Test
    @DisplayName("a recovered effect opens a span of its own")
    fun aRecoveredEffectOpensASpanOfItsOwn() {
        val observer = LiveSpanLedger()

        val interpretation = runBlocking {
            engine(observer).interpret(
                resolution = InvocationReconciliation.RecoverRunning(launchFailedTerminal()),
                request = request(),
            )
        }

        assertInstanceOf(
            RecoveryInterpretationEngine.RecoveryInterpretation.Settled::class.java,
            interpretation,
            "an invocation whose terminal was recovered is settled, not reused",
        )
        val span = observer.opened.single()
        assertEquals("pipelinek.recovery", span.name, "the recovery opens the recovery span")
        assertTrue(span.endedWith != null, "a recovery span is ended, so it is exportable")
        val keys = span.attributes.map { it.key }.toSet()
        assertEquals(
            setOf("run.id", "stage.index", "step.index"),
            keys,
            "the recovery span says WHICH invocation it reconciled",
        )
    }

    /**
     * A recovery that CANNOT be observed leaves no span and settles a failure.
     *
     * The substrate could not be read, so nothing can be said about the external effect. The
     * invocation fails and the durable state is left exactly as it was found. A span here would
     * claim a conclusion the engine did not reach, so there is none.
     */
    @Test
    @DisplayName("an unobservable recovery leaves no span")
    fun anUnobservableRecoveryLeavesNoSpan() {
        val observer = LiveSpanLedger()

        val interpretation = runBlocking {
            engine(observer).interpret(
                resolution = InvocationReconciliation.RecoveryUnobservable(OP_ID),
                request = request(),
            )
        }

        assertInstanceOf(
            RecoveryInterpretationEngine.RecoveryInterpretation.Settled::class.java,
            interpretation,
            "an unobservable recovery is settled with a failure, never executed",
        )
        assertTrue(
            observer.opened.isEmpty(),
            "a recovery that reached no conclusion must not leave a span, but opened ${observer.opened.map { it.name }}",
        )
    }

    // --- the fixtures ------------------------------------------------------

    /** The engine, composed over the journal it settles into and the observer it reports to. */
    private fun engine(observer: LiveSpanLedger): RecoveryInterpretationEngine =
        RecoveryInterpretationEngine(
            eventSink = NullEventSink,
            records = DurableInvocationRecord(InMemoryOperationJournal(FixedClock)),
            telemetry = observer,
            clock = FixedClock,
            idGenerator = SequentialIdGenerator(),
        )

    /** The durable facts of the invocation under observation. */
    private fun request(): RecoveryInterpretationEngine.Request {
        val input = OperationInput(
            stepId = STEP_ID,
            params = emptyMap(),
            runId = RUN_ID,
            attempt = DurableInvocationRecord.ATTEMPT,
        )
        return RecoveryInterpretationEngine.Request(
            operationId = OP_ID,
            fingerprint = Fingerprint.compute(input, input.stepId, ReplayPolicy.RERUN, DurableInvocationRecord.ATTEMPT),
            input = input,
            lifecycleContext = StepLifecycleContext(
                runId = RUN_ID,
                stageIndex = 0,
                stepIndex = 0,
                stepName = "probe",
                stepType = STEP_ID,
            ),
        )
    }

    /** A terminal the recovery observed: the process could not be launched. */
    private fun launchFailedTerminal(): DurableTaskTerminal = DurableTaskTerminal.LaunchFailed(
        FailureRecord(
            code = "TEST_LAUNCH_FAILED",
            kind = FailureKind.INFRASTRUCTURE,
            message = "the operation's process could not be launched",
            origin = FailureOrigin.LAUNCHER,
            retryable = false,
            operationId = OP_ID,
        ),
    )

    /** A clock that does not move, so nothing in these rows depends on the wall clock. */
    private object FixedClock : Clock {
        override fun now(): Instant = Instant.EPOCH
    }

    private companion object {
        const val RUN_ID = "run-1"
        const val STEP_ID = "core.probe"
        const val OP_ID = "run-1-s0-0"
    }
}
