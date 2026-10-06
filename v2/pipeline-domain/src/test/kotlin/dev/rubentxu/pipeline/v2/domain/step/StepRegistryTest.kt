package dev.rubentxu.pipeline.v2.domain.step

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import kotlinx.coroutines.runBlocking
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
        val registry = StepRegistryBuilder().apply { add(definition()) }.build()
        assertTrue(registry.contains(key))
        assertEquals(setOf(key), registry.keys())
    }

    @Test
    fun `duplicate step key fails deterministically`() {
        val builder = StepRegistryBuilder()
        builder.add(definition())
        assertThrows(IllegalArgumentException::class.java) {
            builder.add(definition())
        }
    }

    // ---------------------------------------------------------------------------------------
    // S6/F — the laws the Builder/immutable split introduces.
    //
    // Each row below exists because the PREVIOUS shape made it untrue. The registry used to
    // carry `register`, so "read-only after registration" was a comment; now the type enforces
    // it. These rows are the enforcement, and each carries the mutation that would kill it.
    // ---------------------------------------------------------------------------------------

    /**
     * MUTATION THAT KILLS THIS: `build()` implemented as `FrozenStepRegistry(entries)` — passing
     * the builder's live map instead of a copy. The builder then keeps growing, and a registry
     * already handed to the runtime silently gains Steps mid-run.
     */
    @Test
    fun `build snapshots - adding to the builder afterwards does not reach the built registry`() {
        val builder = StepRegistryBuilder()
        builder.add(definition(key))
        val registry = builder.build()

        val late = PluginStepId("acme.late")
        builder.add(definition(late))

        assertEquals(setOf(key), registry.keys(), "the built registry must not observe later additions")
        assertFalse(registry.contains(late), "a Step added after build() must not appear in the frozen registry")
        assertTrue(registry.contains(key), "the Step present at build() must still be there")
    }

    /**
     * MUTATION THAT KILLS THIS: `keys()` implemented as `entries.keys` without
     * `Collections.unmodifiableSet`. A caller could then cast the returned Set to MutableSet and
     * add or remove Steps through the read side, which is exactly the post-hoc mutation the
     * split exists to remove.
     */
    @Test
    fun `keys is an unmodifiable view - a reader cannot mutate the registry through it`() {
        val registry = StepRegistryBuilder().apply { add(definition()) }.build()

        val keys = registry.keys()
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (keys as MutableSet<PluginStepId>).add(PluginStepId("acme.injected"))
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (keys as MutableSet<PluginStepId>).remove(key)
        }
        assertEquals(setOf(key), registry.keys(), "a refused mutation must leave the registry untouched")
    }

    /**
     * MUTATION THAT KILLS THIS: re-adding `fun register(...)` to the `StepRegistry` interface.
     * Every row above would still pass — the builder, the copy and the unmodifiable view are all
     * unchanged — while the runtime regained a mutation path. This row is the one that notices.
     */
    @Test
    fun `StepRegistry exposes no registration method - composition and reading are separate capabilities`() {
        // Match on the Kotlin name and on the exact mutation verbs, so a read whose name
        // happens to start with "register" is not mistaken for a registration path.
        val mutationMethods = StepRegistry::class.java.declaredMethods.map { it.name.substringBefore('-') }
            .filter { n ->
                n == "register" || n == "unregister" || n.startsWith("add") ||
                    n == "put" || n == "remove" || n == "clear"
            }

        assertTrue(
            mutationMethods.isEmpty(),
            "StepRegistry must not expose any registration method, but found: " +
                mutationMethods.joinToString(),
        )

        // And the contract is only the four reads. Pinning the surface stops a future addition
        // from arriving quietly under a name this assertion does not pattern-match.
        // Inline value classes mangle the JVM name of any parameter they appear in
        // (`contains-bQvmloc`, `definition-bQvmloc`), so compare on the Kotlin name.
        assertEquals(
            setOf("contains", "definition", "keys", "providerOf"),
            StepRegistry::class.java.declaredMethods.map { it.name.substringBefore('-') }.toSet(),
            "StepRegistry's read surface must stay exactly these four operations",
        )
    }

    @Test
    fun `invoke returns typed output on success`() = runBlocking {
        val registry = StepRegistryBuilder().apply { add(definition()) }.build()
        val invoker = RegistryStepInvoker(registry)
        val outcome = invoker.invoke<TInput, TOutput>(key, codec.encode(TInput("hola")), context())
        assertTrue(outcome is StepInvocationOutcome.Success)
        assertEquals(4, (outcome as StepInvocationOutcome.Success).value.length)
    }

    @Test
    fun `handler receives the narrow execution context`() = runBlocking {
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
        val registry = StepRegistryBuilder().apply { add(definition) }.build()
        RegistryStepInvoker(registry).invoke<TInput, TOutput>(key, codec.encode(TInput("x")), context(index = 7))
        assertEquals(runId, seenRunId)
        assertEquals(7, seenIndex)
    }

    @Test
    fun `invoke unknown step returns UnknownStep`() = runBlocking {
        val registry = StepRegistryBuilder().apply { add(definition()) }.build()
        val invoker = RegistryStepInvoker(registry)
        val outcome = invoker.invoke<TInput, TOutput>(
            PluginStepId("acme.unknown"),
            codec.encode(TInput("x")),
            context(),
        )
        assertTrue(outcome is StepInvocationOutcome.UnknownStep)
    }

    @Test
    fun `missing capability fails before handler runs`() = runBlocking {
        val required = setOf(StepCapability("process"))
        val registry = StepRegistryBuilder().apply { add(definition(capabilities = required)) }.build()
        val invoker = RegistryStepInvoker(registry)
        val outcome = invoker.invoke<TInput, TOutput>(key, codec.encode(TInput("x")), context())
        assertTrue(outcome is StepInvocationOutcome.MissingCapability)
        assertEquals(required, (outcome as StepInvocationOutcome.MissingCapability).missing)
    }

    @Test
    fun `handler can resolve a declared available capability`() = runBlocking {
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
        val registry = StepRegistryBuilder().apply { add(definition) }.build()
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
    fun `decode failure returns DecodeFailure and does not run handler`() = runBlocking {
        var handlerCalls = 0
        val contract = StepContract(key, descriptor, codec, outCodec)
        val definition = object : StepDefinition<TInput, TOutput> {
            override val contract: StepContract<TInput, TOutput> = contract
            override val handler: StepHandler<TInput, TOutput> = StepHandler { _, _ ->
                handlerCalls++
                TOutput(0)
            }
        }
        val registry = StepRegistryBuilder().apply { add(definition) }.build()
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
