package dev.rubentxu.pipeline.v2.domain

import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionOwner
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionPolicy

/**
 * The body a Step kind declares, as ONE coherent value (B10 / W1d).
 *
 * ## Why the independent fields collapsed into an ADT
 *
 * Until W1d a descriptor carried the body metadata as independent properties
 * (`takesBody`, `bodyInvocations`, `introducesContext`, `catchesInterruptions`,
 * `bodyExecutionPolicy`, `bodyExecutionOwner`). Every combination was constructible, so
 * the model could express states that have no meaning:
 *
 * ```text
 * takesBody = false + bodyExecutionPolicy = Scoped(Environment)      // now unrepresentable
 * takesBody = false + introducesContext   = CWD                      // now unrepresentable
 * takesBody = true  + owner/policy omitted -> implicit defaults      // now unrepresentable
 * ```
 *
 * The third line is what W1d was chartered to close: `bodyExecutionOwner` defaulted to
 * [BodyExecutionOwner.CANONICAL_ENGINE], so a new body Step that simply forgot to state
 * its ownership silently acquired canonical semantics without demonstrating them. A body
 * Step now has to SAY who executes its body and WHAT shape that body has, so there is no
 * spelling of "this Step takes a body and nobody owns it".
 *
 * ## What the ADT does and does not guarantee
 *
 * Represented here: presence of a body, its declared cardinality, its execution owner and
 * shape, the context kind it introduces, and its interruption policy.
 *
 * Still a cross-field CHECK (not a type): [Declared.invocation] against the shape of
 * [BodyExecution.policy], and [Declared.introduces] against the projection of a
 * [BodyExecutionPolicy.Scoped] shape. Those two invariants relate values that are
 * legitimately independent — `core.catchError` is `Sequential` yet introduces
 * `CANCELLATION`, and `core.warnError` is `Sequential` yet is `AT_MOST_ONCE` — so they are
 * enforced by [dev.rubentxu.pipeline.v2.domain.step.resolveBodyExecutionPolicy], which
 * rejects an incoherent row as
 * [dev.rubentxu.pipeline.v2.domain.step.BodyPolicyRejection.IncoherentMetadata] before any
 * effect, rather than by the type.
 */
sealed interface StepBody {

    /**
     * The Step has no body: it is a terminal Step. It cannot carry cardinality, owner,
     * shape, context kind, or interruption metadata, because there is no field to carry
     * them on.
     */
    data object None : StepBody

    /**
     * The Step takes a body. [invocation] and [execution] are REQUIRED and carry no
     * default: every body row states its own cardinality, owner and shape explicitly.
     */
    data class Declared(
        /** How many times the body children may be invoked. */
        val invocation: BodyInvocationPolicy,
        /** Which engine executes the body, and the shape it executes. */
        val execution: BodyExecution,
        /**
         * The [ContextKind] the body introduces, or `null` when it introduces none
         * (`core.timestamps`, `core.retry`). A real "no kind" fact, not a sentinel.
         */
        val introduces: ContextKind? = null,
        /** Whether the Step's semantics contain an interruption of its body. */
        val catchesInterruptions: Boolean = false,
    ) : StepBody

    /**
     * The declaration, or `null` when this Step is terminal.
     *
     * `null` here is the fact "this Step has no body", exactly as
     * [dev.rubentxu.pipeline.v2.domain.step.BodyContextProjection.requiredContextKind] uses
     * `null` for "this projection has no corresponding context kind". It is not a sentinel
     * and never a default: callers that need the declaration branch on the closed ADT, and
     * the policy resolver turns the absent case into a typed rejection.
     */
    val declared: Declared?
        get() = this as? Declared
}

/**
 * WHO executes a Step's body and WHAT shape that body has, as one value (B10 / W1d).
 *
 * Both fields are required. The owner is not derivable from the shape: `core.catchError`
 * and a plain `core.dir` body can both be `Sequential`, yet only one of them is executed by
 * the canonical body engine (see [BodyExecutionOwner]).
 */
data class BodyExecution(
    /** The engine that re-enters and executes this body. */
    val owner: BodyExecutionOwner,
    /** The declared shape of the body. */
    val policy: BodyExecutionPolicy,
)
