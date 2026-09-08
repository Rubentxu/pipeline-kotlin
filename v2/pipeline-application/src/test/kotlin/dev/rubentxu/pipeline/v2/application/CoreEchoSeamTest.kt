package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.RegistryStepInvoker
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.domain.step.StepInvocationOutcome
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * B1.2b: `core.echo` registered as an open [CoreEchoStep] StepDefinition through the generic
 * B1.1 seam, demonstrated in isolation (no CanonicalNodeDispatcher / coordinator change).
 */
@Timeout(10)
class CoreEchoSeamTest {

    private class MapAccess(private val map: Map<StepCapability, Any>) : StepCapabilityAccess {
        override fun available(): Set<StepCapability> = map.keys

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> get(key: StepCapability): T =
            map[key] as? T ?: throw IllegalArgumentException("capability unavailable: $key")
    }

    private fun registry(): InMemoryStepRegistry = InMemoryStepRegistry().apply { CoreEchoStep.registerInto(this) }

    @Test
    fun `registry resolves and contains core echo`() {
        val registry = registry()
        assertTrue(registry.contains(CoreEchoStep.KEY))
        assertEquals(CoreEchoStep.KEY, registry.definition(CoreEchoStep.KEY)?.contract?.key)
    }

    @Test
    fun `duplicate registration of core echo fails deterministically`() {
        val registry = registry()
        assertThrows(IllegalArgumentException::class.java) {
            CoreEchoStep.registerInto(registry)
        }
    }

    @Test
    fun `echo input codec round-trips`() {
        val codec = CoreEchoStep.definition.contract.inputCodec
        assertEquals(EchoInput("hola mundo"), codec.decode(codec.encode(EchoInput("hola mundo"))))
    }

    @Test
    fun `echo input codec emits the byte-identical durable dsl-v1 envelope the compiler produces`() {
        // B1.2c3-slice1: the registry echo codec must emit the exact payload the compiler writes for
        // core.echo so a migrated registry-routed echo shares fingerprint/journal identity with the
        // legacy-routed echo. Also proves the payload is a well-formed JSON object (durable eligibility).
        val encoded = CoreEchoStep.definition.contract.inputCodec.encode(EchoInput("hola structural"))
        assertEquals("""{"kind":"echo","text":"hola structural"}""", encoded.value)
    }


    @Test
    fun `seam handler emits EchoOutputCaptured and returns the echo payload`() {
        val store = InMemoryEventStore()
        val runId = RunId("b1-2b-run")
        val registry = registry()
        val invoker = RegistryStepInvoker(registry)
        val context = StepHandlerContext(
            runId = runId,
            stepIndex = 3,
            capabilities = MapAccess(mapOf(EVENT_SINK_CAPABILITY to store)),
        )
        val encoded = CoreEchoStep.definition.contract.inputCodec.encode(EchoInput("hi canonical"))
        val outcome = invoker.invoke<EchoInput, String>(CoreEchoStep.KEY, encoded, context)

        assertTrue(outcome is StepInvocationOutcome.Success)
        assertEquals("hi canonical\n", (outcome as StepInvocationOutcome.Success).value)

        val captured = store.eventsFor(runId.value).single() as EchoOutputCaptured
        assertEquals("hi canonical\n", captured.content)
        assertEquals(3, captured.stepIndex)
    }

    @Test
    fun `missing event sink capability fails closed before the handler`() {
        val registry = registry()
        val invoker = RegistryStepInvoker(registry)
        val context = StepHandlerContext(
            runId = RunId("b1-2b-run"),
            stepIndex = 0,
            capabilities = MapAccess(emptyMap()),
        )
        val encoded = CoreEchoStep.definition.contract.inputCodec.encode(EchoInput("x"))
        val outcome = invoker.invoke<EchoInput, String>(CoreEchoStep.KEY, encoded, context)
        assertTrue(outcome is StepInvocationOutcome.MissingCapability)
    }

    @Test
    fun `unknown plugin step key is not resolvable in the core registry`() {
        val registry = registry()
        assertTrue(registry.definition(PluginStepId("acme.unknown")) == null)
        assertTrue(EncodedStepValue::class.isInstance(EncodedStepValue("x")))
    }
}
