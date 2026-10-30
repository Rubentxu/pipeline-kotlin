package dev.rubentxu.pipeline.v2.domain

/**
 * Typed outcome of a structural context transition
 * The coordinator's old mutable stack had two transitions: push on
 * CatchErrorEntered and pop on CatchErrorTriggered(emitted=true), with a
 * fail-closed underflow check. The immutable model preserves BOTH as pure
 * transitions over values; [Rejected] carries the invariant violation that
 * the old `IllegalStateException("Context stack underflow...")` defended.
 *
 * ## RETIRED — published, no producer, no consumer
 * The only transition that produced one of these was the catch-error scope's
 * exit, and that scope no longer pushes a frame: its contract travels in the
 * block payload and its outcome is decided inside the scope. Nothing in the
 * product produces a [ContextTransition] any more, and nothing consumes one.
 *
 * It is PUBLISHED ABI, so it cannot be deleted without a versioned break; it is
 * kept as a declaration of a shape the product no longer exercises. A reader who
 * finds it must not read it as "the context can still transition" — it cannot.
*/
sealed interface ContextTransition {
    /** The scope state machine advanced; carry the successor context. */
    data class Advanced(val context: ExecutionContext) : ContextTransition

    /** Structural invariant violated (e.g. exit without an active catch scope). */
    data class Rejected(val violation: ContextInvariantViolation) : ContextTransition
}

/**
 * Closed ADT of context structural invariant violations.
 *
 * ## RETIRED — published, no producer, no consumer
 * The underflow it named was the catch-error scope's exit; that scope no longer
 * pushes a frame, so the violation cannot occur. Published ABI: kept, but inert.
*/
enum class ContextInvariantViolation {
    /** CatchErrorTriggered(emitted=true) with no active CatchErrorOverlay on top. */
    CATCH_ERROR_UNDERFLOW,
}

/**
 * Immutable execution-context value owned by NOBODY and derived by EVERYONE.
 * Replaces the coordinator's mutable `contextStack: ContextStack`
 * inventory): context is threaded as an explicit parameter and derived purely,
 * so callers keep their own parent value and no finally-restore idiom can lose
 * updates across coroutines.
 * Two distinct scope propagation models share this value:
 *  A. Linearized structural scope (catchError Enter/body/Exit markers in IR):
 *     sequential state machine Context_n + Node_n -> Context_n+1, via
 *     [pushed] and [exitCatchError].
 *  B. Lexical recursive scope (dir/withEnv/withCredentials bodies): child =
 *     parent.pushed(overlay); the caller keeps parent; no restore exists.
 * Ownership boundary: this value MUST NOT contain an
 * OperationJournal, EventSink, StepRegistry, CoroutineScope, process executor,
 * or any coordinator/service-locator reference. ShOptions (functional env/cwd)
 * Intentionally remains a separate per-call value
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
     *
     * ## RETIRED — published, no caller
     * The scope that used to push the frame does not push it any more, so this
     * transition has no caller in the product. Published ABI: kept, but the
     * answer it gives is about a frame nobody pushes.
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
     * catch-error fold-walk input: the maximal trailing chain of
     * [ContextOverlay.CatchErrorOverlay] frames, INNERMOST-FIRST (the baseline
     * coordinator walk started at the top frame and moved outward on FAILURE).
     * The caller folds buildResult FAILURE (continue outward to the next
     * element) / SUCCESS (suppress) / else (unstable) exactly as before.
     * No precedence change is permitted in
     *
     * ## RETIRED — published, no caller
     * The fold-walk that read this chain was the stage continuation's decision
     * over the pushed frame. The scope's own fold decides the outcome now, from
     * inside the scope, so nothing asks for the chain. Published ABI: kept, but
     * inert — and a reader must NOT take its INNERMOST-FIRST order as the
     * product's current catch precedence, because the product no longer has one.
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

