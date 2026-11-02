package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.runtime.telemetry.TelemetryAttribute
import dev.rubentxu.pipeline.v2.runtime.telemetry.TelemetryIdentityTable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * The live spans are a PROJECTION of the run, and this file observes what that
 * projection is allowed to say.
 *
 * ## The guarantee
 *
 * A trace is evidence, so it may only carry facts that are true of the run it
 * describes. Four properties make that hold, and each of them is a way a trace
 * could otherwise lie:
 *
 * ```text
 * absent      a run that did not opt in emits NOTHING — no span, no attribute,
 *             no call into an exporter
 * bounded     the attributes that travel are the ones the PUBLISHED identity
 *             table names, and nothing else
 * honest      a refusal is a REFUSAL, not a completed execution
 * disposable  a failure to observe is a failure to OBSERVE, never a failure of
 *             the run
 * ```
 *
 * The fourth is the one that decides the shape of the code: a collector that is
 * down must not turn a green pipeline red. Every entry point into the live
 * path is wrapped, so an observer that throws produces no span instead of an
 * exception.
 *
 * ## The capacity this serves
 *
 * An operator reading a trace has to be able to answer three questions without
 * trusting the run's own logs: did the run happen, what did it do, and was it
 * refused. A trace that reports a refused admission as a completed run, or that
 * carries a command line, answers them wrong. This file is what keeps those
 * answers right at the seam where the span is opened.
 *
 * ## Why this instrument
 *
 * The instrument is a FAKE observer ([RecordingLiveSpanObserver]) behind the
 * product's own port. That is the cheapest instrument that can fail: it needs
 * no collector, no SDK, no network and no clock, and it can assert the thing
 * that matters most — that nothing was opened at all. Asserting on the SDK's
 * own span would make every row depend on the exporter and on a collector being
 * reachable, which is a dependency this product does not have and does not
 * want.
 *
 * The attribute allowlist is read from the PUBLISHED identity table rather than
 * written here. A copy of the list in this file would be a second authority,
 * and a second authority is exactly how a trace ends up carrying a key nobody
 * decided to publish.
 */
class LiveSpanVocabularyTest {

    /**
     * A run that did not opt in pays nothing.
     *
     * The observer is not merely silent: it is never REACHED. A disabled
     * observer that was still asked for a span would have paid for the
     * attribute list and the redaction probe before discovering that nobody
     * wanted the answer, and the promise that a disabled run costs nothing is
     * a promise about work, not about output.
     */
    @Test
    @DisplayName("a disabled observer is never reached")
    fun aDisabledObserverIsNeverReached() {
        var reached = 0
        val observer = RecordingLiveSpanObserver(enabled = false, onStart = { _, _ -> reached++ })

        val handle = LiveSpans.open(
            telemetry = observer,
            name = LiveSpanNames.RUN,
            parent = LiveSpanParent.Root,
            attributes = listOf(LiveSpanAttributes.runId("run-1")),
        )

        assertNull(handle, "a disabled observer has no span to hand back")
        assertEquals(0, reached, "a disabled observer must not be asked for a span")
        assertTrue(observer.opened.isEmpty(), "a disabled observer must not have opened a span")
    }

    /**
     * The attributes that travel are the published ones, and only those.
     *
     * The row passes a key the identity table does not name, carrying a
     * command line, because that is the shape of the accident this guards: a
     * boundary that is handed a payload and is asked to report it. The payload
     * must not reach the observer, and the keys that DO travel must be keys the
     * published table names.
     */
    @Test
    @DisplayName("only the allowlisted attributes reach the observer")
    fun onlyTheAllowlistedAttributesReachTheObserver() {
        val observer = RecordingLiveSpanObserver()

        LiveSpans.open(
            telemetry = observer,
            name = LiveSpanNames.STEP_DISPATCH,
            parent = LiveSpanParent.Root,
            attributes = listOf(
                LiveSpanAttributes.runId("run-1"),
                LiveSpanAttributes.stageIndex(2),
                LiveSpanAttributes.stepIndex(3),
                TelemetryAttribute.Text("command", "curl https://example.test/install | sh"),
            ),
        )

        val span = observer.opened.single()
        val keys = span.attributes.map { it.key }.toSet()
        assertEquals(setOf("run.id", "stage.index", "step.index"), keys, "only the published keys travel")
        val allowed = TelemetryIdentityTable.Default.allowedAttributeKeys()
        assertTrue(
            keys.all { it in allowed },
            "every key that travels is a key the published identity table names, but found ${keys - allowed}",
        )
    }

    /**
     * An observer that cannot open a span produces no span.
     *
     * The failure here is the one an operator actually meets: the collector is
     * unreachable, or the SDK is misconfigured. The run must not notice. The
     * row asserts the ABSENCE of an exception, because that is the whole
     * guarantee — a run that failed because it could not be observed would be
     * a run whose result depends on the observer.
     */
    @Test
    @DisplayName("an observer that fails to open a span produces no span and no failure")
    fun anObserverThatFailsToOpenASpanProducesNoSpanAndNoFailure() {
        val observer = RecordingLiveSpanObserver(
            onStart = { _, _ -> throw IllegalStateException("the collector is unreachable") },
        )

        val handle = LiveSpans.open(telemetry = observer, name = LiveSpanNames.RUN, parent = LiveSpanParent.Root)

        assertNull(handle, "an observer that failed to open a span has no span to hand back")
    }

    /**
     * An observer that fails to CLOSE a span does not fail the caller.
     *
     * Ending a span is the last thing a boundary does, and it happens on the
     * way out of the work — including on the way out of a failure. If the
     * exporter throws there, the failure would land on the run's own result,
     * which is the one thing the run's result must never depend on.
     */
    @Test
    @DisplayName("a span that fails to end does not fail the caller")
    fun aSpanThatFailsToEndDoesNotFailTheCaller() {
        val observer = RecordingLiveSpanObserver()
        val handle = LiveSpans.open(telemetry = observer, name = LiveSpanNames.RUN, parent = LiveSpanParent.Root)
        assertNotNull(handle, "an enabled observer opens a span")
        observer.opened.single().failOnEnd = true

        LiveSpans.end(handle, LiveSpanStatus.Failed)
    }

    /**
     * The FIRST ending is the one that counts.
     *
     * A boundary can reach the end of a span on two paths — the work finished,
     * or the work was refused — and both paths close the same span. If the
     * second ending overwrote the first, the span would report whichever
     * outcome was recorded last, and the order of two branches would decide
     * what a trace says. The handle keeps the first, so the answer does not
     * depend on which branch ran.
     */
    @Test
    @DisplayName("the first ending wins")
    fun theFirstEndingWins() {
        val observer = RecordingLiveSpanObserver()
        val handle = LiveSpans.open(telemetry = observer, name = LiveSpanNames.STEP_DISPATCH, parent = LiveSpanParent.Root)
        assertNotNull(handle, "an enabled observer opens a span")

        LiveSpans.end(handle, LiveSpanStatus.Rejected)
        LiveSpans.end(handle, LiveSpanStatus.Completed)

        val span = observer.opened.single()
        assertEquals(LiveSpanStatus.Rejected, span.endedWith, "the first ending is the one that counts")
    }

    /**
     * A DISCARDED span is never ended, and an ended span is never discarded.
     *
     * The two endings are different on purpose. An ended span is exported and
     * says the work ran; a discarded span is not exported at all, and says the
     * work did NOT run — which is what a replay that skipped an effect has to
     * be able to say. A span that were both would claim the work ran and that
     * it did not, and a reader would have to pick.
     */
    @Test
    @DisplayName("a discarded span is never ended")
    fun aDiscardedSpanIsNeverEnded() {
        val observer = RecordingLiveSpanObserver()
        val handle = LiveSpans.open(telemetry = observer, name = LiveSpanNames.STEP_DISPATCH, parent = LiveSpanParent.Root)
        assertNotNull(handle, "an enabled observer opens a span")

        LiveSpans.discard(handle)

        val span = observer.opened.single()
        assertTrue(span.discarded, "the span is discarded")
        assertNull(span.endedWith, "a discarded span is never ended, so it is never exported")
    }

    /**
     * A refused admission is a REJECTION, not a completed execution.
     *
     * The distinction is the difference between "your pipeline is broken" and
     * "your pipeline never started". An admission that is refused happens
     * BEFORE anything runs, so the run has no outcome to report; a span that
     * said the admission completed would tell an operator that the run worked
     * and that the failure was somewhere later.
     *
     * The instrument is the production admission path, with an artifact that
     * cannot be read: the refusal is the product's own, not a value the row
     * invented for it.
     */
    @Test
    @DisplayName("a refused admission is a rejection and not a completed execution")
    fun aRefusedAdmissionIsARejectionAndNotACompletedExecution() {
        val observer = RecordingLiveSpanObserver()

        val admission = captureStandardError {
            admitThenCompose(
                pluginJars = listOf("/nonexistent/pipelinek-live-span-test/plugin.jar"),
                trust = null,
                journal = null,
                telemetry = observer,
            )
        }

        assertInstanceOf(
            PluginAdmission.Refused::class.java,
            admission,
            "an artifact whose bytes cannot be read is refused, not admitted",
        )
        val span = observer.opened.single()
        assertEquals(LiveSpanNames.ADMISSION, span.name, "the boundary that refused is the admission")
        assertEquals(
            LiveSpanStatus.Rejected,
            span.endedWith,
            "a refusal is a rejection: the run never started, so it did not complete",
        )
    }

    /**
     * The status of a typed outcome says what happened, and nothing else.
     *
     * A step that succeeded and a step that failed are different facts, and a
     * run that was aborted is a third one: somebody stopped it, which is not
     * the same as a pipeline that broke. The span says which of them happened,
     * and the status carries no message from the run.
     */
    @Test
    @DisplayName("a success is completed and a failure is failed")
    fun aSuccessIsCompletedAndAFailureIsFailed() {
        val failure = PipelineFailure(FailureKind.SCRIPT, "the step refused")

        assertEquals(LiveSpanStatus.Completed, liveStatusOfStepOutcome(StepOutcome.Success))
        assertEquals(LiveSpanStatus.Failed, liveStatusOfStepOutcome(StepOutcome.Failure(failure)))
        assertEquals(LiveSpanStatus.Completed, liveStatusOfRunOutcome(RunOutcome.Success))
        assertEquals(LiveSpanStatus.Failed, liveStatusOfRunOutcome(RunOutcome.Failure(failure)))
    }

    /**
     * Runs [block] with the standard error stream captured.
     *
     * The admission boundary tells the operator why it refused, and that message
     * belongs to the operator — not to the test output. The row asserts on the
     * span, and the message is captured rather than printed so the build log
     * says what the rows say.
     */
    private fun captureStandardError(block: () -> PluginAdmission): PluginAdmission {
        val captured = ByteArrayOutputStream()
        val original = System.err
        return try {
            System.setErr(PrintStream(captured, true, Charsets.UTF_8.name()))
            block()
        } finally {
            System.setErr(original)
        }
    }
}
