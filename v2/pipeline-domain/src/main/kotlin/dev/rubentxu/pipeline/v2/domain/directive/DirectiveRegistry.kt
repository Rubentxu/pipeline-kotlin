package dev.rubentxu.pipeline.v2.domain.directive

/**
 * S1.1 — Definition contract and the open registry.
 *
 * A [DirectiveDefinition] is the ONLY place a concrete directive's behaviour
 * lives. The engine holds definitions, never names: adding a directive is a new
 * implementation of this interface contributed through [DirectiveContributor],
 * and requires zero engine change. That is the S1 exit criterion, "open by key".
 */

/**
 * A registered directive, with its own typed input and output.
 *
 * The type parameters exist so a definition owns the shape of its own arguments.
 * The kernel erases them to [DirectiveDefinition.Any] at the boundary, exactly as
 * the Step registry erases a plugin contract to an untyped handler. Erasure is
 * explicit and named rather than smuggled in as `Any?` at a call site.
 */
interface DirectiveDefinition<I, O> {
    /** The key callers resolve. Unique across the whole composition. */
    val key: DirectiveKey

    /** The structural phase at which this directive is considered. */
    val phase: DirectivePhase

    /** The closed structural policy the engine interprets. */
    val policy: DirectiveExecutionPolicy

    /**
     * Decode this invocation's arguments into the typed input.
     *
     * Returns a typed failure instead of throwing, so a malformed directive is a
     * value in the program rather than an exception at the boundary. Adapters
     * validate external input; the kernel only routes it.
     */
    fun decode(encodedArguments: String): DirectiveDecodeResult<I>
}

/**
 * Decoding outcome, mirroring the "typed errors are values" rule.
 *
 * `Malformed` is deliberately distinct from a domain rejection: a definition that
 * cannot read its own arguments is a programming error, not an expected
 * operational outcome, and the engine reports the two differently.
 */
sealed interface DirectiveDecodeResult<out I> {
    data class Decoded<out I>(val input: I) : DirectiveDecodeResult<I>

    /** The arguments did not match this definition's shape. */
    data class Malformed(val reason: String) : DirectiveDecodeResult<Nothing>
}

/**
 * Type-erased view of a [DirectiveDefinition], for registry storage.
 *
 * This is the single lawful place the generic parameter is discarded. It keeps
 * the engine's dependency on a closed contract rather than on a caller-chosen
 * `Any?`, so a coordinator cannot smuggle an untyped payload past admission.
 */
interface DirectiveDefinitionAny {
    val key: DirectiveKey
    val phase: DirectivePhase
    val policy: DirectiveExecutionPolicy

    /**
     * S2-C: decode this definition's arguments through its OWN codec, with the
     * type parameter erased at the registry boundary. This is the lawful
     * erasure point for decoding, mirroring the one for policy metadata: the
     * engine never learns a concrete key or payload type, and a gate definition
     * contributed by any plugin carries its own decoder with it.
     *
     * Returns [DirectiveDecodeResult.Malformed] as a typed value; the engine
     * fails closed on it without exception-based control flow.
     */
    fun decodeAny(encodedArguments: String): DirectiveDecodeResult<Any>
}

/** Lift a typed definition into the type-erased registry view. */
class ErasedDirectiveDefinition<I, O>(
    private val delegate: DirectiveDefinition<I, O>,
) : DirectiveDefinitionAny {
    override val key: DirectiveKey get() = delegate.key
    override val phase: DirectivePhase get() = delegate.phase
    override val policy: DirectiveExecutionPolicy get() = delegate.policy

    @Suppress("UNCHECKED_CAST")
    override fun decodeAny(encodedArguments: String): DirectiveDecodeResult<Any> =
        delegate.decode(encodedArguments) as DirectiveDecodeResult<Any>
}

/**
 * Composition SPI: the open-world seam.
 *
 * An external directive plugin contributes definitions through this interface
 * and is discovered by whatever mechanism the host uses. The kernel depends on
 * this SPI, never on any concrete plugin.
 */
interface DirectiveContributor {
    fun definitions(): List<DirectiveDefinitionAny>
}

/**
 * Fail-closed registry keyed by [DirectiveKey].
 *
 * Duplicate keys are REJECTED, never resolved first-wins or last-wins: a
 * shadowed definition is a silent semantic change, which is the defect class the
 * Semantic Honesty Gate exists to remove. The rejection names both the key and
 * the contributors involved so the collision is diagnosable.
 */
class DirectiveRegistry private constructor(
    private val byKey: Map<DirectiveKey, DirectiveDefinitionAny>,
) {
    fun find(key: DirectiveKey): DirectiveDefinitionAny? = byKey[key]

    /**
     * Resolve a key, or return a typed rejection.
     *
     * Callers MUST treat [DirectiveAdmission.Rejected] as fatal for that stage:
     * an unresolved directive is never "skipped", because skipping would turn an
     * unknown name into a silent no-op.
     */
    fun admit(invocation: DirectiveInvocation): DirectiveAdmission =
        when (val found = byKey[invocation.key]) {
            null -> DirectiveAdmission.Rejected(
                "unresolved directive '${invocation.key.value}': no definition registered",
            )

            else -> DirectiveAdmission.Admitted(found.policy)
        }

    /** Every registered key, for diagnostics and fitness assertions. */
    fun keys(): Set<DirectiveKey> = byKey.keys

    class DuplicateKeyException(message: String) : IllegalStateException(message)

    class Builder {
        private val byKey = LinkedHashMap<DirectiveKey, DirectiveDefinitionAny>()

        fun add(definition: DirectiveDefinitionAny): Builder = apply {
            val existing = byKey[definition.key]
            if (existing != null) {
                // Thrown explicitly, NOT via `require`: a duplicate key is a
                // composition collision, not an invalid argument, and the caller
                // must be able to catch it as such. `require` would surface an
                // IllegalArgumentException and blur the two failures.
                throw DuplicateKeyException(
                    "duplicate DirectiveKey '${definition.key.value}': a definition is " +
                        "already registered for it; refusing to shadow",
                )
            }
            byKey[definition.key] = definition
        }

        fun addAll(definitions: Iterable<DirectiveDefinitionAny>): Builder =
            apply { definitions.forEach(::add) }

        /**
         * Fold every contributor into the registry.
         *
         * Composition, not branching: a new directive enters by adding a
         * contributor, never by adding a case to the engine.
         */
        fun addContributors(vararg contributors: DirectiveContributor): Builder =
            apply { contributors.forEach { addAll(it.definitions()) } }

        fun build(): DirectiveRegistry = DirectiveRegistry(LinkedHashMap(byKey))
    }
}
