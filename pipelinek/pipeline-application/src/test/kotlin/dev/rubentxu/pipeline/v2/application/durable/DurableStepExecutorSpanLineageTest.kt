package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.LiveSpanAttributes
import dev.rubentxu.pipeline.v2.application.LiveSpanLedger
import dev.rubentxu.pipeline.v2.application.LiveSpanNames
import dev.rubentxu.pipeline.v2.application.LiveSpanParent
import dev.rubentxu.pipeline.v2.application.LiveSpans
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.events.NullEventSink
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant

/**
 * The span of an effect hangs under the decision that caused the effect.
 *
 * ## THE GUARANTEE
 *
 * A plugin invocation is a span of its own, and it hangs under the dispatch
 * that asked for it:
 *
 * ```text
 * nested   the invocation's span names the dispatch's span as its parent,
 *          so the collector receives the effect under the decision that
 *          caused it
 * ```
 *
 * The parent is NOT ambient. It is carried in the invocation itself, which is
 * what lets the same fact survive a hop to another dispatcher and a resume in
 * another process.
 *
 * ## THE CAPACITY THIS SERVES
 *
 * An operator reading a trace asks "who asked for this invocation?". A
 * collector that receives the invocation as a root span cannot answer it, and
 * the answer is the whole reason the spans exist: the trace is not a list of
 * things that happened, it is a tree of causes.
 *
 * ## WHY THIS INSTRUMENT
 *
 * The instrument is the ledger: it records the span each boundary opened and
 * the parent it declared, so the row can compare the two objects directly.
 * The invocation itself does NOT run — the execution boundary returns a value
 * instead of doing anything, because this row is about the lineage and a real
 * invocation would add a process to a guarantee that does not need one.
 *
 * Everything else is in memory too: the clock is fixed, the event sink
 * discards, and the workspace and the control root point at paths that DO NOT
 * EXIST — if any of these compositions wrote there, the row would say so
 * instead of passing unnoticed.
 */
class DurableStepExecutorSpanLineageTest {

    /**
     * The invocation hangs under the dispatch that caused it.
     *
     * The dispatch opens its span where the decision to run is taken; the
     * invocation opens its own span where the effect happens. The two are
     * linked by the parent the invocation was handed, and the row reads that
     * link from the spans themselves.
     */
    @Test
    @DisplayName("the span of a plugin invocation hangs under the dispatch that caused it")
    fun theSpanOfAPluginInvocationHangsUnderTheDispatchThatCausedIt() {
        val ledger = LiveSpanLedger()
        val dispatch = requireNotNull(
            LiveSpans.open(
                telemetry = ledger,
                name = LiveSpanNames.STEP_DISPATCH,
                parent = LiveSpanParent.Root,
                attributes = listOf(LiveSpanAttributes.runId(RUN_ID)),
            ),
        ) { "an enabled telemetry opens a span" }

        val input = input()
        val result = runBlocking {
            executor(ledger).executeAndJournal(
                operationId = OP_ID,
                fingerprint = fingerprint(input),
                input = input,
                journaled = null,
                prepared = prepared(),
                runtime = runtime(),
                lifecycleContext = lifecycle(),
                parent = LiveSpanParent.Local(dispatch),
            )
        }

        assertEquals(StepOutcome.Success, result.outcome, "the invocation succeeded")
        val invocation = ledger.spanNamed(LiveSpanNames.STEP_INVOKE, RUN_ID)
        val dispatchSpan = ledger.spanNamed(LiveSpanNames.STEP_DISPATCH, RUN_ID)
        assertSame(
            dispatchSpan,
            ledger.parentOf(invocation),
            "the invocation hangs under the dispatch, and not under the run",
        )
    }

    // --- the fixtures ------------------------------------------------------

    /**
     * The executor, composed over the ledger.
     *
     * The boundary returns a value instead of running anything: this row
     * observes the LINEAGE, and an invocation that really ran would add a
     * process to a guarantee that does not need one.
     */
    private fun executor(ledger: LiveSpanLedger): DurableStepExecutor = DurableStepExecutor(
        eventSink = NullEventSink,
        executionBoundary = CommonExecutionBoundary { _, _ ->
            CommonExecutionResult(StepOutcome.Success, EncodedStepValue("42"))
        },
        records = DurableInvocationRecord(InMemoryOperationJournal(FixedClock)),
        telemetry = ledger,
    )

    /** The durable input of the invocation under observation. */
    private fun input(): OperationInput = OperationInput(
        stepId = STEP_ID,
        params = mapOf("payload" to JsonPrimitive("value")),
        runId = RUN_ID,
        attempt = DurableInvocationRecord.ATTEMPT,
    )

    /** The fingerprint of the invocation under observation, as the protocol computes it. */
    private fun fingerprint(input: OperationInput): Fingerprint =
        Fingerprint.compute(input, input.stepId, ReplayPolicy.RERUN, DurableInvocationRecord.ATTEMPT)

    /** The prepared execution: a real Step definition, with no handler ever invoked. */
    private fun prepared(): PreparedRegistryExecution = PreparedRegistryExecution(
        key = PluginStepId(STEP_ID),
        definition = definition(),
        decodedInput = "value",
    )

    /** A Step definition that would work, if anything ever called it. */
    private fun definition(): StepDefinition<String, String> = object : StepDefinition<String, String> {
        override val contract: StepContract<String, String> = StepContract(
            key = PluginStepId(STEP_ID),
            descriptor = StepDescriptor(stepId = STEP_ID, name = "probe", configRef = "probe"),
            inputCodec = StringCodec,
            outputCodec = StringCodec,
        )

        override val handler: StepHandler<String, String> = StepHandler { _, _ -> "value" }
    }

    /** The runtime context of the invocation, pointing at paths that do not exist. */
    private fun runtime(): CanonicalRuntimeContext = CanonicalRuntimeContext(
        opId = OpId(RUN_ID, 0, 0),
        runId = RUN_ID,
        stageName = "stage-0",
        stageIndex = 0,
        stepIndex = 0,
        shOptions = ShOptions(
            workspaceRoot = Path.of("/nonexistent-workspace"),
            captureStdout = false,
            timeoutMs = null,
            env = emptyMap(),
        ),
        controlDirRoot = Path.of("/nonexistent-control-root"),
        eventSink = NullEventSink,
    )

    /** The lifecycle coordinates of the invocation. */
    private fun lifecycle(): StepLifecycleContext = StepLifecycleContext(
        runId = RUN_ID,
        stageIndex = 0,
        stepIndex = 0,
        stepName = "probe",
        stepType = STEP_ID,
    )

    /** A clock that does not move, so nothing in this row depends on the wall clock. */
    private object FixedClock : Clock {
        override fun now(): Instant = Instant.EPOCH
    }

    /** A codec that carries a string, and nothing else. */
    private object StringCodec : StepCodec<String> {
        override fun encode(value: String): EncodedStepValue = EncodedStepValue(value)

        override fun decode(encoded: EncodedStepValue): String = encoded.value
    }

    private companion object {
        const val RUN_ID = "run-1"
        const val STEP_ID = "core.probe"
        const val OP_ID = "run-1-s0-0"
    }
}
