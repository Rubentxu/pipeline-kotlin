package dev.rubentxu.pipeline.v2.domain.directive

/**
 * S1.1 — Directive Kernel v1, identity and invocation types.
 *
 * The kernel is OPEN BY KEY and CLOSED BY STRUCTURE (S1 exit criterion). Open by
 * key means the engine resolves a [DirectiveKey] through a registry, so a new
 * directive needs no engine change. Closed by structure means the engine matches
 * a finite structural ADT ([DirectiveExecutionPolicy]) and never branches on a
 * concrete key, mirroring the Step Constitution: variation is read from the
 * declaration, never from the name.
 *
 * This is an inner domain contract. It MUST NOT depend on infrastructure,
 * persistence, process execution, the compiler, or any adapter. Everything here
 * is a pure value: no clocks, no filesystem, no ambient state.
 *
 * No reference implementation yet: S1 introduces the architecture only, and S2
 * adds the first real `when`. An empty kernel with a proven fitness gate is the
 * deliverable, not a speculative vendor directive.
 */

/**
 * Stable identity of a directive, in open-world form.
 *
 * The name is namespaced so an external contributor cannot collide with a core
 * one, and so a duplicate is a legible error rather than a silent shadow. This
 * is deliberately NOT an enum: an enum would close the world at compile time,
 * which is exactly what the "open by key" half of the exit criterion forbids.
 */
@JvmInline
value class DirectiveKey(val value: String) {
    init {
        require(value.isNotBlank()) { "DirectiveKey must not be blank" }
    }
}

/**
 * The structural lifecycle phase at which a directive is considered.
 *
 * This ADT is the "closed by structure" half of the contract. The engine
 * exhaustively matches this sealed hierarchy; it MUST NOT hold a set of known
 * directive names to decide what runs when. Adding a vendor directive means
 * adding a [DirectiveDefinition] that selects one of these cases, never adding a
 * case here.
 */
enum class DirectivePhase {
    /** Before the stage body runs (e.g. an admission guard). */
    BEFORE_STAGE,

    /** While the stage body runs, observing it (e.g. a context provider). */
    DURING_STAGE,

    /** After the stage body completes, still able to veto its outcome. */
    AFTER_STAGE,
}

/**
 * A directive instance as it appears in a compiled program.
 *
 * Carries the key plus its already-typed, already-encoded arguments. The
 * arguments are an opaque encoded payload rather than `Any?` on purpose: the
 * kernel routes by key and hands decoding to the definition, so the kernel never
 * needs to know a directive's argument shape. Encoding lives with the
 * definition, which owns its codec (Step Constitution rule: the compiler MUST
 * NOT know how to build a plugin's input).
 */
data class DirectiveInvocation(
    val key: DirectiveKey,
    val encodedArguments: String,
)

/**
 * How a definition wants its directives executed, as a closed structural policy.
 *
 * This replaces the two common failure modes with a single explicit value:
 * - a `Boolean`/nullable pair that permits incoherent combinations, and
 * - an engine that inspects the key string to decide ordering or short-circuit
 * behaviour.
 *
 * A coordinator MUST NOT both select a policy and apply it. It reads this value
 * and interprets it at the effect boundary; the decision itself is pure.
 */
sealed interface DirectiveExecutionPolicy {
    /**
     * Evaluate and continue. The directive observes but cannot veto.
     */
    data object Evaluate : DirectiveExecutionPolicy

    /**
     * Evaluate and stop the stage on a defined outcome.
     *
     * Carrying the predicate name as data keeps the decision declarative: the
     * interpreter resolves it through the definition's registry, it does not
     * hardcode any concrete predicate.
     */
    data class Gate(val predicate: String) : DirectiveExecutionPolicy

    /**
     * The directive contributes context to the stage body but does not observe
     * or veto the outcome.
     */
    data object ProvideContext : DirectiveExecutionPolicy
}

/**
 * Typed admission verdict for one [DirectiveInvocation].
 *
 * A directive is admitted by the generic engine and interpreted by its own
 * definition. Keeping the two outcomes distinct is what lets an external
 * contributor add a directive without the engine learning a name: the engine
 * only ever sees [DirectiveAdmission].
 */
sealed interface DirectiveAdmission {
    /** Admitted, with the policy the definition declared. */
    data class Admitted(val policy: DirectiveExecutionPolicy) : DirectiveAdmission

    /**
     * Rejected before any effect runs.
     *
     * Fail-closed by construction: the engine cannot proceed past an
     * unresolved key, and [reason] is the diagnostic the operator sees.
     */
    data class Rejected(val reason: String) : DirectiveAdmission
}
