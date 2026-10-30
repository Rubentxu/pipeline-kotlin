package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.step.BODY_INVOKER_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.BodyInvocationContext
import dev.rubentxu.pipeline.v2.domain.step.BodyInvoker
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodyRef
import kotlinx.coroutines.runBlocking

/**
 * Canonical implementation of the [BodyInvoker] port
 * The engine adapter for the body-reentry seam. Block-step handlers registered with the
 * registry (or any future block-step author) declare [BODY_INVOKER_CAPABILITY] in their
 * `requiredCapabilities`. When the runtime binds that capability, the handler receives
 * this adapter and can re-enter the canonical body machinery by calling [invoke] with a
 * previously-issued [BodyRef].
 * ## Why this exists
 * Before the body execution machinery was extracted into [BodyExecutionEngine], the
 * coordinator dispatched children directly via the canonical shared loop. The [BodyInvoker] port was
 * declared but never bound to a real implementation: registry-driven block-step handlers
 * had no way to re-enter the engine. This adapter is the engine-side implementation:
 * - `open(bodyRef, runner)` is called by [BodyExecutionEngine] when it enters a body,
 *   registering a `BodyRef -> suspend () -> StepOutcome` mapping for the body's lifetime.
 * - `invoke(bodyRef, ctx)` is called by a registered handler when it wants to execute
 *   its body. The adapter looks up the runner, invokes it, and wraps the typed outcome
 *   in the closed [BodyOutcome] algebra.
 * ## Single-shared-path contract
 * The mapping from [BodyRef] to body-children is populated by the **same**
 * [BodyExecutionEngine] function that dispatches the legacy
 * executable body Steps. This adapter never iterates body children itself,
 * so the durable engine's body-child loop count remains exactly one.
 * ## Lifetime
 * The adapter is per-run. A fresh adapter starts with an empty `openBodies`. The
 * coordinator creates one per run and the body engine closes every body it
 * opened in a `finally` clause, so the adapter's map stays bounded by the depth of
 * nested bodies. The adapter is not a global service-locator and is not thread-safe
 * across runs.
*/
class CanonicalBodyInvokerAdapter : BodyInvoker {

    /**
     * BodyRef -> body runner for bodies currently open in this coordinator run.
     * Populated by [open] from [BodyExecutionEngine], drained by
     * [close]. The runner receives the [BodyInvocationContext] the caller hands to
     * [invoke], so the same BodyRef can re-execute the body under different attempt /
     * patch / decorator contexts (retry attempt, waitUntil poll, parallel branch) without
     * the engine having to open a new BodyRef per context. Per-attempt identity
     * reaches the runner through `context.attempt`, scope patches through `context.patch`,
     * and non-context decorations (timestamps) through `context.decorator`.
     * The runner signature evolves from
     * `suspend () -> StepOutcome` to `suspend (BodyInvocationContext) -> StepOutcome`.
     * The port [BodyInvoker] already received the context; the binding is now
     * contextually faithful to its public contract.
*/
    private val openBodies: MutableMap<BodyRef, suspend (BodyInvocationContext) -> StepOutcome> = mutableMapOf()

    /**
     * Registers [runner] under [bodyRef] for the lifetime of the body.
     * Called from [BodyExecutionEngine] when entering a body
     * scope. The [runner] closure receives the [BodyInvocationContext] supplied by the
     * engine / caller at invocation time, so a single BodyRef can re-execute the body
     * under different attempt/patch/decorator contexts. Duplicate registration of the same
     * [bodyRef] is a programmer defect (the engine's finally clause must have run)
     * and is silently replaced; the earlier entry's runner is leaked but is no longer
     * reachable through [invoke].
*/
    fun open(bodyRef: BodyRef, runner: suspend (BodyInvocationContext) -> StepOutcome) {
        openBodies[bodyRef] = runner
    }

    /**
     * Removes the body registered under [bodyRef].
     * Called from [BodyExecutionEngine] in its `finally`. The
     * body engine uses [open]/[close] as a structured bracket around the
     * shared body-child loop call, so the map never grows beyond the nesting depth.
*/
    fun close(bodyRef: BodyRef) {
        openBodies.remove(bodyRef)
    }

    /** Number of bodies currently registered. Exposed for diagnostics and tests. */
    val openBodyCount: Int get() = openBodies.size

    /**
     * Re-entry point for a registered handler.
     * - **Known bodyRef**: invokes the registered runner, passing [context] verbatim,
     *   and returns [BodyOutcome.Completed] with the runner's typed [StepOutcome]. The
     *   handler can fold the body result into its own logic exactly as it would a child
     *   outcome. Per-attempt identity, scope patches and decorators reach the runner
     *   through [BodyInvocationContext]; the binding is no longer context-decorative.
     * - **Unknown bodyRef**: returns [BodyOutcome.Completed] carrying a typed engine
     *   failure. The handler never blocks the dispatch and the typed algebra remains
     *   closed; an unknown bodyRef is never a structural reason to throw, it is a runtime
     *   decision the handler can fold.
     *
     * ## Why an absent body is a FAILURE and not a cancellation
     *
     * A cancellation says the run was stopped on purpose: a deadline expired, the parent
     * scope was cancelled, the scope shut down. None of those happened here — what happened is
     * that the handler named a body this run does not have open. The domain says so itself:
     * a cancellation is an execution mechanism and is never reclassified as a step failure,
     * and the symmetric half matters just as much, because an ABSENCE is not a mechanism
     * either. Calling it one made the handler read "the run was stopped" where the truth was
     * "the body you named does not exist", and the two demand different remediation.
     *
     * The engine adapter is where an unknown body encoding fails closed, and the vocabulary
     * is the one a missing SLOT already uses: a contained engine failure inside the
     * handler's own outcome.
*/
    override suspend fun invoke(body: BodyRef, context: BodyInvocationContext): BodyOutcome {
        val runner = openBodies[body]
            ?: return BodyOutcome.Completed(
                StepOutcome.Failure(
                    PipelineFailure(
                        kind = FailureKind.ENGINE,
                        message = "body ref '${body.encoded}' is not open in this run",
                    ),
                ),
            )
        return BodyOutcome.Completed(runner(context))
    }

    /**
     * Synchronous helper for tests and tooling that run inside a `runBlocking` block.
     * Delegates to [invoke]; the canonical path is `suspend invoke`.
*/
    fun invokeBlocking(body: BodyRef, context: BodyInvocationContext): BodyOutcome =
        runBlocking { invoke(body, context) }

    /** Diagnostic accessor for tests. Not a coordination primitive. */
    fun isOpen(bodyRef: BodyRef): Boolean = bodyRef in openBodies
}
