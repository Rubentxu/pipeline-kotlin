package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.application.durable.ExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.application.durable.PreparedRegistryExecution
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionBoundary
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.StructuralFamilyResolver
import dev.rubentxu.pipeline.v2.application.durable.StructuralStepFamily
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.TypedStepOutput
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * S2-A2 / G1 candidate proof. The registry contains `core.sleep`, but legacy membership wins
 * routing until the later cutover gate. These tests intentionally do not alter G0 legacy
 * characterization: they prove the candidate and the authority separation independently.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CoreSleepStepUnitTest {

    @Test
    fun `input validation accepts zero and rejects negative seconds before any duration conversion`() {
        assertEquals(CoreSleepInput(0), CoreSleepInput(0))
        val negative = assertThrows(IllegalArgumentException::class.java) { CoreSleepInput(-1) }
        assertTrue(negative.message!!.contains("seconds >= 0"))
    }

    @Test
    fun `input codec is legacy-envelope-compatible and rejects only negative seconds at decode boundary`() {
        val encoded = CoreSleepStep.definition.contract.inputCodec.encode(CoreSleepInput(1))
        assertEquals("""{"kind":"sleep","seconds":1}""", encoded.value)
        assertEquals(CoreSleepInput(1), CoreSleepStep.definition.contract.inputCodec.decode(encoded))

        assertEquals(
            CoreSleepInput(0),
            CoreSleepStep.definition.contract.inputCodec.decode(EncodedStepValue("""{"kind":"sleep","seconds":0}""")),
        )
        val invalid = assertThrows(IllegalArgumentException::class.java) {
            CoreSleepStep.definition.contract.inputCodec.decode(EncodedStepValue("""{"kind":"sleep","seconds":-1}"""))
        }
        assertTrue(invalid.message!!.contains("seconds >= 0"))
    }

    @Test
    fun `large positive input remains representable without legacy millisecond multiplication`() {
        val input = CoreSleepInput(Long.MAX_VALUE)
        val encoded = CoreSleepStep.definition.contract.inputCodec.encode(input)
        assertEquals(input, CoreSleepStep.definition.contract.inputCodec.decode(encoded))
    }

    @Test
    fun `output codec round-trips typed success carrier`() {
        val encoded = CoreSleepStep.definition.contract.outputCodec.encode(CoreSleepOutput)
        assertEquals("""{"kind":"sleep","outcome":"SUCCESS"}""", encoded.value)
        assertSame(CoreSleepOutput, CoreSleepStep.definition.contract.outputCodec.decode(encoded))
        assertEquals(StepOutcome.Success, CoreSleepOutput.outcome)
        val output: TypedStepOutput = CoreSleepOutput
        assertEquals(StepOutcome.Success, output.outcome)
    }

    @Test
    fun `descriptor preserves legacy read-only memoized controller contract`() {
        val descriptor = CoreSleepStep.definition.contract.descriptor
        assertEquals("core.sleep", descriptor.stepId)
        assertEquals("sleep", descriptor.name)
        assertEquals(listOf(Effect.READ_ONLY), descriptor.effects)
        assertEquals(ReplayPolicy.MEMOIZED, descriptor.replayPolicy)
        assertEquals(RecoveryPolicy.None, descriptor.recoveryPolicy)
    }

    @Test
    fun `candidate declares no capabilities`() {
        assertTrue(CoreSleepStep.definition.contract.requiredCapabilities.isEmpty())
    }

    @Test
    fun `factory registry resolves candidate while legacy membership remains canonical authority`() {
        val registry = CoreStepRegistryFactory.registry()
        assertNotNull(registry.definition(CoreSleepStep.KEY))
        assertSame(CoreSleepStep.definition, registry.definition(CoreSleepStep.KEY))
        assertTrue("core.sleep" in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS)
        assertEquals(
            StructuralStepFamily.LegacyCore,
            StructuralFamilyResolver.classify(CoreSleepStep.KEY, registry),
            "legacy membership wins even when the candidate is registered",
        )
    }

    @Test
    fun `handler completes normally through real registry preparation and boundary`() = runBlocking {
        val result = RegistryExecutionBoundary.coexecute(prepare(CoreSleepInput(1)), context("normal"))
        assertEquals(StepOutcome.Success, result.outcome)
        assertNotNull(result.encodedOutput)
        assertEquals(
            CoreSleepOutput,
            CoreSleepStep.definition.contract.outputCodec.decode(result.encodedOutput!!),
        )
    }

    @Test
    fun `zero completes immediately through the real registry seam`() = runBlocking {
        val result = RegistryExecutionBoundary.coexecute(prepare(CoreSleepInput(0)), context("zero"))
        assertEquals(StepOutcome.Success, result.outcome)
    }

    @Test
    fun `external cancellation remains structured and is never relabelled ENGINE`() = runBlocking {
        val job = launch {
            RegistryExecutionBoundary.coexecute(prepare(CoreSleepInput(Long.MAX_VALUE)), context("cancel"))
        }
        delay(50)
        job.cancelAndJoin()
        assertTrue(job.isCancelled, "ordinary CancellationException must propagate to the coroutine owner")
    }

    @Test
    fun `parent deadline preserves timeout cancellation for its coordinator owner`() = runBlocking {
        assertThrows(kotlinx.coroutines.TimeoutCancellationException::class.java) {
            runBlocking {
                withTimeout(50) {
                    RegistryExecutionBoundary.coexecute(prepare(CoreSleepInput(60)), context("parent-timeout"))
                }
            }
        }
    }

    @Test
    fun `boundary propagates an internal timeout so coroutine ownership is never swallowed`() = runBlocking {
        val definition = object : StepDefinition<CoreSleepInput, CoreSleepOutput> {
            override val contract = CoreSleepStep.definition.contract
            override val handler = StepHandler<CoreSleepInput, CoreSleepOutput> { _, _ ->
                withTimeout(50) { delay(60) }
                CoreSleepOutput
            }
        }
        val registry = InMemoryStepRegistry().also { it.register(definition) }
        val preparation = RegistryExecutionPreparation.prepare(
            registry = registry,
            key = CoreSleepStep.KEY,
            encodedInput = CoreSleepStep.definition.contract.inputCodec.encode(CoreSleepInput(1)),
            availableCapabilities = emptySet(),
        )
        val ready = assertInstanceOf(ExecutionPreparation.Ready::class.java, preparation)
        val prepared = assertInstanceOf(PreparedRegistryExecution::class.java, ready.prepared)
        assertThrows(kotlinx.coroutines.TimeoutCancellationException::class.java) {
            runBlocking { RegistryExecutionBoundary.coexecute(prepared, context("internal-timeout")) }
        }
    }

    private fun prepare(input: CoreSleepInput): PreparedRegistryExecution {
        val preparation = RegistryExecutionPreparation.prepare(
            registry = CoreStepRegistryFactory.registry(),
            key = CoreSleepStep.KEY,
            encodedInput = CoreSleepStep.definition.contract.inputCodec.encode(input),
            availableCapabilities = emptySet(),
        )
        val ready = assertInstanceOf(ExecutionPreparation.Ready::class.java, preparation)
        return assertInstanceOf(PreparedRegistryExecution::class.java, ready.prepared)
    }

    private fun context(label: String): CanonicalRuntimeContext = CanonicalRuntimeContext(
        opId = OpId("g1-sleep-$label", 0, 0),
        runId = "g1-sleep-$label",
        stageName = "candidate",
        stageIndex = 0,
        stepIndex = 0,
        shOptions = ShOptions.EMPTY,
        controlDirRoot = Files.createTempDirectory("g1-sleep-$label-"),
        eventSink = InMemoryEventStore(),
    )
}
