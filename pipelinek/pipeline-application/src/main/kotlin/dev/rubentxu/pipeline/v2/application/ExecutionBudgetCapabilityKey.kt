package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.StepCapability

/**
 * Capability key under which the engine supplies the remaining execution budget of
 * the enclosing scope (RP6-A / WU-091).
 *
 * ## Why the lock needs it
 *
 * The block deadline is *projected* onto child execution as a budget — the body
 * engine writes `shOptions.copy(timeoutMs = effective)` — and the historical
 * enforcement mechanism is the child **shell watchdog**. A Step that does not run a
 * process therefore escapes that enforcement: `timeout(2) { lock("db") { ... } }`
 * left the lock waiting INDEFINITELY, acquired the resource the instant the holder
 * released it, and ran its body long after the deadline had expired. Measured, not
 * inferred — see `UatLockBlockDurableTest.WL-L6`.
 *
 * This capability is the typed seam that lets a *suspending* Step honour the same
 * budget `core.sh` honours through [SHELL_OPERATIONS_CAPABILITY]: the value is
 * already threaded, the Step simply had no legal way to read it (reaching
 * `CanonicalRuntimeContext` directly is forbidden).
 *
 * ## Semantics
 *
 * @property remainingMs budget in milliseconds for this invocation, or `null` when
 *   no scope budget applies (the wait is then unbounded, exactly as declared).
 *
 * Exposed UNCONDITIONALLY: "no budget" is a legitimate value, not an absent
 * capability, so admission of a Step that declares it is deterministic in every
 * wiring.
 */
val EXECUTION_BUDGET_CAPABILITY: StepCapability = StepCapability("executionBudget")

/** Value of [EXECUTION_BUDGET_CAPABILITY]: the remaining budget of this invocation. */
data class ExecutionBudget(val remainingMs: Long?) {
    /**
     * Intersects a declared lock wait with the enclosing budget, in one pure place.
     *
     * [waitMs] is the author's own `timeoutSeconds` translated to millis, or `null`
     * for an unbounded wait. The result is the tighter of the two; `null` stays
     * `null` only when NEITHER side bounds the wait.
     */
    fun bound(waitMs: Long?): Long? = when {
        waitMs == null -> remainingMs
        remainingMs == null -> waitMs
        else -> minOf(waitMs, remainingMs)
    }
}
