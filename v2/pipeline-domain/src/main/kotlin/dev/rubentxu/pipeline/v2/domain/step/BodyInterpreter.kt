package dev.rubentxu.pipeline.v2.domain.step

import dev.rubentxu.pipeline.v2.domain.credentials.CredentialBindingSpec
import java.nio.file.Path

/**
 * WU-LPR-021 — Pure interpretation of an already-decided body execution
 * projection.
 *
 * ## Why this exists
 *
 * The body execution decision lives in two layers:
 *
 *  1. **Decision** — `BlockStepNode.projectBodyExecution(policy, options)`
 *     produces a [BodyExecutionProjection] (pure: `(policy, options) -> ADT`).
 *  2. **Interpretation** — `BodyInterpreter.interpret(projection)` produces a
 *     [InterpretedBody] describing how the body machinery should run the body:
 *     the scope to project, the `ShOptions` patch to apply, whether the body is
 *     a credential lease (which has its own acquisition preamble), or whether
 *     the shape is unimplemented.
 *
 * Before WU-LPR-021, the equivalent of step 2 was inlined in
 * `CanonicalDurableRunCoordinator.dispatchBody` as a sequence of `when` arms
 * over the projection, mixed with event emission and `Files.createDirectories`
 * side effects. The interpretation logic itself was pure (mapping projection
 * to scope + `ShOptions` patch + context overlay), but it was not separable
 * from the effectful surrounding code.
 *
 * ## What this is NOT
 *
 *  - NOT an effectful executor. [BodyInterpreter] does not emit events, does
 *    not touch the filesystem, does not call any capability. The effectful
 *    layer (event emission, directory creation, credential acquisition)
 *    belongs to the coordinator and consumes [InterpretedBody] afterwards.
 *  - NOT a replacement for the projection step. The projection remains the
 *    authoritative decision; the interpreter only re-shapes it for the
 *    runner.
 *  - NOT a Step-name branch. [BodyInterpreter] never reads a `StepKey`, a
 *    plugin id, or any concrete step class. The closed ADT over the
 *    projection guarantees that.
 *
 * ## Relationship to the existing descriptor metadata
 *
 * Same authority as [BodyExecutionPolicy]: declared on the
 * [dev.rubentxu.pipeline.v2.domain.StepDescriptor] of the Step family,
 * resolved by a pure function over the open [StepRegistry], rejected
 * fail-closed when the declaration is unknown.
 *
 * ## Migration note (post WU-LPR-021)
 *
 * The current [BodyInterpreter] is a **seam candidate** with its own
 * parallel typed vocabulary ([BlockShellScopeDescriptor],
 * [BodyExecutionProjectionDescriptor]). The canonical coordinator still uses
 * its private `BlockShellScope` / `BodyExecutionProjection` types. Publishing
 * those types out of the coordinator (so the seam can directly consume them)
 * is a separate WU (WU-LPR-024, InvocationEngine seam). Until then, the two
 * type families coexist by construction: the seam is total and pure, and a
 * future adapter can map canonical-projection → descriptor before invoking
 * the interpreter.
 */
fun interface BodyInterpreter {
    /**
     * `(projection) -> InterpretedBody`. Pure, total, no effects.
     *
     * The projection may carry typed invalid input or an unimplemented shape;
     * both surface as typed variants of [InterpretedBody] (never as exceptions
     * on this path).
     */
    fun interpret(projection: BodyExecutionProjectionDescriptor): InterpretedBody
}

/**
 * Pure interpretation result for one body invocation.
 *
 * Every case carries ONLY the typed payload that the runner needs to consume
 * to apply the body's effects. No runtime effects (events, FS ops, capability
 * calls) are represented here.
 */
sealed interface InterpretedBody {

    /** Body runs once in declaration order in the caller's own context. */
    data object Sequential : InterpretedBody

    /**
     * Body runs once inside a single derived execution context. The runner
     * applies [scope] and [childShPatch] before any child `StepStarted`.
     */
    data class Scoped(
        val scope: BlockShellScopeDescriptor,
        val childShPatch: ShPatch,
        val contextOverlay: ContextOverlayDescriptor,
    ) : InterpretedBody

    /**
     * Body runs under a credential lease (acquire → environment overlay →
     * always close). Carries the decoded bindings so the payload is decoded
     * exactly once, in the projection step.
     */
    data class CredentialLeased(
        val bindings: List<CredentialBindingSpec>,
    ) : InterpretedBody

    /**
     * Decoded input was invalid: typed SCHEMA rejection. The runner MUST
     * NOT iterate children. Never a fallback to [Unimplemented].
     */
    data class InvalidInput(val detail: String) : InterpretedBody

    /**
     * Declared shape has no interpretation in this interpreter. Unreachable
     * by construction (policy resolution rejects a shape outside
     * [BodyExecutionSupport]); kept total so widening the support without
     * implementing the shape fails closed.
     */
    data class Unimplemented(val shape: BodyExecutionPolicyShape) : InterpretedBody
}

/**
 * Pure descriptor of the scope a body is executed under. Parallel to the
 * canonical coordinator's private `BlockShellScope` — see migration note on
 * [BodyInterpreter].
 */
sealed interface BlockShellScopeDescriptor {

    /** No scope — body runs in the caller's own context (Sequential default). */
    data object None : BlockShellScopeDescriptor

    /** Body runs inside a derived working directory. */
    data class Directory(
        val target: Path,
        val previous: Path,
    ) : BlockShellScopeDescriptor

    /** Body runs with the timestamps decorator around all child events. */
    data class Timestamps(val runId: String) : BlockShellScopeDescriptor

    /** Body runs with the environment overrides projected around children. */
    data class Env(
        val overrides: List<String>,
        val parentEnv: Map<String, String>,
    ) : BlockShellScopeDescriptor

    /**
     * B13/E-EM-11: body-deadline contract projected from `core.timeout`.
     * The deadline flows to child ShOptions as the per-invocation watchdog.
     */
    data class Timeout(val budgetMs: Long) : BlockShellScopeDescriptor

    /** Body is re-dispatched up to [maxAttempts] times with deterministic per-attempt identity. */
    data class Retry(val maxAttempts: Int) : BlockShellScopeDescriptor

    /** Body-polling contract: re-dispatched until condition succeeds or backoff exceeds max. */
    data class WaitUntil(
        val initialRecurrencePeriodMs: Long,
        val quiet: Boolean,
    ) : BlockShellScopeDescriptor
}

/**
 * Pure descriptor of the [dev.rubentxu.pipeline.v2.domain.ContextOverlay] the
 * runner should push onto its immutable context stack before iterating body
 * children. The overlay is a typed value, NOT an effect: the runner pushes it
 * onto the stack and pops it on exit.
 *
 * Kept separate from [InterpretedBody.Scoped.childShPatch] because the patch
 * is `ShOptions`-typed (consumed by `core.sh` handler capability) while the
 * overlay is `ContextOverlay`-typed (consumed by the canonical coordinator's
 * scope stack).
 */
sealed interface ContextOverlayDescriptor {

    /** No overlay — the body runs in the parent's own context. */
    data object None : ContextOverlayDescriptor

    /** Push a CWD overlay. */
    data class Cwd(val path: String) : ContextOverlayDescriptor

    /** Push an Environment overlay with the merged values. */
    data class Environment(val values: Map<String, String>) : ContextOverlayDescriptor

    /** Push a Deadline overlay. */
    data class Deadline(val timeoutMs: Long) : ContextOverlayDescriptor
}

/**
 * Pure patch describing the typed values the runner should overlay on the
 * inherited runtime options before running body children.
 *
 * The interpreter produces one of these for every [Scoped] body. No effect,
 * no event emission: the runner applies the patch atomically with the
 * corresponding `*Entered` event.
 *
 * The interpreter is intentionally agnostic to the concrete runtime options
 * type (`ShOptions` lives in `pipeline-step-sdk`). The runner is responsible
 * for materializing this patch onto its concrete options class — the seam
 * contract is "patch.applyToMaterializes(parent)" is the only place
 * `ShOptions` is touched, and the interpreter never imports that type.
 *
 * @property timeoutMs The tighter block deadline (null = inherit parent).
 * @property workingDirectory The projected cwd (null = inherit parent).
 * @property env Merged environment overrides on top of the parent env.
 */
data class ShPatch(
    val timeoutMs: Long? = null,
    val workingDirectory: Path? = null,
    val env: Map<String, String>? = null,
) {
    companion object {
        /** Identity patch: every field `null`, no fields overridden. */
        val NONE: ShPatch = ShPatch()
    }
}

/**
 * Pure descriptor of the canonical coordinator's `BodyExecutionProjection`.
 * Parallel type family — see migration note on [BodyInterpreter].
 */
sealed interface BodyExecutionProjectionDescriptor {

    /** The generic body machinery runs this body with [scope] projected around it. */
    data class Scope(val scope: BlockShellScopeDescriptor) : BodyExecutionProjectionDescriptor

    /** Body runs under a bound credential lease (acquire → env overlay → close). */
    data class CredentialLease(
        val bindings: List<CredentialBindingSpec>,
    ) : BodyExecutionProjectionDescriptor

    /** Decoded input is invalid: typed SCHEMA rejection, never a fallback. */
    data class InvalidInput(val detail: String) : BodyExecutionProjectionDescriptor

    /** Declared shape has no interpretation in this engine build. */
    data class Unimplemented(val shape: BodyExecutionPolicyShape) : BodyExecutionProjectionDescriptor
}

/**
 * WU-LPR-021 — Default implementation of [BodyInterpreter].
 *
 * Maps a [BodyExecutionProjectionDescriptor] to an [InterpretedBody]:
 *
 *  - [BodyExecutionProjectionDescriptor.Scope] with [BlockShellScopeDescriptor.None]
 *    → [InterpretedBody.Sequential].
 *  - [BodyExecutionProjectionDescriptor.Scope] with [BlockShellScopeDescriptor.Directory]
 *    → [InterpretedBody.Scoped] carrying the directory scope, a `ShPatch` that overrides
 *    `workingDirectory`, and a [ContextOverlayDescriptor.Cwd] overlay.
 *  - [BodyExecutionProjectionDescriptor.Scope] with [BlockShellScopeDescriptor.Env]
 *    → [InterpretedBody.Scoped] carrying the env scope, a `ShPatch` that overrides `env`,
 *    and a [ContextOverlayDescriptor.Environment] overlay.
 *  - [BodyExecutionProjectionDescriptor.Scope] with [BlockShellScopeDescriptor.Timeout]
 *    → [InterpretedBody.Scoped] carrying the timeout scope, a `ShPatch` that overrides
 *    `timeoutMs`, and a [ContextOverlayDescriptor.Deadline] overlay.
 *  - [BodyExecutionProjectionDescriptor.Scope] with [BlockShellScopeDescriptor.Retry] /
 *    [BlockShellScopeDescriptor.WaitUntil] / [BlockShellScopeDescriptor.Timestamps]
 *    → [InterpretedBody.Scoped] carrying the scope and an identity patch (no `ShOptions`
 *    override; the runner applies its own loop semantics).
 *  - [BodyExecutionProjectionDescriptor.CredentialLease] → [InterpretedBody.CredentialLeased]
 *    carrying the bindings verbatim (the runner's preamble handles acquisition).
 *  - [BodyExecutionProjectionDescriptor.InvalidInput] → [InterpretedBody.InvalidInput].
 *  - [BodyExecutionProjectionDescriptor.Unimplemented] → [InterpretedBody.Unimplemented].
 *
 * Total over the projection ADT — adding a new projection case forces this
 * `when` to be revisited (compile-time exhaustiveness check via the sealed
 * interface).
 */
object DefaultBodyInterpreter : BodyInterpreter {
    override fun interpret(projection: BodyExecutionProjectionDescriptor): InterpretedBody =
        when (projection) {
            is BodyExecutionProjectionDescriptor.Scope -> interpretScope(projection.scope)
            is BodyExecutionProjectionDescriptor.CredentialLease ->
                InterpretedBody.CredentialLeased(projection.bindings)
            is BodyExecutionProjectionDescriptor.InvalidInput ->
                InterpretedBody.InvalidInput(projection.detail)
            is BodyExecutionProjectionDescriptor.Unimplemented ->
                InterpretedBody.Unimplemented(projection.shape)
        }

    private fun interpretScope(scope: BlockShellScopeDescriptor): InterpretedBody = when (scope) {
        BlockShellScopeDescriptor.None -> InterpretedBody.Sequential
        is BlockShellScopeDescriptor.Directory -> InterpretedBody.Scoped(
            scope = scope,
            childShPatch = ShPatch(workingDirectory = scope.target),
            contextOverlay = ContextOverlayDescriptor.Cwd(scope.target.toString()),
        )
        is BlockShellScopeDescriptor.Env -> {
            val parsed = scope.overrides.mapNotNull { override ->
                val parts = override.split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }.toMap()
            val merged = scope.parentEnv + parsed
            InterpretedBody.Scoped(
                scope = scope,
                childShPatch = ShPatch(env = merged),
                contextOverlay = ContextOverlayDescriptor.Environment(merged),
            )
        }
        is BlockShellScopeDescriptor.Timeout -> InterpretedBody.Scoped(
            scope = scope,
            childShPatch = ShPatch(timeoutMs = scope.budgetMs),
            contextOverlay = ContextOverlayDescriptor.Deadline(scope.budgetMs),
        )
        is BlockShellScopeDescriptor.Retry -> InterpretedBody.Scoped(
            scope = scope,
            childShPatch = ShPatch.NONE,
            contextOverlay = ContextOverlayDescriptor.None,
        )
        is BlockShellScopeDescriptor.WaitUntil -> InterpretedBody.Scoped(
            scope = scope,
            childShPatch = ShPatch.NONE,
            contextOverlay = ContextOverlayDescriptor.None,
        )
        is BlockShellScopeDescriptor.Timestamps -> InterpretedBody.Scoped(
            scope = scope,
            childShPatch = ShPatch.NONE,
            contextOverlay = ContextOverlayDescriptor.None,
        )
    }
}
