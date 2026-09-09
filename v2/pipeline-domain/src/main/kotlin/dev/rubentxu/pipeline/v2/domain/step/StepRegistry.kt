package dev.rubentxu.pipeline.v2.domain.step

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor

/**
 * Canonical serialized form of a step input/output payload (JSON today).
 *
 * Carried across the registry seam so the engine and a plugin can exchange a
 * step payload without sharing a concrete Kotlin type. Reconciles the stringly
 * `StepDescriptor.inputSchema`/`outputSchema` fields with a typed transport form.
 */
@JvmInline
value class EncodedStepValue(val value: String) {
    init {
        require(value.isNotEmpty()) { "EncodedStepValue must not be empty" }
    }
}

/**
 * A capability a Step declares and the engine MUST supply before its handler runs.
 *
 * Reconciles the stringly `StepDescriptor.requiredCapabilities` list with a typed
 * capability token. A handler never runs when a declared capability is unavailable
 * (fail-closed admission, ADR-0070).
 */
@JvmInline
value class StepCapability(val key: String) {
    init {
        require(key.isNotBlank()) { "StepCapability key must not be blank" }
    }

    override fun toString(): String = key
}

/**
 * Typed access to the capabilities the engine supplied for one handler invocation.
 *
 * NOT an omnipotent context: a handler may request only a capability it declared in its
 * [StepContract.requiredCapabilities], and only through its own typed key.
 */
interface StepCapabilityAccess {
    /** Capabilities actually available to this invocation (for fail-closed admission). */
    fun available(): Set<StepCapability>

    /** Returns the typed value bound to [key]. Fails if [key] is not available. */
    fun <T : Any> get(key: StepCapability): T
}

/**
 * Narrow execution identity handed to a [StepHandler] for one invocation (B1.2a).
 *
 * Deliberately carries only execution identity plus explicit capability access. It is NOT a
 * [dev.rubentxu.pipeline.v2.domain.StepExecutionContext]-style catch-all and MUST NOT grow into an
 * omnipotent `PipelineContext`: a handler declares its minimal capabilities in its contract and
 * receives only those through [capabilities].
 */
data class StepHandlerContext(
    val runId: RunId,
    val stepIndex: Int,
    val capabilities: StepCapabilityAccess,
)

/**
 * Symmetric typed codec between a Step payload type and its encoded wire form.
 *
 * Implementations MAY use a serialization library; the seam only requires symmetry.
 */
interface StepCodec<T : Any> {
    fun encode(value: T): EncodedStepValue

    fun decode(encoded: EncodedStepValue): T

    /** JSON Schema fragment describing the payload. Reconciles descriptor input/output schema. */
    fun schema(): String = "{}"
}

/**
 * Static contract of one Step family: descriptor metadata plus typed input/output
 * codecs and the capabilities the handler requires.
 */
data class StepContract<I : Any, O : Any>(
    val key: PluginStepId,
    val descriptor: StepDescriptor,
    val inputCodec: StepCodec<I>,
    val outputCodec: StepCodec<O>,
    val requiredCapabilities: Set<StepCapability> = emptySet(),
)

/**
 * Typed handler adapter for one Step family.
 *
 * Receives the decoded input and a narrow [StepHandlerContext]. MUST NOT throw to signal a step
 * failure; it returns a typed result instead. Throwable exceptions signal an adapter/engine bug and
 * are treated as fail-closed upstream.
 *
 * The execute signature is `suspend` (LB-02 / G3-A4.2) so that handlers can reach suspend
 * capability seams (e.g. `ShellOperations.invoke` which delegates to the suspend
 * `ShExecution.invokeShell` substrate). The boundary (`CommonExecutionBoundary.coexecute`)
 * is suspend and runs the handler directly; pure-function handlers simply ignore the suspend
 * modifier.
 */
fun interface StepHandler<I : Any, O : Any> {
    suspend fun execute(input: I, context: StepHandlerContext): O
}

/**
 * A registered, executable Step family: a typed contract plus its typed handler.
 */
interface StepDefinition<I : Any, O : Any> {
    val contract: StepContract<I, O>
    val handler: StepHandler<I, O>
}

/**
 * Closed outcome algebra of a typed generic invocation through the seam.
 *
 * Distinct semantics, never a boolean-plus-null: a successful typed value, an unknown
 * Step, a decode failure, or a missing capability (rejected before the handler runs).
 */
sealed interface StepInvocationOutcome<out O : Any> {
    data class Success<out O : Any>(val value: O) : StepInvocationOutcome<O>
    data class UnknownStep(val key: PluginStepId) : StepInvocationOutcome<Nothing>
    data class DecodeFailure(val key: PluginStepId, val reason: String) : StepInvocationOutcome<Nothing>
    data class MissingCapability(val key: PluginStepId, val missing: Set<StepCapability>) : StepInvocationOutcome<Nothing>
}

/**
 * Open registry of Step families (ADR-0070).
 *
 * Registration is open to core Steps and external plugins alike; there is no privileged
 * registration path. A duplicate key MUST fail deterministically so a plugin cannot
 * silently shadow a core Step.
 */
interface StepRegistry {
    /** Registers a Step family. Throws [IllegalArgumentException] if the key is already present. */
    fun register(definition: StepDefinition<*, *>)

    /** Returns the registered definition for [key], or null. */
    fun definition(key: PluginStepId): StepDefinition<*, *>?

    fun contains(key: PluginStepId): Boolean

    fun keys(): Set<PluginStepId>
}

/** Default in-memory [StepRegistry] with deterministic duplicate-key rejection. */
class InMemoryStepRegistry : StepRegistry {
    private val definitions = linkedMapOf<PluginStepId, StepDefinition<*, *>>()

    override fun register(definition: StepDefinition<*, *>) {
        val key = definition.contract.key
        if (definitions.containsKey(key)) {
            throw IllegalArgumentException("Duplicate StepKey '${key.value}'")
        }
        definitions[key] = definition
    }

    override fun definition(key: PluginStepId): StepDefinition<*, *>? = definitions[key]

    override fun contains(key: PluginStepId): Boolean = definitions.containsKey(key)

    override fun keys(): Set<PluginStepId> = definitions.keys
}

/**
 * Generic canonical invocation seam (ADR-0070 / ADR-0073).
 *
 * Resolves [StepRegistry] → capability admission → typed decode → [StepHandler].
 * Unknown step, decode failure and missing capability all fail closed BEFORE the handler
 * runs, on every invocation path. This is the erased runtime adapter boundary: the engine
 * holds only an [EncodedStepValue]; the concrete payload type lives behind the codec.
 */
interface StepInvoker {
    suspend fun <I : Any, O : Any> invoke(
        key: PluginStepId,
        encodedInput: EncodedStepValue,
        context: StepHandlerContext,
    ): StepInvocationOutcome<O>
}

/** [StepInvoker] over an open [StepRegistry], erasing payload types at the seam. */
class RegistryStepInvoker(private val registry: StepRegistry) : StepInvoker {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <I : Any, O : Any> invoke(
        key: PluginStepId,
        encodedInput: EncodedStepValue,
        context: StepHandlerContext,
    ): StepInvocationOutcome<O> {
        val raw = registry.definition(key) ?: return StepInvocationOutcome.UnknownStep(key)

        // Erasure boundary: the payload type lives behind the codec, not in the engine.
        val definition = raw as StepDefinition<Any, Any>
        val contract = definition.contract

        val missing = contract.requiredCapabilities - context.capabilities.available()
        if (missing.isNotEmpty()) {
            return StepInvocationOutcome.MissingCapability(key, missing)
        }

        val input = try {
            contract.inputCodec.decode(encodedInput)
        } catch (e: Exception) {
            return StepInvocationOutcome.DecodeFailure(key, e.message ?: "decode failed")
        }

        val output = definition.handler.execute(input, context)
        return StepInvocationOutcome.Success(output as O)
    }
}

/**
 * Registers every definition from each [StepDefinitionContributor] into this registry, fail-closed on
 * a duplicate StepKey. Deterministic ordering is the caller's responsibility (iteration order of
 * [contributors]). On a duplicate the thrown diagnostic names BOTH the StepKey and the contributor id
 * (never first-wins/last-wins). Used by the runtime composition adapter (EP-F1).
 */
fun StepRegistry.registerContributors(contributors: Iterable<StepDefinitionContributor>) {
    for (contributor in contributors) {
        for (definition in contributor.definitions()) {
            val key = definition.contract.key
            try {
                register(definition)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException(
                    "Duplicate StepKey '${key.value}' contributed by '${contributor.id}': ${e.message}",
                    e,
                )
            }
        }
    }
}
