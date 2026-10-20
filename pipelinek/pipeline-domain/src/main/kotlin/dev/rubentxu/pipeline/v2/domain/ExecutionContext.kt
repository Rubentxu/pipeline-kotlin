package dev.rubentxu.pipeline.v2.domain

/**
 * Typed outcome of a structural context transition (CTX-P2).
 *
 * The coordinator's old mutable stack had two transitions: push on
 * CatchErrorEntered and pop on CatchErrorTriggered(emitted=true), with a
 * fail-closed underflow check. The immutable model preserves BOTH as pure
 * transitions over values; [Rejected] carries the invariant violation that
 * the old `IllegalStateException("Context stack underflow...")` defended.
 */
sealed interface ContextTransition {
    /** The scope state machine advanced; carry the successor context. */
    data class Advanced(val context: ExecutionContext) : ContextTransition

    /** Structural invariant violated (e.g. exit without an active catch scope). */
    data class Rejected(val violation: ContextInvariantViolation) : ContextTransition
}

/** Closed ADT of context structural invariant violations. */
enum class ContextInvariantViolation {
    /** CatchErrorTriggered(emitted=true) with no active CatchErrorOverlay on top. */
    CATCH_ERROR_UNDERFLOW,
}

/**
 * Immutable execution-context value owned by NOBODY and derived by EVERYONE.
 *
 * Replaces the coordinator's mutable `contextStack: ContextStack` (PAR-D/CTX-P0
 * inventory): context is threaded as an explicit parameter and derived purely,
 * so callers keep their own parent value and no finally-restore idiom can lose
 * updates across coroutines.
 *
 * Two distinct scope propagation models share this value (CTX-P0 §D):
 *  A. Linearized structural scope (catchError Enter/body/Exit markers in IR):
 *     sequential state machine Context_n + Node_n -> Context_n+1, via
 *     [pushed] and [exitCatchError].
 *  B. Lexical recursive scope (dir/withEnv/withCredentials bodies): child =
 *     parent.pushed(overlay); the caller keeps parent; no restore exists.
 *
 * Ownership boundary (CTX-P contract): this value MUST NOT contain an
 * OperationJournal, EventSink, StepRegistry, CoroutineScope, process executor,
 * or any coordinator/service-locator reference. ShOptions (functional env/cwd)
 * intentionally remains a separate per-call value (CTX-P0 §1.3).
 *
 * No coroutine/thread identity is ever part of this value.
 */
data class ExecutionContext(
    val overlays: List<ContextOverlay> = emptyList(),
) {
    /**
     * Pure derivation (model B and Enter markers): returns a NEW context with
     * [overlay] as the top frame. The receiver is unchanged.
     */
    fun pushed(overlay: ContextOverlay): ExecutionContext =
        copy(overlays = overlays + overlay)

    /**
     * Structural exit transition for a linearized catchError scope (model A):
     * CatchErrorTriggered(emitted=true) closes the active scope iff the top
     * frame is a [ContextOverlay.CatchErrorOverlay]; otherwise the structural
     * invariant is violated and the caller MUST fail closed. No generic
     * pop/drop exists — this is the only frame-removing transition.
     */
    fun exitCatchError(): ContextTransition {
        val top = overlays.lastOrNull()
        return if (top is ContextOverlay.CatchErrorOverlay) {
            ContextTransition.Advanced(copy(overlays = overlays.dropLast(1)))
        } else {
            ContextTransition.Rejected(ContextInvariantViolation.CATCH_ERROR_UNDERFLOW)
        }
    }

    /**
     * EM-5/6 catch-error fold-walk input: the maximal trailing chain of
     * [ContextOverlay.CatchErrorOverlay] frames, INNERMOST-FIRST (the baseline
     * coordinator walk started at the top frame and moved outward on FAILURE).
     * The caller folds buildResult FAILURE (continue outward to the next
     * element) / SUCCESS (suppress) / else (unstable) exactly as before.
     * No precedence change is permitted in CTX-P.
     */
    fun trailingCatchErrorChain(): List<ContextOverlay.CatchErrorOverlay> {
        val result = ArrayList<ContextOverlay.CatchErrorOverlay>()
        for (i in overlays.indices.reversed()) {
            val frame = overlays[i]
            if (frame !is ContextOverlay.CatchErrorOverlay) break
            result.add(frame)
        }
        return result
    }

    companion object {
        val EMPTY = ExecutionContext(emptyList())
    }
}
