package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CoreEchoStep
import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * CDE.3-d4: freezes the durable OUTPUT normalization of a registry execution.
 *
 * Grounding: [StepOutcome] carries no generic output slot and the journal schema is unchanged, so the
 * handler's typed `O` is NEVER stuffed into a [StepOutcome] and NEVER crosses to the durable
 * coordinator. A normal typed return and a void/Unit return both reduce to [StepOutcome.Success]; a
 * thrown handler reduces to a typed [StepOutcome.Failure] (ENGINE). Any durable-observable output must
 * be an effect the handler emits through its declared capabilities (echo emits [EchoOutputCaptured]);
 * the coordinator surface is [StepOutcome] only.
 */
@Timeout(10)
class RegistryExecutionOutcomeTest {

    private val intCodec = object : StepCodec<Int> {
        override fun encode(value: Int): EncodedStepValue = EncodedStepValue(value.toString())
        override fun decode(encoded: EncodedStepValue): Int = encoded.value.toInt()
    }

    private fun stringStep(key: PluginStepId, handler: StepHandler<Int, String>): StepDefinition<Int, String> =
        object : StepDefinition<Int, String> {
            override val contract: StepContract<Int, String> = StepContract(
                key = key,
                descriptor = StepDescriptor(
                    stepId = key.value,
                    name = key.value,
                    configRef = "",
                    executionLocation = ExecutionLocation.CONTROLLER,
                    effects = listOf(Effect.READ_ONLY),
                    replayPolicy = ReplayPolicy.MEMOIZED,
                ),
                inputCodec = intCodec,
                outputCodec = object : StepCodec<String> {
                    override fun encode(value: String): EncodedStepValue = EncodedStepValue(value)
                    override fun decode(encoded: EncodedStepValue): String = encoded.value
                },
            )
            override val handler: StepHandler<Int, String> = handler
        }

    private fun unitCodec(): StepCodec<Unit> = object : StepCodec<Unit> {
        override fun encode(value: Unit): EncodedStepValue = EncodedStepValue("")
        override fun decode(encoded: EncodedStepValue): Unit = Unit
    }

    private fun unitStep(key: PluginStepId): StepDefinition<Int, Unit> =
        object : StepDefinition<Int, Unit> {
            override val contract: StepContract<Int, Unit> = StepContract(
                key = key,
                descriptor = StepDescriptor(
                    stepId = key.value,
                    name = key.value,
                    configRef = "",
                    executionLocation = ExecutionLocation.CONTROLLER,
                    effects = listOf(Effect.READ_ONLY),
                    replayPolicy = ReplayPolicy.MEMOIZED,
                ),
                inputCodec = intCodec,
                outputCodec = unitCodec(),
            )
            override val handler: StepHandler<Int, Unit> = StepHandler { _, _ -> Unit }
        }

    private fun runtime(store: InMemoryEventStore): CanonicalRuntimeContext = CanonicalRuntimeContext(
        opId = OpId("registry-outcome", 0, 0),
        runId = "registry-outcome",
        stageName = "build",
        stageIndex = 0,
        stepIndex = 0,
        shOptions = ShOptions.EMPTY,
        controlDirRoot = null,
        eventSink = store,
    )

    private fun prepareValid(registry: InMemoryStepRegistry, key: PluginStepId): PreparedRegistryExecution {
        val store = InMemoryEventStore()
        val ready = RegistryExecutionPreparation.prepare(
            registry = registry,
            key = key,
            encodedInput = EncodedStepValue("1"),
            availableCapabilities = CanonicalRuntimeCapabilityAccess(runtime(store)).available(),
        )
        assertTrue(ready is ExecutionPreparation.Ready)
        return (ready as ExecutionPreparation.Ready).prepared as PreparedRegistryExecution
    }

    @Test
    fun `typed handler output reduces to Success and never reaches the coordinator`() = runBlocking {
        val store = InMemoryEventStore()
        val registry = InMemoryStepRegistry().apply {
            CoreEchoStep.registerInto(this)
        }
        val prepared = prepareValid(registry, CoreEchoStep.KEY)

        val outcome = RegistryExecutionBoundary.adapt().execute(prepared, runtime(store))

        // The coordinator-facing result is a plain StepOutcome (Success); the echo handler's typed
        // String payload is observable only as the EchoOutputCaptured event it emitted, never as a
        // value on the durable outcome.
        assertEquals(StepOutcome.Success, outcome)
        val captured = store.eventsFor("registry-outcome").single() as EchoOutputCaptured
        assertEquals("1\n", captured.content)
    }

    @Test
    fun `void unit handler output reduces to Success`() = runBlocking {
        val unitKey = PluginStepId("test.unit-out")
        val registry = InMemoryStepRegistry().apply { register(unitStep(unitKey)) }
        val prepared = prepareValid(registry, unitKey)

        val outcome = RegistryExecutionBoundary.adapt().execute(prepared, runtime(InMemoryEventStore()))

        assertEquals(StepOutcome.Success, outcome, "a Unit handler return is a durable Success")
    }

    @Test
    fun `a throwing handler reduces to a typed engine failure`() = runBlocking {
        val boomKey = PluginStepId("test.boom")
        val throwingStep = stringStep(boomKey, StepHandler<Int, String> { _, _ ->
            throw IllegalStateException("handler exploded")
        })
        val registry = InMemoryStepRegistry().apply { register(throwingStep) }
        val prepared = prepareValid(registry, boomKey)

        val outcome = RegistryExecutionBoundary.adapt().execute(prepared, runtime(InMemoryEventStore()))

        assertTrue(outcome is StepOutcome.Failure, "a thrown handler must fail closed, got $outcome")
        val failure = (outcome as StepOutcome.Failure).failure
        assertEquals(FailureKind.ENGINE, failure.kind)
        assertTrue(failure.message.contains("handler exploded"))
        assertEquals("handler exploded", failure.cause?.message)
    }

    @Test
    fun `durable coordinator surface exposes only StepOutcome with no carried value`() = runBlocking {
        val typedKey = PluginStepId("test.typed-out")
        val typedStep = stringStep(typedKey, StepHandler<Int, String> { input, _ -> "out:$input" })
        val registry = InMemoryStepRegistry().apply { register(typedStep) }
        val prepared = prepareValid(registry, typedKey)

        val outcome: StepOutcome = RegistryExecutionBoundary.adapt().execute(prepared, runtime(InMemoryEventStore()))

        // The erased handler O is consumed inside the boundary; the coordinator-side result is a plain
        // StepOutcome (Success is a value-less data object), so no `Any` can leak to the durable protocol.
        assertTrue(outcome is StepOutcome.Success, "the handler's typed O must never surface as a coordinator value")
    }
}
