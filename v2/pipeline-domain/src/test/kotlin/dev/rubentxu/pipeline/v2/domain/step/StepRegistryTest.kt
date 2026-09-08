package dev.rubentxu.pipeline.v2.domain.step

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for the B1.1/B1.2a open Step seam contract types (ADR-0070): typed codec,
 * open registry with deterministic duplicate rejection, the narrow runtime-context
 * seam, and the generic invoker failing closed before the handler on unknown step /
 * decode failure / missing capability.
 */
class StepRegistryTest {

    private data class TInput(val text: String)
    private data class TOutput(val length: Int)

    private val key = PluginStepId("core.echo")
    private val runId = RunId("run-1")

    private val codec = object : StepCodec<TInput> {
        override fun encode(value: TInput): EncodedStepValue =
            EncodedStepValue("in:" + value.text)

        override fun decode(encoded: EncodedStepValue): TInput =
            if (encoded.value.startsWith("in:")) {
                TInput(encoded.value.removePrefix("in:"))
            } else {
                throw IllegalArgumentException("bad input: ${encoded.value}")
            }
    }

    private val descriptor = StepDescriptor("core.echo", "echo", "")

    private val outCodec = object : StepCodec<TOutput> {
        override fun encode(value: TOutput): EncodedStepValue =
            EncodedStepValue("out:" + value.length)

        override fun decode(encoded: EncodedStepValue): TOutput =
            if (encoded.value.startsWith("out:")) {
                TOutput(encoded.value.removePrefix("out:").toInt())
            } else {
                throw IllegalArgumentException("bad output: ${encoded.value}")
            }
    }

    /** Narrow in-memory capability access for tests. */
    private class MapAccess(private val map: Map<StepCapability, Any>) : StepCapabilityAccess {
        override fun available(): Set<StepCapability> = map.keys

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> get(key: StepCapability): T =
            map[key] as? T ?: throw IllegalArgumentException("capability unavailable: $key")
    }

    private fun context(
        index: Int = 0,
        access: StepCapabilityAccess = MapAccess(emptyMap()),
    ) = StepHandlerContext(runId = runId, stepIndex = index, capabilities = access)

    private fun definition(
        id: PluginStepId = key,
        capabilities: Set<StepCapability> = emptySet(),
    ): StepDefinition<TInput, TOutput> {
        val contract = StepContract(id, descriptor, codec, outCodec, capabilities)
        return object : StepDefinition<TInput, TOutput> {
            override val contract: StepContract<TInput, TOutput> = contract
            override val handler: StepHandler<TInput, TOutput> =
                StepHandler { input, _ -> TOutput(input.text.length) }
        }
    }

    @Test
    fun `registry contains and lists a registered step`() {
        val registry = InMemoryStepRegistry()
        registry.register(definition())
        assertTrue(registry.contains(key))
        assertEquals(setOf(key), registry.keys())
    }

    @Test
    fun `duplicate step key fails deterministically`() {
        val registry = InMemoryStepRegistry()
        registry.register(definition())
        assertThrows(IllegalArgumentException::class.java) {
            registry.register(definition())
        }
    }

    @Test
    fun `invoke returns typed output on success`() {
        val registry = InMemoryStepRegistry().apply { register(definition()) }
        val invoker = RegistryStepInvoker(registry)
        val outcome = invoker.invoke<TInput, TOutput>(key, codec.encode(TInput("hola")), context())
        assertTrue(outcome is StepInvocationOutcome.Success)
        assertEquals(4, (outcome as StepInvocationOutcome.Success).value.length)
    }

    @Test
    fun `handler receives the narrow execution context`() {
        var seenRunId: RunId? = null
        var seenIndex = -1
        val contract = StepContract(key, descriptor, codec, outCodec)
        val definition = object : StepDefinition<TInput, TOutput> {
            override val contract: StepContract<TInput, TOutput> = contract
            override val handler: StepHandler<TInput, TOutput> = StepHandler { input, ctx ->
                seenRunId = ctx.runId
                seenIndex = ctx.stepIndex
                TOutput(input.text.length)
            }
        }
        val registry = InMemoryStepRegistry().apply { register(definition) }
        RegistryStepInvoker(registry).invoke<TInput, TOutput>(key, codec.encode(TInput("x")), context(index = 7))
        assertEquals(runId, seenRunId)
        assertEquals(7, seenIndex)
    }

    @Test
    fun `invoke unknown step returns UnknownStep`() {
        val registry = InMemoryStepRegistry().apply { register(definition()) }
        val invoker = RegistryStepInvoker(registry)
        val outcome = invoker.invoke<TInput, TOutput>(
            PluginStepId("acme.unknown"),
            codec.encode(TInput("x")),
            context(),
        )
        assertTrue(outcome is StepInvocationOutcome.UnknownStep)
    }

    @Test
    fun `missing capability fails before handler runs`() {
        val required = setOf(StepCapability("process"))
        val registry = InMemoryStepRegistry().apply { register(definition(capabilities = required)) }
        val invoker = RegistryStepInvoker(registry)
        val outcome = invoker.invoke<TInput, TOutput>(key, codec.encode(TInput("x")), context())
        assertTrue(outcome is StepInvocationOutcome.MissingCapability)
        assertEquals(required, (outcome as StepInvocationOutcome.MissingCapability).missing)
    }

    @Test
    fun `handler can resolve a declared available capability`() {
        val processCap = StepCapability("process")
        val required = setOf(processCap)
        var resolved: String? = null
        val contract = StepContract(key, descriptor, codec, outCodec, required)
        val definition = object : StepDefinition<TInput, TOutput> {
            override val contract: StepContract<TInput, TOutput> = contract
            override val handler: StepHandler<TInput, TOutput> = StepHandler { _, ctx ->
                resolved = ctx.capabilities.get<String>(processCap)
                TOutput(0)
            }
        }
        val registry = InMemoryStepRegistry().apply { register(definition) }
        val invoker = RegistryStepInvoker(registry)
        val outcome = invoker.invoke<TInput, TOutput>(
            key,
            codec.encode(TInput("x")),
            context(access = MapAccess(mapOf(processCap to "proc-token"))),
        )
        assertTrue(outcome is StepInvocationOutcome.Success)
        assertEquals("proc-token", resolved)
    }

    @Test
    fun `decode failure returns DecodeFailure and does not run handler`() {
        var handlerCalls = 0
        val contract = StepContract(key, descriptor, codec, outCodec)
        val definition = object : StepDefinition<TInput, TOutput> {
            override val contract: StepContract<TInput, TOutput> = contract
            override val handler: StepHandler<TInput, TOutput> = StepHandler { _, _ ->
                handlerCalls++
                TOutput(0)
            }
        }
        val registry = InMemoryStepRegistry().apply { register(definition) }
        val invoker = RegistryStepInvoker(registry)
        val outcome = invoker.invoke<TInput, TOutput>(
            key,
            EncodedStepValue("not-an-input"),
            context(),
        )
        assertTrue(outcome is StepInvocationOutcome.DecodeFailure)
        assertTrue(handlerCalls == 0, "handler must not run on decode failure")
        assertFalse(outcome is StepInvocationOutcome.Success<*>)
    }
}
