package dev.rubentxu.pipeline.v2.domain.step

import kotlinx.serialization.Serializable

/**
 * LFC-2E3-P / P2 — typed Step-output value piping.
 *
 * ## The problem
 *
 * A Step's typed output is committed durably, but a LATER Step cannot receive it as input. The
 * only workarounds available before this seam were all forbidden or impossible:
 *
 * - duplicate the data out of band — impossible when the producer's value is computed at runtime
 *   (test totals, digests, resolved paths);
 * - reach the coordinator/journal from a handler — forbidden by the capability-routed handler
 *   discipline (LB-02 / G3-A4.2);
 * - fabricate the value while constructing the DSL — forbidden ("no fake runtime values").
 *
 * ## The shape
 *
 * ```text
 * producer Step
 *   -> typed output
 *   -> durable output identity (declared name + type tag)
 *   -> StepOutputRef  (a DECLARATIVE reference, never a value)
 *   -> consumer Step input binding
 *   -> canonical runtime resolution through a declared capability
 * ```
 *
 * The DSL constructs a REFERENCE. The runtime resolves it from committed durable state. The value
 * never exists at construction time, so no fake runtime value is possible.
 *
 * ## Type tag
 *
 * [StepOutputRef.typeTag] must equal the producer's declared [StepOutputDeclaration.typeTag].
 * Kotlin's DSL cannot statically prove producer/consumer type agreement, so the contract is
 * enforced at resolution time and fails closed on mismatch. The tag is a plugin-owned string
 * (typically the output type's simple name), so the mechanism stays generic and carries no
 * knowledge of any particular Step.
 */

/**
 * What a producer publishes about its output.
 *
 * [name] is the durable identity a consumer binds to; it must be unique within a run.
 * [typeTag] is the producer-declared contract a consumer must match.
 */
@Serializable
data class StepOutputDeclaration(
    val name: String,
    val typeTag: String,
) {
    init {
        require(name.isNotBlank()) { "StepOutputDeclaration name must not be blank" }
        require(typeTag.isNotBlank()) { "StepOutputDeclaration typeTag must not be blank" }
    }
}

/**
 * A declarative reference to a producer Step's output.
 *
 * This is a VALUE the DSL can construct and pass around safely: it names an output and states the
 * type the consumer expects. It carries no runtime data, so constructing one can never fabricate
 * a value.
 */
@Serializable
data class StepOutputRef(
    val name: String,
    val typeTag: String,
) {
    init {
        require(name.isNotBlank()) { "StepOutputRef name must not be blank" }
        require(typeTag.isNotBlank()) { "StepOutputRef typeTag must not be blank" }
    }

    /** True when [declaration] satisfies this reference's type contract. */
    fun accepts(declaration: StepOutputDeclaration): Boolean =
        declaration.name == name && declaration.typeTag == typeTag
}

// ─────────────────────────────────────────────────────────────────────────────
// Typed failure taxonomy
// ─────────────────────────────────────────────────────────────────────────────

sealed interface StepOutputResolutionError {
    /** No Step in this run declared that output name. */
    data class UnknownOutput(val name: String) : StepOutputResolutionError

    /** The producer exists but has not committed its output yet (ordering violation). */
    data class NotYetProduced(val name: String) : StepOutputResolutionError

    /** The producer ran and did not produce a durable output. */
    data class ProducerProducedNoOutput(val name: String) : StepOutputResolutionError

    /** The producer's declared type tag does not match what the consumer expects. */
    data class TypeMismatch(
        val name: String,
        val expected: String,
        val actual: String,
    ) : StepOutputResolutionError

    /** The producer's committed output could not be read. */
    data class Unreadable(val name: String, val reason: String) : StepOutputResolutionError
}

class StepOutputResolutionException(val reason: StepOutputResolutionError) :
    RuntimeException("step-output: ${reason::class.simpleName}: ${reason.describe()}")

private fun StepOutputResolutionError.describe(): String = when (this) {
    is StepOutputResolutionError.UnknownOutput -> "no Step declared output '$name' in this run"
    is StepOutputResolutionError.NotYetProduced ->
        "output '$name' has not been committed yet (the consumer ran before its producer)"
    is StepOutputResolutionError.ProducerProducedNoOutput ->
        "the producer of '$name' committed no durable output"
    is StepOutputResolutionError.TypeMismatch ->
        "output '$name' is declared as '$actual' but the consumer expects '$expected'"
    is StepOutputResolutionError.Unreadable -> "output '$name' is unreadable: $reason"
}

// ─────────────────────────────────────────────────────────────────────────────
// Canonical resolution port
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Canonical resolution port for a producer Step's committed output.
 *
 * Supplied by the runtime as a declared capability ([STEP_OUTPUT_RESOLVER_CAPABILITY]), never
 * looked up ambiently. A consumer declares the capability in its `StepContract`, receives this
 * port, and decodes the encoded output with its OWN codec — so the typed value stays a plugin
 * concern and no `Any`/`Map<String, Any>` crosses the seam.
 *
 * Resolution reads COMMITTED durable state (the operation journal), which is the single authority.
 * It therefore:
 * - cannot observe an output before its producer has committed it ([NotYetProduced]);
 * - reuses the committed output on replay rather than recomputing anything;
 * - fails closed on an unknown name or a type-tag mismatch.
 *
 * The port deliberately exposes ENCODED output. Returning a typed value would require an erased
 * cast at the seam, which is exactly the type-erasing escape hatch the strict-typing rules forbid.
 */
interface StepOutputResolver {
    /**
     * Resolve [ref] to the producer's committed encoded output.
     *
     * @throws StepOutputResolutionException for every case in [StepOutputResolutionError].
     */
    @Throws(StepOutputResolutionException::class)
    fun resolveEncoded(ref: StepOutputRef): EncodedStepValue

    /** True when [ref] is resolvable right now. Lets a consumer probe without exception control. */
    fun isResolvable(ref: StepOutputRef): Boolean
}

/**
 * SDK-owned capability token for [StepOutputResolver].
 *
 * The token lives in the public SDK (not in the runtime) so a plugin can DECLARE the requirement
 * without importing runtime internals, and so admission can reject a consumer fail-closed before
 * its handler runs when the runtime supplies no resolver.
 */
val STEP_OUTPUT_RESOLVER_CAPABILITY: StepCapability = StepCapability("step.output.resolver")
