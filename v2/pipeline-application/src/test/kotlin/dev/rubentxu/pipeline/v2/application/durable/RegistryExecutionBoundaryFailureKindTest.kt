package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.TypedStepOutput
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
/**
 * WU-LPR-FK — TypedStepOutput carrier preservation lock-in.
 *
 * Locks the contract that the boundary preserves the declared failure
 * kind when a plugin handler returns a `TypedStepOutput`. Each row is a
 * negative assertion: the boundary MUST NOT re-classify a typed USER
 * (or SCRIPT, or TIMEOUT) failure as ENGINE.
 *
 * Equally important: when a handler throws an UNEXPECTED exception
 * (NPE, IllegalStateException, etc.), the boundary MUST classify the
 * outcome as ENGINE — that is the only place ENGINE is correct today,
 * and that classification MUST NOT be lost.
 *
 * Why this file exists: F5.2's negative scenarios observed
 * `failureKind=ENGINE` in the event envelope when the plugin handler
 * declared USER. Root cause was the handler `throw PluginStepException(USER)`
 * instead of returning a `TypedStepOutput`. The fix was generic (no
 * boundary change), and these tests guard the boundary contract from
 * accidental regression: any future handler that wishes to declare a
 * typed failure MUST do so via the `TypedStepOutput` carrier, and the
 * boundary MUST project the carrier verbatim.
 */
@Timeout(10)
class RegistryExecutionBoundaryFailureKindTest {

    private val key: PluginStepId = PluginStepId("test.fk.lockin")

    private val stringCodec = object : StepCodec<String> {
        override fun encode(value: String): EncodedStepValue = EncodedStepValue(value)
        override fun decode(encoded: EncodedStepValue): String = encoded.value
    }

    private val unitCodec = object : StepCodec<Unit> {
        override fun encode(value: Unit): EncodedStepValue = EncodedStepValue("null")
        override fun decode(encoded: EncodedStepValue): Unit = Unit
    }

    /** A carrier that pairs a payload with a typed outcome. */
    private data class Carrier<I, T>(
        val payload: I,
        override val outcome: StepOutcome,
    ) : TypedStepOutput

    private fun carrierDefinition(
        handler: StepHandler<String, Carrier<String, String>>,
    ): StepDefinition<String, Carrier<String, String>> =
        object : StepDefinition<String, Carrier<String, String>> {
            override val contract: StepContract<String, Carrier<String, String>> = StepContract(
                key = key,
                descriptor = StepDescriptor(
                    stepId = key.value,
                    name = "fk-lockin",
                    configRef = "",
                    executionLocation = ExecutionLocation.CONTROLLER,
                    effects = listOf(Effect.READ_ONLY),
                    replayPolicy = ReplayPolicy.MEMOIZED,
                ),
                inputCodec = stringCodec,
                outputCodec = stringCodec.let { stringCodec ->
                    object : StepCodec<Carrier<String, String>> {
                        override fun encode(value: Carrier<String, String>): EncodedStepValue =
                            stringCodec.encode(value.payload)
                        override fun decode(encoded: EncodedStepValue): Carrier<String, String> =
                            Carrier(stringCodec.decode(encoded), StepOutcome.Success)
                    }
                },
                requiredCapabilities = emptySet(),
            )
            override val handler = handler
        }

    private fun throwingDefinition(exception: Exception): StepDefinition<String, Carrier<String, String>> {
        val handler = StepHandler<String, Carrier<String, String>> { _, _ ->
            throw exception
        }
        return carrierDefinition(handler)
    }

    private fun runtime(store: InMemoryEventStore): CanonicalRuntimeContext = CanonicalRuntimeContext(
        opId = OpId("fk-lockin", 0, 0),
        runId = "fk-lockin",
        stageName = "build",
        stageIndex = 0,
        stepIndex = 0,
        shOptions = ShOptions.EMPTY,
        controlDirRoot = null,
        eventSink = store,
    )

    private fun prepareAndExecute(def: StepDefinition<String, Carrier<String, String>>, input: String) =
        runBlocking {
            val registry = InMemoryStepRegistry().apply { register(def) }
            val store = InMemoryEventStore()
            val ctx = runtime(store)
            val prepared = RegistryExecutionPreparation.prepare(
                registry = registry,
                key = key,
                encodedInput = EncodedStepValue(input),
                availableCapabilities = CanonicalRuntimeCapabilityAccess(ctx).available(),
            )
            assertTrue(prepared is ExecutionPreparation.Ready, "valid string input must prepare Ready")
            RegistryExecutionBoundary.adapt()
                .execute((prepared as ExecutionPreparation.Ready).prepared, ctx)
        }

    // -- lock-in assertions ------------------------------------------------

    @Test
    fun `handler throws unexpected Exception - boundary outcome kind is ENGINE`() {
        // Regression: unexpected handler exceptions are real adapter/engine
        // defects and the boundary MUST classify them as ENGINE. This is
        // the only path where ENGINE is correct.
        val outcome = prepareAndExecute(
            def = throwingDefinition(NullPointerException("handler NPE")),
            input = "x",
        )
        assertFailureKind(outcome.outcome, FailureKind.ENGINE)
        assertTrue(outcome.encodedOutput == null)
    }

    @Test
    fun `handler throws IllegalStateException - boundary outcome kind is ENGINE`() {
        val outcome = prepareAndExecute(
            def = throwingDefinition(IllegalStateException("bad state")),
            input = "x",
        )
        assertFailureKind(outcome.outcome, FailureKind.ENGINE)
    }

    @Test
    fun `handler returns TypedStepOutput with USER failure - boundary preserves USER`() {
        // Lock-in: a typed USER failure declared via the carrier MUST be
        // preserved end-to-end. Before the WU-LPR-FK fix the handler
        // threw PluginStepException(USER) and the boundary re-classified
        // it as ENGINE — exactly the bug this row guards against.
        val handler = StepHandler<String, Carrier<String, String>> { input, _ ->
            Carrier(
                payload = input,
                outcome = StepOutcome.Failure(
                    PipelineFailure(
                        kind = FailureKind.USER,
                        message = "user-declared failure for '$input'",
                    ),
                ),
            )
        }
        val outcome = prepareAndExecute(
            def = carrierDefinition(handler),
            input = "hello",
        )
        assertFailureKind(outcome.outcome, FailureKind.USER)
        assertNotNull(outcome.encodedOutput, "encoded output must carry the carrier payload")
    }

    @Test
    fun `handler returns TypedStepOutput with SCRIPT failure - boundary preserves SCRIPT`() {
        // Parity with core.sh: SCRIPT must survive the boundary.
        val handler = StepHandler<String, Carrier<String, String>> { input, _ ->
            Carrier(
                payload = input,
                outcome = StepOutcome.Failure(
                    PipelineFailure(
                        kind = FailureKind.SCRIPT,
                        message = "script failed",
                    ),
                ),
            )
        }
        val outcome = prepareAndExecute(
            def = carrierDefinition(handler),
            input = "hello",
        )
        assertFailureKind(outcome.outcome, FailureKind.SCRIPT)
    }

    @Test
    fun `handler returns TypedStepOutput with TIMEOUT failure - boundary preserves TIMEOUT`() {
        val handler = StepHandler<String, Carrier<String, String>> { input, _ ->
            Carrier(
                payload = input,
                outcome = StepOutcome.Failure(
                    PipelineFailure(
                        kind = FailureKind.TIMEOUT,
                        message = "timed out",
                    ),
                ),
            )
        }
        val outcome = prepareAndExecute(
            def = carrierDefinition(handler),
            input = "hello",
        )
        assertFailureKind(outcome.outcome, FailureKind.TIMEOUT)
    }

    @Test
    fun `handler returns TypedStepOutput with Success - boundary preserves Success`() {
        val handler = StepHandler<String, Carrier<String, String>> { input, _ ->
            Carrier(payload = input, outcome = StepOutcome.Success)
        }
        val outcome = prepareAndExecute(
            def = carrierDefinition(handler),
            input = "hello",
        )
        assertEquals(StepOutcome.Success, outcome.outcome)
    }

    @Test
    fun `handler returns TypedStepOutput with INFRASTRUCTURE failure - boundary preserves INFRASTRUCTURE`() {
        // INFRASTRUCTURE on a typed carrier is the legitimate way to
        // signal adapter-side issues (e.g. an I/O stat failure inside
        // the handler). It does NOT match the generic ENGINE catch.
        val handler = StepHandler<String, Carrier<String, String>> { input, _ ->
            Carrier(
                payload = input,
                outcome = StepOutcome.Failure(
                    PipelineFailure(
                        kind = FailureKind.INFRASTRUCTURE,
                        message = "stat() failed",
                    ),
                ),
            )
        }
        val outcome = prepareAndExecute(
            def = carrierDefinition(handler),
            input = "hello",
        )
        assertFailureKind(outcome.outcome, FailureKind.INFRASTRUCTURE)
    }

    private fun assertFailureKind(outcome: StepOutcome, expected: FailureKind) {
        assertTrue(
            outcome is StepOutcome.Failure,
            "outcome must be Failure, got $outcome",
        )
        assertEquals(expected, (outcome as StepOutcome.Failure).failure.kind)
    }
}
