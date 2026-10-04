package dev.rubentxu.pipeline.v2.application.scripted

import dev.rubentxu.pipeline.v2.domain.ShellInvocationResult
import dev.rubentxu.pipeline.v2.domain.durable.InterruptionKind
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus

/**
 * S4-D4 — the ONE place that reads a scripted shell *value* and says what durable status it is.
 *
 * Before this, `JournaledScriptedOperationRuntime` and `DurableScriptedOperationReconciler` each
 * carried a byte-identical private copy. Two copies of a mapping is two opinions waiting to
 * diverge, and they already differed in one invisible way: one spelled `InterruptionKind` fully
 * qualified because it did not import it. That is the shape a copy takes just before someone
 * edits one of them.
 *
 * It is `internal` and top-level on purpose. A `private` member shadows a top-level function of
 * the same name in the same package, so a *private* copy re-added to either class would keep
 * compiling at every call site while the shared function went quietly unused. Top-level and
 * non-private means a second copy has to be written as a second declaration, which
 * `S4D4ScriptedStatusAuthorityTest` then counts. Visibility is what makes the duplication
 * detectable at all.
 *
 * ## This is not the canonical outcome authority, and must not be mistaken for it
 *
 * There is another `toOperationStatus` in this repository:
 * `durable.CanonicalStructuralDecisions.StepOutcome.toOperationStatus()`. It has the same name
 * and a different receiver, because it answers a different question:
 *
 * ```text
 * ShellInvocationResult -> OperationStatus   what the shell DID
 * StepOutcome           -> OperationStatus   what the STEP MEANT
 * ```
 *
 * The two must not be merged, and this file deliberately does not try. `ShellInvocationResult` has
 * no `Unstable` case at all, so a value can only ever be reported as SUCCEEDED, FAILED,
 * FAILED_TIMEOUT or ABORTED. Routing the scripted path through the `StepOutcome` authority would
 * therefore change what a scripted `Unstable` step records in the journal — from SUCCEEDED to
 * FAILED — which is a semantic decision this change does not make.
 *
 * That decision is still open, it is not this file's to make, and it is the reason the scripted
 * journal keeps reading the value for now. Two follow-ups name it precisely:
 *
 * - `JournaledScriptedOperationRuntime` receives a `ScriptedOperationResult` that already carries
 *   the `StepOutcome`, and writes the status from `.value` while holding `.outcome` in hand.
 * - `DurableScriptedOperationReconciler` has no outcome to consult at all: `classifyShellTerminal`
 *   projects a `DurableTaskTerminal` onto a value, and `DurableTaskTerminal` carries no `Unstable`
 *   either. The reattach path cannot express the distinction even if it wanted to.
 *
 * Collapsing the copies is safe today precisely because it changes nothing. Deciding what a
 * scripted `Unstable` records is a separate change, and it belongs to whoever owns the
 * `Unstable` contract — not to a duplication cleanup.
 */
internal fun ShellInvocationResult.toOperationStatus(): OperationStatus = when (this) {
    ShellInvocationResult.UnitValue,
    is ShellInvocationResult.Stdout,
    is ShellInvocationResult.Status,
    -> OperationStatus.SUCCEEDED

    is ShellInvocationResult.Failed -> OperationStatus.FAILED
    is ShellInvocationResult.Interrupted -> if (interruption.kind == InterruptionKind.TIMEOUT) {
        OperationStatus.FAILED_TIMEOUT
    } else {
        OperationStatus.ABORTED
    }
}
