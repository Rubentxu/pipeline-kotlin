package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.step.BODY_INVOKER_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.BodyInvocationContext
import dev.rubentxu.pipeline.v2.domain.step.BodyInvoker
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodyRef
import dev.rubentxu.pipeline.v2.domain.step.CancellationReason
import kotlinx.coroutines.runBlocking

/**
 * Canonical implementation of the [BodyInvoker] port (ADR-0073 / ADR-0081 D1; B11).
 *
 * The engine adapter for the body-reentry seam. Block-step handlers registered with the
 * registry (or any future block-step author) declare [BODY_INVOKER_CAPABILITY] in their
 * `requiredCapabilities`. When the runtime binds that capability, the handler receives
 * this adapter and can re-enter the canonical body machinery by calling [invoke] with a
 * previously-issued [BodyRef].
 *
 * ## Why this exists
 *
 * Before B11 the body execution machinery ([CanonicalDurableRunCoordinator.dispatchBody])
 * dispatched children directly via the canonical shared loop. The [BodyInvoker] port was
 * declared but never bound to a real implementation: registry-driven block-step handlers
 * had no way to re-enter the engine. This adapter is the engine-side implementation:
 * - `open(bodyRef, runner)` is called by the canonical coordinator when it enters a body,
 *   registering a `BodyRef -> suspend () -> StepOutcome` mapping for the body's lifetime.
 * - `invoke(bodyRef, ctx)` is called by a registered handler when it wants to execute
 *   its body. The adapter looks up the runner, invokes it, and wraps the typed outcome
 *   in the closed [BodyOutcome] algebra.
 *
 * ## Single-shared-path contract (B10 / W1d, ADR-0073)
 *
 * The mapping from [BodyRef] to body-children is populated by the **same**
 * [CanonicalDurableRunCoordinator.dispatchBody] function that dispatches the legacy
 * executable body Steps. This adapter never iterates body children itself,
 * so the durable coordinator's body-child loop count remains exactly one
 * (enforced by `Lfc2ConcreteBodyRoutingDebtFitnessTest.BodyChildLoopScanner`).
 *
 * ## Lifetime
 *
 * The adapter is per-run. A fresh adapter starts with an empty `openBodies`. The
 * canonical coordinator creates one in its default constructor and closes every body it
 * opened in a `finally` clause, so the adapter's map stays bounded by the depth of
 * nested bodies. The adapter is not a global service-locator and is not thread-safe
 * across runs.
 */
class CanonicalBodyInvokerAdapter : BodyInvoker {

    /**
     * BodyRef -> body runner for bodies currently open in this coordinator run.
     *
     * Populated by [open] from [CanonicalDurableRunCoordinator.dispatchBody], drained by
     * [close]. The runner closure already freezes every parameter the existing
     * `invokeBodyChildren` requires, so [invoke] does not need to thread
     * `runId / stage / stepIndex` back into the engine.
     */
    private val openBodies: MutableMap<BodyRef, suspend () -> StepOutcome> = mutableMapOf()

    /**
     * Registers [runner] under [bodyRef] for the lifetime of the body.
     *
     * Called from [CanonicalDurableRunCoordinator.dispatchBody] when entering a body
     * scope. Duplicate registration of the same [bodyRef] is a programmer defect
     * (the coordinator's finally clause must have run) and is silently replaced; the
     * earlier entry's runner is leaked but is no longer reachable through [invoke].
     */
    fun open(bodyRef: BodyRef, runner: suspend () -> StepOutcome) {
        openBodies[bodyRef] = runner
    }

    /**
     * Removes the body registered under [bodyRef].
     *
     * Called from [CanonicalDurableRunCoordinator.dispatchBody] in its `finally`. The
     * canonical coordinator uses [open]/[close] as a structured bracket around the
     * shared `invokeBodyChildren` call, so the map never grows beyond the nesting depth.
     */
    fun close(bodyRef: BodyRef) {
        openBodies.remove(bodyRef)
    }

    /** Number of bodies currently registered. Exposed for diagnostics and tests. */
    val openBodyCount: Int get() = openBodies.size

    /**
     * Re-entry point for a registered handler.
     *
     * - **Known bodyRef**: invokes the registered runner and returns
     *   [BodyOutcome.Completed] with the runner's typed [StepOutcome]. The handler can
     *   fold the body result into its own logic exactly as it would a child outcome.
     * - **Unknown bodyRef**: returns [BodyOutcome.Cancelled] with
     *   [CancellationReason.ParentCancelled]. The handler never block the dispatch and
     *   the typed algebra remains closed; an unknown bodyRef is never a structural
     *   reason to throw, it is a runtime decision the handler can fold.
     */
    override suspend fun invoke(body: BodyRef, context: BodyInvocationContext): BodyOutcome {
        val runner = openBodies[body]
            ?: return BodyOutcome.Cancelled(CancellationReason.ParentCancelled)
        return BodyOutcome.Completed(runner())
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
