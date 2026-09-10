package dev.rubentxu.pipeline.v2.domain

/**
 * Immutable execution-context value owned by NOBODY and derived by EVERYONE.
 *
 * Replaces the coordinator's mutable `contextStack: ContextStack` (PAR-D/CTX-P0
 * inventory): context is threaded as an explicit parameter and derived purely,
 * so `parent.pushed(overlay)` never mutates `parent` and no finally-restore
 * idiom can lose updates across coroutines.
 *
 * Ownership boundary (CTX-P contract): this value MUST NOT contain an
 * OperationJournal, EventSink, StepRegistry, CoroutineScope, process executor,
 * or any coordinator/service-locator reference. It is a value describing the
 * active structural overlays, nothing more. ShOptions (functional env/cwd)
 * intentionally remains a separate per-call value (CTX-P0 §1.3).
 *
 * Laws (HF0, CtxPExecutionContextTest):
 *  CTX-Parent   pushed/dropped preserve the receiver
 *  CTX-Siblings independent derivations from one parent never alias
 *  CTX-Nesting  returning from a child needs no restore operation
 *  CTX-CatchError deterministic pure query (EM-5/6 trailing-chain precedence)
 *  CTX-Replay   equal logical inputs -> equal value (data class equality)
 *
 * No coroutine/thread identity is ever part of this value.
 */
data class ExecutionContext(
    val overlays: List<ContextOverlay> = emptyList(),
) {
    /**
     * Pure derivation: returns a NEW context with [overlay] as the top frame.
     * The receiver is unchanged.
     */
    fun pushed(overlay: ContextOverlay): ExecutionContext =
        copy(overlays = overlays + overlay)

    /**
     * EM-5/6 catch-error fold-walk input: the maximal trailing chain of
     * [ContextOverlay.CatchErrorOverlay] frames, outermost-first.
     *
     * Exact current coordinator semantics preserved (walkCatchErrorChain):
     * scan from the top while frames are CatchErrorOverlay; stop at the first
     * non-catch frame. The caller folds buildResult FAILURE (continue
     * outward) / SUCCESS (suppress) / else (unstable) exactly as before.
     * No precedence change is permitted in CTX-P.
     */
    fun trailingCatchErrorChain(): List<ContextOverlay.CatchErrorOverlay> {
        val result = ArrayList<ContextOverlay.CatchErrorOverlay>()
        for (i in overlays.indices.reversed()) {
            val frame = overlays[i]
            if (frame !is ContextOverlay.CatchErrorOverlay) break
            result.add(frame)
        }
        result.reverse() // innermost-last -> outermost-first, matching walk order i-- outward
        return result
    }

    companion object {
        val EMPTY = ExecutionContext(emptyList())
    }
}
