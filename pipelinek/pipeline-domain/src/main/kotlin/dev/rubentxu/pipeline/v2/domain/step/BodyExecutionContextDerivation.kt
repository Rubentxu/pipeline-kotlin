package dev.rubentxu.pipeline.v2.domain.step

import dev.rubentxu.pipeline.v2.domain.ContextOverlay
import dev.rubentxu.pipeline.v2.domain.EnvironmentSpec
import dev.rubentxu.pipeline.v2.domain.ExecutionContext
import kotlinx.serialization.Serializable

/**
 * Closed ADT of non-context-dimension body decorations propagated through [BodyInvoker]
 * (B11 / W1d-W2; concretizes ADR-0081 D4).
 *
 * Why this exists alongside [BodyContextProjection]:
 *
 * - [BodyContextProjection] names the **declaration**: the static shape of a block Step's
 *   body. `core.timestamps` declares [BodyContextProjection.Timestamps] because that is
 *   the *kind* of projection its body has.
 * - [BodyDecorator] names the **runtime fact**: whether the body being invoked
 *   through [BodyInvoker] is currently INSIDE an active scope whose declaration is a
 *   non-context projection. `timestamps` has no [dev.rubentxu.pipeline.v2.domain.ContextKind]
 *   and therefore pushes NO frame on [ExecutionContext]; the runtime must still carry the
 *   decorator state forward so children know they are inside the decorated scope.
 *
 * The split is deliberate: inventing a fictitious [dev.rubentxu.pipeline.v2.domain.ContextKind]
 * for timestamps would make the domain carry a kind it doesn't actually have (W1c
 * already established that the closed ContextKind family does NOT contain one for
 * timestamps; see [BodyContextProjection.Timestamps.requiredContextKind]). A decorator
 * preserves the architectural property that context dimensions and projection shapes
 * are distinct, both closed, neither bleeding into the other.
 *
 * [BodyDecorator.None] is the default for every body invocation that is not inside a
 * decorator scope, including sequential, retrying and credential-leased bodies; the
 * default keeps the typical call site unchanged.
 */
@Serializable
sealed interface BodyDecorator {
    /** No decorator active. The body's projection pushes zero non-context frames. */
    data object None : BodyDecorator

    /**
     * The body is inside an active `core.timestamps` scope (or any future block Step
     * that declares [BodyContextProjection.Timestamps]).
     *
     * Carries no payload: a timestamp source is not a typed value; the runtime
     * interpretation is structural. Children see this decorator via the
     * [BodyInvocationContext] they receive, never via an injected value.
     */
    data object Timestamps : BodyDecorator
}

/**
 * Closed ADT of runtime payloads that can be projected through a [BodyContextProjection]
 * (B11 / W1d-W2).
 *
 * Each variant carries ONLY the payload meaningful for its projection; a projection
 * with no payload shares [None]. Variants are matched against projections in
 * [deriveChildExecutionContext] and NEVER silently merged — passing a [DirectoryValue]
 * to an [BodyContextProjection.Environment] is a typed rejection, not a fallback.
 *
 * Pairs with [BodyDecorator]: the projection carries the SHAPE, the value carries the
 * PAYLOAD, the decorator carries the RUNTIME FACT. Together they exhaust the
 * information the pure context derivation needs from a caller.
 */
@Serializable
sealed interface BodyRuntimeValue {
    /** No payload: the projection either has no runtime value (timestamps) or one was not supplied. */
    data object None : BodyRuntimeValue

    /** A single directory path; the projection's normalization/escape checks live in the caller. */
    data class DirectoryValue(val path: String) : BodyRuntimeValue

    /** Environment overrides; keys are literal strings, values are literal strings. */
    data class EnvironmentValue(val values: Map<String, String>) : BodyRuntimeValue
}

/**
 * Closed ADT of typed reasons [deriveChildExecutionContext] rejects a derivation.
 *
 * Distinct cases have distinct remedies (supply a value, fix a path, route through
 * another seam) and are never collapsed into a string or a single optional value.
 * Mis-routing is a programmer defect at the seam boundary, so the rejection is a
 * value, not an exception.
 */
sealed interface BodyContextRejection {
    /** A [BodyContextProjection] that requires a runtime payload received [BodyRuntimeValue.None]. */
    data class MissingRuntimeValue(val projection: BodyContextProjection) : BodyContextRejection

    /** The supplied payload failed a basic validity check (blank path, wrong shape for the projection). */
    data class InvalidRuntimeValue(val projection: BodyContextProjection, val detail: String) :
        BodyContextRejection

    /**
     * The projection's context carry intentionally lives in another seam: deadlines
     * are projected onto [dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions.timeoutMs]
     * and credential leases are projected by the [executeCredentialLeasedBody]
     * preamble. The seam is honest about what it does not do.
     */
    data class HandledOutsideOverlay(val projection: BodyContextProjection, val at: String) :
        BodyContextRejection
}

/**
 * Typed result of [deriveChildExecutionContext] (B11 / W1d-W2; concretizes ADR-0081 D4).
 *
 * - [Derived] carries the new immutable [ExecutionContext]; the parent is untouched
 *   (CTX-P law).
 * - [Rejected] carries the typed reason; never a stringly-typed message.
 *
 * Two cases; not a transition. A transition implies a state machine, this is a
 * single-step derivation result: parent + projection + payload -> next-context-or-reject.
 */
sealed interface BodyContextDerivation {
    data class Derived(val context: ExecutionContext) : BodyContextDerivation
    data class Rejected(val reason: BodyContextRejection) : BodyContextDerivation
}

/**
 * Pure derivation of a child [ExecutionContext] from a parent and a declared projection.
 *
 * The PURE FORM of the body-machine context step (B11 / W1d-W2; concretizes ADR-0081 D4
 * and CTX-P's "the seam is honest about what it does not do").
 *
 * ## Law
 *
 * - **Total over the closed projection family.** Every [BodyContextProjection] case
 *   has exactly one branch; there is no `else`. Adding a projection case forces this
 *   function to be visited at compile time and motivates the test row that asserts
 *   exhaustivity.
 * - **Pure.** No I/O, no events, no clocks, no registry reads, no global state, no
 *   mutation of any input. The result depends only on (parent, projection, runtime).
 * - **StepKey-blind.** The function does not name or read any
 *   [dev.rubentxu.pipeline.v2.domain.PluginStepId]. Two block Steps that declare the
 *   SAME projection with the SAME payload obtain the SAME derived context — that is
 *   the test row that turns "no concrete-Step switch" from a slogan into an invariant.
 * - **Parent-preserving.** [ExecutionContext] is immutable; derivation never mutates
 *   `parent.overlays` in place. The parent and the derived context can coexist
 *   independently; siblings derived from the same parent are equal but distinct values.
 *
 * ## Branch table
 *
 * | Projection           | Runtime carries        | Branch                       | Output                            |
 * |----------------------|------------------------|------------------------------|-----------------------------------|
 * | `WorkingDirectory`   | `DirectoryValue(path)` | valid non-blank path         | `Derived(parent + Cwd(path))`     |
 * | `WorkingDirectory`   | `DirectoryValue("")`   | blank path                   | `Rejected(InvalidRuntimeValue)`   |
 * | `WorkingDirectory`   | `None` / `Environment` | missing or wrong-typed       | `Rejected(Missing/Invalid)`       |
 * | `Environment`        | `EnvironmentValue(map)`| any value                    | `Derived(parent + Environment)`   |
 * | `Environment`        | `None` / `Directory`   | missing or wrong-typed       | `Rejected(Missing/Invalid)`       |
 * | `Timestamps`         | anything (ignored)     | decorator-only projection    | `Derived(parent)`                 |
 * | `Deadline`           | anything (ignored)     | handled in `ShOptions`       | `Rejected(HandledOutsideOverlay)` |
 * | `CredentialLease`    | anything (ignored)     | handled by lease preamble    | `Rejected(HandledOutsideOverlay)` |
 */
fun deriveChildExecutionContext(
    parent: ExecutionContext,
    projection: BodyContextProjection,
    runtime: BodyRuntimeValue,
): BodyContextDerivation = when (projection) {
    is BodyContextProjection.WorkingDirectory -> when (val r = runtime) {
        is BodyRuntimeValue.DirectoryValue -> when {
            r.path.isBlank() -> BodyContextDerivation.Rejected(
                BodyContextRejection.InvalidRuntimeValue(projection, "path must not be blank"),
            )
            else -> BodyContextDerivation.Derived(
                parent.pushed(ContextOverlay.Cwd(r.path)),
            )
        }
        BodyRuntimeValue.None -> BodyContextDerivation.Rejected(
            BodyContextRejection.MissingRuntimeValue(projection),
        )
        is BodyRuntimeValue.EnvironmentValue -> BodyContextDerivation.Rejected(
            BodyContextRejection.InvalidRuntimeValue(
                projection,
                "expected DirectoryValue (path), got EnvironmentValue",
            ),
        )
    }
    is BodyContextProjection.Environment -> when (val r = runtime) {
        is BodyRuntimeValue.EnvironmentValue -> BodyContextDerivation.Derived(
            parent.pushed(ContextOverlay.Environment(EnvironmentSpec(r.values))),
        )
        BodyRuntimeValue.None -> BodyContextDerivation.Rejected(
            BodyContextRejection.MissingRuntimeValue(projection),
        )
        is BodyRuntimeValue.DirectoryValue -> BodyContextDerivation.Rejected(
            BodyContextRejection.InvalidRuntimeValue(
                projection,
                "expected EnvironmentValue (map), got DirectoryValue",
            ),
        )
    }
    is BodyContextProjection.Timestamps -> BodyContextDerivation.Derived(parent)
    is BodyContextProjection.Deadline -> BodyContextDerivation.Rejected(
        BodyContextRejection.HandledOutsideOverlay(projection, "ShOptions.timeoutMs"),
    )
    is BodyContextProjection.CredentialLease -> BodyContextDerivation.Rejected(
        BodyContextRejection.HandledOutsideOverlay(projection, "credential scope preamble"),
    )
}
