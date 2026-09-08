package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CDE.3-c (slice 1): freezes that the registry PREPARE seam ([RegistryExecutionPreparation]) performs
 * resolution + capability admission + typed decode and NEVER runs the handler, mirroring the legacy
 * split proven in CDE.3-b3. Ready means decoded-and-admitted; Rejected (unknown key, missing
 * capability, decode failure) means the common executor must not run.
 */
class RegistryExecutionPreparationTest {

    private var handlerCalls = 0

    /** Bespoke definition whose input codec decodes only a value starting with "ok:", else throws. */
    private fun registryStep(key: String, required: Set<StepCapability> = emptySet()): StepDefinition<String, String> {
        val pluginKey = PluginStepId(key)
        return object : StepDefinition<String, String> {
            override val contract: StepContract<String, String> = StepContract(
                key = pluginKey,
                descriptor = StepDescriptor(
                    stepId = key,
                    name = "test",
                    configRef = "",
                    executionLocation = ExecutionLocation.CONTROLLER,
                    effects = listOf(Effect.READ_ONLY),
                    replayPolicy = ReplayPolicy.MEMOIZED,
                ),
                inputCodec = object : StepCodec<String> {
                    override fun encode(value: String): EncodedStepValue = EncodedStepValue(value)
                    override fun decode(encoded: EncodedStepValue): String {
                        require(encoded.value.startsWith("ok:")) { "input must start with 'ok:'" }
                        return encoded.value.removePrefix("ok:")
                    }
                },
                outputCodec = object : StepCodec<String> {
                    override fun encode(value: String): EncodedStepValue = EncodedStepValue(value)
                    override fun decode(encoded: EncodedStepValue): String = encoded.value
                },
                requiredCapabilities = required,
            )
            override val handler: StepHandler<String, String> = StepHandler { input, _ ->
                handlerCalls++
                "ran:$input"
            }
        }
    }

    @Test
    fun `fresh valid registry input prepares Ready without running the handler`() {
        val registry = InMemoryStepRegistry()
        registry.register(registryStep("test.echo"))
        handlerCalls = 0

        val result = RegistryExecutionPreparation.prepare(
            registry,
            PluginStepId("test.echo"),
            EncodedStepValue("ok:hello"),
            emptySet(),
        )

        assertTrue(result is ExecutionPreparation.Ready, "valid input must prepare Ready, got $result")
        val prepared = (result as ExecutionPreparation.Ready).prepared
        assertTrue(prepared is PreparedRegistryExecution, "Ready must carry a PreparedRegistryExecution")
        assertEquals("hello", (prepared as PreparedRegistryExecution).decodedInput)
        assertEquals(0, handlerCalls, "prepare must decode/admit WITHOUT running the handler")
    }

    @Test
    fun `fresh typed-invalid registry input rejects without running the handler`() {
        val registry = InMemoryStepRegistry()
        registry.register(registryStep("test.echo"))
        handlerCalls = 0

        val result = RegistryExecutionPreparation.prepare(
            registry,
            PluginStepId("test.echo"),
            EncodedStepValue("bad-input"),
            emptySet(),
        )

        assertTrue(result is ExecutionPreparation.Rejected, "decode failure must reject during prepare")
        assertEquals(0, handlerCalls, "a decode-failed input must never reach the handler")
    }

    @Test
    fun `unknown registry step rejects during prepare`() {
        val registry = InMemoryStepRegistry()

        val result = RegistryExecutionPreparation.prepare(
            registry,
            PluginStepId("not.registered"),
            EncodedStepValue("ok:x"),
            emptySet(),
        )

        assertTrue(result is ExecutionPreparation.Rejected, "an unregistered key must reject during prepare")
        assertEquals(0, handlerCalls)
    }

    @Test
    fun `missing capability rejects during prepare before decode or handler`() {
        val registry = InMemoryStepRegistry()
        val sink = StepCapability("sink")
        registry.register(registryStep("test.needssink", required = setOf(sink)))
        handlerCalls = 0

        // Payload is decodable ("ok:...") but the declared capability is absent: admission must reject
        // BEFORE decode so the invocation can never run.
        val result = RegistryExecutionPreparation.prepare(
            registry,
            PluginStepId("test.needssink"),
            EncodedStepValue("ok:x"),
            emptySet(),
        )

        assertTrue(result is ExecutionPreparation.Rejected, "a missing declared capability must reject during prepare")
        assertEquals(0, handlerCalls, "a capability-rejected invocation must never reach the handler")
    }

    @Test
    fun `supplied capability admits a valid registry input to Ready`() {
        val registry = InMemoryStepRegistry()
        val sink = StepCapability("sink")
        registry.register(registryStep("test.needssink", required = setOf(sink)))
        handlerCalls = 0

        val result = RegistryExecutionPreparation.prepare(
            registry,
            PluginStepId("test.needssink"),
            EncodedStepValue("ok:with-cap"),
            setOf(sink),
        )

        assertTrue(result is ExecutionPreparation.Ready, "a supplied declared capability must admit to Ready")
        assertEquals(0, handlerCalls, "prepare must still not run the handler")
    }
}
