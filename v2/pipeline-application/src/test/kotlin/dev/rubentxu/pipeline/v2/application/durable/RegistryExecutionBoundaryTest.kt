package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CoreEchoStep
import dev.rubentxu.pipeline.v2.application.EVENT_SINK_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * CDE.3-d3: freezes the registry EXECUTE path behind the [CommonExecutionBoundary] (see
 * [RegistryExecutionBoundary]). A [PreparedRegistryExecution] that already passed registry resolution,
 * capability admission and typed decode runs its typed handler exactly once WITHOUT re-decoding, and
 * the durable outcome is produced. Missing capability and typed-invalid input reject during prepare, so
 * the common executor and the handler are never reached (prepare=1, common=0, handler=0).
 */
@Timeout(10)
class RegistryExecutionBoundaryTest {

    private val countingKey: PluginStepId = PluginStepId("test.count")

    /** Strict codec: decode throws for a non-integer payload, so a typed-invalid input is rejected. */
    private val intCodec = object : StepCodec<Int> {
        override fun encode(value: Int): EncodedStepValue = EncodedStepValue(value.toString())
        override fun decode(encoded: EncodedStepValue): Int =
            encoded.value.toIntOrNull() ?: throw IllegalArgumentException("not an int: '${encoded.value}'")
    }

    private val stringCodec = object : StepCodec<String> {
        override fun encode(value: String): EncodedStepValue = EncodedStepValue(value)
        override fun decode(encoded: EncodedStepValue): String = encoded.value
    }

    private fun countingDefinition(counter: AtomicInteger, required: Set<StepCapability>): StepDefinition<Int, String> =
        object : StepDefinition<Int, String> {
            override val contract: StepContract<Int, String> = StepContract(
                key = countingKey,
                descriptor = StepDescriptor(
                    stepId = countingKey.value,
                    name = "count",
                    configRef = "",
                    executionLocation = ExecutionLocation.CONTROLLER,
                    effects = listOf(Effect.READ_ONLY),
                    replayPolicy = ReplayPolicy.MEMOIZED,
                ),
                inputCodec = intCodec,
                outputCodec = stringCodec,
                requiredCapabilities = required,
            )

            override val handler: StepHandler<Int, String> = StepHandler { input, _ ->
                counter.incrementAndGet()
                "out:$input"
            }
        }

    private fun runtime(store: InMemoryEventStore): CanonicalRuntimeContext = CanonicalRuntimeContext(
        opId = OpId("registry-exec", 0, 0),
        runId = "registry-exec",
        stageName = "build",
        stageIndex = 0,
        stepIndex = 4,
        shOptions = ShOptions.EMPTY,
        controlDirRoot = null,
        eventSink = store,
    )

    @Test
    fun `fresh valid registry invocation runs the handler once without re-decoding`() = runBlocking {
        val counter = AtomicInteger(0)
        val registry = InMemoryStepRegistry().apply { register(countingDefinition(counter, emptySet())) }
        val store = InMemoryEventStore()
        val access = CanonicalRuntimeCapabilityAccess(runtime(store))

        // prepare (decode + admission) only: handler must NOT have run yet.
        val prepared = RegistryExecutionPreparation.prepare(
            registry = registry,
            key = countingKey,
            encodedInput = EncodedStepValue("7"),
            availableCapabilities = access.available(),
        )
        assertTrue(prepared is ExecutionPreparation.Ready, "valid int input must prepare Ready")
        assertEquals(0, counter.get(), "prepare must never run the handler")

        val outcome = RegistryExecutionBoundary.adapt()
            .execute((prepared as ExecutionPreparation.Ready).prepared, runtime(store))

        assertEquals(StepOutcome.Success, outcome)
        assertEquals(1, counter.get(), "a single boundary.execute must run the typed handler exactly once")
    }

    @Test
    fun `typed-invalid registry input rejects during prepare so handler never runs`() {
        val counter = AtomicInteger(0)
        val registry = InMemoryStepRegistry().apply { register(countingDefinition(counter, emptySet())) }
        val store = InMemoryEventStore()

        val prepared = RegistryExecutionPreparation.prepare(
            registry = registry,
            key = countingKey,
            encodedInput = EncodedStepValue("not-an-int"),
            availableCapabilities = CanonicalRuntimeCapabilityAccess(runtime(store)).available(),
        )

        assertTrue(prepared is ExecutionPreparation.Rejected, "decode failure must reject during prepare")
        assertEquals(0, counter.get(), "a rejected invocation must never reach the handler")
    }

    @Test
    fun `missing declared capability rejects during prepare so handler never runs`() {
        val counter = AtomicInteger(0)
        val registry = InMemoryStepRegistry().apply {
            register(countingDefinition(counter, setOf(EVENT_SINK_CAPABILITY)))
        }

        // Admission against a capability set that does NOT supply the declared EVENT_SINK.
        val prepared = RegistryExecutionPreparation.prepare(
            registry = registry,
            key = countingKey,
            encodedInput = EncodedStepValue("7"),
            availableCapabilities = emptySet(),
        )

        assertTrue(prepared is ExecutionPreparation.Rejected, "missing capability must reject during prepare")
        assertEquals(0, counter.get(), "a missing-capability invocation must never reach the handler")
    }

    @Test
    fun `capability admission is re-checked against the runtime access before the handler`() = runBlocking {
        val counter = AtomicInteger(0)
        // A prepared that declares a capability the runtime access does NOT expose must fail closed
        // inside the boundary (engine invariant) and never start the handler. Prepare already rejects
        // such an invocation, so this exercises the boundary's own hard guard directly.
        val boundary = RegistryExecutionBoundary.adapt()
        val fabricated = PreparedRegistryExecution(
            key = countingKey,
            definition = countingDefinition(counter, setOf(StepCapability("credentials.missing"))),
            decodedInput = 7,
        )

        org.junit.jupiter.api.Assertions.assertThrows(EngineInvariantViolation::class.java) {
            runBlocking { boundary.execute(fabricated, runtime(InMemoryEventStore())) }
        }
        assertEquals(0, counter.get(), "a capability the runtime does not supply must never start the handler")
    }

    @Test
    fun `a legacy family prepared execution fails closed in the registry boundary`() {
        val store = InMemoryEventStore()
        val boundary = RegistryExecutionBoundary.adapt()
        runBlocking {
            org.junit.jupiter.api.Assertions.assertThrows(EngineInvariantViolation::class.java) {
                runBlocking {
                    boundary.execute(
                        PreparedLegacyExecution(dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand.Echo("x")),
                        runtime(store),
                    )
                }
            }
        }
    }

    @Test
    fun `echo registry execution supplies the event sink capability and emits once`() = runBlocking {
        val registry = InMemoryStepRegistry().apply { CoreEchoStep.registerInto(this) }
        val store = InMemoryEventStore()
        val runtime = runtime(store)

        val prepared = RegistryExecutionPreparation.prepare(
            registry = registry,
            key = CoreEchoStep.KEY,
            encodedInput = CoreEchoStep.definition.contract.inputCodec.encode(dev.rubentxu.pipeline.v2.application.EchoInput("hi registry")),
            availableCapabilities = CanonicalRuntimeCapabilityAccess(runtime).available(),
        )
        assertTrue(prepared is ExecutionPreparation.Ready)

        val outcome = RegistryExecutionBoundary.adapt()
            .execute((prepared as ExecutionPreparation.Ready).prepared, runtime)

        assertEquals(StepOutcome.Success, outcome)
        val captured = store.eventsFor("registry-exec").single() as EchoOutputCaptured
        assertEquals("hi registry\n", captured.content)
        assertEquals(4, captured.stepIndex, "the narrow StepHandlerContext must carry the runtime step index")
    }
}
