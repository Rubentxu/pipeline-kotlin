package dev.rubentxu.pipeline.v2.domain.step

import dev.rubentxu.pipeline.v2.domain.BodyInvocationPolicy
import dev.rubentxu.pipeline.v2.domain.ContextKind
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepBody
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import kotlinx.serialization.Serializable

/**
 * Static, pre-decode declaration of HOW the body of a block Step is executed
 * (B10 / W1b; concretizes ADR-0081 on the execution side).
 *
 * ## Why this exists
 *
 * The engine must decide the execution shape of a body (run it once in sequence,
 * project a scope around it, retry it, fan it out) BEFORE typed input decode, and
 * it must do so WITHOUT branching on a concrete StepKey. Today that decision is
 * taken by name (`when (pluginStepId.value) { "core.dir" -> ... }`), which is the
 * `B10` concreteness debt pinned by `Lfc2ConcreteBodyRoutingDebtFitnessTest`.
 *
 * The policy is the typed replacement: it is DECLARED on the
 * [StepDescriptor] of the Step family (criterion 2), RESOLVED by a pure function
 * over the open [StepRegistry] (criterion 3), and REJECTED fail-closed when the
 * declaration is unknown, incoherent, or not executable by the engine (criterion 4).
 *
 * ## What it is NOT
 *
 * - NOT runtime data. `dir`'s path, `withEnv`'s overrides, `timeout`'s seconds and
 *   `retry`'s `maxAttempts` are decoded typed input, never part of the policy.
 *   The policy is a total function of the Step KIND, identical on every run.
 * - NOT a replacement for [BodyInvocationContext] / [ExecutionContextPatch], which
 *   carry the runtime VALUES projected around one invocation.
 * - NOT a step-name carrier. `BodyExecutionPolicy` names shapes of execution; a
 *   Step family declares which shape it has. The engine reads the shape, not the key.
 *
 * ## Relationship to the existing descriptor metadata
 *
 * [dev.rubentxu.pipeline.v2.domain.StepBody.Declared.invocation] (cardinality) and
 * [dev.rubentxu.pipeline.v2.domain.StepBody.Declared.introduces] (context-kind hint) CANNOT
 * derive the execution shape: `core.timeout` and
 * `core.catchError` both declare [ContextKind.CANCELLATION] yet require different
 * shapes (`Scoped(Deadline)` vs `Sequential`). The policy is therefore declared
 * explicitly and checked for COHERENCE against those hints
 * ([BodyPolicyRejection.IncoherentMetadata]) rather than derived from them.
 */
@Serializable
sealed interface BodyExecutionPolicy {

    /**
     * The body runs once, in declaration order, in the caller's own execution
     * context. No projection, no repetition, no fan-out.
     *
     * Examples: `core.catchError`, `core.warnError`.
     */
    data object Sequential : BodyExecutionPolicy

    /**
     * The body runs once inside a single derived execution context projected from
     * the Step's typed input (a working directory, an environment overlay, a
     * deadline, a credential lease, a timestamp source).
     *
     * The projection is pure derivation: the parent context is never mutated
     * (CTX-P law). The runtime VALUE of the projection arrives through the typed
     * input and reaches the engine as an [ExecutionContextPatch].
     */
    data class Scoped(val projection: BodyContextProjection) : BodyExecutionPolicy

    /**
     * The body is re-dispatched until its typed outcome is no longer a retryable
     * failure or the decoded attempt budget is exhausted.
     *
     * Each attempt carries a deterministic per-attempt identity so restart and
     * replay reconstruct attempt state from durable control rows and the journal,
     * never from memory (ADR-0075, ADR-0081 D9).
     */
    data class Retrying(val policy: RetryPolicy) : BodyExecutionPolicy

    /**
     * The body's branches are dispatched concurrently and their typed outcomes
     * folded by the declared branch policy. Branches are Named Bodies, not a
     * permanent stage-terminal (ADR-0073).
     */
    data class Parallel(val policy: ParallelPolicy) : BodyExecutionPolicy

    /**
     * The closed family this policy belongs to, used for engine capability
     * admission ([BodyExecutionSupport]) without naming any Step.
     */
    val shape: BodyExecutionPolicyShape
        get() = when (this) {
            is Sequential -> BodyExecutionPolicyShape.SEQUENTIAL
            is Scoped -> BodyExecutionPolicyShape.SCOPED
            is Retrying -> BodyExecutionPolicyShape.RETRYING
            is Parallel -> BodyExecutionPolicyShape.PARALLEL
        }

    companion object {
        /** The body-less/sequential default: a Step that does not reshape execution. */
        val DEFAULT: BodyExecutionPolicy = Sequential
    }
}

/**
 * Closed family of [BodyExecutionPolicy]. Exhaustive over the ADT by construction:
 * adding a policy case forces every `when` over this enum and over
 * [BodyExecutionPolicy] to be revisited.
 */
enum class BodyExecutionPolicyShape {
    SEQUENTIAL,
    SCOPED,
    RETRYING,
    PARALLEL,
}

/**
 * Which engine executes a Step's body (B10 / W1c).
 *
 * ## Why this is a declaration and not a shape
 *
 * [BodyExecutionPolicy] says WHAT shape the body has. It deliberately cannot say
 * WHO executes it, and the two do not agree today:
 *
 * ```text
 * core.catchError   Sequential  -> legacy linear workflow-control rewrite
 * core.dir          Scoped(CWD) -> canonical durable body engine
 * core.withEnv      Scoped(ENV) -> canonical durable body engine
 * ```

 * `Sequential` is not the discriminator, because the canonical engine can run a
 * plain sequential body too. What differs is that `catchError` / `warnError`
 * semantics (containment of a failing body, output decoration) live in the legacy
 * workflow-control rewrite and their nodes are refused by the canonical
 * eligibility gate. Claiming `Scoped`/`Retrying` ownership for them, or inferring
 * ownership from the shape, would silently route them to an engine that does not
 * implement their semantics.
 *
 * This enum is therefore the declared migration frontier of body execution: the
 * canonical eligible body set is derived from it (never from a hard-coded list of
 * StepKeys), and `LEGACY_LINEAR` counts what is left to migrate.
 *
 * ## Relationship to the decode/dispatch family
 *
 * Different axis, deliberately not merged. `StructuralStepFamily` (application
 * layer) classifies DECODE/DISPATCH routing: registry Step vs legacy decode +
 * dispatch. This classifies BODY EXECUTION ownership. A Step can be a legacy
 * decoded Step whose body is owned by the canonical body engine (`core.dir`), so
 * neither can be derived from the other without a false equivalence.
 */
enum class BodyExecutionOwner {

    /**
     * The body is re-entered and executed by the canonical durable body engine:
     * scope projection, attempt re-dispatch, and typed event emission all live
     * there. It is the target state for every body, and since W1d it is also
     * REQUIRED to be stated: a body row that omits its owner does not compile, so
     * no Step acquires canonical semantics by defaulting.
     */
    CANONICAL_ENGINE,

    /**
     * The body's semantics are still implemented by the legacy linear
     * workflow-control rewrite. A Step declaring this is NOT eligible for the
     * canonical durable runner and must be rejected there before any effect,
     * never executed as an empty shell.
     */
    LEGACY_LINEAR,
}

/**
 * Which single context dimension a [BodyExecutionPolicy.Scoped] body projects.
 *
 * Closed ADT, 1:1 with the scope kinds the engine can derive. It names the
 * DIMENSION, never the Step: no case carries a StepKey.
 */
@Serializable
sealed interface BodyContextProjection {

    /** Runs the body with the working directory projected from decoded input (`dir`). */
    data object WorkingDirectory : BodyContextProjection

    /** Runs the body with an environment overlay projected from decoded input (`withEnv`). */
    data object Environment : BodyContextProjection

    /** Runs the body under a relative-timestamp source (`timestamps`). */
    data object Timestamps : BodyContextProjection

    /** Runs the body under a deadline projected from decoded input (`timeout`). */
    data object Deadline : BodyContextProjection

    /** Runs the body with a bound credential lease (`withCredentials`). */
    data object CredentialLease : BodyContextProjection

    /**
     * The [ContextKind] a descriptor MUST declare to host this projection, or
     * `null` when the dimension has no corresponding declared context kind.
     *
     * [Timestamps] has none: the existing [ContextKind] family enumerates
     * ENVIRONMENT / CWD / CREDENTIALS / OUTPUT_DECORATOR / CANCELLATION, and a
     * timestamp source is none of those. The `null` is a real "no corresponding
     * kind" fact of the closed [ContextKind] family, not a sentinel.
     */
    val requiredContextKind: ContextKind?
        get() = when (this) {
            is WorkingDirectory -> ContextKind.CWD
            is Environment -> ContextKind.ENVIRONMENT
            is CredentialLease -> ContextKind.CREDENTIALS
            is Deadline -> ContextKind.CANCELLATION
            is Timestamps -> null
        }
}

/**
 * Structural retry parameters that are a property of the Step KIND (not of one
 * invocation): the key from which each attempt's deterministic body-path segment
 * is derived. Attempt COUNT is decoded typed input and is deliberately absent.
 */
@Serializable
data class RetryPolicy(
    /** Segment key for per-attempt identity; mirrors [AttemptSegment]'s default. */
    val attemptKey: PluginStepId = PluginStepId("retry-attempt"),
)

/**
 * Structural parallel parameters that are a property of the Step KIND: the key
 * from which each branch's deterministic body-path segment is derived. Branch
 * COUNT and failure mode are decoded typed input and are deliberately absent.
 */
@Serializable
data class ParallelPolicy(
    /** Segment key for per-branch identity; mirrors [BodyRefs.branchBody]'s usage. */
    val branchKey: PluginStepId = PluginStepId("parallel-branch"),
)

/**
 * The execution shapes an engine build can actually execute.
 *
 * Admission is over the CLOSED [BodyExecutionPolicyShape] family, never over a
 * StepKey: an engine that supports `SCOPED` supports every Step declaring a
 * `Scoped` policy, core or external, with no privileged path. A Step whose
 * declared shape is outside this set is rejected before any effect
 * ([BodyPolicyRejection.UnsupportedByEngine]).
 */
data class BodyExecutionSupport(val shapes: Set<BodyExecutionPolicyShape>) {

    fun supports(policy: BodyExecutionPolicy): Boolean = policy.shape in shapes

    companion object {
        /** No body execution supported: every body-bearing Step is rejected. */
        val NONE: BodyExecutionSupport = BodyExecutionSupport(emptySet())

        /**
         * The W1b engine support: the coordinator still executes bodies through its
         * concrete scope switch, so only `Sequential` bodies are policy-addressable.
         * W1c widens this as it routes scoped/retrying/parallel bodies behind the policy.
         */
        val SEQUENTIAL_ONLY: BodyExecutionSupport =
            BodyExecutionSupport(setOf(BodyExecutionPolicyShape.SEQUENTIAL))

        /**
         * The W1c engine support: the coordinator projects scoped bodies, re-dispatches
         * retrying bodies and runs plain sequential bodies, and it resolves all three
         * from the declared policy instead of from a StepKey switch.
         *
         * `PARALLEL` is deliberately absent: branch fan-out is still executed as a plain
         * sequence, so a Step declaring `Parallel` is rejected fail-closed rather than
         * silently run sequentially (B13 owns the fan-out).
         */
        val SCOPED_SEQUENTIAL_RETRYING: BodyExecutionSupport =
            BodyExecutionSupport(
                setOf(
                    BodyExecutionPolicyShape.SEQUENTIAL,
                    BodyExecutionPolicyShape.SCOPED,
                    BodyExecutionPolicyShape.RETRYING,
                ),
            )

        /** Every shape the closed family can express (representability proofs). */
        val FULL: BodyExecutionSupport =
            BodyExecutionSupport(BodyExecutionPolicyShape.entries.toSet())
    }
}

/**
 * Why a declared body execution policy cannot be admitted.
 *
 * Closed ADT with distinct semantics: an unknown Step, an incoherent declaration
 * (a programmer defect in the descriptor), and an engine that cannot execute the
 * declared shape (a build capability gap) are three different situations with
 * three different remedies, and are never collapsed into a boolean or a string.
 */
sealed interface BodyPolicyRejection {

    /** No descriptor is registered for [key]: the Step is unknown, fail closed. */
    data class UnknownStep(val key: PluginStepId) : BodyPolicyRejection

    /**
     * [key] is a terminal Step ([StepBody.None]): it has no body, so it has no body
     * execution policy to resolve. Asking for one is a caller error, reported instead of
     * answered with a default.
     *
     * Since W1d the contradictory shape this case used to describe — "declares a reshaping
     * policy while taking no body" — is no longer constructible: a terminal Step has
     * nowhere to declare a policy. The case stays because the QUESTION remains answerable
     * and must stay answerable fail-closed.
     */
    data class NotABodyStep(val key: PluginStepId) : BodyPolicyRejection

    /**
     * The declared policy contradicts other descriptor metadata: the execution
     * shape cannot hold bodies [count] times, or a projected scope requires a
     * context kind the descriptor does not declare. Fail closed rather than
     * silently honouring one of two contradictory declarations.
     */
    data class IncoherentMetadata(
        val key: PluginStepId,
        val declared: BodyExecutionPolicy,
        val detail: String,
    ) : BodyPolicyRejection

    /**
     * The declaration is well formed, but this engine build cannot execute the
     * shape. A capability gap in the engine, not a defect in the Step.
     */
    data class UnsupportedByEngine(
        val key: PluginStepId,
        val declared: BodyExecutionPolicy,
        val support: BodyExecutionSupport,
    ) : BodyPolicyRejection
}

/**
 * Typed outcome of policy resolution — the `Either<BodyPolicyError, BodyExecutionPolicy>`
 * of the resolver, expressed as the project's closed result algebra.
 *
 * Never a boolean coupled to a nullable policy: a resolved policy carries the
 * policy, a rejection carries the typed reason.
 */
sealed interface BodyPolicyResolution {

    data class Resolved(val key: PluginStepId, val policy: BodyExecutionPolicy) : BodyPolicyResolution

    data class Rejected(val key: PluginStepId, val reason: BodyPolicyRejection) : BodyPolicyResolution

    /** The policy when resolved, or `null`. For ergonomic call sites, not for control flow. */
    val policyOrNull: BodyExecutionPolicy? get() = (this as? Resolved)?.policy

    /** The rejection when rejected, or `null`. For ergonomic call sites, not for control flow. */
    val rejectionOrNull: BodyPolicyRejection? get() = (this as? Rejected)?.reason
}

/**
 * Pure resolution of a Step family's declared [BodyExecutionPolicy].
 *
 * `(StepDefinition, BodyExecutionSupport) -> BodyPolicyResolution`: no I/O, no
 * clock, no registry mutation, no exception for expected operational outcomes.
 * The engine interprets the returned value; it does not decide by Step name.
 */
fun interface BodyPolicyResolver {

    /**
     * Resolves the policy for [key].
     *
     * Implementations MUST fail closed: an unregistered key, an incoherent
     * declaration, or a shape this engine cannot execute all return a
     * [BodyPolicyResolution.Rejected], never a permissive default.
     */
    fun resolve(key: PluginStepId): BodyPolicyResolution
}

/**
 * The canonical pure resolution function.
 *
 * [descriptor] is `null` when the Step is unknown, which is a rejection and never
 * a default. Everything else is a total function of the declaration plus the
 * engine's declared support.
 */
fun resolveBodyExecutionPolicy(
    key: PluginStepId,
    descriptor: StepDescriptor?,
    support: BodyExecutionSupport,
): BodyPolicyResolution {
    if (descriptor == null) {
        return BodyPolicyResolution.Rejected(key, BodyPolicyRejection.UnknownStep(key))
    }

    // W1d: the declaration is one value. A terminal Step has no policy to resolve, so the
    // only fail-closed answer is a typed rejection; there is no "sequential default" for a
    // Step with no body, and no contradictory declaration left to detect here.
    val declaredBody = descriptor.body.declared
        ?: return BodyPolicyResolution.Rejected(key, BodyPolicyRejection.NotABodyStep(key))
    val declared = declaredBody.execution.policy

    incoherenceOf(key, declaredBody, declared)?.let { detail ->
        return BodyPolicyResolution.Rejected(
            key,
            BodyPolicyRejection.IncoherentMetadata(key, declared, detail),
        )
    }

    if (!support.supports(declared)) {
        return BodyPolicyResolution.Rejected(
            key,
            BodyPolicyRejection.UnsupportedByEngine(key, declared, support),
        )
    }

    return BodyPolicyResolution.Resolved(key, declared)
}

/**
 * Resolves from a registered [StepDefinition], reading the policy off its contract
 * descriptor. This is the port the engine uses: the authority is the open
 * [StepRegistry], so a core Step and an external plugin Step resolve identically.
 */
fun resolveBodyExecutionPolicy(
    definition: StepDefinition<*, *>,
    support: BodyExecutionSupport,
): BodyPolicyResolution =
    resolveBodyExecutionPolicy(definition.contract.key, definition.contract.descriptor, support)

/**
 * Resolves from a [StepDescriptor] alone. Used where a descriptor is available
 * without a registered handler (e.g. static validation, representability checks).
 * Same laws, same fail-closed behaviour.
 */
fun resolveBodyExecutionPolicy(
    descriptor: StepDescriptor,
    support: BodyExecutionSupport,
): BodyPolicyResolution =
    resolveBodyExecutionPolicy(PluginStepId(descriptor.stepId), descriptor, support)

/**
 * [BodyPolicyResolver] over the open [StepRegistry], the only policy authority.
 *
 * Lookup is by structural StepKey against the registry — the SAME seam that
 * resolves handlers — so there is exactly one registration authority and no
 * parallel step-name table. A key that is registered but whose declaration is
 * incoherent or unsupported is rejected exactly as an unknown key is.
 */
class RegistryBodyPolicyResolver(
    private val registry: StepRegistry,
    private val support: BodyExecutionSupport,
) : BodyPolicyResolver {

    override fun resolve(key: PluginStepId): BodyPolicyResolution =
        resolveBodyExecutionPolicy(
            key = key,
            descriptor = registry.definition(key)?.contract?.descriptor,
            support = support,
        )
}

/**
 * Coherence of a declared policy against the descriptor metadata that predates it.
 *
 * Returns a human-facing detail when the declaration contradicts
 * [StepBody.Declared.invocation] or [StepBody.Declared.introduces], or `null`
 * when coherent. Declared checks, in order:
 *
 *  1. Only a repeating shape ([BodyExecutionPolicy.Retrying]) may declare
 *     [BodyInvocationPolicy.ZERO_OR_MORE] cardinality.
 *  2. A [BodyExecutionPolicy.Retrying] declaration requires ZERO_OR_MORE.
 *  3. A [BodyExecutionPolicy.Scoped] declaration requires the descriptor to
 *     declare the matching [ContextKind].
 *
 * The converse of (3) is deliberately NOT checked: `CANCELLATION` is shared by
 * `core.timeout` (`Scoped(Deadline)`) and `core.catchError` (`Sequential`), so a
 * declared context kind does not determine the execution shape.
 */
private fun incoherenceOf(
    key: PluginStepId,
    body: StepBody.Declared,
    declared: BodyExecutionPolicy,
): String? {
    val invocations = body.invocation

    if (declared is BodyExecutionPolicy.Retrying && invocations != BodyInvocationPolicy.ZERO_OR_MORE) {
        return "declared Retrying but invocation=$invocations (requires ZERO_OR_MORE)"
    }
    if (invocations == BodyInvocationPolicy.ZERO_OR_MORE &&
        declared !is BodyExecutionPolicy.Retrying &&
        declared !is BodyExecutionPolicy.Parallel
    ) {
        return "declares ZERO_OR_MORE cardinality but execution shape $declared cannot repeat the body"
    }
    if (declared is BodyExecutionPolicy.Scoped) {
        val required = declared.projection.requiredContextKind ?: return null
        if (body.introduces != required) {
            return "declared Scoped(${declared.projection}) requires introduces=$required " +
                "but the body declares ${body.introduces}"
        }
    }
    return null
}
